#!/usr/bin/env bash
#
# Archive PollMaster and send the build to TestFlight.
#
# Same approach as BunBo: headless, manual signing against the "PollMaster App
# Store" profile (created by scripts/asc-setup.py through the API key), export,
# check what was signed, then upload with altool.
#
#   POLLMASTER_ASC_ISSUER_ID=<uuid> ./release-testflight.sh
#
# POLLMASTER_ASC_KEY_ID defaults to M3H6VX3N58; the key must sit at
# ~/.appstoreconnect/private_keys/AuthKey_<KEY_ID>.p8. When the issuer id is not
# in the environment it is read from the login keychain, stored once with:
#
#   security add-generic-password -U -s pollmaster-asc-issuer -a pollmaster -w <issuer-uuid>
#
# The build number is the commit count, so it always goes up and maps back to a
# commit. Commit before releasing: an uncommitted tree would ship a build whose
# number points at code that does not exist.

set -Eeuo pipefail

cd "$(dirname "$0")"

readonly TEAM_ID="${POLLMASTER_TEAM_ID:-566SKQXVZT}"
readonly BUNDLE_ID=com.evselab.pollmaster
readonly PROFILE="PollMaster App Store"
readonly ARCHIVE="${TMPDIR:-/tmp}/PollMaster.xcarchive"
readonly EXPORT_DIR="${TMPDIR:-/tmp}/PollMaster-export"
readonly KEY_ID="${POLLMASTER_ASC_KEY_ID:-M3H6VX3N58}"

if [[ -z "${POLLMASTER_ASC_ISSUER_ID:-}" ]]; then
	POLLMASTER_ASC_ISSUER_ID=$(security find-generic-password -s pollmaster-asc-issuer -w 2>/dev/null || true)
fi
: "${POLLMASTER_ASC_ISSUER_ID:?App Store Connect issuer id (env or keychain item pollmaster-asc-issuer)}"
[[ -f "$HOME/.appstoreconnect/private_keys/AuthKey_${KEY_ID}.p8" ]] || { echo "no key AuthKey_${KEY_ID}.p8" >&2; exit 1; }

log() { printf '\n==> %s\n' "$*"; }

if [[ -n "$(git status --porcelain -- . ../shared)" ]]; then
	echo "uncommitted changes in IOS/ or shared/ — commit first so the build number maps to real code" >&2
	exit 1
fi

log "provisioning (bundle id, profile, app record)"
ASC_KEY_ID="$KEY_ID" ASC_ISSUER_ID="$POLLMASTER_ASC_ISSUER_ID" \
	"$HOME/.claude/skills/.venv/bin/python3" scripts/asc-setup.py

log "tests"
(cd PollMasterCore && swift test 2>&1 | tail -1)

log "regenerating the project"
xcodegen generate

build_number=$(git rev-list --count HEAD)
log "build $build_number from $(git rev-parse --short HEAD)"

log "archiving"
rm -rf "$ARCHIVE" "$EXPORT_DIR"
xcodebuild \
	-project PollMaster.xcodeproj \
	-scheme PollMaster \
	-configuration Release \
	-destination 'generic/platform=iOS' \
	-archivePath "$ARCHIVE" \
	DEVELOPMENT_TEAM="$TEAM_ID" \
	CURRENT_PROJECT_VERSION="$build_number" \
	archive

log "exporting"
cat >"${TMPDIR:-/tmp}/PollMaster-ExportOptions.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>teamID</key>
	<string>$TEAM_ID</string>
	<key>signingStyle</key>
	<string>manual</string>
	<key>signingCertificate</key>
	<string>Apple Distribution</string>
	<key>provisioningProfiles</key>
	<dict>
		<key>$BUNDLE_ID</key>
		<string>$PROFILE</string>
	</dict>
	<key>uploadSymbols</key>
	<true/>
	<key>destination</key>
	<string>export</string>
	<key>manageAppVersionAndBuildNumber</key>
	<false/>
</dict>
</plist>
PLIST

xcodebuild -exportArchive \
	-archivePath "$ARCHIVE" \
	-exportPath "$EXPORT_DIR" \
	-exportOptionsPlist "${TMPDIR:-/tmp}/PollMaster-ExportOptions.plist"

readonly IPA="$EXPORT_DIR/PollMaster.ipa"

log "checking what was signed"
unpacked=$(mktemp -d)
trap 'rm -rf "$unpacked"' EXIT
unzip -q "$IPA" -d "$unpacked"
app="$unpacked/Payload/PollMaster.app"

codesign -dv --verbose=2 "$app" 2>&1 | grep -q "Authority=Apple Distribution" || {
	echo "not signed with an Apple Distribution certificate — not uploading" >&2; exit 1; }
[[ "$(plutil -extract CFBundleIdentifier raw "$app/Info.plist")" == "$BUNDLE_ID" ]] || {
	echo "bundle id is not $BUNDLE_ID — not uploading" >&2; exit 1; }
shipped=$(plutil -extract CFBundleVersion raw "$app/Info.plist")
[[ "$shipped" == "$build_number" ]] || { echo "ipa carries build $shipped, not $build_number — not uploading" >&2; exit 1; }
[[ -f "$app/PrivacyInfo.xcprivacy" && -d "$app/templates" ]] || {
	echo "privacy manifest or bundled templates missing — not uploading" >&2; exit 1; }
echo "signed: Apple Distribution | $BUNDLE_ID | $(plutil -extract CFBundleShortVersionString raw "$app/Info.plist") ($shipped)"

log "uploading build $build_number"
# altool exits 0 even when the upload is refused, so the output decides.
result=$(xcrun altool --upload-app --type ios -f "$IPA" \
	--apiKey "$KEY_ID" --apiIssuer "$POLLMASTER_ASC_ISSUER_ID" 2>&1) || true
echo "$result"
grep -qiE "UPLOAD SUCCEEDED|No errors uploading" <<<"$result" || { echo "upload refused — read the output above" >&2; exit 1; }

log "uploaded build $build_number"
echo "Apple needs a few minutes to process it; internal testers can install as soon as it shows in TestFlight."
