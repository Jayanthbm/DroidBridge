#!/usr/bin/env python3
"""P17 pairing-prompt verification: compare the numeric code on both sides before confirming.

Setup-only automation. ADB taps reach only the Android pairing dialog (a human action the owner
directed); no ADB input enters the HID path. The code is read from both the Mac's blueutil prompt
and the phone's pairing dialog and MUST match before either side is confirmed.
"""
import re
import subprocess
import sys
import time

MAC = "5c-17-cf-b4-1b-b9"
PHONE = "b6774a1d"


def phone_ui():
    subprocess.run(["adb", "-s", PHONE, "shell", "uiautomator", "dump", "/sdcard/w.xml"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
    out = subprocess.run(["adb", "-s", PHONE, "shell", "cat", "/sdcard/w.xml"],
                         capture_output=True, text=True, timeout=15).stdout
    return out


def phone_code():
    ui = phone_ui()
    if "Bluetooth pairing request" not in ui:
        return None, ui
    # The pairing code is a standalone 6-digit text node next to "Pairing code".
    texts = re.findall(r'text="([^"]+)"', ui)
    for i, t in enumerate(texts):
        if "Pairing code" in t and i + 1 < len(texts):
            candidate = texts[i + 1].strip()
            if re.fullmatch(r"\d{6}", candidate):
                return candidate, ui
    for t in texts:
        if re.fullmatch(r"\d{6}", t.strip()):
            return t.strip(), ui
    return None, ui


def tap_pair():
    ui = phone_ui()
    for node in re.findall(r'<node[^>]*text="Pair"[^>]*>', ui):
        m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if m:
            x = (int(m.group(1)) + int(m.group(3))) // 2
            y = (int(m.group(2)) + int(m.group(4))) // 2
            subprocess.run(["adb", "-s", PHONE, "shell", "input", "tap", str(x), str(y)],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
            return (x, y)
    return None


def main():
    proc = subprocess.Popen(["blueutil", "--pair", MAC],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, bufsize=1)
    mac_code = None
    deadline = time.time() + 40
    while time.time() < deadline and mac_code is None:
        line = proc.stdout.readline()
        if not line:
            break
        print("MAC:", line.rstrip(), flush=True)
        m = re.search(r"display number (\d{6})", line)
        if m:
            mac_code = m.group(1)
    if mac_code is None:
        print("FAIL: no Mac code surfaced")
        proc.kill()
        return 1

    # Wait for the phone dialog and read its code.
    phone_code_value = None
    deadline = time.time() + 25
    while time.time() < deadline:
        phone_code_value, ui = phone_code()
        if phone_code_value:
            break
        time.sleep(1)
    print(f"CODES mac={mac_code} phone={phone_code_value}", flush=True)

    if phone_code_value != mac_code:
        print("FAIL: codes do not match; refusing to confirm either side")
        proc.stdin.write("no\n")
        proc.stdin.flush()
        proc.wait(timeout=10)
        return 2

    # Codes verified equal. Confirm both sides.
    point = tap_pair()
    print(f"PHONE: tapped Pair at {point}", flush=True)
    proc.stdin.write("yes\n")
    proc.stdin.flush()
    try:
        out, _ = proc.communicate(timeout=30)
        print("MAC:", out.strip(), flush=True)
    except subprocess.TimeoutExpired:
        proc.kill()
    time.sleep(3)
    bonds = subprocess.run(["adb", "-s", PHONE, "shell", "dumpsys", "bluetooth_manager"],
                           capture_output=True, text=True, timeout=15).stdout
    events = [l.strip() for l in bonds.splitlines() if "bond_state_changed" in l][-3:]
    print("BOND EVENTS:", " | ".join(events), flush=True)
    paired = subprocess.run(["blueutil", "--paired"], capture_output=True, text=True, timeout=15).stdout
    print("MAC PAIRED:", [l.strip() for l in paired.splitlines() if MAC.replace("-", ":") in l.lower()
                          or "oneplus" in l.lower() or "nord" in l.lower()], flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
