import sys, os
sys.argv = [sys.argv[0], "--cpu"] + sys.argv[1:]
sys.path.insert(0, "/workspace/comfyui_ref")
import comfy.options; comfy.options.enable_args_parsing()
import comfy.cli_args
import torch
torch.set_num_threads(int(os.environ.get("T", "4")))
M = "/workspace/models3d"
def rss_gb():
    for l in open("/proc/self/status"):
        if l.startswith("VmHWM"): return int(l.split()[1]) / 1e6
