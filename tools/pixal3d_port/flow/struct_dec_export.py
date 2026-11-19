"""TRELLIS.2 structure decoder (dense 3D conv, 16^3 x 8 latent -> 64^3 occupancy logits) to ONNX, checked against
the reference voxels (ref/voxel32.pt = maxpool2(logits > 0)) on the reference latent."""
import sys, os, time
sys.path.insert(0, "/workspace/port3d")
from common import *
import torch, numpy as np
import nodes as N
torch.set_grad_enabled(False)
OUT = "/workspace/port3d/onnx/trellis2_struct_dec.onnx"
v = N.VAELoader().load_vae("trellis_2_shape_vae_bf16.safetensors")[0]
dec = v.first_stage_model.struct_dec.float().eval()
for m in dec.modules():
    for n, p in list(m.named_parameters(recurse=False)): p.data = p.data.float()
lat = torch.load("/workspace/port3d/ref/ss_latent.pt", weights_only=False)["samples"][:, :8].float()
t = time.time(); y = dec(lat); print("torch", y.shape, round(time.time() - t, 2), "s", flush=True)
vox = torch.nn.functional.max_pool3d((y > 0).float(), 2, 2, 0) > 0.5
ref = torch.load("/workspace/port3d/ref/voxel32.pt", weights_only=False)
ref = ref.bool().reshape(vox.shape) if ref.numel() == vox.numel() else ref
print("occupied", int(vox.sum()), "ref", int(ref.sum()), "agree", float((vox == ref.bool()).float().mean()))
torch.onnx.export(dec, (lat,), OUT, input_names=["latent"], output_names=["logits"], opset_version=17, dynamo=False)
import onnxruntime as ort
s = ort.InferenceSession(OUT); t = time.time(); yo = s.run(None, {"latent": lat.numpy()})[0]
print("onnx", round(time.time() - t, 2), "s max|d|", float(np.abs(yo - y.numpy()).max()), "size MB", os.path.getsize(OUT) / 1e6)
np.save("/workspace/port3d/ref/struct_logits.npy", y.numpy())
# the port's own structure latent (flow/ss_sample.py) through the same decoder: occupancy agreement with the reference
p = np.load("/workspace/port3d/ref/ss_latent_port.npy")[:, :8]
yp = s.run(None, {"latent": p.astype(np.float32)})[0]
vp = torch.nn.functional.max_pool3d(torch.from_numpy(yp > 0).float(), 2, 2, 0) > 0.5
r = ref.bool().reshape(vp.shape)
inter = int((vp & r).sum()); union = int((vp | r).sum())
print("port voxels", int(vp.sum()), "ref", int(r.sum()), "IoU", inter / max(union, 1))
np.save("/workspace/port3d/ref/voxel32_port.npy", vp.numpy())
