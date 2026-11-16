# ADR 0030 — Detail account and derived analysis projections

**Status:** Draft — owner decision required; no public API commitment.

**Tracker:** bd-01M7J5Q5A9GXDK69JMZ2Q0FP7E. The separate Claude measurement-contract proposal is
not accepted; reconcile this Draft with it before accepting either.

## Proposed account

A record has stable ID, exact recall UTF-16 support, content kind, address, source-reference
hypotheses, evidence/provenance, status and fidelity. Kinds include event, participant/action,
attribute, temporal, spatial, perceptual, dialogue, mental state, relation, semantic/general
knowledge, and discourse. Internal/external is never a kind.

Content, address, assessment status, observed discourse, reported phenomenology and fidelity are
orthogonal. Phenomenology requires explicit report or declared measurement; AI/content features do
not measure it. Fidelity never implies target address.

## Mandatory frozen packet

Every coding packet identifies stable film edition, authorized access route, frozen packet ID,
source-row/time evidence and provenance, recall units in canonical UTF-16 coordinates, and packet
version. Unavailable source evidence is recorded, not supplied by inference.

## Fidelity status and nine grades

Status is pending, completed, unavailable, or refused, each with reason. Completed substantive
assessment has exactly one of eight grades: **supported** (adequate source supports claim);
**gist-correct/detail-error(severity)** (central proposition supported, specified error);
**contradicted** (adequate source supports incompatibility); **recaller-inference** (expressed
conclusion rather than source claim); **external** (own knowledge/commentary, not source claim);
**invented** (presented as source content but absent from adequate record); **unverifiable**
(record inadequate); **misread** (reviewer misunderstood expression).

Pending, unavailable and refused carry **not-assessed(reason)**, are not completed, and are not
counted as substantive grades. Misread is reviewer error: correct/recode or exclude it; never
treat it as a recalled fact. External does not replace address or derived internal/external.

## References and cast

References are single, joint, broad, alternative, unresolved, or typed external destination.
Broad never credits every contained row. Alternatives hold supported single/joint/broad/external
hypotheses; unresolved is never an alternative member. Cast identity is source-local; participant,
action and speaker tags cite source row, speech span or frame receipt. Model proposals/role labels
supply no authority.

## Separate projections

**DL4 is source-row coverage/omission derived from DL3**, not internal atom counting. It fixes an
eligible source-row universe and reports each row present, absent, uncertain, or not-assessable.
No automatic credit follows broad/alternative reference, contradicted/invented grade, or missing
evidence. “Absent” means no qualifying evidence in the record, never proven omission.

Internal/external is independent DL1-based analysis. Recommended initial
target-episode-novel-content/v1 classifies address before countability: TargetSpecific is internal;
OtherSpecific, personal and general knowledge are external; discourse and unresolved are reported
separately. A counting view may exclude discourse/repetitions, but those exclusions never change
address. It has its own denominator and does not require fidelity completion.

`Extended` and `RepeatedOrCategoric` do not establish target membership: the initial policy puts
them in unresolved target membership pending a narrower address. The inventory partitions every
atom into internal, external, unresolved, or excluded; countability exclusions (including discourse
and repetitions) are excluded rather than reclassified. Its headline fraction is
internal / (internal + external), reported only with both counts; unresolved and excluded remain
visible. Changing fidelity alone must not change this classification or fraction.

Atom overlap is a **proposed change requiring owner decision** and reconciliation with the separate
measurement contract: distinct claims may share support, but overlap neither proves duplication nor
permits arbitrary splitting.

## Rejected alternatives

- Internal/external as primitive kinds.
- Detail equals source event.
- Finished graph before span/evidence/source-row output.
- One probability for joint/broad/alternative reference.
- Word timing as memory timing.

## Owner decision requested

Choose or revise the status/grade contract, DL4 row policy, initial address projection, overlap
policy, external schema, and reconciliation with the separate measurement-contract proposal.
