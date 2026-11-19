"""Weight-stationary sparse conv (gather rows per offset -> one big GEMM per offset -> scatter-add), chunked
so the gather buffer stays <= 32 MB. This is the layout a phone kernel should use with a real GEMM
(XNNPACK/KleidiAI on CPU, or a Vulkan GEMM shader). Checks against the C kernel's output."""
import numpy as np, time, sys, json
from run import shell, prep
G, cin, cout = map(int, sys.argv[1:4]); co = shell(G); n = len(co)
x, w, b = prep("/tmp/spc_om", co, cin, cout)
t0 = time.time()
key = (co[:, 0].astype(np.int64) << 20) | (co[:, 1].astype(np.int64) << 10) | co[:, 2]
order = np.argsort(key); sk = key[order]
pairs = []
for k in range(27):
    d = np.array([k // 9 - 1, (k // 3) % 3 - 1, k % 3 - 1]); nb = co + d
    nk = (nb[:, 0].astype(np.int64) << 20) | (nb[:, 1].astype(np.int64) << 10) | nb[:, 2]
    p = np.searchsorted(sk, nk); p = np.clip(p, 0, n - 1); hit = sk[p] == nk
    pairs.append((np.nonzero(hit)[0], order[p[hit]]))
tmap = time.time() - t0
t0 = time.time(); out = np.tile(b, (n, 1)); CH = max(1, (32 << 20) // (4 * cin))
tg = 0; tm = 0
for k, (o, i) in enumerate(pairs):
    for s in range(0, len(o), CH):
        a = time.time(); g = x[i[s:s + CH]]; tg += time.time() - a
        a = time.time(); y = g @ w[k]; tm += time.time() - a
        a = time.time(); out[o[s:s + CH]] += y; tg += time.time() - a   # each output appears once per offset → no conflicts
tt = time.time() - t0; P = sum(len(o) for o, _ in pairs); gf = 2 * P * cin * cout / 1e9
print(json.dumps(dict(n=n, cin=cin, cout=cout, pairs=P, map_s=round(tmap, 3), total_s=round(tt, 3), gemm_s=round(tm, 3), gather_scatter_s=round(tg, 3), gflop=round(gf, 1), gflops=round(gf / tt, 1))))
np.save("/tmp/spc_om/out_om.npy", out)
