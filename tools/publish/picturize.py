#!/usr/bin/env python3
"""Make Markdown images follow the GitHub light/dark theme.
  picturize.py ROOT
For every <img src="X.png" ...> in ROOT/README.md and ROOT/docs/**/*.md whose X-dark.png exists next to it, wrap
it in <picture> with a prefers-color-scheme: dark <source>. Idempotent (images already in a <picture> are left alone).
Phone and emulator screenshots have no -dark twin, so they stay as they are."""
import glob, os, re, sys
root = sys.argv[1]; changed = 0
IMG = re.compile(r'(<picture>.*?</picture>)|(<img\s[^>]*?src="([^"]+?)\.png"[^>]*>)', re.S)
for f in [os.path.join(root, "README.md")] + glob.glob(os.path.join(root, "docs", "**", "*.md"), recursive=True):
    s = open(f, encoding="utf-8").read(); d = os.path.dirname(f); n = 0
    def rep(m):
        global changed
        nonlocal_n = 0
        if m.group(1): return m.group(1)
        tag, base = m.group(2), m.group(3)
        if base.startswith("http") or not os.path.exists(os.path.normpath(os.path.join(d, base + "-dark.png"))): return tag
        changed += 1
        return f'<picture><source media="(prefers-color-scheme: dark)" srcset="{base}-dark.png">{tag}</picture>'
    t = IMG.sub(rep, s)
    if t != s: open(f, "w", encoding="utf-8").write(t)
print(f"picturize: {changed} images now follow the theme")
