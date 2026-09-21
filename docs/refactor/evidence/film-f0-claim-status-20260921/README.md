# Film claim-status decision (F0): 21 September 2026

Mote: `bd-01M2YY1Z7W0SGT0RMYGH68T5PG` (F0). Blocks F4 `bd-01M2YY3R25N9XWWAC4EZ3X3BJ9`.
Governing plan: [film compiler phase plan §7](../../../plans/2026-09-20-film-compiler-phase-plan.md#7-status-decision-for-the-owner),
SHA-256 `005e77efdeaa4e9810c59878acb3eb2a7b9b9d964914515e3bd440024894fe05`.

## The question as presented

The phase-plan ticket `bd-01M2TAGST4EBFR1HM9Q3EWSYAH` put alternatives A and B to the owner in
the [owner decision brief](../d1a-film-phase-plan-20260920/owner-decision-brief.md), committed at
`e52f62f64466df23c636cdfb0ae04c18d09cdee8`, SHA-256
`13a03b010b5abf427d3c12bb7eacb001e21d4276ba19140746fac5a35516d530`. That brief names the concrete
caption trigger and links F4. The brief is left unchanged as the record of the question; this
file records the answer.

On 21 September 2026 the question was carried to the owner again, in this form:

> Film claim status (F0). Trigger case: a generated caption "John opens the door", parsed and
> accepted with exact film support. Can generated captions license film claims as
> SurfaceExplicit?
>
> **A: keep text meaning (recommended).** SurfaceExplicit still means "stated by source text".
> Caption-derived film claims are Hypothesized (StatusWeight 0.25) with full derivation;
> HumanAdjudicated needs a claim-specific adjudication record. No ADR change; ClaimMeta span law
> stays intact; text overlap/causal-cue rules declared inapplicable to film.
>
> **B: caption-derived license.** Captions may license SurfaceExplicit (weight 1.0). Changes the
> term's meaning: needs an ADR amendment plus a checked public basis distinguishing original
> text / human annotation / generated caption, and F4 must add construction, join, component,
> provenance, consistency and wire consequences and a replacement basis rule.

## Answer

**The owner selected A.** Generated captions cannot license film `SurfaceExplicit`.

Provenance: the owner's explicit selection in the Claude Code session of 21 September 2026,
in response to the question quoted above. No elapsed time, plan recommendation or agent
inference stands in for it.

## Consequences F4 implements

These restate the plan's §7 disclosures as they apply to A. F4 cites this record and implements
only A.

- **Meaning.** `SurfaceExplicit` keeps its existing text meaning: directly stated by source text.
  The ADR 0007 license question is settled without amending the term.
- **Construction.** `ClaimMeta`'s span law is unchanged: `SurfaceExplicit` requires text spans,
  and film anchors do not become canonical text evidence. F4 gives every materialized film claim
  family an explicit, selected status rule. It does not reuse the text compiler's
  `SurfaceExplicit` assignments as a film default.
- **Status of caption-derived claims.** A film claim whose only license is caption or model
  conjecture is `Hypothesized`, with its complete derivation retained. `HumanAdjudicated`
  requires an actual adjudication record for that particular emitted claim. Human authorship of
  an annotation alone is not adjudication. Parsing success, accepted resolution and a high raw
  score do not supply a license. Any other status needs its own family-specific basis.
- **Join and provenance.** The derivation still joins the expected source bundle, the
  recipe/model/request, the output-bound caption proposal, its receipt and the sampled-frame
  inventory. A substituted element, or a license borrowed from another bundle, is refused. These
  receipts establish how a caption was produced, not that the depicted event happened.
- **Component and wire.** A adds no license basis, so no basis-bearing claim or component
  version is required for licensing. The text model wire and the frozen S0 values are unchanged.
  Generic film-model wire remains V1 work.
- **Consistency.** General graph and status consistency rules apply to film claims. The text
  overlap and causal-cue rules remain text-witness rules and are declared inapplicable to film
  inputs; they are neither silently skipped nor satisfied by film anchors.
- **StatusWeight.** Semantics are unchanged: `SurfaceExplicit` and `HumanAdjudicated` fall back to
  1.0, `Hypothesized` to 0.25, and calibrated credence overrides the fallback while raw score
  does not. Unadjudicated caption-derived film claims therefore carry the 0.25 fallback in
  relation views. These values are not truth probabilities and not a universal alignment
  multiplier.
- **Text caption versus film claim.** The same sentence remains distinguishable as a separate
  text source, where it can be `SurfaceExplicit` relative to that text, and as a film
  derivative, where it is a claim about the film and is `Hypothesized` under A.
- **Support geometry.** A caption's support is its declared sampled-frame hull; the exact frame
  inventory is retained separately and does not imply continuous observation. Claim unions keep
  their gaps; the hull is never rewritten as per-frame intervals.

## Selected constraints

None beyond A as stated in the plan. F4's court stays as specified there: a
high-score/accepted-but-unlicensed refusal, the same caption on different bundles, substituted
output/receipt/frame inventory, human annotation versus actual adjudication, a lawful film
control, the compiled deletion of the selected license guard, and unchanged text status weights
and calibrated override.

## What this record does not establish

No film status policy is implemented, no build was run (the ticket requires none), and no film
compilation, calibrated confidence, CI or scientific claim follows from it. F4 remains blocked by
F1 (`bd-01M2YY2YJWXT406AZ3W4K9935W`) as well.
