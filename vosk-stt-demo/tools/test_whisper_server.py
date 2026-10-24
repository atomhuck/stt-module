"""Проверка локального HTTP-контракта без загрузки модели Whisper."""

import json
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from whisper_server import FakeBackend, make_handler


class FakeWhisperContractTest(unittest.TestCase):
    """Проверяет оба маршрута расшифровки и код неизвестного POST."""

    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(FakeBackend()))
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def post(self, path, pcm):
        request = Request(
            self.base + path,
            data=pcm,
            headers={"Content-Type": "application/octet-stream"},
            method="POST",
        )
        return urlopen(request, timeout=2)

    def test_text_and_detailed_output(self):
        pcm = b"\x00\x00" * 16000
        with self.post("/transcribe", pcm) as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(response.read().decode("utf-8"), "[fake] 1000 мс аудио")
        with self.post("/transcribe-json", pcm) as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(json.load(response), {"text": "[fake] 1000 мс аудио", "words": []})

    def test_unknown_post_returns_404(self):
        with self.assertRaises(HTTPError) as caught:
            self.post("/unknown", b"\x00\x00")
        self.assertEqual(caught.exception.code, 404)


if __name__ == "__main__":
    unittest.main()
