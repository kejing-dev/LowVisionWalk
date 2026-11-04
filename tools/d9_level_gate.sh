#!/usr/bin/env bash
# v0.1.5 q3-1 evidence (profile D9, LevelGate): head down 30° -> finish the mug -> the home window waits with the level
# hint; head level -> it opens upright; head down again -> 「叫回」 waits the same way. Emulator only.
#   DEV=emulator-5554 AVD=PICO_6_1_kj-alienware_1 tools/d9_level_gate.sh OUT_DIR
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:?out dir}"
mkdir -p "$OUT"
export PKG=io.embodit.lowvision TAG=LowVision SHOTS="$OUT"
H="$ROOT/tools/harness/emu.sh"
D="$ROOT/tools/dev.sh"
A="adb -s $DEV"
paint() { for _ in 1 2 3; do "$H" fast on >/dev/null 2>&1; sleep 3; done; "$H" winshot "$1" >/dev/null 2>&1; echo "shot $1"; }
pitch() { "$H" drag 1080 "$1" 1080 "$2" -Button right -Steps 40 -StepMs 15 -NoFast >/dev/null 2>&1; sleep 2; }
$A shell am force-stop $PKG
$A logcat -c
$A shell am start -n $PKG/.platform.LaunchActivity >/dev/null
sleep 6
$D am --es cmd seed --ei n 7 >/dev/null
$D am --es cmd state --es id S10 --es disease glaucoma --ei level 3 >/dev/null
sleep 22
pitch 600 1400                                      # head down ~30°
$D am --es cmd pose >/dev/null
$D am --es cmd aim --es target cup --ef offsetDeg 0 >/dev/null
sleep 2
$D am --es cmd tap --es s 0 >/dev/null              # S11: the window is wanted
sleep 6
paint d9-1-head-down-result-waits
pitch 1400 600                                      # head level
$D am --es cmd pose >/dev/null
sleep 3
paint d9-2-level-window-opens
$D am --es cmd next >/dev/null                      # S12 (read the sign): the window steps aside
sleep 6
pitch 600 1400                                      # head down again
$D am --es cmd recall >/dev/null                    # the pill's 「叫回」
sleep 6
paint d9-3-head-down-recall-waits
pitch 1400 600
sleep 3
paint d9-4-level-recall-opens
$D am --es cmd pose >/dev/null
$A logcat -d -v time -s LowVision:I | grep -E "STATE|WINDOW|LEVEL|POSE|CMD (ok|reached)" > "$OUT/d9-level-gate.log"
echo done
