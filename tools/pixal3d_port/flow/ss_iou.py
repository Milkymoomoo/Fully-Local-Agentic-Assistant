import sys, numpy as np, torch, onnxruntime as ort
o = ort.SessionOptions(); o.intra_op_num_threads = 2
s = ort.InferenceSession("/workspace/port3d/onnx_opt/trellis2_struct_dec.onnx", o)
ref = torch.load("/workspace/port3d/ref/voxel32.pt", weights_only=False).bool().numpy().reshape(32, 32, 32)
for f in sys.argv[1:]:
    x = np.load(f)[:, :8].astype(np.float32).reshape(1, 8, 16, 16, 16)
    lg = s.run(None, {"latent": x})[0].reshape(64, 64, 64)
    occ = (lg > 0).reshape(32, 2, 32, 2, 32, 2).any(axis=(1, 3, 5))
    print(f, "voxels", occ.sum(), "ref", ref.sum(), "IoU %.3f" % ((occ & ref).sum() / (occ | ref).sum()))
