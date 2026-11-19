#!/usr/bin/env python3
"""
Builds the 2D-widget proof video from what the app's widget pipeline actually produced during a real run:
  - shots/<epochMs>.png       real emulator screenshots taken during the run (background, with the widget's real text/state)
  - frames/<epochMs>_<UI>.jpg  every avatar bitmap the widget Animator pushed to RemoteViews (debug FRAMEDUMP)
  - audio/<epochMs>_mic.wav    the injected microphone audio ("hey bob" / "open the clock")
  - audio/<epochMs>.wav        every TTS buffer the app played (debug AUDIODUMP)
  - events.txt                 "<epochMs> <text>" lines (wake, heard, reply ...) for captions
Avatar frames are pasted at the widget's avatar position (box = x,y,w,h in screen px) over the latest real screenshot.
Long idle stretches where the slow emulator was only loading/processing are shortened and labelled on screen.
"""
import argparse, glob, os, subprocess, wave, json
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ap = argparse.ArgumentParser()
ap.add_argument('--run', required=True); ap.add_argument('--box', required=True)   # x,y,w,h of w_avatar
ap.add_argument('--out', required=True); ap.add_argument('--fps', type=int, default=15)
ap.add_argument('--end', default='')     # final real screenshot (app opened)
ap.add_argument('--max-gap', type=float, default=2.0)
a = ap.parse_args()
R = a.run; bx, by, bw, bh = map(int, a.box.split(','))
shots = sorted((int(os.path.basename(f)[:-4]), f) for f in glob.glob(f'{R}/shots/*.png'))
frames = sorted((int(os.path.basename(f).split('_')[0]), f) for f in glob.glob(f'{R}/frames/*.jpg'))
auds = []
for f in sorted(glob.glob(f'{R}/audio/*.wav')):
    b = os.path.basename(f)[:-4]; mic = b.endswith('_mic'); t = int(b.split('_')[0])
    with wave.open(f) as w: sr = w.getframerate(); x = np.frombuffer(w.readframes(w.getnframes()), '<i2').astype(np.float32) / 32768
    auds.append((t, sr, x, mic))
caps = []   # (t0, t1, text)
if os.path.exists(f'{R}/captions.json'): caps = json.load(open(f'{R}/captions.json'))
t0 = min([s[0] for s in shots] + [f[0] for f in frames]); t1 = max([s[0] for s in shots] + [f[0] for f in frames])
# "busy" intervals that must play in real time: audio playback + 1.5 s around it
busy = [(t - 800, t + int(len(x) / sr * 1000) + 800) for t, sr, x, _ in auds]
def is_busy(t): return any(s <= t <= e for s, e in busy)
# timeline map: output time -> source time, compressing idle stretches longer than max-gap seconds
step = 1000 / a.fps; src = []; t = t0; skipped = []
while t <= t1:
    src.append(t)
    if not is_busy(t):
        # look ahead: how long until the next busy moment?
        nb = min([s for s, e in busy if s > t] + [t1])
        if nb - t > a.max_gap * 1000 * 2 and t - t0 > 2500:   # keep the first 2.5 s of idle as is
            jump = nb - t - a.max_gap * 1000; skipped.append((len(src), jump)); t += jump; continue
    t += step
def latest(lst, t):
    lo, hi, best = 0, len(lst) - 1, None
    while lo <= hi:
        m = (lo + hi) // 2
        if lst[m][0] <= t: best = lst[m]; lo = m + 1
        else: hi = m - 1
    return best or lst[0]
os.makedirs(f'{R}/out', exist_ok=True)
for f in glob.glob(f'{R}/out/*.png'): os.remove(f)
try: font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 40); small = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 28)
except Exception: font = small = ImageFont.load_default()
cache = {}
def img(p):
    if p not in cache:
        if len(cache) > 40: cache.clear()
        cache[p] = Image.open(p).convert('RGB')
    return cache[p]
skip_at = dict(skipped); skip_note = None
for i, t in enumerate(src):
    base = img(latest(shots, t)[1]).copy()
    if frames:
        fr = img(latest(frames, t)[1]); fw, fh = fr.size; k = min(bw / fw, bh / fh); nw, nh = int(fw * k), int(fh * k)
        # the widget shows the frame fitCenter inside the avatar box; paste over the (older) screenshot pixels there
        bg = base.crop((bx, by, bx + bw, by + bh)).resize((1, 1)).getpixel((0, 0))
        patch = Image.new('RGB', (bw, bh), bg); patch.paste(fr.resize((nw, nh), Image.BILINEAR), ((bw - nw) // 2, (bh - nh) // 2))
        base.paste(patch, (bx, by))
    d = ImageDraw.Draw(base)
    if i in skip_at: skip_note = (i, skip_at[i])
    if skip_note and i - skip_note[0] < a.fps * 1.2:
        d.rounded_rectangle((40, 1880, 1040, 1950), 18, fill=(0, 0, 0))
        d.text((540, 1915), f'… {skip_note[1] / 1000:.0f} s of emulator loading skipped …', font=small, fill=(255, 220, 120), anchor='mm')
    for c0, c1, text in caps:
        if c0 <= t <= c1:
            d.rounded_rectangle((40, 1700, 1040, 1860), 24, fill=(20, 12, 28))
            d.multiline_text((540, 1780), text, font=font, fill=(255, 255, 255), anchor='mm', align='center')
    d.text((540, 2235), 'Rendered frames from the app\'s widget pipeline over real emulator screenshots', font=small, fill=(255, 255, 255), anchor='mm', stroke_width=2, stroke_fill=(0, 0, 0))
    base.resize((540, 1140)).save(f'{R}/out/{i:05d}.png')
n = len(src); dur = n / a.fps
end_frames = 0
if a.end:
    e = Image.open(a.end).convert('RGB'); d = ImageDraw.Draw(e)
    d.rounded_rectangle((40, 1700, 1040, 1860), 24, fill=(20, 12, 28)); d.text((540, 1780), 'The Clock app opened', font=font, fill=(255, 255, 255), anchor='mm')
    e = e.resize((540, 1140))
    for j in range(a.fps * 3): e.save(f'{R}/out/{n + j:05d}.png'); end_frames += 1
# audio on the same compressed timeline
sr = 22050; total = int((n + end_frames) / a.fps * sr) + sr; mix = np.zeros(total, np.float32)
def out_time(t):   # source ms -> output seconds (first output frame at/after t)
    import bisect; j = bisect.bisect_left(src, t); return j / a.fps + (t - src[min(j, n - 1)]) / 1000 if j < n else n / a.fps
for t, r, x, mic in auds:
    y = np.interp(np.arange(0, len(x), r / sr), np.arange(len(x)), x)
    s = int(out_time(t) * sr); mix[s:s + len(y)] += y[:max(0, total - s)] * (0.8 if mic else 1.0)
with wave.open(f'{R}/out/mix.wav', 'wb') as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes((np.clip(mix, -1, 1) * 32767).astype('<i2').tobytes())
subprocess.run(['ffmpeg', '-v', 'error', '-y', '-framerate', str(a.fps), '-i', f'{R}/out/%05d.png', '-i', f'{R}/out/mix.wav',
                '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '20', '-c:a', 'aac', '-b:a', '128k', '-shortest', a.out], check=True)
print(json.dumps({'frames': n + end_frames, 'seconds': round((n + end_frames) / a.fps, 1), 'skipped_ms': [s for _, s in skipped],
                  'widget_frames': len(frames), 'screens': len(shots), 'audio_clips': len(auds)}))
