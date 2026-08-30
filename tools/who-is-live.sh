#!/usr/bin/env bash
# who-is-live.sh [IDLE_MINUTES]     (run with: bash tools/who-is-live.sh)
#
# Who is ACTUALLY at the keyboard, derived from the ops log, before you assign
# anyone anything.
#
# `mote actor list` reports `presence=live`, and that field is a LEASE, not a
# heartbeat. It says an actor's session lease has not expired yet. It does not
# say a human or agent is there. On 2026-08-30 the chief read that field as
# liveness THREE TIMES:
#
#   19:16  assigned a sixty-second test to codex-storyatlas-root, whose session
#          had ended at 17:22.
#   20:24  assigned twenty-three probe-anchor slices to codex-storymodel4s-collab.
#          That actor EXPIRED AT 20:22:55 -- ninety seconds before the assignment.
#   20:31  wrote a ruling that described the 19:16 incident as "the mistake I made
#          of reading a valid lease as an actor at the keyboard", nine minutes
#          AFTER making it again.
#
# The third one is why this file exists. The rule was known, written down, and
# quoted by its own author while being broken. Stating a rule is not applying it,
# and a rule this cheap to check should not depend on anybody remembering it.
#
# It also found, on its first run, that a board showing seven `live` actors had a
# real workforce of THREE. Work was assigned to ghosts, beads sat in `doing` with
# no one doing them, and a merge was held open against a deadline for an actor who
# had been gone 174 minutes.
#
# GROUND TRUTH IS THE OPS LOG. Every mote operation records its actor and its
# timestamp, so the last op an actor wrote is the last moment they demonstrably
# existed. That cannot be faked by a lease that nobody revoked. It reads the
# WORKING TREE's .mote/ops rather than a git ref, deliberately: an op committed
# ten minutes ago but written two hours ago would date the commit, not the actor,
# and uncommitted ops are exactly the freshest evidence available.
#
# IT UNDER-REPORTS ACTIVITY AND THAT IS THE SAFE DIRECTION. An actor who is
# reading, thinking, compiling or running a long gate writes no ops and will look
# idle. So a `stale` row means "has not acted recently", never "is not there" --
# ASK BEFORE REASSIGNING. The error this guards against is the opposite one:
# treating absence as presence, which strands work silently.
set -uo pipefail

IDLE="${1:-15}"

if [ ! -d .mote/ops ]; then
  echo "no .mote/ops here -- run from the repository root" >&2
  exit 2
fi

python3 - "$IDLE" <<'PY'
import json, glob, sys, datetime

idle_cut = float(sys.argv[1])
last = {}
for f in glob.glob('.mote/ops/*.json'):
    try:
        d = json.load(open(f))
    except Exception:
        continue
    p = d.get('payload', d)
    a, ts = p.get('actor'), (p.get('ts') or d.get('ts') or '')
    if not a or not ts:
        continue
    if a not in last or ts > last[a]:
        last[a] = ts

if not last:
    print('NO OPS FOUND -- that is a broken query, not an empty board.',
          file=sys.stderr)
    raise SystemExit(3)

now = datetime.datetime.now(datetime.timezone.utc)
rows = []
for a, ts in last.items():
    try:
        t = datetime.datetime.fromisoformat(ts.replace('Z', '+00:00'))
    except Exception:
        continue
    rows.append(((now - t).total_seconds() / 60.0, a, ts[11:19]))
rows.sort()

active = [r for r in rows if r[0] < idle_cut]
stale = [r for r in rows if idle_cut <= r[0] < 120]
gone = [r for r in rows if r[0] >= 120]

def show(title, group):
    print(title)
    if not group:
        print('    (none)')
    for mins, a, hhmm in group:
        print(f'    {mins:6.0f}m idle   {a:36s} last op {hhmm}Z')
    print()

print(f'ACTOR LIVENESS, derived from .mote/ops at {now.strftime("%H:%M:%SZ")}')
print(f'(active = acted within {idle_cut:.0f} minutes)')
print()
show(f'ACTIVE -- safe to assign ({len(active)}):', active)
show(f'STALE -- ask before assigning ({len(stale)}):', stale)
show(f'GONE -- do not assign, do not wait on ({len(gone)}):', gone)

print('`mote actor list` presence=live is a LEASE, not a heartbeat: it means the')
print('session lease has not expired, not that anyone is there. Cross-check here')
print('before assigning work, setting a deadline, or holding a merge open.')
print()
print('A stale row means "has not acted recently", never "is not there" -- an actor')
print('running a long gate writes no ops. ASK before reassigning their work.')
PY
