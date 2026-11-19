#!/usr/bin/env python3
"""Big "download" image buttons for the README and docs site, in a light and a dark version.

Reusable template: one call per platform. Today only the Android one is used:
  make_button.py --label "DOWNLOAD PREMADE APK HERE" --sub "Android · arm64-v8a · free, no account" \
                 --platform android --out ../repo_files/docs/images/buttons/download-apk
Planned (do NOT add until the Windows build ships; keep "exe" out of the label):
  make_button.py --label "DOWNLOAD PREMADE WINDOWS PROGRAM HERE" --sub "Windows 10/11 · 64-bit · free, no account" \
                 --platform windows --out ../repo_files/docs/images/buttons/download-windows
The VirusTotal button that sits next to it:
  make_button.py --label "CHECK IT ON VIRUSTOTAL" --sub "the public scan report for this exact APK" --platform verify --out .../verify-virustotal
Writes <out>.png (for light pages) and <out>-dark.png (for dark pages), transparent around the button, 2x pixel density.
Rendered with headless Chrome from button.html; fonts: Inter (bundled with most Linux font sets, else any sans-serif).
"""
import argparse, html, os, subprocess, tempfile
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
PLATFORMS = {  # accent gradient (top, bottom), edge colour, icon
    "android": dict(top="#3ddc84", bottom="#169a4f", edge="#0d6b35", glow="#3ddc84", icon="phone"),
    "windows": dict(top="#4cc2ff", bottom="#0067c0", edge="#004a8a", glow="#4cc2ff", icon="desktop"),
    "verify":  dict(top="#5b6cff", bottom="#2f3bbd", edge="#1f2785", glow="#7c8aff", icon="shield"),   # the VirusTotal button
}
ICONS = {
    "phone": '<rect x="17" y="6" width="30" height="52" rx="6" fill="none" stroke="currentColor" stroke-width="4.5"/><circle cx="32" cy="50" r="2.8" fill="currentColor"/>',
    "shield": '<path d="M32 5 9 13v17c0 14 10 24 23 29 13-5 23-15 23-29V13Z" fill="none" stroke="currentColor" stroke-width="4.5" stroke-linejoin="round"/>',
    "desktop": '<rect x="6" y="9" width="52" height="34" rx="4" fill="none" stroke="currentColor" stroke-width="4.5"/><path d="M24 55h16M32 43v12" stroke="currentColor" stroke-width="4.5" stroke-linecap="round"/>',
}

ARROW = '<path d="M32 17v20m-9-8 9 9 9-9" fill="none" stroke="currentColor" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round"/>'
CHECK = '<path d="m21 31 8 8 14-15" fill="none" stroke="currentColor" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>'

def render(label, sub, platform, dark, out_png):
    c = PLATFORMS[platform]
    tpl = open(os.path.join(HERE, "button.html"), encoding="utf-8").read()
    page = (tpl.replace("{{LABEL}}", html.escape(label)).replace("{{SUB}}", html.escape(sub)).replace("{{ICON}}", ICONS[c["icon"]])
            .replace("{{GLYPH}}", CHECK if c["icon"] == "shield" else ARROW)
            .replace("{{TOP}}", c["top"]).replace("{{BOTTOM}}", c["bottom"]).replace("{{EDGE}}", c["edge"]).replace("{{GLOW}}", c["glow"])
            .replace("{{THEME}}", "dark" if dark else "light"))
    with tempfile.TemporaryDirectory() as td:
        f = os.path.join(td, "b.html"); open(f, "w", encoding="utf-8").write(page)
        raw = os.path.join(td, "raw.png")
        subprocess.run(["google-chrome", "--headless=new", "--no-sandbox", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=2",
                        "--default-background-color=00000000", "--window-size=2000,360", "--virtual-time-budget=3000",
                        f"--screenshot={raw}", "file://" + f], capture_output=True, timeout=120, check=True)
        im = Image.open(raw).convert("RGBA")
        bb = im.getchannel("A").point(lambda a: 255 if a > 4 else 0).getbbox()
        im = im.crop((max(0, bb[0] - 6), max(0, bb[1] - 6), min(im.width, bb[2] + 6), min(im.height, bb[3] + 6)))
        if im.width > 1800: im = im.resize((1800, round(im.height * 1800 / im.width)), Image.LANCZOS)
        im.save(out_png, optimize=True)   # PIL writes no text/EXIF chunks
        return im.size

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", required=True); ap.add_argument("--sub", default="")
    ap.add_argument("--platform", choices=PLATFORMS, required=True); ap.add_argument("--out", required=True)
    a = ap.parse_args()
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    for dark in (False, True):
        p = a.out + ("-dark" if dark else "") + ".png"
        print(p, render(a.label.upper(), a.sub, a.platform, dark, p))
