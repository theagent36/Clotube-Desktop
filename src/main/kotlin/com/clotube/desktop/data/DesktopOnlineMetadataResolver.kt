package com.clotube.desktop.data

import com.clotube.desktop.model.DesktopMediaItem
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

data class BatchSeriesMetadata(
    val title: String,
    val synopsis: String,
    val genres: String,
    val releaseYear: String,
    val posterUrl: String,
    val episodesBySeasonAndNum: Map<Pair<Int, Int>, BatchEpisodeMetadata>
)

data class BatchEpisodeMetadata(
    val title: String,
    val synopsis: String,
    val durationMs: Long,
    val thumbnailUrl: String
)

data class MovieMetadata(
    val officialTitle: String,
    val posterUrl: String = "",
    val releaseYear: String = "",
    val genre: String = "Drama",
    val language: String = "English",
    val imdbId: String = ""
)

data class MovieCastMember(
    val id: String = "",
    val name: String = "",
    val character: String = "",
    val photoUrl: String = ""
)

data class MovieRichDetails(
    val imdbId: String = "",
    val officialTitle: String = "",
    val releaseYear: String = "",
    val ratingValue: String = "",
    val ratingCount: String = "",
    val genres: String = "",
    val director: String = "",
    val plotSummary: String = "",
    val posterUrl: String = "",
    val cast: List<MovieCastMember> = emptyList()
)

data class SeriesOnlineMeta(
    val officialTitle: String,
    val tvMazeId: Long = -1L,
    val genres: String = "Series",
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val releaseYear: String = "",
    val ageRating: String = "",
    val tagline: String = "",
    val synopsis: String = "",
    val creators: String = "",
    val cast: String = ""
)

data class EpisodeOnlineMeta(
    val officialTitle: String,
    val durationMinutes: Long = 45L,
    val synopsis: String = "",
    val thumbnailUrl: String = "",
    val seasonNumber: Int = 1,
    val episodeNumber: Int = 1
) {
    val title: String get() = officialTitle
    val durationMs: Long get() = durationMinutes * 60_000L
}

typealias SeriesMetadata = SeriesOnlineMeta
typealias EpisodeMetadata = EpisodeOnlineMeta

object DesktopOnlineMetadataResolver {

    private val movieCache = ConcurrentHashMap<String, MovieMetadata>()
    private val seriesCache = ConcurrentHashMap<String, SeriesOnlineMeta>()
    private val episodeCache = ConcurrentHashMap<String, EpisodeOnlineMeta>()
    private val showEpisodesCache = ConcurrentHashMap<Long, Map<Pair<Int, Int>, EpisodeOnlineMeta>>()
    private val gson = Gson()

    // TMDB API Integration for Secondary Episode Stills & Movie Metadata
    // (Replace with your own TMDB v3 API Key if desired, or keep this standard key)
    private const val TMDB_API_KEY = "84148a68b492c79f33269f8323e0702e"
    private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w780"

    // -------------------------------------------------------------------------
    // PERSISTENT TMDB DISK CACHE
    // -------------------------------------------------------------------------
    private val tmdbCacheFile = File("cache", "tmdb_metadata_cache.json")
    private val tmdbGson = com.google.gson.GsonBuilder().setPrettyPrinting().create()

    private data class TmdbPersistentCache(
        val showIds: MutableMap<String, Long> = mutableMapOf(),
        val seasonStills: MutableMap<String, Map<Int, String>> = mutableMapOf()
    )

    private val persistentTmdbCache: TmdbPersistentCache = run {
        try {
            if (tmdbCacheFile.exists() && tmdbCacheFile.length() > 0) {
                val json = tmdbCacheFile.readText(Charsets.UTF_8)
                tmdbGson.fromJson(json, TmdbPersistentCache::class.java) ?: TmdbPersistentCache()
            } else {
                TmdbPersistentCache()
            }
        } catch (_: Exception) {
            TmdbPersistentCache()
        }
    }

    private val tmdbShowIdCache = ConcurrentHashMap<String, Long>(persistentTmdbCache.showIds)
    private val tmdbSeasonStillsCache = ConcurrentHashMap<Pair<Long, Int>, Map<Int, String>>().apply {
        persistentTmdbCache.seasonStills.forEach { (key, map) ->
            val parts = key.split(":")
            if (parts.size == 2) {
                val sId = parts[0].toLongOrNull()
                val sNum = parts[1].toIntOrNull()
                if (sId != null && sNum != null) {
                    put(Pair(sId, sNum), map)
                }
            }
        }
    }

    private fun persistTmdbCacheAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val export = TmdbPersistentCache(
                    showIds = HashMap(tmdbShowIdCache),
                    seasonStills = tmdbSeasonStillsCache.mapKeys { "${it.key.first}:${it.key.second}" }.toMutableMap()
                )
                if (!tmdbCacheFile.parentFile.exists()) {
                    tmdbCacheFile.parentFile.mkdirs()
                }
                tmdbCacheFile.writeText(tmdbGson.toJson(export), Charsets.UTF_8)
            } catch (_: Exception) {}
        }
    }

    // TMDB Movie Genre ID Map
    private val TMDB_GENRE_MAP = mapOf(
        28 to "Action",
        12 to "Adventure",
        16 to "Animation",
        35 to "Comedy",
        80 to "Crime",
        99 to "Documentary",
        18 to "Drama",
        10751 to "Family",
        14 to "Fantasy",
        36 to "History",
        27 to "Horror",
        10402 to "Music",
        9648 to "Mystery",
        10749 to "Romance",
        878 to "Sci-Fi",
        10770 to "TV Movie",
        53 to "Thriller",
        10752 to "War",
        37 to "Western"
    )

    /**
     * Resolves the entire TV series catalog and all episode metadata in a SINGLE HTTP query (~300ms)
     * instead of firing 150+ sequential HTTP requests.
     */
    fun resolveSeriesBatch(seriesTitle: String): BatchSeriesMetadata {
        val sanitized = seriesTitle
            .replace(Regex("(?i)\\b(season|s[0-9]+|complete|1080p|720p|hevc|x264|x265)\\b.*"), "")
            .trim()
        val encoded = URLEncoder.encode(sanitized, "UTF-8")
        val endpoint = "https://api.tvmaze.com/singlesearch/shows?q=$encoded&embed=episodes"

        try {
            val url = URI(endpoint).toURL()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "Clotube-Windows/1.0")
            }

            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val root = gson.fromJson(json, JsonObject::class.java)

                val officialTitle = root.get("name")?.asString ?: seriesTitle
                val rawSummary = root.get("summary")?.asString ?: ""
                val cleanSummary = rawSummary.replace(Regex("<.*?>"), "").trim()

                val genresArray = root.getAsJsonArray("genres")
                val genres = genresArray?.mapNotNull { it.asString }?.joinToString(", ") ?: ""

                val premiered = root.get("premiered")?.asString ?: ""
                val releaseYear = premiered.take(4)

                val imageElement = root.get("image")
                val imageObj = if (imageElement != null && !imageElement.isJsonNull && imageElement.isJsonObject) {
                    imageElement.asJsonObject
                } else null
                var posterUrl = imageObj?.get("original")?.takeIf { !it.isJsonNull }?.asString
                    ?: imageObj?.get("medium")?.takeIf { !it.isJsonNull }?.asString
                    ?: ""
                if (posterUrl.startsWith("http://")) {
                    posterUrl = posterUrl.replace("http://", "https://")
                }

                val episodeMap = mutableMapOf<Pair<Int, Int>, BatchEpisodeMetadata>()
                val embeddedElement = root.get("_embedded")
                val embedded = if (embeddedElement != null && !embeddedElement.isJsonNull && embeddedElement.isJsonObject) {
                    embeddedElement.asJsonObject
                } else null
                val episodesArray = embedded?.getAsJsonArray("episodes")

                episodesArray?.forEach { epElem ->
                    if (epElem.isJsonObject) {
                        val epObj = epElem.asJsonObject
                        val season = epObj.get("season")?.takeIf { !it.isJsonNull }?.asInt ?: 1
                        val number = epObj.get("number")?.takeIf { !it.isJsonNull }?.asInt ?: 1
                        val epName = epObj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "Episode $number"
                        val epSummary = (epObj.get("summary")?.takeIf { !it.isJsonNull }?.asString ?: "").replace(Regex("<.*?>"), "").trim()
                        val runtimeMin = epObj.get("runtime")?.takeIf { !it.isJsonNull }?.asLong ?: 45L
                        
                        // Safe JsonNull guard: prevents ClassCastException when TVMaze has no episode still
                        val epImageElement = epObj.get("image")
                        val epImageObj = if (epImageElement != null && !epImageElement.isJsonNull && epImageElement.isJsonObject) {
                            epImageElement.asJsonObject
                        } else null
                        var epThumb = epImageObj?.get("original")?.takeIf { !it.isJsonNull }?.asString
                            ?: epImageObj?.get("medium")?.takeIf { !it.isJsonNull }?.asString
                            ?: ""
                        if (epThumb.startsWith("http://")) {
                            epThumb = epThumb.replace("http://", "https://")
                        }

                        episodeMap[Pair(season, number)] = BatchEpisodeMetadata(
                            title = epName,
                            synopsis = epSummary,
                            durationMs = runtimeMin * 60_000L,
                            thumbnailUrl = epThumb
                        )
                    }
                }

                return BatchSeriesMetadata(
                    title = officialTitle,
                    synopsis = cleanSummary,
                    genres = genres,
                    releaseYear = releaseYear,
                    posterUrl = posterUrl,
                    episodesBySeasonAndNum = episodeMap
                )
            }
        } catch (e: Exception) {
            System.err.println("[MetadataResolver] Batch lookup error for $seriesTitle: ${e.message}")
        }

        return BatchSeriesMetadata(
            title = seriesTitle,
            synopsis = "",
            genres = "Series",
            releaseYear = "",
            posterUrl = "",
            episodesBySeasonAndNum = emptyMap()
        )
    }

    fun parseSeasonNumber(name: String, fallback: Int = 1): Int {
        val match = Regex("(?i)(?:season|s)\\s*([0-9]{1,2})").find(name)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: fallback
    }

    fun parseEpisodeNumber(name: String, fallback: Int = 1): Int {
        val sXe = Regex("(?i)(?:s[0-9]{1,2}[. _-]*e|ep|episode|[0-9]{1,2}x)([0-9]{1,3})").find(name)
        if (sXe != null) return sXe.groupValues[1].toIntOrNull() ?: fallback
        val numMatch = Regex("(?<![0-9])([0-9]{1,3})(?![0-9])").find(name)
        return numMatch?.groupValues?.get(1)?.toIntOrNull() ?: fallback
    }

    // Curated title, genre, and language registry (posters are fetched dynamically from live APIs)
    private val CURATED_TITLES = mapOf(
        "dune" to MovieMetadata(
            officialTitle = "Dune: Part One",
            posterUrl = "",
            releaseYear = "2021",
            genre = "Sci-Fi • Adventure • Action",
            language = "English"
        ),
        "dune part one" to MovieMetadata(
            officialTitle = "Dune: Part One",
            posterUrl = "",
            releaseYear = "2021",
            genre = "Sci-Fi • Adventure • Action",
            language = "English"
        ),
        "dune part 1" to MovieMetadata(
            officialTitle = "Dune: Part One",
            posterUrl = "",
            releaseYear = "2021",
            genre = "Sci-Fi • Adventure • Action",
            language = "English"
        ),
        "dune 2021" to MovieMetadata(
            officialTitle = "Dune: Part One",
            posterUrl = "",
            releaseYear = "2021",
            genre = "Sci-Fi • Adventure • Action",
            language = "English"
        ),
        "dune part two" to MovieMetadata(
            officialTitle = "Dune: Part Two",
            posterUrl = "",
            releaseYear = "2024",
            genre = "Sci-Fi • Adventure • Action",
            language = "English"
        ),
        "dune part 2" to MovieMetadata(
            officialTitle = "Dune: Part Two",
            posterUrl = "",
            releaseYear = "2024",
            genre = "Sci-Fi • Adventure • Action",
            language = "English"
        ),
        "top gun maverick" to MovieMetadata("Top Gun: Maverick", "", "2022", "Action • Drama", "English"),
        "top gun" to MovieMetadata("Top Gun: Maverick", "", "2022", "Action • Drama", "English"),
        "crime 101" to MovieMetadata("Crime 101", "", "2026", "Crime • Thriller", "English"),
        "12th fail" to MovieMetadata("12th Fail", "", "2023", "Biography • Drama", "Hindi"),
        "batman begins" to MovieMetadata("Batman Begins", "", "2005", "Action • Crime • Drama", "English"),
        "dhurandhar" to MovieMetadata("Dhurandhar", "", "2025", "Action • Thriller", "Hindi"),
        "dhurandhar the revenge" to MovieMetadata("Dhurandhar: The Revenge", "", "2025", "Action • Thriller", "Hindi"),
        "the vast of night" to MovieMetadata("The Vast of Night", "", "2019", "Sci-Fi • Mystery • Drama", "English"),
        "the endless" to MovieMetadata("The Endless", "", "2017", "Sci-Fi • Horror • Mystery", "English"),
        "jack reacher" to MovieMetadata("Jack Reacher", "", "2012", "Action • Mystery • Thriller", "English"),
        "glass onion" to MovieMetadata("Glass Onion: A Knives Out Mystery", "", "2022", "Mystery • Comedy • Crime", "English"),
        "6 underground" to MovieMetadata("6 Underground", "", "2019", "Action • Thriller", "English"),
        "deadpool and wolverine" to MovieMetadata("Deadpool & Wolverine", "", "2024", "Action • Comedy • Sci-Fi", "English"),
        "deadpool wolverine" to MovieMetadata("Deadpool & Wolverine", "", "2024", "Action • Comedy • Sci-Fi", "English"),
        "sanju" to MovieMetadata("Sanju", "", "2018", "Biography • Comedy • Drama", "Hindi"),
        "special 26" to MovieMetadata("Special 26", "", "2013", "Crime • Thriller • Drama", "Hindi"),
        "phantom" to MovieMetadata("Phantom", "", "2015", "Action • Thriller • Drama", "Hindi"),
        "agent vinod" to MovieMetadata("Agent Vinod", "", "2012", "Action • Thriller • Spy", "Hindi"),
        "atrangi re" to MovieMetadata("Atrangi Re", "", "2021", "Romance • Fantasy • Drama", "Hindi"),
        "dunki" to MovieMetadata("Dunki", "", "2023", "Comedy • Drama", "Hindi"),
        "half girlfriend" to MovieMetadata("Half Girlfriend", "", "2017", "Romance • Drama", "Hindi"),
        "kalki 2898 ad" to MovieMetadata("Kalki 2898 AD", "", "2024", "Sci-Fi • Action • Fantasy", "Hindi"),
        "rustom" to MovieMetadata("Rustom", "", "2016", "Crime • Drama • Thriller", "Hindi"),
        "bade miyan chote miyan" to MovieMetadata("Bade Miyan Chote Miyan", "", "2024", "Action • Comedy", "Hindi"),
        "now you see me" to MovieMetadata("Now You See Me", "", "2013", "Crime • Mystery • Thriller", "English"),
        "oppenheimer" to MovieMetadata("Oppenheimer", "", "2023", "Biography • Drama • History", "English"),
        "inception" to MovieMetadata("Inception", "", "2010", "Sci-Fi • Action • Thriller", "English"),
        "mission impossible" to MovieMetadata("Mission: Impossible", "", "1996", "Action • Adventure • Thriller", "English"),
        "dwitiyo purush" to MovieMetadata("Dwitiyo Purush", "", "2020", "Crime • Mystery • Thriller", "Bengali"),
        "alinagarer golokdhadha" to MovieMetadata("Alinagarer Golokdhadha", "", "2018", "Adventure • Mystery", "Bengali"),
        "asche abar shabor" to MovieMetadata("Asche Abar Shabor", "", "2018", "Crime • Mystery • Thriller", "Bengali"),
        "atthoi" to MovieMetadata("Atthoi", "", "2024", "Drama • Thriller", "Bengali"),
        "murder by the sea" to MovieMetadata("Murder by the Sea", "", "2022", "Crime • Mystery • Thriller", "Bengali"),
        "eagoler chokh" to MovieMetadata("Eagoler Chokh", "", "2016", "Crime • Mystery • Thriller", "Bengali"),
        "killbill society" to MovieMetadata("Killbill Society", "", "2023", "Thriller • Drama", "Bengali"),
        "sonar kella" to MovieMetadata("Sonar Kella", "", "1974", "Adventure • Mystery • Family", "Bengali"),
        "shri swapankumarer badami hydrarer kobor" to MovieMetadata("Shri Swapankumarer Badami Hydrarer Kobor", "", "2024", "Mystery • Comedy", "Bengali"),
        "bhagwat chapter one raakshas" to MovieMetadata("Bhagwat: Chapter One - Raakshas", "", "2024", "Crime • Thriller", "Hindi"),
        "bhool chuk maaf" to MovieMetadata("Bhool Chuk Maaf", "", "2024", "Comedy • Drama", "Hindi"),
        "madras cafe" to MovieMetadata("Madras Cafe", "", "2013", "Action • Thriller • Drama", "Hindi")
    )

    val CURATED_MOVIES: Map<String, MovieMetadata> get() = CURATED_TITLES

    fun clearCache() {
        movieCache.clear()
        seriesCache.clear()
        showEpisodesCache.clear()
    }

    fun resolveMovieSync(rawFileName: String): MovieMetadata {
        val (cleanTitle, releaseYear) = extractMovieQueryAndYear(rawFileName)
        val lowerKey = cleanTitle.lowercase().replace(Regex("[^a-z0-9]"), " ").replace(Regex("\\s+"), " ").trim()

        CURATED_TITLES[lowerKey]?.let { return it }

        // Sort by key length descending so specific titles like "dune part two" match before generic "dune"
        val sortedCurated = CURATED_TITLES.entries.sortedByDescending { it.key.length }
        for ((key, meta) in sortedCurated) {
            if (lowerKey == key || (key.length > 3 && lowerKey.contains(key))) {
                return meta
            }
        }

        // Match partial titles where lowerKey is a significant prefix (at least 8 chars) of a curated title
        for ((key, meta) in sortedCurated) {
            if (lowerKey.length >= 8 && key.startsWith(lowerKey)) {
                return meta
            }
        }

        return MovieMetadata(
            officialTitle = cleanTitle,
            releaseYear = releaseYear ?: "",
            genre = "Drama • Cinema",
            language = deduceLanguageFromTitle(cleanTitle)
        )
    }

    /**
     * Actively tests whether a remote poster image URL is reachable and serves an image payload (HTTP 200..299).
     * Uses a lightweight HTTP byte-range request with a 3-second timeout.
     */
    fun isPosterUrlReachable(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        var connection: HttpURLConnection? = null
        return try {
            val targetUrl = URI(url).toURL()
            connection = (targetUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                setRequestProperty("Accept", "image/*,*/*;q=0.8")
            }
            val code = connection.responseCode
            val contentType = connection.contentType ?: ""
            (code in 200..299) && (contentType.startsWith("image/", ignoreCase = true) || contentType.isBlank() || code == 206)
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun resolveMovie(rawFileName: String): MovieMetadata = withContext(Dispatchers.IO) {
        val trimmed = rawFileName.trim()
        movieCache[trimmed]?.let { return@withContext it }

        val syncMeta = resolveMovieSync(trimmed)
        val isCurated = CURATED_TITLES.values.any { it.officialTitle == syncMeta.officialTitle }
        if (isCurated && syncMeta.posterUrl.isNotBlank() && isPosterUrlReachable(syncMeta.posterUrl)) {
            movieCache[trimmed] = syncMeta
            return@withContext syncMeta
        }

        val (cleanQuery, releaseYear) = extractMovieQueryAndYear(trimmed)
        val searchCandidates = generateCandidateQueries(syncMeta.officialTitle.ifBlank { cleanQuery })

        var matchedTitle: String? = syncMeta.officialTitle.ifBlank { null }
        var matchedPoster = if (isPosterUrlReachable(syncMeta.posterUrl)) syncMeta.posterUrl else ""
        var matchedYear = syncMeta.releaseYear.ifBlank { releaseYear ?: "" }
        var matchedGenre: String? = if (syncMeta.genre != "Drama • Cinema") syncMeta.genre else null
        var matchedLanguage: String? = if (syncMeta.language.isNotBlank() && syncMeta.language != "Unknown") syncMeta.language else null
        var matchedImdbId: String = ""

        // 1. Step A: Query IMDb Suggestion API for official title, canonical IMDb ID ("tt..."), and theatrical poster
        for (candidate in searchCandidates) {
            val imdb = fetchFromImdb(candidate, matchedYear)
            if (imdb != null) {
                if (matchedTitle == null) matchedTitle = imdb.officialTitle
                if (imdb.imdbId.isNotBlank() && matchedImdbId.isBlank()) matchedImdbId = imdb.imdbId
                if (imdb.posterUrl.isNotBlank() && matchedPoster.isBlank() && isPosterUrlReachable(imdb.posterUrl)) {
                    matchedPoster = imdb.posterUrl
                }
                if (imdb.releaseYear.isNotBlank() && matchedYear.isBlank()) matchedYear = imdb.releaseYear
                if (matchedImdbId.isNotBlank() && matchedPoster.isNotBlank()) break
            }
        }

        // 2. Step B: TMDB exact /find/{imdb_id} (if IMDb ID found) OR /search/movie
        if (matchedImdbId.startsWith("tt")) {
            val tmdbByImdb = fetchFromTmdbByImdbId(matchedImdbId)
            if (tmdbByImdb != null) {
                if (matchedTitle == null) matchedTitle = tmdbByImdb.officialTitle
                if (tmdbByImdb.language.isNotBlank() && (matchedLanguage == null || matchedLanguage == "Unknown")) {
                    matchedLanguage = tmdbByImdb.language
                }
                if (matchedPoster.isBlank() && tmdbByImdb.posterUrl.isNotBlank() && isPosterUrlReachable(tmdbByImdb.posterUrl)) {
                    matchedPoster = tmdbByImdb.posterUrl
                }
                if (matchedYear.isBlank() && tmdbByImdb.releaseYear.isNotBlank()) matchedYear = tmdbByImdb.releaseYear
                if (matchedGenre.isNullOrBlank() && tmdbByImdb.genre.isNotBlank() && tmdbByImdb.genre != "Drama") {
                    matchedGenre = tmdbByImdb.genre
                }
            }
        }

        if (matchedLanguage.isNullOrBlank() || matchedLanguage == "Unknown" || matchedPoster.isBlank()) {
            for (candidate in searchCandidates) {
                val tmdb = fetchFromTmdbMovie(candidate, releaseYear ?: matchedYear)
                if (tmdb != null) {
                    if (matchedTitle == null) matchedTitle = tmdb.officialTitle
                    if (tmdb.language.isNotBlank() && (matchedLanguage == null || matchedLanguage == "Unknown")) {
                        matchedLanguage = tmdb.language
                    }
                    if (matchedPoster.isBlank() && tmdb.posterUrl.isNotBlank() && isPosterUrlReachable(tmdb.posterUrl)) {
                        matchedPoster = tmdb.posterUrl
                    }
                    if (matchedYear.isBlank() && tmdb.releaseYear.isNotBlank()) matchedYear = tmdb.releaseYear
                    if (matchedGenre.isNullOrBlank() && tmdb.genre.isNotBlank() && tmdb.genre != "Drama") {
                        matchedGenre = tmdb.genre
                    }
                    if (matchedLanguage != null && matchedPoster.isNotBlank()) break
                }
            }
        }

        val queryForWiki = matchedTitle ?: searchCandidates.firstOrNull() ?: cleanQuery

        // 3. Step C: Wikipedia REST API Backup if original language, poster or genre is missing
        if (matchedLanguage.isNullOrBlank() || matchedLanguage == "Unknown" || matchedPoster.isBlank() || matchedGenre.isNullOrBlank()) {
            val wiki = fetchFromWikipedia(queryForWiki)
            if (wiki != null) {
                if (matchedTitle == null) matchedTitle = wiki.officialTitle
                if (wiki.language.isNotBlank() && wiki.language != "Unknown" && (matchedLanguage == null || matchedLanguage == "Unknown")) {
                    matchedLanguage = wiki.language
                }
                if (matchedPoster.isBlank() && wiki.posterUrl.isNotBlank() && isPosterUrlReachable(wiki.posterUrl)) {
                    matchedPoster = wiki.posterUrl
                }
                if (matchedYear.isBlank() && wiki.releaseYear.isNotBlank()) matchedYear = wiki.releaseYear
                if (matchedGenre.isNullOrBlank() && wiki.genre.isNotBlank() && wiki.genre != "Drama") {
                    matchedGenre = wiki.genre
                }
            }
        }

        // 4. iTunes API as fallback for poster/genre
        if (matchedPoster.isBlank() || matchedGenre.isNullOrBlank()) {
            val itunes = fetchFromItunes(queryForWiki, matchedYear)
            if (itunes != null) {
                if (matchedTitle == null) matchedTitle = itunes.officialTitle
                if (matchedPoster.isBlank() && itunes.posterUrl.isNotBlank() && isPosterUrlReachable(itunes.posterUrl)) {
                    matchedPoster = itunes.posterUrl
                }
                if (matchedYear.isBlank() && itunes.releaseYear.isNotBlank()) matchedYear = itunes.releaseYear
                if (matchedGenre.isNullOrBlank() && itunes.genre.isNotBlank() && itunes.genre != "Drama") {
                    matchedGenre = itunes.genre
                }
            }
        }

        val finalTitle = matchedTitle ?: cleanQuery
        val finalGenre = matchedGenre?.ifBlank { null } ?: syncMeta.genre
        val finalLanguage = matchedLanguage?.takeIf { it != "Unknown" } ?: deduceLanguageFromTitle(finalTitle)

        val result = MovieMetadata(
            officialTitle = finalTitle,
            posterUrl = matchedPoster,
            releaseYear = matchedYear,
            genre = finalGenre,
            language = finalLanguage,
            imdbId = matchedImdbId
        )
        movieCache[trimmed] = result
        result
    }

    private fun fetchFromImdb(query: String, targetYear: String?): MovieMetadata? {
        var connection: HttpURLConnection? = null
        try {
            val slug = query.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            if (slug.isBlank()) return null
            val firstChar = slug.first()

            val endpoint = "https://v3.sg.media-imdb.com/suggestion/$firstChar/$slug.json"
            connection = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(jsonText).asJsonObject
                val results = root.getAsJsonArray("d") ?: return null

                val movieQids = setOf("movie", "tvMovie", "short")
                var bestObj: com.google.gson.JsonObject? = null

                for (i in 0 until results.size()) {
                    val item = results.get(i).asJsonObject
                    val title = item.get("l")?.asString ?: ""
                    val year = item.get("y")?.asInt?.toString() ?: ""
                    val qid = item.get("qid")?.asString ?: ""

                    if (isTitleSimilar(query, title)) {
                        if (!targetYear.isNullOrBlank() && year == targetYear && qid in movieQids) {
                            bestObj = item
                            break
                        }
                        if (bestObj == null) {
                            bestObj = item
                        } else if (qid in movieQids && (bestObj.get("qid")?.asString ?: "") !in movieQids) {
                            bestObj = item
                        }
                    }
                }

                if (bestObj != null) {
                    val officialTitle = bestObj.get("l")?.asString?.trim() ?: query
                    val year = bestObj.get("y")?.asInt?.toString() ?: ""
                    val imageObj = bestObj.getAsJsonObject("i")
                    var posterUrl = imageObj?.get("imageUrl")?.asString ?: ""

                    if (posterUrl.contains("._V1_")) {
                        posterUrl = posterUrl.replace(Regex("(?i)\\._V1_.*(\\.[a-zA-Z0-9]+)$"), "._V1_UX780_$1")
                    }

                    val validatedPoster = if (isPosterUrlReachable(posterUrl)) posterUrl else ""
                    val canonicalImdbId = bestObj.get("id")?.asString?.takeIf { it.startsWith("tt") } ?: ""

                    return MovieMetadata(
                        officialTitle = officialTitle,
                        posterUrl = validatedPoster,
                        releaseYear = year,
                        genre = "",
                        imdbId = canonicalImdbId
                    )
                }
            }
        } catch (e: Exception) {
            System.err.println("[IMDb] Movie lookup failed for $query: ${e.message}")
        } finally {
            connection?.disconnect()
        }
        return null
    }

    private data class CachedRichDetails(
        val details: MovieRichDetails,
        val cachedAtMs: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean {
            val isComplete = details.plotSummary.isNotBlank() && details.officialTitle.isNotBlank()
            return if (isComplete) {
                false
            } else {
                System.currentTimeMillis() - cachedAtMs > 60_000L
            }
        }
    }

    private val richDetailsCache = ConcurrentHashMap<String, CachedRichDetails>()

    private data class CinemetaMovieMeta(
        val plotSummary: String = "",
        val imdbRating: String = "",
        val releaseYear: String = "",
        val posterUrl: String = "",
        val director: String = "",
        val genres: String = "",
        val tmdbId: Long = -1L,
        val cast: List<MovieCastMember> = emptyList()
    )

    private fun normalizePersonName(name: String): String {
        return name.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    private fun mergeCastLists(
        primary: List<MovieCastMember>,
        additional: List<MovieCastMember>,
        maxCast: Int = 20
    ): List<MovieCastMember> {
        val merged = primary.toMutableList()
        for (candidate in additional) {
            if (candidate.name.isBlank()) continue
            val norm = normalizePersonName(candidate.name)
            if (norm.isBlank()) continue

            val existingIdx = merged.indexOfFirst { normalizePersonName(it.name) == norm }
            if (existingIdx >= 0) {
                val existing = merged[existingIdx]
                val updatedPhoto = if (existing.photoUrl.isBlank() && candidate.photoUrl.isNotBlank()) candidate.photoUrl else existing.photoUrl
                val updatedChar = if (existing.character.isBlank() && candidate.character.isNotBlank()) candidate.character else existing.character
                val updatedId = if (existing.id.isBlank() && candidate.id.isNotBlank()) candidate.id else existing.id
                merged[existingIdx] = existing.copy(
                    id = updatedId,
                    character = updatedChar,
                    photoUrl = updatedPhoto
                )
            } else if (merged.size < maxCast) {
                merged.add(candidate)
            }
        }
        return merged.take(maxCast)
    }

    private fun fetchFromCinemeta(imdbId: String): CinemetaMovieMeta? {
        if (!imdbId.startsWith("tt")) return null
        var conn: HttpURLConnection? = null
        try {
            val endpoint = "https://v3-cinemeta.strem.io/meta/movie/$imdbId.json"
            conn = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(json).asJsonObject
                val meta = root.getAsJsonObject("meta") ?: return null
                val desc = meta.get("description")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                val rating = meta.get("imdbRating")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                val year = meta.get("year")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                val poster = meta.get("poster")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                val tmdbId = meta.get("moviedb_id")?.takeIf { !it.isJsonNull }?.asLong ?: -1L

                val directorStr = when {
                    meta.has("director") && meta.get("director").isJsonArray -> {
                        meta.getAsJsonArray("director").mapNotNull { if (!it.isJsonNull) it.asString.trim() else null }.filter { it.isNotBlank() }.joinToString(", ")
                    }
                    meta.has("director") && meta.get("director").isJsonPrimitive -> {
                        meta.get("director").asString.trim()
                    }
                    else -> ""
                }

                val genresStr = when {
                    meta.has("genres") && meta.get("genres").isJsonArray -> {
                        meta.getAsJsonArray("genres").mapNotNull { if (!it.isJsonNull) it.asString.trim() else null }.filter { it.isNotBlank() }.joinToString(" • ")
                    }
                    meta.has("genres") && meta.get("genres").isJsonPrimitive -> {
                        meta.get("genres").asString.trim()
                    }
                    else -> ""
                }

                val cinemetaCast = mutableListOf<MovieCastMember>()
                if (meta.has("credits_cast") && meta.get("credits_cast").isJsonArray) {
                    val array = meta.getAsJsonArray("credits_cast")
                    for (i in 0 until array.size()) {
                        val obj = array.get(i)
                        if (obj.isJsonObject) {
                            val cObj = obj.asJsonObject
                            val name = cObj.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                            val character = cObj.get("character")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                            val photo = cObj.get("profile_path")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                            val formattedPhoto = if (photo.isNotBlank()) "https://image.tmdb.org/t/p/w185$photo" else ""
                            if (name.isNotBlank()) {
                                cinemetaCast.add(MovieCastMember(name = name, character = character, photoUrl = formattedPhoto))
                            }
                        }
                    }
                }

                if (cinemetaCast.isEmpty() && meta.has("cast") && meta.get("cast").isJsonArray) {
                    val array = meta.getAsJsonArray("cast")
                    for (i in 0 until array.size()) {
                        val elem = array.get(i)
                        if (elem.isJsonPrimitive) {
                            val name = elem.asString.trim()
                            if (name.isNotBlank()) {
                                cinemetaCast.add(MovieCastMember(name = name))
                            }
                        } else if (elem.isJsonObject) {
                            val cObj = elem.asJsonObject
                            val name = cObj.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                            val character = cObj.get("character")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                            if (name.isNotBlank()) {
                                cinemetaCast.add(MovieCastMember(name = name, character = character))
                            }
                        }
                    }
                }

                if (cinemetaCast.isEmpty() && meta.has("links") && meta.get("links").isJsonArray) {
                    val links = meta.getAsJsonArray("links")
                    for (i in 0 until links.size()) {
                        val lObj = links.get(i)
                        if (lObj.isJsonObject) {
                            val cat = lObj.asJsonObject.get("category")?.takeIf { !it.isJsonNull }?.asString ?: ""
                            if (cat.equals("Cast", ignoreCase = true)) {
                                val name = lObj.asJsonObject.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                                if (name.isNotBlank()) {
                                    cinemetaCast.add(MovieCastMember(name = name))
                                }
                            }
                        }
                    }
                }

                return CinemetaMovieMeta(
                    plotSummary = desc,
                    imdbRating = rating,
                    releaseYear = year,
                    posterUrl = poster,
                    director = directorStr,
                    genres = genresStr,
                    tmdbId = tmdbId,
                    cast = cinemetaCast
                )
            }
        } catch (e: Exception) {
            System.err.println("[Cinemeta] Meta fetch failed for $imdbId: ${e.message}")
        } finally {
            conn?.disconnect()
        }
        return null
    }

    private fun fetchWikipediaPlotSummary(officialTitle: String, releaseYear: String = ""): String {
        val candidates = mutableListOf<String>()
        val cleanTitle = cleanHtml(officialTitle).trim()
        if (cleanTitle.isBlank()) return ""

        try {
            val encodedQuery = URLEncoder.encode(cleanTitle, "UTF-8")
            val searchUrl = "https://en.wikipedia.org/w/api.php?action=opensearch&search=$encodedQuery&limit=5&namespace=0&format=json"
            val conn = (URI(searchUrl).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "Clotube-Windows/1.0 (contact@clotube.app)")
            }
            if (conn.responseCode == 200) {
                val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
                val rootArray = JsonParser.parseString(jsonText).asJsonArray
                if (rootArray.size() >= 2) {
                    val titlesArray = rootArray.get(1).asJsonArray
                    for (i in 0 until titlesArray.size()) {
                        val articleTitle = titlesArray.get(i).asString
                        if (articleTitle.contains("film", ignoreCase = true) ||
                            articleTitle.contains("movie", ignoreCase = true) ||
                            isTitleSimilar(cleanTitle, articleTitle)
                        ) {
                            candidates.add(articleTitle)
                        }
                    }
                }
            }
            conn.disconnect()
        } catch (_: Exception) {}

        if (releaseYear.isNotBlank()) {
            candidates.add("$cleanTitle ($releaseYear film)")
        }
        candidates.add("$cleanTitle (film)")
        candidates.add(cleanTitle)

        for (candidate in candidates.distinct()) {
            try {
                val encodedArticle = URLEncoder.encode(candidate.replace(" ", "_"), "UTF-8")
                val summaryUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/$encodedArticle"
                val conn = (URI(summaryUrl).toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("User-Agent", "Clotube-Windows/1.0 (contact@clotube.app)")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode == 200) {
                    val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
                    val summaryJson = JsonParser.parseString(jsonText).asJsonObject
                    val type = summaryJson.get("type")?.asString ?: ""
                    if (type.equals("disambiguation", ignoreCase = true)) {
                        conn.disconnect()
                        continue
                    }
                    val extract = summaryJson.get("extract")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                    if (extract.isNotBlank() && !extract.contains("may refer to:", ignoreCase = true)) {
                        conn.disconnect()
                        return extract
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }
        return ""
    }

    /**
     * Resolves rich details for a movie across a 4-provider progressive enrichment chain:
     * 1. IMDb Suggestion API + IMDb Title Page scraping
     * 2. TMDB v3 Full Details & Credits (/find, /search/movie, /movie/{id}?append_to_response=credits,external_ids)
     * 3. Stremio Cinemeta Meta API (https://v3-cinemeta.strem.io/meta/movie/{imdbId}.json)
     * 4. Wikipedia REST Summary API (https://en.wikipedia.org/api/rest_v1/page/summary/...)
     * With concurrent cast headshot hydration and smart TTL caching (60s for partial results).
     */
    suspend fun resolveMovieRichDetails(rawTitle: String, fallbackYear: String = ""): MovieRichDetails = withContext(Dispatchers.IO) {
        val (cleanQuery, extractedYear) = extractMovieQueryAndYear(rawTitle)
        val targetYear = fallbackYear.ifBlank { extractedYear ?: "" }
        val cacheKey = "$cleanQuery:$targetYear".lowercase()
        richDetailsCache[cacheKey]?.let { cached ->
            if (!cached.isExpired()) {
                return@withContext cached.details
            } else {
                richDetailsCache.remove(cacheKey)
            }
        }

        var imdbId = ""
        var officialTitle = cleanQuery
        var releaseYear = targetYear
        var posterUrl = ""
        var topActorsFromSuggestion: List<String> = emptyList()

        // -------------------------------------------------------------
        // Provider 1: IMDb Suggestion API + IMDb Title Page scraping
        // -------------------------------------------------------------
        try {
            val slug = cleanQuery.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            if (slug.isNotBlank()) {
                val firstChar = slug.first()
                val endpoint = "https://v3.sg.media-imdb.com/suggestion/$firstChar/$slug.json"
                val conn = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                }
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JsonParser.parseString(jsonText).asJsonObject
                    val results = root.getAsJsonArray("d")
                    if (results != null && results.size() > 0) {
                        val movieQids = setOf("movie", "tvMovie", "short")
                        var bestItem: JsonObject? = null
                        for (i in 0 until results.size()) {
                            val item = results.get(i).asJsonObject
                            val title = item.get("l")?.asString ?: ""
                            val year = item.get("y")?.asInt?.toString() ?: ""
                            val qid = item.get("qid")?.asString ?: ""
                            if (isTitleSimilar(cleanQuery, title)) {
                                if (targetYear.isNotBlank() && year == targetYear && qid in movieQids) {
                                    bestItem = item
                                    break
                                }
                                if (bestItem == null) {
                                    bestItem = item
                                } else if (qid in movieQids && (bestItem.get("qid")?.asString ?: "") !in movieQids) {
                                    bestItem = item
                                }
                            }
                        }
                        bestItem?.let { item ->
                            imdbId = item.get("id")?.asString ?: ""
                            officialTitle = item.get("l")?.asString?.trim() ?: cleanQuery
                            val y = item.get("y")?.asInt?.toString() ?: ""
                            if (y.isNotBlank()) releaseYear = y
                            val img = item.getAsJsonObject("i")?.get("imageUrl")?.asString ?: ""
                            if (img.isNotBlank()) {
                                posterUrl = if (img.contains("._V1_")) {
                                    img.replace(Regex("(?i)\\._V1_.*(\\.[a-zA-Z0-9]+)$"), "._V1_UX780_$1")
                                } else img
                            }
                            val stars = item.get("s")?.asString ?: ""
                            if (stars.isNotBlank()) {
                                topActorsFromSuggestion = stars.split(",").map { it.trim() }.filter { it.isNotBlank() }
                            }
                        }
                    }
                }
                conn.disconnect()
            }
        } catch (e: Exception) {
            System.err.println("[IMDb Details] Suggestion lookup failed: ${e.message}")
        }

        var ratingValue = ""
        var ratingCount = ""
        var genres = ""
        var director = ""
        var plotSummary = ""
        var castList = mutableListOf<MovieCastMember>()

        if (imdbId.startsWith("tt")) {
            try {
                val pageUrl = "https://www.imdb.com/title/$imdbId/"
                val conn = (URI(pageUrl).toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                }
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val html = conn.inputStream.bufferedReader().use { it.readText() }

                    // 1. Parse <script type="application/ld+json">
                    val ldJsonMatcher = Pattern.compile("<script\\s+type=[\"']application/ld\\+json[\"']>(.*?)</script>", Pattern.DOTALL).matcher(html)
                    while (ldJsonMatcher.find()) {
                        val snippet = ldJsonMatcher.group(1).trim()
                        try {
                            val parsed = JsonParser.parseString(snippet)
                            val ldObj = when {
                                parsed.isJsonObject -> parsed.asJsonObject
                                parsed.isJsonArray -> parsed.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }.firstOrNull { it.has("@type") && (it.get("@type").asString == "Movie" || it.has("aggregateRating")) }
                                else -> null
                            }
                            if (ldObj != null) {
                                val name = ldObj.get("name")?.takeIf { !it.isJsonNull }?.asString
                                if (!name.isNullOrBlank()) officialTitle = cleanHtml(name)

                                val desc = ldObj.get("description")?.takeIf { !it.isJsonNull }?.asString
                                if (!desc.isNullOrBlank()) plotSummary = cleanHtml(desc)

                                if (ldObj.has("aggregateRating")) {
                                    val agg = ldObj.getAsJsonObject("aggregateRating")
                                    val rVal = agg.get("ratingValue")?.takeIf { !it.isJsonNull }?.asDouble
                                    if (rVal != null) {
                                        ratingValue = "%.1f".format(rVal)
                                    }
                                    val rCnt = agg.get("ratingCount")?.takeIf { !it.isJsonNull }?.asLong
                                    if (rCnt != null) {
                                        ratingCount = formatCompactVotes(rCnt)
                                    }
                                }

                                if (ldObj.has("genre")) {
                                    val gElem = ldObj.get("genre")
                                    genres = when {
                                        gElem.isJsonArray -> gElem.asJsonArray.mapNotNull { it.asString }.take(3).joinToString(" • ")
                                        gElem.isJsonPrimitive -> gElem.asString
                                        else -> ""
                                    }
                                }

                                if (ldObj.has("director")) {
                                    val dElem = ldObj.get("director")
                                    director = when {
                                        dElem.isJsonArray -> dElem.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject.get("name")?.asString else null }.joinToString(", ")
                                        dElem.isJsonObject -> dElem.asJsonObject.get("name")?.asString ?: ""
                                        else -> ""
                                    }
                                }

                                if (ldObj.has("actor") && castList.isEmpty()) {
                                    val aElem = ldObj.get("actor")
                                    if (aElem.isJsonArray) {
                                        aElem.asJsonArray.forEach { actorElem ->
                                            if (actorElem.isJsonObject) {
                                                val aObj = actorElem.asJsonObject
                                                val aName = aObj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                                val aUrl = aObj.get("url")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                                val aId = Regex("(nm\\d+)").find(aUrl)?.groupValues?.get(1) ?: ""
                                                if (aName.isNotBlank()) {
                                                    castList.add(MovieCastMember(id = aId, name = aName, character = "", photoUrl = ""))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    // 2. Extract rich cast with photos & characters from __NEXT_DATA__
                    val nextDataMatcher = Pattern.compile("<script\\s+id=[\"']__NEXT_DATA__[\"']\\s+type=[\"']application/json[\"']>(.*?)</script>", Pattern.DOTALL).matcher(html)
                    if (nextDataMatcher.find()) {
                        try {
                            val nextJson = JsonParser.parseString(nextDataMatcher.group(1)).asJsonObject
                            val props = nextJson.getAsJsonObject("props")?.getAsJsonObject("pageProps")
                            val above = props?.getAsJsonObject("aboveTheFoldData")
                            val main = props?.getAsJsonObject("mainColumnData")

                            val castEdges = above?.getAsJsonObject("castPageTitle")?.getAsJsonArray("edges")
                                ?: main?.getAsJsonObject("cast")?.getAsJsonArray("edges")

                            if (castEdges != null && castEdges.size() > 0) {
                                val nextCast = mutableListOf<MovieCastMember>()
                                for (i in 0 until castEdges.size().coerceAtMost(20)) {
                                    val node = castEdges.get(i).asJsonObject.getAsJsonObject("node") ?: continue
                                    val personId = node.get("id")?.asString ?: ""
                                    val personName = node.getAsJsonObject("name")?.getAsJsonObject("nameText")?.get("text")?.asString ?: ""
                                    val rawPhoto = node.getAsJsonObject("name")?.getAsJsonObject("primaryImage")?.get("url")?.asString ?: ""
                                    val photoUrlFormatted = if (rawPhoto.contains("._V1_")) {
                                        rawPhoto.replace(Regex("(?i)\\._V1_.*(\\.[a-zA-Z0-9]+)$"), "._V1_UX300_$1")
                                    } else rawPhoto

                                    var charName = ""
                                    val charArray = node.getAsJsonArray("characters")
                                    if (charArray != null && charArray.size() > 0) {
                                        charName = charArray.get(0).asJsonObject.get("name")?.asString ?: ""
                                    }

                                    if (personName.isNotBlank()) {
                                        nextCast.add(
                                            MovieCastMember(
                                                id = personId,
                                                name = personName,
                                                character = charName,
                                                photoUrl = photoUrlFormatted
                                            )
                                        )
                                    }
                                }
                                if (nextCast.isNotEmpty()) {
                                    castList = nextCast
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                System.err.println("[IMDb Details] HTML extraction failed: ${e.message}")
            }
        }

        if (castList.isEmpty() && topActorsFromSuggestion.isNotEmpty()) {
            topActorsFromSuggestion.take(10).forEach { actorName ->
                castList.add(MovieCastMember(name = actorName))
            }
        }

        // -------------------------------------------------------------
        // Provider 2: TMDB v3 Full Details & Credits
        // Always query TMDB whenever cast.size < 12, or any cast member lacks a character name,
        // or plotSummary/rating/director is missing
        // -------------------------------------------------------------
        var tmdbMovieId = -1L
        val shouldQueryTmdb = plotSummary.isBlank() ||
            castList.size < 12 ||
            castList.any { it.character.isBlank() } ||
            ratingValue.isBlank() ||
            director.isBlank()

        if (shouldQueryTmdb) {
            try {
                if (imdbId.startsWith("tt")) {
                    val findUrl = "https://api.themoviedb.org/3/find/$imdbId?api_key=$TMDB_API_KEY&external_source=imdb_id"
                    val conn = (URI(findUrl).toURL().openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 4000
                        readTimeout = 4000
                    }
                    if (conn.responseCode == 200) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        val root = JsonParser.parseString(text).asJsonObject
                        val mResults = root.getAsJsonArray("movie_results")
                        if (mResults != null && mResults.size() > 0) {
                            tmdbMovieId = mResults.get(0).asJsonObject.get("id")?.asLong ?: -1L
                        }
                    }
                    conn.disconnect()
                }

                if (tmdbMovieId <= 0L && targetYear.isNotBlank()) {
                    val sQuery = URLEncoder.encode(officialTitle.ifBlank { cleanQuery }, "UTF-8")
                    val sUrl = "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&query=$sQuery&primary_release_year=$targetYear"
                    val conn = (URI(sUrl).toURL().openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 4000
                        readTimeout = 4000
                    }
                    if (conn.responseCode == 200) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        val root = JsonParser.parseString(text).asJsonObject
                        val results = root.getAsJsonArray("results")
                        if (results != null && results.size() > 0) {
                            tmdbMovieId = results.get(0).asJsonObject.get("id")?.asLong ?: -1L
                        }
                    }
                    conn.disconnect()
                }

                if (tmdbMovieId <= 0L) {
                    val sQuery = URLEncoder.encode(officialTitle.ifBlank { cleanQuery }, "UTF-8")
                    val sUrl = "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&query=$sQuery"
                    val conn = (URI(sUrl).toURL().openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 4000
                        readTimeout = 4000
                    }
                    if (conn.responseCode == 200) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        val root = JsonParser.parseString(text).asJsonObject
                        val results = root.getAsJsonArray("results")
                        if (results != null && results.size() > 0) {
                            tmdbMovieId = results.get(0).asJsonObject.get("id")?.asLong ?: -1L
                        }
                    }
                    conn.disconnect()
                }

                if (tmdbMovieId > 0L) {
                    val detailsUrl = "https://api.themoviedb.org/3/movie/$tmdbMovieId?api_key=$TMDB_API_KEY&append_to_response=credits,external_ids"
                    val conn = (URI(detailsUrl).toURL().openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 5000
                        readTimeout = 5000
                    }
                    if (conn.responseCode == 200) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        val root = JsonParser.parseString(text).asJsonObject

                        val overview = root.get("overview")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                        if (plotSummary.isBlank() && overview.isNotBlank()) {
                            plotSummary = overview
                        }

                        val voteAvg = root.get("vote_average")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
                        val voteCnt = root.get("vote_count")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                        if (ratingValue.isBlank() && voteAvg > 0.0) {
                            ratingValue = "%.1f".format(voteAvg)
                            if (voteCnt > 0L) {
                                ratingCount = formatCompactVotes(voteCnt)
                            }
                        }

                        val rDate = root.get("release_date")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        if (releaseYear.isBlank() && rDate.length >= 4) {
                            releaseYear = rDate.take(4)
                        }

                        if (genres.isBlank() && root.has("genres")) {
                            val gArray = root.getAsJsonArray("genres")
                            if (gArray != null) {
                                genres = gArray.mapNotNull {
                                    if (it.isJsonObject) it.asJsonObject.get("name")?.asString else null
                                }.take(3).joinToString(" • ")
                            }
                        }

                        if (posterUrl.isBlank()) {
                            val pPath = root.get("poster_path")?.takeIf { !it.isJsonNull }?.asString ?: ""
                            if (pPath.isNotBlank()) {
                                posterUrl = "$TMDB_IMAGE_BASE$pPath"
                            }
                        }

                        val extIds = root.getAsJsonObject("external_ids")
                        val extImdb = extIds?.get("imdb_id")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        if (imdbId.isBlank() && extImdb.startsWith("tt")) {
                            imdbId = extImdb
                        }

                        val credits = root.getAsJsonObject("credits")
                        if (credits != null) {
                            if (director.isBlank() && credits.has("crew")) {
                                val crewArray = credits.getAsJsonArray("crew")
                                if (crewArray != null) {
                                    val directors = mutableListOf<String>()
                                    for (i in 0 until crewArray.size()) {
                                        val member = crewArray.get(i).asJsonObject
                                        val job = member.get("job")?.asString ?: ""
                                        if (job.equals("Director", ignoreCase = true)) {
                                            val dName = member.get("name")?.asString ?: ""
                                            if (dName.isNotBlank() && dName !in directors) {
                                                directors.add(dName)
                                            }
                                        }
                                    }
                                    if (directors.isNotEmpty()) {
                                        director = directors.joinToString(", ")
                                    }
                                }
                            }

                            if (credits.has("cast")) {
                                val cArray = credits.getAsJsonArray("cast")
                                if (cArray != null) {
                                    val sortedCast = (0 until cArray.size())
                                        .map { cArray.get(it).asJsonObject }
                                        .sortedBy { it.get("order")?.asInt ?: 999 }
                                        .take(20)

                                    val tmdbCast = mutableListOf<MovieCastMember>()
                                    for (cObj in sortedCast) {
                                        val cName = cObj.get("name")?.asString ?: ""
                                        val cChar = cObj.get("character")?.asString ?: ""
                                        val cPath = cObj.get("profile_path")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                        val cPhoto = if (cPath.isNotBlank()) "https://image.tmdb.org/t/p/w185$cPath" else ""
                                        val cId = cObj.get("id")?.asString ?: ""
                                        if (cName.isNotBlank()) {
                                            tmdbCast.add(
                                                MovieCastMember(
                                                    id = cId,
                                                    name = cName,
                                                    character = cChar,
                                                    photoUrl = cPhoto
                                                )
                                            )
                                        }
                                    }
                                    if (tmdbCast.isNotEmpty()) {
                                        castList = mergeCastLists(castList, tmdbCast, maxCast = 20).toMutableList()
                                    }
                                }
                            }
                        }
                    }
                    conn.disconnect()
                }
            } catch (e: Exception) {
                System.err.println("[TMDB Fallback] Failed for $officialTitle: ${e.message}")
            }
        }

        // -------------------------------------------------------------
        // Provider 3: Stremio Cinemeta Meta API
        // Query Cinemeta whenever cast.size < 12, or any cast member lacks character,
        // or plotSummary/rating/director is missing
        // -------------------------------------------------------------
        val shouldQueryCinemeta = imdbId.startsWith("tt") && (
            plotSummary.isBlank() ||
            castList.size < 12 ||
            castList.any { it.character.isBlank() } ||
            ratingValue.isBlank() ||
            director.isBlank()
        )

        if (shouldQueryCinemeta) {
            val meta = fetchFromCinemeta(imdbId)
            if (meta != null) {
                if (plotSummary.isBlank() && meta.plotSummary.isNotBlank()) {
                    plotSummary = meta.plotSummary
                }
                if (ratingValue.isBlank() && meta.imdbRating.isNotBlank()) {
                    ratingValue = meta.imdbRating
                }
                if (releaseYear.isBlank() && meta.releaseYear.isNotBlank()) {
                    releaseYear = meta.releaseYear
                }
                if (director.isBlank() && meta.director.isNotBlank()) {
                    director = meta.director
                }
                if (genres.isBlank() && meta.genres.isNotBlank()) {
                    genres = meta.genres
                }
                if (posterUrl.isBlank() && meta.posterUrl.isNotBlank()) {
                    posterUrl = meta.posterUrl
                }
                if (meta.cast.isNotEmpty()) {
                    castList = mergeCastLists(castList, meta.cast, maxCast = 20).toMutableList()
                }
            }
        }

        // -------------------------------------------------------------
        // Provider 4: Wikipedia REST Summary API
        // Trigger if plotSummary.isBlank() after IMDb, TMDB, and Cinemeta
        // -------------------------------------------------------------
        if (plotSummary.isBlank()) {
            val wikiSummary = fetchWikipediaPlotSummary(officialTitle.ifBlank { cleanQuery }, releaseYear)
            if (wikiSummary.isNotBlank()) {
                plotSummary = wikiSummary
            }
        }

        if (plotSummary.isBlank()) {
            plotSummary = "No plot summary available for this title."
        }

        val result = MovieRichDetails(
            imdbId = imdbId,
            officialTitle = officialTitle,
            releaseYear = releaseYear,
            ratingValue = ratingValue,
            ratingCount = ratingCount,
            genres = genres,
            director = director,
            plotSummary = plotSummary,
            posterUrl = posterUrl,
            cast = castList
        )
        richDetailsCache[cacheKey] = CachedRichDetails(result)
        result
    }

    private fun formatCompactVotes(count: Long): String {
        return when {
            count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
            count >= 1_000 -> "${count / 1_000}K"
            else -> count.toString()
        }
    }

    fun mapTmdbLanguageCode(code: String?): String {
        if (code.isNullOrBlank()) return ""
        return when (code.lowercase().trim()) {
            "en" -> "English"
            "hi" -> "Hindi"
            "bn" -> "Bengali"
            "ta" -> "Tamil"
            "te" -> "Telugu"
            "ml" -> "Malayalam"
            "kn" -> "Kannada"
            "mr" -> "Marathi"
            "pa" -> "Punjabi"
            "gu" -> "Gujarati"
            "ko" -> "Korean"
            "ja" -> "Japanese"
            "es" -> "Spanish"
            "fr" -> "French"
            "de" -> "German"
            else -> {
                try {
                    val display = java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH)
                    if (display.isNotBlank()) display.replaceFirstChar { it.uppercase() } else "English"
                } catch (_: Exception) {
                    "English"
                }
            }
        }
    }

    /**
     * Resolves canonical movie metadata directly by IMDb ID using TMDB /find endpoint.
     */
    fun fetchFromTmdbByImdbId(imdbId: String): MovieMetadata? {
        if (!imdbId.startsWith("tt")) return null
        var connection: HttpURLConnection? = null
        try {
            val endpoint = "https://api.themoviedb.org/3/find/$imdbId?api_key=$TMDB_API_KEY&external_source=imdb_id"
            connection = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                setRequestProperty("Accept", "application/json")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(jsonText).asJsonObject
                val movieResults = root.getAsJsonArray("movie_results")
                if (movieResults != null && movieResults.size() > 0) {
                    val match = movieResults.get(0).asJsonObject
                    val officialTitle = match.get("title")?.takeIf { !it.isJsonNull }?.asString?.trim()
                        ?: match.get("original_title")?.takeIf { !it.isJsonNull }?.asString?.trim()
                        ?: ""
                    val posterPath = match.get("poster_path")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val posterUrl = if (posterPath.isNotBlank()) "$TMDB_IMAGE_BASE$posterPath" else ""
                    val releaseYear = match.get("release_date")?.takeIf { !it.isJsonNull }?.asString?.take(4) ?: ""
                    val origLang = match.get("original_language")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val lang = mapTmdbLanguageCode(origLang)

                    val genreIds = match.getAsJsonArray("genre_ids")
                    val genresList = mutableListOf<String>()
                    genreIds?.forEach { idElement ->
                        if (!idElement.isJsonNull) {
                            TMDB_GENRE_MAP[idElement.asInt]?.let { genresList.add(it) }
                        }
                    }
                    val cleanGenre = if (genresList.isNotEmpty()) genresList.take(3).joinToString(" • ") else "Drama"

                    return MovieMetadata(
                        officialTitle = officialTitle,
                        posterUrl = posterUrl,
                        releaseYear = releaseYear,
                        genre = cleanGenre,
                        language = lang,
                        imdbId = imdbId
                    )
                }
            }
        } catch (e: Exception) {
            System.err.println("[TMDB] Find by IMDb ID failed for $imdbId: ${e.message}")
        } finally {
            connection?.disconnect()
        }
        return null
    }

    /**
     * Resolves movie title, high-res poster (w780), release year, genres, and original language from TMDB.
     */
    private fun fetchFromTmdbMovie(query: String, targetYear: String?): MovieMetadata? {
        var connection: HttpURLConnection? = null
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val yearParam = if (!targetYear.isNullOrBlank()) "&primary_release_year=$targetYear" else ""
            val endpoint = "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&query=$encoded$yearParam"

            connection = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                setRequestProperty("Accept", "application/json")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(jsonText).asJsonObject
                val results = root.getAsJsonArray("results") ?: return null

                var bestMatch: com.google.gson.JsonObject? = null

                for (i in 0 until results.size()) {
                    val item = results.get(i).asJsonObject
                    val title = item.get("title")?.takeIf { !it.isJsonNull }?.asString 
                        ?: item.get("original_title")?.takeIf { !it.isJsonNull }?.asString 
                        ?: ""
                    val releaseDate = item.get("release_date")?.takeIf { !it.isJsonNull }?.asString ?: ""

                    if (isTitleSimilar(query, title)) {
                        if (!targetYear.isNullOrBlank() && releaseDate.startsWith(targetYear)) {
                            bestMatch = item
                            break
                        }
                        if (bestMatch == null) {
                            bestMatch = item
                        }
                    }
                }

                // Fallback to top result if title is sufficiently similar
                if (bestMatch == null && results.size() > 0) {
                    val first = results.get(0).asJsonObject
                    val firstTitle = first.get("title")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    if (isTitleSimilar(query, firstTitle)) {
                        bestMatch = first
                    }
                }

                if (bestMatch != null) {
                    val officialTitle = bestMatch.get("title")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: query
                    val posterPath = bestMatch.get("poster_path")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val posterUrl = if (posterPath.isNotBlank()) "$TMDB_IMAGE_BASE$posterPath" else ""
                    val releaseYear = bestMatch.get("release_date")?.takeIf { !it.isJsonNull }?.asString?.take(4) ?: ""
                    val origLang = bestMatch.get("original_language")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val lang = mapTmdbLanguageCode(origLang)

                    val genreIds = bestMatch.getAsJsonArray("genre_ids")
                    val genresList = mutableListOf<String>()
                    genreIds?.forEach { idElement ->
                        if (!idElement.isJsonNull) {
                            TMDB_GENRE_MAP[idElement.asInt]?.let { genresList.add(it) }
                        }
                    }
                    val cleanGenre = if (genresList.isNotEmpty()) genresList.take(3).joinToString(" • ") else "Drama"

                    return MovieMetadata(
                        officialTitle = officialTitle,
                        posterUrl = posterUrl,
                        releaseYear = releaseYear,
                        genre = cleanGenre,
                        language = lang
                    )
                }
            }
        } catch (e: Exception) {
            System.err.println("[TMDB] Movie lookup failed for $query: ${e.message}")
        } finally {
            connection?.disconnect()
        }
        return null
    }

    private fun fetchFromWikipedia(query: String): MovieMetadata? {
        var connection: HttpURLConnection? = null
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://en.wikipedia.org/w/api.php?action=opensearch&search=$encodedQuery&limit=5&namespace=0&format=json"

            connection = (URI(searchUrl).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Clotube-Windows/1.0 (contact@clotube.app)")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val rootArray = JsonParser.parseString(jsonText).asJsonArray
                if (rootArray.size() >= 2) {
                    val titlesArray = rootArray.get(1).asJsonArray
                    var matchedArticle: String? = null

                    for (i in 0 until titlesArray.size()) {
                        val candidate = titlesArray.get(i).asString
                        val cleanCandidate = candidate.replace(Regex("(?i)\\s*\\((film|bengali film|hindi film|movie|\\d{4} film)\\)"), "").trim()
                        if (isTitleSimilar(query, cleanCandidate)) {
                            matchedArticle = candidate
                            break
                        }
                    }

                    if (matchedArticle != null) {
                        connection.disconnect()

                        val summaryUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(matchedArticle, "UTF-8")
                        connection = (URI(summaryUrl).toURL().openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 5000
                            readTimeout = 5000
                            setRequestProperty("User-Agent", "Clotube-Windows/1.0 (contact@clotube.app)")
                        }

                        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                            val summaryText = connection.inputStream.bufferedReader().use { it.readText() }
                            val summaryJson = JsonParser.parseString(summaryText).asJsonObject

                            val displayTitle = summaryJson.get("title")?.asString
                                ?.replace(Regex("(?i)\\s*\\((film|bengali film|hindi film|movie|\\d{4} film)\\)"), "")
                                ?.trim() ?: query

                            val thumbObj = summaryJson.getAsJsonObject("thumbnail")
                            val origObj = summaryJson.getAsJsonObject("originalimage")
                            val posterUrl = origObj?.get("source")?.asString ?: thumbObj?.get("source")?.asString ?: ""

                            val extract = summaryJson.get("extract")?.asString ?: ""
                            val description = summaryJson.get("description")?.asString ?: ""
                            val fullText = "$description $extract"

                            val yearPattern = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b").matcher(extract)
                            val releaseYear = if (yearPattern.find()) yearPattern.group(1) else ""

                            val parsedGenre = extractGenresFromText(fullText)

                            val parsedLanguage = detectLanguageFromWikiText(fullText) ?: "Unknown"

                            return MovieMetadata(
                                officialTitle = displayTitle,
                                posterUrl = posterUrl,
                                releaseYear = releaseYear,
                                genre = parsedGenre.ifBlank { "Drama" },
                                language = parsedLanguage
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            connection?.disconnect()
        }
        return null
    }

    private fun fetchFromItunes(query: String, targetYear: String?): MovieMetadata? {
        var connection: HttpURLConnection? = null
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val endpoint = "https://itunes.apple.com/search?term=$encoded&entity=movie&limit=8"
            connection = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Clotube-Windows/1.0")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(jsonText).asJsonObject
                val results = root.getAsJsonArray("results") ?: return null

                var bestMatch: com.google.gson.JsonObject? = null

                for (i in 0 until results.size()) {
                    val item = results.get(i).asJsonObject
                    val title = item.get("trackName")?.asString ?: ""
                    val releaseDate = item.get("releaseDate")?.asString ?: ""

                    if (isTitleSimilar(query, title)) {
                        if (!targetYear.isNullOrBlank() && releaseDate.startsWith(targetYear)) {
                            bestMatch = item
                            break
                        }
                        if (bestMatch == null) {
                            bestMatch = item
                        }
                    }
                }

                if (bestMatch != null) {
                    val officialTitle = bestMatch.get("trackName")?.asString?.trim() ?: query
                    val rawThumb = bestMatch.get("artworkUrl100")?.asString ?: ""
                    val highResPoster = rawThumb.replace("100x100bb.jpg", "600x600bb.jpg")
                    val rawGenre = bestMatch.get("primaryGenreName")?.asString ?: "Drama"
                    val cleanGenre = rawGenre.replace("&", "•").replace("/", "•").trim()
                    val year = bestMatch.get("releaseDate")?.asString?.take(4) ?: ""

                    return MovieMetadata(
                        officialTitle = officialTitle,
                        posterUrl = highResPoster,
                        releaseYear = year,
                        genre = cleanGenre
                    )
                }
            }
        } catch (_: Exception) {
        } finally {
            connection?.disconnect()
        }
        return null
    }

    fun extractMovieQueryAndYear(raw: String): Pair<String, String?> {
        var text = raw.trim()
        text = text.replace(Regex("(?i)\\.(mkv|mp4|avi|mov|wmv|flv|webm|m4v|ts|m2ts)$"), "")
        text = text.replace(Regex("(?i)@[\\w._-]+"), " ")
        text = text.replace(Regex("(?i)\\b(https?://)?(www\\.)?[a-z0-9_-]+\\.(org|com|net|in|top|xyz|me|io|co|info|biz|club|site|online|tv|ru|cc|to|shop|buzz)\\b"), " ")
        text = normalizeIndicDigits(text)
        text = text.replace(Regex("[\\[\\](){}_+~\\-–—|]"), " ")
        text = text.replace(Regex("(?<=\\D)\\.|\\.(?=\\D)"), " ")
        text = text.replace(Regex("\\s+"), " ").trim()

        // Strip leading release group / uploader tags (e.g. "Cebray", "CBR", "YTS", etc.)
        val leadingTagRegex = Regex(
            "(?i)^\\s*(cebray\\w*|cbr\\w*|1337x\\w*|mlsbd\\w*|hub4u\\w*|filmy\\w*|fbm\\w*|wmr\\w*|vegamovies\\w*|hdhub4u\\w*|" +
            "bolly\\w*|katmovie\\w*|skymovies\\w*|cinevood\\w*|9xmovies\\w*|worldfree4u\\w*|" +
            "mkvcinemas\\w*|uindex\\w*|yts\\w*|yify\\w*|rarbg\\w*|psa\\w*|pahe\\w*|galaxytv\\w*|" +
            "galaxyrg\\w*|tgx\\w*|qxr\\w*|1[-_]?world\\w*|levelbook\\w*|t[-_]?series\\w*|" +
            "shop|all|new|the\\s+complete|full\\s+movie|movie|film|cinema|hd|hq|exclusive)\\b[\\s:-]*"
        )
        while (true) {
            val m = leadingTagRegex.find(text)
            if (m != null && m.range.first == 0) {
                text = text.substring(m.range.last + 1).trim()
            } else {
                break
            }
        }

        var year: String? = null
        val yearRegex = Regex("(?<!kalki\\s)\\b(19\\d{2}|20[0-2]\\d)\\b", RegexOption.IGNORE_CASE)
        val yearMatch = yearRegex.find(text)

        val cutoffPattern = Regex(
            "(?i)\\b(" +
            "cebray\\w*|cbr\\w*|" +
            "2160p|1080p|1080i|720p|480p|360p|4k|uhd|fhd|hdtv|hdr\\d*|dovi|dv|" +
            "bluray|blu-ray|brrip|bdrip|dvdrip|dvd|hdrip|web[-_]?dl|web[-_]?rip|webri|web|" +
            "imax|remux|untouched|proper|repack|extended|unrated|" +
            "x264|x265|h\\.?264|h\\.?265|hevc|avc|10bit|8bit|" +
            "aac\\d*|ddp?\\d*|dd\\d*|ac3|eac3|dts[-_]?hd|dts|truehd|atmos|mp3|flac|opus|" +
            "dual[-_\\s]*audio|multi[-_\\s]*audio|hindi[-_\\s]*dubbed|eng[-_\\s]*subbed|dubbed|esubs?|msubs?|subbed|subs?|" +
            "1337x\\w*|mlsbd\\w*|hub4u\\w*|filmy\\w*|fbm\\w*|wmr\\w*|vegamovies\\w*|hdhub4u\\w*|" +
            "bolly\\w*|katmovie\\w*|skymovies\\w*|cinevood\\w*|9xmovies\\w*|worldfree4u\\w*|" +
            "mkvcinemas\\w*|uindex\\w*|yts\\w*|yify\\w*|rarbg\\w*|psa\\w*|pahe\\w*|galaxytv\\w*|" +
            "galaxyrg\\w*|tgx\\w*|qxr\\w*|1[-_]?world\\w*|levelbook\\w*|t[-_]?series\\w*|" +
            "amzn|amazon|netflix|nf|hulu|dsnp|atvp|apple[-_]?tv|zee5|hotstar|hoichoi|chorki|addatimes|klikk|svf|" +
            "hq\\s*s\\s*print|camrip|telesync|predvd|upmix|s0\\d|e0\\d|season\\s*\\d|episode\\s*\\d" +
            ")\\b"
        )

        var cutoffIndex = -1
        if (yearMatch != null) {
            year = yearMatch.groupValues[1]
            cutoffIndex = yearMatch.range.first
        }

        val tagMatch = cutoffPattern.find(text)
        if (tagMatch != null) {
            if (cutoffIndex == -1 || tagMatch.range.first < cutoffIndex) {
                cutoffIndex = tagMatch.range.first
            }
        }

        var cleanTitle = if (cutoffIndex > 0) {
            text.substring(0, cutoffIndex)
        } else {
            text
        }

        val latinOnly = cleanTitle.replace(Regex("[^a-zA-Z0-9\\s-]"), " ").replace(Regex("\\s+"), " ").trim()
        if (latinOnly.length >= 3) {
            cleanTitle = latinOnly
        }

        cleanTitle = cleanTitle.replace(Regex("(?i)^\\s*(cebray|cbr|shop|all|new|the\\s+complete|full\\s+movie|movie|film|cinema|hd|hq|exclusive)\\s+"), " ")
        cleanTitle = cleanTitle.replace(Regex("(?i)\\s+(bollywood\\s+movie|full\\s+movie|movie|film|cinema|exclusive|latest|trailer|teaser|bengali|hindi|english)\\s*$"), " ")
        cleanTitle = cleanTitle.replace(Regex("(^\\s*[-–—:]+\\s*|\\s*[-–—:]+\\s*$)"), " ")
        cleanTitle = cleanTitle.replace(Regex("\\s+"), " ").trim()

        return Pair(toTitleCase(cleanTitle), year)
    }

    fun cleanTitleQuick(raw: String): String {
        return extractMovieQueryAndYear(raw).first
    }

    private fun generateCandidateQueries(query: String): List<String> {
        val candidates = mutableListOf<String>()
        candidates.add(query)

        val words = query.split(" ").filter { it.isNotBlank() }
        if (words.size > 1) {
            val leadingIgnored = setOf("cebray", "cbr", "shop", "all", "new", "top", "hd", "the", "a")
            if (words.first().lowercase() in leadingIgnored) {
                candidates.add(words.drop(1).joinToString(" "))
            }
            if (words.size >= 3) {
                candidates.add(words.take(words.size - 1).joinToString(" "))
            }
        }
        return candidates.distinct()
    }

    private fun normalizeIndicDigits(input: String): String {
        val bengaliDigits = "০১২৩৪৫৬৭৮৯"
        val devanagariDigits = "०१२३४५६७८९"
        val sb = StringBuilder()
        for (ch in input) {
            val bIdx = bengaliDigits.indexOf(ch)
            val dIdx = devanagariDigits.indexOf(ch)
            when {
                bIdx != -1 -> sb.append(bIdx)
                dIdx != -1 -> sb.append(dIdx)
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun isTitleSimilar(query: String, candidate: String): Boolean {
        val stopWords = setOf("the", "a", "an", "of", "and", "in", "on", "at", "to", "for", "with", "by", "film", "movie", "part")
        val qTokens = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() && it !in stopWords }
        val cTokens = candidate.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() && it !in stopWords }

        if (qTokens.isEmpty() || cTokens.isEmpty()) return false

        var matches = 0
        for (q in qTokens) {
            if (cTokens.any { c -> c == q || (c.length > 3 && q.length > 3 && (c.contains(q) || q.contains(c))) }) {
                matches++
            }
        }
        return (matches.toDouble() / qTokens.size.toDouble()) >= 0.35
    }

    fun isTitleDirty(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        val lower = title.lowercase()
        val dirtyMarkers = listOf(
            "cebray", "cbr", "1080p", "720p", "2160p", "480p", "bluray", "imax", "10bit", "8bit",
            "x265", "x264", "hevc", "web-dl", "webrip", "webri", "amzn", "aac",
            "1-world", "galaxyrg", "1400mb", "hindi", "bengali", "bollywood",
            "yts", "yify", "rarbg", "hub4u", "mlsbd", "1337x", "fbm", "wmr",
            "s01", "e01", "upmix", "print"
        )
        return dirtyMarkers.any { lower.contains(it) } || lower.contains("@")
    }

    fun detectMovieLanguage(item: DesktopMediaItem?): String {
        if (item == null) return "English"
        val lang = item.language ?: ""
        if (lang.isNotBlank() && lang in listOf("Hindi", "Bengali", "English")) {
            return lang
        }
        return deduceLanguageFromTitle(item.title ?: "")
    }

    fun detectLanguageFromWikiText(text: String): String? {
        val lower = text.lowercase()
        return when {
            Regex("(?i)\\b(bengali-language|bengali film|bengali cinema)\\b").containsMatchIn(lower) -> "Bengali"
            Regex("(?i)\\b(hindi-language|hindi film|hindi cinema|bollywood)\\b").containsMatchIn(lower) -> "Hindi"
            Regex("(?i)\\b(tamil-language|tamil film|tamil cinema|kollywood)\\b").containsMatchIn(lower) -> "Tamil"
            Regex("(?i)\\b(telugu-language|telugu film|telugu cinema|tollywood)\\b").containsMatchIn(lower) -> "Telugu"
            Regex("(?i)\\b(malayalam-language|malayalam film|malayalam cinema|mollywood)\\b").containsMatchIn(lower) -> "Malayalam"
            Regex("(?i)\\b(kannada-language|kannada film|sandalwood)\\b").containsMatchIn(lower) -> "Kannada"
            Regex("(?i)\\b(marathi-language|marathi film)\\b").containsMatchIn(lower) -> "Marathi"
            Regex("(?i)\\b(english-language|american film|british film|hollywood|canadian film|australian film)\\b").containsMatchIn(lower) -> "English"
            else -> null
        }
    }

    fun deduceLanguageFromTitle(title: String?): String {
        if (title.isNullOrBlank()) return "English"
        val lower = title.lowercase().trim()

        if (Regex("[\\u0980-\\u09FF]").containsMatchIn(title)) return "Bengali"
        if (Regex("[\\u0900-\\u097F]").containsMatchIn(title)) return "Hindi"

        val bengaliTitles = setOf(
            "dwitiyo purush", "alinagarer golokdhadha", "asche abar shabor", "atthoi",
            "murder by the sea", "eagoler chokh", "killbill society", "sonar kella",
            "shri swapankumarer badami hydrarer kobor", "bhooter bhabishyat", "chander pahar",
            "baishe srabon", "dawshom awbotaar", "tekka", "kishmish", "ballabhpurer roopkotha"
        )
        if (bengaliTitles.contains(lower)) return "Bengali"

        val hindiTitles = setOf(
            "12th fail", "sanju", "special 26", "phantom", "agent vinod", "atrangi re",
            "dunki", "half girlfriend", "kalki 2898 ad", "rustom", "bade miyan chote miyan",
            "bhagwat chapter one raakshas", "bhool chuk maaf", "madras cafe", "dhurandhar",
            "dhamaal", "dhamaal 4", "total dhamaal", "double dhamaal", "ikka", "raees",
            "stree", "stree 2", "pushpa", "pushpa 2", "animal", "jawan", "pathaan",
            "war", "war 2", "kabir singh", "uri", "article 15", "andhadhun", "badhaai ho",
            "chhichhore", "kesari", "brahmastra", "rocky aur rani", "jogi", "heropanti",
            "baaghi", "baaghi 2", "baaghi 3", "tiger zinda hai", "sultan", "dangal",
            "pk", "3 idiots", "dil dhadakne do", "dilwale", "kapoor and sons",
            "shershaah", "bhuj", "bell bottom", "sooryavanshi", "simba", "simmba"
        )
        if (hindiTitles.contains(lower)) return "Hindi"

        val englishTitles = setOf(
            "dune", "dune: part one", "dune part one", "dune: part two", "dune part two",
            "top gun maverick", "top gun: maverick", "crime 101", "batman begins",
            "the vast of night", "the endless", "jack reacher", "glass onion",
            "6 underground", "deadpool and wolverine", "deadpool & wolverine",
            "now you see me", "oppenheimer", "inception", "mission impossible",
            "mission: impossible"
        )
        if (englishTitles.contains(lower)) return "English"

        return "English"
    }

    fun extractGenresFromText(text: String): String {
        if (text.isBlank()) return ""
        val lead = text.take(380).lowercase()

        val matched = LinkedHashSet<String>()
        if (lead.contains("biographical") || lead.contains("biopic") || lead.contains("biography")) matched.add("Biography")
        if (lead.contains("heist") || lead.contains("gangster") || lead.contains("neo-noir") || lead.contains("crime")) matched.add("Crime")
        if (lead.contains("mystery") || lead.contains("detective") || lead.contains("whodunit")) matched.add("Mystery")
        if (lead.contains("psychological") || lead.contains("suspense") || lead.contains("thriller")) matched.add("Thriller")
        if (lead.contains("sci-fi") || lead.contains("science fiction")) matched.add("Sci-Fi")
        if (lead.contains("horror") || lead.contains("slasher")) matched.add("Horror")
        if (lead.contains("fantasy") || lead.contains("mytholog")) matched.add("Fantasy")
        if (lead.contains("comedy") || lead.contains("satir")) matched.add("Comedy")
        if (lead.contains("romantic") || lead.contains("romance")) matched.add("Romance")
        if (lead.contains("action") || lead.contains("martial arts") || lead.contains("superhero") || lead.contains("spy")) matched.add("Action")
        if (lead.contains("adventure") || lead.contains("survival")) matched.add("Adventure")
        if (lead.contains("historical") || lead.contains("period") || lead.contains("war")) matched.add("History")
        if (lead.contains("drama")) matched.add("Drama")

        return if (matched.isNotEmpty()) matched.take(3).joinToString(" • ") else ""
    }

    private fun cleanHtml(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&apos;", "'")
            .replace("&#x2F;", "/")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .trim()
    }

    private fun cleanSeriesQuery(rawTitle: String): String {
        var text = rawTitle.substringBeforeLast('.')
        val noiseTags = listOf(
            "(?i)\\b(2160p|1080p|720p|480p|4K|UHD|FHD|WEB-?DL|WEBRip|BluRay|BRRip|HDR|DV|IMAX)\\b",
            "(?i)\\b(x264|x265|HEVC|AVC|10bit|8bit|AAC|AC3|EAC3|DDP?5\\.1|Atmos|DTS)\\b",
            "(?i)\\b(Hindi|English|Tamil|Telugu|Dual\\s*Audio|Multi\\s*Audio|ESubs?|Sub|Dubbed)\\b",
            "(?i)\\b(MoviesMod|MoviesVerse|VegaMovies|Pahe|YTS|YIFY|PSA|GalaxyTV|KatmovieHD|MkvCinemas)\\b",
            "(?i)\\b(s\\d+|season\\s*\\d+|complete)\\b.*",
            "(?i)\\[[^\\]]*\\]", "(?i)\\([^\\)]*\\)"
        )
        for (tag in noiseTags) {
            text = text.replace(Regex(tag), "")
        }
        return text.replace('.', ' ').replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Resolves TV Series metadata using TVMaze search with smart disambiguation.
     * Prevents picking old miniseries (e.g. 1990 BBC House of Cards) when the user has modern series (2013 Netflix).
     */
    suspend fun resolveSeries(rawTitle: String, episodeCountHint: Int = 0): SeriesOnlineMeta = withContext(Dispatchers.IO) {
        val cleanQuery = cleanSeriesQuery(rawTitle)
        if (cleanQuery.isBlank()) return@withContext fallbackSeries(rawTitle)

        seriesCache[cleanQuery.lowercase()]?.let { return@withContext it }

        try {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val endpoint = "https://api.tvmaze.com/search/shows?q=$encoded"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Clotube-Windows/1.0")
            }

            if (conn.responseCode in 200..299) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val rootArray = JsonParser.parseString(json).asJsonArray

                if (rootArray.size() > 0) {
                    var bestShowObj: JsonObject? = null
                    var highestScore = -1000.0

                    for (elem in rootArray) {
                        val item = elem.asJsonObject
                        val show = item.getAsJsonObject("show") ?: continue
                        val showName = show.get("name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val weight = show.get("weight")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
                        val premiered = show.get("premiered")?.takeIf { !it.isJsonNull }?.asString ?: ""

                        var score = weight

                        // Exact title match bonus
                        if (showName.equals(cleanQuery, ignoreCase = true)) {
                            score += 50.0
                        }

                        // Check year hint if folder has year (e.g. "House of Cards 2013")
                        val yearMatcher = Pattern.compile("\\b(19\\d\\d|20\\d\\d)\\b").matcher(rawTitle)
                        if (yearMatcher.find()) {
                            val year = yearMatcher.group()
                            if (premiered.startsWith(year)) {
                                score += 100.0
                            }
                        }

                        // Disambiguation for episode count hint:
                        // If episodeCountHint > 4 and show premiered in the 1990s without explicit year hint in title,
                        // penalize older miniseries in favor of modern long-running shows
                        if (episodeCountHint > 4) {
                            if (premiered.startsWith("19") && !rawTitle.contains("19")) {
                                score -= 40.0
                            }
                            val webChannel = show.get("webChannel")?.takeIf { !it.isJsonNull }
                            if (webChannel != null) {
                                score += 20.0
                            }
                        }

                        if (score > highestScore) {
                            highestScore = score
                            bestShowObj = show
                        }
                    }

                    val chosenShow = bestShowObj ?: rootArray[0].asJsonObject.getAsJsonObject("show")
                    if (chosenShow != null) {
                        val id = chosenShow.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: -1L
                        val title = chosenShow.get("name")?.takeIf { !it.isJsonNull }?.asString ?: cleanQuery
                        val genresArr = chosenShow.getAsJsonArray("genres")
                        val genres = if (genresArr != null && genresArr.size() > 0) {
                            genresArr.joinToString(" • ") { it.asString }
                        } else "Drama • Thriller"

                        val imageElement = chosenShow.get("image")
                        val imageObj = if (imageElement != null && !imageElement.isJsonNull && imageElement.isJsonObject) {
                            imageElement.asJsonObject
                        } else null
                        var poster = imageObj?.get("original")?.takeIf { !it.isJsonNull }?.asString 
                            ?: imageObj?.get("medium")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        if (poster.startsWith("http://")) {
                            poster = poster.replace("http://", "https://")
                        }
                        val premiered = chosenShow.get("premiered")?.takeIf { !it.isJsonNull }?.asString?.take(4) ?: "2024"

                        // Fallback to IMDb or Wikipedia if TVMaze has no poster image
                        if (poster.isBlank()) {
                            val yearHint = premiered.takeIf { it.isNotBlank() && it != "2024" }
                            val imdb = fetchFromImdb(title, yearHint) ?: fetchFromImdb(cleanQuery, null)
                            if (imdb != null && imdb.posterUrl.isNotBlank()) {
                                poster = imdb.posterUrl
                            } else {
                                val wiki = fetchFromWikipedia("$title (TV series)")
                                    ?: fetchFromWikipedia(title)
                                    ?: fetchFromWikipedia(cleanQuery)
                                if (wiki != null && wiki.posterUrl.isNotBlank()) {
                                    poster = wiki.posterUrl
                                }
                            }
                        }

                        val summaryRaw = chosenShow.get("summary")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val synopsis = cleanHtml(summaryRaw)

                        val meta = SeriesOnlineMeta(
                            officialTitle = title,
                            tvMazeId = id,
                            posterUrl = poster,
                            backdropUrl = poster,
                            releaseYear = premiered,
                            genres = genres,
                            synopsis = synopsis
                        )
                        seriesCache[cleanQuery.lowercase()] = meta
                        return@withContext meta
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[DesktopOnlineMetadataResolver] Failed to resolve series for $rawTitle: ${e.message}")
        }

        val fallback = fallbackSeries(cleanQuery)
        val resolvedFallback = if (fallback.posterUrl.isBlank()) {
            val imdb = fetchFromImdb(cleanQuery, null)
            if (imdb != null && imdb.posterUrl.isNotBlank()) {
                fallback.copy(
                    posterUrl = imdb.posterUrl,
                    backdropUrl = imdb.posterUrl,
                    officialTitle = imdb.officialTitle.ifBlank { fallback.officialTitle }
                )
            } else {
                val wiki = fetchFromWikipedia("$cleanQuery (TV series)")
                    ?: fetchFromWikipedia(cleanQuery)
                if (wiki != null && wiki.posterUrl.isNotBlank()) {
                    fallback.copy(
                        posterUrl = wiki.posterUrl,
                        backdropUrl = wiki.posterUrl,
                        officialTitle = wiki.officialTitle.ifBlank { fallback.officialTitle }
                    )
                } else {
                    fallback
                }
            }
        } else {
            fallback
        }
        seriesCache[cleanQuery.lowercase()] = resolvedFallback
        resolvedFallback
    }

    suspend fun resolveSeries(rawTitle: String): SeriesOnlineMeta = resolveSeries(rawTitle, 0)

    /**
     * Downloads the entire episode guide for a series in a single network request.
     */
    suspend fun fetchShowEpisodesBulk(tvMazeShowId: Long): Map<Pair<Int, Int>, EpisodeOnlineMeta> = withContext(Dispatchers.IO) {
        if (tvMazeShowId <= 0L) return@withContext emptyMap()
        showEpisodesCache[tvMazeShowId]?.let { return@withContext it }

        val map = mutableMapOf<Pair<Int, Int>, EpisodeOnlineMeta>()
        try {
            val endpoint = "https://api.tvmaze.com/shows/$tvMazeShowId/episodes?specials=1"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "Clotube-Windows/1.0")
            }

            if (conn.responseCode in 200..299) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val array = JsonParser.parseString(json).asJsonArray

                for (elem in array) {
                    val epObj = elem.asJsonObject
                    val season = epObj.get("season")?.takeIf { !it.isJsonNull }?.asInt ?: 1
                    val number = epObj.get("number")?.takeIf { !it.isJsonNull }?.asInt ?: 1
                    val epName = epObj.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: "Episode $number"
                    val runtime = epObj.get("runtime")?.takeIf { !it.isJsonNull }?.asLong ?: 45L
                    
                    // Safe JsonNull guard: prevents ClassCastException when TVMaze has no episode still
                    val imageElement = epObj.get("image")
                    val imageObj = if (imageElement != null && !imageElement.isJsonNull && imageElement.isJsonObject) {
                        imageElement.asJsonObject
                    } else {
                        null
                    }
                    var thumb = imageObj?.get("original")?.takeIf { !it.isJsonNull }?.asString 
                        ?: imageObj?.get("medium")?.takeIf { !it.isJsonNull }?.asString 
                        ?: ""
                    
                    // Upgrade HTTP to HTTPS for secure CDN retrieval
                    if (thumb.startsWith("http://")) {
                        thumb = thumb.replace("http://", "https://")
                    }

                    val summaryRaw = epObj.get("summary")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val synopsis = cleanHtml(summaryRaw)

                    if (season > 0 && number > 0 && epName.isNotBlank()) {
                        map[Pair(season, number)] = EpisodeOnlineMeta(
                            officialTitle = epName,
                            thumbnailUrl = thumb,
                            durationMinutes = runtime,
                            synopsis = synopsis,
                            seasonNumber = season,
                            episodeNumber = number
                        )
                    }
                }
                println("[DesktopOnlineMetadataResolver] Bulk-indexed ${map.size} official episodes from TVMaze (Show ID: $tvMazeShowId).")
            }
        } catch (e: Exception) {
            System.err.println("[DesktopOnlineMetadataResolver] Failed bulk episode fetch for show $tvMazeShowId: ${e.message}")
        }

        showEpisodesCache[tvMazeShowId] = map
        map
    }

    suspend fun fetchAllEpisodesForShow(tvMazeShowId: Long): Map<Pair<Int, Int>, EpisodeOnlineMeta> =
        fetchShowEpisodesBulk(tvMazeShowId)

    /**
     * Aggressively scrubs torrent/scene tags from raw filenames so ugly tags NEVER appear on screen.
     */
    fun cleanEpisodeFallbackTitle(rawFileName: String, episodeNumber: Int, seriesTitle: String): String {
        var text = rawFileName.substringBeforeLast('.')

        val garbagePatterns = listOf(
            "(?i)\\b(2160p|1080p|720p|480p|4K|UHD|FHD|WEB-?DL|WEBRip|BluRay|BRRip|HDR|DV|IMAX)\\b",
            "(?i)\\b(x264|x265|HEVC|AVC|10bit|8bit|AAC|AC3|EAC3|DDP?5\\.1|Atmos|DTS)\\b",
            "(?i)\\b(Hindi|English|Tamil|Telugu|Dual\\s*Audio|Multi\\s*Audio|ESubs?|Sub|Dubbed)\\b",
            "(?i)\\b(MoviesMod|MoviesVerse|VegaMovies|Pahe|YTS|YIFY|PSA|GalaxyTV|KatmovieHD|MkvCinemas)\\b",
            "(?i)\\[[^\\]]*\\]", "(?i)\\([^\\)]*\\)",
            "(?i)\\bS\\d{1,2}\\s*E\\d{1,3}\\b", "(?i)\\b\\d{1,2}x\\d{1,3}\\b", "(?i)\\bEpisode\\s*\\d+\\b", "(?i)\\bEp\\s*\\d+\\b"
        )

        for (pattern in garbagePatterns) {
            text = text.replace(Regex(pattern), "")
        }

        // Remove the series title if repeated in filename
        if (seriesTitle.isNotBlank()) {
            text = text.replace(Regex("(?i)" + Pattern.quote(seriesTitle)), "")
        }

        text = text.replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        // If nothing clean is left, return "Episode X"
        return if (text.isBlank() || text.length <= 1) {
            "Episode $episodeNumber"
        } else {
            text
        }
    }

    /**
     * Resolves authentic episode title, duration, synopsis, and poster artwork from TVMaze.
     */
    suspend fun resolveEpisode(
        seriesTitle: String,
        seasonNumber: Int,
        episodeNumber: Int,
        rawFileName: String
    ): EpisodeMetadata = resolveEpisode(
        seriesName = seriesTitle,
        tvMazeSeriesId = -1L,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        rawFileName = rawFileName
    )

    suspend fun resolveEpisode(
        seriesName: String = "",
        tvMazeSeriesId: Long = -1L,
        seasonNumber: Int,
        episodeNumber: Int,
        rawFileName: String,
        seriesTitle: String = seriesName,
        tvMazeId: Long = tvMazeSeriesId
    ): EpisodeMetadata = withContext(Dispatchers.IO) {
        val effectiveTitle = seriesName.ifBlank { seriesTitle }
        val effectiveShowId = if (tvMazeSeriesId > 0L) tvMazeSeriesId else if (tvMazeId > 0L) tvMazeId else resolveSeries(effectiveTitle).tvMazeId
        val cacheKey = "$effectiveShowId:S${seasonNumber}E${episodeNumber}"
        episodeCache[cacheKey]?.let { return@withContext it }

        var tvMazeMeta: EpisodeMetadata? = null

        if (effectiveShowId > 0L) {
            var episodesMap = showEpisodesCache[effectiveShowId]
            if (episodesMap == null) {
                episodesMap = fetchAllEpisodesForShow(effectiveShowId)
                showEpisodesCache[effectiveShowId] = episodesMap
            }
            tvMazeMeta = episodesMap[Pair(seasonNumber, episodeNumber)]

            // If bulk map didn't have it, try single episode by number
            if (tvMazeMeta == null) {
                try {
                    val endpoint = "https://api.tvmaze.com/shows/$effectiveShowId/episodebynumber?season=$seasonNumber&number=$episodeNumber"
                    val conn = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                        setRequestProperty("User-Agent", "Clotube-Desktop/1.0")
                    }
                    if (conn.responseCode in 200..299) {
                        val json = conn.inputStream.bufferedReader().use { it.readText() }
                        val root = JsonParser.parseString(json).asJsonObject

                        val epName = root.get("name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val runtime = root.get("runtime")?.takeIf { !it.isJsonNull }?.asLong ?: 45L
                        val rawSummary = root.get("summary")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val synopsis = cleanHtml(rawSummary)

                        val imageElement = root.get("image")
                        val imageObj = if (imageElement != null && !imageElement.isJsonNull && imageElement.isJsonObject) {
                            imageElement.asJsonObject
                        } else null
                        var thumbUrl = imageObj?.get("original")?.takeIf { !it.isJsonNull }?.asString
                            ?: imageObj?.get("medium")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        if (thumbUrl.startsWith("http://")) {
                            thumbUrl = thumbUrl.replace("http://", "https://")
                        }

                        if (epName.isNotBlank() || thumbUrl.isNotBlank()) {
                            tvMazeMeta = EpisodeMetadata(
                                officialTitle = epName.ifBlank { cleanEpisodeTitleFromFileName(rawFileName, seasonNumber, episodeNumber) },
                                durationMinutes = runtime,
                                synopsis = synopsis,
                                thumbnailUrl = thumbUrl,
                                seasonNumber = seasonNumber,
                                episodeNumber = episodeNumber
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Check if TVMaze already provided a valid, non-blank thumbnail still
        if (tvMazeMeta != null && tvMazeMeta.thumbnailUrl.isNotBlank()) {
            episodeCache[cacheKey] = tvMazeMeta
            return@withContext tvMazeMeta
        }

        // ====================================================================
        // SECONDARY FALLBACK: Resolve Episode Still from TMDB
        // ====================================================================
        val tmdbStill = fetchTmdbEpisodeStill(effectiveTitle, seasonNumber, episodeNumber)
        if (tmdbStill.isNotBlank()) {
            val resolved = (tvMazeMeta ?: EpisodeMetadata(
                officialTitle = cleanEpisodeTitleFromFileName(rawFileName, seasonNumber, episodeNumber),
                thumbnailUrl = tmdbStill,
                durationMinutes = 0L,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber
            )).copy(thumbnailUrl = tmdbStill)
            episodeCache[cacheKey] = resolved
            return@withContext resolved
        }

        // Return TVMaze metadata (or filename fallback) if TMDB also has no still
        val fallbackTitle = cleanEpisodeTitleFromFileName(rawFileName, seasonNumber, episodeNumber)
        val finalMeta = tvMazeMeta ?: EpisodeMetadata(
            officialTitle = fallbackTitle,
            thumbnailUrl = "",
            durationMinutes = 0L,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber
        )
        episodeCache[cacheKey] = finalMeta
        finalMeta
    }

    /**
     * Resolves an episode still from TMDB, caching show IDs and entire seasons in memory.
     */
    fun fetchTmdbEpisodeStill(
        seriesName: String,
        seasonNumber: Int,
        episodeNumber: Int
    ): String {
        try {
            val cleanTitle = cleanSeriesTitle(seriesName)
            val showId = tmdbShowIdCache.computeIfAbsent(cleanTitle.lowercase()) {
                val fetched = fetchTmdbShowId(cleanTitle)
                if (fetched > 0L) persistTmdbCacheAsync()
                fetched
            }
            if (showId <= 0L) return ""

            val seasonKey = Pair(showId, seasonNumber)
            val seasonStills = tmdbSeasonStillsCache.computeIfAbsent(seasonKey) {
                val fetched = fetchTmdbSeasonStills(showId, seasonNumber)
                if (fetched.isNotEmpty()) persistTmdbCacheAsync()
                fetched
            }

            return seasonStills[episodeNumber] ?: ""
        } catch (e: Exception) {
            return ""
        }
    }

    /**
     * Finds the TMDB Show ID via /search/tv.
     */
    fun fetchTmdbShowId(seriesTitle: String): Long {
        var connection: HttpURLConnection? = null
        try {
            val encoded = URLEncoder.encode(seriesTitle, "UTF-8")
            val endpoint = "https://api.themoviedb.org/3/search/tv?api_key=$TMDB_API_KEY&query=$encoded"
            connection = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(jsonText).asJsonObject
                val results = root.getAsJsonArray("results")
                if (results != null && results.size() > 0) {
                    for (i in 0 until results.size()) {
                        val item = results.get(i).asJsonObject
                        val name = item.get("name")?.asString ?: ""
                        if (isTitleSimilar(seriesTitle, name)) {
                            return item.get("id")?.asLong ?: -1L
                        }
                    }
                    return results.get(0).asJsonObject.get("id")?.asLong ?: -1L
                }
            }
        } catch (_: Exception) {
        } finally {
            connection?.disconnect()
        }
        return -1L
    }

    /**
     * Batch-fetches all episode stills for an entire season in a single request.
     */
    fun fetchTmdbSeasonStills(showId: Long, seasonNumber: Int): Map<Int, String> {
        val resultMap = mutableMapOf<Int, String>()
        var connection: HttpURLConnection? = null
        try {
            val endpoint = "https://api.themoviedb.org/3/tv/$showId/season/$seasonNumber?api_key=$TMDB_API_KEY"
            connection = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(jsonText).asJsonObject
                val episodesArray = root.getAsJsonArray("episodes") ?: return emptyMap()

                for (i in 0 until episodesArray.size()) {
                    val epObj = episodesArray.get(i).asJsonObject
                    val epNum = epObj.get("episode_number")?.asInt ?: -1
                    val stillPath = epObj.get("still_path")?.takeIf { !it.isJsonNull }?.asString ?: ""

                    if (epNum > 0 && stillPath.isNotBlank()) {
                        resultMap[epNum] = "$TMDB_IMAGE_BASE$stillPath"
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            connection?.disconnect()
        }
        return resultMap
    }

    private fun cleanEpisodeFileName(fileName: String, episodeNumber: Int): String {
        return cleanEpisodeFallbackTitle(fileName, episodeNumber, "")
    }

    fun cleanSeriesTitle(raw: String): String = cleanSeriesQuery(raw)

    fun cleanEpisodeTitleFromFileName(rawFileName: String, seasonNumber: Int, episodeNumber: Int): String {
        return cleanEpisodeFallbackTitle(rawFileName, episodeNumber, "")
    }

    private fun fallbackSeries(rawTitle: String) = SeriesOnlineMeta(
        officialTitle = cleanSeriesQuery(rawTitle).ifBlank { rawTitle },
        tvMazeId = -1L,
        posterUrl = "",
        backdropUrl = "",
        releaseYear = "2024",
        genres = "Series",
        synopsis = "Streamed directly from your private Google Drive collection."
    )

    fun parseSeasonAndEpisodeNumbers(fileName: String): Pair<Int, Int>? {
        val pattern = Pattern.compile("(?i)s(\\d{1,2})e(\\d{1,2})")
        val matcher = pattern.matcher(fileName)
        if (matcher.find()) {
            return Pair(matcher.group(1)?.toIntOrNull() ?: 1, matcher.group(2)?.toIntOrNull() ?: 1)
        }
        val altPattern = Pattern.compile("(?i)(\\d{1,2})x(\\d{1,2})")
        val altMatcher = altPattern.matcher(fileName)
        if (altMatcher.find()) {
            return Pair(altMatcher.group(1)?.toIntOrNull() ?: 1, altMatcher.group(2)?.toIntOrNull() ?: 1)
        }
        return null
    }

    /**
     * Extracts the integer season number from a season folder name.
     * Handles patterns: "Season 1", "Season 02", "S1", "S03", "Season - 2", "Series 2", "Specials" (0).
     */
    fun parseSeasonNumberFromFolder(folderName: String): Int {
        val clean = folderName.trim()
        if (clean.contains("special", ignoreCase = true) || clean.contains("extra", ignoreCase = true)) {
            return 0
        }

        val pattern = java.util.regex.Pattern.compile("""(?i)(?:season|series|s)[\s._-]*(\d{1,2})""")
        val matcher = pattern.matcher(clean)
        if (matcher.find()) {
            return matcher.group(1).toIntOrNull() ?: 1
        }

        val standaloneDigits = java.util.regex.Pattern.compile("""\b\d{1,2}\b""").matcher(clean)
        if (standaloneDigits.find()) {
            return standaloneDigits.group().toIntOrNull() ?: 1
        }

        return 1
    }

    /**
     * Extracts the episode number from an episode video filename.
     * Handles patterns:
     * - S01E05 / s1e5
     * - 1x05 / 01x05
     * - Episode 05 / Ep 5 / Ep.05
     * - Leading/trailing numbers: "01 - Pilot.mkv", "01.mkv", "Pilot - 05.mp4"
     */
    fun parseEpisodeNumberFromFileName(fileName: String, seasonNumber: Int = 1): Int {
        val nameWithoutExt = fileName.substringBeforeLast('.')
        // Strip out common resolution and codec noise tags
        val stripped = nameWithoutExt.replace(
            Regex("""(?i)\b(2160p|1080p|720p|480p|x264|x265|hevc|web-dl|bluray|aac|ac3|dts|remux|h264|hdr|uhd)\b"""),
            ""
        )

        // 1. Check for standard SxxExx or s1e01
        val seMatch = Regex("""(?i)s\d{1,2}[\s._-]*e(\d{1,3})""").find(stripped)
        if (seMatch != null) {
            return seMatch.groupValues[1].toIntOrNull() ?: 1
        }

        // 2. Check for 1x05 or 01x05
        val crossMatch = Regex("""(?i)\b\d{1,2}x(\d{1,3})\b""").find(stripped)
        if (crossMatch != null) {
            return crossMatch.groupValues[1].toIntOrNull() ?: 1
        }

        // 3. Check for "Episode 05", "Ep 05", "EP.05"
        val epWordMatch = Regex("""(?i)\b(?:episode|ep|e)[\s._-]*(\d{1,3})\b""").find(stripped)
        if (epWordMatch != null) {
            return epWordMatch.groupValues[1].toIntOrNull() ?: 1
        }

        // 4. Check for leading track/episode number like "01 - Pilot" or "01. Pilot"
        val leadingMatch = Regex("""^\s*(\d{1,3})\s*[-._\s]""").find(stripped)
        if (leadingMatch != null) {
            return leadingMatch.groupValues[1].toIntOrNull() ?: 1
        }

        // 5. Check for trailing episode number like "Pilot - 01"
        val trailingMatch = Regex("""[-._\s](\d{1,3})\s*$""").find(stripped)
        if (trailingMatch != null) {
            return trailingMatch.groupValues[1].toIntOrNull() ?: 1
        }

        return 1
    }

    private fun toTitleCase(input: String): String {
        val minorWords = setOf("a", "an", "the", "and", "but", "or", "for", "nor", "on", "at", "to", "from", "by", "of", "in")
        return input.split(" ").filter { it.isNotBlank() }.mapIndexed { index, word ->
            val lower = word.lowercase()
            if (index == 0 || lower !in minorWords) {
                lower.replaceFirstChar { it.uppercase() }
            } else {
                lower
            }
        }.joinToString(" ")
    }
}
