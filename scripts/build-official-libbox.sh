#!/usr/bin/env bash
# Build the official sing-box Android bridge used by the Kotlin VPN service.
# NekoPilot deliberately ships no second, product-specific Go runtime.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
version=${SING_BOX_VERSION:-1.14.0-alpha.48}
cache_root=${NEKOPILOT_BUILD_CACHE:-"${XDG_CACHE_HOME:-$HOME/.cache}/nekopilot"}
source_dir=${SING_BOX_SOURCE:-"$cache_root/sing-box-$version"}
tools_dir="$cache_root/gomobile-0.1.12"
output_dir="$root/app/libs"
output_aar="$output_dir/libbox.aar"

export GOPROXY=${GOPROXY:-https://goproxy.cn,direct}
export GOSUMDB=${GOSUMDB:-sum.golang.google.cn}
export GONOSUMDB=${GONOSUMDB:-github.com/sagernet/*}

if [ ! -f "$source_dir/go.mod" ]; then
  mkdir -p "$(dirname "$source_dir")"
  git clone --depth 1 --branch "v$version" \
    https://github.com/SagerNet/sing-box.git "$source_dir"
fi
rg -q 'module github.com/sagernet/sing-box' "$source_dir/go.mod" || {
  echo "SING_BOX_SOURCE is not an official sing-box checkout: $source_dir" >&2
  exit 1
}

mkdir -p "$tools_dir"
gomobile_module="$(go env GOPATH)/pkg/mod/github.com/sagernet/gomobile@v0.1.12"
if [ ! -d "$gomobile_module" ]; then
  (cd "$source_dir" && go mod download github.com/sagernet/gomobile@v0.1.12)
fi
if [ ! -x "$tools_dir/gomobile" ] || [ ! -x "$tools_dir/gobind" ]; then
  (cd "$gomobile_module" && go build -o "$tools_dir/gomobile" ./cmd/gomobile)
  (cd "$gomobile_module" && go build -o "$tools_dir/gobind" ./cmd/gobind)
fi

# CommandServer uses the upstream Clash state collector internally when a platform log writer is
# attached. This compiles that implementation only; NekoPilot emits no `experimental.clash_api`
# configuration, listener, REST endpoint, or YACD asset.
tags='with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0,with_low_memory'
temporary_aar=$(mktemp "${TMPDIR:-/tmp}/nekopilot-libbox.XXXXXX.aar")
trap 'rm -f "$temporary_aar"' EXIT
(
  cd "$source_dir"
  PATH="$tools_dir:$PATH" "$tools_dir/gomobile" bind \
    -o "$temporary_aar" \
    -target android/arm64 \
    -androidapi 23 \
    -javapkg=io.nekohasekai \
    -libname=box \
    -trimpath \
    -buildvcs=false \
    -ldflags="-X github.com/sagernet/sing-box/constant.Version=$version -s -w -buildid= -checklinkname=0" \
    -tags="$tags" \
    ./experimental/libbox
)

mkdir -p "$output_dir"
cp "$temporary_aar" "$output_aar"
printf '%s\n' "$version" > "$output_dir/libbox.version"
echo ">> installed official libbox $version at $output_aar"
