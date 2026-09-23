# Admissibility integration qualification, 23 September 2026

The source landed on primary `main` at
`b01c3d09dcb4711b3d1c6abc9a15ba822fda7a9b`. All 51 required task receipts are
accounted for: **8,303 passed, 5 skipped, 0 failed, 0 errors**. The final platform
run and both formatting checks exited zero. The tested source candidate is
`27033f4725c99c7146219697db1ed73280af66a8`; `merge-equivalence.json` proves the
merge adds only docs and Mote records to that tree.

This integrates `claude-sm/admissibility-seal@9f72ce15` with landed sampled-frame
S1. `Admissibility` and `StructuralCoverage` use checked construction without
case-class forgery doors. Codec admission uses the same checked count contract.
No inference algorithm, numeric threshold, or default test timeout changes.

## Checks and source identity

`admissibility-actual-reference-scope.log` requires 21 modules: 15 portable
modules on JVM, JavaScript and Native, plus six JVM-only modules (51 tasks).
`check_qualification.py` joins one successful receipt per required task and
refuses missing, duplicated, failed or incomplete coverage. It checks each raw
log against the expected digest in `receipt-bindings.json`, which records its
source revision and reuse reason. Those source bindings are the lead's recorded
inputs, reviewed independently; the script does not reconstruct historical
checkouts. Its reproducible output is `qualification.json`. This is composed
scoped evidence, not a new full `checkAll`.

The original `280a9931` gate is retained in the sibling
[author evidence](../admissibility-seal-20260923/README.md). Only its completed,
unaffected tasks are reusable. The two grakern consumers were rerun with the
pinned dependency; media was rerun with S1; changed mapping/fixture tests were
rerun; the omitted AMR adapter tasks were added. The author's abbreviated Native
follow-up summaries are retained historically but are not selected receipts.

`pinned-jvm-qualification.json` records the exact clean source and dependency
SHAs. All new gates use fatal warnings and the pinned grakern checkout
`0329c43c88a0b71e9aa4456723bb16bac2fa3841`, with isolated pinned gale and graph4s
staging. Metadata files preserve argv, cwd, elapsed time and actual exit codes.
Compressed raw logs and their hashes preserve each attempt.

The existing [Atlas consumer](../admissibility-consumer-20260923/README.md) passed
402 tests, strict compilation, formatting and JavaScript linking at Atlas
`39b7f27844674197546610c87cae79102b4c5e73` against Model `c57d0ac7`.
`consumer-equivalence.json` checks that the final consumer-visible main/build
sources differ only by an explanatory comment. The other production changes are
in the JVM media adapter, which Atlas does not consume. No browser or hosted-CI
qualification is claimed here.

## Fixture repairs and falsifiers

The existing 30-second limit remains. Three test files were changed in two
reviewed commits:

- `2981b6b0`: use one checked recall unit for the per-target scope court and the
  contextual workspace-join court. Keep the full source targets, matching
  inventory, exact mass/digest checks, an accepted matching-record control, and
  the semantic perturbation/refusal.
- `27033f47`: use one checked recall unit for in-support structured decode;
  retain the full four-unit result and two distinct units for the foreign-link court, constructing the
  receiving links once and replacing one target link with the foreign-unit link.
  Destination and candidate identity stay equal; derivation unit differs.

Independent `p1_cleanup_review` gave GO for both exact commits, source review GO
for `280a9931`, and GO for S1 `c9522693`. The original seal's seven named mutants
and positive constructor-shape controls remain in its author evidence. New
repair controls are recorded with their exact patches and commands:

| Control | Observed result |
| --- | --- |
| Remove scope binding guards | Named scope-twins test fails; 13 siblings pass. |
| Remove cause from both recall hashes | Survives (20/20); this does not establish either hash's independent necessity, since other contextual checks remain. |
| Neutralize the foreign-recall cause change | Named workspace contextual-refusal assertion fails; 19 siblings pass. |
| Remove foreign-link unit guard | Named cross-unit-link refusal fails; 22 siblings pass. |
| Label an in-support decode as gap fill | Named structured-decode assertion fails; 13 siblings pass. |
| Restore production sources | First repaired suites 34/34; second repaired suites 37/37. |

The surviving cause-hash mutant is deliberately preserved and is not counted as a
killed mutant or a proof that the semantic checks are independent.

## Failed attempts and limits

The author gate used nonpinned grakern `8efc5efa` and failed a Native timeout;
one later Native fixture summary also failed. Its base controls reproduce the
named timeouts. That demonstrates an existing failure, not the stronger causal
claim that the change cannot contribute to runtime.

The lead's first Native run at `2981b6b0` passed 520 and failed two different
mapping tests at the unchanged deadline. A follow-up passed 158 and failed two
WorkspaceVoyage timeout tests. Other broad builds were observed concurrently;
those observations do not by themselves establish causality. After the two
bounded repairs, further fixture changes were stopped and the remaining work was
scheduled sequentially. `quiet-window-snapshot.json` is a point observation, not
continuous isolation proof. The final 17-task platform run passed, including all
522 alignment and 160 fixture Native tests under the unchanged deadline. The resource snapshots do not isolate
contention as the cause of the earlier failures. The AMR JVM sandbox attempt failed before sbt could
open its boot lock; the authorized rerun passed 92 tests.

S1 is independently landed at `eb5463b3`, with its merge equivalence in
`s1-merge-equivalence.json`; its own receipt records 60 passed, 3 skipped and ten
mutants killed. It is closed in Mote. The broader video workflow, API freeze,
Estimate eligibility repair and 1.0 release remain separate open work.
