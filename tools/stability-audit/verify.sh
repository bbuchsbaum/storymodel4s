#!/usr/bin/env bash
set -euo pipefail

repo=$(cd "$(dirname "$0")/../.." && pwd)
evidence=${1:-"$repo/docs/refactor/evidence/stability-audit-20261014/semantic-inventory.json"}
bash "$repo/tools/stability-audit/run.sh" "$evidence"

jq -e '
  (.unresolved | length == 0) and
  (any(.inventory[]; startswith("storymodel4s.align.MappingRun.Execution ::"))) and
  (any(.inventory[]; startswith("storymodel4s.align.LocalReference.Result ::"))) and
  (any(.inventory[]; startswith("storymodel4s.align.StructuredReconstruction.Result ::"))) and
  (any(.inventory[]; startswith("storymodel4s.align.HsmmConfig ::"))) and
  (any(.inventory[]; startswith("storymodel4s.embed.onnx.OnnxMappingRegistration.Registered ::"))) and
  (all(.inventory[]; startswith("storymodel4s.align.GraphHsmm ::") | not)) and
  (all(.inventory[]; startswith("storymodel4s.pipeline.MappingPipeline.Organization ::") | not)) and
  ([.inventory[] | select(startswith("storymodel4s.pipeline.MappingPipeline.analyze ::"))] | length == 2)
' "$evidence" >/dev/null

# These source-level controls establish the public-copy and private-constructor boundaries that
# TASTy signatures report. They use the same immutable installed classpath as the extractor.
dist=${STABILITY_DIST:?Set STABILITY_DIST to the installed CLI distribution being audited}
runtime=$(awk -v base="$dist/lib" '{print base "/" $0}' "$dist/classpath.txt" | paste -sd: -)
cache="$HOME/Library/Caches/Coursier/v1"
one() { find "$cache" -type f -name "$1" -print -quit; }
compiler="$(one 'scala3-compiler_3-3.7.4.jar'):$(one 'scala3-interfaces-3.7.4.jar'):$(one 'scala3-library_3-3.7.4.jar'):$(one 'tasty-core_3-3.7.4.jar'):$(one 'scala-library-2.13.16.jar'):$(one 'scala-asm-9.8.0-scala-1.jar'):$(one 'compiler-interface-1.10.7.jar'):$(one 'jline-reader-3.29.0.jar'):$(one 'jline-terminal-3.29.0.jar'):$(one 'jline-terminal-jni-3.29.0.jar')"
java_opts=(-Xmx2g -XX:ActiveProcessorCount=2)
work=$(mktemp -d "${TMPDIR:-/tmp}/storymodel4s-stability-controls.XXXXXX")
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/private"
printf '%s\n' 'import storymodel4s.pipeline.MappingPipeline; val c = MappingPipeline.Config("mapping-lexical-pair/v1", 1.0, 1, 1, Vector(MappingPipeline.Capability.Mapping)); val copied = c.copy(temperature = 2.0)' > "$work/PublicCopy.scala"
java "${java_opts[@]}" -cp "$compiler" dotty.tools.dotc.Main -classpath "$runtime:$compiler" -d "$work/classes" "$work/PublicCopy.scala"
printf '%s\n' 'import storymodel4s.align.*; val forged = new LocalReference.Result(??? : LocalEvidence, ??? : LocalReference.Profile, Vector.empty)' > "$work/PrivateConstructor.scala"
if java "${java_opts[@]}" -cp "$compiler" dotty.tools.dotc.Main -classpath "$runtime:$compiler" -d "$work/private" "$work/PrivateConstructor.scala" >"$work/private.log" 2>&1; then
  echo "private LocalReference.Result constructor compiled" >&2
  exit 1
fi
mkdir -p "$work/fixtures"
java "${java_opts[@]}" -cp "$compiler" dotty.tools.dotc.Main -classpath "$runtime:$compiler" -d "$work/fixtures" "$repo/tools/stability-audit/test/SignatureFixtures.scala"
STABILITY_EXTRA_TASTY="$work/fixtures" \
  STABILITY_ROOTS="$repo/tools/stability-audit/test/fixture-roots.txt" \
  bash "$repo/tools/stability-audit/run.sh" "$work/fixtures.json"
jq -e '
  (.unresolved | length == 0) and
  ([.inventory[] | select(startswith("storymodel4s.stabilityfixture.FixtureFacade.overload ::"))] | length == 2) and
  (any(.inventory[]; startswith("storymodel4s.stabilityfixture.ParameterOnly ::"))) and
  (any(.inventory[]; startswith("storymodel4s.stabilityfixture.Lower ::"))) and
  (any(.inventory[]; startswith("storymodel4s.stabilityfixture.Upper ::"))) and
  (any(.inventory[]; startswith("storymodel4s.stabilityfixture.SignatureFixtures$package.OpaqueOnly ::"))) and
  (any(.inventory[]; startswith("storymodel4s.core.OpaqueId.T ::"))) and
  (any(.inventory[]; startswith("storymodel4s.stabilityfixture.ConstructorParameterOnly ::"))) and
  (any(.inventory[]; startswith("storymodel4s.stabilityfixture.ParentOnly ::"))) and
  (any(.inventory[]; startswith("storymodel4s.stabilityfixture.FactoryParameterOnly ::"))) and
  (any(.inventory[]; startswith("storymodel4s.stabilityfixture.BuildParameterOnly ::"))) and
  (any(.inventory[]; startswith("storymodel4s.stabilityfixture.WrappedFactoryParameterOnly ::"))) and
  (any(.inventory[]; startswith("storymodel4s.stabilityfixture.AliasParameterOnly ::"))) and
  (all(.inventory[]; startswith("storymodel4s.stabilityfixture.HiddenParameterOnly ::") | not)) and
  (any(.inventory[]; startswith("storymodel4s.stabilityfixture.PublicEnum.Rich ::"))) and
  (any(.inventory[]; startswith("storymodel4s.stabilityfixture.PublicEnum.Plain ::"))) and
  (any(.inventory[]; startswith("storymodel4s.stabilityfixture.Good ::"))) and
  (any(.inventory[]; startswith("storymodel4s.stabilityfixture.Empty ::"))) and
  (any(.inventory[]; startswith("storymodel4s.stabilityfixture.EnumPayloadOnly ::"))) and
  (any(.inventory[]; startswith("storymodel4s.stabilityfixture.SealedPayloadOnly ::"))) and
  (all(.inventory[]; startswith("storymodel4s.stabilityfixture.UnrelatedAlternativePayload ::") | not)) and
  (any(.edges[]; .kind == "public-alternative")) and
  (any(.edges[]; .kind | contains("parameter"))) and
  (any(.edges[]; .kind | contains("upper-bound"))) and
  (any(.edges[]; .kind | contains("lower-bound"))) and
  (any(.edges[]; .kind == "constructor:parameter:nominal")) and
  (any(.edges[]; .kind == "parent-term:nominal")) and
  (any(.edges[]; .kind == "companion-factory:parameter:nominal")) and
  ([.edges[] | select(.kind == "companion-factory" and .detail == "build")] | length == 1) and
  ([.edges[] | select(.kind == "companion-factory" and .detail == "wrapped")] | length == 1) and
  ([.edges[] | select(.kind == "companion-factory" and .detail == "aliased")] | length == 1)
' "$work/fixtures.json" >/dev/null
