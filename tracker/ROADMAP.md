# Roadmap

1. **Foundation:** offline demo and emulator smoke tests (implemented; manual
   emulator journeys reported passing by user).
2. **Playlist onboarding — current:** no-playlist landing → Add playlist → Stalker
   Portal → host/IP, editable MAC, optional serial ID → Docker handshake/profile
   test → encrypted local save. Existing playlists can be selected on landing.
   See [tickets](stories/PROFILE-001.md).
3. **Network catalogue — in verification:** select saved profile, fetch all channel pages, resolve and
   play Docker sample channels; retain offline demo separately.
4. **Recovery:** expired tokens, disconnected server, malformed responses, retry,
   edit/remove profile, and cancellation tests.
5. **MVP features:** EPG, movies, series, track controls, favourites, resume,
   catalogue search, and preferences. Extend Docker fixtures as needed.
6. **Real-provider and physical-box validation:** authorized registration, portal
   differences, actual codecs/live playback, long playback and recovery tests.
7. **Release:** security review, device matrix, signing, installation, release notes.

Docker success is simulated protocol evidence, not real-provider compatibility.
