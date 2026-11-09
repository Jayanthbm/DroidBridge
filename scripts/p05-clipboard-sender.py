#!/usr/bin/env python3
"""P05 Mac -> Android clipboard sender (development harness).

Listens for the P05 Android receiver and sends one or more length-prefixed UTF-8 payloads:

    [4-byte big-endian length][UTF-8 text]

This is a test harness, not the product transport. P03 owns TLS/pairing; P05 measures what Android
does when a background app writes the clipboard, so the transport is deliberately plain TCP.

Usage:
  python3 scripts/p05-clipboard-sender.py --payload unicode
  python3 scripts/p05-clipboard-sender.py --payload duplicate --repeat 2 --delay 0.3
  python3 scripts/p05-clipboard-sender.py --file /tmp/p05-payload.txt
  python3 scripts/p05-clipboard-sender.py --payload large --hold 5

The script never prints clipboard text, only payload name, byte length and hash prefix.
"""

import argparse
import hashlib
import socket
import struct
import sys
import time

# Synthetic payloads only. Never use real clipboard content for a prototype run.
PAYLOADS = {
    "foreground": "p05-foreground-sample",
    "background": "p05-background-sample",
    "crossapp": "p05-cross-app-sample",
    "duplicate": "p05-duplicate-sample",
    "unicode": "P05 emoji \U0001F600 CJK \u6f22\u5b57\nline2\n  leading/trailing  \u200b",
    "newlines": "p05-line1\np05-line2\r\np05-line3\n",
    "empty": "",
    "large": "A" * (100 * 1024),
    "oversize": "A" * (100 * 1024 + 1),
}


def send_frame(conn: socket.socket, data: bytes) -> None:
    conn.sendall(struct.pack(">I", len(data)) + data)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=47900)
    parser.add_argument("--payload", default="foreground", choices=sorted(PAYLOADS))
    parser.add_argument("--file", help="read UTF-8 text from this file instead of a built-in payload")
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--delay", type=float, default=0.5)
    parser.add_argument("--hold", type=float, default=1.0, help="seconds to keep the connection open")
    args = parser.parse_args()

    if args.file:
        with open(args.file, "r", encoding="utf-8") as handle:
            text = handle.read()
        label = f"file:{args.file}"
    else:
        text = PAYLOADS[args.payload]
        label = args.payload

    data = text.encode("utf-8")
    digest = hashlib.sha256(data).hexdigest()[:12]

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((args.host, args.port))
    server.listen(1)
    print(f"P05_SENDER listening host={args.host} port={args.port}", flush=True)
    try:
        conn, peer = server.accept()
    except KeyboardInterrupt:
        server.close()
        return 130

    print(f"P05_SENDER connected peer={peer[0]}:{peer[1]}", flush=True)
    try:
        for index in range(args.repeat):
            try:
                send_frame(conn, data)
                print(
                    f"P05_SENDER sent index={index + 1}/{args.repeat} payload={label} "
                    f"bytes={len(data)} hash={digest}",
                    flush=True,
                )
            except OSError as error:
                print(f"P05_SENDER send failed: {type(error).__name__}: {error}", flush=True)
                break
            time.sleep(max(0.0, args.delay))
        time.sleep(max(0.0, args.hold))
    finally:
        conn.close()
        server.close()
    print("P05_SENDER closed", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
