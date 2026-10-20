import { useMemo, useState } from "react"

type IconName = "home" | "movies" | "tv" | "clock" | "settings" | "search" | "cloud" | "play" | "plus" | "chevron" | "menu" | "volume" | "expand" | "skip" | "check"

const iconPaths: Record<IconName, React.ReactNode> = {
  home: (
    <>
      <path d="m3 11 9-8 9 8" />
      <path d="M5 10v10h14V10M9 20v-6h6v6" />
    </>
  ),
  movies: (
    <>
      <rect x="3" y="5" width="18" height="14" rx="2" />
      <path d="m7 5 3 4m1-4 3 4m1-4 3 4M3 9h18" />
    </>
  ),
  tv: (
    <>
      <rect x="3" y="6" width="18" height="14" rx="2" />
      <path d="m8 2 4 4 4-4" />
    </>
  ),
  clock: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </>
  ),
  settings: (
    <>
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.83 2.83-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 .6 1.7 1.7 0 0 0-.4 1.1V21h-4v-.1A1.7 1.7 0 0 0 8.6 19.4a1.7 1.7 0 0 0-1.88.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-.6-1 1.7 1.7 0 0 0-1.1-.4H3v-4h.1A1.7 1.7 0 0 0 4.6 8.6a1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-.6 1.7 1.7 0 0 0 .4-1.1V3h4v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.88-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.4 9c.14.36.35.7.6 1 .3.3.7.4 1.1.4h.1v4h-.1a1.7 1.7 0 0 0-1.7.6Z" />
    </>
  ),
  search: (
    <>
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-4-4" />
    </>
  ),
  cloud: (
    <path d="M7 18h11a4 4 0 0 0 .5-7.97A7 7 0 0 0 5.2 8.2 5 5 0 0 0 7 18Z" />
  ),
  play: <path d="m9 7 8 5-8 5V7Z" />,
  plus: (
    <>
      <path d="M12 5v14M5 12h14" />
    </>
  ),
  chevron: <path d="m9 18 6-6-6-6" />,
  menu: (
    <>
      <path d="M5 7h14M5 12h14M5 17h14" />
    </>
  ),
  volume: (
    <>
      <path d="M11 6 7 10H4v4h3l4 4V6Z" />
      <path d="M15 9a4 4 0 0 1 0 6M18 6a8 8 0 0 1 0 12" />
    </>
  ),
  expand: (
    <>
      <path d="M8 3H3v5M16 3h5v5M8 21H3v-5M16 21h5v-5" />
    </>
  ),
  skip: (
    <>
      <path d="m5 7 7 5-7 5V7Zm7 0 7 5-7 5V7Z" />
    </>
  ),
  check: <path d="m5 12 4 4L19 6" />,
}

function Icon({ name, size = 20 }: { name: IconName; size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {iconPaths[name]}
    </svg>
  )
}

const images = {
  hero: "https://images.unsplash.com/photo-1536846118400-dc03751fd172?auto=format&fit=crop&w=1800&q=85",
  mountain:
    "https://images.unsplash.com/photo-1748451470625-a68329ecba44?auto=format&fit=crop&w=900&q=80",
  coast:
    "https://images.unsplash.com/photo-1766699623153-096b7ff8b5d1?auto=format&fit=crop&w=900&q=80",
  ice: "https://images.unsplash.com/photo-1701143918294-2beb84c3b524?auto=format&fit=crop&w=900&q=80",
  tokyo:
    "https://images.unsplash.com/photo-1601042879364-f3947d3f9c16?auto=format&fit=crop&w=900&q=80",
  skyline:
    "https://images.unsplash.com/photo-1535391879778-3bae11d29a24?auto=format&fit=crop&w=900&q=80",
  night:
    "https://images.unsplash.com/photo-1519608487953-e999c86e7455?auto=format&fit=crop&w=900&q=80",
  mist: "https://images.unsplash.com/photo-1759505448644-8e36ede6f120?auto=format&fit=crop&w=900&q=80",
}

type Media = {
  title: string
  genre: string
  image: string
  duration: string
  quality: string
  progress?: number
}

const continueWatching: Media[] = [
  {
    title: "Northline",
    genre: "S1 E4 · Into the White",
    image: images.hero,
    duration: "28m left",
    quality: "4K",
    progress: 61,
  },
  {
    title: "Neon Run",
    genre: "Action · Sci-Fi",
    image: images.tokyo,
    duration: "42m left",
    quality: "UHD",
    progress: 34,
  },
  {
    title: "Aether",
    genre: "Mystery · Drama",
    image: images.mist,
    duration: "19m left",
    quality: "HD",
    progress: 72,
  },
  {
    title: "After Dark",
    genre: "S2 E7 · The Signal",
    image: images.night,
    duration: "31m left",
    quality: "4K",
    progress: 46,
  },
]

const trending: Media[] = [
  {
    title: "The Last Ascent",
    genre: "Adventure · 2025",
    image: images.mountain,
    duration: "1h 48m",
    quality: "4K",
  },
  {
    title: "Silent Current",
    genre: "Documentary · 2024",
    image: images.coast,
    duration: "54m",
    quality: "UHD",
  },
  {
    title: "Glass Horizon",
    genre: "Thriller · 2025",
    image: images.skyline,
    duration: "2h 06m",
    quality: "4K",
  },
  {
    title: "Northern Light",
    genre: "Drama · 2024",
    image: images.ice,
    duration: "1h 52m",
    quality: "HD",
  },
]

const movieCatalog: Media[] = [
  {
    title: "Glass Horizon",
    genre: "Thriller · 2025",
    image: images.skyline,
    duration: "2h 06m",
    quality: "4K",
  },
  {
    title: "The Last Ascent",
    genre: "Adventure · 2025",
    image: images.mountain,
    duration: "1h 48m",
    quality: "4K",
  },
  {
    title: "Neon Run",
    genre: "Action · 2024",
    image: images.tokyo,
    duration: "1h 57m",
    quality: "UHD",
  },
  {
    title: "Silent Current",
    genre: "Documentary · 2024",
    image: images.coast,
    duration: "1h 22m",
    quality: "UHD",
  },
  {
    title: "Aether",
    genre: "Mystery · 2025",
    image: images.mist,
    duration: "1h 44m",
    quality: "4K",
  },
  {
    title: "Northern Light",
    genre: "Drama · 2024",
    image: images.ice,
    duration: "1h 52m",
    quality: "HD",
  },
  {
    title: "After Dark",
    genre: "Thriller · 2023",
    image: images.night,
    duration: "2h 11m",
    quality: "4K",
  },
  {
    title: "Edge of Winter",
    genre: "Adventure · 2025",
    image: images.hero,
    duration: "1h 39m",
    quality: "UHD",
  },
]

const episodes = [
  {
    number: 1,
    title: "The Departure",
    duration: "48 min",
    progress: 100,
    image: images.mountain,
  },
  {
    number: 2,
    title: "Thin Ice",
    duration: "51 min",
    progress: 100,
    image: images.coast,
  },
  {
    number: 3,
    title: "White Silence",
    duration: "46 min",
    progress: 100,
    image: images.ice,
  },
  {
    number: 4,
    title: "Into the White",
    duration: "53 min",
    progress: 61,
    image: images.hero,
  },
  {
    number: 5,
    title: "No Way Back",
    duration: "49 min",
    progress: 0,
    image: images.mist,
  },
]

function MediaCard({
  item,
  onPlay,
}: {
  item: Media
  onPlay: (item: Media) => void
}) {
  return (
    <button
      className="media-card"
      onClick={() => onPlay(item)}
      aria-label={`Play ${item.title}`}
    >
      <img src={item.image} alt="" />
      <span className="quality-badge">{item.quality}</span>
      <span className="duration-badge">{item.duration}</span>
      <span className="card-scrim" />
      <span className="card-copy">
        <strong>{item.title}</strong>
        <small>{item.genre}</small>
      </span>
      <span className="card-play">
        <Icon name="play" size={18} />
      </span>
      {item.progress !== undefined && (
        <span className="progress-track">
          <span style={{ width: `${item.progress}%` }} />
        </span>
      )}
    </button>
  )
}

function MoviesPage({ onPlay }: { onPlay: (item: Media) => void }) {
  const [genre, setGenre] = useState("All")
  const [sort, setSort] = useState("Popular")
  const genres = [
    "All",
    "Action",
    "Adventure",
    "Drama",
    "Thriller",
    "Mystery",
    "Documentary",
  ]
  const visibleMovies =
    genre === "All"
      ? movieCatalog
      : movieCatalog.filter((movie) => movie.genre.includes(genre))

  return (
    <div className="movies-page">
      <div className="page-intro">
        <div>
          <span className="eyebrow">EXPLORE THE COLLECTION</span>
          <h1>Movies</h1>
          <p>Blockbusters, hidden gems, and stories worth staying in for.</p>
        </div>
        <div className="library-stat">
          <strong>1,284</strong>
          <span>movies available</span>
        </div>
      </div>

      <section className="movie-feature">
        <img src={images.skyline} alt="Glass Horizon city skyline at night" />
        <div className="movie-feature-overlay" />
        <div className="movie-feature-copy">
          <span className="premiere-label">
            <i /> NEW PREMIERE
          </span>
          <h2>
            GLASS
            <br />
            <span>HORIZON</span>
          </h2>
          <div className="meta-line">
            <span>2025</span>
            <i />
            <span>16+</span>
            <i />
            <span>2h 06m</span>
            <i />
            <span>Thriller</span>
          </div>
          <p>
            A systems architect uncovers a signal buried beneath the city’s
            infrastructure—and realizes someone has been waiting for her to find
            it.
          </p>
          <div className="hero-actions">
            <button
              className="primary-button"
              onClick={() => onPlay(movieCatalog[0])}
            >
              <Icon name="play" /> Watch now
            </button>
            <button className="secondary-button">
              <Icon name="plus" /> My list
            </button>
          </div>
        </div>
        <div className="feature-rating">
          <strong>8.7</strong>
          <span>Viewer score</span>
        </div>
      </section>

      <section className="movie-discovery">
        <div className="discovery-toolbar">
          <div className="genre-tabs" aria-label="Filter movies by genre">
            {genres.map((item) => (
              <button
                key={item}
                className={genre === item ? "active" : ""}
                onClick={() => setGenre(item)}
              >
                {item}
              </button>
            ))}
          </div>
          <label className="sort-control">
            <span>Sort by</span>
            <select
              value={sort}
              onChange={(event) => setSort(event.target.value)}
            >
              <option>Popular</option>
              <option>Newest</option>
              <option>Top rated</option>
            </select>
          </label>
        </div>

        <div className="section-heading movie-grid-heading">
          <div>
            <span className="eyebrow">CURATED FOR YOU</span>
            <h2>{genre === "All" ? "Explore movies" : `${genre} movies`}</h2>
          </div>
          <span className="result-count">{visibleMovies.length} titles</span>
        </div>
        <div className="media-grid movies-grid">
          {visibleMovies.map((movie) => (
            <MediaCard key={movie.title} item={movie} onPlay={onPlay} />
          ))}
        </div>
      </section>

      <section className="top-picks">
        <div className="section-heading">
          <div>
            <span className="eyebrow">CLOTUBE CHARTS</span>
            <h2>Top 4 this week</h2>
          </div>
          <button className="text-button">
            See full chart <Icon name="chevron" size={16} />
          </button>
        </div>
        <div className="ranked-grid">
          {movieCatalog.slice(0, 4).map((movie, index) => (
            <button
              className="ranked-card"
              key={movie.title}
              onClick={() => onPlay(movie)}
            >
              <span className="rank-number">0{index + 1}</span>
              <img src={movie.image} alt="" />
              <span className="ranked-copy">
                <strong>{movie.title}</strong>
                <small>
                  {movie.genre} · {movie.quality}
                </small>
              </span>
              <span className="ranked-play">
                <Icon name="play" size={15} />
              </span>
            </button>
          ))}
        </div>
      </section>
    </div>
  )
}

export default function App() {
  const [collapsed, setCollapsed] = useState(false)
  const [activeNav, setActiveNav] = useState("Home")
  const [search, setSearch] = useState("")
  const [playing, setPlaying] = useState(true)
  const [selectedEpisode, setSelectedEpisode] = useState(3)
  const [showPlayer, setShowPlayer] = useState(false)

  const allMedia = [...continueWatching, ...trending, ...movieCatalog]
  const filtered = useMemo(
    () =>
      allMedia.filter((item) =>
        `${item.title} ${item.genre}`
          .toLowerCase()
          .includes(search.toLowerCase()),
      ),
    [search],
  )

  const selectMedia = (item: Media) => {
    if (item.title === "Northline") {
      setShowPlayer(true)
      window.setTimeout(
        () =>
          document
            .getElementById("watch")
            ?.scrollIntoView({ behavior: "smooth" }),
        20,
      )
    } else {
      setPlaying(true)
    }
  }

  const navItems: { label: string; icon: IconName }[] = [
    { label: "Home", icon: "home" },
    { label: "Movies", icon: "movies" },
    { label: "TV Series", icon: "tv" },
    { label: "Recents", icon: "clock" },
  ]

  return (
    <div className={`app-shell ${collapsed ? "sidebar-collapsed" : ""}`}>
      <aside className="sidebar">
        <div className="brand-row">
          <div className="brand-mark">
            <span />
          </div>
          {!collapsed && <span className="brand-name">Clotube</span>}
          <button
            className="icon-button collapse-button"
            onClick={() => setCollapsed(!collapsed)}
            aria-label="Toggle sidebar"
          >
            <Icon name="menu" />
          </button>
        </div>

        <nav className="main-nav">
          <span className="nav-label">{!collapsed ? "BROWSE" : ""}</span>
          {navItems.map((item) => (
            <button
              key={item.label}
              className={`nav-item ${activeNav === item.label ? "active" : ""}`}
              onClick={() => {
                setActiveNav(item.label)
                setSearch("")
              }}
            >
              <Icon name={item.icon} />
              {!collapsed && <span>{item.label}</span>}
              {item.label === "Recents" && !collapsed && <small>4</small>}
            </button>
          ))}
        </nav>

        <div className="sidebar-bottom">
          <button className="nav-item" onClick={() => setActiveNav("Settings")}>
            <Icon name="settings" />
            {!collapsed && <span>Settings</span>}
          </button>
          {!collapsed && (
            <div className="storage-card">
              <div className="storage-title">
                <Icon name="cloud" size={17} />
                <span>Cloud library</span>
                <strong>68%</strong>
              </div>
              <div className="storage-bar">
                <span />
              </div>
              <small>67.8 GB of 100 GB</small>
            </div>
          )}
        </div>
      </aside>

      <main className="main">
        <header className="topbar">
          <div className="search-wrap">
            <Icon name="search" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search movies, series..."
              aria-label="Search movies and series"
            />
            <kbd>⌘ K</kbd>
          </div>
          <div className="top-actions">
            <div className="stream-badge">
              <span className="live-dot" />
              <Icon name="cloud" size={17} />
              <span>Direct stream</span>
            </div>
            <button className="profile-button" aria-label="Open profile menu">
              <span className="avatar">DA</span>
              <span className="profile-copy">
                <strong>Daniel A.</strong>
                <small>Google account</small>
              </span>
              <Icon name="chevron" size={15} />
            </button>
          </div>
        </header>

        <div className="content">
          {search ? (
            <section className="search-results">
              <div className="section-heading">
                <div>
                  <span className="eyebrow">SEARCH</span>
                  <h2>Results for “{search}”</h2>
                </div>
                <span className="result-count">{filtered.length} titles</span>
              </div>
              <div className="media-grid">
                {filtered.map((item) => (
                  <MediaCard
                    key={`${item.title}-${item.genre}`}
                    item={item}
                    onPlay={selectMedia}
                  />
                ))}
              </div>
              {filtered.length === 0 && (
                <div className="empty-state">
                  No titles found. Try another search.
                </div>
              )}
            </section>
          ) : activeNav === "Movies" ? (
            <MoviesPage onPlay={selectMedia} />
          ) : (
            <>
              <section className="hero">
                <img
                  src={images.hero}
                  alt="Snow-covered mountain valley featured in Northline"
                />
                <div className="hero-overlay" />
                <div className="hero-content">
                  <div className="clotube-original">
                    <span /> A CLOTUBE ORIGINAL
                  </div>
                  <h1>
                    NORTH<span>LINE</span>
                  </h1>
                  <div className="meta-line">
                    <span>2025</span>
                    <i />
                    <span>16+</span>
                    <i />
                    <span>1 Season</span>
                    <i />
                    <span>Adventure · Drama</span>
                  </div>
                  <p>
                    Beyond the last road, survival becomes a choice. Follow a
                    team of explorers into the untamed Arctic as every step
                    takes them further from home.
                  </p>
                  <div className="hero-actions">
                    <button
                      className="primary-button"
                      onClick={() => selectMedia(continueWatching[0])}
                    >
                      <Icon name="play" /> Resume watching
                    </button>
                    <button className="secondary-button">
                      <Icon name="plus" /> My list
                    </button>
                  </div>
                </div>
                <div className="hero-footer">
                  <span>
                    <Icon name="volume" size={16} /> Dolby Atmos
                  </span>
                  <span>4K UHD</span>
                  <span>HDR</span>
                  <div className="hero-progress">
                    <span />
                    <small>Episode 4 · 28 min left</small>
                  </div>
                </div>
              </section>

              <section className="shelf">
                <div className="section-heading">
                  <div>
                    <span className="eyebrow">PICK UP WHERE YOU LEFT OFF</span>
                    <h2>Continue watching</h2>
                  </div>
                  <button className="text-button">
                    View all <Icon name="chevron" size={16} />
                  </button>
                </div>
                <div className="media-grid">
                  {continueWatching.map((item) => (
                    <MediaCard
                      key={item.title}
                      item={item}
                      onPlay={selectMedia}
                    />
                  ))}
                </div>
              </section>

              <section className="shelf">
                <div className="section-heading">
                  <div>
                    <span className="eyebrow">POPULAR THIS WEEK</span>
                    <h2>Trending now</h2>
                  </div>
                  <button className="text-button">
                    View all <Icon name="chevron" size={16} />
                  </button>
                </div>
                <div className="media-grid">
                  {trending.map((item) => (
                    <MediaCard
                      key={item.title}
                      item={item}
                      onPlay={selectMedia}
                    />
                  ))}
                </div>
              </section>

              {showPlayer && (
                <section id="watch" className="watch-section">
                  <div className="section-heading">
                    <div>
                      <span className="eyebrow">NOW PLAYING</span>
                      <h2>Northline · Season 1</h2>
                    </div>
                    <button
                      className="text-button"
                      onClick={() => setShowPlayer(false)}
                    >
                      Close player
                    </button>
                  </div>
                  <div className="watch-layout">
                    <div className="player">
                      <img src={episodes[selectedEpisode].image} alt="" />
                      <div className="player-vignette" />
                      <button
                        className="big-play"
                        onClick={() => setPlaying(!playing)}
                        aria-label={playing ? "Pause episode" : "Play episode"}
                      >
                        {playing ? (
                          <span className="pause-icon">
                            <i />
                            <i />
                          </span>
                        ) : (
                          <Icon name="play" size={30} />
                        )}
                      </button>
                      <div className="player-title">
                        <small>YOU’RE WATCHING</small>
                        <strong>
                          S1 E{episodes[selectedEpisode].number} ·{" "}
                          {episodes[selectedEpisode].title}
                        </strong>
                      </div>
                      <div className="player-controls">
                        <button
                          onClick={() => setPlaying(!playing)}
                          aria-label="Play or pause"
                        >
                          <Icon name="play" size={18} />
                        </button>
                        <button aria-label="Skip forward">
                          <Icon name="skip" size={18} />
                        </button>
                        <div className="playback-track">
                          <span
                            style={{
                              width: `${episodes[selectedEpisode].progress}%`,
                            }}
                          />
                        </div>
                        <span>
                          32:14 / {episodes[selectedEpisode].duration}
                        </span>
                        <button aria-label="Volume">
                          <Icon name="volume" size={18} />
                        </button>
                        <button aria-label="Full screen">
                          <Icon name="expand" size={18} />
                        </button>
                      </div>
                    </div>

                    <aside className="episode-panel">
                      <div className="episode-panel-head">
                        <div>
                          <small>EPISODE QUEUE</small>
                          <strong>Northline</strong>
                        </div>
                        <select aria-label="Choose season">
                          <option>Season 1</option>
                        </select>
                      </div>
                      <div className="episode-list">
                        {episodes.map((episode, index) => (
                          <button
                            key={episode.number}
                            className={`episode-item ${
                              selectedEpisode === index ? "selected" : ""
                            }`}
                            onClick={() => {
                              setSelectedEpisode(index)
                              setPlaying(true)
                            }}
                          >
                            <div className="episode-thumb">
                              <img src={episode.image} alt="" />
                              {episode.progress === 100 ? (
                                <span className="watched">
                                  <Icon name="check" size={14} />
                                </span>
                              ) : (
                                <span className="episode-number">
                                  {episode.number}
                                </span>
                              )}
                              {episode.progress > 0 && (
                                <span className="episode-progress">
                                  <span
                                    style={{ width: `${episode.progress}%` }}
                                  />
                                </span>
                              )}
                            </div>
                            <span className="episode-copy">
                              <strong>{episode.title}</strong>
                              <small>
                                Episode {episode.number} · {episode.duration}
                              </small>
                            </span>
                            {selectedEpisode === index && (
                              <span className="playing-bars">
                                <i />
                                <i />
                                <i />
                              </span>
                            )}
                          </button>
                        ))}
                      </div>
                    </aside>
                  </div>
                </section>
              )}
            </>
          )}
        </div>
      </main>
    </div>
  )
}
