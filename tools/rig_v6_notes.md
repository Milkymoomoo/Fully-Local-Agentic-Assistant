# Rig v6 progress notes (t501u)

Goal: ≥95 % of pre-v5 motion for every part, every region < 1.15 stretch (Boob Dragon, dress shell, dragoness, Bob).

## Tooling
- `BreastNoTearTest` is now parallel (all 4 models ~1 min). Summary: `python3 /workspace/rigsum.py 'AMP|regions|edges > 1.15'`.
- Test prints `<name> AMP ...` = effective amplitude per Pose3D group: RMS vertex displacement of the part's region
  (dominant bone in the plain smooth weighting, `AutoRig3D.lastBaseWeights`) final rig / plain weighting at full motion.
  Asserts ≥ 0.95 (and motion scale). `CONTACTS=1` env prints bad edges by dominant-bone pair.
- Knobs (system props via gradlew -D): `rig.contact=follow|anchor|none`, `rig.seam=heat|relax|none`, `rig.reach=<h share>`.
- v5 baseline worktree: /workspace/v5wt (HEAD 55bf1f0 + lastBaseWeights + new test) for the "before" table.

## Findings so far
- Plain weighting at full motion (no contact/seam pass): huge stretch everywhere (wings 8-13, neck/head 4-6.5,
  tail 3-4, arms 2) -> the generated meshes are fused: wing tips touch head/tail, hands fused to hips/thighs, tail on legs.
- v5 contact pass (anchor to LCA) without motion limits: all regions < 1.10 once the bust is allowed to take the fade
  (frozen fix) BUT effective amplitude collapses (tail ~0-30 %, wings 4-9 %, head 25-50 %) even with reach 0.06 h:
  anchoring cascades at adjacent seams (head/neck: boundary moves up each round).
- heatSeams (patch diffusion) and relaxStretch over whole body: leak (head onto chest, breast weights wiped), no convergence.
- followContacts (less mobile side copies the mover's weights): diverges (copied rotation weights far from the contact
  move a lot: Δ grows with distance from the pivot).
- Physics: wing tip fused to tail/back moves ~0.1 h relative -> needs ~1 h of band at 10 % strain => impossible locally.
  Wings/tail on fused meshes are a true trade-off; head/neck, shoulders, hand-on-hip should be fixable.

## Next
1. Adjacent seams: pivot-centred smooth blend bands (width from max joint angle × limb radius × 10) + split head
   rotation between neck and head in Pose3D (same world head motion, half the angle per seam).
2. Contacts only for non-adjacent bones, anchored with a capped reach; measure amp/stretch trade-off per region.
3. Choose default, bump VERSION 6, render sheets/strips, unit tests, scripts/release.sh, report tables.

## 00:50 update
- v5 "before" measured (worktree): dragoness scale spine .21 chest .17 shoulders .33 tail1/2 .51; effective head .15, chest .25,
  wings ~.01-.02, tail .06-.33. Saved /tmp/v5_amp.txt (regenerate: ROOT=/workspace/v5wt python3 /workspace/rigsum.py AMP).
- Sweep tool: /workspace/sweep.sh "TAG -Drig.x=.." ... -> /tmp/sweep_all.txt (sumx.py prints AMP + STRETCH per model).
- Local anchoring (1 round, reach .06h): amplitudes 0.5-1.0 but stretch 1.3-2.1 (wings 2.1, legs 1.8). <1.15 needs heavy damping.
- Reading of the brief: don't limit motion; where 95 % and 1.15 conflict, report the trade-off. Plan: contact damping with a
  motion budget (no part below 95 % effective), residual stretch reported per region.

## 01:20 update (commit "joint-seam relax before contacts")
- Pipeline now: bust pass -> lastBaseWeights (= pre-v5 weighting, amplitude reference) -> joint-seam relax (parent/child
  seams only, bust excluded) -> anchorContacts (no-tear weighting) -> keepMotion budget (rig.keep, default 0.95).
- Pose3D: head turn/nod split 50/50 neck/head (HEAD_SPLIT, rig.headsplit).
- Results without seam relax: keep .95 -> all parts 95 % but stretch wings 8.5-13, tail 3.3-3.9, legs 2.5-3, neck/head
  3.3-5, arms 1.7-2; keep 0 -> all < 1.10 but tail 0-36 %, wings 4-8 %, head 24-31 %, ears 16 %.
  => on these fused meshes ≥95 % and <1.15 cannot coexist for wings/tail/legs/arms (fused contacts); report trade-off.
- Sweep running: S95/S80/S50/S0 with seam relax -> /tmp/sweep_all.txt.

## 01:25 — wrap-up (rig v6 done)
- priorityFollow off (rig.prio=on to try). VERSION=6. Both weightings saved: JOINTS_0/WEIGHTS_0 = full motion (keep .95, budget aims .98),
  JOINTS_1/WEIGHTS_1 = no stretching (contact pass). Renderer mixes them per frame by Prefs.animStrength (1 / 0.5 / 0),
  chooser "Animation strength" on the 3D avatar page. Live, no re-rig.
- BreastNoTearTest runs each model at strength 1 / 0.5 / 0: full ≥95 % motion; none <1.15 every region; balanced ≥40 % motion.
  All 4 pass. Tables in the final report (/tmp/final/*.xml, sumx.py).
- Next: full unit tests, scripts/release.sh, then Pixal3D (55bf1f0 / c3adf64, q8.py).

## 01:35 — released 7717156, arm64 sha ccf04625cafa8b0b11680b8efd6873907bd1f8a1260f1793758d07b977fde760; all 220 unit tests pass (3 skipped)
