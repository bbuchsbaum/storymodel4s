# Film Festival: first end-to-end run

*2026-09-04.* The pipeline now runs on a second corpus. This records what was built, what it
measured, and the one finding that should shape what comes next. It is a development probe, not an
arm of the recall-to-video study: no partition was drawn, nothing was pre-registered, and no claim
here selects a configuration.

Corpus identity and admission state: `docs/data/filmfestival/`. Both remaining blockers are
unchanged — no acquisition court is open, and no participant recall prose is proposed for Git.

## What was built

| Piece | Where |
| --- | --- |
| stdlib xlsx reader | `tools/corpus/xlsx_rows.py` |
| annotation replay, per coder, with receipt | `tools/corpus/filmfest_annotation.py` |
| recall utterances to word-onset CSV, with receipt | `tools/corpus/filmfest_recall.py` |
| Scala adapter | `embed-bench/.../bench/filmfestival/FilmFestivalRecallMapping.scala` |
| per-corpus part offsets for the judge of record | `tools/recall-study/agreement.py` (`parts.json`) |

The aligner was not touched. The adapter produces `TimedSegment`s directly, as the seam analysis
said it could: leaves are fine segments, groups are the global 1..216 scenes, and the two scanning
runs take the place of Sherlock's two media parts. `agreement.py` keeps Sherlock's offsets as its
default, so existing Sherlock arms score bit-identically.

## The result

20 participants, one recall run each, JL annotation, ONNX sentence encoder, otherwise stock
configuration. Every participant mapped; 146-414 recall units each; **100% of units anchored**.

| | median gap | within 60 s | pairs |
| --- | --- | --- | --- |
| Sherlock, tuned, one narrative | 68.5 s [56.5, 92.5] | 47.9% | 884 |
| **Film Festival, ONNX** | **274.5 s** [204.0, 320.5] | 40.2% | 1788 |
| Film Festival, lexical fallback | 458.0 s [338.0, 549.0] | 35.1% | 1787 |
| uniform-anchor null (this corpus) | ~832 s | — | — |

## The finding: the deficit is film selection, not localization

Splitting the same 1,788 pairs by whether both anchors landed in the same film:

| | pairs | median gap |
| --- | --- | --- |
| both anchors in the **same film** | 846 (47.3%) | **11.0 s** |
| anchors in **different films** | 942 (52.7%) | 1288.5 s |

Conditional on the two participants' units being placed in the same film, the model localizes to
**11 seconds** — far tighter than the 68.5 s it achieves on Sherlock, where there is only one film
and the question cannot arise. The aggregate 274.5 s is a near-binary mixture: film chosen right, or
film chosen wrong.

**This is a conditional statistic and must not be quoted as a within-film accuracy.** Selecting
pairs by whether they agreed on the film selects the pairs the model found easy; an unbiased
within-film number needs the recall-to-scene gold, which is gold-dependent work this probe avoided.
What the split does license is a claim about *where the error lives*: the lever is film
disambiguation, not temporal precision.

That is exactly the failure mode `2026-09-03-second-corpus-scouting.md` predicted for this corpus
and which Sherlock never exercised. It also reframes the prior question the brief raised: re-fitting
the monotone prior addresses ordering within a narrative, whereas the dominant error here is
choosing among ten of them.

## Data-quality findings, all caught by validation

Three defects that would each have silently corrupted results:

1. **`duration` is `N/A` for 779 utterance rows**, all in the six files of subjects 03, 04, 05, 06
   and 15 — precisely the five the release excludes for excessive motion. A first version coerced,
   failed, and dropped them without counting: five participants vanished. Utterance spans are now
   `[onset, next onset)`, and the corpus yields 4,005 utterances / 86,351 words rather than 3,226 /
   70,171.
2. **`duration` overruns the next onset on 41% of utterance pairs** by up to a second, because it is
   rounded up. Interpolating over it ran word onsets backwards across every boundary.
3. **Coder KM's scene numbering cannot carry the gold**: 191 coarse scenes, run-1 count 81, with a
   restart and two gaps. JL (628 fine segments) and RC (489) both produce a clean 1..216. The replay
   emits an explicit `sceneNumberingUsableForGold` verdict rather than wrong scene ids. This settles
   as measurement what the scouting brief could only infer about the reference annotator.

The `+106` run-2 offset is now confirmed in output: run 2 opens at scene 107, and the emitted TSV
carries 216 contiguous scene numbers.

## Honest limits

- The playback axis is a **placeholder**: no Film Festival video is admitted, so the axis is the
  annotation's own extent, identified by the annotation's checksum. It is not a claim about a film.
- Word onsets are **interpolated within utterances**, not measured. Real word timings exist in the
  Chen lab release but need an undocumented participant-code mapping.
- Five participants' **second recall runs are excluded**; the release states no offset between runs,
  and pairing a participant against themselves would inflate agreement.
- The annotation clock and the `events.tsv` clock still differ by a few seconds; that check is owed.
- Nothing here is tuned for this corpus.

## Against the gold: film selection measured, not conditioned

The conditional 11.0 s above could not be quoted as an accuracy. The released
`recall_scenematched` gold settles it directly. `tools/corpus/filmfest_gold.py` replays it (19 files,
15 participants, 3,226 labelled utterances, 2,809 naming a specific scene) and **refuses any file
whose onsets do not match `events.tsv` row for row within 0.05 s** — none were refused, so the join
is exact rather than approximate.

`tools/recall-study/filmfest_gold_film.py` scores each report unit against the gold utterance its
recall span most overlaps. Units whose gold label is `memory search`, `gave up` or `off task` are
never scored: an off-task remark has no correct anchor and charging it to the model would be a
scoring error, not a result.

| arm | film correct | 95% CI | per-participant median |
| --- | --- | --- | --- |
| **ONNX encoder** | **67.2%** | [65.7, 68.7] | 61.0% |
| lexical fallback | 63.7% | [62.2, 65.3] | 58.1% |
| duration-weighted guess | 11.3% | — | — |
| always answer the most-recalled film | 18.1% | — | — |

3,688 units scored across 15 participants; 31 skipped as off-task, 71 with no overlapping gold.

Six times chance, and it independently corroborates the agreement split: if each unit is placed in
the right film 67.2% of the time, two units agree on the film about 45% of the time, against the
47.3% actually observed.

## Where the error concentrates

Comparing how often the model places a unit in each film against how often the gold does:

| film | screen time | gold | model | model / gold |
| --- | --- | --- | --- | --- |
| Cartoon intro 2 | 1.5% | 0.3% | 5.7% | **19.0x** |
| Bus Stop | 14.6% | 12.6% | 24.0% | **1.9x** |
| Post-it Love | 5.9% | 3.8% | 6.2% | 1.6x |
| The Boyfriend | 16.8% | 18.1% | 7.9% | **0.44x** |
| The Prisoner | 9.5% | 8.2% | 4.2% | **0.51x** |

Two biases, both diagnosable:

1. **The cartoon pre-roll is an attractor.** *Let's All Go to the Lobby* is 39 s of generic
   high-frequency vocabulary — people, popcorn, singing, candy — that matches loosely against
   everything. It takes 5.7% of all placements while the gold sends 0.3% of recall there. It is not
   part of the narrative task; the gold does not even give it a film code, only `lobby`.
2. **Long, event-dense films absorb units from their neighbours.** Bus Stop has the most scenes of
   any film and takes nearly twice its share, while The Boyfriend — the longest film by screen time
   — takes less than half of its. The Boyfriend is *High Maintenance*, one of the five films with
   speech, and this adapter feeds the aligner annotation prose only.

Neither is a claim about a fix. The obvious first test is excluding the two cartoon intros from the
candidate set, which would free about 5.4% of placements currently spent on a block accounting for
0.3% of recall — but whether they redistribute correctly is exactly what the experiment has to
show, not something to assume.

## Standing limits on these numbers

This is still a development probe. No partition was drawn, so every participant with gold was used;
nothing is pre-registered; and no configuration has been selected on the strength of it. The five
participants without gold are unscored, and second recall runs remain excluded.

## Next

1. ~~Measure film-selection accuracy against the gold.~~ Done above: 67.2% against 11.3% chance.
2. Test excluding the two cartoon intros from the candidate set. Cheapest available lever, and the
   bias it targets is measured rather than suspected.
3. Ask whether The Boyfriend's deficit is a speech-film deficit. Five of the ten films carry
   dialogue and this adapter gives the aligner annotation prose only; the corpus has no subtitles
   for four of those five, so this may bound what the text lane can do here.
4. Only then the prior re-fit. Ordering is a second-order problem on a corpus that is still placing
   a third of its units in the wrong film.
