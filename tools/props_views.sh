#!/usr/bin/env bash
# v0.1.5 q3-2 evidence (profile D25 / B12): the Tripo props in the app. The table with the three items on the floor
# marker, veil off: front, side (turned 90° with the thumbstick command) and from above (head down ~45°); the sign; the
# shoe of the challenge. Emulator only; the head is left level at the end.
#   DEV=emulator-5554 AVD=PICO_6_1_kj-alienware_1 tools/props_views.sh OUT_DIR
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:?out dir}"
mkdir -p "$OUT"
export PKG=io.embodit.lowvision TAG=LowVision SHOTS="$OUT"
H="$ROOT/tools/harness/emu.sh"
D="$ROOT/tools/dev.sh"
A="adb -s $DEV"
paint() { for _ in 1 2; do "$H" fast on >/dev/null 2>&1; sleep 3; done; "$H" winshot "$1" >/dev/null 2>&1; echo "shot $1"; }
pitch() { "$H" drag 1080 "$1" 1080 "$2" -Button right -Steps 40 -StepMs 15 -NoFast >/dev/null 2>&1; sleep 2; }
# the Tripo table shows when the scan found no table (virtual stand-in, S19): try up to 4 starts
for try in 1 2 3 4; do
  $A shell am force-stop $PKG
  $A logcat -c
  $A shell am start -n $PKG/.platform.LaunchActivity >/dev/null
  sleep 6
  $D am --es cmd seed --ei n 7 >/dev/null
  $D am --es cmd state --es id S17 --es disease glaucoma --ei level 1 >/dev/null   # ready, veil off
  sleep 25
  if $A logcat -d -s LowVision:I | grep -q "S07 -> S19"; then echo "virtual table (try $try)"; break; fi
  echo "real table found (try $try), again"
done
$D am --es cmd marker --es s 1.2 >/dev/null         # floor marker 1.2 m ahead
sleep 2
$D am --es cmd bring --es s table >/dev/null        # the table (with the items) onto the marker
sleep 3
paint props-1-table-on-marker-front
$D am --es cmd stick --es s 0.8,1500 >/dev/null     # thumbstick: turn the table
sleep 4
paint props-2-table-on-marker-turned
pitch 600 1700                                      # head down ~40°: from above
paint props-3-table-on-marker-above
pitch 1700 600
$D am --es cmd bring --es s sign >/dev/null         # the (virtual) sign onto the marker
sleep 3
paint props-4-sign-on-marker
$D am --es cmd dump >/dev/null
$A logcat -d -v time -s LowVision:I | grep -E "MODEL|OBJECT|STATE|CMD (ok|fail|reached)|INSTANCE" > "$OUT/props-views.log"
echo done
