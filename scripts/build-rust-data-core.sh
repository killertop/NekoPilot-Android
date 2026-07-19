#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${1:?missing Android JNI output directory}"
sdk_dir="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
ndk_dir="${ANDROID_NDK_HOME:-$sdk_dir/ndk/25.0.8775105}"
ndk_host="$(find "$ndk_dir/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
linker="$ndk_host/bin/aarch64-linux-android21-clang"
stripper="$ndk_host/bin/llvm-strip"

if [[ ! -x "$linker" || ! -x "$stripper" ]]; then
  echo "Android NDK aarch64 toolchain not found under: $ndk_host/bin" >&2
  exit 1
fi

if command -v cargo >/dev/null 2>&1; then
  cargo_command=(cargo)
elif command -v rustup >/dev/null 2>&1; then
  cargo_path="$(rustup which --toolchain stable cargo)"
  export PATH="$(dirname "$cargo_path"):$PATH"
  cargo_command=("$cargo_path")
else
  echo "Rust stable toolchain is required to build nekodata-core." >&2
  exit 1
fi

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$linker"
"${cargo_command[@]}" build \
  --manifest-path "$root/rust/nekodata-core/Cargo.toml" \
  --target aarch64-linux-android \
  --release \
  --locked

mkdir -p "$output_dir"
cp "$root/rust/nekodata-core/target/aarch64-linux-android/release/libnekodata_core.so" \
  "$output_dir/libnekodata_core.so"
"$stripper" --strip-unneeded "$output_dir/libnekodata_core.so"
