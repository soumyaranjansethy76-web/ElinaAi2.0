#!/usr/bin/env bash
# =============================================================================
# setup-sherpa.sh — fetch sherpa-onnx native libs, Kotlin API, and Chinese
# ASR/TTS models so tour-avatar can speak/listen on real devices.
#
# Usage:
#     bash scripts/setup-sherpa.sh                # libs + API only
#     bash scripts/setup-sherpa.sh --models       # also fetch ASR/TTS models
#     bash scripts/setup-sherpa.sh --models --push <serial>
#                                                 # also adb push to a device
#
# All artifacts are Apache-2.0; tour-avatar is MIT.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"
APP_MAIN="$ROOT/app/src/main"

SHERPA_VER="v1.12.39"
GH_BASE="${GITHUB_MIRROR:-}https://github.com/k2-fsa/sherpa-onnx/releases/download"
GH_RAW="${GITHUB_MIRROR:-}https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master"
HF="${HF_MIRROR:-https://hf-mirror.com}"

# ASR: Paraformer Chinese, int8 quantized (~250MB; FP32 model.onnx is 785MB)
ASR_HF="$HF/csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09/resolve/main"
ASR_MODEL_FILE="model.int8.onnx"

# TTS: VITS Chinese (zh-ll), ~115MB
TTS_HF="$HF/csukuangfj/sherpa-onnx-vits-zh-ll/resolve/main"

JNI_DIR="$APP_MAIN/jniLibs/arm64-v8a"
API_DIR="$APP_MAIN/kotlin/com/k2fsa/sherpa/onnx"
MODELS_DIR="$APP_MAIN/../../../models-staging"      # local staging, not bundled
ASR_DIR="$MODELS_DIR/asr"
TTS_DIR="$MODELS_DIR/tts"

mkdir -p "$JNI_DIR" "$API_DIR" "$ASR_DIR" "$TTS_DIR"

want_models=false
push_serial=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --models) want_models=true ;;
        --push)   shift; push_serial="$1" ;;
        *) echo "unknown arg: $1" ; exit 2 ;;
    esac
    shift
done

# ─── 1. native .so libs ─────────────────────────────────────────────
if compgen -G "$JNI_DIR/lib*.so" >/dev/null; then
    echo "[1/3] sherpa-onnx native libs already present, skipping"
else
    echo "[1/3] downloading sherpa-onnx $SHERPA_VER native libs (~35 MB)"
    tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
    archive="sherpa-onnx-$SHERPA_VER-android.tar.bz2"
    curl -fL --progress-bar -o "$tmp/$archive" "$GH_BASE/$SHERPA_VER/$archive"
    tar -xjf "$tmp/$archive" -C "$tmp"
    find "$tmp" -name "*.so" -path "*/arm64-v8a/*" -exec cp {} "$JNI_DIR/" \;
    ls -lh "$JNI_DIR"
fi

# ─── 2. sherpa-onnx Kotlin API source ───────────────────────────────
if [[ -f "$API_DIR/OfflineRecognizer.kt" ]]; then
    echo "[2/3] Kotlin API already present, skipping"
else
    echo "[2/3] downloading sherpa-onnx Kotlin API source"
    for kt in OfflineRecognizer.kt Tts.kt FeatureConfig.kt \
              HomophoneReplacerConfig.kt QnnConfig.kt OfflineStream.kt; do
        curl -fL --progress-bar -o "$API_DIR/$kt" "$GH_RAW/sherpa-onnx/kotlin-api/$kt"
    done
fi

# ─── 3. (optional) ASR + TTS models ─────────────────────────────────
if ! $want_models; then
    cat <<EOF
✓ libs + API ready. Build with: ./gradlew assembleDebug

To also fetch ASR/TTS models (and optionally push to a device), run:
    bash scripts/setup-sherpa.sh --models
    bash scripts/setup-sherpa.sh --models --push <adb-serial>
EOF
    exit 0
fi

echo "[3/3] downloading Chinese ASR (Paraformer) and TTS (VITS) models"

curl -fL --progress-bar -o "$ASR_DIR/model.onnx"  "$ASR_HF/$ASR_MODEL_FILE"
curl -fL --progress-bar -o "$ASR_DIR/tokens.txt"  "$ASR_HF/tokens.txt"

curl -fL --progress-bar -o "$TTS_DIR/model.onnx"   "$TTS_HF/model.onnx"
curl -fL --progress-bar -o "$TTS_DIR/tokens.txt"   "$TTS_HF/tokens.txt"
curl -fL --progress-bar -o "$TTS_DIR/lexicon.txt"  "$TTS_HF/lexicon.txt"
# Optional FST data — improves Chinese number/date handling
for fst in date.fst phone.fst number.fst; do
    curl -fL -o "$TTS_DIR/$fst" "$TTS_HF/$fst" 2>/dev/null || true
done

echo "models staged at $MODELS_DIR"
du -sh "$ASR_DIR" "$TTS_DIR"

# ─── push to device (optional) ──────────────────────────────────────
if [[ -n "$push_serial" ]]; then
    APP_DATA="/sdcard/Android/data/com.elina.assistant/files/models"
    echo "pushing to device $push_serial → $APP_DATA"
    adb -s "$push_serial" shell "mkdir -p $APP_DATA/asr $APP_DATA/tts"
    adb -s "$push_serial" push "$ASR_DIR/." "$APP_DATA/asr/"
    adb -s "$push_serial" push "$TTS_DIR/." "$APP_DATA/tts/"
    echo "✓ models on device. Force-stop + relaunch the app to use them:"
    echo "    adb -s $push_serial shell am force-stop com.elina.assistant"
    echo "    adb -s $push_serial shell am start -n com.elina.assistant/.MainActivity"
fi
