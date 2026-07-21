#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT

actual_java="$temporary/actual-java.txt"
find "$root" \
  \( -path "$root/.git" -o -path "$root/.gradle" -o -path "$root/app/build" \
     -o -path "$root/build" -o -path "$root/design/audit" -o -path "$root/external" \
     \) -prune \
  -o -type f -name '*.java' -print |
  sed "s#^$root/##" | LC_ALL=C sort > "$actual_java"
if [ -s "$actual_java" ]; then
  echo "Java source is not allowed; Android code belongs in Kotlin:" >&2
  cat "$actual_java" >&2
  exit 1
fi

# Kotlin owns Android-side parsing, subscriptions, and pure data algorithms.
# The final native boundary is the upstream libbox AAR only.

forbidden_files="$temporary/forbidden-files.txt"
find "$root" \
  \( -path "$root/.git" -o -path "$root/.gradle" -o -path "$root/app/build" \
     -o -path "$root/build" -o -path "$root/design/audit" -o -path "$root/external" \
     \) -prune \
  -o -type f \
  \( -name '*.rs' -o -name 'Cargo.toml' -o -name 'Cargo.lock' \
     -o -name 'rust-toolchain' -o -name 'rust-toolchain.toml' \
     -o -name '*.rlib' -o -name '*.rmeta' -o -path '*/.cargo/*' \) \
  -print | sed "s#^$root/##" | LC_ALL=C sort > "$forbidden_files"
find "$root/scripts" -type f \
  \( -iname '*build*rust*' -o -iname '*cargo*' -o -iname '*nekodata*' \) \
  -print | sed "s#^$root/##" >> "$forbidden_files"
if [ -s "$forbidden_files" ]; then
  echo "Rust source, toolchain metadata, or build artifacts are not allowed:" >&2
  cat "$forbidden_files" >&2
  exit 1
fi

configuration_hits="$temporary/configuration-hits.txt"
scan_paths=(
  "$root/.github" "$root/app/src" "$root/app/build.gradle.kts"
  "$root/build.gradle.kts" "$root/settings.gradle.kts" "$root/repositories.gradle.kts"
  "$root/gradle.properties" "$root/gradlew" "$root/buildSrc" "$root/buildScript"
  "$root/scripts/build-official-libbox.sh" "$root/run"
)
{
  grep -RInE -i 'cargo|rustc|rustup|rust-toolchain|RustDataCore|nekodata|System\.loadLibrary|external[[:space:]]+fun' \
    "${scan_paths[@]}" || true
  find "$root/scripts" -type f ! -name 'verify-language-boundaries.sh' -exec \
    grep -HnEi 'cargo|rustc|rustup|rust-toolchain|RustDataCore|nekodata|System\.loadLibrary|external[[:space:]]+fun' {} + || true
} > "$configuration_hits"
if [ -s "$configuration_hits" ]; then
  echo "Unapproved native bridge or Rust tooling remains in executable source/configuration:" >&2
  cat "$configuration_hits" >&2
  exit 1
fi

actual_cgo="$temporary/actual-go.txt"
find "$root" \
  \( -path "$root/.git" -o -path "$root/.gradle" -o -path "$root/app/build" \
     -o -path "$root/build" -o -path "$root/design/audit" -o -path "$root/external" \) -prune \
  -o -type f -name '*.go' -print | sed "s#^$root/##" | LC_ALL=C sort > "$actual_cgo"
if [ -s "$actual_cgo" ]; then
  echo "Product Go source is not allowed; use the official libbox AAR:" >&2
  cat "$actual_cgo" >&2
  exit 1
fi

unapproved_native="$temporary/unapproved-native.txt"
find "$root/app/src" "$root/app/executableSo" \
  -type f \( -name '*.so' -o -name '*.dylib' -o -name '*.dll' \) \
  -print 2>/dev/null | sed "s#^$root/##" > "$unapproved_native" || true
if [ -s "$unapproved_native" ]; then
  echo "Native libraries must come only from the verified Go AAR:" >&2
  cat "$unapproved_native" >&2
  exit 1
fi

if [ -d "$root/app/libs" ]; then
  unexpected_libs="$temporary/unexpected-libs.txt"
  find "$root/app/libs" -maxdepth 1 -type f \
    ! -name 'libbox.aar' ! -name 'libbox.version' -print > "$unexpected_libs"
  if [ -s "$unexpected_libs" ]; then
    echo "app/libs contains an unapproved dependency:" >&2
    cat "$unexpected_libs" >&2
    exit 1
  fi
fi

official_aar="$root/app/libs/libbox.aar"
if [ -f "$official_aar" ]; then
  marker="$root/app/libs/libbox.version"
  [ -f "$marker" ] || {
    echo "libbox.aar has no pinned version marker; rebuild it with ./scripts/build-official-libbox.sh" >&2
    exit 1
  }
  [ "$(tr -d '[:space:]' < "$marker")" = '1.14.0-alpha.48' ] || {
    echo "libbox.aar version marker is not the pinned official core" >&2
    exit 1
  }
  actual_aar_native="$temporary/actual-official-aar-native.txt"
  unzip -Z1 "$official_aar" | grep -E '\.(so|dylib|dll)$' | LC_ALL=C sort > "$actual_aar_native"
  if [ ! -s "$actual_aar_native" ] ||
    grep -Ev '^jni/(arm64-v8a|x86_64)/libbox\.so$' "$actual_aar_native" >/dev/null; then
    echo "libbox.aar native entries differ from the official AAR allowlist." >&2
    exit 1
  fi
fi

while IFS= read -r apk; do
  apk_native="$temporary/apk-native.txt"
  unzip -Z1 "$apk" | grep -E '\.(so|dylib|dll)$' | LC_ALL=C sort > "$apk_native"
  # The packaged runtime is determined by the declared AAR, not by whether
  # transitional source files still exist in the checkout.
  expected_apk_native='^lib/(arm64-v8a|x86_64)/libbox\.so$'
  if [ ! -s "$apk_native" ] || grep -Ev "$expected_apk_native" "$apk_native" >/dev/null; then
    echo "APK native entries differ from the declared runtime allowlist: $apk" >&2
    cat "$apk_native" >&2
    exit 1
  fi
done < <(
  # Historical APKs may have been produced before the runtime cutover. Only
  # inspect outputs generated after the currently declared AAR; the build
  # system invokes this verifier before producing the next output.
  if [ -f "$root/app/libs/libbox.aar" ]; then
    find "$root/app/build/outputs/apk" -type f -name '*.apk' \
      ! -name '*-androidTest.apk' -newer "$root/app/libs/libbox.aar" -print 2>/dev/null || true
  else
    find "$root/app/build/outputs/apk" -type f -name '*.apk' \
      ! -name '*-androidTest.apk' -print 2>/dev/null || true
  fi
)

echo "Language boundaries verified: Kotlin platform code plus official libbox only; no product Go, Java, or Rust."
