# Toward the best recall-to-video mapper: the plan (revision 3)

*2026-09-18. Draft for owner approval, written against `main` at `29d023bf`.*

*This revision folds in the conversation that followed revision 2: the language-model baseline,
running it locally so no recall text leaves this machine, TypeSafe's Jev, and the owner's rulings
of 2026-09-17/18.*

*Sources: four read-only surveys (engine, efficacy, usability, project state) and one cold review
of revision 1. The author re-verified every load-bearing claim marked as checked in §7. Everything
is LocallyObserved.*

## 0. Goal, and what is already decided

**Goal (owner, 2026-09-18).** "The best automatic recall-to-video mapper in the world; Sherlock
and FilmFestival are test cases (also Friends)."

**What "best" will mean.** On a benchmark someone else can rerun, per input track and on clean
test data, the mapper beats:
- the strongest baselines, including language models;
- the published Sherlock method;
- simple retrieval.

Every result is stated against a human ceiling.

**Decided:**

| | Ruling | Consequence here |
|---|---|---|
| A | "build first, then researchers ask questions": ADR 0007's research gate is removed | engineering is never gated on a research answer |
| C | the D1A split into D1A-types and D1A-film | D1A-types S0–S2 are part of Phase 2 |
| D | one bundle per model; film proposal text lives outside the bundle | fixed public shapes for the film route |
| E | 1.0 ships video recall in the library, end to end | Phase 2 delivers it through the measured mapper and D1B's typed source view (§4) |

**Proposed defaults for what is still open.** Each can be overridden (§5).
- **Local first.** The language-model work starts with a local, pinned open-weights model.
  Hosted models, and TypeSafe's Jev, join only after the owner decides whether participant recall
  prose may leave this machine.
- **D1A-film is not scheduled.** That is a capacity choice, not a research gate: it is not on the
  mapper's critical path. The spine pilot (H5) will have reported before capacity frees up.

## 1. Where the mapper stands

| | Today |
|---|---|
| Best system | Retrieval against coder scene descriptions, then the library's `GraphHsmm`, then a monotone scene decode in bench code, then gap fill |
| Sherlock, development | **63.8%** of recall units placed in the exact scene of 50 (83.0% within one scene), against a 9.7% timing floor. No confidence interval |
| FilmFestival, transfer | 67.5% film identity; 81.5% without fill. Development data only |
| Friends and Memento, clean test | Never run |
| Human ceiling, language-model or published baselines | **None** |
| Where it lives | `embed-bench`, driven by 19 environment variables and scored in Python |

**Where the gain comes from.** About 24.5 of Sherlock's 30.4-point gain comes from the decode
step, not the aligner.

## 2. The concerns this plan answers

**Efficacy**
1. **"Best" cannot be claimed.** No human ceiling, no strong baseline, and no clean test data:
   the Sherlock and FilmFestival holdouts are spent (FilmFestival's own record,
   stabilization-results:118-119).
2. **A language model labelling units directly may win, and it has never been run.** It is the
   cheapest experiment with the most power to redirect the work.
3. **Settings are fitted to Sherlock.** Fill gains 6.8 points on Sherlock and costs 14.0 on
   FilmFestival.
4. **"Automatic" means matching to coder prose.** Only Sherlock has local video.
5. **Two instruments mislead.**
   - Agreement did not track accuracy.
   - Risk–coverage ranks by the wrong node's mass, because the decode moves 57% of anchors.
6. **Statistical power is unstated.**

**Engine**
7. **Two systems.** The measured mapper sits outside the library.
8. **Duration is only geometric.** A rate prior was tried and refused.
9. **Empty evidence publishes full support** on the `hsmm/v3` wire.
10. **Native builds break byte identity** (a 1-ULP difference in `exp`).
11. **The typed story compiler has never validated a real text.** This matters only to the
    spine's film route.

**Usability**
12. **No command runs from inputs to a mapping.** Nothing is published, the docs overstate the
    product, and the CLI exits 0 on an unvalidated draft.

**Project**
13. **Unique work is at risk.** The disk is at 91%, the tracker has drifted, CI has never run, and
    about 75 commits exist only on this machine.

## 3. The plan

```text
Phase 0 ──> Phase 1a (baselines incl. local LLM; Friends split sealed) ──> CHECKPOINT
                                                                             │
                     ┌───────────────────────────────────────────────────────┤
                     v                                                       v
         Phase 1b (benchmark v1)                       Phase 2 (mapper in the library)
                     └─────────────────────────────> Phase 3 (climb) ──> Phase 4 (ship 1.0)
```

Sizes: S is up to 2 days, M is up to 1 week, L is 2–4 weeks. Every slice lands on `main` behind
its own green gate, with a separate cold review (AGENTS.md, SD2 and SD6).

### Phase 0 — Protect and clean (S)

1. **Rescue `b756b4bb`.** It is an audit branch that exists only inside the unregistered clone
   `.worktrees/feature-values-dev`. Fetch it into a main-repo ref, then verify with
   `git for-each-ref --contains`.
2. **Free disk, only with the owner's OK.**
   - The four merged worktrees (`ladder-run`, `own-the-metric`, `perception-first-court`,
     `rootseg`) go through `git worktree remove` and then `prune`.
   - The redundant clones (`filmfestival-evaluation`, `filmfestival-trace-run`,
     `feature-atlas-dev`, then `feature-values-dev` once step 1 is verified) are removed only
     after their ignored files are audited by digest. `perception-first-court/tmp/` holds 144 MB
     of study material.
   - Before removing anything, check that no process has its working directory there.
   - `feature-grakern-ref` stays until `docs/calibration/2026-09-04-mutations.json` is re-pointed
     at grakern's origin.
3. **Tracker.**
   - Close the beads for landed work: T1–T5, the nine T4 probe beads, `PopulationAggregate` and
     `MassRatio`.
   - Move V1 and E0 to 1.x, and supersede the fleet-era film-chain bodies.
   - File the Phase 1a, 1b and 2 beads.
4. **Correct false claims.**
   - The README's distortion and facet claims, the grakern note and the module counts.
   - `tools/recall-study/README.md:35-37`, which calls agreement "the primary outcome".
5. **Fix the `checkAll` alias** so it runs formatting last.
6. **Owner actions, in parallel.** Settle CI billing or pick another route, push `main`, and
   apply for Jev early access.

### Phase 1a — Baselines, including a language model (M)

**Seal the clean test data first.** Draw the Friends test split at participant level. Commit its
seed and membership before anything else in this phase. Nothing in Phases 1a–2 reads it. It is
"model-untouched, descriptive statistics seen": the 74% monotonicity figure was computed over all
23 participants.

**Local runtime.**
- Install an OpenAI-compatible local runtime, such as Ollama or `llama-server`. This machine is an
  M3 Max with about 36 GB of memory.
- Choose one open-weights instruct model in the 14–32B range, on a declared two-participant pilot.
- Pin it by the SHA-256 of its weights file, the runtime version, the quantization and the context
  size, at temperature 0 with a fixed seed.
- The receipt records all of this.
- Nothing leaves the machine.

**Inputs, track T1.** Sherlock's coder descriptions come to about 44,000 words, roughly 60,000
tokens. They are grouped by the 50 scenes.
- **Units:** every arm scores **the same recall units** the current pipeline scores.
- **Scoring:** the same scorer (`tools/recall-study/gold_scene.py`), with participant-bootstrap
  CIs on the paired difference.

**The arms:**

| Arm | What it is |
|---|---|
| B0 | timing-only floor (exists) |
| B1 | BM25 argmax over scenes |
| B2 | embedding cosine argmax over scenes |
| B3 | current pipeline, with fill (the 63.8% arm, recomputed with a CI) |
| B4 | current pipeline, without fill |
| L1 | local model, **per transcript**: labels every unit in order, seeing the whole recall |
| L2 | local model, **per unit**: one unit at a time, with prefix caching of the descriptions. Isolates matching from context |
| L3 | **memorisation probe**: L1 given scene titles only (the "Scene Segments" column), not descriptions. Measures what the model already knows about the episode |
| H1 | hosted frontier model, per transcript. **Only after the owner's data decision** |
| J1 | Jev, per unit, a choice over 50 scenes with its probabilities. **Only after early access and the data decision.** Cost at posted prices is under $1 |

**FilmFestival, development.** The film-identity task runs with L1, B1, B2 and B3/B4 on both the
coder index (T1) and the crowd index (T2).

**Pre-declared decision rules.** These are fixed before any language-model output is read.
Comparisons use Sherlock exact scene, paired against B3.
- **R1 — a language model wins.** If the best L or H arm beats B3 with a CI excluding zero,
  Phase 2 builds the mapper around a pluggable scoring channel, with that model as the first
  non-embedding provider. Phase 3 then opens with H1: model scores fed through the decode.
  - **The library's claim becomes what a bare prompt lacks:** pinned reproducibility, calibrated
    confidence with alternatives, typed playback intervals, and a benchmark harness.
- **R2 — the pipeline holds.** If the CI includes zero, or B3 wins, Phase 2 proceeds as designed,
  and language-model scoring stays hypothesis H1.
- **R3 — contamination.** If L3 comes within 10 points of L1, language-model numbers on Sherlock
  are treated as contaminated. Their claims wait for the Friends and Memento test splits.
- **R4 — pinned or hosted.** If the local pinned model comes within 5 points of the hosted one,
  the pinned model is the library's default provider.

**Output:** a readout document with every arm, its CI and its receipt. It is committed before the
checkpoint.

### Checkpoint

The owner reads the Phase 1a readout. The decision rules say what Phase 2 builds. The owner
confirms or overrides.

### Phase 1b — Benchmark v1 (M–L; partly waits on people)

**Tasks,** one versioned descriptor per corpus:
- Sherlock: exact scene, within one scene, temporal error.
- FilmFestival: film identity. Scenes within a film wait for the clock work: the +106 offset,
  run-relative times, and media equivalence.
- Friends: `WhichEvent` (630 gold units, veridical recall only).
- Memento, once admitted.

**Tracks:**

| Track | Input |
|---|---|
| T1 | coder descriptions |
| T2 | crowd descriptions |
| T3 | dialogue only |
| T4 | video-derived; Sherlock only until media is acquired |

**Scoring:**
- weighted by word or time;
- a gold-unit oracle row;
- `RecallSegmenter` evaluated against Friends' gold runs;
- stated split sizes and minimum detectable effects;
- risk–coverage on the decoded anchor's own mass.

**Human ceiling:**
- a second coding of sampled Sherlock, FilmFestival and Friends units, which needs people;
- the within-scene human lane, about 90 minutes.

**Published comparator:** Heusser et al. (2021) on Sherlock. Its 30 events are recall-tuned, so
that is stated, along with how 30 events are scored against 50 scenes.

**Output:** one command regenerates the report and every leaderboard file, from upstream releases
plus hashed manifests. Nothing restricted is redistributed.

### Phase 2 — The mapper in the library (L)

This phase includes D1A-types S0–S2 and D1B's signature work.

- **Parity first.** D1A S0 pins the text path. The Sherlock per-unit anchors reproduce
  byte-for-byte through the new path before anything else changes.
- **Typed source view (D1B signatures).**
  - `SourceView` without canonical text.
  - Nodes carrying checked `EvidenceSupport` and exact `PlaybackInterval`s. The core types come
    from D1A S2.
  - `HsmmResult` keeping exact intervals.
  - This replaces the bench's joined-text surrogate.
- **A pluggable scoring channel.** Every channel returns scores over candidates with a receipt,
  behind one typed interface:
  - lexical and embedding;
  - a local model pinned by weights digest;
  - a hosted model carrying its runtime identity;
  - Jev.

  A calibration layer, fitted on development data, turns scores into probabilities.
- **Declared decoders.** `MonotoneScene` is generalised. Fill is off by default, and per-corpus
  settings are data.
- **`recall-map`.** One command takes a corpus descriptor and recall transcripts. It writes a
  per-unit mapping with interval, confidence and alternatives, plus a self-contained HTML report.
  It exits non-zero unless the mapping is complete. The benchmark runs through it, and
  `run-arm.sh` retires.

### Phase 3 — Climb (iterative)

Each hypothesis becomes an arm. It is tuned on development data, then read **once** on the
Friends and Memento test splits against its minimum detectable effect.

- **H1 — language-model scoring through the decode.** Local, hosted and Jev channels. Scores are
  calibrated against gold on development data, so the vendors' own calibration claims are not
  relied on.
- **H2 — transfer-safe decoding:**
  - a per-participant monotonicity estimate;
  - a storyline-aware decode for braided recall;
  - fill decided per corpus;
  - a duration distribution last.
- **H3 — coder-independent inputs.** Dialogue, captions and crowd descriptions, plus the
  independence ablation. T4 on held-out corpora needs media acquisition first.
- **H4 — calibrated confidence and abstention,** after the support-honesty fix.
- **H5 — the structural spine pilot.** Does a proposition-level channel add anything beyond the
  best scoring channel? Its s43 validation fix comes with an ADR 0005 amendment.

### Phase 4 — Ship 1.0 (M–L)

- **Release plumbing:** publishing, a MiMa baseline, and deployed docs.
- **The `hsmm/v4` support-honesty wire.** It breaks the wire, so it lands before the freeze.
- **Fixes:** platform-labelled goldens, one refusal supertype, readable I/O errors, and a facade
  around `recall-map`.
- **Stability table:**
  - Stable: the mapper, the typed source view, `HsmmResult`, and the core evidence types.
  - Experimental, outside MiMa: `StoryModel`, the compiler and the proposition spine, unless the
    spine route is funded.

## 4. What changes in the film chain

| Bead | Now |
|---|---|
| D1A-types | S0–S2 run inside Phase 2. S3 runs only if D1B needs its accessor. S4a–S4c pause |
| D1B | splits. Its signature work is Phase 2. Its spine-dependent proofs go with D1A-film |
| D1A-film | not scheduled (§0 default). H5 informs when |
| V1, E0 | 1.x |

## 5. Owner decisions

**Needed to start:**
1. Approve this plan and its order.
2. Phase 0 deletions: approve the list in Phase 0, step 2.

**Needed before the H1 and J1 arms (Phase 1a starts without them):**
3. May participant recall prose go to hosted APIs (a frontier model, TypeSafe)? What is the spend
   budget?

**Needed later:**
4. People for the second human codings (Phase 1b).
5. Media acquisition for FilmFestival and Memento (T4 on held-out data).
6. Override the "D1A-film not scheduled" default, if wanted.
7. CI billing and pushing `main`.

## 6. Tracker changes on approval

- File beads for Phase 0, Phase 1a (with the arms table and rules R1–R4), Phase 1b and Phase 2.
- Re-scope D1A-types to S0–S2 plus the conditional S3.
- Split D1B.
- Move V1 and E0 to 1.x.
- Mark D1A-film "not scheduled", with its reason.

## 7. Non-claims

- **What the author checked by hand:**
  - the validation gate;
  - the orphaned commit;
  - FilmFestival's partition statement;
  - which corpora have video;
  - the dwell transitions;
  - the environment-variable count;
  - the Sherlock description size;
  - the scorer path;
  - this machine's hardware;
  - the absence of any installed local runtime.

  Other figures are as the surveys reported them from the repo's study logs.
- The Jev figures are the vendor's claims. The only independent commentary found says its
  calibration is unpublished and its accuracy trails frontier models on the vendor's own
  benchmark.
- H1–H5 and the rule thresholds (10 and 5 points) are judgement, declared before data.
- Revision 3 has not been cold-reviewed.
