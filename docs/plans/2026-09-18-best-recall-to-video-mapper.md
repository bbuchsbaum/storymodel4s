# Toward the best recall-to-video mapper: the plan (revision 6)

*2026-09-18. Draft for owner approval, written against `main` at `598c7852`. Everything below is
LocallyObserved.*

*Revision 6 applies three owner-approved changes to revision 5 (2026-09-18):*
- *the participant-average difference is the primary Phase 1a estimand, as in the preregistration
  and the scorer;*
- *Phase 2 orders the mapper slices before the film-compiler slices;*
- *Memento is admitted and sealed, so the single final test opening has enough participants.*

*Revision 5 preserves revision 4's input-size correction, FilmFestival inventory and explicit
scope alternatives. It makes the recorded release scope the default, closes the scoring-denominator
contract, reserves one final test opening, moves support honesty before confidence work, separates
LLM labels from candidate scores, and withdraws the contamination interpretation of titles-only
performance.*

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
This is also the default while P1 is undecided: approving the plan does not implicitly adopt P1.
Phase 2 includes D1A S3–S4c, D1A-film and D1B's end-to-end proofs as separately gated slices.
H5 is advisory under the recorded rulings; its result does not gate film engineering.

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
6. **Power is unstated.** Only 10 development participants have usable Sherlock gold. Uncertainty
   estimates with so few clusters need sensitivity checks; changing the interval method does not
   create an independent confirmation sample.
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
               Phase 1b (benchmark v1)                                 Phase 2 (library + support honesty)
                           └──────────────────────────────────> Phase 3 (development climb)
                                                                          │
                                                            freeze → one final test opening
                                                                          │
                                                                  Phase 4 (ship 1.0)
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
- The available pool is 23 participants and 630 gold units, not the test-set size. Record the
  actual development/test counts and power assumptions when the split is drawn, before model
  outputs are read. Test metadata used for this accounting must not enter tuning.
- Nothing in Phases 1a–2 reads it. The segmenter evaluation and any second coding in Phase 1b use
  development participants only.
- Record the prior exposure: aggregate recall-order statistics have already been inspected across
  all 23 participants. This is a model-untouched split, not an entirely unseen corpus.

**Step 1b. Admit and seal Memento (owner decision, 2026-09-18).** Friends alone would leave the
single final test with perhaps ten participants. Memento has 133 (`docs/data/memento/README.md`).
- **Admission.** The owner's decision clears blocker 1 in the Memento record. Blocker 2, an ethics
  basis for committing recall prose, bars only committing text. Analysis reads the staged bytes
  from the ignored data root, and nothing text-bearing is committed.
- **Task, fixed at admission and before any model output:**
  - scene gold on the five-second grid;
  - only accurate recall (codes 1–2) enters accuracy; codes 3–5 never do;
  - the seven non-integer rows are excluded explicitly;
  - the population comes from `recall-population.json`, not sheet order.
- **Seal** a participant-level test split, **stratified by the four conditions**, before any model
  or tuning inspection. Commit the seed and membership, and record the counts and power
  assumptions. Condition 1 (the nonlinear cut) is the hardest transfer test for monotone
  decoding. Condition 4 (the linear re-cut) is its within-corpus control.
- The final test opening (§3, Phase 3) covers both sealed corpora.

**Step 2. The scoring contract.** This is a fix in `tools/recall-study`, because today the scorer
fails open.
- Export B3's **unit manifest**: stable unit id, participant, ordinal, text checksum, onset and
  source-input checksum. The current pipeline produces
  2,560 `RecallSegmenter` units, 67–351 per participant (arm `all17-monofill`).
- Keep text-bearing manifests and arm outputs in the ignored data root; commit only hashes and
  aggregates. Freeze gold eligibility and exclusion reasons independently of arm predictions.
- Every arm returns one typed outcome for every manifest unit. Language-model arms use constrained
  JSON keyed by unit id. Outcomes distinguish a valid label, abstention, invalid response and
  provider failure; raw failures and any budgeted retries remain in the receipt.
- `gold_scene.py` changes before any baseline comparison:
  - reconcile each arm against the manifest, refusing missing, duplicate or extra unit ids,
    changed unit identity, and unexpected participant files;
  - retain explicit blank/invalid predictions, abstentions and provider failures as wrong in the
    primary exact-scene denominator whenever the unit has gold;
  - retain units without gold in accounting, but outside the accuracy denominator under the
    existing preregistration; never infer gold eligibility from a prediction;
  - report input count, gold-eligible count, every exclusion/outcome count and prediction coverage;
  - use the same frozen denominator and unit weights for both arms, including paired resamples.
- **The primary Phase 1a estimand is the participant-average difference:** the mean of
  per-participant exact-scene accuracy differences. This is what the preregistration's paired
  comparisons report and what `gold_scene.py` `paired` computes (`:170-181`). The pooled,
  unit-weighted difference is reported as a secondary series. The two are not interchangeable:
  fill's development gain is +5.77 by the participant mean, while pooled accuracy rose 7.3 points
  (study-log:714). Per-participant unit counts range from 67 to 351, so unit weighting lets the
  heaviest recallers dominate.
- **Scorer drops today:** it discards unlabelled rows silently (`:108-112`), and `paired` never
  checks unit identity (`:171`).
- **Mutation witnesses.** Drop, duplicate or change one unit id and the paired run must fail.
  Blank a wrong label or replace it with a failure and primary accuracy must not improve.
  A no-gold unit must remain accounted for without becoming a model error.

**Step 3. The preregistration governs.** The committed Sherlock preregistration binds this phase.
- **Rule 2:** any choice made on gold uses development participants only.
- **Rule 3:** every gold comparison is counted.
- `partition.json` says the untouched set "chooses nothing".

What that means here:
- **One primary development comparison is pre-specified.** L1 (local) against B3, on the 10
  development participants with usable gold, exact scene. It informs an engineering choice; it
  is not independent confirmation on clean data and does not replace the preregistration's
  historical comparison. Record this additional comparison in the ledger before running it.
- Freeze the CI method, seed and resample count before scoring. Resample whole paired participant
  clusters and recompute the participant-average difference, with the pooled difference as a
  secondary series; show participant-level differences and a
  leave-one-participant-out sensitivity analysis. Report the small-cluster limitation.
- **Every other arm is descriptive.** Any inferential family and multiplicity adjustment must be
  declared before its outputs are read; an unadjusted best-arm CI cannot establish superiority.
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
| B3 / B4 | current pipeline, with and without fill (B3 is the primary development comparator) |
| L0 | local model given **scene numbers and durations only**. An order/timing diagnostic against B0; it does not isolate prior episode knowledge |
| L1 | local model, per transcript: labels every unit in order. **Primary development arm** |
| L2 | local model, per unit, with prefix caching. Isolates matching |
| L3 | local model, per transcript, **titles only**. Title-information ablation, compared separately with B1t and B2t; not a contamination estimate |
| H1, J1 | hosted frontier model; Jev. Only after the owner's per-corpus data decision and Jev access |

**FilmFestival (development).** Film identity with B1, B2, B3/B4, L1 and L3, on the coder index
(T1) and the crowd index (T2). This is a cross-corpus development check. Training exposure is
unknown; obscurity or wordlessness does not establish that a film or its annotations were unseen.

**Step 6. Pre-declared rules, fixed before any language-model output is read.**
- **R1.** The primary development L1 − B3 CI excludes zero in L1's favour. Phase 2 then includes
  the winning label-producing provider as a **Phase 2 deliverable**. A score-producing adapter
  must pass the separate score-contract pilot below before H1 feeds it through the decode.
  This rule selects engineering work; it does not establish superiority on clean test data.
- **R2.** It does not. Phase 2 ships only the channel interface, with embedding and lexical
  providers. Language-model providers wait for H1 in Phase 3.
- **R3.** Report L3 as an input ablation and cross-corpus differences as transfer diagnostics.
  Strong title matching can reflect semantic inference, and weak matching does not rule out
  memorisation. Neither result establishes or bounds contamination. Record known training-data
  provenance and unresolved exposure for every model/corpus pair, including Friends and Memento;
  a participant split controls tuning leakage, not episode-level training exposure.
- **R4.** If an authorized hosted arm runs, compare it with the pinned local model on identical
  development units and inputs. If local exact-scene accuracy is within 5 percentage points of
  hosted accuracy, prefer local as the default *language-model* channel. Otherwise the checkpoint
  considers the measured gain, runtime and cost. This is a descriptive engineering preference,
  not a non-inferiority or equivalence claim; without a hosted run, local remains the default.

**Step 7. The readout.** Every arm, with coverage, CI and receipt hash. It is committed before the
checkpoint. The word "calibrated" is not used: preregistration §7 rule 5 bars it on one film.

### Checkpoint

The owner reads the readout, confirms or overrides R1/R2, and decides P1 and P2 if not already
decided. Until an explicit P1 decision changes it, the recorded full film route remains scheduled.

### Phase 1b — Benchmark v1 (M–L; partly waits on people)

- **Tasks,** one versioned descriptor per corpus:
  - Sherlock: exact scene, within one scene, and temporal error.
  - FilmFestival: film identity. Scene within film waits for the clock work: the +106 offset,
    run-relative times, and media equivalence, which Phase 0 step 6 enables.
  - Friends: `WhichEvent`, veridical recall only.
  - Memento: scene on the five-second grid for accurate recall, per condition (Step 1b).
- **Tracks:**

  | Track | Input | Corpora |
  |---|---|---|
  | T1 | coder descriptions | all |
  | T2 | crowd descriptions | FilmFestival |
  | T3 | dialogue only | Friends; FilmFestival speech films via ASR |
  | T4 | video-derived | Sherlock; FilmFestival's eight films |

- **Scoring and statistics:**
  - freeze a primary estimand and weighting rule per task before comparisons. The participant
    average stays primary for continuity with Phase 1a and the ledger. Pooled, word-weighted or
    time-weighted scores are labelled secondary series;
  - a gold-unit oracle row;
  - the segmenter evaluated on Friends development gold;
  - stated split sizes and minimum detectable effects;
  - fixed-denominator accounting from Phase 1a, with abstention coverage measured against all
    gold-eligible units, not only units that received a prediction;
  - risk–coverage for the final emitted decision. The decoded anchor's own mass is a diagnostic
    input, not automatically its probability of correctness. Record scene mass separately from
    leaf mass, and identify fill decisions and missing scores explicitly.
- **Human ceiling.** A second coding of sampled **development** units for Sherlock, FilmFestival
  and Friends, which needs people. Plus the within-scene human lane.
- **Published comparator.** Heusser et al. (2021) on Sherlock, stating that its 30 events are
  recall-tuned and how they are scored against 50 scenes.
- **Output.** One command regenerates every leaderboard from upstream releases plus hashed
  manifests. Nothing restricted is redistributed.

### Phase 2 — The mapper in the library (L for the mapper slices; XL+ with the film route)

Under the recorded rulings, this phase includes all D1A-types slices, D1A-film and all of D1B.
Land the mapper and film-compiler work in separate slices; neither waits for H5. Only explicit
adoption of P1 reduces this to S0–S2 (plus S3 if needed) and D1B's signature work.

**Order (owner-approved, 2026-09-18): the mapper slices first, then the film-compiler slices.**
- **Mapper slices:** D1A S0–S2, D1B's signatures, support honesty, the scoring channel, the
  declared decoders, and `recall-map`. They serve the stated goal, and D1B's type changes are
  shared with the film route.
- **Film-compiler slices, after them:** D1A S3–S4c, D1A-film and D1B's end-to-end proofs.
- **This is sequencing for one developer, not a gate.** H5 does not decide it, and ruling E still
  requires the film slices before 1.0.
- **Size.** The mapper slices are L. The film-compiler slices add roughly another XL: D1A S3–S4c
  are L, D1A-film has no plan yet, and D1B's proofs are XL. Under the recorded rulings, Phase 2 as
  a whole is multi-month.

- **Parity first.** D1A S0 pins the text path. Sherlock's per-unit anchors reproduce
  byte-for-byte through the new path.
- **Typed source view (D1B signatures).**
  - `SourceView` without canonical text.
  - Nodes carry checked `EvidenceSupport` and exact `PlaybackInterval`s.
  - `HsmmResult` keeps exact intervals.
- **Support honesty, before public confidence output or H4.** Land `hsmm/v4` with tagged
  `Assessed` / `Unestablished` / `NotApplicable` support and an explicit support basis. Empty or
  zero-weight eligible evidence must not publish full support. Include schema migration,
  platform-labelled goldens, construction checks and a mutation restoring the false full-support
  value that fails a named test. Text-path anchor parity is established before this separate,
  receipted wire change; any resulting numerical movement is reported, not silently rebaselined.
- **Scoring channel.** One typed score interface with a receipt per call, separate from the
  direct label-producing baseline interface.
  - It declares its **candidate level** (scene or leaf), and how scene scores distribute over
    leaves for `GraphHsmm`. A scene-level score alone cannot establish within-scene precision.
  - It has a **recorded-response replay mode**, so gates run without any model.
  - Providers: lexical and embedding always; language-model providers per R1/R2.
  - **LLM score-contract pilot:** declare the extraction method (for example candidate-label
    likelihoods or an explicit reranker), candidate set/order, score direction and normalization,
    context and retry budgets, and unavailable-score outcomes. Pin its prompt and receipt.
    A generated label, verbal confidence or absent candidate must not silently become a
    probability vector, certainty, or zero score. Record candidate coverage and rerun agreement.
  - Run the pilot on development data as a separately counted arm. R1's direct-label win does
    not establish that this scoring adapter works. If scores cannot be obtained under the
    declared contract, retain the direct labeller and report H1 as unavailable for that provider.
- **Reliability belongs to the final decision.** Define the event being assessed (correct scene,
  correct film, or a declared interval tolerance), and bind the reliability model to the complete
  scorer, candidate set, decoder, fill policy and support version. Fit on a development calibration
  partition or participant-level out-of-fold predictions, separate from its reliability assessment.
  Report held-out reliability and risk–coverage with their corpus limits. A provider-score mapping
  does not certify a changed decoded anchor; changing the decision policy requires reassessment.
  Preserve the Sherlock preregistration's bar on a calibration claim based on one film.
- **Declared decoders.** `MonotoneScene` generalised; fill off by default; per-corpus settings as
  data.
- **`recall-map`.**
  - Inputs: a corpus descriptor and recall transcripts.
  - Output: one outcome per input unit, with a supported mapping or explicit abstention/external
    state/failure. Mappings carry intervals, score provenance, available alternatives and a typed
    reliability status; unavailable scores or reliability are explicit. Include an HTML report.
  - Completeness means every input unit is accounted for, not that every unit is forced onto
    the video. Valid abstentions and external states succeed; malformed/incomplete artifacts or
    provider failures cause a non-zero exit while retaining the receipted partial result.
  - The benchmark runs through it, and `run-arm.sh` retires.
- **Full film route (the default).** D1A-film compiles a film source through the library API;
  D1B aligns recall against that compiled source and preserves exact playback intervals. Land its
  end-to-end proofs and refusal mutations before declaring Phase 2 complete under ruling E.

### Phase 3 — Climb (iterative)

Each hypothesis is an arm, iterated and selected using development data only. Friends and Memento
test outputs remain sealed throughout that iteration; "once per hypothesis" is not the policy.

- **H1.** Language-model scoring through the decode.
- **H2.** Transfer-safe decoding:
  - monotonicity estimated per participant;
  - a storyline-aware decode;
  - fill per corpus;
  - a duration distribution, last.
- **H3.** Coder-independent inputs: dialogue, captions from the FilmFestival and Sherlock frames,
  and crowd descriptions, plus the independence ablation.
- **H4.** Confidence and abstention, using Phase 2's support-honesty and final-decision contracts.
- **H5.** The structural-spine pilot. Its s43 validation fix comes with an ADR 0005 amendment.
  Under ruling A this pilot informs mapper design, not permission to build D1A-film.

**Final freeze and one test opening.** Before reading any test output, commit a release-candidate
manifest: code/model/prompt hashes, all compared configurations and baselines, calibration fits,
decoder/fill/abstention settings, task estimands and weights, exclusions, primary contrasts,
multiplicity treatment, CI methods and power assumptions. Select the shipping configuration on
development data before this freeze. Baselines and frozen ablations run in the same final batch.

Open each sealed corpus test once for that frozen batch. Compare results with the predeclared
effect target and uncertainty; lack of evidence for a gain is not evidence of equivalence. Test
results neither choose the winner nor start another climb on the same test participants. Any
output-affecting correction, retuning or new hypothesis after opening is labelled development
work on a consumed test; a new confirmation claim requires new untouched data. Preserve all
failures and the original readout. Record an unavailable corpus or arm rather than substituting
one after seeing results. Shipping the library and earning a superiority claim are separate gates.

### Phase 4 — Ship 1.0 (M–L)

- **Release infrastructure.** Publishing, a MiMa baseline, and deployed docs.
- **Freeze the Phase 2 support wire and decision contracts.** No first landing of support honesty
  remains for this phase; publish the migration documentation with the release.
- **Hardening:**
  - verify the platform-labelled goldens landed in Phase 2;
  - one refusal supertype;
  - readable I/O errors;
  - a facade around `recall-map`.
- **The stability table.**
  - Stable: the mapper, the typed source view, `HsmmResult`, the core evidence types.
  - `StoryModel` and the compiler: stable under the recorded full-film route, otherwise
    experimental.
- **Release gate.** Under ruling E, the film compiler and D1B end-to-end proofs must be landed
  and green. Only an explicit recorded P1 decision can substitute the narrower mapper route.

## 4. The film chain under each choice

| Bead | Only if P1 is explicitly adopted | Default: E as recorded |
|---|---|---|
| D1A-types | S0–S2 in Phase 2. S3 only if D1B needs it. S4a–S4c pause | all slices, Phase 2 |
| D1B | signature work in Phase 2; spine proofs with D1A-film | all of it before 1.0 |
| D1A-film | P1a: unscheduled. P1b: after a positive H5 (needs A narrowed) | Phase 2; on the 1.0 path regardless of H5 |
| V1, E0 | 1.x | 1.x |

## 5. Owner decisions

**To start:**
1. Approve this plan.
2. Approve the Phase 0 deletions.

**At or before the checkpoint:**
3. P1, only if a scope change is wanted: adopt P1a or P1b (and narrow A). Otherwise E stands;
   its already-approved engineering needs no new scope approval.
4. P2: per corpus, may recall prose go to hosted APIs? What is the spend budget?

**Later:**
5. People for second codings.
6. Media for Memento. FilmFestival is in progress.
7. CI and the push.

## 6. Tracker changes on approval

- File the phase beads, carrying the Phase 1a arms table and rules R1–R4.
- Preserve the recorded D1A-types/D1A-film/D1B scope and dependencies by default; apply §4's
  alternative column only after an explicit P1 decision.
- Put support honesty and its mutation evidence in Phase 2, ahead of confidence work.
- Record the final-freeze/test-opening gate, including corpus exposure and read-count ledgers.
- Move V1 and E0 to 1.x.

## 7. Non-claims

- **Checks reported by the revision 4 author (not all rerun in this revision):**
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
- Revision 5 rechecked the recorded ruling E, the Sherlock preregistration and the scorer's
  dropped-row behaviour. A two-unit probe scored 50% with one wrong prediction and 100% when
  that prediction was blank or invalid; this demonstrates the defect, not a repaired scorer.
- H1–H5 and the R-rule choices are judgement, declared before data.
- Titles-only and cross-corpus contrasts neither establish nor bound training contamination.
- A pinned model/runtime is replay provenance, not proof of bit-identical fresh inference.
- This plan specifies implementation and evidence gates; it does not claim those gates have run.
