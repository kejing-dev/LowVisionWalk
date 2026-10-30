#!/usr/bin/env bash
# Developer / QA helper, pinned to the factory emulator of this app (Git Bash).
#   DEV (required: the emulator assigned in state.json), AVD (required)
#
#   tools/dev.sh build                 assembleDebug (inline JAVA_HOME: primer JDK 21)
#   tools/dev.sh install               install the debug APK and (re)start the app
#   tools/dev.sh start | stop          start / force-stop the app
#   tools/dev.sh cmd NAME [ARG]        debug broadcast (see AGENTS.md "Debug broadcast"), e.g. cmd room S4
#   tools/dev.sh am EXTRAS...         debug broadcast with raw extras (state jumps with disease / level)
#   tools/dev.sh shot NAME             console screenshot -> .scratch/shots/NAME.png (+ NAME_half.png)
#   tools/dev.sh log [REGEX]           LowVision log lines (optionally filtered)
set -u
DEV="${DEV:-emulator-5566}"
AVD="${AVD:-PICO_6_1_Factory4}"
PKG=io.embodit.lowvision
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHOTS="${SHOTS:-$ROOT/.scratch/shots}"
mkdir -p "$SHOTS"
A="adb -s $DEV"

check() {
  local avd
  avd="$($A emu avd name 2>/dev/null | head -1 | tr -d '\r')"
  if [ "$avd" != "$AVD" ]; then echo "WRONG AVD on $DEV: '$avd' (expected $AVD)" >&2; exit 2; fi
}

act="${1:-}"; shift || true
case "$act" in
  build)
    export JAVA_HOME="${JAVA_HOME:-C:/Users/Admin/.pico/primer-cli/jdk/jdk-21.0.12.1+1}"
    (cd "$ROOT" && ./gradlew.bat assembleDebug --console=plain -q) ;;
  install)
    check
    $A install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" | tail -1
    $A shell am force-stop $PKG
    $A shell am start -n $PKG/.platform.LaunchActivity | tail -1 ;;
  start) check; $A shell am start -n $PKG/.platform.LaunchActivity | tail -1 ;;
  stop) check; $A shell am force-stop $PKG ;;
  cmd)
    check
    name="$1"; shift || true
    if [ $# -gt 0 ]; then $A shell am broadcast -a $PKG.DEBUG -p $PKG --es cmd "$name" --es s "$1" >/dev/null
    else $A shell am broadcast -a $PKG.DEBUG -p $PKG --es cmd "$name" >/dev/null; fi
    sleep 0.5
    $A logcat -d -s LowVision:I | grep -E "CMD (ok|fail|reached|timeout)|DUMP|SCENE|ITEM|POSE|PANEL id" | tail -6 ;;
  am)
    # raw extras: tools/dev.sh am --es cmd state --es id S10 --es disease amd --ei level 3
    check
    $A shell am broadcast -a $PKG.DEBUG -p $PKG "$@" >/dev/null
    sleep 0.5
    $A logcat -d -s LowVision:I | grep -E "CMD (ok|fail|reached|timeout)|DUMP|SCENE|ITEM|POSE" | tail -6 ;;
  shot)
    check
    name="${1:-shot}"
    dir="$(cygpath -w "$SHOTS")"
    # a posted focus-in gives ~10 s of 60 fps: without it the console often returns an old frame
    "$ROOT/tools/harness/emu.sh" fast on >/dev/null 2>&1; sleep 1.5
    before="$(ls -t "$SHOTS"/Screenshot_*.png 2>/dev/null | head -1)"
    $A emu screenrecord screenshot "$dir" >/dev/null
    for i in $(seq 1 20); do
      latest="$(ls -t "$SHOTS"/Screenshot_*.png 2>/dev/null | head -1)"
      [ -n "$latest" ] && [ "$latest" != "$before" ] && break
      sleep 0.3
    done
    mv "$latest" "$SHOTS/$name.png"
    python -I -c "import sys;from PIL import Image;im=Image.open(sys.argv[1]);im.resize((im.width//2,im.height//2)).save(sys.argv[2])" "$SHOTS/$name.png" "$SHOTS/${name}_half.png"
    echo "$SHOTS/$name.png" ;;
  log) $A logcat -d -s LowVision:I | grep -E "${1:-.}" | tail -40 ;;
  *) sed -n 2,12p "$0" ;;
esac
