#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RESOURCE_PATH="$ROOT/app/src/main/assets/AuroraSignedSeedTrust.bin"
ENCODED='AQGhoaGhoaGhoaGhoaGhoaGhDmehdcbgoQvoAE1oXDEFyAFBAgEAQQRrF9Hy4SxCR/i85uVjpEDydwN9gS3rM6D0oTlF2JjClk/jQuL+Gn+bjufrSnwPnhYrzjNXazFezsu2QGg3v1H1AAAAAAAAAAEAAAAA9IZXAAAAAAAE'

mkdir -p "$(dirname "$RESOURCE_PATH")"
umask 077
if base64 --decode </dev/null >/dev/null 2>&1; then
    printf '%s' "$ENCODED" | base64 --decode > "$RESOURCE_PATH"
else
    printf '%s' "$ENCODED" | base64 -D > "$RESOURCE_PATH"
fi
chmod 600 "$RESOURCE_PATH"
printf 'prepared non-production native trust test resource\n'
