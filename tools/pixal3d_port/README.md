# Pixal3D on-device port — stage status (measured 2026-11-16, box CPU: Intel Xeon, 4 threads; not on a phone)

| Stage | Runs? | Time | Peak memory | Notes |
|---|---|---|---|---|
| BiRefNet background removal (torch, ComfyUI manual_cast ops) | ✗ | — | >9.5 GB, climbing | Root cause: ComfyUI's per-op weight casting under its model patcher, not the network. |
| BiRefNet, plain fp32 ops, 512 px | ✓ | 3.2 s | 2.10 GB | `bg2.py` (S=512) |
| BiRefNet, plain fp32 ops, 1024 px | ✓ | 13.5 s | 3.92 GB | `bg2.py` (S=1024) |
| BiRefNet ONNX (opset 19, DeformConv), 512 px, onnxruntime 1.32 CPU | ✓ | 1.92 s | 2.41 GB | `bg_export.py` / `bg_ort.py`; mask identical to torch (max Δ 0.0000). 887 MB fp32 weights. The phone's ORT (in sherpa-onnx 1.13.8 AAR, ORT 1.28.x) contains the DeformConv kernel. |
| MoGe-2 ViT-L FOV, 3600 tokens ONNX (fp32 / nb8) | ✓ | 5.9 s / 7.1 s | | FOV 46.824° (fp32, identical to torch) / 47.067° (nb8, 419 MB). (re-measured 2026-11-18) |
| DINOv3-L conditioning ONNX, 512 / 1024 px | ✓ | 1.6 / 12.0 s (fp32), 1.4 / 10.7 s (nb8) | 3.6 GB | fp32 exact; nb8 (316 MB) token cosine mean 0.9999, min 0.998 / 0.990. dq8 is unusable (cos min 0.61). |
| Pixal3D structure flow (int8 ConvRot) ONNX | ✓ box | 20 s / forward (4 thr); 12 steps 478 s | 4.7 GB (arena off) | `flow/ss_export.py`: int8 rows kept exact in MatMulNBits (8-bit, block 256) behind an in-graph 256-Hadamard on the activations; 1.67 GB. One forward vs captured reference: rel 0.4 % (uncond) / 1.9–2.6 % (cond) — same order as the reference's own int8-activation noise (ComfyUI re-run vs its capture: 0.4 / 0.9 %). Full sampler (`flow/ss_sample.py`, euler, normal, shift 5, CFG 7.5 + RescaleCFG 0.7, cfg 1 below 66.7 %), same seed: voxel IoU 0.878 vs reference (1487 vs 1601 voxels). Not yet run on a phone. |
| TRELLIS.2 structure decoder ONNX | ✓ | 1.2 s | | `flow/struct_dec_export.py`, 295 MB fp32; voxels identical to the reference (1601/1601). |
| Shape flow 512 (sparse, int8 ConvRot) ONNX | ✓ box | 4.7 s / forward; 20 steps 252 s (8 thr) | 3.15 GB | `flow/shape_export.py`, `flow/shape_sample.py`. Same noise as the (fp32-fixed) reference: latent corr 0.957; decoded mesh vs reference mesh: chamfer 0.36 % of the bbox, F-score@1 % 0.989 (`flow/shape_dec_ort.py LAT=… CHAMFER=1`). |
| TRELLIS.2 shape VAE decoder (5 ONNX segments + app-side sparse conv neighbours / subdivision / dual grid) | ✓ | 30–42 s | 4.3 GB (seg0 fp32 993 MB) | exact vs ComfyUI on the reference latent (max dv 6e-8). |
| Colour: photo projection bake (`TextureBake.kt`) | ✓ | ~1 s | small | Not the TRELLIS.2 texture model: seen surface takes the crop's colour (z-buffer visibility, subject mask), the back is filled from the nearest seen colours. Then 256³ vertex clustering (≈ 600k → 120k verts, 290k tris). |
| TRELLIS.2 1024/1536 refinement, texture flow + texture VAE, UV unwrap / normal / AO bakes | not ported | | | The phone version stops at 512. |

## Whole app pipeline, end to end — phone model set (`Pixal3DEndToEndTest`, 2026-11-19)
The app's own Kotlin (`Pixal3D.generateFrom`) through the app's JNI ONNX runner built for the box against ONNX Runtime
1.28 (the phone's version), 4 threads pinned to 4 cores (`taskset -c 0-3`), art/dragoness.png + the reference mask:
**7.8 min, peak RSS 3.27 GB** (whole JVM process; was 26.5 min / 5.9 GB on 11-18). `proof/e2e_phone_set.json`, sheet
`proof/e2e_phone_set_sheet.png` (top: this run; bottom: the ComfyUI reference glb).

| Stage | Time | Peak RSS |
|---|---|---|
| MoGe-2 FOV (nb8) | 12.3 s | 2.94 GB |
| DINOv3 512 (nb8) | 3.6 s | 1.0 GB |
| structure flow, 8 steps (14 model calls) | 278 s (19.8 s / call) | **3.27 GB** |
| structure decoder | 2.1 s | 1.06 GB |
| NAF + projection | 8.9 s | 1.41 GB |
| shape flow 512, 12 steps (21 calls) | 121 s (load 19 s) | 2.96 GB |
| shape decoder seg0..4 (q8 / fp32-opt / fp32 / fp32 / fp32) | 17.2 s | 2.13 GB |
| dual grid, colour bake, clustering, glb | 0.9 s | 1.15 GB |

What changed: every model is released and the native heap handed back (`malloc_trim` / `M_PURGE`) after each stage, so
the next stage starts from ~0.4–0.9 GB instead of a creeping 2.6 GB; ORT-'basic'-optimised files whose weights are
mmapped (.onnx.data); FastGelu instead of the exported 8-op tanh-GELU in both flows (4–6 % faster, same accuracy on the
captured calls); int8 decoder seg0; decoder seg2-4 in their original form (the 'basic'-optimised ones peak at up to 4.4 GB);
8 structure steps instead of 12 (voxel IoU vs reference 0.890 vs 0.902) and 12 shape steps instead of 20 (mesh chamfer
0.359 % vs 0.36 %); the NAF map (268 MB) is dropped after use; floating voxel specks are removed after the structure stage.
Mesh vs the ComfyUI reference (different noise generator): chamfer 1.47 % of the bbox, F@2 % 0.850 (11-18 run: 1.1 %, 0.868).

Phone files (models/3d, `make_bundle.py /workspace/port3d/phone <out>`): 5.29 GB — pixal3d_ss_flow 1.67 GB,
pixal3d_shape512_flow 1.47 GB, trellis2_shape_dec_seg1 0.73 GB, moge2_l_3600_nb8 0.42 GB, dinov3_l_nb8 0.32 GB,
trellis2_struct_dec 0.29 GB, seg0 (int8) 0.27 GB, seg2-4 0.13 GB, naf 3 MB. Background removal uses the app's own matting.

Not measured: an arm64 phone or emulator (no arm64 system image on the box and /dev/kvm isn't accessible, so only a
software-emulated x86_64 AVD would run — far too slow for this job). The numbers above are x86-64 (Xeon, AVX-512 VNNI);
ORT's int8 MatMulNBits path differs on ARM (dotprod/i8mm), so phone time can differ a lot.
