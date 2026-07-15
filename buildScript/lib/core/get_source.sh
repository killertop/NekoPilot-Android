#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
PROJECT_ROOT="$PWD"
pushd ..

####

SING_BOX_DIR="sing-box-${SING_BOX_VERSION}-neko"
if [ ! -d "$SING_BOX_DIR" ]; then
  TMP_DIR=$(mktemp -d)
  trap 'rm -rf "$TMP_DIR"' EXIT
  ARCHIVE="$TMP_DIR/sing-box.tar.gz"
  curl --fail --location --retry 3 \
    "https://codeload.github.com/SagerNet/sing-box/tar.gz/refs/tags/v${SING_BOX_VERSION}" \
    --output "$ARCHIVE"
  echo "${SING_BOX_ARCHIVE_SHA256}  ${ARCHIVE}" | shasum -a 256 --check
  tar -xzf "$ARCHIVE" -C "$TMP_DIR"
  mv "$TMP_DIR/sing-box-${SING_BOX_VERSION}" "$SING_BOX_DIR"
  patch -d "$SING_BOX_DIR" -p1 \
    < "$PROJECT_ROOT/buildScript/lib/core/patches/sing-box-${SING_BOX_VERSION}-neko.patch"
  rm -rf "$TMP_DIR"
  trap - EXIT
fi
grep -q "${SING_BOX_VERSION}-neko-1" "$SING_BOX_DIR/constant/version.go"

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/libneko.git
fi
pushd libneko
git checkout "$COMMIT_LIBNEKO"
popd

####

popd
