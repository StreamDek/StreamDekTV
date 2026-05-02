# StreamDek Android TV Architecture

## Product goal

Build a premium Android TV / Google TV app that feels cinematic, fast, and remote-first.

The TV client should:

- Use D-pad navigation and focus-aware controls
- Share the same account ecosystem as mobile and web
- Keep startup and navigation lightweight
- Feel distinct from the phone UI, not copied from it

## Recommended app shape

- React Native TV client in `streamdek-androidtv`
- Shared backend API contracts
- Dedicated account bootstrap and sync layer
- Reusable TV primitives for cards, rails, hero banners, overlays, and settings rows

## Navigation structure

Primary surfaces:

1. Home
2. Search
3. Library
4. Continue Watching
5. Watchlist
6. Settings
7. Auth / Pairing

Secondary pushed surfaces:

- Detail
- Player

## Screen design

### Home

- Hero banner
- Continue Watching rail
- Featured rails
- Trending / Popular rows
- Personalized recommendations when available

### Detail

- Backdrop art
- Poster
- Metadata
- Cast / credits
- Play / Resume action
- Trailer action
- Related content

### Search

- On-screen keyboard input
- Recent searches
- Fast result grid
- Type / genre / year filters

### Player

- Large TV-friendly overlay controls
- Seek / skip / subtitle / audio / stream switching
- Resume and next-episode flow
- Minimal motion and low overhead

### Settings

- Playback
- Subtitles
- Audio
- Theme
- Language
- Account
- Sync
- Device / session management

### Auth

- Email/password
- Registration
- Password reset
- Pairing-code flow for easier TV login

## Sync model

Shared state across devices:

- Profile
- Preferences
- Theme
- Language
- Subtitle preferences
- Audio preferences
- Stream preferences
- Watchlist
- Continue watching
- Playback progress
- Device/session metadata

### Current backend coverage

The backend already covers:

- Auth and profile
- Account bootstrap and preferences
- Devices and sessions
- TMDB discovery/detail/search
- Trakt watchlist/history/collection/playback
- Addons, debrid, and stream resolution

### Missing APIs recommended for TV parity

Add first-class playback sync endpoints:

- `GET /sync/progress`
- `PUT /sync/progress`
- `POST /sync/progress/batch`
- `DELETE /sync/progress/:entityType/:entityId`
- `GET /sync/continue-watching`
- `GET /sync/library`
- `POST /sync/heartbeat`

These should become the source of truth for progress and resume state across mobile, web, and TV.

## Component breakdown

- `ScreenShell`
- `Focusable`
- `Rail`
- `PosterCard`
- `HeroBanner`
- `ActionPill`
- `LoadingState`
- `EmptyState`
- `SettingsRow`
- `PlayerOverlay`

## Performance rules

- Virtualize rails and result grids
- Prefetch only visible artwork
- Keep animations subtle and native-driver friendly
- Avoid heavyweight blur/glass effects
- Keep focus changes cheap and predictable

## Suggested implementation order

1. App shell and auth
2. Account bootstrap and preference sync
3. Home rails and detail screen
4. Search and library screens
5. Player overlay and playback reporting
6. Settings and device/session management
7. Add missing backend sync endpoints

