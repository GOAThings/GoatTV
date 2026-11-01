#!/usr/bin/env bash
# Volta pela interface de telemóvel, com prints em build/phone. Corre com um emulador ligado e a lista em :8765.
set -u
OUT=build/phone
mkdir -p "$OUT"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb reverse tcp:8765 tcp:8765
read -r W H < <(adb shell wm size | sed -E 's/.* ([0-9]+)x([0-9]+).*/\1 \2/' | tr -d '\r')
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
tab() { adb shell input tap $((W * (2 * $1 + 1) / 8)) $((H - 130)); sleep 3; }

adb shell am start -n cc.slokker.itv.debug/cc.slokker.itv.MainActivity >/dev/null
sleep 12
shot 01-welcome
adb shell am force-stop cc.slokker.itv.debug
adb shell am start -n cc.slokker.itv.debug/cc.slokker.itv.MainActivity --es demo_m3u http://127.0.0.1:8765/demo.m3u >/dev/null
sleep 35
shot 02-channels
adb shell input tap $((W / 2)) $((H * 60 / 100))
sleep 6
shot 03-channel-opened
adb shell input swipe $((W / 2)) $((H * 60 / 100)) $((W / 2)) $((H * 60 / 100)) 900
sleep 3
shot 04-guide
adb shell input keyevent BACK
sleep 2
tab 1; shot 05-favorites
tab 2; shot 06-search
tab 3; shot 07-menu
adb shell input swipe $((W / 2)) $((H * 70 / 100)) $((W / 2)) $((H * 30 / 100)) 300
sleep 2
shot 08-menu-more
tab 0
adb shell am force-stop cc.slokker.itv.debug
adb shell am start -n cc.slokker.itv.debug/cc.slokker.itv.MainActivity >/dev/null
sleep 25
shot 09-reopen-last-channel
adb shell cmd window user-rotation lock 1 2>/dev/null || adb shell settings put system user_rotation 1
sleep 6
shot 10-landscape
adb logcat -d > "$OUT/logcat.txt"
