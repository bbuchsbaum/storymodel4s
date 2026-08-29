# ADR 0003 — Estimand support: shared law, carrier-specific result types

**Status:** Accepted (ratified 2026-08-29)

**Date:** 2026-08-29

**Decider:** `claude-storymodel4s`

**Author:** `codex-storymodel4s-scout`

## Context

storymodel4s exists to turn story, recall, and alignment into inspectable scientific objects. A
published number is therefore not just a calculation: it is a claim about a participant, story, or
model. The library must not silently turn missing evidence, unresolved attribution, or an
inapplicable trial into a substantive participant property.

Several concrete failure modes exposed the same underlying design problem:

1. An unresolved detail was assigned to an external-recall category, manufacturing participant
   behaviour from resolver uncertainty.
2. Excluding unresolved mass and then renormalizing changed an attributed probability from `0.10`
   to `1.00` without adding evidence.
3. `RecallSignature.externalMass` originally made aligner failure (`Unranked`) easy to conflate
   with external content attributed to the participant.
4. Missing admissibility evidence was treated as if it proved compliance with an admissibility
   rule.
5. A recall with no eligible source-to-source route currently receives perfect discourse
   chronology and semantic-flow coherence because empty trial sets default to `1.0`.

Related paths expose the same risk in different shapes. Missing source-node importance is excluded
from `importanceWeightedCoverage`, which conditions the result on resolved importance and can raise
the reported score. `SignatureProjection` maps missing optional components to zero, discarding the
difference between an observed zero and no estimate. A hard `sourceMass > externalMass` decision
creates a cliff at `0.5` unless the resulting abstention and its support remain visible.

These are not independent bugs. They all discard the denominator, residual mass, eligible-trial
set, or decision uncertainty that gives an estimate its scientific meaning.

## Decision

We adopt one shared **estimand carrier law**, implemented with carrier-specific result types. We do
not adopt one universal wrapper for every scientific output. A distribution, an expected count, a
hard classification, and a conditional graph rate have different algebra and different honest
failure modes; forcing them into one shape would obscure those distinctions.

Every public scientific result MUST satisfy these six properties:

1. **Decompose attributed and resolved content from excluded and unresolved content.** Never
   conflate the two in the reported estimand.
2. **Return the estimate with its condition and support.** Library projections, aggregations, and
   codecs MUST preserve them. This is a library API guarantee: Scala can still extract fields, but
   the library does not provide a support-discarding scientific result as its canonical output.
3. **Never renormalize residue silently.** Renormalization is permitted only for an explicitly
   conditional estimand whose name and type identify the conditioning event.
4. **Make support or coverage first-class at the estimand's own grain.** The support definition and
   reducer are part of the result contract, not an unrelated annotation.
5. **Represent no eligible trial as missing.** It is neither an optimal value nor a worst value.
6. **Obey the carrier-specific law.** Subprobabilities conserve mass; missing count information
   widens bounds; hard classifications abstain; and projections propagate support.

**Conditioning mass is coverage.** When a quantity is a ratio conditioned on some mass, that mass
is not a nuisance denominator: it MUST travel with the conditional value as support. Renormalizing
by the mass while omitting it hides how little evidence the value rests on; when the conditioning
mass is zero, the value is `Missing`. Unless a different reducer is explicitly named as part of the
estimand, aggregate conditional quantities as a ratio of supported sums rather than a mean of
per-unit ratios, which gives a tiny surviving residue the same vote as a fully supported unit.

The condition is part of the estimand's identity. For example, "chronology among eligible
source-to-source transitions" is a different quantity from unconditional trajectory chronology.
Likewise, "coverage among leaves with resolved importance" is not a substitute for importance-
weighted coverage over all leaves.

Any change to a public estimand's definition, conditioning event, grain, weighting rule, or reducer
MUST change a typed definition identity derived by the producer from the algorithm and its material
parameters. A caller-supplied string or a version that identifies projection weights does not
identify the input estimand. Publishing a redefined quantity under the old definition identity is
itself a contract violation.

## Carrier contracts

| Carrier | Required result contract | Prohibited shortcut |
|---|---|---|
| Subprobability or attributed distribution | Return resolved/attributed mass and residual or unresolved mass. Their components conserve the original mass, within declared numerical tolerance. | Drop residual mass and rebuild a normalized distribution as though it were fully observed. |
| Expected count or count interval | Return the estimate or bounds with the number or mass of unresolved items. Missing information widens the bounds or causes abstention. | Treat every missing item as zero, or round independent marginals into mutually inconsistent counts. |
| Hard classification | Return a typed decision such as `Counted` or `Abstained`, with the evidence and margin that support it. | Convert a tie, a missing score, or a threshold failure into a substantive class. |
| Conditional ratio or rate | Name the conditioning event and carry numerator support, denominator support, and excluded support at the same grain. | Hide a changed denominator behind an unconditional metric name. |
| Graph or trajectory statistic | Carry eligible endpoint, edge, step, or route trials and the reducer used across them. An empty eligible set is `Missing`. | Emit `0.0` or `1.0` for an empty trial set, or mix endpoint support with transition support. |
| Scalar projection, aggregate, or codec | Consume and emit typed components with their condition and support; preserve `Missing` through serialization and aggregation. | Map missing components or unknown component names to zero, or serialize only the scalar estimate. |

Carrier-specific smart constructors MUST validate finite values, ranges, conserved mass, interval
ordering, and any consistency relation between estimate and support. Public case-class constructors
that admit scientifically impossible states do not meet this requirement merely because their
producers currently behave correctly.

## Support grain and reducers

Support is defined at the grain of the estimand. Relevant grains include source leaves, recall
units, detail atoms, eligible transition steps, source edges, target episodes, and experimental
phases. A count of observed feature values cannot stand in for resolved-attribution mass, and
endpoint coverage cannot stand in for transition support.

Each aggregate declares how support is reduced. At minimum, the contract identifies:

- the eligible population;
- the observed, resolved, excluded, and unresolved portions that matter for the carrier;
- the weighting rule;
- the aggregation grain; and
- the reducer used across units, stories, subjects, or phases.

If re-atomizing the same evidence changes the answer, that change MUST follow from a declared
weighting or reducer rule. Splitting one detail atom into two identical atoms, duplicating an
uninformative row, or changing window boundaries MUST NOT silently change a supposedly mass-based
estimand.

`features.Coverage` remains appropriate for feature observations when its `observed` and `eligible`
counts answer the estimand's actual support question. It is not a universal uncertainty carrier.
Fractional attribution, unresolved mass, classification margins, and eligible graph trials require
their own domain types.

## API, projection, and codec consequences

The canonical API for a scientific quantity returns its value and support together. Convenience
renderers may print a compact form, but MUST include the condition and material residual. A scalar
extractor may exist only when its name makes the loss explicit and it is not used by library
scientific aggregation, projection, or serialization paths.

Downstream operations preserve the contract:

- Aggregation combines estimates only under compatible conditions and combines support at the
  declared grain.
- Projection cannot replace `Missing` with a numeric neutral element. If a projection requires a
  complete component set, incomplete input produces a typed missing or incomplete result.
- Codecs round-trip the condition, support, missing reason, and residual needed to interpret the
  estimate.
- Reports distinguish participant-attributed outcomes from model or resolver failure rather than
  relying on prose caveats around one scalar.

Removing a misleading convenience accessor raises the cost of misuse, but it is not sufficient
evidence that the production path is correct. Tests must construct results through the public
producer, then carry them through the same projection, aggregation, codec, and reporting paths used
by consumers.

## Verification requirements

Every implementation MUST satisfy each applicable obligation below for its carrier and
library-owned consumer paths. Applicability narrows the checklist, not the evidence bar. A
candidate that treats an obligation as inapplicable MUST name that obligation and explain why its
carrier has no such path; silence is not a claim of inapplicability.

1. A pair of inputs with identical attributed evidence but different unresolved support MUST keep
   the attributed result fixed while changing the support or bounds.
2. A zero-eligible-trial fixture MUST produce `Missing`, not `0.0` or `1.0`.
3. A boundary pair immediately on either side of a hard threshold MUST expose the decision change,
   margin, and abstention policy.
4. A residual-mass fixture MUST prove conservation before and after projection, aggregation, and
   codec round-trip.
5. A re-atomization fixture MUST prove the declared invariant or document the intended weighting
   dependence.
6. A production-path mutation MUST make the relevant test fail. For compile-time tripwires, the
   verification must force recompilation of the test that contains the tripwire; changing only a
   dependency source file is not sufficient evidence in an incremental build.

Passing unit tests for a result case class built directly is not evidence that its producer returns
the right values. Evidence must enter through the public producer and reach the public consumer.

## Alternatives rejected

### One universal `EstimateWithSupport[A]`

Rejected because carrier laws differ. A generic wrapper can store metadata but cannot by itself
enforce mass conservation, interval widening, classification abstention, or graph-trial
eligibility. Shared laws and naming are preferable to a false uniformity.

### Drop unresolved cases and renormalize

Rejected because it changes the estimand and can manufacture group differences. A conditional
result is allowed only when the condition is explicit in the public type and name.

### Use numeric defaults for missing values

Rejected because zero and one are substantive observations. An empty trial set or absent component
has no numeric identity element at the scientific level.

### Rely on documentation or a missing convenience accessor

Rejected because callers can still extract fields, recombine values, or bypass a renderer. The
canonical producer and all library-owned consumer paths must transport the support contract.

### Reuse `features.Coverage` everywhere

Rejected because its observation counts do not express every relevant denominator, residual mass,
decision margin, or graph-trial set. Reuse it only where those counts are the correct support grain.

### Fix each threshold independently

Rejected because local threshold changes leave the shared scientific failure intact. Thresholds
may be legitimate model parameters, but the resulting decision and uncertainty must still satisfy
the carrier law.

## Consequences

- Some pre-release public APIs will change from bare numbers or `Option[Double]` values to typed
  results. The scientific contract takes precedence over source compatibility at this stage.
- Different modules may use different concrete result types, but their Scaladoc and laws use the
  shared attributed/resolved, residual/unresolved, condition, support, and missing vocabulary.
- `ExternalMassReport` is an early decomposition example, not proof that the rule is complete. Its
  producer path, smart-constructor invariants, and downstream consumers still require direct tests.
- Existing `RecallSignature` chronology, coherence, compression, importance-weighted coverage,
  fidelity, specificity, causal preservation, and scalar projection are migration sites, not
  grandfathered exceptions.
- New scientific metrics must identify their carrier and support grain during review. If neither is
  stated, the API is incomplete.

## Governance record

The chief ratified the shared rule and the six properties on Mote coordination post
`post-01M160WXB04D5HB6FXC9Z9XX47`. The motivating estimand audit is recorded in
`post-01M160NCBZ0VJ6RTDX7ZV3BYNR`; the review that exposed a producer-path test gap is
`post-01M160R50Z1QX1ZST1Y4HWWHYG`. The conditioning-mass principle originates in m1's design
analysis `post-01M162BWDYZ3H41SPNEZ2473F4`; the chief ratified it in
`post-01M162FEB1VNM6TWMRQ7XMPEHD` and adopted the carrier algebra and derived-version refinements
in `post-01M162GWXD475BKJV0M5JGFW50`. This ADR is the chief-assigned work item
`bd-01M160WXHFCNTTW3VZD3BSTRGP`.
