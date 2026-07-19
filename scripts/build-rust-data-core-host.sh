#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${1:?missing host JNI output directory}"

case "$(uname -s)" in
  Darwin) host_library="libnekodata_core.dylib" ;;
  MINGW*|MSYS*|CYGWIN*) host_library="nekodata_core.dll" ;;
  *) host_library="libnekodata_core.so" ;;
esac

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

"${cargo_command[@]}" build --manifest-path "$root/rust/nekodata-core/Cargo.toml" --release --locked
mkdir -p "$output_dir"
cp "$root/rust/nekodata-core/target/release/$host_library" \
  "$output_dir/$host_library"
