#!/usr/bin/env bash
# v0.1.6 evidence (profile D41, user's headset v0.1.5 crash: open the lens -> close it -> 「按住看原样」 = crash).
# Every entry twice: open -> close -> open again, and open A -> close -> open B. Headset only (the camera source and the
# SpatialML service are real there). "syskill" force-stops the SpatialML service, which is what the lens window's ×
# does (closeSpatialContainerById ... killProcessIfLast): the session dies under the app.
#   DEV=PB324XJGL2090081G tools/d41_reopen.sh OUT_DIR
set -u
OUT="${1:?out dir}"
mkdir -p "$OUT"
DEV="${DEV:-PB324XJGL2090081G}"
PKG=io.embodit.lowvision
A="adb -s $DEV"
LOG="$OUT/d41-reopen.log"
# DEBUG_LIVE: the run-time receiver (the headset can hold broadcasts to the manifest one for minutes)
cmd() { $A shell am broadcast -a $PKG.DEBUG_LIVE -p $PKG --es cmd "$@" >/dev/null; }
state() { cmd state --es id "$1"; sleep "${2:-4}"; }
mark() { sync; echo "== $*"; $A shell "log -t LowVision 'D41 $(echo "$*" | tr -d "'<>;()")'"; }
# wait until the app has handled everything sent so far (broadcasts to it were once held back 4 min device-wide)
SYNCS=0
sync() {
  $A shell input keyevent KEYCODE_WAKEUP   # unworn headset sleeps after a few minutes and then refuses the Full Space
  SYNCS=$((SYNCS + 1)); cmd seed --ei n 7
  for _ in $(seq 1 60); do [ "$(grep -c 'CMD ok seed' "$LOG")" -ge "$SYNCS" ] && return 0; sleep 1; done
  echo "app did not answer within 60 s"; kill $LC 2>/dev/null; echo FAIL; exit 1
}
syskill() { $A shell am force-stop com.bytedance.pico.openmr.spatial; }

$A shell input keyevent KEYCODE_WAKEUP
$A shell am force-stop $PKG
$A shell am start -n $PKG/.platform.LaunchActivity >/dev/null
sleep 6
PID="$($A shell pidof $PKG | tr -d '\r')"
# by tag, not --pid: a platform RmClient thread logs ~65k lines/s in every SpatialML-using process and buries the rest
$A logcat -v threadtime -T 1 -s LowVision:V AndroidRuntime:E > "$LOG" &
LC=$!
state S02 2

for r in 1 2; do
  mark "round $r lens: open -> close -> open -> hold"
  cmd lensOpen; sleep 5; cmd lensClose; sleep 2
  cmd lensOpen; sleep 5; cmd lensHold --es s 1; sleep 2; cmd lensHold --es s 0; sleep 1; cmd lensClose; sleep 2

  mark "round $r lens: camera -> close -> kitchen photo; pills -> close -> camera"
  cmd lensOpen; sleep 4; cmd lensClose; sleep 2
  cmd lensOpen; sleep 3; cmd lensSource --es s kitchen; sleep 3; cmd lensHold --es s 1; sleep 1; cmd lensHold --es s 0
  cmd lensSource --es s pills; sleep 2; cmd lensClose; sleep 2
  cmd lensOpen; sleep 4; cmd lensClose; sleep 2

  mark "round $r lens: closed by the system (×) -> hold at once -> open -> hold"
  cmd lensOpen; sleep 5; syskill; cmd lensHold --es s 1; sleep 2; cmd lensHold --es s 0; sleep 1
  cmd lensOpen; sleep 5; cmd lensHold --es s 1; sleep 2; cmd lensHold --es s 0; sleep 1; cmd lensClose; sleep 2

  mark "round $r lens: photo source closed by the system -> switch source -> open"
  cmd lensOpen; sleep 3; cmd lensSource --es s stairs; sleep 3; syskill; cmd lensSource --es s kitchen; sleep 3
  cmd lensOpen; sleep 5; cmd lensClose; sleep 2

  mark "round $r walk: open -> home -> open; walk -> home -> lens; lens -> close -> walk"
  state S06 8; state S02 6; state S06 8; state S02 6
  state S21 8; cmd lensClose; sleep 2; state S06 8; state S02 6

  mark "round $r settings / quit dialog: open -> close -> open"
  state S05 2; state S02 2; state S05 2; state S05Q 2; cmd quit --es s cancel; sleep 1; state S05Q 2; cmd quit --es s cancel; sleep 1
  state S02 2
done

sleep 2
kill $LC 2>/dev/null
NOW="$($A shell pidof $PKG | tr -d '\r')"
echo "pid before=$PID after=$NOW"
echo "FATAL: $(grep -c 'FATAL EXCEPTION' "$LOG")  lost: $(grep -c 'LENS session lost' "$LOG")  pictures up: $(grep -c 'LENS picture up' "$LOG")  ready: $(grep -c 'LENS ready' "$LOG")"
[ "$PID" = "$NOW" ] && ! grep -q 'FATAL EXCEPTION' "$LOG" && grep -q 'LENS ready' "$LOG" && echo PASS || { echo FAIL; exit 1; }
