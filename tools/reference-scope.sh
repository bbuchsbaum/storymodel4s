#!/usr/bin/env bash
# reference-scope.sh BASE [HEAD]    (run with: bash tools/reference-scope.sh BASE HEAD)
#
# Prints the modules a gate must cover for a candidate: every module whose
# sources reference any type DEFINED in the candidate's changed files.
#
# AGENTS.md rule 3 says the gate covers every module that REFERENCES the changed
# type, and that a stacked candidate takes the UNION across its commits. That
# rule has caught three real breaks -- laws on the population candidate,
# embed-bench and codec on the signature stack -- and every one of them got
# through first because the rule depended on a person remembering to grep.
# This is the grep.
#
# It searches src (main AND test) deliberately: compileAll does not compile
# tests, so a break in a test generator is invisible to it, which is exactly how
# the codec break survived a gate that had already been run.
#
# `type` is in the alternation deliberately. A type alias is a NAME, and a
# word-boundary grep for the underlying type will never match it -- ScoreEstimate
# does not match \bEstimate\b. Omitting it silently narrows the scope for
# exactly the consumers hardest to find by eye. Verified against ec0c439, which
# changes features/estimate.scala: without `type` the tool omits ScoreEstimate
# from the types it found, so any module referencing only the alias goes
# unnamed.
#
# Mechanised by claude-storymodel4s-m1 and validated by RETRODICTION against the
# three known failures before adoption -- run it on the commit that broke, and
# check it names the module that broke.
#
# IT OVER-APPROXIMATES, AND THAT IS THE SAFE DIRECTION. It matches bare NAMES,
# so two unrelated classes sharing a name put both their modules on the list:
# 4383a85 touches recall's GraphSuite and the tool therefore also names `story`
# and `amr-interop`, which have their own GraphSuite and reference nothing of
# recall's. A named module is a module to GATE, not evidence of a real consumer.
# Do not add a filter to suppress these -- a heuristic narrow enough to drop a
# same-named test class is narrow enough to drop a real shared helper, and this
# tool exists because the scope was too narrow, not because it was too wide.
set -euo pipefail
BASE="${1:?usage: reference-scope.sh BASE [HEAD]}"
HEAD_REF="${2:-HEAD}"

types="$(
  for f in $(git diff --name-only "$BASE..$HEAD_REF" | grep '\.scala$' || true); do
    git show "$HEAD_REF:$f" 2>/dev/null \
      | grep -hoE '^[[:space:]]*(final )?(sealed )?(case )?(class|trait|object|enum|type) [A-Z][A-Za-z0-9_]*' \
      | awk '{print $NF}'
  done | sort -u
)"

if [ -z "$types" ]; then
  echo "no types defined in changed files"
  exit 0
fi

echo "types defined by this candidate:"
echo "$types" | sed 's/^/  /'
echo
echo "modules that reference them (the gate must cover all of these):"
{
  for t in $types; do
    for m in $(ls -d */ | sed 's#/##' | grep -vE '^(project|target|docs|tools)$'); do
      if [ -d "$m/src" ] && grep -rqsw "$t" "$m/src"; then echo "$m"; fi
    done
  done
} | sort -u | sed 's/^/  /'
