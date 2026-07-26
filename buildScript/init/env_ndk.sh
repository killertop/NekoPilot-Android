#!/bin/bash

if [ -n "${ZSH_VERSION:-}" ]; then
  _env_ndk_script_path="${(%):-%N}"
else
  _env_ndk_script_path=${BASH_SOURCE[0]:-$0}
fi
_env_ndk_root=$(cd -P "$(dirname "$_env_ndk_script_path")/../.." && pwd) || {
  echo "Error: unable to resolve repository root for env_ndk.sh." >&2
  return 1 2>/dev/null || exit 1
}

if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  elif [ -d "$HOME/.local/lib/android/sdk" ]; then
    export ANDROID_HOME="$HOME/.local/lib/android/sdk"
  elif [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  fi
fi

_env_ndk_version_file="$_env_ndk_root/.android-ndk-version"
if [ ! -f "$_env_ndk_version_file" ]; then
  echo "Error: missing Android NDK version file: $_env_ndk_version_file" >&2
  return 1 2>/dev/null || exit 1
fi
_env_ndk_version=$(cat "$_env_ndk_version_file")
_env_ndk_line_count=$(LC_ALL=C wc -l < "$_env_ndk_version_file" | tr -d '[:space:]')
if [ "$_env_ndk_line_count" != 1 ] ||
  [[ ! "$_env_ndk_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Error: .android-ndk-version must contain exactly one numeric major.minor.patch line." >&2
  return 1 2>/dev/null || exit 1
fi
if [ "${NEKOPILOT_NDK_VERSION+x}" = x ] &&
  [ "$NEKOPILOT_NDK_VERSION" != "$_env_ndk_version" ]; then
  echo "Error: NEKOPILOT_NDK_VERSION must match repository pin $_env_ndk_version." >&2
  return 1 2>/dev/null || exit 1
fi

_NDK=""
for _env_ndk_candidate in \
  "${ANDROID_HOME:-}/ndk/$_env_ndk_version" \
  "${ANDROID_SDK_ROOT:-}/ndk/$_env_ndk_version" \
  "${ANDROID_NDK_HOME:-}" \
  "${NDK:-}" \
  "${ANDROID_HOME:-}/ndk-bundle"; do
  [ -f "$_env_ndk_candidate/source.properties" ] || continue
  _env_ndk_actual_version=$(awk '/^[[:space:]]*Pkg[.]Revision[[:space:]]*=/ {
    line = $0
    sub(/^[^=]*=[[:space:]]*/, "", line)
    print line
    exit
  }' "$_env_ndk_candidate/source.properties" 2>/dev/null || true)
  if [ "$_env_ndk_actual_version" = "$_env_ndk_version" ]; then
    _NDK="$_env_ndk_candidate"
    break
  fi
done

if [ -z "$_NDK" ]; then
  echo "Error: Android NDK $_env_ndk_version not found or does not match the repository pin." >&2
  return 1 2>/dev/null || exit 1
fi

export ANDROID_NDK_HOME=$_NDK
export NDK=$_NDK
