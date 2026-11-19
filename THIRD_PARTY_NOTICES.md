# Third-party notices

Fully Local Agentic Assistant is licensed under the Apache License 2.0 (see `LICENSE`). It includes, links to or
downloads the components below. Their own licence texts are in [`licenses/`](licenses) or in the component's folder.

**Built with DINOv3.**

## 1. Shipped inside the APK

| Component | Use | Licence | Licence text |
|---|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8 (`app/libs/*.aar`) | wake words, speech-to-text, text-to-speech runtime | Apache-2.0 | `licenses/Apache-2.0.txt` |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) (inside sherpa-onnx; headers in `third_party/ort_headers`) | neural-network runtime (also runs Pixal3D) | MIT, © Microsoft | `licenses/onnxruntime-LICENSE.txt` |
| [eSpeak NG](https://github.com/espeak-ng/espeak-ng) (statically linked inside sherpa-onnx's JNI library, used by the Piper voices for phonemes) | text → phonemes | **GPL-3.0-or-later** | `licenses/GPL-3.0.txt` |
| [llama.cpp / ggml](https://github.com/ggml-org/llama.cpp) (`third_party/llama.cpp`) | local language model | MIT, © the ggml authors | `third_party/llama.cpp/LICENSE` |
| [dav1d](https://code.videolan.org/videolan/dav1d) (`third_party/dav1d_prebuilt`) | AV1 / AVIF backgrounds | BSD-2-Clause, © VideoLAN and dav1d authors | `third_party/dav1d_prebuilt/COPYING` |
| [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) 2.0.27.0 | reading PDFs aloud | Apache-2.0 | `licenses/Apache-2.0.txt` |
| Liberation Sans Regular (bundled by PdfBox-Android) | PDF text fallback font | SIL Open Font License 1.1 | inside the PdfBox-Android artifact |
| Apache Commons Compress 1.28.0 | unpacking voice archives | Apache-2.0 | `licenses/Apache-2.0.txt` |
| AndroidX, Jetpack Compose, Kotlin standard library | UI and language runtime | Apache-2.0 | `licenses/Apache-2.0.txt` |
| [KWS zipformer gigaspeech 3.3M](https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models) (fetched at build time by `scripts/fetch_models.sh`) | wake-word model | Apache-2.0 (k2-fsa / icefall) | `licenses/Apache-2.0.txt` |
| [U²-Net-P](https://github.com/xuebinqin/U-2-Net) `u2netp.onnx` (fetched at build time from the rembg release) | background cut-out | Apache-2.0, © Xuebin Qin et al. | `licenses/Apache-2.0.txt` |
| Kokoro voice sample (`assets/voice_samples/kokoro-*.ogg`), made with [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) | voice preview | Apache-2.0 | `licenses/Apache-2.0.txt` |
| Bob From Accounting, Boob Dragon / Duchess avatars, app icon (`art/`, `assets/default_avatars/`) | default assistants | Apache-2.0 (this project) | `LICENSE` |

**About GPL-3.0 (eSpeak NG):** the Piper voices need eSpeak NG, which sherpa-onnx links into its native library. The
release APKs therefore contain GPL-3.0 code, and an APK as a whole is distributed under the terms of the GNU GPL v3.
This project's own code is Apache-2.0, which is compatible with GPL-3.0. The complete corresponding source is this
repository plus [sherpa-onnx v1.13.8](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8) and its eSpeak NG fork
(<https://github.com/csukuangfj/espeak-ng>). You may rebuild the APK from them.

## 2. Pixal3D photo → 3D models (GitHub Release `models-3d-v2`, installed by *Install 3D generation*)

These are ONNX conversions (int8 MatMulNBits, operator fusion, split decoder) of the official checkpoints. They were
converted by this project (`tools/pixal3d_port/`) and are otherwise unmodified in behaviour.

| Release files | Original | Licence | Notice |
|---|---|---|---|
| `pixal3d_ss_flow.*`, `pixal3d_shape512_flow.*` | [Pixal3D](https://github.com/TencentARC/Pixal3D) | MIT, © 2026 Tencent | `licenses/Pixal3D-LICENSE.txt`, `licenses/Pixal3D-NOTICE.txt` |
| `trellis2_struct_dec.*`, `trellis2_shape_dec_seg0…4.*` | [TRELLIS.2](https://github.com/microsoft/TRELLIS.2) | MIT, © Microsoft | `licenses/TRELLIS.2-LICENSE.txt` |
| `moge2_l_3600_nb8.onnx` | [MoGe-2 ViT-L](https://github.com/microsoft/MoGe) (DINOv2 backbone, Apache-2.0) | MIT, © Microsoft | `licenses/MoGe-LICENSE.txt` |
| `naf_q_r1.*` | [NAF](https://github.com/valeoai/NAF) | Apache-2.0, © valeo.ai | `licenses/NAF-LICENSE.txt` |
| `dinov3_l_nb8.*` | [DINOv3 ViT-L/16](https://github.com/facebookresearch/dinov3) | **DINOv3 License**, © Meta Platforms | `licenses/DINOv3-LICENSE.md` |

**DINOv3:** redistributed under the DINOv3 License, a copy of which is included. "Built with DINOv3." Anyone who
downloads or uses these files is bound by that licence, including its acceptable-use and trade-control terms. The app
shows the licence and asks you to accept it before it downloads these files. If you prefer, get DINOv3 from Meta's
official source (<https://ai.meta.com/dinov3/> / <https://huggingface.co/facebook/dinov3-vitl16-pretrain-lvd1689m>, gated)
and convert it with `tools/pixal3d_port/dino_export.py`.

Pixal3D's NOTICE asks for responsible use: don't use generated 3D content to deceive, harass or infringe the rights of others.

## 3. Downloaded by the app from the original publishers (not redistributed here)

The app's *Get models* screen downloads these directly from their official Hugging Face / GitHub pages. Each one's
licence is shown before download and applies to you as the user.

| Component | Source | Licence |
|---|---|---|
| Moonshine Tiny (English) speech-to-text | k2-fsa/sherpa-onnx releases | MIT, © Useful Sensors |
| Silero VAD | k2-fsa/sherpa-onnx releases | MIT |
| Piper voices | k2-fsa/sherpa-onnx `tts-models` | per voice: public domain, CC0, CC BY 4.0, MIT, **CC BY-NC-SA 4.0** or **Blizzard 2013 Lessac (non-commercial)** |
| Kokoro-82M voices | k2-fsa/sherpa-onnx `tts-models` | Apache-2.0 |
| Language models (Qwen2.5, Qwen3, Llama 3.2, Dolphin and others, GGUF) | Hugging Face | per model, e.g. Apache-2.0, Llama 3.2 Community License, **Qwen Research License (non-commercial)** |

## 4. Docs site (`docs-site/`, not in the APK)

| Component | Use | Licence |
|---|---|---|
| [marked](https://github.com/markedjs/marked) (`docs-site/assets/vendor/marked.min.js`) | renders the Markdown docs in the browser | MIT, © MarkedJS, © Christopher Jeffrey; full text in `docs-site/assets/vendor/LICENSE` |
