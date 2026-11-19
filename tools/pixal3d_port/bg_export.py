exec(open("bg2.py").read().split("S=int(")[0])
import numpy as np
S=int(os.environ.get("S","512"))
x=torch.rand(1,3,S,S); 
with torch.inference_mode(): ref=m(pixel_values=x).numpy()
np.save(f"bg_in_{S}.npy", x.numpy()); np.save(f"bg_ref_{S}.npy", ref)
class W(torch.nn.Module):
    def __init__(s,m): super().__init__(); s.m=m
    def forward(s,x): return s.m(pixel_values=x)
t=time.time()
with torch.no_grad(): torch.onnx.export(W(m), x, f"birefnet_{S}.onnx", input_names=["image"], output_names=["mask_logits"], opset_version=int(os.environ.get("OPSET","19")), dynamo=True)
print("export %.1fs peak %.2f GB"%(time.time()-t, peak[0]/1e6))
