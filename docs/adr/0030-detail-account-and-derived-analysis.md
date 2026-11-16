# ADR 0030 — Detail account and derived analysis projections

**Status:** Accepted direction — owner decision, 2026-11-16. Runtime types and wire format are not yet implemented.

**Tracker:** bd-01M7J5Q5A9GXDK69JMZ2Q0FP7E. The measurement planning contract merged on main at
cb1a0b97a. The owner accepted the separation of content, fidelity and analysis, distinct-claim
span overlap, and conservative supported-only row coverage after discussion in this session.
This replaces the plan's blanket prohibition on atom overlap; the DL1 admission rule now links
this decision. The initial projection specification below remains distinct from the unresolved
public implementation and wire-format choices.

## Account

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
counted as substantive grades. Misread is reviewer error: correct/recode or exclude the invalid
fidelity judgement only from fidelity and coverage analyses; retain the independently admitted
atom/address unless it is separately adjudicated invalid. External does not replace address or
derived internal/external.

## References and cast

References are single, joint, broad, alternative, unresolved, or typed external destination.
Broad never credits every contained row. Alternatives hold supported single/joint/broad/external
hypotheses; unresolved is never an alternative member. Cast identity is source-local; participant,
action and speaker tags cite source row, speech span or frame receipt. Model proposals/role labels
supply no authority.

Reference status is separate from reference outcome: **attempted** may yield an unresolved outcome;
**not-attempted**, **failed**, and **search-limited** record their reason and never coerce an
unresolved reference. Reference status is distinct from fidelity assessment status.

## Separate projections

**DL4 is source-row coverage/omission derived from DL3**, not internal atom counting. It fixes an
eligible source-row universe and reports each row present, absent, uncertain, or not-assessable.
No automatic credit follows broad/alternative reference, contradicted/invented grade, or missing
evidence. “Absent” means no qualifying evidence in the record, never proven omission.

The initial DL4 policy fixes the eligible source-row universe. A definite single or joint binding
with a supported grade credits **present**. Gist-detail bindings are retained per grade and reported
separately pending owner decision on severity; they receive no default credit. If no qualifying
present binding exists, unavailable required evidence or assessment yields **not-assessable**;
candidate, broad, or alternative-only evidence yields **uncertain**; otherwise an assessable row is
**absent**. Mixed evidence precedence is present > not-assessable > uncertain > absent. Reports
retain per-grade bindings, denominators, and excluded/unavailable rows.

Internal/external is independent DL1-based analysis. The initial
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

Distinct claims may share support. Overlap neither proves duplication nor permits arbitrary
splitting. Preserve claim identities and apply explicit counting rules instead of forcing
disjoint text spans. This policy was accepted by the owner on 2026-11-16.

## Rejected alternatives

- Internal/external as primitive kinds.
- Detail equals source event.
- Finished graph before span/evidence/source-row output.
- One probability for joint/broad/alternative reference.
- Word timing as memory timing.

## Remaining implementation decisions

The accepted direction does not settle Scala type placement across core/features/align, a public
response-frequency MeasureKind, held-level feature allocation, or producer wire compatibility.
Resolve those bounded contracts against ADRs 0019/0022 and the NMP profile before adding public
APIs. Gist-detail coverage credit requires a separate severity rule; it is reported separately
under the initial policy. No human pilot or scientific validation is implied by this decision.

The [synthetic rescore](../design/detail-coding/synthetic-worked-example.md) illustrates two
analyses of one unchanged detail account. It is a policy witness, not a human coding result.
