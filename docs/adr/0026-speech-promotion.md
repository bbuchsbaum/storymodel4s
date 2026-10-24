# ADR 0026 — Defeasible promotion of speech assertions into the narrated world

**Status:** Proposed 2026-10-23 (AGENTS.md SD5). The owner decides the questions under "Owner
decisions"; each states a recommendation.

**Decider:** the owner

**Tracker:** `bd-01M5MR4Q8FD8F1EBRNNJ6SGAJM` (S4 of the evidence-binding epic). This ADR settles the
two questions ADR 0024 §4 left to S4: how a promoted claim is typed, and where contestation comes
from.

## Context

Someone in the film says she is dead. The story model records that as a situation inside a
`Speech(holder)` frame, and nothing moves it to `NarratedWorld`. Design rule 4 says reported content
never becomes root-world fact *by default*. ADR 0024 §4 allows a named defeasible rule to add a
separate narrated-world claim, distinguishable from depicted and surface-explicit claims, when the
speech is unembedded, the referent is licensed, and nothing contests it.

What the code offers today:

- **Status.** `EpistemicStatus` (`core/claim.scala`) has six singleton cases. The codec decodes it by
  `values`, and `story/relations.scala` `StatusWeight` maps every case to a provisional weight. A
  case with fields (`Inferred(rule, premises)`) would break the codec's enum decoding. A new
  singleton case would need a new weight, a number nothing licenses (rule 7).
- **The model is closed after validation.** `StoryModel` is an unforgeable class built by `draft`
  and `StoryValidator.validate`. Adding a promoted situation means rebuilding and revalidating the
  model, and every consumer of the graph would then see the promotion as an ordinary root
  situation.
- **Duplicate detection.** `story/consistency.scala` rule 2 warns when a root situation duplicates a
  speech-scoped one (same predicate, same participants, overlapping *text* spans) without a
  `ReferenceEdge` between them. It never fires on media-anchored (ASR) speech, which has no text
  spans.
- **Referents.** `document/referent.scala` gives a dialogue mention its speech context
  (`MentionOrigin.Dialogue(context)`) and answers `relation(a, b)`. A mention in a contested class
  answers `Open` even about itself. Story entities and referent mentions are not linked to each
  other anywhere.
- **Contestation.** Three kinds are already expressible in a model: a root situation with the same
  predicate and participants and the opposite polarity; a rival speech situation (another speaker,
  or a later utterance) with the opposite polarity; and a `HypothesisClaim`, which keeps a reading
  open at the narrated-world level by design (§27.2). A lie that the narrative reveals without
  stating the negation has no vocabulary.

## Decision

### 1. A promotion is a derived overlay, not a situation in the model

`SpeechPromotion` (in `document`, which depends on `story`) is a final non-case class built only by
`SpeechPromotion.of`. It records:

- the speech-scoped twin (`SituationId`) and the rule (`uncontested-assertion/v1`);
- the premises: the twin's claim id, the speech frame, and the referent mention bound to each
  participant;
- the digests of the `StoryModel`, the `ReferentContext` and the contestation set it was checked
  against.

The promotion never enters `StoryModel`, its graph or its claim ledger. Analyses include promotions
by reading the overlay and exclude them by not reading it. That makes the distinction structural:
no consumer of the graph can mistake a promoted claim for a depicted or surface-explicit one,
because the claim is not in the graph. The overlay's own `ClaimMeta` carries
`WorldKnowledgeInferred`. Its evidence cites the twin's claim id upstream, and its extractor is the
rule's fingerprint. `core` is unchanged.

### 2. The rule checks every condition from the source artifacts it is given

`SpeechPromotion.of(model, twin, bindings, resolution, contestations)` refuses with a typed reason
unless all of the following hold:

1. **Unembedded speech.** The twin's frame is `Speech(_)`, and its parent is the `NarratedWorld`
   root. Speech nested in belief, desire, intention, hypothetical, counterfactual, memory,
   imagination or another speech is refused.
2. **Asserted content.** The twin's modality is `Asserted` or `Reported`, and its polarity is not
   `Unknown`. "She might be dead" (`Possible`) is refused.
3. **Bound participants.** Every participant of the twin is bound to a referent mention whose origin
   is `Dialogue(twin.context)` and whose support overlaps both the twin's support and the entity's
   own support. A participant left unbound is refused.
4. **Settled referents.** Each bound mention's class is not `Open` in the given resolution, and the
   resolution's context is the one the mentions come from.
5. **Uncontested.** No contestation applies (§3).

The rule does not require a link to a depicted person. A promoted fact may be about someone never
shown. Whether the depicted woman is the dead "she" stays a question for `ReferentResolution`
(`relation(dialogue, visual) == Same`), which S5 asks.

### 3. Contestation comes from the source, never from the recall question

Contestation is read from recall-blind source artifacts. Reading record text per question is
rejected: it lets the per-recall assessor extend the source graph, which ADR 0024 §5 forbids. An
assertion is contested when any of these holds:

- a root situation with the same predicate and participants has the opposite polarity;
- another `Speech` situation with the same predicate and participants has the opposite polarity;
- a `HypothesisClaim` whose subject has the twin's predicate and participants keeps the reading open;
- a `Contestation` record targets the twin.

`Contestation(target: SituationId, basis: ContestationBasis, meta: ClaimMeta)` is new vocabulary.
`ContestationBasis` is `Deception` (the narrative reveals the speaker lied), `Retraction` (the
speaker takes it back) or `Correction` (another source corrects it). A `Contestation` must cite
evidence like any accepted claim (rule 3). The contestation set enters the promotion's digest, so a
consumer can see what the promotion was checked against.

### 4. The duplicate check covers media-anchored speech

`consistency.scala` rule 2 extends its overlap test from text spans to any `TypedSupport`, so that
an uncited root duplicate of ASR speech warns like a text one. This is the gap the ADR 0024 review
recorded. With promotions kept out of the model, the check guards hand-authored or producer-written
root duplicates.

## Owner decisions

1. **Typing (recommended: overlay).** Keep promotions outside `StoryModel` as a typed overlay with
   `WorldKnowledgeInferred` (§1). Rejected alternative: a new `EpistemicStatus` case on a promoted
   root situation. That puts the claim into every graph consumer, needs a weight that nothing
   licenses, and widens the change to `core`, `codec` and the storyatlas4s seam.
2. **Contestation source (recommended: source artifacts).** Recall-blind, from the model plus typed
   `Contestation` records (§3). Rejected alternative: text read per question.
3. **Deception vocabulary (recommended: a contestation record on the utterance).** Deception is
   something the narrative establishes about one utterance, so it is a `Contestation` with basis
   `Deception`. Rejected alternative: a new `ContextKind`. A context is where content holds, and a
   lie's content still holds nowhere new; a new frame kind would also reach every exhaustive match
   on `ContextKind`.
4. **Unattributed speakers (recommended: allowed).** A `Speech(Unattributed(_))` frame may promote.
   The motivating case is "someone says she is dead", and contestation targets the utterance, not
   the speaker. Rejected alternative: require a named holder. That would refuse the motivating case
   and add no check, because no current guard reads the speaker.

## Consequences

- No change to `core`, `codec`, `view` or anything storyatlas4s consumes. The gate scope is `story`
  (§4) and `document`, plus the modules that `tools/reference-scope.sh` names for them.
- The S1 pair `test_succeeds_on_uncontested_assertion_as_inference` /
  `test_resists_contested_assertion_as_world_fact` becomes an acceptance witness once S0 and S1 land.
  In the Scala court, the contested case must be refused *because of* contestation: a mutant that
  deletes the contestation check must fail a named test while the context guards still pass.
- Known limit: the binding check ties a mention to its utterance and to the entity's support, not to
  the entity's role. A producer that swaps two participants within one utterance is not caught here.
  Its receipt carries the binding.

## Rejected alternatives

- **A new `EpistemicStatus` case** (decision 1).
- **Contestation read per question** (decision 2).
- **A `Deceptive` context kind** (decision 3).
- **Requiring the depicted-person link inside the rule.** A promoted fact need not concern anyone
  depicted. Binding to the depicted person is a separate question (S5).
