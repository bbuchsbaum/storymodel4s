# Slice 1.7: four falsehoods in the built model

- **Date:** 2026-09-02
- **Standard:** `vision.md`. "Success is a system that makes its interpretation falsifiable and
  examinable." A model that asserts what it did not derive fails that test even when every test is
  green. AGENTS.md design contract item 7 and rule 4: model uncertainty must never be rendered as a
  substantive finding, and a default epistemic status is a fabricated license.
- **Measured on:** the model the pipeline built from the fifty captured War of the Ghosts replies.
  Every fix below is verified by replaying those, so the slice costs no API spend.

These are not absences. Absences (no causal layer, no episodes, no features) are the next slice and
are honest today because the model reports them as empty. These four are things the model **says**
that are not true.

## D1. Entity turnover reads 1.0 on 53 of 64 trajectory steps

`DiscourseTrajectory.derive` computes turnover as one minus the Jaccard overlap of the two
situations' expanded entity sets, and yields `0.0` when the union is empty. Both branches produce a
number under conditions where turnover is not defined:

- with the entity layer of D2 and D3, adjacent situations almost never share a resolved referent, so
  the model reports total cast replacement between nearly every pair of adjacent events;
- when neither situation has a resolved participant, the model reports zero turnover, which reads as
  perfect continuity.

Our own uncertainty is being published as a measurement, in both directions. This is precisely the
class AGENTS.md rule 4 was written for.

**Fix.** `FlowStep.entityTurnover` stops being a bare `Double` and becomes an estimate that carries
its own resolution coverage: a value only where both endpoints have resolved participants, and an
explicit unresolved state with a reason otherwise. `derive` computes turnover only over resolved
referents and records the coverage it had. No step reports a turnover it could not measure.

Touches `story` (the `FlowStep` type), `document`, `view`, `codec`, `laws`. It is a public signature
change and needs an ADR line.

## D2. Coreference is asserted by lemma

`compiler.scala:1382` groups entity mentions by `(lowercased label, entityType)`. Every "he" in the
story becomes one entity with eleven mentions; every "they" becomes one with ten. The story has
several male referents and at least two distinct groups. Nothing evidenced that merge: it is an
identity claim produced by string equality, which the contract's identity ruling forbids
("if the caller lied here, what would catch it?" — nothing does).

`document/mentionform.scala` already defines the vocabulary to refuse it: `MentionForm` with
`Name`, `Nominal`, `Pronominal`, `Other`, an `isIntroducing` predicate whose Scaladoc says a pronoun
"carries no introducing power, so it may resolve only to referents already introduced at or before
its discourse position", and `MentionForms.resolvableAt` implementing exactly that. **No main source
references any of it.** The right rule was written and never wired in.

**Fix.**
1. The provider classifies each filler's mention form from the chart: a `:name` construction is a
   `Name`, a closed list of pronoun lemmas is `Pronominal`, everything else is `Nominal`. The form
   is part of the mention proposal and therefore evidenced and receipted.
2. The compiler clusters **only introducing forms** by exact label match. That keeps exact
   coreference where it is defensible and stops it where it is not.
3. A pronominal mention resolves to an already-introduced referent only when `resolvableAt` yields
   exactly one candidate; otherwise the mention stays **unresolved**, and the model says so.
4. A participant edge whose filler is unresolved still exists, because the situation does have an
   agent, but its referent endpoint is explicitly unresolved. It must not mint an entity node, since
   an unresolved referent that counts as an entity is D1's error in a different place.

## D3. Non-referents are entities

`then`, `now`, `midnight`, `thus`, `together` hold participant roles today, and `sick` and `other`
are entity nodes. A time is not a participant and a property is not a referent. They arrive because
`scanFillers` admits any filler whose concept kind passes `KindWitness.entity` and whose role has
exactly one normalized role, and `Time` and `Manner` are normalized roles.

**Fix.** Referentiality is decided by the role and the concept together, under a closed named rule
in `RulesText`: only roles that take a referent (agent, patient, theme, recipient, beneficiary,
source, destination, and location when its filler is a place) admit an entity mention. A `:time` or
`:manner` filler is not discarded silently: it is recorded on the situation as a typed temporal or
manner value with its own span, so the evidence survives where the model can hold it, and the
coverage row counts it. Property-kind fillers reached through `:domain` or `:mod` are attributes,
not entities.

## D4. The story is titled `wog.txt`

The pipeline passes the input file's name to `StorySource.fromText` as the title. The provider's
summary rule then proposes that string as the story summary, and the compiler accepts it at credence
1.0 under a calibration model named `title-rule-v1`. The model therefore contains a claim, carried
with full confidence and a receipt, that this narrative is called `wog.txt`. It is the smallest
defect here and the most embarrassing, because it is a fabricated claim about the work itself.

**Fix.** A filename is not a title. The pipeline stops deriving one; a title is either supplied
explicitly by the caller, and then recorded as caller-supplied provenance rather than derived, or
absent. When absent the provider takes its existing `NoTitle` abstention path and the model carries
a summary gap, which is true. Note the consequence and accept it: without a title the summary family
is unresolved, so a model built from a bare text file does not promote to validated. That is the
honest outcome, and the fix for it is a real summary rule, not a filename.

## Order and dependency

D4 is independent and small. D3 is the root of the entity noise and must precede D2, because
clustering rules are pointless while non-referents are in the population. D2 must precede D1,
because turnover cannot be measured against an entity layer that is wrong. So: **D4, D3, D2, D1.**

## Acceptance

Replaying the fifty captured recordings:

- no entity whose label is a temporal or manner adverb, and no entity that is a property word;
- no entity that merges mentions of a pronoun by lemma; every pronominal mention is either resolved
  to a referent introduced earlier or explicitly unresolved, and the counts of each are reported;
- every trajectory step either carries a turnover with its coverage or says it could not measure
  one; no step reports 1.0 or 0.0 by default;
- no claim in the model names a filename;
- the coverage ledger accounts for every filler the provider saw, admitted or not, with a reason;
- each rule carries a mutation proof, and the captured court pins the new counts as literals.
