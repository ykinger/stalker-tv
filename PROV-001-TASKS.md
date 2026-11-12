# PROV-001 — Portal compatibility tasks

Parent: [PROV-001 in STORIES.md](STORIES.md#prov-001--validate-portal-compatibility)

All tasks are **Backlog**, priority **P0**. This is a feasibility prototype: a basic connection screen, channel list, and player. Polished setup, persistent provider management, full playback controls, and poster browsing remain in their respective MVP stories.

## Task overview

| ID | Task | Depends on |
| --- | --- | --- |
| PROV-001-01 | Establish test portal and device requirements | None |
| PROV-001-02 | Create the Android TV prototype skeleton | None |
| PROV-001-03 | Implement portal requests and safe diagnostics | 01, 02 |
| PROV-001-04 | Implement handshake and authenticated profile access | 03 |
| PROV-001-05 | Fetch channel groups and channels | 04 |
| PROV-001-06 | Resolve a live stream for playback | 05 |
| PROV-001-07 | Play a channel on a physical Android box | 02, 06 |
| PROV-001-08 | Probe movies, series, and EPG capabilities | 04, 05 |
| PROV-001-09 | Verify session expiry and failure handling | 04, 06, 07 |
| PROV-001-10 | Record compatibility results and handoff | 07, 08, 09 |

Dependencies use the final two digits of the IDs above. Mock development can proceed before test access is ready; live compatibility cannot be signed off without it.

## PROV-001-01 — Establish test portal and device requirements

Record the supported test scenario before implementing portal-specific behaviour.

Acceptance criteria:

- An authorized test portal is available, with the provider's required login and device registration method established.
- Test access details are supplied privately and are absent from repository files and issue descriptions.
- The target physical box, Android version, remote, and network conditions are recorded.
- Expected access to live TV, movies, series, and EPG is listed, including unknowns.
- An initial minimum Android version is chosen for the prototype and recorded with its rationale.

## PROV-001-02 — Create the Android TV prototype skeleton

Set up the smallest runnable native TV application to exercise the integration.

Acceptance criteria:

- Kotlin project builds using a documented command and pinned tool versions.
- A debug APK installs and launches from the Android TV launcher.
- Basic connection, channel-list, and player destinations are reachable with a remote.
- Provider code and stream playback sit behind separate interfaces so sample data can be used independently.
- No production credentials or signing keys are included in the project.

## PROV-001-03 — Implement portal requests and safe diagnostics

Provide the networking foundation for the agreed portal variant.

Acceptance criteria:

- Portal URL parsing preserves required path information and rejects malformed or unsupported schemes.
- Supported endpoint paths and required headers/cookies are explicit and verified against the test portal.
- Connect/read timeouts and bounded retries are configured; cancellation stops pending work.
- Diagnostics redact credentials, device identifiers, cookies, tokens, and signed stream URLs.
- Focused tests cover URL normalization, response decoding failures, and redaction.
- Any requirement for cleartext HTTP is documented and handled deliberately without disabling TLS certificate verification.

## PROV-001-04 — Implement handshake and authenticated profile access

Establish a working session using provider-issued access details.

Acceptance criteria:

- Handshake obtains a usable session token and profile/access requests succeed in the required sequence.
- Only provider-required, authorized device details are used; no generated or borrowed identities are used to bypass registration.
- Authentication state is held in memory for this prototype and can be explicitly cleared.
- Denied access, malformed responses, and connection failure produce distinct integration errors.
- Mock-response checks cover successful authentication, rejection, and missing/invalid token responses.

## PROV-001-05 — Fetch channel groups and channels

Turn portal responses into a minimal browseable channel catalogue.

Acceptance criteria:

- Available groups and channels load, including all pages where the portal paginates.
- Common models preserve provider IDs, group membership, names, optional logos, and stream-resolution references.
- Missing optional fields use fallbacks; malformed records do not silently corrupt the catalogue.
- The prototype displays a selectable channel list with an explicit empty/error state.
- Checks cover pagination, duplicate IDs, empty results, and missing optional metadata.

## PROV-001-06 — Resolve a live stream for playback

Convert a selected channel reference into a playable source at the time it is selected.

Acceptance criteria:

- The portal's stream-link resolution flow returns the URL and any required playback headers/cookies.
- Provider command wrappers are parsed as data and never executed as shell commands.
- Missing, malformed, or unsupported stream URLs produce an actionable failure.
- Sensitive stream URLs are not logged or persisted in the catalogue.
- Mock-response checks cover successful resolution and malformed/unavailable links.

## PROV-001-07 — Play a channel on a physical Android box

Connect the resolved stream to a minimal Media3 player.

Acceptance criteria:

- At least one subscribed, supported live stream renders video and audio on the target physical box.
- Required request headers are applied to playback requests, including segment requests where applicable.
- Basic play/pause and Back work with the remote; leaving the player releases resources.
- A second channel can be selected without stale playback or overlapping audio.
- At least 15 minutes of playback is observed, recording startup, buffering, codec/format, and any failures without access secrets.

This task establishes the playback portion of PROV-001. PLAY-001 extends it with the complete player experience rather than blocking this prototype.

## PROV-001-08 — Probe movies, series, and EPG capabilities

Determine what later catalogue features can rely on.

Acceptance criteria:

- Movies: check category/list/detail availability and stream resolution for one entitled sample where available.
- Series: check the series/season/episode hierarchy and episode stream resolution where available.
- EPG: check programme retrieval for a sample channel and document timestamps, timezone semantics, and available time range.
- Each capability is classified as verified available, provider-confirmed unsupported, unavailable to this subscription, or unverified due to failure/unknown behaviour.
- Empty data alone is not treated as proof of unsupported functionality.
- Pagination and metadata limitations are recorded using sanitized examples.

## PROV-001-09 — Verify session expiry and failure handling

Make the prototype's recovery behaviour predictable before building the full app.

Acceptance criteria:

- An expired authentication session triggers at most one renewal and retry of the affected request.
- An expired playback link triggers one fresh link resolution before surfacing failure; retries cannot loop indefinitely.
- Invalid registration/access, timeout, malformed response, and unavailable stream are distinguishable.
- Network interruption produces an error/retry path that works without restarting the app.
- Expiry paths are verified with controlled responses; live observations are recorded separately from simulated results.
- Failures expose no secrets in UI messages, logs, or captured evidence.

## PROV-001-10 — Record compatibility results and handoff

Produce evidence that the parent story is complete and inform subsequent implementation.

Acceptance criteria:

- A compatibility report records a non-sensitive portal alias, tested portal variant if known, device/Android version, build commit, and test date.
- The report includes authentication, channel loading, physical playback, content capability probes, and failure/recovery results.
- Results clearly distinguish physical-device evidence, mock checks, and anything still unverified.
- Reproduction instructions reference private configuration without embedding credentials or identifiable access URLs.
- Known limitations and follow-up work are linked to PROV-002, LIVE-001/002, MOV-001, SER-001, and PLAY-001/002 as applicable.
- PROV-001 closes only when authentication, channel listing, and supported live playback pass on the physical device and capability findings are recorded.

## Recommended start

Start with 01 and 02. Then complete 03–07 for the first connection-to-playback demonstration. Probe capabilities in 08 after catalogue access works, validate recovery in 09, and finish with the report in 10.
