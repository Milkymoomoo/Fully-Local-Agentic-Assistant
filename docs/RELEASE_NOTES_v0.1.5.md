# Milky Moo's Assistant 0.1.5 (pre-release)

A private, offline Android voice assistant that runs entirely on your phone: no cloud, no accounts, no API keys and no
Google services.

> [!WARNING]
> **In development:** on-device, from-scratch image-to-3D-mesh generation (Pixal3D on the phone) is still in development
> and **may not work**. Built-in and downloadable 3D models (via *Settings* / *Avatar Studio* install) work.

## What's new in 0.1.5
* **Photo → 3D memory fix:** every stage after the shape (surface decoder, texture bake, vertex clustering) now runs in
  native memory instead of the Java heap, which 0.1.3 ran out of at *Building the surface* on a real phone.
  Verified by a unit test (`PostShapeHeapTest`, 6.7 MB of Java heap); **not yet verified on a phone**, and the 512 MB
  heap release gate was **skipped** for this release.
* **Resume a 0.1.3 build:** installing over 0.1.3 resets the try count, and **Resume** continues a saved 0.1.3
  checkpoint from the surface stage instead of starting over.
* **Honest failure labels:** an out-of-memory failure is labelled as such and is not retried blindly on the same version;
  every failed try logs its real error or Android's exit reason (no more "app closed" for an OOM).
* **Downloads page freeze fixed:** *Settings › Downloads (one tap)* opens instantly (lazy list, device facts loaded once).
* **Faster built-in avatar rigging:** the first-launch *Rigging bones* step for the built-in avatars is much faster and
  uses far less memory.

## Downloads
| File | For |
|---|---|
| `MilkyMoosAssistant-0.1.5-3b2686c-arm64-v8a.apk` (44.8 MB) | almost every phone (64-bit ARM) |
| `MilkyMoosAssistant-0.1.5-3b2686c-universal.apk` (85.8 MB) | if unsure (arm64 + x86_64) |
| `MilkyMoosAssistant-0.1.5-3b2686c-x86_64.apk` (46.3 MB) | emulators, Chromebooks |
| `SHA256SUMS.txt` | checksums |

## Install
1. Android 10 or newer. Download the **arm64-v8a** APK on the phone (or `adb install -r <apk>` from a PC).
2. Optional: check it against `SHA256SUMS.txt` (`sha256sum -c SHA256SUMS.txt`, or `Get-FileHash` on Windows).
3. Open it and allow installing from this source when Android asks (sideload; it isn't on Google Play).
4. Updating from 0.1.x: install **over** the existing app, don't uninstall, or you lose your assistants and saved builds.
5. It works fully offline and needs no Google Play Services (no GMS). Optional models and voices are one-tap downloads
   inside the app; the 3D generation set comes from the [`models-3d-v2`](https://github.com/Milkymoomoo/Fully-Local-Agentic-Assistant/releases/tag/models-3d-v2) release.

## Licences
App code: Apache-2.0. The APKs include eSpeak NG (GPL-3.0) through sherpa-onnx, so each APK as a whole is distributed
under the GPL v3; the corresponding source is this tag plus sherpa-onnx v1.13.8. Photo → 3D uses DINOv3 under Meta's
DINOv3 License, which you accept in the app before it downloads. See THIRD_PARTY_NOTICES.md.
