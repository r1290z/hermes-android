#!/usr/bin/env bash
# Author's own pipeline: builds on a remote host that has JDK + Android SDK
# (this one has neither). Not needed to build the app — ./gradlew works anywhere
# with JDK 17 and Android SDK 36 installed; see README.md.
#
#   ./build.sh              assembleRelease, pull the APK back
#   ./build.sh publish      also bump version.json and publish to the webapp bundle
#
# The last line of a successful run is "BUILD SUCCESSFUL" — treat anything else
# as a failure, including a missing APK.
set -euo pipefail

HOST=${HERMES_BUILD_HOST:-root@build.example.com}
REMOTE_DIR=/root/hermes-app
HERE=$(cd "$(dirname "$0")" && pwd)
APK="$HERE/app/build/outputs/apk/release/app-release.apk"
SECRETS="$HOME/.hermes/secrets"
BUILD_TOOLS=/opt/android-sdk/build-tools/36.0.0

# The signing key lives OUTSIDE the project and is copied over on every run.
# It used to sit in the project directory, `rsync --delete` silently removed it,
# the release build fell back to the debug key and published an APK that no phone
# could install over the previous one. Three guards now: no --delete, the key is
# pushed explicitly, and the built APK's signature is verified before publishing.
[[ -f "$SECRETS/hermes-android.jks" && -f "$SECRETS/hermes-android.properties" ]] || {
  echo "нет ключа подписи в $SECRETS — без него APK не встанет поверх установленного" >&2
  exit 1
}

# Sources mirror the repo EXACTLY, including deletions. Without --delete a file
# removed locally stays on the remote forever, and the compiler then sees both the
# old and the new version of the same private class — which is exactly how a green
# build turned red: ProjectsActivity.kt was deleted in git, its stale copy carried a
# second `private class ProjectsAdapter`, and every build failed with "cannot access
# … it is private in file" while the sources were correct.
# Excluded paths (the signing key, keystore.properties, build output, gradle caches)
# are NOT deleted: rsync protects excluded files unless --delete-excluded is given.
# Layout id check first: a missing id or a wrong view class compiles clean and
# crashes the screen at runtime (6.1 shipped exactly that, ClassCastException on
# every chat open). Cheaper to catch here than on the phone.
python3 "$HERE/scripts/check_layout_ids.py"
rsync -az --delete \
  --exclude build --exclude .gradle --exclude local.properties \
  --exclude '*.apk' --exclude .git \
  --exclude keystore.properties --exclude '*.jks' \
  "$HERE/" "$HOST:$REMOTE_DIR/"

scp -q -o BatchMode=yes "$SECRETS/hermes-android.jks" "$HOST:$REMOTE_DIR/hermes-release.jks"
scp -q -o BatchMode=yes "$SECRETS/hermes-android.properties" "$HOST:$REMOTE_DIR/keystore.properties"
ssh -o BatchMode=yes "$HOST" "chmod 600 $REMOTE_DIR/hermes-release.jks $REMOTE_DIR/keystore.properties"

# `| tail -40` inside the remote command made the remote shell exit with tail's
# status, so a failed compile still looked like success and the script happily
# copied the PREVIOUS APK back. Keep the log tail, but propagate gradle's status.
BUILD_LOG=$(ssh -o BatchMode=yes "$HOST" "cd $REMOTE_DIR && \
  export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk && \
  /root/.gradle/wrapper/dists/gradle-8.11.1-bin/*/gradle-8.11.1/bin/gradle \
    assembleRelease --no-daemon -q -PrequireSigning=true 2>&1; echo \"__STATUS=\$?\"")
echo "$BUILD_LOG" | sed '/^__STATUS=/d' | tail -40
BUILD_STATUS=$(echo "$BUILD_LOG" | sed -n 's/^__STATUS=//p' | tail -1)
if [[ "$BUILD_STATUS" != "0" ]]; then
  echo "ОТКАЗ: сборка упала (gradle exit $BUILD_STATUS)" >&2
  exit 1
fi

# A debug-signed build must never reach a phone: it cannot update the installed app.
SIGNER=$(ssh -o BatchMode=yes "$HOST" "$BUILD_TOOLS/apksigner verify --print-certs \
  $REMOTE_DIR/app/build/outputs/apk/release/app-release.apk" | head -1)
echo "подпись: $SIGNER"
case "$SIGNER" in
  *"CN=Hermes"*) ;;
  *) echo "ОТКАЗ: APK подписан не нашим ключом ($SIGNER)" >&2; exit 1 ;;
esac

mkdir -p "$(dirname "$APK")"
scp -q -o BatchMode=yes "$HOST:$REMOTE_DIR/app/build/outputs/apk/release/app-release.apk" "$APK"
echo "APK: $APK ($(stat -c%s "$APK") bytes)"

if [[ "${1:-}" == "publish" ]]; then
  VERSION_NAME=${VERSION_NAME:?set VERSION_NAME, e.g. VERSION_NAME=4.3 ./build.sh publish}
  NOTES=${NOTES:-}
  # version.json must carry the SAME versionCode the APK declares, or the app
  # offers an update on every launch. Read it from gradle instead of counting up.
  VERSION_CODE=$(grep -oP 'versionCode\s*=\s*\K[0-9]+' "$HERE/app/build.gradle.kts" | head -1)
  cp "$APK" "$HOME/.hermes/scripts/webapp-mobile/hermes-latest.apk"
  VERSION_NAME="$VERSION_NAME" VERSION_CODE="$VERSION_CODE" NOTES="$NOTES" python3 - <<'PY'
import json, os, pathlib
p = pathlib.Path.home() / ".hermes/scripts/webapp-mobile/version.json"
d = json.loads(p.read_text(encoding="utf-8"))
d["versionCode"] = int(os.environ["VERSION_CODE"])
d["versionName"] = os.environ["VERSION_NAME"]
if os.environ.get("NOTES"):
    d["notes"] = os.environ["NOTES"]
p.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("version.json ->", d["versionCode"], d["versionName"])
PY
  python3 "$HOME/.hermes/scripts/webapp_mobile.py"
  python3 "$HOME/.hermes/scripts/webapp_mobile.py" --check
  echo "published $(python3 -c 'import json,pathlib;print(json.loads((pathlib.Path.home()/".hermes/scripts/webapp-mobile/version.json").read_text())["versionCode"])')"
fi
