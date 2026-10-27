#!/usr/bin/env bash
# The mechanical half of /release. See SKILL.md beside this folder.
#   release.sh next [patch|minor|major]   the next version, from the last tag
#   release.sh log                        commits since the last tag, to write the notes from
#   release.sh cut <version> <notes-file> bump versionName, commit, tag with the notes, push
#   release.sh verify <version>           wait for the workflow, check the Release's APK, install it on the phone
set -euo pipefail

GITHUB_REPO=triptu/handset
GRADLE_FILE=android/app/build.gradle.kts
APK=handset.apk
WORKFLOW=.github/workflows/release.yml

cd "$(git rev-parse --show-toplevel)"
last_tag() { git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true; }
version_name() { sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$GRADLE_FILE"; }

case "${1:-}" in
next)
  current=$(last_tag); current=${current#v}; current=${current:-$(version_name)}
  IFS=. read -r major minor patch <<<"$current"
  case "${2:-patch}" in
    major) echo "$((major + 1)).0.0" ;;
    minor) echo "$major.$((minor + 1)).0" ;;
    patch) echo "$major.$minor.$((patch + 1))" ;;
    *) echo "next takes patch, minor or major" >&2; exit 1 ;;
  esac
  ;;
log)
  tag=$(last_tag)
  git log --no-merges --format='%h %s%n%b' ${tag:+"$tag"..HEAD}
  ;;
cut)
  version=${2:?version}; notes=${3:?notes file}
  [[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "version looks like 1.2.3" >&2; exit 1; }
  [ "$(git branch --show-current)" = main ] || { echo "release from main" >&2; exit 1; }
  git diff --quiet HEAD -- "$GRADLE_FILE" || { echo "$GRADLE_FILE has uncommitted changes" >&2; exit 1; }
  git rev-parse -q --verify "refs/tags/v$version" >/dev/null && { echo "v$version exists" >&2; exit 1; }
  git fetch -q origin main
  git merge-base --is-ancestor origin/main HEAD || { echo "main is behind origin/main" >&2; exit 1; }
  if [ "$(version_name)" != "$version" ]; then
    sed -i.bak "s/versionName = \".*\"/versionName = \"$version\"/" "$GRADLE_FILE" && rm "$GRADLE_FILE.bak"
    git commit -q -m "Version $version" -- "$GRADLE_FILE"
  fi
  git tag -a "v$version" -F "$notes"
  git push -q origin main "v$version"
  echo "Pushed main and v$version"
  ;;
verify)
  version=${2:?version}; tag=v$version
  run=""
  for _ in $(seq 60); do
    run=$(gh run list -R "$GITHUB_REPO" --workflow release.yml --branch "$tag" --json databaseId --jq '.[0].databaseId // empty')
    [ -n "$run" ] && break; sleep 5
  done
  [ -n "$run" ] || { echo "No workflow run for $tag" >&2; exit 1; }
  echo "Run: https://github.com/$GITHUB_REPO/actions/runs/$run"
  gh run watch "$run" -R "$GITHUB_REPO" --exit-status >/dev/null || { echo "The workflow failed" >&2; exit 1; }

  dir=$(mktemp -d)
  gh release download "$tag" -R "$GITHUB_REPO" --pattern "$APK" --dir "$dir"
  sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
  apksigner="$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)/apksigner"
  cert=$("$apksigner" verify --print-certs "$dir/$APK" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1 \
    | tr a-f A-F | sed 's/../&:/g; s/:$//')
  expected=$(sed -n 's/.*EXPECTED_CERT: *//p' "$WORKFLOW")
  [ "$cert" = "$expected" ] || { echo "The APK is signed with $cert, expected $expected" >&2; exit 1; }
  echo "Certificate: $cert"
  echo "Release: https://github.com/$GITHUB_REPO/releases/tag/$tag"

  # The phone: ANDROID_SERIAL when set, else the first device that isn't an emulator. An update only, never an uninstall.
  phone=${ANDROID_SERIAL:-$(adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ { print $1; exit }')}
  if [ -n "$phone" ]; then
    adb -s "$phone" install -r "$dir/$APK" && echo "Installed on $phone"
  else
    echo "No phone connected; skipped the install"
  fi
  ;;
*)
  sed -n '2,6p' "$0"; exit 1 ;;
esac
