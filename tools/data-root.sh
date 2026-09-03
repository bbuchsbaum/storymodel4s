#!/usr/bin/env bash
# Print the local data root: the directory holding external sources, model weights, and the
# study run record, none of which is committed. See data/README.md for the layout.
#
# Resolution, in order:
#   1. $STORYMODEL4S_DATA, when set;
#   2. <main checkout>/data, found through the git common dir so every worktree shares one copy.
#
# Usage: root="$(tools/data-root.sh)"        or        tools/data-root.sh --check
set -euo pipefail
if [ -n "${STORYMODEL4S_DATA:-}" ]; then
  root="$STORYMODEL4S_DATA"
else
  common="$(git rev-parse --git-common-dir)"
  root="$(cd "$common/.." && pwd)/data"
fi
if [ "${1:-}" = "--check" ]; then
  status=0
  for rel in sherlock/Sherlock_Segments_1000_NN_2017.tsv sherlock/Sherlock_Recall_Scene_n50_Onsets.csv \
             sherlock/recall models/onnx/model.onnx models/onnx/tokenizer.json study/recall-to-video/partition.json; do
    if [ -e "$root/$rel" ]; then echo "present  $root/$rel"; else echo "MISSING  $root/$rel"; status=1; fi
  done
  exit $status
fi
printf '%s\n' "$root"
