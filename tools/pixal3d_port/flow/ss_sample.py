"""Structure stage end to end on the ONNX flow model with the sampler the app will use (euler, 'normal' schedule,
flow shift 5, CFG 7.5 with RescaleCFG 0.7 in x0 space, cfg 1 for sigma <= sigma(66.7%)). Same seed-56 noise as the
reference, so the result is directly comparable to ref/ss_latent.pt and, after the structure VAE, ref/voxel32.pt."""
import numpy as np, onnxruntime as ort, torch, time, sys, os, math, json
def rss():
    for l in open("/proc/self/status"):
        if l.startswith("VmHWM"): return int(l.split()[1]) / 1e6
SHIFT, STEPS, CFG, RESCALE, OVR = 5.0, int(os.environ.get("STEPS", 12)), 7.5, 0.7, 0.667
def sigma_of_t(t): return SHIFT * (t / 1000) / (1 + (SHIFT - 1) * (t / 1000))
def schedule(steps):
    # ModelSamplingDiscreteFlow: sigmas = sigma(arange(1..1000)); normal scheduler: linspace(timestep(sigma_max), timestep(sigma_min))
    tmax, tmin = 1000.0, 1.0   # timestep(sigma) = sigma*1000 of the table ends: sigma_max = sigma(1000) = 1, sigma_min = sigma(1)
    smin = sigma_of_t(tmin); tmin = smin * 1000
    ts = np.linspace(tmax, tmin, steps)
    return [sigma_of_t(t) for t in ts] + [0.0]
sig = schedule(STEPS)
# CFGOverride sits before ModelSamplingSD3 in the official workflow, so its sigma range uses the base shift (3), not 5
sigma_hi = 3.0 * (1 - OVR) / (1 + 2.0 * (1 - OVR))
if __name__ == "__main__":
    o = ort.SessionOptions(); o.intra_op_num_threads = 4; o.enable_cpu_mem_arena = False; o.enable_mem_pattern = False
    s = ort.InferenceSession(sys.argv[1], o)
    calls = torch.load("/workspace/port3d/ref/calls_ss.pt", weights_only=False)
    cond = calls[0]["args"][2]
    g1, p1 = cond["global"][1:2].float().numpy(), cond["proj"][1:2].float().numpy()
    g0, p0 = np.zeros_like(g1), np.zeros_like(p1)
    gen = torch.Generator().manual_seed(56)
    x = torch.randn((1, 32, 16, 16, 16), generator=gen).numpy() * sig[0]
    print("sigmas", [round(v, 4) for v in sig], "sigma_hi", sigma_hi, flush=True)
    def v(x8, t, g, p): return s.run(None, {"x": x8.astype(np.float32), "t": np.array([t], np.float32), "cond_global": g, "proj": p})[0]
    t0 = time.time()
    for i in range(STEPS):
        sg = sig[i]; t = sg * 1000
        cfg = 1.0 if sg <= sigma_hi else CFG
        vc = v(x[:, :8].copy(), t, g1, p1)
        dc = x.copy(); dc[:, :8] = x[:, :8] - sg * vc
        if cfg != 1.0:
            vu = v(x[:, :8].copy(), t, g0, p0)
            du = x.copy(); du[:, :8] = x[:, :8] - sg * vu
            xc = du + cfg * (dc - du)
            ro_pos = dc.std(ddof=1); ro_cfg = max(xc.std(ddof=1), 1e-8)
            den = RESCALE * xc * (ro_pos / ro_cfg) + (1 - RESCALE) * xc
        else:
            den = dc
        d = (x - den) / sg
        x = x + d * (sig[i + 1] - sg)
        print(f"step {i} sigma {sg:.4f} cfg {cfg} {time.time()-t0:.0f}s rss {rss():.2f}", flush=True)
    ref = torch.load("/workspace/port3d/ref/ss_latent.pt", weights_only=False)["samples"].float().numpy()
    print("latent rel err vs reference", np.linalg.norm(x[:, :8] - ref[:, :8]) / np.linalg.norm(ref[:, :8]), "corr", np.corrcoef(x[:, :8].ravel(), ref[:, :8].ravel())[0, 1])
    np.save("/workspace/port3d/ref/ss_latent_port.npy", x.astype(np.float32))
