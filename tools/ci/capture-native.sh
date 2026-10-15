#!/usr/bin/env bash
set -euo pipefail

test "${GITHUB_ACTIONS:-}" = true
test "${RUNNER_OS:-}" = Linux
evidence=target/native-ci-evidence
mkdir -p "$evidence"
git rev-parse HEAD > "$evidence/source-sha.txt"
git diff --exit-code HEAD
git ls-files -z | xargs -0 sha256sum > "$evidence/source-files.sha256"
git status --short > "$evidence/source-status.txt"
find align/.native/target -type f -name storymodel4s-align-test -print > "$evidence/executables.txt"
test "$(wc -l < "$evidence/executables.txt")" -eq 1
find align/.native/target -type f \( -name '*.ll' -o -name '*.nir' -o -name '*-test' -o -name '*.out' \) -print0 > "$evidence/native-files.list"
find align/.native/target -type f -perm /111 -print0 >> "$evidence/native-files.list"
sort -zu "$evidence/native-files.list" -o "$evidence/native-files.list"
test -s "$evidence/native-files.list"
xargs -0 sha256sum < "$evidence/native-files.list" > "$evidence/native-files.sha256"
tar --null -T "$evidence/native-files.list" -czf "$evidence/align-before-test.tar.gz"
