# Strict candidate tie policy: evidence

Evidence for the tie-policy slice of mote `bd-01M2TACM78289S4TECE91GT5K2` on branch
`solo/tie-policy`. It was reviewed on Fray #55, #58 and #59, with independent exact-SHA **GO** on
`1c7ece00437b7295528634f55ec5219729a169d8` (Fray #58, seq 595). Everything here is
LocallyObserved: the author ran it. The lead's combined final gate is the landing authority.

## What the slice establishes

- **Isolation measured (S1).** `LocalEvidenceIsolationSuite` checks local evidence content under
  several behavioural perturbations. It is unchanged when discourse and scoring positions are
  permuted, support coordinates are permuted, world order is reversed and every non-hierarchy edge
  is reversed and reweighted, and it is unchanged under a change of storage order.
- **The historical defect.** A bijective rename of target identifiers changes the historical
  generator's nominations. Its per-level cut is `sortBy((distance, ref.key)).take(perLevel)`, so
  ties at the cut are broken by identifier. This is kept as a named expected failure.
- **The strict policy removes it.** The strict `TieComplete` policy makes the same rename law pass.
- **A strict policy with checked construction.** The per-level cut is tie-complete, with exact
  finite equality and dense ranks. The budget is explicit, counts the union of channels, and an
  overflow withholds the level, is recorded, and is never truncated.
  `UniformSemanticScores` counts every scored candidate. Non-finite scores are refused through
  `Either`.
- **Binding.** Strict candidates bind one checked recall and one source snapshot: the legacy
  checksums plus the presence-sensitive supplements, and the unit order. `LocalEvidence` refuses
  any mismatch before pricing.
- **Issuance.** Strict candidates, their per-unit sets and strict provenance have bare `private`
  constructors. They are issued only by checked generation, and a package-attack court proves
  that an `align` subpackage cannot rebuild them.
- **The historical generator.** It is unchanged, and the pre-refactor parity pins still reproduce.

## Gates ([gates.json](gates.json), raw logs in [gate-logs.tar.gz](gate-logs.tar.gz))

Archive SHA-256: `47e0d8b6b5f7baa9aae30f1f2e110c33dc5573375f44a5cfd8dafa90b16a0983`.

| Exact SHA | Scope | Exit | Test tasks | Passed / failed |
|---|---|---|---|---|
| `44752783` | 21-module reference-scope JVM set, then `align` JS/Native/JVM with fatal warnings, then fmt | 0 | 24 | 4569 / 0 |
| `9119e2a3` | the same | 0 | 24 | 4589 / 0 |
| `1c7ece00` | `align` JVM/JS/Native, `codec`, `laws`, with fatal warnings, then fmt | 0 | 5 | 1949 / 0 |

The `1c7ece00` run executed JS and Native without holding the Fray #16 gate slot. That was a
coordination error, acknowledged on Fray #58 (seq 600). The result stands, but the procedure was
wrong. `corpusIntake` ran without fatal warnings: its pre-existing E175 warnings belong to
the strict-gate lane.

## Mutations ([mutations.json](mutations.json), raw logs in [mutation-logs.tar.gz](mutation-logs.tar.gz))

Archive SHA-256: `c01eeaa39ebc3abe3d6bc89ca6277aa89481a2a9ec34c1184faf753acedc9211`.

All 14 mutants were re-run against exact `1c7ece00`, JVM only, and every one was killed by its
named test. `mutations.json` records, for each mutant: the command, the exit status, the failed
tests, the log SHA-256, and the source SHA-256 before mutation, while mutated and after
restoration. The restored hash equals the original in every case. W1-W3 ran after a clean test
compile, because `typeCheckErrors` is a macro.

| Mutant | Killed by |
|---|---|
| T1 historical cut instead of tie completion | tie-completion and overflow tests |
| T2 rank by position | dense-rank test |
| T3 truncate on overflow | overflow and tie-completion tests |
| T4 budget counts semantic only | overflow test |
| T5 uniform flag over kept candidates | uniform-population test (it survived the first test version) |
| T6 non-finite dropped | non-finite refusal test |
| T7 provenance absent from identity | identity-binding test |
| B0 binding guard removed | four refusal witnesses |
| B1 unit check removed | subset-recall witness |
| B2 recall check removed | absent-vs-empty recall witness |
| B3 source check removed | both source witnesses |
| W1-W3 constructors widened to `private[align]` | package-attack court |

The S1 finding was diagnosed before this archive: a reverted tie-complete experiment made the
rename law pass. Only a summary of that diagnosis survives (Fray #50). The committed strict
rename law now demonstrates it executably.

## Not established here

- A registered execution `StageReceipt` binding.
- The strict reference refusal of an overflowed unit, which belongs to the reference ticket.
- Content-only scorer construction (AC3 S2) and registered, replayable channels (AC2).
- The mote bead remains open for those.
