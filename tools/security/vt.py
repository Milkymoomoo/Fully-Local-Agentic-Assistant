#!/usr/bin/env python3
"""VirusTotal checks for release assets, kept inside the free public API limits (4 requests/min, 500/day).

  vt.py scan    --site SITE --version V --commit C --out R.json FILE... [--url-base URL]
  vt.py queue   --site SITE --out R.json          finish queued uploads / analyses
  vt.py recheck --site SITE --out R.json          re-read the newest release's reports (re-analyse if > 7 days old)

SITE is a checkout of the gh-pages branch. State kept there:
  security/queue.json   scans that hit the quota or were still running (finished later by the scheduled job)
  security/quota.json   {"date": "YYYY-MM-DD" (UTC), "used": n}, so separate runs share the daily budget
R.json is a results file for record.py. The key comes from the VT_API_KEY environment variable (a repository
secret); it is never printed or written anywhere. Every file is looked up by SHA-256 first and only uploaded if
VirusTotal has never seen it. Only the standard library is used.
"""
import argparse, datetime as dt, hashlib, json, os, sys, tempfile, time, urllib.error, urllib.request, uuid

API = "https://www.virustotal.com/api/v3"
GUI = "https://www.virustotal.com/gui/file/"
MIN_GAP = float(os.environ.get("VT_MIN_GAP_SEC", "15.5"))        # 4/min -> one call every 15 s (+ margin)
DAILY = int(os.environ.get("VT_DAILY_BUDGET", "480"))            # 500/day minus a margin for manual use
POLL_MIN = float(os.environ.get("VT_POLL_MINUTES", "25"))        # how long one run waits for an analysis
POLL_EVERY = float(os.environ.get("VT_POLL_EVERY_SEC", "60"))     # one poll per minute (each poll is one call)
REANALYSE_DAYS = 7

class Quota(Exception): pass

def now(): return dt.datetime.now(dt.timezone.utc)
def iso(t): return t.strftime("%Y-%m-%dT%H:%M:%SZ")
def load(p, d):
    try: return json.load(open(p, encoding="utf-8"))
    except (OSError, ValueError): return d
def save(p, v):
    os.makedirs(os.path.dirname(p), exist_ok=True); tmp = p + ".tmp"
    json.dump(v, open(tmp, "w", encoding="utf-8"), indent=1, sort_keys=True); os.replace(tmp, p)
def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    return h.hexdigest()

class VT:
    def __init__(self, site):
        self.key = os.environ.get("VT_API_KEY", "")
        self.qpath = os.path.join(site, "security", "quota.json")
        q = load(self.qpath, {}); today = now().strftime("%Y-%m-%d")
        self.quota = q if q.get("date") == today else {"date": today, "used": 0}
        self.last = 0.0
    def _req(self, method, url, data=None, headers=None, timeout=120):
        if self.quota["used"] >= DAILY: raise Quota(f"daily budget of {DAILY} calls used")
        wait = self.last + MIN_GAP - time.monotonic()
        if wait > 0: time.sleep(wait)
        h = {"x-apikey": self.key, "accept": "application/json"}; h.update(headers or {})
        req = urllib.request.Request(url, data=data, headers=h, method=method)
        self.quota["used"] += 1; save(self.qpath, self.quota); self.last = time.monotonic()
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r: return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            body = {}
            try: body = json.load(e)
            except ValueError: pass
            code = (body.get("error") or {}).get("code", "")
            if e.code == 429 or code == "QuotaExceededError": raise Quota(code or "HTTP 429")
            if e.code == 404: return 404, body
            raise RuntimeError(f"VirusTotal {method} {url.split('/api/v3')[-1]}: HTTP {e.code} {code}") from None
    def file(self, sha):
        st, b = self._req("GET", f"{API}/files/{sha}")
        return None if st == 404 else b["data"]["attributes"]
    def upload(self, path):
        url = f"{API}/files"
        if os.path.getsize(path) > 32_000_000:   # big files go to a one-time upload URL (max 650 MB)
            url = self._req("GET", f"{API}/files/upload_url")[1]["data"]
        bnd = uuid.uuid4().hex; name = os.path.basename(path)
        with open(path, "rb") as f: content = f.read()
        body = (f"--{bnd}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\n"
                f"Content-Type: application/octet-stream\r\n\r\n").encode() + content + f"\r\n--{bnd}--\r\n".encode()
        del content
        return self._req("POST", url, body, {"content-type": f"multipart/form-data; boundary={bnd}"}, timeout=900)[1]["data"]["id"]
    def reanalyse(self, sha): return self._req("POST", f"{API}/files/{sha}/analyse", b"")[1]["data"]["id"]
    def analysis(self, aid):
        a = self._req("GET", f"{API}/analyses/{aid}")[1]["data"]["attributes"]
        return a.get("status"), a.get("stats") or {}

def verdict(stats):
    m, s = stats.get("malicious", 0), stats.get("suspicious", 0)
    return "fail" if m else ("warn" if s else "pass")

def entry(item, stats, when, note):
    eng = sum(stats.get(k, 0) for k in ("malicious", "suspicious", "undetected", "harmless"))
    return {"asset": item["asset"], "sha256": item["sha256"], "version": item["version"], "commit": item.get("commit", ""),
            "status": verdict(stats), "stats": stats, "analysis_date": when, "report_url": GUI + item["sha256"],
            "summary": f"{stats.get('malicious', 0)} of {eng} engines flag it" + (f", {stats.get('suspicious')} suspicious" if stats.get("suspicious") else ""),
            "note": note}

def from_attrs(item, a, note):
    if not a or not a.get("last_analysis_date"): return None
    return entry(item, a.get("last_analysis_stats", {}), iso(dt.datetime.fromtimestamp(a["last_analysis_date"], dt.timezone.utc)), note)

def wait_for(vt, item, aid):
    """Poll an analysis until it completes or this run's polling time runs out (then it is queued)."""
    end = time.monotonic() + POLL_MIN * 60
    while True:
        st, stats = vt.analysis(aid)
        if st == "completed":
            a = vt.file(item["sha256"])   # the file report carries the date and the full engine counts
            return from_attrs(item, a, item.get("note", "")) or entry(item, stats, iso(now()), item.get("note", ""))
        if time.monotonic() > end: return None
        time.sleep(max(0, POLL_EVERY - MIN_GAP))

def process(vt, item, path=None):
    """One asset: look up by hash, upload only if unknown, poll. Returns (result | None, queue item | None)."""
    try:
        if item.get("analysis_id"):
            r = wait_for(vt, item, item["analysis_id"])
            return (r, None) if r else (None, dict(item, reason="analysis still running"))
        a = vt.file(item["sha256"])
        if item.get("reanalyse") and a:
            item = dict(item, analysis_id=vt.reanalyse(item["sha256"]), note="re-analysed")
            r = wait_for(vt, item, item["analysis_id"])
            return (r, None) if r else (None, dict(item, reason="re-analysis still running"))
        r = from_attrs(item, a, "already known to VirusTotal")
        if r: return r, None
        if a:   # known but its first analysis is not finished yet
            return None, dict(item, reason="first analysis still running")
        tmp = None
        if path is None:   # queued upload: fetch the release asset again, delete it right after
            fd, tmp = tempfile.mkstemp(suffix="-" + item["asset"]); os.close(fd)
            urllib.request.urlretrieve(item["download_url"], tmp); path = tmp
            if sha256(path) != item["sha256"]: os.remove(tmp); raise RuntimeError(f"{item['asset']}: downloaded file has a different SHA-256")
        try: item = dict(item, analysis_id=vt.upload(path), note="uploaded")
        finally:
            if tmp: os.remove(tmp)
        r = wait_for(vt, item, item["analysis_id"])
        return (r, None) if r else (None, dict(item, reason="analysis still running"))
    except Quota as q:
        return None, dict(item, reason=f"VirusTotal quota reached ({q})")

def run(vt, items, paths, site):
    qpath = os.path.join(site, "security", "queue.json")
    queue = [q for q in load(qpath, []) if q["sha256"] not in {i["sha256"] for i in items}]
    results, quota_hit = [], False
    for it in items:
        if quota_hit:
            queue.append(dict(it, reason="VirusTotal quota reached", since=it.get("since", iso(now())))); continue
        r, q = process(vt, it, paths.get(it["sha256"]))
        if r: results.append(r)
        if q:
            queue.append(dict(q, since=it.get("since", iso(now())))); quota_hit = "quota" in q["reason"]
    save(qpath, queue)
    return results, queue

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("mode", choices=["scan", "queue", "recheck"])
    ap.add_argument("--site", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--version"); ap.add_argument("--commit", default=""); ap.add_argument("--url-base", default="")
    ap.add_argument("files", nargs="*"); a = ap.parse_args()
    if not os.environ.get("VT_API_KEY"):
        print("VT_API_KEY is not set (add it as a repository secret); skipping VirusTotal."); json.dump({"scan": "virustotal", "entries": []}, open(a.out, "w")); return
    vt = VT(a.site); items, paths = [], {}
    if a.mode == "scan":
        seen = set()
        for f in a.files:
            h = sha256(f)
            if h in seen: continue     # the stable-name copy has the same bytes as the versioned file
            seen.add(h); paths[h] = f
            items.append({"asset": os.path.basename(f), "sha256": h, "version": a.version, "commit": a.commit,
                          "download_url": (a.url_base.rstrip("/") + "/" + os.path.basename(f)) if a.url_base else ""})
    elif a.mode == "queue":
        items = load(os.path.join(a.site, "security", "queue.json"), [])
    else:
        idx = load(os.path.join(a.site, "security", "index.json"), {})
        vt_entries = [e for e in idx.get("entries", []) if e["scan"] == "virustotal"]
        if vt_entries:
            newest = max(vt_entries, key=lambda e: e["date"])["version"]
            latest = {}
            for e in sorted((e for e in vt_entries if e["version"] == newest), key=lambda e: e["date"]): latest[e["sha256"]] = e
            for e in latest.values():
                old = (now() - dt.datetime.strptime(e["analysis_date"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc)).days >= REANALYSE_DAYS
                items.append({"asset": e["asset"], "sha256": e["sha256"], "version": e["version"], "commit": e["commit"], "reanalyse": old})
    results, queue = run(vt, items, paths, a.site)
    json.dump({"scan": "virustotal", "entries": results}, open(a.out, "w"), indent=1)
    print(f"VirusTotal: {len(results)} result(s), {len(queue)} queued, {vt.quota['used']} of {DAILY} calls used today")
    for r in results: print(f"  {r['asset']}: {r['status']} ({r['summary']}) {r['report_url']}")
    for q in queue: print(f"  queued {q['asset']}: {q['reason']}")

if __name__ == "__main__":
    main()
