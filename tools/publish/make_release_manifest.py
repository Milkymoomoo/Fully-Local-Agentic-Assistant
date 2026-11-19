#!/usr/bin/env python3
"""Make app/src/main/assets/pixal3d_models.json (the in-app "Install 3D generation" manifest) and the list of
GitHub Release assets from a Pixal3D bundle (tools/pixal3d_port/make_bundle.py output).

  python3 tools/publish/make_release_manifest.py /workspace/port3d/bundle [--repo Milkymoomoo/Fully-Local-Agentic-Assistant] [--tag models-3d-v2]
          [--split-dir OUT]   # also write the split parts of any file over the asset limit into OUT

GitHub Release assets must be under 2 GiB each; anything bigger is split into PART_SIZE pieces (<name>.001, .002 …)
that the app joins and checks after download. Prints the asset list (name, bytes) to upload, in order."""
import argparse, hashlib, json, os, sys

LIMIT = 2 * 1024**3 - 1          # GitHub: each release asset < 2 GiB
PART_SIZE = 1_900_000_000

LABEL = {
    "pixal3d_ss_flow": "Pixal3D structure flow", "pixal3d_shape512_flow": "Pixal3D shape flow",
    "trellis2_struct_dec": "TRELLIS.2 structure decoder", "trellis2_shape_dec": "TRELLIS.2 shape decoder",
    "naf_q_r1": "NAF upsampler", "dinov3_l_nb8": "DINOv3 ViT-L image encoder", "moge2_l_3600_nb8": "MoGe-2 depth / camera",
}
LICENSE = {
    "pixal3d": "MIT (Tencent Pixal3D)", "trellis2": "MIT (Microsoft TRELLIS.2)", "naf": "Apache-2.0 (valeo.ai NAF)",
    "dinov3": "DINOv3 License (Meta)", "moge2": "MIT (Microsoft MoGe-2; DINOv2 backbone Apache-2.0)",
}

def sha(p, off=0, n=None):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        f.seek(off); left = os.path.getsize(p) - off if n is None else n
        while left > 0:
            b = f.read(min(1 << 24, left)); h.update(b); left -= len(b)
    return h.hexdigest()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("bundle"); ap.add_argument("--repo", default="Milkymoomoo/Fully-Local-Agentic-Assistant")
    ap.add_argument("--tag", default="models-3d-v2"); ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "../../app/src/main/assets/pixal3d_models.json"))
    ap.add_argument("--split-dir")
    a = ap.parse_args()
    man = json.load(open(os.path.join(a.bundle, "manifest.json")))
    base = f"https://github.com/{a.repo}/releases/download/{a.tag}/"
    files, assets = [], []
    for e in man["files"]:
        name, size = e["name"], e["size"]
        stem = name.split(".")[0]; key = next((k for k in LABEL if stem.startswith(k)), stem)
        lic = next(v for k, v in LICENSE.items() if stem.startswith(k))
        label = LABEL.get(key, stem) + (f" {stem[-4:]}" if key == "trellis2_shape_dec" else "") + (" (weights)" if name.endswith(".data") else "")
        f = {"name": name, "label": label, "size": size, "sha256": e["sha256"], "license": lic}
        if size <= LIMIT:
            f["url"] = base + name; assets.append((name, size))
        else:
            parts, off, i = [], 0, 1
            while off < size:
                n = min(PART_SIZE, size - off); an = f"{name}.{i:03d}"
                p = os.path.join(a.bundle, name)
                parts.append({"asset": an, "url": base + an, "size": n, "sha256": sha(p, off, n)})
                if a.split_dir:
                    os.makedirs(a.split_dir, exist_ok=True)
                    with open(p, "rb") as src, open(os.path.join(a.split_dir, an), "wb") as dst:
                        src.seek(off); left = n
                        while left: b = src.read(min(1 << 24, left)); dst.write(b); left -= len(b)
                assets.append((an, n)); off += n; i += 1
            f["parts"] = parts
        files.append(f)
    out = {
        "version": man.get("version", ""), "release": f"https://github.com/{a.repo}/releases/tag/{a.tag}",
        "files": files,
        "licenses": [
            {"id": "dinov3", "title": "DINOv3 License", "mustAccept": True,
             "url": f"https://github.com/{a.repo}/blob/main/licenses/DINOv3-LICENSE.md",
             "notice": "Built with DINOv3. The image encoder is Meta's DINOv3 ViT-L/16, converted to ONNX and redistributed under the DINOv3 License. By installing it you agree to that licence (including its acceptable-use and trade-control terms).",
             "files": [x["name"] for x in files if x["name"].startswith("dinov3")]},
            {"id": "mit-apache", "title": "Pixal3D, TRELLIS.2, MoGe-2 (MIT) · NAF, DINOv2 (Apache-2.0)", "mustAccept": False,
             "url": f"https://github.com/{a.repo}/blob/main/THIRD_PARTY_NOTICES.md",
             "notice": "Converted from the official checkpoints; see THIRD_PARTY_NOTICES.md for the copyright notices.",
             "files": [x["name"] for x in files if not x["name"].startswith("dinov3")]},
        ],
    }
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    json.dump(out, open(a.out, "w"), indent=1)
    tot = sum(s for _, s in assets)
    print(f"{len(assets)} release assets for tag {a.tag}, {tot/1e9:.2f} GB total (largest {max(s for _, s in assets)/1e9:.2f} GB):")
    for n, s in assets: print(f"  {s:>14,}  {n}")
    print("wrote", os.path.abspath(a.out))

if __name__ == "__main__":
    main()
