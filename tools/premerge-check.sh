#!/usr/bin/env bash
# premerge-check.sh CANDIDATE_ID COMMIT [GATE_LOG]
#   bash tools/premerge-check.sh cand-XXXX 1a2b3c4 /tmp/gate.log
#
# The chief's merge gate, mechanised. Every check below is an AGENTS.md sub-rule that
# EXISTS ONLY AS PROSE, and on 2026-08-30 the author of those rules broke two of them in
# one day while holding the pen:
#
#   * committed to a path another actor held a LIVE RESERVATION on, without running
#     who-has -- having enforced that same rule on two other actors within the hour;
#   * merged a candidate without re-reading the board, two minutes after a message
#     addressed to him by name said the merge commit should say something different.
#
# reference-scope.sh's header states the principle this file follows: rule 3 "has caught
# three real breaks -- and every one of them got through first because THE RULE DEPENDED
# ON A PERSON REMEMBERING TO GREP. This is the grep." This is the rest of the greps.
#
# IT EXITS NONZERO ON ANY DOUBT. A merge gate that passes when it could not check is the
# very defect it exists to prevent: a failure that renders as a clean result.
set -uo pipefail

CAND="${1:?usage: premerge-check.sh CANDIDATE_ID COMMIT [GATE_LOG]}"
COMMIT="${2:?usage: premerge-check.sh CANDIDATE_ID COMMIT [GATE_LOG]}"
GATE_LOG="${3:-}"
fail=0
note() { printf '  %s\n' "$1"; }
bad() { printf 'FAIL: %s\n' "$1" >&2; fail=1; }

echo "== 1. branch =="
BR="$(git rev-parse --abbrev-ref HEAD)"
if [ "$BR" = "main" ]; then note "on main"; else bad "HEAD is '$BR', not main"; fi

echo "== 2. candidate object is reachable HERE =="
# A candidate proposed from a standalone clone can be 'landable' while unreadable by the
# actor who must land it. Measured twice on 2026-08-30; filed as bbuchsbaum/mote#16.
if git cat-file -e "${COMMIT}^{commit}" 2>/dev/null; then
  note "$COMMIT present"
else
  bad "$COMMIT is NOT in this repository -- ask its author to push it or name their clone"
fi

echo "== 3. candidate blocker list is EMPTY, read in full =="
# Never head this list. On 2026-08-30 a truncated read told a reviewer their review was
# satisfied when review_missing was the first line dropped.
if ! out="$(mote candidate show "$CAND" 2>&1)"; then
  bad "mote candidate show failed -- an empty blocker list and a failed command look identical"
else
  blockers="$(printf '%s\n' "$out" | sed -n '2,$p' | sed '/^[[:space:]]*$/d')"
  if [ -z "$blockers" ]; then
    note "no blockers"
  else
    bad "candidate is blocked:"
    printf '%s\n' "$blockers" >&2
  fi
fi

echo "== 4. no live reservation on any path this commit touches =="
base="$(git merge-base main "$COMMIT" 2>/dev/null || echo '')"
if [ -z "$base" ]; then
  bad "cannot compute merge-base for $COMMIT"
else
  paths="$(git diff --name-only "$base..$COMMIT" 2>/dev/null)"
  if [ -z "$paths" ]; then
    bad "candidate changes NO files -- verify you have the right commit"
  else
    held=0
    while IFS= read -r p; do
      [ -z "$p" ] && continue
      w="$(mote who-has "$p" 2>&1 || true)"
      case "$w" in
        *"no live reservations"*) : ;;
        *) printf 'FAIL: reserved: %s\n' "$w" >&2; held=1 ;;
      esac
    done <<< "$paths"
    if [ "$held" -eq 0 ]; then
      note "$(printf '%s\n' "$paths" | wc -l | tr -d ' ') path(s), none reserved"
    else
      fail=1
    fi
  fi
fi

echo "== 5. gate log has TEST TOTALS, not just an exit =="
# A gate that produced no totals did not FAIL, it did not RUN. Measured 2026-08-30: a full
# disk gave exit 1, plausible compiler errors and zero totals across three modules, which
# reads exactly like "this candidate breaks three modules".
if [ -z "$GATE_LOG" ]; then
  bad "no gate log given -- pass one; 'I ran it and it looked fine' is not a receipt"
elif [ ! -f "$GATE_LOG" ]; then
  bad "gate log $GATE_LOG does not exist"
else
  totals="$(grep -c 'Passed: Total' "$GATE_LOG" 2>/dev/null || echo 0)"
  failed="$(grep -oE 'Failed [0-9]+' "$GATE_LOG" 2>/dev/null | awk '{s+=$2} END {print s+0}')"
  if [ "$totals" -eq 0 ]; then
    bad "gate log has ZERO 'Passed: Total' lines -- it measured your infrastructure, not the code"
  elif [ "${failed:-0}" -ne 0 ]; then
    bad "gate log reports $failed failed test(s)"
  else
    note "$totals totals, 0 failed"
  fi
  if ! grep -qE '_EXIT=[0-9]+' "$GATE_LOG" 2>/dev/null; then
    bad "gate log has no captured exit status -- a backgrounded gate's exit describes the FORK"
  else
    note "exit captured: $(grep -oE '[A-Z]+_EXIT=[0-9]+' "$GATE_LOG" | tr '\n' ' ')"
  fi
fi

echo "== 6. RE-READ THE BOARD NOW, not before the gate =="
# The gate takes minutes and reviewers work in parallel, so a hold -- or a correction to
# what the merge commit should SAY -- can arrive INSIDE the gate window.
# NOT `mote discuss list`: that surfaces STICKY posts first, so the first version of this
# check printed pins from two days earlier and looked like it had done its job. A check that
# renders as done while showing the wrong data is the defect this whole file exists to catch,
# and it was in the file on its first run. `unread` is posts newer than this actor's cursor,
# which is the actual question -- did anything arrive while the gate was running.
echo "  unread since your cursor:"
if unread="$(mote discuss unread --limit 8 2>/dev/null)" && [ -n "$unread" ]; then
  printf '%s\n' "$unread" | sed 's/^/    /'
else
  echo "    (nothing unread -- but you have still not looked at replies to your own posts)"
fi
echo "  READ THOSE. This check cannot judge them for you; it only refuses to let you skip looking."

echo "== 7. has anything reached main WITHOUT the merge gate? =="
# Measured 2026-08-30: a candidate's commit sat on main's FIRST-PARENT LINE, committed directly,
# seven hours before anyone noticed -- while its candidate row was still pending with a BLOCKING
# review. Every candidate based on main then inherited `ancestor_blocked` from a row nobody could
# resolve, holding up another actor's approved work.
#
# The signal is precise: a commit that was PROPOSED as a candidate yet sits on the first-parent
# line never went through a merge. Matching must be on the candidate's commit_oid specifically --
# a raw grep of the candidate JSON also hits base_oid and landing targets, which flagged two of my
# own ordinary commits as bypasses when I first wrote this.
cands_tmp="$(mktemp)"
if mote candidate list --json 2>/dev/null \
  | python3 -c 'import sys,json
d=json.load(sys.stdin)
rows=d if isinstance(d,list) else d.get("candidates",d.get("items",[]))
for r in rows:
    o=(r.get("identity") or {}).get("commit_oid") or r.get("commit_oid")
    if o: print(o)' > "$cands_tmp" 2>/dev/null && [ -s "$cands_tmp" ]; then
  # NO WINDOW, AND NO LOOP. Two separate defects, both found by someone else re-running this:
  #
  # The window: the first version scanned 20 commits and missed the live case entirely; the second
  # scanned 60 and found one. claude-storymodel4s-m1 ran the same predicate over main's ENTIRE
  # first-parent line -- 865 commits against 45 candidate oids -- and found THREE. A window is a
  # number someone picked, and every value of it is wrong for some history. Two of m1's three were
  # ABANDONED candidates, so they block nothing today; they are still the same structural event,
  # and a check that reports "clean" because it stopped short is the defect this file is about.
  #
  # The loop: scanning all 865 by shelling out `git log -1` per commit took 53 SECONDS, and a
  # pre-merge check that slow is a pre-merge check people skip -- worse than absent, because it
  # also carries a claim to have looked. `--parents` prints "<sha> <parent>..." so a line with
  # exactly two fields is a non-merge commit; intersect those with the candidate oids in one grep.
  nonmerge_tmp="$(mktemp)"
  git rev-list --first-parent --parents main 2>/dev/null \
    | awk 'NF==2 {print $1}' > "$nonmerge_tmp"
  hits="$(grep -Fx -f "$cands_tmp" "$nonmerge_tmp" 2>/dev/null || true)"
  rm -f "$nonmerge_tmp"
  if [ -z "$hits" ]; then
    note "no proposed commit sits on main's first-parent line ($(wc -l < "$cands_tmp" | tr -d ' ') candidates checked)"
  else
    while IFS= read -r c; do
      [ -z "$c" ] && continue
      printf 'FAIL: gate bypass: %s %s\n' "$(git rev-parse --short "$c")" \
        "$(git log -1 --format=%s "$c")" >&2
    done <<< "$hits"
    fail=1
  fi
else
  bad "could not enumerate candidate commits -- cannot check for gate bypasses"
fi
rm -f "$cands_tmp"

echo
if [ "$fail" -ne 0 ]; then
  echo "PRE-MERGE CHECK FAILED. Do not merge." >&2
  exit 3
fi
echo "Mechanical checks passed. The board re-read in step 6 is yours to do."
