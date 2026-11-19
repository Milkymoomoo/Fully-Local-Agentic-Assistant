#!/usr/bin/env bash
# Build signed release APKs for a commit and archive them where nothing cleans them.
#   scripts/release.sh               bump versionCode (+1) and versionName (patch +1) in app/build.gradle.kts,
#                                    commit that bump, then build it
#   scripts/release.sh <commit>      build an existing commit as-is (no bump)
# APK names carry the version and build: MilkyMoosAssistant-<versionName>-<sha7>-<abi>.apk, and the app shows the
# same version + build in Settings (bottom of the list, and Privacy › About this build).
# Each release ALSO gets byte-identical copies under stable, unversioned names, MilkyMoosAssistant-<abi>.apk, so
# https://github.com/<owner>/<repo>/releases/latest/download/MilkyMoosAssistant-arm64-v8a.apk (the README's big
# download button) always points at the newest release. Upload both sets with scripts/publish_app_release.sh.
# Builds in a separate git worktree (/workspace/release_<sha>) so the working tree is untouched,
# then copies APKs + SHA256SUMS.txt + INSTALL.txt (with this release's real file names and sizes) to /workspace/releases/<sha>/ (read-only)
# and mirrors them into dist/. This script NEVER deletes or overwrites an existing release:
# an existing /workspace/releases/<sha>/ makes it stop. Nothing in Gradle touches dist/ either
# (`clean` only removes build/ directories).
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_FILE="$REPO/app/build.gradle.kts"
if [ -z "${1:-}" ]; then
  VC=$(sed -nE 's/^ *versionCode = ([0-9]+).*/\1/p' "$GRADLE_FILE" | head -1)
  VN=$(sed -nE 's/^ *versionName = "([^"]+)".*/\1/p' "$GRADLE_FILE" | head -1)
  NVC=$((VC + 1)); NVN="${VN%.*}.$(( ${VN##*.} + 1 ))"
  sed -i -E "s/^( *versionCode = )$VC\b/\1$NVC/; s/^( *versionName = )\"$VN\"/\1\"$NVN\"/" "$GRADLE_FILE"
  git -C "$REPO" commit -q -m "Release $NVN (versionCode $NVC)" -- app/build.gradle.kts
  echo "bumped $VN ($VC) -> $NVN ($NVC)"
fi
SHA="$(git -C "$REPO" rev-parse --short=7 "${1:-HEAD}")"
VERSION="$(git -C "$REPO" show "$SHA:app/build.gradle.kts" | sed -nE 's/^ *versionName = "([^"]+)".*/\1/p' | head -1)"
WT="/workspace/release_$SHA"
OUT="/workspace/releases/$SHA"
APKSIGNER="${APKSIGNER:-/workspace/.toolchain/sdk/build-tools/37.0.0/apksigner}"
CERT="0e5ab971bb55b0ff140dacbccb75043eae1f883c4eec4c2b664ffa71ea6fed8b"
[ -e "$OUT" ] && { echo "refusing: $OUT already exists (releases are never overwritten)"; exit 1; }
source /opt/android/env.sh
[ -d "$WT" ] || git -C "$REPO" worktree add --detach "$WT" "$SHA"
# git-ignored inputs the build needs (never committed)
cp -n "$REPO/local.properties" "$WT/" 2>/dev/null || true
mkdir -p "$WT/keystore" "$WT/app/src/main/assets"
cp -n "$REPO/keystore/release.jks" "$REPO/keystore/keystore.properties" "$WT/keystore/"
[ -d "$WT/app/src/main/assets/models" ] || cp -a "$REPO/app/src/main/assets/models" "$WT/app/src/main/assets/"
[ -d "$WT/third_party/dav1d" ] || cp -a "$REPO/third_party/dav1d" "$WT/third_party/" 2>/dev/null || true
(cd "$WT" && ./gradlew :app:assembleRelease --no-daemon)
# RELEASE GATE (AGENTS.md "Memory rule"): the whole photo → 3D → rigged, attached avatar in THIS release build on the
# emulator with the Java heap capped at 512 MB, uninterrupted and resumed from a post-shape checkpoint. No pass, no
# release: nothing is archived or mirrored when it fails. There is no switch to skip it.
GATE="/workspace/releases/gates/$SHA"; mkdir -p "$GATE"
"$REPO/scripts/heap_gate.sh" "$WT/app/build/outputs/apk/release/app-x86_64-release.apk" "$GATE" || { echo "HEAP GATE FAILED: see $GATE (not released)"; exit 1; }
mkdir -p "$OUT"
cp "$GATE"/heap_gate* "$OUT/"
for a in arm64-v8a universal x86_64; do
  f="$OUT/MilkyMoosAssistant-$VERSION-$SHA-$a.apk"
  cp "$WT/app/build/outputs/apk/release/app-$a-release.apk" "$f"
  "$APKSIGNER" verify --print-certs "$f" | grep -q "certificate SHA-256 digest: $CERT" || { echo "BAD SIGNING CERT: $f"; exit 1; }
done
# stable, unversioned copies (same bytes) for the releases/latest/download/... links
for a in arm64-v8a universal x86_64; do cp "$OUT/MilkyMoosAssistant-$VERSION-$SHA-$a.apk" "$OUT/MilkyMoosAssistant-$a.apk"; done
# INSTALL.txt: its "Files" block gets this release's real APK names and sizes
python3 "$REPO/scripts/install_files.py" "$WT/dist/INSTALL.txt" "$OUT" "$OUT/INSTALL.txt"
(cd "$OUT" && sha256sum MilkyMoosAssistant-*.apk > SHA256SUMS.txt && cat SHA256SUMS.txt)
cat "$OUT/heap_gate.status"
chmod a-w "$OUT"/* "$OUT"
echo "archived to $OUT (read-only)"
# dist/ is only a convenience mirror of the newest release; its old copies are read-only, so replace them with
# install (unlink + create) rather than cp. The archive above is the copy that matters.
mkdir -p "$REPO/dist"
rm -f "$REPO"/dist/MilkyMoosAssistant-*.apk   # only the newest release is mirrored; older ones stay in /workspace/releases/
for f in "$OUT"/MilkyMoosAssistant-*.apk "$OUT/SHA256SUMS.txt"; do install -m 444 "$f" "$REPO/dist/$(basename "$f")"; done
chmod u+w "$REPO/dist/SHA256SUMS.txt"   # tracked in git
install -m 644 "$OUT/INSTALL.txt" "$REPO/dist/INSTALL.txt"   # tracked too: names + sizes of the newest release
echo "mirrored into $REPO/dist/"
# the two tracked dist/ files describe this release: commit them right away (only those two paths), so the tree is
# clean after a release and the repo's checksums always match the newest archived APKs
if ! git -C "$REPO" diff --quiet -- dist/INSTALL.txt dist/SHA256SUMS.txt; then
  git -C "$REPO" commit -q -m "dist: $VERSION ($SHA) checksums and install notes" -- dist/INSTALL.txt dist/SHA256SUMS.txt
  echo "committed dist/INSTALL.txt and dist/SHA256SUMS.txt"
fi
