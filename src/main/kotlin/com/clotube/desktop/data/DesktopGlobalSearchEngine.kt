package com.clotube.desktop.data

import com.clotube.desktop.model.DesktopEpisode
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.model.DesktopSeries
import com.clotube.desktop.model.GlobalSearchResult
import com.clotube.desktop.model.SearchMediaType
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Hybrid global search engine combining:
 * 1. Instantaneous (0 ms) local Google Drive catalog search across movies, TV series, and cached episodes.
 * 2. Concurrent, debounced, thread-safe online catalog search with keyless IMDb Suggestion API as
 *    primary provider, TMDB v3 multi-search + Cinemeta as secondary fallback chain, and on-demand
 *    TMDB `/find/{imdb_id}` modal detail enrichment.
 */
object DesktopGlobalSearchEngine {

    private const val TMDB_API_KEY = "84148a68b492c79f33269f8323e0702e"
    private const val TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w500"
    private const val TMDB_BACKDROP_BASE = "https://image.tmdb.org/t/p/w780"

    private const val PROVIDER_TIMEOUT_MS = 4000L
    private const val ENRICH_TIMEOUT_MS = 3500L
    private const val HTTP_CONNECT_TIMEOUT_MS = 3500
    private const val HTTP_READ_TIMEOUT_MS = 3500
    private const val ONLINE_DEBOUNCE_MS = 250L
    private const val MAX_ONLINE_CACHE_ENTRIES = 250
    private const val MAX_ENRICH_CACHE_ENTRIES = 300
    private const val SERIES_CACHE_TTL_MS = 30_000L

    private const val USER_AGENT_DESKTOP = "Clotube-Desktop/1.0"
    private const val USER_AGENT_BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    private val onlineSearchCache = ConcurrentHashMap<String, List<GlobalSearchResult>>()
    private val detailEnrichmentCache = ConcurrentHashMap<String, GlobalSearchResult>()

    private data class CachedSeriesEntry(
        val loadedAtMs: Long,
        val series: DesktopSeries
    )

    private val seriesMemoryCache = ConcurrentHashMap<String, CachedSeriesEntry>()

    // Unified TMDB Movie + TV Genre ID dictionary
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
        37 to "Western",
        10759 to "Action & Adventure",
        10762 to "Kids",
        10763 to "News",
        10764 to "Reality",
        10765 to "Sci-Fi & Fantasy",
        10766 to "Soap",
        10767 to "Talk",
        10768 to "War & Politics"
    )

    private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
    private val FOUR_DIGIT_YEAR_REGEX = Regex("\\b(19\\d{2}|20\\d{2})\\b")
    private val EPISODE_CODE_REGEX = Regex("(?i)(?:s(\\d{1,2})[\\s._-]*e(\\d{1,3})|(\\d{1,2})x(\\d{1,3}))")
    private val IMDB_IMAGE_RESIZE_REGEX = Regex("\\._V1_.*(\\.[a-zA-Z]+)$")

    /**
     * Synchronous, zero-latency local Drive catalog search across movies, TV series, and all
     * cached episodes retrieved via [DesktopSeriesCache.get].
     *
     * Exact title and prefix matches are strictly ranked above substring, genre, and episode matches.
     */
    fun searchLocalDriveCatalog(
        query: String,
        userEmail: String,
        movies: List<DesktopMediaItem>,
        series: List<DesktopMediaItem>
    ): List<GlobalSearchResult> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return emptyList()

        val qLower = trimmedQuery.lowercase()
        val qNorm = normalizeText(trimmedQuery)
        if (qNorm.isBlank()) return emptyList()

        val qTokens = tokenize(qNorm)
        val parsedEpisodeQuery = extractSeasonEpisodePair(trimmedQuery)

        val results = ArrayList<GlobalSearchResult>(movies.size.coerceAtMost(32) + series.size.coerceAtMost(32))
        val seenMovieIds = HashSet<String>()
        val seenSeriesIds = HashSet<String>()
        val seenEpisodeIds = HashSet<String>()

        // 1. Search In-Drive Movies
        for (movie in movies) {
            if (movie.id.isBlank() || !seenMovieIds.add(movie.id)) continue

            val score = scoreMediaItem(
                item = movie,
                qLower = qLower,
                qNorm = qNorm,
                qTokens = qTokens,
                extraTitle = null,
                extraGenres = null,
                extraOverview = null,
                extraPeople = null
            )
            if (score > 0) {
                results.add(
                    GlobalSearchResult(
                        id = movie.id,
                        title = movie.title,
                        originalTitle = movie.title,
                        mediaType = SearchMediaType.MOVIE,
                        isInDrive = true,
                        year = movie.releaseYear,
                        overview = "",
                        rating = 0.0,
                        posterUrl = movie.thumbnailUrl.orEmpty().ifBlank { movie.fallbackThumbnailUrl.orEmpty() },
                        backdropUrl = movie.fallbackThumbnailUrl.orEmpty().ifBlank { movie.thumbnailUrl.orEmpty() },
                        genre = movie.genre,
                        driveMediaItem = movie,
                        parentSeriesItem = null,
                        episodeId = null,
                        seasonNumber = null,
                        episodeNumber = null,
                        externalSource = "DRIVE",
                        externalId = movie.id,
                        relevanceScore = score
                    )
                )
            }
        }

        // 2. Search In-Drive TV Series and their cached episodes
        for (seriesItem in series) {
            if (seriesItem.id.isBlank() || !seenSeriesIds.add(seriesItem.id)) continue

            val cachedSeries = getCachedSeries(userEmail, seriesItem.id)
            val resolvedTitle = cachedSeries?.title?.takeIf { it.isNotBlank() } ?: seriesItem.title
            val resolvedGenre = cachedSeries?.genres?.takeIf { it.isNotBlank() } ?: seriesItem.genre
            val resolvedYear = cachedSeries?.releaseYear?.takeIf { it.isNotBlank() } ?: seriesItem.releaseYear
            val resolvedPoster = seriesItem.thumbnailUrl.orEmpty()
                .ifBlank { cachedSeries?.posterUrl.orEmpty() }
                .ifBlank { seriesItem.fallbackThumbnailUrl.orEmpty() }
            val resolvedBackdrop = cachedSeries?.backdropUrl.orEmpty()
                .ifBlank { seriesItem.fallbackThumbnailUrl.orEmpty() }
                .ifBlank { seriesItem.thumbnailUrl.orEmpty() }

            val peopleText = buildString {
                if (!cachedSeries?.creators.isNullOrBlank()) append(cachedSeries?.creators).append(' ')
                if (!cachedSeries?.cast.isNullOrBlank()) append(cachedSeries?.cast)
            }.trim()

            val seriesScore = scoreMediaItem(
                item = seriesItem,
                qLower = qLower,
                qNorm = qNorm,
                qTokens = qTokens,
                extraTitle = cachedSeries?.title,
                extraGenres = cachedSeries?.genres,
                extraOverview = cachedSeries?.synopsis,
                extraPeople = peopleText
            )

            if (seriesScore > 0) {
                results.add(
                    GlobalSearchResult(
                        id = seriesItem.id,
                        title = resolvedTitle,
                        originalTitle = seriesItem.title,
                        mediaType = SearchMediaType.TV_SERIES,
                        isInDrive = true,
                        year = resolvedYear,
                        overview = cachedSeries?.synopsis.orEmpty(),
                        rating = 0.0,
                        posterUrl = resolvedPoster,
                        backdropUrl = resolvedBackdrop,
                        genre = resolvedGenre,
                        driveMediaItem = seriesItem,
                        parentSeriesItem = null,
                        episodeId = null,
                        seasonNumber = null,
                        episodeNumber = null,
                        externalSource = "DRIVE",
                        externalId = seriesItem.id,
                        relevanceScore = seriesScore
                    )
                )
            }

            // 3. Search all cached episodes for this series
            if (cachedSeries != null && cachedSeries.seasons.isNotEmpty()) {
                val parentSeriesTitleMatch = scorePrimaryTitle(resolvedTitle, qLower, qNorm, qTokens)
                    .coerceAtLeast(scorePrimaryTitle(seriesItem.title, qLower, qNorm, qTokens))

                for (season in cachedSeries.seasons) {
                    for (episode in season.episodes) {
                        if (episode.id.isBlank() || !seenEpisodeIds.add(episode.id)) continue

                        val episodeScore = scoreCachedEpisode(
                            episode = episode,
                            parentSeriesTitle = resolvedTitle,
                            parentSeriesRawTitle = seriesItem.title,
                            parentTitleScore = parentSeriesTitleMatch,
                            qLower = qLower,
                            qNorm = qNorm,
                            qTokens = qTokens,
                            parsedEpisodeQuery = parsedEpisodeQuery
                        )

                        if (episodeScore > 0) {
                            val episodeDisplayTitle = episode.title.ifBlank {
                                "S%02dE%02d".format(episode.seasonNumber, episode.episodeNumber)
                            }
                            val episodePoster = episode.thumbnailUrl.ifBlank { resolvedPoster }
                            val episodeBackdrop = episode.thumbnailUrl.ifBlank { resolvedBackdrop }

                            results.add(
                                GlobalSearchResult(
                                    id = episode.id,
                                    title = episodeDisplayTitle,
                                    originalTitle = episode.seriesTitle.ifBlank { resolvedTitle },
                                    mediaType = SearchMediaType.EPISODE,
                                    isInDrive = true,
                                    year = resolvedYear,
                                    overview = episode.synopsis,
                                    rating = 0.0,
                                    posterUrl = episodePoster,
                                    backdropUrl = episodeBackdrop,
                                    genre = resolvedGenre,
                                    driveMediaItem = null,
                                    parentSeriesItem = seriesItem,
                                    episodeId = episode.id,
                                    seasonNumber = episode.seasonNumber,
                                    episodeNumber = episode.episodeNumber,
                                    externalSource = "DRIVE",
                                    externalId = episode.id,
                                    relevanceScore = episodeScore
                                )
                            )
                        }
                    }
                }
            }
        }

        return results.sortedWith(
            compareByDescending<GlobalSearchResult> { it.relevanceScore }
                .thenBy { mediaTypeSortOrder(it.mediaType) }
                .thenBy { it.seasonNumber ?: Int.MAX_VALUE }
                .thenBy { it.episodeNumber ?: Int.MAX_VALUE }
                .thenBy { it.title.lowercase() }
        )
    }

    /**
     * Concurrent, debounced (250 ms), thread-safe online catalog search.
     *
     * Queries the keyless IMDb Suggestion API as Primary provider (4 s timeout). If IMDb returns
     * empty, throws, or times out, falls back to TMDB v3 Multi-Search (4 s timeout), and if TMDB
     * also returns empty/fails, falls back to parallel Cinemeta (movie + series) catalogs.
     */
    suspend fun searchOnlineCatalog(query: String): List<GlobalSearchResult> =
        searchOnlineCatalog(query = query, debounceMs = ONLINE_DEBOUNCE_MS)

    suspend fun searchOnlineCatalog(
        query: String,
        debounceMs: Long
    ): List<GlobalSearchResult> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        val cacheKey = trimmed.lowercase()
        onlineSearchCache[cacheKey]?.let { return it }

        if (debounceMs > 0L) {
            delay(debounceMs)
        }

        // Re-check cache after debounce window in case another coroutine resolved it concurrently
        onlineSearchCache[cacheKey]?.let { return it }

        // 1. Primary Provider: Keyless IMDb Suggestion API
        val imdbPrimaryResults = searchImdbPrimary(trimmed)

        // 2. Secondary Provider: TMDB v3 Multi-Search
        val finalResults: List<GlobalSearchResult> = if (!imdbPrimaryResults.isNullOrEmpty()) {
            imdbPrimaryResults
        } else {
            val tmdbSecondaryResults = searchTmdbSecondary(trimmed)
            if (!tmdbSecondaryResults.isNullOrEmpty()) {
                tmdbSecondaryResults
            } else {
                // 3. Tertiary Provider: Parallel Cinemeta Movie + Series Catalogs
                fetchFromCinemetaFallback(trimmed)
            }
        }

        if (finalResults.isNotEmpty()) {
            if (onlineSearchCache.size >= MAX_ONLINE_CACHE_ENTRIES) {
                onlineSearchCache.clear()
            }
            onlineSearchCache[cacheKey] = finalResults
        }

        return finalResults
    }

    /**
     * On-demand TMDB `/find/{imdb_id}` detail enricher for external preview modals.
     * When [item.overview] is blank and [item.imdbId] starts with `"tt"`, fetches and backfills
     * full plot overview, TMDB rating, `w780` backdrop, `w500` poster (if missing), genre, and year.
     */
    suspend fun enrichExternalResult(item: GlobalSearchResult): GlobalSearchResult {
        val imdbId = item.imdbId.trim()
        if (item.overview.isNotBlank() || !imdbId.startsWith("tt")) {
            return item
        }

        detailEnrichmentCache[imdbId]?.let { cached ->
            return mergeEnrichedDetails(item, cached)
        }

        val fetched = withTimeoutOrNull(ENRICH_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                fetchTmdbDetailsByImdbId(item, imdbId)
            }
        } ?: return item

        if (detailEnrichmentCache.size >= MAX_ENRICH_CACHE_ENTRIES) {
            detailEnrichmentCache.clear()
        }
        detailEnrichmentCache[imdbId] = fetched
        return mergeEnrichedDetails(item, fetched)
    }

    /**
     * Clears all in-memory online search, detail enrichment, and series cache entries
     * (call on profile switch or sign-out).
     */
    fun clearCache() {
        onlineSearchCache.clear()
        detailEnrichmentCache.clear()
        seriesMemoryCache.clear()
    }

    // -------------------------------------------------------------------------
    // LOCAL CATALOG SCORING & CACHING
    // -------------------------------------------------------------------------

    private fun getCachedSeries(userEmail: String, seriesId: String): DesktopSeries? {
        val now = System.currentTimeMillis()
        val key = "${userEmail.lowercase()}|$seriesId"
        val cached = seriesMemoryCache[key]
        if (cached != null && (now - cached.loadedAtMs) <= SERIES_CACHE_TTL_MS) {
            return cached.series
        }

        val loaded = DesktopSeriesCache.get(userEmail, seriesId) ?: return null
        seriesMemoryCache[key] = CachedSeriesEntry(loadedAtMs = now, series = loaded)
        return loaded
    }

    private fun scoreMediaItem(
        item: DesktopMediaItem,
        qLower: String,
        qNorm: String,
        qTokens: List<String>,
        extraTitle: String?,
        extraGenres: String?,
        extraOverview: String?,
        extraPeople: String?
    ): Int {
        var bestScore = scorePrimaryTitle(item.title, qLower, qNorm, qTokens)

        if (!extraTitle.isNullOrBlank() && !extraTitle.equals(item.title, ignoreCase = true)) {
            bestScore = bestScore.coerceAtLeast(scorePrimaryTitle(extraTitle, qLower, qNorm, qTokens))
        }

        // Match against cleaned title if the Drive filename/title carries raw scene tags
        if (DesktopOnlineMetadataResolver.isTitleDirty(item.title)) {
            val cleanedTitle = DesktopOnlineMetadataResolver.cleanTitleQuick(item.title)
            if (cleanedTitle.isNotBlank() && !cleanedTitle.equals(item.title, ignoreCase = true)) {
                val cleanedScore = scorePrimaryTitle(cleanedTitle, qLower, qNorm, qTokens)
                if (cleanedScore > 0) {
                    bestScore = bestScore.coerceAtLeast((cleanedScore - 20).coerceAtLeast(500))
                }
            }
        }

        // Inspect streamUrl path segment as a raw filename/path fallback
        val streamFileSegment = extractUrlFileSegment(item.streamUrl)
        if (streamFileSegment.isNotBlank()) {
            val segmentNorm = normalizeText(streamFileSegment)
            if (segmentNorm.contains(qNorm) || (qTokens.isNotEmpty() && allTokensMatchText(qTokens, segmentNorm))) {
                bestScore = bestScore.coerceAtLeast(520)
            }
        }

        // Match against genre(s)
        val combinedGenres = buildString {
            append(item.genre)
            if (!extraGenres.isNullOrBlank()) {
                append(' ')
                append(extraGenres)
            }
        }
        val genreNorm = normalizeText(combinedGenres)
        if (genreNorm.isNotBlank()) {
            if (genreNorm.contains(qNorm)) {
                bestScore = bestScore.coerceAtLeast(360)
            } else if (qTokens.isNotEmpty() && allTokensMatchText(qTokens, genreNorm)) {
                bestScore = bestScore.coerceAtLeast(340)
            }
        }

        // Match against language or release year
        val langNorm = normalizeText(item.language)
        if (langNorm.isNotBlank() && (langNorm == qNorm || (qNorm.length >= 3 && langNorm.startsWith(qNorm)))) {
            bestScore = bestScore.coerceAtLeast(320)
        }
        if (item.releaseYear.isNotBlank() && item.releaseYear == qNorm) {
            bestScore = bestScore.coerceAtLeast(310)
        }

        // Cross-field combined match (e.g., "Dune 2021" or "Hindi Thriller")
        if (qTokens.size >= 2 && bestScore < 420) {
            val combinedMetadata = normalizeText(
                listOfNotNull(
                    item.title,
                    extraTitle,
                    item.genre,
                    extraGenres,
                    item.language,
                    item.releaseYear
                ).joinToString(" ")
            )
            if (allTokensMatchText(qTokens, combinedMetadata)) {
                bestScore = bestScore.coerceAtLeast(420)
            }
        }

        // Secondary match against cached cast/creators or synopsis for TV series
        if (bestScore == 0 && !extraPeople.isNullOrBlank()) {
            val peopleNorm = normalizeText(extraPeople)
            if (peopleNorm.contains(qNorm) || (qTokens.size >= 2 && allTokensMatchText(qTokens, peopleNorm))) {
                bestScore = 260
            }
        }
        if (bestScore == 0 && qNorm.length >= 4 && !extraOverview.isNullOrBlank()) {
            val overviewNorm = normalizeText(extraOverview)
            if (overviewNorm.contains(qNorm)) {
                bestScore = 180
            }
        }

        return bestScore
    }

    private fun scorePrimaryTitle(
        title: String,
        qLower: String,
        qNorm: String,
        qTokens: List<String>
    ): Int {
        if (title.isBlank()) return 0
        val titleLower = title.trim().lowercase()
        val titleNorm = normalizeText(title)
        if (titleNorm.isBlank()) return 0

        if (titleLower == qLower || titleNorm == qNorm) return 1000
        if (titleLower.startsWith(qLower) || titleNorm.startsWith(qNorm)) return 860

        val titleWords = tokenize(titleNorm)
        if (titleWords.any { it.startsWith(qNorm) }) return 760

        if (qTokens.size > 1 && qTokens.all { qt -> titleWords.any { tw -> tw.startsWith(qt) } }) {
            return 720
        }

        if (titleLower.contains(qLower) || titleNorm.contains(qNorm)) return 640

        if (qTokens.size > 1 && allTokensMatchText(qTokens, titleNorm)) return 590

        return 0
    }

    private fun scoreCachedEpisode(
        episode: DesktopEpisode,
        parentSeriesTitle: String,
        parentSeriesRawTitle: String,
        parentTitleScore: Int,
        qLower: String,
        qNorm: String,
        qTokens: List<String>,
        parsedEpisodeQuery: Pair<Int, Int>?
    ): Int {
        var score = 0

        val s = episode.seasonNumber
        val e = episode.episodeNumber
        val paddedSe = "s%02de%02d".format(s, e)
        val compactSe = "s${s}e${e}"
        val paddedSCompactE = "s%02de%d".format(s, e)
        val compactSPaddedE = "s%de%02d".format(s, e)
        val xCompact = "${s}x${e}"
        val xZeroEp = "${s}x%02d".format(e)
        val xPadded = "%02dx%02d".format(s, e)
        val codes = listOf(paddedSe, compactSe, paddedSCompactE, compactSPaddedE, xCompact, xZeroEp, xPadded)

        val exactCodeMatched = (parsedEpisodeQuery != null &&
            parsedEpisodeQuery.first == s &&
            parsedEpisodeQuery.second == e) ||
            codes.any { it == qLower || it == qNorm }

        val prefixCodeMatched = !exactCodeMatched && qNorm.length >= 2 && codes.any { it.startsWith(qNorm) }

        val effectiveParentTitle = episode.seriesTitle.ifBlank { parentSeriesTitle }
        val parentNorm = normalizeText("$effectiveParentTitle $parentSeriesRawTitle")

        // 1. Direct episode code matching (e.g., "S01E08", "S01 E08", "1x08", or "Breaking Bad S01E08")
        if (exactCodeMatched) {
            val queryWithoutCode = EPISODE_CODE_REGEX.replace(qLower, " ")
            val remainingTokens = tokenize(normalizeText(queryWithoutCode))
            val epAndParentNorm = normalizeText("$effectiveParentTitle $parentSeriesRawTitle ${episode.title}")
            score = when {
                remainingTokens.isEmpty() -> 560
                allTokensMatchText(remainingTokens, parentNorm) || allTokensMatchText(remainingTokens, epAndParentNorm) -> 620
                else -> 0
            }
        } else if (prefixCodeMatched) {
            score = score.coerceAtLeast(470)
        }

        // 2. Episode title matching (kept strictly below Movie/Series exact & prefix tiers)
        val epTitle = episode.title.trim()
        if (epTitle.isNotBlank()) {
            val epLower = epTitle.lowercase()
            val epNorm = normalizeText(epTitle)
            if (epNorm.isNotBlank()) {
                val epWords = tokenize(epNorm)
                val titleScore = when {
                    epLower == qLower || epNorm == qNorm -> 560
                    epLower.startsWith(qLower) || epNorm.startsWith(qNorm) -> 490
                    epWords.any { it.startsWith(qNorm) } -> 450
                    epLower.contains(qLower) || epNorm.contains(qNorm) -> 410
                    qTokens.size > 1 && allTokensMatchText(qTokens, epNorm) -> 390
                    else -> 0
                }
                score = score.coerceAtLeast(titleScore)
            }
        }

        // 3. Combined parent series + episode title / code / season-episode text (e.g., "Dark Secrets" or "Dark season 1 episode 8")
        if (qTokens.size >= 2 && score < 520) {
            val combinedEpisodeSearchable = normalizeText(
                "$effectiveParentTitle $parentSeriesRawTitle $epTitle $paddedSe $compactSe $xZeroEp season $s episode $e ep $e"
            )
            if (allTokensMatchText(qTokens, combinedEpisodeSearchable)) {
                val hasNonParentToken = qTokens.any { token -> !parentNorm.contains(token) }
                if (hasNonParentToken) {
                    score = score.coerceAtLeast(520)
                }
            }
        }

        // 4. Parent series title match alone (ranked below the series itself and below direct title/genre hits)
        if (score == 0 && parentTitleScore >= 590) {
            val parentEpisodeTier = when {
                parentTitleScore >= 1000 -> 240
                parentTitleScore >= 850 -> 215
                parentTitleScore >= 720 -> 195
                else -> 175
            }
            score = parentEpisodeTier
        }

        return score
    }

    private fun extractSeasonEpisodePair(input: String): Pair<Int, Int>? {
        val match = EPISODE_CODE_REGEX.find(input) ?: return null
        val s1 = match.groupValues[1].toIntOrNull()
        val e1 = match.groupValues[2].toIntOrNull()
        if (s1 != null && e1 != null) return Pair(s1, e1)

        val s2 = match.groupValues[3].toIntOrNull()
        val e2 = match.groupValues[4].toIntOrNull()
        if (s2 != null && e2 != null) return Pair(s2, e2)

        return null
    }

    private fun extractUrlFileSegment(streamUrl: String): String {
        if (streamUrl.isBlank()) return ""
        val withoutQuery = streamUrl.substringBefore('?').substringBefore('#')
        val lastSegment = withoutQuery.substringAfterLast('/', missingDelimiterValue = "").trim()
        if (lastSegment.isBlank()) return ""
        return try {
            URLDecoder.decode(lastSegment, Charsets.UTF_8.name())
        } catch (_: Exception) {
            lastSegment
        }
    }

    private fun mediaTypeSortOrder(type: SearchMediaType): Int = when (type) {
        SearchMediaType.MOVIE -> 0
        SearchMediaType.TV_SERIES -> 1
        SearchMediaType.EPISODE -> 2
    }

    // -------------------------------------------------------------------------
    // PRIMARY ONLINE ENGINE: KEYLESS IMDB SUGGESTION API
    // -------------------------------------------------------------------------

    private suspend fun searchImdbPrimary(query: String): List<GlobalSearchResult>? {
        return withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                fetchFromImdbPrimary(query)
            }
        }
    }

    private fun fetchFromImdbPrimary(query: String): List<GlobalSearchResult>? {
        val slug = query.trim()
            .lowercase()
            .replace(NON_ALNUM_REGEX, "_")
            .trim('_')
        if (slug.isBlank()) return emptyList()

        val firstChar = slug.first().let { ch -> if (ch in 'a'..'z') ch else 'x' }
        val endpoint = "https://v3.sg.media-imdb.com/suggestion/$firstChar/$slug.json"

        val jsonText = executeHttpGet(
            endpoint = endpoint,
            userAgent = USER_AGENT_BROWSER,
            acceptJson = true
        ) ?: return null

        val root = try {
            JsonParser.parseString(jsonText).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return null

        val items = root.get("d")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()

        val qLower = query.trim().lowercase()
        val qNorm = normalizeText(query)
        val qTokens = tokenize(qNorm)
        val qLeet = normalizeLeetspeak(qNorm)
        val qLeetTokens = tokenize(qLeet)

        val results = ArrayList<GlobalSearchResult>(items.size())
        val seenIds = HashSet<String>()

        for (i in 0 until items.size()) {
            val elem = items.get(i)
            if (elem == null || !elem.isJsonObject) continue
            val item = elem.asJsonObject

            val imdbId = safeString(item, "id").trim()
            // Strictly require canonical video title IDs (ignore actors nm*, companies co*, etc.)
            if (!imdbId.startsWith("tt") || !seenIds.add(imdbId)) continue

            // Strictly filter to movie, tvSeries, or tvMiniSeries (ignoring shorts, video games, episodes)
            val qid = safeString(item, "qid").trim()
            val mediaType = when (qid.lowercase()) {
                "movie" -> SearchMediaType.MOVIE
                "tvseries", "tvminiseries" -> SearchMediaType.TV_SERIES
                else -> continue
            }

            val title = safeString(item, "l").trim()
            if (title.isBlank()) continue

            val yearInt = safeLong(item, "y")
            val year = if (yearInt != null && yearInt in 1880L..2100L) {
                yearInt.toString()
            } else {
                extractFourDigitYear(safeString(item, "yr"))
            }

            val castSubtitle = safeString(item, "s").trim()

            val imageObj = item.get("i")?.takeIf { it.isJsonObject }?.asJsonObject
            var posterUrl = if (imageObj != null) safeString(imageObj, "imageUrl").trim() else ""
            if (posterUrl.contains("._V1_")) {
                posterUrl = posterUrl.replace(IMDB_IMAGE_RESIZE_REGEX, "._V1_UX500$1")
            }

            val directTitleScore = scorePrimaryTitle(title, qLower, qNorm, qTokens)
            val leetTitleNorm = normalizeLeetspeak(normalizeText(title))
            val leetTitleScore = if (qLeet.isNotBlank() && leetTitleNorm.isNotBlank()) {
                scorePrimaryTitle(leetTitleNorm, qLeet, qLeet, qLeetTokens)
            } else {
                0
            }
            val titleScore = maxOf(directTitleScore, leetTitleScore)

            // Inverse IMDb rank boost (lower rank = higher fame, e.g., Se7en 1995 always ranks #1)
            val imdbRank = safeLong(item, "rank") ?: 100_000L
            val rankBoost = maxOf(0, 300 - (ln(imdbRank.coerceAtLeast(1L).toDouble()) * 25.0).toInt())
            val posterBonus = if (posterUrl.isNotBlank()) 25 else 0

            results.add(
                GlobalSearchResult(
                    id = imdbId,
                    title = title,
                    originalTitle = title,
                    mediaType = mediaType,
                    isInDrive = false,
                    year = year,
                    overview = "",
                    rating = 0.0,
                    posterUrl = posterUrl,
                    backdropUrl = "",
                    genre = "",
                    castSubtitle = castSubtitle,
                    imdbId = imdbId,
                    driveMediaItem = null,
                    parentSeriesItem = null,
                    episodeId = null,
                    seasonNumber = null,
                    episodeNumber = null,
                    externalSource = "IMDB",
                    externalId = imdbId,
                    relevanceScore = titleScore + rankBoost + posterBonus
                )
            )
        }

        return results.sortedWith(
            compareByDescending<GlobalSearchResult> { it.relevanceScore }
                .thenBy { it.title.lowercase() }
        )
    }

    // -------------------------------------------------------------------------
    // SECONDARY ONLINE PROVIDER: TMDB v3 MULTI-SEARCH
    // -------------------------------------------------------------------------

    private suspend fun searchTmdbSecondary(query: String): List<GlobalSearchResult>? {
        return withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                fetchFromTmdbMultiSearch(query)
            }
        }
    }

    private fun fetchFromTmdbMultiSearch(query: String): List<GlobalSearchResult>? {
        val encodedQuery = encodeUrlComponent(query)
        val endpoint =
            "https://api.themoviedb.org/3/search/multi?api_key=$TMDB_API_KEY&query=$encodedQuery&include_adult=false&language=en-US&page=1"

        val jsonText = executeHttpGet(
            endpoint = endpoint,
            userAgent = USER_AGENT_DESKTOP,
            acceptJson = true
        ) ?: return null

        val root = try {
            JsonParser.parseString(jsonText).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return null

        val resultsArray = root.get("results")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()

        val qLower = query.lowercase()
        val qNorm = normalizeText(query)
        val qTokens = tokenize(qNorm)
        val qLeet = normalizeLeetspeak(qNorm)
        val qLeetTokens = tokenize(qLeet)

        val parsed = ArrayList<GlobalSearchResult>(resultsArray.size())
        val seenCanonicalIds = HashSet<String>()

        for (i in 0 until resultsArray.size()) {
            val element = resultsArray.get(i)
            if (element == null || !element.isJsonObject) continue
            val item = element.asJsonObject

            val mediaTypeRaw = safeString(item, "media_type").lowercase()
            if (mediaTypeRaw != "movie" && mediaTypeRaw != "tv") continue

            val tmdbId = safeLong(item, "id") ?: continue
            if (tmdbId <= 0L) continue

            val isMovie = mediaTypeRaw == "movie"
            val mediaType = if (isMovie) SearchMediaType.MOVIE else SearchMediaType.TV_SERIES
            val canonicalId = if (isMovie) "tmdb_movie_$tmdbId" else "tmdb_tv_$tmdbId"
            if (!seenCanonicalIds.add(canonicalId)) continue

            val title = if (isMovie) {
                safeString(item, "title").ifBlank { safeString(item, "original_title") }
            } else {
                safeString(item, "name").ifBlank { safeString(item, "original_name") }
            }.trim()
            if (title.isBlank()) continue

            val originalTitle = if (isMovie) {
                safeString(item, "original_title")
            } else {
                safeString(item, "original_name")
            }.trim()

            val rawDate = if (isMovie) {
                safeString(item, "release_date")
            } else {
                safeString(item, "first_air_date")
            }
            val year = extractFourDigitYear(rawDate)

            val posterPath = safeString(item, "poster_path")
            val posterUrl = if (posterPath.isNotBlank()) "$TMDB_POSTER_BASE$posterPath" else ""

            val backdropPath = safeString(item, "backdrop_path")
            val backdropUrl = if (backdropPath.isNotBlank()) "$TMDB_BACKDROP_BASE$backdropPath" else ""

            val overview = safeString(item, "overview").trim()
            val voteAverage = safeDouble(item, "vote_average")
            val popularity = safeDouble(item, "popularity")

            val genre = extractTmdbGenres(item)

            val directScore = scorePrimaryTitle(title, qLower, qNorm, qTokens)
                .coerceAtLeast(scorePrimaryTitle(originalTitle, qLower, qNorm, qTokens))
            val leetScore = scorePrimaryTitle(normalizeLeetspeak(normalizeText(title)), qLeet, qLeet, qLeetTokens)
            val titleScore = maxOf(directScore, leetScore)
            val positionBonus = (resultsArray.size() - i).coerceAtLeast(0) * 5
            val popularityBonus = popularity.coerceAtMost(100.0).roundToInt()
            val posterBonus = if (posterUrl.isNotBlank()) 25 else 0
            val relevance = titleScore + positionBonus + popularityBonus + posterBonus

            parsed.add(
                GlobalSearchResult(
                    id = canonicalId,
                    title = title,
                    originalTitle = originalTitle,
                    mediaType = mediaType,
                    isInDrive = false,
                    year = year,
                    overview = overview,
                    rating = voteAverage,
                    posterUrl = posterUrl,
                    backdropUrl = backdropUrl,
                    genre = genre,
                    castSubtitle = "",
                    imdbId = "",
                    driveMediaItem = null,
                    parentSeriesItem = null,
                    episodeId = null,
                    seasonNumber = null,
                    episodeNumber = null,
                    externalSource = "TMDB",
                    externalId = canonicalId,
                    relevanceScore = relevance
                )
            )
        }

        return parsed.sortedWith(
            compareByDescending<GlobalSearchResult> { it.relevanceScore }
                .thenByDescending { it.rating }
                .thenBy { it.title.lowercase() }
        )
    }

    // -------------------------------------------------------------------------
    // ON-DEMAND DETAIL ENRICHER: TMDB /find/{imdb_id}
    // -------------------------------------------------------------------------

    private fun fetchTmdbDetailsByImdbId(
        baseItem: GlobalSearchResult,
        imdbId: String
    ): GlobalSearchResult? {
        val encodedImdb = encodeUrlComponent(imdbId)
        val endpoint =
            "https://api.themoviedb.org/3/find/$encodedImdb?api_key=$TMDB_API_KEY&external_source=imdb_id"

        val jsonText = executeHttpGet(
            endpoint = endpoint,
            userAgent = USER_AGENT_DESKTOP,
            acceptJson = true
        ) ?: return null

        val root = try {
            JsonParser.parseString(jsonText).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return null

        val movieResults = root.get("movie_results")?.takeIf { it.isJsonArray }?.asJsonArray
        val tvResults = root.get("tv_results")?.takeIf { it.isJsonArray }?.asJsonArray

        val firstMovie = movieResults?.takeIf { it.size() > 0 }?.get(0)?.takeIf { it.isJsonObject }?.asJsonObject
        val firstTv = tvResults?.takeIf { it.size() > 0 }?.get(0)?.takeIf { it.isJsonObject }?.asJsonObject

        val matchedObj = when (baseItem.mediaType) {
            SearchMediaType.TV_SERIES -> firstTv ?: firstMovie
            else -> firstMovie ?: firstTv
        } ?: return null

        val overview = safeString(matchedObj, "overview").trim()
        val rating = safeDouble(matchedObj, "vote_average")
        val backdropPath = safeString(matchedObj, "backdrop_path")
        val backdropUrl = if (backdropPath.isNotBlank()) "$TMDB_BACKDROP_BASE$backdropPath" else ""
        val posterPath = safeString(matchedObj, "poster_path")
        val posterUrl = if (posterPath.isNotBlank()) "$TMDB_POSTER_BASE$posterPath" else ""
        val genre = extractTmdbGenres(matchedObj)
        val rawDate = safeString(matchedObj, "release_date").ifBlank { safeString(matchedObj, "first_air_date") }
        val year = extractFourDigitYear(rawDate)

        return baseItem.copy(
            overview = overview,
            rating = rating,
            backdropUrl = backdropUrl,
            posterUrl = posterUrl,
            genre = genre,
            year = year
        )
    }

    private fun mergeEnrichedDetails(
        item: GlobalSearchResult,
        enriched: GlobalSearchResult
    ): GlobalSearchResult {
        return item.copy(
            overview = item.overview.ifBlank { enriched.overview },
            rating = if (item.rating > 0.0) item.rating else enriched.rating,
            backdropUrl = item.backdropUrl.ifBlank { enriched.backdropUrl },
            posterUrl = item.posterUrl.ifBlank { enriched.posterUrl },
            genre = item.genre.ifBlank { enriched.genre },
            year = item.year.ifBlank { enriched.year }
        )
    }

    private fun extractTmdbGenres(item: JsonObject): String {
        val genreIds = item.get("genre_ids")?.takeIf { it.isJsonArray }?.asJsonArray ?: return ""
        val mapped = ArrayList<String>(3)
        for (idx in 0 until genreIds.size()) {
            val elem = genreIds.get(idx)
            val id = elem?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asIntOrNull() ?: continue
            val name = TMDB_GENRE_MAP[id] ?: continue
            if (name !in mapped) {
                mapped.add(name)
                if (mapped.size >= 3) break
            }
        }
        return mapped.joinToString(" • ")
    }

    // -------------------------------------------------------------------------
    // TERTIARY FALLBACK: PARALLEL CINEMETA (MOVIE + SERIES)
    // -------------------------------------------------------------------------

    private suspend fun fetchFromCinemetaFallback(query: String): List<GlobalSearchResult> = coroutineScope {
        val cinemetaMoviesDeferred = async(Dispatchers.IO) {
            withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                fetchFromCinemeta(query = query, isSeries = false)
            }.orEmpty()
        }

        val cinemetaSeriesDeferred = async(Dispatchers.IO) {
            withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                fetchFromCinemeta(query = query, isSeries = true)
            }.orEmpty()
        }

        val combined = cinemetaMoviesDeferred.await() + cinemetaSeriesDeferred.await()
        deduplicateCinemetaResults(combined)
    }

    private fun fetchFromCinemeta(
        query: String,
        isSeries: Boolean
    ): List<GlobalSearchResult> {
        val catalogType = if (isSeries) "series" else "movie"
        val mediaType = if (isSeries) SearchMediaType.TV_SERIES else SearchMediaType.MOVIE
        val encodedQuery = encodeUrlComponent(query)
        val endpoint = "https://v3-cinemeta.strem.io/catalog/$catalogType/top/search=$encodedQuery.json"

        val jsonText = executeHttpGet(
            endpoint = endpoint,
            userAgent = USER_AGENT_DESKTOP,
            acceptJson = true
        ) ?: return emptyList()

        val root = try {
            JsonParser.parseString(jsonText).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val metas = root.get("metas")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()

        val qLower = query.lowercase()
        val qNorm = normalizeText(query)
        val qTokens = tokenize(qNorm)

        val results = ArrayList<GlobalSearchResult>(metas.size().coerceAtMost(25))
        val limit = metas.size().coerceAtMost(25)

        for (i in 0 until limit) {
            val elem = metas.get(i)
            if (elem == null || !elem.isJsonObject) continue
            val meta = elem.asJsonObject

            val title = safeString(meta, "name").trim()
            if (title.isBlank()) continue

            val rawId = safeString(meta, "id")
                .ifBlank { safeString(meta, "imdb_id") }
                .trim()
            val canonicalId = if (rawId.isNotBlank()) {
                rawId
            } else {
                "cinemeta_${catalogType}_${normalizeText(title).replace(' ', '_')}_$i"
            }

            val releaseRaw = safeString(meta, "releaseInfo").ifBlank { safeString(meta, "year") }
            val year = extractFourDigitYear(releaseRaw)
            val posterUrl = safeString(meta, "poster").trim()
            val backdropUrl = safeString(meta, "background").trim()
            val overview = safeString(meta, "description").trim()
            val rating = safeDouble(meta, "imdbRating")
            val genre = extractStringArrayGenres(meta)
            val resolvedImdbId = if (canonicalId.startsWith("tt")) canonicalId else ""

            val titleScore = scorePrimaryTitle(title, qLower, qNorm, qTokens)
            val rankBonus = (limit - i) * 4
            val metadataBonus = (if (posterUrl.isNotBlank()) 20 else 0) + (if (overview.isNotBlank()) 10 else 0)

            results.add(
                GlobalSearchResult(
                    id = canonicalId,
                    title = title,
                    originalTitle = title,
                    mediaType = mediaType,
                    isInDrive = false,
                    year = year,
                    overview = overview,
                    rating = rating,
                    posterUrl = posterUrl,
                    backdropUrl = backdropUrl,
                    genre = genre,
                    castSubtitle = "",
                    imdbId = resolvedImdbId,
                    driveMediaItem = null,
                    parentSeriesItem = null,
                    episodeId = null,
                    seasonNumber = null,
                    episodeNumber = null,
                    externalSource = "CINEMETA",
                    externalId = canonicalId,
                    relevanceScore = titleScore + rankBonus + metadataBonus
                )
            )
        }

        return results
    }

    private fun deduplicateCinemetaResults(hits: List<GlobalSearchResult>): List<GlobalSearchResult> {
        val byTitleAndType = LinkedHashMap<Pair<String, SearchMediaType>, GlobalSearchResult>()
        val keyByExternalId = HashMap<String, Pair<String, SearchMediaType>>()

        for (candidate in hits) {
            val normalizedKey = Pair(normalizeText(candidate.title), candidate.mediaType)
            if (normalizedKey.first.isBlank()) continue

            val existingKey = if (candidate.externalId.isNotBlank()) {
                keyByExternalId[candidate.externalId] ?: normalizedKey
            } else {
                normalizedKey
            }

            val existing = byTitleAndType[existingKey]
            if (existing == null) {
                byTitleAndType[normalizedKey] = candidate
                if (candidate.externalId.isNotBlank()) {
                    keyByExternalId[candidate.externalId] = normalizedKey
                }
            } else {
                byTitleAndType[existingKey] = existing.copy(
                    year = existing.year.ifBlank { candidate.year },
                    overview = existing.overview.ifBlank { candidate.overview },
                    rating = if (existing.rating > 0.0) existing.rating else candidate.rating,
                    posterUrl = existing.posterUrl.ifBlank { candidate.posterUrl },
                    backdropUrl = existing.backdropUrl.ifBlank { candidate.backdropUrl },
                    genre = existing.genre.ifBlank { candidate.genre },
                    imdbId = existing.imdbId.ifBlank { candidate.imdbId },
                    relevanceScore = maxOf(existing.relevanceScore, candidate.relevanceScore)
                )
            }
        }

        return byTitleAndType.values.sortedWith(
            compareByDescending<GlobalSearchResult> { it.relevanceScore }
                .thenByDescending { it.rating }
                .thenBy { it.title.lowercase() }
        )
    }

    /**
     * Normalizes common cinematic leetspeak spellings (e.g. `se7en` <-> `seven`, `m3gan` <-> `megan`,
     * `fant4stic` <-> `fantastic`, `f1ght` <-> `fight`) so queries match iconic stylized titles.
     */
    private fun normalizeLeetspeak(normalized: String): String {
        if (normalized.isBlank()) return ""
        return normalized.split(' ').joinToString(" ") { token ->
            when (token) {
                "se7en" -> "seven"
                "fant4stic" -> "fantastic"
                "m3gan" -> "megan"
                "s1m0ne" -> "simone"
                "l33t" -> "leet"
                else -> {
                    val hasLetter = token.any { it in 'a'..'z' }
                    val hasDigit = token.any { it in '0'..'9' }
                    if (!hasLetter || !hasDigit) {
                        token
                    } else {
                        val sb = StringBuilder(token.length)
                        for (idx in token.indices) {
                            val c = token[idx]
                            val mapped = when (c) {
                                '0' -> 'o'
                                '1' -> 'i'
                                '3' -> 'e'
                                '4' -> 'a'
                                '5' -> 's'
                                '7' -> 't'
                                else -> c
                            }
                            sb.append(mapped)
                        }
                        sb.toString()
                    }
                }
            }
        }
    }

    private fun extractStringArrayGenres(obj: JsonObject): String {
        val genresElement = obj.get("genres") ?: obj.get("genre") ?: return ""
        if (!genresElement.isJsonArray) return ""
        val arr = genresElement.asJsonArray
        val list = ArrayList<String>(3)
        for (i in 0 until arr.size()) {
            val item = arr.get(i)
            val text = item?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim().orEmpty()
            if (text.isNotBlank() && text !in list) {
                list.add(text)
                if (list.size >= 3) break
            }
        }
        return list.joinToString(" • ")
    }

    // -------------------------------------------------------------------------
    // HTTP & JSON UTILITIES
    // -------------------------------------------------------------------------

    private fun executeHttpGet(
        endpoint: String,
        userAgent: String,
        acceptJson: Boolean
    ): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URI(endpoint).toURL()
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HTTP_CONNECT_TIMEOUT_MS
                readTimeout = HTTP_READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                if (acceptJson) {
                    setRequestProperty("Accept", "application/json")
                }
            }

            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("[DesktopGlobalSearchEngine] HTTP GET failed for $endpoint: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun encodeUrlComponent(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun normalizeText(text: String): String {
        if (text.isBlank()) return ""
        return text.lowercase()
            .replace(NON_ALNUM_REGEX, " ")
            .trim()
    }

    private fun tokenize(normalized: String): List<String> {
        if (normalized.isBlank()) return emptyList()
        return normalized.split(' ').filter { it.isNotBlank() }
    }

    private fun allTokensMatchText(tokens: List<String>, normalizedText: String): Boolean {
        if (tokens.isEmpty() || normalizedText.isBlank()) return false
        return tokens.all { token -> normalizedText.contains(token) }
    }

    private fun extractFourDigitYear(raw: String): String {
        if (raw.isBlank()) return ""
        return FOUR_DIGIT_YEAR_REGEX.find(raw)?.groupValues?.getOrNull(1) ?: ""
    }

    private fun safeString(obj: JsonObject, key: String): String {
        val elem = obj.get(key) ?: return ""
        return if (!elem.isJsonNull && elem.isJsonPrimitive) {
            try {
                elem.asString ?: ""
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
    }

    private fun safeLong(obj: JsonObject, key: String): Long? {
        val elem = obj.get(key) ?: return null
        if (elem.isJsonNull || !elem.isJsonPrimitive) return null
        return try {
            elem.asLong
        } catch (_: Exception) {
            try {
                elem.asString?.trim()?.toLongOrNull()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun safeDouble(obj: JsonObject, key: String): Double {
        val elem = obj.get(key) ?: return 0.0
        if (elem.isJsonNull || !elem.isJsonPrimitive) return 0.0
        return try {
            elem.asDouble
        } catch (_: Exception) {
            try {
                elem.asString?.trim()?.toDoubleOrNull() ?: 0.0
            } catch (_: Exception) {
                0.0
            }
        }
    }

    private fun JsonElement.asIntOrNull(): Int? {
        return try {
            this.asInt
        } catch (_: Exception) {
            try {
                this.asString?.trim()?.toIntOrNull()
            } catch (_: Exception) {
                null
            }
        }
    }
}
