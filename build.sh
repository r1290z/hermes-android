#!/usr/bin/env bash
# Build the APK on the build host (.123 has JDK + Android SDK; this host has none).
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

# Sources only: a build/ or .gradle/ copied over would poison the remote cache
# with paths from this machine.
rsync -az --delete \
  --exclude build --exclude .gradle --exclude local.properties \
  --exclude '*.apk' --exclude .git \
  "$HERE/" "$HOST:$REMOTE_DIR/"

ssh -o BatchMode=yes "$HOST" "cd $REMOTE_DIR && \
  export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk && \
  /root/.gradle/wrapper/dists/gradle-8.11.1-bin/*/gradle-8.11.1/bin/gradle \
    assembleRelease --no-daemon -q 2>&1 | tail -40"

mkdir -p "$(dirname "$APK")"
scp -q -o BatchMode=yes "$HOST:$REMOTE_DIR/app/build/outputs/apk/release/app-release.apk" "$APK"
echo "APK: $APK ($(stat -c%s "$APK") bytes)"

if [[ "${1:-}" == "publish" ]]; then
  VERSION_NAME=${VERSION_NAME:?set VERSION_NAME, e.g. VERSION_NAME=4.2 ./build.sh publish}
  NOTES=${NOTES:-}
  cp "$APK" "$HOME/.hermes/scripts/webapp-mobile/hermes-latest.apk"
  VERSION_NAME="$VERSION_NAME" NOTES="$NOTES" python3 - <<'PY'
import json, os, pathlib
p = pathlib.Path.home() / ".hermes/scripts/webapp-mobile/version.json"
d = json.loads(p.read_text(encoding="utf-8"))
d["versionCode"] = int(d.get("versionCode", 0)) + 1
d["versionName"] = os.environ["VERSION_NAME"]
if os.environ.get("NOTES"):
    d["notes"] = os.environ["NOTES"]
p.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("versionCode ->", d["versionCode"], d["versionName"])
PY
  python3 "$HOME/.hermes/scripts/webapp_mobile.py"
  python3 "$HOME/.hermes/scripts/webapp_mobile.py" --check
  echo "published $(python3 -c 'import json,pathlib;print(json.loads((pathlib.Path.home()/".hermes/scripts/webapp-mobile/version.json").read_text())["versionCode"])')"
fi
