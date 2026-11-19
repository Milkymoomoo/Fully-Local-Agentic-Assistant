import onnxruntime as ort, numpy as np, time, os, resource, sys
S=int(os.environ.get("S","512")); T=int(os.environ.get("T","4"))
o=ort.SessionOptions(); o.intra_op_num_threads=T; o.graph_optimization_level=ort.GraphOptimizationLevel.ORT_ENABLE_ALL
o.enable_cpu_mem_arena=os.environ.get("ARENA","1")=="1"
t=time.time(); s=ort.InferenceSession(sys.argv[1] if len(sys.argv)>1 else f"birefnet_{S}.onnx", o, providers=["CPUExecutionProvider"]); load=time.time()-t
x=np.load(f"bg_in_{S}.npy"); ref=np.load(f"bg_ref_{S}.npy")
t=time.time(); y=s.run(None,{"image":x})[0]; dt=time.time()-t
sig=lambda a:1/(1+np.exp(-a))
print(f"S={S} T={T} load {load:.1f}s run {dt:.2f}s maxRSS {resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/1e6:.2f} GB  max|Δmask| {np.abs(sig(y)-sig(ref)).max():.4f}")
