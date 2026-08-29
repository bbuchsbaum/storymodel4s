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
# `opaque` is DELIBERATELY ABSENT from the alternation, and this is not an
# oversight to be helpfully corrected. Checked against every opaque type in
# core, story and features: all of them except one have a companion object or
# class, so the tool already finds them by that name. The exception is
# `opaque type T = String`, an abstract type MEMBER of OpaqueId (core/ids.scala)
# rather than a standalone type -- and adding `opaque` would put a bare `T` in
# the type list for any candidate touching ids.scala, where `grep -w T` matches
# 12 of 13 modules. The tool would then name every module on every such change,
# which is not a wider scope, it is no signal at all.
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
mods="$(
  {
    for t in $types; do
      for m in $(ls -d */ | sed 's#/##' | grep -vE '^(project|target|docs|tools)$'); do
        if [ -d "$m/src" ] && grep -rqsw "$t" "$m/src"; then echo "$m"; fi
      done
    done
  } | sort -u
)"

echo "modules that reference them (the gate must cover all of these):"
echo "$mods" | sed 's/^/  /'

# A DIRECTORY NAME IS NOT AN SBT PROJECT ID, and the whole point of this tool is to
# tell you what to gate -- so emitting a name the gate rejects is emitting nothing.
# Measured on 2026-08-29: a D2 gate run as `acquire/test` died with "Not a valid
# command: acquire", GATE_EXIT=1 and zero test totals, which reads exactly like a
# failing suite. That is the second way in one day to get a red gate that measured
# nothing (the first was running inside a linked worktree, where sbt-git sees the
# `.git` FILE as a bare repo and project loading fails outright).
#
# The mapping is kebab-case to camelCase plus a `JVM` suffix, WITH TWO EXCEPTIONS
# that are not guessable and must not be "helpfully" regularised: `embed-bench` and
# `embed-grakern` are single JVM-only projects with NO platform suffix, so
# `embedBenchJVM` does not exist and would reproduce the exact failure above.
#
# The IDs are derived here rather than scraped from `sbt projects` deliberately.
# This build stages three sibling builds (grakern, gale, graph4s), and `sbt projects`
# prints THEIR projects too -- including colliding `coreJVM`, `lawsJVM` and `rootJVM`.
# Scraping that output without first cutting to this build's own block silently
# resolves a module to a sibling repo's project.
sbt_id() {
  case "$1" in
    embed-bench)   echo "embedBench" ;;
    embed-grakern) echo "embedGrakern" ;;
    *) echo "$1" | awk -F- '{p=$1; for(i=2;i<=NF;i++) p=p toupper(substr($i,1,1)) substr($i,2); print p "JVM"}' ;;
  esac
}

cmd=""
for m in $mods; do cmd="$cmd $(sbt_id "$m")/test;"; done
echo
echo "gate command (JVM; paste it, do not retype the module names):"
echo "  sbt -batch \"$(echo "$cmd" | sed 's/^ //; s/;$//')\""
echo
echo "  Run it in a CLONE, never a linked worktree. Capture the exit status. Run"
echo "  scalafmtCheckAll LAST, separately. Then grep the log for 'Passed: Total' --"
echo "  a red gate with no totals measured your infrastructure, not the candidate."
