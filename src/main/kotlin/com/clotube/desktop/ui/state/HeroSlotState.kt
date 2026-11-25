package com.clotube.desktop.ui.state

import com.clotube.desktop.data.ContinueWatchingDisplayData
import com.clotube.desktop.data.DesktopContinueWatchingSnapshotStore
import com.clotube.desktop.image.DesktopCanonicalImageCache
import com.clotube.desktop.model.DesktopMediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface HeroSlotState {
    data object Resolving : HeroSlotState
    data class ContinueWatching(val item: ContinueWatchingDisplayData) : HeroSlotState
    data class FeaturedFallback(val movie: DesktopMediaItem) : HeroSlotState
}

/**
 * Monotonic tri-state Hero slot controller.
 * - Starts in [HeroSlotState.Resolving] (renders Obsidian skeleton, NEVER a fallback movie).
 * - Cold launch hydrates synchronously from `cw_hero_snapshot.json` in 0-15 ms.
 * - Once [hasResolvedContinueWatchingLatch] flips to true, background Drive catalog
 *   packets or metadata refreshes can enrich [HeroSlotState.ContinueWatching] in place,
 *   but can NEVER revert the Hero slot back to Resolving or FeaturedFallback.
 */
object HeroSlotStateMachine {
    private val _heroState = MutableStateFlow<HeroSlotState>(HeroSlotState.Resolving)
    val heroState: StateFlow<HeroSlotState> = _heroState.asStateFlow()

    @Volatile
    var hasResolvedContinueWatchingLatch: Boolean = false
        private set

    @Volatile
    var hasConfirmedEmptyHistoryLatch: Boolean = false
        private set

    @Volatile
    private var activeProfileEmail: String = ""

    @Volatile
    private var cachedCatalogFeatured: DesktopMediaItem? = null

    @Synchronized
    fun resetForProfileSwitch(newUserEmail: String = "") {
        activeProfileEmail = newUserEmail.trim()
        hasResolvedContinueWatchingLatch = false
        hasConfirmedEmptyHistoryLatch = false
        cachedCatalogFeatured = null
        _heroState.value = HeroSlotState.Resolving
    }

    /**
     * Frame-1 cold-launch hydration from profile-scoped `cw_hero_snapshot.json`.
     */
    @Synchronized
    fun hydrateFromLocalSnapshot(userEmail: String) {
        if (userEmail.isBlank()) return
        val normalized = userEmail.trim()
        if (!activeProfileEmail.equals(normalized, ignoreCase = true)) {
            resetForProfileSwitch(normalized)
        }
        val snapshot = DesktopContinueWatchingSnapshotStore.readSnapshot(normalized) ?: return
        if (!snapshot.confirmedEmpty && snapshot.mediaId.isNotBlank()) {
            if (snapshot.canonicalArtworkUrl.isNotBlank()) {
                DesktopCanonicalImageCache.registerOfficialArtworkUrl(
                    canonicalKey = DesktopCanonicalImageCache.heroKey(snapshot.mediaId),
                    officialUrl = snapshot.canonicalArtworkUrl,
                    linkedMediaId = snapshot.mediaId
                )
            }
            hasResolvedContinueWatchingLatch = true
            hasConfirmedEmptyHistoryLatch = false
            _heroState.value = HeroSlotState.ContinueWatching(snapshot)
        } else if (snapshot.confirmedEmpty && !hasResolvedContinueWatchingLatch) {
            hasConfirmedEmptyHistoryLatch = true
            val featured = cachedCatalogFeatured
            if (featured != null) {
                _heroState.value = HeroSlotState.FeaturedFallback(featured)
            }
        }
    }

    /**
     * Called on local playback pause/seek/completion/toggle OR timestamp-fenced Drive V2 reconciliation.
     * Set [explicitClear] = true ONLY when the user explicitly removes/completes the last in-progress item
     * or when authoritative Drive sync confirms 0 in-progress items on a profile without a newer local watch.
     */
    @Synchronized
    fun onContinueWatchingResolved(
        item: ContinueWatchingDisplayData?,
        explicitClear: Boolean = false
    ) {
        if (item != null && item.mediaId.isNotBlank() && !item.confirmedEmpty) {
            val current = (_heroState.value as? HeroSlotState.ContinueWatching)?.item
            val sameEntity = current != null && (
                current.mediaId == item.mediaId ||
                    (!item.seriesId.isNullOrBlank() && current.seriesId == item.seriesId)
                )
            val existingArtwork = if (sameEntity) current?.canonicalArtworkUrl else null
            val mergedArtwork = DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
                candidateUrl = item.canonicalArtworkUrl,
                existingUrl = existingArtwork
            )
            if (mergedArtwork.isNotBlank()) {
                DesktopCanonicalImageCache.registerOfficialArtworkUrl(
                    canonicalKey = DesktopCanonicalImageCache.heroKey(item.mediaId),
                    officialUrl = mergedArtwork,
                    linkedMediaId = item.mediaId
                )
            }
            val mergedItem = item.copy(
                canonicalArtworkUrl = mergedArtwork,
                displayHeader = item.displayHeader.ifBlank { if (sameEntity) current?.displayHeader.orEmpty() else "CONTINUE WATCHING" },
                displayTitle = item.displayTitle.ifBlank { if (sameEntity) current?.displayTitle.orEmpty() else "" },
                seriesTitle = item.seriesTitle ?: if (sameEntity) current?.seriesTitle else null,
                episodeTitle = item.episodeTitle ?: if (sameEntity && current?.mediaId == item.mediaId) current.episodeTitle else null
            )
            hasResolvedContinueWatchingLatch = true
            hasConfirmedEmptyHistoryLatch = false
            _heroState.value = HeroSlotState.ContinueWatching(mergedItem)
            return
        }

        // Empty result handling: enforce one-way monotonic latch unless explicitly cleared/confirmed
        if (explicitClear) {
            hasResolvedContinueWatchingLatch = false
            hasConfirmedEmptyHistoryLatch = true
            val featured = cachedCatalogFeatured
            _heroState.value = if (featured != null) {
                HeroSlotState.FeaturedFallback(featured)
            } else {
                HeroSlotState.Resolving
            }
        } else if (!hasResolvedContinueWatchingLatch) {
            hasConfirmedEmptyHistoryLatch = true
            val featured = cachedCatalogFeatured
            if (featured != null) {
                _heroState.value = HeroSlotState.FeaturedFallback(featured)
            }
        }
    }

    /**
     * Enriches an already-active ContinueWatching card in place when TMDB/TVMaze/Cinemeta
     * metadata or catalog details finish resolving in the background. Never downgrades state.
     */
    @Synchronized
    fun enrichContinueWatchingInPlace(
        mediaId: String,
        seriesId: String? = null,
        officialArtworkUrl: String? = null,
        resolvedTitle: String? = null,
        resolvedSeriesTitle: String? = null,
        resolvedEpisodeTitle: String? = null
    ) {
        val current = (_heroState.value as? HeroSlotState.ContinueWatching)?.item ?: return
        val matchesMedia = mediaId.isNotBlank() && current.mediaId == mediaId
        val matchesSeries = !seriesId.isNullOrBlank() && current.seriesId == seriesId
        if (!matchesMedia && !matchesSeries) return

        val candidateArt = if (matchesMedia || current.canonicalArtworkUrl.isBlank()) {
            officialArtworkUrl
        } else {
            null
        }
        val upgradedArtwork = DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
            candidateUrl = candidateArt,
            existingUrl = current.canonicalArtworkUrl
        )
        if (upgradedArtwork.isNotBlank()) {
            DesktopCanonicalImageCache.registerOfficialArtworkUrl(
                canonicalKey = DesktopCanonicalImageCache.heroKey(current.mediaId),
                officialUrl = upgradedArtwork,
                linkedMediaId = current.mediaId
            )
        }

        val updatedDisplayTitle = if (current.isSeries) {
            resolvedSeriesTitle?.takeIf { it.isNotBlank() }
                ?: current.displayTitle.takeIf { it.isNotBlank() }
                ?: resolvedTitle?.takeIf { it.isNotBlank() }
                ?: current.displayTitle
        } else {
            resolvedTitle?.takeIf { it.isNotBlank() } ?: current.displayTitle
        }

        val enriched = current.copy(
            canonicalArtworkUrl = upgradedArtwork,
            displayTitle = updatedDisplayTitle,
            seriesTitle = resolvedSeriesTitle?.takeIf { it.isNotBlank() } ?: current.seriesTitle,
            episodeTitle = if (matchesMedia) {
                resolvedEpisodeTitle?.takeIf { it.isNotBlank() } ?: current.episodeTitle
            } else {
                current.episodeTitle
            }
        )
        if (enriched == current) return

        _heroState.value = HeroSlotState.ContinueWatching(enriched)
        if (activeProfileEmail.isNotBlank()) {
            DesktopContinueWatchingSnapshotStore.writeSnapshotAsync(activeProfileEmail, enriched)
        }
    }

    /**
     * Supplies the catalog's curated featured movie for empty-history fallback.
     * Strictly ignored if [hasResolvedContinueWatchingLatch] is true or history has not yet confirmed empty.
     */
    @Synchronized
    fun onCatalogFeaturedAvailable(featured: DesktopMediaItem?) {
        if (featured != null) {
            cachedCatalogFeatured = featured
        }
        if (hasResolvedContinueWatchingLatch || _heroState.value is HeroSlotState.ContinueWatching) {
            return
        }
        if (hasConfirmedEmptyHistoryLatch && cachedCatalogFeatured != null) {
            _heroState.value = HeroSlotState.FeaturedFallback(cachedCatalogFeatured!!)
        }
    }
}
