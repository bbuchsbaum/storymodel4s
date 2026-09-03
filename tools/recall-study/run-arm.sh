#!/usr/bin/env bash
# Run one configuration of the recall-to-video mapping over one partition of participants.
#
# Usage: run-arm.sh ARM_LABEL PARTITION   with PARTITION in {development, untouchedTest}
# Every STORYMODEL4S_* variable in the environment passes through to the runner and changes the
# report identity; the handoff brief lists them with their defaults.
#
# Inputs come from the data root (tools/data-root.sh; see data/README.md) and stay outside Git.
# Outputs land in <data>/study/recall-to-video/<ARM_LABEL>/ and are scored by the scripts beside
# this one. The untouched partition is unsealed once; running it is a deliberate act.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$here/../.."
data="$(bash "$root/tools/data-root.sh")"
study="$data/study/recall-to-video"
arm="${1:?arm label}"
partition="${2:?development or untouchedTest}"

out="$study/$arm"
mkdir -p "$out"
names=$(python3 -c "
import json,sys
p=json.load(open('$study/partition.json'))
print('\n'.join(p['$partition']))")

export ORT_DISABLE_TELEMETRY=1
export STORYMODEL4S_ONNX_MODEL="${STORYMODEL4S_ONNX_MODEL:-$data/models/onnx/model.onnx}"
export STORYMODEL4S_ONNX_TOKENIZER="${STORYMODEL4S_ONNX_TOKENIZER:-$data/models/onnx/tokenizer.json}"
annotation="$data/sherlock/Sherlock_Segments_1000_NN_2017.tsv"

echo "arm=$arm partition=$partition data=$data"
env | grep '^STORYMODEL4S_' | grep -v '_ONNX_' | sed 's/^/  /' || true
for n in $names; do
  recall="$data/sherlock/recall/$n.csv"
  report="$out/recall-map-$n.tsv"
  if [ -f "$report" ]; then echo "  $n: present, skipping"; continue; fi
  echo "  $n ..."
  ( cd "$root" && sbt -batch "embedBench/runMain storymodel4s.bench.sherlock.sherlockRecallMap $annotation $recall $report" ) \
    > "$out/$n.log" 2>&1 || { echo "  $n FAILED, see $out/$n.log"; exit 1; }
done
echo "done: $(ls "$out"/recall-map-*.tsv 2>/dev/null | wc -l | tr -d ' ') reports in $out"
