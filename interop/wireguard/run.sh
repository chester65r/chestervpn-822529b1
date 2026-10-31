#!/usr/bin/env bash
# Runs the WireGuard packet-level interoperability fixture. Requires Bun and Go 1.24+.
# Evidence scope: wireguard-go engine + shared config format on loopback. NOT an Android device test.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
fixtures="$(mktemp -d)"
trap 'rm -rf "$fixtures"' EXIT
bun "$here/generate-fixtures.ts" "$fixtures"
cd "$here"
if command -v go >/dev/null 2>&1; then
  WG_FIXTURES="$fixtures" go test -count=1 -v ./...
elif command -v nix >/dev/null 2>&1; then
  WG_FIXTURES="$fixtures" nix shell nixpkgs#go --command go test -count=1 -v ./...
else
  echo "Go 1.24+ is required (or Nix to provide it)." >&2
  exit 127
fi
