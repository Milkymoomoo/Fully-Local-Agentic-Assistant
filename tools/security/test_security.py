#!/usr/bin/env python3
"""Offline tests for vt.py / record.py with a fake VirusTotal (no network, no key): python3 test_security.py"""
import json, os, subprocess, sys, tempfile, unittest
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ["VT_MIN_GAP_SEC"] = "0"; os.environ["VT_POLL_MINUTES"] = "0.001"; os.environ["VT_POLL_EVERY_SEC"] = "0"
import vt, record

STATS = {"malicious": 0, "suspicious": 0, "undetected": 60, "harmless": 0}
class FakeVT(vt.VT):
    def __init__(self, site, known=(), quota_after=None, finishes=True):
        super().__init__(site); self.known = dict.fromkeys(known, 1_700_000_000); self.calls = []; self.quota_after = quota_after; self.finishes = finishes
    def _req(self, method, url, data=None, headers=None, timeout=0):
        if self.quota_after is not None and len(self.calls) >= self.quota_after: raise vt.Quota("QuotaExceededError")
        self.calls.append((method, url.split("/api/v3")[-1])); self.quota["used"] += 1
        if url.endswith("/files/upload_url"): return 200, {"data": "https://upload.example/x"}
        if method == "GET" and "/files/" in url:
            sha = url.rsplit("/", 1)[1]
            if sha in self.known: return 200, {"data": {"attributes": {"last_analysis_date": self.known[sha], "last_analysis_stats": STATS}}}
            return 404, {}
        if method == "POST": self.uploaded = True; return 200, {"data": {"id": "an-1"}}
        if "/analyses/" in url:
            if self.finishes:
                for k in list(self.known) + [s for s in getattr(self, "pending_sha", [])]: self.known[k] = 1_700_000_100
                return 200, {"data": {"attributes": {"status": "completed", "stats": STATS}}}
            return 200, {"data": {"attributes": {"status": "queued"}}}
        raise AssertionError(url)

class T(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp(); self.site = os.path.join(self.d, "site"); os.makedirs(os.path.join(self.site, "security"))
        self.f1 = os.path.join(self.d, "A-arm64-v8a.apk"); open(self.f1, "wb").write(b"one")
        self.f2 = os.path.join(self.d, "A-universal.apk"); open(self.f2, "wb").write(b"two")
        self.h1, self.h2 = vt.sha256(self.f1), vt.sha256(self.f2)
    def items(self): return [{"asset": os.path.basename(f), "sha256": h, "version": "0.1.2", "commit": "abc", "download_url": ""} for f, h in ((self.f1, self.h1), (self.f2, self.h2))]
    def test_known_hash_is_not_uploaded(self):
        v = FakeVT(self.site, known=[self.h1, self.h2]); res, q = vt.run(v, self.items(), {self.h1: self.f1, self.h2: self.f2}, self.site)
        self.assertEqual(len(res), 2); self.assertEqual(q, []); self.assertTrue(all(m == "GET" for m, _ in v.calls))
        self.assertEqual(res[0]["report_url"], "https://www.virustotal.com/gui/file/" + self.h1); self.assertEqual(res[0]["status"], "pass")
    def test_unknown_is_uploaded_then_polled(self):
        v = FakeVT(self.site, known=[self.h2]); v.pending_sha = [self.h1]
        res, q = vt.run(v, self.items(), {self.h1: self.f1, self.h2: self.f2}, self.site)
        self.assertIn(("POST", "/files"), v.calls); self.assertEqual(len(res), 2); self.assertEqual(res[0]["note"], "uploaded")
    def test_quota_queues_the_rest(self):
        v = FakeVT(self.site, known=[self.h1], quota_after=1); res, q = vt.run(v, self.items(), {self.h1: self.f1, self.h2: self.f2}, self.site)
        self.assertEqual(len(res), 1); self.assertEqual([x["sha256"] for x in q], [self.h2]); self.assertIn("quota", q[0]["reason"])
        self.assertEqual(json.load(open(os.path.join(self.site, "security", "queue.json")))[0]["sha256"], self.h2)
    def test_slow_analysis_is_queued_with_its_id(self):
        v = FakeVT(self.site, finishes=False); res, q = vt.run(v, self.items()[:1], {self.h1: self.f1}, self.site)
        self.assertEqual(res, []); self.assertEqual(q[0]["analysis_id"], "an-1")
        v2 = FakeVT(self.site); v2.pending_sha = [self.h1]
        res, q = vt.run(v2, q, {}, self.site); self.assertEqual(len(res), 1); self.assertEqual(q, [])
        self.assertNotIn(("POST", "/files"), v2.calls)   # resumed polling, no second upload
    def test_daily_budget_is_shared_between_runs(self):
        json.dump({"date": vt.now().strftime("%Y-%m-%d"), "used": vt.DAILY}, open(os.path.join(self.site, "security", "quota.json"), "w"))
        real = vt.VT(self.site); real.key = "x"
        with self.assertRaises(vt.Quota): real._req("GET", vt.API + "/files/" + self.h1)
    def test_history_is_append_only_and_index_keeps_everything(self):
        r = os.path.join(self.d, "r.json")
        json.dump({"scan": "virustotal", "entries": [vt.entry(self.items()[0], STATS, "2026-11-19T10:00:00Z", "")]}, open(r, "w"))
        for _ in range(2):
            sys.argv = ["record.py", "--site", self.site, "--version", "0.1.2", "--commit", "abc", r]; record.main()
        files = sorted(os.listdir(os.path.join(self.site, "security", "history", "0.1.2")))
        self.assertEqual(len(files), 2, files)   # same second -> "-2" suffix, never overwritten
        idx = json.load(open(os.path.join(self.site, "security", "index.json")))
        self.assertEqual(len(idx["entries"]), 2); e = idx["entries"][0]
        for k in ("version", "commit", "asset", "sha256", "stats", "date", "report_url"): self.assertTrue(e[k], k)
        b = json.load(open(os.path.join(self.site, "security", "badges", "virustotal.json"))); self.assertEqual(b["color"], "brightgreen")

if __name__ == "__main__":
    unittest.main(verbosity=1)
