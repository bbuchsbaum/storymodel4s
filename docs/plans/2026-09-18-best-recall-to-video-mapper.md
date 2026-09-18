# Toward the best recall-to-video mapper: concerns and a plan

*2026-09-18. Draft for owner approval, written against `main` at `448f04d6`.*

*The owner's goal, stated 2026-09-18: "the best automatic recall-to-video mapper in the world;
Sherlock and FilmFestival are test cases (also Friends)."*

*Sources: four read-only surveys run the same day (the computational engine, efficacy, usability,
project state), each citing file:line. The author spot-checked the facts this plan leans on. Every
claim is LocallyObserved. Where a claim rests on a survey rather than on the author's own check,
it says so.*

## 0. What "best" has to mean before anyone can claim it

"Best in the world" is a comparative claim. It needs three things that do not exist yet:

1. **A benchmark someone else could rerun.** That means fixed tasks, metrics with confidence
   intervals, and a locked test split for each corpus.
2. **The strongest competitors, run on the same benchmark.**
   - The comparator a 2026 reviewer will demand first is a long-context model given the source
     descriptions and the recall, asked to label each unit directly
     (`docs/plans/2026-09-04-own-the-metric.md:169-174`).
   - Next is the published Sherlock method: the topic-model trajectory approach of Heusser,
     Fitzpatrick and Manning (2021). The repo already tracks its 30-event segmentation as
     `TopicHmmEvent30` (`docs/design/sherlock-source-representation-audit.md:65`).
   - Then BM25, embedding cosine, and the existing 9.7% timing-only floor.
3. **A human ceiling.** How often do two human coders agree on where a recall unit belongs? Until
   that is known, 63.8% cannot be read as good or bad.

The own-the-metric plan of 2026-09-04 said this already. Its line was: "Supremacy is won by
releasing the benchmark, not by winning it." Of its six first-wave items, only the apparatus repair
(W1.0) and risk–coverage (W1.5) are done. The rest are half done or untouched, according to the
efficacy survey: a second corpus exists only for FilmFestival, and the independence ablation covers
only which film was watched.

## 1. Where the mapper stands

| | Today |
|---|---|
| Best measured system | Retrieval against coder scene descriptions, then the library's `GraphHsmm`, then a bench-side monotone scene decode (`MonotoneScene`), then a gap fill |
| Sherlock | **63.8%** exact scene of 50, 83.0% within one scene, against a 9.7% timing-only floor. The decode's gain replicated on 5 held-out participants. The headline figure has no confidence interval |
| FilmFestival | 67.5% film identity against 11.3% chance, dev only. The same pipeline **without** fill scores 81.5%. Fill costs 14.0 points there and gains 6.8 on Sherlock |
| Friends | The model has **never been run**. The .39/.90 correlations describe the human coding, not the mapper |
| Human ceiling | **None, for any task** |
| LLM or published baselines | **None run** |
| Where it lives | `embed-bench`, a diagnostics module. It is driven by about 26 environment variables through `run-arm.sh` (one JVM per participant, Sherlock paths hard-coded) and scored in Python |

**Where the gain comes from.** Of Sherlock's 30.4-point gain, about 24.5 points come from the
bench-side decode applied after inference, not from the library aligner. The aligner's own argmax
scores 33.4–36.0%. These figures are from the engine survey, citing own-the-metric.

## 2. The biggest concerns

Ranked by what they cost the goal. Each gives the evidence, then what resolves it.

### Efficacy

1. **No human ceiling and no strong baseline, so "best" cannot be claimed.**
   - *Evidence:* the efficacy survey.
     - The within-scene human lane has zero comparisons.
     - FilmFestival inter-coder reliability was never computed (`filmfestival-handoff.md:139`).
     - The LLM and published baselines were never run.
   - *Resolves it:* Phase 1.
2. **The settings are fitted to Sherlock and do not transfer.**
   - *Evidence:* fill costs 14 points on FilmFestival. `MonotoneScene.scala:64-75` calls itself
     "fitted to this corpus". Blend 0.8, prior 1.5, candidates 8 and `hard` were all chosen on
     Sherlock dev. Friends recall is 74% non-decreasing, against Sherlock's 98%, so a hard
     monotone decode should hurt it. That last point is inferred, not measured.
   - *Resolves it:* Phase 3, hypothesis H2.
3. **The Sherlock holdout is spent.**
   - *Evidence:* the untouched five were read four or five times. A gold-based sweep appears only
     in a code comment.
   - *Resolves it:* Sherlock becomes the development corpus. Locked test splits for FilmFestival
     and Friends are drawn before anything is tuned on them (Phase 1).
4. **The measuring instruments mislead.**
   - *Evidence:* the decode made dev agreement worse (85.5 s to 92.0 s) while gold accuracy rose
     27 points, and a constant anchor scores a perfect 0 s. Risk–coverage ranks units by the mass
     of the argmax, but the decode moves 57% of anchors, so the confidence often belongs to a
     different node.
   - *Resolves it:* retire agreement as a way to judge decoders, and recompute risk–coverage on
     the decoded anchor's own mass.
5. **"Automatic" currently means "matched to a coder's prose".**
   - *Evidence:* every Sherlock number matches recall to the coder descriptions, from the same
     annotation family that defines the gold (own-the-metric:43-45). Machine captions (Qwen3-VL)
     were tried once. More than half their ordering gain was an anchor-granularity artifact, and
     their harm to localisation was real (`recall-to-video-study-log.md:107-146`).
   - *Resolves it:* Phase 3, hypothesis H3, plus the independence ablation.

### Computational engine

6. **The measured mapper and the library are two systems.**
   - *Evidence:* `embed-bench` never references the compiler, `PropositionChart` or `StoryModel`.
     The decode that supplies most of the gain lives in bench code.
   - *Resolves it:* Phase 2.
7. **The "HSMM" has no duration model.**
   - *Evidence:* `align/src/main` has no duration or dwell term. It is a first-order HMM over
     candidate states (engine survey). A duration prior is a standard lever for event-sequence
     alignment that was never tried.
   - *Resolves it:* H2.
8. **Support honesty.**
   - *Evidence:* an empty eligible set publishes `supportWeight` 1.0 on the `hsmm/v3` wire
     (`cost.scala:847,850`; bead `bd-01M19956MFSG7076QE4J66T7E9`). The fix breaks the wire, and
     its implementation sits on a branch 447 commits behind main.
   - *Resolves it:* Phase 4, before 1.0 and before any confidence calibration.
9. **Cross-platform byte identity is claimed and false on Native.**
   - *Evidence:* a 1-ULP difference in `math.exp` (bead `bd-01M1D215EY4T5BR0VRJ694AMBQ`). The
     checksum court is JVM-only, so Native tests cannot see it.
   - *Resolves it:* Phase 4 governs it, with platform-labelled goldens.
10. **The typed story spine has not yet run end to end on a real text.**
    - *Evidence:* the 50-sentence War of the Ghosts compile does not validate. All three blocking
      errors sit at one sentence (s43), where the provider abstained. The authored three-chart
      replay validates, so the gate is asymmetric: an unparsed sentence is silent and an abstained
      one blocks (`compiler.scala:2183-2197`, checked by the author). Alignment accepts only
      validated models, so no compiled real story has ever been aligned.
    - *Why it matters:* this is the spine the D1A film work extends. It is not the mapper
      measured in §1.
    - *Resolves it:* a cheap fix in Phase 0 (a typed absence, as ADR 0005 §10 already did for the
      summary). Whether the spine helps mapping at all is hypothesis H5.

### Usability

11. **No runnable path from inputs to a mapping.**
    - *Evidence:* `storyBuild` stops at a model, and alignment examples start from a 1,559-line
      hand-built fixture. The film mapper is `run-arm.sh` plus environment variables. The only
      alignment report is a Sherlock-only Python page.
    - *Resolves it:* Phase 2's `recall-map` command.
12. **Nothing can be depended on.**
    - *Evidence:* publishing is off, there is no release tag, and the docs site is not deployed.
      Main is 69 commits ahead of origin, and CI has never executed a job.
    - *Resolves it:* Phase 0 (push strategy) and Phase 4 (publish).
13. **The docs contradict the build and overstate the product.**
    - *Evidence:* the README says grakern is unpublished, while the build clones it. Module
      counts are wrong. The README implies distortion detection and fidelity facets that have no
      efficacy evidence: the mode gate has never refused anything on Sherlock.
      `tools/recall-study/README.md` still cites a retracted sign test.
    - *Resolves it:* Phase 0 for the false claims, Phase 4 for the rest.
14. **Failure modes flatter the user.**
    - *Evidence:* `storyBuild` exits 0 on an unvalidated draft. I/O errors are hashed ("cannot
      read path (reason checksum)"). There are 45 unrelated refusal types.
    - *Resolves it:* Phase 2 for the CLI, Phase 4 for error ergonomics.

### Project

15. **Unpushed work, disk pressure, tracker drift.**
    - *Evidence:*
      - One branch (`audit/masc-role-corpus`, `b756b4bb`) exists **only** inside an unregistered
        clone under `.worktrees/`. The author verified it is not in the main repo.
      - The disk is at 91%, with about 16 GB of merged or redundant worktrees and clones.
      - 12 of 13 "doing" beads are stale.
      - The film-chain beads V1 and E0 are still P0 with fleet-era bodies.
    - *Resolves it:* Phase 0.

## 3. The plan

Phases 1 and 2 run in parallel after Phase 0. Phase 3 needs both.

### Phase 0 — Protect and clean (days)

- **Rescue `b756b4bb`** into the main repo as a branch.
- **Free disk.** After the rescue, remove the four merged worktrees and five redundant clones. This
  is a deletion step: it lists every path and waits for the owner's OK.
- **Tracker pass.**
  - Close the beads describing landed work: T1–T5, the nine T4 probe beads, and
    `PopulationAggregate`/`MassRatio`.
  - Move V1 and E0 to 1.x.
  - Rewrite or supersede the fleet-era film-chain bodies (D1B, V1, the CODE RED parent).
  - Release the fleet assignees.
- **Correct the false claims.**
  - The README's distortion and facet claims, the grakern note and the module count.
  - The retracted sign test in `tools/recall-study/README.md`.
  - The stale "ladder unscored" note in memory.
- **Fix the `checkAll` alias** so formatting runs last.
- **Fix the text spine's asymmetric gate** (concern 10). Record an abstained derivation as a typed
  absence rather than a blocking error, and add a court that the War of the Ghosts compile
  validates or names exactly why not.
- **CI.** The owner deferred billing, so every gate stays LocallyObserved. Options: settle
  billing, make the repository public, or use a self-hosted runner. Pushing main is the owner's
  call; 69 commits exist only on this machine.

### Phase 1 — Benchmark v1: make "best" measurable (M–L)

- **Tasks.** Each task, with its gold and unit definition, lives in one versioned descriptor per
  corpus. The corpus descriptor reader landed with the intake contract.
  - Sherlock: the exact scene of 50, within one scene, and temporal error in seconds.
  - FilmFestival: the film watched, then the scene within that film.
  - Friends: the `WhichEvent` event, 630 gold units, with storyline-aware scoring for braided
    recall.
- **Splits.**
  - Sherlock is the development corpus.
  - FilmFestival and Friends each get a participant-level locked test split, drawn **now** and
    committed with its seed before any tuning.
  - Memento (133 participants) is admitted as a third test corpus when ready.
- **Metrics.** Participant-bootstrap confidence intervals on every number, including the ones
  that currently lack them. Risk–coverage is scored on the decoded anchor's own mass.
- **Baselines**, each with a stated tuning budget:
  - timing-only;
  - BM25;
  - embedding cosine;
  - the current pipeline;
  - Heusser et al.'s method on Sherlock;
  - an **LLM direct-labelling baseline**: a long-context model given the scene descriptions and
    the recall. Its calls are recorded, so it replays offline.
- **Human ceiling.**
  - Compute inter-coder agreement wherever two codings exist.
  - Where only one exists (the Sherlock scenes, Friends `WhichEvent`), commission a second coding
    of a sampled subset.
  - Run the within-scene human lane (about 90 minutes).
- **Output:** a benchmark report and one leaderboard file per corpus, regenerated by one command.

### Phase 2 — The mapper in the library, one path (L)

- **Promote the bench pipeline into library modules.**
  - A typed timed source over corpus segmentations, with `PlaybackInterval` coordinates rather
    than `TimedSourceView`'s joined-text surrogate.
  - Declared, versioned decoders: `MonotoneScene` generalised, with fill declared and **off by
    default** until H2 decides it.
  - Per-corpus settings as data, not environment variables or code.
- **One command, `recall-map`.** It takes a corpus descriptor plus recall transcripts and writes a
  mapping (per-unit scene or interval, confidence, alternatives) and a self-contained HTML report.
  It exits non-zero on anything short of a complete mapping, with actionable errors.
- **Benchmark runs go through `recall-map`.** `run-arm.sh` and its 26 environment variables are
  retired.
- **Parity:** the Sherlock 63.8% must reproduce through the new path, byte for byte on the
  per-unit anchors, before anything else changes.

### Phase 3 — Win the benchmark (iterative)

Each hypothesis becomes an arm. It is compared on development data, and **read once** on the locked
test splits when frozen. The order below is the author's estimate of gain per cost. It is not a
measurement.

- **H1 — LLM candidate scoring.** Rerank the top candidates with a model that reads the recall
  unit against each candidate scene's description. Calls are recorded for replay. Phase 1's LLM
  baseline shows how much headroom exists.
- **H2 — Transfer-safe decoding.**
  - Estimate how monotone the recall is from the data instead of fixing `hard`.
  - A storyline-aware decode for braided recall (Friends).
  - A real duration prior.
  - Fill decided per corpus or dropped.
- **H3 — Descriptions that do not depend on coders.**
  - Combine dialogue (subtitles or ASR), machine captions and coder annotations.
  - The independence ablation, perception-only against annotation-only, says what "automatic"
    can honestly mean on a film nobody has annotated.
- **H4 — Calibrated confidence and abstention.** It wins only once concern 8's support honesty
  is fixed.
- **H5 — The structural channel (the typed spine).**
  - A cheap pilot comes first: does a proposition-level channel separate gold scenes better than
    the best retrieval channel? That means `d_chart`, or atomic-claim decomposition plus an NLI
    checker, the FactScore/AlignScore line that own-the-metric:321-326 names.
  - Only a positive pilot funds the spine's film route (D1A-film, D1B).

### Phase 4 — Ship 1.0 (M–L)

- Publishing, a MiMa baseline and a deployed docs site.
- The `hsmm/v4` support-honesty wire, which breaks the wire, so it goes before the freeze.
- Platform-labelled goldens.
- One refusal supertype with readable I/O errors.
- The progressive facade around `recall-map`.
- 1.0's public surface is the mapper and the types it needs.

## 4. What this changes in earlier decisions

**Decision E should be revisited.** On 2026-09-18 the owner accepted the author's recommendation
that 1.0 ship film recall "library end to end" **through the story spine** (D1A-film and D1B).
The surveys since then show three things:

- the measured mapper does not use that spine;
- the spine has not yet validated one real text;
- the spine's value for mapping is untested.

Given the stated goal, the author now recommends a different route to the same promise. 1.0 ships
the best measured mapper in the library (Phase 2), which carries video recall through typed
playback intervals. The spine's film route becomes hypothesis H5, funded by its pilot.

**D1A-types.** S0 (the text parity pins) and S1 (the ADR amendment) are still worth doing, because
they are cheap and protect the text path. S2–S4c freeze `StoryModel` for film. They pause until H5
says whether film ever goes through `StoryModel`. Freezing that type for a route that may never be
built has no benefit.

**Ruling A stands.** Engineering is not gated on research questions. What Phase 3 introduces is a
product gate: an XL component is built after the benchmark shows it helps the mapper, not before.

## 5. Decisions for the owner

1. **Adopt the goal-first ordering: benchmark, then library mapper, then climb.** This demotes the
   D1A film route to H5.
2. **Revise decision E**, as in §4: 1.0 is the best mapper in the library, and film through the
   story spine is 1.x or later, if H5 pays.
3. **The Phase 0 deletions and the push.** The author lists paths and waits for an OK before
   deleting anything. Pushing main, and when, is the owner's call.
4. **A budget for the LLM baseline and for H1's recorded calls.** Both spend against a hosted
   model.
5. **Commissioning a second human coding** of sampled Sherlock and Friends units.

## 6. Non-claims

- Most figures here come from the surveys, which cite the repo's own study logs. The author
  re-checked the validation gate, the orphaned commit, and the baseline and caption passages. The
  rest are as the surveys reported them.
- H1–H5 are hypotheses ordered by judgement. None is a measurement.
- "Best in the world" cannot be claimed until Phase 1 exists. Even then it is claimable only
  against the baselines actually run, on these three corpora.
