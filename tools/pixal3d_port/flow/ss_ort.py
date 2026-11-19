import numpy as np, onnxruntime as ort, torch, time, sys, os
def rss():
    for l in open("/proc/self/status"):
        if l.startswith("VmHWM"): return int(l.split()[1]) / 1e6
o = ort.SessionOptions(); o.intra_op_num_threads = int(os.environ.get("T", "4"))
t0 = time.time(); s = ort.InferenceSession(sys.argv[1], o); print("load", round(time.time() - t0, 1), "s rss", rss(), flush=True)
calls = torch.load("/workspace/port3d/ref/calls_ss.pt", weights_only=False)
for ci, c in enumerate(calls):
    x, t, cond = c["args"]
    for b in range(2):
        ts = time.time()
        y = s.run(None, {"x": x[b:b+1].float().numpy(), "t": t[b:b+1].float().numpy(), "cond_global": cond["global"][b:b+1].float().numpy(), "proj": cond["proj"][b:b+1].float().numpy()})[0]
        ref = c["out"][b:b+1, :8].float().numpy()
        print(f"ort call{ci} b{b}: {time.time()-ts:.1f}s rel {np.linalg.norm(y-ref)/np.linalg.norm(ref):.5f} max {np.abs(y-ref).max():.4f} rss {rss():.2f}", flush=True)
