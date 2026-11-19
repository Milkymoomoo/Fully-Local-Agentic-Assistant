#!/bin/bash
# Release gate: the whole photo → 3D → rigged, attached avatar in the RELEASE app on the emulator with the Java heap
# capped at 512 MB (dalvik.vm.heapgrowthlimit = dalvik.vm.heapsize = 512m, the limit the OUKITEL WP100 TITAN hit in 0.1.3),
# then a post-shape checkpoint taken during that run is rewritten as 0.1.3 left it (tries 3 of 3, no app_version) and resumed under the same cap (the update resets the tries).
# Cheap defaults so a software-CPU emulator can finish in tens of minutes: 1 + 1 sampling steps, 512 px crop, 20k
# triangles, and a 512×512 RGBA photo (skips BiRefNet). Every post-shape stage still runs (surface, decode, texture,
# rig, attach). Records per-stage peak Java heap, RSS and native buffers. Exit 0 only if both phases end with a
# rigged avatar attached to its assistant.
# Usage: scripts/heap_gate.sh <release x86_64 apk> <out dir>      (scripts/release.sh calls it; see AGENTS.md)
# Needs: the api29 x86_64 emulator (started here with 6 GB RAM if it isn't running with ≥ 5.5 GB), the phone model set
# in $P3D_MODELS (default /workspace/port3d/gate: the phone set from onnx/ + onnx_q8/) and art/dragoness.png (a 512×512 RGBA copy is made at runtime). Software CPU is the default (this box has no KVM access).
# GATE_ACCEL=on uses KVM where the user can open /dev/kvm.
set -euo pipefail
APK=${1:?release apk}; OUT=${2:?out dir}; mkdir -p "$OUT"
cd "$(dirname "$0")/.."
source /workspace/.toolchain/env.sh 2>/dev/null || true
PKG=app.belle.assistant; MODELS=${P3D_MODELS:-/workspace/port3d/gate}; PHOTO=${P3D_GATE_PHOTO:-}
EXTRA_MODEL=${P3D_BIREFNET:-/workspace/port3d/birefnet_512.onnx}

# 512×512 RGBA cut-out of art/dragoness.png (host BiRefNet, ~2 s). The phone can then skip that model.
# P3D_GATE_PHOTO, if set, is used as-is. The generated file is only a gate input, not an app asset.
prepare_gate_photo() {
  if [ -n "$PHOTO" ]; then return 0; fi
  local src=art/dragoness.png dest="$OUT/gate_photo_512.png"
  python3 - "$src" "$dest" "${P3D_BIREFNET:-/workspace/port3d/birefnet_512.onnx}" <<'PY' || return 1
import sys
from pathlib import Path
from PIL import Image
src, dest, model = sys.argv[1], sys.argv[2], sys.argv[3]
img = Image.open(src).convert("RGB").resize((512, 512), Image.Resampling.LANCZOS)
mask = None
if Path(model).is_file():
    try:
        import numpy as np, onnxruntime as ort
        x = (np.asarray(img).astype("float32") / 255.0).transpose(2, 0, 1)[None]
        opt = ort.SessionOptions(); opt.intra_op_num_threads = 4
        logits = ort.InferenceSession(model, opt, providers=["CPUExecutionProvider"]).run(None, {"image": x})[0]
        m = 1 / (1 + np.exp(-logits[0, 0]))
        mask = Image.fromarray((m * 255).clip(0, 255).astype("uint8"), "L")
    except Exception as e:
        print("host BiRefNet unavailable (%s); making a simple cut-out" % e)
if mask is None:
    # last resort: a centred oval, enough opaque and transparent pixels for alphaMask()
    mask = Image.new("L", (512, 512), 0)
    from PIL import ImageDraw
    ImageDraw.Draw(mask).ellipse((40, 20, 472, 500), fill=255)
out = img.convert("RGBA"); out.putalpha(mask); out.save(dest)
print("gate photo %s %s" % (out.size, dest))
PY
  PHOTO=$dest
}

log() { echo "[heap-gate $(date +%H:%M:%S)] $*" | tee -a "$OUT/heap_gate.log"; }
booted() { [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }
wait_boot() { adb wait-for-device; for i in $(seq 400); do booted && break; sleep 5; done; booted || { log "emulator did not boot"; exit 2; }
  # after a zygote restart sys.boot_completed stays 1: wait until the package manager really answers
  for i in $(seq 120); do adb shell pm path android 2>/dev/null | grep -q package: && adb shell dumpsys activity activities >/dev/null 2>&1 && { sleep 20; return 0; }; sleep 5; done
  log "the package manager never came up"; exit 2; }

# 1. emulator with enough RAM for the native side (~3.5 GB peak) and the 512 MB Java heap cap
# software CPU by default: this box cannot open /dev/kvm (permission denied). GATE_ACCEL=on tries KVM elsewhere.
ACCEL=${GATE_ACCEL:-off}
mem_kb=$(adb shell cat /proc/meminfo 2>/dev/null | awk '/MemTotal/ {print $2}' || echo 0)
running_off=0; pgrep -af "qemu-system-x86_64.*-avd api29" | grep -q -- "-accel off" && running_off=1
if [ "${mem_kb:-0}" -lt 5500000 ] || { [ $ACCEL = on ] && [ $running_off = 1 ]; }; then
  log "starting the emulator with 6 GB, accel $ACCEL (was ${mem_kb:-0} kB)"
  pkill -f "qemu-system-x86_64.*-avd api29" || true; sleep 5
  nohup $ANDROID_HOME/emulator/emulator -avd api29 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect -accel $ACCEL -cores 4 -memory 6144 \
    -prop dalvik.vm.heapgrowthlimit=512m -prop dalvik.vm.heapsize=512m > /tmp/emu_gate.log 2>&1 &
  sleep 20; wait_boot
fi
adb root >/dev/null; sleep 3; adb wait-for-device; wait_boot
if [ "$(adb shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r')" != 512m ] || [ "$(adb shell getprop dalvik.vm.heapsize | tr -d '\r')" != 512m ]; then
  log "capping the Java heap at 512m (restarting zygote)"
  adb shell setprop dalvik.vm.heapgrowthlimit 512m; adb shell setprop dalvik.vm.heapsize 512m
  adb shell setprop sys.boot_completed 0 2>/dev/null || true
  adb shell stop; sleep 2; adb shell start; sleep 30; wait_boot
fi
log "heapgrowthlimit=$(adb shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r') heapsize=$(adb shell getprop dalvik.vm.heapsize | tr -d '\r') RAM=$(adb shell cat /proc/meminfo | awk '/MemTotal/ {print $2}') kB"

# 2. the release app, fresh, with the models
adb uninstall $PKG >/dev/null 2>&1 || true
for i in 1 2 3; do adb install -r "$APK" >/dev/null 2>"$OUT/install.err" && break; log "install failed (try $i): $(head -c 200 "$OUT/install.err")"; sleep 30; [ $i = 3 ] && exit 2; done
adb shell am start -W -n $PKG/.ui.MainActivity >/dev/null; sleep 15; adb shell am force-stop $PKG
APPDIR=/data/data/$PKG/files; OWNER=$(adb shell stat -c %u:%g /data/data/$PKG | tr -d '\r')
adb shell mkdir -p $APPDIR/models/3d $APPDIR/heap_gate
prepare_gate_photo
log "pushing the 3D models ($(du -shL "$MODELS" | cut -f1)) and a 512 px gate photo"
for f in "$MODELS"/* $EXTRA_MODEL $EXTRA_MODEL.data; do [ -e "$f" ] && adb push "$(readlink -f "$f")" "$APPDIR/models/3d/$(basename "$f")" >/dev/null; done
adb push "$PHOTO" $APPDIR/heap_gate/photo.png >/dev/null
# lowest safe plan: 1+1 steps, 512 px, 20k triangles; skip BiRefNet when the photo already has a cut-out
python3 -c 'import sys; from PIL import Image; i=Image.open(sys.argv[1]); print("rgba" if i.mode=="RGBA" and i.getextrema()[-1][0]<25 and i.getextrema()[-1][1]>230 else "rgb")' "$PHOTO" > "$OUT/photo_mode.txt"
HAS_CUTOUT=$(cat "$OUT/photo_mode.txt")
if [ "$HAS_CUTOUT" = rgba ]; then BG=false; else BG=true; fi
printf '%s' '{"cropSize":512,"decimateTarget":20000,"removeBackground":'"$BG"',"stages":{"Structure":{"steps":1},"Shape":{"steps":1}}}' > "$OUT/workflow3d_overrides.json"
adb push "$OUT/workflow3d_overrides.json" $APPDIR/workflow3d_overrides.json >/dev/null
log "plan overrides: 512 px, 20k triangles, 1+1 steps, removeBackground=$BG"
adb shell "chown -R $OWNER $APPDIR && restorecon -R $APPDIR"

phase() {  # $1 name, $2 request json, $3 1 = expect the process to kill itself
  adb shell "rm -f $APPDIR/heap_gate/result.json; echo '$2' > $APPDIR/heap_gate/request.json; chown $OWNER $APPDIR/heap_gate/request.json; restorecon $APPDIR/heap_gate/request.json"
  adb shell am start -n $PKG/.ui.MainActivity >/dev/null
  local t0=$(date +%s)
  while true; do
    sleep 30
    local st=$(adb shell cat $APPDIR/heap_gate/result.json 2>/dev/null | tr -d '\r' | python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("state",""))
except Exception: print("")')
    # kill_at writes heap_gate/killed.txt (fsynced) then dies; START_STICKY may restart the process, so do not
    # wait for pidof to stay empty — force-stop as soon as the marker appears, leaving the post-shape checkpoint
    if [ "$3" = 1 ] && adb shell "test -f $APPDIR/heap_gate/killed.txt" >/dev/null 2>&1; then
      log "$1: the process killed itself at $(adb shell cat $APPDIR/heap_gate/killed.txt | tr -d '\r') (post-shape checkpoint kept)"
      adb shell am force-stop $PKG; sleep 2
      return 0
    fi
    # once the surface stage starts, the shape checkpoint is complete — keep a copy for the 0.1.3 resume (no second shape run)
    if [ "$1" = full ] && [ ! -d "$OUT/post_shape_job" ]; then
      if adb logcat -d -s BelleEvent:I 2>/dev/null | grep -q "Building the surface"; then
        adb pull $APPDIR/pixal3d_job "$OUT/post_shape_job" >/dev/null
        log "$1: snapshotted the post-shape checkpoint"
      fi
    fi
    [ "$st" = done ] && break
    [ $(( $(date +%s) - t0 )) -gt ${HEAP_GATE_TIMEOUT:-21600} ] && { log "$1: timed out"; return 1; }
  done
  adb shell cat $APPDIR/heap_gate/result.json | tr -d '\r' > "$OUT/heap_gate_$1.json"
  python3 - "$OUT/heap_gate_$1.json" "$1" <<'PY'
import json, sys
r = json.load(open(sys.argv[1])); name = sys.argv[2]
print(f"{name}: ok={r.get('ok')} heap limit {r.get('heap_limit_mb')} MB, debuggable={r.get('debuggable')}, attached={r.get('attached')}, rigged={r.get('rigged')}, error={r.get('error')}")
for s in ((r.get('last_run') or {}).get('stages') or []):
    print(f"  {s['stage'][:40]:40s} {s['ms']/1000:8.1f} s  java heap {s.get('heap_mb',0):4d} MB  rss {s['peak_mb']:5d} MB  native buffers {s.get('native_buf_mb',0):5d} MB")
a = r.get('avatar') or {}
print(f"  avatar import: java heap {a.get('peakJavaHeapMB')} MB, native {a.get('peakNativeMB')} MB, bones {a.get('bones')}, triangles {a.get('triangles')}")
print(f"  history: {r.get('history_last')}")
ok = r.get('ok') is True and r.get('heap_limit_mb', 9999) <= 512 and r.get('debuggable') is False
sys.exit(0 if ok else 1)
PY
}

# Rewrite the unfinished job as 0.1.3 left it on the WP100 (sealed job.json, no app_version, attempts 3, no
# failure record). The next start is then an "app update": afterUpdate resets the tries and the surface can resume.
# Uses the same MMCK sealed format as AtomicFiles (MAGIC | big-endian length | payload | sha256(payload)).
plant_013() {
  local planted="$OUT/job_as_013.json"
  adb shell am force-stop $PKG; sleep 2
  adb pull $APPDIR/pixal3d_job/job.json "$OUT/job.json.sealed" >/dev/null
  python3 - "$OUT/job.json.sealed" "$OUT/job_before_013.json" "$planted" <<'PLANT'
import hashlib, json, struct, sys
magic, src, raw_out, planted_out = b"MMCK", sys.argv[1], sys.argv[2], sys.argv[3]
b = open(src, "rb").read()
assert b[:4] == magic and len(b) >= 44, "job.json is not a sealed AtomicFiles blob"
n = struct.unpack(">Q", b[4:12])[0]
assert 12 + n + 32 == len(b)
payload = b[12:12+n]
assert hashlib.sha256(payload).digest() == b[-32:], "job.json checksum mismatch"
job = json.loads(payload)
open(raw_out, "w").write(json.dumps(job, indent=2))
job.pop("app_version", None); job.pop("updated_from", None); job.pop("failure", None); job.pop("open_try", None)
job["attempts"] = 3
payload = json.dumps(job, separators=(",", ":")).encode()
open(src, "wb").write(magic + struct.pack(">Q", len(payload)) + payload + hashlib.sha256(payload).digest())
open(planted_out, "w").write(json.dumps(job, indent=2))
print("planted 0.1.3 job: attempts=%s, app_version=%s" % (job["attempts"], "present" if "app_version" in job else "absent"))
PLANT
  adb push "$OUT/job.json.sealed" $APPDIR/pixal3d_job/job.json >/dev/null
  adb shell "chown $OWNER $APPDIR/pixal3d_job/job.json && restorecon $APPDIR/pixal3d_job/job.json && rm -f $APPDIR/heap_gate/killed.txt $APPDIR/heap_gate/result.json"
  log "planted a 0.1.3-format job (tries 3 of 3, no app_version); see $planted"
}

STATUS=0
log "phase 1: photo → rigged, attached avatar, uninterrupted"
phase full '{"mode":"full","steps":1}' 0 2>&1 | tee -a "$OUT/heap_gate.log"; [ ${PIPESTATUS[0]} -eq 0 ] || STATUS=1
# phase 2: reuse the post-shape checkpoint from phase 1 (do not run the shape stages again)
if [ -f "$OUT/post_shape_job/job.json" ]; then
  log "phase 2a: restoring the post-shape checkpoint taken during phase 1"
  adb shell am force-stop $PKG; sleep 2
  adb shell rm -rf $APPDIR/pixal3d_job
  adb push "$OUT/post_shape_job/." $APPDIR/pixal3d_job/ >/dev/null
  adb shell "chown -R $OWNER $APPDIR/pixal3d_job && restorecon -R $APPDIR/pixal3d_job"
else
  log "phase 2a: no snapshot; running again and killing at the surface"
  adb shell am force-stop $PKG
  adb shell rm -f $APPDIR/heap_gate/killed.txt $APPDIR/heap_gate/result.json
  phase killed '{"mode":"full","steps":1,"kill_at":"Building the surface"}' 1 2>&1 | tee -a "$OUT/heap_gate.log" || STATUS=1
fi
log "phase 2a2: rewrite that job as a 0.1.3 checkpoint (tries 3 of 3, no app_version)"
plant_013 2>&1 | tee -a "$OUT/heap_gate.log" || STATUS=1
log "phase 2b: resume from that 0.1.3-format checkpoint (the update resets the tries)"
phase resume '{"mode":"resume"}' 0 2>&1 | tee -a "$OUT/heap_gate.log"; [ ${PIPESTATUS[0]} -eq 0 ] || STATUS=1
# the resume must have gone through afterUpdate (tries reset after an app update)
python3 - "$OUT/heap_gate_resume.json" <<'CHECK'
import json, sys
r = json.load(open(sys.argv[1]))
blob = json.dumps(r)
ok = "the app was updated" in blob and "tries reset" in blob
print("resume went through an app-update tries reset" if ok else "FAIL: resume result has no afterUpdate / tries-reset log")
sys.exit(0 if ok else 1)
CHECK
adb pull $APPDIR/pixal3d_history_events.log "$OUT/heap_gate_events.log" >/dev/null 2>&1 || true
[ $STATUS = 0 ] && log "PASSED" || log "FAILED"
echo "$(git rev-parse --short HEAD) $(sha256sum "$APK" | cut -c1-64) $([ $STATUS = 0 ] && echo PASSED || echo FAILED)" > "$OUT/heap_gate.status"
exit $STATUS
