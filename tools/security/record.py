#!/usr/bin/env python3
"""Append scan results to the append-only history on the gh-pages branch.

  record.py --site SITE --version V --commit C --run-url URL R.json [R.json ...]

Writes, inside SITE (a gh-pages checkout):
  security/history/<version>/<scan>-<UTC date>.json   one file per scan run, created exclusively: an existing file
                                                      is never overwritten (a -2, -3 ... suffix is added instead)
  security/index.json        every entry ever recorded (newest first) + the versions + the pending queue
  security/badges/<scan>.json  shields.io "endpoint" badges for the newest result of each scan
Entries: version, commit, scan, asset, sha256, status (pass/warn/fail), stats, summary, date, analysis_date,
report_url (the official public report), run_url, file (the history file).
"""
import argparse, datetime as dt, json, os, re

REPO = os.environ.get("GITHUB_REPOSITORY", "Milkymoomoo/Fully-Local-Agentic-Assistant")
LABEL = {"virustotal": "VirusTotal", "codeql": "CodeQL", "gitleaks": "gitleaks", "osv": "OSV-Scanner", "scorecard": "Scorecard"}
COLOR = {"pass": "brightgreen", "warn": "yellow", "fail": "red"}

def load(p, d):
    try: return json.load(open(p, encoding="utf-8"))
    except (OSError, ValueError): return d
def save(p, v):
    os.makedirs(os.path.dirname(p), exist_ok=True); tmp = p + ".tmp"
    json.dump(v, open(tmp, "w", encoding="utf-8"), indent=1); os.replace(tmp, p)

def create_new(dirpath, stem, data):
    os.makedirs(dirpath, exist_ok=True); n = 1
    while True:
        name = f"{stem}.json" if n == 1 else f"{stem}-{n}.json"
        try:
            with open(os.path.join(dirpath, name), "x", encoding="utf-8") as f: json.dump(data, f, indent=1)
            return name
        except FileExistsError: n += 1

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--site", required=True); ap.add_argument("--version", required=True)
    ap.add_argument("--commit", required=True); ap.add_argument("--run-url", default=""); ap.add_argument("results", nargs="+")
    a = ap.parse_args()
    sec = os.path.join(a.site, "security"); ipath = os.path.join(sec, "index.json")
    idx = load(ipath, {"schema": 1, "repo": REPO, "entries": [], "versions": {}, "pending": []})
    t = dt.datetime.now(dt.timezone.utc); date = t.strftime("%Y-%m-%dT%H:%M:%SZ"); stamp = t.strftime("%Y-%m-%dT%H%M%SZ")
    safe_v = re.sub(r"[^0-9A-Za-z._-]", "_", a.version)
    for rp in a.results:
        r = load(rp, None)
        if not r or not r.get("entries"): continue
        by_ver = {}
        for e in r["entries"]: by_ver.setdefault(e.get("version") or a.version, []).append(e)
        for ver, ents in by_ver.items():
            sv = re.sub(r"[^0-9A-Za-z._-]", "_", ver)
            commit = ents[0].get("commit") or a.commit
            doc = {"scan": r["scan"], "version": ver, "commit": commit, "date": date, "run_url": a.run_url,
                   "entries": [dict(e, version=ver, commit=e.get("commit") or commit) for e in ents]}
            name = create_new(os.path.join(sec, "history", sv), f"{r['scan']}-{stamp}", doc)
            for e in doc["entries"]:
                idx["entries"].insert(0, {"version": ver, "commit": e["commit"], "scan": r["scan"], "asset": e["asset"], "sha256": e.get("sha256", ""),
                    "status": e["status"], "stats": e.get("stats", {}), "summary": e.get("summary", ""), "date": date,
                    "analysis_date": e.get("analysis_date", date), "report_url": e.get("report_url", ""), "run_url": a.run_url,
                    "note": e.get("note", ""), "file": f"history/{sv}/{name}"})
            v = idx["versions"].setdefault(ver, {"commit": commit, "first_seen": date,
                    "release_url": f"https://github.com/{REPO}/releases/tag/v{ver}"})
            v["last_scan"] = date
    idx["entries"].sort(key=lambda e: e["date"], reverse=True)
    idx["pending"] = [{k: q.get(k) for k in ("scan", "asset", "sha256", "version", "commit", "reason", "since")} | {"scan": "virustotal"}
                      for q in load(os.path.join(sec, "queue.json"), [])]
    idx["updated"] = date
    save(ipath, idx)
    # badges: newest result per scan (for VirusTotal: the newest version's assets together)
    for scan in {e["scan"] for e in idx["entries"]}:
        es = [e for e in idx["entries"] if e["scan"] == scan]
        if scan == "virustotal":
            ver = es[0]["version"]; latest = {}
            for e in reversed([e for e in es if e["version"] == ver]): latest[e["asset"]] = e
            worst = max(latest.values(), key=lambda e: ["pass", "warn", "fail"].index(e["status"]))
            flagged = sum(e["stats"].get("malicious", 0) for e in latest.values())
            msg = f"{'clean' if worst['status'] == 'pass' else str(flagged) + ' flags'} · v{ver}"
            st = worst["status"]
        else:
            e = es[0]; st = e["status"]
            msg = f"{e['stats'].get('score')}/10" if scan == "scorecard" else {"pass": "passing", "warn": "warnings", "fail": "failing"}[st]
        pending = any(p["scan"] == scan for p in idx["pending"])
        save(os.path.join(sec, "badges", f"{scan}.json"), {"schemaVersion": 1, "label": LABEL.get(scan, scan),
             "message": msg + (" (rescan pending)" if pending else ""), "color": COLOR[st]})
    print(f"recorded into {sec}: {len(idx['entries'])} entries, {len(idx['pending'])} pending")

if __name__ == "__main__":
    main()
