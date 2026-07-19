#!/bin/bash

set -euo pipefail

DIR=app/src/main/assets/sing-box
mkdir -p "$DIR"

latest_release_tag() {
  local repo="$1"
  local filename="$2"
  curl --fail --silent --show-error --location --head --retry 3 --retry-all-errors \
    --connect-timeout 10 --max-time 60 \
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
  local staging_dir

  version="$(latest_release_tag "$repo" "$filename" || true)"
  version="${version:-latest}"
  if [[ -f "${DIR}/${filename}.xz" && -f "${DIR}/${version_file}" &&
    "$(tr -d '\r\n' < "${DIR}/${version_file}")" == "$version" ]]; then
    echo "${filename} already current (${version})"
    return
  fi

  staging_dir="$(mktemp -d)"
  echo "${filename} version=${version}"

  curl --fail --location --silent --show-error --retry 3 --retry-all-errors \
    --connect-timeout 10 --max-time 60 \
    --user-agent "NekoPilot-build" \
    --output "${staging_dir}/${filename}" \
    "https://github.com/${repo}/releases/latest/download/${filename}"
  curl --fail --location --silent --show-error --retry 3 --retry-all-errors \
    --connect-timeout 10 --max-time 60 \
    --user-agent "NekoPilot-build" \
    --output "${staging_dir}/${filename}.sha256sum" \
    "https://github.com/${repo}/releases/latest/download/${filename}.sha256sum"
  (
    cd "$staging_dir"
    shasum -a 256 -c "${filename}.sha256sum"
    xz -9 "$filename"
  )

  mv -f "${staging_dir}/${filename}.xz" "${DIR}/${filename}.xz"
  printf '%s' "$version" > "${DIR}/${version_file}"
}

download_asset "SagerNet/sing-geoip" "geoip.db"
download_asset "SagerNet/sing-geosite" "geosite.db"
