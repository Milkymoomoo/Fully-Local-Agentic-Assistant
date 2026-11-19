#!/usr/bin/env bash
# Assemble the static docs site into OUT (default docs-site/_site): the viewer + the repository's Markdown and images.
# Used by .github/workflows/docs-site.yml and for local previews:  docs-site/build.sh && python3 -m http.server -d docs-site/_site
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-docs-site/_site}"
rm -rf "$OUT"; mkdir -p "$OUT"
cp -a docs-site/index.html docs-site/security.html docs-site/nav.json docs-site/assets "$OUT/"
cp -a docs README.md AGENTS.md "$OUT/" 2>/dev/null || cp -a docs README.md "$OUT/"
touch "$OUT/.nojekyll"
echo "site: $OUT ($(du -sh "$OUT" | cut -f1))"
