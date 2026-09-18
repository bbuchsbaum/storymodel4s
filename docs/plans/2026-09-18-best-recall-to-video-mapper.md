# Toward the best recall-to-video mapper: concerns and a plan (revision 2)

*2026-09-18. Draft for owner approval, written against `main` at `f6e466e2`.*

*The owner's goal, stated 2026-09-18: "the best automatic recall-to-video mapper in the world;
Sherlock and FilmFestival are test cases (also Friends)."*

*Sources: four read-only surveys (the computational engine, efficacy, usability, project state).
Revision 1 was returned by a fresh-context cold review with three blockers:*

- *it drew a FilmFestival test split that the repo's own records say cannot be clean;*
- *it paused D1A work that Phase 2 needs;*
- *it had no held-out path for "automatic".*

*The author verified all three. This revision also carries the review's factual corrections.
Every claim here is LocallyObserved.*

## 0. What "best" has to mean before anyone can claim it

"Best in the world" compares us against others. It needs four things that do not exist yet:

1. **A benchmark that someone else can rerun.** It cannot be a data release. The corpora forbid
   redistribution and committing prose, the Friends licence is undecided, and two ethics-board
   dispositions are open (bead `bd-01M184XP908JX51S3ZYFX20HC7`). So the benchmark is code, loaders
   and hashed manifests that run against the upstream releases.
2. **Clean test data.** Sherlock's untouched five have been read against gold at least five times.
   FilmFestival's records say: "A newly drawn partition cannot retroactively make them untouched"
   (`2026-09-04-filmfestival-stabilization-results.md:118-119`). The clean test corpora are
   therefore **Friends**, where the model has never been run, and **Memento**, once it is
   admitted. Sherlock and FilmFestival are development and transfer corpora.
3. **The strongest competitors, run on the same inputs:**
   - an LLM given the scene descriptions and the recall, asked to label each unit directly (the
     arm own-the-metric:169-174 expects a reviewer to demand);
   - the published Sherlock method, Heusser, Fitzpatrick and Manning (2021). Its 30-event
     segmentation is recorded as `TopicHmmEvent30` and marked "recall-tuned"
     (`docs/data/sherlock/README.md:59`; `sherlock-source-representation-audit.md:65-67`). That
     tuning asymmetry must be stated, along with how 30 events are scored against 50 scenes;
   - BM25, embedding cosine, and the timing-only floor.
4. **A human ceiling:** how often two human coders place a recall unit in the same place.

### Tracks: what the mapper is given

"Automatic" is only honest per input. Only Sherlock has local video. FilmFestival, Memento and
Friends store none (their `docs/data/*/README.md`). The benchmark therefore has four tracks:

| Track | Input | Corpora today |
|---|---|---|
| T1 | coder scene descriptions | all four |
| T2 | independent human descriptions | FilmFestival (crowd index) |
| T3 | dialogue only | Friends (transcript) |
| T4 | video-derived (captions, ASR) | Sherlock only, until media is acquired with edition equivalence (`cmiyc_long` is a longer cut than the scanner excerpt, stabilization-results:41-43) |

**Common rules.** Every track takes human recall transcripts with timings, not audio. Every
baseline gets identical recall units. Scoring is weighted by word or time, so that segmentation
cannot be gamed. A gold-unit oracle row separates mapping error from segmentation error.

## 1. Where the mapper stands

| | Today |
|---|---|
| Best measured system | Retrieval against coder scene descriptions, then the library's `GraphHsmm`, then a bench-side monotone scene decode (`MonotoneScene`), then a gap fill |
| Sherlock (dev) | **63.8%** exact scene of 50 and 83.0% within one scene, against a 9.7% timing-only floor. The decode's gain was also seen on the untouched five. The 63.8% has no confidence interval |
| FilmFestival (transfer) | 67.5% film identity, dev only. Chance must be re-quoted from the same scorer. The pipeline **without** fill scores 81.5%. Fill costs 14.0 points here and gains 6.8 on Sherlock |
| Friends | The model has **never been run**. The .39/.90 correlations describe the human coding |
| Human ceiling | **None, for any task** |
| LLM or published baselines | **None run** |
| Where it lives | `embed-bench`, a diagnostics module. It is driven by 19 `STORYMODEL4S_*` environment variables through `run-arm.sh` (one JVM per participant, Sherlock paths hard-coded) and scored in Python |

**Where the gain comes from.** Of Sherlock's 30.4-point gain, about 24.5 come from the decode,
which consumes the aligner's posterior. The aligner's own contribution over local costs was
measured only on FilmFestival (+4.53 points, diagnostic).

## 2. The biggest concerns

Ranked by what they cost the goal.

### Efficacy

1. **"Best" cannot be claimed yet.** There is no human ceiling, no strong baseline and no clean
   test corpus. The within-scene human lane has zero comparisons. No inter-coder reliability exists
   for any recall mapping: Sherlock scenes, FilmFestival `recall_scenematched` and Friends
   `WhichEvent` are each one coding.
2. **An LLM baseline might simply win, and nothing says what then.** It is the cheapest
   experiment in this plan and the one with the most power to redirect it, so it runs first
   (Phase 1a). The published corpora have been public since 2021, so any hosted model may have
   seen them. Contamination is a stated caveat on every LLM row.
3. **The settings are fitted to Sherlock and do not transfer.** Fill costs 14 points on
   FilmFestival. `MonotoneScene.scala:64-75` calls itself "fitted to this corpus". Friends recall
   is 74% non-decreasing, against Sherlock's 98%, so a hard monotone decode should hurt it. That
   last point is inferred.
4. **The measuring instruments mislead.** Two instruments are at fault:
   - **Agreement.** The decode plus fill did not improve dev agreement, while gold accuracy rose
     by about 27 points. A constant anchor scores a perfect 0 s.
   - **Risk–coverage.** It ranks by the argmax's mass, but the decode moves 57% of anchors.

   Retire agreement as a judge of decoders, and recompute risk–coverage on the decoded anchor's
   own mass. `tools/recall-study/README.md:35-37` still calls agreement "the primary outcome".
5. **"Automatic" is currently T1.** Scenes and labels were coded by different people (audit:70),
   but the mapper still reads coder prose. Machine captions were tried once. More than half their
   ordering gain was an anchor-granularity artifact, and their harm to localisation was real
   (study-log:107-146).
6. **Statistical power is unstated.** Sherlock's untouched five gave a CI of [+3.83, +23.81] on a
   16-point effect (study-log:668). Every test split needs a stated size and minimum detectable
   effect before it is read.

### Computational engine

7. **The measured mapper and the library are two systems.** `embed-bench` never references the
   compiler, `PropositionChart` or `StoryModel`, and the decode that supplies most of the gain lives
   in bench code.
8. **Duration is modelled only geometrically.** `TransitionKind.Stay` (`hsmm.scala:24`) gives a
   geometric dwell. There is no explicit duration distribution. A rate prior was tried and refused
   (study-log:726-732), which is evidence against this lever, not for it.
9. **Support honesty.** An empty eligible set publishes `supportWeight` 1.0 on the `hsmm/v3` wire
   (bead `bd-01M19956MFSG7076QE4J66T7E9`). The fix breaks the wire. Its implementation sits on a
   branch 447 commits behind main.
10. **Cross-platform byte identity is claimed but false on Native.** A 1-ULP difference in
    `math.exp` (bead `bd-01M1D215EY4T5BR0VRJ694AMBQ`) goes unseen, because the checksum court runs
    on the JVM only.
11. **The typed story spine has never run end to end on a real text.** The 50-sentence War of the
    Ghosts compile does not validate. All three blocking errors sit at s43, where the provider
    abstained, and an unparsed sentence is silent while an abstained one blocks
    (`compiler.scala:2183-2197`). No compiled real story has ever been aligned. This matters to
    the film route through the spine (D1A-film). It does not affect the mapper in §1.

### Usability

12. **No runnable path from inputs to a mapping.** `storyBuild` stops at a model. Alignment
    examples start from a 1,559-line hand-built fixture. The film mapper is `run-arm.sh` plus
    environment variables.
13. **Nothing can be depended on.** Publishing is off, there is no release, and the docs are not
    deployed. Main is 69 commits ahead of origin, and CI has never executed a job.
14. **The docs contradict the build and overstate the product.** They repeat stale grakern notes
    and wrong module counts. The README implies distortion detection and fidelity facets that have
    no efficacy evidence, and the mode gate has never refused anything on Sherlock.
15. **Failure modes flatter the user.** `storyBuild` exits 0 on an unvalidated draft, I/O errors
    are hashed, and there are 45 unrelated refusal types.

### Project

16. **Unique work at risk, disk pressure, tracker drift.** Branch `audit/masc-role-corpus`
    (`b756b4bb`) exists only inside an unregistered clone. The disk is at 91%, with about 16 GB of
    merged or redundant checkouts. 12 of 13 "doing" beads are stale.

## 3. The plan

```text
Phase 0 (days) ─> Phase 1a (LLM + baselines on dev; Friends split drawn) ─> checkpoint
                                                                              │
                              ┌───────────────────────────────────────────────┤
                              v                                               v
              Phase 1b (full benchmark)                 Phase 2 (mapper in the library)
                              └──────────────────────> Phase 3 (climb) ─> Phase 4 (ship 1.0)
```

### Phase 0 — Protect and clean (days)

**Rescue unique work first.** Fetch `b756b4bb` into a main-repo ref, then verify it with
`git for-each-ref --contains`, not object presence. Only after that does anything get deleted.

**Remove checkouts only with the owner's OK, by this procedure:**
- **The ten directories under `.worktrees/`:**
  - Four registered worktrees: `ladder-run`, `own-the-metric`, `perception-first-court` and
    `rootseg`. All are merged into main and clean.
  - Six unregistered clones: `feature-values-dev` (holds `b756b4bb`), `filmfestival-evaluation`,
    `filmfestival-trace-run` (its origin is `filmfestival-evaluation`), `feature-atlas-dev`
    (redundant with `../storyatlas4s`), `feature-grakern-ref` and `feature-intaglio-ref`.
- **Before removing a directory:**
  - Audit its ignored files by digest. `perception-first-court/tmp/` holds 144 MB of study arms
    and scene frames, and it matches the data root by name only.
  - Check that no live process has its working directory there.
  - Worktrees go through `git worktree remove` and then `prune`.
- **Before removing `feature-grakern-ref`:** re-point
  `docs/calibration/2026-09-04-mutations.json`, which cites that path ten times. Commit
  `d736dc5` is on grakern's origin, so the receipt can point there instead.

**Tracker.**
- Close the beads for landed work: T1–T5, the nine T4 probe beads, `PopulationAggregate` and
  `MassRatio`.
- Move V1 and E0 to 1.x.
- Supersede the fleet-era film-chain bodies.
- Release the fleet assignees.

**Corrections.**
- The README's distortion and facet claims, the grakern note, and the module counts.
- `tools/recall-study/README.md:35-37`, which says "agreement is the primary outcome".
- The stale "ladder unscored" note in memory.

**Build.** Fix the `checkAll` alias so formatting runs last.

**CI and push.** These are the owner's decisions. Until then every gate is LocallyObserved, and
69 commits exist only on this machine.

### Phase 1a — Baselines on development data, and a clean split (S–M)

- **Draw the Friends test split now.** Draw it at participant level, commit it with its seed, and
  **before** any further Friends statistics are computed. The 74% monotonicity figure was computed
  over all 23 participants, so Friends test is "model-untouched, descriptive statistics seen".
  Declare that.
- **Run baselines on Sherlock and FilmFestival (dev, T1).**
  - Run the LLM direct-labelling baseline with its calls recorded.
  - Give it a separate "remote" leaderboard row, carrying the runtime identity (ADR 0008:15-41:
    hosted weights cannot be pinned).
  - Pair it with an **open-weights pinned** row.
  - Also run BM25, cosine, and the current pipeline with and without fill.
- **Pre-declared response if an LLM wins outright:**
  - The library's value moves to what an LLM row lacks: pinned reproducibility, structured
    output with calibrated confidence and alternatives, typed playback intervals, and cost.
  - The LLM becomes a scored component (H1), not a rival.
  - The owner decides at the checkpoint, with the numbers in hand.
- **Needs from the owner:**
  - a spend budget;
  - a disposition on sending participant recall prose to a hosted API (§5).

### Phase 1b — Benchmark v1 (M–L)

- **Tasks** live in one versioned descriptor per corpus.
  - Sherlock: exact scene of 50, within one scene, and temporal error.
  - FilmFestival: film identity. Scenes within a film need the clock work first: the +106 run-2
    offset, run-relative times, and media equivalence.
  - Friends: `WhichEvent` events (630 gold units; veridical recall only, friends-plan:170).
  - Memento, once admitted.
- **Metrics:** a participant-bootstrap CI on every number, stated split sizes and minimum
  detectable effects, and risk–coverage on the decoded anchor's mass.
- **Segmentation:** evaluate `RecallSegmenter` against Friends' gold runs. It has never been
  evaluated.
- **Human ceiling:**
  - Commission a second coding of sampled units for Sherlock scenes, FilmFestival
    `recall_scenematched` and Friends `WhichEvent`.
  - Run the within-scene human lane.
- **Output:** one command regenerates the report and a leaderboard file per corpus and track,
  from upstream releases plus hashed manifests.

### Phase 2 — The mapper in the library (L)

This phase includes **D1B's signature work**, and it depends on **D1A-types S0–S2**. It is the
same work the film chain already planned, now done for the measured mapper instead of for the
spine.

- **The typed source view.**
  - `SourceView` no longer assumes canonical text.
  - Each source node carries checked `EvidenceSupport` and its primary projection, so a film
    segmentation's nodes carry exact `PlaybackInterval`s.
  - `HsmmResult` retains exact source intervals.
  - This replaces `TimedSourceView`'s joined-text surrogate.
- **Declared, versioned decoders.** `MonotoneScene` is generalised. Fill is **off by default**
  until H2 decides it, and per-corpus settings are data.
- **`recall-map`,** one command:
  - Input: a corpus descriptor plus recall transcripts.
  - Output: a mapping (per-unit segment and interval, confidence, alternatives) and a
    self-contained HTML report.
  - It exits non-zero on anything short of a complete mapping, with actionable errors.
- **The benchmark runs through `recall-map`,** and `run-arm.sh` is retired.
- **Parity first.** Sherlock's per-unit anchors reproduce byte-for-byte through the new path
  before anything else changes.

### Phase 3 — Climb (iterative)

Each hypothesis runs as an arm. It is compared on development data, then read **once** on the
Friends and Memento test splits after it is frozen, against its stated minimum detectable effect.
The order below is judgement, not measurement.

- **H1 — LLM candidate scoring,** with calls recorded. It has a pinned open-weights variant and a
  remote variant. Phase 1a sizes the headroom.
- **H2 — Transfer-safe decoding.**
  - Estimate monotonicity from each participant's own data.
  - Add a storyline-aware decode for braided recall.
  - Fill: decide it per corpus or drop it.
  - An explicit duration distribution comes last, because a rate prior was already refused.
- **H3 — Coder-independent inputs (T2–T4).** Use dialogue, captions and crowd descriptions. The
  independence ablation says what "automatic" means per track. T4 on held-out data needs media
  acquisition with edition equivalence first.
- **H4 — Calibrated confidence and abstention,** after concern 9 is fixed.
- **H5 — The structural channel (the typed spine).**
  - A pilot tests whether a proposition-level channel separates gold scenes beyond the best
    retrieval channel. Candidates are `d_chart`, or atomic claims plus NLI (own-the-metric:321-326).
  - Fixing the s43 gate asymmetry belongs here, with an ADR 0005 amendment. It changes
    validation semantics and must not look like weakening a gate to make one text pass.
  - Whether D1A-film waits for this pilot is decision 3 in §5.

### Phase 4 — Ship 1.0 (M–L)

- **Release plumbing.** Publishing, a MiMa baseline, and deployed docs.
- **Wire and fixture repairs.** The `hsmm/v4` support-honesty wire (it breaks the wire, so it goes
  before the freeze), and platform-labelled goldens.
- **Errors and entry point.** One refusal supertype, readable I/O errors, and the facade around
  `recall-map`.
- **An explicit stability table.**
  - **Stable at 1.0:** the mapper API, the typed source view, `HsmmResult`, and the core evidence
    types.
  - **Experimental, outside MiMa:** `StoryModel`, the compiler and the proposition spine, unless
    D1A-types S4 and D1A-film land first. This follows the 1.0 handoff's rule that 1.0 must "say
    plainly" what it does not freeze (one-point-oh-handoff:430-434).

## 4. What this changes in earlier decisions

- **Decision E** (1.0 ships film recall library end to end) **stands in substance.** The route
  changes. Film reaches 1.0 through the measured mapper and D1B's typed source view (Phase 2), not
  through the story compiler.
- **D1A-types.** S0, S1 and S2 go ahead, because Phase 2 needs S2's core types. S3 goes ahead only
  if D1B needs the `EvidenceRef` support accessor. S4a–S4c pause. They make `StoryModel`
  film-capable, and `StoryModel` becomes experimental at 1.0 unless the spine route is funded.
- **D1B** splits. Its signature work moves into Phase 2. Its spine-dependent proofs go with
  D1A-film.

## 5. Decisions for the owner

1. **Adopt the goal-first order,** with the LLM baseline first, and Friends and Memento as the
   test corpora.
2. **1.0's stability table** (Phase 4): the mapper is stable, and the spine is experimental unless
   funded.
3. **Ruling A and the spine's film route.** Choose one:
   - Build D1A-film regardless, which keeps ruling A as it stands.
   - Sequence it after the H5 pilot. That puts a research result in front of an engineering
     spend, so ruling A would have to be narrowed explicitly.
4. **Hosted models and participant data.** Can recall prose go to a hosted API? What is the spend
   budget for the Phase 1a baseline and H1?
5. **Commissioning second human codings** for Sherlock, FilmFestival and Friends samples.
6. **Media acquisition** for FilmFestival and Memento. This is needed for T4 on held-out data.
7. **Phase 0 deletions, CI and the push.**

## 6. Non-claims

- The figures come from four surveys and one cold review. The author re-checked the validation
  gate, the orphaned commit, the FilmFestival partition statement, the per-corpus video absence,
  the dwell transitions, the environment-variable count, and the baseline and caption passages.
- H1–H5 are hypotheses ordered by judgement.
- "Best in the world" can be claimed only per track, only against the baselines actually run, and
  only on these corpora.
- Revision 2 has not itself been cold-reviewed.
