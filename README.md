# Stalker TV

Native Android TV player for users connecting their own Stalker/Ministra IPTV providers. Product name is provisional.

The initial app runs with **mock data**: a left navigation drawer, poster grids, movie/series details, channel programme listings, search, and sample playback. No portal URL or credentials are required. Real Stalker integration is a later phase.

## Build and run

1. Install Android Studio, **JDK 17**, Android SDK Platform **35**, and Build Tools **35.0.0**.
2. Open the project in Android Studio. Set its Gradle JDK to 17 and let it create your local SDK configuration, or export `ANDROID_HOME` to your SDK location.
3. Build and run:

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on an Android TV emulator or box (Android 8.0 / API 26 or newer), or use Android Studio's Run action. The app appears as **Stalker TV · Demo** in the TV launcher.

For connected UI checks:

```sh
./gradlew :app:connectedDebugAndroidTest
```

The Gradle wrapper and dependency versions are pinned; the Gradle distribution is checksum-verified. First-time builds require internet access to Google Maven, Maven Central, and Gradle distribution hosting.

## Try the demo

- Use a remote/D-pad: Up/Down selects a sidebar section, Right enters content, OK opens it, Back returns. At the sidebar, Back follows the system's normal exit behaviour.
- Movies and Series show poster grids. A series exposes seasons and episodes.
- Live TV opens sample channel details and a local-time programme listing. Channel 4 intentionally has no guide data.
- Settings lets you choose Success, Delayed, Empty, Unsupported, and Failure. Then return to a catalogue section. Retry repeats the current scenario; select Success to recover from a persistent simulated failure.
- Search uses the TV text input method or an attached keyboard and searches the loaded mock catalogue.
- Every playable entry uses the **same Big Buck Bunny sample**, regardless of its fictional title. The credited 15-second excerpt is bundled; browsing and sample playback work offline.
- Favourites, watch history, provider setup, and real IPTV streams are not implemented yet. Settings reset after process restart.

## Implementation

- Kotlin, Compose for TV Material components, Navigation Compose.
- ViewModels expose immutable StateFlow state, observed with lifecycle-aware Compose APIs.
- Manual dependency injection via an application container, following Android's guidance for small apps.
- Provider-neutral repository and models in `core`; deterministic mock implementation with an injectable clock.
- Media3 ExoPlayer with its standard PlayerView controls and lifecycle cleanup.

See [architecture](docs/ARCHITECTURE.md), [sample credits](docs/ASSETS.md), and [verification](docs/FOUNDATION-VERIFICATION.md).

## Planning

- [Product plan](PRODUCT.md)
- [User stories](STORIES.md)
- [Mock-first foundation tasks](FOUNDATION-TASKS.md)
- [Real portal compatibility tasks](PROV-001-TASKS.md)
