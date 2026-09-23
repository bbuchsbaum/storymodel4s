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
