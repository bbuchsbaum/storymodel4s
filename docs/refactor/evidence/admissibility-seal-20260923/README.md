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
