# Slice 1.7: the model must stop asserting what it did not derive

- **Date:** 2026-09-02, revised the same day after an adversarial audit of the built model.
- **Standard:** `vision.md`. "Success is a system that makes its interpretation falsifiable and
  examinable." AGENTS.md contract 4 (reported content never becomes root-world fact by default),
  contract 7 (indistinguishable values; a default epistemic status is a fabricated license), the
  identity ruling (derived, never asserted), and rule 4 (model uncertainty must never be rendered
  as a substantive finding).
- **Measured on:** the model built from the fifty captured War of the Ghosts replies. Every fix is
  verified by replaying those, so the slice costs no API spend.

**Absences are not in this slice.** No causal layer, no episodes, no features: the model reports
those as empty and that is honest. What follows are things the model **says** that are false.

## D0. Reported speech is asserted as world fact, and the model invents a second battle

The story's climax is a man's retelling: *He said: "Behold, I accompanied the ghosts," and he told
everything. "We did such and such a thing: we fought. Many of our fellows were killed... They said
that I was shot, and I did not feel sick."*

Only the first of those sentences contains a speech verb. The other three lie wholly inside the
quotation and are parsed alone, so each becomes a root-world situation. The trajectory therefore
runs `tell he everything` -> `do we thing` -> `fight we` -> `kill fellow` -> `kill person` ->
`become he quiet`: the model states that a second battle happened, in story-world time, after the
man reached home and lit his fire. The ghosts' recruiting speech, the question "What do you think?",
and all three `think` situations are asserted the same way. All 65 situations sit in one
`NarratedWorld` context under a calibration model named `narrated-world-default-v1`, which is
contract 7's fabricated license written out in a field name.

**The evidence for the fix is in the surface text.** Quotation marks are observations, and the
vision names the exact surface text as the observational coordinate system. Two mechanisms:

1. **Quotation spans.** The surface analyzer gains quotation-span detection over the canonical text.
   A situation whose support lies inside an open quotation is placed in a child `Speech` context
   rather than the root, with the quotation span as its evidence. Attribution is separate and may
   fail: the speaker is the agent of the nearest preceding speech verb when exactly one is
   available, and otherwise the context is unattributed. An unattributed speech context is correct;
   a root-world assertion is not.
2. **Within-sentence embedding.** `PropositionChart` already carries `EmbeddedProposition(container,
   kind, content)` and `Interop.embeddings` already maps say/tell/think/believe/know/want/wish/
   hope/intend/plan/try/possible/likely/remember/recall/imagine/dream to Speech, Belief, Desire,
   Intention, Hypothetical, Memory and Imagination. The compiler currently refuses an embedded
   source with `UnsupportedEmbeddedContext`. It should instead emit a child `ContextFrame` of the
   mapped kind and place the content there.

Nothing here is new capability: both the embedding table and the context vocabulary exist and are
unused.

## D1. Entity identity is lemma grouping presented as coreference

`compiler.scala:1382` groups mentions by `(lowercased label, entityType)`. Consequences the audit
verified against the model's own evidence offsets:

- `he`, eleven mentions, merges the man who refused and went home with the man who accompanied the
  ghosts and died. The model asserts they are one person, erasing the story's central fact.
- `they`, ten mentions, merges the two young men with the ghost war party.
- `we` merges the ghosts' "We wish to take you along" with the protagonist's "we fought".
- "Two young men" becomes one entity with no cardinality; Egulac becomes two entities.
- Absent as entities: ghost, arrow, seal, river, shore, sun. The story's title referent is not in
  the entity layer.

`document/mentionform.scala` defines `MentionForm`, `isIntroducing` and `MentionForms.resolvableAt`,
whose Scaladoc states the correct rule and whose implementation refuses forward resolution. **No
main source references any of it.** The rule was written and never wired in.

**Fix.** Mention form is classified by the provider from the chart and carried as evidence. Only
introducing forms cluster by exact label. A pronominal resolves only when `resolvableAt` yields
exactly one candidate; otherwise it is explicitly unresolved and mints no entity node. Cardinality
is carried, and a name is not merged with its type node.

## D2. Non-referents are entities

`then`, `now`, `midnight`, `night`, `thus`, `together`, `sick`, `other`, `it`, `everything` hold
participant roles. Referentiality is decided by the role and the concept together under a closed
rule in `RulesText`: the referential roles are Agent, Patient, Theme, Experiencer, Stimulus,
Instrument, Beneficiary, Source, Destination and Location; `Time` and `Manner` take values, not
participants; `Cause` and `Result` take situations and belong to the causal layer, not the entity
layer; `Custom` fails closed. A time or manner filler is recorded on the situation as a typed value
with its own span, so the evidence survives where the model can hold it.

## D3. The story is titled `wog.txt`

The pipeline passes the input filename as the title; the provider proposes it as the summary; the
compiler accepts it at credence 1.0 under `title-rule-v1`. A filename is not a title. The pipeline
stops deriving one; a title is caller-supplied and recorded as such, or absent, in which case the
summary abstains and the model carries a gap. Accept the consequence: a model built from a bare text
file does not promote to validated, and the fix for that is a real summary rule, not a filename.

## D4. Credence is the constant 1.0 on all 398 claims

262 claims carry `chart-rule-v1` calibrated 1.0; one carries `title-rule-v1`; **135 carry rawScore
1.0 with no calibration model at all**, which contract 3 forbids outright. There is zero variance in
the model. Meanwhile `InteropTables` already computes `StandardRoleRawScore = 0.9` and
`LexiconRawScore = 0.5`, documented as uncalibrated, and those graded values never reach the model.

The deeper error: the calibration records certainty that a rule fired, and lands in a field a reader
reads as certainty about the claim. Contract 7's indistinguishability test fails: the certainty of a
total function is stored identically to the certainty of a coreference judgement.

**Fix.** Propagate the interop raw scores. Stop writing a calibrated probability for rules whose
determinism concerns the mapping rather than the claim. Every claim that carries a raw score without
a calibration model is either given one or stops claiming to be a probability.

## D5. Provenance identifies nothing, and costs 100 MB

All 65 situations carry a byte-identical list of 564 provider calls: the run's entire call set,
copied onto every claim. The provenance of `carry they he` cannot be distinguished from that of
`person (location: egulac)`, and neither names the call that produced it. That is a field with the
authority of a receipt recording nothing, and it is why `storymodel.json` is 100 MB.

**Fix.** A claim carries the calls that produced it.

## D6. Turnover cannot tell an empty cast from an unrecorded one

53 of 64 steps read 1.0. Fourteen of 65 situations have no participant edges at all, so a step
scores 1.0 when its neighbour recorded no cast, and 0.0 when neither side did. The same condition
produces opposite extremes depending on context, and the denominator counts adverbs as entities.

**Fix.** `FlowStep.entityTurnover` carries its resolution coverage: a value only where both
endpoints have resolved participants, an explicit unresolved state otherwise. Depends on D1 and D2.

## D7. Typed modality and polarity are constants while the description says otherwise

All 65 situations carry modality `Asserted`. `go you (accompanier: they) (mod: possible)` holds the
modality in its description string and `Asserted` in its typed field, so a permission is typed as a
statement of fact. "I will not go along" yields polarity Positive. "I have no arrows" yields
Positive. The refusal that drives the plot is negated nowhere in the model. The four State
situations carry polarity `Unknown`, which here means "never computed" and is stored identically to
"genuinely unclear".

## D8. The temporal layer is discourse order relabelled as time

All 64 temporal edges are `Unclear` at calibrated credence 1.0, over exactly the 64 adjacent pairs.
The layer adds nothing beyond reading order while occupying the name "temporal", and the text
licenses real relations throughout: "One night", "While they were paddling", "When the canoes came
opposite", "At midnight", "When the sun rose". Certainty 1.0 on "Unclear" is certainty about not
knowing.

## Order

D0 first: it removes a fabricated event sequence and nothing else in the model can be read honestly
until reported content leaves the root world. Then D2 (the entity population), then D1 (identity
over that population), then D6 (turnover, which needs both). D3, D4 and D5 are independent and small
enough to travel with whichever slice touches their code. D7 and D8 follow.

## Acceptance

Replaying the fifty captured recordings: no situation drawn from inside a quotation sits in the root
context; no trajectory step asserts a story-world sequence across a speech boundary; no entity is an
adverb or a property; no pronoun merges by lemma; every credence either carries a calibration model
or does not claim to be a probability; a claim's provenance names its own calls; every step either
measures turnover or says it could not; and every count is pinned as a literal with a mutation proof
behind each new rule.
