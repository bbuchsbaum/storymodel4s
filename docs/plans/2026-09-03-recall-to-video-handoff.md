# Recall-to-video: handoff

What a fresh agent needs to pick this up. Results live in
`2026-09-02-recall-to-video-study-log.md`; the evaluation rules live in
`2026-09-02-gold-scene-preregistration.md` and are **binding, not advisory**. This file is the
operational brief: what runs today, what the traps are, and what is actually left.

## 1. Where the mapping stands

Scene-level accuracy against human labels, 15 participants, 2,134 units:

| | scene-exact | within one scene |
|---|---|---|
| Before this work | 33.4% | 42.0% |
| Now | **63.8%** | **83.0%** |

The honest floor: a content-free ramp using recall timing and no content at all scores 9.7%. The
result is content-driven.

Two things this number is **not**. It is scene granularity, 50 scenes, while the pipeline actually
emits one of 1000 segments — so within-scene precision is unmeasured, not good. And it is one film.

## 2. What runs by default

All in `embed-bench/.../video/` unless noted. Every knob is an environment variable; the default is
what a bare run does.

| Variable | Default | What it does |
|---|---|---|
| `STORYMODEL4S_LEXICAL_BLEND` | `0.8` | BM25 re-ranking weight on the semantic side. `1.0`/`off` restores the unblended channel. |
| `STORYMODEL4S_LEXICAL_FIELDS` | `lemmas` | Lexical index also holds node lemmas (locations, cast). `text` for node text only. |
| `STORYMODEL4S_PRIOR_SCALE` | `1.5` | Scales the four time-direction transitions in the HSMM. |
| `STORYMODEL4S_MONOTONE_SCENE` | `on` | Scene-monotone decode of the whole recall. **This is the big one.** |
| `STORYMODEL4S_MONOTONE_FILL` | `on` | Fills a unit with no candidate in its assigned scene. |
| `STORYMODEL4S_BACKWARD_PENALTY` | `hard` | Cost of a backward scene step. `hard` forbids it. |
| `STORYMODEL4S_FORWARD_PENALTY` | `0.0` | Cost per scene skipped. Measured null here; kept for other corpora. |
| `STORYMODEL4S_CANDIDATES_PER_LEVEL` | `8` | Nomination breadth. Widening to 16 was measured worse. |
| `STORYMODEL4S_SOURCE_TEXT` | `bare` | `enriched-leaf` and `enriched-scene` both measured harmful. |
| `STORYMODEL4S_SCENE_CAPTIONS` | unset | Path to VLM scene captions. Measured null on this corpus. |
| `STORYMODEL4S_SHUFFLE_RECALL` | unset | Control: permutes recall units. See trap 3. |

| `STORYMODEL4S_SCENE_CODING` | unset | Path to the released scene coding CSV. When set, the run's Recall Voyage document carries it as an independent coding, with the participant read from the recall file name under the pre-registered mapping. Changes nothing in the report. |

Every run also writes two content-bearing companions beside the report, neither of which changes
the TSV: `<report>.posterior.json`, the per-unit posterior (every admitted anchor with mass, the
argmax, the decoded anchor with *its own* mass, the decode's scene and whether it bound the unit),
and `<report>.voyage.json`, the typed Recall Voyage document (ADR 0002 §14) that storyatlas4s
renders. Read `mapAnchorMass` in the TSV as the posterior argmax's mass: the decode moves 57% of
anchors, and for those the report's anchor and its mass column belong to different nodes.

storyatlas4s renders the voyage document natively (landed on storyatlas4s main at `b605679`,
gated against storymodel4s `dd40d95f` and intaglio `4eb566d`; intaglio main is pushed at
`5a64360` with the five primitives and their motes closed):
`sbt <overrides> "cli/run voyage --document <report>.voyage.json --out <dir>"` writes the static
plate (`voyage.svg`, every mark titled and classed through intaglio `GrobMeta`), its textual twin,
`voyage-receipt.json`, and `voyage.html`, which with `app.js` beside it mounts the interactive pane
(hover card, click and arrow-key walk, posterior column, ghost and all-columns toggles, a plate
fitted to its panel, the within-group strip). The NN03 pane is published as a private artifact for
the owner (`Storyatlas Recall Voyage`). Five intaglio upgrades were filed as motes and implemented
for it: `PointShape.Diamond` (circle-area parity), `Grob.annotated`/`GrobMeta` (title, class,
data-*), rect corner radius, step lines, and the classes a stylesheet needs. Trap: intaglio's point
`size` is the device radius, not a diameter.

`STORYMODEL4S_ONNX_MODEL` and `..._TOKENIZER` must point at `data/models/onnx/` or the pipeline
silently falls back to a free lexical baseline and the run is not comparable to anything.
`run-arm.sh` sets them from the data root (`tools/data-root.sh`; layout in `data/README.md`).
Everything that used to live under `tmp/` — sources, model, the study record — now lives under
`data/`, one copy shared by every worktree.

## 3. The evaluation apparatus

- **Gold**: `data/sherlock/Sherlock_Recall_Scene_n50_Onsets.csv`, sha256 `68cc307c…3753`, from
  <https://gin.g-node.org/ljchang/Sherlock>. Kept outside Git like every other external source.
  Onsets are **TRs at 1.5 s** against the Princeton clock, which is the clock the pipeline reads.
- **Participant mapping**: gold subject *N* ↔ `NN0N` for N ≤ 4, ↔ `NN0(N+1)` for N ≥ 5. `NN05` has no
  gold; `NN01` is **excluded** — its coding runs to 1417 s against a 782 s transcript. Do not try to
  rescue NN01 without new evidence; the exclusion is fixed in the pre-registration.
- `tools/recall-study/run-arm.sh ARM PARTITION` runs one config over one partition.
- `gold_scene.py` is the accuracy scorer. `agreement.py` is the gold-free cross-participant proxy.
  `score.py` and `matched.py` are the older gold-free outcomes.

**Concentration and localizability may not choose an arm.** Both measure how peaked the posterior is
and both improve monotonically with prior strength while accuracy collapses. This is measured, not
theoretical. Report them as diagnostics only.

## 4. Traps, all of which cost real time here

1. **The untouched five have been read four times.** Once for the lexical blend, once each for the
   monotone decode and the fill, once for the ramp floor. They are no longer a clean holdout. Say so
   in anything you report, and prefer leave-one-participant-out on the ten development participants
   for new selection.
2. **Do not tune against gold.** Pre-registration rules 1–3 bind: gold evaluates, development
   selects, and every gold comparison gets counted. The count so far is in the study log.
3. **The shuffle control does not work and is not worth fixing casually.** A sentence-level shuffle
   silently does nothing (these transcripts have no punctuation), and a unit-level shuffle collapses
   172 units into 88 because `StorySource` canonicalisation drops the punctuation used to rejoin
   them. Preserving segmentation needs the recall graph rebuilt from permuted units. Setting
   `STORYMODEL4S_PRIOR_SCALE=0` answers most of the same questions for free.
4. **A null that is too clean has usually not run.** The first shuffle returned tau identical to four
   decimals in both arms. Check that the manipulation took effect before believing a null.
5. **Choose the estimator before reading it.** A paired *sign* test reported a false null on
   agreement because ~40% of anchors are unchanged between arms, so the median difference is 0 by
   construction and magnitude is discarded. Use the paired bootstrap and signed-rank in
   `agreement.py`.
6. **Scene identity is categorical.** An isotonic-regression projection of predicted scene indices
   collapsed accuracy to 7.3% by averaging pooled scene numbers. Decode over discrete candidates.
7. Commit source edits *before* running any sweep; a mutation script's `git checkout --` has wiped
   uncommitted work here. Never run an arm while recompiling.
8. Disk fills fast. Gate exports are ~2.8 GB each; delete your own the moment it passes. A sibling
   session shares this machine.

## 5. What is actually left, ranked

**1. Within-scene precision — apparatus landed 2026-09-03; the human read is a ninety-minute task.**
Gold resolves 50 scenes; the pipeline emits 1000 segments. The pre-registration
(`2026-09-03-within-scene-precision-preregistration.md`), the blind packet, the sealed key and the
scorer (`tools/recall-study/within_scene.py`) exist, and a *diagnostic* machine lane has read the
packet: on 124 primary units the leaf anchor lands inside the adjudicated span 66.1% of the time
against 25.0% for the scene midpoint, median time gap 0 s. Provisional, never gold, selects nothing.
What remains is the human lane: fill the 200 answer lines in
`data/study/recall-to-video/within-scene/packet.md`, then `extract --lane human` and `score` as the
study log's last section spells out. Do not open the machine answers first.

**2. Emission sharpness, which is the binding constraint on scene accuracy.**
Errors are now boundary errors: 70.8% within two scenes, only 4.0% beyond ten. The runner-up holds
the gold scene for just 9.8% of wrong units, so the right scene is usually not second either. The
decode places the path well; the per-unit evidence is not sharp enough to nail the boundary. Known
levers and their measured sizes: a stronger encoder buys +0.043 raw-argmax alone but only ~+0.013
once blended (they are substitutes, not complements); source-text enrichment is exhausted at scene
level; leaf-level captions have a poor prior *here* because the annotation is unusually complete.

**3. A second corpus, which is the credibility bottleneck.**
One film, 15 gold participants, a worn holdout. A second corpus is worth more than any further
parameter on this one. It also reactivates the captioning lane: captions were a measured null here
only because Sherlock's human annotation already says what the model sees, which is not the ordinary
case. `media/worker-vlm/` and the pinned Qwen3-VL-4B are ready.

**4. Re-fit the two structural priors per corpus, do not inherit them.**
`hard` backward and `0.0` forward are *fitted to this corpus*, where recall of one linear episode is
97.9% non-decreasing. Reminiscence, a non-linear narrative, an interviewer prompting revisits, or
several stories at once would all break that. The sweeps are cheap; run them on development before
trusting the defaults.

**5. Small and bounded, listed so nobody spends a week on them.**
Unit straddling caps at ~8.5% of units and realistically far less. Confidence is still informative
(59.4% → 72.5% across quartiles) but that buys selective prediction, not accuracy. A rate prior is
already refuted: the content-free ramp at 9.7% *is* that prior in pure form.

## 6. Owner decisions still open

- The human lane of the within-scene adjudication (item 1): ninety minutes, packet ready.
- Whether to bring in a second corpus, and which (item 3). `2026-09-03-second-corpus-scouting.md`
  ranks eight candidates and recommends the Chen lab's Film Festival release; the first question is
  its licence.
- Whether a diagnostic-only ceiling from one film is worth further investment at all.
