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

remote="https://gitee.com/${repository}.git"
base="https://gitee.com/${repository}/releases"
repo_api="https://gitee.com/api/v5/repos/${repository}"
api="$repo_api/releases"
jq --arg url "$base/download/$tag/$apk" '.apkUrl = $url' "$temp/update.json" > "$temp/gitee-update.json"

curl_options=(--silent --show-error --connect-timeout 10 --max-time 240)
status=$(curl "${curl_options[@]}" -o "$temp/release.json" -w '%{http_code}' "$api/tags/$tag")
if [[ "$status" == 404 ]] || { [[ "$status" == 200 ]] && jq -e 'type == "null"' "$temp/release.json" > /dev/null; }; then
  target_branch=$(curl "${curl_options[@]}" --fail "$repo_api" | jq -er '.default_branch | strings | select(length > 0)')
  tag_ref="refs/tags/$tag"
  source_tag=$(git rev-parse "$tag_ref")
  mirror_tag=$(git ls-remote --refs "$remote" "$tag_ref" | cut -f1)
  if [[ "$mirror_tag" == "$source_tag" ]]; then
    # 修复早期镜像推送的 GitHub 标签。只删除无 Release 且对象完全相同的标签。
    export GITEE_USERNAME="${GITEE_USERNAME:-${repository%%/*}}"
    export GIT_TERMINAL_PROMPT=0
    export GIT_ASKPASS="$temp/gitee-askpass"
    cat > "$GIT_ASKPASS" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  *Username*) printf '%s\n' "$GITEE_USERNAME" ;;
  *Password*) printf '%s\n' "$GITEE_ACCESS_TOKEN" ;;
esac
EOF
    chmod 700 "$GIT_ASKPASS"
    git -c credential.helper= push --force-with-lease="$tag_ref:$mirror_tag" "$remote" ":$tag_ref"
  fi

  # 附件校验完成后再转为正式版。
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
curl "${curl_options[@]}" --fail -o "$temp/assets.json" "$api/$release_id/attach_files"
for name in "$apk" update.json; do
  file="$temp/$name"
  if [[ "$name" == update.json ]]; then file="$temp/gitee-update.json"; fi
  if ! jq -e --arg name "$name" 'any(.[]; .name == $name)' "$temp/assets.json" > /dev/null; then
    echo "Uploading $name to Gitee"
    curl "${curl_options[@]}" --max-time 900 --fail -o /dev/null \
      --write-out 'Gitee upload returned HTTP %{http_code} after %{time_total}s (%{size_upload} bytes sent)\n' \
      --form-string "access_token=$GITEE_ACCESS_TOKEN" --form "file=@$file;filename=$name" \
      "$api/$release_id/attach_files"
  fi
  curl "${curl_options[@]}" --retry 2 --fail --location -o "$temp/downloaded" "$base/download/$tag/$name"
  if ! cmp -s "$file" "$temp/downloaded"; then
    echo "Gitee attachment $name differs from the release artifact"
    exit 1
  fi
done

if jq -e '.prerelease' "$temp/release.json" > /dev/null; then
  curl "${curl_options[@]}" --fail -o /dev/null -X PATCH \
    --data-urlencode "access_token=$GITEE_ACCESS_TOKEN" --data-urlencode 'prerelease=false' \
    --data-urlencode "tag_name=$tag" \
    --data-urlencode "name=雕雕助手 $tag" \
    --data-urlencode "body=$(jq -r '.notes' "$temp/update.json")" "$api/$release_id"
fi
