#!/usr/bin/env bash
# Run one configuration of the recall-to-video mapping over one partition of participants.
#
# Usage: run-arm.sh ARM_LABEL PARTITION   with PARTITION in {development, untouchedTest}
# Environment overrides passed through to the runner (each changes the report identity):
#   STORYMODEL4S_CANDIDATES_PER_LEVEL, STORYMODEL4S_CANDIDATES_LEXICAL_OVERLAP
#
# Outputs land in tmp/study/<ARM_LABEL>/ and are scored by tools/recall-study/score.py.
# Inputs stay outside Git. The untouched partition is unsealed once; running it is a deliberate act.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$here/../.."
arm="${1:?arm label}"
partition="${2:?development or untouchedTest}"

out="$root/tmp/study/$arm"
mkdir -p "$out"
names=$(python3 -c "
import json,sys
p=json.load(open('$root/tmp/study/partition.json'))
print('\n'.join(p['$partition']))")

export ORT_DISABLE_TELEMETRY=1
export STORYMODEL4S_ONNX_MODEL="${STORYMODEL4S_ONNX_MODEL:-$root/tmp/onnx/model.onnx}"
export STORYMODEL4S_ONNX_TOKENIZER="${STORYMODEL4S_ONNX_TOKENIZER:-$root/tmp/onnx/tokenizer.json}"
annotation="$root/tmp/Sherlock_Segments_1000_NN_2017.tsv"

echo "arm=$arm partition=$partition perLevel=${STORYMODEL4S_CANDIDATES_PER_LEVEL:-8} lexicalOverlap=${STORYMODEL4S_CANDIDATES_LEXICAL_OVERLAP:-false}"
for n in $names; do
  recall="$root/tmp/recall_zenodo/$n.csv"
  report="$out/recall-map-$n.tsv"
  if [ -f "$report" ]; then echo "  $n: present, skipping"; continue; fi
  echo "  $n ..."
  ( cd "$root" && sbt -batch "embedBench/runMain storymodel4s.bench.sherlock.sherlockRecallMap $annotation $recall $report" ) \
    > "$out/$n.log" 2>&1 || { echo "  $n FAILED, see $out/$n.log"; exit 1; }
done
echo "done: $(ls "$out"/recall-map-*.tsv 2>/dev/null | wc -l | tr -d ' ') reports in $out"
