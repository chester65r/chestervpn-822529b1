#!/usr/bin/env bash
# Runs the WireGuard packet-level interoperability fixture. Requires Bun and Go 1.24+.
# Evidence scope: wireguard-go engine + shared config format on loopback. NOT an Android device test.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
fixtures="$(mktemp -d)"
trap 'rm -rf "$fixtures"' EXIT
bun "$here/generate-fixtures.ts" "$fixtures"
cd "$here"
WG_FIXTURES="$fixtures" go test -count=1 -v ./...
