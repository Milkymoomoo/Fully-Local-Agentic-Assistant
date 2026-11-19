import onnxruntime as ort, numpy as np, time, sys, resource
f, inp, ref = sys.argv[1], sys.argv[2], sys.argv[3]; th = int(sys.argv[4]) if len(sys.argv) > 4 else 4
o = ort.SessionOptions(); o.intra_op_num_threads = th
s = ort.InferenceSession(f, o, providers=["CPUExecutionProvider"])
x = np.load(inp); r = np.load(ref)
print("inputs", [(i.name, i.shape, i.type) for i in s.get_inputs()])
t = time.time(); y = s.run(None, {s.get_inputs()[0].name: x.astype(np.float16 if "16" in s.get_inputs()[0].type else np.float32)})[0]; t1 = time.time() - t
t = time.time(); y = s.run(None, {s.get_inputs()[0].name: x.astype(np.float16 if "16" in s.get_inputs()[0].type else np.float32)})[0]; t2 = time.time() - t
y = y.astype(np.float32); cos = (y * r).sum() / np.linalg.norm(y) / np.linalg.norm(r)
print(f"{f}: first {t1:.2f}s second {t2:.2f}s threads={th} maxabs={np.abs(y-r).max():.4f} cos={cos:.6f} peakRSS={resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/1e6:.2f}GB")
