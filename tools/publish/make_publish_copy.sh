#!/usr/bin/env bash
# Builds the public copy of this repo in a fresh directory with ONE squashed commit (no dev history), for
# https://github.com/Milkymoomoo/Fully-Local-Agentic-Assistant. Never pushes.
#   tools/publish/make_publish_copy.sh [OUT=/workspace/publish/repo]
# Source = the committed tree at HEAD (uncommitted work is not included), minus dev-only paths, plus tools/publish/repo_files/.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="${1:-/workspace/publish/repo}"
[ -z "$(git status --porcelain)" ] || echo "note: uncommitted changes are NOT included"
rm -rf "$OUT"; mkdir -p "$OUT"
git archive --format=tar HEAD | tar -x -C "$OUT"
cd "$OUT"
# dev-only material: proof screenshots/logs, local release output, private test, internal notes, prebuilt test binaries
rm -rf proof dist tools/publish_NOTES.md tools/feasibility/spconv/spconv_android_x86_64 tools/feasibility/spconv/spconv_arm64 tools/feasibility/spconv/spconv_x86
rm -f app/src/test/java/app/belle/assistant/book/UserBookTest.kt
rm -rf tools/publish/repo_files/.git
cp -a tools/publish/repo_files/. .
# the real-device screenshot (user-supplied; metadata stripped before it is put in repo_files) must be there
[ -f docs/screenshots/phone_widget_home.png ] || { echo "missing docs/screenshots/phone_widget_home.png (put the stripped PNG in tools/publish/repo_files/docs/screenshots/)"; exit 1; }
rm -rf tools/publish/repo_files
# images with a -dark.png twin follow the reader's GitHub theme (<picture> + prefers-color-scheme)
python3 tools/publish/picturize.py .
# documentation (docs/README.md + docs/features + images): every local link and image must resolve
[ -f docs/README.md ] || { echo "missing docs/README.md"; exit 1; }
python3 - <<'PY'
import re, os, glob, sys
bad = []
for f in ["README.md", "AGENTS.md", "CLAUDE.md", ".github/copilot-instructions.md"] + glob.glob("docs/**/*.md", recursive=True):
    s = open(f, encoding="utf-8").read(); d = os.path.dirname(f)
    for m in re.findall(r'(?:src="|href="|\]\()([^")#\s]+)', s):
        if m.startswith(("http:", "https:", "mailto:")): continue
        if not os.path.exists(os.path.normpath(os.path.join(d, m))): bad.append(f"{f} -> {m}")
if bad: print("broken doc links:\n  " + "\n  ".join(bad)); sys.exit(1)
print(f"docs: links ok ({len(glob.glob('docs/**/*.md', recursive=True))} pages, {len(glob.glob('docs/**/*.png', recursive=True))} images)")
PY
# AGENTS.md and the docs must cover every source file, settings page, feature page and settings key
python3 tools/publish/check_agents.py
git init -q -b main
git remote add origin https://github.com/Milkymoomoo/Fully-Local-Agentic-Assistant.git
if git fetch -q origin main 2>/dev/null; then git reset -q --soft FETCH_HEAD; fi   # on top of the public placeholder commit (read-only fetch)
git -c user.name="Milkymoomoo" -c user.email="154686492+Milkymoomoo@users.noreply.github.com" add -A
git -c user.name="Milkymoomoo" -c user.email="154686492+Milkymoomoo@users.noreply.github.com" commit -q -m "Fully Local Agentic Assistant 0.1.5 (source)"
echo "publish copy: $OUT ($(git ls-files | wc -l) files, $(du -sh .git | cut -f1) .git) — not pushed"
