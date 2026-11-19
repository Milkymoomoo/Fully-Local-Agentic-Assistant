#!/usr/bin/env python3
"""Generate every launcher-icon asset + the default avatar from one square image.
Usage: python3 scripts/make_icons.py path/to/icon-1024.png
  - mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png         legacy (rounded square)
  - mipmap-*/ic_launcher_round.png                      legacy round
  - mipmap-*/ic_launcher_foreground.png                 adaptive foreground (subject in the 66dp safe zone)
  - mipmap-*/ic_launcher_background.png                 adaptive background (image's own backdrop, softened)
  - mipmap-*/ic_launcher_monochrome.png                 Android 13+ themed icon (white silhouette)
  - mipmap-anydpi-v26/ic_launcher{,_round}.xml
  - drawable-nodpi/avatar_default.png                   default avatar (background removed when it is plain)
"""
import sys, os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

RES = os.environ.get("ICON_RES") or os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
DENS = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

import argparse
ap = argparse.ArgumentParser()
ap.add_argument("image")
ap.add_argument("--icon-crop", help="x0,y0,x1,y1 in source pixels: square region used for the launcher icon (busy backgrounds)")
ap.add_argument("--avatar-crop", help="x0,y0,x1,y1: square region used for the default avatar")
args = ap.parse_args()
orig = Image.open(args.image).convert("RGBA")
def crop_arg(v):
    return orig.crop(tuple(int(t) for t in v.split(","))) if v else None
icon_src = crop_arg(args.icon_crop)
avatar_src = crop_arg(args.avatar_crop)
src = orig
w, h = src.size
side = min(w, h)
src = src.crop(((w - side) // 2, (h - side) // 2, (w - side) // 2 + side, (h - side) // 2 + side)).resize((1024, 1024), Image.LANCZOS)
a = np.asarray(src).astype(np.int32)

# --- backdrop colour = median of the outer border ---
border = np.concatenate([a[:8].reshape(-1, 4), a[-8:].reshape(-1, 4), a[:, :8].reshape(-1, 4), a[:, -8:].reshape(-1, 4)])
bg = np.median(border[:, :3], axis=0).astype(int)
border_spread = np.median(np.abs(border[:, :3] - bg).sum(1))
print("backdrop", tuple(bg), "spread", border_spread)

# --- subject mask: flood from the edges over pixels close to the backdrop ---
already_transparent = (a[:, :, 3] < 250).mean() > 0.05
if already_transparent:
    mask = a[:, :, 3] > 24
else:
    dist = np.abs(a[:, :, :3] - bg).sum(2)
    tol = max(60, border_spread * 3)
    near = dist < tol
    from collections import deque
    seen = np.zeros_like(near)
    q = deque()
    for x in range(1024):
        for y in (0, 1023):
            if near[y, x] and not seen[y, x]: seen[y, x] = True; q.append((y, x))
    for y in range(1024):
        for x in (0, 1023):
            if near[y, x] and not seen[y, x]: seen[y, x] = True; q.append((y, x))
    while q:
        y, x = q.popleft()
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < 1024 and 0 <= nx < 1024 and near[ny, nx] and not seen[ny, nx]:
                seen[ny, nx] = True; q.append((ny, nx))
    mask = ~seen
plain_backdrop = (not already_transparent) and (~mask).mean() > 0.12 and border_spread < 40
print("subject coverage %.2f, plain backdrop: %s" % (mask.mean(), plain_backdrop))

mimg = Image.fromarray((mask * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.2))
cut = src.copy(); cut.putalpha(Image.fromarray(np.minimum(np.asarray(mimg), np.asarray(src)[:, :, 3])))
bbox = mimg.point(lambda v: 255 if v > 40 else 0).getbbox() or (0, 0, 1024, 1024)

def save(img, folder, name):
    d = os.path.join(RES, folder); os.makedirs(d, exist_ok=True)
    img.save(os.path.join(d, name), optimize=True)

def rounded(img, r):
    m = Image.new("L", img.size, 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, img.size[0] - 1, img.size[1] - 1), r, fill=255)
    out = img.copy(); out.putalpha(m); return out

def circle(img):
    m = Image.new("L", img.size, 0); ImageDraw.Draw(m).ellipse((0, 0, img.size[0] - 1, img.size[1] - 1), fill=255)
    out = img.copy(); out.putalpha(m); return out

def fit_subject(px, frac, image):
    """Subject's bounding box scaled to fit a centred square of frac*px, on a transparent px canvas."""
    sub = image.crop(bbox)
    s = frac * px / max(sub.size)
    sub = sub.resize((max(1, round(sub.size[0] * s)), max(1, round(sub.size[1] * s))), Image.LANCZOS)
    c = Image.new("RGBA", (px, px), (0, 0, 0, 0)); c.alpha_composite(sub, ((px - sub.size[0]) // 2, (px - sub.size[1]) // 2)); return c

def mono_layer(px):
    """Themed-icon silhouette (white + alpha). Plain backdrop: the subject mask. Busy backdrop: the character's
    distinctive colours (e.g. a red dragon's skin, horns, crest) inside the icon crop, cleaned up."""
    if plain_backdrop:
        return fit_subject(px, 56 / 108, Image.merge("RGBA", (*[Image.new("L", (1024, 1024), 255)] * 3, cut.getchannel("A"))))
    im = np.asarray(icon_img.convert("RGB")).astype(np.int32)
    r, g, b = im[..., 0], im[..., 1], im[..., 2]
    red = (r > 140) & (r > g * 1.7) & (r > b * 1.6)
    purple = (b > 110) & (r > 90) & (g < r * 0.75) & (g < b * 0.75)
    horn = (r > 170) & (g > 140) & (b > 80) & (b < 170) & (r - b > 50)
    m = Image.fromarray(((red | purple) * 255).astype(np.uint8)).filter(ImageFilter.MedianFilter(5))
    m = m.filter(ImageFilter.MaxFilter(21)).filter(ImageFilter.MinFilter(21))      # close eyes/scale gaps
    # keep only the largest blob (the character), drop stray matches in the background
    a = np.asarray(m) > 128
    from collections import deque
    lab = np.zeros(a.shape, np.int32); best, best_n, cur = 0, 0, 0
    for y0, x0 in zip(*np.nonzero(a[::8, ::8])):
        y0, x0 = y0 * 8, x0 * 8
        if lab[y0, x0] or not a[y0, x0]: continue
        cur += 1; n = 0; q = deque([(y0, x0)]); lab[y0, x0] = cur
        while q:
            y, x = q.popleft(); n += 1
            for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                ny, nx = y + dy, x + dx
                if 0 <= ny < 1024 and 0 <= nx < 1024 and a[ny, nx] and not lab[ny, nx]:
                    lab[ny, nx] = cur; q.append((ny, nx))
        if n > best_n: best, best_n = cur, n
    m = Image.fromarray(((lab == best) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(2))
    # keep it inside the visible/safe area
    safe = Image.new("L", (1024, 1024), 0); ImageDraw.Draw(safe).ellipse((200, 200, 824, 824), fill=255)
    m = Image.fromarray(np.minimum(np.asarray(m), np.asarray(safe)))
    white = Image.new("L", (1024, 1024), 255)
    return Image.merge("RGBA", (white, white, white, m)).resize((px, px), Image.LANCZOS)

# Adaptive layers are 108dp; launchers show the centre 72dp and guarantee the centre 66dp circle.
# The subject's box goes in ~62dp (fits the safe circle for typical tall/round subjects).
FG_FRAC = 62 / 108
bg_layer_full = src.resize((1024, 1024), Image.LANCZOS)
if plain_backdrop:
    bg_layer_full = Image.new("RGBA", (1024, 1024), tuple(bg) + (255,))
else:
    bg_layer_full = (icon_src or src).resize((1024, 1024), Image.LANCZOS)
icon_img = (icon_src or src).resize((1024, 1024), Image.LANCZOS)

for dname, k in DENS.items():
    folder = "mipmap-" + dname
    leg = round(48 * k)
    leg_src = icon_img.crop((171, 171, 853, 853)) if not plain_backdrop else src   # same framing as the adaptive icon
    save(rounded(leg_src.resize((leg, leg), Image.LANCZOS), leg * 0.18), folder, "ic_launcher.png")
    save(circle(leg_src.resize((leg, leg), Image.LANCZOS)), folder, "ic_launcher_round.png")
    ad = round(108 * k)
    if plain_backdrop:
        fg = fit_subject(ad, FG_FRAC, cut)
    else:
        # Busy backdrop: the (cropped) picture is the full-bleed background layer and the foreground is empty,
        # so every launcher mask shape shows the centre 2/3 of the crop with no hard badge edge.
        fg = Image.new("RGBA", (ad, ad), (0, 0, 0, 0))
    save(fg, folder, "ic_launcher_foreground.png")
    save(bg_layer_full.resize((ad, ad), Image.LANCZOS).convert("RGB"), folder, "ic_launcher_background.png")
    # Themed icon: white silhouette of the subject, system tints it.
    save(mono_layer(ad), folder, "ic_launcher_monochrome.png")

xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""
os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)
for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
    open(os.path.join(RES, "mipmap-anydpi-v26", n), "w").write(xml)

# Default avatar: subject with transparent background if the backdrop was plain, else a circle crop.
av = fit_subject(512, 0.96, cut) if plain_backdrop else circle((avatar_src or src).resize((512, 512), Image.LANCZOS))
save(av, "drawable-nodpi", "avatar_default.png")
# Preview sheet for checking by eye.
sheet = Image.new("RGBA", (5 * 220, 220), (235, 235, 235, 255))
k = 2
fg = Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_foreground.png")); bgp = Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_background.png")).convert("RGBA")
comp = bgp.copy(); comp.alpha_composite(fg)
vis = comp.crop((72, 72, 360, 360)).resize((200, 200))   # centre 72dp
sheet.alpha_composite(circle(vis), (10, 10))
sheet.alpha_composite(rounded(vis, 50), (230, 10))
mono = Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_monochrome.png")).crop((72, 72, 360, 360)).resize((200, 200))
dark = Image.new("RGBA", (200, 200), (40, 50, 70, 255)); dark.alpha_composite(mono); sheet.alpha_composite(circle(dark), (450, 10))
sheet.alpha_composite(Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.png")).resize((200, 200)), (670, 10))
sheet.alpha_composite(av.resize((200, 200)), (890, 10))
sheet.save("/tmp/icon_preview.png")
print("done")

# Wire up (only when writing into the real project): the PNG avatar replaces the vector placeholder,
# and the manifest points at the mipmap icons.
if not os.environ.get("ICON_RES"):
    old = os.path.join(RES, "drawable", "avatar_default.xml")
    if os.path.exists(old): os.remove(old)
    man = os.path.join(RES, "..", "AndroidManifest.xml")
    m = open(man).read()
    m = m.replace('android:icon="@drawable/ic_launcher"', 'android:icon="@mipmap/ic_launcher"\n        android:roundIcon="@mipmap/ic_launcher_round"')
    open(man, "w").write(m)
    print("manifest + avatar wired")
