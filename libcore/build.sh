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
# The Android binding build resolves its own temporary module graph. Keep it on
# the same reachable checksum/proxy route as the verified core unit tests.
export GOPROXY="${GOPROXY:-https://goproxy.cn,direct}"
export GOSUMDB="${GOSUMDB:-sum.golang.google.cn}"
export GONOSUMDB="${GONOSUMDB:-github.com/sagernet/*}"
"$GOPATH"/bin/gomobile-matsuri bind -v -target=android/arm64 -androidapi 21 -cache "$(realpath "$BUILD")" -trimpath -ldflags='-s -w -X github.com/sagernet/sing-box/constant.Version=1.14.0-alpha.48' -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls' .
rm -f libcore-sources.jar

proj="../app/libs"
mkdir -p "$proj"
cp -f libcore.aar "$proj"
../scripts/libcore-source-fingerprint.sh > "$proj/libcore.sources.sha256"
echo ">> install $(realpath "$proj")/libcore.aar"
