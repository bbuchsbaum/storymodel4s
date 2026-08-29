# ADR 0003 migration status

ADR 0003 states that the `RecallSignature` metrics are "migration sites, not grandfathered
exceptions". It does not say which are migrated. This file does, so that the gap is visible
rather than assumed closed because the ADR is frequently cited.

**Audited 2026-08-29 against `main`.** Re-audit before claiming the contract holds.

## What "migrated" means here

A field is migrated when an absent measurement is **typed as absent** — `Estimate.Missing` with a
reason, or a carrier holding value *and* support — rather than represented as a bare number, or as
an `Option` that a consumer must defensively unwrap.

`Option[Double]` is **not** partial credit. It distinguishes absent from present and nothing else:
it cannot say whether a value is *ineligible* (the question does not apply) or *missing* (it
applies and could not be answered), it carries no coverage, and any aggregate that drops `None`
silently renormalizes. Every `Option[Double]` below is a site where a consumer can, and on `main`
does, substitute a constant.

## `RecallSignature` — 20 numeric fields

| Field | Type on `main` | State | Tracked by |
|---|---|---|---|
| `uniformCoverage` | `Double` | not migrated | — |
| `importanceWeightedCoverage` | `Double` | not migrated | — |
| `fidelity` | `Option[Double]` | redefinition specified | `bd-01M162FEGPSY50MFHTYH3C3RHF` |
| `specificity` | `Option[Double]` | not migrated | — |
| `compression` | `Double` | redefinition specified | `bd-01M162FEGPSY50MFHTYH3C3RHF` |
| `discourseChronology` | `Double` | not migrated | — |
| `worldChronology` | `Option[Double]` | not migrated | — |
| `causalPreservation` | `Option[Double]` | not migrated | — |
| `semanticFlowCoherence` | `Double` | redefinition specified | `bd-01M162FEGPSY50MFHTYH3C3RHF` |
| `associationMass` | `Double` | not migrated | — |
| `intrusionMass` | `Double` | not migrated | — |
| `commentaryMass` | `Double` | not migrated | — |
| `sourceConsistentInferenceMass` | `Double` | not migrated | — |
| `uninterpretableMass` | `Double` | not migrated | — |
| `unrankedMass` | `Double` | not migrated | — |
| `distortedMass` | `Double` | not migrated | — |
| `distortedMassByFacet` | `Map[Facet, Double]` | not migrated | — |
| `backwardMass` | `Double` | `StepMass` in flight | m1 stack |
| `worldBackwardMass` | `Option[Double]` | `StepMass` in flight | m1 stack |
| `perUnitLocalizability` | `Map[RecallUnitId, Double]` | not migrated | — |

**Migrated on `main`: none.** The one correct construct on the type is `externalMass`, a *derived
method* returning `ExternalMassReport`, which holds our own failure (`unrankedMass`) apart from
claims about the participant and deliberately offers no method returning their sum. Its Scaladoc
argues the case better than ADR 0003 does and is the model for the rest.

## Consumers that substitute a constant

These are the live consequence of the table above — the point where an untyped absence becomes a
published number.

| Site | What it does | State |
|---|---|---|
| `align/signature.scala` `SignatureProjection.apply` | `getOrElse(0.0)` on five components, and `comps.getOrElse(k, 0.0)` for an unrecognised weight key | fix unblocked, in m1's stack |
| `interview/induce.scala:478` | classifier catch-all yields `Estimate.observed(0.0)`; the scrutinee defaults unclassified units to `Uninterpretable` | `bd-01M16BPV38CMQ74963BCGCAPGN` (P1) |
| `interview/induce.scala:536` | `getOrElse(d.id, Estimate.observed(0.0))` for a detail whose source unit is absent from the graph | same bead |
| `align/source.scala:109` | `importance: ScoreEstimate = Estimate.observed(1.0)` as a **default parameter** | open question, below |

## Open question — the `importance` default

`NodeSummary.importance` defaults to `Estimate.observed(1.0)`. The field's own contract says
"`Missing` importance excludes the node from importance-weighted coverage; it is never zero", so
`Missing` is meaningful and available. With the current default, a caller who injects no
importances gets every node at 1.0, and `importanceWeightedCoverage` silently equals
`uniformCoverage` — two published numbers that are secretly the same measurement.

Defaulting to `Missing` instead would make importance-weighted coverage `AllMissing` when no
importance was supplied, which states the truth: we were not given importances, so we cannot
weight by them. This is a design decision with a defensible other side (a uniform prior is a
legitimate modelling choice) and is recorded here rather than filed, pending a ruling.

## The pattern is mostly right elsewhere

Stated because a list of defects misrepresents the codebase. Sweeping every
`Estimate.observed(<literal>)` in production sources: the great majority sit in the **correct**
shape — `if eligible.isEmpty then Missing else observed(computed)` — for example
`align/population.scala:136`, `interview/scoring/Scoring.scala:483` and `:501`. The defects above
are the outliers, not the norm.
