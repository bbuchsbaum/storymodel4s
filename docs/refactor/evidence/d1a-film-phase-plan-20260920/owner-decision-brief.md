# Owner decision brief: caption-derived film claim license

This is the durable record of the question presented to the owner for phase-plan
Mote `bd-01M2TAGST4EBFR1HM9Q3EWSYAH` on 20 September 2026. It records a request
for a policy choice, not an answer or an authorization to implement either path.
F0 `bd-01M2YY1Z7W0SGT0RMYGH68T5PG` records any answer; F4
`bd-01M2YY3R25N9XWWAC4EZ3X3BJ9` remains blocked until it does.

## Decision requested

**A — preserve the existing text meaning (recommended).** `SurfaceExplicit`
continues to mean directly stated by source text. A caption/model-derived claim
about a film is `Hypothesized` with complete derivation unless a claim-specific
human adjudication record licenses `HumanAdjudicated`. Parsing success, accepted
resolution, high raw score, or annotation authorship alone do not establish a
claim license.

**B — add a caption-derived explicit license.** Permit caption-derived film
claims to be `SurfaceExplicit`. This changes the term's existing meaning and
requires an ADR amendment plus a checked, public basis that distinguishes original
text, human annotation, and generated caption. F4 must then implement and test
the construction, join, component, provenance, consistency and wire consequences.

## Concrete trigger and disclosures

Consider a caption saying “John opens the door,” successfully parsed and accepted
with exact film support. Its model/recipe/request and sampled-frame receipts, plus
the output-bound proposal identity, show how the caption was produced. They do
not establish that the depicted event happened. The same sentence as a separate
text source and as a film derivative must remain distinguishable.

Under A, the existing `ClaimMeta` span law remains intact: film anchors do not
become canonical text evidence. Under B, anchors still cannot mint the license;
the new basis must be explicit and checked. In either case, caption hulls remain
distinct from their exact sampled-frame inventories, claim-union gaps remain
visible, and text `StatusWeight`/calibrated-credence semantics remain unchanged.
`SurfaceExplicit` and `HumanAdjudicated` have fallback `StatusWeight` 1.0 and
`Hypothesized` has 0.25; calibrated credence overrides that fallback and raw score
does not. These values are neither truth probabilities nor a universal alignment
multiplier.

Under A, text overlap and causal-cue rules remain text-witness rules and are
inapplicable to film inputs. Under B, F4 must define and test a replacement
family-specific basis rule before film claims may be admitted. See the governing
[plan's status section](../../../plans/2026-09-20-film-compiler-phase-plan.md#7-status-decision-for-the-owner)
for the complete implementation court.

## Current state

Selection: **pending owner answer**. No elapsed time, recommendation, plan text,
or ticket filing implies approval. No film status policy has been implemented.
This brief is the decision presentation required by the phase-plan ticket; its
dependent F0 and F4 work stays open until the owner selects A or B.
