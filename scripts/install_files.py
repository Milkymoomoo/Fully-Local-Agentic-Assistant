#!/usr/bin/env python3
"""Fill the "Files" block of INSTALL.txt with the real APK names and sizes of a release.
   install_files.py <INSTALL.txt template> <dir with the APKs> <out INSTALL.txt>
The block runs from the line "Files" to the line before "Requirements:"; everything else is copied unchanged."""
import os, re, sys

tpl, apk_dir, out = sys.argv[1:4]
what = {"arm64-v8a": "use this one on any modern Android phone (64-bit ARM)",
        "universal": "arm64 + x86_64 in one file (if unsure)",
        "x86_64": "emulators / Chromebooks / x86 tablets"}
apks = sorted(f for f in os.listdir(apk_dir) if re.match(r"MilkyMoosAssistant-.+-(arm64-v8a|universal|x86_64)\.apk$", f))
if len(apks) != 3: sys.exit(f"expected 3 APKs in {apk_dir}, found {apks}")
order = ["arm64-v8a", "universal", "x86_64"]
abi_of = lambda f: next(a for a in order if f.endswith(a + ".apk"))
apks.sort(key=lambda f: order.index(abi_of(f)))
w = max(map(len, apks))
rows = []
for f in apks:
    abi = abi_of(f)
    mb = os.path.getsize(os.path.join(apk_dir, f)) / 1e6
    rows.append(f"  {f:<{w}}  {mb:5.1f} MB   {what[abi]}")
lines = open(tpl, encoding="utf-8").read().split("\n")
a = lines.index("Files"); b = next(i for i, l in enumerate(lines) if l.startswith("Requirements:"))
rows.append("  The same files are also published as MilkyMoosAssistant-<abi>.apk (no version in the name), so the")
rows.append("  .../releases/latest/download/MilkyMoosAssistant-arm64-v8a.apk link always gets the newest one.")
new = lines[:a + 1] + rows + [""] + lines[b:]
open(out, "w", encoding="utf-8").write("\n".join(new))
print("\n".join(rows))
