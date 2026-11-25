package com.clotube.desktop.data

import com.clotube.desktop.image.ArtworkProvenance
import com.clotube.desktop.image.DesktopCanonicalImageCache
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.ui.state.HeroSlotState
import com.clotube.desktop.ui.state.HeroSlotStateMachine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OttStartupPipelineTest {
    private val testProfilesToCleanup = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        HeroSlotStateMachine.resetForProfileSwitch("")
        DesktopCanonicalImageCache.clearMemory()
        testProfilesToCleanup.forEach { email ->
            DesktopContinueWatchingSnapshotStore.clearMemoryForProfileSwitch(email)
            runCatching { DesktopContinueWatchingSnapshotStore.resolveProfileDir(email).deleteRecursively() }
        }
        testProfilesToCleanup.clear()
    }

    private fun newTestEmail(tag: String): String {
        val email = "__clotube_unit_${tag}_${System.nanoTime()}@example.com"
        testProfilesToCleanup += email
        return email
    }

    @Test
    fun testEpochMsNormalizationAndSanitizeEmailParity() {
        assertEquals(1_700_000_000_000L, DesktopContinueWatchingSnapshotStore.normalizeEpochMs(1_700_000_000L))
        assertEquals(1_700_000_000_123L, DesktopContinueWatchingSnapshotStore.normalizeEpochMs(1_700_000_000_123L))
        assertEquals(0L, DesktopContinueWatchingSnapshotStore.normalizeEpochMs(0L))

        val email = "User.Name+01@Gmail.COM"
        assertEquals(
            DesktopProfileManager.sanitizeEmail(email),
            DesktopContinueWatchingSnapshotStore.sanitizeEmail(email)
        )
    }

    @Test
    fun testPoisonedDriveFrameFilteringAndSanitization() {
        val tmdbUrl = "https://image.tmdb.org/t/p/original/hero.jpg"
        val driveThumb = "https://drive.google.com/thumbnail?id=abc&sz=w1600"
        val localLoopback = "http://127.0.0.1:45123/stream/xyz"

        assertTrue(DesktopContinueWatchingSnapshotStore.isPoisonedDriveFrameUrl(driveThumb))
        assertTrue(DesktopContinueWatchingSnapshotStore.isPoisonedDriveFrameUrl("https://lh3.googleusercontent.com/d/abc"))
        assertTrue(DesktopContinueWatchingSnapshotStore.isPoisonedDriveFrameUrl(localLoopback))
        assertFalse(DesktopContinueWatchingSnapshotStore.isPoisonedDriveFrameUrl(tmdbUrl))

        assertEquals(
            tmdbUrl,
            DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
                candidateUrl = driveThumb,
                existingUrl = tmdbUrl
            )
        )
        assertEquals(
            "",
            DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
                candidateUrl = localLoopback,
                existingUrl = driveThumb
            )
        )
    }

    @Test
    fun testSnapshotStoreRoundTripAndProfileIsolation() {
        val email = newTestEmail("snapshot")
        assertNull(DesktopContinueWatchingSnapshotStore.readSnapshot(email))

        val cw = ContinueWatchingDisplayData(
            mediaId = "ep_101",
            seriesId = "series_10",
            isSeries = true,
            displayHeader = "RESUME S01E01",
            displayTitle = "Test Show",
            seriesTitle = "Test Show",
            seasonNumber = 1,
            episodeNumber = 1,
            episodeTitle = "Pilot",
            canonicalArtworkUrl = "https://image.tmdb.org/t/p/original/pilot.jpg",
            progressFraction = 0.42f,
            currentPositionMs = 420_000L,
            durationMs = 1_000_000L,
            updatedAt = 1_750_000_000L,
            confirmedEmpty = false
        )
        DesktopContinueWatchingSnapshotStore.writeSnapshotBlocking(email, cw)
        DesktopContinueWatchingSnapshotStore.clearMemoryForProfileSwitch(email)

        val reloaded = DesktopContinueWatchingSnapshotStore.readSnapshot(email)
        assertNotNull(reloaded)
        assertEquals("ep_101", reloaded.mediaId)
        assertEquals("series_10", reloaded.seriesId)
        assertTrue(reloaded.isSeries)
        assertEquals(1, reloaded.seasonNumber)
        assertEquals(1, reloaded.episodeNumber)
        assertEquals(1_750_000_000_000L, reloaded.updatedAt)
        assertEquals("https://image.tmdb.org/t/p/original/pilot.jpg", reloaded.canonicalArtworkUrl)
        assertFalse(reloaded.confirmedEmpty)
    }

    @Test
    fun testHeroSlotStateMachineMonotonicLatchAndEnrichment() {
        val email = newTestEmail("hero")
        val fallbackMovie = DesktopMediaItem(
            id = "movie_fallback",
            title = "Featured Fallback Movie",
            durationMs = 7_200_000L,
            thumbnailUrl = "https://image.tmdb.org/t/p/original/featured.jpg"
        )

        HeroSlotStateMachine.resetForProfileSwitch(email)
        assertIs<HeroSlotState.Resolving>(HeroSlotStateMachine.heroState.value)

        // Featured movie alone must NOT flash before CW history confirms empty
        HeroSlotStateMachine.onCatalogFeaturedAvailable(fallbackMovie)
        assertIs<HeroSlotState.Resolving>(HeroSlotStateMachine.heroState.value)

        val cw = ContinueWatchingDisplayData(
            mediaId = "ep_202",
            seriesId = "series_20",
            isSeries = true,
            displayHeader = "CONTINUE WATCHING",
            displayTitle = "Dark",
            seriesTitle = "Dark",
            seasonNumber = 1,
            episodeNumber = 2,
            episodeTitle = "Lies",
            canonicalArtworkUrl = "https://image.tmdb.org/t/p/original/dark_s1e2.jpg",
            progressFraction = 0.5f,
            currentPositionMs = 1_500_000L,
            durationMs = 3_000_000L,
            updatedAt = 1_750_000_123_000L
        )
        HeroSlotStateMachine.onContinueWatchingResolved(cw, explicitClear = false)

        val activeState = assertIs<HeroSlotState.ContinueWatching>(HeroSlotStateMachine.heroState.value)
        assertEquals("ep_202", activeState.item.mediaId)

        // Late catalog updates cannot overwrite active Continue Watching Hero
        HeroSlotStateMachine.onCatalogFeaturedAvailable(fallbackMovie)
        assertIs<HeroSlotState.ContinueWatching>(HeroSlotStateMachine.heroState.value)

        // Drive-frame URL must not overwrite official artwork; official upgrade succeeds
        HeroSlotStateMachine.enrichContinueWatchingInPlace(
            mediaId = "ep_202",
            seriesId = "series_20",
            officialArtworkUrl = "https://drive.google.com/thumbnail?id=ep_202",
            resolvedSeriesTitle = "Dark (2017)"
        )
        val afterPoisonAttempt = assertIs<HeroSlotState.ContinueWatching>(HeroSlotStateMachine.heroState.value)
        assertEquals("https://image.tmdb.org/t/p/original/dark_s1e2.jpg", afterPoisonAttempt.item.canonicalArtworkUrl)
        assertEquals("Dark (2017)", afterPoisonAttempt.item.seriesTitle)

        // Explicit clear transitions cleanly to FeaturedFallback
        HeroSlotStateMachine.onContinueWatchingResolved(null, explicitClear = true)
        val fallbackState = assertIs<HeroSlotState.FeaturedFallback>(HeroSlotStateMachine.heroState.value)
        assertEquals("movie_fallback", fallbackState.movie.id)
    }

    @Test
    fun testCanonicalImageCacheProvenanceClassificationAndLock() {
        val officialUrl = "https://image.tmdb.org/t/p/w780/poster.jpg"
        val driveUrl = "https://lh3.googleusercontent.com/drive/frame123"
        val loopbackUrl = "http://127.0.0.1:8080/media/1"

        assertEquals(ArtworkProvenance.OFFICIAL_CATALOG, DesktopCanonicalImageCache.classifyProvenance(officialUrl))
        assertEquals(ArtworkProvenance.DRIVE_FRAME_CAPTURE, DesktopCanonicalImageCache.classifyProvenance(driveUrl))
        assertEquals(ArtworkProvenance.NONE, DesktopCanonicalImageCache.classifyProvenance(loopbackUrl))
        assertEquals(ArtworkProvenance.NONE, DesktopCanonicalImageCache.classifyProvenance(""))

        val key = DesktopCanonicalImageCache.heroKey("media_prov_test")
        DesktopCanonicalImageCache.registerOfficialArtworkUrl(key, officialUrl, linkedMediaId = "media_prov_test")
        assertEquals(officialUrl, DesktopCanonicalImageCache.getCanonicalUrl(key))

        // Attempting to overwrite official provenance with Drive frame capture is rejected
        DesktopCanonicalImageCache.registerOfficialArtworkUrl(key, driveUrl, linkedMediaId = "media_prov_test")
        assertEquals(officialUrl, DesktopCanonicalImageCache.getCanonicalUrl(key))
    }

    @Test
    fun testShelfSnapshotStoreAndFrameOneColdBootFallback() {
        val email = newTestEmail("shelf")
        DesktopWatchHistoryManager.clearInMemory()
        try {
            val epItem = DesktopContinueWatchingItem(
                mediaId = "ep_mentalist_s1e8",
                title = "The Mentalist",
                subtitle = "S1E8 • The Thin Red Line",
                thumbnailUrl = "https://drive.google.com/thumbnail?id=poisoned",
                fallbackThumbnailUrl = "",
                positionMs = 900_000L,
                durationMs = 2_600_000L,
                progressFraction = 0.346f,
                isSeries = true,
                seriesId = "series_mentalist",
                seasonNumber = 1,
                episodeNumber = 8,
                episodeTitle = "The Thin Red Line",
                timestamp = 1_750_000_200_000L
            )
            val movieItem = DesktopContinueWatchingItem(
                mediaId = "movie_top_gun",
                title = "Top Gun: Maverick",
                subtitle = "Action • Drama",
                thumbnailUrl = "https://image.tmdb.org/t/p/w780/maverick.jpg",
                fallbackThumbnailUrl = "",
                positionMs = 4_200_000L,
                durationMs = 7_620_000L,
                progressFraction = 0.55f,
                isSeries = false,
                timestamp = 1_750_000_100_000L
            )

            DesktopContinueWatchingSnapshotStore.writeShelfSnapshotBlocking(
                userEmail = email,
                items = listOf(movieItem, epItem),
                updatedAt = 1_750_000_200_000L,
                confirmedEmpty = false
            )
            DesktopContinueWatchingSnapshotStore.clearMemoryForProfileSwitch(email)
            DesktopCanonicalImageCache.clearMemory()

            val catalogSeries = listOf(
                DesktopMediaItem(
                    id = "series_mentalist",
                    title = "The Mentalist",
                    thumbnailUrl = "https://image.tmdb.org/t/p/w780/mentalist_poster.jpg",
                    isSeries = true
                )
            )

            // Simulate Frame 1 before Drive syncFromCloud runs (RAM history is empty)
            val frame1Items = DesktopWatchHistoryManager.getContinueWatching(email, emptyList(), catalogSeries)
            assertEquals(2, frame1Items.size)
            assertEquals("ep_mentalist_s1e8", frame1Items[0].mediaId)
            assertEquals("https://image.tmdb.org/t/p/w780/mentalist_poster.jpg", frame1Items[0].thumbnailUrl)
            assertEquals("movie_top_gun", frame1Items[1].mediaId)
            assertEquals(
                "https://image.tmdb.org/t/p/w780/mentalist_poster.jpg",
                DesktopCanonicalImageCache.getCanonicalUrl(DesktopCanonicalImageCache.heroKey("ep_mentalist_s1e8"))
            )
            assertEquals(
                "https://image.tmdb.org/t/p/w780/maverick.jpg",
                DesktopCanonicalImageCache.getCanonicalUrl(DesktopCanonicalImageCache.moviePosterKey("movie_top_gun"))
            )
        } finally {
            DesktopWatchHistoryManager.clearInMemory()
        }
    }

    @Test
    fun testPriorityProtectedPruningAndArchiveSelfHealing() {
        val email = newTestEmail("prune")
        DesktopWatchHistoryManager.clearInMemory()
        try {
            DesktopWatchHistoryManager.updateEntries {
                // Seed two older in-progress movies
                this["maverick_title"] = DesktopTypedEntry("string", "Top Gun: Maverick")
                this["maverick_pos"] = DesktopTypedEntry("long", 3_600_000L)
                this["maverick_dur"] = DesktopTypedEntry("long", 7_020_000L)
                this["maverick_fraction"] = DesktopTypedEntry("float", 0.51f)
                this["maverick_timestamp"] = DesktopTypedEntry("long", 1_750_000_001_000L)
                this["maverick_is_watched"] = DesktopTypedEntry("boolean", false)
                this["maverick_is_series"] = DesktopTypedEntry("boolean", false)

                this["crime101_title"] = DesktopTypedEntry("string", "Crime 101")
                this["crime101_pos"] = DesktopTypedEntry("long", 2_400_000L)
                this["crime101_dur"] = DesktopTypedEntry("long", 5_640_000L)
                this["crime101_fraction"] = DesktopTypedEntry("float", 0.42f)
                this["crime101_timestamp"] = DesktopTypedEntry("long", 1_750_000_002_000L)
                this["crime101_is_watched"] = DesktopTypedEntry("boolean", false)
                this["crime101_is_series"] = DesktopTypedEntry("boolean", false)

                // Seed 55 newer watched episodes (exceeds MAX_ACTIVE_WINDOW = 50)
                for (i in 1..55) {
                    val epId = "ep_watched_$i"
                    this["${epId}_title"] = DesktopTypedEntry("string", "Episode $i")
                    this["${epId}_pos"] = DesktopTypedEntry("long", 2_500_000L)
                    this["${epId}_dur"] = DesktopTypedEntry("long", 2_500_000L)
                    this["${epId}_fraction"] = DesktopTypedEntry("float", 1.0f)
                    this["${epId}_timestamp"] = DesktopTypedEntry("long", 1_750_000_100_000L + i)
                    this["${epId}_is_watched"] = DesktopTypedEntry("boolean", true)
                    this["${epId}_is_series"] = DesktopTypedEntry("boolean", true)
                    this["${epId}_series_id"] = DesktopTypedEntry("string", "mentalist_id")
                }
            }

            // Both older in-progress movies must be pinned in active window & retain exact resume positions
            assertEquals(3_600_000L, DesktopWatchHistoryManager.getPlaybackPosition(email, "maverick"))
            assertEquals(2_400_000L, DesktopWatchHistoryManager.getPlaybackPosition(email, "crime101"))
            assertTrue(DesktopWatchHistoryManager.hasSavedWatchProgress(email, "maverick"))
            assertTrue(DesktopWatchHistoryManager.snapshotArchivedIds().size >= 7)

            // Also verify self-healing promotion when replaceAllFromDrive receives an in-progress archived movie
            val promoted = DesktopWatchHistoryManager.replaceAllFromDrive(
                activeEntries = emptyMap(),
                archiveIds = emptySet(),
                archiveMetadata = mapOf(
                    "crime101" to DesktopArchivedHistoryEntry(
                        id = "crime101",
                        title = "Crime 101",
                        positionMs = 2_400_000L,
                        durationMs = 5_640_000L,
                        timestamp = 1_750_000_002_000L,
                        isWatched = false
                    )
                ),
                remoteLatestTs = 1_750_000_002_000L
            )
            assertTrue(promoted)
            assertEquals(2_400_000L, DesktopWatchHistoryManager.getPlaybackPosition(email, "crime101"))
            assertFalse(DesktopWatchHistoryManager.snapshotArchivedItems().containsKey("crime101"))
        } finally {
            DesktopWatchHistoryManager.clearInMemory()
        }
    }

    @Test
    fun testColdBootMetadataKeyAndCatalogIndependentFallback() {
        val email = newTestEmail("cold_boot_fixes")
        DesktopWatchHistoryManager.clearInMemory()
        try {
            // Bug 1: volatileEntries containing ONLY global_latest_timestamp must NOT skip cw_shelf_snapshot.json
            DesktopContinueWatchingSnapshotStore.writeShelfSnapshotBlocking(
                userEmail = email,
                items = listOf(
                    DesktopContinueWatchingItem(
                        mediaId = "movie_cold_1",
                        title = "Cold Movie",
                        subtitle = "Sci-Fi",
                        thumbnailUrl = "https://image.tmdb.org/t/p/w780/cold.jpg",
                        positionMs = 1_200_000L,
                        durationMs = 6_000_000L,
                        progressFraction = 0.2f,
                        isSeries = false,
                        timestamp = 1_750_000_500_000L
                    )
                ),
                updatedAt = 1_750_000_500_000L,
                confirmedEmpty = false
            )
            DesktopWatchHistoryManager.updateEntries {
                this["global_latest_timestamp"] = DesktopTypedEntry("long", 1_750_000_600_000L)
            }
            val coldItems = DesktopWatchHistoryManager.getContinueWatching(email, emptyList(), emptyList())
            assertEquals(1, coldItems.size)
            assertEquals("movie_cold_1", coldItems.first().mediaId)

            // Bug 3: if Hero snapshot holds an active item >= empty shelf update, shelf store refuses confirmedEmpty poison
            DesktopContinueWatchingSnapshotStore.writeSnapshotBlocking(
                userEmail = email,
                data = ContinueWatchingDisplayData(
                    mediaId = "hero_active_1",
                    displayTitle = "Active Hero",
                    canonicalArtworkUrl = "https://image.tmdb.org/t/p/original/hero_active.jpg",
                    progressFraction = 0.45f,
                    currentPositionMs = 1_800_000L,
                    durationMs = 4_000_000L,
                    updatedAt = 1_750_000_900_000L,
                    confirmedEmpty = false
                )
            )
            DesktopContinueWatchingSnapshotStore.writeShelfSnapshotBlocking(
                userEmail = email,
                items = emptyList(),
                updatedAt = 1_750_000_800_000L,
                confirmedEmpty = true
            )
            val heroFallbackShelf = DesktopWatchHistoryManager.getContinueWatching(email, emptyList(), emptyList())
            assertEquals(1, heroFallbackShelf.size)
            assertEquals("hero_active_1", heroFallbackShelf.first().mediaId)

            // Bug 2: once RAM has real media entries, getContinueWatching works even with empty movies/series lists
            DesktopWatchHistoryManager.updateEntries {
                this["uncataloged_m1_title"] = DesktopTypedEntry("string", "Uncataloged Movie")
                this["uncataloged_m1_pos"] = DesktopTypedEntry("long", 900_000L)
                this["uncataloged_m1_dur"] = DesktopTypedEntry("long", 3_600_000L)
                this["uncataloged_m1_fraction"] = DesktopTypedEntry("float", 0.25f)
                this["uncataloged_m1_timestamp"] = DesktopTypedEntry("long", 1_750_001_000_000L)
                this["uncataloged_m1_is_watched"] = DesktopTypedEntry("boolean", false)
                this["uncataloged_m1_is_series"] = DesktopTypedEntry("boolean", false)
            }
            val uncatalogedItems = DesktopWatchHistoryManager.getContinueWatching(email, emptyList(), emptyList())
            assertTrue(uncatalogedItems.any { it.mediaId == "uncataloged_m1" })
        } finally {
            DesktopWatchHistoryManager.clearInMemory()
        }
    }

    @Test
    fun testReadShelfSnapshotCrossReconcilesWithHeroAndWatchedStatus() {
        val email = newTestEmail("reconcile_shelf")
        DesktopWatchHistoryManager.clearInMemory()
        try {
            // Seed older shelf containing two items
            val staleMovie = DesktopContinueWatchingItem(
                mediaId = "movie_stale",
                title = "Stale Movie",
                subtitle = "Thriller",
                thumbnailUrl = "https://image.tmdb.org/t/p/w780/stale.jpg",
                positionMs = 1_500_000L,
                durationMs = 6_000_000L,
                progressFraction = 0.25f,
                isSeries = false,
                timestamp = 1_750_000_100_000L
            )
            DesktopContinueWatchingSnapshotStore.writeShelfSnapshotBlocking(
                userEmail = email,
                items = listOf(staleMovie),
                updatedAt = 1_750_000_100_000L,
                confirmedEmpty = false
            )

            // Hero snapshot advances to a newer episode while shelf file still has only the old item
            DesktopContinueWatchingSnapshotStore.writeSnapshotBlocking(
                userEmail = email,
                data = ContinueWatchingDisplayData(
                    mediaId = "ep_mentalist_8",
                    seriesId = "series_mentalist",
                    isSeries = true,
                    displayHeader = "RESUME S1E8",
                    displayTitle = "The Mentalist",
                    seriesTitle = "The Mentalist",
                    seasonNumber = 1,
                    episodeNumber = 8,
                    episodeTitle = "The Thin Red Line",
                    canonicalArtworkUrl = "https://image.tmdb.org/t/p/original/mentalist.jpg",
                    progressFraction = 0.35f,
                    currentPositionMs = 900_000L,
                    durationMs = 2_600_000L,
                    updatedAt = 1_750_000_500_000L,
                    confirmedEmpty = false
                )
            )
            DesktopContinueWatchingSnapshotStore.clearMemoryForProfileSwitch(email)

            // Reconciled shelf must prepend Mentalist S1E8 at index 0 and keep older items behind it
            val reconciled = DesktopContinueWatchingSnapshotStore.readShelfSnapshot(email)
            assertNotNull(reconciled)
            assertFalse(reconciled.confirmedEmpty)
            assertEquals(2, reconciled.items.size)
            assertEquals("ep_mentalist_8", reconciled.items[0].mediaId)
            assertEquals("movie_stale", reconciled.items[1].mediaId)
            assertEquals(
                "https://image.tmdb.org/t/p/original/mentalist.jpg",
                DesktopCanonicalImageCache.getCanonicalUrl(DesktopCanonicalImageCache.seriesPosterKey("series_mentalist"))
            )

            // When Hero confirms empty at a newer timestamp, readShelfSnapshot yields confirmedEmpty = true
            DesktopContinueWatchingSnapshotStore.writeSnapshotBlocking(
                userEmail = email,
                data = ContinueWatchingDisplayData(
                    mediaId = "",
                    updatedAt = 1_750_000_900_000L,
                    confirmedEmpty = true
                )
            )
            DesktopContinueWatchingSnapshotStore.clearMemoryForProfileSwitch(email)
            val emptyReconciled = DesktopContinueWatchingSnapshotStore.readShelfSnapshot(email)
            assertNotNull(emptyReconciled)
            assertTrue(emptyReconciled.confirmedEmpty)
            assertTrue(emptyReconciled.items.isEmpty())
        } finally {
            DesktopWatchHistoryManager.clearInMemory()
        }
    }
}
