# Windows program

> *The same private, offline assistant on your PC: a resizable desktop widget, a tray icon that keeps it running, the phone app's own settings and Avatar Studio, and Photo → 3D on your CPU or NVIDIA GPU.*

<!-- nav -->
<p align="center"><sub><a href="privacy-permissions-storage.md">← Privacy, permissions and storage</a> &nbsp;·&nbsp; <a href="../README.md">Docs home</a></sub></p>
<!-- /nav -->

> **Status: in progress, not released yet.** There is no download button yet. It appears in the README only once the
> program has been run end to end on a real PC. Until then you can build it from source (below), or take the
> **test build** from the latest *Windows program* run under the repository's **Actions** tab. Each run lists the
> commit it was built from, a `SHA256SUMS.txt` and the program's own self-test reports.

## At a glance

| | |
|---|---|
| **What it is** | A normal Windows program (installer, Start-menu entry, uninstall in *Settings › Apps*), built from the same code as the phone app |
| **Needs** | Windows 10 or 11, 64-bit; 8 GB RAM for chat, 16 GB or more for Photo → 3D on the CPU. No admin rights (it installs for your user only) |
| **Offline** | Yes. Nothing leaves the PC unless you turn on the internet switch (the same switch as on the phone) or download a model or runtime |
| **GPU** | Optional. NVIDIA CUDA is the main GPU target. The AMD and Intel paths are built in but **untested** (see the table below) |

## Install

1. Run `MilkyMoosAssistant-<version>-windows-x64-setup.exe` (or the `-installer.msi`). There is also a portable
   `-portable.zip`: unpack it anywhere and run `MilkyMoosAssistant.exe`.
2. Windows SmartScreen may say *"Windows protected your PC"*, because the program is new and not code-signed. Click
   **More info › Run anyway** only if the file's SHA-256 matches `SHA256SUMS.txt` from the same build.
3. On the first start the settings window opens. **Settings › Downloads (one click)** gets the chat model, the voice,
   speech recognition and, if you want it, the 3D set. You see a progress bar, the time left and a **Retry** button for
   each part. Downloads resume after closing the program or a reboot, and every file is checked against its SHA-256.

Windows shows the version as `1.x.y` for app version `0.x.y`, because Windows installers can't start a version with 0.
The file names carry the real version.

## Using it

| Where | What you can do |
|---|---|
| **Desktop widget** | The phone widget's look: name art, the moving 2D or 3D avatar with its background, and a status line. Buttons: **Talk / End**, **2D / 3D**, **Read to me**, **⚙ settings**. Drag it by its body; resize it with the corner grip. It stays on screen when you change displays |
| **Tray icon** (by the clock) | Talk / End, Read to me (pause, play), Show / Hide widget, **Pin on top**, Widget 2D / 3D, Conversation, Avatar Studio, Settings, Quit. Hiding the widget or closing the settings window **doesn't stop** the wake word, listening, voice, reminders or a 3D build. Only *Quit* does |
| **Settings window** | The phone app's screens: Home, Conversation and Settings, with the same **Simple / Advanced** modes. That includes profiles, personalities and the prompt editor, memory and the directive tracker, reminders, Read to me with bookmarks, voices, backgrounds, Avatar Studio (separate 2D and 3D sections), and the build log with history and export |
| **Avatar Studio** | The same steps as on the phone. The framing and rig editors open as their own windows, and the mouse wheel zooms |
| **This PC** (Advanced settings) | The hardware found, the backend for Photo → 3D and for chat, GPU runtime downloads, *Start with Windows* and keep-awake |

Phone-only features are left out instead of showing dead buttons: the floating bubble, Android permissions, battery
optimisation, calls, texts, the flashlight and the camera. Ask for one and the assistant says plainly that a PC can't.

## Acceleration (backends)

| Backend | Photo → 3D (ONNX Runtime) | Chat (llama.cpp) | Status |
|---|---|---|---|
| **CPU** | ✔ bundled (plain RAM, chunked with checkpoints) | ✔ bundled | Supported. Built and self-tested in CI |
| **NVIDIA CUDA** | ✔ download: ONNX Runtime GPU 1.32.0 (155 MB) plus the CUDA runtime | ✔ bundled CUDA build plus the CUDA runtime | Main GPU target (RTX 20-series to 50-series, driver 580+). Built in CI; **first real-PC run pending** |
| **DirectML** (AMD / Intel / any DX12 GPU) | ✔ download (215 MB) | — | **Untested.** Nobody has run it on an AMD or Intel GPU |
| **Intel OpenVINO** | ✔ download (43 MB) | — | **Untested** |
| **Vulkan** | — (ONNX Runtime has no Vulkan path, so it falls back to the CPU) | ✔ bundled Vulkan build | **Untested** on AMD and Intel |
| **AMD ROCm** | Not on Windows (ONNX Runtime ships ROCm only for Linux; use DirectML) | — | **Untested** |
| **Auto** | CUDA if an NVIDIA GPU and its runtime are there, otherwise the CPU | the same | Never picks an untested path on its own |

The **NVIDIA CUDA 13 runtime + cuDNN 9** download (about 1.9 GB, straight from NVIDIA) is shared by Photo → 3D and
chat. If a GPU backend fails to start, the program falls back to the CPU and says why in the build log. Speech (the
wake word, recognition and voices) always runs on the CPU. A backend change takes effect after a restart; the page
has a **Restart now** button, and a 3D build carries on from its last checkpoint.

## Desktop settings (stored in `belle_desktop.json`)

| Key | Meaning | Default |
|---|---|---|
| `photo_backend` | Backend for Photo → 3D (`auto`, `cpu`, `cuda`, `dml`, `openvino`, `rocm`) | `auto` |
| `chat_backend` | Backend for chat (`auto`, `cpu`, `cuda`, `vulkan`) | `auto` |
| `widget_x`, `widget_y` | Widget position in dp (`-1` = bottom-right corner of the main screen) | `-1` |
| `widget_w`, `widget_h` | Widget size in dp | `260` × `340` |
| `widget_on_top` | Pin the widget above other windows | on |
| `widget_visible` | Widget shown (off = only the tray icon) | on |
| `start_with_windows` | Start in the tray when you sign in (a per-user Run entry, no admin) | off |
| `welcomed` | First start done (the settings window opens on the first start) | off |

Everything else (assistants, voices, avatars, memory and so on) uses the same settings as the phone. See the
[settings reference](../settings-reference.md).

## Where things are kept

| What | Where |
|---|---|
| Settings (one JSON file per assistant) | `%APPDATA%\MilkyMoo\profiles` |
| Models, avatars, books, memory, checkpoints, logs | `%LOCALAPPDATA%\MilkyMoo` |
| GPU runtimes and FFmpeg (downloaded) | `%LOCALAPPDATA%\MilkyMoo\runtimes` |

Memory is encrypted with a key that only your Windows account on this PC can unlock (DPAPI). Uninstalling the program
keeps these folders. Delete them yourself if you want everything gone.

## Self-test

`MilkyMoosAssistant.exe --selftest report.txt` loads every native part (ONNX Runtime and the 3D runner, llama.cpp,
the sherpa-onnx speech engine, Skia drawing), checks HTTPS and the data folders, writes `PASS` / `FAIL` lines to the
report and exits. No window opens and nothing is downloaded. CI runs it on every build.

## Build from source

```sh
./gradlew :desktop:run --args=--settings      # run (BELLE_NATIVES = a folder with the native libraries)
./gradlew :desktop:test                       # tests
./gradlew :desktop:packageMsi :desktop:packageExe :desktop:createDistributable   # on Windows
```

The native libraries are built with `desktop/native/ci/build-natives.ps1` (MSVC and Ninja; the CUDA compiler comes
from `fetch-cuda.ps1`). `.github/workflows/windows.yml` does the whole thing on GitHub's Windows machines. It runs the
tests with small real models, then runs the packaged program's `--selftest` from the zip and again after a silent
install.

## Roadmap

1. ◐ Widget, tray, settings, chat and voice on the CPU (runs from source; the CI installer build is being brought up)
2. ☐ Photo → 3D on the CPU on Windows, end to end
3. ☐ Real-PC test: CUDA for chat and Photo → 3D on an RTX card
4. ☐ ComfyUI workflow import (auto-fit to the backend and hardware, with overrides and reset)
5. ☐ First public release (installer, checksums, VirusTotal scan), then the download button in the README
6. ☐ AMD and Intel testers wanted for DirectML, OpenVINO and Vulkan
