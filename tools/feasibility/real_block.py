"""Runs ONE REAL TRELLIS 2 structure-flow block (weights fetched from the official int8 'convrot' checkpoint)
through onnxruntime in the phone-friendly form: activations rotated with a 256-group Hadamard (exact inverse of
comfy's convrot), dynamic int8 activation quant, MatMulInteger with the checkpoint's own int8 weights (no
re-quantisation), per-channel scale. Compared against a float64 numpy reference of the same block."""
import numpy as np, onnx, onnxruntime as ort, time, json, os, resource, sys
from onnx import helper as H, TensorProto as T, numpy_helper as nh
w = dict(np.load("/tmp/block0.npz")); D, NH, HD, MLP, CTX, NCTX = 1536, 12, 128, 8192, 1024, 1029
N = int(sys.argv[1]) if len(sys.argv) > 1 else 4096
def had(n):  # comfy-kitchen's "regular" Hadamard: H4 (x) H4 (x) H4 (x) H4 / 16 (symmetric, orthogonal)
    h4 = np.array([[1, 1, 1, -1], [1, 1, -1, 1], [1, -1, 1, 1], [-1, 1, 1, 1]], float); h = h4
    while h.shape[0] < n: h = np.kron(h, h4)
    return h / np.sqrt(n)
H256 = had(256)
# --- sanity check of the convrot convention: un-rotated weights should show the usual outlier input channels
def kurt(m): c = np.linalg.norm(m, axis=0); return float(((c - c.mean())**4).mean() / c.var()**2)
wq = w["self_attn.to_qkv.weight"].astype(np.float64) * w["self_attn.to_qkv.weight_scale"]
unrot = (wq.reshape(wq.shape[0], -1, 256) @ H256).reshape(wq.shape)
print("column-norm kurtosis  stored(rotated)=%.2f  un-rotated=%.2f" % (kurt(wq), kurt(unrot)))
def W(name):  # effective float weight [in, out]
    q = w[name + ".weight"].astype(np.float64) * w[name + ".weight_scale"]
    return (q.reshape(q.shape[0], -1, 256) @ H256).reshape(q.shape).T
r = np.random.default_rng(0); x = r.standard_normal((N, D)).astype(np.float32); ctx = r.standard_normal((NCTX, CTX)).astype(np.float32)
mod = w["modulation"].reshape(6, D)  # shift/scale/gate (msa), shift/scale/gate (mlp); t-embedding adds to this at runtime
def ln(v, g=None, b=None): m = v.mean(-1, keepdims=True); s = v.var(-1, keepdims=True); o = (v - m) / np.sqrt(s + 1e-6); return o * g + b if g is not None else o
def rms(v, g): return v / np.sqrt((v**2).mean(-1, keepdims=True) + 1e-6) * g
def attn(q, k, v):
    s = np.einsum("hqd,hkd->hqk", q, k) / np.sqrt(HD); s -= s.max(-1, keepdims=True); p = np.exp(s); p /= p.sum(-1, keepdims=True)
    return np.einsum("hqk,hkd->hqd", p, v)
def heads(v): return v.reshape(len(v), NH, HD).transpose(1, 0, 2)
def ref(x):
    x = x.astype(np.float64); h = ln(x) * (1 + mod[1]) + mod[0]
    qkv = h @ W("self_attn.to_qkv") + w["self_attn.to_qkv.bias"]; q, k, v = np.split(qkv, 3, -1)
    q = rms(heads(q), w["self_attn.q_rms_norm.gamma"][:, None]); k = rms(heads(k), w["self_attn.k_rms_norm.gamma"][:, None])
    a = attn(q, k, heads(v)).transpose(1, 0, 2).reshape(len(x), D) @ W("self_attn.to_out") + w["self_attn.to_out.bias"]
    x = x + mod[2] * a
    h = ln(x, w["norm2.weight"], w["norm2.bias"]); q = h @ W("cross_attn.to_q") + w["cross_attn.to_q.bias"]
    kv = ctx.astype(np.float64) @ W("cross_attn.to_kv") + w["cross_attn.to_kv.bias"]; k, v = np.split(kv, 2, -1)
    q = rms(heads(q), w["cross_attn.q_rms_norm.gamma"][:, None]); k = rms(heads(k), w["cross_attn.k_rms_norm.gamma"][:, None])
    x = x + attn(q, k, heads(v)).transpose(1, 0, 2).reshape(len(x), D) @ W("cross_attn.to_out") + w["cross_attn.to_out.bias"]
    h = ln(x) * (1 + mod[4]) + mod[3]; m = h @ W("mlp.mlp.0") + w["mlp.mlp.0.bias"]
    m = 0.5 * m * (1 + np.tanh(0.7978845608 * (m + 0.044715 * m**3)))
    return x + mod[5] * (m @ W("mlp.mlp.2") + w["mlp.mlp.2.bias"])
# ---------------- ONNX graph ----------------
nodes, inits = [], []; cnt = [0]
def c(a, dt=np.float32):
    cnt[0] += 1; n = f"c{cnt[0]}"; inits.append(nh.from_array(np.asarray(a, dt), n)); return n
def op(t, ins, **kw):
    cnt[0] += 1; o = f"t{cnt[0]}"; nodes.append(H.make_node(t, ins, [o], **kw)); return o
def lin(x, name, n_rows):
    din = w[name + ".weight"].shape[1]
    xr = op("Reshape", [x, c([n_rows, din // 256, 256], np.int64)]); xr = op("MatMul", [xr, c(H256)])  # x·H (H symmetric)
    xr = op("Reshape", [xr, c([n_rows, din], np.int64)])
    qx, sx, zx = [f"q{cnt[0]}", f"s{cnt[0]}", f"z{cnt[0]}"]; nodes.append(H.make_node("DynamicQuantizeLinear", [xr], [qx, sx, zx]))
    wi = c(w[name + ".weight"].T.copy(), np.int8)
    mm = op("MatMulInteger", [qx, wi, zx, c(np.int8(0), np.int8)]); mm = op("Cast", [mm], to=T.FLOAT)
    mm = op("Mul", [mm, op("Mul", [sx, c(w[name + ".weight_scale"][:, 0])])])
    return op("Add", [mm, c(w[name + ".bias"])])
def lnorm(x, g=None, b=None): return op("LayerNormalization", [x, c(g if g is not None else np.ones(D)), c(b if b is not None else np.zeros(D))], axis=-1, epsilon=1e-6)
def rmsn(x, g):  # x [NH, n, HD]
    return op("Mul", [op("Div", [x, op("Sqrt", [op("Add", [op("ReduceMean", [op("Mul", [x, x]), c([-1], np.int64)], keepdims=1), c(1e-6)])])]), c(g[:, None, :])])
def hd(x, n): return op("Transpose", [op("Reshape", [x, c([n, NH, HD], np.int64)])], perm=[1, 0, 2])
def sdpa(q, k, v, n):
    s = op("Mul", [op("MatMul", [q, op("Transpose", [k], perm=[0, 2, 1])]), c(1 / np.sqrt(HD))])
    o = op("MatMul", [op("Softmax", [s], axis=-1), v]); return op("Reshape", [op("Transpose", [o], perm=[1, 0, 2]), c([n, D], np.int64)])
def split(x, k): outs = [f"sp{cnt[0]}_{i}" for i in range(k)]; cnt[0] += 1; nodes.append(H.make_node("Split", [x], outs, axis=-1, num_outputs=k)); return outs
h = op("Add", [op("Mul", [lnorm("x"), c(1 + mod[1])]), c(mod[0])])
q, k, v = split(lin(h, "self_attn.to_qkv", N), 3)
a = sdpa(rmsn(hd(q, N), w["self_attn.q_rms_norm.gamma"]), rmsn(hd(k, N), w["self_attn.k_rms_norm.gamma"]), hd(v, N), N)
x1 = op("Add", ["x", op("Mul", [lin(a, "self_attn.to_out", N), c(mod[2])])])
h = lnorm(x1, w["norm2.weight"], w["norm2.bias"]); q = lin(h, "cross_attn.to_q", N)
k, v = split(lin("ctx", "cross_attn.to_kv", NCTX), 2)
a = sdpa(rmsn(hd(q, N), w["cross_attn.q_rms_norm.gamma"]), rmsn(hd(k, NCTX), w["cross_attn.k_rms_norm.gamma"]), hd(v, NCTX), N)
x2 = op("Add", [x1, lin(a, "cross_attn.to_out", N)])
h = op("Add", [op("Mul", [lnorm(x2), c(1 + mod[4])]), c(mod[3])])
m = op("Gelu", [lin(h, "mlp.mlp.0", N)], approximate="tanh")
y = op("Add", [x2, op("Mul", [lin(m, "mlp.mlp.2", N), c(mod[5])])]); nodes.append(H.make_node("Identity", [y], ["y"]))
g = H.make_graph(nodes, "trellis2_block0", [H.make_tensor_value_info("x", T.FLOAT, [N, D]), H.make_tensor_value_info("ctx", T.FLOAT, [NCTX, CTX])], [H.make_tensor_value_info("y", T.FLOAT, [N, D])], inits)
model = H.make_model(g, opset_imports=[H.make_opsetid("", 21)], ir_version=10); f = f"/tmp/trellis2_block0_{N}.onnx"; onnx.save(model, f)
so = ort.SessionOptions(); so.intra_op_num_threads = int(os.environ.get("THREADS", "4"))
t = time.time(); s = ort.InferenceSession(f, so, providers=["CPUExecutionProvider"]); tload = time.time() - t
s.run(None, {"x": x, "ctx": ctx}); t = time.time(); reps = 3
for _ in range(reps): y = s.run(None, {"x": x, "ctx": ctx})[0]
dt = (time.time() - t) / reps; yr = ref(x)
d = y - yr; rel = float(np.linalg.norm(d) / np.linalg.norm(yr - x)); cos = float((d * 0 + (y - x)).ravel() @ (yr - x).ravel() / np.linalg.norm(y - x) / np.linalg.norm(yr - x))
res = dict(tokens=N, onnx_mb=os.path.getsize(f) / 1e6, load_s=round(tload, 2), block_s=round(dt, 3), rel_err_of_update=round(rel, 4), cosine_of_update=round(cos, 5),
           peak_rss_gb=round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1e6, 2), finite=bool(np.isfinite(y).all()), threads=so.intra_op_num_threads)
print(json.dumps(res)); json.dump(res, open(f"real_block_{N}.json", "w"))
