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
# PASS THE MERGE BASE, NOT `main`, when main has advanced past the candidate's
# parent. `git diff main..CAND` reports files that MAIN added and the candidate
# lacks as if the candidate had changed them -- so a freshly merged sibling's files
# show up REVERSED, in the candidate's type list, and widen the scope with types it
# never touched. Measured on 2026-08-29: gating D1 off `main` after D2 had landed
# named all 16 modules and listed D2's BirthdayInterview and ConstructionBoundarySuite
# among "types defined by this candidate". Use:
#     bash tools/reference-scope.sh "$(git merge-base main CAND)" CAND
# The error is toward a WIDER scope here, which is the safe direction, but it also
# misreports what the candidate does -- and the same arithmetic can mislead in the
# other direction when reasoning about who a change affects.
# RUN THIS IN A CLONE CHECKED OUT AT THE CANDIDATE, NOT IN THE SHARED TREE. It reads
# type NAMES from the candidate via `git show`, but greps for CONSUMERS in the
# WORKING TREE. When the candidate DEFINES A NEW TYPE, the working tree contains
# nothing that references it yet, so the consumer grep finds nothing and the module
# list comes back EMPTY. Measured 2026-08-30 on 72b106c, which introduces
# CellCoordinates: run from the shared tree it emitted `sbt -batch ""` and named no
# module; run from a clone at the candidate it correctly named `align`.
#
# AN EMPTY SCOPE IS A TOOL RESULT, NOT A FINDING, and it fails in the dangerous
# direction -- silently, and toward gating nothing. It is the same shape as a red
# gate with no test totals: the output looks like an answer and measured nothing.
# The check below therefore EXITS NONZERO rather than printing an empty gate
# command, because a tool whose failure mode is a plausible-looking blank is a tool
# that will eventually be believed.

set -euo pipefail
BASE="${1:?usage: reference-scope.sh BASE [HEAD]   (BASE should usually be $(git merge-base main HEAD))}"
HEAD_REF="${2:-HEAD}"

build_sbt="$(git show "$HEAD_REF:build.sbt")"
base_build_sbt="$(git show "$BASE:build.sbt" 2>/dev/null || true)"
module_dirs="$(
  printf '%s\n' "$build_sbt" \
    | sed -n 's/.*\.in(file("\([^"]*\)")).*/\1/p' \
    | sort -u
)"

# Declarations removed by the candidate still define its downstream scope. A
# whole module may also disappear from HEAD, so recognize source paths from the
# union of the base and candidate builds while emitting gates only for modules
# that survive in the candidate build.
source_module_dirs="$(
  {
    printf '%s\n' "$base_build_sbt"
    printf '%s\n' "$build_sbt"
  } \
    | sed -n 's/.*\.in(file("\([^"]*\)")).*/\1/p' \
    | sort -u
)"

if [ -z "$module_dirs" ]; then
  echo "could not derive module directories from $HEAD_REF:build.sbt" >&2
  exit 2
fi

is_module_source() {
  file="$1"
  for module in $source_module_dirs; do
    case "$file" in
      "$module"/src/*.scala|"$module"/.jvm/src/*.scala) return 0 ;;
    esac
  done
  return 1
}

unsupported_platform_source() {
  file="$1"
  for module in $source_module_dirs; do
    case "$file" in
      "$module"/.js/src/*.scala|"$module"/.native/src/*.scala) return 0 ;;
    esac
  done
  return 1
}

# This script emits JVM test tasks. Silently ignoring a changed Scala.js or
# Native source would make that JVM gate look complete when it cannot cover the
# changed platform. Refuse until this tool has platform-specific task emission
# and regression courts.
changed_unsupported_platform_paths() {
  git diff --name-status --find-renames "$BASE..$HEAD_REF" -- '*.scala' \
    | while IFS=$'\t' read -r status path_before path_after; do
        case "$status" in
          R*)
            if unsupported_platform_source "$path_before"; then printf '%s\n' "$path_before"; fi
            if unsupported_platform_source "$path_after"; then printf '%s\n' "$path_after"; fi
            ;;
          C*)
            if unsupported_platform_source "$path_before"; then printf '%s\n' "$path_before"; fi
            if unsupported_platform_source "$path_after"; then printf '%s\n' "$path_after"; fi
            ;;
          *)
            if unsupported_platform_source "$path_before"; then printf '%s\n' "$path_before"; fi
            ;;
        esac
      done
}

unsupported_platform_paths="$(changed_unsupported_platform_paths | sort -u)"

if [ -n "$unsupported_platform_paths" ]; then
  echo "unsupported changed platform source for JVM-only gate:" >&2
  while IFS= read -r path; do printf '  %s\n' "$path" >&2; done <<< "$unsupported_platform_paths"
  echo "reference-scope.sh cannot emit complete Scala.js or Native gate tasks; refusing partial scope." >&2
  exit 4
fi

declared_types_at() {
  ref="$1"
  file="$2"
  if is_module_source "$file"; then
    # Keep the ordinary Scala 3 declaration-modifier family together here so a
    # new legal prefix does not silently turn an API change into "no types".
    # `opaque` remains intentionally excluded for the reason documented above.
    declaration_modifier='(private|protected)(\[[^]]+\])?|abstract|final|sealed|case|implicit|lazy|override|inline|infix|transparent|open|erased'
    git show "$ref:$file" 2>/dev/null \
      | grep -hoE "^[[:space:]]*((${declaration_modifier})[[:space:]]+)*(class|trait|object|enum|type)[[:space:]]+[A-Z][A-Za-z0-9_]*" \
      | awk '{print $NF}' \
      || true
  fi
}

changed_types() {
  git diff --name-status --find-renames "$BASE..$HEAD_REF" -- '*.scala' \
    | while IFS=$'\t' read -r status path_before path_after; do
        case "$status" in
          A*) declared_types_at "$HEAD_REF" "$path_before" ;;
          D*) declared_types_at "$BASE" "$path_before" ;;
          R*|C*)
            declared_types_at "$BASE" "$path_before"
            declared_types_at "$HEAD_REF" "$path_after"
            ;;
          *)
            # Scan both revisions: a modified file can delete one declaration
            # while retaining or adding another.
            declared_types_at "$BASE" "$path_before"
            declared_types_at "$HEAD_REF" "$path_before"
            ;;
        esac
      done
}

types="$(changed_types | sort -u)"

if [ -z "$types" ]; then
  echo "no types defined in changed files"
  exit 0
fi

platform_consumers="$(
  {
    for t in $types; do
      for m in $module_dirs; do
        for platform in js native; do
          source_root="$m/.$platform/src"
          if [ -d "$source_root" ]; then
            while IFS= read -r path; do
              [ -n "$path" ] && printf '%s\t%s\n' "$m" "$path"
            done < <(grep -rlsw "$t" "$source_root" 2>/dev/null || true)
          fi
        done
      done
    done
  } | sort -u
)"

# A changed shared declaration can have an unchanged Scala.js- or Native-only
# consumer. This JVM-only emitter cannot cover that consumer, so refuse before
# printing a partial gate command and name the exact module/path that forced the
# refusal.
if [ -n "$platform_consumers" ]; then
  echo "unsupported platform-specific consumer for JVM-only gate:" >&2
  while IFS=$'\t' read -r module path; do
    printf '  %s: %s\n' "$module" "$path" >&2
  done <<< "$platform_consumers"
  echo "reference-scope.sh cannot emit complete Scala.js or Native gate tasks; refusing partial scope." >&2
  exit 4
fi

echo "types defined by this candidate:"
while IFS= read -r type_name; do printf '  %s\n' "$type_name"; done <<< "$types"
echo
mods="$(
  {
    for t in $types; do
      for m in $module_dirs; do
        for source_root in "$m/src" "$m/.jvm/src"; do
          if [ -d "$source_root" ] && grep -rqsw "$t" "$source_root"; then
            echo "$m"
            break
          fi
        done
      done
    done
  } | sort -u
)"

if [ -z "$mods" ]; then
  echo "EMPTY SCOPE -- REFUSING TO EMIT A GATE COMMAND." >&2
  echo "No module in this tree references any type defined by the candidate. That is" >&2
  echo "almost never true of real work; the usual cause is running this in the shared" >&2
  echo "tree while the candidate DEFINES a new type, so nothing here references it yet." >&2
  echo "Re-run in a clone checked out AT the candidate commit:" >&2
  echo "    git clone --no-hardlinks . /tmp/scope && git -C /tmp/scope checkout $HEAD_REF" >&2
  echo "    bash /tmp/scope/tools/reference-scope.sh \"$BASE\" $HEAD_REF" >&2
  echo "If it is still empty there, gate the module that DEFINES the types and say so" >&2
  echo "explicitly in the merge commit. Do not gate nothing." >&2
  exit 3
fi

echo "modules that reference them (the gate must cover all of these):"
while IFS= read -r module_name; do printf '  %s\n' "$module_name"; done <<< "$mods"

# A DIRECTORY NAME IS NOT AN SBT PROJECT ID, and the whole point of this tool is to
# tell you what to gate -- so emitting a name the gate rejects is emitting nothing.
# Measured on 2026-08-29: a D2 gate run as `acquire/test` died with "Not a valid
# command: acquire", GATE_EXIT=1 and zero test totals, which reads exactly like a
# failing suite. That is the second way in one day to get a red gate that measured
# nothing (the first was running inside a linked worktree, where sbt-git sees the
# `.git` FILE as a bare repo and project loading fails outright).
#
# The mapping is kebab-case to camelCase plus a `JVM` suffix, except for projects
# named in build.sbt's jvmOnlyModules list. Those projects have NO platform suffix,
# so inventing (for example) `embedOnnxJVM` emits a gate command for a project that
# does not exist. Read the list from the candidate build rather than maintaining a
# second list here that becomes stale when a JVM-only module is added.
#
# The IDs are derived here rather than scraped from `sbt projects` deliberately.
# This build stages three sibling builds (grakern, gale, graph4s), and `sbt projects`
# prints THEIR projects too -- including colliding `coreJVM`, `lawsJVM` and `rootJVM`.
# Scraping that output without first cutting to this build's own block silently
# resolves a module to a sibling repo's project.
#
# The list is joined onto one line before the sed, because scalafmt wraps it once it
# passes 100 columns and sed matches per line. Measured 2026-09-02: the two-line form
# `val jvmOnlyModules =\n  List(...)` on main matched NOTHING, so this tool emitted
# `providerAgentJVM/test` for a provider-agent change -- a gate command for a project
# that does not exist, the exact failure the paragraph above was written against.
jvm_only_ids="$(
  printf '%s\n' "$build_sbt" \
    | tr '\n' ' ' \
    | sed -n 's/.*jvmOnlyModules[[:space:]]*=[[:space:]]*List(\([^)]*\)).*/\1/p' \
    | tr ',' '\n' \
    | sed 's/[[:space:]\"]//g; /^$/d'
)"

camel_id() {
  echo "$1" | awk -F- '{p=$1; for(i=2;i<=NF;i++) p=p toupper(substr($i,1,1)) substr($i,2); print p}'
}

sbt_id() {
  base_id="$(camel_id "$1")"
  if printf '%s\n' "$jvm_only_ids" | grep -qx "$base_id"; then
    echo "$base_id"
  else
    echo "${base_id}JVM"
  fi
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
