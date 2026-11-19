from common import *
import time, threading, json
def cur():
    for l in open("/proc/self/status"):
        if l.startswith("VmRSS"): return int(l.split()[1])
peak=[0]
def w():
    while True:
        r=cur(); peak[0]=max(peak[0],r)
        if r>float(os.environ.get("CAP","5.5e6")): print("BIG", r, flush=True); os._exit(3)
        time.sleep(0.02)
threading.Thread(target=w,daemon=True).start()
import comfy.ops, comfy.background_removal.birefnet as B
from comfy.utils import load_torch_file
cfg=json.load(open(os.path.dirname(B.__file__)+"/../background_removal/birefnet.json")) if not os.path.exists(os.path.dirname(B.__file__)+"/birefnet.json") else json.load(open(os.path.dirname(B.__file__)+"/birefnet.json"))
sd=load_torch_file(M+"/birefnet.safetensors")
print("sd dtypes", {str(v.dtype) for v in sd.values()}, "rss %.2f GB"%(cur()/1e6), flush=True)
m=B.BiRefNet(cfg, torch.float32, "cpu", comfy.ops.disable_weight_init)
sd={k:v.float() for k,v in sd.items()}
mi,u=m.load_state_dict(sd, strict=False); print("missing",len(mi),"unexpected",len(u)); del sd
m.eval(); print("loaded rss %.2f GB"%(cur()/1e6), flush=True)
S=int(os.environ.get("S","1024"))
with torch.inference_mode():
    t=time.time(); o=m(pixel_values=torch.rand(1,3,S,S)); print("S=%d done %.1fs peak %.2f GB"%(S,time.time()-t, peak[0]/1e6), o.shape if hasattr(o,'shape') else type(o))
