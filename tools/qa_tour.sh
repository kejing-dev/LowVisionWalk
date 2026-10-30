#!/usr/bin/env bash
# QA tour: jump to design states over the debug broadcast and shoot each twice (the second one counts).
#   tools/qa_tour.sh "S02" "S10 disease=amd level=3" ...    -> .scratch/tour/<id>.png (+ _half)
set -u
DEV="${DEV:-emulator-5566}"
PKG=io.embodit.lowvision
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export SHOTS="${SHOTS:-$ROOT/.scratch/tour}"
mkdir -p "$SHOTS"
A="adb -s $DEV"
for spec in "$@"; do
  set -- $spec
  id="$1"; shift
  extras=(--es cmd state --es id "$id")
  name="$id"
  for kv in "$@"; do
    k="${kv%%=*}"; v="${kv#*=}"
    if [ "$k" = "name" ]; then name="$v"; continue; fi
    extras+=(--es "$k" "$v")
  done
  $A logcat -c
  $A shell am broadcast -a $PKG.DEBUG -p $PKG "${extras[@]}" >/dev/null
  for i in $(seq 1 60); do
    if $A logcat -d -s LowVision:I | grep -qE "CMD (reached|timeout)"; then break; fi
    sleep 0.5
  done
  $A logcat -d -s LowVision:I | grep -E "CMD (reached|timeout|fail)" | tail -1
  sleep "${SETTLE:-4}"
  DEV=$DEV "$ROOT/tools/dev.sh" shot "${name}_a" >/dev/null
  DEV=$DEV "$ROOT/tools/dev.sh" shot "$name"
done
