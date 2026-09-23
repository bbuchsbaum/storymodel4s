# Admissibility / StructuralCoverage seal: author evidence (LocallyObserved)

Ticket bd-01M17ZNXY6AS1CMBQJRH3JMNVX. Branch claude-sm/admissibility-seal, code tip f9c9b04e, base main 577ebfdb.

`mutants.log` is produced by `mutate_adm.py`: seven single-site mutants, each run on alignJVM testOnly over the probe suite and the four affected align suites (67 tests). The tree was clean before and after (CLEAN=0, POST_CLEAN=0).

| mutant | failed / 67 | named failure |
|---|---|---|
| M1 Admissibility back to a case class (clean recompile) | 1 | probe "Admissibility derives a Mirror" |
| M2 StructuralCoverage back to a case class (clean recompile) | 1 | probe "StructuralCoverage derives a Mirror" |
| M3 checked factory guard removed | 3 | factory refusal test, plus two wire checks it now reaches |
| M4 counted miscounts evidence | 5 | Evidence/StructuralReduction coverage equalities |
| M5 faithful hard-wired to true | 5 | derived-field law, gate proofs |
| M6 distortion dropped | 6 | derived-field law, facet gate proofs |
| M7 de-duplication removed | 1 | derived-field law (GateProofSuite dedup assertion) |

After the mutants, a clean baseline passed 67/67. Digests are untouched by construction: every digest renderer (AlignWire.admissibilityEcho, mappingbinding) reads the same accessors, and they return the same values.

## Rerun at 3cc516f4 and the probe controls (after cold review)

- `mutants-3cc516f4.log`: the same 7 mutants after the per-door probe split; clean before and after. M1 and M2 each fail exactly the two doors a case class reopens (Mirror and companion fromProduct); copy, apply and new stay closed even in the mutant, as they should for a bare-private constructor. Baseline 70/70 align plus 25/25 codec.
- The mutant runs chain align before codec, so codec suites did not run under the mutants. `adm-mutant-m3-codec.log` reruns M3 (factory guard removed) against codecJVM AlignCodecSuite alone: the named decode-refusal test fails and 20 sibling tests pass; the baseline is 21/21.
- The lead's BLOCK (Fray #98) is addressed in the next commit: identical-signature local controls for apply, new and counted (AdmissibilityShape, CoverageShape, CoverageShape.counted). Probe 8/8 after a clean recompile.

## Slice gate at 280a9931 (T3) and Native timeout controls

`slice-gate-280a9931.log.gz` (raw sha256 d6a21337a17857bb430bce8b53faaa7da841324d2f926c7b0e87a45d310ebb06). 48 tasks as explicit argv: 20 JVM modules plus JS and Native for 14 cross modules, with tlFatalWarnings. Clean tree. Grakern came from the local override at 8efc5efa, so this is not a pinned pass. Formatting: FMT_EXIT=0.

- The run stopped (GATE_EXIT=1) at alignNative on one timeout. MappingHistoricalSuite "Declared and undeclared twins..." took 31.1s against a 30s limit, with no assertion failure. Everything before that passed: 36 totals, 6197 passed, 5 skipped.
- Remaining Native tasks, run from a clean build:
  - codec, core, corpus, document, embedCore: all passed (`native-followup-1.log`).
  - fixturesNative: WorkspaceJoinSuite "historical results..." timed out at 34.0s.
  - interview, laws, proposition, recall, story, view: all passed (`native-followup-2.log`).
- Controls on base main 577ebfdb from clean Native builds:
  - MappingHistoricalSuite times out at 30.43s (native-followup-2.log).
  - WorkspaceJoinSuite times out at 34.40s (base) vs 34.68s (candidate) (`workspacejoin-control.log`).
  Both timeouts are pre-existing on main and not caused by this change.
- A first control attempt was invalid: stale probe classes left in target/ after the checkout broke the Native link. That attempt is recorded in native-followup-1.log and superseded by the clean-build controls.
