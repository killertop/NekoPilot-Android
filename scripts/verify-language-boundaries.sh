#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT

actual_java="$temporary/actual-java.txt"
find "$root" \
  \( -path "$root/.git" -o -path "$root/.gradle" -o -path "$root/app/build" \
     -o -path "$root/build" -o -path "$root/design/audit" -o -path "$root/external" \
     -o -path "$root/libcore/.build" \) -prune \
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
     -o -path "$root/libcore/.build" \) -prune \
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
  "$root/libcore/build.sh" "$root/libcore/init.sh" "$root/scripts/build-official-libbox.sh" "$root/run"
)
{
  rg -n -i '\b(cargo|rustc|rustup)\b|rust-toolchain|RustDataCore|nekodata|System\.loadLibrary|\bexternal\s+fun\b' \
    "${scan_paths[@]}" || true
  rg -n -i '\b(cargo|rustc|rustup)\b|rust-toolchain|RustDataCore|nekodata|System\.loadLibrary|\bexternal\s+fun\b' \
    "$root/scripts" -g '!verify-language-boundaries.sh' || true
} > "$configuration_hits"
if [ -s "$configuration_hits" ]; then
  echo "Unapproved native bridge or Rust tooling remains in executable source/configuration:" >&2
  cat "$configuration_hits" >&2
  exit 1
fi

actual_cgo="$temporary/actual-cgo.txt"
if [ -d "$root/libcore" ]; then
  # Transitional safeguard. The final branch below becomes active as soon as
  # the private bridge is deleted.
  expected_cgo="$temporary/expected-cgo.txt"
  sed -e '/^[[:space:]]*#/d' -e '/^[[:space:]]*$/d' \
    "$root/config/cgo-compat-allowlist.txt" | LC_ALL=C sort > "$expected_cgo"
  rg -l '(^|[[:space:]])import[[:space:]]+"C"|#cgo' "$root/libcore" \
    -g '*.go' -g '!**/.build/**' | sed "s#^$root/##" | LC_ALL=C sort > "$actual_cgo" || true
  if ! diff -u "$expected_cgo" "$actual_cgo"; then
    echo "cgo is limited to the reviewed Android system DNS compatibility bridge." >&2
    exit 1
  fi
else
  find "$root" \
    \( -path "$root/.git" -o -path "$root/.gradle" -o -path "$root/app/build" \
       -o -path "$root/build" -o -path "$root/design/audit" -o -path "$root/external" \) -prune \
    -o -type f -name '*.go' -print | sed "s#^$root/##" | LC_ALL=C sort > "$actual_cgo"
  if [ -s "$actual_cgo" ]; then
    echo "Product Go source is not allowed; use the official libbox AAR:" >&2
    cat "$actual_cgo" >&2
    exit 1
  fi
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
    ! -name 'libcore.aar' ! -name 'libcore.sources.sha256' \
    ! -name 'libbox.aar' ! -name 'libbox.version' -print > "$unexpected_libs"
  if [ -s "$unexpected_libs" ]; then
    echo "app/libs contains an unapproved dependency:" >&2
    cat "$unexpected_libs" >&2
    exit 1
  fi
fi

aar="$root/app/libs/libcore.aar"
if [ -f "$aar" ]; then
  marker="$root/app/libs/libcore.sources.sha256"
  [ -f "$marker" ] || {
    echo "libcore.aar has no source fingerprint; rebuild it with ./run lib core" >&2
    exit 1
  }
  expected_fingerprint=$("$root/scripts/libcore-source-fingerprint.sh")
  actual_fingerprint=$(tr -d '[:space:]' < "$marker")
  [ "$expected_fingerprint" = "$actual_fingerprint" ] || {
    echo "libcore.aar is stale; rebuild it with ./run lib core" >&2
    exit 1
  }
  expected_aar_native="$temporary/expected-aar-native.txt"
  actual_aar_native="$temporary/actual-aar-native.txt"
  printf '%s\n' \
    'jni/arm64-v8a/libgojni.so' > "$expected_aar_native"
  unzip -Z1 "$aar" | rg '\.(so|dylib|dll)$' | LC_ALL=C sort > "$actual_aar_native"
  if ! diff -u "$expected_aar_native" "$actual_aar_native"; then
    echo "libcore.aar native entries differ from the exact Go allowlist." >&2
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
  expected_aar_native="$temporary/expected-official-aar-native.txt"
  actual_aar_native="$temporary/actual-official-aar-native.txt"
  printf '%s\n' 'jni/arm64-v8a/libbox.so' > "$expected_aar_native"
  unzip -Z1 "$official_aar" | rg '\.(so|dylib|dll)$' | LC_ALL=C sort > "$actual_aar_native"
  if ! diff -u "$expected_aar_native" "$actual_aar_native"; then
    echo "libbox.aar native entries differ from the official AAR allowlist." >&2
    exit 1
  fi
fi

while IFS= read -r apk; do
  apk_native="$temporary/apk-native.txt"
  unzip -Z1 "$apk" | rg '\.(so|dylib|dll)$' | LC_ALL=C sort > "$apk_native"
  # The packaged runtime is determined by the declared AAR, not by whether
  # transitional source files still exist in the checkout.
  expected_apk_native='^lib/arm64-v8a/libgojni\.so$'
  if [ -f "$root/app/libs/libbox.aar" ]; then
    expected_apk_native='^lib/arm64-v8a/libbox\.so$'
  fi
  if [ ! -s "$apk_native" ] || rg -v "$expected_apk_native" "$apk_native" >/dev/null; then
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

echo "Language boundaries verified: Kotlin platform code and approved Go runtime only; no Java or Rust."
