# Product plan

## Next milestone

Start on a landing page. Settings offers Add profile → protocol selection (Stalker
only initially) → serial ID, host address, and registered MAC. Validate against the
Docker portal before saving locally. Emulator-first development; physical-box and
real-provider validation happen near release, not as a prerequisite for this milestone.

## Vision

Let viewers browse and play their own provider's live TV, movies, and series on an Android TV box using a remote-friendly interface.

## Audience and content

Users supply their own authorized Stalker/Ministra portal access. The app does not supply channels or subscriptions. Portal capabilities vary; missing content types and metadata must be handled gracefully.

## Experience

- Left vertical menu: Home, Live TV, Movies, Series, Favourites, Search, Settings.
- Right content area: poster grids for movies and series; channel logos and a programme guide for live TV.
- Clear remote focus, readable text, dark background, and artwork fallbacks.
- Familiar TV navigation with original branding, styling, and assets.

## MVP

Multiple saved connection profiles, with one active provider at a time; connection setup and validation; live channels and available EPG; movie and series browsing; search; playback controls; subtitles and audio selection where available; favourites; continue watching; provider management and refresh.

All functionality must work with directional buttons, OK, and Back. Watch history and provider settings stay on the device. A backend and app account are not required initially.

## Technical direction

Kotlin, Jetpack Compose for TV, Media3/ExoPlayer, OkHttp, Kotlin serialization, Room, and DataStore. Keep portal integration behind a provider interface with common content models. Resolve streams at playback time and support session renewal. Protect sensitive stored values using Android Keystore-backed encryption and redact logs and backups.

## Delivery order

1. Build the Android TV skeleton, common provider interface, and deterministic mock provider without portal access.
2. Build the sidebar, poster grids, details, channel/EPG screens, remote navigation, and sample playback using mocks.
3. Verify the mock demonstration, including loading, empty, and error scenarios.
4. Implement the real Stalker adapter and prove authentication, catalogue retrieval, and playback on a physical Android TV box.
5. Integrate real live TV, EPG, movies, and series into the existing screens.
6. Complete home discovery, favourites, resume, search, and provider settings.
7. Validate device compatibility, recovery, and signed APK delivery.

Mock mode is the initial development default. Sample playback uses bundled or authorized sample media. Mock results do not establish Stalker compatibility; real portal validation remains a release prerequisite.

## Deferred

Multiple active providers, Xtream Codes, M3U, recording, catch-up, timeshift, cloud sync, billing, and recommendations. DRM integration depends on provider requirements and is not assumed for the MVP.

## Open decisions

- Final app name and visual identity.
- Authorized test portal and supported portal variants.
- Minimum Android version and physical device test matrix.
- Supported stream codecs and DRM requirements.
- Direct APK versus Google Play distribution.

## Release gate

The MVP must connect to the agreed test portals, play supported live and on-demand streams on the target devices, support all primary flows with a remote, preserve local favourites and progress, and recover clearly from network and session failures. Compatibility claims cover tested portal/device combinations only.
