#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
PROJECT_ROOT="$PWD"
pushd ..

####

SING_BOX_DIR="sing-box-${SING_BOX_VERSION}"
if [ ! -d "$SING_BOX_DIR" ]; then
  TMP_DIR=$(mktemp -d)
  trap 'rm -rf "$TMP_DIR"' EXIT
  ARCHIVE="$TMP_DIR/sing-box.tar.gz"
  curl --fail --location --silent --show-error --retry 3 --retry-all-errors \
    --connect-timeout 10 --max-time 180 --user-agent "NekoPilot-build" \
    --output "$ARCHIVE" "https://codeload.github.com/SagerNet/sing-box/tar.gz/$SING_BOX_COMMIT"
  tar -tzf "$ARCHIVE" | head -n 1 | grep -q "^sing-box-${SING_BOX_COMMIT}/"
  tar -xzf "$ARCHIVE" -C "$TMP_DIR"
  mv "$TMP_DIR/sing-box-$SING_BOX_COMMIT" "$SING_BOX_DIR"
  rm -rf "$TMP_DIR"
  trap - EXIT
fi
test -f "$SING_BOX_DIR/go.mod"

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/libneko.git
fi
pushd libneko
git checkout "$COMMIT_LIBNEKO"
popd

####

popd
