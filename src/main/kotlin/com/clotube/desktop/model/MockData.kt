package com.clotube.desktop.model

data class MediaItem(
    val id: String,
    val title: String,
    val genre: String,
    val duration: String,
    val quality: String = "4K UHD",
    val frameRate: String = "60FPS",
    val hdr: String = "HDR10",
    val codec: String = "HEVC",
    val year: String = "2025",
    val rating: String = "8.8",
    val description: String,
    val thumbnailUrl: String,
    val backdropUrl: String = thumbnailUrl,
    val progress: Float? = null,
    val isSeries: Boolean = false,
    val seasons: List<Season> = emptyList()
)

data class Season(
    val seasonNumber: Int,
    val title: String,
    val episodes: List<Episode>
)

data class Episode(
    val episodeNumber: Int,
    val title: String,
    val duration: String,
    val synopsis: String,
    val thumbnailUrl: String,
    val progress: Float? = null
)

object MockData {
    val heroBillboard = MediaItem(
        id = "movie-crime-101",
        title = "Crime 101",
        genre = "Crime • Thriller • Mystery",
        duration = "2h 18m",
        quality = "4K UHD",
        frameRate = "60FPS",
        hdr = "HDR10+",
        codec = "HEVC",
        year = "2025",
        rating = "9.2",
        description = "High-profile jewel thefts along the Pacific Coast have gone unsolved for years due to a strict code of ethics: zero casualties, meticulous planning, and disappearing without a trace. Detective Lou Lubesnick is the only one who sees the pattern.",
        thumbnailUrl = "https://images.unsplash.com/photo-1536846118400-dc03751fd172?auto=format&fit=crop&w=1200&q=80",
        backdropUrl = "https://images.unsplash.com/photo-1536846118400-dc03751fd172?auto=format&fit=crop&w=1800&q=85",
        progress = 0.45f
    )

    val continueWatchingList = listOf(
        MediaItem(
            id = "cw-northline",
            title = "Northline",
            genre = "S1 E4 • Into the White",
            duration = "28m left",
            quality = "4K",
            year = "2025",
            rating = "8.6",
            description = "Stranded above the Arctic Circle, an expedition team uncovers an abandoned covert facility buried beneath glacial permafrost.",
            thumbnailUrl = "https://images.unsplash.com/photo-1701143918294-2beb84c3b524?auto=format&fit=crop&w=900&q=80",
            progress = 0.61f,
            isSeries = true
        ),
        MediaItem(
            id = "cw-neon-run",
            title = "Neon Run",
            genre = "Action • Cyberpunk",
            duration = "42m left",
            quality = "UHD",
            year = "2024",
            rating = "8.4",
            description = "A memory courier must deliver an encrypted neural core across a rainfall-drenched neo-Tokyo underworld before his bio-lock expires.",
            thumbnailUrl = "https://images.unsplash.com/photo-1601042879364-f3947d3f9c16?auto=format&fit=crop&w=900&q=80",
            progress = 0.34f
        ),
        MediaItem(
            id = "cw-aether",
            title = "Aether",
            genre = "Sci-Fi • Mystery",
            duration = "19m left",
            quality = "4K",
            year = "2025",
            rating = "8.9",
            description = "Astronomers recording subterranean acoustic anomalies in the Atacama Desert intercept a synchronized transmission that shifts Earth's magnetosphere.",
            thumbnailUrl = "https://images.unsplash.com/photo-1759505448644-8e36ede6f120?auto=format&fit=crop&w=900&q=80",
            progress = 0.72f
        ),
        MediaItem(
            id = "cw-after-dark",
            title = "After Dark",
            genre = "S2 E7 • The Signal",
            duration = "31m left",
            quality = "4K",
            year = "2023",
            rating = "8.7",
            description = "Under the cover of night, rogue investigative journalists broadcast clandestine evidence implicating a global aerospace conglomerate.",
            thumbnailUrl = "https://images.unsplash.com/photo-1519608487953-e999c86e7455?auto=format&fit=crop&w=900&q=80",
            progress = 0.46f,
            isSeries = true
        )
    )

    val trendingMovies = listOf(
        MediaItem(
            id = "mov-top-gun",
            title = "Top Gun: Maverick",
            genre = "Action • Drama",
            duration = "2h 10m",
            quality = "4K UHD",
            frameRate = "60FPS",
            hdr = "Dolby Vision",
            codec = "HEVC",
            year = "2022",
            rating = "9.1",
            description = "After thirty years, Maverick is still pushing the envelope as a top naval aviator, but must confront ghosts of his past when he leads TOP GUN's elite graduates on a mission requiring the ultimate sacrifice.",
            thumbnailUrl = "https://images.unsplash.com/photo-1508614589041-895b88991e3e?auto=format&fit=crop&w=900&q=80"
        ),
        MediaItem(
            id = "mov-last-ascent",
            title = "The Last Ascent",
            genre = "Adventure • Documentary",
            duration = "1h 48m",
            quality = "4K",
            year = "2025",
            rating = "8.9",
            description = "Two mountaineers attempt the treacherous unclimbed eastern ridge of K2 in brutal mid-winter alpine style without supplemental oxygen.",
            thumbnailUrl = "https://images.unsplash.com/photo-1748451470625-a68329ecba44?auto=format&fit=crop&w=900&q=80"
        ),
        MediaItem(
            id = "mov-silent-current",
            title = "Silent Current",
            genre = "Thriller • Suspense",
            duration = "1h 54m",
            quality = "UHD",
            year = "2024",
            rating = "8.5",
            description = "A deep-sea acoustic research crew detects an unregistered submarine tracking bio-luminescent oceanic trenches near the Mariana Trench.",
            thumbnailUrl = "https://images.unsplash.com/photo-1766699623153-096b7ff8b5d1?auto=format&fit=crop&w=900&q=80"
        ),
        MediaItem(
            id = "mov-glass-horizon",
            title = "Glass Horizon",
            genre = "Neo-Noir • Thriller",
            duration = "2h 06m",
            quality = "4K",
            year = "2025",
            rating = "8.8",
            description = "A forensic architect examining the structural collapse of a mega-skyscraper in Hong Kong unearths an intentional sabotage conspiracy.",
            thumbnailUrl = "https://images.unsplash.com/photo-1535391879778-3bae11d29a24?auto=format&fit=crop&w=900&q=80"
        ),
        MediaItem(
            id = "mov-northern-light",
            title = "Northern Light",
            genre = "Drama • Sci-Fi",
            duration = "1h 52m",
            quality = "HD",
            year = "2024",
            rating = "8.3",
            description = "When the aurora borealis triggers anomalous localized time lapses across Norway, a physicist races to reconnect with her lost daughter.",
            thumbnailUrl = "https://images.unsplash.com/photo-1701143918294-2beb84c3b524?auto=format&fit=crop&w=900&q=80"
        )
    )

    val popularSeries = listOf(
        MediaItem(
            id = "series-severance",
            title = "Severance",
            genre = "Sci-Fi • Psychological Thriller",
            duration = "2 Seasons",
            quality = "4K UHD",
            frameRate = "60FPS",
            hdr = "HDR10+",
            codec = "HEVC",
            year = "2022-2025",
            rating = "9.0",
            description = "Mark leads a team of office workers whose memories have been surgically divided between their work and personal lives. When a mysterious colleague appears outside of work, it begins a journey to discover the truth about their jobs.",
            thumbnailUrl = "https://images.unsplash.com/photo-1519608487953-e999c86e7455?auto=format&fit=crop&w=900&q=80",
            backdropUrl = "https://images.unsplash.com/photo-1536846118400-dc03751fd172?auto=format&fit=crop&w=1800&q=85",
            isSeries = true,
            seasons = listOf(
                Season(
                    seasonNumber = 1,
                    title = "Season 1",
                    episodes = listOf(
                        Episode(1, "Good News About Hell", "57m", "Mark Scout leads a team at Lumon Industries, whose employees have undergone a severance procedure.", "https://images.unsplash.com/photo-1519608487953-e999c86e7455?auto=format&fit=crop&w=900&q=80", progress = 1.0f),
                        Episode(2, "Half Loop", "53m", "The Macrodata Refinement division trains new recruit Helly on numerical sorting protocols.", "https://images.unsplash.com/photo-1535391879778-3bae11d29a24?auto=format&fit=crop&w=900&q=80", progress = 1.0f),
                        Episode(3, "In Perpetuity", "54m", "Mark takes the team on a forbidden field trip to the Perpetuity Wing.", "https://images.unsplash.com/photo-1601042879364-f3947d3f9c16?auto=format&fit=crop&w=900&q=80", progress = 0.82f),
                        Episode(4, "The You You Are", "52m", "Helly undergoes aggressive disciplinary measures while Mark discovers contraband literature.", "https://images.unsplash.com/photo-1748451470625-a68329ecba44?auto=format&fit=crop&w=900&q=80", progress = 0.25f),
                        Episode(5, "The Grim Barbarity of Optics", "43m", "Irving explores Optics and Design while Dylan encounters startling department secrets.", "https://images.unsplash.com/photo-1766699623153-096b7ff8b5d1?auto=format&fit=crop&w=900&q=80", progress = null),
                        Episode(6, "Hide and Seek", "40m", "Cobel tightens security across the severed floor as the department hatches an audacious escape plan.", "https://images.unsplash.com/photo-1701143918294-2beb84c3b524?auto=format&fit=crop&w=900&q=80", progress = null)
                    )
                ),
                Season(
                    seasonNumber = 2,
                    title = "Season 2",
                    episodes = listOf(
                        Episode(1, "The Overtime Contingency", "58m", "In the aftermath of the gala broadcast, the severed employees face unprecedented recalibrations.", "https://images.unsplash.com/photo-1536846118400-dc03751fd172?auto=format&fit=crop&w=900&q=80", progress = null),
                        Episode(2, "The Board Speaks", "55m", "Lumon executive leadership introduces new algorithmic overseers to MDR.", "https://images.unsplash.com/photo-1508614589041-895b88991e3e?auto=format&fit=crop&w=900&q=80", progress = null)
                    )
                )
            )
        ),
        MediaItem(
            id = "series-mentalist",
            title = "The Mentalist",
            genre = "Crime • Mystery • Drama",
            duration = "7 Seasons",
            quality = "4K UHD",
            frameRate = "60FPS",
            hdr = "HDR10",
            codec = "HEVC",
            year = "2008-2015",
            rating = "8.8",
            description = "A famous former 'psychic' medium uses his razor-sharp observational skills to help the California Bureau of Investigation solve puzzling homicides while obsessively hunting the elusive serial killer Red John.",
            thumbnailUrl = "https://images.unsplash.com/photo-1748451470625-a68329ecba44?auto=format&fit=crop&w=900&q=80",
            backdropUrl = "https://images.unsplash.com/photo-1766699623153-096b7ff8b5d1?auto=format&fit=crop&w=1800&q=85",
            isSeries = true,
            seasons = listOf(
                Season(
                    seasonNumber = 1,
                    title = "Season 1",
                    episodes = listOf(
                        Episode(1, "Pilot", "48m", "Patrick Jane assists Senior Agent Teresa Lisbon in unraveling a double murder tied to a Palm Springs golf pro.", "https://images.unsplash.com/photo-1748451470625-a68329ecba44?auto=format&fit=crop&w=900&q=80", progress = 1.0f),
                        Episode(2, "Red Hair and Silver Tape", "44m", "The CBI team investigates the murder of a high school waitress found in Napa Valley.", "https://images.unsplash.com/photo-1601042879364-f3947d3f9c16?auto=format&fit=crop&w=900&q=80", progress = 1.0f),
                        Episode(3, "Red Tide", "43m", "When a young girl's body washes ashore on a state beach, Jane plays high-stakes psychological mind games with local surfers.", "https://images.unsplash.com/photo-1766699623153-096b7ff8b5d1?auto=format&fit=crop&w=900&q=80", progress = 0.55f),
                        Episode(4, "Ladies in Red", "45m", "Jane reconstructs the hidden lifestyle of a wealthy investment banker discovered in his panic room.", "https://images.unsplash.com/photo-1535391879778-3bae11d29a24?auto=format&fit=crop&w=900&q=80", progress = null),
                        Episode(5, "Redwood", "44m", "A teenage girl found covered in blood holding a murder weapon claims complete amnesia.", "https://images.unsplash.com/photo-1759505448644-8e36ede6f120?auto=format&fit=crop&w=900&q=80", progress = null)
                    )
                ),
                Season(
                    seasonNumber = 2,
                    title = "Season 2",
                    episodes = listOf(
                        Episode(1, "Redemption", "46m", "After the Red John investigation is reassigned to another unit, Jane considers leaving the CBI permanently.", "https://images.unsplash.com/photo-1519608487953-e999c86e7455?auto=format&fit=crop&w=900&q=80", progress = null),
                        Episode(2, "The Scarlet Letter", "43m", "The CBI is called to investigate the death of a prominent state senator's legislative aide.", "https://images.unsplash.com/photo-1701143918294-2beb84c3b524?auto=format&fit=crop&w=900&q=80", progress = null)
                    )
                )
            )
        )
    )

    val allMedia: List<MediaItem> = listOf(heroBillboard) + continueWatchingList + trendingMovies + popularSeries
}
