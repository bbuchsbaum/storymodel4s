# Recovery and restart checkpoint — 14 November 2026

Recovery ticket: `bd-01M7E2VPWMJHR1FTGFJ6KQYXNF`, under the unified-path epic
`bd-01M6Y7MWZGP29TNDJ90SG36A45`. The owner authorized preservation, tracker portability,
checkout consolidation, qualification of the producer/consumer pair, and a short restart queue.

## Preserved work

The verified private backup is
`/Users/bbuchsbaum/code/recovery/storymodel4s-20261114`. Its manifest records SHA-256 hashes
for 64 backup files, including both complete Git object stores, Git bundles, both Mote stores,
and every worktree's staged/unstaged patches and untracked-file archive. Both primary Git
object stores pass `git fsck --full --no-reflogs`. All 32 dangling commits found by that sweep
are retained under private recovery refs; [their inventory](dangling-commits.json) preserves
the original SHAs. The recent November snapshots were checked against the landed work.

All six linked worktrees were retired after preservation and integration checks. Five obsolete
worktrees had their current patches and status compared with the original backups; the final
common worktree was retired after its reviewed commits reached primary `main`. [The disposition record](worktrees.json) names each original tip,
backup and retained local `refs/recovery/` reference. Those references are private recovery
material, outside ordinary branch/tag pushes. The staged changes in `main-wt` exactly recreated
the earlier committed tree `1b0c7e79c`; they were preserved as an old snapshot, not landed as new
implementation. The [branch inventory](branches.json) classifies all seventeen pre-recovery
local branches, including the separately named claim-verifier patch already incorporated on
main. The [final consolidation receipt](checkout-consolidation.json) records one remaining
worktree and one local branch, `main`. The two owned standalone gate clones were
[retired after verified private bundles](gate-clone-retirement.json). Research data stays in the original ignored `data/` tree.

## Portable tracker and publication boundary

`mote authority migrate` moved each repository's legacy authority binding into local state.
Authority journals are ignored and removed from Git tracking, with local originals preserved.
The original StoryModel store had 18,337 valid operations, including 991 uncommitted operations.
The initial full-history recovery replica reproduced all issue summaries, the dependency graph,
three detailed issue/note records and 141 candidate records (excluding observation timestamps).
The independent reviewer verified all original operation bytes and backup hashes.

That review found two never-published operations containing quoted case wording with
unestablished publication provenance. The complete original history remains in the private
backup. The public tracker uses the supported CLI to reconstruct the affected issue's current
requirements, nine historical notes and twenty current relationships under
`recovery-evidence-binding-20261023`, replacing `bd-01M5MR1KTXEP898CTN2KE3J219`.
Recovered notes name their original author, time and operation. The
[publication receipt](tracker-publication.json) records the withheld operation hashes and
crosswalk. This is a reconstructed public history, not a claim of identical historical
acceptance ordering. The [independent history comparison](tracker-rejection-review.json)
found exactly 30 accepted-to-rejected changes: twenty relations, nine notes and one tag.
Eleven belong to the replaced issue and nineteen to its children. All nineteen children
preserve their complete current state after the ID substitution, with no dangling current
edges. No claim is made that the withheld wording is participant verbatim.

[Preservation and preliminary checks](preservation.json) distinguish the original private
replica comparison from the publication reconstruction. Atlas's fresh replica also reproduces
its issue list and dependency graph, and passes integrity checks.

## Qualification

The runtime baseline is `9aa0ad3ded5f5f2d426ecd1f6aa80470da0b0c1d`. Its
[hosted CI run](https://github.com/bbuchsbaum/storymodel4s/actions/runs/42940443641) has 80
test-task totals: 17,000 passed, zero failed/errors, and 44 skipped across JVM 17/21, JavaScript
and Native. The full downloaded logs, rather than only the run status, establish those counts.

The [fresh local compilation/test receipt](storymodel-test-gate.json) binds all 56 test-task
totals: **12,087 passed, five skipped, zero failed/errors** on JVM, JavaScript and Native.
The original `checkAll` process was stopped in its final formatter phase (exit 143); its
completed tests were not repeated. An [upstream multi-release JAR defect](formatter-runtime.json)
breaks formatter initialization on Java 22. Both unchanged
[format checks passed separately on Java 25](storymodel-format.json). No successful exit for
the original `checkAll` invocation is claimed. [Input equivalence](gate-input-equivalence.json)
binds the tested runtime to the public recovery candidate.

Python agent-mapping and FilmFestival checks total 262 tests: 260 passed, two skipped, zero
failed, across eleven files. The [six recorded replays](recorded-replay.json) produced
**114 bundles and 19,356 unit rows**, with zero prediction-row differences and all twelve
score reports byte-identical to their saved v4 counterparts. No model calls were made.

The public candidate `87c4b55016f615c9e837e3b85c842bc4d2e0d2ff` also has a fresh
[successful hosted run](https://github.com/bbuchsbaum/storymodel4s/actions/runs/43652097024):
[all eighty test-task totals](candidate-hosted-ci.json) sum to 17,000 passed, zero failed/errors
and 44 skipped across JVM 17/21, JavaScript and Native. Its documentation workflow passed.

Independent review of the preserved Atlas patch exposed an omitted `ProducerChoice` label.
The repaired candidate `20f553a160719917498c330f8104031cb2e97f93` passed review. Its
[compiled mutation](atlas-label-mutation.json) removes only that case: the named categorical
test fails with `MatchError`, while the named numerical control passes in the JUnit report.
The original bytes were restored before the [full consumer gate](atlas-gate.json):
**520 passed, zero failed/errors/skipped** across ten JVM/JavaScript test tasks. Compilation,
app linking and workflow generation passed, followed by both format checks on Java 25. The
consumer resolved the published immutable producer pin without a local source override.
The complete consumer receipt is in Atlas commit `311dea48e89d3718441a805d2b499326eb4132a6`.
Local evidence is not hosted evidence; neither establishes new scientific validity or a
package release.

## Integrated checkouts

Recovery is complete. Source integration `1fe57a9df609dbbd96898c918541afeb9d26ea24` was
published non-force and has [successful hosted CI](https://github.com/bbuchsbaum/storymodel4s/actions/runs/43671844099).
[All eighty task totals](storymodel4s-main-hosted-ci.json) sum to **17,000 passed, zero
failed/errors, and 44 skipped** across JVM 17/21, JavaScript and Native. The
[documentation run](main-docs-ci.json) also passed on that exact SHA.

[StoryModel adoption](storymodel4s-adoption.json) and [Atlas adoption](storyatlas4s-adoption.json)
rebuilt local Mote replicas from tracked operations, reproduced the reviewed full issue lists
and dependency graphs, and passed `mote doctor`. The original complete private stores remain
in the recovery archive. StoryModel now has one worktree and one local branch, `main`.
Atlas's compatibility branch is integrated and retired to a private recovery ref. All eighteen
of Atlas's unrelated unfinished files remain byte-identical; its other historical branches
were preserved outside this cleanup's scope.

Atlas integration `93d0703faec4e04ff2c9370a7943e55a808b4188` has
[successful hosted CI](https://github.com/bbuchsbaum/storyatlas4s/actions/runs/43672015236):
[fifteen task totals](storyatlas4s-main-hosted-ci.json) sum to **806 passed, zero failed/errors
and zero skipped**, across JVM 17/21 and JavaScript. Its final receipt-only closure is
`ef0844bb229a0406d4f2a4e65ec89532c18d99a3`, independently [reviewed](atlas-closeout-review.json)
and pushed with local/tracking/remote parity verified. Atlas still pins the published immutable source
candidate `87c4b55016f615c9e837e3b85c842bc4d2e0d2ff`, whose runtime/build inputs match the
integrated source baseline.

The final recovery closure changes only documentation and Mote records. Under T2 it receives
exact-commit independent review and `git diff --check`; `[skip ci]` avoids repeating unchanged
runtime gates. The hosted receipts bind the integration SHAs above, not a claimed new CI run
on the later receipt-only commits. Both recovery tickets are closed; scientific qualification
and release remain separate acceptance work.

## Restart queue

The next implementation ticket is **`bd-01M7E3G6TFG1YTWM9NTSZ4NRKX`**:
Sherlock and a third synthetic study through the common recorded pipeline, one reader, every
prediction preserved, and zero model calls. Its acceptance explicitly resolves legacy versus
new record digests before implementation. See the
[common-pipeline design](../../../plans/2026-11-10-common-recorded-pipeline-design.md).

The old abandoned `doing` ticket `bd-01M5RAN674YW0QGQFM7RY66WXM` moved to review: its source
landed, but ancestry alone did not establish all integration acceptance. The
[queue triage](queue-triage.json) retains mapping A–H and S1 in review where exact integration
or packaged-byte acceptance needs reconciliation. Parents were not closed over incomplete
children. The open common-pipeline follow-up ticket retains its unresolved missingness,
clock, readback and output-safety findings; the time-condition implementation itself is landed.

Use `mote ls --ready --tag resume-next-20261114` for the immediate implementation queue. Rich-event
research, scientific measurement qualification and release obligations retain their separate
acceptance. The next engineering delivery after slice 5 is live execution with recording,
bounded retries, resume and offline replay, as specified in the scientific-preview plan.
