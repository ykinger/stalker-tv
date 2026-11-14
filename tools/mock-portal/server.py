"""Development-only Stalker-style portal. No real provider access required."""

import json
import os
import secrets
import time
from datetime import datetime, timezone
from http.cookies import SimpleCookie
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

MAC = os.getenv("MOCK_MAC", "00:1A:79:00:00:01").upper()
TOKEN_TTL = int(os.getenv("TOKEN_TTL_SECONDS", "3600"))
TOKENS = {}

# Deliberately small, synthetic fixtures. IDs and commands remain stable across
# requests; dates in the EPG are anchored to the current UTC hour.
MOVIES = [
    {"id": "101", "name": "Sample Feature", "category_id": "11", "movie_type": "movie",
     "year": "2022", "description": "Fictional movie for local UI testing.",
     "cmd": "ffmpeg http://localhost/vod/movie/101", "screenshot_uri": "mountain"},
    {"id": "102", "name": "Sample Documentary", "category_id": "11", "movie_type": "movie",
     "year": "2024", "description": "Fictional documentary for local UI testing.",
     "cmd": "ffmpeg http://localhost/vod/movie/102", "screenshot_uri": "ocean"},
]
SERIES = [
    {"id": "201", "name": "Sample Series", "category_id": "21", "movie_type": "series",
     "is_series": 1, "year": "2023", "description": "Fictional show for local UI testing.",
     "screenshot_uri": "orbit"},
]
SEASONS = [
    {"id": "301", "series_id": "201", "season_number": 1, "name": "Season 1"},
    {"id": "302", "series_id": "201", "season_number": 2, "name": "Season 2"},
]
EPISODES = [
    {"id": "401", "series_id": "201", "season_id": "301", "episode_number": 1,
     "name": "A New Beginning", "cmd": "ffmpeg http://localhost/vod/episode/401"},
    {"id": "402", "series_id": "201", "season_id": "301", "episode_number": 2,
     "name": "The Next Step", "cmd": "ffmpeg http://localhost/vod/episode/402"},
    {"id": "403", "series_id": "201", "season_id": "302", "episode_number": 1,
     "name": "Another Chapter", "cmd": "ffmpeg http://localhost/vod/episode/403"},
]


def page_of(rows, params):
    try:
        page = int(params.get("p", ["1"])[0])
        if page < 1:
            raise ValueError
    except ValueError:
        return None
    return {"data": rows[(page - 1) * 2:page * 2],
            "total_items": len(rows), "max_page_items": 2}


def guide_for(channel_id):
    start_hour = int(time.time()) // 3600 * 3600
    titles = ("News Update", "Featured Programme", "Evening Preview", "Late Show")
    entries = []
    for index, name in enumerate(titles):
        start = start_hour + (index - 1) * 3600
        entries.append({
            "id": f"{channel_id}-{index}", "ch_id": channel_id,
            "name": f"{name} {channel_id}", "descr": "Synthetic programme from the local mock portal.",
            "start_timestamp": start, "stop_timestamp": start + 3600,
            "time": datetime.fromtimestamp(start, timezone.utc).strftime("%Y-%m-%d %H:%M:%S"),
            "time_to": datetime.fromtimestamp(start + 3600, timezone.utc).strftime("%Y-%m-%d %H:%M:%S"),
        })
    return entries


class Portal(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory="/media", **kwargs)

    def log_message(self, fmt, *args):
        # Never print URLs, cookies, or authentication headers.
        pass

    def reply(self, data, status=200):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlsplit(self.path)
        if url.path == "/health":
            return self.reply({"status": "ok", "mock": True})
        if url.path == "/sample.mp4":
            return super().do_GET()
        if url.path not in ("/portal.php", "/server/load.php", "/stalker_portal/server/load.php"):
            return self.reply({"error": "Not found"}, 404)

        params = parse_qs(url.query)
        action = params.get("action", [""])[0]
        kind = params.get("type", [""])[0]
        cookie = SimpleCookie()
        try:
            cookie.load(self.headers.get("Cookie", ""))
            mac = cookie["mac"].value.upper() if "mac" in cookie else ""
        except Exception:
            mac = ""
        if mac != MAC:
            return self.reply({"js": {"error": "Device not registered"}}, 403)

        if kind == "stb" and action == "handshake":
            now = time.time()
            for token in list(TOKENS):
                if TOKENS[token] <= now:
                    del TOKENS[token]
            token = secrets.token_hex(24)
            TOKENS[token] = now + TOKEN_TTL
            return self.reply({"js": {"token": token}})

        token = self.headers.get("Authorization", "").removeprefix("Bearer ")
        if TOKENS.get(token, 0) <= time.time():
            return self.reply({"js": {"error": "Session expired or invalid"}}, 401)
        if kind == "stb" and action == "get_profile":
            return self.reply({"js": {"id": "1", "name": "Local mock subscriber", "status": 1}})
        if kind == "account_info" and action == "get_main_info":
            return self.reply({"js": {"status": "active", "end_date": "Unlimited mock access"}})
        if kind == "itv" and action == "get_genres":
            return self.reply({"js": [{"id": "1", "title": "Demo", "alias": "demo"}]})
        if kind == "itv" and action in ("get_short_epg", "get_epg_info"):
            channel_id = params.get("ch_id", [""])[0]
            if channel_id not in {str(i) for i in range(1, 6)}:
                return self.reply({"js": {"error": "Unknown channel"}}, 404)
            try:
                size = int(params.get("size", ["4"])[0])
                if size not in range(1, 49):
                    raise ValueError
            except ValueError:
                return self.reply({"js": {"error": "Invalid guide size"}}, 400)
            return self.reply({"js": {"data": guide_for(channel_id)[:size]}})
        if kind == "itv" and action in ("get_all_channels", "get_ordered_list"):
            channels = [
                {"id": str(i), "name": f"Demo Channel {i}", "number": i,
                 "tv_genre_id": "1", "cmd": f"ffmpeg http://localhost/ch/{i}_",
                 "logo": "", "use_http_tmp_link": 1}
                for i in range(1, 6)
            ]
            genre = params.get("genre", ["*"])[0]
            if genre not in ("*", "0", "1", ""):
                channels = []
            if action == "get_all_channels":
                return self.reply({"js": {"data": channels, "total_items": len(channels)}})
            result = page_of(channels, params)
            if result is None:
                return self.reply({"js": {"error": "Invalid page"}}, 400)
            return self.reply({"js": result})
        if kind == "itv" and action == "create_link":
            cmd = params.get("cmd", [""])[0]
            if cmd not in [f"ffmpeg http://localhost/ch/{i}_" for i in range(1, 6)]:
                return self.reply({"js": {"error": "Unknown channel"}}, 400)
            # Configured address must be reachable by the Android client.
            base = os.getenv("PUBLIC_BASE_URL", "http://10.0.2.2:8080").rstrip("/")
            return self.reply({"js": {"cmd": f"ffmpeg {base}/sample.mp4"}})
        if kind in ("vod", "series") and action in ("get_categories", "get_genres"):
            categories = [{"id": "11", "title": "Demo Movies"},
                          {"id": "21", "title": "Demo Series"}]
            return self.reply({"js": categories if kind == "vod" else categories[1:]})
        if kind in ("vod", "series") and action in ("get_ordered_list", "get_all_channels"):
            category = params.get("category", ["*"])[0]
            rows = MOVIES + SERIES if kind == "vod" else SERIES
            selected = ([row for row in rows if row["category_id"] == category]
                        if category not in ("*", "0", "") else rows)
            if action == "get_all_channels":
                return self.reply({"js": {"data": selected, "total_items": len(selected)}})
            result = page_of(selected, params)
            if result is None:
                return self.reply({"js": {"error": "Invalid page"}}, 400)
            return self.reply({"js": result})
        if kind in ("vod", "series") and action in ("get_seasons", "get_series_info"):
            series_id = params.get("series_id", params.get("movie_id", [""]))[0]
            if series_id != "201":
                return self.reply({"js": {"error": "Unknown series"}}, 404)
            return self.reply({"js": {"data": SEASONS}})
        if kind in ("vod", "series") and action in ("get_episodes", "get_series_data"):
            series_id = params.get("series_id", [""])[0]
            season_id = params.get("season_id", [""])[0]
            if series_id != "201" or season_id not in {"301", "302"}:
                return self.reply({"js": {"error": "Unknown season"}}, 404)
            rows = [row for row in EPISODES if row["season_id"] == season_id]
            return self.reply({"js": {"data": rows, "total_items": len(rows)}})
        if kind == "vod" and action == "create_link":
            cmd = params.get("cmd", [""])[0]
            if cmd not in {row["cmd"] for row in MOVIES + EPISODES}:
                return self.reply({"js": {"error": "Unknown video"}}, 400)
            base = os.getenv("PUBLIC_BASE_URL", "http://10.0.2.2:8080").rstrip("/")
            return self.reply({"js": {"cmd": f"ffmpeg {base}/sample.mp4"}})
        return self.reply({"js": {"error": "Unsupported mock action"}}, 400)


if __name__ == "__main__":
    print("Development mock portal listening on port 8080", flush=True)
    ThreadingHTTPServer(("0.0.0.0", 8080), Portal).serve_forever()
