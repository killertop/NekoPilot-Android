#!/bin/bash

set -euo pipefail

DIR=app/src/main/assets/sing-box
assets=(geoip-cn.srs geosite-cn.srs)
legacy_assets=(geoip.db.xz geoip.version.txt geosite.db.xz geosite.version.txt)

# These assets are source-controlled and their uncompressed SHA-256 is committed beside each
# archive. Builds must be reproducible and offline: refreshing rule data is an explicit reviewed
# maintenance operation, never an implicit fetch from a mutable branch or CDN.
for legacy_asset in "${legacy_assets[@]}"; do
  if [[ -e "${DIR}/${legacy_asset}" ]]; then
    echo "Obsolete bundled rule asset: ${DIR}/${legacy_asset}" >&2
    exit 1
  fi
done

for filename in "${assets[@]}"; do
  archive="${DIR}/${filename}.xz"
  version_file="${DIR}/${filename%.srs}.version.txt"
  if [[ ! -f "$archive" || ! -f "$version_file" ]]; then
    echo "Missing source-controlled bundled rule asset for ${filename}" >&2
    exit 1
  fi

  expected="$(tr -d '\r\n' < "$version_file")"
  if [[ ! "$expected" =~ ^[[:xdigit:]]{64}$ ]]; then
    echo "Invalid SHA-256 sidecar for ${filename}" >&2
    exit 1
  fi

  actual="$(xz --decompress --stdout -- "$archive" | shasum -a 256 | awk '{print $1}')"
  if [[ "$actual" != "$expected" ]]; then
    echo "Bundled rule asset digest mismatch for ${filename}" >&2
    exit 1
  fi
done
