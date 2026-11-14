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

Five obsolete linked worktrees were removed after their current patches and status were
compared with those backups. [The disposition record](worktrees.json) names each original tip,
backup and retained local `refs/recovery/` reference. Those references are private recovery
material, outside ordinary branch/tag pushes. The staged changes in `main-wt` exactly recreated
the earlier committed tree `1b0c7e79c`; they were preserved as an old snapshot, not landed as new
implementation. Research data stays in the original ignored `data/` tree.

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
acceptance ordering. No claim is made that the withheld wording is participant verbatim.

[Preservation and preliminary checks](preservation.json) distinguish the original private
replica comparison from the publication reconstruction. Atlas's fresh replica also reproduces
its issue list and dependency graph, and passes integrity checks.

## Qualification

The runtime baseline is `9aa0ad3ded5f5f2d426ecd1f6aa80470da0b0c1d`. Its
[hosted CI run](https://github.com/bbuchsbaum/storymodel4s/actions/runs/42940443641) has 80
test-task totals: 17,000 passed, zero failed/errors, and 44 skipped across JVM 17/21, JavaScript
and Native. The full downloaded logs, rather than only the run status, establish those counts.

Fresh local gate, recorded-replay, exact revision and independent-review receipts are recorded
here as they complete. Python agent-mapping and FilmFestival checks currently total 262 tests:
260 passed, two skipped, zero failed, across eleven files. Local evidence is not hosted evidence;
neither establishes new scientific validity or a package release.

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

Use `mote ready --tag resume-next-20261114` for the immediate implementation queue. Rich-event
research, scientific measurement qualification and release obligations retain their separate
acceptance. The next engineering delivery after slice 5 is live execution with recording,
bounded retries, resume and offline replay, as specified in the scientific-preview plan.
