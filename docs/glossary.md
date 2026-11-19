# Glossary

> *Plain-English meanings of the words used in these docs.*

<!-- nav -->
<p align="center"><sub><a href="troubleshooting.md">← Troubleshooting</a> &nbsp;·&nbsp; <a href="README.md">Docs home</a> &nbsp;·&nbsp; <a href="SECURITY.md">Security & verification →</a></sub></p>
<!-- /nav -->

| Term | What it means |
|---|---|
| **Wake word** | The phrase that makes the assistant start listening, like *"hey bob"*. |
| **Sleep word** | A phrase that ends the conversation, like *"that's all"*. |
| **Speech recognition (STT)** | Turning your voice into text. |
| **VAD** | The part that notices when you start and stop talking. |
| **TTS / voice** | Turning text into speech. |
| **Thinking model / LLM** | The "brain" that understands everyday sentences and writes answers. |
| **GGUF** | The file type of a thinking model. |
| **Quantised / int8 / Q4** | A smaller, slightly rounded copy of a model that runs faster and uses less memory. |
| **Uncensored / abliterated** | Model versions that refuse less often. |
| **System prompt / personality** | The hidden instructions that tell the model who it is and how to talk. |
| **Context / tokens** | How much the model can "see" at once, counted in word-pieces. |
| **KV cache** | The model's short-term scratchpad, reused so it doesn't re-read the same text. |
| **Directive** | A standing instruction you give by voice, like *"always call me captain"*. |
| **Tool call** | When the model decides to *do* something (set an alarm) instead of only talking. |
| **Long-term memory** | What the assistant remembers between conversations. |
| **BM25** | A way to find the most relevant old notes for what you just said. |
| **Foreground service** | An Android background job that shows a notification so it isn't killed. |
| **Checkpoint** | A saved copy of a long job's progress, so it can continue after an interruption instead of starting over. |
| **Atomic write** | Saving a file so that it is either the old version or the new one, never half of each (write a temporary file, flush it to storage, then rename it). |
| **Checksum (SHA-256)** | A fingerprint of a file. If even one byte changes, the fingerprint no longer matches, so a damaged file is noticed. |
| **Time left (ETA)** | The app's estimate of how long something will still take, based on how fast it has been going. |
| **Avatar Studio** | The step-by-step way to make a new avatar in Simple settings. |
| **Wake lock** | Keeps the processor from sleeping so listening or reading continues with the screen off. |
| **Overlay / bubble** | The floating avatar on top of other apps. |
| **Accessibility service** | An Android feature that lets an app read and tap the screen for you. |
| **Rig / rigging** | Giving a picture or 3D model joints so it can move. |
| **Skinning / weights** | How much each bit of the surface follows each bone. |
| **glTF / .glb** | The standard 3D model file type the app uses. |
| **Shader** | A tiny GPU program that decides how pixels look. |
| **Detail level** | Trading model sharpness for speed. |
| **Photo → 3D / Pixal3D** | Making a 3D model from one picture on the phone. |
| **ONNX / ONNX Runtime** | A common file type for AI models, and the engine that runs them. |
| **Flow model / steps** | A generator that refines noise into a shape over several steps. |
| **SHA-256 / checksum** | A fingerprint that proves a download is exactly the right file. |
| **Resumable download** | A download that continues where it stopped instead of starting over. |
| **Widget** | The assistant on your home screen. |
| **Ducking** | Turning other audio down while the assistant speaks. |
| **Emulator render / JVM render** | Pictures made on a computer, not a phone (labelled in these docs). |

<details><summary>Technical details</summary>

| Term | Technical meaning |
|---|---|
| Wake word | Keyword spotting (KWS): a small streaming transducer model scores phoneme-token paths every 100 ms of audio. |
| Sleep word | Matched on the speech-recognition text (equal, suffix, or prefix + ≤ 2 words). |
| Speech recognition (STT) | Moonshine Tiny (encoder–decoder) via sherpa-onnx, fed in ≤ 8 s pieces. |
| VAD | Silero voice-activity detector, 512-sample windows at 16 kHz. |
| TTS / voice | Piper (VITS) or Kokoro neural vocoders, run sentence by sentence. |
| Thinking model / LLM | A large language model in GGUF format, run by llama.cpp on the CPU. |
| GGUF | llama.cpp's single-file format holding weights (often 4-bit quantised), tokenizer and chat template. |
| Quantised / int8 / Q4 | Weights stored as 8-bit or 4-bit integers with scale factors instead of 16/32-bit floats. |
| Uncensored / abliterated | Uncensored = fine-tuned on unfiltered data (Dolphin); abliterated = the "refusal direction" removed from the weights afterwards. |
| System prompt / personality | Built by `PersonaPrompt` from four sections and placed first in the context. |
| Context / tokens | 4096-token window; about ¾ of a word per token in English. |
| KV cache | Cached attention keys/values; the app reuses the common prefix between turns. |
| Directive | Parsed by regex, stored per assistant (max 10 × 220 chars), injected into the prompt. |
| Tool call | The model emits JSON matching a grammar; 23 tools are allowed. |
| Long-term memory | Encrypted SQLite of turns, running summaries and facts; BM25 retrieval into a token budget. |
| BM25 | Classic term-frequency ranking function used for fact and snippet retrieval. |
| Foreground service | `startForeground` with a declared type (microphone, mediaPlayback, dataSync). |
| Wake lock | `PARTIAL_WAKE_LOCK`. |
| Overlay / bubble | A `TYPE_APPLICATION_OVERLAY` window; needs *Display over other apps*. |
| Accessibility service | `BelleAccessibilityService`; used for screen commands and frontend web reading. |
| Rig / rigging | 2D: a 20×20 warp mesh with joints; 3D: a skeleton with per-vertex bone weights. |
| Skinning / weights | Linear blend skinning with 4 influences per vertex (`JOINTS_0`/`WEIGHTS_0`). |
| glTF / .glb | Khronos glTF 2.0 binary container (mesh, materials, textures, skin). |
| Shader | GLSL ES 3.0 vertex/fragment programs; the retro looks are shader variants. |
| Detail level | Progressive mesh triangle count, texture mip bias and render-scale per surface. |
| Photo → 3D / Pixal3D | Tencent Pixal3D + Microsoft TRELLIS.2 flow models exported to ONNX and run with ONNX Runtime. |
| ONNX / ONNX Runtime | Open Neural Network Exchange graphs, executed by Microsoft's ONNX Runtime (CPU). |
| Flow model / steps | Rectified-flow sampler (Euler); fewer steps = faster, slightly less exact. |
| SHA-256 / checksum | 256-bit hash compared with the catalog value before the file is used. |
| Resumable download | HTTP `Range` requests appended to a `.part` file. |
| Widget | An `AppWidgetProvider` with `RemoteViews` and pre-rendered frames. |
| Ducking | `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`. |
| Emulator render / JVM render | Android emulator with software GPU (SwiftShader), or the renderer run in unit tests on the desktop JVM. |

</details>

<!-- nav -->
<p align="center"><sub><a href="troubleshooting.md">← Troubleshooting</a> &nbsp;·&nbsp; <a href="README.md">Docs home</a> &nbsp;·&nbsp; <a href="SECURITY.md">Security & verification →</a></sub></p>
<!-- /nav -->
