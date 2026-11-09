#!/usr/bin/env python3
"""P01 repeated start/stop harness for the MediaProjection experiment app.

Development-only: it drives the debug experiment UI over ADB (including the system consent
dialog). It is not part of the product and must never be treated as product evidence on its own;
it exists so the 20-cycle acceptance item can be run the same way twice.

Usage:
  python3 scripts/p01-capture-cycles.py [--cycles 20] [--settle 2.5]

Preconditions:
  - The S24 Ultra is attached, unlocked and on the home screen or the experiment activity.
  - `com.droidbridge.experiment.mediaprojection` is installed.
  - USB debugging is allowed for this development run (the report must state that).

What it records per cycle: teardown frames/bytes/keyframes, and whether the app process survived.
After the loop it records process id/thread/fd counts and any crash-buffer entries.
"""

import argparse
import re
import subprocess
import sys
import time

PKG = "com.droidbridge.experiment.mediaprojection"
ACTIVITY = f"{PKG}/.ExperimentActivity"


def adb(*args: str, check: bool = True) -> str:
    result = subprocess.run(["adb", *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def dump() -> str:
    for _ in range(3):
        result = subprocess.run(
            ["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
            capture_output=True,
            text=True,
        )
        if "dumped" in (result.stdout + result.stderr):
            break
        time.sleep(0.4)
    return adb("shell", "cat", "/sdcard/ui.xml", check=False)


def nodes(xml: str):
    out = []
    for match in re.finditer(r"<node[^>]*>", xml):
        tag = match.group(0)

        def attr(name: str) -> str:
            found = re.search(rf'{name}="([^"]*)"', tag)
            return found.group(1) if found else ""

        text = attr("text").strip()
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not text or not bounds:
            continue
        x1, y1, x2, y2 = map(int, bounds.groups())
        out.append({"text": text, "center": ((x1 + x2) // 2, (y1 + y2) // 2)})
    return out


def find(xml: str, needle: str):
    return [n for n in nodes(xml) if needle.lower() in n["text"].lower()]


def tap(needle: str, timeout: float = 10.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        hits = find(dump(), needle)
        if hits:
            x, y = hits[0]["center"]
            adb("shell", "input", "tap", str(x), str(y))
            return
        time.sleep(0.4)
    raise SystemExit(f"could not find tap target: {needle}")


def wait_for(needle: str, timeout: float) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if find(dump(), needle):
            return True
        time.sleep(0.4)
    return False


def scroll_to_top() -> None:
    adb("shell", "input", "swipe", "540", "700", "540", "1900", "250")
    time.sleep(0.4)


def process_stats() -> dict:
    pid = adb("shell", "pidof", PKG, check=False).strip()
    if not pid:
        return {"pid": None, "threads": None, "fds": None}
    threads = adb("shell", "ls", f"/proc/{pid}/task", check=False).split()
    # /proc/<pid>/fd is readable by the app's own uid, not by shell; run-as is the debug-only way
    # to count descriptors without root.
    fds = adb("shell", "run-as", PKG, "ls", f"/proc/{pid}/fd", check=False).split()
    return {"pid": pid, "threads": len(threads), "fds": len(fds)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cycles", type=int, default=20)
    parser.add_argument("--settle", type=float, default=2.5)
    args = parser.parse_args()

    adb("shell", "am", "start", "-n", ACTIVITY)
    time.sleep(1.5)
    scroll_to_top()
    before = process_stats()
    print(f"before: pid={before['pid']} threads={before['threads']} fds={before['fds']}")

    adb("logcat", "-c")
    results = []
    for cycle in range(1, args.cycles + 1):
        scroll_to_top()
        tap("Start capture")
        time.sleep(1.0)

        # Notification permission is requested once; handle it if it appears on the first cycle.
        if wait_for("Allow P01 Projection", 1.5):
            tap("Allow")
            time.sleep(0.5)

        if not wait_for("Share your screen", 10):
            raise SystemExit(f"cycle {cycle}: capture consent dialog never appeared")
        # The consent dialog defaults to single-app capture. Switch it to the whole display.
        if find(dump(), "Share one app"):
            tap("Share one app")
            time.sleep(0.5)
            tap("Share entire screen")
            time.sleep(0.5)
        tap("Share screen")

        if not wait_for("running: true", 12):
            raise SystemExit(f"cycle {cycle}: session never reported running")
        time.sleep(args.settle)
        tap("Stop")
        if not wait_for("running: false", 12):
            raise SystemExit(f"cycle {cycle}: session never reported stopped")

        log = adb("logcat", "-d", "-s", "P01Projection", check=False)
        teardowns = re.findall(
            r"TEARDOWN reason=user stop .*?frames=(\d+) bytes=(\d+) keyframes=(\d+).*?"
            r"durationMs=(\d+)",
            log,
        )
        if not teardowns:
            raise SystemExit(f"cycle {cycle}: no teardown line found")
        frames, byte_count, keyframes, duration = teardowns[-1]
        results.append(
            {
                "cycle": cycle,
                "frames": int(frames),
                "bytes": int(byte_count),
                "keyframes": int(keyframes),
                "duration_ms": int(duration),
            }
        )
        print(
            f"cycle {cycle:02d}: frames={frames} bytes={byte_count} "
            f"keyframes={keyframes} durationMs={duration}"
        )

    after = process_stats()
    crashes = adb("logcat", "-d", "-b", "crash", "-t", "200", check=False)
    crash_lines = [line for line in crashes.splitlines() if "FATAL EXCEPTION" in line]

    print("\nsummary")
    print(f"  cycles: {len(results)}")
    print(f"  frames: {sum(r['frames'] for r in results)}")
    print(f"  bytes: {sum(r['bytes'] for r in results)}")
    print(f"  process before: pid={before['pid']} threads={before['threads']} fds={before['fds']}")
    print(f"  process after:  pid={after['pid']} threads={after['threads']} fds={after['fds']}")
    print(f"  same process: {before['pid'] == after['pid'] and before['pid'] is not None}")
    print(f"  crash-buffer FATAL entries: {len(crash_lines)}")
    for line in crash_lines[-5:]:
        print(f"    {line}")
    return 0 if not crash_lines else 1


if __name__ == "__main__":
    sys.exit(main())
