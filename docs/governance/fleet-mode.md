# Fleet-mode governance and liveness history

Moved verbatim from `AGENTS.md` as of `main` `604080fc` on 2026-09-23 (owner directive: refactor
AGENTS.md). Nothing here was reworded; the short current form of these rules lives in
`AGENTS.md`, which links here. These sections describe the fleet protocol (chief, candidates, reservations, board check-ins) and the single-developer mode that replaced it on 2026-09-01. As of 2026-09-23 the operating mode is multi-agent via Fray (see `AGENTS.md`); ids L1-L10, SD1-SD8 and governance items 1-7 remain citable here.

## 0. Liveness — read this before anything below it

Everything after this section is a filter. There are roughly 220 of them and each
was added after a real break, so each is individually correct. Collectively,
measured on 2026-09-01, they produced this: in eight hours **21 commits landed on
`main`, 16 of them board bookkeeping by one agent and 5 board bookkeeping by the
chief. Zero lines of Scala shipped. One bead closed.** Three candidates sat
`pending landable` with no blockers — one of them `tools/reference-scope.sh`, a
tool *this file mandates* — because their proposer correctly refused to land his
own work and nobody was ever named to do it.

Nothing was broken. Everyone was right. That is the failure mode: **a quality rule
with no liveness counterpart converges on zero throughput, and every step down is
locally defensible.** The rules below catch bad code landing. None of them fires
when no code lands. These do, and **they outrank every rule below them.**

**L1. Zero-ship is a P0 defect.** If no commit touching anything outside `.mote/`
lands on `main` in 24 hours, the merge gate is presumed broken. Diagnosing it
becomes the chief's first priority, ahead of any review. Report the cause on
`coordination`, not the symptom.

**L2. A landable candidate has a named lander within one hour.** `pending
landable` with zero blockers is a promise, not a status. Ladder, in order: the
chief; any actor who is neither the proposer nor a reviewer of record; the owner.
**"I will not land my own work" is correct and must never be the end of the
sentence** — it is followed, in the same post, by naming who will. A proposer who
cannot name anyone escalates to the owner. Silence is not a fallback, and on
2026-09-01 it cost three ready candidates a full night.

**L3. A block is priced.** A BLOCK carries one of: (a) a patch, (b) a named owner
who has accepted the fix, with a deadline, or (c) the words *advisory, not
blocking*. A block carrying none of the three expires after 4 hours and the
candidate returns to its previous landability. Correctness is not sufficient
grounds to block indefinitely — every reviewer in the 2026-09-01 CODE RED thread
was right on the merits, and the net product was nothing.

**L4. Reap the ledger before reading it.** `superseded`, `abandoned`, `landed` and
`landed_out_of_band` rows must emit no blocking reasons and must not appear in the
default queue. Measured 2026-09-01: **93 of 133 candidates were dead or already
landed and generated 3,737 blocker strings**, burying the 3 rows that mattered. A
queue you have to filter by hand is a queue nobody reads.

**L5. A design thread must terminate.** Within 50 posts or 24 hours a topic
produces a landed change, a filed bead with a named owner, or a written *we are
not doing this*. The CODE RED thread reached 150 posts and a genuinely good PRD in
seventy minutes and shipped nothing. It should have stopped at its first
executable court and gone to write it.

**L6. Stop signing disclaimers.** Board posts are advisory by default; claiming
authority is explicit and rare, which makes the disclaimer redundant. On one topic
**100 of ~150 posts ended with some form of "no implementation, ADR, vocabulary,
bead, path, reservation, or authority claimed."** That ritual is evidence that
taking responsibility feels dangerous here. It should not be. State what you are
doing and omit what you are not.

**L7. Correct in place, once, then act.** A self-correction that does not change
what you do next does not need a post. Three sequential reversals of one claim is
a signal to go and run the thing, not to publish a fourth position on it.

**L8. Prefer the smallest landable slice to the most complete analysis.** When
both are available, land the slice. Analysis keeps; an unlanded branch rots
against a moving `main`.

**L10. Substitute authority. No single named actor may be a permanent single point
of failure.** Agents drop out — sessions end, leases lapse, operators stop prompting.
A governance object that hard-names one actor and provides no transfer path converts an
ordinary absence into a permanently dead row. Measured 2026-09-01: the gate-repair
candidate `cand-2G3GBE7Z` named `authorizer: cursor-grok-4.6`, who went 101 minutes idle;
`mote` refused every other seat with *"only the named authorizer may authorize a pending
candidate"*; `mote candidate` offers `amend-reviewers` but **no `amend-authorizer`**. That
one stale name blocked the repair of the merge gate itself, which blocked every other
landing. **The blocker was not a disagreement. It was a name.**

  - **Any named role — authorizer, required reviewer, lander, bead assignee — has a
    substitute ladder, and the ladder is part of the role, not a favour.** In order: the
    named actor; the chief; any active actor who is neither the proposer nor a reviewer of
    record; the owner. *Active* means recently acted per `tools/who-is-live.sh`, never a
    presence lease.
  - **Staleness is measured, then announced, then substituted.** An actor idle beyond the
    liveness window is substitutable without their consent and without prejudice; say so on
    the board, name the substitute, and proceed. Returning actors resume their seat and
    nothing is held against them.
  - **A recut whose only purpose is to replace a stale role-holder is bookkeeping, not a
    new proposal.** It carries the original's reviews, evidence and gate results forward
    unchanged. It MUST NOT trigger a fresh review round, and treating it as one is how a
    name becomes a week.
  - **Tooling that cannot express substitution is a defect, not a constraint.** File it.
    Until `mote` can transfer a named authorizer in place, recut is the workaround and it
    must be cheap and immediate.

**L9. These rules are also filters, so they expire.** If L1-L8 have not increased
landed non-`.mote` commits within a week, they failed and should be replaced
rather than supplemented. Do not let this section become the thing it was written
against.


## Single-developer mode

**This project has two modes. Read this section before anything below it and know
which one you are in.** *Fleet mode* is everything in **Coordination and
governance**: a chief, named reviewers, the candidate protocol, reservations,
board check-ins. *Single-developer mode* is the owner plus at most one agent, with
no chief and no second reviewer. **As of 2026-09-01 this project is in
single-developer mode.** The owner switches modes; nobody else.

The mode is not a relaxation. Every rule in **Design contract**, **Mechanised
checks**, **Evidence discipline** (§4) and **Style** applies unchanged — those
never depended on the fleet. What changes is who authorises a landing and what
stands in for a reviewer.

### Why this mode exists

The fleet stopped on 2026-09-01 when it ran out of budget. The salvage measured
what it left behind, and the numbers indict the coordination layer, not the work:

- **170 candidates proposed, 12 landed.** Of the 61 left pending, **4** carried a
  block on the merits. 24 had approvals and no block. **33 had never been reviewed
  by anyone.**
- The story-output bundle was **approved six minutes after it was proposed** and
  never landed.
- `provider-parser` — 4,638 lines — had **two approvals and chief authorization**,
  and `main` had no `provider-parser` directory at all.
- Both were recovered as **dangling commits** that no branch pointed at.
- The largest single piece of work in the salvage, the C1 film-source contract
  (2,283 lines), was sitting **uncommitted in the primary worktree**.
- **103 terminal candidate rows emitted 3,308 blocking reasons** — 79% of all
  blocker output — burying the 38 rows a reader could act on.

Nothing there was a disagreement about code. It was a queue nobody could read and
a landing nobody was named to perform.

### SD1. A mechanism replaces the reviewer, and confidence does not

§4 says *an author's own gate is LocallyObserved, not verified.* That rule assumed
a second party existed. Here one does not, so **the second party is replaced by a
control that can fail, never by the author's belief.** Any claim that would have
needed a reviewer of record needs a runnable falsifier instead.

Mutation is the default and it is cheap. Measured 2026-09-01 on the salvaged
`bd50c89f` cost-overflow fix, which no one had ever reviewed: deleting the
aggregate-representability guard failed two named tests ("non-representable
aggregate weights were admitted", "invalid weights reached GraphHsmm probability
work"); deleting the stable reassociation failed a third ("representable
partial-support cost became Infinity"). **Two minutes of mutation settled what
review never got to,** and settled it harder — an approval says someone read it, a
dead mutant says the test can fail.

A claim with no mutation behind it is a claim you have not tested, whatever the
gate says.

### SD2. Land on green. There is no approval to wait for

No candidate rows, no authorization, no lander ladder, no `pending landable`.
**`sbt checkAll` green on the merge result is the authority to land**, and the
author lands their own work. "I will not land my own work" is a fleet-mode
sentence; in this mode it is how a branch rots.

`tools/premerge-check.sh` is a fleet-mode instrument. Four of its eight checks —
candidate phase and authorization (3), live reservations (4), board re-read (6),
ungated-landing scan (7) — have nothing to measure here. **Its two substantive
checks survive and are mandatory:** a clean merge-result tree over the exact
touched paths, and a gate log carrying **bound TEST TOTALS with command
receipts**. A run with no test totals did not run (§4).

### SD3. Uncommitted is lost, and unreferenced is invisible

In fleet mode a board row remembered your work. **Nothing remembers it here.** The
2026-09-01 salvage recovered its single largest piece from an uncommitted working
tree and two of its best commits from the dangling-object graph, and it found them
only because someone went looking.

- Commit the moment it compiles, on a branch, even mid-thought. A WIP commit is
  free; reconstructing 2,283 lines is not.
- **A commit no ref points at is invisible to every tool you would use to find
  it.** `git log`, `git branch --contains` and any survey built on them will all
  miss it. Sweep `git fsck --dangling` before concluding work is gone — the
  2026-09-01 survey did not, and consequently merged the wrong tip of the output
  bundle, nine commits behind the one that had been approved.
- Never let a `/tmp` scratch clone be the only copy of a branch.

### SD4. The board is a notebook, not a protocol

Keep `.mote/` if it helps you think. **Do not run the protocol against yourself:**
no check-ins, no claims, no reservations, no presence, no candidate rows, no
authorization. There is no path contention with one worker, and a reservation you
grant yourself measures nothing. §5 and §7 are suspended.

The audit trail is **git**. A commit message carries what a board row would have:
what changed, the evidence, and what the evidence does not establish.

### SD5. ADRs still gate vocabulary; you decide them

No new module, dependency, or public vocabulary without an ADR line — unchanged.
What changes is that you write it and you decide it. **The requirement was always
the written record, not the approval.** Record the alternative you rejected, in
the ADR, on the day; there is no dissenting reviewer to record it for you.

### SD6. Read your own work cold, in a separate pass

The one thing the fleet reliably supplied was a reader with no memory of writing
it. Substitute deliberately: review in a **separate pass with a fresh-context
agent**, or after enough delay to have forgotten your intent. **Never approve in
the same breath as authoring.**

But know what this buys and what it does not. Re-reading catches prose and shape;
it does not catch a wrong number or a wrong object. Two from 2026-09-01, both
caught by measurement and neither catchable by reading:
merging `e0ede3e7` when the approved tip was `84b6ce8d`, caught only by comparing
candidate `commit_oid`s against `main`; and reporting an 18 s uncovered media tail
computed on the repaired notebook axis when the raw axis gives 11 s — a mistake
`timebase-repair.json` had already written a non-equivalence against. **Both times
the record held the answer and the reader did not go and get it.**

### SD7. The failure mode here is different, so watch a different thing

L1-L8 were written against a fleet deadlocking into zero throughput. One developer
does not deadlock. **The single-developer failures are scope creep, an unlanded
branch aging against a moving `main`, and a claim that was never falsified.**

L3, L5 and L10 concern blocks, threads and substitute authority between actors;
they are inert here. L1, L2, L4 and L9 re-point: if a week passes with no landed
non-`.mote` commit, **the obstacle is the plan, not the queue.** L7 and L8 apply
unchanged and matter more, not less.

### SD8. Leaving this mode

When a fleet restarts, fleet mode resumes for new work and **this mode's landings
are not retroactively defective.** Close their audit rows with
`mote candidate reconcile --operator-override`, which records that formal review
did not govern the landing — that is the honest entry, and it is the one the
salvage used.


## Coordination and governance

> *(Archived 2026-09-23: the operating mode is now multi-agent via Fray; see `AGENTS.md`.)*
>
> **Fleet mode only. This whole section is suspended as of 2026-09-01** — see
> **Single-developer mode** above, which says which of these rules survive and
> what replaces the rest. Items 5 and 7 are suspended outright; items 1 and 3
> have no one to address; item 2 survives with the owner deciding; items 4 and 6
> apply unchanged in both modes.

Several agents (Claude and Codex sessions) work in this checkout and its
worktrees at once. Coordination runs on the package-local mote board
(`.mote/`; `mote board`, `mote in-flight`, `mote discuss unread`). Governance
was set by the owner on 2026-08-28 (sticky decision
`post-01M14Y4D3QE7PKRDXTTT1H0PKM` on topic `coordination`):

1. **Chief architect.** The single accountable coordinator. The 2026-08-28
   owner decision named `claude-storymodel4s`; the operating chief since
   2026-08-31 is `codex-storymodel4s-chief`. **This discrepancy is recorded,
   not resolved — the owner reassigns, nobody else.** Address rulings to the
   operating chief and say which one you mean. The role It assigns, scopes (exact paths + test gate), prioritizes, and
   closes beads, and holds ADR authority. Claim only beads assigned or
   explicitly offered to you; propose new work as a bead on `coordination`.
   Closing a bead needs the assignee's evidence post (tests + SHA) and the
   chief's ack.

   *`presence` is a lease, not a heartbeat.* `live` means an actor renewed a
   session lease; `expired` means a TTL elapsed. NEITHER MEASURES WHETHER ANYONE
   IS WORKING. Before choosing a reviewer, reassigning work, or concluding that
   someone is gone, read `last=` and their recent ops — not the presence word.
   Measured 2026-08-30, twice in one day and in both directions: an actor was
   named as a substitute reviewer on the strength of `presence=live` while being
   the LEAST recently active of the candidates (3h41m silent against another's
   2h); and `claude-storymodel4s-m1` was written off as "not coming back" on
   `presence=expired` thirty-five seconds after their last op, having merely let
   a short lease lapse — they returned and cleared three rows. The failure runs
   in both directions and the correction is the same: **a status word about a
   lease is not evidence about a person.** An `orphaned` reservation is the same
   error in a different field — it means the work behind the hold resolved, not
   that the holder is absent, so ask them to release rather than reaching past
   it.
2. **Design.** ADRs (`docs/adr/`) are drafted by the chief or a named
   delegate and reviewed by dispositions on the board; the chief decides,
   dissent stays on the record. No new module, dependency, or public
   vocabulary without an ADR line or an explicit ok on the board.
3. **Merge gate.** *Subject to L1-L4: this gate exists to stop bad landings, not
   to stop landings. A candidate that is `pending landable` with no blockers has
   already passed it and needs a hand, not another opinion — see L2 for the
   ladder when the proposer cannot land their own work.*
   Nothing lands on `main` — including worktree merges by any
   **The chief must not close a bead holding another actor's live reservations.**
   `mote done` closes and releases together, but the release half is owner-only —
   so it is unavailable to the chief on someone else's holds. `mote close` is
   available and releases nothing. `mote unreserve` refuses outright. **The only
   close available to a chief is the one that orphans**, which makes this a
   structural failure mode rather than carelessness: landing a candidate and
   closing its bead in one tidy motion is exactly how it happens. Before closing
   another actor's bead, run `who-has` on its paths; if anything is held, ask the
   holder to `mote done` it instead. (Recorded 2026-08-29 after the chief orphaned
   two reservations this way, twenty minutes after advising a different agent to
   prefer `done` for precisely this reason, and blocked a third party who could not
   clear them either.)

   **Mint your own child bead under a scoped parent.** If the chief has posted a
   scope in public — slices A–E of a sweep, say — and you volunteer for one, create
   the child bead yourself, claim it, reserve, and start. Do not wait for the chief
   to mint it. The scoping was the decision; minting afterwards is clerical and
   serialising it makes the chief a bottleneck on a step that carries no judgement.
   (Added 2026-08-29 after an agent sat idle thirty minutes waiting for a bead on a
   slice that had already been scoped in a board post.) **What stays with the chief
   is the merge gate itself** — the merged-tree run, the captured exit status, the
   branch check, the landing — not because the chief runs sbt better, but because
   the gate's value is concentrated in the checks that are *about* someone else's
   work: the tree recompute that catches `main` moving under a candidate, the
   mutation court that turns "these probes look weak" into "these probes are live",
   the narrowing that separates bookkeeping churn from a real conflict. Those are
   cheap for a disinterested party and awkward for an author.

   **Verify the branch before you merge.** `git rev-parse --abbrev-ref HEAD` must
   read `main` before any `git merge` of a candidate. Nothing warns you otherwise:
   `git merge` succeeds identically on the wrong branch, and the only symptom is
   that `main` does not move. Measured 2026-08-29: a candidate merge landed on
   another agent's slice branch, which someone had checked out in the shared
   checkout, minutes before they gated it — the gate would have PASSED and carried
   106 unattributed lines into their candidate. Caught only because the landing
   routine prints `main`'s SHA afterwards and it had not changed. **Print the thing
   you are trying to change, not the command's report of itself.** Repairing such a
   mistake: check the working tree FIRST (another agent's uncommitted reserved work
   may be in it), switch **unforced** so git refuses rather than clobbers, and move
   the branch pointer back with `git branch -f <branch> <their tip>` — never
   `reset --hard`. Corollary: branch switches in the shared checkout change HEAD for
   everyone, so use a worktree.
   session — without a posted candidate SHA, scoped-gate evidence (see *Build
   and test*: `scalafmtCheckAll`, `compileAll`, and the touched modules' tests
   on every platform they cross-build to), and the chief's ack in the thread.
   The chief gates the merged tree and runs the full court before a push. `codex-storymodel-release` performs the mechanical push to
   GitHub. Commits are narrow: only the paths reserved under your bead.

   *Ancestry.* Before merging, verify the candidate's parent chain contains
   only landed commits: `git merge-base --is-ancestor <unlanded> <candidate>`
   must exit nonzero for every unlanded candidate. A tree-equality check on the
   touched paths cannot see an unlanded — possibly blocked — commit sitting in
   the parent chain, which is how `cf162a9` was landed and `main` had to be
   reset on 2026-08-29. *Unlanded* means **not an ancestor of `main`**: a commit
   already in `main`'s history is landed by definition and needs no check, even
   if it never passed through a merge (docs committed directly under a live
   reservation, for instance). The rule guards the reverse case — a parent chain
   containing something that is not yet in `main` and might never be.

   *Ledger phase is advisory; git is authoritative.* Before treating any
   candidate as outstanding — to work it, review it, report it as blocked, or
   cite it as a hold — run `git merge-base --is-ancestor <candidate-commit>
   main`. Exit 0 means **the work is landed** and the ledger row is a recording
   artifact, not a hold. This is the mirror of *Ancestry*: that rule asks
   whether a candidate's parents are landed, this one asks whether the candidate
   itself already is. Measured 2026-08-29: four of six candidates carried
   `ancestor_abandoned`, and of the three showing `pending blocked`, **two were
   already on `main`** (`e022c5f`, `7ae2ec9`) — including an audit the chief had
   been citing all evening as a landed result while the board called it blocked.
   A `pending blocked` row cannot distinguish *not yet landed* from *landed,
   recorded late*, so reading the board without this check reports finished work
   as a backlog and gets people assigned to it. `mote board` already prints
   *[advisory: read from git, not from replayed state]* over RECENT COMMITS; the
   same staleness applies to CANDIDATES, where the consequence is larger.
   Corollary for the chief: when a state machine mislabels one candidate it
   mislabels every candidate in that position — **rule on the class, not the
   instance**, and the first question about any reported block is *how many*.

   *Recording a landing is half of landing it, and the unrecorded half rots.*
   The rule above tells a reader how to compensate for a stale row; this one says
   do not create it. `git merge` and `mote candidate landed` are two acts, and
   deferring the second to the end of a tick is how 2026-08-30 produced a queue
   that was 42% bookkeeping: **five of twelve pending candidates were already
   ancestors of `main`**. The cost is not cosmetic. An unrecorded landing keeps
   its author's reservations alive: `cand-7SRW` held `cost.scala`, `Laws.scala`
   and eight other paths under `codex-storymodel-new-engineer` for an hour on
   nothing but a row nobody had written, and the reservation released within
   seconds of the record being made. **Record the landing before you report the
   merge**, not at the end of the batch.

   *A reason string phrased as a measurement may be a cached one.* Landability
   reasons are replayed from recorded evidence, but they are written in the
   vocabulary of live git — `ancestor_ambiguous: base relation is missing; tip
   relation is not_ancestor`. Measured 2026-08-30: that exact string was reported
   for `cand-4X710` while `git merge-base --is-ancestor 7ae2ec9 main` exited 0
   and the commit sat in `main` at `795fe12`. The ledger asserted a false git
   fact in git's own idiom, which is much harder to disbelieve than a phase
   label is. Never let a reason string stand in for the command; run the command.

   *Evidence is minted in a clone, and a clone cannot see an uncommitted op.*
   The store is tracked in git, so a producer's `.mote` is only as current as
   their last pull. Measured 2026-08-30: 641 ops sat untracked in the shared tree
   while three candidates — all with approving reviews and granted authorization
   — reported `git_evidence_stale` naming a proposal op from `13:23:12Z`. The
   producer minted a fresh receipt at `13:28:38Z`, five minutes later, and it
   still did not cover it, **because a receipt minted after a proposal cannot
   miss it by timing — only by not being able to see it**. Each new proposal then
   invalidated every outstanding receipt, and no producer could mint a covering
   one, so with six live actors the covered set could never close. **Commit
   `.mote` before asking anyone to re-mint.** When a producer reports stale
   evidence they cannot fix, suspect the store's visibility before suspecting
   their receipt — and note the asymmetry: the only actor who can clear it is the
   one holding the shared tree, and nothing in the error says so.

   *Stale bases.* A candidate's gate must have run on a tree where the merge
   cannot surprise us. If the candidate and the upstream commits its base is
   missing touch the **same file**, the author rebases and re-gates — not
   negotiable. If they are in **different modules**, the chief merges locally
   and gates the merged tree himself rather than charging the author a rebase,
   and backs the merge out if it is red.

   *A base is stale only if the delta can INFLUENCE the gate — the test is
   influence, not recency.* Let D be the commits in `main` since the candidate's
   base. The base is **gate-current** when no commit in D touches either a file the
   candidate touches or a build input the candidate's gate compiles (a `.scala` file
   in any gated module, `build.sbt`, `project/`). Commits touching **only** `docs/`,
   `AGENTS.md`, or `.mote/` are **gate-inert by construction** and never invalidate
   a gate. If the test were tip-currency instead, no candidate on an active board
   could ever be simultaneously gated and current: a gate takes minutes, `main`
   moves during those minutes, and the author rebases into the next gate forever.
   Measured 2026-08-29: an eight-minute cross-platform gate finished to find `main`
   two commits ahead, both documentation, one file each — and both authored by the
   two people reviewing that candidate. A rule letting documentation invalidate
   someone else's gate evidence charges authors for the reviewers' commit rate.
   Note the pairing with *Ledger phase is advisory*: there the board reported
   landed work as pending, here a base's recency reports an inert delta as a
   hazard. Both are a status that cannot distinguish *something changed that
   matters* from *something changed*.

   *A conflict-free merge is not a working merge.* Textual non-conflict says
   nothing about whether the types still agree. When two candidates touch the
   same file, run the affected module's suite on the **merged** tree before
   landing — regardless of whether either base is stale. Observed twice on
   `SignatureSuite.scala`: two actors appended to different regions while the
   types underneath moved, `git` reported a clean merge and then a clean rebase,
   and both results failed to compile with the same two errors.

   *Reference scope.* The gate must cover **every module that references the
   changed type**, not only the module that contains it. Find them
   mechanically — grep the type name across `*/src/main/scala` — and name them
   in the evidence post. `compileAll` catches a broken signature; it does not
   catch a law that still compiles and now asserts something different, which
   is what a change to a receipt's identity or canonical ordering does to
   `laws/Laws.scala`. Observed on the population reference-shape candidate:
   author evidence was `alignJVM` only and the chief's gate was `align` on
   three platforms, while `Laws.scala` calls `PopulationAggregate.of` in ten
   places and asserts on the very receipt fields being reshaped — neither of us
   ran `laws`.

   *A stacked candidate has the union of its commits' reference scopes.*
   Computing the scope for the headline commit is not computing it. Take the
   union across **test** sources as well as `main`: `compileAll` does not compile
   tests, so a break living in a test generator is invisible to it. Observed on
   a five-commit stack whose signature changes implied `align` + `embed-bench`
   (gated) while a recall change in the same stack implied eight modules
   including `codec` (not gated) — the author found that break, not the chief's
   gate. Bundling a stack into one gate is right for machine load and invites
   this error; the bundling is not the mistake, failing to take the union is.

   *Sibling seam.* `storyatlas4s` is a sibling repository that builds
   `storymodel4s` from source (`-Dstoryatlas4s.storymodel4s.build`), so it
   inherits every dependency edge added here. Whenever `main` moves in a way
   that touches anything it consumes — a new inter-module `dependsOn`, a change
   to `core`/`view`/`features` — compile and **run its test suite** against
   it. This is **part of the gate, not a follow-up**: for a candidate touching a
   consumed module, the seam check runs *before* the ack, exactly like the test
   run. Landing first and checking after is how the parity landing `04fdf6b`
   shipped a `SelectionPlacement[+Mark]` signature that broke the sibling's app
   module — a break no storymodel4s gate can see, because our tests cannot
   compile a separate repository. The two repositories keep separate
   mote stores (relative reservation paths like `build.sbt` would otherwise be
   ambiguous across them); cross-repo work is coordinated on the
   `narrative-atlas-intaglio` topic with provider/consumer SHAs recorded as
   notes, never as dependency edges between stores.
4. **Evidence discipline** applies in every mode. Its full text moved to
   [docs/EVIDENCE.md](../EVIDENCE.md).

5. **Actors and reservations.** **A grep over a name is not an actor filter — parse
   the field.** Matching `"<actor>"` anywhere in an op file also matches the actor's
   name inside the BODY of messages written *to* them, so chasing someone makes your
   monitor report them as increasingly active. Measured 2026-08-29: an agent's last
   operation was 15:07 while a name-grep reported 15:22, the difference being three
   escalating messages the chief had sent naming that agent — **the more they were
   chased, the more alive they appeared.** This is the second instance of the same
   shape in one day; earlier, an agent was reported silent five times while posting
   under an inherited actor, because the board scan filtered the chief's own name as
   noise. A monitor that hides your own name cannot see someone wearing it; a monitor
   that matches free text counts your own voice as theirs. Read the `actor` key.
   **Check liveness before escalating urgency, not after** — the tone of a third
   chase reads very differently once you learn nobody was there to receive the first.

   One mote actor per session. Set your identity
   explicitly in every session — `export MOTE_ACTOR=<actor>` (and
   `mote session start --as <actor>`) or `--actor` on each call — and never run
   `mote actor set` in the shared checkout: `.mote/local/actor` is shared by
   every session in this directory, so changing it relabels other actors'
   posts. **A DEFAULT IDENTITY THAT IS A REAL ACTOR FORGES THAT ACTOR**, and
   this rule already existed while the hazard sat in the checkout unremoved.
   Measured 2026-08-29: `.mote/local/actor` held `claude-storymodel4s` — the
   CHIEF — so any invocation that missed the override, or any subcommand that
   did not thread it, posted with ruling authority. It published a substantive
   architecture position under the chief's name; the actual author noticed and
   corrected it append-only before the chief finished investigating. The chief
   meanwhile chased PIDs, which prove nothing, because every `mote` call is a
   fresh process. Fixed with `mote actor clear`: identity now resolves to
   *actor identity unresolved* and writes fail CLOSED. The general lesson is not
   about mote. **A rule forbidding the CREATION of a bad state does not remove
   the instance already there** — when you write one, go and check whether the
   state exists right now, because a prohibition is not a repair. Reserve paths with
   `mote begin <bead> --paths …` before editing (`mote preflight` first);
   never edit another actor's live reservation — send a request instead.
   **A DEADLINE THE MECHANISM DOES NOT ENFORCE IS A WISH.** Reservation TTL is
   what actually governs when work can change hands, so a reassignment deadline
   not aligned to a TTL is theatre. Measured 2026-08-29: the chief announced an
   18:30Z reassignment checkpoint on slice C without reading that its
   reservation ran to 19:06:20Z — reassigning on time would have handed the
   successor a bead whose files they could not touch for another thirty-six
   minutes, which is precisely the orphan-block the chief had caused that
   morning and written a rule about. **Read the expiry BEFORE naming the time**,
   and when you publish a deadline, publish the TTL beside it so people plan
   against the mechanism rather than against your intention. This is the second
   form of one error in a single day — the first was a written authority the
   tooling does not implement (the constitution claiming the chief may release
   another actor's reservations; `mote` refuses) — and both are the management
   version of the defect class this file exists to prevent: **a claim published
   without the mechanism that would make it true.**
   **IN A SHARED TREE, `git add <path>` FOLLOWED BY A BARE `git commit` COMMITS
   THE WHOLE INDEX** — including whatever another agent has staged. Use
   `git commit --only <paths>`, or run `git status --short` **with no pathspec**
   and read all of it first. Measured 2026-08-29: the chief added one doc, ran a
   bare commit, and landed NINE FILES — another agent's entire in-flight
   canonicalisation across `core`, `acquire`, `codec` and `document` — under a
   commit message about something else, ungated as its own candidate. It had
   written the hazard down forty minutes earlier and named those exact files.
   The check that failed was `git status --short <my own file>`, which **only
   reports the file you ask about**: it confirms yours is staged and cannot tell
   you what else is. A status query scoped to your own work cannot answer "what
   am I about to take". (It survived only because a later full-main audit
   happened to cover it — that was luck, not process.)
   `build.sbt`, `AGENTS.md`, and `README.md` edits are announced on
   `coordination` before they are made. `docs/adr/*.md` are exempt from
   single-holder reservation for paragraph-disjoint edits: edit only the
   paragraph your bead names, announce it, include the hunk in your candidate;
   the chief resolves textual overlap at merge. Mote syncs commit with an
   explicit pathspec (`git commit -- .mote/ops`), never the whole index, and
   candidates stay unstaged in the shared tree until the chief's ack.
6. **Working together (owner directive, 2026-08-28).** Keep work clean and
   commit when it is safe and feasible — do not let green work sit
   uncommitted in the shared tree. Don't step on toes. Collaborate and
   discuss on the board: post design choices before they harden, read and
   answer each other, disagree with evidence. The goal: the best library in
   history for representing stories and their recall.
7. **Reporting.** Every active agent posts a check-in on `coordination` at
   each bead transition and at least hourly: bead id, state, blockers, next.
   Silence longer than two hours on a claimed bead means the chief reassigns
   it.

## Appendix: pre-refactor intro, Layout, Build and test, and Style (verbatim)

These sections were rewritten in the current `AGENTS.md`. The original text is kept here, so every
line of the pre-refactor file on `main` (`604080fc`) survives verbatim in some file. The only
exception is a T1-T7 draft that never reached `main`; its rules appear, condensed, in
`AGENTS.md`.

# AGENTS.md

storymodel4s is a Scala 3 library for neurosymbolic representation of written
stories, human recall of those stories, and the alignment between the two —
including the Autobiographical Interview as a latent-source alignment problem.
The design record is `NARRATIVE_PROCESS_ALIGNMENT_NOTES.md`; the synthesized
architecture and roadmap are in `docs/plans/`.

## Layout

Flat module directories, `CrossType.Pure`, cross-built JVM / Scala.js / Native.
Package namespace is flat `storymodel4s.<module>`.

| Module      | Package                   | Responsibility |
|-------------|---------------------------|----------------|
| `core`      | `storymodel4s.core`       | Opaque IDs, `TextSpan`/`SpanSet`, source atlas, claims/evidence/credence, provenance, content hashing |
| `proposition` | `storymodel4s.proposition` | **Canonical local semantic contract**: partial, evidence-backed `PropositionChart` (concept with optional frame, numbered/named roles, polarity, reentrancy, embedded propositions, exact alignment, alternatives) |
| `amr-interop` | `storymodel4s.amr`      | Standards-compatible AMR **adapter**: PENMAN syntax, checked AMR graphs, role canonicalization, isomorphism, frame lexicon, conversion to/from `PropositionChart`. Nothing outside this module depends on AMR types |
| `features`  | `storymodel4s.features`   | Aligned feature tracks over the surface axis: typed spaces/targets, estimates with coverage, window plans + declared reducers, derivation recipes (dependency DAG), sidecar refs, boundary evidence, feature-use ledger (anti-circularity). Depends only on `core` |
| `acquire`   | `storymodel4s.acquire`    | Autonomous acquisition protocol: task packets, proposal-only agent results, critic findings, typed patches, `ResolutionState`, stage-cache keys, prompt-package manifests. Pure; providers are JVM-only adapters |
| `story`     | `storymodel4s.story`      | Narrative ontology: entities, situations, contexts, typed relation layers, hierarchy, trajectory, validators, `AlignmentSource` |
| `document`  | `storymodel4s.document`   | Mention graph (disjoint union of charts), exact-coreference quotient, projection to narrative nodes |
| `recall`    | `storymodel4s.recall`     | Recall units, discourse functions, recall relations, recall graph |
| `align`     | `storymodel4s.align`      | Local costs, unbalanced transport, graph-HSMM trajectory inference, recall signature |
| `interview` | `storymodel4s.interview`  | Transcript atlas, detail atoms, memory addresses, target-episode induction, AI-compatible scores, rich profile |
| `embed-core` | `storymodel4s.embed`     | Portable embedding contract (ADR 0001): `ProviderFingerprint` vs per-recipe `EmbeddingSpace`/`GeometryId` with validated `GeometryPair`, `EmbedBatch → BatchResult` with per-item outcomes and `AttemptReceipt`, `ValidatedVector`/`ValidatedDistance`, `SemanticView`, free deterministic baselines (hashed n-gram, TF-IDF), `EmbeddingCache` + keyed `SensitiveDigest`, `RemotePolicy → AuthorizedRemoteRequest`. Depends on `core`, `features`, `acquire`; providers are JVM-only adapters |
| `embed-grakern` | `storymodel4s.embed.grakern` | **JVM-only** structural channel (ADR 0001 §D4c): `PropositionChart` → grakern `LabelledNeighbourhood` by relation reification with explicit source/target incidence; WL subtree + optimal-assignment kernels; `structural.wl.grakern` spaces; `GrakernStructuralDistance` (`d_wl`) with memoized query rows and `ProviderCall` receipts. Consumes grakern by immutable SHA `ProjectRef` (`-Dstorymodel4s.grakern.build=<local checkout>` optionally substitutes a local checkout; without it sbt clones the pin from GitHub, which is what CI does); no grakern/graph4s type crosses into portable modules |
| `codec`     | `storymodel4s.codec`      | Canonical circe JSON codecs |
| `fixtures`  | `storymodel4s.fixtures`   | Hand-authored *War of the Ghosts* model, recall paraphrases, interview example |
| `laws`      | `storymodel4s.laws`       | Published Discipline law suites and ScalaCheck generators |
| `provider-parser` | `storymodel4s.provider.parser` | **JVM-only** AMR parser-provider contract: atlas-bound sentence inputs, receipted JSON transport, `PinnedRuntime`/`RemoteRuntime` readiness, deterministic admission court (token echo, `explicit-index-list/v1` sidecar, strict PENMAN conversion), cache replay, typed failure courts. Model runtimes stay external |
| `provider-agent` | `storymodel4s.provider.agent` | **JVM-only** remote parser adapter (ADR 0008): Anthropic Java SDK behind `ParserTransport`, content-keyed record/replay `Recordings` store, environment court for spend, `ClaudeParseDriver.parse`/`run` (text to charts with a served-from ledger and an `ExtendedBuildReceipt`), `claudeParse` main |
| `media`     | `storymodel4s.media`      | **JVM-only** media acquisition adapter (ADR 0007 §4): exact-byte fixture manifests, ffprobe packet-index ingest yielding `Draft`-authority records; the only module allowed to spawn a process |
| `pipeline`  | `storymodel4s.pipeline`   | **JVM-only** story-build orchestrator (ADR 0009): `storyBuild` main runs text → `provider-agent` charts → `ChartProposalProvider` → `NarrativeCompiler` → `storymodel.json`, `compilation-report.json`, `receipts.json`. Owns I/O, receipt composition, file layout, exit status; no semantics |
| `view`      | `storymodel4s.view`       | Portable semantic view artifacts shared by the Narrative Codex and Narrative Atlas |
| `corpus`    | `storymodel4s.corpus`     | Portable corpus-intake contract (ADR 0018): source coordinates, typed cells, segmentations, segment links, code books, profiles, capabilities, source manifest, typed refusals. Schema and laws only; no I/O, knows no corpus |
| `corpus-intake` | `storymodel4s.corpus.intake` | **JVM-only** corpus readers (ADR 0018): xlsx/zip/tsv readers, byte verifier, receipt and descriptor emission. Owns I/O and file layout, no semantics |
| `embed-onnx` | `storymodel4s.embed.onnx` | **JVM-only** local neural sentence-embedding channel; pinned model and tokenizer checksums define one reviewed geometry; no ONNX/DJL type crosses into portable modules |
| `embed-bench` | `storymodel4s.bench`    | **JVM-only** evaluation harness (ADR 0001 §D7): scores alignments against adjudicated gold from checksum-verified frozen sets, or diagnostic material never labelled calibrated. No portable module depends on it |

## Build and test

- Scala 3.7.4, sbt 1.12.14, sbt-typelevel 0.8.7.
- Candidate authors run a **scoped gate**: `scalafmtCheckAll`, `compileAll`, and
  the test tasks for the modules the change touches, on all platforms those
  modules cross-build to. The full `testAll` court is the **chief's**, run once
  before a push — concurrent full courts on one machine were the cause of
  stalled and killed audits, not any one slow suite. `sbt testJVM` for a fast
  loop. The JVM-only `embed-grakern` project clones grakern's pinned revision
  from its public remote unless `-Dstorymodel4s.grakern.build=/path/to/grakern`
  (or `STORYMODEL4S_GRAKERN_BUILD`) names a local checkout; it is part of
  `compileAll`/`testAll`/`testJVM`.
- Warnings are errors in spirit: keep `-Wunused:all -Wvalue-discard` clean.
- munit + munit-scalacheck at test scope; law suites in `laws` use discipline-munit.
- `Test / parallelExecution := false`.
- **sbt does not build in a linked git worktree.** sbt-git's jgit backend raises
  `NoWorkTreeException` (or `MissingObjectException`) when `.git` is a file
  pointing at the parent repository, which is how `git worktree add` sets things
  up. This has cost several sessions time. Two workarounds, both proven here:
  export the exact commit and build that — `mkdir -p $DIR && git archive <sha> |
  tar -x -C $DIR` — which is also what the chief's gate does so evidence is
  bound to a SHA rather than a dirty tree; or build in a standalone clone. A
  `zz-worktree-local.sbt` shim pinning `git.gitUncommittedChanges := false` and
  friends works for some tasks but is not reliable for all of them.

### Mechanised checks — run these, do not re-derive them by eye

Two rules below are enforced by scripts. Until 2026-08-30 neither script was named
anywhere in this file, so every agent learned the rules as prose and none of them
knew a checker existed. Prose you have to remember to apply is the failure mode
these were written for.

- `bash tools/reference-scope.sh "$(git merge-base main CAND)" CAND` — prints the
  modules your gate must cover, and the exact sbt command. Pass the MERGE BASE,
  and run it in a clone checked out AT the candidate. It exits 3 rather than emit
  an empty gate command, because a tool whose failure mode is a plausible blank is
  a tool that will eventually be believed.
- `bash tools/premerge-check.sh CAND COMMIT GATE_LOG` — the chief's merge gate.
  Seven checks: branch, object reachable here, blockers read in full, no live
  reservation on a changed path, gate log has TOTALS and a captured exit, board
  re-read INSIDE the gate window, and no candidate commit sitting on main's
  first-parent line. It exits nonzero on any doubt, including doubt about whether
  it could check at all.

- `bash tools/nan-polarity.sh [REF]` — sorts every numeric zero-guard in the main
  sources into FAILS-OPEN / FAILS-CLOSED / GUARDED by the NaN polarity criterion in
  rule 7. A survey, not a gate: polarity is syntax and says nothing about whether NaN
  can ARRIVE or what comes OUT when it does. A FAILS-OPEN line is a site to read.

- `bash tools/who-is-live.sh` — who has actually acted recently, derived from the ops
  log. Run it BEFORE assigning work, setting a deadline, or holding a merge open.
  `mote actor list` presence=live is a LEASE, not a heartbeat; measured on 2026-08-30,
  three of four actors reading `live` were gone. It under-reports (an actor running a
  long gate writes no ops), so `stale` means ask, never conclude.

Both encode the same lesson and it generalises: when a rule here has caught a real
break more than once, the next step is not a firmer sentence, it is a script. Add
the check to the script and leave one line here pointing at it.

## Style

- scalafmt 3.10.7, `maxColumn = 100`.
- Scaladoc on every public type with a one-line "why", not just "what".
- Tests: one law/invariant per property; adversarial fixtures for every
  prohibited failure mode in the design record (§27.2, §52.3).
