"""The 5 shape-decoder ONNX segments in ONNX Runtime, driven exactly like ShapeDecoder.kt, against the reference mesh."""
import sys, os, time
sys.path.insert(0, "/workspace/port3d"); sys.path.insert(0, "/workspace/port3d/flow")
from common import *
import numpy as np, onnxruntime as ort
from shape_dec_export import neighbours, subdivide, to_mesh, MEAN, STD
so = ort.SessionOptions(); so.enable_cpu_mem_arena = False; so.enable_mem_pattern = False; so.intra_op_num_threads = int(os.environ.get("T", "8"))
s = torch.load("/workspace/port3d/ref/shape512_latent.pt", weights_only=False)
vox = torch.load("/workspace/port3d/ref/voxel32.pt", weights_only=False)
coords = torch.argwhere(vox.unsqueeze(1).bool())[:, [2, 3, 4]].long()
lat = s["samples"][0, :, :, 0].T.float() if not os.environ.get("LAT") else torch.from_numpy(np.load(os.environ["LAT"]))
a = (lat * STD + MEAN).numpy(); b = None; t0 = time.time()
for i in range(5):
    nbr = neighbours(coords).numpy(); ts = time.time()
    sess = ort.InferenceSession(f"/workspace/port3d/onnx/trellis2_shape_dec_seg{i}.onnx", so)
    feed = {"x": a, "nbr": nbr} if i == 0 else {"hg": a, "skip": b, "nbr": nbr}
    r = sess.run(None, feed); del sess
    print(f"seg{i} N {coords.shape[0]} {time.time()-ts:.1f}s rss {rss_gb():.2f}", flush=True)
    if i < 4:
        coords, a, b = subdivide(coords, torch.from_numpy(r[1]), torch.from_numpy(r[0]), torch.from_numpy(r[2])); a = a.numpy(); b = b.numpy()
verts, tri = to_mesh(coords, torch.from_numpy(r[0]), 512)
ref = torch.load("/workspace/port3d/ref/mesh512.pt", weights_only=False); rv, rf = ref["v"][0].float(), ref["f"][0]
print("ort mesh", tuple(verts.shape), tuple(tri.shape), "ref", tuple(rv.shape), tuple(rf.shape), round(time.time() - t0, 1), "s")
if rv.shape == verts.shape: print("max |dv|", (rv - verts).abs().max().item())

def surf_pts(v, f, n=200000, seed=0):
    g = torch.Generator().manual_seed(seed); v = v.float(); f = f.long()
    a, b, c = v[f[:, 0]], v[f[:, 1]], v[f[:, 2]]; ar = torch.linalg.cross(b - a, c - a).norm(dim=1)
    idx = torch.multinomial(ar, n, True, generator=g); u = torch.rand(n, 1, generator=g); w = torch.rand(n, 1, generator=g)
    m = u + w > 1; u[m] = 1 - u[m]; w[m] = 1 - w[m]
    return a[idx] + u * (b[idx] - a[idx]) + w * (c[idx] - a[idx])
def nn_dist(p, q):
    out = []
    for i in range(0, p.shape[0], 4096): out.append(torch.cdist(p[i:i+4096], q).min(1).values)
    return torch.cat(out)
if os.environ.get("CHAMFER"):
    P = surf_pts(verts, tri, 30000); Q = surf_pts(rv, rf, 30000)
    d1, d2 = nn_dist(P, Q), nn_dist(Q, P); ext = (rv.max(0).values - rv.min(0).values).max().item()
    print(f"chamfer (mean of both directions) {((d1.mean()+d2.mean())/2).item()/ext:.5f} of bbox; F-score@1% {(2*((d1<0.01*ext).float().mean()*(d2<0.01*ext).float().mean())/((d1<0.01*ext).float().mean()+(d2<0.01*ext).float().mean())).item():.3f}; bbox ext {ext:.3f}")
    torch.save({"v": verts, "f": tri}, os.environ.get("OUT", "/workspace/port3d/ref/mesh512_port.pt"))
