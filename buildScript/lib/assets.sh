#!/bin/bash

set -euo pipefail

DIR=app/src/main/assets/sing-box
rm -rf "$DIR"
mkdir -p "$DIR"
cd "$DIR"

latest_release_tag() {
  local repo="$1"
  local filename="$2"
  curl --fail --silent --show-error --location --head --retry 3 --retry-all-errors \
    --user-agent "NekoPilot-build" \
    "https://github.com/${repo}/releases/latest/download/${filename}" |
    tr -d '\r' |
    sed -nE 's#^[Ll]ocation: https://github\.com/[^/]+/[^/]+/releases/download/([^/]+)/.*#\1#p' |
    sed -n '1p'
}

download_asset() {
  local repo="$1"
  local filename="$2"
  local version_file="${filename%.db}.version.txt"
  local version

  version="$(latest_release_tag "$repo" "$filename" || true)"
  version="${version:-latest}"
  printf '%s' "$version" > "$version_file"
  echo "${filename} version=${version}"

  curl --fail --location --silent --show-error --retry 3 --retry-all-errors \
    --user-agent "NekoPilot-build" \
    --output "$filename" \
    "https://github.com/${repo}/releases/latest/download/${filename}"
  xz -9 "$filename"
}

download_asset "SagerNet/sing-geoip" "geoip.db"
download_asset "SagerNet/sing-geosite" "geosite.db"
