# ADR 0032 — Recall reference vocabulary for case study 2

**Status:** Proposed, 2026-11-17, revision 5. The owner decides this ADR and the placement
of `WorkId`. See the [first-wave slice plan][slices] and [proposed timing amendment][timing].

**Evidence.** `H/` means `tools/case-studies/recall-precision/cs2/` at harness commit
`a482fa3f2` on `claude/cs2-mapping-object`. The account comes from the FilmFestival
development replay. The first wave ends at representation and row parity. ADR 0030 defines
the detail account. ADR 0031 defines reference measures and single-condition held-level series.

## 1. Talk and topic frame

A checked `RecallTalk` anchors a classification to exact recall support and its producer
condition. It carries a topic frame independently of its reference. The need comes from the
harness's separate talk, frame and word support (`H/ff_producer.py:892–904, 1061–1078`).

Reuse `recall.DiscourseFunction`, including its existing `Evaluation`. Add only the categories
the current converter needs: `Content` (narration or identification), `Metamemory`, `Fragment`,
`SearchingBetweenWorks`, `EndingRecall`, `OffTask`, and `Custom(namespace, label)`
(`H/account.py:200–209`, `H/ff_producer.py:963–979, 999–1019`). The last case retains an
explicit extension label. Missing classification retains its processing outcome
(`H/ff_producer.py:817–819`). Keep existing classifications, legacy wire tokens and their
aligner interpretation. In particular, the new cases acquire no implicit mapping to an
aligner external state. The existing codec uses `DiscourseFunction.values`
(`codec/src/main/scala/storymodel4s/codec/recall.scala:22–24`), so adding a parameterized case
requires an explicit codec update. The segmenter keeps its existing classifications
(`recall/src/main/scala/storymodel4s/recall/segmenter.scala:536–549`).

- Content has a reference, including unresolved content or a recorded processing outcome.
- Evaluation, metamemory, fragments and custom talk can carry a frame without a reference.
- Searching between works, ending recall and being off task require an unframed or unavailable
  frame. Each contributes a state derived from its function.

A referring expression inside evaluation or metamemory gets its own anchored content stretch
(`H/ff_producer.py:1015–1051`). Frame values distinguish a supplied weighted set of works,
an observed absence of a frame and an unavailable frame with outcome. Validate membership,
distinct keys, finite positive weights and total at most 1. Keep the weights as supplied.
A frame alone contributes no reference weight.

A checked factory enforces the table, programme and source binding, returning refusals as
values. Reject a second discourse enum, which would duplicate evaluation and require a
parallel classification vocabulary. Reject a single field for talk and address: pass 1's
`work` reply can be an identification or an unaddressed remark.

## 2. References

A checked `RecallReference` has the following addressed forms. Unresolved content and
processing outcomes use ADR 0031's existing distinction and outcome vocabulary.

- **Moment.** A definite or alternative reference over existing single, joint or broad
  hypotheses (`H/account.py:51–68, 102–125`, `H/ff_producer.py:789–797`).
- **Work.** One declared work with its supplied weight, if any
  (`H/account.py:129–138`, `H/ff_producer.py:889`).
- **Position.** An ordinal from the start or end of viewing order, or a step relative to
  the earlier frame, with its supplied weight if any. Preserve the stated expression as
  evidence. (`H/account.py:142–152`, `H/ff_producer.py:194–225, 880–887`)

A moment holds exactly one existing `TemporalSupport.Coordinate` from the source, as
`ProvisionalReference.of` does
(`align/src/main/scala/storymodel4s/align/referencemeasures.scala:139–156`).
Work and position bind to the source's programme without a temporal coordinate. Keep
ADR 0031's weight coverage, duplicate and remainder rules. Alternatives contain moments
only in this profile. Extent for work and position is unavailable. Reject a broad span as
the representation of a work mention, which would fabricate temporal support.

Resolve positions against the programme and the earlier frame from the same pass. FilmFestival
declares the ordered list for resolution by excluding the introduction and taking the first
showing of each distinct work (`H/ff_producer.py:169–225`). Keep absolute positions from either
end and relative steps in that list. Missing anchors and out-of-range positions are unresolved
results. The checked account derives the resolution. A producer-supplied resolution is evidence
for replay comparison.

## 3. One account, two conditions

A checked `RecallAccount` groups stretches for one recall and one source digest. It carries
both recorded conditions, with the pass-1 frame as an explicit input to the corresponding
pass-2 work. The harness records separate conditions for work/position and moments
(`H/ff_producer.py:963–979, 1043–1058`) and groups them in an account
(`H/account.py:236–246`). Preserve each observation's pass and its exact support. If either
pass did not return an observation, retain the outcome at that pass's support.

- The whole-recall projection reads pass-1 talk, work and position references, and states.
  A pass-1 topic frame by itself remains an observation.
- The content-local projection reads pass-2 talk and moment references. It records which
  pass-1 frame routed the request, while assignment mass comes from the pass-2 reference.
- Each pointing or held-level series selects one producer condition. Importing both passes
  into the same account does not admit both as samples in one series (ADR 0031 §4).
  The case-study harness's pooled forecast remains an explicitly named study calculation.

Reject flattening the passes or treating pass-1 frames as local evidence. The account checks
source digest, recall support and condition membership, and rejects foreign inputs.
`RecallTalk`, `RecallReference` and `RecallAccount` are final, non-case classes built
through checked factories with structural equality. Decode through those factories when
the wire form is added.

## 4. Programme and placement

A checked `Programme` binds existing work identity to ordered showings. A showing has one
work, a viewing position and part-local playback intervals. The harness needs repeated works
and derives each unit's work through its showing (`H/source.py:59–85, 121–164`,
`adapters/filmfestival.py:195–217` at the same commit). The first-wave FilmFestival source
uses one part per showing. Multiple part intervals in a showing give no cross-part coordinate.

`SourceRepresentation` validates and binds the programme into its digest. Check unique
positions, declared works, intervals on admitted primary axes, and no overlapping showings
on a part. Derive unit membership only from unambiguous containment in the part interval.
The programme is a final, non-case class with a checked factory and structural equality.
Sources without a programme retain their present digest. Reject per-work sources for
this case: they discard the viewing-order relation and cannot distinguish repeated showings.

**Placement for owner decision.** `WorkId` is already used by `corpus.Segmentation`
(`corpus/src/main/scala/storymodel4s/corpus/segmentation.scala:8–9, 75`).

- **Recommended.** Lift the existing `WorkId` into `core` and place `Programme` there.
  Preserve the `corpus.WorkId` type and constructor spelling through aliases or forwarding
  and preserve wire values. V1 must check corpus callers and the sibling build. Moving the
  opaque type's defining owner can affect compiled clients, so check linkage before claiming
  binary compatibility.
- **Programme in corpus.** Keeps the existing identifier owner. Align would take a
  dependency on corpus or receive a copy via an adapter. The former couples alignment to
  corpus contracts and the latter requires two independently validated representations.
- **Second identifier in core.** Avoids moving the opaque type. Every boundary would
  require a conversion and identity agreement, duplicating vocabulary for the same work.

## 5. Pointing row and ledger

`PointingDistribution` projects onto recall-clock bins, with one sparse row per bin
(`H/projections.py:83–103, 272–315`). A **support key** identifies one allocation in the
row. It can identify a material target, a derived non-work state, or the reference's known
unassigned remainder. Topic frames and ledger counts do not add mass.

1. Select the producer condition and apply the timing amendment's run rule. As in ADR 0031
   §3, sample by word onset and named nesting policy. Under innermost nesting, distinct
   observations tied for the fewest covered inventory word cells make the row unavailable.
   FilmFestival pointing uses that cell count; reply order has no role. This does not change
   ADR 0031's held-level UTF-16 support policy.
2. Average distinct reference and state samples, once per sample. A weight-one work reference
   and one state contribute one half each. A state contributes unit mass before averaging.
3. Under the case-study allocation rule, a single moment spreads uniformly over its units.
   A joint moment is held out by work, apportioned by unit count. A broad moment uses its
   unit support within one scene, preserves the scene set for a bounded span over several
   scenes of one work, uses the work level if its scene set cannot be determined, and is
   held out across works (`H/projections.py:113–195`, `H/scorer-config.json:23`).
   A work or position contributes to one corresponding key.
4. A present row sums to one within tolerance. A lawful sample with unresolved assignment,
   incomplete weights or unknown remainder makes the row unavailable, with its reason.
   No onset, only non-addressed talk and only uncovered words are distinct absences.
   Prefer the non-addressed absence when non-addressed and uncovered words share a bin.

The per-bin ledger counts words with onsets once as content, state, non-addressed talk, or
uncovered (inside or outside a recall unit)
(`H/projections.py:242–266, 528–542`, `H/ff_producer.py:1075–1078`). Missing timing belongs to
the series ledger because the word cannot be put in a bin. Under all nesting, use
content, then state, then non-addressed for words covered by more than one category, and
count those overlaps. Preserve the support and outcome when the pointing row is unavailable.
A state may have a pointing row while a held level over film support is inapplicable.

Build the distribution through the projection; the checked row and its ledger belong to
that result. Reject a zero-weight key for uncovered words and dropping a sample with unknown
mass. Both hide changes in the denominator.

## FilmFestival parity and the freeze

The converter and parity run record the following differences against the saved replay.
A strict success must match each eligible row, with an enumerated refusal or declared replay
difference for every other row. Aggregate counts are supporting evidence.

- **Frame weights.** The harness clips individual values and normalizes fallback mixtures
  above 1. Refuse malformed frames and inventory the affected rows.
  (`H/ff_producer.py:821–835`, `H/baselines.py:207–213`)
- **Member weights.** The converter multiplies pass-2 weights by the pass-1 frame weight.
  Retain original weights and declare the multiplication in legacy forecast replay.
  (`H/ff_producer.py:797`)
- **Work and position weights.** The converter copies the leading frame weight for a work or
  position reference. For an unframed position it defaults to 1. Preserve
  the frame input and declare each borrowed or invented weight in legacy replay. The strict
  reference keeps a missing weight unavailable. (`H/ff_producer.py:880–889`)
- **Missing pass-2 replies.** The harness leaves words uncovered. Preserve the processing
  outcome and account for any row absence it causes. (`H/ff_producer.py:982–985`)
- **Innermost ties.** The corrected converter at `b6a11ebaa` selects the fewest covered
  inventory word cells and makes equal minima unavailable. The pinned historical replay profile
  retains first-reply selection; report it as a historical behavior, not the converter's current
  rule. (`H/ff_producer.py:1055–1060`)
- **Cross-scene broad mass.** Preserve the current scene-set key and its distinct mass in
  parity. The earlier work-level allocation remains a separate replay rule.
  (`H/projections.py:168–177`)
- **Pass pooling.** Compare condition-specific contributions, then reconstruct the legacy
  pooled rows in the parity comparator. (`H/ff_producer.py:963–985, 1043–1060`)
- **One-member forms.** Work and position stay definite. Account for any composition
  addresses outside this profile. (`H/account.py:129–166`)

Before freeze, the case-study record must identify the affected development rows and state
which behaviours were corrected or retained in the declared replay profile. An unclassified
mismatch fails parity. No sealed participant is needed for this check.

## Deferred

- Mixed-kind alternatives, when a producer emits a supported mixed reply.
- Composition addresses, when a producer points to the whole programme.
- External kinds, when a producer distinguishes an external address beyond the current profile.
- Excerpts and outside-stimulus addresses, when a producer resolves an address to unseen material.
- Comparison and attribution, when a producer emits either relation.
- Recall-order positions, when a producer distinguishes them from viewing order.
- Closing and searching within a work, when a producer distinguishes them from metamemory.
- Programme-segment selection and coordinate windows, when a temporal consumer needs them.
- Whole-showing references and per-part extent, when one measure must span axes.
- Conditioning on known samples or on film support, when a consumer needs either series.
- Retirement of aligner external states, after the talk boundary lands. See [retirement notes][old].
- Coverage, correspondence and scoring changes, when a separately scoped consumer needs them.

[slices]: ../plans/2026-11-17-cs2-library-slices.md
[timing]: 0031-reference-measures-and-held-level-projection.md
[old]: ../plans/2026-11-17-externalstate-retirement-notes.md
