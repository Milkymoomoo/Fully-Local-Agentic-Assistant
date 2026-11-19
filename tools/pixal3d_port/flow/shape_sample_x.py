"""First shape pass (img2shape_512) end to end on the ONNX model with the sampler the app uses: euler, 'normal'
schedule, flow shift 3 (the model default; the official workflow adds no ModelSamplingSD3 here), CFG 7.5 with
RescaleCFG 0.5 in x0 space, cfg 1 once sigma <= shift3(1 - 0.769). Noise: torch.randn([1,32,N,1]) seed 42 like
ComfyUI's prepare_noise, so the result is comparable to ref/shape512_latent.pt. Inputs (coords, cond) come from the
captured reference call."""
import numpy as np, onnxruntime as ort, torch, time, sys, os
def rss():
    for l in open("/proc/self/status"):
        if l.startswith("VmHWM"): return int(l.split()[1]) / 1e6
SHIFT, STEPS, CFG, RESCALE, OVR = 3.0, int(os.environ.get("STEPS", 20)), 7.5, 0.5, 0.769
def snr_shift(a, t): return a * t / (1 + (a - 1) * t)
def schedule(steps):
    tmin = snr_shift(SHIFT, 1 / 1000) * 1000
    return [snr_shift(SHIFT, t / 1000) for t in np.linspace(1000.0, tmin, steps)] + [0.0]
sig = schedule(STEPS); sigma_hi = snr_shift(SHIFT, 1 - OVR)
def rescaled(x, dc, du):
    xc = du + CFG * (dc - du)
    return RESCALE * xc * (dc.std(ddof=1) / max(xc.std(ddof=1), 1e-8)) + (1 - RESCALE) * xc
if __name__ == "__main__":
    sys.path.insert(0, "/workspace/port3d"); from common import rss_gb
    o = ort.SessionOptions(); o.intra_op_num_threads = 4; o.enable_cpu_mem_arena = False; o.enable_mem_pattern = False
    s = ort.InferenceSession(os.environ.get("ONNX", "/workspace/port3d/onnx/pixal3d_shape512_flow.onnx"), o)
    c = torch.load("/workspace/port3d/ref/calls_shape512.pt", weights_only=False)[0]
    lay = c["args"][0].layout[1]
    coords = c["coords"][lay, 1:].float().numpy(); g1 = c["args"][2]["global"][1:2].float().numpy(); p1 = c["args"][2]["proj"][lay].float().numpy()
    g0, p0 = np.zeros_like(g1), np.zeros_like(p1); N = coords.shape[0]
    x = torch.randn((1, 32, N, 1), generator=torch.manual_seed(42)).numpy()[0, :, :, 0].T.copy() * sig[0]   # [N,32]
    print("N", N, "sigmas", [round(v, 4) for v in sig], "sigma_hi", round(sigma_hi, 4), flush=True)
    def v(x, t, g, p): return s.run(None, {"x": x.astype(np.float32), "coords": coords, "t": np.array([t], np.float32), "cond_global": g, "proj": p})[0]
    t0 = time.time()
    for i in range(STEPS):
        sg = sig[i]; cfg = 1.0 if sg <= sigma_hi else CFG
        dc = x - sg * v(x, sg * 1000, g1, p1)
        den = rescaled(x, dc, x - sg * v(x, sg * 1000, g0, p0)) if cfg != 1.0 else dc
        x = x + (x - den) / sg * (sig[i + 1] - sg)
        print(f"step {i} sigma {sg:.4f} cfg {cfg} {time.time()-t0:.0f}s rss {rss():.2f}", flush=True)
    ref = torch.load("/workspace/port3d/ref/shape512_latent.pt", weights_only=False)["samples"].float().numpy()[0, :, :, 0].T
    print("latent rel err vs reference", np.linalg.norm(x - ref) / np.linalg.norm(ref), "corr", np.corrcoef(x.ravel(), ref.ravel())[0, 1], flush=True)
    np.save(os.environ.get("OUT", "/tmp/sh_lat.npy"), x.astype(np.float32))
