# Toward the best recall-to-video mapper: the plan (revision 4)

*2026-09-18. Draft for owner approval, written against `main` at `aab88b2e`. Everything below is
LocallyObserved.*

*Revision 3 was returned by a cold review with four blockers.*
- *It misstated the owner's rulings C and E, and quietly reintroduced a research gate.*
- *It left the scoring contract unspecified, even though the scorer silently drops rows.*
- *Its contamination probe did not measure contamination.*
- *It ran several arms and picked the best on gold, without the Sherlock preregistration's
  counting rules.*

*It also caught a wrong input size that revision 3 had marked as checked. Revision 4 fixes all of
it. It also records the FilmFestival stimulus material found on Trillium the same day.*

## 0. Goal, rulings, and what this plan proposes to change

**Goal (owner, 2026-09-18).** "The best automatic recall-to-video mapper in the world; Sherlock
and FilmFestival are test cases (also Friends)."

**"Best" means:** on a benchmark someone else can rerun, per input track, on the cleanest test
data available, the mapper beats language-model baselines, the published Sherlock method, and
simple retrieval. Every number is read against a human ceiling. It is claimable only per track,
only against the baselines actually run, and only on these corpora.

### The owner's rulings, as recorded

| | Ruling | Source |
|---|---|---|
| A | "build first, then researchers ask questions". ADR 0007's research gate is removed | d1a plan §0, 2026-09-18 |
| C | the D1A split is approved with **all** slices, S0 through S4c | d1a plan §0 |
| D | one bundle per model; film proposal text lives outside the bundle | d1a plan §0 |
| E | 1.0 waits for D1A-types, **D1A-film** and all of D1B. "D1A-film is therefore on the 1.0 path." | d1a plan §0; the D1A-film bead |

### Proposed changes to those rulings (the owner re-decides; nothing below assumes them)

**P1. Change E's route. Film reaches 1.0 through the measured mapper, not the story compiler.**
The surveys found three things. The mapper that produces every measured result does not use the
story compiler. The compiler has never validated a real text. And the mapper is what the goal
names. Under P1:
- 1.0 ships the mapper and D1B's typed source view (Phase 2).
- D1A-types S3 and S4a–S4c, and D1A-film, leave the 1.0 path.
- `StoryModel` and the compiler are marked experimental in 1.0.

**P1 has two versions, and the owner picks one:**
- **P1a.** D1A-film is simply not scheduled before 1.0. It is resumed whenever the owner wants.
- **P1b.** D1A-film resumes only if the H5 pilot shows the spine helps mapping. That puts a
  research result in front of an engineering spend, so it **requires narrowing ruling A**.
  Revision 3 called this a "capacity choice". It is not one, and this revision withdraws that
  framing.

**If the owner declines P1,** E stands: D1A-film and all D1A-types slices are on the 1.0 path.
Phase 2 then adds D1A S3–S4c, and Phase 4 waits for D1A-film.

**P2. Language models run locally first.** Hosted models, including TypeSafe's Jev, run only after
the owner decides, per corpus, whether participant recall prose may leave this machine.

## 1. Where the mapper stands

| | Today |
|---|---|
| Best system | Retrieval against coder scene descriptions, then the library's `GraphHsmm`, then a monotone scene decode in bench code, then gap fill |
| Sherlock | **63.8%** exact scene of 50, **pooled over 15 participants** (study-log:716). Development-only: 65.2%. The scorer prints a CI (`gold_scene.py:164`), but none was recorded for this number. Timing floor: 9.7% |
| FilmFestival, transfer | 67.5% film identity; 81.5% without fill. Development data only |
| Friends and Memento | Never run |
| Human ceiling; language-model or published baselines | **None** |
| Where it lives | `embed-bench`, driven by 19 environment variables, scored in Python |

About 24.5 of Sherlock's 30.4-point gain comes from the decode step, not the aligner.

**FilmFestival stimulus material on Trillium** (`/scratch/brad/ff_stim`, found 2026-09-18):
- extracted frames (10,744 files);
- audio;
- speech-recognition transcripts for the speech films;
- derived features for eight films plus the cartoon.

`catch_me_if_you_can` and `the_prisoner` are absent. The source video files are being located
(§3, Phase 0). This opens track T4, video-derived input, on FilmFestival, not only on Sherlock.

## 2. The concerns this plan answers

**Efficacy**
1. **"Best" cannot be claimed.** There is no human ceiling, no strong baseline, and no clean test
   data. Both the Sherlock and FilmFestival holdouts are spent.
2. **A language model labelling units directly may win.** Nobody has run one.
3. **Settings are fitted to Sherlock.** Fill gains 6.8 points on Sherlock and costs 14.0 on
   FilmFestival.
4. **"Automatic" today means matching recall to coder prose.**
5. **Two instruments mislead.**
   - Agreement did not track accuracy.
   - Risk–coverage ranks units by the wrong node's mass.
6. **Power is unstated.** Only 10 development participants have usable Sherlock gold. A percentile
   bootstrap over 10 clusters under-covers.
7. **Contamination.** Sherlock's annotation (2021), recall transcripts (2023) and gold are public.
   Friends and *Memento* are famous. A sealed participant split controls tuning leakage, not what
   a model saw in training.

**Engine**
8. **The measured mapper lives outside the library.**
9. **Duration is only geometric.** A rate prior was tried and refused.
10. **Empty evidence publishes full support** on the `hsmm/v3` wire.
11. **Native builds break byte identity.**
12. **The typed story compiler has never validated a real text.**

**Usability**
13. **No command runs from inputs to a mapping.** Nothing is published, the docs overstate the
    product, and the CLI exits 0 on drafts.

**Project**
14. **Project hygiene.**
    - Unique work at risk.
    - Disk at 91%.
    - Tracker drift.
    - CI never run.
    - About 75 commits unpushed.

## 3. The plan

```text
Phase 0 ──> Phase 1a (sealed Friends split; unit manifest; baselines incl. local LLM) ──> CHECKPOINT
                                                                                             │
                           ┌─────────────────────────────────────────────────────────────────┤
                           v                                                                 v
               Phase 1b (benchmark v1)                                 Phase 2 (mapper in the library)
                           └──────────────────────────────────> Phase 3 (climb) ──> Phase 4 (ship 1.0)
```

**Sizes:** S is up to 2 days, M up to a week, L is 2–4 weeks. Every slice lands behind its own
green gate and a separate cold review.

### Phase 0 — Protect and clean (S)

1. **Rescue `b756b4bb`.** It exists only in the unregistered clone
   `.worktrees/feature-values-dev`. Fetch it into a main-repo ref, then verify it with
   `git for-each-ref --contains`.
2. **Free disk, only with the owner's OK.**
   - Remove the four merged worktrees with `git worktree remove`, then `prune`.
   - Remove the redundant clones only after digest-auditing their ignored files
     (`perception-first-court/tmp/` holds 144 MB of study material).
   - Nothing is removed while a process's working directory is inside it.
   - `feature-grakern-ref` stays until `docs/calibration/2026-09-04-mutations.json` is re-pointed.
3. **Tracker.**
   - Close the beads for landed work: T1–T5, the nine T4 probe beads, `PopulationAggregate` and
     `MassRatio`.
   - Move V1 and E0 to 1.x.
   - File the phase beads.
4. **Correct false claims.**
   - In the README: the distortion/facet claims, the grakern note and the module counts.
   - `tools/recall-study/README.md:35-37`.
5. **Fix the `checkAll` alias** so formatting runs last.
6. **FilmFestival media.**
   - Locate the source videos behind `ff_stim`.
   - Record what exists per film: container, duration, and the offset against the scanned version.
   - Pull the videos, or, if they are gone, the frames, audio and transcripts, into the local data
     root under `filmfestival/media/`. That path is gitignored, and no bytes are committed.
   - Commit a hash manifest.
7. **Owner actions.** CI billing or another route; push `main`; apply for Jev early access.

### Phase 1a — Baselines, including a language model (M)

**Step 1. Seal the Friends test split before anything else.**
- Draw it at participant level, and commit the seed and membership.
- Record its size and minimum detectable effect when it is drawn: 23 participants, 630 gold
  units.
- Nothing in Phases 1a–2 reads it. The segmenter evaluation and any second coding in Phase 1b use
  development participants only.

**Step 2. The scoring contract.** This is a fix in `tools/recall-study`, because today the scorer
fails open.
- Export B3's **unit manifest**: participant, ordinal, text, onset. The current pipeline scores
  2,560 `RecallSegmenter` units, 67–351 per participant (arm `all17-monofill`).
- Every arm labels exactly those units. Language-model arms use constrained JSON keyed by unit id.
- `gold_scene.py` changes in four ways:
  - it fails loudly unless both arms cover the identical unit set;
  - a missing or invalid label counts as wrong;
  - it reports coverage;
  - it rejects a file name that does not match a partition member instead of dropping it.
- **Scorer drops today:** it discards unlabelled rows silently (`:108-112`), and `paired` never
  checks unit identity (`:171`).
- **Mutation witnesses.** Drop one unit from one arm, and the paired run must fail. Blank one
  label, and it must be scored wrong.

**Step 3. The preregistration governs.** The committed Sherlock preregistration binds this phase.
- **Rule 2:** any choice made on gold uses development participants only.
- **Rule 3:** every gold comparison is counted.
- `partition.json` says the untouched set "chooses nothing".

What that means here:
- **One confirmatory comparison is pre-specified.** L1 (local) against B3, on the 10 development
  participants with usable gold, exact scene. Its paired CI uses BCa or a cluster permutation, not
  a percentile bootstrap over 10 clusters.
- **Every other arm is descriptive,** Holm-adjusted if any claim is made from it.
- **Model and prompt piloting** use NN01 and NN05, the two participants with no usable gold, and
  judge only gold-free criteria: parse validity, coverage, whether the prompt fits the context
  window, and rerun agreement.
- **The prompt hash and a stated tuning budget are frozen before any gold is read.**
- **Every read is entered in the study-log ledger.**
- B3 was tuned on development gold. The readout says so, per own-the-metric:170-174.

**Step 4. The local runtime.**
- **Input size.** Sherlock's `Scene Details - A Level` column is **18,203 words**, roughly 24,000
  tokens. Revision 3's 44,000 counted the whole 23-column file. L1 needs roughly 26,000–33,000
  tokens plus up to 351 output labels.
- **Model.** An open-weights instruct model of about 14B with a native context of at least 64k.
  This machine is an M3 Max with 36 GB, and the default GPU memory cap is in force. A 32B model at
  about 30 GB with its cache does not fit under that cap. Raising the cap is a system change the
  owner would approve.
- **The receipt pins everything that changes output:**
  - the weights SHA-256;
  - the runtime version;
  - quantization and KV-cache type;
  - context size;
  - batch and ubatch sizes;
  - flash-attention;
  - rope scaling;
  - the chat-template hash;
  - temperature 0 and the seed.
- **Guards:**
  - Assert that the number of prompt tokens the model evaluated equals the number sent. Some
    runtimes truncate silently at their default context.
  - Prompt caching is not bit-identical in llama.cpp, so every arm that uses it reports rerun
    agreement.
- **Governance.** Local runs are permitted (Sherlock README:63-66; FilmFestival README:36-37).
  Arm files and prompt logs carry recall text, so they stay under `data/study`. The committed
  readout carries only hashes and aggregates.

**Step 5. The arms.**

| Arm | What it is |
|---|---|
| B0 | timing-only floor |
| B1 / B2 | BM25 / embedding cosine, argmax over scenes |
| B1t / B2t | the same, on scene **titles only** |
| B3 / B4 | current pipeline, with and without fill (B3 is the confirmatory comparator) |
| L0 | local model given **scene numbers and durations only**. Compared with B0, it measures order-and-timing guessing |
| L1 | local model, per transcript: labels every unit in order. **Confirmatory arm** |
| L2 | local model, per unit, with prefix caching. Isolates matching |
| L3 | local model, per transcript, **titles only**. Contamination estimate: L3 minus the better of B1t and B2t |
| H1, J1 | hosted frontier model; Jev. Only after the owner's per-corpus data decision and Jev access |

**FilmFestival (development).** Film identity with B1, B2, B3/B4, L1 and L3, on the coder index
(T1) and the crowd index (T2). Its obscure, often wordless shorts are the **least-exposed check**
on the language-model arms.

**Step 6. Pre-declared rules, fixed before any language-model output is read.**
- **R1.** The confirmatory L1 − B3 CI excludes zero in L1's favour. Phase 2 then ships a
  language-model provider as a **Phase 2 deliverable**, and Phase 3 opens with H1.
- **R2.** It does not. Phase 2 ships only the channel interface, with embedding and lexical
  providers. Language-model providers wait for H1 in Phase 3.
- **R3.** The contamination estimate (L3 minus the titles-only retrieval) exceeds 10 points, or
  FilmFestival's language-model advantage is materially smaller than Sherlock's. Then Sherlock's
  language-model numbers are reported as **contamination-bounded**. R3 qualifies R1's *claim*, not
  its build decision.
- **R4** is scoped to the default *language-model* channel, and applies only if a hosted arm runs.
  It is descriptive, not confirmatory.

**Step 7. The readout.** Every arm, with coverage, CI and receipt hash. It is committed before the
checkpoint. The word "calibrated" is not used: preregistration §7 rule 5 bars it on one film.

### Checkpoint

The owner reads the readout, confirms or overrides R1/R2, and decides P1 and P2 if not already
decided.

### Phase 1b — Benchmark v1 (M–L; partly waits on people)

- **Tasks,** one versioned descriptor per corpus:
  - Sherlock: exact scene, within one scene, and temporal error.
  - FilmFestival: film identity. Scene within film waits for the clock work: the +106 offset,
    run-relative times, and media equivalence, which Phase 0 step 6 enables.
  - Friends: `WhichEvent`, veridical recall only.
  - Memento, once admitted.
- **Tracks:**

  | Track | Input | Corpora |
  |---|---|---|
  | T1 | coder descriptions | all |
  | T2 | crowd descriptions | FilmFestival |
  | T3 | dialogue only | Friends; FilmFestival speech films via ASR |
  | T4 | video-derived | Sherlock; FilmFestival's eight films |

- **Scoring and statistics:**
  - weighting by words or time;
  - a gold-unit oracle row;
  - the segmenter evaluated on Friends development gold;
  - stated split sizes and minimum detectable effects;
  - risk–coverage on the decoded anchor's own mass.
- **Human ceiling.** A second coding of sampled **development** units for Sherlock, FilmFestival
  and Friends, which needs people. Plus the within-scene human lane.
- **Published comparator.** Heusser et al. (2021) on Sherlock, stating that its 30 events are
  recall-tuned and how they are scored against 50 scenes.
- **Output.** One command regenerates every leaderboard from upstream releases plus hashed
  manifests. Nothing restricted is redistributed.

### Phase 2 — The mapper in the library (L)

This phase includes D1A-types S0–S2 and D1B's signature work. It also includes S3–S4c if P1 is
declined.

- **Parity first.** D1A S0 pins the text path. Sherlock's per-unit anchors reproduce
  byte-for-byte through the new path.
- **Typed source view (D1B signatures).**
  - `SourceView` without canonical text.
  - Nodes carry checked `EvidenceSupport` and exact `PlaybackInterval`s.
  - `HsmmResult` keeps exact intervals.
- **Scoring channel.** One typed interface with a receipt per call.
  - It declares its **candidate level** (scene or leaf), and how scene scores distribute over
    leaves for `GraphHsmm`.
  - It has a **recorded-response replay mode**, so gates run without any model.
  - Providers: lexical and embedding always; language-model providers per R1/R2.
  - Score-to-probability mapping is fitted on development data and reported as *measured
    reliability*, not as calibration.
- **Declared decoders.** `MonotoneScene` generalised; fill off by default; per-corpus settings as
  data.
- **`recall-map`.**
  - Inputs: a corpus descriptor and recall transcripts.
  - Output: a per-unit mapping with interval, score and alternatives, plus an HTML report.
  - It exits non-zero unless the mapping is complete.
  - The benchmark runs through it, and `run-arm.sh` retires.

### Phase 3 — Climb (iterative)

Each hypothesis is an arm, tuned on development data and read **once** on the sealed Friends
split (and Memento, when admitted) against its minimum detectable effect.

- **H1.** Language-model scoring through the decode.
- **H2.** Transfer-safe decoding:
  - monotonicity estimated per participant;
  - a storyline-aware decode;
  - fill per corpus;
  - a duration distribution, last.
- **H3.** Coder-independent inputs: dialogue, captions from the FilmFestival and Sherlock frames,
  and crowd descriptions, plus the independence ablation.
- **H4.** Confidence and abstention, after the support-honesty fix.
- **H5.** The structural-spine pilot. Its s43 validation fix comes with an ADR 0005 amendment.

### Phase 4 — Ship 1.0 (M–L)

- **Release infrastructure.** Publishing, a MiMa baseline, and deployed docs.
- **The `hsmm/v4` support-honesty wire,** before the freeze.
- **Hardening:**
  - platform-labelled goldens;
  - one refusal supertype;
  - readable I/O errors;
  - a facade around `recall-map`.
- **The stability table.**
  - Stable: the mapper, the typed source view, `HsmmResult`, the core evidence types.
  - `StoryModel` and the compiler: stable if P1 is declined and D1A-film lands, otherwise
    experimental.

## 4. The film chain under each choice

| Bead | If P1 is adopted | If P1 is declined (E as recorded) |
|---|---|---|
| D1A-types | S0–S2 in Phase 2. S3 only if D1B needs it. S4a–S4c pause | all slices, Phase 2 |
| D1B | signature work in Phase 2; spine proofs with D1A-film | all of it before 1.0 |
| D1A-film | P1a: unscheduled. P1b: after a positive H5 (needs A narrowed) | on the 1.0 path |
| V1, E0 | 1.x | 1.x |

## 5. Owner decisions

**To start:**
1. Approve this plan.
2. Approve the Phase 0 deletions.

**At or before the checkpoint:**
3. P1: adopt P1a, adopt P1b (and narrow A), or keep E as recorded.
4. P2: per corpus, may recall prose go to hosted APIs? What is the spend budget?

**Later:**
5. People for second codings.
6. Media for Memento. FilmFestival is in progress.
7. CI and the push.

## 6. Tracker changes on approval

- File the phase beads, carrying the Phase 1a arms table and rules R1–R4.
- Once P1 is decided:
  - apply §4's column to D1A-types, D1B and D1A-film;
  - move V1 and E0 to 1.x.

## 7. Non-claims

- **Checked by the author:**
  - the validation gate;
  - the orphaned commit;
  - FilmFestival's partition statement;
  - which corpora have video;
  - the dwell transitions;
  - the environment-variable count;
  - the `Scene Details` word count (18,203; corrected from revision 3);
  - the scorer's drop behaviour;
  - the preregistration's "calibrated" bar;
  - the Trillium `ff_stim` inventory;
  - this machine's hardware.

  Other figures are as the surveys reported them.
- The 63.8% is pooled over 15 participants. Development-only is 65.2%.
- The Jev figures are vendor claims.
- H1–H5 and the R-rule thresholds are judgement, declared before data.
- Revision 4 has not been cold-reviewed.
