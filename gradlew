#!/usr/bin/env sh
set -eu
if command -v gradle >/dev/null 2>&1; then exec gradle "$@"; fi
DIST_URL="https://services.gradle.org/distributions/gradle-9.6.0-bin.zip"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/elina-gradle-9.6.0"
BIN="$CACHE/gradle-9.6.0/bin/gradle"
if [ ! -x "$BIN" ]; then
  mkdir -p "$CACHE"
  ZIP="$CACHE/gradle.zip"
  if command -v curl >/dev/null 2>&1; then curl -fsSL "$DIST_URL" -o "$ZIP"; else wget -q "$DIST_URL" -O "$ZIP"; fi
  rm -rf "$CACHE/gradle-9.6.0"; unzip -q "$ZIP" -d "$CACHE"
fi
exec "$BIN" "$@"
