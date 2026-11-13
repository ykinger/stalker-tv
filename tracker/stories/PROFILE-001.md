# PROFILE-001 — Configure a Stalker playlist

UI update: user-facing "profile" is now "playlist". Existing encrypted profile
records are read without a destructive migration. When none are saved, show the
no-playlist notice with Add playlist / Settings; when saved, offer selection
without connecting automatically. Choosing Stalker Portal turns the two-column
type screen into a three-column layout: title, connection fields, Next/Back.
The visual references are in `../assets/` and are not copied as production artwork.
Portal host/IP and editable MAC are required; serial ID is optional and omitted
from the profile request when blank. Prefill a hardware MAC only if Android
exposes one; never present a made-up MAC as the TV's. The Docker test MAC is
labelled as a demo value. Successful Next validates, saves and opens the
existing connected sidebar Home. Selection/session are still memory-only.

As a viewer, I can add and select a playlist before browsing provider content.
This is the next slice of PROV-002;
it does not complete all of PROV-002 or prove real portal compatibility.

| Ticket | Scope | Status |
| --- | --- | --- |
| PROFILE-001-01 | No-playlist page, playlist chooser when saved, offline demo separate | Verification |
| PROFILE-001-02 | Add playlist → Stalker Portal; 2-column type → 3-column form | Verification |
| PROFILE-001-03 | Optional serial ID, HTTP(S) host and editable MAC | Verification |
| PROFILE-001-04 | Test MAC handshake and authenticated profile against Docker | Verification |
| PROFILE-001-05 | Save successful playlist encrypted locally; survive restart | Verification |
| PROFILE-001-06 | Build checks and emulator acceptance test | Verification (remote-only checks open) |

## Acceptance criteria

- Cold launch opens landing, not mock browsing. Remote can reach every action.
- Settings lists every saved playlist with protocol, host, serial ID and MAC,
  and offers Add playlist / Add another playlist. Adding does not replace existing
  entries; exact duplicates are rejected. Original single-profile storage migrates on read.
- Protocol selection offers Stalker only.
- Form requires host and colon-separated MAC; serial ID is optional; errors preserve input.
- Bare host/IP is accepted and defaults to HTTP for local development. Explicit
  HTTP/HTTPS URLs retain endpoint paths. Reject credentials, fragments and queries.
- Next sends MAC cookie, obtains token, then requests an authenticated profile,
  with serial ID only when supplied. Incorrect MAC and unavailable host show safe errors.
- Save only after successful validation; playlist survives restart using Android
  Keystore-backed encryption. Tokens are neither logged nor persisted.
- HTTP is allowed only for debug development; release keeps cleartext blocked.
- Docker test values (not automatic device defaults): host `http://10.0.2.2:8080`, MAC `00:1A:79:00:00:01`.
- Docker currently validates MAC, not serial registration; serial interoperability
  remains unverified until a real provider is available.
- Selected playlists load the Docker channel catalogue through the common repository.

## Manual verification

### Selection and connection follow-up

- Saving successfully selects the new playlist and opens the existing sidebar Home.
- Landing and Settings offer playlist selection; Settings also offers deselection.
- Connecting performs a fresh handshake and profile request. Failed connections
  stay on the current screen with a retryable error; Home is success-only.
- No selected profile means no portal requests or session. Selection and session
  are memory-only: restart requires explicit selection; saved configurations persist.
- Home shows provider channels from Docker (not offline demo data).

Automated evidence: debug build, lint and core tests passed with JDK 17. On the
Android 16 TV emulator, both onboarding tests passed after the UI update: form
validation and Docker handshake/save/channel playback plus activity recreation/reload. Three existing
offline navigation tests failed after entering the demo (missing expected nodes);
regression investigation and remote-only manual acceptance remain open. Full
process restart persistence has not yet been independently tested.

Start Docker; run app; landing → Add playlist → Stalker Portal. Enter Docker host
and fake MAC above, leave serial blank, then select Next. Restart app and confirm
the playlist remains but is not automatically connected. Try wrong
MAC and stopped Docker; verify failures and retry. Navigate every screen with
D-pad/OK/Back. Ensure offline demo remains available.
