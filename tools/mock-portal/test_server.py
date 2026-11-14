"""Black-box tests for the local mock's authenticated HTTP contract."""

import json
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from server import MAC, Portal


class MockPortalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Portal)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}/server/load.php"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def request(self, kind, action, token=None, mac=MAC, **params):
        query = urlencode({"type": kind, "action": action, **params})
        headers = {"Cookie": f"mac={mac}"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        try:
            with urlopen(Request(f"{self.base}?{query}", headers=headers), timeout=3) as response:
                return response.status, json.load(response)["js"]
        except HTTPError as error:
            with error:
                return error.code, json.load(error)["js"]

    def login(self):
        status, payload = self.request("stb", "handshake")
        self.assertEqual(status, 200)
        return payload["token"]

    def test_authentication_and_existing_channels(self):
        status, _ = self.request("stb", "handshake", mac="00:00:00:00:00:00")
        self.assertEqual(status, 403)
        status, _ = self.request("itv", "get_genres")
        self.assertEqual(status, 401)
        token = self.login()
        status, profile = self.request("stb", "get_profile", token)
        self.assertEqual((status, profile["id"]), (200, "1"))
        first = self.request("itv", "get_ordered_list", token, genre="*", p=1)[1]
        last = self.request("itv", "get_ordered_list", token, genre="*", p=3)[1]
        self.assertEqual((first["total_items"], len(first["data"]), len(last["data"])), (5, 2, 1))
        status, link = self.request("itv", "create_link", token, cmd=first["data"][0]["cmd"])
        self.assertEqual(status, 200)
        self.assertTrue(link["cmd"].endswith("/sample.mp4"))

    def test_guide_is_channel_scoped_and_ordered(self):
        token = self.login()
        status, guide = self.request("itv", "get_short_epg", token, ch_id="1", size=3)
        self.assertEqual(status, 200)
        self.assertEqual(len(guide["data"]), 3)
        self.assertTrue(all(row["ch_id"] == "1" for row in guide["data"]))
        self.assertTrue(all(row["stop_timestamp"] - row["start_timestamp"] == 3600
                            for row in guide["data"]))
        self.assertEqual(guide["data"][1]["start_timestamp"], guide["data"][0]["stop_timestamp"])
        status, another = self.request("itv", "get_epg_info", token, ch_id="2")
        self.assertEqual(status, 200)
        self.assertNotEqual(guide["data"][0]["name"], another["data"][0]["name"])
        self.assertEqual(self.request("itv", "get_short_epg", token, ch_id="99")[0], 404)
        self.assertEqual(self.request("itv", "get_short_epg", token, ch_id="1", size=0)[0], 400)

    def test_movies_series_seasons_episodes_and_links(self):
        token = self.login()
        categories = self.request("vod", "get_categories", token)[1]
        self.assertEqual([row["id"] for row in categories], ["11", "21"])
        first = self.request("vod", "get_ordered_list", token, category="*", p=1)[1]
        second = self.request("vod", "get_ordered_list", token, category="*", p=2)[1]
        self.assertEqual((first["total_items"], len(first["data"]), len(second["data"])), (3, 2, 1))
        self.assertEqual(first["data"][0]["screenshot_uri"], "mountain")
        self.assertEqual(second["data"][0]["is_series"], 1)
        self.assertEqual(len(self.request("vod", "get_ordered_list", token, category="11")[1]["data"]), 2)
        self.assertEqual(len(self.request("vod", "get_ordered_list", token, category="21")[1]["data"]), 1)
        seasons = self.request("vod", "get_seasons", token, series_id="201")[1]["data"]
        self.assertEqual([row["season_number"] for row in seasons], [1, 2])
        episodes = self.request("vod", "get_episodes", token, series_id="201", season_id="301")[1]["data"]
        self.assertEqual(len(episodes), 2)
        for cmd in (first["data"][0]["cmd"], episodes[0]["cmd"]):
            status, link = self.request("vod", "create_link", token, cmd=cmd)
            self.assertEqual(status, 200)
            self.assertTrue(link["cmd"].endswith("/sample.mp4"))
        self.assertEqual(self.request("vod", "get_episodes", token, series_id="201", season_id="999")[0], 404)
        self.assertEqual(self.request("vod", "get_ordered_list", token, p=0)[0], 400)


if __name__ == "__main__":
    unittest.main()
