# ADR 0012 — Entity identity by referring form; pronouns resolve or stay open; turnover can be unmeasured

**Status:** Accepted 2026-09-03, single-developer mode (AGENTS.md SD5)

**Date:** 2026-09-03

**Decider:** the owner's agent, single-developer mode

**Plan:** `docs/plans/2026-09-02-model-truthfulness-slice.md` D1 and D6;
`docs/plans/2026-09-03-story-and-viewer-handoff.md` §2 item 8, §5 item 4

## Context

The compiler decided entity identity at one line: mentions grouped by lowercased label and type.
Every `he` in the fifty-sentence War of the Ghosts was therefore one entity with eleven mentions,
merging the man who refused and went home with the man who accompanied the ghosts and died;
`they` merged the two young men with the war party; `we` merged the ghosts' "we wish to take you
along" with the protagonist's "we fought". The model asserted the story's central fact away.
`document/mentionform.scala` had the correct vocabulary (referring forms, discourse positions,
`resolvableAt`) written out and used by nothing (audit D1).

Separately, a flow step's `entityTurnover` was a bare `Double`, so the compiler blocked every
trajectory step whose endpoints lacked a complete participant coverage, all or nothing: one
unresolved cast emptied the whole route (audit D6).

## Decisions

### 1. Identity is decided by the mention's referring form

`EntityIdentity.resolve` (`document/coreference.scala`), fed by `MentionForms.infer` over the
mention table, the surface text of each mention's support, and the atlas's sentence order:

- an **introducing** mention (`Name`, `Nominal`) clusters with the other introducing mentions of
  exactly its folded label and type, under rule `introducing-label/v1`. A pronoun never joins by
  lemma: it has no lemma of its own to join on.
- a **third-person pronoun** resolves under `pronoun-unique-antecedent/v1` when exactly one
  introducing cluster precedes it (`resolvableAt`: nothing resolves forward) and is compatible
  with it in number. Number comes from the chart alone (`ChartNumber`: an explicit `:quant`
  literal, else `Unknown`, which is compatible with anything), because AMR lemmas are singular and
  plural morphology does not survive parsing. With no compatible candidate the pronoun is open
  with `NoAntecedent`; with several it is open with `SeveralAntecedents` and the candidates are
  recorded as a set on the gap's upstream claims. The rule ranks nothing: choosing the nearest
  would be an inference with an error rate nobody has measured here, so it is recorded as the
  alternative it is and not accepted (design contract 3, 7).
- a **first- or second-person pronoun** refers to the speaker or addressee of the held frame it
  sits in, which is a fact about the context, not about any antecedent. Decision 3 (amendment of
  2026-09-03) reads that holder.
- any other form is open as `UnrecognizedForm`.

An **open mention mints no entity**. Consequences follow the existing gap machinery: a
participant edge whose filler is open is a `ParticipantRole` gap `MissingUpstream(EntityMention)`,
the situation's `ParticipantCoverage` is a gap, and a speech holder offered an open candidate is
`Unattributed(UnresolvedCandidate)` even beside a candidate that did resolve (naming the resolved
one would attribute the content on the strength of the other being unreadable).

Vocabulary: `NarrativeCandidateAddress.EntityReference(mention)` (one coreference attempt per
non-introducing mention, family `EntityCoreference`, emitted with the entity's claim when resolved),
`DerivationGapReason.OpenReference(OpenReference)`, `MentionPosition` gains a surface `offset`
between sentence rank and concept id, and `MentionForms.of/infer` take the sentence order
explicitly: the mention graph's `sentences` are sorted by id, which is not discourse order once a
story has ten sentences (`s10` before `s2`), a latent defect the old default carried.

### 2. A flow step's turnover is an estimate

`FlowStep.entityTurnover: ScoreEstimate`. `DiscourseTrajectory.derive` takes `castResolved`; a
step between situations whose casts are not both resolved carries
`Missing(MissingReason.InputUnresolved)` (a new portable reason in `features`: an input the
estimate needed is itself unresolved), and a step whose casts are resolved carries the measured
number. The compiler no longer blocks a pair on missing coverage, only on a missing temporal
relation. Model wire schema `0.4.0 → 0.5.0`; `flow-step/v1 → v2` in the fingerprint render;
`trajectory.turnover-in-unit` checks observed values only.

### 3. First- and second-person pronouns read the frame's holder (amendment, 2026-09-03)

`EntityIdentity.resolve` takes a `holderOf` function that the compiler builds from the accepted
context placements: for a mention, the holder candidate of the innermost held step (a `Quoted`
quotation, or an `Embedded` step whose kind carries a holder: speech, belief, desire, intention,
memory, imagination) on the placement path of the situation the mention fills, or nothing when
that situation sits in no held frame. A mention filling several situations reads the first in
discourse order.

The rule runs in two passes. Pass one decides introducing mentions and third-person pronouns
(decision 1). Pass two reads each first- or second-person pronoun against the holder, where the
holder is *one cluster of pass one*: every chart node the frame offered as holder is a mention
that pass one placed, and all of them lie in the same cluster. A holder that is itself a
resolved pronoun ("the man ... he said: 'I ...'") therefore carries its antecedent through; a
holder that is an open pronoun carries nothing.

| pronoun | frame | decision |
|---|---|---|
| first singular | holder is one cluster | `Resolved(speech-holder/v1)`, attached to that cluster |
| first plural | holder is one cluster | open `SpeakerGroup`, holder recorded as the candidate |
| second | holder is one cluster | open `NeedsAddressee`, holder recorded as the candidate |
| first or second | holder offered but not one cluster (open pronoun, two speakers, no candidate) | open `NeedsSpeechHolder(person)` |
| first or second | no held frame | open `OutsideSpeech(person)` |

Three distinctions the marks keep apart, because they are different facts: a group that includes
the speaker is not the speaker (so "we" is never the man, and never a second entity invented for
the group); the addressee is someone the model has no node for (so "you" names nobody, and the
speaker is recorded only as the frame it sits in); a narrator's "I" outside any held frame is a
fact about the discourse the model has no vocabulary for, and is not the same gap as a quotation
whose speaker went unread. `OutsideSpeech` is also what a caller gets who passes no holders at
all, which is the honest reading of "no context was consulted".

The embedded content of a same-sentence quotation is not a situation under the chart proposal
rule (one root per chart), so an "I" inside it is not a candidate at all today; the rule closes
the quoted path, which is where every first-person pronoun of the fifty-sentence replay sits.

## Measured on the fifty-sentence replay

| quantity | before | after |
|---|---|---|
| entities | 29 | 23 (no entity is a pronoun) |
| participant edges | 57 | 30 |
| coreference attempts | 0 | 26: 22 `SeveralAntecedents`, 3 `NeedsSpeechHolder(First)`, 1 `NeedsSpeechHolder(Second)`, 0 resolved |
| derivation gaps | 70 | 149 (+26 open references, +27 participant roles, +26 participant coverages) |
| validation violations | 135 | 135 (the trajectory is still complete) |
| trajectory steps | 64, turnover 1.0 on 53 | 64: 43 `Missing(InputUnresolved)`, 17 observed 1.0, 4 observed 0.0 |
| speech frames with a named holder | (see the placement court) | fewer: a holder offered a pronoun is unresolved |

No pronoun in this story resolves, because every `he` and `they` has several preceding referents
whose number the chart does not fix, and every `I`/`we`/`you` sits in a speech frame. That is the
honest reading of what the chart evidence licenses today.

After decision 3 (2026-09-03) the counts are the same and one mark moved: the `you` of "You may
go with them" sits under a named speaker and is `NeedsAddressee` with that speaker as its
candidate; the three first-person sites ("We are going up the river", "we fought", "I did not
feel sick") sit under "They said", whose `they` is itself open with several antecedents, so they
stay `NeedsSpeechHolder(First)`. Nothing resolves on this story until a third-person pronoun does;
on the authored court "The man said: 'Hello.' 'I fought.'" the `I` is the man and the fighting is
his participant edge. The candidates are all recorded, and the
viewer's D9 vocabulary draws an open reference with several candidates as a fan and one with none
as a placeholder.

## What this does not do

- It does not choose antecedents. A recency or salience policy would be a calibrated model's job
  (contract 3); when one is fitted its acceptances arrive as `Calibrated` bases and the
  `Alternatives` gaps become claims, nothing else changes.
- It does not represent addressees or speaker groups, so "you" and "we" stay open even under a
  named speaker (decision 3). A group node whose members are "the speaker and others" would be a
  claim about cardinality and membership the text does not make.
- It does not fix the referentiality of `everything`, `there`, `sick`, `home` as entities (D2's
  remainder): those are introducing nominals under the current admission rule.

## Rejected alternatives

- **Nearest preceding compatible antecedent** (the recall lane's rule). Accepting it would put an
  unmeasured heuristic's choice in the entity layer as a fact; the model would again say which man
  died on the strength of a policy, not evidence.
- **One singleton entity per open pronoun.** "Every `he` is a new person" is as false as "every
  `he` is one person", and it would keep the participant edges only by inventing referents.
- **Keeping the all-or-nothing trajectory block.** One open pronoun would erase the discourse
  route; the route is derived from discourse order, which the open pronoun does not touch, and
  only the turnover is unmeasured.
