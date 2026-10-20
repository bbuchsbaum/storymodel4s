# Mapping audit implementation — 20 October 2026

Epic `bd-01M5DAE8KHF6VX3RE2V5RJG64T`, tag `mapping-audit-20261020`.
The implementation branch is `codex/mapping-audit-20261020`. No merge or push was performed.
This page records local engineering evidence, not hosted CI, release or scientific acceptance.

| Child | Implemented outcome | Evidence |
|---|---|---|
| A | Every attempt, known/unknown inventory, availability and diagnostics retained | [Summary contract](mapping-summary-20261020.md), `MappingPipelineSuite`, independent readers |
| B | Executed profiles, temperatures, cut, decisions and scientific limits described | Summary/model tests and independent descriptor readback |
| C | Independent all-path weighted interior witness and exact-fraction dispatch | `StructuralWeightSuite`, 343-path oracle and analytic limiting values |
| D | Owner-approved concrete producer/codec/selection contract | [ADR 0019](../adr/0019-mapping-measurement-policy.md), independent design review |
| E | Complete selected cut precedes pricing; checked reconstruction policy and grouping execute | Nonzero-cut, rank/order discrimination, ONNX hierarchical empty-nomination controls |
| F | Checked reference producer, separate authority and contextual new-version codec | Foreign-input, projection-tampering and outside-package construction courts |
| G | Explicit reference-only settings, CLI, attempt accounting, one-member export and readback | [Usage and limits](reference-only-mapping-20261020.md), matched scientific/temporal/organization controls |
| H | Bounded workload, internal phase diagnostics, heap/RSS and parity receipts | [Workload report](mapping-workload-20261020.md), [profile aggregate](mapping-hsmm-profile-20261020.json) |

Reviewed functional candidate: `a11c79726ea817a23c5e4096ee0b7848aa47b71b`.
Independent review resolved all three findings: fabricated unused reconstruction metadata,
unspecified ONNX cuts and mislabeled memory footprint versus RSS. The paired legacy ONNX
exception derives cut equivalence from an exclusively level-zero checked source.

Focused fatal-warning checks passed: 131 pipeline and 14 alignment/boundary tests before the
review corrections; the correction run passed 24 pipeline and 8 alignment tests. Python readback
passed 9 reference, 20 paired and 14 weighted controls. The reference projection comparison retains
policies, processing/localization, mass and normalization, gate/fidelity/support and choices;
execution-derived stage/candidate identifiers are excluded. LocalReference outcomes are compared
separately, including costs and information/diagnostics.

Six named compiling mutations each failed exactly their intended assertion, with all sibling tests
passing; the reviewed source was restored exactly. Full JVM scope passed 4,482 tests, with zero
failures/errors and five opt-in/live skips across 24 modules. Final matched capture exited 0:
36 cells and 108 reference/paired scientific comparisons agree; all legacy publication witnesses
match. JavaScript passed all 3,319 tests across 16 portable modules, with zero failures, errors or
skips. Native also passed all 3,319 tests across the same 16 modules, with zero failures,
errors or skips; final Scala and sbt formatting checks passed. StoryAtlas
`50a160168daa03c51fcab60a4eaaed66674da83a` compiled against that exact source and passed
all 518 consumer tests, with zero failures/errors/skips; final formatting also passed.
[Committed gate receipts](mapping-audit-gates-20261020.json) bind commands, actual exits,
test-summary rows and raw-log hashes to the reviewed source. Full logs and source hashes live in
`.agent-work/mapping-audit-20261020/`. The three Java22
export-profiler commands have reconstructed invocation receipts and no retained numeric exit
status; their complete output, phase invariants and cross-run cryptographic parity were independently
reconciled. They are not labeled exit-zero gate receipts.

The local grakern override is clean revision `8efc5efade4e56c6e0b29745bd1b108d4c9a9d57`,
which differs from the build's default pin. Initial public timing and final facade comparisons use
Java25.0.1; export-only phase/heap diagnostics use Java22. Performance comparisons stay within
matched captures. No optimization, new solver, endpoint continuity, calibrated correctness,
empirical fidelity accuracy or scientific efficacy is claimed. Existing research acceptance issues
retain their own gates and ownership.

The Native gate UTC endpoints span about 62 minutes, while the runner records
1,620.612 seconds (about 27 minutes). These clocks disagree; both are retained, and no
performance conclusion uses this gate. Observed suite durations included PopulationSuite
1,470.168 seconds, MappingHistoricalSuite 343.934 seconds and LawsSuite 554.096 seconds.
A two-second sample of the owned laws process captured `ObjectMeta_ClearBlockAt` in the
Native runtime and a 3 GiB process footprint. These uncontrolled observations are retained in
`native-laws-sample.txt` alongside the gate logs; they do not identify a source regression or
establish a performance budget. No model or test threshold was changed in response.
