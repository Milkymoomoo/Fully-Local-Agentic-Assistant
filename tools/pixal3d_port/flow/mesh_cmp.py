# e2e unclustered mesh (.f32/.i32) vs the ComfyUI 512 reference mesh: chamfer (% of bbox) and F-scores
import sys, numpy as np, torch
def surf_pts(v, f, n, seed=0):
    g = torch.Generator().manual_seed(seed); v = v.float(); f = f.long()
    a, b, c = v[f[:, 0]], v[f[:, 1]], v[f[:, 2]]; ar = torch.linalg.cross(b - a, c - a).norm(dim=1)
    idx = torch.multinomial(ar, n, True, generator=g); u = torch.rand(n, 1, generator=g); w = torch.rand(n, 1, generator=g)
    m = u + w > 1; u[m] = 1 - u[m]; w[m] = 1 - w[m]
    return a[idx] + u * (b[idx] - a[idx]) + w * (c[idx] - a[idx])
def nn(p, q): return torch.cat([torch.cdist(p[i:i + 4096], q).min(1).values for i in range(0, p.shape[0], 4096)])
v = torch.from_numpy(np.fromfile(sys.argv[1] + ".f32", np.float32).reshape(-1, 3)); f = torch.from_numpy(np.fromfile(sys.argv[1] + ".i32", np.int32).reshape(-1, 3))
ref = torch.load("/workspace/port3d/ref/mesh512.pt", weights_only=False); rv, rf = ref["v"][0].float(), ref["f"][0]
P = surf_pts(v, f, 30000); Q = surf_pts(rv, rf, 30000); d1, d2 = nn(P, Q), nn(Q, P); ext = (rv.max(0).values - rv.min(0).values).max().item()
def F(t): a = (d1 < t * ext).float().mean(); b = (d2 < t * ext).float().mean(); return (2 * a * b / (a + b)).item()
print(f"{sys.argv[1]}: {v.shape[0]} verts; chamfer {((d1.mean() + d2.mean()) / 2).item() / ext * 100:.2f} % of bbox, F@1% {F(0.01):.3f}, F@2% {F(0.02):.3f}")
