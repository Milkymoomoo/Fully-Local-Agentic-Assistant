"""Export Pixal3D's structure flow (SparseStructureFlowModel, 30 blocks, 16^3 tokens) to ONNX for the phone.
The int8 ConvRot linears stay int8: activations are rotated by the same 256-group Hadamard in the graph and the
stored int8 rows go into com.microsoft MatMulNBits (bits 8, block 256, zero point 128, per-row scale repeated per
block) — the exact stored weights, no re-quantization. Everything else is fp32. Attention is chunked over queries so
the 4096x4096 score matrix is never materialized at once. B=1: the app runs cond / uncond itself (CFG)."""
import sys, os, time, math
sys.path.insert(0, "/workspace/port3d")
from common import *
import torch, torch.nn as nn, torch.nn.functional as F, numpy as np
import comfy.ldm.trellis2.model as TM
from comfy.ldm.flux.math import _apply_rope1
torch.set_grad_enabled(False)
OUT = os.environ.get("OUT", "/workspace/port3d/onnx/pixal3d_ss_flow.onnx")
CHUNK = int(os.environ.get("CHUNK", "512"))

def hadamard(n):
    from comfy_kitchen.tensor.int8_utils import _build_hadamard
    return _build_hadamard(n, device="cpu", dtype=torch.float32)

class NBits(torch.autograd.Function):
    @staticmethod
    def forward(ctx, x, B, scales, K: int, N: int):
        w = (B.reshape(N, K).to(torch.int16) - 128).float() * scales[:, :1]
        return x @ w.T
    @staticmethod
    def symbolic(g, x, B, scales, K, N):
        return g.op("com.microsoft::MatMulNBits", x, B, scales, K_i=K, N_i=N, bits_i=8, block_size_i=256, accuracy_level_i=4)

class QLinear(nn.Module):
    def __init__(self, lin):
        super().__init__()
        w = lin.weight; p = w._params
        q = w._qdata.to(torch.int8); N, K = q.shape
        self.K, self.N, self.gs = K, N, (p.convrot_groupsize if p.convrot else 0)
        self.register_buffer("B", (q.to(torch.int16) + 128).to(torch.uint8).reshape(N, K // 256, 256).contiguous())
        self.register_buffer("scales", p.scale.float().reshape(N, 1).repeat(1, K // 256).contiguous())
        self.register_buffer("bias", lin.bias.float().clone() if lin.bias is not None else torch.zeros(N))
        if self.gs: self.register_buffer("H", hadamard(self.gs))
    def forward(self, x):
        if self.gs:
            s = x.shape; x = (x.reshape(*s[:-1], self.K // self.gs, self.gs) @ self.H).reshape(s)
        return NBits.apply(x, self.B, self.scales, self.K, self.N) + self.bias

def plain_linear(lin):
    l = nn.Linear(lin.in_features, lin.out_features, bias=lin.bias is not None)
    l.weight.data = lin.weight.float().clone()
    if lin.bias is not None: l.bias.data = lin.bias.float().clone()
    return l

def convert(m):
    for name, ch in list(m.named_children()):
        tn = type(ch).__name__
        if isinstance(ch, nn.Linear) or (tn in ("Linear", "_SparseLinear") and hasattr(ch, "in_features")):
            setattr(m, name, QLinear(ch) if hasattr(ch.weight, "_qdata") else plain_linear(ch))
        elif isinstance(ch, nn.LayerNorm) or tn == "LayerNorm":
            l = nn.LayerNorm(ch.normalized_shape, eps=ch.eps, elementwise_affine=ch.elementwise_affine)
            if ch.elementwise_affine: l.weight.data = ch.weight.float().clone(); l.bias.data = ch.bias.float().clone()
            setattr(m, name, l)
        else: convert(ch)
    for n, p in list(m.named_parameters(recurse=False)): p.data = p.data.float()

def chunked_attention(q, k, v, **kw):
    if isinstance(q, TM.AttentionTensorContainer): q, k, v = q.take(), k.take(), v.take()
    q = q.permute(0, 2, 1, 3); k = k.permute(0, 2, 1, 3); v = v.permute(0, 2, 1, 3)
    sc = 1 / math.sqrt(q.shape[-1]); kt = k.transpose(-1, -2)
    outs = []
    for i in range(0, q.shape[2], CHUNK):
        a = torch.softmax((q[:, :, i:i + CHUNK] * sc) @ kt, dim=-1); outs.append(a @ v)
    return torch.cat(outs, 2).permute(0, 2, 1, 3)

class Wrap(nn.Module):
    def __init__(self, sm): super().__init__(); self.sm = sm
    def forward(self, x, t, cond_global, proj):
        return self.sm(x, t, {"global": cond_global, "proj": proj})

if __name__ == "__main__":
    import nodes as N
    t0 = time.time()
    m0 = N.UNETLoader().load_unet("pixal3d_int8_convrot.safetensors", "default")[0]
    sm = m0.model.diffusion_model.structure_model
    sm.rope_phases = sm.rope_phases.float()
    calls = torch.load("/workspace/port3d/ref/calls_ss.pt", weights_only=False)
    if os.environ.get("BASELINE"):
        # the unmodified ComfyUI module (int8 kernels, its own RoPE / attention) on the captured inputs
        import importlib; x, t, cond = calls[0]["args"]
        y = sm(x.float(), t.float(), {"global": cond["global"].float(), "proj": cond["proj"].float()})
        ref = calls[0]["out"][:, :8].float()
        for b in range(2): print("baseline b", b, ((y[b] - ref[b]).norm() / ref[b].norm()).item(), flush=True)
    TM.dense_attention = chunked_attention; TM.apply_rope = lambda q, k, f: (_apply_rope1(q, f), _apply_rope1(k, f))
    def rms(self, x):   # F.rms_norm with eps=None (finfo eps), spelled out for the ONNX exporter
        return x * torch.rsqrt(x.pow(2).mean(-1, keepdim=True) + torch.finfo(torch.float32).eps) * self.gamma
    TM.MultiHeadRMSNorm.forward = rms
    def ss_forward(self, x, t, cond, transformer_options=None):   # model.py's forward with static shapes
        R = self.resolution; C = self.model_channels
        h = x.reshape(1, self.in_channels, R * R * R).permute(0, 2, 1)
        h = self.input_layer(h)
        t_emb = self.t_embedder(t, out_dtype=torch.float32)
        if self.share_mod: t_emb = self.adaLN_modulation(t_emb)
        for block in self.blocks: h = block(h, t_emb, cond, self.rope_phases)
        h = F.layer_norm(h, (C,))
        h = self.out_layer(h)
        return h.permute(0, 2, 1).reshape(1, self.out_channels, R, R, R)
    TM.SparseStructureFlowModel.forward = ss_forward
    convert(sm); sm.float()
    print("modules", {type(m).__name__ for m in sm.modules()}, flush=True)
    del m0; import gc; gc.collect()
    print("loaded+converted", round(time.time() - t0, 1), "s rss", rss_gb(), flush=True)
    w = Wrap(sm).eval()
    for ci, c in enumerate(calls if not os.environ.get("SKIPVAL") else []):
        x, t, cond = c["args"]
        for b in range(2):
            ts = time.time()
            y = w(x[b:b + 1].float(), t[b:b + 1].float(), cond["global"][b:b + 1].float(), cond["proj"][b:b + 1].float())
            ref = c["out"][b:b + 1, :8].float()
            err = (y - ref).abs().max().item(); rel = ((y - ref).norm() / ref.norm()).item()
            print(f"torch call{ci} b{b}: {time.time()-ts:.1f}s max|d| {err:.4f} rel {rel:.5f}", flush=True)
    if os.environ.get("EXPORT", "1") == "1":
        c = calls[0]; x, t, cond = c["args"]
        args = (x[1:2].float(), t[1:2].float(), cond["global"][1:2].float(), cond["proj"][1:2].float())
        ts = time.time()
        torch.onnx.export(w, args, OUT, input_names=["x", "t", "cond_global", "proj"], output_names=["v"],
                          opset_version=17, dynamo=False, do_constant_folding=False,
                          custom_opsets={"com.microsoft": 1})
        print("exported", OUT, round(time.time() - ts, 1), "s", os.path.getsize(OUT) / 1e6, "MB", flush=True)
