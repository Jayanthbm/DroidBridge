#!/usr/bin/env bash
#
# P08 device-cost sampler: battery, temperature and frame counters during a live mirroring session.
#
# It does NOT start mirroring and does NOT grant MediaProjection consent — those are human steps.
# It only reads state while a session is already running, so nothing here shapes the measured input.
#
# Usage:
#   scripts/p08-device-cost-sample.sh <serial> <duration-minutes> [label]
# Example:
#   scripts/p08-device-cost-sample.sh 192.168.31.64:5555 60 oneplus-60m
#
# Output: /tmp/v1-acceptance/p08-cost-<label>.log
set -euo pipefail

SERIAL="${1:?usage: p08-device-cost-sample.sh <serial> <minutes> [label]}"
MINUTES="${2:?usage: p08-device-cost-sample.sh <serial> <minutes> [label]}"
LABEL="${3:-cost}"
OUT="/tmp/v1-acceptance/p08-cost-${LABEL}.log"
INTERVAL=60 # seconds
SAMPLES=$(( MINUTES * 60 / INTERVAL ))

: > "$OUT"
echo "device-cost sample start $(date '+%Y-%m-%d %H:%M:%S') serial=$SERIAL minutes=$MINUTES" >> "$OUT"

for i in $(seq 1 "$SAMPLES"); do
  TS=$(date '+%Y-%m-%d %H:%M:%S')
  if ! adb -s "$SERIAL" shell true >/dev/null 2>&1; then
    echo "$TS sample=$i ADB_UNREACHABLE" >> "$OUT"
    sleep "$INTERVAL"
    continue
  fi
  INFO=$(adb -s "$SERIAL" shell '
    level=$(dumpsys battery | grep -o "level: [0-9]*" | awk "{print \$2}")
    status=$(dumpsys battery | grep -o "status: [0-9]*" | awk "{print \$2}")
    plug=$(dumpsys battery | grep -o "plug: [0-9]*" | awk "{print \$2}")
    temp=$(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null || echo 0)
    pid=$(pidof com.droidbridge.app 2>/dev/null || true)
    echo "level=$level status=$status plug=$plug tempMilliC=$temp pid=${pid:-none}"
  ' 2>&1 | tr '\n' ' ')
  echo "$TS sample=$i $INFO" >> "$OUT"
  sleep "$INTERVAL"
done

echo "device-cost sample end $(date '+%Y-%m-%d %H:%M:%S')" >> "$OUT"
echo "wrote $OUT"
