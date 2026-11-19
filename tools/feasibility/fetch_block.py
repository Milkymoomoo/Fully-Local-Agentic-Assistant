"""Fetch ONE real DiT block (structure_model.blocks.0) from the official Comfy-Org TRELLIS.2 int8 checkpoint
with HTTP Range requests (~45 MB), decode comfy's int8 'convrot' format, save as .npz."""
import json, struct, urllib.request, numpy as np, sys
REPO, PATH = "Comfy-Org/TRELLIS.2", "diffusion_models/trellis_2_int8_convrot.safetensors"
PREFIX = sys.argv[1] if len(sys.argv) > 1 else "model.structure_model.blocks.0."
url = f"https://huggingface.co/{REPO}/resolve/main/{PATH}"
def get(a, b):
    r = urllib.request.Request(url, headers={"Range": f"bytes={a}-{b}", "User-Agent": "feasibility"})
    return urllib.request.urlopen(r, timeout=120).read()
n = struct.unpack("<Q", get(0, 7))[0]; h = json.loads(get(8, 8 + n - 1)); h.pop("__metadata__", None)
mine = {k: v for k, v in h.items() if k.startswith(PREFIX)}
print(f"{len(mine)} tensors, {sum(v['data_offsets'][1]-v['data_offsets'][0] for v in mine.values())/1e6:.1f} MB (one Range request each)")
lo = 0; parts = {k: get(8 + n + v["data_offsets"][0], 8 + n + v["data_offsets"][1] - 1) for k, v in mine.items()}
DT = {"I8": np.int8, "U8": np.uint8, "F32": np.float32, "BF16": np.uint16, "F16": np.float16}
out = {}
for k, v in mine.items():
    arr = np.frombuffer(parts[k], DT[v["dtype"]]).reshape(v["shape"])
    if v["dtype"] == "BF16": arr = (arr.astype(np.uint32) << 16).view(np.float32)
    out[k[len(PREFIX):]] = arr
    if k.endswith("comfy_quant"): print(k[len(PREFIX):], bytes(arr).decode(errors="replace"))
np.savez("/tmp/block0.npz", **out)
for k, a in sorted(out.items()): print(f"  {k:40s} {str(a.dtype):8s} {a.shape}")
