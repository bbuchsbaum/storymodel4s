# ADR 0014 — Directed causal transition kinds beside the symmetrized one

**Status:** Accepted 2026-09-04, single-developer mode (AGENTS.md SD5)

**Date:** 2026-09-04

**Decider:** the owner's agent, single-developer mode

**Plan:** `docs/plans/2026-09-03-navigation-assessment.md` §1.1, §6 slice 2

## Context

`TransitionFeatures.between` computed one causal feature, `CausalNeighbor`, as the OR of a causal
edge in either direction (`align/hsmm.scala`). Moving from a cause to its effect and moving from an
effect back to one of its causes were therefore one feature with one weight, 0.8 in
`TransitionModel.default`. The story side keeps the direction — `CausalEdge(cause, relation,
effect)`, indexed both ways as `causalOut`/`causalIn` — so the loss was entirely in the projection.

The loss matters for a stated reason. The navigation assessment
(`docs/plans/2026-09-03-navigation-assessment.md` §1.1) reports, from Antony et al. (2024, *JoCN*
36(11):2368), that scene memorability correlates with inbound causal weight and not with outbound;
the paper is not in the tree and the figures are the assessment's, not verified here. Whatever
the size of that asymmetry, a feature that cannot tell inbound from outbound cannot represent it,
let alone test it. On the text lane today the causal layer is
empty, so the feature is inert; the first corpus that supplies a directed graph (seven raters'
44×44 directed matrices) is the reason to have the distinction ready.

Two facts shaped the fix. `TransitionFeatures.score` sums `θ_k φ_k` in `TransitionKind.values`
order, so inserting a case mid-enum could move the last bits of every transition score.
`TransitionModel.apply` returns 0.0 for a kind absent from θ, so a kind the default does not name
contributes nothing by construction rather than by arithmetic coincidence.

## Decisions

### 1. Two directed kinds, appended

```scala
enum TransitionKind:
  case Stay, …, ExternalStay,
    CauseToEffect, EffectToCause
```

`CauseToEffect(s, t) = [Causal s→t]`, `EffectToCause(s, t) = [Causal t→s]`. Appended after every
older case so the summation order of the existing twelve is untouched
(`DirectedCausalSuite` pins the position and the count).

### 2. `CausalNeighbor` stays, unchanged

The symmetrized OR remains, at 0.8 in the default, documented as the feature every landed number
was produced under. It is not deprecated: the default references it, and the build treats
warnings as errors in spirit.

### 3. The default does not name the new kinds

`TransitionModel.default` is unchanged. The new kinds weigh 0.0 through `apply`'s absent-key rule,
so every shipped score is bit-identical: the appended terms add `0.0` at the end of the same sum.
A study that wants direction supplies a `TransitionModel` that zeroes `CausalNeighbor` and weights
the two directions; `RecallOrderControl.scaledConfig` maps over the default's keys and is
unaffected.

### 4. A directed model is not a re-weighting of the symmetrized one

Reciprocal causal edges are legal (`story/validate.scala` refuses only self-edges; there is no
causal acyclicity law). On such a pair the OR fires once while both directed features fire, so
`θ_C2E = θ_E2C = 0.8` reproduces the old score on a one-directional edge and exceeds it by 0.8 on a
reciprocal one. `DirectedCausalSuite` demonstrates both cases. Any study that swaps models must
say which graph it ran on.

## Consequences

- No serialized artifact names a `TransitionKind`; no codec, sidecar or report column changes.
  No fingerprint changes either, but only because θ is fingerprinted nowhere: `ViewFingerprint`
  hashes node fields, adjacency, world order and text length, and the run's provenance string
  carries `priorScale` but not the model. An identity check would not have caught a θ change
  before this slice and does not after it; that gap is ADR 0016's (planned).
  `RecallOrderControl.orderingKinds` is unchanged.
- The declaration order of all fourteen kinds is pinned in full by `DirectedCausalSuite`, so a
  later reordering or mid-enum insertion fails a test rather than moving the last bits of every
  transition score silently.
- The gate for this slice is bit-identity of the shipped score, pinned exactly (not approximately)
  against the twelve older kinds summed in their declared order.
- The circularity the assessment names — a reported "recall followed causal structure" computed
  under `CausalNeighbor -> 0.8` — is untouched here; it belongs to the layer-use ledger and θ
  provenance slice (ADR 0016, planned).

## Rejected

- **Keep `CausalNeighbor` as a "deprecated alias" of the two.** Scala 3 has no enum case that
  aliases two others; `@deprecated` would only warn, at the two sites that must keep referencing
  it (`TransitionModel.default` and `TransitionFeatures.between`), in a build that treats
  warnings as errors in spirit.
- **Replace the OR with the two directed kinds at 0.8 each.** Reproduces the old score only on
  graphs with no reciprocal pair, and silently double-counts elsewhere; every Sherlock number
  would have to be re-derived to know it had not moved.
- **Insert the new kinds beside `CausalNeighbor`.** Changes the floating-point summation order of
  every transition score for a cosmetic grouping.
