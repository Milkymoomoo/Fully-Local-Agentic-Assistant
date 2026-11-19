#!/usr/bin/env python3
"""merge_state.py FROM_DIR TO_DIR: fold a job's VirusTotal queue.json / quota.json into the gh-pages copies
(queue: union by SHA-256, the job's entry wins; quota: the higher count for the same UTC day)."""
import json, os, sys
a, b = sys.argv[1:3]
def load(p, d):
    try: return json.load(open(p))
    except (OSError, ValueError): return d
q = {x["sha256"]: x for x in load(os.path.join(b, "queue.json"), [])}
q.update({x["sha256"]: x for x in load(os.path.join(a, "queue.json"), [])})
json.dump(list(q.values()), open(os.path.join(b, "queue.json"), "w"), indent=1)
qa, qb = load(os.path.join(a, "quota.json"), {}), load(os.path.join(b, "quota.json"), {})
if qa.get("date") and (qa.get("date") != qb.get("date") or qa.get("used", 0) > qb.get("used", 0)):
    json.dump(qa if qa.get("date", "") >= qb.get("date", "") else qb, open(os.path.join(b, "quota.json"), "w"))
