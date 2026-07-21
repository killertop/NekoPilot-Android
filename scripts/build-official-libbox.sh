#!/usr/bin/env bash
# Build the official sing-box Android bridge used by the Kotlin VPN service.
# NekoPilot deliberately ships no second, product-specific Go runtime.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
version=${SING_BOX_VERSION:-1.14.0-alpha.48}
commit=${SING_BOX_COMMIT:-fa36eb769a200e9558c414a36eb16da9a2446ea9}
abis=${NEKOPILOT_LIBBOX_ABIS:-arm64-v8a}
cache_root=${NEKOPILOT_BUILD_CACHE:-"${XDG_CACHE_HOME:-$HOME/.cache}/nekopilot"}
source_dir=${SING_BOX_SOURCE:-"$cache_root/sing-box-$version"}
tools_dir="$cache_root/gomobile-0.1.12"
output_dir="$root/app/libs"
output_aar="$output_dir/libbox.aar"

targets=()
IFS=',' read -r -a requested_abis <<< "$abis"
for abi in "${requested_abis[@]}"; do
  case "${abi//[[:space:]]/}" in
    arm64-v8a) targets+=(android/arm64) ;;
    x86_64) targets+=(android/amd64) ;;
    *)
      echo "Unsupported NEKOPILOT_LIBBOX_ABIS value: $abi (expected arm64-v8a and/or x86_64)" >&2
      exit 1
      ;;
  esac
done
[ "${#targets[@]}" -gt 0 ] || { echo "No libbox ABI requested" >&2; exit 1; }
target=$(IFS=,; echo "${targets[*]}")

export GOPROXY=${GOPROXY:-https://goproxy.cn,direct}
export GOSUMDB=${GOSUMDB:-sum.golang.google.cn}
export GONOSUMDB=${GONOSUMDB:-github.com/sagernet/*}

if [ -z "${SING_BOX_SOURCE:-}" ] && [ ! -f "$source_dir/go.mod" ]; then
  # A cancelled clone can leave an empty .git directory. Fetching the immutable
  # commit into that directory makes the next build recover instead of failing.
  mkdir -p "$source_dir"
  if [ ! -d "$source_dir/.git" ]; then
    git -C "$source_dir" init -q
  fi
  if git -C "$source_dir" remote get-url origin >/dev/null 2>&1; then
    git -C "$source_dir" remote set-url origin https://github.com/SagerNet/sing-box.git
  else
    git -C "$source_dir" remote add origin https://github.com/SagerNet/sing-box.git
  fi
  git -C "$source_dir" fetch --depth 1 origin "$commit"
  git -C "$source_dir" checkout --detach --force FETCH_HEAD
fi
grep -qF 'module github.com/sagernet/sing-box' "$source_dir/go.mod" || {
  echo "SING_BOX_SOURCE is not an official sing-box checkout: $source_dir" >&2
  exit 1
}
if [ -z "${SING_BOX_SOURCE:-}" ]; then
  actual_commit=$(git -C "$source_dir" rev-parse HEAD)
  [ "$actual_commit" = "$commit" ] || {
    echo "Official sing-box checkout is $actual_commit, expected pinned $commit" >&2
    exit 1
  }
fi

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
    -target "$target" \
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
echo ">> installed official libbox $version ($abis) at $output_aar"
