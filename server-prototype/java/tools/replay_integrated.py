"""Feed a local 16 kHz mono WAV through the integrated browser API for smoke testing.

The original WAV is treated as computer audio; the microphone track is silent.
This is a protocol check, not a replacement for a live multi-speaker demo.
"""
import argparse
import json
import struct
import time
import urllib.request
import wave

BASE = "http://127.0.0.1:8765"
CHUNK = 10240
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def request(path, data=None, session=None, sequence=None):
    headers = {}
    if session:
        headers["X-Session-Id"] = session
    if sequence is not None:
        headers["X-Sequence"] = str(sequence)
    req = urllib.request.Request(BASE + path, data=data, headers=headers,
                                 method="POST" if data is not None else "GET")
    with OPENER.open(req, timeout=120) as response:
        return json.load(response)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("wav", help="16 kHz mono PCM16 WAV")
    ap.add_argument("--seconds", type=float, default=40, help="live replay duration")
    ap.add_argument("--enrollment-start", type=float, default=20)
    ap.add_argument("--enrollment-seconds", type=float, default=10)
    args = ap.parse_args()
    with wave.open(args.wav, "rb") as wav:
        if (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) != (16000, 1, 2):
            raise ValueError("WAV must be 16 kHz mono PCM16")
        status = request("/api/enrollment/start", b"")
        session = status["session_id"]
        wav.setpos(int(args.enrollment_start * 16000))
        remaining = int(args.enrollment_seconds * 16000)
        sequence = 0
        while remaining:
            count = min(CHUNK, remaining)
            block = wav.readframes(count)
            request("/api/enrollment/audio", block, session, sequence)
            remaining -= count
            sequence += 1
        status = request("/api/enrollment/finish", b"", session)
        if status["phase"] != "live":
            raise RuntimeError(f"Enrollment failed: {status}")
        for _ in range(30):
            status = request("/api/status", session=session)
            if status.get("integration", {}).get("question"):
                break
            time.sleep(.2)
        request("/api/question/asked", b"", session)
        wav.rewind()
        remaining = min(int(args.seconds * 16000), wav.getnframes())
        sequence = 0
        began = time.monotonic()
        while remaining:
            count = min(CHUNK, remaining)
            system = wav.readframes(count)
            values = struct.unpack("<" + "h" * count, system)
            pair = struct.pack("<" + "h" * (2 * count), *(v for sample in values for v in (sample, 0)))
            request("/api/audio", pair, session, sequence)
            remaining -= count
            sequence += 1
            deadline = began + sequence * .64
            time.sleep(max(0, deadline - time.monotonic()))
        request("/api/finish", b"", session)
        for _ in range(240):
            status = request("/api/status", session=session)
            if status["phase"] in ("finished", "error"):
                print(json.dumps(status, ensure_ascii=False, indent=2))
                return
            time.sleep(.25)
        raise TimeoutError("Integrated run did not finish within 60 seconds")


if __name__ == "__main__":
    main()
