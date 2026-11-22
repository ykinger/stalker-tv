# Roadmap

1. **Foundation:** offline demo and emulator smoke tests (implemented; manual
   emulator journeys reported passing by user).
2. **Playlist onboarding — current:** create profile → no-playlist landing → Add
   playlist → Stalker Portal → who can use the playlist → host/IP, editable MAC,
   optional serial ID → Docker handshake/profile test → encrypted local save. The
   last selected playlist reconnects on launch;
   explicit deselection keeps the app disconnected.
   See [tickets](stories/PROFILE-001.md).
3. **Network catalogue — in verification:** select saved profile, fetch all channel pages, resolve and
    play Docker sample channels in the guide-first connected UI; retain offline demo separately.
4. **Recovery:** expired tokens, disconnected server, malformed responses, retry,
   edit/remove profile, and cancellation tests.
5. **MVP features:** EPG, movies, series, track controls, favourites, resume,
   catalogue search, and preferences. Extend Docker fixtures as needed.
6. **Real-provider and physical-box validation:** authorized registration, portal
   differences, actual codecs/live playback, long playback and recovery tests.
7. **VibeTV Pro entitlements — next, blocked on Play Console:** free tier allows one
   profile and one playlist; an annual subscription or a one-time purchase unlocks
   more. See [BILLING-001](stories/BILLING-001.md).
8. **Release:** security review, device matrix, signing, installation, release notes,
   Play Console listing and internal testing.

Docker success is simulated protocol evidence, not real-provider compatibility.
