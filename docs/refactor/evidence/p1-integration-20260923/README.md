# P1 integration evidence (2026-09-23)

Status: landed locally on StoryModel `577ebfdb0f9348f235ef75706167ddbbd6684cec`.
The exact tested code is `7469d3d7f49dd04df2d2bc76504fbef1202a703b`.
The complete strict pinned gate passed 8,570 tests, skipped 5, and failed none.
StoryAtlas `ef08425759840b84c5b6c4a5ecb8626bc5401265` passed all 402 consumer tests,
strict compilation, formatting and JavaScript linking. No hosted CI run or browser
smoke is claimed. Earlier sections below retain the failed attempts as history.

The compiler candidate is `10290a125adf600b85cf3fad5535f59f302998ee`, with
ADR-first commit `b9b23952`. Independent cold review (Fray49 seq505) found that
only the corresponding nonempty, entirely abstained provider bundle exempts its
own `NoProposal` gap from blocking derivation. Structural/rejected/upstream
failures retain their checks, and s43's typed coverage and three gaps remain.

`wog-prior-evidence.json` archives and independently recounts the author's focused,
baseline and mutation logs: 31 compiler plus 21 pipeline tests pass; baseline and
reverted-behavior mutant each report one failing compiler test. Those older logs
do not embed their process exit, command or Git binding. Their candidate association
comes from the author's Mote/Fray handoff, and is not upgraded here into a bound
execution receipt. The final integration gate must supply its own command, exact
SHA, dependency identities, complete totals and captured process exit.

## First integration gate

`compiler-checkAll-failed.meta.json` records the actual command and exit 1 for
`d08774acee0b770dfae7b66f8526515b6bc23820`, with Grakern at the repository pin
`0329c43c88a0b71e9aa4456723bb16bac2fa3841`. Both tracked trees remained clean.
The complete compressed log and `compiler-checkAll-failed.json` retain hashes,
27 test-total lines, and 4,496 passes out of 4,497 executed tests.

`alignNative / Test / test` stopped at `MappingHistoricalSuite.adapter captures a
changing view once for binding and all link assessment`: the existing 30-second
test timeout fired (40.193 seconds reported). No assertion failure was reported.
Later test tasks did not run; these partial totals are not a complete `checkAll`
result. The cause of the timeout is not established by this receipt. Diagnosis
and a completed integration gate remain required before landing.

## Bounded snapshot-test repair

`05e0c3627f89a156784a4ee2ce2be1bbb14fe35b` reduces only the snapshot test's
recall fixture to one checked unit. It still requires multiple assessed target
links, exactly one changing-view read, and complete result-digest equality with
a baseline adaptation. Independent review approved those retained guarantees.
`61d12cc2` applies the formatter; no timeout or production validation changed.

`snapshot-fixture-qualification.json` binds the retained control and mutation
logs. The JVM suite passes 14/14. Removing the production snapshot makes exactly
the target test fail while 13 siblings pass; the production file was restored
byte-for-byte, and the formatted restored control passes 14/14. Initial sandbox
boot-lock and formatting refusals are retained separately.

`native-snapshot-tie-qualification.json` binds the later Native control to
`e58983ad6f1cb468f0a52e60886641c1319baaba`, with the same pinned Grakern: 33/33
tests across `MappingHistoricalSuite`, `CandidateTiePolicySuite`, and
`LocalEvidenceIsolationSuite`. A fourth requested suite used the wrong package
name and did not execute; no credit is assigned to it. Its actual
`storymodel4s.probes.StrictCandidatesUnforgeableSuite` remains in the full gate.


## Independent exchange readers in CI

`c64bcb9d` adds the production text-source witness and the independent Python
source/export readers to the generated GitHub workflow, once in its JVM Java 17
cell. Workflow generation and `githubWorkflowCheck` pass; their complete logs and
command metadata are retained here.

`independent-readers-qualification.json` binds local execution at
`fb22215a2216fe5a6573183e656f33d08851585a` with pinned Grakern. The source reader
checks 20,001 actual Scala canonicalization witnesses and three exchange packages,
and refuses both rehashed capability corruptions. The StoryModel reader checks
its lawful example and refuses all 21 rehashed corruptions. The captured process
exit is zero and both tracked trees remain clean. This is local command evidence,
not an executed hosted CI receipt.


`03569d1a` adds the synthetic scene-frame planner tests to the same generated CI
cell. Workflow generation/check passes and the local command passes 15/15. Its
independent review approved the generated/source workflow delta.
`scene-ci-qualification.json` binds those observations and their limits. The
video author's separate archive retains the parser repairs, 15 killed mutants,
and frozen-Sherlock annotation parity; this does not establish media seek
accuracy or the complete video source package.


## Merged gate at 03569d1a: pipeline failure

The merged strict pinned gate completed the portable platform tasks, then failed
13 pipeline assertions across `TextParitySuite`, `WarOfTheGhostsDraftCodexSuite`
and `WarOfTheGhostsDraftAtlasSuite`. Those expectations still describe the
compiler's previous non-promotable WOG result and its three required-derivation
violations. The accepted compiler change preserves typed abstention gaps while
allowing promotion, so the author must reconcile the S0 fingerprint/view delta
and preserve genuine negative refusal/view coverage. The log and exact failures
are retained in `merged-checkAll-03569d1a-failed.json`. Later corpus-intake and
final formatting tasks did not run. This is not a complete passing gate.

The verification order was inefficient: the full affected JVM pipeline court
should precede another complete cross-platform run. That narrower court and cold
review are the next landing prerequisites.

The separately executed remaining `corpusIntake/test`, `scalafmtCheckAll` and
`scalafmtSbtCheck` tasks pass at the same clean `03569d1a` revision.
`missing-tail-03569d1a.json` binds the command receipt and actual test totals.
This narrows the known landing blocker to the pipeline failures; it does not
turn the failed full gate into a passing one.

## Pipeline repair and final gate

`7469d3d7` reconciles the accepted abstention behavior with the pipeline witnesses.
The original S0 golden is unchanged. A three-hash overlay records the validation
fingerprint, its derivation binding and the rendered promotion/laws. An executable
control recovers all three historical hashes from the previous validation result.
A checked missing-proposal fixture still refuses promotion with three errors and
the same model; both views display those laws and refuse a crossed receipt.

`pipeline-repair-qualification.json` records independent cold-review GO and the
complete affected JVM court: 120/120 pass with fatal warnings and formatting.
The first test-only encoder compilation error is retained alongside the repaired
run. The final strict pinned `checkAll` subsequently passed on this clean committed tree.


## Final receipts and consumer seam

`final-checkAll-qualification.json` binds 56 task summaries: 8,575 total,
8,570 passed, 5 skipped, zero failures or errors. `final-checkAll.meta.json`
records the exact command and exit 0; the compressed full log is retained.
Pinned dependencies and unchanged tracked trees are checked before and after.
`merge-equivalence.json` lists every delta from tested code to the landed merge;
all executable inputs are identical, including the non-comment lines of
`tools/reference-scope.sh`.

The first Atlas consumer attempt failed strict test compilation because
`PlateCraftSuite` did not traverse `DeviceElement.Annotated`. The failed log and
metadata are retained as `atlas-seam-failed.*`. Independently approved
`ef08425759840b84c5b6c4a5ecb8626bc5401265` adds recursion through those children,
retaining the enclosing group name and all leader geometry/count assertions.
The complete rerun passed 402/402 across 10 test tasks, followed by both formatting
checks and `app/fastLinkJS`. `atlas-seam-qualification.json` and
`atlas-seam.meta.json` bind the results, exact Atlas/StoryModel/Intaglio/Grakern
inputs and exit 0. `atlas-seam-preflight.json` records the initial attempt's inputs;
the successful receipt names the repaired Atlas SHA explicitly.
