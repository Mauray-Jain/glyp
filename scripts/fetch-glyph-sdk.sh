#!/usr/bin/env bash
# Downloads Nothing's Glyph SDK into app/libs. It is not checked in because Nothing's
# Glyph Developer Kit repository publishes it without a licence for redistribution.
set -euo pipefail

SDK=glyph-matrix-sdk-2.0.aar
URL="https://raw.githubusercontent.com/Nothing-Developer-Programme/Glyph-Developer-Kit/main/sdk/$SDK"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/libs/$SDK"

mkdir -p "$(dirname "$DEST")"
curl -fL --progress-bar -o "$DEST" "$URL"
echo "Saved $DEST"
