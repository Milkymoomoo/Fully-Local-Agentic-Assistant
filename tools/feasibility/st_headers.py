"""Reads ONLY the safetensors headers (HTTP Range) of every model in the TRELLIS 2 / Pixal3D workflow:
exact tensor shapes, dtypes and parameter counts, without downloading the multi-GB weights."""
import json, struct, urllib.request, sys
FILES = {
 "trellis2_flow": ("Comfy-Org/TRELLIS.2", "diffusion_models/trellis_2_int8_convrot.safetensors"),
 "pixal3d_flow": ("Comfy-Org/Pixal3D", "diffusion_models/pixal3d_int8_convrot.safetensors"),
 "shape_vae": ("Comfy-Org/Pixal3D", "vae/trellis_2_shape_vae_bf16.safetensors"),
 "texture_vae": ("Comfy-Org/Pixal3D", "vae/trellis_2_texture_vae_bf16.safetensors"),
 "dinov3_L": ("Comfy-Org/Pixal3D", "clip_vision/dino_v3_L_naf_fp32.safetensors"),
 "moge2_vitl": ("Comfy-Org/MoGe", "geometry_estimation/moge_2_vitl_normal_fp16.safetensors"),
 "birefnet": ("Comfy-Org/BiRefNet", "background_removal/birefnet.safetensors"),
}
def get(url, a, b):
    r = urllib.request.Request(url, headers={"Range": f"bytes={a}-{b}", "User-Agent": "feasibility"})
    return urllib.request.urlopen(r, timeout=60).read()
out = {}
for k, (repo, path) in FILES.items():
    url = f"https://huggingface.co/{repo}/resolve/main/{path}"
    n = struct.unpack("<Q", get(url, 0, 7))[0]
    h = json.loads(get(url, 8, 8 + n - 1))
    meta = h.pop("__metadata__", None)
    params = 0; by_dtype = {}
    for t, v in h.items():
        c = 1
        for s in v["shape"]: c *= s
        params += c; by_dtype[v["dtype"]] = by_dtype.get(v["dtype"], 0) + c
    out[k] = {"repo": repo, "path": path, "tensors": len(h), "params": params, "dtypes": by_dtype, "meta": meta,
              "shapes": {t: v["shape"] for t, v in h.items()}}
    print(f"{k:14s} tensors={len(h):5d} params={params/1e6:9.1f}M dtypes={by_dtype}")
json.dump(out, open("st_headers.json", "w"), indent=1)
