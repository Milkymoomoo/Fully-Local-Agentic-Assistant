# Architecture

> *How the parts fit together: one always-on listener, engines that load on demand, and everything on the phone.*

<!-- nav -->
<p align="center"><sub><a href="settings-reference.md">← Settings reference</a> &nbsp;·&nbsp; <a href="README.md">Docs home</a> &nbsp;·&nbsp; <a href="status.md">Verification status →</a></sub></p>
<!-- /nav -->

<p align="center"><picture><source media="(prefers-color-scheme: dark)" srcset="images/diagrams/architecture-dark.png"><img src="images/diagrams/architecture.png" width="720" alt="Architecture diagram"></picture><br><sub>Diagram · the whole app</sub></p>

<details><summary>Mermaid source (GitHub draws this live)</summary>

```mermaid
flowchart TB
  subgraph UI["App UI (Jetpack Compose)"]
    Home["Home / Conversation"] --- Settings["Settings pages"]
    Settings --- Editors["Framing & rig editors"]
  end
  subgraph Surfaces["Surfaces"]
    Widget["Home-screen widget"]
    Bubble["Floating bubble + edge glow"]
    Notif["Notifications"]
  end
  subgraph Svc["AssistantService (foreground: microphone)"]
    Mic["Microphone 16 kHz"] --> KWS["Wake word<br/>sherpa-onnx KWS"]
    KWS --> VAD["Silero VAD"] --> STT["Moonshine STT"]
    STT --> Brain["Brain<br/>rules → directives → LLM"]
    Brain --> Tools["Phone actions<br/>23 tools"]
    Brain --> TTS["Piper / Kokoro TTS"] --> Out["AudioOut"]
    Brain <--> Mem[("Encrypted memory<br/>SQLite + AES-GCM")]
    Brain --> LLM["llama.cpp (JNI)<br/>GGUF model"]
    Brain --> Web["Net / WebView<br/>(only if Internet on)"]
  end
  subgraph Book["BookPlaybackService (mediaPlayback)"]
    Lib[("Library files/books")] --> Reader["BookReader"] --> TTS2["TTS"]
  end
  subgraph Gfx["Avatar engines"]
    A2["Skin2D mesh warp"]
    A3["GL ES 3D renderer<br/>AutoRig3D skins"]
    P3["Pixal3D (ONNX Runtime)<br/>photo → 3D"]
  end
  DL["DownloadService (dataSync)<br/>Fetcher: HTTPS + SHA-256"] --> Files[("files/models")]
  Files --> Svc
  UI --> Svc
  Svc --> Surfaces
  Gfx --> Surfaces
  UI --> Gfx
  UI --> Book
  Widget --> Book
```

</details>


## The big picture

The app is a normal Android app with no server behind it. It has three long-running pieces, each of which shows a notification while it runs:

| Piece | What it does | When it runs |
|---|---|---|
| **Assistant service** | Listens for the wake word, understands you, thinks, acts and speaks | While listening is switched on |
| **Book service** | Reads books aloud with the screen off | While a book is playing, plus 5 minutes after a pause |
| **Download service** | Fetches models in the background | Only during downloads |

Everything else (settings pages, widget, bubble, 3D renderer, photo → 3D) is ordinary app code that runs when you use it.

<details><summary>Technical details</summary>

- **Modules:**
  - `:app`: Android UI, services and native glue.
  - `:shared`: pure-Kotlin core (parsers, catalog, policies, memory assembler, rig maths), unit-tested on the JVM.
  - `:downloader`: the optional companion app.
- **Native code:** `app/src/main/cpp`.
  - `llm_core.cpp` / `llm_jni.cpp`: llama.cpp (GGUF, KV-prefix reuse, streaming).
  - `ort_jni.c`: a generic ONNX Runtime runner bound to sherpa-onnx's `libonnxruntime.so`, used by Pixal3D.
  - `av1_jni.c`: dav1d, for AVIF / AV1 backgrounds.
- **Speech:** sherpa-onnx 1.13.8 AAR (KWS, Silero VAD, Moonshine, Piper / Kokoro VITS) on ONNX Runtime CPU.
- **Graphics:**
  - 2D avatar: `Canvas` mesh warp (`Skin2D`).
  - 3D avatar: OpenGL ES 3 with GPU skinning and two weight sets (see [3D avatar](features/avatar-3d.md)).
  - The widget gets software-rendered or pre-rendered frames.
- **Threads:** the microphone has its own thread. Model loads use separate loader threads (speech recognition first, then the voice and the thinking model in parallel). Speaking is a producer/consumer queue: one sentence is synthesized while the previous one plays.
- **No IPC with other apps** except Android intents (alarms, calls, maps) and the optional Accessibility service.

</details>

## Voice pipeline

<p align="center"><picture><source media="(prefers-color-scheme: dark)" srcset="images/diagrams/voice_pipeline-dark.png"><img src="images/diagrams/voice_pipeline.png" width="560" alt="Voice pipeline diagram"></picture><br><sub>Diagram · from the microphone to a spoken reply</sub></p>

<details><summary>Mermaid source (GitHub draws this live)</summary>

```mermaid
flowchart TB
  subgraph A["1 · Waiting (always on, ~6 MB)"]
    direction LR
    M["🎤 Mic<br/>16 kHz, 100 ms frames"] --> K["Wake-word spotter<br/>sherpa-onnx KWS"]
  end
  subgraph B["2 · Listening"]
    direction LR
    C["Chime; load speech<br/>(voice + model load in parallel)"] --> V["Silero VAD<br/>finds where you stop"] --> S["Moonshine Tiny<br/>speech → text"]
  end
  subgraph Cc["3 · Thinking"]
    direction LR
    Z["Sleep phrase?<br/>→ goodbye"] --> Br["Brain: rules → directives<br/>→ llama.cpp model"] --> Ac["Phone action<br/>alarm, call, search …"]
  end
  subgraph D["4 · Speaking"]
    direction LR
    T["TTS sentence by sentence<br/>Piper / Kokoro"] --> O["🔊 AudioOut<br/>limiter, music ducking"] --> L["Continuous? listen again<br/>else back to 1"]
  end
  A --> B --> Cc --> D
```

</details>

More in [Wake word](features/wake-word.md), [Speech recognition](features/speech-recognition.md), [Thinking model](features/thinking-model.md) and [Voices](features/voices.md).

## 3D auto-rig

<p align="center"><picture><source media="(prefers-color-scheme: dark)" srcset="images/diagrams/rig-dark.png"><img src="images/diagrams/rig.png" width="560" alt="Auto-rig diagram"></picture><br><sub>Diagram · AutoRig3D v6, from a mesh to an animated avatar</sub></p>

<details><summary>Mermaid source (GitHub draws this live)</summary>

```mermaid
flowchart TB
  subgraph A["1 · Understand the body"]
    direction LR
    G["Imported .glb / .obj<br/>or Pixal3D mesh"] --> N["Normalise<br/>up axis, scale"] --> F["Find parts in 48 slices<br/>head, neck, arms, legs,<br/>wings, tail, jaw"]
  end
  subgraph B["2 · Weight the skin"]
    direction LR
    W["Initial weights<br/>bone distance + part labels"] --> X["Transfer + smooth<br/>bust pass"] --> T["Stretch test in all poses<br/>→ seam diffusion, contacts,<br/>motion budget (repeat)"]
  end
  subgraph C["3 · Save and play"]
    direction LR
    O[".glb with two weightings<br/>JOINTS_0 full · JOINTS_1 no-stretch"] --> R["Renderer mixes them<br/>by anim_strength 0…1"]
  end
  A --> B --> C
```

</details>

More in [AutoRig3D](features/autorig3d.md).

## Book playback

<p align="center"><picture><source media="(prefers-color-scheme: dark)" srcset="images/diagrams/book_playback-dark.png"><img src="images/diagrams/book_playback.png" width="560" alt="Book playback diagram"></picture><br><sub>Diagram · import, library and playback</sub></p>

<details><summary>Mermaid source (GitHub draws this live)</summary>

```mermaid
flowchart TB
  P["EPUB / PDF / TXT"] --> I["BookImport (streaming)<br/>clean running lines, join paragraphs,<br/>chapters from TOC/outline"]
  I --> L[("files/books/id<br/>book.json, chapter .txt + .idx, pos.json")]
  L --> R["BookReader<br/>reads 3 sentences ahead"]
  R --> T["TTS (assistant's voice)"] --> A["AudioOut"]
  A --> R
  R --> Pos["Save position every sentence"] --> L
  C["Voice: 'read my book', 'pause', 'faster', 'recap'"] --> R
  N["Notification / widget / headset<br/>prev · play-pause · next · stop"] --> S["BookPlaybackService<br/>mediaPlayback, wake lock, 5 min grace"]
  S --> R
```

</details>

More in [Read to me](features/read-to-me.md).

## Long stories and songs

<p align="center"><picture><source media="(prefers-color-scheme: dark)" srcset="images/diagrams/longform-dark.png"><img src="images/diagrams/longform.png" width="720" alt="Long-form diagram"></picture><br><sub>Diagram · writing and speaking at the same time</sub></p>

<details><summary>Mermaid source (GitHub draws this live)</summary>

```mermaid
flowchart LR
  Q["'Tell me a long story<br/>about …'"] --> K["LongForm: kind + length<br/>story 450 w, song 220, poem 140;<br/>minutes × 150 words"]
  K --> G["llama.cpp streams tokens<br/>temp 0.8, top-p 0.95, min-p 0.05"]
  G --> SB["Sentence splitter"]
  SB --> T["TTS speaks sentence N<br/>while N+1 is written"]
  SB --> LP{"Same sentence twice<br/>in last 8?"}
  LP -- yes --> X["Stop (loop guard)"]
  LP -- no --> G
  T --> E["Done → saved to story thread;<br/>'continue' picks up from the tail"]
```

</details>

More in [Stories, songs and poems](features/stories.md).

## Photo → 3D installer

<p align="center"><picture><source media="(prefers-color-scheme: dark)" srcset="images/diagrams/installer_flow-dark.png"><img src="images/diagrams/installer_flow.png" width="420" alt="Installer flow diagram"></picture><br><sub>Diagram · the one-button installer</sub></p>

<details><summary>Mermaid source (GitHub draws this live)</summary>

```mermaid
flowchart TB
  B["Tap Install 3D generation"] --> Lc{"DINOv3 licence<br/>accepted?"}
  Lc -- no --> Stop["Nothing downloaded"]
  Lc -- yes --> Sp{"Free space ≥<br/>remaining + 300 MB?"}
  Sp -- no --> Msg["Tell user how much to free"]
  Sp -- yes --> Q["Queue 18 files (5.29 GB)<br/>one after another, DownloadService<br/>wake + Wi-Fi lock"]
  Q --> F["Fetcher: HTTPS, allow-listed hosts,<br/>resume .part with Range"]
  F --> H{"Size + SHA-256 ok?"}
  H -- no --> Re["Auto-retry ×5, back-off 2 s · 2ⁿ ≤ 60 s"] --> F
  H -- yes --> D["Rename + .sha256 marker"]
  D --> All{"All 18 done?"}
  All -- no --> Q
  All -- yes --> Ready["Make 3D from a photo enabled"]
  K["App killed / phone restarted"] --> W{".install_wanted<br/>marker?"}
  W -- yes --> Q
```

</details>

## Photo → 3D pipeline

<p align="center"><picture><source media="(prefers-color-scheme: dark)" srcset="images/diagrams/pixal3d_pipeline-dark.png"><img src="images/diagrams/pixal3d_pipeline.png" width="640" alt="Pixal3D pipeline diagram"></picture><br><sub>Diagram · the on-phone Pixal3D pipeline</sub></p>

<details><summary>Mermaid source (GitHub draws this live)</summary>

```mermaid
flowchart TB
  subgraph R1["1 · Prepare the photo"]
    direction LR
    P["Photo"] --> M["U²-Net-P<br/>subject mask"] --> C["Crop 1024²<br/>black background"] --> F["MoGe-2<br/>field of view"] --> D["DINOv3-L + NAF<br/>image features"]
  end
  subgraph R2["2 · Generate the shape"]
    direction LR
    S["Structure flow<br/>8 steps by default"] --> SD["Structure decoder<br/>32³ voxels"] --> SH["Shape flow<br/>12 steps by default, sparse"] --> DEC["TRELLIS.2 decoder<br/>5 segments → mesh"]
  end
  subgraph R3["3 · Finish"]
    direction LR
    TX["Colour from the photo<br/>TextureBake"] --> G["Simplify → .glb"] --> AR["AutoRig3D → avatar"]
  end
  R1 --> R2 --> R3
```

</details>

More in [Photo → 3D](features/photo-to-3d.md).

## Long jobs: checkpoints, resume and time left

<p align="center"><picture><source media="(prefers-color-scheme: dark)" srcset="images/diagrams/resume_flow-dark.png"><img src="images/diagrams/resume_flow.png" width="640" alt="Checkpoint and resume diagram"></picture><br><sub>Diagram · how a photo → 3D run survives a kill, a dead battery or a reboot</sub></p>

A photo → 3D run takes minutes, so it is built to be interrupted. Everything it finishes is saved right away in a way that can't be half-written, and the next start continues from there.

<details><summary>Technical details</summary>

- **`core/Checkpoints.kt`:** `AtomicFiles.write` (temp → fsync → rename), `seal`/`unseal` (`MMCK` | length | payload | SHA-256), and `CheckpointStore` (two slots per key; `load` returns the newest valid one).
- **`threed/Pixal3DCheckpoint.kt`:** what is saved per stage (cond, ss per step, vox, shape per step, glb).
- **`threed/Pixal3DJob.kt` + `Pixal3DService.kt`:** the job record with fixed seeds, the foreground service with a wake lock, resume from `MainActivity.onResume` and `BootReceiver`, at most 3 attempts, assignment only after the atomic import.
- **`core/Eta.kt`:** `Pixal3DEta` (weighted stages, smoothed per-step times saved per device) and `TransferEta` (downloads: smoothed speed, queue position).
- **`core/StudioFlow.kt`:** Avatar Studio's saved state and its step rules (no Android code, so a desktop port can reuse it).
- **Tests:** `CheckpointsTest` (kill during a write, truncated and corrupt slots), `ResumeTest` (kill after every step → identical result; truncated newest checkpoint), `EtaTest`, `StudioFlowTest`, `Run3DPlanTest` (plan JSON and fixed seeds).

</details>

<details><summary>Mermaid source (GitHub draws this live)</summary>

```mermaid
flowchart TB
  subgraph A["Start"]
    direction LR
    P["Photo + run plan<br/>seeds fixed"] --> J["job.json<br/>sealed, atomic"] --> SV["Foreground service<br/>+ wake lock"]
  end
  subgraph B["Every stage and every step"]
    direction LR
    ST["Run one step"] --> CK["Checkpoint<br/>tmp → fsync → rename<br/>2 slots + SHA-256"] --> ETA["Time left<br/>smoothed per step"]
    ETA --> ST
  end
  subgraph C["Killed / battery dies / reboot"]
    direction LR
    RS["App opens or phone starts"] --> LD["Newest valid checkpoint<br/>damaged slot skipped"] --> CT["Continue at that step<br/>same result"]
  end
  subgraph D["Finish"]
    direction LR
    GLB["Finished .glb"] --> IMP["Atomic import<br/>mesh + avatar3d.json"] --> AS["Assign to the assistant<br/>then delete the job"]
  end
  A --> B --> D
  B -. interrupted .-> C --> B
```

</details>

<!-- nav -->
<p align="center"><sub><a href="settings-reference.md">← Settings reference</a> &nbsp;·&nbsp; <a href="README.md">Docs home</a> &nbsp;·&nbsp; <a href="status.md">Verification status →</a></sub></p>
<!-- /nav -->
