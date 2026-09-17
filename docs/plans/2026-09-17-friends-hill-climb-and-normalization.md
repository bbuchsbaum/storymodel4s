# Friends without the video: what it can tune, and the standardization it forces

*2026-09-17. Written against `main` at `43140bcb`. Companion to
[`2026-09-16-friends-intake.md`](2026-09-16-friends-intake.md), which accounts for the bytes and
proposes admission. This document opens no court and commits no Friends artifact: the intake files
remain untracked at the owner's request. Every number below was measured from the git-ignored data
root on 2026-09-17 and is reproducible from the workbooks.*

Two questions were asked: how Friends improves the method while the video is missing, and whether
the annotation heterogeneity across corpora deserves a normalization layer. The answers are
connected — the second is the reason the first is cheap.

## 1. The video is not on the critical path

The aligner's stimulus side is text. `TimedSegment`
(`embed-bench/src/main/scala/storymodel4s/bench/video/RecallToVideo.scala:66-75`) requires
`ordinal` and `text`; `locus`, `group`, `extraLemmas`, `locations`, `embedText` and `lexicalText`
are all optional. Nothing in `align` consumes frames.

The precedent is in the repository already. The Film Festival adapter runs the full pipeline with
no film bytes (`FilmFestivalRecallMapping.scala:27-31`):

> There is no admitted media edition. The playback axis here is the **annotation's own extent**,
> identified by the checksum of the replayed TSV, not by any film's bytes.

Friends takes the same route, and is better supplied than Film Festival was.

### The storyboard is a complete, timed presentation axis

`friendsStoryBoard.xlsx!FriendsNarrComb` is 52 events plus a terminal sentinel row, over 21
columns:

| Column | Content |
|---|---|
| `Episode` | 1 (30 events) or 2 (22 events + sentinel) |
| `EventModelNum` | 1–52, dense, presentation order |
| `TimeOrig` | seconds on the **original broadcast episode** clock, resetting at episode 2 |
| `Time` | seconds on the **concatenated edited clip**, strictly increasing 0 → 2515 |
| `Character1`–`Character7` | 17 name tokens, 7 slots |
| `Place` | 10 locations |
| `Explanation` | the event description — the segment text |
| `SceneNum` | 1–30, the coarse group |
| `EventModel1` | storyline, 5 values |
| `EMchangeWSTchange` | 0/1 on 29 rows |

Times are Excel 1900-epoch datetimes; subtract `datetime(1900,1,1)`. Columns R–U are a stray
legend block, not data.

That is a `TimedSegment` vector with nothing reconstructed: `ordinal = EventModelNum`,
`text = Explanation`, `group = SceneNum`, `extraLemmas = Character1..7 ∪ Place`,
`locations = Place` — the shape `SherlockRecallMapping.scala:158-187` builds from `namesAll`,
`namesSpeaking` and `location`. Offsets come from differencing `Time`, with the sentinel closing
event 52. Median inter-onset 34 s, range 5–228 s.

**There is a second text channel.** `friendsTranscript` (694 rows) has a `Scene` column that is
*not* `SceneNum` — it runs 1..52 and is the event id on the 52-scale. Every one of the 52 events
therefore has its own verbatim transcript block, 1 to 64 lines each, verified four ways
(1:1 coverage, episode agreement, bracketed stage headers falling exactly on the 29/30 `SceneNum`
openings, character overlap on 49/52). Description text and dialogue text per event is exactly the
`embedText` / `lexicalText` split the codebase already distinguishes.

**The two clocks are the edit map.** Cumulative excision is 91 s across episode 1 and 24 s across
episode 2. The cut that the video would otherwise have been needed to establish is *measurable*
from the workbook. The exact cut stays unreconstructed as a claim; the axis does not depend on it.

**The axis is independently validated, behaviourally.** The event-segmentation logs name the
stimulus `Friends_comb.mp4` and give its duration in 50 independent runs: median 2518.5 s, sd
0.25 — against the storyboard's terminal sentinel of 2515 s (`FriendsNarrComb`) / 2519 s
(`FriendsMoreEMs`). And 2,185 button presses from 42 participants land on event onsets far above
chance: 22.2% within ±1 s against 4.1% chance (5.4×), 39.2% within ±2 s against 8.2% (4.8×); mean
press-to-nearest-onset distance 11.14 s against a uniform null of 20.24 s (z = −21.1); 47 of 52
onsets are hit within 3 s by ≥5 presses. The presses and the storyboard are on the same clock.

What the missing video still costs: the caption arms (`STORYMODEL4S_SCENE_CAPTIONS`,
`caption-scene-dev`, `scene-frames`), frame extraction, any perception lane, and human
within-scene adjudication against playback. None is on the path to the experiments below.

## 2. What Friends measures that no corpus we hold can measure

`MonotoneScene.scala:71-79` states the risk in the project's own words:

> **This default is fitted to this corpus and should not travel unexamined.** ... the human coding
> is 97.9% non-decreasing ... A corpus with genuine reminiscence, a non-linear narrative, an
> interviewer prompting revisits, or **recall of several stories at once** would not look like
> this, and the constraint would then be doing real damage rather than 2.1% of it.

Friends *is* recall of several stories at once, and the effect is large.

### Measured, 2026-09-17

**Stimulus side** (52 events, `EventModel1`): five storylines — Phoebe/Ursula/Joey 14,
Rachel/Monica 11, Marcel/SAP 10, Ross parenting 9, Chandler/Nina/boss 8. **76.5%** of consecutive
events switch storyline; the longest run of one storyline is **2 events**. A near-perfect five-way
interleave.

**Recall side** (23 participant sheets, `SecondsOfRecall` × `WhichEvent`; uses only the scorer's
own ordinal space, so it is independent of the event-scale crosswalk):

| Statistic | Friends | Sherlock |
|---|---|---|
| Spearman ρ, recall time × event ordinal, per participant | median **0.39**, mean 0.41, range **−0.53 … 0.98**, 4 of 23 negative | — |
| Same, computed **within storyline** | median **0.90**, mean 0.71, 2 of 23 negative | — |
| Recalled event sequence non-decreasing (adjacent transitions) | **74.1%** | **97.9%** |
| Same, within storyline | **81.5%** | — |
| Recall transitions that switch storyline | **38.2%** | — (single thread) |

The two views agree on the direction and disagree on the size, and both belong in the record. On
the global rank measure, threading is transformative — s6 goes 0.392 → 0.939, s26 0.046 → 0.902,
s21 −0.526 → 0.874. On the harsher adjacent-transition measure it buys 7.4 points. Read together:
**participants replay each storyline in order and interleave the storylines freely**, so the
sequence is globally scrambled and locally ordered.

The scoring legend explains why: scorers were instructed that storyline recall "MUST ENUMERATE."
This is how people recall a braided sitcom, not an artifact of a single coder's habit.

Three consequences, in order of value:

1. **`backwardPenalty = hard` is the wrong prior here, and the decode is 81% of the headline
   number.** About a quarter of human-coded adjacent transitions step backwards. The +17.67 points
   the monotone decode contributes on Sherlock is a prediction about corpora; Friends is where it
   gets tested rather than assumed.
2. **The thread is the missing state.** A per-thread monotone chain with a thread-switch cost is
   the obvious model, and unlike most representation arms it has a measured target to hit
   (ρ 0.39 → 0.90) rather than a hope.
3. **Both sides carry thread labels** — `EventModel1` on the stimulus, `WhichStoryline` on recall
   — so within-thread versus across-thread error decomposition is available without any new
   annotation.

### `WorldOrderInput` is under-typed for this corpus

`WorldOrderInput.Explicit(byOrdinal: Map[Int, Int], witness)`
(`embed-bench/.../video/WorldOrderInput.scala:54-57`) expresses only a **total** order. Sherlock
declares `SameAsPresentation`; Memento would declare a permutation. Friends fits neither: five
storylines run *concurrently* in story time, so events in different threads are genuinely
unordered with respect to each other, and a total rank would invent ordering facts. The honest
options are `Unknown(NotSupplied)`, which discards the structure, or extending the type to carry a
partial order or per-thread chains. A design finding, not a blocker — but it should be an ADR
before any Friends world-order claim is made.

### Gold the testbed does not currently have

- **Per-utterance recall→event gold**, 23 participants, **630 recall units** (contiguous
  `WhichEvent` runs, median 23 per participant, median run 24 s, no 1-second runs). Per-event
  coverage median 11 of 23 participants. Sherlock's gold is scene onsets only.
- **Gold for the recall segmenter, which has none today.** `RecallSegmenter.segment`
  (`recall/.../segmenter.scala:400-420`) cuts recall into one unit per *clause*; the human scorers
  cut by event reference. No Python scorer evaluates unit boundaries at all. Friends gives 630
  event-level units and a finer 1,268 transcript-utterance runs — two reference granularities.
- **Specificity per row** (`Detail` 1–4: 1,825 / 6,035 / 7,748 / 5,151) — accuracy conditioned on
  how much the participant actually said, and a principled target for risk-coverage after the
  finding that the flagship arm's confidence orders units no better than chance.
- **Six independent causality/importance raters.** 52×52 upper-triangular causal matrices, scale
  0–10, mean pairwise Pearson **0.675** (range 0.567–0.824), mean Jaccard on binarised links
  0.507; 41 of 1,326 pairs marked by all six. Importance 52-vectors, mean pairwise Spearman 0.636,
  **Spearman–Brown reliability of the 6-rater mean 0.905**. The mean is usable as a feature; a
  single rater is not.
- **Human event boundaries from 42 participants** on the same clock as the stimulus — 2,240
  keypresses, median 30 per participant.

That last item unblocks a rung the ladder readout explicitly leaves open
(`2026-09-05-navigation-ladder-readout.md`): *"+external and +sensory remain unimplemented rungs.
Identical causality/similarity outcomes on this source do not establish that these relations are
unhelpful on a source that supplies them."* Friends supplies them. `features` has been waiting for
a producer since ADR 0011, and `FeatureUseLedger` with its `excluded` set and `CircularityWarning`
(`features/.../ledger.scala:15-58`) already makes withhold-the-feature-under-test ablations
first-class.

### What the gold does not cover

`WhichEvent` is populated **if and only if `RecallType == 1`** (veridical) — 20,735 of 20,735,
with two anomalous exceptions. Gist recall (`RecallType == 2`, 1,093 rows) carries
`WhichStoryline` but **no event id**. So the event-level gold is a veridical-recall gold, and
storyline is the only label available for gist. Any coverage claim must say so. Non-recall codes
4/5/6 are 5.12% of labelled rows.

`WhichEvent` is also a single scorer's judgment with no inter-coder reliability anywhere in the
source set.

## 3. The event-scale crosswalk: solved, and it must become a contract

The intake record left the raw-to-canonical mapping open, pending the upstream notebook. The
notebook is not needed — the map is recoverable from the workbooks, and the recall gold's scale is
now identified.

**`WhichEvent` is on a 56-event scale, not the storyboard's 52.** `FriendsMoreEMs` *is* that
56-event scale, sharing the `Time` axis with `FriendsNarrComb`. Two independent proofs:

1. `WhichStoryline == 'Interlude'` occurs in exactly 67 rows, and `WhichEvent == 37` occurs in
   exactly 67 rows; event 37 on the 56-scale is `Interlude, door action`.
2. Collapsing the 56-scale `EventModel1` (9 finer values) to the legend's 5 storyline codes agrees
   with the scorer's `WhichStoryline` on **98.97%** of rows (20,512 / 20,725). The same test on
   the 52-scale gives **52.72%**.

The piecewise map, joining the 56-, 54- and 52-event sheets on the shared `Time` axis:

```
em56  1..18 -> em52  1..18  (shift  0)
em56 19     -> REMOVED  (interlude, door action, t=722)
em56 20..36 -> em52 19..35  (shift -1)
em56 37     -> REMOVED  (interlude, door action, t=1571)
em56 38     -> em52 36      (shift -2)
em56 39     -> REMOVED  (merged, Phoebe/Ursula birthday, t=1639)
em56 40..47 -> em52 37..44  (shift -3)
em56 48     -> REMOVED  (merged, Phoebe/Ursula/Joey, t=2149)
em56 49..56 -> em52 45..52  (shift -4)
```

**Hazard:** three matched events carry `Time` values that differ between the two sheets (146/147,
763/766, 2005/2007). An exact-equality join silently drops them and yields a wrong map. Join on
`(TimeOrig, Time ± 5 s)` or on `(Explanation, SceneNum)`, and assert a bijection of size 52.
Nothing in `FriendsNarrComb` marks the removed or merged events; the provenance lives solely in
`FriendsMoreEMs`, which must be retained as the mapping's source of truth.

This is the first thing to build, and it is the archetype for the `SegmentLink` type in §5: a
versioned, evidenced mapping between two segmentations of one work, with its own oracle, not
arithmetic hidden inside a scorer.

## 4. The experiment program

Ordered by value per unit of work. Nothing here needs video.

**F0 — draw the partition first.** 23 participants, seeded split, the rules already written in
`data/study/recall-to-video/partition.json` ("Development chooses every recipe. The untouched set
is unsealed once and chooses nothing."). Suggest 14 development / 9 untouched. Do this *before* a
model is run. The statistics in §2 were computed over all 23; they describe the human coding, in
the same class as Sherlock's 97.9%, and must not select an arm.

**F1 — the crosswalk contract** (§3), with the bijection assertion and the time-tolerance join as
tests.

**F2 — adapter and a cold run.** ~200 lines against `TimedSegment`, following
`FilmFestivalRecallMapping.scala`; description as `embedText`, transcript block as `lexicalText`.
Then run the shipped configuration unchanged, on development only. A pure generalization test; the
size of the drop is the headline, and it is interpretable only if nothing was tuned first.

**F3 — refit the order prior.** Sweep `STORYMODEL4S_BACKWARD_PENALTY` (default `hard`) and
`STORYMODEL4S_FORWARD_PENALTY` (default `0.0`) on development. The pre-registered prediction is
that `hard` loses on Friends and a permissive penalty wins — the opposite direction to Sherlock.
Record the prediction before running. A confirmed reversal turns a fitted constant into a
measured, corpus-conditional parameter; a failure to reverse is equally informative and cheaper to
learn now than later.

**F4 — the braided decode.** Per-thread monotone chains plus a thread-switch cost, factorial
against the decode per the open defect ("any representation arm must be factorial against the
decode, on three corpora, or it is uninterpretable"). Target: the ρ 0.39 → 0.90 gap.

**F5 — the granularity factor.** The same recall against nested stimulus segmentations of the same
episode, all on one `Time` axis: 30 scenes, 52 events, 54 events, 56 events, 694 transcript lines.
No other corpus we hold offers this. It separates "the method is accurate" from "the metric's bins
are generous."

**F6 — the segmenter's first evaluation.** Score `RecallSegmenter`'s clause units against the 630
event-level units and the 1,268 utterance-level runs, boundary F1 against a shuffled-boundary
null.

**F7 — the +causality and +external rungs.** Six-rater causal graph and importance as `features`
tracks with per-rater provenance and blanks read as 0; 42-participant press density as a
`BoundarySignal`. Test the Memento result (inbound causal weight predicts memorability, r = .48)
as a prior on occupancy. Declare every use through `FeatureUseLedger` so the circularity question
stays answerable. Use the 6-rater mean (reliability 0.905), never a single rater, and z-score or
rank importance before averaging — rater means span 2.71 to 7.57.

**F8 — specificity-conditional risk-coverage.** Accuracy and abstention conditioned on `Detail`
1–4.

**F9 — non-veridical recall.** `RecallType` 3 (18 rows), 590 commentary and 459 unrelated rows,
and 1,531 `False memory?` flags give labelled instances for `ModeGate` / `ContradictionDetector`,
which has never refused anything on Sherlock.

## 5. The normalization layer

### The heterogeneity is real, and it is structured

Not four unrelated formats — two lab dialects plus a physical-encoding problem.

**Antony dialect — Friends and Memento.** Identical `RecallType` 1–5 and `Detail` 1–4 code books,
identical 0–10 causality and 1–10 importance scales, per-row recall labels, false-memory flags,
independent rater workbooks. They differ in recall grid (Friends 1 s, Memento 5 s), column names
(`WhichEvent` vs `BroadSceneNum`), code cardinality (Friends adds 6 = inaudible), and layout
stability (Memento has 12 header-layout variants across its own 133 sheets).

**Chen dialect — Sherlock and Film Festival.** Timed annotation tables with per-rater
arousal/valence, scene onsets as separate gold files, no recall-quality codes at all, TR-based
clocks.

**And drift inside our own records.** The four `docs/data/*/source-manifest.json` declare four
schema names and share exactly seven keys: `artifactSetId`, `contentPolicy`, `nonClaims`,
`recordedAt`, `schema`, `schemaVersion`, `scientificRole`. `admissionStatus` and `verification`
appear in three, `upstreamSources` in two. Film Festival calls its inventory `artifactGroups`;
Friends and Memento call the same thing `artifacts`; Sherlock has neither. That divergence is
entirely ours.

**Worse: none of it is read by code.** No Scala, Python or shell reads any
`docs/data/*/source-manifest.json`. The four references to `docs/data` in the codebase are all
comments. The Sherlock timebase crosswalk exists three times — as JSON in
`docs/data/sherlock/timebase-repair.json`, hand-transcribed into `sherlock.scala:45-67`, and
transcribed a third time into `SherlockAnnotationsSuite.scala:188-199`, which asserts the literals
rather than opening the JSON. Editing any source manifest changes no behaviour and breaks no test.
The machinery to fix this already exists and is test-only: `FixtureManifest.parse` /
`verify(bytes)` (`media/.../manifest.scala:96-133`) checks `schema` and `schemaVersion` and refuses
on length or hash mismatch, but is called only from `MediaProbeSuite`.

### What to standardize, and what not to

Four layers; three worth touching.

1. **Physical encoding** (xlsx, zipped PsychoPy, TSV, BIDS) — leave idiosyncratic. One replay
   script per corpus, as `tools/corpus/filmfest_annotation.py` already is. `xlsx_rows.py` is
   already shared and both Antony-dialect corpora inherit it.
2. **Dialect** — a declared, versioned **profile**: source column → canonical field, code value →
   canonical code, with the code book's citation. Unmapped codes become `Unmapped(raw)`, never
   silently collapsed; every canonical value keeps its `SourceCoordinate(file, sheet, row, column,
   rawValue)`. Friends is the stress test: `WhichStoryline` has every code as both string and
   float, `WhichEvent` has 60 string cells, `Detail` 8, `SecondsInMinuteTime` is 14,439 strings
   and 13,330 Excel datetimes, and `False memory?` carries an enumeration counter `'2'..'17'` in a
   binary column. These are handled by typing, not repair.
3. **Conceptual schema** — small, and nearly universal already: `Work`/`Edition`; `Clock` (units,
   origin, run-relative or global); `Segmentation` (a *named, versioned* partition at a
   granularity level, with an authority — author, crowd, derived, participant-consensus);
   `SegmentLink` (an evidenced mapping between two segmentations of one work); `RecallSession` /
   `RecallUnit`; `RecallLabel` (unit → segment, with coder identity and the code's condition of
   applicability — Friends' "event id only when `RecallType == 1`" is exactly such a condition);
   `RaterTrack` (per-rater, aggregation *not* pre-applied); `BoundaryTrack`.
4. **Capability** — the part that must become first-class. Each corpus declares what it has
   (`hasStimulusClock`, `goldGranularity`, `goldCoverage`, `goldCoderCount`, `hasThreadLabels`,
   `hasStoryOrder`, `hasBoundaryPresses`, `causalRaters: n`, `hasVideo`, …). Each experiment
   declares what it requires. The harness resolves and **refuses, typed**, when a corpus cannot
   support an experiment — the idiom the 1.0 work just established at the wire for
   `RequireMinCoverage`, `MassRatio` and `AlignError.EmptyPopulation`. "Which experiments can this
   corpus run" becomes a computed answer instead of an archaeology project.

Two named consequences: `SegmentLink` makes remaps data with provenance rather than arithmetic
inside a scorer (Film Festival's +106, Friends' 56→52, Sherlock's 1000→50); and `Clock` makes the
Film Festival `min.sec`-decimal trap and the Friends Excel-datetime trap type errors rather than
silent wrong numbers.

### Where it lands

`tools/corpus/` is already the embryo. `filmfest_annotation.py` replays a workbook into a
9-column TSV with a receipt, and those columns —
`segment | part_id | run | film | scene_number | coarse_start_s | start_s | end_s | description` —
are within one rename of the canonical stimulus-segmentation table. The work is to (a) declare
that table plus a recall-unit table, a recall-label table and a rater-track table as a versioned
interchange; (b) have the Scala adapters read the canonical shape rather than corpus-specific
columns; (c) make `docs/data/*/source-manifest.json` **machine-read** — the 1.0 plan's T8
(manifest sha256 verifier) is the hook, and `FixtureManifest` is the existing pattern to
generalize.

`SherlockSceneCoding.scala:14-15` already names the cost of not doing this: *"Two implementations
of one rule is one too many, so this one cites the other"* — the gold-eligibility rule exists once
in Scala and once in `gold_scene.py`.

### Sequencing, and the trap to avoid

Do **not** design the standard first. Standardizing on n = 1 produces a schema shaped like
Friends.

1. Build the Friends adapter against a deliberately minimal canonical schema (F2).
2. Build the Memento adapter — same dialect, different clock, different narrative topology — and
   let it break the schema. Two corpora in one dialect plus Sherlock in another is enough evidence
   that the schema is real.
3. Only then lift the manifest to `storymodel4s.corpus.source-manifest v2` (the seven common keys
   + `capabilities` + `clocks` + `segmentations` + `codeBooks` + `extensions.<corpus>`) and write
   the capability-refusal ADR.

The strategic argument is the one already on record: supremacy is won by *releasing* the
benchmark, not by winning it. A declared interchange with adapters for four corpora is the
releasable artifact.

## 6. Corrections owed to the intake record

`docs/data/friends/source-manifest.json` and its README are wrong in four places, all found by
direct measurement. They are untracked, so nothing has shipped; they should be corrected before
they are ever staged.

1. **`friendsSRMStoryBoard` is not 57 events from this stimulus.** It holds **14 events plus an
   `[end]` sentinel**, and it is a **different episode** — S01E18, *The One With All The Poker*
   (poker, Aunt Iris, Rachel's interview, Ross's apartment). The `<dimension ref="A1:O58">` the
   manifest counted is style-only formatting. There is no crosswalk to `FriendsNarrComb` and none
   is possible. It must never be joined to the main storyboard. The manifest's "57 event rows" is
   wrong twice over.
2. **The `CausalityRatings` (54×54) and `ImportanceRatings` (53×2) sheets embedded in
   `friendsStoryBoard.xlsx` are empty templates.** The causality sheet's 211 non-null cells are
   104 index labels and 107 `Explanation` strings — **zero rating values**; the importance sheet
   has zero numeric cells. Recording their dimensions implies content that does not exist. The
   only causality and importance data in the source set is in `ratings.zip`.
3. **All 57 event-segmentation CSVs are empty** — 63 bytes each, a BOM plus a header, zero data
   rows. The presses are only in the `.log` files, on the experiment clock, and must be shifted by
   the `movie: autoDraw = True` marker; the pre-onset "press space to start" key must be dropped;
   seven logs lack the end marker. The manifest's "parsing must select a declared file contract"
   understates this: the declared CSV contract contains no data.
4. **`OrigFriends` is empty** (10 styled rows, zero non-null cells), and the recall workbook's
   column J `Recall types` is a pasted copy of the legend present in only 18 of 23 sheets, not
   data.

Two further hazards worth recording: **r5's causality sheet writes explicit `0`** in ~1,174
non-causal cells where the others leave blanks (1,347 filled cells vs ~150), so a loader that
counts filled cells rather than non-zero values will rank r5 a wild outlier — blank ≡ 0; and
**six sheets carry trailing padding** (s16, s19, s20, s23, s25, s26; four pre-filled to
`SecondsOfRecall` 3000), so the real extent must be taken from the last row with any of columns
C–I populated, not from the seconds column. Content-bearing rows total 27,777.

## 7. Constraints this plan operates under

- The Friends intake files are **untracked and must not be committed**; no participant prose or
  stimulus transcript may enter Git. Everything above reads the git-ignored data root and emits
  content-free artifacts only.
- Admission state is `proposed`. No court is open. The owner's 2026-09-04 licence decision for
  Memento (open-science use, no redistribution, recorded disclaimer) is precedent, not a ruling
  here; the human-subject provenance question under
  `docs/design/story-text-admission-checklist.md` §3 is separate and unresolved.
- `WhichEvent` is one scorer's judgment with no inter-coder reliability. Calling it gold requires
  either an independent re-code of a sample or an explicit statement of the limitation on every
  number derived from it. Under M1 Law I1 a machine adjudication lane is diagnostic, may not
  select an arm, and must clear median range-Jaccard ≥ 0.5 against a human lane first.
- The §2 statistics were computed across all 23 participants. Draw the partition before a model is
  run.

## 8. Non-claims

- No Friends loader, adapter, normalized table, alignment result or fixture exists.
- The crosswalk in §3 is derived and internally cross-validated, but is not yet a tested contract
  and has not been checked against the upstream notebook, which is not present locally.
- No admission court is opened and no licence or ethics determination is made.
- The braided decode is a hypothesis with a measured target, not a result.
- The exact edited presentation cut is not reconstructed; only the two clocks, their drift, and
  the clip duration measured from the segmentation logs.
- The event-segmentation group assignment (notebook-declared 20 + 25) cannot be recovered from the
  archive; the directories carry no group label.
