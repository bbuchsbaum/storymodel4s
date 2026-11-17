# Notes: retiring `ExternalState.Commentary` and `SourceConsistentInference`

Status: notes, 2026-11-17. This is **not an ADR** and decides nothing. It keeps what was learned
while a retirement was drafted too early, so that the later ADR does not rediscover it.

## Where this stands

- The owner agreed to retire the two cases "if we now have a better solution in place". The
  better solution is the talk-type field of
  [ADR 0032](../adr/0032-recall-reference-vocabulary.md), and it is not in the library yet.
- ADR 0032's revision 3 (`eedc2e35f`, §8) decided the retirement anyway. A cold review found
  that section contradicted the code. Revision 4 removed it and deferred the retirement.
- **Until a later ADR is accepted, nothing changes:** the six `ExternalState` cases, the HSMM row
  contract and every persisted form stay as they are.
- The later ADR should be written after the talk-type field lands (slices V5 and V9 of
  [the slice plan](2026-11-17-cs2-library-slices.md)), when there is a current representation
  for old commentary and inference mass to be compared with.

Paths: `A/` is `align/src/main/scala/storymodel4s/align/`, `C/` is
`codec/src/main/scala/storymodel4s/codec/`. Lines are in this branch's tree and were read, not
run.

## What the cases are today

- `ExternalState` has six cases (`A/matrix.scala:13-14`). Every unit the aligner can rank is
  priced against all of them but `Unranked` (`A/matrix.scala:64-65`, `A/evidence.scala:204-211`).
- `ExternalStates.natural` maps the `Evaluation`, `TaskCommentary` and `SourceMonitoring`
  discourse functions to `Commentary`, and `Inference` to `SourceConsistentInference`
  (`A/cost.scala:1100-1109`).
- `RecallSignature` reports `commentaryMass` and `sourceConsistentInferenceMass`
  (`A/signature.scala:37-38`), adds both into attributed external mass (`:74-75`) and exports
  them under those keys (`:759-760`, `:818-819`).

## What revision 3 proposed, and why it does not hold

Revision 3 proposed four things. Each finding below is the cold reviewer's, checked at the cited
lines.

**1. Give `Inference` no natural external state.**

- `atFloor` is `state == Unranked || natural(f).forall(_ == state)` (`A/cost.scala:1117-1118`).
  With no natural state, `forall` over an empty option is true, so **every** external state
  costs only the floor for an inference unit.
- Today one external state is at the floor (1.0) and the others pay the mismatch as well (0.5
  more; `A/cost.scala:1199-1200`, `:1206-1208`). The reviewer's hand calculation, at the default
  temperature of 0.15 (`A/hsmm.scala:239`), is that an inference unit's external weight rises
  by about 3×. That figure was not reproduced here.
- So the change would not move inference mass onto source anchors, as revision 3 claimed. It
  would move it toward external states.

**2. Add a `NonAddressed` column that is "neither a destination nor external mass".**

- `natural` and `externalCost` are typed on `ExternalState` (`A/cost.scala:1094`, `:1100`). They
  cannot name a new `AlignState` member, so the pricing interface would have to change.
- `isExternal` is `!isSource` (`A/matrix.scala:58`), and `externalMass` sums every state that is
  not a source (`A/matrix.scala:135`). A new non-source member is therefore external mass by
  construction. That mass gates the signature (`A/signature.scala:430`, `:566`) and partitions
  transitions (`A/hsmm.scala:929`), and the external-state support check keys on it
  (`A/hsmm.scala:676`).
- `Destination.of` is total over `AlignState` (`A/mappingvocabulary.scala:14-17`) and has 14
  call sites outside tests. A member that is "not a destination" has no value to return there.

**3. Decode legacy records through a second, legacy-only path.**

- By `A/evidence.scala:204-211`, **every** existing HSMM-derived record with a rankable unit
  holds both retired states. They would all become legacy at once, not a few.
- Accepted ADR 0019 preserves record bytes for the default configuration
  (`docs/adr/0019-mapping-measurement-policy.md:41-44`).
  `HistoricalEmbeddingParitySuite` pins a complete body by checksum (lines 42-58 of
  `embed-bench/src/test/scala/storymodel4s/bench/video/HistoricalEmbeddingParitySuite.scala`),
  and that body holds both retired tokens. A retirement that changes what the default
  configuration emits contradicts ADR 0019 unless that ADR is amended.
- A legacy-only enum member that wraps a value is a case class. A case class is no boundary
  under design rule 8, so "built only by the legacy decode path" cannot be enforced that way.
- `AlignRef` parsing has one mode (`A/ref.scala:46-70`). There is no place to say "legacy".

**4. A list of persisted forms.** Revision 3 named these:

- `AlignState` in HSMM results, by case name (`C/align.scala:248-281`);
- `ext:<case>` destinations (`C/mappingjson.scala:103-106`), used by mapping outcomes, surface
  workspaces and temporal inspection;
- address key parts `external`, `<case>` (`A/matrix.scala:82-107`), through `AlignRef`
  (`A/ref.scala:38-54`), `ViewRef.parse` (`view/src/main/scala/storymodel4s/view/ref.scala:34-44`)
  and Voyage documents, which must re-encode canonically
  (`C/voyage.scala:40-45`);
- the view's external columns (`view/src/main/scala/storymodel4s/view/mappingmatrix.scala:13`).

It still missed these, which also write an external state by name:

- `C/temporalquery.scala:81` and `:103`;
- `C/temporalprojection.scala:160` and `:247`.

## Other users a retirement must reach

- `embed-bench/src/main/scala/storymodel4s/bench/wog.scala:98` expects
  `SourceConsistentInference` for the War of the Ghosts inference paraphrase, and
  `embed-bench/src/main/scala/storymodel4s/bench/metrics.scala:108` and `:305` report its mass.
- Tests pin the current behaviour: `ViewRefSuite` round-trips `Commentary`
  (`view/src/test/scala/storymodel4s/view/ViewRefSuite.scala:24`); `NotAssessedSuite` asserts
  every assessed function's natural state
  (`align/src/test/scala/storymodel4s/align/NotAssessedSuite.scala:21`); and the War of the
  Ghosts inference test passes on any of three conditions joined by "or"
  (`fixtures/src/test/scala/storymodel4s/fixtures/wog/WarOfTheGhostsAlignmentSuite.scala:443-452`),
  so it would not show whether inference mass went where a retirement intends.
- `ProvisionalReference` types its external members by `ExternalState`
  (`A/referencemeasures.scala:72`, `:100`). ADR 0032 gives the recall reference its own
  `ExternalKind` and leaves `ProvisionalReference` alone.
- `storyatlas4s` builds this repository from source and consumes these records.

## Questions the later ADR must answer

1. **What is the row contract for a unit that is not addressed to material?** `HsmmResult` needs
   one row per unit, each summing to 1 (`A/hsmm.scala:492`, `:508`). The options seen so far are
   a column that is honestly external mass, an eligibility rule that removes units from the
   matrix, or leaving the HSMM alone and retiring the cases only from newer producers' output.
2. **Where does inference mass go**, given that removing a natural state raises external weight?
   A discriminating test is needed before any change, since the current one cannot tell.
3. **How do existing records stay valid** when every one of them holds the retired states, and
   ADR 0019 fixes the default configuration's bytes?
4. **What boundary keeps a retired value out of new results**, given that an enum member is not
   one under rule 8?
5. **Is retirement needed in the HSMM path at all?** The reference account of ADR 0032 never
   produces the two cases. The cost of leaving them in the older aligner may be lower than the
   cost of migrating every record.
