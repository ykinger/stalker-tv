# Mock foundation verification

Date: 2026-11-12. Scope: FND-001; no real portal testing.

## Build and automated checks

- Core repository unit tests: **6 passed** (no failures or skipped tests).
- Debug APK: passed, including the bundled-media build (13 MB debug APK).
- UI-test APK: passed; connected execution remains pending.
- Android lint: passed after explicitly opting into the Media3 APIs used by PlayerView. Remaining warnings concern newer dependency/SDK versions and vector rasterization guidance.
- Kotlin formatting: ktfmt 0.54, Kotlin language style.
- Bundled sample inspected with FFprobe: 15 seconds, H.264 video (320×180), AAC audio, 279,283 bytes.

## Runtime verification

Android TV API 30 x86 emulator, 960×540 at 160 dpi, using software CPU/GPU emulation because `/dev/kvm` is unavailable. Emulator startup and connected UI checks are in progress. No physical Android box has been tested.

Connected test coverage is defined in `MockNavigationTest`: D-pad/sidebar/Back behaviour, movie-details return, and failure-scenario recovery. A compiled test is not a passing runtime test.

## Manual checks to reproduce

1. Open Movies using the remote and enter the grid. Browse beyond the first screen, open details, and return; verify focus and position.
2. Open Series, choose a season and episode, and play the bundled excerpt. Check play/pause, Back, and that playback stops after leaving.
3. Open Live TV and choose a channel. Check local-time programme listings and the missing-guide case on channel 4.
4. Search for a partial title; check matching results and a no-match query.
5. Choose each Settings scenario and return to Movies. Check loading, empty, unsupported, and failure/retry states. Select Success to recover.
6. Restart the app and verify mock mode requires no credentials. Favourites/history are explicitly deferred.

## Limits

- No real Stalker handshake, stream resolution, session renewal, or provider compatibility has been tested or claimed.
- Live TV is a mock catalogue with sample playback, not a genuine live stream.
- Favourites, resume, persistent settings, and production provider management remain separate stories.
- Dependencies are pinned to the initial compatible stack; lint may report newer available versions. Release target/dependency updates remain subject to compatibility testing before distribution.
