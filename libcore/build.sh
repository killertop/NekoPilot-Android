#!/usr/bin/env bash
set -eo pipefail

[ ! -f ./env_java.sh ] || source ./env_java.sh
source ../buildScript/init/env_ndk.sh

BUILD=".build"

rm -rf "$BUILD/android" \
  "$BUILD/java" \
  "$BUILD/javac-output" \
  "$BUILD/src"
mkdir -p "$BUILD"

if [ -z "$GOPATH" ]; then
  GOPATH=$(go env GOPATH)
fi

export GOBIND="$GOPATH/bin/gobind-matsuri"
"$GOPATH"/bin/gomobile-matsuri bind -v -androidapi 21 -cache "$(realpath "$BUILD")" -trimpath -ldflags='-s -w' -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls' .
rm -f libcore-sources.jar

proj="../app/libs"
mkdir -p "$proj"
cp -f libcore.aar "$proj"
../scripts/libcore-source-fingerprint.sh > "$proj/libcore.sources.sha256"
echo ">> install $(realpath "$proj")/libcore.aar"
