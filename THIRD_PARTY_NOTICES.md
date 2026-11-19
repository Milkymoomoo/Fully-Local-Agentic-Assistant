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

## 5. Windows program (`desktop/`, in the installer and the portable zip)

Everything in section 1 that the shared code uses (sherpa-onnx with ONNX Runtime and eSpeak NG, llama.cpp, Apache
Commons Compress, the Kotlin standard library, the default avatars) is in the Windows program too, plus:

| Component | Use | Licence |
|---|---|---|
| Java runtime (OpenJDK 21, trimmed by jpackage) | runs the program | GPL-2.0 with the Classpath Exception |
| [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) and Skiko / [Skia](https://skia.org) | windows, UI, drawing | Apache-2.0 (Compose, Skiko); BSD-3-Clause (Skia) |
| [LWJGL](https://www.lwjgl.org) 3 (OpenGL bindings) | the 3D avatar renderer | BSD-3-Clause |
| [JNA](https://github.com/java-native-access/jna) 5.17 | the few Win32 calls (DPAPI, keep awake, memory use) | Apache-2.0 (dual-licensed with LGPL-2.1) |
| [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) 3.50 (with SQLite) | the memory store | Apache-2.0; SQLite is public domain |
| [Apache PDFBox](https://pdfbox.apache.org) 2.0.32 | reading PDFs aloud | Apache-2.0 |
| [org.json](https://github.com/stleary/JSON-java) | settings files | Public domain |
| mp3spi, vorbisspi, JLayer, JOrbis, Tritonus share (JavaZOOM / soundlibs) | playing MP3 and Ogg chimes and sounds | **LGPL-2.1** (used as unmodified separate jars, which you may replace) |
| belle_llm in CUDA and Vulkan builds (llama.cpp / ggml) | chat on the GPU | MIT |

**Downloaded by the program only when you choose a GPU backend or video backgrounds.** They come straight from the
publisher, are checked against a pinned SHA-256, and are never redistributed here:

| Component | Source | Licence |
|---|---|---|
| ONNX Runtime GPU 1.32.0 (CUDA) | NuGet `Microsoft.ML.OnnxRuntime.Gpu.Windows` | MIT, © Microsoft |
| NVIDIA CUDA 13 runtime, cuBLAS, cuFFT, cuDNN 9 | developer.download.nvidia.com (redistributable archives) | NVIDIA CUDA Toolkit EULA; NVIDIA cuDNN Software License Agreement |
| ONNX Runtime DirectML 1.24.4, DirectML 1.15.4 | NuGet `Microsoft.ML.OnnxRuntime.DirectML`, `Microsoft.AI.DirectML` | MIT; Microsoft DirectML licence |
| ONNX Runtime with OpenVINO 1.24.1 | NuGet `Intel.ML.OnnxRuntime.OpenVino` | MIT (ONNX Runtime); Apache-2.0 (OpenVINO) |
| FFmpeg 9.0.2 essentials build (gyan.dev) | github.com/GyanD/codexffmpeg | **GPL-3.0**. A separate program that the assistant runs; it is not linked into it |
