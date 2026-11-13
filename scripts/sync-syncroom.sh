#!/usr/bin/env bash
# Vendor the syncroom Kotlin SDK into the app (the syncroom repo is private,
# so CI can't fetch it). Never edit app/src/main/java/dev/syncroom by hand:
# change it in syncroom/sdk/kotlin (where its tests live) and re-run this.
#   scripts/sync-syncroom.sh [path/to/syncroom]
set -euo pipefail
SRC="${1:-$(dirname "$0")/../../syncroom}"
SDK="$SRC/sdk/kotlin/src/main/kotlin/dev/syncroom"
DST="$(dirname "$0")/../app/src/main/java/dev/syncroom"
[ -d "$SDK" ] || { echo "no SDK at $SDK" >&2; exit 1; }
REV="$(git -C "$SRC" rev-parse --short HEAD)$(git -C "$SRC" diff --quiet -- sdk/kotlin || echo '-dirty')"
rm -rf "$DST" && mkdir -p "$DST"
for f in "$SDK"/*.kt; do
  { echo "// Vendored from syncroom@$REV (sdk/kotlin) by scripts/sync-syncroom.sh. Do not edit here."
    cat "$f"; } > "$DST/$(basename "$f")"
done
echo "vendored syncroom@$REV -> app/src/main/java/dev/syncroom ($(ls "$DST" | wc -l) files)"
