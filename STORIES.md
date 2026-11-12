# User stories

Status: all stories are **Backlog**. P0 establishes feasibility; P1 is required for MVP. IDs remain stable when moved to an issue tracker. Dependencies refer to story IDs. These are high-level stories to split into implementation tasks during development.

## Mock-first foundation

### FND-001 — Explore the app with mock content
**Priority:** P0 | **Dependencies:** none; no portal access required

As a viewer, I can navigate a working TV prototype with sample content before connecting a provider.

- The app launches with a mock provider behind a shared provider interface.
- Sidebar, poster grids, title details, channel/EPG screens, and sample playback work with a remote.
- Fixtures cover movies, series/seasons/episodes, channels, and programmes, including missing metadata and failure states.
- Sample media uses bundled or authorized content and requires no Stalker credentials.
- The real provider can later replace the mock implementation without rewriting UI screens.
- Mock evidence is recorded separately from real portal compatibility evidence.

Implementation breakdown: [Mock-first foundation tasks](FOUNDATION-TASKS.md).

## Provider connection

### PROV-001 — Validate portal compatibility
**Priority:** P0 for the real integration phase | **Dependencies:** FND-001, authorized test access

As a viewer, I can connect to a supported Stalker/Ministra portal so I can access my subscription.

- Handshake and provider-required authentication work using authorized credentials/device registration.
- Channels load and at least one supported live stream plays on a physical target box.
- Available movies, series, and EPG are checked and portal limitations documented without secrets.

Implementation breakdown: [PROV-001 tasks](PROV-001-TASKS.md).

### PROV-002 — Set up my provider
**Priority:** P1 | **Dependencies:** PROV-001

As a viewer, I can enter and validate my provider details so I can start watching.

- Setup collects a portal URL and the details required by the supported portal variant.
- Invalid URL, denied access, and unreachable portal produce distinct actionable messages.
- Successful setup opens the app and survives an app restart.
- Sensitive details are protected locally and omitted from logs and backups.

### PROV-003 — Manage provider data
**Priority:** P1 | **Dependencies:** PROV-002

As a viewer, I can refresh, edit, or remove my provider so my content stays current.

- Refresh reports loading, success, and failure without discarding the last usable catalogue on failure.
- Editing validates new details before replacing the working configuration.
- Removal asks for confirmation and clears associated credentials, catalogue, favourites, and history.

## TV navigation

### TVUI-001 — Navigate the sidebar
**Priority:** P1 | **Dependencies:** none; use sample content

As a viewer, I can choose a section from a left vertical menu using my remote.

- Home, Live TV, Movies, Series, Favourites, Search, and Settings are reachable.
- Selection and keyboard/remote focus are visually distinguishable.
- Right enters content; Back returns predictably without trapping focus.

### TVUI-002 — Browse the poster grid
**Priority:** P1 | **Dependencies:** TVUI-001

As a viewer, I can browse posters on the right and open a selected title.

- Directional navigation scrolls the grid and keeps the focused item visible.
- Focused posters have a clear highlight; OK opens details.
- Missing artwork displays a readable title placeholder.
- Returning from details restores the previous item and scroll position.

### TVUI-003 — Understand loading and empty screens
**Priority:** P1 | **Dependencies:** TVUI-001

As a viewer, I can understand why content is unavailable and recover where possible.

- Loading, empty catalogue, unsupported content type, and request failure have distinct states.
- Retry is accessible by remote and preserves the current section.

## Live TV

### LIVE-001 — Browse and play channels
**Priority:** P1 | **Dependencies:** PROV-002, TVUI-001, PLAY-001

As a viewer, I can browse provider channel groups and watch a channel.

- Groups and channels show provider names and available logos with fallbacks.
- Selecting a channel resolves its current stream and starts playback.
- I can switch channels and return to browsing with focus preserved.

### LIVE-002 — View the programme guide
**Priority:** P1 | **Dependencies:** LIVE-001

As a viewer, I can see what is on now and next so I can choose a channel.

- Available EPG is aligned to the device's local time and navigable by remote.
- Current programme and selected programme details are readable.
- Missing or stale guide data is identified; channels remain playable.

## Movies

### MOV-001 — Browse movie details
**Priority:** P1 | **Dependencies:** PROV-002, TVUI-002

As a viewer, I can browse movie categories and details so I can choose what to watch.

- Categories and paginated content are reachable without dropped or duplicated items.
- Details show available title, artwork, synopsis, and metadata with sensible fallbacks.
- Play starts the selected title through the shared player.

## Series

### SER-001 — Choose and play an episode
**Priority:** P1 | **Dependencies:** PROV-002, TVUI-002, PLAY-001

As a viewer, I can browse series, seasons, and episodes so I can watch a show.

- Available seasons and episodes appear in provider-defined order with clear labels.
- Episode selection starts the corresponding stream.
- Empty seasons and unavailable episodes show useful states.

## Playback

### PLAY-001 — Control playback
**Priority:** P0 for sample playback; P1 for full controls | **Dependencies:** shared interface and sample data for initial playback; PROV-001 for real Stalker integration

As a viewer, I can watch supported streams and control playback using my remote.

- Live and on-demand supported streams play on target hardware.
- Play/pause and overlay dismissal work by remote; seeking is enabled only where supported.
- Available subtitle/audio tracks and aspect ratio options can be selected.
- Leaving the player releases playback resources.

### PLAY-002 — Recover from playback failures
**Priority:** P1 | **Dependencies:** PLAY-001

As a viewer, I can recover from a failed stream without restarting the app.

- Buffering is visible and failed playback offers retry or return.
- Expired sessions/links trigger bounded renewal and stream resolution attempts.
- Network loss and unsupported playback produce useful messages without infinite retries.

## Personal library and discovery

### LIB-001 — Save favourites
**Priority:** P1 | **Dependencies:** LIVE-001, MOV-001, SER-001

As a viewer, I can favourite channels and titles for quick access.

- I can add/remove favourites and browse them from the sidebar.
- Favourites survive restart and catalogue refresh using stable provider identifiers.
- Removed provider content is handled gracefully.

### LIB-002 — Continue watching
**Priority:** P1 | **Dependencies:** MOV-001, SER-001, PLAY-001

As a viewer, I can resume movies and episodes from my saved position.

- Progress is saved locally and survives restart.
- In-progress titles appear on Home with Resume and Restart choices.
- Completed titles leave Continue Watching using a documented completion threshold.
- Resume is offered only when the stream supports seeking.

### FIND-001 — Search my catalogue
**Priority:** P1 | **Dependencies:** LIVE-001, MOV-001, SER-001

As a viewer, I can search channels, movies, and series by title.

- Remote-accessible text input returns grouped results from the indexed catalogue.
- Search indicates whether catalogue loading is incomplete.
- Selecting a result opens the correct channel or title; no matches has a clear state.

### HOME-001 — Discover content from Home
**Priority:** P1 | **Dependencies:** TVUI-002, LIB-001, LIB-002

As a viewer, I can quickly reach favourites, unfinished titles, and provider content.

- Home includes Continue Watching, favourites, and available provider category rows.
- Empty rows are omitted; featured artwork has a readable fallback.
- Cards lead to the same details/player flows as catalogue browsing.

## Settings and release readiness

### SET-001 — Manage preferences and local data
**Priority:** P1 | **Dependencies:** PROV-003, PLAY-001, LIB-002

As a viewer, I can adjust playback preferences and clear local viewing data.

- Supported language/subtitle preferences persist and apply when matching tracks exist.
- Clear-history and clear-cache actions explain their scope; destructive actions require confirmation.
- Cache clearing preserves provider credentials and favourites.

### REL-001 — Install and use the app on target boxes
**Priority:** P1 | **Dependencies:** all MVP stories

As a viewer, I can install a release build and reliably use it on a supported Android TV device.

- Signed APK installs and appears in the TV launcher on the agreed device matrix.
- Primary journeys pass using a basic remote, including restart and background/foreground transitions.
- Long playback, network interruption, session expiry, and catalogue refresh are checked.
- Release notes list supported Android versions, tested devices/portals, and known limitations.

## Definition of done

A story meets its acceptance criteria, has appropriate verification evidence, and handles loading, missing data, and failure where applicable. User-facing flows are remote-accessible. Provider secrets are absent from source, screenshots, logs, and documentation. Integration stories record the tested portal/device combination without exposing access details.
