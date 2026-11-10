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
    val language: String = "English"
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
            posterUrl = "https://m.media-amazon.com/images/M/MV5BN2FjNmEyNWMtYzM0ZS00NjIyLTg5YzYtYThlMGVjNzE1OGViXkEyXkFqcGc@._V1_.jpg",
            releaseYear = "2021",
            genre = "Sci-Fi • Adventure • Action",
            language = "English"
        ),
        "dune part one" to MovieMetadata(
            officialTitle = "Dune: Part One",
            posterUrl = "https://m.media-amazon.com/images/M/MV5BN2FjNmEyNWMtYzM0ZS00NjIyLTg5YzYtYThlMGVjNzE1OGViXkEyXkFqcGc@._V1_.jpg",
            releaseYear = "2021",
            genre = "Sci-Fi • Adventure • Action",
            language = "English"
        ),
        "dune part 1" to MovieMetadata(
            officialTitle = "Dune: Part One",
            posterUrl = "https://m.media-amazon.com/images/M/MV5BN2FjNmEyNWMtYzM0ZS00NjIyLTg5YzYtYThlMGVjNzE1OGViXkEyXkFqcGc@._V1_.jpg",
            releaseYear = "2021",
            genre = "Sci-Fi • Adventure • Action",
            language = "English"
        ),
        "dune 2021" to MovieMetadata(
            officialTitle = "Dune: Part One",
            posterUrl = "https://m.media-amazon.com/images/M/MV5BN2FjNmEyNWMtYzM0ZS00NjIyLTg5YzYtYThlMGVjNzE1OGViXkEyXkFqcGc@._V1_.jpg",
            releaseYear = "2021",
            genre = "Sci-Fi • Adventure • Action",
            language = "English"
        ),
        "dune part two" to MovieMetadata(
            officialTitle = "Dune: Part Two",
            posterUrl = "https://m.media-amazon.com/images/M/MV5BNTc0YmQxMjEtODI5MC00NjFiLTlkMWUtOGQ5NjFmYWUyZGJhXkEyXkFqcGc@._V1_.jpg",
            releaseYear = "2024",
            genre = "Sci-Fi • Adventure • Action",
            language = "English"
        ),
        "dune part 2" to MovieMetadata(
            officialTitle = "Dune: Part Two",
            posterUrl = "https://m.media-amazon.com/images/M/MV5BNTc0YmQxMjEtODI5MC00NjFiLTlkMWUtOGQ5NjFmYWUyZGJhXkEyXkFqcGc@._V1_.jpg",
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

    suspend fun resolveMovie(rawFileName: String): MovieMetadata = withContext(Dispatchers.IO) {
        val trimmed = rawFileName.trim()
        movieCache[trimmed]?.let { return@withContext it }

        val syncMeta = resolveMovieSync(trimmed)
        if (CURATED_TITLES.values.any { it.officialTitle == syncMeta.officialTitle } && syncMeta.posterUrl.isNotBlank()) {
            movieCache[trimmed] = syncMeta
            return@withContext syncMeta
        }

        val (cleanQuery, releaseYear) = extractMovieQueryAndYear(trimmed)
        val searchCandidates = generateCandidateQueries(syncMeta.officialTitle.ifBlank { cleanQuery })

        var matchedTitle: String? = syncMeta.officialTitle.ifBlank { null }
        var matchedPoster = syncMeta.posterUrl
        var matchedYear = syncMeta.releaseYear.ifBlank { releaseYear ?: "" }
        var matchedGenre: String? = if (syncMeta.genre != "Drama • Cinema") syncMeta.genre else null
        var matchedLanguage: String? = if (syncMeta.language != "Unknown") syncMeta.language else null

        // 1. Query IMDb Suggestion API for official title and live theatrical poster
        for (candidate in searchCandidates) {
            val imdb = fetchFromImdb(candidate, matchedYear)
            if (imdb != null) {
                if (matchedTitle == null) matchedTitle = imdb.officialTitle
                if (imdb.posterUrl.isNotBlank()) matchedPoster = imdb.posterUrl
                if (imdb.releaseYear.isNotBlank() && matchedYear.isBlank()) matchedYear = imdb.releaseYear
                break
            }
        }

        // 2. Query TMDB Movie API (High-Resolution Theatrical Posters & Genres)
        if (matchedPoster.isBlank()) {
            for (candidate in searchCandidates) {
                val tmdb = fetchFromTmdbMovie(candidate, releaseYear ?: matchedYear)
                if (tmdb != null) {
                    if (matchedTitle == null) matchedTitle = tmdb.officialTitle
                    if (matchedPoster.isBlank() && tmdb.posterUrl.isNotBlank()) matchedPoster = tmdb.posterUrl
                    if (matchedYear.isBlank() && tmdb.releaseYear.isNotBlank()) matchedYear = tmdb.releaseYear
                    if (matchedGenre.isNullOrBlank() && tmdb.genre.isNotBlank() && tmdb.genre != "Drama") {
                        matchedGenre = tmdb.genre
                    }
                    break
                }
            }
        }

        val queryForWiki = matchedTitle ?: searchCandidates.firstOrNull() ?: cleanQuery

        // 3. Query Wikipedia API if poster or genre is missing
        if (matchedPoster.isBlank() || matchedGenre.isNullOrBlank()) {
            val wiki = fetchFromWikipedia(queryForWiki)
            if (wiki != null) {
                if (matchedTitle == null) matchedTitle = wiki.officialTitle
                if (matchedPoster.isBlank() && wiki.posterUrl.isNotBlank()) matchedPoster = wiki.posterUrl
                if (matchedYear.isBlank() && wiki.releaseYear.isNotBlank()) matchedYear = wiki.releaseYear
                if (matchedGenre.isNullOrBlank() && wiki.genre.isNotBlank() && wiki.genre != "Drama") {
                    matchedGenre = wiki.genre
                }
                if (matchedLanguage == null && wiki.language.isNotBlank() && wiki.language != "Unknown") {
                    matchedLanguage = wiki.language
                }
            }
        }

        // 4. Query iTunes API as fallback
        if (matchedPoster.isBlank() || matchedGenre.isNullOrBlank()) {
            val itunes = fetchFromItunes(queryForWiki, matchedYear)
            if (itunes != null) {
                if (matchedTitle == null) matchedTitle = itunes.officialTitle
                if (matchedPoster.isBlank() && itunes.posterUrl.isNotBlank()) matchedPoster = itunes.posterUrl
                if (matchedYear.isBlank() && itunes.releaseYear.isNotBlank()) matchedYear = itunes.releaseYear
                if (matchedGenre.isNullOrBlank() && itunes.genre.isNotBlank() && itunes.genre != "Drama") {
                    matchedGenre = itunes.genre
                }
            }
        }

        val finalTitle = matchedTitle ?: cleanQuery
        val finalGenre = matchedGenre?.ifBlank { null } ?: syncMeta.genre
        val finalLanguage = matchedLanguage ?: deduceLanguageFromTitle(finalTitle)

        val result = MovieMetadata(
            officialTitle = finalTitle,
            posterUrl = matchedPoster,
            releaseYear = matchedYear,
            genre = finalGenre,
            language = finalLanguage
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
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(jsonText).asJsonObject
                val results = root.getAsJsonArray("d") ?: return null

                var bestObj: com.google.gson.JsonObject? = null

                for (i in 0 until results.size()) {
                    val item = results.get(i).asJsonObject
                    val title = item.get("l")?.asString ?: ""
                    val year = item.get("y")?.asInt?.toString() ?: ""
                    val qid = item.get("qid")?.asString ?: ""

                    if (isTitleSimilar(query, title)) {
                        if (!targetYear.isNullOrBlank() && year == targetYear) {
                            bestObj = item
                            break
                        }
                        if (bestObj == null || (qid == "movie" && bestObj.get("qid")?.asString != "movie")) {
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
                        posterUrl = posterUrl.replace(Regex("\\._V1_.*(\\.[a-zA-Z]+)$"), "._V1_$1")
                    }

                    return MovieMetadata(
                        officialTitle = officialTitle,
                        posterUrl = posterUrl,
                        releaseYear = year,
                        genre = ""
                    )
                }
            }
        } catch (_: Exception) {
        } finally {
            connection?.disconnect()
        }
        return null
    }

    /**
     * Resolves movie title, high-res poster (w780), release year, and genres from TMDB.
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
                        genre = cleanGenre
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

                            val parsedLanguage = when {
                                Regex("(?i)\\b(bengali-language|bengali film|bengali cinema)\\b").containsMatchIn(fullText) -> "Bengali"
                                Regex("(?i)\\b(hindi-language|hindi film|hindi cinema|bollywood)\\b").containsMatchIn(fullText) -> "Hindi"
                                Regex("(?i)\\b(english-language|american film|british film|hollywood)\\b").containsMatchIn(fullText) -> "English"
                                else -> "Unknown"
                            }

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
            "dual[-_\\s]*audio|multi[-_\\s]*audio|esubs?|msubs?|subbed|subs?|" +
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
            "bhagwat chapter one raakshas", "bhool chuk maaf", "madras cafe", "dhurandhar"
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
        seriesCache[cleanQuery.lowercase()] = fallback
        fallback
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
