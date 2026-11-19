// Docs viewer: renders the repository's Markdown (README.md, docs/**) live, so the site never drifts from the docs.
(async function () {
  const side = document.getElementById("side"), doc = document.getElementById("doc"), pager = document.getElementById("pager");
  const nav = await (await fetch("nav.json")).json();
  const flat = nav.flatMap(g => g.pages);
  side.innerHTML = nav.map(g => `<h4>${g.group}</h4>` + g.pages.map(([p, t]) =>
    `<a href="${p.endsWith(".html") ? p : "#/" + p}" data-p="${p}">${t}</a>`).join("")).join("");
  marked.setOptions({ gfm: true });
  const dir = p => p.includes("/") ? p.slice(0, p.lastIndexOf("/") + 1) : "";
  function norm(path) { const out = []; for (const s of path.split("/")) { if (s === "..") out.pop(); else if (s && s !== ".") out.push(s); } return out.join("/"); }
  const DL = `<p class="hero-dl">
    <a href="https://github.com/Milkymoomoo/Fully-Local-Agentic-Assistant/releases/latest/download/MilkyMoosAssistant-arm64-v8a.apk"><picture><source media="(prefers-color-scheme: dark)" srcset="docs/images/buttons/download-apk-dark.png"><img alt="DOWNLOAD PREMADE APK HERE" src="docs/images/buttons/download-apk.png"></picture></a>
    <a class="vt" href="security.html"><picture><source media="(prefers-color-scheme: dark)" srcset="docs/images/buttons/verify-virustotal-dark.png"><img alt="CHECK IT ON VIRUSTOTAL" src="docs/images/buttons/verify-virustotal.png"></picture></a></p>`;
  async function show() {
    let [p, anchor] = decodeURIComponent(location.hash.replace(/^#\/?/, "")).split("#");
    if (!p || !flat.some(([q]) => q === p)) p = p && p.endsWith(".md") ? p : "docs/README.md";
    let text;
    try { const r = await fetch(p); if (!r.ok) throw r.status; text = await r.text(); }
    catch (e) { doc.innerHTML = `<h1>Not found</h1><p>${p} could not be loaded.</p>`; return; }
    text = text.replace(/<!-- nav -->[\s\S]*?<!-- \/nav -->/g, "");          // the site has its own navigation
    doc.innerHTML = (p === "README.md" || p === "docs/README.md" ? DL : "") + marked.parse(text);
    const base = dir(p);
    doc.querySelectorAll("img,source").forEach(el => {
      for (const a of ["src", "srcset"]) { const v = el.getAttribute(a); if (v && !/^(https?:|data:|\/)/.test(v) && !el.closest(".hero-dl")) el.setAttribute(a, norm(base + v)); }
    });
    doc.querySelectorAll("a[href]").forEach(a => {
      const h = a.getAttribute("href"); if (/^(https?:|mailto:|#)/.test(h)) { if (h.startsWith("#")) a.setAttribute("href", "#/" + p + h); return; }
      const [f, frag] = h.split("#"); const t = norm(base + f);
      if (t.endsWith(".md")) a.setAttribute("href", "#/" + t + (frag ? "#" + frag : ""));
      else if (t.endsWith(".html")) a.setAttribute("href", t);
      else a.setAttribute("href", "https://github.com/Milkymoomoo/Fully-Local-Agentic-Assistant/blob/main/" + t);
    });
    doc.querySelectorAll("h1,h2,h3").forEach(h => { h.id = h.textContent.trim().toLowerCase().replace(/[^\w\- ]+/g, "").replace(/\s+/g, "-"); });
    window.mmTheme.apply();
    side.querySelectorAll("a").forEach(a => a.classList.toggle("on", a.dataset.p === p));
    const i = flat.findIndex(([q]) => q === p), prev = flat[i - 1], next = flat[i + 1];
    const link = ([q, t]) => q.endsWith(".html") ? q : "#/" + q;
    pager.innerHTML = (prev ? `<a href="${link(prev)}"><small>← Previous</small>${prev[1]}</a>` : "<span></span>") +
                      (next ? `<a class="n" href="${link(next)}"><small>Next →</small>${next[1]}</a>` : "<span></span>");
    document.title = (doc.querySelector("h1")?.textContent || "Docs") + " · Milky Moo's Assistant";
    if (anchor) document.getElementById(anchor)?.scrollIntoView(); else scrollTo(0, 0);
  }
  addEventListener("hashchange", show); show();
})();
