# Stalker TV

Native Android TV player for users connecting their own Stalker/Ministra IPTV providers. Product name is provisional.

The app starts on a landing page. Choose **Add playlist → Stalker Portal** to
enter a host/IP, registered MAC and optional serial ID, validate the connection,
and save an encrypted local playlist. A processed-playlist confirmation shows the
channel count and lets you name the playlist before **Done** opens the TV guide. For Docker testing use host
`http://10.0.2.2:8080`, MAC `00:1A:79:00:00:01` (see
[mock portal](tools/mock-portal/README.md)); serial may be left blank. Saving or selecting a playlist connects
and opens the guide with channel groups, time slots, and a video preview.
Select a channel to resolve and preview it, then press OK again for full-screen
playback. On the next launch, the selected saved playlist reconnects directly to
the guide; deselect it in Settings to stop automatic connections. If the server is
offline, the app offers retry or playlist management instead of opening the guide.
The left navigation keeps TV, Movies, Shows and Recordings together. The second
column shows portal categories for the selected section; Movies and Shows use
vertically scrolling poster grids on the right. The remote Back key moves from
content to categories to navigation.
Docker serves the same sample MP4 for all five channels, not live TV.
**Open offline demo** retains the original sample browsing experience.

The connected guide loads programme information from the selected portal when
available. Movies and Shows browse portal VOD titles, seasons, and episodes;
movie and episode playback links resolve through the portal. The local mock
supplies synthetic fixtures, not evidence of support for every real provider.
Recordings and My list remain navigation placeholders, not functional DVR or
favourites. The app uses its own branding and icons.

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

- [Tracker and roadmap](tracker/README.md)
- [Product plan](tracker/PRODUCT.md)
- [User stories](tracker/stories/STORIES.md)
- [Mock-first foundation tasks](tracker/tasks/foundation/FND-001.md)
- [Real portal compatibility tasks](tracker/tasks/provider/PROV-001.md)
