# RECOVERY-001 — Survive portal session expiry and idle

Status: Verification. Depends on PROFILE-001 and the Docker mock portal.

A connected playlist keeps one in-memory `PortalSessionHolder`. Portals expire
that token on their own schedule, including after a long TV idle or a server
restart. Previously only a manual Connect re-handshaked, so every other screen
showed a generic portal error until the viewer reconnected.

| Ticket | Scope | Status |
| --- | --- | --- |
| RECOVERY-001-01 | Shared mutable session holder with single-flight re-handshake and one retry per request | Verification |
| RECOVERY-001-02 | Recover catalogue, EPG, seasons/episodes and playback link resolution without leaving the screen | Verification |
| RECOVERY-001-03 | Lifecycle-aware resume health check plus watchdog keep-alive using the current token | Verification |
| RECOVERY-001-04 | Silent recovery; "Reconnecting to portal…" banner only after 700 ms | Verification |
| RECOVERY-001-05 | Specific session-expired message when automatic renewal fails | Verification |
| RECOVERY-001-06 | Connect and Refresh playlist always install a fresh token; Refresh also rescans categories | Verification |
| RECOVERY-001-07 | Unit tests, Docker expiry test, connected UI regression, builds | Verification |

## Acceptance criteria

- After the server expires a token, the next catalogue, EPG, season, episode or
  playback-link request re-handshakes once and retries the original request. The
  viewer does not see an error and does not lose the current place in the UI.
- Concurrent requests that see the same expired token cause exactly one
  handshake; later callers reuse the replacement token.
- A failed renewal produces "Your portal session expired and couldn't be
  renewed. Try again, or reconnect the playlist." instead of the generic message.
  Retry goes through the holder, not a dead token.
- A connection reset is retried once on a fresh connection; timeouts are not
  doubled; a second session error never triggers another handshake.
- Returning to the app after idle revalidates the session quietly. A short app
  switch does not issue a health-check request.
- The watchdog uses the live token. A watchdog 401 refreshes the session instead
  of being swallowed by a debug log.
- No banner appears for a fast recovery. If renewal takes more than 700 ms, a
  small "Reconnecting to portal…" banner appears at the top and then disappears.
- Both Settings → Connect and Settings → Refresh playlist perform a new
  handshake and replace the holder. Refresh playlist still rescans VOD category
  classifications; Connect keeps the cached classifications.
- Tokens are never logged or persisted. Diagnostics keep using fixed labels.

## Verification

- `PortalSessionTest`: 401 and JSON session errors, single-flight refresh,
  failed renewal, no retry loop, connection reset vs timeout, health check.
- `StalkerContentTest` with the Docker mock: `manualVerifyAlwaysIssuesAFreshToken`
  and `expiredSessionRecoversWithoutReconnecting`, which uses the mock-only
  `GET /test/expire-sessions` control route.
- Manual: connect, leave the TV idle for a long period (or expire the mock
  session), then browse channels, VOD, EPG and an episode without reconnecting.
