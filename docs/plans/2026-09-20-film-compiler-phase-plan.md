# Film compiler phase plan

Revision 2, 20 September 2026. This is the plan for
`bd-01M2TAGST4EBFR1HM9Q3EWSYAH`, under film parent
`bd-01M2T32SGVJXR2RT3RTS2DCN9P`. It answers the parent's ten questions against
provider candidate `774fb1e8`; its production APIs are identical to the inspected
`850a6d64` checkpoint. S4c is substantively landed at `146df652`. D1B signatures
qualified and landed locally; D1B's end-to-end film proof remains pending, and this
document neither adds to nor substitutes for it.

Status: cold review completed with both findings repaired; see the
[review record](../refactor/evidence/d1a-film-phase-plan-20260920/cold-review.json).
The seven child tickets are filed with their declared dependencies, and the disclosed
[status-decision brief](../refactor/evidence/d1a-film-phase-plan-20260920/owner-decision-brief.md)
has been put to the owner. The owner has not selected a caption license, so F0 records
any later answer and F4 remains blocked until it exists.
The [child payloads](../refactor/evidence/d1a-film-phase-plan-20260920/children.json)
are the reviewable initial payloads of the filed tickets, rather than a substitute for
their live tracker records. F4 and film materialization remain blocked until the selected
policy is recorded; independently unblocked prerequisite work keeps its live dependency
schedule. No acceptance criterion is waived.

The governing [delivery plan](../refactor/PLAN.md),
[D1A revision 5](2026-09-17-d1a-source-to-story-plan.md), and
[ADR 0007](../adr/0007-film-source-representation.md) retain rulings A/C/D/E:
one composed bundle per model; proposal text outside that bundle; checked anchors;
film compilation on the 1.0 path without a research-win prerequisite. Full film
model wire V1 and terminal Sherlock E0 remain later 1.x work. P1 is unadopted.

## Result and implementation boundary

The result of the future children is an actual film input passing through the
existing deterministic compiler stages into an admitted `StoryModel`, followed by
its general `AlignmentSource`. A hand-built model or annotation preview does not
satisfy this result. Local construction and parity evidence does not establish
recovery accuracy, calibration, factual truth, CI execution or release readiness.

Keep `NarrativeCompiler.compile`, `NarrativeCompilation`, `TextModel` and the text
compiler's public return types intact. Add a checked `FilmCompilerInput`, a named
`NarrativeCompiler.compileFilm` entry point and an `AnchoredNarrativeCompilation`
result carrying the general model/validation outcome. These are proposed public
names for the children's ADR entries, not implemented APIs. Share deterministic
resolution/materialization logic behind a private source adapter; do not coerce a
film to `TextModel` or manufacture empty text support to reuse the text entry point.
The new result must preserve resolutions, typed gaps, reports, receipt joins and
status promotion identity just as the text result does.

## 1. Bound proposal surface

F1 constructs a canonical derived `StorySource` and `SurfaceAtlas` from an ordered
inventory of annotation/caption records. Preserve the exact descriptions and their
boundaries; include a versioned deterministic separator/sentence-splitting recipe
in the derivation. Each generated sentence has one new proposal unit associated
with that sentence's `SurfaceUnitId`, with checked inherited physical support from
its originating record. A multi-sentence description becomes several sentence
units with distinct IDs and the same declared physical envelope; it does not put
several sentences into the existing one-sentence association field. Preserve the
original row/scene units separately, with `surface=None`, and an explicit
sentence-unit-to-origin table. This table is checked and identity-bearing.

`BoundProposalSurface` already binds the complete surface structure, canonical
source checksum and safe receipt binding. It is an asserted input/output
association, not an attestation that an algorithm produced the output or that the
description is true. F1 additionally checks expected annotation/caption identities,
ordered record IDs, recipe, supplied output checksum and the origin table at its
input join. Caption joins retain both the derivation receipt and output-bound
proposal identity. Missing, duplicated or foreign associations are typed refusals.

Proposal text is available for tokenization, local charts and grammatical mention
forms. It is never node/claim physical support in the film model. Retain the
existing model canonical-form guard and strict full-bundle `EvidenceSupport`
binding. A substituted surface, sentence structure, receipt, origin or source
bundle must change identity or be refused; byte-identical input is the control.

## 2. Non-text identity and compiler headers

F2 derives a tagged film compilation-input identity from full bundle identity,
bound surface identity, the complete ordered origin/unit table, canonical unit
supports, compiler/policy version and all proposal/derivation inputs that affect
the compilation. Candidate-set and compilation/build-receipt headers use that
identity. Their receipt join must compare the expected film input, not only the
description checksum or legacy bundle ID. Do not change text header preimages.

Use the existing safe tagged identity machinery, including full mapping payloads,
safe receipt binding and non-primary stream coordinates. Test identical descriptions
on different bundles; changed mapping offset with unchanged endpoint IDs; changed
timebase with unchanged legacy axis ID; changed sentence units and receipt; and a
substituted compilation receipt. Each difference must remain observable. A named
omitted-field mutant must fail without changing an unrelated input.

## 3. Context placement

The first film compiler is **NarratedWorld-only with an explicit Held gap**.
Do not port `ContextStep` text spans into playback coordinates in this phase.
A proposal whose placement is `Held`, ambiguous or otherwise refused retains its
resolution/provenance and emits a named film-placement `DerivationGap`; it does
not create that situation in the root context. Relations requiring an omitted
endpoint also gap. An independent root proposition in the same input may compile.

This choice is one of the two permitted routes, bounds F3/F5, and preserves the
mandatory-context rule. Later anchored Held construction needs a separate child
and checked holder/context support; it is not silently included here. A positive
root control plus Held-to-root fallback mutant is required. Existing text quoted
and embedded-context courts remain unchanged.

## 4. Anchored within

F1 resolves every attempt's declared surface sentence IDs to their unique checked
proposal units. Cross-sentence attempts must explicitly declare every participating
sentence; an undeclared sentence or absent/foreign unit is refused. The permitted
support is the exact union of those units' envelopes, never their hull.

For this first route admit `MediaTime` and `MediaPoint` support. Other checked core
anchor kinds remain representable in the library but get a typed unsupported-kind
refusal at this compiler input, rather than being discarded. Validate full bundle
binding and every anchor's stream/axis association first. Then require containment
against the declared units on the same stream and axis: intervals must be covered
by their complete interval union; points may match an explicit point or lie in an
interval's half-open interior. A point cannot cover a positive-duration interval.
Repeat containment for every native anchor as well as the direct-primary anchors;
matching the primary projection alone cannot license a native anchor from another
unit. No implicit mapping, narrowing, duration fabrication or gap filling occurs.

At least one direct primary-axis member remains necessary for total projection.
Retain all native evidence. Courts isolate wrong sentence, wrong native stream,
foreign full binding with the same legacy ID, a gap, excluded interval end,
point-only support, mixed point/interval support and a partly covered interval.

## 5. Stage 1b provenance

F1 carries a checked association from each sentence unit to its local chart and
the parse call(s) that produced that chart, plus the originating annotation/caption
derivation. F5 resolves claim provenance through these associations and the actual
contributing chart references; it does not infer parse membership from playback
ticks or proposal-text span overlap. Bind expected parser/request/recipe/source
identities and output chart identity at the join. A run-wide list is not sufficient.

F1 tests the association table and provenance-selection helper with two independently
parsed descriptions: selecting A's chart references selects A's relevant calls,
selecting both selects both, and common upstream calls are deduplicated without
losing them. Missing, swapped or foreign associations refuse the input. F5 then
tests the actual emitted claims for the same inclusion/exclusion behavior through
`compileFilm`. Keep exact proposal surface/chart links for audit without converting
those spans into film evidence. F1 does not claim to emit a film claim.

## 6. Mention positions and IDs

F3 preserves the existing `MentionPosition(sentenceRank: Int, offset: Int, concept)`
constructor, fields and text ordering. Add a sealed `MentionCoordinate` family in
the same source file: the existing text position is one case, and a checked
`AnchoredMentionPosition` is the additive case. The latter carries full bundle
binding, primary axis, exact `Long` lower/upper bounds, proposal-unit ordinal/ID
and concept ID. A point has equal bounds; no conversion to `Int` is allowed.

Preserve the existing `MentionForms` text return types. Add the film-facing checked
forms/positions result, sharing the grammatical classifier but taking its text from
the bound proposal surface. Do not add a total `Order[MentionCoordinate]` that
pretends text offsets and ticks are comparable. The checked film result orders
only positions from its own joined atlas by `(lower, upper, proposalOrdinal,
proposalUnitId, conceptId)`. Equal-time mentions remain distinct and deterministic.

Film mention/node IDs use tagged exact physical support plus unit/concept identity;
text ID preimages and span refs remain unchanged. Test ticks above `Int.MaxValue`,
point/interval ties, disjoint interval unions, input permutation and cross-bundle
refusal. Mention-form surfaces remain linguistic features, separate from physical
support. The shared compiler's `Material`, `EmittedMention` and derived metadata
must carry typed support on the film path without replacing the text overloads.

## 7. Status decision for the owner

F0 records the owner's choice; F4 and film materialization remain blocked until
that answer exists. This plan recommends A but adopts neither alternative.
The phase-plan ticket owns putting the question to the owner before its own
closure; F0 then records the answer to that already-presented question. Its
dependency on the phase-plan ticket does not defer asking the question until
after this ticket closes.

**A — preserve the text meaning (recommended).** `SurfaceExplicit` continues to
mean directly stated by source text. A caption-derived claim about a film retains
its exact anchors and complete derivation but receives `Hypothesized` when its
only license is model conjecture. Human-authored annotation alone also does not
prove specific claim adjudication. `HumanAdjudicated` requires an actual record
for that particular claim. Other statuses require their own family-specific basis.
Successful parsing, accepted resolution and high raw score do not supply a license.

**B — introduce a disclosed caption-derived explicit license.** This changes the
meaning of `SurfaceExplicit` and requires an ADR amendment and a checked basis
distinguishing original text, human annotation and generated caption. F4 must add
that basis to claim construction and the public claim/component record, validate
expected derivation/output/bundle joins, specify a film consistency rule and
document status-weight consequences. Anchors alone cannot mint the license;
proposal-surface spans cannot satisfy the old span law by pretending to be film
evidence. A new basis-bearing component version may be needed; the text model wire
and S0 expectations remain unchanged and full generic model wire remains V1 work.

The concrete trigger is a caption saying “John opens the door”, successfully parsed
and accepted with exact film support. Its receipt records the model/recipe/request
and sampled frames; the output-bound proposal identity records the caption text.
Neither establishes that the depicted event happened. The same sentence as a
separate text source and as a film derivative must remain distinguishable.

Consequences to disclose with the question:

- `ClaimMeta` currently requires spans for `SurfaceExplicit`, while film admission
  forbids canonical text evidence. A leaves that law intact; B must change the
  license with an explicit checked basis, not replace `hasSpans` with `hasSupport`.
- The existing text compiler's SurfaceExplicit assignments cannot be reused as a
  film default. F4 supplies an explicit selected rule to every materialized family.
- General graph/status consistency rules still apply. Text overlap and causal-cue
  rules remain text-witness rules; A declares them inapplicable to film inputs,
  while B must define and test the replacement basis rule before admitting claims.
- `StatusWeight` is a relation-view fallback: SurfaceExplicit/HumanAdjudicated 1.0,
  Hypothesized 0.25, with calibrated credence overriding it. Raw score does not.
  These are not truth probabilities or a universal multiplier on alignment.
- Caption support is the declared sampled-frame hull, with the exact frame inventory
  retained separately. It does not establish continuous observation. Claim unions
  preserve their own gaps; do not rewrite the caption hull as per-frame intervals.

F4's court includes these distinctions, a high-score/accepted-but-unlicensed refusal,
same caption on different bundles, substituted output/receipt/frame inventory,
human annotation versus actual adjudication, a lawful film control, the selected
license deletion mutant, and unchanged text status weights/calibrated override.
No answer is not approval. The phase-plan criterion is to put the concrete decision
to the owner; the dependent implementation remains gated afterward if unanswered.

## 8. Anchored evidence and source-support rendering

F2 adds explicitly tagged canonical film compiler renderings for inline evidence,
ledger evidence and `SourceSupport`. Bind complete `TypedSupport.identity` and its
canonical payload, including full bundle binding, stream, axis, anchor kind and
all exact intervals/points; include kind-specific occurrence IDs if a later input
version admits those kinds. Use safe lengths/tags and canonical ordering, not the
legacy delimiter-only receipt preimage. Text rendering stays byte-identical.

The temporary text-input anchor refusals remain on the text entry point. The film
entry point may accept anchors only after both the checked input and complete
rendering participate in candidate/compilation identity. Courts separately move
one tick in each evidence carrier, change stream/binding/kind, permute an equivalent
interval union, retain gaps and explicit points, and kill an omitted-field mutant.
This is compiler identity rendering, not a claim that hsmm/v3 or schema 0.7.0 now
round-trips non-text models. D1B's checked wire refusals remain in force.

## 9. Actual film compiler court

F5 constructs a synthetic single edition with two supported intervals separated by
a real gap, an explicit point, two proposal descriptions and separately receipted
local charts. Drive the public film compiler through acquisition/resolution,
placement, mentions, materialization and checked model validation. Inspect retained
resolutions and gaps as well as emitted nodes. Include an independent root clause
and Held clause to prove the chosen context boundary. Use the owner-selected F4
license, not a fixture-only bypass or unchecked constructor.

The resulting model must retain exact original native/primary support and complete
unions/points, use its primary projection for order, refuse proposal-text physical
support and expose those exact values through general `AlignmentSource`. Exercise
actual result receipt/model joins and both successful and blocked validation.
With two independently parsed descriptions, the actual claim for A includes A's
parse/derivation calls and excludes B's unrelated calls; a claim using both includes
both, preserving relevant shared upstream calls. This emitted-claim provenance
court belongs here, after the input helper is available from F1.
Permuting semantically unordered inputs preserves canonical results. Foreign
bundle/axis/receipt, stale same-ID full binding, native-unit substitution and
Held-to-root fallback each have a passing control and compiled falsifier. Run the
existing full text/S0 courts and mandatory consumer suites at the exact candidate.

This compiler court does not close the separately scoped end-to-end film alignment
proof, scientific benchmarks, model-wire V1 or terminal Sherlock E0.

## 10. Sherlock repair and composition

Reuse D1B's `SherlockSourceAtlas` and its already specified exact part composition,
LCM timebase conversion, native/primary row and scene support, point row 13 and full
receipt binding. Do not rebuild a second clock or restore joined-description spans
as physical evidence. F6 adds only the checked proposal-surface/chart associations
and film compiler integration from F1/F5. Preserve the repair-record checksum,
annotation identity and ordered part identities through each unit's derivation.

Accept only pinned admitted annotation/media identities. Byte-identity admission
and local media reachability are different facts; absence of a playable file does
not authorize a replacement identity. Test wrong part, composition offset,
differing valid part rates, unsupported mapping image, point-only scene, native and
primary point retention, and substituted repair receipt. Reuse D1B's complete
1,000-row coordinate oracle and frozen all17/2,577-row parity rather than opening
gold or tuning parameters. Legacy annotation-text scoring coordinates remain an
explicit optional feature; they never become film support or a made-up world clock.

The first real integration witness is compilation of a small content-free fixture
derived from the declared intake contracts. A subsequent local admitted-input
court binds every row/unit association and preserves raw annotations/recalls outside
Git. It makes a construction/provenance claim only. Full participant film-alignment
proof remains on its existing later ticket.

## Children, gates and closure

The film parent now has this phase-plan ticket and the filed F0–F6 children:
F0 `bd-01M2YY1Z7W0SGT0RMYGH68T5PG`, F1 `bd-01M2YY2YJWXT406AZ3W4K9935W`,
F2 `bd-01M2YY37377TYWWKQM6AZJSBQ0`, F3 `bd-01M2YY3FK1F5MJEMQX36NTXHNP`,
F4 `bd-01M2YY3R25N9XWWAC4EZ3X3BJ9`, F5 `bd-01M2YY40FSG20HFF30S6RVVDQ5`,
and F6 `bd-01M2YY490C0BAPDT6VRTEZ5DTF`. Preserve existing S4c, D1B signature
and film parent tickets. Parent links and blocking edges are distinct; do not introduce
a dependency cycle. The filed **F6 blocks film parent** edge makes the parent wait for
F6; its transitive prerequisites cover F0–F5 and the existing plan/types/signature work.

| Key | Deliverable | Blocking prerequisites |
|---|---|---|
| F0 | Record the disclosed owner status decision | This phase-plan ticket |
| F1 | Checked proposal surface, unit containment and chart provenance input | This plan; D1A-types parent; D1B signatures |
| F2 | Film compiler identity and complete support rendering | F1 |
| F3 | Exact film mention coordinates and root-only placement | F1 |
| F4 | Implement the selected explicit status rule | F0; F1 |
| F5 | Compile the synthetic film through the real stages | F2; F3; F4 |
| F6 | Join Sherlock repair/composition to the film compiler | F5; D1B signatures |

Each payload has decisive accepting/refusing controls and compiled mutations,
scope limits and exact local gates. Code children retain separate SD6 review,
standalone clean full-provider/format gates, S0 parity, docs examples for public API
changes and storyatlas consumer qualification where touched. F0 is a decision
record, not a build. Every child is future open work; no dispatch is implied here.

To close the phase-plan ticket: commit this plan and its cold-review resolution;
file every child with actual parent/blocking edges; replace local keys in a filing
receipt with real IDs; then put section 7's concrete alternatives and affected
child link to the owner. Record the question and answer or explicitly pending
answer. Tracker rejection or an unasked question leaves this ticket open. The film
parent remains open until its implementation children meet their own criteria.
