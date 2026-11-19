import sys, os, time, json, numpy as np, collections
sys.path.insert(0, "/workspace/ort128")
import onnxruntime as ort
m = sys.argv[1]; spin = os.environ.get("SPIN", "1"); N = int(os.environ.get("N", "1601"))
o = ort.SessionOptions(); o.intra_op_num_threads = int(os.environ.get("T", "4")); o.enable_cpu_mem_arena = False
o.add_session_config_entry("session.intra_op.allow_spinning", spin)
for kv in sys.argv[2:]: k, v = kv.split("="); o.add_session_config_entry(k, v)
if os.environ.get("PROF"): o.enable_profiling = True
if os.environ.get("NOPAT"): o.enable_mem_pattern = False
if os.environ.get("ORDER"): o.execution_order = getattr(ort.ExecutionOrder, os.environ["ORDER"])
t = time.time(); s = ort.InferenceSession(m, o); print("load", round(time.time() - t, 1), [l.strip() for l in open("/proc/self/status") if l.startswith(("VmRSS","RssAnon","RssFile"))])
feeds = {}
rng = np.random.default_rng(0)
for i in s.get_inputs():
    shp = [d if isinstance(d, int) else (5 if d == "L" else N) for d in i.shape]
    if i.name == "t": feeds[i.name] = np.array([500.0], np.float32)
    elif i.name == "coords": feeds[i.name] = rng.integers(0, 32, (N, 3)).astype(np.float32)
    else: feeds[i.name] = rng.standard_normal(shp).astype(np.float32)
for r in range(int(os.environ.get("R", "2"))):
    t = time.time(); y = s.run(None, feeds); print("run", round(time.time() - t, 2), flush=True)
hwm = [l for l in open("/proc/self/status") if l.startswith("VmHWM")][0].split()[1]; print("HWM MB", int(hwm) // 1024)
np.save("/tmp/prof_out.npy", y[0])
if os.environ.get("PROF"):
    f = s.end_profiling(); d = json.load(open(f)); c = collections.Counter()
    for e in d:
        if e.get("cat") == "Node" and e["name"].endswith("_kernel_time"): c[e["args"]["op_name"]] += e["dur"]
    tot = sum(c.values()); print({k: round(v / tot * 100, 1) for k, v in c.most_common(10)}, "total ms", tot / 1000 / int(os.environ.get("R", "2")))
