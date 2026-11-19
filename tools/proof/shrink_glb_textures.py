#!/usr/bin/env python3
"""Re-encode every embedded GLB image to <= MAX px JPEG (base colour q88, others q80). Usage: in.glb out.glb [max]"""
import json, struct, sys, io
from PIL import Image
src, dst = sys.argv[1], sys.argv[2]; MAX = int(sys.argv[3]) if len(sys.argv) > 3 else 1024
b = open(src, 'rb').read()
jl = struct.unpack_from('<I', b, 12)[0]; j = json.loads(b[20:20 + jl]); off = 20 + jl
bl = struct.unpack_from('<I', b, off)[0]; bin_ = b[off + 8: off + 8 + bl]
base_imgs = set()
for m in j.get('materials', []):
    t = m.get('pbrMetallicRoughness', {}).get('baseColorTexture')
    if t is not None: base_imgs.add(j['textures'][t['index']]['source'])
chunks = []; views = j['bufferViews']; img_views = {im['bufferView']: i for i, im in enumerate(j.get('images', []))}
newbin = bytearray(); remap = {}
for vi, v in enumerate(views):
    data = bin_[v.get('byteOffset', 0): v.get('byteOffset', 0) + v['byteLength']]
    if vi in img_views:
        ii = img_views[vi]; im = Image.open(io.BytesIO(data)); im.load()
        im = im.convert('RGB'); s = min(1, MAX / max(im.size))
        if s < 1: im = im.resize((int(im.width * s), int(im.height * s)), Image.LANCZOS)
        o = io.BytesIO(); im.save(o, 'JPEG', quality=88 if ii in base_imgs else 80, optimize=True); data = o.getvalue()
        j['images'][ii]['mimeType'] = 'image/jpeg'
        print(f'image {ii}: {v["byteLength"]} -> {len(data)} bytes {im.size} {"base" if ii in base_imgs else "aux"}')
    while len(newbin) % 4: newbin.append(0)
    v['byteOffset'] = len(newbin); v['byteLength'] = len(data); newbin += data
while len(newbin) % 4: newbin.append(0)
j['buffers'][0]['byteLength'] = len(newbin)
js = json.dumps(j, separators=(',', ':')).encode()
while len(js) % 4: js += b' '
out = struct.pack('<III', 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(newbin)) + struct.pack('<I', len(js)) + b'JSON' + js + struct.pack('<I', len(newbin)) + b'BIN\x00' + newbin
open(dst, 'wb').write(out); print(dst, len(out))
