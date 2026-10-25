package com.clotube.desktop.data

import com.clotube.desktop.model.DesktopMediaItem
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

data class MovieMetadata(
    val officialTitle: String,
    val posterUrl: String = "",
    val releaseYear: String = "",
    val genre: String = "Drama",
    val language: String = "English"
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

    // Curated title, genre, and language registry (posters are fetched dynamically from live APIs)
    private val CURATED_TITLES = mapOf(
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

    fun clearCache() {
        movieCache.clear()
        seriesCache.clear()
        showEpisodesCache.clear()
    }

    fun resolveMovieSync(rawFileName: String): MovieMetadata {
        val (cleanTitle, releaseYear) = extractMovieQueryAndYear(rawFileName)
        val lowerKey = cleanTitle.lowercase().replace(Regex("[^a-z0-9]"), " ").replace(Regex("\\s+"), " ").trim()

        CURATED_TITLES[lowerKey]?.let { return it }
        for ((key, meta) in CURATED_TITLES) {
            if (lowerKey == key || (key.length > 4 && lowerKey.contains(key)) || (lowerKey.length > 4 && key.contains(lowerKey))) {
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
        val (cleanQuery, releaseYear) = extractMovieQueryAndYear(trimmed)
        val searchCandidates = generateCandidateQueries(syncMeta.officialTitle.ifBlank { cleanQuery })

        var matchedTitle: String? = syncMeta.officialTitle.ifBlank { null }
        var matchedPoster = ""
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

        val queryForWiki = matchedTitle ?: searchCandidates.firstOrNull() ?: cleanQuery

        // 2. Query Wikipedia API if poster or genre is missing
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

        // 3. Query iTunes API as fallback
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
        text = text.replace(Regex("[\\[\\](){}_+~]"), " ")
        text = text.replace(Regex("(?<=\\D)\\.|\\.(?=\\D)"), " ")
        text = text.replace(Regex("\\s+"), " ").trim()

        var year: String? = null
        val yearRegex = Regex("(?<!kalki\\s)\\b(19\\d{2}|20[0-2]\\d)\\b", RegexOption.IGNORE_CASE)
        val yearMatch = yearRegex.find(text)

        val cutoffPattern = Regex(
            "(?i)\\b(" +
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

        cleanTitle = cleanTitle.replace(Regex("(?i)^\\s*(shop|all|new|the\\s+complete|full\\s+movie|movie|film|cinema|hd|hq|exclusive)\\s+"), " ")
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
            val leadingIgnored = setOf("shop", "all", "new", "top", "hd", "the", "a")
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

    fun isTitleDirty(title: String): Boolean {
        val lower = title.lowercase()
        val dirtyMarkers = listOf(
            "1080p", "720p", "2160p", "480p", "bluray", "imax", "10bit", "8bit",
            "x265", "x264", "hevc", "web-dl", "webrip", "webri", "amzn", "aac",
            "1-world", "galaxyrg", "1400mb", "hindi", "bengali", "bollywood",
            "yts", "yify", "rarbg", "hub4u", "mlsbd", "1337x", "fbm", "wmr",
            "s01", "e01", "upmix", "print"
        )
        return dirtyMarkers.any { lower.contains(it) } || lower.contains("@")
    }

    fun detectMovieLanguage(item: DesktopMediaItem): String {
        if (item.language.isNotBlank() && item.language in listOf("Hindi", "Bengali", "English")) {
            return item.language
        }
        return deduceLanguageFromTitle(item.title)
    }

    fun deduceLanguageFromTitle(title: String): String {
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

    suspend fun resolveSeries(rawSeriesName: String): SeriesMetadata = withContext(Dispatchers.IO) {
        val trimmed = rawSeriesName.trim()
        seriesCache[trimmed]?.let { return@withContext it }

        val cleanTitle = cleanSeriesTitle(trimmed)
        var connection: HttpURLConnection? = null

        try {
            val encoded = URLEncoder.encode(cleanTitle, "UTF-8")
            val endpoint = "https://api.tvmaze.com/singlesearch/shows?q=$encoded"
            connection = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Clotube-Windows/1.0")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(jsonText).asJsonObject

                val tvMazeId = root.get("id")?.asLong ?: -1L
                val officialName = root.get("name")?.asString ?: cleanTitle
                val imageObj = root.getAsJsonObject("image")
                val posterUrl = imageObj?.get("original")?.asString ?: imageObj?.get("medium")?.asString ?: ""

                val genresArray = root.getAsJsonArray("genres")
                val genresList = mutableListOf<String>()
                genresArray?.forEach { genresList.add(it.asString) }
                val genreString = if (genresList.isNotEmpty()) genresList.joinToString(" • ") else "Drama • Mystery"

                val result = SeriesMetadata(
                    tvMazeId = tvMazeId,
                    officialTitle = officialName,
                    posterUrl = posterUrl,
                    genres = genreString
                )
                seriesCache[trimmed] = result
                return@withContext result
            }
        } catch (_: Exception) {
        } finally {
            connection?.disconnect()
        }

        val fallback = SeriesMetadata(tvMazeId = -1L, officialTitle = cleanTitle, posterUrl = "", genres = "TV Series • Drama")
        seriesCache[trimmed] = fallback
        fallback
    }

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
            connection = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Clotube-Windows/1.0")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val array = JsonParser.parseString(jsonText).asJsonArray

                for (i in 0 until array.size()) {
                    val item = array.get(i).asJsonObject
                    val s = item.get("season")?.asInt ?: -1
                    val e = item.get("number")?.asInt ?: -1
                    val name = item.get("name")?.asString?.trim() ?: ""
                    val runtime = item.get("runtime")?.asLong ?: 0L
                    val imageObj = item.getAsJsonObject("image")
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
        } catch (_: Exception) {
        } finally {
            connection?.disconnect()
        }
        return map
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
     * Handles patterns such as: "Season 1", "Season 02", "S1", "S03", "Season - 2", "Series 1".
     * Returns 1 if no number is found.
     */
    fun parseSeasonNumberFromFolder(folderName: String): Int {
        val clean = folderName.trim()
        if (clean.isBlank()) return 1

        // 1. Matches "Season 1", "Season 02", "Series 1", "Season - 2", "Season_02", etc.
        val seasonKeywordPattern = Regex("(?i)\\b(?:season|series|staffel|saison)\\s*[-_.]*\\s*(\\d{1,3})\\b")
        seasonKeywordPattern.find(clean)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }

        // 2. Matches "S1", "S02", "S03", "S-02", "S 2", "s1"
        val sPattern = Regex("(?i)\\bS\\s*[-_.]*\\s*(\\d{1,3})\\b")
        sPattern.find(clean)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }

        // 3. Standalone pure number folder: "1", "01", "02"
        val pureNumberPattern = Regex("^\\s*0*(\\d{1,3})\\s*$")
        pureNumberPattern.find(clean)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }

        // 4. Any standalone 1-2 digit number that is not a 4-digit year
        val anyNumberPattern = Regex("(?<!\\d)(?!19\\d{2}|20\\d{2})0*([1-9]\\d?)(?!\\d)")
        anyNumberPattern.find(clean)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }

        return 1
    }

    /**
     * Extracts the episode number from an episode video filename.
     * Context-aware: accepts the inferred [seasonNumber] from the parent folder.
     * Matches patterns:
     *   1. S01E05 / s1e5
     *   2. 1x05 / 01x05
     *   3. Episode 05 / Ep 5 / Ep.05
     *   4. Standalone leading or trailing numbers: "01 - Pilot.mkv", "01.mkv", "Pilot - 01.mp4"
     * Strips codec tags (1080p, x264, HEVC, AAC, etc.) before matching so resolutions are not mistaken for episode numbers.
     */
    fun parseEpisodeNumberFromFileName(fileName: String, seasonNumber: Int = 1): Int {
        var text = fileName.substringBeforeLast('.').trim()

        // Strip codec tags, resolutions, and typical container audio/video indicators
        val codecStripPattern = Regex(
            "(?i)\\b(" +
            "2160p|1080p|1080i|720p|480p|360p|4k|uhd|fhd|hdtv|hdr\\d*|dovi|dv|" +
            "bluray|blu-ray|brrip|bdrip|dvdrip|dvd|hdrip|web[-_]?dl|web[-_]?rip|webri|web|" +
            "imax|remux|untouched|proper|repack|extended|unrated|" +
            "x264|x265|h\\.?264|h\\.?265|hevc|avc|10bit|8bit|" +
            "aac\\d*|ddp?\\d*|dd\\d*|ac3|eac3|dts[-_]?hd|dts|truehd|atmos|mp3|flac|opus|" +
            "5\\.1ch|2\\.0ch|5\\.1|7\\.1|" +
            "dual[-_\\s]*audio|multi[-_\\s]*audio|esubs?|msubs?|subbed|subs?" +
            ")\\b"
        )
        text = text.replace(codecStripPattern, " ")
        text = text.replace(Regex("\\[[^\\]]*\\]"), " ")

        // 1. S01E05 / s1e5 / S1E5
        val sPattern = Regex("(?i)\\bS\\s*\\d{1,2}\\s*[-_.]*\\s*E\\s*0*(\\d{1,3})\\b")
        sPattern.find(text)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }

        // 2. 1x05 / 01x05
        val xPattern = Regex("(?i)\\b\\d{1,2}\\s*x\\s*0*(\\d{1,3})\\b")
        xPattern.find(text)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }

        // 3. Episode 05 / Ep 5 / Ep.05 / Ep_05 / E05
        val epPattern = Regex("(?i)\\b(?:episode|ep|e)\\s*[-_.:#]?\\s*0*(\\d{1,3})\\b")
        epPattern.find(text)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }

        // Context-aware: If seasonNumber is provided, strip explicit references to that season (e.g., S01, Season 1)
        // so that the season number itself is not mistaken for a standalone episode number
        var contextText = text
        if (seasonNumber > 0) {
            val seasonPattern = Regex("(?i)\\b(?:season|series|s)\\s*[-_.]*\\s*0*$seasonNumber\\b")
            contextText = contextText.replace(seasonPattern, " ")

            // Handle [Season]-[Episode] or [Season].[Episode] like "1-05" or "01 - 05 - Pilot"
            val seasonEpPrefix = Regex("(?i)(?:^|[-_\\s])0*$seasonNumber\\s*[-_.]\\s*0*(\\d{1,3})(?:[-_\\s]|$)")
            seasonEpPrefix.find(text)?.let { match ->
                match.groupValues[1].toIntOrNull()?.let { return it }
            }
        }

        // 4a. Standalone leading numbers: "01 - Pilot.mkv", "01.mkv", "05 - Title"
        val leadingNumPattern = Regex("^\\s*0*(\\d{1,3})(?:\\s*[-–—._\\s]+|$)")
        leadingNumPattern.find(contextText)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }

        // 4b. Standalone trailing numbers: "Pilot - 01.mp4", "Pilot 01"
        val trailingNumPattern = Regex("(?:[-–—._\\s]+|^)0*(\\d{1,3})\\s*$")
        trailingNumPattern.find(contextText)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }

        // 4c. Number delimited by separators (e.g. "Series Name - 05 - Pilot") excluding 4-digit years
        val delimitedNumPattern = Regex("(?<!\\d)(?!19\\d{2}|20\\d{2})0*([1-9]\\d{0,2})(?!\\d)")
        delimitedNumPattern.find(contextText)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
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
