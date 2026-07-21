#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
temporary=$(mktemp)
trap 'rm -f "$temporary"' EXIT

{
  echo "nekopilot-libcore-fingerprint-v1"
  go version
  find "$root/libcore" \
    \( -path "$root/libcore/.build" -o -path "$root/libcore/vendor" \) -prune \
    -o -type f \
    \( -name '*.go' -o -name '*.c' -o -name '*.cc' -o -name '*.cpp' \
       -o -name '*.cxx' -o -name '*.m' -o -name '*.h' -o -name '*.hh' \
       -o -name '*.hpp' -o -name '*.hxx' -o -name '*.f' -o -name '*.F' \
       -o -name '*.for' -o -name '*.f90' -o -name '*.s' -o -name '*.S' \
       -o -name '*.syso' -o -name 'go.mod' -o -name 'go.sum' \
       -o -name 'build.sh' -o -name 'init.sh' \) \
    -print
  find "$root/buildScript/lib/core" -type f -print
  printf '%s\n' \
    "$root/buildScript/init/env.sh" \
    "$root/buildScript/init/env_ndk.sh" \
    "$root/scripts/libcore-source-fingerprint.sh"
} | {
  read -r format
  read -r go_version
  printf '%s\n%s\n' "$format" "$go_version" > "$temporary"
  LC_ALL=C sort -u | while IFS= read -r file; do
    [ -f "$file" ] || continue
    relative=${file#"$root/"}
    digest=$(openssl dgst -sha256 "$file" | awk '{print $NF}')
    printf '%s  %s\n' "$digest" "$relative" >> "$temporary"
  done
}

openssl dgst -sha256 "$temporary" | awk '{print $NF}'
