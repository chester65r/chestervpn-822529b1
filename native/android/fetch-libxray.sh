#!/usr/bin/env bash
# Fetches the pinned Android libXray AAR and includes upstream license texts.
set -euo pipefail
destination="${1:?Usage: fetch-libxray.sh <generated-project>/app/libs>}"
mkdir -p "$destination"
app_dir="$(cd "$destination/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
version="v26.10.31"
archive="$tmp/libxray-android.zip"
url="https://github.com/XTLS/libXray/releases/download/${version}/libxray-android.zip"
curl --fail --location --retry 3 --retry-delay 2 --output "$archive" "$url"
echo "fb99f04acf379cc2957c1e1d0ce2b3c0005d3f49974a44c09a11242a0562c1ca  $archive" | sha256sum --check
python3 - "$archive" "$destination/libXray.aar" <<'PY'
import pathlib, shutil, sys, zipfile
archive, output = sys.argv[1:]
with zipfile.ZipFile(archive) as bundle:
    matches = [item for item in bundle.infolist() if not item.is_dir() and pathlib.PurePosixPath(item.filename).name == 'libXray.aar']
    if len(matches) != 1:
        raise SystemExit('Expected exactly one libXray.aar in the verified release archive')
    item = matches[0]
    if item.file_size < 10_000_000 or item.file_size > 120_000_000:
        raise SystemExit('Unexpected libXray.aar size')
    with bundle.open(item) as source, open(output, 'wb') as target:
        shutil.copyfileobj(source, target)
PY
test -s "$destination/libXray.aar"
licenses="$app_dir/src/main/assets/licenses"
mkdir -p "$licenses"
curl --fail --location --retry 3 "https://raw.githubusercontent.com/XTLS/libXray/${version}/LICENSE" -o "$licenses/LibXray-MIT.txt"
curl --fail --location --retry 3 "https://raw.githubusercontent.com/XTLS/Xray-core/${version}/LICENSE" -o "$licenses/Xray-core-MPL-2.0.txt"
grep -q '^MIT License' "$licenses/LibXray-MIT.txt"
grep -q '^Mozilla Public License Version 2.0' "$licenses/Xray-core-MPL-2.0.txt"
echo "Verified libXray ${version} AAR and upstream licenses: $destination/libXray.aar"
