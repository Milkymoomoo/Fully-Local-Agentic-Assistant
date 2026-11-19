import sys, os, time, json, threading, numpy as np
sys.path.insert(0, "/workspace/ort128")
import onnxruntime as ort
m = sys.argv[1]; N = int(os.environ.get("N", "1601"))
o = ort.SessionOptions(); o.intra_op_num_threads = 4; o.enable_cpu_mem_arena = False; o.enable_mem_pattern = False; o.enable_profiling = True
for kv in sys.argv[2:]: k, v = kv.split("="); o.add_session_config_entry(k, v)
s = ort.InferenceSession(m, o); t0ns = s.get_profiling_start_time_ns()
rng = np.random.default_rng(0); feeds = {}
for i in s.get_inputs():
    shp = [d if isinstance(d, int) else (5 if d == "L" else N) for d in i.shape]
    if i.name == "t": feeds[i.name] = np.array([500.0], np.float32)
    elif i.name == "coords": feeds[i.name] = rng.integers(0, 32, (N, 3)).astype(np.float32)
    else: feeds[i.name] = rng.standard_normal(shp).astype(np.float32)
samples = []; on = True
def poll():
    while on:
        samples.append((time.time_ns(), int(open("/proc/self/statm").read().split()[1]) * 4096 // 2**20)); time.sleep(0.003)
th = threading.Thread(target=poll); th.start(); s.run(None, feeds); on = False; th.join()
d = json.load(open(s.end_profiling()))
ev = sorted([e for e in d if e.get("cat") == "Node" and e["name"].endswith("_kernel_time")], key=lambda e: e["ts"])
base = samples[0][1]; pk = max(samples, key=lambda x: x[1]); print("base", base, "peak", pk[1])
# rss timeline at each node end, top increments
import bisect
ts = [x[0] for x in samples]
def rss_at(us): i = bisect.bisect_left(ts, t0ns + us * 1000); return samples[min(i, len(samples) - 1)][1]
rows = [(rss_at(e["ts"] + e["dur"]), e["name"][:-12], e["args"]["op_name"]) for e in ev]
prev = 0
for r in rows:
    if r[0] - prev > 30: print("JUMP", r[0] - prev, r)
    prev = r[0]
step = max(1, len(rows) // 40)
for r in rows[::step]: print(r)
print("max at", max(rows))
