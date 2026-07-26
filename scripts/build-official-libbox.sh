#!/usr/bin/env bash
# Build the official sing-box Android bridge used by the Kotlin VPN service.
# NekoPilot deliberately ships no second, product-specific Go runtime.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
version=${SING_BOX_VERSION:-1.14.0-beta.1}
commit=${SING_BOX_COMMIT:-8bc6787c7ff785e5f6343241affdadd5ca239bd7}
abis=${NEKOPILOT_LIBBOX_ABIS:-arm64-v8a}
cache_root=${NEKOPILOT_BUILD_CACHE:-"${XDG_CACHE_HOME:-$HOME/.cache}/nekopilot"}
source_dir=${SING_BOX_SOURCE:-"$cache_root/sing-box-$version"}
tools_dir="$cache_root/gomobile-0.1.12"
output_dir="$root/app/libs"
output_aar="$output_dir/libbox.aar"
ndk_version=${NEKOPILOT_NDK_VERSION:-28.1.13356709}

# Naive's pinned Cronet static library is built with the upstream Android r28 toolchain. Older
# NDK linkers reject its AArch64 authenticated relocations, so never leave gomobile to pick an
# arbitrary side-by-side NDK from the SDK directory. Hosted runners can pre-set
# ANDROID_NDK_HOME to an older side-by-side version, so prefer the repository pin explicitly.
sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [ -n "$sdk_root" ]; then
  candidate_ndk="$sdk_root/ndk/$ndk_version"
  if [ -d "$candidate_ndk" ]; then
    export ANDROID_NDK_HOME="$candidate_ndk"
  fi
fi
if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "Android NDK r28 is required; install ndk;$ndk_version or set ANDROID_NDK_HOME" >&2
  exit 1
fi
ndk_source_properties="$ANDROID_NDK_HOME/source.properties"
actual_ndk_version=$(
  awk '/^[[:space:]]*Pkg[.]Revision[[:space:]]*=/ {
    line = $0
    sub(/^[^=]*=[[:space:]]*/, "", line)
    print line
    exit
  }' "$ndk_source_properties" 2>/dev/null || true
)
if [ "$actual_ndk_version" != "$ndk_version" ]; then
  echo "Android NDK $ndk_version is required (got ${actual_ndk_version:-unknown} at $ANDROID_NDK_HOME)" >&2
  exit 1
fi
echo ">> using Android NDK $actual_ndk_version at $ANDROID_NDK_HOME"

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
# A caller-provided directory is a supply-chain input just like the automatically populated
# cache. Checking only its go.mod would let a different (or locally modified) sing-box source
# silently produce a release AAR. Require a detached/checked-out Git revision at the pin.
actual_commit=$(git -C "$source_dir" rev-parse HEAD 2>/dev/null || true)
[ "$actual_commit" = "$commit" ] || {
  echo "Official sing-box checkout must be at pinned commit $commit (got ${actual_commit:-non-git source})" >&2
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

# Official libbox sets a platform log writer for its command server. In sing-box 1.14 this also
# instantiates the internal Clash state collector. The build tag is therefore required even
# though NekoPilot exposes no Clash REST listener, dashboard, configuration, or YACD assets.
tags='with_gvisor,with_quic,with_wireguard,with_utls,with_naive_outbound,with_clash_api,badlinkname,tfogo_checklinkname0,with_low_memory'
temporary_dir=$(mktemp -d "${TMPDIR:-/tmp}/nekopilot-libbox.XXXXXX")
temporary_aar="$temporary_dir/libbox.aar"
trap 'rm -rf "$temporary_dir"' EXIT
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
    -ldflags="-X github.com/sagernet/sing-box/constant.Version=$version -X runtime.godebugDefault=multipathtcp=0,tlssha1=1,tlsunsafeekm=1 -s -w -buildid= -checklinkname=0" \
    -tags="$tags" \
    ./experimental/libbox
)

mkdir -p "$output_dir"
cp "$temporary_aar" "$output_aar"
printf '%s\n' "$version" > "$output_dir/libbox.version"
echo ">> installed official libbox $version ($abis) at $output_aar"
