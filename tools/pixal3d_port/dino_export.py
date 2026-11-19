from common import *
import comfy.clip_vision, time, numpy as np
cv = comfy.clip_vision.load(f"{M}/dino_v3_L_naf_fp32.safetensors")
print(type(cv), type(cv.model), "naf", type(getattr(cv, "naf", None)))
inner = cv.model
class Wrap(torch.nn.Module):
    def __init__(s, m): super().__init__(); s.m = m
    def forward(s, img):   # img: [1,3,S,S] already normalised
        return s.m(img, skip_norm_elementwise=True)[0]
w = Wrap(inner).eval()
for S in (512,):
    x = torch.randn(1, 3, S, S)
    t = time.time()
    with torch.no_grad(): ref = w(x)
    print(S, "torch", ref.shape, "%.1fs" % (time.time() - t))
    torch.onnx.export(w, (x,), f"dinov3_l_{S}.onnx", input_names=["image"], output_names=["tokens"], opset_version=18, dynamo=True, external_data=False)
    np.save(f"dino_ref_{S}.npy", ref.numpy()); np.save(f"dino_in_{S}.npy", x.numpy())
print("peak rss GB", rss_gb())
