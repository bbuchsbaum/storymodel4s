# ADR 0003 migration status

ADR 0003 states that the `RecallSignature` metrics are "migration sites, not grandfathered
exceptions". It does not say which are migrated. This file does, so that the gap is visible
rather than assumed closed because the ADR is frequently cited.

**Audited 2026-08-29; re-audited 09:35Z after that night's landings.** Re-audit before claiming
the contract holds — this file went stale within an hour of being written, which is the normal
case for a status file and the reason it carries a timestamp rather than a verdict.

## What "migrated" means here

A field is migrated when an absent measurement is **typed as absent** — `Estimate.Missing` with a
reason, or a carrier holding value *and* support — rather than represented as a bare number, or as
an `Option` that a consumer must defensively unwrap.

`Option[Double]` is **not** partial credit. It distinguishes absent from present and nothing else:
it cannot say whether a value is *ineligible* (the question does not apply) or *missing* (it
applies and could not be answered), it carries no coverage, and any aggregate that drops `None`
silently renormalizes. Every `Option[Double]` below is a site where a consumer can, and on `main`
does, substitute a constant.

## `RecallSignature` — 22 numeric fields

**Re-audited 2026-08-29 09:35Z after the night's landings.** Six fields now carry support, up
from none.

| Field | Type on `main` | State |
|---|---|---|
| `fidelityMass` | `MassRatio` | **migrated** — value `N/A`, support `A/T` |
| `fidelityByFacet` | `Map[Facet, MassRatio]` | **migrated** — per facet, `A_f` is *specified*-verdict mass |
| `compression` | `MassRatio` | **migrated** — ratio-of-sums |
| `semanticFlowCoherence` | `MassRatio` | **migrated** — ratio-of-sums |
| `backwardMass` | `Option[StepMass]` | **migrated** — carries comparable/total steps |
| `worldBackwardMass` | `Option[StepMass]` | **migrated** |
| `fidelity` | `Option[Double]` | projection of `fidelityMass`; **residual clause-2 leak** |
| `discourseChronology` | `Option[Double]` | improved (was bare `Double`), not migrated |
| `specificity` | `Option[Double]` | not migrated |
| `worldChronology` | `Option[Double]` | not migrated |
| `causalPreservation` | `Option[Double]` | not migrated |
| `uniformCoverage` | `Double` | not migrated |
| `importanceWeightedCoverage` | `Double` | not migrated — and see `bd-01M16C9HT9V9Q80F7411V87BBY` |
| `associationMass` | `Double` | not migrated |
| `intrusionMass` | `Double` | not migrated |
| `commentaryMass` | `Double` | not migrated |
| `sourceConsistentInferenceMass` | `Double` | not migrated |
| `uninterpretableMass` | `Double` | not migrated |
| `unrankedMass` | `Double` | not migrated |
| `distortedMass` | `Double` | not migrated |
| `distortedMassByFacet` | `Map[Facet, Double]` | not migrated |
| `perUnitLocalizability` | `Map[RecallUnitId, Double]` | not migrated |

`externalMass` remains a derived method returning `ExternalMassReport`, holding our own failure
(`unrankedMass`) apart from claims about the participant, with no accessor returning their sum.

**The residual on `fidelity`.** A bare `Option[Double]` beside the supported `fidelityMass` lets a
consumer quote the number without its support — exactly what `externalMass` prevents by refusing a
sum accessor. It was *not* blocked on, because the number it now carries is correct and blocking a
strict improvement for incompleteness is an error already made once tonight. It goes when the
remaining `Option[Double]` fields migrate.

## Consumers that substitute a constant

These are the live consequence of the table above — the point where an untyped absence becomes a
published number.

| Site | What it does | State |
|---|---|---|
| `align/signature.scala` `SignatureProjection.apply` | `getOrElse(0.0)` on five components, and `comps.getOrElse(k, 0.0)` for an unrecognised weight key | fix unblocked, in m1's stack |
| `interview/induce.scala:478` | classifier catch-all yields `Estimate.observed(0.0)`; the scrutinee defaults unclassified units to `Uninterpretable` | `bd-01M16BPV38CMQ74963BCGCAPGN` (P1) |
| `interview/induce.scala:536` | `getOrElse(d.id, Estimate.observed(0.0))` for a detail whose source unit is absent from the graph | same bead |
| `align/source.scala:109` | `importance: ScoreEstimate = Estimate.observed(1.0)` as a **default parameter** | open question, below |

## The `importance` default — recorded as an open question, resolved as a defect

This section first read "open question, defensible other side, pending a ruling". Checking whether
anything actually injects importance made it decidable. It is
`bd-01M16C9HT9V9Q80F7411V87BBY` (P1). *An open question that can be closed by checking is not an
open question; it is unfinished work.*

`NodeSummary.importance` defaults to `Estimate.observed(1.0)`. The **only** production
construction site is `bridge/StorySourceView.scala:98`, which passes positional arguments through
`lemmas` and then jumps to `evidence =` by name — it never passes `importance`. The sole other
writer in the repository is a laws generator. So every node in every real run carries
`observed(1.0)`.

The arithmetic then collapses (`signature.scala:91-96`): `uniform` is
`sum(visitation)/leaves.size`; `weighted` is `sum(w·visitation)/wsum`; with every `w = 1.0`,
`wsum = leaves.size` and `weighted` reduces to exactly `uniform`. **`uniformCoverage` and
`importanceWeightedCoverage` are identical by construction in every production run.** A
researcher comparing them finds them always equal and may conclude importance weighting does not
affect coverage — a finding about participants caused by a wiring gap in our pipeline.

A second, independent defect survives even if the wiring is fixed: `if wsum <= 0 then uniform`
fills the weighted figure with the uniform figure when every importance is `Missing`. The comment
one line above has the right instinct — Missing importance is excluded, never counted as zero —
and then the all-Missing case substitutes a different measurement instead of abstaining.

## The pattern is mostly right elsewhere

Stated because a list of defects misrepresents the codebase. Sweeping every
`Estimate.observed(<literal>)` in production sources: the great majority sit in the **correct**
shape — `if eligible.isEmpty then Missing else observed(computed)` — for example
`align/population.scala:136`, `interview/scoring/Scoring.scala:483` and `:501`. The defects above
are the outliers, not the norm.
