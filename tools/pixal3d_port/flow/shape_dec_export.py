"""TRELLIS.2 shape VAE decoder (FlexiDualGridVaeDecoder, sparse 32^3 -> 512^3) for the phone, as 5 ONNX segments
split at the data-dependent points. Sparse submanifold 3^3 conv = neighbour lookup (done by the app, fed in as
nbr [N,27] int64 with -1 -> N, the appended zero row) + 27 gathers and matmuls (no N x 27 x C buffer).
  seg0: x [N0,32] (denormalised latent), nbr0         -> hc [N0, C1*8], sub [N0,8], h [N0,C0]
  segi: hg [Ni,Ci] (hc rows picked by the subdivision), skip [Ni, C(i-1)/8], nbri -> hc, sub, h   (i = 1..3)
  seg4: hg [N4,64], skip [N4,16], nbr4                -> out [N4,7] (dual vertex xyz logits, 3 edge flags, quad split)
The app between segments: flat = nonzero(sub > 0) over [N,8] row-major; parent = flat / 8, child bit k = (flat % 8)
>> k & 1 for axis k; new coord = 2·coord + bits; hg = hc.view(N*8, C)[flat]; skip = h.view(N*8, C0/8)[flat].
This file also runs that driver in numpy-free torch to check the result against the ComfyUI reference mesh."""
import sys, os, time
sys.path.insert(0, "/workspace/port3d")
from common import *
import torch, torch.nn as nn, torch.nn.functional as F, numpy as np
torch.set_grad_enabled(False)
OUTD = os.environ.get("OUTD", "/workspace/port3d/onnx")
MEAN = torch.tensor([0.781296, 0.018091, -0.495192, -0.558457, 1.060530, 0.093252, 1.518149, -0.933218, -0.732996, 2.604095, -0.118341, -2.143904, 0.495076, -2.179512, -2.130751, -0.996944, 0.261421, -2.217463, 1.260067, -0.150213, 3.790713, 1.481266, -1.046058, -1.523667, -0.059621, 2.220780, 1.621212, 0.877230, 0.567247, -3.175944, -3.186688, 1.578665])
STD = torch.tensor([5.972266, 4.706852, 5.445010, 5.209927, 5.320220, 4.547237, 5.020802, 5.444004, 5.226681, 5.683095, 4.831436, 5.286469, 5.652043, 5.367606, 5.525084, 4.730578, 4.805265, 5.124013, 5.530808, 5.619001, 5.103930, 5.417670, 5.269677, 5.547194, 5.634698, 5.235274, 6.110351, 5.511298, 6.237273, 4.879207, 5.347008, 5.405691])

def subconv(m, x, nbr):
    Co = m.weight.shape[0]; Ci = x.shape[1]
    W = m.weight.float().reshape(Co, 27, Ci)
    xz = torch.cat([x, torch.zeros(1, Ci)], 0)
    out = m.bias.float()[None] if m.bias is not None else 0
    for v in range(27): out = out + xz[nbr[:, v]] @ W[:, v].T
    return out

def ln(m, x): return F.layer_norm(x, tuple(m.normalized_shape), m.weight.float() if m.weight is not None else None, m.bias.float() if m.bias is not None else None, m.eps)
def lin(m, x): return F.linear(x, m.weight.float(), m.bias.float() if m.bias is not None else None)

class Seg(nn.Module):
    def __init__(self, dec, i):
        super().__init__(); self.dec, self.i = dec, i
        self.level = dec.blocks[i]; self.nb = dec.num_blocks[i]
        self.prev = dec.blocks[i - 1][-1] if i > 0 else None
    def forward(self, a, b, nbr=None):
        d = self.dec
        if self.i == 0: nbr = b; h = lin(d.from_latent, a)
        else:
            p = self.prev; h = subconv(p.conv2, F.silu(ln(p.norm2, a)), nbr)
            rep = p.out_channels // (p.channels // 8)
            h = (h.reshape(-1, p.out_channels // rep, rep) + b[:, :, None]).reshape(-1, p.out_channels)
        for j in range(self.nb):
            blk = self.level[j]
            y = ln(blk.norm, subconv(blk.conv, h, nbr))
            h = lin(blk.mlp[2], F.silu(lin(blk.mlp[0], y))) + h
        if self.i < 4:
            up = self.level[-1]
            return subconv(up.conv1, F.silu(ln(up.norm1, h)), nbr), lin(up.to_subdiv, h), h
        return lin(d.output_layer, F.layer_norm(h, (d.model_channels[-1],)))

def neighbours(coords):          # coords [N,3] int64 -> [N,27] int64, missing = N  (same order as flexgemm)
    N = coords.shape[0]; S = 1024
    key = (coords[:, 0] * S + coords[:, 1]) * S + coords[:, 2]
    sk, order = torch.sort(key)
    off = torch.tensor([(a - 1, b - 1, c - 1) for a in range(3) for b in range(3) for c in range(3)])
    out = torch.empty(N, 27, dtype=torch.long)
    for v in range(27):
        c = coords + off[v]; ok = ((c >= 0) & (c < S)).all(1)
        k = (c[:, 0] * S + c[:, 1]) * S + c[:, 2]
        i = torch.searchsorted(sk, k).clamp(max=N - 1); hit = ok & (sk[i] == k)
        out[:, v] = torch.where(hit, order[i], torch.full_like(k, N))
    return out

def subdivide(coords, sub, hc, h):
    flat = (sub > 0).flatten().nonzero()[:, 0]
    par = flat // 8; bits = flat % 8
    nc = coords[par] * 2 + torch.stack([(bits >> k) & 1 for k in range(3)], 1)
    return nc, hc.reshape(hc.shape[0] * 8, -1)[flat], h.reshape(h.shape[0] * 8, -1)[flat]

def to_mesh(coords, out, res):
    """flexible_dual_grid_to_mesh with split weights (the ComfyUI path)."""
    verts = (coords.float() + (2 * torch.sigmoid(out[:, :3]) - 0.5)) / res - 0.5
    inter = out[:, 3:6] > 0; qw = F.softplus(out[:, 6])
    S = 1024; key = (coords[:, 0] * S + coords[:, 1]) * S + coords[:, 2]
    sk, order = torch.sort(key)
    offs = torch.tensor([[[0, 0, 0], [0, 0, 1], [0, 1, 1], [0, 1, 0]], [[0, 0, 0], [1, 0, 0], [1, 0, 1], [0, 0, 1]], [[0, 0, 0], [0, 1, 0], [1, 1, 0], [1, 0, 0]]])
    n, ax = inter.nonzero(as_tuple=True)
    cv = (coords[n][:, None] + offs[ax]).reshape(-1, 3); k = (cv[:, 0] * S + cv[:, 1]) * S + cv[:, 2]
    i = torch.searchsorted(sk, k).clamp(max=len(sk) - 1); idx = torch.where(sk[i] == k, order[i], -1).reshape(-1, 4)
    q = idx[(idx >= 0).all(1)]
    w = qw[q]; s1 = torch.tensor([0, 1, 2, 0, 2, 3]); s2 = torch.tensor([0, 1, 3, 3, 1, 2])
    tri = torch.where((w[:, 0] * w[:, 2] > w[:, 1] * w[:, 3])[:, None], q[:, s1], q[:, s2]).reshape(-1, 3)
    return verts, tri

if __name__ == "__main__":
    import nodes as N
    v = N.VAELoader().load_vae("trellis_2_shape_vae_bf16.safetensors")[0]
    dec = v.first_stage_model.shape_dec.float().eval()
    print("vae dtype", v.vae_dtype, "rss", rss_gb(), flush=True)
    s = torch.load("/workspace/port3d/ref/shape512_latent.pt", weights_only=False)
    vox = torch.load("/workspace/port3d/ref/voxel32.pt", weights_only=False)
    coords = torch.argwhere(vox.unsqueeze(1).bool())[:, [2, 3, 4]].long()
    x = s["samples"][0, :, :, 0].T.float() * STD + MEAN
    print("N0", coords.shape[0], "latent", tuple(x.shape), flush=True)
    segs = [Seg(dec, i).eval() for i in range(5)]
    t0 = time.time(); a, b = x, None
    for i in range(5):
        nbr = neighbours(coords); ts = time.time()
        r = segs[i](a, nbr) if i == 0 else segs[i](a, b, nbr)
        if os.environ.get("EXPORT", "1") == "1":
            n = min(a.shape[0], 4096)   # trace on a slice (the graph is per-row except the neighbour gather)
            na = nbr[:n].clamp(max=n)
            args = (a[:n], na) if i == 0 else (a[:n], b[:n], na)
            names = ["x", "nbr"] if i == 0 else ["hg", "skip", "nbr"]
            outs = ["hc", "sub", "h"] if i < 4 else ["out"]
            path = f"{OUTD}/trellis2_shape_dec_seg{i}.onnx"; te = time.time()
            torch.onnx.export(segs[i], args, path, input_names=names, output_names=outs,
                              dynamic_axes={k: {0: "N"} for k in names + outs}, opset_version=17, dynamo=False)
            print(f"  exported {path} {os.path.getsize(path)/1e6:.0f} MB {time.time()-te:.0f}s", flush=True)
        if i < 4:
            hc, sub, h = r
            coords, a, b = subdivide(coords, sub, hc, h)
        print(f"seg{i}: {time.time()-ts:.1f}s -> N{i+1} {coords.shape[0]} rss {rss_gb():.2f}", flush=True)
    verts, tri = to_mesh(coords, r, 512)
    print("port mesh verts", tuple(verts.shape), "faces", tuple(tri.shape), round(time.time() - t0, 1), "s", flush=True)
    ref = torch.load("/workspace/port3d/ref/mesh512.pt", weights_only=False)
    rv, rf = ref["v"][0].float(), ref["f"][0]
    print("ref  mesh verts", tuple(rv.shape), "faces", tuple(rf.shape), flush=True)
    if rv.shape == verts.shape: print("max |dv|", (rv - verts).abs().max().item(), "faces equal", torch.equal(rf.long(), tri.long()))
    torch.save({"coords": coords, "out": r}, "/workspace/port3d/ref/shape_dec_port.pt")
