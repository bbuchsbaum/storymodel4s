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

| Field | Type on `main` (derived 2026-08-29T16:05Z, commit e7b6498) | State |
|---|---|---|
| `fidelityMass` | `MassRatio` | **migrated** — value `N/A`, support `A/T` |
| `fidelityByFacet` | `Map[Facet, MassRatio]` | **migrated** — per facet, `A_f` is *specified*-verdict mass |
| `compression` | `MassRatio` | **migrated** — ratio-of-sums |
| `semanticFlowCoherence` | `MassRatio` | **migrated** — ratio-of-sums |
| `backwardMass` | `Option[StepMass]` | **migrated** — carries comparable/total steps |
| `worldBackwardMass` | `Option[StepMass]` | **migrated** |
| `specificityMass` | `MassRatio` | **migrated** — *renamed from `specificity`*; published figure moved 0.6103 → 0.6583 |
| `discourseChronology` | `MassRatio` | **migrated** — was `Option[Double]` |
| `worldChronology` | `MassRatio` | **migrated** |
| `causalPreservation` | `MassRatio` | **migrated** |
| `importanceWeightedCoverage` | `ScoreEstimate` | **partial** — can express absence, but carries no coverage. Needs `WeightedCoverage` (see below) |
| `uniformCoverage` | `Double` | not migrated — absence-only defect (zero leaves) |
| `associationMass` | `Double` | not migrated — external term |
| `intrusionMass` | `Double` | not migrated — external term |
| `commentaryMass` | `Double` | not migrated — external term |
| `sourceConsistentInferenceMass` | `Double` | not migrated — external term |
| `uninterpretableMass` | `Double` | not migrated — external term |
| `unrankedMass` | `Double` | not migrated — external term |
| `distortedMass` | `Double` | not migrated — same shape as the external terms |
| `distortedMassByFacet` | `Map[Facet, Double]` | not migrated — **empty map conflates no-rows with true zero** |
| `perUnitLocalizability` | `Map[RecallUnitId, Double]` | not migrated — needs per-unit typed absence |

**Ten migrated, one partial, eleven outstanding.** This table was materially stale before
2026-08-29T16:05Z — it listed `specificity`, `discourseChronology`, `worldChronology` and
`causalPreservation` as unmigrated after all four had landed, and `importanceWeightedCoverage` as a
bare `Double` after it became a `ScoreEstimate`. It has been **re-derived from source**, not
patched, because a status document corrected from memory is how it went stale in the first place.

**The outstanding eleven are FIVE DIFFERENT SHAPES, not one** (classified by
`codex-storymodel4s-scout`):

1. `uniformCoverage` — unconditional leaf proportion; only a zero-leaf absence defect.
2. `importanceWeightedCoverage` — weight-sum value conditioning **and** separate leaf-availability
   coverage. Ruled: needs both carriers; `MassRatio` cannot hold them (three masses, and coverage
   is a count pair). New `WeightedCoverage(estimate, conditioningWeight, coverage)` approved.
3. The six external terms plus `distortedMass` — unconditional mean-per-unit row masses. **Rows may
   be subnormalized, so swapping a count for a summed mass CHANGES the estimand.** Applying
   `specificityMass`'s ratio-of-sums fix here mechanically would be wrong, and wrong in a way that
   produces a plausible number.
4. `distortedMassByFacet` — an empty map conflates *no rows* with *true zero*.
5. `perUnitLocalizability` — needs per-unit typed absence.

**Do not size this backlog as one migration.** It was offered that way once and the offer was
withdrawn.

`externalMass` remains a derived method returning `ExternalMassReport`, holding our own failure
(`unrankedMass`) apart from claims about the participant, with no accessor returning their sum.

**The residual on `fidelity` is closed** (merge `8a03e52`). The bare `Option[Double]` beside the
supported `fidelityMass` let a consumer quote the number without its support — exactly what
`externalMass` prevents by refusing a sum accessor. It was not blocked on; the author closed it
within ten minutes of it being named, and a compile-time assertion pins that the accessor does not
return.

**`specificity` is next and is ruled but not yet built.** Unlike `fidelity` it was not a
mechanical removal: it had no conditioning mass, so choosing one was an estimand decision rather
than an author's guess. Ruled to compression's shape — `N = Σ sourceMass·localizability`,
`A = Σ sourceMass`, `T` = total row mass — on `post-01M16EMSQN1N134AEM3AWTWS44`.

## Why this matters — one measured number

`discourseChronology` on the worked example publishes **0.0468**, resting on **25.6%** of the
route (conditioning mass 0.7676 of a total step mass of 3.0000). `worldChronology` is the same.
The value is not wrong — `fw/(fw+bw)` was always a ratio of sums — what was missing was **T**.
Only *ordered* pairs can be forward or backward at all; a step onto an ancestor, or onto a node
with no position, is **unjudgeable, not disordered**.

So two participants could both report 0.0468 while one rests on 90% of their route and the other
on 5%, and until that field carried its support **nothing in the type could tell them apart**.

*War of the Ghosts* is curated, adjudicated, and deliberately well-formed. If it is at 25.6%, real
interview data will not be better — which means every chronology comparison run on this library so
far has been over an unstated and probably wildly varying denominator. That is the empirical case
for this whole migration, and it is worth more than the argument for it.

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
