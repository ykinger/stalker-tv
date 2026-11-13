"""Development-only Stalker-style portal. No real provider access required."""

import json
import os
import secrets
import time
from http.cookies import SimpleCookie
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

MAC = os.getenv("MOCK_MAC", "00:1A:79:00:00:01").upper()
TOKEN_TTL = int(os.getenv("TOKEN_TTL_SECONDS", "3600"))
TOKENS = {}


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
            try:
                page = max(1, int(params.get("p", ["1"])[0]))
            except ValueError:
                return self.reply({"js": {"error": "Invalid page"}}, 400)
            return self.reply({"js": {"data": channels[(page-1)*2:page*2],
                                      "total_items": len(channels), "max_page_items": 2}})
        if kind == "itv" and action == "create_link":
            cmd = params.get("cmd", [""])[0]
            if cmd not in [f"ffmpeg http://localhost/ch/{i}_" for i in range(1, 6)]:
                return self.reply({"js": {"error": "Unknown channel"}}, 400)
            # Configured address must be reachable by the Android client.
            base = os.getenv("PUBLIC_BASE_URL", "http://10.0.2.2:8080").rstrip("/")
            return self.reply({"js": {"cmd": f"ffmpeg {base}/sample.mp4"}})
        return self.reply({"js": {"error": "Unsupported mock action"}}, 400)


if __name__ == "__main__":
    print("Development mock portal listening on port 8080", flush=True)
    ThreadingHTTPServer(("0.0.0.0", 8080), Portal).serve_forever()
