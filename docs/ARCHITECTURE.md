# Foundation architecture

## Framework choices

Use Android's established application architecture: Compose UI → ViewModel → repository. State flows down; UI actions call ViewModel functions. `collectAsStateWithLifecycle` stops unnecessary UI observation while the activity is inactive. Networking for real portals will be added inside a repository adapter, not screens.

`app` contains the TV application, app container, navigation graph, ViewModels, and Media3 playback. `core` is a Kotlin/JVM module with immutable provider-neutral data models, the repository contract, mock implementation, and tests. Additional modules can be introduced when actual implementation complexity justifies them.

The application container supplies dependencies through standard ViewModel factories. This uses Android's documented manual dependency injection pattern for a small app; a DI framework is unnecessary at this stage. No custom navigation engine, focus search algorithm, player engine, or HTTP stack is introduced.

## UI and navigation

Compose for TV's NavigationDrawer, NavigationDrawerItem, Card, FilterChip, Button, and MaterialTheme provide TV-specific interaction states. Compose foundation supplies lazy grids/lists and focus primitives. Navigation Compose owns the browse → details → player back stack. Stable content IDs and saveable selection/grid state support returning to browsing.

Each route uses a ViewModel and immutable loading/ready/message state. Provider results distinguish success, empty, unsupported, and failure. Loading is a presentation state, not a stored catalogue record. Mock scenario changes cancel the previous catalogue request.

## Playback

Stream resolution happens when the player route opens. Media3 handles buffering, decoding, track support, standard remote controls, and playback errors. Playback requests carry the headers provided by the repository. The player pauses while the activity is stopped and is released when its composition is disposed.

## Mock boundaries

The mock repository supplies deterministic IDs, category membership, series hierarchies, optional metadata, and EPG based on an injected clock. Scenario-specific delay uses cancellable coroutines. No credentials, session tokens, Stalker DTOs, or portal requests exist in mock mode.

Real Stalker support will implement the same repository interface. Authentication and session management remain internal to that adapter; setup and persistence are separate stories. The mock provider remains available for development.

## Compatibility choices

Minimum API 26 (Android 8.0) is an initial project decision: native java.time support simplifies guide/time handling, while covering many TV boxes. This is not a tested hardware compatibility claim. Compile/target SDK 35 and JDK 17 are pinned for the initial build. Revisit the minimum Android version against actual target devices before release.

## References

- [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)
- [Compose for TV](https://developer.android.com/training/tv/playback/compose)
- [Navigation Compose](https://developer.android.com/develop/ui/compose/navigation)
- [Manual dependency injection](https://developer.android.com/training/dependency-injection/manual)
- [Media3 PlayerView](https://developer.android.com/media/media3/ui/playerview)
