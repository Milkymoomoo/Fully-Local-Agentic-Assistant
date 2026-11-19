"""One TRELLIS 2 flow-DiT block at the exact dimensions read from the checkpoint headers
(d=1536, 12 heads x 128, MLP 8192, cross-attention to 1024-dim DINOv3 tokens), exported to ONNX
and run with onnxruntime CPU (same runtime family as on Android: ORT + NNAPI/QNN/XNNPACK EPs).
Measures fp32 and int8 (dynamic quant, like the int8 'convrot' checkpoint) per block."""
import numpy as np, onnx, onnxruntime as ort, time, sys, os, json, resource
from onnx import helper as H, TensorProto as T, numpy_helper as nh
D, HD, NH, MLP, CTX, NCTX = 1536, 128, 12, 8192, 1024, 1029
rng = np.random.default_rng(0)
def W(name, *shape): return nh.from_array((rng.standard_normal(shape) * 0.02).astype(np.float32), name)

def block(N):
    inits, nodes = [], []
    def lin(x, i, o, name):
        inits.append(W(name + "_w", i, o)); inits.append(W(name + "_b", o))
        nodes.append(H.make_node("MatMul", [x, name + "_w"], [name + "_mm"], name=name + "_matmul"))
        nodes.append(H.make_node("Add", [name + "_mm", name + "_b"], [name]))
        return name
    def ln(x, name):
        inits.append(nh.from_array(np.ones(D, np.float32), name + "_g"))
        nodes.append(H.make_node("LayerNormalization", [x, name + "_g"], [name], axis=-1, epsilon=1e-6)); return name
    def heads(x, n, name):  # [n, D] -> [NH, n, HD]
        inits.append(nh.from_array(np.array([n, NH, HD], np.int64), name + "_s"))
        nodes.append(H.make_node("Reshape", [x, name + "_s"], [name + "_r"]))
        nodes.append(H.make_node("Transpose", [name + "_r"], [name], perm=[1, 0, 2])); return name
    def attn(q, k, v, nq, name):  # chunk-free SDPA; for big N the runtime has to tile (see bench_attention)
        nodes.append(H.make_node("Transpose", [k], [name + "_kt"], perm=[0, 2, 1]))
        nodes.append(H.make_node("MatMul", [q, name + "_kt"], [name + "_s"]))
        inits.append(nh.from_array(np.array(1 / np.sqrt(HD), np.float32), name + "_sc"))
        nodes.append(H.make_node("Mul", [name + "_s", name + "_sc"], [name + "_ss"]))
        nodes.append(H.make_node("Softmax", [name + "_ss"], [name + "_p"], axis=-1))
        nodes.append(H.make_node("MatMul", [name + "_p", v], [name + "_o"]))
        nodes.append(H.make_node("Transpose", [name + "_o"], [name + "_t"], perm=[1, 0, 2]))
        inits.append(nh.from_array(np.array([nq, D], np.int64), name + "_s2"))
        nodes.append(H.make_node("Reshape", [name + "_t", name + "_s2"], [name])); return name
    # self-attention
    h = ln("x", "ln1"); qkv = lin(h, D, 3 * D, "qkv")
    nodes.append(H.make_node("Split", [qkv], ["q0", "k0", "v0"], axis=-1, num_outputs=3))
    a = attn(heads("q0", N, "q"), heads("k0", N, "k"), heads("v0", N, "v"), N, "sa")
    a = lin(a, D, D, "proj"); nodes.append(H.make_node("Add", ["x", a], ["x1"]))
    # cross-attention to image tokens
    h = ln("x1", "ln2"); q = lin(h, D, D, "cq"); kv = lin("ctx", CTX, 2 * D, "ckv")
    nodes.append(H.make_node("Split", [kv], ["ck0", "cv0"], axis=-1, num_outputs=2))
    a = attn(heads(q, N, "cqh"), heads("ck0", NCTX, "ckh"), heads("cv0", NCTX, "cvh"), N, "ca")
    a = lin(a, D, D, "cproj"); nodes.append(H.make_node("Add", ["x1", a], ["x2"]))
    # MLP
    h = ln("x2", "ln3"); m = lin(h, D, MLP, "fc1"); nodes.append(H.make_node("Gelu", [m], ["g"], approximate="tanh"))
    m = lin("g", MLP, D, "fc2"); nodes.append(H.make_node("Add", ["x2", m], ["y"]))
    g = H.make_graph(nodes, "dit_block", [H.make_tensor_value_info("x", T.FLOAT, [N, D]), H.make_tensor_value_info("ctx", T.FLOAT, [NCTX, CTX])],
                     [H.make_tensor_value_info("y", T.FLOAT, [N, D])], inits)
    return H.make_model(g, opset_imports=[H.make_opsetid("", 21), H.make_opsetid("com.microsoft", 1)], ir_version=10)

def run(path, N, reps=3):
    so = ort.SessionOptions(); so.intra_op_num_threads = int(os.environ.get("THREADS", "4"))
    s = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])
    r = np.random.default_rng(1); x = r.standard_normal((N, D)).astype(np.float32); c = r.standard_normal((NCTX, CTX)).astype(np.float32)
    s.run(None, {"x": x, "ctx": c}); t = time.time()
    for _ in range(reps): y = s.run(None, {"x": x, "ctx": c})[0]
    return (time.time() - t) / reps, y

if __name__ == "__main__":
    from onnxruntime.quantization import quantize_dynamic, QuantType
    out = {}
    for N in [int(a) for a in sys.argv[1:]] or [1024, 4096]:
        m = block(N); f = f"/tmp/dit_{N}.onnx"; onnx.save(m, f)
        q = f"/tmp/dit_{N}_int8.onnx"; quantize_dynamic(f, q, weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul"],
                      nodes_to_quantize=[n.name for n in m.graph.node if n.op_type == "MatMul" and n.input[1].endswith("_w")])
        t32, y32 = run(f, N); t8, y8 = run(q, N)
        rel = float(np.abs(y32 - y8).mean() / np.abs(y32).mean())
        lin_flops = 2 * N * D * (3 * D + D + D + D + 2 * MLP) + 2 * NCTX * CTX * 2 * D
        att_flops = 2 * 2 * N * N * D + 2 * 2 * N * NCTX * D
        out[N] = dict(fp32_s=t32, int8_s=t8, int8_rel_err=rel, gflop=(lin_flops + att_flops) / 1e9,
                      fp32_gflops=(lin_flops + att_flops) / t32 / 1e9, int8_gflops=(lin_flops + att_flops) / t8 / 1e9,
                      params_M=sum(np.prod(i.dims) for i in m.graph.initializer) / 1e6, onnx_mb_fp32=os.path.getsize(f) / 1e6, onnx_mb_int8=os.path.getsize(q) / 1e6,
                      peak_rss_gb=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1e6)
        print(N, json.dumps(out[N]), flush=True)
    json.dump(out, open("bench_dit.json", "w"), indent=1)
