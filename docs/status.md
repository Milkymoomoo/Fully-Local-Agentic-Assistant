# Verification status

> *What has been checked, where, and how: real phone, emulator, desktop test, or not at all.*

<!-- nav -->
<p align="center"><sub><a href="architecture.md">← Architecture</a> &nbsp;·&nbsp; <a href="README.md">Docs home</a> &nbsp;·&nbsp; <a href="troubleshooting.md">Troubleshooting →</a></sub></p>
<!-- /nav -->

## How to read this

| Mark | Meaning |
|---|---|
| 📱 | Seen working on a **real phone** (evidence in this repo) |
| 🖥️ | Checked on the **Android emulator** (x86_64, software graphics, much slower than a phone) |
| 🧪 | Covered by **automated tests** on a desktop computer (JVM unit tests, a local test server, or desktop runs of the same code) |
| ⚪ | **Not verified** for this release |
| ◐ | Partly seen on a real phone, as reported by the developer, with no recording in this repo |

Numbers measured on an emulator or desktop are labelled that way on every page and are **never phone numbers**.

## Feature by feature

| Feature | 📱 Real phone | 🖥️ Emulator | 🧪 Tests | Not verified |
|---|---|---|---|---|
| [Wake word](features/wake-word.md) | | ✓ starts and listens (ready in 68.5 s on the emulator) | phrase routing, sensitivity maths | false-wake rate, phone start-up time |
| [Speech recognition](features/speech-recognition.md) | | ✓ rule reply in 4.5 s end to end | long-request splitting, listen policy | phone latency, accuracy |
| [Thinking model](features/thinking-model.md) | | rule replies only | prompt build, tool grammar, retries, parrot check | phone reply speed |
| [Phone actions](features/phone-actions.md) | | ✓ open app, open settings, alarm, timer, volume, flashlight, device info | rule parser; reminder store (restart, late, re-arm) | calls, texts, calendar, navigation on a phone; reminder after a reboot on a real phone |
| [Web and news](features/web-and-news.md) | | | live web test from a desktop, parsers | web answers on a phone |
| [Voices](features/voices.md) | | ✓ voice loads (77 s on the emulator); Kokoro speaker picker saves per voice; sample refused with the internet off | voice shift, audio limiter, speakers, sample decision | phone load time |
| [Memory](features/memory.md) | | | assembler budgets, summarizer, directives, encryption | |
| [Assistants](features/assistants-and-personalities.md) | | ✓ pages render | profile router, export/import | |
| [Stories](features/stories.md) | | | long-form length, loop guard | phone timing |
| [Read to me](features/read-to-me.md) | | ✓ library page | import, cleaning, chapters, library | background playback on a phone |
| [2D avatar](features/avatar-2d.md) | | ✓ bubble | rig maths | bubble docking on phones |
| [3D avatar](features/avatar-3d.md) | ✓ on the widget | ✓ Home and bubble; frame times measured | renderer, detail levels, shaders | phone frame rate, battery |
| [AutoRig3D](features/autorig3d.md) | ✓ rigged model on the widget | | rig sheets, stretch, jaw, bust tests | motion quality judged visually only |
| [Widget](features/widget.md) | ✓ [screenshot](screenshots/phone_widget_home.png) | ✓ tap areas, idle frames | moving-picture switch logic | frame rate, battery |
| [Photo → 3D](features/photo-to-3d.md) | ◐ OUKITEL WP100 TITAN (0.1.3): both shape stages finished, then out of Java memory at the surface (fixed in 0.1.5, not yet re-run on the phone) | 0.1.5 release gate skipped (user request; software-CPU emulator still in *Finding the subject* after 16 min, not hung). Future releases use a cheaper gate (1+1 steps, 512 px, 20k triangles, resume from a snapped post-shape / 0.1.3 checkpoint) | full pipeline on a desktop CPU (7.8 min, 3.27 GB peak); installer against the real release with resume; installer order/retry/resume tests; **resume after a kill at every step gives an identical result**; truncated/corrupt checkpoints skipped; time-left maths **no finished run on a phone**; resume after a real reboot on a phone |
| [Avatar Studio](features/avatar-studio.md) | | ✓ pages render | step rules, saved state with a damaged save | a full Studio build on a phone |
| [Models and downloads](features/models-and-downloads.md) | | ✓ one-tap downloads | fetcher, checksum, retries, time left | resume after reboot on a real phone |
| [Settings](settings-reference.md) | | ✓ every page rendered ([screens](images/screens)) | | |

<details><summary>Technical details</summary>

- **Emulator:** Android API 29 x86_64 image with SwiftShader software GPU and TCG CPU emulation (no KVM), 1080×2280. Proof logs and renders are kept in the development repository (`proof/api29/`).
- **Tests:** JVM unit tests in `app/src/test` (about 50 test classes), run with `./gradlew :app:testDebugUnitTest`. Heavy tests (the Pixal3D end to end, live web, live release install) need opt-in system properties or native libraries, and are skipped otherwise.
- **Desktop Pixal3D run:** Intel Xeon, 4 threads pinned, ONNX Runtime 1.28, with exactly the phone's model files and Kotlin code through the app's JNI runner (`Pixal3DEndToEndTest`).
- **Real-device evidence** in this repo is limited to the widget screenshot. Other features were used on the developer's phone during development, and the bug reports from that led to fixes, but no phone measurements were recorded for this release.

</details>

## Known gaps found while writing these docs

| Gap | Effect | Status |
|---|---|---|
| Reminders weren't re-scheduled after a reboot | A voice reminder set before a restart never fired | **Fixed** (0.1.2): saved in `reminders.json`, re-armed at boot, after an update and on app start; late ones fire with their due time |
| No speaker choice for multi-speaker voices | Kokoro / LibriTTS always used speaker 0 | **Fixed** (0.1.2): *Speaker* card, saved per voice |
| `mem_budget_mb` setting | Stored but unused | **Removed** (0.1.2) |
| Widget `animate` flag | Read but never set | **Fixed** (0.1.2): *Moving picture* switch on the Avatar page |
| ▶ voice samples bypassed the internet switch | A small download with *Allow internet access* off | **Fixed** (0.1.2): no download while off; kept samples still play |
| Manifest comment said "three" internet uses | There are four | **Fixed** (0.1.2) |
| Leftover ComfyUI workflow code and files | Dead code; old files stayed on the phone | **Removed** (0.1.2), old files deleted on app start |
| Photo → 3D *ComfyUI workflow* editor | Its values were never used by the phone run | **Fixed** (0.1.3): back as *Workflow & run settings*; the imported workflow is fitted to the phone and the run uses exactly the plan the table shows (tested) |
| Photo → 3D stopped if the app was closed | A long run was lost | **Fixed** (0.1.3): foreground service + wake lock, per-step checkpoints, resume after a kill or reboot |
| No idea how long a 3D run or the downloads would take | Hard to plan | **Fixed** (0.1.3): live *time left* in the app and the notification |
| Making an avatar meant visiting several pages | Easy to get lost | **Fixed** (0.1.3): Avatar Studio in Simple settings; Advanced pages split into *2D avatar*, *3D avatar*, *Bubble & widget* with numbered steps |
| 0.1.0: a photo → 3D build could finish without reaching the assistant it was for | Milly had no 3D model; the finished model was stranded | **Fixed** (0.1.3): explicit *Build it for*, saved in the job; import only to that assistant; **Attach last generated model to…** recovers stranded ones (`BuildLogTest`) |
| 0.1.0: widget rendered another assistant's 3D model | Active 2D-only Milly showed as Bob in 3D | **Fixed** (0.1.3): widget always draws the active assistant, 2D when they have no mesh (3D toggle greyed), caches keyed by profile, refresh on every avatar/profile change (`WidgetPlanTest`) |
| 0.1.3: *Stop* squeezed beside the Make button, one letter per line (OUKITEL WP100 TITAN) | Unreadable control while a build ran | **Fixed** (0.1.4): status block at the top, full-width Stop under it; one-line button labels and wrapping button rows app-wide; checked at font scale 1.3/2.0 (`ProgressLayoutTest`) |
| 0.1.3: 3D time left far too short and jumping up and down (WP100: "30 min" with ~1.5 h left) | Couldn't plan around a build | **Fixed** (0.1.4): live step times, later stages scaled, leans long, countdown that only re-anchors on big changes. In the detailed stage, *2/20* showed 43 min with ~1 h 25 min left; now a detailed step counts as at least 0.8 × a rough one until it is timed (`EtaTest` replays both traces) |
| Plan's "min of sampling" before the first run used desktop speed | Looked ~10× too short on a phone | **Fixed** (0.1.4): shown × 10 and marked *(first guess)* until this phone has timed a run. The step **fit** (time budget) still uses desktop speed until a run on this phone finishes (open) |
| No record of past 3D builds | Hard to tell what happened to an unattended run | **Fixed** (0.1.3): build log & history, *Export log*, done/failed notifications |
| 0.1.4: *Downloads (one tap)* page slow to open: every model row built at once, each asking the system for the RAM size several times | Emulator: about 11 s frozen (release build); debug build "not responding" | **Fixed** (0.1.5): a lazy list, the installed / RAM facts loaded once on a background thread, RAM looked up once per app start. `MainThreadStallTest`: no main-thread disk access; on the (unaccelerated) emulator the page opens about as fast as the plain Privacy page |
| 0.1.4: first launch sat at *Rigging bones 82 %* for many minutes while preparing the built-in avatars | The 3D page looked stuck; the app was sluggish meanwhile | **Fixed** (0.1.5): the auto-rig's inner loops no longer create boxed objects (1.3 → 0.3 GB of garbage for Bob, 2.7 → 0.4 GB for Boob Dragon, identical result); the page says *Preparing the built-in 3D avatars*; it always ran in the background. The emulator here has no hardware acceleration, so its minutes don't reflect a phone |
| 0.1.3: photo → 3D failed at *Building the surface* on the OUKITEL WP100 TITAN (Java heap out of memory at the 512 MB limit) | ~2 h of work stopped short | **Fixed in code** (0.1.5): decoder, bake and clustering buffers in native memory; `PostShapeHeapTest` (6.6 MB Java heap on a desktop). The on-emulator 512 MB release gate (`heap_gate.sh`) was **skipped for 0.1.5** at the user's request and has not been re-run on the phone |
| 0.1.3: tries 2 and 3 failed with no reason in the log | Couldn't tell what happened | **Fixed** (0.1.5): every failed try logs its real error or Android's exit reason; out of memory is labelled as such and not retried on the same version |
| 0.1.3: after 3 failed tries the saved build couldn't be resumed | The finished shape steps were stuck | **Fixed** (0.1.5): an update resets the tries and **Resume** continues a 0.1.3 checkpoint at the surface (`CheckpointCompatTest`) |
| The home-screen widget shows no 3D progress | No countdown there | Open: the notification and the app show it |
| 3D page: *Make 3D from a photo* hidden until installed | Hard to find | **Fixed**: always visible, with an *N of 18 files ready* counter |
| 3D generation install listed in two places | Confusing | **Fixed**: one place (3D page), linked from Downloads |
| *Open Downloader* buttons when the companion app isn't installed | Dead button with a contradictory message | **Fixed**: shown only when installed; *One-tap download* added |
| In-app Privacy page said models are never downloaded and no cookies are kept | Out of date | **Fixed** |
| Downloads stopped if the app was killed or the phone restarted | Had to start again by hand | **Fixed**: resume markers, sticky service, boot handling, wake + Wi-Fi locks |

<!-- nav -->
<p align="center"><sub><a href="architecture.md">← Architecture</a> &nbsp;·&nbsp; <a href="README.md">Docs home</a> &nbsp;·&nbsp; <a href="troubleshooting.md">Troubleshooting →</a></sub></p>
<!-- /nav -->
