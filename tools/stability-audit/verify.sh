#!/usr/bin/env bash
set -euo pipefail

repo=$(cd "$(dirname "$0")/../.." && pwd)
evidence="$repo/docs/refactor/evidence/stability-audit-20261014/semantic-inventory.json"
bash "$repo/tools/stability-audit/run.sh" "$evidence"

jq -e '
  (.unresolved | length == 0) and
  (([.inventory[]] | index("storymodel4s.align.MappingRun.Execution")) != null) and
  (([.inventory[]] | index("storymodel4s.align.LocalReference.Result")) != null) and
  (([.inventory[]] | index("storymodel4s.align.StructuredReconstruction.Result")) != null) and
  (([.inventory[]] | index("storymodel4s.align.HsmmConfig")) != null) and
  (([.inventory[]] | index("storymodel4s.embed.onnx.OnnxMappingRegistration.Registered")) != null) and
  (([.inventory[]] | index("storymodel4s.align.GraphHsmm")) | not) and
  (([.inventory[]] | index("storymodel4s.pipeline.MappingPipeline.Organization")) | not)
' "$evidence" >/dev/null

# These source-level controls establish the public-copy and private-constructor boundaries that
# TASTy signatures report. They use the same immutable installed classpath as the extractor.
dist="$repo/.agent-work/release-workflow-20261014/distribution/installed/storymodel4s-0.1-2c4b74f-SNAPSHOT"
runtime=$(awk -v base="$dist/lib" '{print base "/" $0}' "$dist/classpath.txt" | paste -sd: -)
cache="$HOME/Library/Caches/Coursier/v1"
one() { find "$cache" -type f -name "$1" -print -quit; }
compiler="$(one 'scala3-compiler_3-3.7.4.jar'):$(one 'scala3-interfaces-3.7.4.jar'):$(one 'scala3-library_3-3.7.4.jar'):$(one 'tasty-core_3-3.7.4.jar'):$(one 'scala-library-2.13.16.jar'):$(one 'scala-asm-9.8.0-scala-1.jar'):$(one 'compiler-interface-1.10.7.jar'):$(one 'jline-reader-3.29.0.jar'):$(one 'jline-terminal-3.29.0.jar'):$(one 'jline-terminal-jni-3.29.0.jar')"
work=$(mktemp -d "${TMPDIR:-/tmp}/storymodel4s-stability-controls.XXXXXX")
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/private"
printf '%s\n' 'import storymodel4s.pipeline.MappingPipeline; val c = MappingPipeline.Config("mapping-lexical-pair/v1", 1.0, 1, 1, Vector(MappingPipeline.Capability.Mapping)); val copied = c.copy(temperature = 2.0)' > "$work/PublicCopy.scala"
java -cp "$compiler" dotty.tools.dotc.Main -classpath "$runtime:$compiler" -d "$work/classes" "$work/PublicCopy.scala"
printf '%s\n' 'import storymodel4s.align.*; val forged = new LocalReference.Result(??? : LocalEvidence, ??? : LocalReference.Profile, Vector.empty)' > "$work/PrivateConstructor.scala"
if java -cp "$compiler" dotty.tools.dotc.Main -classpath "$runtime:$compiler" -d "$work/private" "$work/PrivateConstructor.scala" >"$work/private.log" 2>&1; then
  echo "private LocalReference.Result constructor compiled" >&2
  exit 1
fi
