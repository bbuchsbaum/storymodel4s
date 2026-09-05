# Film Festival as a second corpus: handoff

What a fresh agent needs to pick up the second-corpus line.

**Read `2026-09-04-own-the-metric.md` item 1 first.** Another session wrote it independently and it
specifies exactly this work — same seam, same "gold-free scorers first" strategy, same `+106` trap.
What follows is its execution, carried past the point that plan stops at. The other design records
are `2026-09-03-second-corpus-scouting.md` (the landed brief; it recommends this corpus),
`2026-09-03-second-dataset-survey.md` (the wider sweep, including Memento) and
`2026-09-04-filmfestival-first-run.md` (results). The admission records are
`docs/data/filmfestival/` and `docs/data/memento/`. This file is the operational brief.

## 1. Where it stands

Film Festival runs end to end through the existing pipeline and has been scored against its own
released gold. Nothing is committed.

| | state |
|---|---|
| `main` | in sync with `origin/main` at `cc11d142` (moved twice mid-session; re-verify) |
| pipeline | runs on Film Festival, 20 participants, 100% of units anchored |
| judge of record (gold-free) | 274.5 s median gap [204.0, 320.5], vs Sherlock's 68.5 s and a ~832 s null |
| film selection (gold) | **67.2%** [65.7, 68.7] against 11.3% duration-weighted chance |
| aligner changed? | **No.** The adapter emits `TimedSegment`s; `align` and the aligner are untouched |
| own-the-metric item 1 | done for FilmFestival, through to gold scoring; Memento still parked |
| Sherlock scoring changed? | **No.** `agreement.py` gained a `parts.json` override that defaults to Sherlock's map; existing arms score bit-identically |
| corpus admitted? | **No.** Both records say `admissionStatus.state = "proposed"` |

### What is uncommitted

Everything below is working-tree only. `mission.md`, `vision.md`, `README.md` and
`docs/plans/2026-09-03-navigation-assessment.md` are **another session's** work in this shared
checkout — do not commit or revert them.

Mine: `tools/corpus/` (4 new files), `tools/recall-study/filmfest_gold_film.py`,
`embed-bench/.../bench/filmfestival/FilmFestivalRecallMapping.scala`, the modification to
`tools/recall-study/agreement.py`, `docs/data/filmfestival/`, `docs/data/memento/`, and three
`docs/plans/` documents dated 2026-09-03/04.

### The loop you will use constantly

```bash
D=$(bash tools/data-root.sh)
python3 tools/corpus/filmfest_annotation.py JL "$D/filmfestival/derived/annotation-JL.tsv" \
  "$D/filmfestival/derived/annotation-JL.receipt.json"
python3 tools/corpus/filmfest_recall.py "$D/filmfestival/derived/recall" \
  "$D/filmfestival/derived/recall.receipt.json"
python3 tools/corpus/filmfest_gold.py "$D/filmfestival/derived/gold-scenematched.tsv" \
  "$D/filmfestival/derived/gold-scenematched.receipt.json"
# one participant, ~4 s; set the ONNX vars or you silently get a lexical fallback
export ORT_DISABLE_TELEMETRY=1
export STORYMODEL4S_ONNX_MODEL="$D/models/onnx/model.onnx"
export STORYMODEL4S_ONNX_TOKENIZER="$D/models/onnx/tokenizer.json"
sbt -batch "embedBench/runMain storymodel4s.bench.filmfestival.filmFestivalRecallMap \
  $D/filmfestival/derived/annotation-JL.tsv \
  $D/filmfestival/derived/recall/sub-01_run-01.csv /tmp/probe.tsv"
# scoring
python3 tools/recall-study/agreement.py LABEL "$D/study/filmfestival/jl-onnx-dev"
python3 tools/recall-study/filmfest_gold_film.py \
  "$D/filmfestival/derived/annotation-JL.tsv" "$D/filmfestival/derived/gold-scenematched.tsv" \
  LABEL "$D/study/filmfestival/jl-onnx-dev"
```

A full 20-participant arm takes roughly 8 minutes and must be backgrounded; the tool timeout will
kill it otherwise. An arm directory needs a `parts.json` of
`{"run-01": 0.0, "run-02": 1490.0}` or `agreement.py` will silently score nothing.

## 2. What is true, and what is not

**True and measured.** The pipeline places a recall unit in the right film 67.2% of the time, six
times a duration-weighted guess. The `+106` run-2 scene offset is confirmed in emitted output, not
inferred. The gold joins to `events.tsv` exactly — the replay refuses any file whose onsets drift
past 0.05 s, and none did. Coder KM's numbering cannot carry the gold (191 scenes, run-1 count 81,
with a restart and gaps); JL and RC both produce a clean 1..216.

**Not true, and do not let it drift into being claimed.** The 11.0 s within-film figure in the
results doc is *conditional on both anchors landing in the same film* and is not an accuracy — it
selects the pairs the model found easy. The playback axis is a **placeholder** identified by the
annotation's checksum; no Film Festival video is admitted and nothing may read the axis as a claim
about a film. Word onsets are **interpolated within utterances**, not measured. Nothing is tuned for
this corpus, no partition has been drawn, and no configuration has been selected on any of these
numbers.

## 3. Traps, each of which cost real time

1. **The ONNX encoder is opt-in and fails silently.** Without `STORYMODEL4S_ONNX_MODEL` the run
   falls back to a lexical channel and still succeeds. That is a 274.5 s vs 458.0 s difference.
   Check the `semantic channel:` line says `neural:onnx-sentence-encoder`.
2. **`duration` is `N/A` for 779 recall rows** — all in subjects 03, 04, 05, 06, 15, exactly the
   five the release excludes for motion. Coercing to float and skipping on failure loses five
   participants without a word of warning. Span rule is `[onset, next onset)`.
3. **`duration` overruns the next onset on 41% of utterance pairs** because it is rounded up.
   `[onset, onset + duration)` runs word onsets backwards across every boundary.
4. **Annotation times are `min.sec` decimals** arriving as dirty floats (`6.1000000000000005` is
   6:31). Format to two decimals and split; never treat as a number. RC additionally writes two
   values whose seconds field is exactly 60, meaning a rollover, verified against neighbours.
5. **Memento's `Time` column is a date-formatted cell.** `tools/corpus/xlsx_rows.py` deliberately
   does not apply number formats, so use `serial_time_seconds()`; a raw read gives meaningless
   floats. The reader was differentially tested against openpyxl on five sheets — zero cell
   disagreements outside that column.
6. **`agreement.py` has no `__main__` guard**; importing it runs the CLI. Strip the trailing
   `main(sys.argv)` before `exec` if you need its helpers.
7. **The formatter rewrites files after every write.** String-replacement patches against a file you
   wrote earlier in the same session will miss. Read first, and assert every replacement.
8. **`git pull` refuses untracked files that collide with incoming ones**, even when byte-identical.
9. **`TimedSourceView.build` is about to change signature and will break this adapter.**
   `solo/world-order-input` (ADR 0013, worktree `.worktrees/worldorder`) makes it return
   `Either[WorldOrderRefusal, Built]`. `FilmFestivalRecallMapping.scala` calls it bare inside its own
   `Either` chain and will not compile once that lands. The fix is small — flatMap it instead of
   wrapping — but do it on that branch rather than in parallel, per the collision note in
   `2026-09-04-own-the-metric.md` W1 item 0(b). Verified compiling against `cc11d142` today.

## 4. What I would do next, in order

1. **Exclude the two cartoon intros from the candidate set.** *Let's All Go to the Lobby* takes 5.7%
   of all placements while the gold sends 0.3% of recall there — a 19x over-selection of 39 seconds
   of generic vocabulary that is not part of the narrative task. Cheapest lever, and the bias is
   measured rather than suspected. Whether those placements redistribute *correctly* is the
   experiment; do not assume the gain.
2. **Ask whether The Boyfriend's deficit is a speech-film deficit.** It has the most screen time and
   the model finds it least (0.44x). It is *High Maintenance*, one of five films with dialogue, and
   this adapter feeds the aligner annotation prose only. Four of those five have no subtitles
   anywhere, so this may bound what the text lane can achieve on this corpus rather than being a
   configuration problem.
3. **Then, and only then, the prior re-fit** the scouting brief calls for. Ordering is second-order
   while a third of units are in the wrong film.
   Note `own-the-metric` item 2 wants a human ceiling as the denominator; film selection now has a
   real one (11.3% duration-weighted chance, 67.2% achieved), so this corpus can contribute that
   argument without waiting on an adjudication lane.
4. If a real arm is wanted, **draw a partition first**. Everything so far is a development probe on
   all available participants.

## 5. Open, and the owner's to decide

- **Whether to open an acquisition court** for Film Festival, and whether Memento follows. The
  scouting brief reserves this; both admission records sit at `proposed`.
- **§3 of `docs/design/story-text-admission-checklist.md`** — an REB/IRB basis, required before any
  participant recall prose is committed. It does *not* block analysis: reading the git-ignored data
  root is fine, and nothing proposed here commits prose.
- Licence was **settled on 2026-09-04**: open-science research use, recorded in both manifests with
  a disclaimer for sources whose upstream carries no LICENSE file.

## 6. Memento, parked but ready

`docs/plans/2026-09-03-memento-integration.md` and `docs/data/memento/`. 133 participants recalling
a film whose presentation order is deliberately anti-chronological, with `StoryOrderSceneNum` giving
both orders, a 7-rater causal graph, and 150 labelled false memories. Its condition 4 watched a
**restitched linear cut**, so condition 1 versus condition 4 isolates presentation-order from
story-order with everything else held fixed — a control Sherlock cannot provide and Film Festival
does not either. Bytes are staged and verified against upstream blobs; the `RecallType` code book is
recorded from the article. Untouched otherwise.
