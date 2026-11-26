#!/bin/bash
# Drive >10 sequential stored-pin drops via the app's own Drop button.
# ADB taps reach only the app's setup control; they never generate the functional
# input under test (this prototype tests reconnect logic, not HID delivery).
# OnePlus wrinkles: screen locks after 15 s (ScreenOnGate holds the retry budget
# while dark) and a clipboard banner can cover the Drop button.
D=192.168.31.64:5555
LOG=/tmp/v1-acceptance/adr062-phone.log
SUMMARY=/tmp/v1-acceptance/adr062-drops.txt
TARGET=12
: > "$SUMMARY"

hs() { grep -c 'handshake complete' "$LOG"; }
drops() { grep -c 'drop requested by app' "$LOG"; }
wake_if_dark() {
  w=$(adb -s $D shell 'dumpsys power | grep -o "mWakefulness=[A-Za-z]*" | head -1' 2>/dev/null)
  case "$w" in *Awake*) : ;; *) adb -s $D shell input keyevent 224 >/dev/null 2>&1 ;; esac
}
dismiss_banner() {
  adb -s $D shell 'uiautomator dump /sdcard/win.xml >/dev/null 2>&1'
  db=$(adb -s $D shell 'cat /sdcard/win.xml' | tr '<' '\n' \
    | grep -oE 'text="Dismiss"[^>]*bounds="\[[0-9,]+\]\[[0-9,]+\]"' \
    | grep -oE 'bounds="[^"]+"' | head -1 | sed -E 's/[^0-9]/ /g')
  if [ -n "$(echo "$db" | tr -d ' ')" ]; then
    set -- $db
    [ $# -ge 4 ] && adb -s $D shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) >/dev/null 2>&1
  fi
}

echo "starting: drops=$(drops) handshakes=$(hs) (target $TARGET reconnects)"

n=0
while [ "$n" -lt "$TARGET" ]; do
  h0=$(hs); d0=$(drops)
  wake_if_dark; sleep 1
  dismiss_banner
  # Drop button is stable at bounds [164,864][916,921] -> center (540,892).
  adb -s $D shell input tap 540 892 >/dev/null 2>&1
  sleep 2
  # If the tap did not register as a drop, wake and retry once.
  if [ "$(drops)" -eq "$d0" ]; then
    wake_if_dark; sleep 1
    adb -s $D shell input tap 540 892 >/dev/null 2>&1
    sleep 2
  fi
  n=$((n + 1))
  registered=$([ "$(drops)" -gt "$d0" ] && echo yes || echo no)
  echo "cycle $n: drop_registered=$registered (handshakes before=$h0)" >> "$SUMMARY"

  # Wait for a NEW handshake, waking only when the screen is dark.
  ok=0
  for t in $(seq 1 45); do
    wake_if_dark
    sleep 1
    if [ "$(hs)" -gt "$h0" ]; then ok=1; break; fi
  done
  last=$(grep -E 'reconnect scheduled attempt=|state=CONNECTED attempt=|giving up' "$LOG" | tail -3 | tr '\n' '|')
  echo "cycle $n: reconnect_ok=$ok | $last" >> "$SUMMARY"
  if [ "$ok" -eq 0 ]; then
    echo "cycle $n: reconnect did not complete; stopping" | tee -a "$SUMMARY"
    break
  fi
done

echo "=== final counts ==="
echo "drop requests: $(drops)"
echo "reconnect scheduled: $(grep -c 'reconnect scheduled attempt=' "$LOG")"
echo "handshakes: $(hs)"
echo "GAVE_UP lines: $(grep -c 'giving up after' "$LOG")"
echo "unique reconnect attempt values: $(grep -oE 'reconnect scheduled attempt=[0-9]+' "$LOG" | sort -u | tr '\n' ' ')"
