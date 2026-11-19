# 3D generation models (models-3d-v2)

The on-phone photo → 3D model set used by *Settings → 3D avatar → Install 3D generation* (app 0.1.0 and later).
You don't need to download these by hand: the app does it, resumes, and checks every file. For an offline install,
download all files (including `manifest.json`) into one folder, copy it to the phone and pick it under
*Or add them from a folder*.

18 files plus `manifest.json`, 5.29 GB in total, the largest 1.67 GB. ONNX Runtime 1.28, int8 MatMulNBits weights,
operator-fused (FastGelu), the TRELLIS.2 shape decoder split into 5 segments so peak memory stays about 3.4 GB.

| Files | Model | Licence |
|---|---|---|
| `pixal3d_ss_flow.*`, `pixal3d_shape512_flow.*` | Pixal3D (TencentARC) structure and shape flows | MIT |
| `trellis2_struct_dec.*`, `trellis2_shape_dec_seg0…4.*` | TRELLIS.2 decoders (Microsoft) | MIT |
| `moge2_l_3600_nb8.onnx` | MoGe-2 ViT-L (Microsoft), camera / field of view | MIT |
| `naf_q_r1.*` | NAF feature upsampler (valeo.ai) | Apache-2.0 |
| `dinov3_l_nb8.*` | DINOv3 ViT-L/16 (Meta) image encoder | DINOv3 License |
| `LICENSES.zip` | all licence and notice texts | |

**Built with DINOv3.** `dinov3_l_nb8.onnx` and `.onnx.data` are redistributed under the DINOv3 License
(`licenses/DINOv3-LICENSE.md`); by downloading or using them you agree to it. The others keep their MIT/Apache-2.0
licences; see THIRD_PARTY_NOTICES.md. Converted with `tools/pixal3d_port/` from the official checkpoints.

SHA-256 of every file: see `manifest.json` (the app has the same values built in).
