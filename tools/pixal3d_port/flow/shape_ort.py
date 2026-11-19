"""Runs the exported img2shape_512 ONNX in ONNX Runtime on the captured reference calls (and a cropped N to prove the
dynamic token axis), reporting error vs the ComfyUI output, time and peak RSS."""
import sys, os, time
sys.path.insert(0, "/workspace/port3d")
from common import *
import numpy as np, onnxruntime as ort
P = os.environ.get("ONNX") or "/workspace/port3d/onnx/pixal3d_shape512_flow.onnx"
so = ort.SessionOptions(); so.enable_cpu_mem_arena = False; so.enable_mem_pattern = False
so.intra_op_num_threads = int(os.environ.get("T", "8"))
t0 = time.time(); s = ort.InferenceSession(P, so, providers=["CPUExecutionProvider"]); print("load", round(time.time() - t0, 1), "s rss", rss_gb(), flush=True)
calls = torch.load("/workspace/port3d/ref/calls_shape512.pt", weights_only=False)
for ci, c in enumerate(calls):
    for b in range(2):
        lay = c["args"][0].layout[b]
        feed = {"x": c["x_feats"][lay].float().numpy(), "coords": c["coords"][lay, 1:].float().numpy(),
                "t": c["args"][1][b:b + 1].float().numpy(), "cond_global": c["args"][2]["global"][b:b + 1].float().numpy(),
                "proj": c["args"][2]["proj"][lay].float().numpy()}
        ref = c["out"][lay].float().numpy()
        ts = time.time(); y = s.run(None, feed)[0]
        print(f"ort call{ci} b{b}: N {y.shape[0]} {time.time()-ts:.1f}s rel {np.linalg.norm(y-ref)/np.linalg.norm(ref):.5f} rss {rss_gb():.2f}", flush=True)
feed = {k: (v[:1000] if k in ("x", "coords", "proj") else v) for k, v in feed.items()}
print("dynamic N=1000 ->", s.run(None, feed)[0].shape)
