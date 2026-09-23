# Offline text-source qualification, 2026-09-23

Code: `b52b26a0b3e02de62cde695511de5c4ad170cc1d`, branch
`solo/offline-text-source`, based on `604080fc`. Mote:
`bd-01M35PB1H5PDD55YVKR4TQ8M66`. Author: codex-temporal. Independent reviewer:
claude-sm-0923 (Fray 62/67). Integration and final gate: codex-p1-lead.

The bounded slice supplies checked offline text targets, the existing
`TextNarrativeAtlas`/`SourceBundle` seam, a flat alignment view, JSON/TSV exchange,
CLI and independent Python reader. It also repairs a measured JVM/JavaScript
canonical-source identity divergence. It does not close ordinary-file mapping
facade/prepare consumption or establish scientific mapping accuracy.

## Retained evidence

[receipts.tar.gz](receipts.tar.gz) has SHA-256
`e6dd3286d8b82edcacc5782c6f963f071e12157f83e3ba2827eefaa597a11b9e`.
Its `index.json` binds 141 payloads by size and SHA-256, including source snapshots,
raw logs, command/exit sidecars, source/dependency bindings, synthetic production
outputs, mutation results and the independent review archive. All examples are
synthetic. No participant or narrative media is included.

| Run | Source and result | Scope and limitation |
| --- | --- | --- |
| `cross-platform-11` | Exact clean `b52b26a0`; exit 0; 604.54 s | 2,449 tests pass; compileAll and formatting pass. Core JVM/JS/Native 250 each, pipeline 81, align JS/Native 493 each, codec JS/Native 316 each. Source and Grakern tracked trees clean before/after. |
| `cross-platform-09` | Exact clean `ee076a47`; exit 1; 191.62 s | compileAll and 18 JVM modules complete: 2,840 pass, five skips. Then coreJS reports 248 pass/one failure: the new Unicode-anchor witness exposes pre-existing platform-dependent canonicalization. Later tasks did not run. |
| `anchor-10` | Working patch subsequently committed as `b52b26a0`; exit 0 | 43 source/atlas/splitter and six CLI tests pass. Generates three exchanges and 20,001 cases from actual `StorySource.canonicalize`. This is a precommit run; run11 supplies the exact committed binding. |
| `review-07`, `external-probes-08` | Changes subsequently committed as `ee076a47`; exit 0 | Reader repairs, external construction probes and nonlexical-fragment semantics. These precede the absolute-anchor/platform repair. |
| `jvm-restored-05` | Earlier restored source implementation | 1,137 affected-module JVM tests pass; retained as development evidence, not final-tip coverage. |

The final scoped gate uses exact clean Grakern
`0329c43c88a0b71e9aa4456723bb16bac2fa3841` through its disclosed local override.
Run11 retains two build warnings: deprecated local clang 15 and multiple discovered
main classes. It is not the final merged fatal-warnings `checkAll` court.

The canonicalizer change expands the mechanical reference scope from 18 to
20 JVM modules, adding acquire and provider-parser. Both checker outputs are
retained. At Fray16/659 the lead explicitly owns one final exact merged strict
`checkAll` covering all 20 modules and portable platforms; the author did not
repeat that full coverage immediately before the lead's run. That integrated
receipt must be consulted for merge/release acceptance.

## Independent reader and falsifiers

The production CLI study at `5a0b0f97` exercises eight real JVM processes: generated,
supplied and help succeed; overwrite, malformed UTF-8, BOM-only, foreign-atlas and
bad arguments refuse with exit 2. Two independent table reads pass; rebound fake
IDs and clock capabilities are refused. Its receipt records that historical
producer and reader, rather than relabeling it as final-tip evidence.

The final reader court consumes fresh production witnesses (BOM/combining mark/
supplementary code point, Unicode line terminators, supplied atlas), compares
20,001 production Scala normalization outputs, and refuses two rehashed extra
capability fields. The source classes' private construction/copy/product/mirror
boundaries are probed from outside the core package with compiling controls.

Five Scala mutations were killed: removing strict UTF-8 admission, allowing
surrogate-bisecting spans, bypassing reconstructed-wire comparison, restoring the
caller-ID prefix overflow, and removing metadata/ID Unicode admission. Final
reader mutations also fail: disabling exact object keys admits a rehashed
capability, and restoring the Python dollar anchor disagrees at case zero.
Historical insufficient anchor repairs and failed development compilations remain
in the archive.

## Reciprocal review

The reviewer supplied a lawful counterexample and differential tests, leading to
the final absolute-end Python anchor and explicit portable Scala trim. Their
verified archive is nested at `peer-normalization/review-receipt.tar.gz`, SHA-256
`4ceab8b4c8ef074d582cdd1009225c9671017ce9cccaf033b572533ab43385fa`.
The reviewer ran 300,000 broader seeded cases: compiled new Scala versus the old
JVM regex chain, then Python versus compiled Scala, with zero mismatches in each
comparison. Their receipt/harness and 16-test JVM log are retained; their local
Grakern override differs from the author's pinned gate. They did not claim JS or
Native coverage; run11 provides that confirmation.

The same pairing found and repaired a distinct StoryModel-export typed-ID-family
collision. That review belongs to the exporter issue, not this intake acceptance.

## Reproduction

See [the workflow](../../../workflows/offline-text-source.md) for the CLI and
production-witness/Python commands. The exact sbt argv, working directory, exit
status and timing are in each raw-log metadata file. Extract the archive with
standard tar tools, then verify `index.json` before relying on individual payloads.
The Python reader checks joins, coordinates and identity; it does not independently
reimplement Scala sentence segmentation. Nonlexical surface targets remain
explicitly allowed and are not counts of semantic units.
