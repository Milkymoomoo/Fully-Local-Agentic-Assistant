# Pixal3D port — working notes (restart here)

Resumed 2026-11-19 01:30 EST after rig v6 (release 7717156). Previous state: commits 55bf1f0 / c3adf64, README.md here.
Work dirs on the box: /workspace/port3d (ONNX files: onnx/, onnx_opt/ (ORT-optimised, mmap-able), onnx_q8/ (int8 decoder
seg0/seg1 via q8.py), hostjni/ (libbelle_ort.so for the desktop JVM test), logs/).

## Baseline (e2e 2026-11-18, logs/e2e_lowmem.json): 26.5 min, peak 5.9 GB @4 thr
- ss flow 854 s (12 steps), shape flow 627 s (20 steps), decoder seg0 27 s peak 5.9 GB (fp32 seg0 1 GB).
- RSS never comes back between stages (base climbs 0.27 → 2.6 GB): glibc keeps freed memory.

## Goals (t-brief 01:28): peak well < 4 GB, faster, measure on arm64 emulator / phone-class config, check quality vs
reference + look at renders, model delivery to the phone + total size, release.sh when usable.

## Environment facts
- No arm64 system image; /dev/kvm not accessible to user box (root:pulse-access) → Android emulator only in software
  (TCG), far too slow for a 25-min job. "Phone-class config" = desktop JVM e2e, 4 threads pinned (taskset), memory
  measured as process peak RSS (and capped with a cgroup if possible).

## Log
### 01:55
- Measured (python ORT 1.28, 4 thr pinned): ss flow forward 19–21 s, process peak 2.83 GB = 1.48 GB prepacked int8 weights
  + ~1.3 GB activations (grows inside block 0's attention + MLP, then steady). Not glibc fragmentation (MALLOC_MMAP_THRESHOLD_
  test), not op ordering (tried reorder / gating: no change). disable_prepacking: weights stay file-mmapped but +35 % time.
- FastGelu fusion (/tmp/fastgelu.py → onnx_opt/ss_fg.onnx, shape_fg.onnx): same accuracy vs the captured ComfyUI calls
  (ss rel 0.43–2.4 %, shape 0.79–2.6 %, identical to unfused), 4–6 % faster. Random-input comparisons are chaotic (int8
  activation rounding) — only compare on the captured calls.
- int8 decoder seg0/seg1 (onnx_q8, q8.py): exact latent → mesh chamfer 0.32 % bbox, F@1 % 0.998 vs reference; 20.6 s.
- JNI: malloc_trim(0) / mallopt(M_PURGE) after each session close (e2e base was creeping to 2.6 GB). Host lib rebuilt:
  gcc -O2 -shared -fPIC -I port3d/hostjni -I third_party/ort_headers -I $JDK/include{,/linux} ort_jni.c -ldl
- Phone model set: /workspace/port3d/phone (symlinks): 4.75 GB (was 6.6): opt+FastGelu flows, q8 seg0/1, opt rest, moge nb8.
- e2e #1 with phone set running (log /tmp/e2e1.log, json → /tmp/e2e1.json). ss step-count test (8, 6 steps) → /tmp/ss_steps_*.log,
  IoU via /tmp/ss_iou.py (12-step port latent: IoU 0.902 vs reference voxels).
### 02:20
- e2e #1 (phone set, 12/20 steps, with other jobs on cores 4-7): 19.2 min, peak 5.9 GB — at decoder seg4: the ORT-'basic'
  optimised seg2-4 use far MORE memory (seg4 N=590k: 4.4 GB vs 1.05 GB original; seg3 2.6 vs 1.07). Phone set now uses the
  original onnx/ seg2-4. q8 seg1 is slower and bigger at run time (12 s, 2.3 GB) than fp32 opt seg1 (4.2 s, 1.48 GB) → fp32
  opt seg1; q8 seg0 kept (1.4 s, 1.04 GB). Set = 5.29 GB.
- Steps: ss 8 steps IoU 0.890 (12: 0.902, 6: 0.861); shape 12 steps mesh chamfer 0.359 %/F 0.990 (20: 0.36 %/0.989, 10: 0.362 %).
  → FlowSampler.Params.STRUCTURE_PHONE (8) / SHAPE_512_PHONE (12) used by Pixal3D.generateFrom.
### 02:20 (restart)
- e2e #2 running (log /tmp/e2e2.log, results copied to /tmp/e2e2.{json,glb}, /tmp/e2e2_mesh.{f32,i32} when done): phone set
  with original seg2-4, fp32-opt seg1, 8/12 steps, no other jobs (cores 0-3 pinned). RSS after ss stage back to 495 MB (trim works).
  (Note: e2e #2's compiled code predates dropFloaters.)
- Quality tools: /tmp/mesh_cmp.py <prefix> (chamfer/F vs ref/mesh512.pt; copy in tools/pixal3d_port/flow), port3d/compare_glb.py
  ours.glb art/dragoness_pixal3d.glb art/dragoness.png out.png. e2e #1: chamfer 1.18 %, F@2 % 0.851 (old baseline 1.1 %, 0.868).
  Both e2e sheets show a thin column behind the tail + a plinth disk (also in the 11-18 proof sheet: sample artefact of the
  Java-Random seed, not a regression).
### 02:35
- e2e #2 done: 7.8 min, peak 3.27 GB (ss flow), all decoder segs ≤ 2.13 GB → decoder tiling not needed. Chamfer 1.47 %, F@2 %
  0.850. No column artefact this time; small speck top-right (dropFloaters, committed after this run, targets that).
  README updated, proof/e2e_phone_set_sheet.png + .json.
- No cgroup/systemd-run memory cap possible on the box (permission denied) → "phone-class" = 4 pinned cores + whole-process RSS.
- Bundle building: /workspace/port3d/bundle (log /tmp/bundle.log), VERSION=2.
- Next: final e2e with dropFloaters (e2e #3), unit tests, release.sh, report.
### 02:50
- e2e #3 (final code incl. dropFloaters): peak 3.37 GB (ss flow), 12.2 min BUT 244 s of that is the shape-flow model load
  from a cold disk (page cache evicted by the bundle copy running at the same time); compute same as #2 (ss 279 s, shape 138 s).
  Mesh: chamfer 1.43 %, F@2 % 0.858; speck gone (1517 → 1515 voxels). Sheet replaced in proof/.
- Bundle: /workspace/port3d/bundle, 5.29 GB, manifest.json v2 (sha256 per file). Settings help text updated (5.3 GB, ~3.3 GB peak).
- Next: unit tests, release.sh, report.

### 02:58
- 221 unit tests pass (3 skipped). Released bb988f8: arm64 sha c75702ea4633fc49a0c6331354b9339394f4f7b7be96ebc44973e85fabf7deed.
- Model bundle for the phone: /workspace/port3d/bundle (5.29 GB). Not yet copied anywhere.
