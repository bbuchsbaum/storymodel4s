# Does machine perception of a film change where recall lands? A study plan

- **Date:** 2026-09-02
- **Mode:** single-developer (AGENTS.md SD1–SD8). The owner authorises; mutation witnesses and cold
  reviews stand in for reviewers.
- **Status:** proposed. Nothing here is executed until the owner approves the design and the
  predeclaration in §6 is frozen.
- **Governing documents:** movie plan §8, §9, §10.1–§10.4; ADR 0001 §D5 L1/L2/L6 and §D7; ADR 0003;
  `docs/design/external-floor-calibration-court.md`; `docs/design/sherlock-local-pair-admission-court.md`;
  `docs/design/recall-alignment-visualization-court.md`; `docs/design/vlm-captioning-admission-record.md`.
- **What exists to build on:** the landed acquisition and captioning courts (`main` at `d9aff873`),
  the `media` module, and the general recall-to-video pipeline in `embed-bench`.

## 0. The verdict this plan starts from

The machinery to answer the question now exists on both ends and has never been connected. The
source end can turn a film into typed, receipted, timed evidence. The recall end can align a
word-timestamped transcript against timed source segments and report masses and flows. Between them
sits one missing adapter and one unasked question.

The question is worth asking because the current alignment has a specific, measurable weakness, and
it is exactly the weakness visual evidence should address.

## 1. What is already true, measured

Measured 2026-09-02 over the 33 local recall reports produced by the neural semantic channel
(`tmp/neural/recall-map-*.tsv`), 5,086 recall units in total, human coder annotations as the source:

| Quantity | Median | Reading |
|---|---|---|
| Source mass per unit | 0.675 | Two thirds of each unit's mass is attributed to the film |
| MAP anchor mass per unit | 0.115 | The single best segment holds about a ninth of it |
| Localizability | 0.664 | |
| Kendall tau, anchored source time against recall order | 0.424 | The time map is substantially out of order |

One representative run also reports `unranked = 0.000`: every unit receives candidate nominations,
so the diffuseness is not a nomination failure. Human microsegment annotations run a median of 14
words ("Shot of London", "John opens up his laptop"); recall units run a median of 11.

**The pattern, stated precisely.** The candidate generator nominates the top eight segments per
hierarchy level with lexical overlap disabled (`RecallToVideo.scala:338`), so the posterior is spread
over roughly eight to sixteen admitted states, not over all thousand segments. The MAP anchor holding
0.115 therefore means the mass is close to uniform across the shortlist: the aligner is fairly
confident a unit is about the film, and close to indifferent among the handful of segments it
shortlisted. The resulting recall-to-time map is only weakly ordered.

Two distinct failures could produce that, and the study can tell them apart. Either the right segment
is not reaching the shortlist, which is a retrieval failure that `strict-recall@k` and `mrr` measure
directly once gold exists, or it reaches the shortlist and cannot be distinguished within it, which
the concentration and ordering measures see. The natural explanation for both is the same: one-line
human annotations are mutually confusable, with many segments described in nearly the same words.

**The hypothesis this licenses.** Machine descriptions of the picture carry discriminating detail the
coder's one-liners omit (who is present, where, what action, what is on screen), so they should
sharpen localisation. That is a real, falsifiable prediction, and a null is a complete answer.

## 2. The question, stated so it can fail

> Under a frozen source-construction recipe and a frozen aligner, does replacing or supplementing the
> human coder atlas with machine visual descriptions change where a recall unit is placed in the film,
> and in which direction, measured by predeclared quantities on recalls not used to choose anything?

Three arms are available. Movie plan §10.4 fixes five; the captioning admission record holds two of
them for want of admitted components:

| Arm | Source text | Status |
|---|---|---|
| A. Transcript-only | Human coder atlas (1,000 microsegments) | Available; the current baseline |
| B. Visual-only | Machine captions of the same 1,000 windows | Available after Phase 2 |
| C. Late fusion | Both, fused late per notes §120 | Available after Phase 2 |
| D. Audio-only | ASR | **Held**: no ASR component admitted |
| E. One-shot omni | A single omni model | **Held**: no such component admitted |

## 3. What may and may not be claimed, and why

These are not my constraints; they are the project's, and they bind the design.

- **No calibrated claim is reachable from Sherlock alone.** ADR 0001 L6 requires a calibration model
  fitted against adjudicated targets leave-story-out, and the notes require leave-story-out
  explicitly. There is one film. Every number this study produces is therefore **model-relative and
  diagnostic**, and must be reported as posterior mass, never as probability (L2, and the
  visualization court).
- **The coder atlas is not gold.** The Sherlock admission court fixes it as `CoderAtlasDiagnostic`,
  "a diagnostic proposal source, not direct episode truth". Movie plan §10.2: "an available movie
  does not supply its own gold." So arm A is a *comparator*, not a reference standard.
- **The first Sherlock result may claim only agreement with the coder atlas under a predeclared
  question** (Sherlock court §5). That shapes Phase 1 below and caps Phase 3's language.
- **Captions are legitimately `SourceFrozen`.** They are generated from the film alone, with no
  recall access, direct or transitive. This is a real methodological advantage over any
  recall-tuned channel, and the leakage court is satisfiable by construction. The frozen source
  artifact hash must not change when a held-out recall changes; that becomes a court.
- **The diagnostic ceiling is mechanically enforceable, not just prose.** `embed-bench`'s
  `BenchReport.label` returns `Diagnostic` unless six conditions hold, and `Calibrated` has a private
  constructor reachable only through it. Wiring this study through that label makes the ceiling a
  type, not a promise. The recall-to-video path currently references no `BenchReport`, `Origin`,
  `Gold`, `Metrics` or `MetricValue` at all; connecting it is Phase 2 work.
- **Dense-provider runs need lexical controls.** The external-floor court requires hashed n-gram and
  TF-IDF channels beside every dense channel, independently executed. The caption arm inherits this.

## 4. The confound that would ruin this, and its control

Longer, richer source text can absorb more recall mass without localising it any better. Source mass
would rise and nothing would be learned. Three defences, all predeclared:

1. **Primary outcomes measure discrimination, not absorption.** Sequential coherence and posterior
   concentration both fall if the model merely matches everything.
2. **A length-matched caption condition.** Captions are generated under a token budget whose
   distribution matches the coder annotations (median 14 words), and a second, deliberately verbose
   condition is run as a sensitivity arm. If the effect tracks length rather than content, it shows.
3. **A scrambled-caption control.** The same captions attached to the wrong segments. Any measure
   that does not collapse under scrambling is not measuring localisation.

## 5. The gold problem, and the one thing that needs a human

No one has ever labelled which source segment a given recall unit refers to. Without such labels the
study can report only internal quantities: the map moved, concentrated, ordered better or worse. That
is a methods result, not a memory finding.

**The apparatus to consume such labels is already built and completely unused here.** `embed-bench`
carries a full scoring stack that the recall-to-video path never touches:

- `gold.scala`: `Gold.validated(units, view)`, `GoldUnit(unit, targets, primary, facets, groundedness,
  externalSubtype, discourse, blend)`, and `enum Groundedness: Source, Association, Inference,
  Intrusion, Uninterpretable`.
- `metrics.scala`: roughly twenty-one named metrics including `strict-recall@k`, `mrr`,
  `ancestor-credit@k`, `level-exact`, and the route family (`route:support-midpoint-direction`,
  `route:transition-displacement-closeness`, `route:revisit-pair-recall`). `MetricValue` is never a
  bare number: it carries an `Estimate`, coverage, story count, a seeded percentile bootstrap
  interval, and a receipt.
- `origin.scala`: `enum Partition: Development, Calibration, UntouchedTest` with `FrozenSet.verify`
  failing closed. `leakage.scala`: the memorisation contrast.

Two consequences. First, the adjudication task should be specified to produce `GoldUnit` values
directly, which means the adjudicator names the target node *and* its groundedness on the existing
five-way scale, not merely a segment. Second, once gold exists, the study's strongest primary
outcomes are the ones already implemented and tested, with intervals, rather than anything I would
invent.

One property of `Metrics.aggregate` deserves emphasis, because it defends the whole comparison: any
missing eligible observation makes the aggregate missing, so an arm cannot improve its score by
abstaining on the cases it finds hard. A caption channel that refuses the difficult segments cannot
win that way.

**The proposal.**

- **Size:** 300 recall units, stratified across participants and across the film's runtime, drawn by
  a seeded rule and frozen before any arm is scored.
- **Task:** for each unit the adjudicator names the source segment or scene it refers to and its
  groundedness (source, association, inference, intrusion, uninterpretable), working from the film
  and the annotation text, blind to every arm's output and to arm identity.
- **Cost:** roughly 30 seconds a unit, about 2.5 hours of expert time, splittable across sittings.
- **Payoff:** it turns on `strict-recall@k`, `mrr`, `level-exact`, `ancestor-credit@k` and the route
  metrics, each with a bootstrap interval and coverage, at no implementation cost. It is the only
  route to the word "correct" appearing in the result. It supplies the adjudicated targets ADR 0001
  L6 names, though one film still bars the word "calibrated".

This is the study's single largest dependency on you, and it should be decided before Phase 3.

## 6. Predeclaration, to be frozen before any arm is scored

This section **refines an already-frozen question and does not replace it.** The captioning
admission record §5 predeclared, before any Sherlock frame was shown: "does the visual-only arm
change recall alignment on held-out recalls relative to transcript-only, measured by the existing
HSMM courts; 'no' is an admissible answer", with recipes frozen before scoring and no model swapped
mid-comparison. That question stands. What follows names the specific quantities "the existing HSMM
courts" will be read through, and those names must themselves be frozen before scoring.

Written into a content-addressed record and hashed before scoring, per the external-floor court's
freeze rule.

**Primary outcomes with gold** (if §5 proceeds), taken from the existing metric set so that the
statistic, its interval and its coverage rule are already implemented and tested:

1. `mrr` and `strict-recall@k` for k in 1, 3, 5, 10 against the adjudicated targets.
2. `level-exact` and `ancestor-credit@k`, which score getting the right part of the hierarchy when
   the exact segment is missed.
3. The route family, above all `route:transition-displacement-closeness`, which is the ordering
   question stated in the project's own terms.

**Primary outcomes without gold**, reportable whether or not §5 proceeds, and the only outcomes if it
does not:

4. **Sequential coherence.** Kendall tau between MAP-anchored source time and recall order, per
   participant. Baseline median 0.424.
5. **Localisation concentration.** MAP anchor mass per unit, participant-median. Baseline 0.115.

Outcomes 4 and 5 are consistency measures, not accuracy, and will be labelled as such wherever they
appear.

**Secondary, reported separately and never aggregated into a score** (movie plan §10.4 requires
target retrieval, source interval, hierarchy level, role and polarity, sensory detail, ordering,
compression, omission and external mass to stay separate): source mass; the external-state
decomposition by state; localizability; coverage and refusal counts.

**Split.** Participants are partitioned using the existing `Partition` type (`Development`,
`Calibration`, `UntouchedTest`) with `FrozenSet.verify` failing closed, by a seeded rule fixed in
advance. Development freezes every recipe. The untouched set is unsealed once and chooses nothing;
if any test outcome changes any frozen choice, the test set is contaminated and must be replaced.
The split unit is the participant; leave-one-participant-out gives stability. The local recall set
must first be reconciled against `docs/data/sherlock/recall-lineage.json`, which enumerates 17 public
sources while 33 local exports are present; the discrepancy is resolved and recorded before the split
is drawn. Separately, `RecallWordsCsv` hard-codes the Princeton onset column, so the OpenNeuro clock
is currently unreachable; if the split or any timing analysis needs it, that is a small fix to make
before freezing, not after.

**Decision rule, with an explicit honest null.** For each primary outcome: the visual arm is said to
differ from transcript-only when the paired per-participant difference on the untouched set excludes
zero at the predeclared interval, and the direction is reported as found. Superiority is claimed only
if it survives the length-matched and scrambled controls.

Following the external-floor court's structure, the result is **unestablished** rather than null
whenever any of these fails: both directions of the effect remain observable in the corpus; the
difference survives every leave-one-participant-out refit; no identity, support, coverage or roster
check failed; and the controls behaved as required. "Unestablished" and "no difference" are different
findings and are reported as different findings. **A null is a complete answer and will be published
as one.**

**Stopping rule.** If Phase 1 fails its gate, the study stops there and the negative is recorded;
Phases 2 and 3 are not attempted.

## 7. Phases

### Phase 1 — Do the captions describe this film at all? (source-side, no recall touched)

Caption all 1,000 microsegment windows of the admitted Sherlock edition with the realized 4B model,
then ask a question that needs no recall and no gold beyond the coder atlas:

> Given the caption of segment *k*, does the coder's description of segment *k* rank above chance
> among all 1,000 descriptions by embedding similarity, and how far above?

This is exactly the "agreement with the coder atlas under a predeclared question" the Sherlock court
licenses. It is cheap, it needs nothing new from you, and it is a genuine kill gate: if a caption
cannot retrieve its own segment's description, the recall arm cannot work and the study stops.

- **Exit gate (predeclared):** median reciprocal rank of the true description materially above the
  chance value of about 0.001, with the hashed n-gram and TF-IDF controls run beside it. A specific
  threshold is fixed in the frozen record before running.
- **Cost:** about 1.5 to 4 hours of local compute for 1,000 windows on the 4B model; no owner time.
- **Also produced:** a caption identity, receipts, and a `SourceFrozen` artifact hash.

### Phase 2 — The adapter: a caption becomes a timed segment without becoming trusted text

Build the receipt-carrying seam from `CaptionProposal` to the pipeline's `TimedSegment`, so a
machine description can drive alignment while staying visibly a proposal. This is the piece the
recall-to-video pipeline's own header anticipates: "a machine perception channel producing the same
`TimedSegment` stream (with its provenance stated) slots in here unchanged."

- Provenance travels: the segment carries its caption proposal identity, model pin, recipe and
  receipt, so no consumer can mistake it for annotation.
- **Second seam, equally important:** wire the recall-to-video path into the bench harness it has
  never touched, so results arrive as `MetricValue`s with coverage, intervals and receipts, carrying
  a `BenchReport` label. Today the path emits a bare TSV and references no `Origin`, `Gold`,
  `Metrics` or `BenchReport`; that is why anything built on it is diagnostic by the repo's own rule.
  Wiring it makes the ceiling mechanical rather than editorial.
- Courts: a scrambled-caption fixture must degrade the alignment; a proposal without a receipt must
  refuse to enter; the frozen source hash must not change when a held-out recall changes; a report
  without a verified frozen set must label itself `Diagnostic`.
- **Exit gate:** the existing WOG and Sherlock suites unchanged, new courts green, mutation witnesses
  for each new guard, one cold review.

### Phase 3 — The three arms on held-out recalls

Run arms A, B and C under the frozen recipes, on development first, then once on the untouched set,
through the existing bench harness rather than a new one: `Metrics.observe` per case,
`Metrics.aggregate` for the story-macro mean and seeded bootstrap, `LeakageControl.assess` beside the
dense channel, and `BenchReport.label` on the result. Report every predeclared quantity with support
and coverage per ADR 0003, and the honest null or the unestablished verdict where a condition is
unmet.

- **Exit gate:** a written result that names its estimand, population, support, missingness handling
  and uncertainty, and that states plainly what it does not establish.

### Phase 4 — What would make this calibrated

Named now so it is not mistaken for available: a second film with its own recalls, giving
leave-story-out, plus the adjudicated targets from §5, plus a named calibration model with ECE and
Brier reported per claim family. Candidates already surveyed in the ledger include the FilmFestival
deposit, held for missing film files, and MF2, excluded by licence. This phase is a proposal for
later, not a commitment.

## 8. Costs and risks

| Item | Estimate |
|---|---|
| Phase 1 captioning, 1,000 windows, 4B model | 1.5–4 h local compute |
| Phase 1 analysis and controls | Under a day |
| Phase 2 adapter, bench wiring and courts | 2–3 days |
| Phase 3 three arms across participants | Hours of compute per arm |
| Adjudication (§5), if approved | ~2.5 h of your time |
| 8B arm as a further comparison | 17.5 GB disk plus roughly 2–3× the caption compute |

**Principal risks.** The captions may be too generic on real footage to discriminate, which Phase 1
detects cheaply. The verbosity confound, controlled in §4. Contamination of the untouched set, which
the freeze rule prevents and a court can enforce. And the standing governance flag: the movie plan
places the P1 real-transcript vertical before movie implementation, and that gate remains formally
open; this plan proceeds on your instruction and records that it does.

## 9. What I need from you

1. **Approve or amend the question and the primary outcomes** in §2 and §6. They are the part that
   must not move later.
2. **Decide the adjudication track** in §5. Without it the study reports that the map changed;
   with it, whether it changed for the better.
3. **Confirm the claim ceiling.** One film means diagnostic, never calibrated. If that is too weak to
   be worth the work, Phase 4's second film becomes the first priority instead.

On approval I will freeze the predeclaration record, then execute Phase 1 and report its gate before
touching anything else.
