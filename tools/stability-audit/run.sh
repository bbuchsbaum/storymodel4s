#!/usr/bin/env bash
set -euo pipefail

# Runs against the immutable installed distribution named by the release workflow.
repo=$(cd "$(dirname "$0")/../.." && pwd)
dist="$repo/.agent-work/release-workflow-20261014/distribution/installed/storymodel4s-0.1-2c4b74f-SNAPSHOT"
out=${1:-"$repo/docs/refactor/evidence/stability-audit-20261014/semantic-inventory.json"}
roots=${STABILITY_ROOTS:-"$repo/tools/stability-audit/roots.txt"}
work=$(mktemp -d "${TMPDIR:-/tmp}/storymodel4s-stability-audit.XXXXXX")
trap 'rm -rf "$work"' EXIT
mkdir -p "$(dirname "$out")" "$work/tasty" "$work/classes"

runtime=$(awk -v base="$dist/lib" '{print base "/" $0}' "$dist/classpath.txt" | paste -sd: -)
cache="$HOME/Library/Caches/Coursier/v1"
one() { find "$cache" -type f -name "$1" -print -quit; }
compiler="$(one 'scala3-compiler_3-3.7.4.jar'):$(one 'scala3-tasty-inspector_3-3.7.4.jar'):$(one 'scala3-interfaces-3.7.4.jar'):$(one 'scala3-library_3-3.7.4.jar'):$(one 'tasty-core_3-3.7.4.jar'):$(one 'scala-library-2.13.16.jar'):$(one 'scala-asm-9.8.0-scala-1.jar'):$(one 'compiler-interface-1.10.7.jar'):$(one 'jline-reader-3.29.0.jar'):$(one 'jline-terminal-3.29.0.jar'):$(one 'jline-terminal-jni-3.29.0.jar')"
java_opts=(-Xmx2g -XX:ActiveProcessorCount=2)
# `jar` has no portable extraction-to option. Extract only project TASTy entries inside the
# ignored temporary directory, avoiding the third-party runtime jars in the installed classpath.
for jarfile in "$dist"/lib/*.jar; do
  entries=()
  while IFS= read -r entry; do entries+=("$entry"); done < <(jar tf "$jarfile" | grep '^storymodel4s/.*\.tasty$' || true)
  if test "${#entries[@]}" -gt 0; then (cd "$work/tasty" && jar xf "$jarfile" "${entries[@]}"); fi
done
tasty=()
while IFS= read -r entry; do tasty+=("$entry"); done < <(find "$work/tasty" -name '*.tasty' -print | sort)
if test -n "${STABILITY_EXTRA_TASTY:-}"; then
  while IFS= read -r entry; do tasty+=("$entry"); done < <(find "$STABILITY_EXTRA_TASTY" -name '*.tasty' -print | sort)
fi
test "${#tasty[@]}" -gt 0
java "${java_opts[@]}" -cp "$compiler" dotty.tools.dotc.Main -experimental -classpath "$runtime:$compiler" -d "$work/classes" "$repo/tools/stability-audit/src/StableApiAudit.scala"
java "${java_opts[@]}" -cp "$work/classes:$runtime:$compiler" storymodel4s.stabilityaudit.StableApiAudit "$roots" "$out" "${tasty[@]}"
