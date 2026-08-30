#!/usr/bin/env bash
# nan-polarity.sh [REF]     (run with: bash tools/nan-polarity.sh main)
#
# Sorts every numeric zero-guard in the main sources into FAILS-OPEN and
# FAILS-CLOSED, by the criterion claude-storymodel4s-m1 established on
# bd-01M174W3J and the chief ratified there:
#
#     if x <= 0.0 then SAFE else COMPUTE     NaN fails the test, takes ELSE,
#                                            arithmetic runs on NaN.  FAILS OPEN
#     if x >  0.0 then COMPUTE else SAFE     NaN fails the test, takes ELSE,
#                                            lands on SAFE.           FAILS CLOSED
#
# Same check, opposite outcome, decided entirely by which branch holds the safe
# path. Every IEEE-754 comparison against NaN is false except `!=`, so the guard
# does not "detect NaN and fall through" -- it silently classifies NaN as
# "not less than or equal to zero", which is to say, as a fine positive number.
#
# THE POINT OF THIS FILE IS THAT THE CRITERION IS MECHANICAL. The chief scoped
# the original bead as case-by-case reachability tracing across nineteen sites --
# real work. m1 replaced it with a criterion that sorts by grep and makes the
# fail-closed half need no thought at all. That is a survey replaced by a rule,
# and a rule that has now caught three real defects is due a script rather than
# a firmer sentence in AGENTS.md. This is the script.
#
# WHAT IT ACTUALLY FOUND WHEN FIRST RUN -- and the first draft of this header
# claimed one thing the tool does not report, which is worth leaving on the record
# since it is the exact failure this file is about:
#
#   align/signature.scala:192,:212  REPORTED. MassRatio.unsafe and `support` --
#                           `Some(NaN)` out of the very type whose purpose is
#                           refusing unsupported numbers, and the carrier for
#                           eight migrated estimand fields.
#   align/sinkhorn.scala    NOT REPORTED, AND CORRECTLY SO. I listed it here from
#                           the bead's description before running the tool. It has
#                           since been fixed: :113 now reads
#                           `Option.unless(config.epsilon.isFinite && config.epsilon > 0.0)`
#                           -- explicit finiteness AND fail-closed polarity. The
#                           tool was right and the header was wrong, because the
#                           header was written from a description rather than from
#                           a run.
#   features/window.scala:52  fixed at 6e92028, and the first version of this tool
#                           REPORTED IT ANYWAY -- see the chain-lookback note below.
#
# IT OVER-APPROXIMATES AND WILL NOT DECIDE FOR YOU. It reads polarity, which is
# syntax, and says nothing about REACHABILITY (can NaN actually arrive) or
# CONSEQUENCE (what comes out when it does). Those are the other two of the three
# questions on that bead and both need a human -- consequence has to be RUN, not
# predicted. A FAILS-OPEN line here is a site to read, not a defect to report.
#
# IT LOOKS BACK UP THE if/else CHAIN, and the first version did not. A guard does
# not have to sit on the line it protects. features/window.scala reads
#
#     if !Estimate.isFinite(b) then Double.NaN
#     else if b <= 0.0 then (if d == 0.0 then 1.0 else 0.0)
#
# and the second line, read alone, is textbook fail-open. It is not: line one
# already took NaN out. The line-local first draft reported it as a defect -- a
# site FIXED EARLIER THE SAME DAY at 6e92028, which is how the false positive was
# caught at all. So when a hit begins with `else`, this walks back up the chain
# looking for an explicit finiteness test and classifies it GUARDED-BY-CHAIN.
#
# That lookback is a HEURISTIC and it is the weakest part of this tool. It stops
# at the first line that does not continue an if/else chain, so a guard placed
# further away, or one expressed as an early `return`/`require`, will not be seen
# and the site will be reported. Reported-when-safe is the direction to err: it
# costs a read, where the other direction costs a defect.
#
# A guard that names isNaN or isFinite explicitly is classified GUARDED and is
# not a finding: an explicit finiteness test in the condition is the fix, and
# interview/Scoring.scala and embed-core/vector.scala already do exactly that.
# Both correct and incorrect forms are present in this codebase -- in window.scala
# they were fifteen lines apart -- which is the signature of no convention at all:
# nobody chose, so both appear.
#
# EXIT 0 always. This is a survey instrument, not a gate; it reports a
# population, and a population of zero would be the suspicious answer, not the
# clean one. If you want it to gate something, gate on a specific site you have
# already read and understood.
set -uo pipefail

REF="${1:-main}"
if ! git rev-parse --verify --quiet "$REF^{commit}" >/dev/null; then
  echo "no such ref: $REF" >&2
  exit 2
fi

# `git grep` on a ref rather than the working tree, so the result is bound to a
# commit and can be quoted as evidence. Reading the working tree would report a
# population that no SHA can reproduce.
hits="$(git grep -nE 'if [^)]*(<=|<|>=|>) 0\.0 then' "$REF" \
          -- '*/src/main/scala/**/*.scala' 2>/dev/null || true)"

if [ -z "$hits" ]; then
  echo "NO ZERO-GUARDS FOUND AT $REF -- that is not a clean result, it is a broken query." >&2
  echo "This codebase certainly has numeric guards; check the pathspec and the ref." >&2
  exit 3
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

open_n=0; closed_n=0; guarded_n=0
open_out=""; closed_out=""; guarded_out=""

while IFS= read -r line; do
  [ -z "$line" ] && continue
  # `git grep -n REF -- path` emits "REF:path:lineno:code", so the code needs
  # THREE strips, not two. The first version did two, leaving "lineno:code" in
  # $body -- which silently disabled the `^else` anchor below and with it the
  # whole chain lookback, while the polarity regex went on matching unanchored
  # and looking perfectly healthy. A prefix nobody sees, on a variable that still
  # produces plausible output.
  rest="${line#*:}"                         # path:lineno:code
  path="${rest%%:*}"
  lineno="${rest#*:}"; lineno="${lineno%%:*}"
  body="${rest#*:}"; body="${body#*:}"      # code alone
  short="$(printf '%s' "$path" | sed 's#/src/main/scala/storymodel4s#/…#')"

  # An explicit finiteness test in the CONDITION is the remedy, not a finding.
  if printf '%s' "$body" | grep -qE 'isNaN|isFinite'; then
    guarded_n=$((guarded_n+1))
    guarded_out="${guarded_out}    ${short}:${lineno}\n"
    continue
  fi

  # ...and so is one EARLIER IN THE SAME CHAIN. Only worth walking when this hit
  # is itself an `else` branch; a chain-opening `if` has nothing above it to
  # inherit a guard from. File content is cached per path -- `git show` once per
  # file rather than once per hit.
  if printf '%s' "$body" | grep -qE '^[[:space:]]*else'; then
    cache="$tmpdir/$(printf '%s' "$path" | tr '/' '_')"
    [ -f "$cache" ] || git show "$REF:$path" > "$cache" 2>/dev/null
    chain_guarded=0
    n=$((lineno-1))
    while [ "$n" -ge 1 ] && [ "$n" -gt $((lineno-12)) ]; do
      prev="$(sed -n "${n}p" "$cache")"
      if printf '%s' "$prev" | grep -qE 'isNaN|isFinite'; then chain_guarded=1; break; fi
      # Stop at the first line that does not continue an if/else chain.
      printf '%s' "$prev" | grep -qE '^[[:space:]]*(else|if |\|\||&&)' || break
      n=$((n-1))
    done
    if [ "$chain_guarded" -eq 1 ]; then
      guarded_n=$((guarded_n+1))
      guarded_out="${guarded_out}    ${short}:${lineno}  (guarded by chain)\n"
      continue
    fi
  fi

  # Polarity: `> 0.0` / `>= 0.0` puts COMPUTE in the then-branch, so NaN lands on
  # the else, which is the safe path. `<= 0.0` / `< 0.0` puts SAFE in the then,
  # so NaN lands on the else, which computes.
  if printf '%s' "$body" | grep -qE 'if [^)]*(>=|>) 0\.0 then'; then
    closed_n=$((closed_n+1))
    closed_out="${closed_out}    ${short}:${lineno}\n"
  else
    open_n=$((open_n+1))
    trimmed="$(printf '%s' "$body" | sed 's/^[[:space:]]*//' | cut -c1-96)"
    open_out="${open_out}    ${short}:${lineno}\n      ${trimmed}\n"
  fi
done <<< "$hits"

echo "NaN POLARITY SURVEY at $REF"
echo
echo "FAILS OPEN -- NaN reaches the arithmetic ($open_n sites). READ EVERY ONE."
printf "$open_out"
echo
echo "FAILS CLOSED -- NaN lands on the safe branch for free ($closed_n sites)."
printf "$closed_out"
echo
echo "GUARDED -- condition names isNaN/isFinite explicitly ($guarded_n sites). Not findings."
printf "$guarded_out"
echo
echo "Polarity is syntax. It does not tell you whether NaN can ARRIVE (reachability,"
echo "needs source tracing) or what comes OUT when it does (consequence, must be RUN)."
echo "Prefer the fail-closed shape in new code: it is correct under NaN for free,"
echo "needs no isNaN check, and costs nothing to write that way round."
