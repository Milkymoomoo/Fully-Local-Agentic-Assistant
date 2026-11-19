#!/usr/bin/env bash
# Upload an archived app release (/workspace/releases/<sha>/, made by scripts/release.sh) to GitHub Releases.
# NEVER run without the owner's go-ahead: it publishes. Read-only checks first, then it asks before uploading.
#   scripts/publish_app_release.sh <sha7> [owner/repo]
# Uploads, to the tag v<versionName>:
#   MilkyMoosAssistant-<versionName>-<sha7>-<abi>.apk   versioned (arm64-v8a, universal, x86_64)
#   MilkyMoosAssistant-<abi>.apk                       the same bytes under stable names; with the release marked
#                                                      "latest", .../releases/latest/download/MilkyMoosAssistant-arm64-v8a.apk
#                                                      (the README's DOWNLOAD PREMADE APK HERE button) serves it
#   SHA256SUMS.txt, INSTALL.txt
set -euo pipefail
SHA="${1:?usage: publish_app_release.sh <sha7> [owner/repo]}"; REPO="${2:-Milkymoomoo/Fully-Local-Agentic-Assistant}"
DIR="/workspace/releases/$SHA"; [ -d "$DIR" ] || { echo "no archived release $DIR"; exit 1; }
VERSION=$(ls "$DIR" | sed -nE "s/^MilkyMoosAssistant-(.+)-$SHA-arm64-v8a\.apk$/\1/p"); TAG="v$VERSION"
FILES=()
for a in arm64-v8a universal x86_64; do
  v="$DIR/MilkyMoosAssistant-$VERSION-$SHA-$a.apk"; s="$DIR/MilkyMoosAssistant-$a.apk"
  [ -f "$v" ] && [ -f "$s" ] || { echo "missing $v or $s"; exit 1; }
  cmp -s "$v" "$s" || { echo "stable copy differs from $v"; exit 1; }
  FILES+=("$v" "$s")
done
FILES+=("$DIR/SHA256SUMS.txt" "$DIR/INSTALL.txt")
(cd "$DIR" && sha256sum -c --quiet <(grep -E "MilkyMoosAssistant-.*\.apk$" SHA256SUMS.txt)) || { echo "checksum mismatch"; exit 1; }
echo "Will publish $TAG to $REPO (marked latest):"; printf '  %s\n' "${FILES[@]##*/}"
read -r -p "Type PUBLISH to upload: " ok; [ "$ok" = PUBLISH ] || { echo "cancelled"; exit 1; }
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  gh release upload "$TAG" "${FILES[@]}" --repo "$REPO" --clobber
  gh release edit "$TAG" --repo "$REPO" --latest
else
  gh release create "$TAG" "${FILES[@]}" --repo "$REPO" --title "Milky Moo's Assistant $VERSION" --notes-file "$DIR/INSTALL.txt" --latest
fi
echo "published: https://github.com/$REPO/releases/latest/download/MilkyMoosAssistant-arm64-v8a.apk"
