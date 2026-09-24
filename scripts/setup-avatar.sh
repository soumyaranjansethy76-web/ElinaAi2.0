#!/usr/bin/env bash
# =============================================================================
# setup-avatar.sh — fetch a default VRM model into assets/avatar/.
#
# Default: AvatarSample_B from VRoid (CC-with-conditions, 15 MB, female).
# To swap in a custom model later, just drop your `model.vrm` into the same
# location — `app/src/main/assets/avatar/model.vrm` — and rebuild.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"
TARGET="$ROOT/app/src/main/assets/avatar/model.vrm"

URL="${VRM_URL:-https://raw.githubusercontent.com/madjin/vrm-samples/master/vroid/stable/AvatarSample_B.vrm}"

if [[ -s "$TARGET" ]]; then
    echo "✓ $TARGET already present ($(du -h "$TARGET" | cut -f1)) — skipping"
    echo "  set VRM_URL and re-run, or delete the file, to override"
    exit 0
fi

mkdir -p "$(dirname "$TARGET")"
echo "downloading default VRM model:"
echo "  src: $URL"
echo "  dst: $TARGET"
curl -fL --progress-bar -o "$TARGET" "$URL"

# Verify the glTF binary magic
magic=$(xxd -p -l 4 "$TARGET")
if [[ "$magic" != "676c5446" ]]; then
    echo "❌ downloaded file is not a valid glTF binary (got magic $magic)"
    rm "$TARGET"
    exit 1
fi
echo "✓ verified glTF magic — VRM ready"
echo "  $(du -h "$TARGET" | cut -f1)  $TARGET"
