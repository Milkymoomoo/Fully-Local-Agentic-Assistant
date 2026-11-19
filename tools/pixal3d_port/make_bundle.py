"""Builds the side-load folder for the app's on-phone Pixal3D (Settings → 3D avatar → "Add the photo → 3D models"):
the load-optimised ONNX files (graph optimisations done once here at ORT 'basic' level, which is hardware-independent,
weights in .onnx.data files) + manifest.json (name, size, sha256 of every file). Copy the folder to the phone (USB,
or `adb push <folder> /sdcard/Download/`) and pick it in the app.
  python3 make_bundle.py <onnx_opt dir> <out dir>"""
import sys, os, json, hashlib, shutil
SRC, OUT = sys.argv[1], sys.argv[2]
MODELS = ["pixal3d_ss_flow.onnx", "trellis2_struct_dec.onnx", "pixal3d_shape512_flow.onnx", "naf_q_r1.onnx",
          "dinov3_l_nb8.onnx", "moge2_l_3600_nb8.onnx"] + [f"trellis2_shape_dec_seg{i}.onnx" for i in range(5)]
os.makedirs(OUT, exist_ok=True); files = []
for m in MODELS:
    for n in (m, m + ".data"):
        p = os.path.join(SRC, n)
        if not os.path.exists(p):
            if n == m: sys.exit(f"missing {p}")
            continue
        dst = os.path.join(OUT, n)
        if not os.path.exists(dst) or os.path.getsize(dst) != os.path.getsize(p): shutil.copyfile(p, dst)
        h = hashlib.sha256()
        with open(dst, "rb") as f:
            for b in iter(lambda: f.read(1 << 24), b""): h.update(b)
        files.append({"name": n, "size": os.path.getsize(dst), "sha256": h.hexdigest()}); print(n, files[-1]["size"], flush=True)
man = {"version": os.environ.get("VERSION", "1"), "ort": "1.28 basic-optimised", "files": files, "total_bytes": sum(f["size"] for f in files)}
json.dump(man, open(os.path.join(OUT, "manifest.json"), "w"), indent=1)
print("total", round(man["total_bytes"] / 1e9, 2), "GB")
