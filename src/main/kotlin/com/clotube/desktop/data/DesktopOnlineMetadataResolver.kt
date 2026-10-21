package com.clotube.desktop.data

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

data class MovieMetadata(
    val officialTitle: String,
    val posterUrl: String = "",
    val releaseYear: String = "",
    val genre: String = "Movie • Cinema"
)

data class SeriesMetadata(
    val tvMazeId: Long,
    val officialTitle: String,
    val posterUrl: String = "",
    val genres: String = "TV Series • Drama"
)

data class EpisodeMetadata(
    val officialTitle: String,
    val thumbnailUrl: String = "",
    val durationMinutes: Long = 0L
)

object DesktopOnlineMetadataResolver {

    private val movieCache = ConcurrentHashMap<String, MovieMetadata>()
    private val seriesCache = ConcurrentHashMap<String, SeriesMetadata>()
    private val showEpisodesCache = ConcurrentHashMap<Long, Map<Pair<Int, Int>, EpisodeMetadata>>()

    // ========================================================================
    // 1. MOVIE METADATA RESOLUTION (IMDb Suggestion API + iTunes Fallback)
    // ========================================================================
    suspend fun resolveMovie(rawFileName: String): MovieMetadata = withContext(Dispatchers.IO) {
        val trimmed = rawFileName.trim()
        movieCache[trimmed]?.let { return@withContext it }

        val (cleanQuery, releaseYear) = extractMovieQueryAndYear(trimmed)
        if (cleanQuery.isBlank()) {
            val fallback = MovieMetadata(officialTitle = toTitleCase(trimmed.substringBeforeLast('.')))
            movieCache[trimmed] = fallback
            return@withContext fallback
        }

        // Engine 1: IMDb Suggestion API (Global catalog: Hollywood, Bollywood, Netflix, Amazon)
        val imdbResult = fetchFromImdb(cleanQuery, releaseYear)
        if (imdbResult != null && imdbResult.posterUrl.isNotBlank()) {
            movieCache[trimmed] = imdbResult
            return@withContext imdbResult
        }

        // Engine 2: iTunes Store API (Secondary)
        val itunesResult = fetchFromItunes(cleanQuery, releaseYear)
        if (itunesResult != null) {
            val mergedResult = if (imdbResult != null && itunesResult.posterUrl.isBlank()) {
                imdbResult.copy(posterUrl = itunesResult.posterUrl)
            } else {
                itunesResult
            }
            movieCache[trimmed] = mergedResult
            return@withContext mergedResult
        }

        // Engine 3: Clean Fallback
        val safeFallback = imdbResult ?: MovieMetadata(
            officialTitle = toTitleCase(cleanQuery),
            posterUrl = "",
            releaseYear = releaseYear ?: "",
            genre = "Movie • Cinema"
        )
        movieCache[trimmed] = safeFallback
        safeFallback
    }

    private fun fetchFromImdb(query: String, targetYear: String?): MovieMetadata? {
        var connection: HttpURLConnection? = null
        try {
            val slug = query.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            if (slug.isBlank()) return null

            val endpoint = "https://v3.sg.media-imdb.com/suggestion/x/$slug.json"
            @Suppress("DEPRECATION")
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(jsonText).asJsonObject
                val results = if (root.has("d") && root.get("d").isJsonArray) root.getAsJsonArray("d") else return null

                var bestObj: com.google.gson.JsonObject? = null

                for (i in 0 until results.size()) {
                    val element = results.get(i)
                    if (!element.isJsonObject) continue
                    val item = element.asJsonObject
                    val title = if (item.has("l") && !item.get("l").isJsonNull) item.get("l").asString else ""
                    val year = if (item.has("y") && !item.get("y").isJsonNull) item.get("y").asInt.toString() else ""

                    if (isTitleSimilar(query, title)) {
                        if (!targetYear.isNullOrBlank() && year == targetYear) {
                            bestObj = item
                            break
                        }
                        if (bestObj == null) {
                            bestObj = item
                        }
                    }
                }

                if (bestObj != null) {
                    val officialTitle = if (bestObj.has("l") && !bestObj.get("l").isJsonNull) bestObj.get("l").asString.trim() else query
                    val year = if (bestObj.has("y") && !bestObj.get("y").isJsonNull) bestObj.get("y").asInt.toString() else ""
                    val imageObj = if (bestObj.has("i") && bestObj.get("i").isJsonObject) bestObj.getAsJsonObject("i") else null
                    var posterUrl = if (imageObj != null && imageObj.has("imageUrl") && !imageObj.get("imageUrl").isJsonNull) imageObj.get("imageUrl").asString else ""

                    // Upgrade IMDb poster to uncompressed high resolution
                    if (posterUrl.contains("._V1_")) {
                        posterUrl = posterUrl.replace(Regex("\\._V1_.*(\\.[a-zA-Z]+)$"), "._V1_$1")
                    }

                    println("IMDb match: '$query' -> '$officialTitle' ($year)")
                    return MovieMetadata(
                        officialTitle = officialTitle,
                        posterUrl = posterUrl,
                        releaseYear = year,
                        genre = "Movie • Cinema"
                    )
                }
            }
        } catch (e: Exception) {
            System.err.println("IMDb lookup failed for '$query': ${e.message}")
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
            @Suppress("DEPRECATION")
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Clotube-Windows/1.0")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(jsonText).asJsonObject
                val results = if (root.has("results") && root.get("results").isJsonArray) root.getAsJsonArray("results") else return null

                var bestMatch: com.google.gson.JsonObject? = null

                for (i in 0 until results.size()) {
                    val element = results.get(i)
                    if (!element.isJsonObject) continue
                    val item = element.asJsonObject
                    val title = if (item.has("trackName") && !item.get("trackName").isJsonNull) item.get("trackName").asString else ""
                    val releaseDate = if (item.has("releaseDate") && !item.get("releaseDate").isJsonNull) item.get("releaseDate").asString else ""

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
                    val officialTitle = if (bestMatch.has("trackName") && !bestMatch.get("trackName").isJsonNull) bestMatch.get("trackName").asString.trim() else query
                    val rawThumb = if (bestMatch.has("artworkUrl100") && !bestMatch.get("artworkUrl100").isJsonNull) bestMatch.get("artworkUrl100").asString else ""
                    val highResPoster = rawThumb.replace("100x100bb.jpg", "600x600bb.jpg")
                    val genre = if (bestMatch.has("primaryGenreName") && !bestMatch.get("primaryGenreName").isJsonNull) bestMatch.get("primaryGenreName").asString else "Cinema"
                    val year = if (bestMatch.has("releaseDate") && !bestMatch.get("releaseDate").isJsonNull) bestMatch.get("releaseDate").asString.take(4) else ""

                    println("iTunes match: '$query' -> '$officialTitle' ($year)")
                    return MovieMetadata(
                        officialTitle = officialTitle,
                        posterUrl = highResPoster,
                        releaseYear = year,
                        genre = "Movie • $genre"
                    )
                }
            }
        } catch (e: Exception) {
            System.err.println("iTunes lookup failed for '$query': ${e.message}")
        } finally {
            connection?.disconnect()
        }
        return null
    }

    // ========================================================================
    // 2. SERIES METADATA RESOLUTION (TVMaze API)
    // ========================================================================
    suspend fun resolveSeries(rawSeriesName: String): SeriesMetadata = withContext(Dispatchers.IO) {
        val trimmed = rawSeriesName.trim()
        seriesCache[trimmed]?.let { return@withContext it }

        val cleanTitle = cleanSeriesTitle(trimmed)
        var connection: HttpURLConnection? = null

        try {
            val encoded = URLEncoder.encode(cleanTitle, "UTF-8")
            val endpoint = "https://api.tvmaze.com/singlesearch/shows?q=$encoded"
            @Suppress("DEPRECATION")
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Clotube-Windows/1.0")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(jsonText).asJsonObject

                val tvMazeId = if (root.has("id") && !root.get("id").isJsonNull) root.get("id").asLong else -1L
                val officialName = if (root.has("name") && !root.get("name").isJsonNull) root.get("name").asString else cleanTitle
                val imageObj = if (root.has("image") && root.get("image").isJsonObject) root.getAsJsonObject("image") else null
                val posterUrl = imageObj?.get("original")?.asString ?: imageObj?.get("medium")?.asString ?: ""

                val genresArray = if (root.has("genres") && root.get("genres").isJsonArray) root.getAsJsonArray("genres") else null
                val genresList = mutableListOf<String>()
                genresArray?.forEach { if (!it.isJsonNull) genresList.add(it.asString) }
                val genreString = if (genresList.isNotEmpty()) genresList.joinToString(" • ") else "TV Series • Drama"

                val result = SeriesMetadata(
                    tvMazeId = tvMazeId,
                    officialTitle = officialName,
                    posterUrl = posterUrl,
                    genres = genreString
                )
                seriesCache[trimmed] = result
                println("TVMaze series match: '$cleanTitle' -> '$officialName' (ID: $tvMazeId)")
                return@withContext result
            }
        } catch (e: Exception) {
            System.err.println("Series lookup failed for '$cleanTitle': ${e.message}")
        } finally {
            connection?.disconnect()
        }

        val fallback = SeriesMetadata(tvMazeId = -1L, officialTitle = cleanTitle, posterUrl = "", genres = "TV Series")
        seriesCache[trimmed] = fallback
        fallback
    }

    // ========================================================================
    // 3. EPISODE METADATA RESOLUTION (Batch TVMaze Episodes API)
    // ========================================================================
    suspend fun resolveEpisode(
        seriesName: String,
        tvMazeSeriesId: Long,
        seasonNumber: Int,
        episodeNumber: Int,
        rawFileName: String
    ): EpisodeMetadata = withContext(Dispatchers.IO) {
        val targetShowId = if (tvMazeSeriesId > 0L) tvMazeSeriesId else resolveSeries(seriesName).tvMazeId

        if (targetShowId > 0L) {
            var episodesMap = showEpisodesCache[targetShowId]
            if (episodesMap == null) {
                episodesMap = fetchAllEpisodesForShow(targetShowId)
                showEpisodesCache[targetShowId] = episodesMap
            }

            episodesMap[Pair(seasonNumber, episodeNumber)]?.let { return@withContext it }
        }

        val fallbackTitle = cleanEpisodeTitleFromFileName(rawFileName, seasonNumber, episodeNumber)
        EpisodeMetadata(officialTitle = fallbackTitle, thumbnailUrl = "", durationMinutes = 0L)
    }

    private fun fetchAllEpisodesForShow(showId: Long): Map<Pair<Int, Int>, EpisodeMetadata> {
        val map = mutableMapOf<Pair<Int, Int>, EpisodeMetadata>()
        var connection: HttpURLConnection? = null
        try {
            val endpoint = "https://api.tvmaze.com/shows/$showId/episodes"
            @Suppress("DEPRECATION")
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Clotube-Windows/1.0")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val parsed = JsonParser.parseString(jsonText)
                if (parsed.isJsonArray) {
                    val array = parsed.asJsonArray
                    for (i in 0 until array.size()) {
                        val element = array.get(i)
                        if (!element.isJsonObject) continue
                        val item = element.asJsonObject
                        val s = if (item.has("season") && !item.get("season").isJsonNull) item.get("season").asInt else -1
                        val e = if (item.has("number") && !item.get("number").isJsonNull) item.get("number").asInt else -1
                        val name = if (item.has("name") && !item.get("name").isJsonNull) item.get("name").asString.trim() else ""
                        val runtime = if (item.has("runtime") && !item.get("runtime").isJsonNull) item.get("runtime").asLong else 0L
                        val imageObj = if (item.has("image") && item.get("image").isJsonObject) item.getAsJsonObject("image") else null
                        val thumb = imageObj?.get("original")?.asString ?: imageObj?.get("medium")?.asString ?: ""

                        if (s > 0 && e > 0 && name.isNotBlank()) {
                            map[Pair(s, e)] = EpisodeMetadata(
                                officialTitle = name,
                                thumbnailUrl = thumb,
                                durationMinutes = runtime
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("Batch episodes fetch failed for show $showId: ${e.message}")
        } finally {
            connection?.disconnect()
        }
        return map
    }

    // ========================================================================
    // REGEX SANITIZERS & MATCHERS
    // ========================================================================
    fun extractMovieQueryAndYear(raw: String): Pair<String, String?> {
        var text = raw.substringBeforeLast('.')

        // 1. Strip domain names (e.g. www.uindex.org, pahe.me)
        text = text.replace(Regex("(?i)\\b(https?://)?(www\\.)?[a-z0-9_-]+\\.(org|com|net|in|top|xyz|me|io|co|info|biz|club|site|online|tv|ru|cc|to)\\b"), " ")

        // 2. Strip uploader & site watermarks
        text = text.replace(Regex("(?i)\\b(uindex|uindexorg|yts|yify|rarbg|psa|pahe|galaxytv|tgx|qxr|1337x|torrent|extratorrent|eztv|directdl|line|bone)\\b"), " ")

        // 3. Extract 4-digit release year (1900-2099)
        val yearMatcher = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b").matcher(text)
        var year: String? = null
        if (yearMatcher.find()) {
            year = yearMatcher.group(1)
        }

        // 4. Strip technical rip tags, codecs, audio, resolutions
        val cleanupTags = listOf(
            "(?i)\\b(2160p|1080p|1080i|720p|480p|FHD|UHD|4K|HDTV|WEB-?DL|WEBRip|BluRay|BRRip|DVDRip|IMAX|Remux)\\b",
            "(?i)\\b(x264|x265|H\\.?264|H\\.?265|HEVC|AVC|10bit|8bit|HDR10?\\+?|DV|Dolby)\\b",
            "(?i)\\b(AAC|AC3|EAC3|DTS|DDP5\\.1|5\\.1CH|2\\.0CH|MP3|Dual\\s*Audio|Hindi)\\b",
            "(?i)\\[[^\\]]*\\]",
            "(?i)\\([^\\)]*\\)"
        )
        for (tag in cleanupTags) {
            text = text.replace(Regex(tag), " ")
        }
        if (year != null) {
            text = text.replace(year, " ")
        }

        text = text.replace(Regex("[()\\[\\]{}_.]"), " ")
        text = text.replace(Regex("^\\s*-\\s*|\\s*-\\s*$"), " ")
        text = text.replace(Regex("\\s+"), " ").trim()

        return Pair(text, year)
    }

    fun isTitleSimilar(query: String, candidate: String): Boolean {
        val stopWords = setOf("the", "a", "an", "of", "and", "in", "on", "at", "to", "for", "with", "by", "film", "movie")
        val qTokens = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() && it !in stopWords }
        val cTokens = candidate.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() && it !in stopWords }

        if (qTokens.isEmpty() || cTokens.isEmpty()) return false

        var matches = 0
        for (q in qTokens) {
            if (cTokens.any { c -> c == q || (c.length > 3 && (c.contains(q) || q.contains(c))) }) {
                matches++
            }
        }
        return (matches.toDouble() / qTokens.size.toDouble()) >= 0.50
    }

    fun cleanSeriesTitle(raw: String): String {
        var text = raw
        val cleanupTags = listOf(
            "(?i)\\b(Complete|Series|Season|S\\d+|Pack|1080p|720p|480p|BluRay|WEB-?DL|WEBRip|x264|x265|HEVC)\\b",
            "(?i)\\[[^\\]]*\\]",
            "(?i)\\([^\\)]*\\)"
        )
        for (tag in cleanupTags) {
            text = text.replace(Regex(tag), "")
        }
        return text.replace('.', ' ').replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim()
    }

    fun cleanEpisodeTitleFromFileName(rawFileName: String, seasonNumber: Int, episodeNumber: Int): String {
        var text = rawFileName.substringBeforeLast('.')
        val sTag = "S" + seasonNumber.toString().padStart(2, '0')
        val eTag = "E" + episodeNumber.toString().padStart(2, '0')
        val epCodePattern = "(?i)($sTag$eTag|${seasonNumber}x${episodeNumber}|Episode\\s*0*$episodeNumber)"

        text = text.replace(Regex(epCodePattern), " ")

        val cleanupTags = listOf(
            "(?i)\\b(2160p|1080p|1080i|720p|480p|FHD|UHD|4K|HDTV|WEB-?DL|WEBRip|BluRay|BRRip|DVDRip)\\b",
            "(?i)\\b(x264|x265|H\\.?264|H\\.?265|HEVC|AVC|10bit|8bit|HDR10?)\\b",
            "(?i)\\b(AAC|AC3|EAC3|DTS|DDP5\\.1|5\\.1CH|2\\.0CH)\\b",
            "(?i)\\[[^\\]]*\\]",
            "(?i)\\([^\\)]*\\)"
        )
        for (tag in cleanupTags) {
            text = text.replace(Regex(tag), "")
        }
        text = text.replace('.', ' ').replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim()
        return if (text.isBlank()) "Episode $episodeNumber" else toTitleCase(text)
    }

    fun parseSeasonAndEpisodeNumbers(fileName: String): Pair<Int, Int>? {
        val sEpPattern = Pattern.compile("(?i)[sS](\\d{1,2})[eE](\\d{1,2})")
        val matcher = sEpPattern.matcher(fileName)
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

    private fun toTitleCase(input: String): String {
        val minorWords = setOf("a", "an", "the", "and", "but", "or", "for", "nor", "on", "at", "to", "from", "by", "of", "in")
        return input.split(" ").mapIndexed { index, word ->
            if (word.isBlank()) ""
            else {
                val lower = word.lowercase()
                if (index == 0 || lower !in minorWords) {
                    lower.replaceFirstChar { it.uppercase() }
                } else {
                    lower
                }
            }
        }.joinToString(" ")
    }
}
