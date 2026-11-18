package com.clotube.desktop.data

import com.clotube.desktop.model.GlobalSearchResult
import com.clotube.desktop.model.SearchMediaType
import kotlin.math.abs
import kotlin.math.min

/**
 * 4-tier title deduplication and metadata enrichment engine bridging local Google Drive
 * catalog hits with online search provider hits (TMDB / Cinemeta / IMDb).
 */
object DesktopDriveCatalogMatcher {

    private const val TIER_NONE = Int.MAX_VALUE
    private const val TIER_1_EXACT = 1
    private const val TIER_2_SUBTITLE_YEAR = 2
    private const val TIER_3_TOKEN_OVERLAP = 3
    private const val TIER_4_LEVENSHTEIN = 4

    private const val TOKEN_OVERLAP_THRESHOLD = 0.75
    private const val MIN_TOKEN_INTERSECTION = 2
    private const val MIN_LEVENSHTEIN_LENGTH = 5
    private const val MAX_LEVENSHTEIN_DISTANCE = 2
    private const val MIN_LEVENSHTEIN_SIMILARITY = 0.86

    private val VIDEO_EXTENSION_REGEX = Regex("(?i)\\.(mkv|mp4|avi|mov|webm|m4v)$")
    private val BRACKETED_TAG_REGEX = Regex("\\[[^\\]]*]")
    private val PAREN_TAG_REGEX = Regex("\\([^)]*\\)")

    // Multi-word, dotted, and hyphenated release/codec/audio tags stripped before separator normalization
    private val COMPOUND_RELEASE_TOKENS_REGEX = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(" +
            "dolby[\\s._-]*vision|" +
            "director'?s[\\s._-]*cut|" +
            "dual[\\s._-]*audio|" +
            "web[\\s._-]*dl|" +
            "web[\\s._-]*rip|" +
            "blu[\\s._-]*ray|" +
            "h\\.?264|" +
            "h\\.?265|" +
            "(?:aac|ac3|eac3|ddp?|dts)[\\s._-]*(?:5\\.1|7\\.1|2\\.0)|" +
            "5\\.1|" +
            "7\\.1" +
            ")(?![\\p{L}\\p{N}])"
    )

    // Single-word resolution, source, codec, audio, and language tags
    private val SINGLE_RELEASE_TOKENS_REGEX = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(" +
            "2160p|1080p|720p|480p|4k|uhd|fhd|" +
            "hdr10|hdr|dv|" +
            "webrip|bluray|bdrip|brrip|dvdrip|hdtv|remux|imax|extended|" +
            "x264|x265|h264|h265|hevc|av1|10bit|8bit|" +
            "aac|ac3|eac3|dts|truehd|atmos|" +
            "esubs?|hindi|bengali|english" +
            ")(?![\\p{L}\\p{N}])"
    )

    private val SEPARATED_YEAR_REGEX = Regex("(?<=^|[\\s._\\-()\\[\\]])((?:19|20)\\d{2})(?=$|[\\s._\\-()\\[\\]])")
    private val FOUR_DIGIT_YEAR_REGEX = Regex("\\b((?:19|20)\\d{2})\\b")
    private val SUBTITLE_DASH_REGEX = Regex("(?:\\s+|\\.)[-–—](?:\\s+|\\.)")
    private val SEPARATOR_CHARS_REGEX = Regex("[._\\-–—:/\\\\|,&+]+")
    private val NON_UNICODE_ALNUM_SPACE_REGEX = Regex("[^\\p{L}\\p{N}\\s]+")
    private val MULTI_WHITESPACE_REGEX = Regex("\\s+")

    /**
     * Normalizes a raw movie/series title or Drive filename into a canonical comparison key:
     * 1. Strips video extensions (`.mkv`, `.mp4`, `.avi`, `.mov`, `.webm`, `.m4v`).
     * 2. Strips bracketed `[...]` and parenthesized `(...)` tags.
     * 3. Strips resolution, source, codec, HDR, audio, edition, and language tokens.
     * 4. Strips separated 4-digit release years (`1900`–`2099`) without erasing year-only titles (e.g., `"1917"`).
     * 5. Replaces `.`, `_`, `-` with spaces, removes non-alphanumeric characters while preserving
     *    Unicode letters/digits, collapses whitespace, and lowercases.
     */
    fun normalizeTitle(raw: String): String {
        if (raw.isBlank()) return ""

        var text = raw.trim()
        text = text.replace(VIDEO_EXTENSION_REGEX, "")
        text = text.replace(BRACKETED_TAG_REGEX, " ")
        text = text.replace(PAREN_TAG_REGEX, " ")
        text = text.replace(COMPOUND_RELEASE_TOKENS_REGEX, " ")

        val beforeSingleTokens = text
        val afterSingleTokens = text.replace(SINGLE_RELEASE_TOKENS_REGEX, " ")
        text = if (hasAlnumContent(afterSingleTokens)) afterSingleTokens else beforeSingleTokens

        val afterYearStrip = text.replace(SEPARATED_YEAR_REGEX, " ")
        if (hasAlnumContent(afterYearStrip)) {
            text = afterYearStrip
        }

        text = text.replace(SEPARATOR_CHARS_REGEX, " ")
        text = text.replace(NON_UNICODE_ALNUM_SPACE_REGEX, "")
        text = text.replace(MULTI_WHITESPACE_REGEX, " ").trim().lowercase()

        return text
    }

    /**
     * Evaluates whether an online catalog result refers to the same title as an In-Drive item
     * using the 4-tier matcher while strictly enforcing media-type compatibility.
     */
    fun matchesDriveItem(
        driveItem: GlobalSearchResult,
        onlineItem: GlobalSearchResult
    ): Boolean {
        return evaluateMatchTier(driveItem, onlineItem) != TIER_NONE
    }

    /**
     * Merges local In-Drive search results with online search results:
     * - Suppresses duplicate online cards that match any local Drive item via the 4-tier matcher.
     * - Backfills blank `posterUrl`, `backdropUrl`, `overview`, `year`, `genre`, zero `rating`,
     *   and attaches `externalId` onto matched In-Drive items.
     * - Ranks all `isInDrive == true` results first (`relevanceScore` DESC), followed by
     *   external `isInDrive == false` results (`relevanceScore` DESC).
     */
    fun mergeAndDeduplicate(
        localResults: List<GlobalSearchResult>,
        onlineResults: List<GlobalSearchResult>
    ): List<GlobalSearchResult> {
        val enrichedLocal = localResults.toMutableList()
        val unmatchedOnline = ArrayList<GlobalSearchResult>(onlineResults.size)

        for (onlineItem in onlineResults) {
            var bestMatchIndex = -1
            var bestMatchTier = TIER_NONE
            var bestLocalRelevance = Int.MIN_VALUE

            for (i in enrichedLocal.indices) {
                val localCandidate = enrichedLocal[i]
                val tier = evaluateMatchTier(localCandidate, onlineItem)
                if (tier != TIER_NONE) {
                    val isBetterMatch = tier < bestMatchTier ||
                        (tier == bestMatchTier && localCandidate.relevanceScore > bestLocalRelevance)
                    if (isBetterMatch) {
                        bestMatchIndex = i
                        bestMatchTier = tier
                        bestLocalRelevance = localCandidate.relevanceScore
                    }
                }
            }

            if (bestMatchIndex >= 0) {
                enrichedLocal[bestMatchIndex] = enrichDriveItem(
                    driveItem = enrichedLocal[bestMatchIndex],
                    onlineItem = onlineItem
                )
            } else {
                unmatchedOnline.add(onlineItem.copy(isInDrive = false))
            }
        }

        val inDriveSection = enrichedLocal
            .filter { it.isInDrive }
            .sortedWith(
                compareByDescending<GlobalSearchResult> { it.relevanceScore }
                    .thenBy { it.title.lowercase() }
            )

        val externalSection = (enrichedLocal.filterNot { it.isInDrive } + unmatchedOnline)
            .sortedWith(
                compareByDescending<GlobalSearchResult> { it.relevanceScore }
                    .thenByDescending { it.rating }
                    .thenBy { it.title.lowercase() }
            )

        return inDriveSection + externalSection
    }

    // -------------------------------------------------------------------------
    // INTERNAL 4-TIER MATCHING & ENRICHMENT IMPLEMENTATION
    // -------------------------------------------------------------------------

    private fun evaluateMatchTier(
        driveItem: GlobalSearchResult,
        onlineItem: GlobalSearchResult
    ): Int {
        // Episodes are never suppressed by movie/series deduplication
        if (driveItem.mediaType == SearchMediaType.EPISODE || onlineItem.mediaType == SearchMediaType.EPISODE) {
            return TIER_NONE
        }
        // Strict media-type compatibility (MOVIE <-> MOVIE, TV_SERIES <-> TV_SERIES)
        if (driveItem.mediaType != onlineItem.mediaType) {
            return TIER_NONE
        }

        val driveRawTitles = distinctRawTitles(driveItem)
        val onlineRawTitles = distinctRawTitles(onlineItem)
        if (driveRawTitles.isEmpty() || onlineRawTitles.isEmpty()) return TIER_NONE

        val driveYear = extractYearInt(driveItem.year)
            ?: extractYearInt(driveItem.title)
            ?: extractYearInt(driveItem.originalTitle)
        val onlineYear = extractYearInt(onlineItem.year)
            ?: extractYearInt(onlineItem.title)
            ?: extractYearInt(onlineItem.originalTitle)

        var bestTier = TIER_NONE

        for (rawDrive in driveRawTitles) {
            val normDrive = normalizeTitle(rawDrive)
            if (normDrive.isBlank()) continue

            val driveBase = normalizeTitle(stripSubtitleSuffix(rawDrive))
            val driveTokens = tokenizeForOverlap(normDrive)

            for (rawOnline in onlineRawTitles) {
                val normOnline = normalizeTitle(rawOnline)
                if (normOnline.isBlank()) continue

                // Tier 1: Exact Normalized Match
                if (normDrive == normOnline) {
                    return TIER_1_EXACT
                }

                // Tier 2: Normalized Title (subtitle suffix stripped) + Year Tolerance (<= 1 year)
                if (bestTier > TIER_2_SUBTITLE_YEAR) {
                    val onlineBase = normalizeTitle(stripSubtitleSuffix(rawOnline))
                    if (driveBase.isNotBlank() && onlineBase.isNotBlank() && driveBase == onlineBase) {
                        val yearsCompatible = driveYear == null || onlineYear == null ||
                            abs(driveYear - onlineYear) <= 1
                        if (yearsCompatible) {
                            bestTier = TIER_2_SUBTITLE_YEAR
                        }
                    }
                }

                // Tier 3: Token Subset Overlap >= 75% (at least 2 matching tokens)
                if (bestTier > TIER_3_TOKEN_OVERLAP && driveTokens.isNotEmpty()) {
                    val onlineTokens = tokenizeForOverlap(normOnline)
                    if (onlineTokens.isNotEmpty()) {
                        val intersectionCount = driveTokens.count { it in onlineTokens }
                        val minTokenCount = min(driveTokens.size, onlineTokens.size)
                        if (intersectionCount >= MIN_TOKEN_INTERSECTION &&
                            (intersectionCount.toDouble() / minTokenCount.toDouble()) >= TOKEN_OVERLAP_THRESHOLD
                        ) {
                            bestTier = TIER_3_TOKEN_OVERLAP
                        }
                    }
                }

                // Tier 4: Bounded Levenshtein Similarity (len >= 5, dist <= 2, similarity >= 0.86)
                if (bestTier > TIER_4_LEVENSHTEIN &&
                    normDrive.length >= MIN_LEVENSHTEIN_LENGTH &&
                    normOnline.length >= MIN_LEVENSHTEIN_LENGTH
                ) {
                    val distance = boundedLevenshteinDistance(
                        first = normDrive,
                        second = normOnline,
                        maxDistance = MAX_LEVENSHTEIN_DISTANCE
                    )
                    if (distance <= MAX_LEVENSHTEIN_DISTANCE) {
                        val maxLen = maxOf(normDrive.length, normOnline.length)
                        val similarity = 1.0 - (distance.toDouble() / maxLen.toDouble())
                        if (similarity >= MIN_LEVENSHTEIN_SIMILARITY) {
                            bestTier = TIER_4_LEVENSHTEIN
                        }
                    }
                }
            }
        }

        return bestTier
    }

    private fun enrichDriveItem(
        driveItem: GlobalSearchResult,
        onlineItem: GlobalSearchResult
    ): GlobalSearchResult {
        val resolvedExternalId = when {
            driveItem.externalId.isNotBlank() && driveItem.externalId != driveItem.id -> driveItem.externalId
            onlineItem.externalId.isNotBlank() -> onlineItem.externalId
            else -> driveItem.externalId
        }

        return driveItem.copy(
            posterUrl = driveItem.posterUrl.ifBlank { onlineItem.posterUrl },
            backdropUrl = driveItem.backdropUrl.ifBlank { onlineItem.backdropUrl },
            overview = driveItem.overview.ifBlank { onlineItem.overview },
            year = driveItem.year.ifBlank { onlineItem.year },
            rating = if (driveItem.rating > 0.0) driveItem.rating else onlineItem.rating,
            genre = driveItem.genre.ifBlank { onlineItem.genre },
            castSubtitle = driveItem.castSubtitle.ifBlank { onlineItem.castSubtitle },
            imdbId = driveItem.imdbId.ifBlank { onlineItem.imdbId },
            externalId = resolvedExternalId
        )
    }

    private fun distinctRawTitles(item: GlobalSearchResult): List<String> {
        val titles = ArrayList<String>(2)
        if (item.title.isNotBlank()) titles.add(item.title.trim())
        val orig = item.originalTitle.trim()
        if (orig.isNotBlank() && !orig.equals(item.title.trim(), ignoreCase = true)) {
            titles.add(orig)
        }
        return titles
    }

    private fun stripSubtitleSuffix(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""

        val beforeColon = trimmed.substringBefore(':').trim()
        val beforeSubtitleDash = SUBTITLE_DASH_REGEX.split(beforeColon, limit = 2).first().trim()
        return beforeSubtitleDash.ifBlank { beforeColon }.ifBlank { trimmed }
    }

    private fun tokenizeForOverlap(normalized: String): Set<String> {
        val tokens = normalized.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptySet()
        return if (tokens.size <= 2) {
            tokens.toSet()
        } else {
            tokens.filter { it.length > 1 }.toSet()
        }
    }

    private fun extractYearInt(text: String): Int? {
        if (text.isBlank()) return null
        return FOUR_DIGIT_YEAR_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun hasAlnumContent(text: String): Boolean {
        return text.any { it.isLetterOrDigit() }
    }

    /**
     * Computes Levenshtein edit distance bounded by [maxDistance].
     * Returns `maxDistance + 1` early whenever the edit distance is guaranteed to exceed the bound.
     */
    private fun boundedLevenshteinDistance(
        first: String,
        second: String,
        maxDistance: Int
    ): Int {
        val n = first.length
        val m = second.length
        if (abs(n - m) > maxDistance) return maxDistance + 1
        if (n == 0) return m
        if (m == 0) return n

        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)

        for (i in 1..n) {
            curr.fill(maxDistance + 1)
            curr[0] = i
            val fromJ = maxOf(1, i - maxDistance)
            val toJ = minOf(m, i + maxDistance)

            var rowMin = if (i <= maxDistance) i else maxDistance + 1
            val c1 = first[i - 1]

            for (j in fromJ..toJ) {
                val cost = if (c1 == second[j - 1]) 0 else 1
                val deletion = prev[j] + 1
                val insertion = curr[j - 1] + 1
                val substitution = prev[j - 1] + cost
                val cell = minOf(deletion, insertion, substitution)
                curr[j] = cell
                if (cell < rowMin) rowMin = cell
            }

            if (rowMin > maxDistance) {
                return maxDistance + 1
            }

            val swap = prev
            prev = curr
            curr = swap
        }

        return prev[m]
    }
}
