#!/usr/bin/env sh
set -eu
GRADLE_VERSION="8.9"
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/zz-wrapper"
DIST="$BASE/gradle-$GRADLE_VERSION"
ZIP="$BASE/gradle-$GRADLE_VERSION-bin.zip"
if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$BASE"
  if [ ! -f "$ZIP" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fsSL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
    elif command -v wget >/dev/null 2>&1; then
      wget -q "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -O "$ZIP"
    else
      echo "curl or wget is required to bootstrap Gradle" >&2
      exit 1
    fi
  fi
  rm -rf "$DIST"
  unzip -q "$ZIP" -d "$BASE"
fi
exec "$DIST/bin/gradle" "$@"
