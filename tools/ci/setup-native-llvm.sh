#!/usr/bin/env bash
set -euo pipefail

# This changes only an ephemeral GitHub-hosted runner. Refuse accidental local installation.
test "${GITHUB_ACTIONS:-}" = true
test "${RUNNER_OS:-}" = Linux
test "$(lsb_release -cs)" = jammy
evidence=target/native-ci-evidence
mkdir -p "$evidence"
curl --fail --silent --show-error --location \
  https://apt.llvm.org/llvm-snapshot.gpg.key --output "$evidence/llvm.key"
gpg --show-keys --with-colons "$evidence/llvm.key" > "$evidence/key-details.txt"
test "$(awk -F: '$1 == "fpr" {print $10; exit}' "$evidence/key-details.txt")" = \
  6084F3CF814B57C1CF12EFD515CF4D18AF4F7421
gpg --dearmor --output "$evidence/llvm.gpg" "$evidence/llvm.key"
sudo install -m 644 "$evidence/llvm.gpg" /usr/share/keyrings/llvm-storymodel.gpg
echo 'deb [signed-by=/usr/share/keyrings/llvm-storymodel.gpg] https://apt.llvm.org/jammy/ llvm-toolchain-jammy-18 main' > "$evidence/llvm.list"
sudo install -m 644 "$evidence/llvm.list" /etc/apt/sources.list.d/llvm-storymodel.list
sudo apt-get update > "$evidence/apt-update.log" 2>&1
sudo apt-get install -y clang-18 lld-18 > "$evidence/apt-install.log" 2>&1
echo 'LLVM_BIN=/usr/lib/llvm-18/bin' >> "$GITHUB_ENV"
/usr/lib/llvm-18/bin/clang --version > "$evidence/clang-version.txt"
/usr/lib/llvm-18/bin/clang++ --version > "$evidence/clangpp-version.txt"
dpkg-query -W 'clang-18' 'llvm-18*' 'libllvm18*' 'lld-18' > "$evidence/packages.txt"
java -version > "$evidence/java-version.txt" 2>&1
uname -a > "$evidence/uname.txt"
cat "$evidence/clang-version.txt"
