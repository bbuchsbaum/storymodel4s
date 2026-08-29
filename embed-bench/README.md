# embed-bench

JVM-only evaluation harness for the M1 embeddings spike (spec rev 2, ADR 0001 §D7).
It runs the proven alignment pipeline (`CandidateGenerator → GraphHsmm.infer → HsmmResult`)
once per *channel* (a semantic distance plus a structural distance, each with its provider
fingerprint and geometry ids) over a set of *cases* (story, recall, adjudicated gold), and
scores the posterior against the gold.

## What a report may be called

`BenchReport` is `Diagnostic` or `Calibrated`. `Calibrated` has a private constructor and
exists only through `BenchReport.label`, which requires every case to come from **one**
`FrozenSet` verified by `FrozenSet.verify` (every listed file present and matching its
manifest checksum, set id carrying the manifest suffix, every story partitioned) whose
recorded `protocolChecksum` equals the adjudication protocol document at the candidate under
test (`ProtocolDocument.pinned`; a test recomputes it from `docs/plans/`). Any other input
yields `Diagnostic` with a typed `DiagnosticReason` — `DiagnosticOrigin`, `MixedSets`,
`ProtocolDrift(recorded, current)`, `ProtocolVersion`, `NoCases` — never a silent downgrade.

*The War of the Ghosts* is wired as `Origin.Diagnostic` cases only (`WogDiagnostic`): one
single-unit case per recall paraphrase and one full-recall trajectory. It is regression
material for the metric code paths; it never selects a default and never calibrates.

## What is deliberately absent

There is **no `Defaults` API** in this slice. Choosing a tier default needs the
untouched-test partition of a frozen, adjudicated, audited set (spec pass/fail rules), and
none is frozen. Offering the API first would invite benchmark-tuned numbers into `align`
under a calibrated name.

Loading a frozen set from disk needs the codec (`manifest.json`, `model.json`, transcripts);
the codec is not a dependency of this module. `FrozenManifest` is the typed value the codec
will produce, and `FrozenSet.verify` owns the FREEZE rule over it.

## Metrics

Every number is a `MetricValue`: an `Estimate[Double]` (never a bare `Double`), the
`Coverage` of the units it was computed over, the number of stories that contributed, a
seeded percentile-bootstrap interval over story-macro means when more than one story
contributed, and a receipt naming the inputs' checksums. Source-anchor metrics (strict
recall@k at the declared level, ancestor credit reported separately, MRR over anchored
states, level-exact, summary accuracy, blend coverage, candidate burden, false gating,
distortion detection and conditional facet correctness, cost-term coverage) and open-world
metrics (external-over-source rule, external subtype, source-consistent-inference mass) are
reported in separate lists and never pooled. Each case also yields a three-clock panel from
the recall signature (discourse and world chronology, compression, backward mass).

A channel whose structural side has nothing to work on (a view without proposition charts)
reports `StructuralIdentity.Absent(reason)` and a structural-term coverage of 0 rather than a
score.

## Gate

`embedBench/test` (metric arithmetic on hand-checkable cases, determinism under a seed,
FREEZE-rule rejections, label discipline, protocol pin, WOG end-to-end through the hashed
n-gram and TF-IDF baselines with grakern `d_wl` absent, and a no-story-text canary over the
rendered report), plus `compileAll` and `scalafmtCheckAll`, at the clean grakern pin.
