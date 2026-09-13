#!/usr/bin/env bash
# Build, archive and upload Pit Pass for iPad to TestFlight in one command.
# See docs/IOS.md "Releasing to TestFlight" for the one-time setup.
#
#   ios/release.sh                 tests → archive → upload → git tag
#   ios/release.sh --skip-tests    (when the test run already passed)
#   ios/release.sh --no-upload     archive + export an .ipa, upload nothing
#   ios/release.sh --build 42      pick the build number yourself
#   ios/release.sh --allow-dirty   release with uncommitted changes (no tag)
#
# Version = MARKETING_VERSION in project.yml (bump that line to release a new
# version). Build = UTC minute stamp, e.g. 202609122145 — unique without a
# counter file, always increasing, and readable in App Store Connect.
#
# Signing + upload authentication, in order of preference:
#   1. An App Store Connect API key (App Manager role), from ios/.release.env
#      (git-ignored):
#        ASC_KEY_ID=ABC123DEF4
#        ASC_ISSUER_ID=12345678-aaaa-bbbb-cccc-1234567890ab
#        ASC_KEY_PATH=$HOME/.appstoreconnect/private_keys/AuthKey_ABC123DEF4.p8
#      The export is then signed manually with the keychain's Apple Distribution
#      certificate and an App Store profile that asc_profile.py keeps current
#      through the API (with a key, xcodebuild's automatic signing insists on
#      cloud-managed certificates, which need an Admin key).
#   2. Otherwise the Apple ID signed into Xcode (Settings → Accounts) does
#      automatic signing and the upload through -allowProvisioningUpdates.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$here"

run_tests=1
upload=1
allow_dirty=0
build=""
while [ $# -gt 0 ]; do
    case "$1" in
        --skip-tests) run_tests=0 ;;
        --no-upload) upload=0 ;;
        --allow-dirty) allow_dirty=1 ;;
        --build) shift; build="${1:-}" ;;
        -h|--help) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
    shift
done

log() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# --- Preflight -------------------------------------------------------------

export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
[ -d "$DEVELOPER_DIR" ] || die "Xcode not found at $DEVELOPER_DIR (set DEVELOPER_DIR)"
command -v xcodegen >/dev/null || die "xcodegen missing: brew install xcodegen"

team="$(sed -n 's/^ *DEVELOPMENT_TEAM: *"\{0,1\}\([A-Z0-9]*\)"\{0,1\} *$/\1/p' project.yml | head -1)"
team="${DEVELOPMENT_TEAM:-$team}"
[ -n "$team" ] || die "DEVELOPMENT_TEAM is empty in project.yml — put your Apple Developer team ID there"

version="$(sed -n 's/^ *MARKETING_VERSION: *"\{0,1\}\([0-9][0-9.]*\)"\{0,1\} *$/\1/p' project.yml | head -1)"
[ -n "$version" ] || die "MARKETING_VERSION not found in project.yml"
[ -n "$build" ] || build="$(date -u +%Y%m%d%H%M)"

dirty=0
if [ -n "$(git status --porcelain -- .)" ]; then
    dirty=1
    [ "$allow_dirty" = 1 ] || die "uncommitted changes in ios/ — commit them, or pass --allow-dirty (no tag will be made)"
fi
commit="$(git rev-parse --short HEAD)"

auth=()
if [ -f .release.env ]; then
    # shellcheck disable=SC1091
    set -a; . ./.release.env; set +a
fi
if [ -n "${ASC_KEY_ID:-}" ]; then
    [ -n "${ASC_ISSUER_ID:-}" ] || die "ASC_KEY_ID is set but ASC_ISSUER_ID is not"
    key_path="${ASC_KEY_PATH:-$HOME/.appstoreconnect/private_keys/AuthKey_${ASC_KEY_ID}.p8}"
    [ -f "$key_path" ] || die "App Store Connect key not found at $key_path"
    auth=(-authenticationKeyPath "$key_path" -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID")
    auth_note="App Store Connect API key $ASC_KEY_ID, manual signing"
else
    auth_note="the Apple ID signed into Xcode (no ios/.release.env), automatic signing"
fi
bundle_id=com.arjunakankipati.pitpass
profile_name="PitPass App Store"

pretty() { if command -v xcbeautify >/dev/null; then xcbeautify; else cat; fi; }

out="build/release/$version-$build"
archive="$out/PitPass.xcarchive"
mkdir -p "$out"

log "Pit Pass $version ($build) from $commit · team $team · auth: $auth_note"

# --- Generate + test -------------------------------------------------------

log "xcodegen generate"
xcodegen generate --quiet

if [ "$run_tests" = 1 ]; then
    log "Unit tests (simulator)"
    xcodebuild test \
        -project PitPass.xcodeproj -scheme PitPass \
        -destination 'platform=iOS Simulator,name=iPad Pro 11-inch (M5)' \
        -derivedDataPath build-tests \
        CODE_SIGNING_ALLOWED=NO \
        | pretty
fi

# --- Archive ---------------------------------------------------------------

log "Archive (Release, generic iOS device)"
xcodebuild archive \
    -project PitPass.xcodeproj -scheme PitPass -configuration Release \
    -destination 'generic/platform=iOS' \
    -archivePath "$archive" \
    -derivedDataPath "$out/DerivedData" \
    -allowProvisioningUpdates ${auth[@]+"${auth[@]}"} \
    DEVELOPMENT_TEAM="$team" \
    MARKETING_VERSION="$version" \
    CURRENT_PROJECT_VERSION="$build" \
    | pretty

# --- Export / upload -------------------------------------------------------

if [ "$upload" = 1 ]; then destination=upload; else destination=export; fi
if [ ${#auth[@]} -gt 0 ]; then
    log "App Store profile for the keychain's Apple Distribution certificate"
    profile="$(python3 asc_profile.py --key-id "$ASC_KEY_ID" --issuer-id "$ASC_ISSUER_ID" \
        --key-path "$key_path" --team "$team" --bundle-id "$bundle_id" --name "$profile_name")"
    signing="	<key>signingStyle</key><string>manual</string>
	<key>signingCertificate</key><string>Apple Distribution</string>
	<key>provisioningProfiles</key><dict><key>$bundle_id</key><string>$profile</string></dict>"
else
    signing="	<key>signingStyle</key><string>automatic</string>"
fi
cat > "$out/ExportOptions.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key><string>app-store-connect</string>
	<key>destination</key><string>$destination</string>
	<key>teamID</key><string>$team</string>
$signing
	<key>uploadSymbols</key><true/>
	<key>manageAppVersionAndBuildNumber</key><false/>
</dict>
</plist>
EOF

if [ "$upload" = 1 ]; then log "Upload to App Store Connect"; else log "Export .ipa"; fi
xcodebuild -exportArchive \
    -archivePath "$archive" \
    -exportOptionsPlist "$out/ExportOptions.plist" \
    -exportPath "$out/export" \
    -allowProvisioningUpdates ${auth[@]+"${auth[@]}"} \
    | pretty

# --- Tag -------------------------------------------------------------------

if [ "$upload" = 1 ] && [ "$dirty" = 0 ]; then
    tag="ios/v$version-$build"
    git tag -a "$tag" -m "Pit Pass for iPad $version ($build)"
    log "Tagged $tag — push it with: git push origin $tag"
fi

log "Done: Pit Pass $version ($build)"
if [ "$upload" = 1 ]; then
    cat <<EOF
App Store Connect is processing the build (usually 5–15 minutes). Then:
  · TestFlight → iOS builds: the build appears under $version; internal groups
    with "automatic distribution" get it without further clicks.
  · Add "What to Test" notes on the build if testers should look at something.
  · Archive kept at ios/$archive (dSYMs inside; keep it until the next release).
EOF
else
    echo ".ipa exported to ios/$out/export/"
fi
