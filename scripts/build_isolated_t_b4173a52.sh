#!/usr/bin/env bash
# One-off acceptance build for t_b4173a52 in an ISOLATED remote dir.
# Motivation: the watch-companion worker keeps rsyncing its wear/ sources into
# the shared /root/hermes-app on the build host, breaking concurrent builds
# (same hotspot as t_140be145). Same steps as build.sh (layout-id check, rsync,
# assembleRelease, signature check), different REMOTE_DIR. Not for publishing.
set -euo pipefail
HOST=${HERMES_BUILD_HOST:-root@build.example.com}
REMOTE_DIR=/root/hermes-app-sessions-t_b4173a52
HERE=$(cd "$(dirname "$0")/.." && pwd)
SECRETS="$HOME/.hermes/secrets"
BUILD_TOOLS=/opt/android-sdk/build-tools/36.0.0

[[ -f "$SECRETS/hermes-android.jks" && -f "$SECRETS/hermes-android.properties" ]] || {
  echo "нет ключа подписи в $SECRETS" >&2; exit 1
}

python3 "$HERE/scripts/check_layout_ids.py"

rsync -az --delete \
  --exclude build --exclude .gradle --exclude local.properties \
  --exclude '*.apk' --exclude .git --exclude .worktrees \
  --exclude keystore.properties --exclude '*.jks' \
  "$HERE/" "$HOST:$REMOTE_DIR/"

scp -q -o BatchMode=yes "$SECRETS/hermes-android.jks" "$HOST:$REMOTE_DIR/hermes-release.jks"
scp -q -o BatchMode=yes "$SECRETS/hermes-android.properties" "$HOST:$REMOTE_DIR/keystore.properties"
ssh -o BatchMode=yes "$HOST" "chmod 600 $REMOTE_DIR/hermes-release.jks $REMOTE_DIR/keystore.properties"

BUILD_LOG_FILE=$(mktemp)
if ssh -o BatchMode=yes "$HOST" "cd $REMOTE_DIR && \
  export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk && \
  /root/.gradle/wrapper/dists/gradle-8.11.1-bin/*/gradle-8.11.1/bin/gradle \
    assembleRelease --no-daemon -q" > "$BUILD_LOG_FILE" 2>&1; then
  tail -5 "$BUILD_LOG_FILE"
else
  tail -30 "$BUILD_LOG_FILE"
  echo "ОТКАЗ: сборка упала" >&2
  rm -f "$BUILD_LOG_FILE"
  exit 1
fi
rm -f "$BUILD_LOG_FILE"

SIGNER=$(ssh -o BatchMode=yes "$HOST" "$BUILD_TOOLS/apksigner verify --print-certs \
  $REMOTE_DIR/app/build/outputs/apk/release/app-release.apk" | head -1)
echo "подпись: $SIGNER"

scp -q -o BatchMode=yes "$HOST:$REMOTE_DIR/app/build/outputs/apk/release/app-release.apk" \
  "$HERE/app/build/outputs/apk/release/app-release.apk"
echo "APK: $HERE/app/build/outputs/apk/release/app-release.apk ($(stat -c %s "$HERE/app/build/outputs/apk/release/app-release.apk") bytes)"

# The isolated dir is a copy, not a workspace: drop it so the build host does
# not accumulate task dirs.
ssh -o BatchMode=yes "$HOST" "rm -rf $REMOTE_DIR"
echo "изолированный каталог удалён"
