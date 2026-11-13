# CHANNEL-001 — Browse and play portal channels

Status: Verification. Depends on PROFILE-001. Docker-first slice of LIVE-001;
does not establish real-provider compatibility.

UI correction: connected profiles now reuse the foundation sidebar, grid,
details and player rather than a separate channel-list Home. Home includes
provider channels; Live TV filters channels; Movies/Series are explicitly empty
until their adapters exist. Settings → Manage profiles opens profile management.
Latest emulator run verified channel grid → details → Playing → Back, but the
combined onboarding test failed locating the sidebar Settings node afterward.
This navigation regression remains open; do not count the latest suite as passing.

- [x] Read channel genres and all channel pages through a ContentRepository adapter.
- [x] Preserve IDs and category membership; deduplicate IDs and bound pagination.
- [x] Show network channels on connected Home, separate from offline demo.
- [x] Resolve selected channel using create_link; parse wrappers as data only.
- [x] Play resolved HTTP(S) source; Back releases player and returns to channels.
- [x] Offer channel refresh and retryable catalogue/resolution error states.
- [x] Verify emulator network playback: all five channels loaded across three
  pages, channel selected, Media3 reported Playing, Back returned to Home.
- [ ] Full offline-demo regression suite (previous failures remain open).
- [ ] Remote-only focus restoration acceptance.

Session renewal, provider-specific playback headers, remote artwork, full category
navigation and HLS/DASH remain future work. Docker channels use one bundled MP4,
not live video. No stream URL is persisted or logged by the adapter.

Evidence: debug APK build, lint and core tests passed. Both ProfileSetupTest
instrumentation tests passed on Android 16 TV emulator against Docker, including
network playback, deselection and saved-profile reload. Remote-only acceptance
and second-channel playback still need manual verification.
