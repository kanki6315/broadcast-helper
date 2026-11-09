#!/bin/sh
# Build and run the question lab (see main.swift) with the app's own sources.
# Private Cloud Compute needs the managed entitlement, which an unsigned
# command-line tool can't carry — the lab reports the fallback and runs on-device.
set -e
here="$(cd "$(dirname "$0")" && pwd)"
app="$here/../../PitPass"
out="${TMPDIR:-/tmp}/pitpass-question-lab"
swiftc -O -swift-version 6 -o "$out" \
  "$here/main.swift" \
  "$app/Questions/"*.swift \
  "$app/Model/SheetModels.swift" "$app/Model/ProfileModels.swift" "$app/Model/SeasonModels.swift" \
  "$app/Conversations/ConversationBook.swift" "$app/Season/ProfileLogic.swift"
exec "$out" "$@"
