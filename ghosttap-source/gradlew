#!/usr/bin/env sh
set -e

DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$DIR/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_PROPS="$DIR/gradle/wrapper/gradle-wrapper.properties"

# AndroidForge compatibility: use the pinned distribution directly.
# A small wrapper JAR is retained so wrapper-based project detection does not force Legacy mode.
if [ ! -f "$WRAPPER_PROPS" ]; then
  echo "Missing gradle-wrapper.properties"
  exit 1
fi

DIST_URL=$(grep '^distributionUrl=' "$WRAPPER_PROPS" | cut -d'=' -f2- | sed 's#\\:#:#g')
if [ -z "$DIST_URL" ]; then
  echo "distributionUrl not found in gradle-wrapper.properties"
  exit 1
fi

DIST_FILE=$(basename "$DIST_URL")
DIST_BASENAME=${DIST_FILE%.zip}
DIST_NAME=${DIST_BASENAME%-bin}
DIST_NAME=${DIST_NAME%-all}
CACHE_DIR="$DIR/.gradle/wrapper/fallback"
ZIP_PATH="$CACHE_DIR/$DIST_FILE"
GRADLE_HOME="$CACHE_DIR/$DIST_NAME"
GRADLE_BIN="$GRADLE_HOME/bin/gradle"

mkdir -p "$CACHE_DIR"

if [ ! -f "$ZIP_PATH" ]; then
  if command -v curl >/dev/null 2>&1; then
    curl -L "$DIST_URL" -o "$ZIP_PATH"
  elif command -v wget >/dev/null 2>&1; then
    wget "$DIST_URL" -O "$ZIP_PATH"
  else
    echo "Neither curl nor wget is available to download Gradle distribution"
    exit 1
  fi
fi

if [ ! -x "$GRADLE_BIN" ]; then
  rm -rf "$GRADLE_HOME"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$ZIP_PATH" -d "$CACHE_DIR"
  elif command -v bsdtar >/dev/null 2>&1; then
    bsdtar -xf "$ZIP_PATH" -C "$CACHE_DIR"
  else
    echo "No unzip tool available to extract Gradle distribution"
    exit 1
  fi
fi

exec "$GRADLE_BIN" "$@"
