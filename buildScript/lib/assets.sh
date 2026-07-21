#!/bin/bash

set -euo pipefail

DIR=app/src/main/assets/sing-box
mkdir -p "$DIR"

# The 1.14 runtime consumes standard SRS rule-sets. Remove the superseded DB
# assets so a clean package cannot accidentally retain two rule formats.
for legacy_asset in geoip.db.xz geoip.version.txt geosite.db.xz geosite.version.txt; do
  if [[ -e "${DIR}/${legacy_asset}" ]]; then
    unlink "${DIR}/${legacy_asset}"
  fi
done

download_asset() {
  local repo="$1"
  local filename="$2"
  local version_file="${filename%.srs}.version.txt"
  local staging_dir
  local downloaded=false
  local url

  if [[ -f "${DIR}/${filename}.xz" && -f "${DIR}/${version_file}" ]]; then
    echo "${filename} already bundled"
    return
  fi

  staging_dir="$(mktemp -d)"
  for url in \
    "https://raw.githubusercontent.com/${repo}/rule-set/${filename}" \
    "https://cdn.jsdelivr.net/gh/${repo}@rule-set/${filename}"; do
    if curl --fail --location --silent --show-error --retry 3 --retry-all-errors \
      --connect-timeout 10 --max-time 60 \
      --user-agent "NekoPilot-build" \
      --output "${staging_dir}/${filename}" "$url"; then
      downloaded=true
      break
    fi
  done
  if [[ "$downloaded" != true ]]; then
    rm -rf "$staging_dir"
    echo "Unable to download ${filename}" >&2
    exit 1
  fi
  (
    cd "$staging_dir"
    shasum -a 256 "$filename" | awk '{print $1}' > "$version_file"
    xz -9 "$filename"
  )

  mv -f "${staging_dir}/${filename}.xz" "${DIR}/${filename}.xz"
  mv -f "${staging_dir}/${version_file}" "${DIR}/${version_file}"
}

download_asset "SagerNet/sing-geoip" "geoip-cn.srs"
download_asset "SagerNet/sing-geosite" "geosite-cn.srs"
