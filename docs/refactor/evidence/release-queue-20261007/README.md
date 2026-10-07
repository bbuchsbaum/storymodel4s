# Release queue reconciliation — 7 October 2026

Owner scope: audit existing tickets, assign release groups, reconcile landed versus unfinished
work, restore context and leave an actionable forward plan. The working guide is
[RELEASE-QUEUE.md](../../RELEASE-QUEUE.md); the authoritative store is Mote, replicated through
Git `.mote/ops`. These files are dated evidence snapshots, not a second tracker or a locking system.

Baseline source: `8d4881a931fb809b27ac7ad14478fe60cde1e6e3`.
Baseline consumer: `90f682d1acddd759867098e90f6e3ef163244b0e`.
Actor: `codex-release-queue-20261007`.
Audit Mote: `bd-01M4B6XZS3DR91PEQ40JV9A3F0`.

## Dispositions

- All 113 previously unfinished Motes receive current context and release/milestone assignments;
  their original bodies and scientific amendments remain verbatim in the revised bodies.
- S2a-4 `bd-01M379NZZ67GZJMWVBT5XNA0ZD` closes against already-landed and independently
  re-audited evidence. See the [acceptance crosswalk](s2a4-closure.md). No runtime work was repeated.
- Stale docs `bd-01M31TQTBMBGEAS9A666B5MPA6` returns `review -> open`, with current module,
  grakern, efficacy and provenance findings and a bounded salvage pointer.
- The membership and privacy tickets recover their missing acceptance from canonical
  September22 notes. The private-input workflow makes typed disclosure lineage 1.0 work.
- The new CI P0 `bd-01M4B896MCEE43Q9QMR4HT60R0` records the current-main Native timeout;
  a successful retry alone does not close it.
- Four parents with closed children remain open: their complete acceptance was not satisfied.
  H5's compiler repair is complete, but its preregistered development pilot is not.
- The existing 24 `release-1.1` assignments remain unchanged. Research, experimental
  interview/signature work, later view requests and optional maintenance have `release-later`,
  with no new release-number commitment.

The final unfinished queue contains **113 tickets: 27 for 1.0, 24 for 1.1, 62 later**. The
audit ticket is separate bookkeeping and closes after the metadata is reviewed and verified.
The snapshots/counts concern this reconciliation baseline; use `mote ls` for subsequent work.

## Evidence files

| File | Meaning |
| --- | --- |
| `before.json` | Canonical baseline bodies, clocks, tags, status and dependency/relationship state of the original 113 unfinished issues. |
| `classification.json` | Per-ID release/milestone decision, remaining action, evidence boundary, original/revised body hashes and status disposition; includes the new CI ticket as an addition. |
| `after.json` | Canonical post-edit state of those tickets and the new CI ticket, including the closed S2a-4. |
| `planned-batch.jsonl` | Actual CLI tag/edge request payload. Scalar body/status changes use `mote set`; the operation receipts preserve their exits. |
| `application.json`, `batch-result.json` | Actual mutation command exits and accepted/rejected batch outcomes. Preflight ownership carries scoped counts and the raw-output digest; full machine actor/session inventory stays local. |
| `validation.json` | Counts, original-body preservation, stable 1.1 scope, dependency checks and the actual format/integrity checks. |
| `native-attempt-1.log.gz`, `main-ci-native-totals.json` | Full retained failed Native job and parsed 15-cell totals. |
| `native-attempt-2.log.gz`, `native-attempt-2-totals.json`, `main-ci-observation.json` | Successful retry: all four jobs green; Native 16 cells / 2822 Passed / 0 Failed / 0 Errors, with exact run/SHA/attempt provenance. |
| `prewrite-review.txt`, `s2a4-closure.md` | Independent classification/acceptance reviews. Exact committed metadata review is recorded separately on the audit Mote. |
| `docs-archive.json`, `docs-archive-handoff.json` | Reviewed and remotely verified preservation branch; original docs ancestry retained, legacy workflows removed, canonical docs-ticket handoff note observed. |
| `preservation.json` | Unrelated source draft and all 1,177 pre-existing consumer paths remain byte-identical. No participant content is included. |
| `verify.py`, `verify-controls.py`, `verification-controls.json` | Reproducible receipt checks and four rehashed corruptions that must fail their named boundary, with passing controls. |
| `SHA256SUMS` | Exact static artifact byte identities; the manifest itself and generated `validation.json` checker output are excluded to avoid self-reference. |

Canonical snapshots were read with `mote show --json`; bodies/status were changed with `mote set`,
tags/edges with `mote batch`, and closure with `mote done`. No custom op-log reducer or second
Mote store was used. All commands set their actor explicitly. No other actor held live claims
or reservations at the preflight; the unrelated source draft remains unchanged.

Ten explicit dependency additions bind privacy to non-public workspace publication, caption
selection/extent to multipart loader and frame correspondence, those children to the video
package, and the CI/courts/header/privacy obligations to the release gate. Existing coarse
G1 dependencies and scientific protocol edges are preserved. The validator checks no added
cycle or unresolved parent and no 1.0 blocker on an active 1.1/later ticket.

Documentation/Mote changes are gate-inert under AGENTS T2. Verification consists of canonical
readback, body/hash/count/graph checks, `mote doctor`, `mote fsck`, full diff whitespace and local
link checks, and separate exact-SHA metadata review. No sbt suite, benchmark, held-out read or
scientific study was run for this metadata slice.

## Current-main CI boundary

[Run37620122197](https://github.com/bbuchsbaum/storymodel4s/actions/runs/37620122197), attempt 1,
source 8d4881a, job 112788512394, `ubuntu-22.04 / 3.7.4 / temurin17 / rootNative`:
the named external-global-winner WorkspaceVoyage test timed out after 30s (reported 30.907s).
Producer setup took 63593ms; suite 166.771s. The fixture cell had 159 Passed / 1 Failed / 0 Errors;
all 15 executed Native cells had 2704 Passed / 1 Failed / 0 Errors. The remaining Native test cell
did not run. JVM17/JVM21/JS were green. Attempt 2 completed successfully: all four matrix jobs
are green, and Native 16 cells passed 2,822 tests with zero failures/errors. Its observed state is
in `main-ci-observation.json` and its full Native log is retained. Preserve both attempts.

The prior exact-code run 37613462330 qualified source a2d795b on all platforms; it does not
substitute for current-head release health. The CI P0 remains the next runtime task even if
the retry succeeds. It calls for measured diagnosis and repair/disposition while retaining
the property, controls, scope and exact-source hosted evidence.

## Handoff

Run the commands in RELEASE-QUEUE, inspect the chosen Mote, claim narrow paths, and finish the
smallest current acceptance. Reuse landed timing/source/exchange/temporal components. Close
against exact landed evidence, including negative/not-applicable study dispositions where
the original protocol allows them; never close an epic merely because children are closed.

GitHub parity and approved `buc-gw01` synchronization are recorded after metadata review.
StoryAtlas code/pin is unchanged by this slice. Private data, credentials, live actor/session
state and local runtime caches are not moved. Other machines may work on synthetic/public
fixtures; private data needs the existing approved-host transfer and corpus-use boundaries.
