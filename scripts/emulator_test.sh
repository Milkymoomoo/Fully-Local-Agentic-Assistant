#!/usr/bin/env bash
# End-to-end checks on a running emulator/phone with the debug APK installed.
# Proof (screenshots + event log) goes to proof/<tag>/. Needs /tmp/{wake_timer,sleep,w1only}.wav (16 kHz mono).
set -u
TAG=${1:-api29}
OUT=$(cd "$(dirname "$0")/.." && pwd)/proof/$TAG; mkdir -p "$OUT"
PKG=app.belle.assistant
br() { adb shell am broadcast -n $PKG/.DebugReceiver -a "$@" >/dev/null; }
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
MARK=0
mark() { MARK=$(wc -l < "$OUT/events.log"); }
# waits for a pattern in events logged after the last mark
waitfor() { local t=0; while ! tail -n +$((MARK+1)) "$OUT/events.log" | grep -qE "$1"; do sleep 2; t=$((t+2)); [ $t -ge ${2:-120} ] && { echo "TIMEOUT: $1"; return 1; }; done; echo "OK: $1"; }
adb shell am force-stop $PKG; sleep 2
adb logcat -c
adb logcat -v time -s BelleEvent:I > "$OUT/events.log" &
LOG=$!
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard 2>/dev/null
adb shell am start -n $PKG/.ui.MainActivity >/dev/null; shot 01_home 8
br START; waitfor "mode=WAKE" 300; shot 02_ready 3
for c in "set a timer for 5 minutes" "turn on the flashlight" "what is the battery level" "open wifi settings" "set an alarm for 7:30 am" "turn the volume up" "goodnight bob"; do
  br TEXT --es text "'$c'" --ez speak false; sleep 5
done
waitfor "session ended \(sleep word\)" 90
mark; waitfor "mode=WAKE" 400   # goodbye spoken (TTS is ~30x slower under TCG software emulation)
br PREF --es continuous 1 --es ondemand 1 --es timeout 8
for f in wake_timer sleep w1only; do adb push /tmp/$f.wav /data/local/tmp/$f.wav >/dev/null; done
mark; br INJECT --es path /data/local/tmp/wake_timer.wav
waitfor "wake: " 180 && shot 03_wake 1
waitfor "heard: " 300
waitfor "reply\[" 300; shot 04_reply 2
waitfor "ui=LISTENING" 300
mark; br INJECT --es path /data/local/tmp/sleep.wav
waitfor "session ended \((sleep word|.*)\)" 300 && shot 05_sleep 1; mark
waitfor "idle: unloaded" 120
waitfor "mode=WAKE" 400; mark
br INJECT --es path /data/local/tmp/w1only.wav
waitfor "timed out after" 300 && shot 06_timeout 1; mark; waitfor "mode=WAKE" 400
# ---- assistant profiles: overlapping wake words, exactly one answers ----
wake_as() {  # $1 = expected answering assistant ("" = nobody)
  sleep 2; mark
  br INJECT --es path /data/local/tmp/w1only.wav
  if [ -n "$1" ]; then waitfor "wake: .* → " 240; if tail -n +$((MARK+1)) "$OUT/events.log" | grep -q "wake: .* → $1"; then echo "PROFILE-OK: answered by $1"; else echo "PROFILE-FAIL: expected $1"; fi
    [ $(tail -n +$((MARK+1)) "$OUT/events.log" | grep -c "wake: .* →") -le 1 ] && echo "PROFILE-OK: exactly one answered"
    mark; br END; waitfor "mode=WAKE" 400
  else waitfor "inject: done" 400; if tail -n +$((MARK+1)) "$OUT/events.log" | grep -q "wake: .* →"; then echo "PROFILE-FAIL: someone answered"; else echo "PROFILE-OK: nobody answered (not the active assistant's word)"; fi; fi
}
br PREF --es continuous 0 --es timeout 4
br PROFILE --es reset 1
br PROFILE --es create Dixie --es wake "hey dixie,hey bob"
br PROFILE --es create Jarvis --es wake "yo jarvis"
br PROFILE --es multi 1 --es active Bob;  wake_as Bob      # shared phrase, active owns it
br PROFILE --es active Dixie;               wake_as Dixie      # shared phrase, other active owns it
br PROFILE --es active Jarvis --es priority "Dixie,Bob"; wake_as Dixie   # active doesn't own it → priority order
br PROFILE --es priority "Bob,Dixie";     wake_as Bob
br PROFILE --es multi 0 --es active Jarvis; wake_as ""         # single mode: only Jarvis listens
br PROFILE --es active Bob
adb shell am start -n $PKG/.ui.MainActivity --es tab settings --es page assistants >/dev/null; shot 09_assistants 5
br PROFILE --es multi 1; adb shell am start -n $PKG/.ui.MainActivity --es tab settings --es page assistants >/dev/null; shot 09_assistants_multi 5
br PROFILE --es multi 0 --es active Bob
for th in dark light; do
  br PREF --es theme $th
  adb shell am force-stop $PKG; sleep 1
  adb shell am start -n $PKG/.ui.MainActivity --es tab home >/dev/null; shot "30_${th}_home" 8
  adb shell input swipe 500 1500 500 500 400; shot "30_${th}_home_setup" 2
  for p in "" getmodels assistants avatar look voice; do
    adb shell am start -n $PKG/.ui.MainActivity --es tab settings --es page "$p" >/dev/null; shot "31_${th}_settings_${p:-list}" 4
  done
  adb shell am start -n $PKG/.ui.MainActivity --es tab chat >/dev/null; shot "32_${th}_conversation" 4
done
br PREF --es theme dark
adb shell am start -n $PKG/.avatar.FramingEditorActivity >/dev/null; shot 40_framing 6
adb shell am start -n $PKG/.avatar.RigEditorActivity >/dev/null; shot 41_rig 6
for p in "" wake sleep conversation speech voice model internet news avatar setup look privacy; do
  adb shell am start -n $PKG/.ui.MainActivity --es tab settings --es page "$p" >/dev/null; shot "10_settings_${p:-list}" 4
done
adb shell am start -n $PKG/.ui.MainActivity --es tab chat >/dev/null; shot 20_conversation 4
br STATE; sleep 2; kill $LOG; echo DONE
