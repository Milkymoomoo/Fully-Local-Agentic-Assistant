#!/usr/bin/env python3
"""Build-time: make the tiny bundled preview clip(s) for voices that have no official online sample (Kokoro).
Uses the official model archive (k2-fsa/sherpa-onnx tts-models release) extracted at argv[1]; writes Ogg Vorbis."""
import sys, os, wave, subprocess, tempfile, numpy as np, sherpa_onnx as so
d = sys.argv[1]; out = sys.argv[2]
text = "Hi there! I'm one of the Kokoro voices. This is how I sound, right on your phone."
m = so.OfflineTtsModelConfig(kokoro=so.OfflineTtsKokoroModelConfig(model=os.path.join(d, "model.int8.onnx"), voices=os.path.join(d, "voices.bin"),
    tokens=os.path.join(d, "tokens.txt"), data_dir=os.path.join(d, "espeak-ng-data")), num_threads=4)
a = so.OfflineTts(so.OfflineTtsConfig(model=m)).generate(text, sid=0, speed=1.0)
x = np.clip(np.array(a.samples) / max(1e-6, np.abs(a.samples).max()) * 0.9, -1, 1)
with tempfile.NamedTemporaryFile(suffix=".wav") as t:
    w = wave.open(t.name, "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(a.sample_rate); w.writeframes((x * 32767).astype("<i2").tobytes()); w.close()
    subprocess.check_call(["ffmpeg", "-y", "-loglevel", "error", "-i", t.name, "-c:a", "libvorbis", "-q:a", "3", out])
print(out, os.path.getsize(out), "bytes")
