# Clotube Desktop 🎬

A modern, cinematic streaming and media playback desktop web application crafted with React 19, Vite, TypeScript, and Tailwind CSS.

![Clotube Desktop Preview](https://images.unsplash.com/photo-1536846118400-dc03751fd172?auto=format&fit=crop&w=1200&q=80)

---

## ✨ Features

- 🌌 **Cinematic Dark Theme**: Custom dark aesthetics with glassmorphic cards, glowing accents, and high-contrast typography designed for desktop viewing.
- 📺 **Interactive Media Player**:
  - Full playback controls (Play/Pause, Fast-forward, Rewind)
  - Interactive scrub bar with real-time timestamp tracking
  - Volume slider and one-click mute toggle
  - Resolution selector (4K UHD, 1080p FHD, 720p HD)
  - Theater mode and Fullscreen support
- 🧭 **Multi-View Navigation**:
  - **Home**: Hero premiere showcase, "Continue Watching" row with progress tracking, and trending media carousel.
  - **Movies**: Full high-resolution film catalog with duration, rating, and genre tags.
  - **TV Series**: Multi-season breakdown with episode cards and watch history.
  - **Recents**: Quick-resume interface for recently played media.
  - **Settings**: Audio, video, and streaming quality preferences.
- 🔍 **Instant Search**: Real-time filtering across titles, genres, and release years.
- 📐 **Collapsible Sidebar**: Compact or expanded sidebar navigation with intuitive icons and keyboard accessibility.
- ⚡ **Ultra-Fast Performance**: Built on Vite 8 and React 19 with instantaneous hot module replacement.

---

## 🛠️ Tech Stack

- **Framework**: [React 19](https://react.dev/)
- **Build Tool**: [Vite 8](https://vite.dev/)
- **Styling**: [Tailwind CSS v4](https://tailwindcss.com/)
- **Language**: [TypeScript](https://www.typescriptlang.org/)
- **Code Formatter**: [oxfmt](https://github.com/oxc-project/oxc)

---

## 🚀 Getting Started

### Prerequisites

- [Node.js](https://nodejs.org/) (v18 or higher recommended)
- `npm` or `pnpm`

### Installation

1. **Clone the repository**:
   ```bash
   git clone https://github.com/theagent36/Clotube-Desktop.git
   cd Clotube-Desktop
   ```

2. **Install dependencies**:
   ```bash
   npm install
   # or
   pnpm install
   ```

3. **Start the development server**:
   ```bash
   npm run dev
   ```

4. **Build for production**:
   ```bash
   npm run build
   ```

5. **Preview production build**:
   ```bash
   npm run preview
   ```

---

## 📂 Project Structure

```text
Clotube-Desktop/
├── public/              # Static assets & icons
│   └── favicon.svg
├── src/
│   ├── App.tsx          # Main application component & player logic
│   ├── index.css        # Global CSS & Tailwind CSS styling
│   ├── main.tsx         # React root mounting
│   └── vite-env.d.ts    # TypeScript environment declarations
├── index.html           # HTML shell
├── package.json         # Project metadata and dependencies
├── tsconfig.json        # TypeScript configuration
└── vite.config.ts       # Vite build configuration
```

---

## 📄 License

This project is licensed under the MIT License.
