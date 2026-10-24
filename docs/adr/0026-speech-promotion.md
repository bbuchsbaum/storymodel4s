# ADR 0026 — Defeasible promotion of speech assertions into the narrated world

**Status:** Accepted 2026-10-23 (AGENTS.md SD5). The owner chose the recommended answer to each
question under "Owner decisions". The cold review of `fdde4f998` found four blocking defects in the
text. They are corrected here, and none changes a decision.

**Decider:** the owner

**Tracker:** `bd-01M5MR4Q8FD8F1EBRNNJ6SGAJM` (S4 of the evidence-binding epic). This ADR settles the
two questions ADR 0024 §4 left to S4: how a promoted claim is typed, and where contestation comes
from. It also amends ADR 0024 §4 (see "Amendment to ADR 0024 §4").

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
- **No model content digest.** `StoryModel.sourceChecksum` covers the source atlas only. Two graphs
  over one source share it, so it cannot say which model a derived claim was checked against.
- **Duplicate detection is narrow.** `story/consistency.scala` rule 2 warns when a root situation
  duplicates a speech-scoped one without a `ReferenceEdge` between them. It runs only on text models
  (inside `text.foreach`; `checkGeneral` passes no text), it compares only asserted root *events*,
  and it tests overlap only between text spans. "She is dead" is a state, so the motivating case
  never warns, in text or in media. `TypedSupport` has no overlap operation.
- **Referents.** `document/referent.scala` gives a dialogue mention its speech context
  (`MentionOrigin.Dialogue(context)`) and answers `relation(a, b)`. A mention in a contested class
  answers `Open` even about itself, and a mention no link touches is a singleton class that answers
  `Same` about itself. `ReferentMention` is not linked to a story entity. The document mention graph
  (`document/projection.scala`) projects chart mentions to entities, but referent mentions are a
  separate family.
- **Contestation.** Three kinds are already expressible in a model: a root situation with the same
  predicate and participants and the opposite polarity; a rival speech situation (another speaker,
  or a later utterance) with the opposite polarity; and a `HypothesisClaim`, which keeps a reading
  open at the narrated-world level by design (§27.2). A lie that the narrative reveals without
  stating the negation has no vocabulary.

## Decision

### 1. A promotion is a derived overlay, not a situation in the model

`SpeechPromotion` (in `document`, which depends on `story`) is a final non-case class built only by
`SpeechPromotion.of`. It records:

- the speech-scoped twin (`SituationId`), its frame, predicate and polarity, and the rule
  (`uncontested-assertion/v1`);
- each participant's role, entity and bound referent mention;
- three digests: the model's, the `ReferentContext`'s and the `ContestationSet`'s.

The **model digest** is new and lives in `document`. It hashes everything the rule reads: contexts
(id, parent, kind, support), situations (id, claim id, context, predicate, polarity, modality,
support, participants), entity supports, hypotheses (subject, claim id), and the story id and source
checksum. Each field is hashed before joining, and each list is length-prefixed, as in
`ReferentContext.digest`. `promotion.holdsIn(model)` answers whether a model is the one the
promotion was checked against. A consumer holding a model checks this before reading the promotion.

The promotion never enters `StoryModel`, its graph or its claim ledger. Analyses include promotions
by reading the overlay and exclude them by not reading it. That makes the distinction structural:
no consumer of the graph can mistake a promoted claim for a depicted or surface-explicit one,
because the claim is not in the graph. The overlay's own `ClaimMeta` carries
`WorldKnowledgeInferred` with `Credence.unmeasured`. Its evidence cites the twin's claim id upstream,
its extractor is the rule's fingerprint, and its claim and evidence ids are content addresses over
all of the fields above (rule 10). Overlay claims are never weighted: `StatusWeight`'s provisional
0.5 for `WorldKnowledgeInferred` licenses no number for a promotion, and a consumer that weights one
needs its own calibration. `core` is unchanged.

### 2. The rule checks every condition from the source artifacts it is given

`SpeechPromotion.of(model: StoryModel[Validated], twin, bindings, resolution, contestations:
ContestationSet)` refuses with a typed reason unless all of the following hold:

1. **Unembedded speech.** The twin's frame is `Speech(_)`, and its parent is the `NarratedWorld`
   root. Speech nested in belief, desire, intention, hypothetical, counterfactual, memory,
   imagination or another speech is refused.
2. **Asserted content.** The twin's modality is `Asserted` or `Reported`, and its polarity is not
   `Unknown`. "She might be dead" (`Possible`) is refused.
3. **Bound participants.** `bindings` maps each participant entity of the twin to a referent mention.
   That mention must be in the resolution's context, its origin must be `Dialogue(twin.context)`, and
   its support must overlap both the twin's support and the entity's own support. Overlap is defined
   per support family: text spans overlap by offset, and anchored supports overlap by playback
   extent on the same bundle and axis. A text support never overlaps an anchored one. A participant
   left unbound is refused, and so is a binding for an entity that is not a participant.
4. **Settled referents.** Each bound mention's class is not `Open` in the given resolution.
5. **Uncontested.** The contestation set was built for this model (its model digest matches), and
   no contestation applies (§3).

### 3. Contestation comes from the source, never from the recall question

Contestation is read from recall-blind source artifacts. Reading record text per question is
rejected: it lets the per-recall assessor extend the source graph, which ADR 0024 §5 forbids.

Two situations have the **same content** when their predicate lemmas are equal and their sets of
(role, entity) participants are equal. This is the equality `consistency.scala` rule 2 already uses.
Its known limit is that "dead" and "die" differ, so a contesting rival worded with another lemma
escapes. Closing that needs a lexical or frame normalization in a producer with receipts.

An assertion is contested when any of these holds:

- a situation in a `NarratedWorld` frame with the same content has the opposite polarity or
  `Unknown` polarity, of any modality. An undetermined root claim leaves the matter open;
- a situation in any `Speech` frame, at any depth, with the same content has the opposite polarity;
- a `HypothesisClaim` targets the twin itself, or targets a situation with the same content;
- a `Contestation` record in the set targets the twin.

A same-polarity root situation with the same content does not contest. The narrated world already
agrees, and the promotion is redundant but sound.

`Contestation(target, basis, meta)` is new vocabulary. `ContestationBasis` is `Deception` (the
narrative reveals the speaker lied), `Retraction` (the speaker takes it back) or `Correction`
(another source corrects it). `Contestation.of` refuses a record unless at least one of its evidence
entries has spans, anchors or an upstream claim (rule 3; `ClaimMeta` alone enforces only the
`SurfaceExplicit` span law).

`ContestationSet` is a final non-case class built only by `ContestationSet.of(model: StoryModel[
Validated], records)`. It refuses a record whose target is absent from the model, and a record whose
target is not speech-scoped. It records the model digest, and its own digest is order-independent
and length-prefixed. An empty set is a statement by its producer that it found none. It is still
bound to its model, so a set checked for one model cannot vouch for another.

The set is produced by the recall-blind source build, the same build that produces the referent
context (S3c), and is stored and exported beside it (S3d). Types cannot prove that a producer
ignored the recall. The promotion therefore records the set's digest, so an audit can name exactly
which set a promotion relied on.

### 4. The duplicate check is a separate slice

Promotions never enter the model, so `consistency.scala` rule 2 does not guard them. It guards root
situations that a producer or author wrote as plain facts. Its three gaps (text models only, events
only, text overlap only) are real, but closing them changes warnings on existing anchored models and
needs its own measurement. They move to their own bead. That fix lifts the text gate and the
event-only filter, and defines overlap per support family with no overlap across families, as in §2
condition 3.

## Amendment to ADR 0024 §4

ADR 0024 §4 requires that "the referent link is licensed under §2". ADR 0026 replaces that condition
with §2 conditions 3 and 4. Each participant is bound to a dialogue mention of the utterance itself,
checked by context and overlap, and that mention's identity is not open.

The rule therefore does not require a licensed `IdentityLink`. A dialogue mention that no link
touches is a settled singleton, and it passes. The reason is that the promoted fact concerns the
referent of the utterance, whoever that is. A promoted fact may be about someone never shown or
never named again. Whether the depicted woman is the dead "she" stays a separate question for
`ReferentResolution` (`relation(dialogue, visual) == Same`), which S5 asks.

Rejected alternative: require a licensed identity link from each bound mention to another mention.
That would refuse every promotion about a referent mentioned once, and it would add no protection.
A link to some other mention says nothing about whether this utterance's assertion is true.

ADR 0024 §4 also says "an uncited promotion must keep tripping" rule 2. Under the overlay, a
promotion cites its twin by construction and is not a model situation. The sentence now applies
only to root duplicates written into the model, and §4 above records that rule 2 does not yet catch
them all.

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

- No change to `core`, `codec`, `view`, `story` or anything storyatlas4s consumes. The gate scope is
  `document` plus the modules that `tools/reference-scope.sh` names for it.
- The S1 pair `test_succeeds_on_uncontested_assertion_as_inference` /
  `test_resists_contested_assertion_as_world_fact` becomes an acceptance witness once S0 and S1 land.
  In the Scala court, the contested case must be refused *because of* contestation: a mutant that
  deletes the contestation check must fail a named test while the context guards still pass.
- `SpeechPromotion`, `Contestation` and `ContestationSet` each get a rule-8 court from outside the
  `document` package that refuses `new`, `apply`, `copy`, `fromProduct` and `Mirror.ProductOf`.

### Known limits

- The binding check ties a mention to its utterance and to the entity's support, not to the
  entity's role. A producer that swaps two participants within one utterance is not caught here.
- A text span carries no document identity. For text supports, the overlap in §2 condition 3 trusts
  that both sides index the model's own text. Anchored supports carry their bundle and are fully
  checked.
- Same content is lemma equality (§3).

## Rejected alternatives

- **A new `EpistemicStatus` case** (decision 1).
- **Contestation read per question** (decision 2).
- **A `Deceptive` context kind** (decision 3).
- **Requiring a licensed identity link inside the rule** (amendment to ADR 0024 §4).
- **A caller-supplied vector of contestations.** It cannot tell "none exist" from "none supplied",
  and it cannot bind a record to the model whose situation it targets. It is replaced by
  `ContestationSet`.
