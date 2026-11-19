// Security & verification panel: reads security/index.json (written by the workflows on gh-pages) live.
(async function () {
  const $ = id => document.getElementById(id);
  const REPO = "Milkymoomoo/Fully-Local-Agentic-Assistant";
  const NAMES = { virustotal: "VirusTotal", codeql: "CodeQL", gitleaks: "gitleaks", osv: "OSV-Scanner", scorecard: "OpenSSF Scorecard" };
  const ORDER = ["virustotal", "codeql", "gitleaks", "osv", "scorecard"];
  const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const day = iso => iso ? new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" }) : "–";
  const vtUrl = sha => "https://www.virustotal.com/gui/file/" + sha;

  // sidebar, shared with the docs viewer
  try {
    const nav = await (await fetch("nav.json")).json();
    $("side").innerHTML = nav.map(g => `<h4>${g.group}</h4>` + g.pages.map(([p, t]) =>
      `<a href="${p.endsWith(".html") ? p : "./#/" + p}" class="${p === "security.html" ? "on" : ""}">${t}</a>`).join("")).join("");
  } catch (e) { /* the page still works without it */ }

  // OS verify commands
  const CMDS = {
    win: ["PowerShell", 'Get-FileHash .\\MilkyMoosAssistant-arm64-v8a.apk -Algorithm SHA256', "or in Command Prompt:", "certutil -hashfile MilkyMoosAssistant-arm64-v8a.apk SHA256"],
    mac: ["Terminal", "shasum -a 256 MilkyMoosAssistant-arm64-v8a.apk", "check against the release's list (prints OK):", "shasum -a 256 -c SHA256SUMS.txt --ignore-missing"],
    linux: ["Terminal", "sha256sum MilkyMoosAssistant-arm64-v8a.apk", "check against the release's list (prints OK):", "sha256sum -c SHA256SUMS.txt --ignore-missing"],
    android: ["Termux, or adb from a computer", "sha256sum /sdcard/Download/MilkyMoosAssistant-arm64-v8a.apk", "from a computer:", "adb shell sha256sum /sdcard/Download/MilkyMoosAssistant-arm64-v8a.apk"],
  };
  function os(k) {
    const c = CMDS[k];
    $("osbox").innerHTML = `<p class="small muted">${c[0]}</p><pre><code>${esc(c[1])}</code></pre><p class="small muted">${c[2]}</p><pre><code>${esc(c[3])}</code></pre>`;
    document.querySelectorAll("#ostabs button").forEach(b => b.classList.toggle("on", b.dataset.os === k));
  }
  document.querySelectorAll("#ostabs button").forEach(b => b.onclick = () => os(b.dataset.os));
  const ua = navigator.userAgent; os(/Windows/.test(ua) ? "win" : /Android/.test(ua) ? "android" : /Mac/.test(ua) ? "mac" : "linux");

  let idx;
  try {
    const r = await fetch("security/index.json", { cache: "no-store" });
    if (!r.ok) throw new Error(r.status);
    idx = await r.json();
  } catch (e) {
    $("state").innerHTML = `<b>No results published yet.</b> The first scans appear here after the first release and the first push with the
      security workflows enabled. Until then, check the files yourself with the commands below, or upload one to
      <a href="https://www.virustotal.com/gui/home/upload">VirusTotal</a>.`;
    $("vtable").innerHTML = '<p class="muted">No versions recorded yet.</p>'; return;
  }
  const E = idx.entries || [], P = idx.pending || [];
  $("updated").textContent = idx.updated ? "· updated " + day(idx.updated) : "";
  const vtE = E.filter(e => e.scan === "virustotal");
  const isModels = v => String(v || "").startsWith("models-");
  const vLabel = v => isModels(v) ? String(v) : "v" + v;
  const newestVer = (vtE.find(e => !isModels(e.version)) || vtE[0] || E[0] || {}).version;

  function card(e, pending, title) {
    const st = pending ? "pending" : e ? e.status : "none";
    const label = pending ? (e ? "pending" : "queued") : { pass: "pass", warn: "warning", fail: "fail", toolarge: "too large", none: "no result" }[st];
    const big = !e ? "–" : e.status === "toolarge" ? "&gt; 650 MB" : e.scan === "virustotal" ? `${e.stats.malicious ?? 0} / ${["malicious", "suspicious", "undetected", "harmless"].reduce((a, k) => a + (e.stats[k] || 0), 0)}`
      : e.scan === "scorecard" ? `${e.stats.score}/10` : e.scan === "gitleaks" ? `${e.stats.findings}` : e.scan === "osv" ? `${e.stats.vulnerabilities}` : `${e.stats.error ?? 0}·${e.stats.warning ?? 0}`;
    const unit = !e ? "" : { virustotal: "engines flag it", scorecard: "score", gitleaks: "possible secrets", osv: "known vulnerabilities", codeql: "errors · warnings" }[e.scan];
    const pend = pending ? `<div class="small"><b>Pending</b> (${esc(pending.reason || "queued")}), ${e ? "last result from " + day(e.analysis_date || e.date) : "no earlier result for this file yet"}.</div>` : "";
    const sha = (e && e.sha256) || (pending && pending.sha256);
    return `<div class="card"><h3>${esc(title)}<span class="pill ${st}">${label}</span></h3>
      <div><span class="big">${big}</span> <span class="muted small">${unit}</span></div>
      ${pend}<div class="small muted">${e ? esc(e.summary) : ""}</div>
      <div class="small">Checked ${e ? day(e.analysis_date || e.date) : "–"}${e ? " · v" + esc(e.version) + " · " + esc((e.commit || "").slice(0, 7)) : ""}</div>
      ${sha ? `<div class="sha">sha256 ${esc(sha)}</div>` : ""}
      <div class="acts">${e && e.report_url ? `<a class="btn primary" href="${esc(e.report_url)}" target="_blank" rel="noopener">Official report</a>` : ""}
        ${sha && (!e || e.scan === "virustotal") ? `<a class="btn" href="${vtUrl(sha)}" target="_blank" rel="noopener" title="Opens VirusTotal; press Reanalyze there">Rescan on VirusTotal</a>` : ""}
        ${e && e.file ? `<a class="btn" href="security/${esc(e.file)}">JSON</a>` : ""}</div></div>`;
  }

  // latest cards: the newest version's release files on VirusTotal, then the newest result of each source scan
  const cards = [];
  const assets = new Map();
  for (const e of vtE.filter(e => e.version === newestVer)) if (!assets.has(e.asset)) assets.set(e.asset, e);
  for (const p of P.filter(p => p.version === newestVer && ![...assets.values()].some(e => e.sha256 === p.sha256))) assets.set(p.asset, null);
  for (const [asset, e] of assets) {
    const pending = P.find(p => p.sha256 === (e ? e.sha256 : P.find(q => q.asset === asset)?.sha256));
    const prev = e || vtE.find(x => x.asset === asset);
    cards.push(card(prev, pending, "VirusTotal · " + asset));
  }
  for (const s of ORDER.slice(1)) { const e = E.find(x => x.scan === s); if (e) cards.push(card(e, null, NAMES[s])); }
  $("cards").innerHTML = cards.join("") || "";
  const worst = [...assets.values()].filter(Boolean).map(e => e.status);
  $("state").innerHTML = assets.size ? `<b>${esc(vLabel(newestVer))}:</b> ${worst.includes("fail") ? "at least one engine flags a release file: open its report" : worst.includes("warn") ? "no engine calls it malicious; some mark it suspicious" : "no antivirus engine flags the release files"}${P.length ? ` · ${P.length} scan(s) pending` : ""}.`
    : "No release files recorded yet; source scans are below.";
  // the 3D model set (models-* releases): one summary line, details in the version history below
  const mv = vtE.find(e => isModels(e.version))?.version || P.find(p => isModels(p.version))?.version;
  if (mv) {
    const m = new Map(); for (const e of vtE.filter(e => e.version === mv)) if (!m.has(e.asset)) m.set(e.asset, e);
    const mp = P.filter(p => p.version === mv && ![...m.values()].some(e => e.sha256 === p.sha256 && e.status !== "toolarge"));
    const ok = [...m.values()].filter(e => e.status === "pass").length, fl = [...m.values()].filter(e => e.status === "warn" || e.status === "fail").length;
    const big = [...m.values()].filter(e => e.status === "toolarge");
    $("state").innerHTML += `<br><b>3D model set (${esc(mv)}):</b> ${ok} file(s) clean${fl ? `, ${fl} flagged (open the report)` : ""}${mp.length ? `, ${mp.length} scan(s) pending` : ""}` +
      (big.length ? `, ${big.length} too large for VirusTotal (650 MB limit): ${big.map(e => esc(e.asset)).join(", ")}` : "") +
      ` · <a href="#v${esc(mv)}" onclick="setTimeout(()=>location.reload())">details and SHA-256s</a>.`;
  }
  const firstVt = [...assets.values()].find(Boolean);
  if (firstVt) $("vt-hero").href = vtUrl(firstVt.sha256);

  // version history
  const versions = Object.keys(idx.versions || {}).sort((a, b) => (isModels(a) - isModels(b)) || (idx.versions[b].first_seen || "").localeCompare(idx.versions[a].first_seen || ""));   // app versions first, newest first
  $("ver").innerHTML = versions.map(v => `<option value="${esc(v)}">${esc(vLabel(v))}</option>`).join("");
  function pick(v) {
    $("ver").value = v; const info = idx.versions[v] || {};
    $("rel").href = info.release_url || `https://github.com/${REPO}/releases`;
    const rows = E.filter(e => e.version === v);
    $("vtable").innerHTML = rows.length ? `<table><tr><th>Date</th><th>Scan</th><th>File</th><th>Result</th><th>Details</th><th>Report</th></tr>` +
      rows.map(e => `<tr><td>${day(e.date)}</td><td>${NAMES[e.scan] || esc(e.scan)}</td><td>${esc(e.asset)}${e.sha256 ? `<div class="sha">${esc(e.sha256)}</div>` : ""}</td>
        <td><span class="pill ${e.status}">${esc(e.status)}</span></td><td class="small">${esc(e.summary)}${e.note ? " · " + esc(e.note) : ""}</td>
        <td>${e.report_url ? `<a href="${esc(e.report_url)}" target="_blank" rel="noopener">report</a> · ` : ""}<a href="security/${esc(e.file)}">json</a></td></tr>`).join("") + "</table>"
      : '<p class="muted">No scans recorded for this version.</p>';
  }
  $("ver").onchange = () => { history.replaceState(null, "", "#v" + $("ver").value); pick($("ver").value); };
  const want = decodeURIComponent(location.hash.replace(/^#v/, ""));
  if (versions.length) pick(versions.includes(want) ? want : versions[0]); else $("vtable").innerHTML = '<p class="muted">No versions recorded yet.</p>';
  if (versions.includes(want)) $("history").scrollIntoView();
})();
