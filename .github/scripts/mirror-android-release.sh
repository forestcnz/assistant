#!/usr/bin/env bash
set -euo pipefail

tag=$1
repository=$(sed -n 's/^appUpdateGiteeRepository=//p' gradle.properties)
if [[ -z "$repository" || -z "${GITEE_ACCESS_TOKEN:-}" ]]; then
  echo '::notice::Gitee mirror skipped. Configure the GITEE_ACCESS_TOKEN repository secret to enable publishing.'
  exit 0
fi

temp=$(mktemp -d)
trap 'rm -rf "$temp"' EXIT
apk="assistant-${tag}-release.apk"
gh release download "$tag" --pattern update.json --pattern "$apk" --dir "$temp"
expected_hash=$(jq -er '.sha256' "$temp/update.json")
actual_hash=$(sha256sum "$temp/$apk" | cut -d' ' -f1)
[[ "$actual_hash" == "$expected_hash" ]] || { echo 'GitHub APK and update manifest disagree'; exit 1; }

base="https://gitee.com/${repository}/releases"
jq --arg url "$base/download/$tag/$apk" '.apkUrl = $url' "$temp/update.json" > "$temp/gitee-update.json"

curl_options=(--silent --show-error --connect-timeout 30 --max-time 240)
read_options=("${curl_options[@]}" --retry 3 --retry-connrefused --retry-max-time 180)

status=000
for host in api.gitee.com gitee.com; do
  repo_api="https://$host/api/v5/repos/$repository"
  api="$repo_api/releases"
  echo "Looking up Gitee Release $tag via $host"
  if status=$(curl "${curl_options[@]}" --connect-timeout 15 --max-time 30 --retry 1 \
    -o "$temp/release.json" -w '%{http_code}' "$api/tags/$tag") && [[ "$status" == 200 || "$status" == 404 ]]; then
    break
  fi
  echo "Gitee lookup via $host failed (HTTP $status)"
done
if [[ "$status" == 404 ]] || { [[ "$status" == 200 ]] && jq -e 'type == "null"' "$temp/release.json" > /dev/null; }; then
  echo 'Looking up the Gitee default branch'
  curl "${read_options[@]}" --max-time 45 --fail -o "$temp/repo.json" "$repo_api"
  target_branch=$(jq -er '.default_branch | strings | select(length > 0)' "$temp/repo.json")
  # 附件校验完成后再转为正式版。
  echo "Creating Gitee prerelease $tag"
  curl "${curl_options[@]}" --fail -o "$temp/release.json" \
    --data-urlencode "access_token=$GITEE_ACCESS_TOKEN" \
    --data-urlencode "tag_name=$tag" --data-urlencode "target_commitish=$target_branch" \
    --data-urlencode "name=雕雕助手 $tag" --data-urlencode 'prerelease=true' \
    --data-urlencode "body=$(jq -r '.notes' "$temp/update.json")" "$api"
elif [[ "$status" != 200 ]]; then
  echo "Unable to look up Gitee release ($status)"
  exit 1
fi

release_id=$(jq -er '.id | numbers' "$temp/release.json")
attachments="$api/$release_id/attach_files"
echo 'Listing Gitee Release attachments'
curl "${read_options[@]}" --max-time 45 --fail -o "$temp/assets.json" "$attachments"
for name in "$apk" update.json; do
  file="$temp/$name"
  if [[ "$name" == update.json ]]; then file="$temp/gitee-update.json"; fi
  if ! jq -e --arg name "$name" 'any(.[]; .name == $name)' "$temp/assets.json" > /dev/null; then
    echo "Uploading $name to Gitee"
    curl "${curl_options[@]}" --max-time 7200 --speed-limit 1024 --speed-time 180 --fail -o /dev/null \
      --write-out 'Gitee upload returned HTTP %{http_code} after %{time_total}s (%{size_upload} bytes sent)\n' \
      --form-string "access_token=$GITEE_ACCESS_TOKEN" --form "file=@$file;filename=$name" \
      "$attachments"
    curl "${read_options[@]}" --max-time 45 --fail -o "$temp/assets.json" "$attachments"
  fi
  attachment_id=$(jq -er --arg name "$name" '.[] | select(.name == $name) | .id | numbers' "$temp/assets.json")
  echo "Verifying Gitee attachment $name"
  curl "${read_options[@]}" --fail --location -o "$temp/downloaded" "$attachments/$attachment_id/download"
  if ! cmp -s "$file" "$temp/downloaded"; then
    echo "Gitee attachment $name differs from the release artifact"
    exit 1
  fi
done

if jq -e '.prerelease' "$temp/release.json" > /dev/null; then
  echo "Publishing Gitee Release $tag"
  curl "${curl_options[@]}" --fail -o /dev/null -X PATCH \
    --data-urlencode "access_token=$GITEE_ACCESS_TOKEN" --data-urlencode 'prerelease=false' \
    --data-urlencode "tag_name=$tag" \
    --data-urlencode "name=雕雕助手 $tag" \
    --data-urlencode "body=$(jq -r '.notes' "$temp/update.json")" "$api/$release_id"
fi
