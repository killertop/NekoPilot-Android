#!/usr/bin/env bash
set -euo pipefail

# Keep Android version names in the product format major.minor.patch.
# patch is intentionally bounded to 0..10; patch bumps roll into minor.
root_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
version_file="${VERSION_FILE:-$root_dir/nb4a.properties}"
mode="${1:-patch}"

if [[ ! -f "$version_file" ]]; then
  echo "Version file not found: $version_file" >&2
  exit 1
fi

version="$(sed -n 's/^VERSION_NAME=//p' "$version_file" | head -n 1 | tr -d '\r')"
if [[ ! "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "VERSION_NAME must use major.minor.patch: $version" >&2
  exit 1
fi

major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
patch="${BASH_REMATCH[3]}"
if (( patch > 10 )); then
  echo "VERSION_NAME patch component must be between 0 and 10: $version" >&2
  exit 1
fi

case "$mode" in
  patch)
    if (( patch >= 10 )); then
      minor=$((minor + 1))
      patch=0
    else
      patch=$((patch + 1))
    fi
    ;;
  minor)
    minor=$((minor + 1))
    patch=0
    ;;
  major)
    major=$((major + 1))
    minor=0
    patch=0
    ;;
  *)
    echo "Usage: $0 [patch|minor|major]" >&2
    exit 2
    ;;
esac

new_version="$major.$minor.$patch"
tmp_file="$(mktemp "${version_file}.XXXXXX")"
trap 'rm -f "$tmp_file"' EXIT
sed "s/^VERSION_NAME=.*/VERSION_NAME=$new_version/" "$version_file" > "$tmp_file"
mv "$tmp_file" "$version_file"
trap - EXIT

echo "$version -> $new_version"
