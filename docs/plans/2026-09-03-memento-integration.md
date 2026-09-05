# Memento as a third corpus: what integration costs, and what it buys

*2026-09-03.* Companion to `2026-09-03-second-corpus-scouting.md`, which recommends Film Festival
first and does not mention Memento. This document does not propose changing that order. It argues
that Memento is worth a court **after** Film Festival, for a reason no other corpus supplies.

Source: `github.com/JamesWardAntony/memento` (Antony et al. 2024, *J. Cogn. Neurosci.* 36(11):2368).
Contents and measured traps: `data/README.md`. **Staged but not admitted**: the bytes sit in
`data/memento/` (the sanctioned, git-ignored home for recall prose) without the `docs/data/` source
admission record the data root requires. See "Correction on the staged bytes" below.

## Why Memento and not another corpus

Sherlock cannot distinguish two hypotheses about our aligner, because in Sherlock they coincide:

- the monotone prior is picking up **presentation order** (the order frames arrive), or
- it is picking up **story order** (the order events happened).

Memento separates them by construction. Its 44 broad scenes carry `StoryOrderSceneNum`, a complete
permutation into chronological order, and `NarrativePart` marks each of 129 subscenes as Backward
(82), Forward (26), FlashbackWithinBackward (10) or FlashbackWithinForward (10). Presentation order
is largely *reverse* chronological.

The scouting brief already notes the `hard` backward and `0.0` forward priors were fitted on
Sherlock's 97.9% non-decreasing scene index and must be re-fitted for Film Festival's ten films in
free order. Memento is the sharper version of the same question: a single narrative whose
presentation order is *deliberately* anti-correlated with its story order. If our monotone prior is
really a presentation-order prior, Memento is where it breaks, and the breakage is diagnostic rather
than merely a loss of accuracy.

Two further things Memento carries that nothing else in the survey does:

- **150 rows flagged `FalseMemory`.** The current scorers measure whether recall lands on the right
  scene. They cannot measure recall that lands on *no* scene because the participant confabulated.
  This is a precision axis the testbed has never had.
- **A 44x44 causal graph from 7 raters plus per-scene importance.** Lets us ask whether alignment
  errors concentrate on causally peripheral or low-importance scenes — i.e. whether the model fails
  where the narrative itself is weakly constrained. Use a consensus threshold and record it; the
  raters disagree substantially (see `data/README.md`).

## What integration costs

### Scala side: clean, ~40 lines

Verified in this checkout: `SherlockAnnotations.Atlas` does **not** appear anywhere in
`embed-bench/src/main/scala/storymodel4s/bench/video/`, and `align` does not depend on `acquire`
(`build.sbt:161`). `TimedSegment` (`RecallToVideo.scala:66-75`) is genuinely dataset-neutral: only
`ordinal` and `text` are required. A new corpus produces `TimedSegment`s directly and **reuses the
aligner untouched**.

The contract is three values:

1. `Vector[TimedSegment]` — Memento: one per subscene (129), `ordinal` in presentation order,
   `text` from `Explanation`, `group` = the broad scene (44) with its `broadSceneDescriptions`
   text, `locations` from `Place`, `extraLemmas` from the character columns.
2. `Map[String, PresentationAxis]` — Memento is **one** film, so a single entry, against
   `SourceBundle.filmEdition(...)`. Sherlock's two-part split is not exercised.
3. `Vector[RecallWordsCsv.RecallWord]` per subject.

Then `TimedSourceView.build(...)` → `RecallToVideo.run(...)`. Template:
`SherlockRecallMapping.scala:154-221`.

Two frictions, both in the adapter, neither in the aligner:

- `SherlockAnnotations.parse` gates on a pinned SHA-256 (`sherlock.scala:271-278`). Per-corpus by
  design — Memento writes its own manifest — but there is no shared parser to inherit.
- `RecallWordsCsv.parse` requires a header whose first column is literally `Words`
  (`RecallToVideo.scala:261-267`). Memento's recall is a 5-second grid, not per-word, so it needs a
  sibling parser. **`onsetSeconds` is `Option[Double]`** (verified), so coarse or absent timing is
  already supported downstream.

### Python side: this is the actual work

Sherlock is wired in as constants, not behind an interface:

| File | What is hardcoded |
| --- | --- |
| `gold_scene.py` | `TR=1.5`, NN01 exclusion, alias arithmetic, `Subject/Onset/Offset/Scene` schema, `NN\d\d` filename parse |
| `score.py`, `agreement.py`, `matched.py` | literal two-part offset maps `{"media-part-a": …, "media-part-b": …}` |
| `within_scene.py` | `sit:sherlock:row:` / `seg:sherlock:scene:` id regexes; Sherlock TSV column names |
| `run-arm.sh` | annotation and recall paths literal |
| `extract_scene_frames.py` | absolute paths (caption lane only) |

Two of these get *easier* for Memento rather than harder:

- Memento carries a scene id **per recall row** (`BroadSceneNum`), so the gold join skips
  `gold_scene.py`'s onset-∈-interval rule (`:39-44`) entirely — a different and simpler function.
  Film Festival has the same property.
- One film means one part, so the two-part offset maps collapse to a single-entry table.

There is no recall→scene gold type in Scala at all; the gold join is Python-only, by re-parsing the
TSV the Scala side emits. Adding a second corpus is the moment to decide whether that stays true.

## Loader traps specific to Memento

All measured, all recorded in `data/README.md`: 12 distinct header layouts in `Subjects.xlsx`
(key on names, never position); `RecallType` values include Excel dates and free text; codes 2-5 are
undocumented in the repo and the paper is paywalled; `SecondsInMinuteTime` mixes three encodings.

**The `RecallType` legend is resolved** — the owner supplied the paper. Codes (p. 2372): (1)
veridical with specific detail; (2) gist-based, temporally imprecise but **accurate**; (3)
**inaccurate** detail about the movie; (4) irrelevant commentary; (5) words not related to recall.
Strict veridical = code 1 (11,762 rows); gist-inclusive = 1+2 (16,726, both accurate); never 3, 4 or
5. Full codes and the `Detail` rubric in `data/README.md`.

One correction to what this document previously said: it left code 3 undecided. **Code 3 must be
excluded** from any accuracy measure — it is wrong content — but it is independently valuable as a
confabulation signal distinct from the `FalseMemory` column.

## Order of work

1. Film Festival first, per the landed brief. Nothing here changes that.
2. Whichever corpus goes first, build the Scala adapter against `TimedSegment` and leave the aligner
   alone. That path is verified clean.
3. Generalise the Python scorers by parameterising the five constants above — done once, it serves
   both new corpora.
4. Memento afterwards, as the adversarial test of the monotone prior, plus the two measures the
   testbed has never had: false-memory precision, and error concentration by causal centrality.

## Correcting two things this document was drafted on

**The "two lanes" are two adjudicators, not two models.** Both produce the *labels* that
`within_scene.py`'s `hit` is scored against. 66.1% is the model's hit rate under the machine lane's
labels; 25.0% is the **scene-midpoint null** on the same 124 units, and the uniform null is 19.4%.
It is one model against two nulls, not a contest between lanes.

**And 66.1% is not yet quotable.** The machine lane is diagnostic under M1 Law I1: it may not select
an arm, is never called gold, and must clear median range-Jaccard >= 0.5 against the human lane
before its numbers may be quoted at all. The human lane — the owner filling 200 answer lines — has
never been run (0 comparisons). Any new corpus inherits this gate unchanged: a Memento within-scene
number would be exactly as unquotable until a human lane exists for it.

## The cheapest useful thing to do with Memento

Three of the five scorers are **gold-free**, and the judge of record among them —
`tools/recall-study/agreement.py` — measures the median film-second gap between cross-participant
unit pairs matched by mutual-best IDF overlap **of recall text alone**. It never reads a gold file.

That means Memento can be run through the judge of record *without* solving the `RecallType` legend,
without a gold join, and without touching `gold_scene.py`. The only prerequisites are the Scala
adapter and the single-part offset table. With 133 participants against Sherlock's 17, Memento is
also by far the strongest test of cross-participant agreement the testbed has had.

Do that first. The gold-dependent work — `scene-exact`, `scene-within-1`, `scene-distance`, and
anything within-scene — can follow once the legend question is settled and an adjudication lane
exists.

## Correction on the staged bytes

An earlier note in this session said the staged directories sat "outside the process". That
overstated it. `data/` is the *sanctioned* home for recall prose: origin's `data/README.md` says
"Recall prose lives only here and in the study record derived from it. A file admitted to Git from
this tree must be content-free." The bytes are in the right place and are git-ignored.

What is genuinely missing is narrower: the **`docs/data/` source admission record** that the same
file requires before a new source is read. And `docs/design/story-text-admission-checklist.md` §3
binds at the moment any participant recall text would be *committed* — an REB/IRB basis for
redistribution, recorded by the owner, checked by someone other than the proposer. §1 separately
bars admitting recall transcripts under a public-domain basis, so "it is on GitHub" is not a basis.

Verified: none of the documents written in this session contain any participant recall text.

## The paper hands us a within-dataset control

The four `subs` conditions are four **experiments**, not four instruction tweaks, and the fourth is
the one that matters:

| cond | film | recall instruction | paper n |
| --- | --- | --- | --- |
| 1 | original (nonlinear) | freely, any order | 28 |
| 2 | original (nonlinear) | **in presented order** | 32 |
| 3 | original (nonlinear) | **in chronological order** | 32 |
| 4 | **restitched into linear chronological order**, same length | freely, any order | 36 |

Experiment 4 watched a re-edited version in which presentation order *equals* story order. So
**E1 vs E4 is the exact confound isolated**: same film, same runtime, same free-recall instruction,
differing only in whether the presentation order carries the chronology. That is a within-dataset
manipulation of the very thing Sherlock cannot separate — and it comes with two further arms (E2,
E3) that push recall order around by instruction while holding the stimulus fixed.

Concretely, for our monotone prior: fit on E4 and it should behave like Sherlock; fit on E1 and it
should not. If it behaves the same on both, the prior is tracking something other than narrative
order, and E2/E3 say whether instruction alone can move it. That is a sharper experiment than
anything we can run on Sherlock or Film Festival.

**Reconciliation needed first:** `subs` holds 134 rows split 31/33/34/36, while the paper reports
28/32/32/36 enrolled (plus 1/0/2/3 dropped for fewer than two identifiable events). Six sheets are
unaccounted for. Resolve the mapping before using condition as a factor — the same class of trap as
`partition.json`'s "33 exports are 17 participants" note.

## A structural finding worth testing against our model

The paper reports that scene memorability (proportion of participants recalling a scene) correlates
with average importance at **r = .75**, semantic node degree **r = .57**, causal node degree
**r = .43** — and, splitting the directed causal graph, with **inbound** causal weight **r = .48**
but **outbound** weight **r = .12 (ns)**. Causes predict memorability; effects do not.

That is a directional, falsifiable claim our aligner's error profile can be tested against: if
alignment errors concentrate on scenes with weak *inbound* causal weight, our model is failing in
the same place human memory does, which is evidence it is tracking narrative structure rather than
surface similarity. Nothing in Sherlock or Film Festival supports this test.

Note the paper computes semantic similarity with Google's Universal Sentence Encoder (512-dim) over
a cleaned screenplay; our pipeline uses a different ONNX encoder, so semantic numbers are not
directly comparable — the causal and importance ratings are.
