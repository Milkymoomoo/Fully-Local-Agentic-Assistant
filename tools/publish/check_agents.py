#!/usr/bin/env python3
"""Fails (exit 1) when AGENTS.md or the docs miss something the app has. Run from the repository root (the publish
copy runs it automatically). Checks:
  1. every Kotlin source file of the app, shared and downloader modules is named in AGENTS.md
  2. every settings page id (ui/SettingsScreen.kt) is in AGENTS.md and in docs/features/app-basics.md
  3. every feature page docs/features/*.md is linked from AGENTS.md, docs/README.md and docs-site/nav.json
  4. every settings key in Prefs.kt is in docs/settings-reference.md
  5. every Kotlin file of the Windows program (desktop/src/main/kotlin) is named in AGENTS.md (Android API shims on
     the line of their package), and every key in DesktopPrefs.kt is documented in docs/features/windows.md
"""
import glob, json, os, re, sys

APP = "app/src/main/java/app/belle/assistant"
# literals in Prefs.kt that are not settings keys (values, or legacy keys only read once to migrate)
NOT_KEYS = {"bob": "default name fragment", "mature": "legacy key, migrated and removed (PersonaPrompt)"}

def read(p):
    with open(p, encoding="utf-8") as f: return f.read()

def main():
    agents = read("AGENTS.md"); errs = []
    # 1. source files
    srcs = sorted(glob.glob("app/src/main/java/**/*.kt", recursive=True) + glob.glob("shared/src/main/java/**/*.kt", recursive=True)
                  + glob.glob("downloader/src/main/java/**/*.kt", recursive=True))
    for f in srcs:
        name = os.path.basename(f)
        if not re.search(r"(?<![\w.])" + re.escape(name) + r"\b", agents) and name not in agents:
            errs.append(f"AGENTS.md doesn't mention source file {f}")
    # 2. settings pages
    pages = re.findall(r'^\s*Page\("([a-z0-9]+)"', read(f"{APP}/ui/SettingsScreen.kt"), re.M)
    basics = read("docs/features/app-basics.md")
    for p in pages:
        if f"`{p}`" not in agents: errs.append(f"AGENTS.md doesn't list settings page id `{p}`")
        if f"`{p}`" not in basics: errs.append(f"docs/features/app-basics.md doesn't list settings page id `{p}`")
    # 3. feature pages
    home = read("docs/README.md"); nav = json.dumps(json.load(open("docs-site/nav.json", encoding="utf-8")))
    for f in sorted(glob.glob("docs/features/*.md")):
        b = os.path.basename(f)
        if f"docs/features/{b}" not in agents: errs.append(f"AGENTS.md doesn't link feature page {f}")
        if f"features/{b}" not in home: errs.append(f"docs/README.md doesn't link feature page {f}")
        if f"docs/features/{b}" not in nav: errs.append(f"docs-site/nav.json doesn't list feature page {f}")
    # 4. settings keys
    ref = read("docs/settings-reference.md")
    keys = sorted(set(re.findall(r'"([a-z][a-z0-9_]*)"', read(f"{APP}/Prefs.kt"))) - set(NOT_KEYS))
    for k in keys:
        if not re.search(r"(?<![a-z0-9_])" + re.escape(k) + r"(?![a-z0-9])", ref): errs.append(f"docs/settings-reference.md doesn't document settings key {k}")
    # 5. the Windows program
    dsrcs = sorted(glob.glob("desktop/src/main/kotlin/**/*.kt", recursive=True))
    for f in dsrcs:
        name = os.path.basename(f)
        rel = f[len("desktop/src/main/kotlin/"):]
        if rel.split("/")[0] in ("android", "androidx", "com"):
            # Android API shims: named on the AGENTS.md line of their package (e.g. "`android/graphics/`: Bitmap, Canvas")
            pkg = os.path.dirname(rel) + "/"; stem = name[:-3]
            if not any(f"`{pkg}`" in line and re.search(r"(?<![\w.])" + re.escape(stem) + r"\b", line) for line in agents.splitlines()):
                errs.append(f"AGENTS.md doesn't list shim {stem} on the `{pkg}` line ({f})")
        elif name not in agents: errs.append(f"AGENTS.md doesn't mention desktop source file {f}")
    dp = "desktop/src/main/kotlin/app/belle/desktop/DesktopPrefs.kt"
    dkeys = sorted(set(re.findall(r'(?:getString|getInt|getBoolean|getLong|getFloat|int|bool|put\w*)\("([a-z][a-z0-9_]*)"', read(dp)))) if os.path.exists(dp) else []
    win = read("docs/features/windows.md") if os.path.exists("docs/features/windows.md") else ""
    if dsrcs and not win: errs.append("docs/features/windows.md is missing")
    for k in dkeys:
        if f"`{k}`" not in win: errs.append(f"docs/features/windows.md doesn't document desktop setting `{k}`")
    srcs += dsrcs
    if errs:
        print("AGENTS.md / docs coverage check FAILED:\n  " + "\n  ".join(errs)); sys.exit(1)
    print(f"AGENTS.md / docs coverage ok: {len(srcs)} source files, {len(pages)} settings pages, "
          f"{len(glob.glob('docs/features/*.md'))} feature pages, {len(keys)} settings keys")

if __name__ == "__main__":
    main()
