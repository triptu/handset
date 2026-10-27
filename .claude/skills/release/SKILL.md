---
name: release
description: Cut a new release of the app, a GitHub Release with the signed APK. Use for /release, "ship it", "release a new version" or "put out a new APK".
---

# Release

A pushed tag `vX.Y.Z` makes `.github/workflows/release.yml` test, build and sign the APK and publish it as that tag's GitHub Release, under the stable name the README's download link uses. This skill cuts the tag and checks the result. `scripts/release.sh` does the mechanical parts; its header lists the commands.

1. Pick the version with `release.sh next`: a patch by default, minor or major when the user says so.
2. Write the release notes from `release.sh log`. A few short lines on what changed for someone using the app, in plain words, by the user's writing rules. Not commit titles, not internals. Leave out anything a user wouldn't notice. The workflow appends the APK's SHA-256 and certificate.
3. `release.sh cut <version> <notes-file>` bumps versionName, commits, tags with the notes as the tag message and pushes main and the tag.
4. `release.sh verify <version>` waits for the workflow, checks the Release's APK is signed with the expected key (`EXPECTED_CERT` in the workflow) and installs it on a connected phone with `adb install -r`. It's always an update: never uninstall, since that would wipe the user's data.
5. Report the Release link, the run link and the install result.

If a run fails, fix the cause, delete the tag locally and on origin, and cut it again. If a release comes out wrong, fix the workflow or this skill too.

The signing key lives outside the repo (see the build file's signing config) and in the repository's secrets as `*_KEYSTORE_BASE64` and `*_KEYSTORE_PASSWORD`. Never print or commit it. Losing it means users can't update without uninstalling.
