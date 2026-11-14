# Local mock Stalker portal

Development simulator, not a production server or proof of real-provider compatibility.
Uses only a fake registered MAC. No third-party subscriptions or channels.

## Start

Install and start Docker Desktop, then run from the repository root:

```sh
docker compose -f tools/mock-portal/compose.yaml up --build -d
```

Open http://localhost:8080/health — expect `{"status":"ok","mock":true}`.

- Laptop portal: `http://localhost:8080/server/load.php`
- Android emulator portal: `http://10.0.2.2:8080/server/load.php`
- Fake MAC: `00:1A:79:00:00:01`

In the debug Android app, open Add playlist → Stalker Portal. Enter the emulator
host above and the fake MAC; serial ID is optional. After confirming the playlist,
Done opens the guide with five channels fetched over HTTP. Select one to preview
and press OK again to play the Docker video full-screen.
Existing playlists can be selected from the landing screen or Settings. HTTP is enabled in debug only.

## Protocol

GET requests support `/server/load.php`, `/portal.php`, and
`/stalker_portal/server/load.php`. Send `Cookie: mac=00:1A:79:00:00:01`.

1. `?type=stb&action=handshake` returns `js.token`.
2. Send `Authorization: Bearer TOKEN` for subsequent requests.
3. `?type=stb&action=get_profile` returns a fake profile.
4. `?type=itv&action=get_genres` returns the demo group.
5. `?type=itv&action=get_ordered_list&genre=1&p=1` returns two channels per page (five total).
6. `?type=itv&action=create_link&cmd=...` accepts the exact channel command from step 5
   (URL-encode it) and returns `ffmpeg http://10.0.2.2:8080/sample.mp4`.
7. `?type=itv&action=get_short_epg&ch_id=1&size=4` (or `get_epg_info`) returns
   four synthetic, hour-long UTC programmes for a channel, including Unix
   `start_timestamp` and `stop_timestamp`. Valid channel IDs are 1–5.
8. `?type=vod&action=get_categories` returns Demo Movies (`11`) and Demo Series
   (`21`). `get_ordered_list&category=*&p=1` paginates two fictional movies
   and one fictional series; filtering by `category=11` or `21` is supported.
9. `?type=vod&action=get_seasons&series_id=201` returns two seasons;
   `get_episodes&series_id=201&season_id=301` returns the first season's episodes.
   `type=series` also accepts these series queries as a fixture-specific alias.
10. `?type=vod&action=create_link&cmd=...` accepts only a returned movie or
    episode command and resolves it to the same sample MP4.

The mock's `screenshot_uri` values are synthetic bundled-artwork keys
(`mountain`, `ocean`, `orbit`), not real poster URLs. The app also accepts
HTTP(S) artwork URLs from portals that supply them.

Command wrappers are data, never executable commands. Every channel plays the same
bundled 15-second Big Buck Bunny excerpt, **not a live stream**. Credits:
[sample licence and attribution](../../docs/ASSETS.md).

Wrong/missing MAC returns HTTP 403. Missing/expired token returns HTTP 401.
Set `TOKEN_TTL_SECONDS` to a short duration in Compose and recreate the service to test expiry.
Tokens live only in memory; restart clears them. These synthetic endpoints are
test fixtures, not proof of compatibility with every Stalker/Ministra portal.
The Android app loads these fixtures into the connected guide, Movies, and
Series screens. This fixture does not simulate HLS/DASH or network failures.

Run mock HTTP contract tests without Docker:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/mock-portal -p 'test_*.py' -v
```

The service is bound to laptop loopback only. For a physical box, deliberately change
the port binding to your laptop's LAN IP and set `PUBLIC_BASE_URL` to that same IP
and port. Use only a trusted local network; do not expose this server to the internet.

## Stop

```sh
docker compose -f tools/mock-portal/compose.yaml down
```
