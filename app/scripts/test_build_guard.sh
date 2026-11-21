#!/usr/bin/env bash
# Tests build.sh's failure guard: a gradle that exits 1 must make build.sh exit
# nonzero WITHOUT copying an APK back (the "failed compile published the stale
# APK" regression).
#
# It never touches the real build host or the network: HERMES_BUILD_HOST is
# pointed at a local ssh stub whose remote gradle exits 1, and the build is run
# against a throwaway copy of the project so the real tree stays clean.
set -euo pipefail

HERE=$(cd "$(dirname "$0")/../.." && pwd)   # project root (app/scripts/../..)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"

# ssh stub: eats scp/ssh calls; any gradle invocation fails with exit 1.
cat > "$WORK/bin/ssh" <<'EOF'
#!/usr/bin/env bash
# args: -o BatchMode=yes host "remote command..."
for a in "$@"; do
  case "$a" in
    */gradle*|gradle) echo "gradle: stub failure" >&2; exit 1 ;;
  esac
done
exit 0
EOF
chmod +x "$WORK/bin/ssh"

# scp stub: key push "succeeds" without touching the network.
cat > "$WORK/bin/scp" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$WORK/bin/scp"

# rsync stub: pretend the sources synced; nothing leaves this machine.
cat > "$WORK/bin/rsync" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$WORK/bin/rsync"

# Build needs the signing key present (guard #1) — real or fake, rsync never runs.
SECRETS="$WORK/.hermes/secrets"; mkdir -p "$SECRETS"
cp "$HOME/.hermes/secrets/hermes-android.jks" "$SECRETS/" 2>/dev/null || : > "$SECRETS/hermes-android.jks"
: > "$SECRETS/hermes-android.properties"

# Throwaway project copy with a pre-planted APK: if the script copies an APK
# over it on a failing build, the guard has failed.
cp -r "$HERE" "$WORK/proj"
APK_OUT="$WORK/proj/app/build/outputs/apk/release"
mkdir -p "$APK_OUT"
echo "OLD-APK" > "$APK_OUT/app-release.apk"

# No network: host stub + a HOME with the fake secrets.
export PATH="$WORK/bin:$PATH"
set +e
HOME="$WORK" HERMES_BUILD_HOST=fake@buildhost bash "$WORK/proj/build.sh" > "$WORK/out.log" 2>&1
RC=$?
set -e

echo "--- build.sh exit=$RC, log tail:"
tail -5 "$WORK/out.log"

FAIL=0
[[ $RC -ne 0 ]] || { echo "FAIL: build.sh вернул 0 при упавшей сборке"; FAIL=1; }
[[ $(cat "$APK_OUT/app-release.apk") == "OLD-APK" ]] || {
  echo "FAIL: build.sh перезаписал APK при упавшей сборке"; FAIL=1; }

if [[ $FAIL -eq 0 ]]; then
  echo "OK: падающая сборка не публикуется"
else
  exit 1
fi
