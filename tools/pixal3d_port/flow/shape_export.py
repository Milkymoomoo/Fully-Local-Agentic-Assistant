"""Export Pixal3D's first shape pass (img2shape_512: SLatFlowModel, 30 blocks, 1536 ch, sparse tokens on the 32^3
structure voxels) to ONNX for the phone. Same int8 handling as ss_export.py (ConvRot rows into MatMulNBits, Hadamard
rotation of activations in graph). The sparse tensor is just a token list for this model, so the graph takes plain
tensors with a dynamic token count N: x [N,32], coords [N,3] (voxel xyz, float), t [1], cond_global [1,L,1024],
proj [N,2048] -> v [N,32]. B=1: the app runs cond / uncond itself (CFG). Self-attention runs one head at a time so
only an N x N score matrix lives at once (N ~ 1.6k-6k)."""
import sys, os, time, math
sys.path.insert(0, "/workspace/port3d"); sys.path.insert(0, "/workspace/port3d/flow")
from common import *
import torch, torch.nn as nn, torch.nn.functional as F
import comfy.ldm.trellis2.model as TM
from ss_export import QLinear, convert
torch.set_grad_enabled(False)
OUT = os.environ.get("OUT", "/workspace/port3d/onnx/pixal3d_shape512_flow.onnx")

def rms(x, gamma):
    return x * torch.rsqrt(x.pow(2).mean(-1, keepdim=True) + torch.finfo(torch.float32).eps) * gamma

def rope(x, f):        # x [N,H,D], f [N,1,D/2,2,2]  (comfy.ldm.flux.math._apply_rope1)
    x_ = x.reshape(x.shape[0], x.shape[1], -1, 1, 2)
    o = f[..., 0] * x_[..., 0] + f[..., 1] * x_[..., 1]
    return o.reshape(x.shape)

def attn(q, k, v):     # [N,H,D],[M,H,D] -> [N,H,D], one head at a time
    sc = 1 / math.sqrt(q.shape[-1]); outs = []
    for h in range(q.shape[1]):
        a = torch.softmax((q[:, h] * sc) @ k[:, h].T, dim=-1); outs.append(a @ v[:, h])
    return torch.stack(outs, 1)

class Shape(nn.Module):
    def __init__(self, m):
        super().__init__(); self.m = m
        r = m.blocks[0].self_attn.rope
        self.head_dim, self.H = r.head_dim, m.blocks[0].self_attn.num_heads
        self.register_buffer("freqs", r.freqs.float().clone())
    def freqs_cis(self, coords):
        ph = torch.cat([coords[:, i:i + 1] * self.freqs[None] for i in range(3)], -1)
        ph = F.pad(ph, (0, self.head_dim // 2 - ph.shape[-1]))
        c, s = torch.cos(ph), torch.sin(ph)
        return torch.stack([torch.stack([c, s], -1), torch.stack([-s, c], -1)], -1).unsqueeze(1)
    def forward(self, x, coords, t, cond_global, proj):
        m = self.m; H = self.H; N = x.shape[0]; C = m.model_channels
        f = self.freqs_cis(coords)
        h = m.input_layer(x)
        temb = m.t_embedder(t, out_dtype=torch.float32)
        if m.share_mod: temb = m.adaLN_modulation(temb)
        ctx = cond_global[0]
        for b in m.blocks:
            mod = b.modulation[None] + temb
            sh1, sc1, g1, sh2, sc2, g2 = mod.chunk(6, dim=1)
            y = b.norm1(h) * (1 + sc1) + sh1
            sa = b.self_attn
            qkv = sa.to_qkv(y).reshape(N, 3, H, -1); q, k, v = qkv[:, 0], qkv[:, 1], qkv[:, 2]
            if sa.qk_rms_norm: q = rms(q, sa.q_rms_norm.gamma); k = rms(k, sa.k_rms_norm.gamma)
            q, k = rope(q, f), rope(k, f)
            h = h + sa.to_out(attn(q, k, v).reshape(N, C)) * g1
            y = b.norm2(h)
            ca = b.cross_attn; inner = ca.cross_attn_block if hasattr(ca, "cross_attn_block") else ca
            q = inner.to_q(y).reshape(N, H, -1)
            kv = inner.to_kv(ctx).reshape(ctx.shape[0], 2, H, -1); k, v = kv[:, 0], kv[:, 1]
            if inner.qk_rms_norm: q = rms(q, inner.q_rms_norm.gamma); k = rms(k, inner.k_rms_norm.gamma)
            o = inner.to_out(attn(q, k, v).reshape(N, C))
            if hasattr(ca, "proj_linear"): o = o + ca.proj_linear(proj)
            h = h + o
            y = b.norm3(h) * (1 + sc2) + sh2
            h = h + b.mlp.mlp[2](F.gelu(b.mlp.mlp[0](y), approximate="tanh")) * g2
        h = F.layer_norm(h, (C,))
        return m.out_layer(h)

def unwrap_sparse_linears(m):   # SparseLinear is an nn.Linear subclass taking SparseTensor; plain tensors here
    for name, ch in list(m.named_children()):
        if type(ch).__name__ == "SparseLinear":
            l = nn.Linear(ch.in_features, ch.out_features, bias=ch.bias is not None)
            l.weight = ch.weight; l.bias = ch.bias; setattr(m, name, l)
        else: unwrap_sparse_linears(ch)

if __name__ == "__main__":
    import nodes as N
    t0 = time.time()
    m0 = N.UNETLoader().load_unet("pixal3d_int8_convrot.safetensors", "default")[0]
    dm = m0.model.diffusion_model
    sm = dm.img2shape_512
    calls = torch.load("/workspace/port3d/ref/calls_shape512.pt", weights_only=False)
    print("blocks", len(sm.blocks), "ch", sm.model_channels, "heads", sm.num_heads, "proj_in", sm.proj_in_channels,
          "attn", sm.image_attn_mode, "share_mod", sm.share_mod, flush=True)
    dm.img2shape = None; dm.structure_model = None
    convert(sm); sm.float()
    left = {type(x).__name__ for x in sm.modules() if 'Linear' in type(x).__name__}; print('linears', left, flush=True)
    del m0; import gc; gc.collect()
    print("loaded+converted", round(time.time() - t0, 1), "s rss", rss_gb(), flush=True)
    w = Shape(sm).eval()
    def inputs(c, b):
        lay = c["args"][0].layout[b]; x = c["x_feats"][lay].float(); co = c["coords"][lay, 1:].float()
        t = c["args"][1][b:b + 1].float(); g = c["args"][2]["global"][b:b + 1].float(); p = c["args"][2]["proj"][lay].float()
        return (x, co, t, g, p), c["out"][lay].float()
    for ci, c in enumerate(calls if not os.environ.get("SKIPVAL") else []):
        for b in range(2):
            ts = time.time(); a, ref = inputs(c, b); y = w(*a)
            print(f"torch call{ci} b{b}: N {a[0].shape[0]} {time.time()-ts:.1f}s max|d| {(y-ref).abs().max().item():.4f} "
                  f"rel {((y-ref).norm()/ref.norm()).item():.5f}", flush=True)
    if os.environ.get("EXPORT", "1") == "1":
        a, _ = inputs(calls[0], 0); ts = time.time()
        torch.onnx.export(w, a, OUT, input_names=["x", "coords", "t", "cond_global", "proj"], output_names=["v"],
                          dynamic_axes={"x": {0: "N"}, "coords": {0: "N"}, "proj": {0: "N"}, "v": {0: "N"}, "cond_global": {1: "L"}},
                          opset_version=17, dynamo=False, do_constant_folding=False, custom_opsets={"com.microsoft": 1})
        print("exported", OUT, round(time.time() - ts, 1), "s", os.path.getsize(OUT) / 1e6, "MB", flush=True)
