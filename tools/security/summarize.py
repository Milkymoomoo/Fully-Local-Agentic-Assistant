#!/usr/bin/env python3
"""Turn a scanner's report into a results file for record.py (counts only: no secrets, no code snippets).

  summarize.py codeql    SARIF_DIR   --out R.json --report-url URL
  summarize.py gitleaks  REPORT.json --out R.json --report-url URL
  summarize.py osv       REPORT.json --out R.json --report-url URL
  summarize.py scorecard RESULTS.json --out R.json --report-url URL
"""
import argparse, datetime as dt, glob, json, os

def now(): return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
def load(p):
    try: return json.load(open(p, encoding="utf-8"))
    except (OSError, ValueError): return None

def codeql(path):
    stats = {"error": 0, "warning": 0, "note": 0}; langs = []
    for f in glob.glob(os.path.join(path, "*.sarif")) if os.path.isdir(path) else [path]:
        s = load(f) or {}
        for run in s.get("runs", []):
            rules = {r["id"]: r for r in run.get("tool", {}).get("driver", {}).get("rules", [])}
            langs.append(os.path.basename(f).replace(".sarif", ""))
            for res in run.get("results", []):
                lvl = res.get("level") or rules.get(res.get("ruleId"), {}).get("defaultConfiguration", {}).get("level", "warning")
                stats[lvl if lvl in stats else "note"] += 1
    st = "fail" if stats["error"] else ("warn" if stats["warning"] else "pass")
    return st, stats, f"{stats['error']} errors, {stats['warning']} warnings, {stats['note']} notes ({', '.join(langs) or 'no SARIF'})"

def gitleaks(path):
    f = load(path) or []
    stats = {"findings": len(f), "rules": len({x.get("RuleID") for x in f})}
    return ("fail" if f else "pass"), stats, (f"{len(f)} possible secrets found" if f else "no secrets found")

def osv(path):
    r = load(path) or {}; vulns = 0; high = 0; pkgs = 0
    for res in r.get("results", []):
        for p in res.get("packages", []):
            if p.get("vulnerabilities"): pkgs += 1
            vulns += len(p.get("vulnerabilities", []))
            for g in p.get("groups", []):
                try:
                    if float(g.get("max_severity") or 0) >= 7.0: high += 1
                except ValueError: pass
    stats = {"vulnerabilities": vulns, "high_or_critical": high, "affected_packages": pkgs}
    return ("fail" if high else ("warn" if vulns else "pass")), stats, f"{vulns} known vulnerabilities in {pkgs} packages ({high} high/critical)"

def scorecard(path):
    r = load(path) or {}; score = r.get("score", -1)
    checks = {c["name"]: c.get("score", -1) for c in r.get("checks", [])}
    return ("pass" if score >= 7 else "warn"), {"score": score, "checks": checks}, f"OpenSSF Scorecard {score}/10"

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("tool", choices=["codeql", "gitleaks", "osv", "scorecard"]); ap.add_argument("path")
    ap.add_argument("--out", required=True); ap.add_argument("--report-url", required=True); a = ap.parse_args()
    st, stats, summary = globals()[a.tool](a.path)
    e = {"asset": "source code", "sha256": "", "status": st, "stats": stats, "summary": summary, "analysis_date": now(), "report_url": a.report_url, "note": ""}
    json.dump({"scan": a.tool, "entries": [e]}, open(a.out, "w"), indent=1); print(a.tool, st, summary)

if __name__ == "__main__":
    main()
