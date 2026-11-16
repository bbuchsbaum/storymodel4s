# ADR 0030 — Detail account and derived analysis projections

**Status:** Draft — owner review required; not a public API commitment.

**Decider:** the owner

**Tracker:** bd-01M7J5Q5A9GXDK69JMZ2Q0FP7E, coordinated with bd-01M7J5PK4A84NF0W89JKEA6836.
The measurement contract branch is proposal only.

## Context

Detailed analysis needs an account of expressed content, source reference, assessment, and analytic
counting. These are not one construct. Existing interview detail/address/assessment types,
align MeasureKind, and feature support/provenance/missingness are precedents; this draft adds no
runtime type.

## Proposed decision

### Stable account before analytic label

A detail-account record has stable ID, exact recall span, kind, source-reference hypotheses,
evidence/provenance, and explicit assessment state. It is a recall-side proposal, not silently an
interview DetailAtom (which has resolved narrative identities).

Kinds: event occurrence, participant/action, attribute, temporal, spatial, perceptual,
dialogue/reported speech, mental state, relation, external/semantic content, and discourse.
Kinds state what was expressed, not correctness, target membership, inference, or experience.
Implementations must map or explicitly decline to map them to existing interview vocabulary.

| Axis | Question | Must not imply |
|---|---|---|
| Content | What did recall express? | fidelity or phenomenology |
| Fidelity | How does it compare with source evidence? | internal/external or vividness |
| Processing/phenomenology | How was it expressed or experienced? | fidelity or quantity |

Fidelity is an evidence-bearing assessment with not-assessed/not-applicable states, not a kind.
Processing preserves observed basis and never turns a model account into experience.

### Source-reference semantics

References are sparse and use a declared source-unit resolution:

- **single:** one identifiable unit;
- **joint:** several units, each referred to;
- **broad:** interval/episode without separate recall of every constituent;
- **alternative:** competing supported single, joint, or broad hypotheses;
- **unresolved:** no supported hypothesis.

A broad reference is not evidence that every contained unit was recalled. Any quantity declares
candidate universe, conditioning, method, and provenance. MeasureKind already separates raw score,
normalized/transport mass, model posterior, and calibrated probability. A future sampling frequency
records model, prompt, procedure and answer-equivalence rule; it measures instability, not
calibration, and cannot be calibrated probability without a calibration model.

### Cast identity and versioned projection

Source cast identities are source-local. Participant/action/speaker associations cite a source row,
speech span, or frame and retain a receipt. A role label or model proposal supplies no identity
authority; ADRs 0024/0027 retain the identity boundary.

Internal/external is not a kind. A projection records target episode, versioned policy, detail IDs
and current address/assessment inputs, denominator, treatment of discourse/repetition/other
episodes, plus unresolved/excluded material. It is reproducible without extraction or remapping.
Internal representation may evolve behind a versioned external schema; consumers retain projection
version and input-account identity.

### Aggregation, timing, allocation, missingness

Summaries name whether they count items, mentions, atoms, observed mass, duration, or rate and
retain numerator/denominator. Missing, ineligible, unresolved and excluded are not zero. A word
timestamp marks speech, not memory onset. Duration support also needs interval and allocation:
amount-conserving detail integrates to one; level-held rating remains four. Missing timing stays
unavailable.

### Synthetic rescore

| ID | kind | address/processing | fidelity | reference |
|---|---|---|---|---|
| D1 | perceptual | target episode | accurate | single S1 |
| D2 | dialogue | target episode, repetition | detail-wrong | single S1 |
| D3 | external/semantic | general knowledge | not assessed | unresolved |
| D4 | discourse | inference | not applicable | broad S1–S2 |

Policy target-content/v1: D1+D2 are 2 internal; D3 is 1 external; D4 is 1 excluded.
Policy target-novel-content/v2: D1 is 1 internal; D2+D4 are 2 excluded; D3 is 1 external.
Both retain D2 fidelity. Only declared projection changes; extraction, mapping and atom boundaries
do not.

## Rejected alternatives

- **Internal/external as kinds:** policy-dependent labels lose rescorability.
- **Every detail as a source event:** forces identity and delays useful non-event content.
- **Wait for a complete graph:** span/evidence/source-row records already support bounded output.
- **One normalized confidence:** joint/broad meaning and sampling frequency are not one probability.
- **Word timing as memory timing or duplicated atom values:** manufactures precision and multiplies observations.

## Owner review questions

1. Ratify/change/reject kinds and reference meanings.
2. Choose the first external projection version and compatibility promise.
3. Decide public fidelity and sampling-frequency vocabulary.
4. Approve policies only with target, denominator, exclusions, and validation court; no threshold
   is selected here.
