# AGENTS.md: guide for coding agents and porters

This file is for AI coding agents (Claude Code, Copilot, Cursor, Codex and others) and for people porting the app.
`CLAUDE.md`, `.github/copilot-instructions.md` and `.cursor/rules/agents.mdc` all point here. **Read this file before
you change anything.** User documentation is in [docs/](docs/README.md), and every setting is listed in
[docs/settings-reference.md](docs/settings-reference.md).

`tools/publish/make_publish_copy.sh` checks this file. It fails if a Kotlin source file, a settings page or a
feature page is missing here, or if a settings key is missing from the settings reference. When you add any of these,
document it in the same change.

## Contents

1. [Purpose](#1-purpose)
2. [Feature inventory](#2-feature-inventory): what exists, where it lives, and what a port must keep
3. [Architecture and data flow](#3-architecture-and-data-flow)
4. [Rules](#4-rules): read before every change
5. [Mapping to other platforms](#5-mapping-to-other-platforms)
6. [Playbook for common requests](#6-playbook-for-common-requests)
7. [Tooling, build and tests](#7-tooling-build-and-tests)
8. [Status](#8-status)
9. [Explaining choices to users](#9-explaining-choices-to-users)

## 1. Purpose

**Milky Moo's Assistant** is a private voice assistant that runs entirely on the user's device, with no account, no
cloud, no analytics and no Google services. It provides a wake word, speech recognition, a local LLM with phone tools,
natural voices, encrypted long-term memory, several assistants with their own personalities, "Read to me" audiobooks,
and an animated 2D/3D avatar on Home, in a floating bubble and on a home-screen widget. It can also generate a 3D avatar
from one photo on the device (Pixal3D).

Non-negotiables: **local first** (the network is used only for downloads the user starts and an opt-in web switch),
**honest UI** (no fake progress, no claims that weren't measured), and **robust long jobs** (resume, never corrupt).

Platform today: Android 10 to 17 (minSdk 29, target/compile 37), Kotlin + Jetpack Compose, JDK 17, NDK 29.0.14206865,
ABIs arm64-v8a and x86_64. A **Windows program** (`desktop/`, Compose Desktop, same shared code) is in progress: see §2 *Windows program* and [windows](docs/features/windows.md).

## 2. Feature inventory

Paths are relative to `app/src/main/java/app/belle/assistant/` unless they start with another top-level folder.

### Settings, profiles and state

| What | Files | Invariants a port must keep |
|---|---|---|
| One assistant's settings | `Prefs.kt` | Every key is per assistant except those in `ProfileStore`. Setters clamp to the documented ranges. Every key is documented in `docs/settings-reference.md` (the publish check enforces this). |
| Several assistants | `ProfileStore.kt`, `core/Profiles.kt` | Exactly **one** assistant answers a wake phrase (priority order). Export/import never includes secrets; an import gets a new id. |
| Observable settings | `SettingsRev.kt` | The UI re-reads after every edit. Never cache a setting longer than one use. |
| Settings UI | `ui/SettingsScreen.kt` (pages), `ui/SettingsKit.kt` (Simple view and shared rows), `ui/Components.kt`, `ui/SettingsActivity.kt`, `ui/Theme.kt` | **Simple** and **Advanced** views. Page ids: `books`, `assistants`, `wake`, `conversation`, `sleep`, `voice`, `studio` (hidden, opened from Simple), `avatar`, `avatar3d`, `bubble`, `background`, `chimes`, `getmodels`, `model`, `memory`, `speech`, `internet`, `news`, `setup`, `look`, `privacy`. No dead controls: a control that can't work yet stays visible, greyed out, and says why. |
| Colours | `core/Palette.kt` | WCAG AA contrast, tested in `PaletteTest`. |

### Listening and talking

| What | Files | Invariants |
|---|---|---|
| Wake word | `core/WakeWord.kt`, `speech/SpeechEngines.kt` (`WakeWordEngine`) | Typed phrases need no training: they are tokenised for the sherpa-onnx keyword spotter. threshold = 0.35 − 0.2·sensitivity. A phrase that can't be used is shown as *Couldn't use*, never silently dropped. |
| Speech recognition | `speech/SpeechEngines.kt`, `speech/SpeechChunks.kt`, `core/ListenPolicy.kt` | Silero VAD + Moonshine Tiny. Audio is split into pieces of 8 s or less (the model fails above ~9.3 s). *Unlimited listening* is honoured on every path (`ListenPolicy`). |
| Sleep words | `core/RuleParser.kt`, `service/AssistantRuntime.kt` | Matched on the transcript: equal, suffix, or prefix + up to 2 words. |
| Brain | `core/Brain.kt`, `core/RuleParser.kt`, `core/Tools.kt`, `core/LlmOutputParser.kt`, `core/Action.kt`, `core/ActionRouter.kt`, `core/Intent.kt`, `core/NumberWords.kt`, `core/MiniJson.kt`, `core/TimedCall.kt` | Order: sleep → pending confirmation → directives → long form → rules (~70 patterns, under 1 ms) → LLM with a GBNF grammar that allows exactly one tool call. `Intent` vetoes tool calls that came from chat or roleplay. `TimedCall` makes sure a reply can never hang. |
| LLM runtime | `llm/LlamaEngine.kt`, `app/src/main/cpp/llm_core.cpp`, `llm_jni.cpp`, `core/ModelChoice.kt`, `core/ModelCatalog.kt` (in `shared/`) | llama.cpp on the CPU. One context, with KV-prefix reuse. Models are GGUF files with exact sizes and SHA-256 in the catalog. |
| Personality and prompt | `core/PersonaPrompt.kt`, `ui/PromptEditor.kt`, `core/Directives.kt` | Four editable sections per assistant, and a section left at its default follows the assistant's name. Standing directives are pinned into the prompt. |
| Long stories and songs | `core/LongForm.kt` | Streamed sentence by sentence while the model writes, with a loop guard. |
| Voices | `speech/SpeechEngines.kt` (TTS), `core/VoiceShift.kt`, `speech/VoicePreview.kt`, `speech/Sounds.kt` | Piper/Kokoro through sherpa-onnx. The masculine ↔ feminine slider shifts pitch and formants **separately** (no chipmunk effect). There is a 0.97 peak limiter. The speaker id is saved per voice. |
| Phone actions | `actions/AndroidPhoneActions.kt`, `actions/BelleAccessibilityService.kt`, `service/Reminders.kt`, `service/ReminderStore.kt`, `service/Receivers.kt` | Texts are read back and need *yes* first. Reminders survive a reboot (`reminders.json`, re-armed at boot). Accessibility acts only when asked and never logs the screen. |
| Default assistant | `service/VoiceInteraction.kt` | Can be the phone's digital assistant (long-press Home). |
| Web and news | `net/Net.kt`, `net/WebViewFetcher.kt`, `core/Web.kt`, `core/RateLimiter.kt` | `Net` is the **only** place that opens connections, and it refuses everything while *Internet access* is off (the default). HTTPS only, 1.5 MB cap per page, polite rate limits. |
| Memory | `core/Memory.kt`, `memory/SqlMemoryStore.kt`, `ui/MemoryPage.kt` | Kept per assistant, in SQLite with every text column encrypted (AES-256-GCM, Keystore key) and excluded from backups. Every entry is visible and deletable. |
| Read to me | `book/BookImport.kt`, `book/BookText.kt`, `book/Epub.kt`, `book/Library.kt`, `book/BookCommands.kt`, `service/BookReader.kt`, `service/BookPlaybackService.kt`, `ui/BooksPage.kt` | The whole book is never held in memory (streaming import). The text is read word for word, never rewritten by the model. Playback is a mediaPlayback foreground service. |
| Runtime and service | `service/AssistantRuntime.kt`, `service/AssistantService.kt` | A foreground `microphone` service with only the keyword spotter resident (~6 MB). Everything else loads on demand. |

### Looks

| What | Files | Invariants |
|---|---|---|
| 2D avatar | `core/Skin2D.kt`, `avatar/RigStore.kt`, `avatar/RigEditorActivity.kt`, `avatar/AvatarView.kt`, `avatar/LipSync.kt`, `ui/AvatarPages.kt` (`Avatar2DSteps`, `Avatar2DPage`, `assign2DPicture`) | A 20×20 mesh warp with linear-blend skinning. Lip sync follows the RMS of the audio that is actually playing. Each assistant keeps its own picture, rig and framing. |
| Bubble and listening light | `avatar/AvatarOverlay.kt`, `ui/AvatarPages.kt` (`BubbleWidgetPage`) | The bubble window is only as big as the avatar and is not focusable (it never blocks touches elsewhere). It shows the *current* assistant (the active one, or the one just called by name) and swaps the picture when that changes (`syncPicture`). |
| Framing | `core/Framing.kt`, `avatar/FramingEditorActivity.kt`, `avatar/EditorViews.kt` (the framing / rig canvases, shared with the PC editor windows) | Per assistant, and per surface (Home, bubble, widget) or the same for all. |
| Backgrounds | `avatar/Backgrounds.kt`, `avatar/BackgroundDecoders.kt` (the phone decoders, split out so the PC can plug in Skia + FFmpeg), `avatar/Cutout.kt`, `avatar/Av1.kt`, `avatar/Avif.kt`, `ui/BackgroundPage.kt`, `app/src/main/cpp/av1_jni.c` | Still pictures, GIF/WebP/AVIF and video. AVIF works on Android 10–11 through the bundled dav1d and our own container parser. |
| 3D avatar | `avatar/Avatar3DRenderer.kt`, `avatar/Avatar3DFiles.kt`, `core/Avatar3DSettings.kt`, `mesh/Mesh3D.kt`, `mesh/Import3D.kt`, `mesh/ProgressiveMesh.kt`, `mesh/TextureMips.kt`, `mesh/Orientation.kt`, `ui/AvatarPages.kt` (`Avatar3DSteps`, `Avatar3DPage`) | OpenGL ES 3 off-screen, one GL thread, bitmaps for every surface. If 3D is impossible, the 2D picture is used. One `avatar.glb` per assistant (standard glTF with the progressive-mesh tables and the rig in `extras`/`skins`). Detail levels come from the progressive mesh. Retro shaders: Retrostation (PS1) and Retro64 (N64). |
| AutoRig3D v6 | `mesh/AutoRig3D.kt` (+ `tools/rig_v6_notes.md`) | Works on any model, furry and anthro included. Two weight sets are mixed live by *animation strength*. Motion per part must stay at 95 % or more of the plain weighting, with no region stretching more than 1.15 (rig tests). **Never reduce animation to hide tearing** (see §4). Inner loops stay allocation-free (primitive arrays, `CellMap`, `BoneMix`, `LongHeap`): Android's runtime doesn't remove boxed temporaries, and boxed maps made the first-launch rig take minutes and stall the UI with garbage collection (`RigAllocationTest` budget). |
| Widget | `ui/AssistantWidget.kt`, `ui/WidgetIdleLoop.kt`, `core/WidgetPlan.kt`, `ui/NameArt.kt`, `ui/NameStyleSection.kt` | One widget type that **always renders the active assistant's own avatar** (`WidgetPlan.decide`: 2D with the 3D toggle greyed when that profile has no mesh). Every view uses `Prefs(ctx, activeId)`, never `Prefs(ctx)`, which follows a conversation's session profile. Cached idle loops are keyed by profile id and mode (`WidgetPlan.canReuse`). Call `AssistantWidget.avatarChanged()` after any avatar or active-profile change. Regression test: `WidgetPlanTest`. The idle loop is pre-rendered so the launcher animates it without waking the app. The name is drawn as a bitmap. There is no 3D-generation progress on the widget (so no countdown there). |
| Home and chat | `ui/HomeAndChat.kt`, `ui/MainActivity.kt` | The setup checklist, *Talk now*, and the listening switch. `MainActivity.onResume` resumes wanted downloads and a pending 3D job. |

### Avatar Studio

| What | Files | Invariants |
|---|---|---|
| Guided wizard (Simple) | `ui/AvatarStudio.kt`, `core/StudioFlow.kt` | Steps: 1 assistant (new or existing) → 2 picture → 3 2D or 3D → 4 Download all → 5 build. Every step shows **done / next / waiting + why**. Its state is saved atomically with a checksum in two slots and reopens where the user left off. A step counts as done only when what it produced still exists. The avatar is assigned **only after the final atomic commit**. It **reuses** the Advanced parts (step cards, `Install3DPanel`, `assign2DPicture`, `Pixal3DJob`); never fork them. |

### Photo → 3D (Pixal3D)

| What | Files | Invariants |
|---|---|---|
| Installer | `threed/ModelInstaller.kt`, `threed/Pixal3DInstall.kt`, `ui/Install3DPanel.kt`, `models/DownloadService.kt`, `assets/pixal3d_models.json` (made by `tools/publish/make_release_manifest.py`) | One button. 18 files, one at a time, each SHA-256 checked, with Range resume and 5 automatic retries. It resumes after a kill or reboot (`.install_wanted`). The DINOv3 licence must be accepted first. There is an offline folder import. |
| Pipeline | `threed/Pixal3D.kt`, `threed/FlowSampler.kt`, `threed/ShapeDecoder.kt`, `threed/TextureBake.kt`, `threed/P3Math.kt`, `threed/Ort.kt`, `threed/Port3D.kt`, `threed/Big.kt`, `app/src/main/cpp/ort_jni.c` | A port of the official ComfyUI workflow, checked stage by stage against fixtures. Each stage loads only its own model and frees memory between stages (`Ort.trim`). Desktop reference: 7.8 min and 3.27 GB peak. **Memory rule (§4.9):** the NAF map, every decoder level, the mesh, its colours and the clustering tables are `Big` native buffers; `Port3D.measure` records peak RSS, Java heap and native buffers per stage. A resumed run with a finished detailed shape goes straight to the surface. |
| Heap gate | `threed/HeapGate.kt`, `scripts/heap_gate.sh` | Release gate (§4.9). Runs only when `files/heap_gate/request.json` exists (root-only path). Results in `/workspace/releases/gates/<sha>/` and next to the release APKs. |
| Run plan and workflow import | `core/Workflow3D.kt`, `threed/Run3DPlan.kt` | An imported ComfyUI `.json` is **fitted** to the device. The table shows *workflow value → used → why*, and the run uses exactly that plan (`Pixal3D.samplerInputs`, tested). User overrides are per value, each with its own reset. |
| Background run, checkpoints, resume | `threed/Pixal3DJob.kt`, `threed/Pixal3DService.kt`, `threed/Pixal3DCheckpoint.kt`, `core/Checkpoints.kt`, `core/BuildJobRules.kt`, `service/Receivers.kt` (`BootReceiver`) | Failures are classified (`BuildJobRules`): an out-of-memory failure is recorded as such and is not retried by itself on the same version; a try that ended with no result is logged at the next start with Android's exit reason; after an app update the tries reset to 0 and the checkpoint (format unchanged since 0.1.3) resumes. Test: `CheckpointCompatTest` (a 0.1.3 job at detailed shape 20/20, tries 3 of 3). A foreground `specialUse` service with a partial wake lock. A sealed job record keeps the plan **with fixed seeds**. Checkpoints after every stage and every sampling step use temp → fsync → rename, two slots and a SHA-256, and a corrupt slot is skipped. Resume on app start and boot. A resumed run is **bit-identical** to an uninterrupted one. At most 3 attempts. Import is atomic, and the avatar is assigned only after it. |
| 3D build log, export, notifications | `core/BuildLog.kt`, `ui/BuildLogPanel.kt`, `threed/Pixal3DJob.kt`, `threed/Pixal3DService.kt` | Every build gets a `BuildRecord` (who it is for, start and end, stage reached, result or error, duration, peak RSS) in `files/pixal3d_history.ck`. The **target profile is explicit** and saved in the job; import refuses a deleted profile, and the model is kept as an orphan (`OrphanModel`, **Attach last generated model to…**). Done and failed notifications go on channel `pixal3d_result`. `KeepScreenOnWhileBuilding`, `BuildPrep.askBatteryOnce` and `BuildPrep.showOnWidget`. `DiagnosticsExport` uses an **allow-list** of event prefixes and redacts paths, URIs and emails. Never add conversation text to it. Tests: `BuildLogTest`. |
| Time left | `core/Eta.kt`, used by `Pixal3DJob`, `Pixal3DService`, `models/Downloads.kt`, `DownloadService`, `Install3DPanel` | Plain words, rounded up (1 / 5 / 10 min steps). 3D: **live measured step times win** after 1–2 steps; later stages are scaled by the measured speed factor; the first guess is the desktop × `PHONE_VS_DESKTOP` (10, from real OUKITEL WP100 TITAN traces; an untimed detailed step ≥ 0.8 × the run's rough step; step counts come from the pipeline's *N/M*). **Lean long** (×1.1). The display counts down and re-anchors only on big changes (never small jumps up). Times are saved per device after every step. Downloads use bytes left ÷ smoothed speed plus the queue position. Never show a number that isn't based on a measurement or the plan. Test: `EtaTest` (WP100 trace). |
| Progress & buttons | `ui/ProgressUi.kt`, `core/ProgressText.kt` | `ProgressBlock` (stage, *Step N of M*, %, time left, bar, then a **full-width Stop below**; it scrolls itself to the top of the page when it appears), `BtnText` (button labels: one line, capped at 1.5× font, ellipsis; keep labels short and put explanations in help text), `FieldRow` (field + button; stacks at large font) and `ButtonRow` (wrapping row). Tests: `ProgressTextTest`, `ProgressLayoutTest` (androidTest, font scale 1.0/1.3/2.0, widths 280–411 dp). |

### Models and downloads

| What | Files | Invariants |
|---|---|---|
| Catalog and downloads | `core/ModelCatalog.kt` (in `shared/`), `models/Downloads.kt`, `models/Fetcher.kt`, `models/ModelFiles.kt`, `models/ModelImport.kt`, `models/DownloadService.kt` | HTTPS with an allow-list checked on every redirect hop. Size and SHA-256 are checked before the rename. A sticky `dataSync` foreground service with wake and Wi-Fi locks. Downloads resume after a kill or reboot. Offline import by file type. The page is `ui/ModelsPage.kt`: a `LazyColumn` whose installed? / RAM / recommended-voice facts are loaded once on a background thread (`ModelsPageData`); progress ticks recompose only the visible rows. Total RAM is looked up once per process (`Downloads.totalRamGb`). |
| Companion downloader | `downloader/DownloaderActivity.kt` | An optional separate app. The *Open Downloader* button appears only when it is installed. |

### Windows program (`desktop/`)

The Gradle `:desktop` module (Kotlin/JVM, Compose Multiplatform Desktop, JDK 21 to build) compiles the phone app's own
sources **unchanged**: `syncSharedSources` copies the `sharedFromApp` list from `app/src/main/java` into the build, and
the Android APIs they touch are provided by small shims (below). Where the phone has an Android-only class, the desktop
has a **same-name twin** (`*Desktop.kt`, same package and members), so shared callers compile untouched. Rule: a fix
for a shared screen goes into the shared file, never into a desktop copy. Shared hooks: `core/Platform.kt` (phone or
PC: phone-only settings pages are left out on the PC, and "tap" reads "click"), `core/NativeLibs.kt` (the PC picks the
backend's library variant), `ui/UiState.kt` (observable settings / state counters).

| What | Files | Invariants |
|---|---|---|
| Start, single copy, self-test | `Main.kt`, `ui/SingleInstance.kt`, `SelfTest.kt` | One copy at a time (a loopback port in `instance.port`; a second start shows the first). `--tray` starts hidden, `--settings` opens settings, `--version`, `--selftest <report>` loads every native part and exits 0/1 (CI runs it on the packaged program). |
| Widget, tray, windows | `ui/DesktopApp.kt`, `ui/WidgetWindow.kt`, `ui/WidgetMode.kt`, `ui/SettingsWindow.kt`, `ui/EditorWindow.kt`, `ui/Screens.kt`, `ui/FileDialogs.kt`, `ui/Notify.kt`, `ui/UiThread.kt` | The widget window is **always composed**, so hiding it to the tray never stops wake word, listening, voice or builds. Its place is clamped to the screens (`widgetPlace`). The settings window hosts the phone's Home / Conversation / Settings screens. Notifications are tray toasts; the tray tooltip is the ongoing-progress notification. |
| Desktop-only settings | `DesktopPrefs.kt`, `ui/DesktopPages.kt` | Global (not per assistant), in `belle_desktop.json`; every key is in [windows](docs/features/windows.md). Extra pages are added through `PlatformPages` in the shared SettingsScreen. |
| Acceleration | `accel/Backend.kt`, `accel/Hardware.kt`, `Natives.kt`, `RuntimePacks.kt` | CPU always works (bundled). GPU runtimes are **downloaded on demand** from their publishers (NuGet, NVIDIA, FFmpeg builds) with pinned size + SHA-256, resume and per-part retry. Auto never picks an untested path; a failing provider falls back to the CPU and says so in the build log. Speech always uses the bundled CPU onnxruntime; the 3D runner gets the GPU one by full path (`BELLE_ORT_DLL`). |
| Platform layer | `Paths.kt`, `DesktopContext.kt`, `JsonPrefs.kt`, `DesktopLog.kt`, `platform/KeepAwake.kt`, `platform/SecretKeys.kt`, `platform/Win32.kt`, `platform/WinActions.kt`, `platform/Autostart.kt`, `platform/Chimes.kt`, `platform/ProcMem.kt` | Settings as JSON per profile, written temp → fsync → rename. Memory key wrapped with DPAPI. The PC is kept awake (`SetThreadExecutionState`) during builds, downloads and books. Start with Windows = an HKCU Run entry (no admin). |
| Twins of Android-only classes | `R.kt`, `actions/AndroidPhoneActionsDesktop.kt`, `avatar/BackgroundDecodersDesktop.kt`, `avatar/EditorsDesktop.kt`, `memory/SqlMemoryStoreDesktop.kt`, `models/DownloadServiceDesktop.kt`, `net/WebViewFetcherDesktop.kt`, `service/AssistantServiceDesktop.kt`, `service/BookPlaybackServiceDesktop.kt`, `service/RemindersDesktop.kt`, `threed/Pixal3DServiceDesktop.kt`, `ui/AssistantWidgetDesktop.kt`, `ui/MainActivityDesktop.kt` | Same class names and members as the phone ones. Phone-only actions (calls, texts, flashlight, camera) answer plainly that a PC can't, never pretend. Reminders re-arm at every start from `reminders.json`. |
| Native build and CI | `desktop/native/CMakeLists.txt`, `desktop/native/ci/build-natives.ps1`, `desktop/native/ci/fetch-cuda.ps1`, `.github/workflows/windows.yml` | Same C/C++ as the phone (`llm_core.cpp`, `ort_jni.c`). belle_llm in CPU / CUDA / Vulkan builds (`natives/`, `natives/llm-cuda/`, `natives/llm-vulkan/`), static CRT. CI builds on windows-latest, runs the desktop tests with small real models and the packaged `--selftest`, and uploads **artifacts only** (no release). |

Android API shims (`desktop/src/main/kotlin/`), one line per package. They implement only what the shared code calls,
on Skia / AWT / Java Sound / LWJGL:
- `android/`: Manifest
- `android/app/`: ActivityManager, AlertDialog · `android/app/role/`: RoleManager · `android/appwidget/`: AppWidgetManager (one desktop widget, id 1)
- `android/content/`: ComponentName, Context, DialogInterface, Intent, SharedPreferences
- `android/content/pm/`: ApplicationInfo, PackageManager · `android/content/res/`: AssetFileDescriptor, AssetManager, Resources
- `android/database/`: Cursor, ListCursor
- `android/graphics/`: Bitmap, BitmapFactory, Canvas, Color, ImageDecoder, Paint, Path, RectF (all on Skia; Typeface falls back through common system fonts)
- `android/graphics/drawable/`: Drawable
- `android/media/`: AudioAttributes, AudioFormat, AudioManager, AudioTrack, FaceDetector, MediaPlayer, RingtoneManager
- `android/net/`: Uri
- `android/opengl/`: EGL14, GLES30, GLUtils, Matrix (LWJGL, an offscreen GL context for the 3D renderer)
- `android/os/`: Build, Debug, Handler, Looper (the main looper is the AWT event thread), PowerManager, Process, SystemClock
- `android/provider/`: DocumentsContract, OpenableColumns, Settings
- `android/text/`: StaticLayout · `android/util/`: Log, Size · `android/webkit/`: MimeTypeMap · `android/widget/`: Toast
- `android/view/`: ScaleGestureDetector, View, WindowManager
- `androidx/activity/`: ComponentActivity · `androidx/activity/compose/`: ActivityResult · `androidx/activity/result/`: ActivityResult · `androidx/activity/result/contract/`: ActivityResultContracts
- `androidx/compose/material3/`: DynamicColor · `androidx/compose/ui/graphics/`: AndroidBitmap · `androidx/compose/ui/platform/`: LocalContext · `androidx/compose/ui/res/`: PainterResource · `androidx/compose/ui/viewinterop/`: AndroidView (hosts the custom-drawn views)
- `androidx/core/content/`: FileProvider
- `com/tom_roush/pdfbox/`: Aliases (PdfBox-Android names onto Apache PDFBox) · `com/tom_roush/pdfbox/android/`: PDFBoxResourceLoader · `com/tom_roush/pdfbox/io/`: Aliases · `com/tom_roush/pdfbox/text/`: Aliases · `com/tom_roush/pdfbox/pdmodel/interactive/documentnavigation/outline/`: Aliases

### Feature docs (each must stay in sync with the code)

[app-basics](docs/features/app-basics.md) · [wake-word](docs/features/wake-word.md) · [speech-recognition](docs/features/speech-recognition.md) ·
[thinking-model](docs/features/thinking-model.md) · [phone-actions](docs/features/phone-actions.md) · [web-and-news](docs/features/web-and-news.md) ·
[voices](docs/features/voices.md) · [memory](docs/features/memory.md) · [assistants-and-personalities](docs/features/assistants-and-personalities.md) ·
[stories](docs/features/stories.md) · [read-to-me](docs/features/read-to-me.md) · [avatar-studio](docs/features/avatar-studio.md) ·
[avatar-2d](docs/features/avatar-2d.md) · [avatar-3d](docs/features/avatar-3d.md) · [autorig3d](docs/features/autorig3d.md) ·
[widget](docs/features/widget.md) · [photo-to-3d](docs/features/photo-to-3d.md) · [models-and-downloads](docs/features/models-and-downloads.md) ·
[privacy-permissions-storage](docs/features/privacy-permissions-storage.md) · [windows](docs/features/windows.md)

## 3. Architecture and data flow

Diagrams with their Mermaid source are in [docs/architecture.md](docs/architecture.md).

- **Voice:** mic (16 kHz) → keyword spotter (always on) → wake → VAD + Moonshine (pieces of 8 s or less) → `Brain`
  (sleep / confirm / directives / long form / rules / LLM + grammar) → `Action` → `PhoneActions` or a reply → TTS
  (sentence streaming, voice shift, limiter) → speaker. Lip sync reads the same audio.
- **Memory:** every turn → encrypted SQLite → rolling summaries + facts → BM25 retrieval into the prompt's token budget.
- **Personalities:** `ProfileStore` picks the profile → `Prefs(ctx)` follows it → `PersonaPrompt` + directives + memory
  → system prompt.
- **Read to me:** file → streaming import → cleaned chapters on disk → `BookReader` reads a few sentences ahead →
  TTS → `BookPlaybackService` (media session).
- **Avatars:** picture → `RigStore` auto-rig → `Skin2D` mesh warp. Or `.glb`/`.obj`/`.ply` → `Import3D` →
  `ProgressiveMesh` + `TextureMips` + `AutoRig3D` → `avatar.glb` → `Avatar3DRenderer` (GL thread) → bitmaps for Home,
  the bubble and the widget.
- **AutoRig3D v6:** slice the body to find the neck, head, limbs, wings and tail → bones → two weight sets (smooth and
  contact-safe) → mixed live by animation strength → seam and contact passes that keep motion at 95 % or more.
- **Widget:** `AssistantWidget` (RemoteViews) + `WidgetIdleLoop` (pre-rendered frames in a ViewFlipper) + `NameArt`.
- **Pixal3D:** photo → mask → crop → MoGe-2 FOV → DINOv3 + NAF → structure flow → voxels → shape flow → 5-segment
  decoder → vertex colours → simplify → `.glb` → import (atomic) → assign → AutoRig3D. It runs inside
  `Pixal3DService`, with checkpoints after every step and ETA updates.
- **Installer:** manifest → `ModelInstaller` (one at a time, Range resume, retries, SHA-256) under `DownloadService`
  → `.sha256` markers → *N of 18 ready*.

## 4. Rules

1. **Generic fixes only.** Fix the cause for every model, every voice and every assistant. Never special-case one
   test asset, one character or one phone. If a fix only helps one input, it isn't done.
2. **Never cut animation to hide tearing.** If a mesh tears, fix the weights, seams or contacts. Don't lower motion,
   amplitude or detail. The rig tests require 95 % or more of the motion and a stretch below 1.15.
3. **Consistency over single runs.** Judge a change across all test models and several seeds or runs, not one lucky
   render. Report the worst case.
4. **No secrets and no personal data,** in code, tests, docs, screenshots or commits. No keys, tokens, signing
   passwords, real names, phone numbers, recordings or personal photos. `.gitleaks.toml` and the CI gitleaks job
   enforce part of this, and you are responsible for the rest.
5. **Licence duties.** Keep `THIRD_PARTY_NOTICES.md` and `licenses/` complete. DINOv3 needs the user's acceptance and
   the words "Built with DINOv3". eSpeak NG makes the APK GPL-3.0 as a whole. Show a model's licence before downloading
   it, and mark non-commercial models.
6. **Local first.** Every network call goes through `Net` (or the allow-listed `Fetcher` for user-started downloads).
   No analytics, telemetry or crash upload.
7. **Long jobs must survive interruption.** Write atomically (temp → fsync → rename), checksum checkpoints, resume,
   and assign results only after the final commit. A job names its **target explicitly** (the profile id is saved in
   the job, never "whoever is current at the end"). It keeps the device awake for its whole run and releases on every
   exit path, records itself in a history, and notifies the user on success or failure.
   Anything per-assistant that is cached (widget loops, previews) is keyed by profile id.
8. **Honest UI and docs.** Label every number with where it was measured. Never present emulator or desktop numbers
   as phone numbers. No dead buttons. **Layouts survive large text:** button labels use `BtnText` (one line, never
   wrapped letter by letter). Buttons side by side go in a `ButtonRow`, and never beside a weighted text or bar. A
   long job's Stop is full width under its `ProgressBlock`. Keep button labels short (about 20 characters); put the
   explanation in help text under the button. Check font scale 1.3 and 2.0, a large display size and a narrow width.
9. **Memory rule: nothing mesh-, image-stack- or tensor-sized on the Java heap.** Android caps an app's Java heap at
   256–512 MB *whatever the phone's RAM* (`largeHeap="true"` is set, but it is only a backstop: on the OUKITEL WP100
   TITAN, 11.2 GB RAM, that large heap is the 512 MB that 0.1.3 ran out of building the surface). Any array that grows
   with the model (voxels, vertices, triangles, features, NAF maps, model outputs) and could pass ~16 MB lives in native
   memory: `threed/Big.kt` (`Big`, `NativeMem`, `BigLongIntMap`; malloc'd by `ort_jni.c`, freed as soon as the next
   sub-step has its copy) and `Ort.Session.runBig` for big model outputs. No boxed `HashMap<Long, Int>` / `sortedBy` over
   millions of elements. **Release gate:** `scripts/heap_gate.sh` (run by `scripts/release.sh`, no skip switch) runs
   a cheap photo → 3D → rigged, attached avatar (1+1 steps, 512 px crop, 20k triangles) in the release APK on the emulator with
   `dalvik.vm.heapgrowthlimit = heapsize = 512m`, then again killed at the surface stage, the job rewritten as
   a 0.1.3 checkpoint (tries 3 of 3, no `app_version`) and resumed (the update resets the tries), and records peak
   Java heap / RSS / native buffers per stage. A failing gate means no release. Unit gate:
   `PostShapeHeapTest` (the post-shape chain at the size of the failing phone's model must allocate < 64 MB of Java heap).
10. **Docs in the same change.** New settings key → `docs/settings-reference.md` (generated table). New page or
   Kotlin file → this file. New feature → a page in `docs/features/`. The publish script fails otherwise.

## 5. Mapping to other platforms

| Android piece | Windows (first target) | Linux | macOS |
|---|---|---|---|
| Home-screen widget (`AssistantWidget`) | **Resizable, pin-on-top, borderless window** + **tray icon** (Talk, Read to me, 2D/3D, Avatar Studio, Quit) | Same window; tray through StatusNotifierItem | Same window; menu-bar item |
| Floating bubble | The same window in a small mode | same | same |
| Foreground services (mic, downloads, Pixal3D, books) | A background process started with the tray app (Task Scheduler entry for start at login); keep the PC awake during a 3D build with `SetThreadExecutionState` | systemd user service; `systemd-inhibit` | LaunchAgent; `IOPMAssertion` |
| SharedPreferences (`Prefs`) | JSON per profile in `%APPDATA%\MilkyMoo\profiles\<id>.json` (atomic writes) | `$XDG_CONFIG_HOME` | `~/Library/Application Support` |
| App files (`files/…`) | `%LOCALAPPDATA%\MilkyMoo\` | `$XDG_DATA_HOME` | `~/Library/Application Support` |
| Memory encryption (Keystore) | DPAPI-protected key | libsecret | Keychain |
| Audio (AudioRecord/AudioTrack) | WASAPI (or miniaudio) | PipeWire/PulseAudio (miniaudio) | CoreAudio (miniaudio) |
| Accessibility actions | UI Automation (opt-in) | AT-SPI (opt-in) | AX API (opt-in) |
| Phone actions (call, SMS) | Not available: say so plainly; keep timers, reminders, apps, music, notes | same | same |
| Notifications | Toast notifications | libnotify | UserNotifications |

**Backends** for llama.cpp and ONNX Runtime, in this order: **CPU (chunked)** first, so it works everywhere, then
**CUDA** (NVIDIA), **ROCm** (AMD), **DirectML** (any DX12 GPU on Windows), **Vulkan**, **OpenVINO** (Intel),
**Metal/CoreML** (macOS). Add an **Auto** choice that probes what works and falls back to the CPU. Show which backend
is in use, and why.

**One-click setup:** like the phone, one button installs what the machine can run (probe RAM, VRAM and the backend;
choose a model, a voice and the 3D set), with SHA-256 checks, resume and a time left.

**Reuse the native code:** `app/src/main/cpp/llm_core.cpp` (llama.cpp wrapper) and `ort_jni.c` (the ONNX Runtime
runner) are plain C/C++ behind thin JNI layers. Build them as shared libraries for the desktop. The pure-Kotlin parts
(`core/*`, `threed/FlowSampler.kt`, `P3Math.kt`, `Run3DPlan.kt`, `core/Eta.kt`, `core/Checkpoints.kt`,
`core/StudioFlow.kt`, `book/BookText.kt`) run on the JVM unchanged (they are unit-tested there). Compose Multiplatform
Desktop can reuse most of `ui/`.

**Avatar Studio on Windows:** the tray menu's *Avatar Studio…* opens the same five steps in a window. The 3D build
runs in the background process, with a tray progress tooltip and a notification countdown. Resume works the same way
(checkpoints in `%LOCALAPPDATA%`).

## 6. Playbook for common requests

Each entry: steps → build/test → done when.

### "Build it for Linux" (or Windows/macOS)
1. Make a desktop module (Kotlin/JVM + Compose Desktop) that depends on the pure-Kotlin packages listed in §5.
2. Build `llm_core.cpp` and the ONNX Runtime runner as shared libraries with CMake for the target, CPU backend first.
3. Implement the platform layer from the §5 table (window/tray, storage, audio, background process, notifications).
4. Port the settings pages that apply. Hide phone-only actions with a clear note instead of dead buttons.
- **Build/test:** `./gradlew :desktop:test :desktop:packageDistributionForCurrentOS`; run the JVM unit tests of the reused packages.
- **Done when:** wake word → reply → voice works offline; the window/tray works; settings persist; a photo → 3D build
  survives killing the process mid-run; the docs and this file are updated.

### "Add GPU support"
1. Add a backend enum (CPU, CUDA, ROCm, DirectML, Vulkan, OpenVINO, Metal/CoreML, Auto) to the settings and the docs.
2. llama.cpp: build with the matching `GGML_*` option, and choose layers to offload from the free VRAM. ONNX Runtime: register the execution provider and fall back to the CPU on failure.
3. Auto: probe in order, run a tiny warm-up, keep the first that works, and remember it per device.
- **Build/test:** the unit tests, plus the Pixal3D end to end on each backend (`-Dp3d.e2e=1`, see `tools/pixal3d_port/README.md`); compare the outputs against the CPU (voxel IoU ≥ 0.88, chamfer ≤ 0.5 %).
- **Done when:** it is faster than the CPU on that backend, the outputs match within tolerance, failure falls back to the CPU with a visible note, and the ETA learns the new speed.

### "Only the 3D generation"
1. Keep `threed/*`, `core/Checkpoints.kt`, `core/Eta.kt`, `core/Workflow3D.kt`, `mesh/*` (for import and the rig) and the installer.
2. A minimal UI: install → pick a photo → build (with time left) → export the `.glb`.
- **Build/test:** `ResumeTest`, `Run3DPlanTest`, `FlowSamplerTest`, `ModelInstallerTest`, the Pixal3D end to end.
- **Done when:** a photo becomes a `.glb` offline, resumes after a kill, and the licence gate is kept.

### "Make it smaller"
1. Measure first (APK analyser, `du` of the files on the device). The APK is ~45 MB arm64; the models are downloaded.
2. Options: drop an ABI (ship arm64 only), use int8 models, smaller default models, R8 is already on, check what is in `assets/`.
- **Done when:** the size went down, nothing changed for the user except what you said, and the docs' sizes are updated.

### "Add a setting"
1. Add a property to `Prefs.kt` (clamped), a control on the right page, a row in `tools/publish/docs_gen/gen_settings.py`
   (then run it to regenerate `docs/settings-reference.md`, and `nav.py` for the back/next links), and a line in the feature page.
- **Done when:** the publish check passes, and the control works or says why it can't.

## 7. Tooling, build and tests

```sh
scripts/fetch_models.sh                 # wake-word model + U²-Net-P into app/src/main/assets/models
./gradlew :app:testDebugUnitTest        # JVM unit tests (heavy ones opt in with -Dp3d.*, -Dspecks.*, -Drig.*)
./gradlew assembleDebug                 # debug APKs (arm64-v8a, x86_64)
./gradlew :app:connectedDebugAndroidTest # Compose layout tests on a device/emulator (large font scales); screenshots in
                                        # app/build/outputs/connected_android_test_additional_output/;
                                        # MainThreadStallTest (no main-thread disk access, no message > 100 ms on a phone);
                                        # RigBenchTest only with -Pandroid.testInstrumentationRunnerArguments.rigbench=1
scripts/heap_gate.sh <x86_64 apk> <dir> # release gate: 1+1 steps, 512 px, 20k tris, 512 MB Java heap, + post-shape 0.1.3-format resume
scripts/release.sh                      # maintainer: clean-worktree release build, HEAP GATE, signing check, SHA256SUMS, stable names;
                                        # commits dist/INSTALL.txt and dist/SHA256SUMS.txt for the new release
scripts/emulator_test.sh                # emulator smoke test
./gradlew :desktop:test                 # desktop tests (speech/chat need BELLE_NATIVES + -Ddesk.models / -Ddesk.gguf)
./gradlew :desktop:run --args=--settings # run the Windows program from source (BELLE_NATIVES, BELLE_HOME)
./gradlew :desktop:packageMsi :desktop:packageExe   # the Windows program's installers (on Windows)
tools/publish/make_publish_copy.sh      # public copy with link and AGENTS.md coverage checks (never pushes)
python3 tools/security/test_security.py # security tooling tests
```

- Kotlin JVM 17, Compose. Native code: `app/src/main/cpp` (CMake; 16 KB page alignment). Third-party code lives in `third_party/`.
- CI (`.github/workflows/`): CodeQL, gitleaks, OSV-Scanner, Scorecard, VirusTotal on release, the docs site (manual), and the Windows program build (`windows.yml`, artifacts only). Every action is pinned to a commit SHA. See [docs/SECURITY.md](docs/SECURITY.md).
- Pixal3D export and port: `tools/pixal3d_port/` (PyTorch → ONNX, int8, fixtures, `ref_pipeline.py`, `make_bundle.py`).
- Host LLM test harness: `tools/host_llm/`. Rig notes: `tools/rig_v6_notes.md`.

## 8. Status

See [docs/status.md](docs/status.md) for the honest table. In short: the app is used daily on Android, and the 3D
widget is verified on a real phone. Photo → 3D is verified end to end on a desktop CPU, but a phone run has not yet
been timed to the end. Its resume logic is verified by unit tests (kill after every step, truncated checkpoints).
The Windows program is in progress: it runs from source with the widget, tray, settings, chat and voice on the CPU, and CI builds and self-tests the installer; GPU (CUDA) and photo → 3D on a real PC have not been run yet. See [windows](docs/features/windows.md) for the tested / untested table.

## 9. Explaining choices to users

- Say what you changed, why, and what it costs (size, speed, battery), in plain words first, then the technical detail.
- Give numbers with where they were measured ("desktop CPU", "emulator", "your phone").
- If something can't work on a platform (for example SMS on Windows), say so plainly and offer the closest thing that does work.
- If you are unsure, say so and say how to check. Never claim a feature works on hardware you didn't run it on.
- When you trade quality for speed (fewer steps, fewer triangles), show it in the UI (like the *workflow → used → why* table) rather than hiding it.
