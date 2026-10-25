package com.clotube.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ParsingTest {

    @Test
    fun testParseSeasonNumberFromFolder() {
        assertEquals(1, DesktopOnlineMetadataResolver.parseSeasonNumberFromFolder("Season 1"))
        assertEquals(2, DesktopOnlineMetadataResolver.parseSeasonNumberFromFolder("Season 02"))
        assertEquals(1, DesktopOnlineMetadataResolver.parseSeasonNumberFromFolder("S1"))
        assertEquals(3, DesktopOnlineMetadataResolver.parseSeasonNumberFromFolder("S03"))
        assertEquals(2, DesktopOnlineMetadataResolver.parseSeasonNumberFromFolder("Season - 2"))
        assertEquals(1, DesktopOnlineMetadataResolver.parseSeasonNumberFromFolder("Series 1"))
        assertEquals(2, DesktopOnlineMetadataResolver.parseSeasonNumberFromFolder("S02"))
        assertEquals(3, DesktopOnlineMetadataResolver.parseSeasonNumberFromFolder("Season 3"))
        assertEquals(1, DesktopOnlineMetadataResolver.parseSeasonNumberFromFolder("Unknown Folder"))
        assertEquals(1, DesktopOnlineMetadataResolver.parseSeasonNumberFromFolder("01"))
        assertEquals(2, DesktopOnlineMetadataResolver.parseSeasonNumberFromFolder("02"))
    }

    @Test
    fun testParseEpisodeNumberFromFileName() {
        // 1. S01E05 / s1e5
        assertEquals(5, DesktopOnlineMetadataResolver.parseEpisodeNumberFromFileName("Breaking.Bad.S01E05.mkv", 1))
        assertEquals(5, DesktopOnlineMetadataResolver.parseEpisodeNumberFromFileName("Dark.s1e5.mp4", 1))
        assertEquals(1, DesktopOnlineMetadataResolver.parseEpisodeNumberFromFileName("S01E01 - Secrets.mp4", 1))

        // 2. 1x05 / 01x05
        assertEquals(5, DesktopOnlineMetadataResolver.parseEpisodeNumberFromFileName("Show.1x05.mkv", 1))
        assertEquals(5, DesktopOnlineMetadataResolver.parseEpisodeNumberFromFileName("01x05 - Episode Name.mkv", 1))

        // 3. Episode 05 / Ep 5 / Ep.05
        assertEquals(5, DesktopOnlineMetadataResolver.parseEpisodeNumberFromFileName("Episode 05.mkv", 1))
        assertEquals(5, DesktopOnlineMetadataResolver.parseEpisodeNumberFromFileName("Ep 5.mp4", 1))
        assertEquals(5, DesktopOnlineMetadataResolver.parseEpisodeNumberFromFileName("Ep.05.mkv", 1))
        assertEquals(1, DesktopOnlineMetadataResolver.parseEpisodeNumberFromFileName("Episode 01.mkv", 2))

        // 4. Standalone leading or trailing numbers
        assertEquals(1, DesktopOnlineMetadataResolver.parseEpisodeNumberFromFileName("01 - Pilot.mkv", 1))
        assertEquals(1, DesktopOnlineMetadataResolver.parseEpisodeNumberFromFileName("01.mkv", 1))
        assertEquals(1, DesktopOnlineMetadataResolver.parseEpisodeNumberFromFileName("Pilot - 01.mp4", 1))
        assertEquals(1, DesktopOnlineMetadataResolver.parseEpisodeNumberFromFileName("01.mkv", 3))

        // Strips codec tags (1080p, x264, HEVC, AAC, etc.)
        assertEquals(5, DesktopOnlineMetadataResolver.parseEpisodeNumberFromFileName("Breaking.Bad.S01E05.1080p.BluRay.x264.AAC.mkv", 1))
        assertEquals(1, DesktopOnlineMetadataResolver.parseEpisodeNumberFromFileName("Fargo S03 01 (1080p HEVC).mkv", 3))
        assertEquals(2, DesktopOnlineMetadataResolver.parseEpisodeNumberFromFileName("02 - Chapter Two 720p x265.mkv", 1))
    }
}
