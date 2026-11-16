# ADR 0030 — Detail account and derived analysis projections

**Status:** Draft — owner review required; not a public API commitment.

**Decider:** the owner

**Tracker:** bd-01M7J5Q5A9GXDK69JMZ2Q0FP7E; coordinated with bd-01M7J5PK4A84NF0W89JKEA6836.
The measurement-contract branch is proposal only.

## Proposed decision

A stable detail account precedes analytic labels. A record has stable ID, exact recall support,
content kind, source-reference hypotheses, evidence/provenance, memory address, and assessment
fields. It is a recall-side proposal, not silently a resolved interview DetailAtom.

Content kinds are event occurrence, participant/action, attribute, temporal, spatial, perceptual,
dialogue/reported speech, mental state, relation, semantic/general-knowledge content, and discourse.
Internal/external is never a kind: address and policy decide that projection.

| Separate field | Question |
|---|---|
| Content | What did recall express? |
| Fidelity | How does claim compare with source evidence? |
| Assessment status | Pending, completed, unavailable, or refused, with reason? |
| Observed discourse | What function is expressed, with evidence? |
| Reported phenomenology | What did recaller explicitly report, or what declared measure observed? |

No AI output or content feature measures phenomenology. Fidelity is evidence-bearing assessment.

## Fidelity vocabulary and boundaries

Exactly nine grades: (1) supported; (2) gist-correct/detail-error with declared severity; (3)
contradicted; (4) recaller-inference; (5) external; (6) invented; (7) unverifiable; (8) misread;
(9) not-assessed. Only completed assessment has a grade. External does not replace address or
internal/external. Invented requires adequate source record; incomplete evidence is unverifiable.
Misread is assessor error, not recaller evidence.

## References, cast, quantities

Reference meanings are single, joint, broad, alternative, and unresolved. Broad does not mean every
contained unit was recalled. Alternatives contain supported single/joint/broad hypotheses only;
unresolved is not an alternative member. Quantities retain universe, conditioning, method,
provenance and MeasureKind. Sampling frequency records model/prompt/procedure/equivalence and
measures instability, not calibration.

Cast identity is source-local; participant/action/speaker tags cite row, speech span, or frame and
retain receipt. Role labels and model proposals supply no identity authority; ADRs 0024/0027 bind.

## Recommended first projection

Adopt **target-episode-novel-content/v1** for engineering: count one internal atom only if its
address is resolved TargetSpecific episode content, it is non-discourse and non-repetition.
Denominator: all admitted atoms with resolved address and completed assessment. Report numerator,
denominator and kind breakdown; separately report unresolved address, pending/unavailable/refused,
discourse, repetition and exclusions. Other-specific, personal-knowledge and semantic/general-
knowledge atoms are external under this policy. This is not AI or phenomenology validation.

The projection is versioned and reproducible without extraction/remapping; consumers retain policy
version and input-account identity. Summaries preserve item/mention/atom/mass/duration/rate meaning,
numerator/denominator, and missing/ineligible/unresolved/excluded states. Word timing is speech,
not memory onset; duration needs interval and declared conserved-amount versus held-level allocation.

## Synthetic rescore

D1 perceptual target-specific supported single S1; D2 dialogue target-specific repetition
gist-correct/detail-error(minor) single S1; D3 semantic/general-knowledge general-address external
unresolved; D4 discourse inference recaller-inference broad S1–S2. target-content/v0 counts D1+D2;
recommended v1 counts D1 and reports the rest separately. Inputs do not change.

## Rejected alternatives

- Internal/external kinds lose rescorability.
- Every detail as source event forces identity and delays useful content.
- Finished graph first delays span/evidence/source-row output.
- One normalized confidence cannot carry joint/broad meaning or sampling frequency.
- Word timing as memory timing or duplicated atom values manufactures precision.

## Owner decision requested

Adopt or revise the nine grades and target-episode-novel-content/v1; separately select external
schema compatibility and any public sampling-frequency representation.
