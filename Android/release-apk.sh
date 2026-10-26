#!/usr/bin/env bash
#
# Build a signed release APK and publish it as a GitHub release, so the link can be sent to anyone (the repo is public).
#
#   ./release-apk.sh
#
# The key is ~/.android/pollmaster-release.jks (alias pollmaster); its password sits in the login keychain. One-time
# setup (back the .jks up: without it, installed apps can no longer be updated):
#
#   PW=$(openssl rand -base64 24 | tr -d '/+=') && \
#   security add-generic-password -U -s pollmaster-android-keystore -a pollmaster -w "$PW" && \
#   keytool -genkeypair -keystore ~/.android/pollmaster-release.jks -storepass "$PW" -keypass "$PW" \
#     -alias pollmaster -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=PollMaster, C=VN"
#
# The version code is the commit count (same as the iOS build number). Commit and push first.

set -Eeuo pipefail

cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export POLLMASTER_KEYSTORE="${POLLMASTER_KEYSTORE:-$HOME/.android/pollmaster-release.jks}"
export POLLMASTER_KEY_ALIAS="${POLLMASTER_KEY_ALIAS:-pollmaster}"

log() { printf '\n==> %s\n' "$*"; }

[[ -f "$POLLMASTER_KEYSTORE" ]] || { echo "no keystore at $POLLMASTER_KEYSTORE — see the one-time setup at the top of this script" >&2; exit 1; }
if [[ -z "${POLLMASTER_KEYSTORE_PASSWORD:-}" ]]; then
	POLLMASTER_KEYSTORE_PASSWORD=$(security find-generic-password -s pollmaster-android-keystore -w 2>/dev/null || true)
fi
: "${POLLMASTER_KEYSTORE_PASSWORD:?keystore password (env or keychain item pollmaster-android-keystore)}"
export POLLMASTER_KEYSTORE_PASSWORD

if [[ -n "$(git status --porcelain -- . ../shared)" ]]; then
	echo "uncommitted changes in Android/ or shared/ — commit first so the version code maps to real code" >&2
	exit 1
fi
git fetch -q origin
[[ "$(git rev-parse HEAD)" == "$(git rev-parse @{u})" ]] || { echo "HEAD is not pushed — push first so the release tag points at code on GitHub" >&2; exit 1; }

build=$(git rev-list --count HEAD)
name=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
tag="android-v$name-$build"
apk="app/build/outputs/apk/release/PollMaster-$name-$build.apk"
log "build $build ($name) from $(git rev-parse --short HEAD)"

if gh release view "$tag" >/dev/null 2>&1; then
	echo "release $tag already exists: $(gh release view "$tag" --json url -q .url)"
	exit 0
fi

log "tests + release build"
./gradlew -q test assembleRelease -PversionCode="$build"
cp app/build/outputs/apk/release/app-release.apk "$apk"

log "checking the signature"
apksigner=$(ls -d "${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"/build-tools/*/apksigner | tail -1)
"$apksigner" verify --print-certs "$apk" | grep -q "CN=PollMaster" || { echo "APK not signed with the PollMaster key — not publishing" >&2; exit 1; }

log "publishing $tag"
previous=$(git tag --list 'android-v*' --sort=-creatordate | head -1)
notes=$(git log --no-merges --format='- %s' ${previous:+$previous..}HEAD -- . ../shared ../docs | head -40)
gh release create "$tag" "$apk" --target "$(git rev-parse HEAD)" --latest \
	--title "PollMaster Android $name ($build)" \
	--notes "Cài: tải PollMaster-$name-$build.apk trên điện thoại Android rồi mở (cho phép cài từ nguồn này).

${notes:-- bản dựng lại}"
git fetch -q --tags origin

log "done"
echo "trang phát hành: $(gh release view "$tag" --json url -q .url)"
echo "link APK:        https://github.com/$(gh repo view --json nameWithOwner -q .nameWithOwner)/releases/download/$tag/PollMaster-$name-$build.apk"
