#!/usr/bin/env bash
set -euo pipefail

manifest=dist/update.json
tag=$1
apksigner=$2
[[ -f "$manifest" ]] || exit 0
previous=$(gh release list --exclude-drafts --exclude-pre-releases --limit 100 --json isLatest,tagName \
  --jq '.[] | select(.isLatest) | .tagName')
if [[ ! "$previous" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ || "$previous" == "$tag" ]]; then exit 0; fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
old_name="assistant-$previous-release.apk"
gh release download "$previous" --pattern update.json --pattern "$old_name" --dir "$work"
old="$work/$old_name"
new="dist/assistant-$tag-release.apk"
expected_cert=$(sed -n 's/^appReleaseCertificateSha256=//p' gradle.properties)
report=$("$apksigner" verify --print-certs "$old")
cert=$(sed -nE 's/^.*certificate SHA-256 digest: ([0-9a-f]{64})[[:space:]]*$/\1/p' <<< "$report" | sort -u)
[[ "$cert" == "$expected_cert" && "$cert" =~ ^[0-9a-f]{64}$ ]]

base_code=$(jq -er '.versionCode | numbers | select(. > 0 and floor == .)' "$work/update.json")
target_code=$(jq -er '.versionCode | numbers | select(. > 0 and floor == .)' "$manifest")
if (( base_code >= target_code )); then exit 0; fi
old_hash=$(sha256sum "$old" | cut -d' ' -f1)
[[ "$old_hash" == "$(jq -er '.sha256' "$work/update.json")" ]]

tool_archive=hdiffpatch_v5.1.3_bin_linux64.zip
curl --fail --silent --show-error --location --retry 2 --connect-timeout 15 --max-time 120 \
  -o "$work/$tool_archive" "https://github.com/sisong/HDiffPatch/releases/download/v5.1.3/$tool_archive"
printf '%s  %s\n' '628963bf2ee9108a97260fa5eef44acd9ec94369b76090a957c9182b3abbb558' "$work/$tool_archive" | sha256sum --check
unzip -q "$work/$tool_archive" -d "$work"
chmod +x "$work/linux64/hdiffz" "$work/linux64/hpatchz"

patch_name="assistant-$base_code-to-$target_code.hdiff"
patch="$work/$patch_name"
"$work/linux64/hdiffz" -s-64 -SD -c-zstd-20-23 "$old" "$new" "$patch"
patch_size=$(stat -c '%s' "$patch")
full_size=$(stat -c '%s' "$new")
if (( patch_size == 0 || patch_size * 2 >= full_size )); then
  echo "Patch is $patch_size bytes for a $full_size byte APK. Publishing the complete APK."
  exit 0
fi
# The Android client accepts only the single-stream zstd format with a bounded step buffer.
python3 - "$patch" "$old" "$new" <<'PY'
from pathlib import Path
import sys

with open(sys.argv[1], "rb") as stream:
    assert stream.read(15) == b"HDIFFSF20&zstd\x00"

    def read_size():
        size = 0
        for _ in range(9):
            byte = stream.read(1)[0]
            size = (size << 7) | (byte & 0x7f)
            if byte < 0x80:
                return size
        raise ValueError("Invalid patch size field")

    assert read_size() == Path(sys.argv[3]).stat().st_size
    assert read_size() == Path(sys.argv[2]).stat().st_size
    read_size()  # cover count
    assert read_size() <= 4 * 1024 * 1024
PY
"$work/linux64/hpatchz" "$old" "$patch" "$work/rebuilt.apk"
cmp "$new" "$work/rebuilt.apk"
patch_hash=$(sha256sum "$patch" | cut -d' ' -f1)
patch_url="https://github.com/$GITHUB_REPOSITORY/releases/download/$tag/$patch_name"
jq --arg format 'hdiff-sd-zstd-v1' --argjson base "$base_code" --arg oldHash "$old_hash" \
  --argjson size "$patch_size" --arg hash "$patch_hash" --arg url "$patch_url" \
  '.patches = [{format: $format, fromVersionCode: $base, fromSha256: $oldHash, sizeBytes: $size, sha256: $hash, url: $url}]' \
  "$manifest" > "$work/update.json.new"
mv "$patch" "dist/$patch_name"
mv "$work/update.json.new" "$manifest"
echo "Verified patch $patch_name ($patch_size of $full_size bytes)."
