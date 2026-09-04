# Own the Metric — a plan for supremacy in narrative recall modeling

*Approved by the owner 2026-09-03. Written against `main` at `8307316d`; the survey, measurements, and
owner decisions it records are all from 2026-09-03. Each workstream executes task-by-task with
verification at each step.*

## Context

`vision.md` claims one object seen through three kinds of evidence — text, film, autobiographical
interview — served by *the same* typed narrative model, alignment, evidence discipline, and
instrument. The repository does not yet do that, and a first draft of this plan proposed unifying the
two halves immediately. An adversarial review killed that ordering. What follows is the revised plan;
the diagnosis survives, the sequencing does not.

**Baseline: `main` at `8307316d`, 2026-09-03.** This repository is moving under the plan — write against
that commit and re-verify before starting. Already landed or in flight since the survey began:
derivation record, credence (ADR 0010), measured feature tracks (ADR 0011) are **on main**; the
`solo/root-segment` worktree carries **pronoun coreference wired into the compiler (ADR 0012, "entity
identity by referring form")**, first/second-person holder resolution, schema heading to 0.6.0, and WIP
on the root segment carrying its own claim. **ADR 0012 is taken; new work claims 0013.**

### The diagnosis (holds, and is worse than stated)

Two systems share a vocabulary and a build but not a data path:

- **System A — the semantic spine.** Text → `PropositionChart` → `NarrativeCompiler` →
  `NarrativeGraph` → `view`. Runs on one hand-curated 50-sentence fixture, does not validate
  (70 gaps), has never touched a film.
- **System B — the recall benchmark.** Sherlock annotation → `TimedSegment(ordinal, text)` →
  `RecallSegmenter` → `GraphHsmm` → **63.8% scene-exact / 83.0% within-one-scene**, floor 9.7%.

System B never builds a chart. `RecallSegmenter` never assigns `RecallUnit.evidence`, and
`codec/recall.scala:133-155` emits and decodes eight keys, silently dropping `evidence`, so units are
chartless even across a round trip — and `RecallUnit` is not in `CodecSuite`'s `lawsFor` list, so no
round-trip law covers the hole. `d_chart` and `d_wl` are `Missing` in every run that has produced a
number. And System B matches recall text against *a coder's prose description*, so 63.8% means "we
match one retelling to another retelling," not `vision.md`'s "where each thing they said lands **in the
film**."

**The source side is worse, and this is an active correctness defect.** `TimedSourceView` builds every
`NodeSummary` — leaves and groups alike — with `predicate = None`, `participants = Vector.empty`,
`context = ContextTag.NarratedWorld` hardcoded, and `polarity`/`modality` `Unknown`
(`embed-bench/.../RecallToVideo.scala:144-158, 166-178`). Only `locations` and `lemmas` carry content.
Verified consequences:

- `ContradictionDetector.detect` gates on
  `predicateMatch = sketch.predicate.exists(p => node.predicate.exists(_ == p))` (`align/.../cost.scala:600`),
  which is **always false** when `node.predicate` is `None`; role reversal needs `node.agent`/`node.patient`,
  which are empty. The detector returns empty on every pair.
- Therefore `ModeGate.assess` admits Faithful everywhere, and `engages` (`:651-652`) likewise requires a
  predicate match. **ADR 0001's "non-bypassable hard gate," the project's signature guarantee, has never
  refused anything on Sherlock**, and the `Distortion` cost term is identically zero.
- `RecallSignature` *is* computed on every run (`RecallToVideo.scala:461`). `FidelityFacets.assess`
  scores `(Some(_), None) => Wrong` for actor, action, and object (`align/.../facets.scala:99,107`), so
  every unit expressing one is scored **Wrong** — not `Unspecified` — because the source declares
  nothing; while `Facet.Context` returns `Correct` unconditionally for asserted units since
  `node.context` is hardcoded `NarratedWorld` (`:130-132`). `fidelityByFacet` and `distortedMass` on
  Sherlock are artifacts of an empty source view, not measurements of recall.

Mitigating: the study log reports no fidelity or facet numbers, and the printed outputs are coverage,
specificity, and external mass — so no published claim is contaminated yet. But the signature flows into
every arm report, and a benchmark cannot ship facet columns computed this way. **Fix this before any
scoreboard column is defined.** It also strengthens the reordering below: a thesis test on the current
harness would compare an inert apparatus against an inert apparatus.

### The three findings that reordered the plan

**1. The thesis test is underpowered on Sherlock, and would be uninterpretable.** The study log's own
decomposition (`docs/plans/2026-09-02-recall-to-video-study-log.md:599,671,707`):

| step | scene-exact | delta |
|---|---|---|
| baseline (per-unit argmax, semantic + sketch) | 33.4% | — |
| + lexical blend 0.8 | 36.0% | **+2.48, CI [+0.51, +4.37]** |
| + `MonotoneScene` decode | 55.8% | **+17.67, CI [+13.12, +21.24]** |
| + monotone fill | 63.8% | **+6.83** |

**24.5 of 30.4 points — 81% — come from the sequence decode, not the representation.** Adding
`d_chart` is a marginal emission term entering a +2.5-point regime measured on 15 participants from
one film. A null there would falsify nothing, and pre-committing to report it as a falsification of
the thesis would be publishing an underpowered shrug. The thesis test needs more corpora *first*.

**2. Circularity is structural, and one artifact is doing three incompatible jobs.**
`Sherlock_Segments_1000_NN_2017.tsv` and its 50-scene sibling would simultaneously be the source of
the film model, the gold admitting every perception lane, and the substrate of the recall gold.
Nothing would be independent of it, and no detector would exist if it is idiosyncratic. Worse, the
first draft proposed resolving `ClaimFamily.SpeakerAttribution` with `requireAgreement ≥ 2` counting
"the `Name - Speaking` column" and "the in-prose `Name: "…"` pattern" as distinct providers — they are
the same coder, same row, same pass. That manufactures the appearance of corroboration and is cut.

**3. The escape is already on disk and unused.** `data/filmfestival/textdata/Cleaned Data/Description_Cleaned_Data/`
holds **8,730 free-text descriptions of 30-second windows over six films**, each with `onset`,
`offset`, `description_content` (rich — the sampled row runs ~60 words of visual and action
description) and an `importance` rating, written by ~271 crowd participants who are **not** the coders
who produced the recall gold. `Analysis Data/Description/*_consensus_mapped_to_neuro.csv` already maps
the consensus onto the fMRI clock. This is an independent film-side description source with exact
timing, and it makes the circularity objection an *empirical question* rather than a caveat.

### The strategic conclusion

Supremacy in a research field is not winning a metric on your own notebook. It is when other people
have to report *your* numbers. The highest-leverage move available — and the one that de-risks the
thesis either way — is to **release the evaluation apparatus as the public artifact**: a multi-corpus
recall-to-source alignment benchmark whose scored columns are exactly this project's distinctive
commitments. If the spine wins, it wins on your benchmark. If it doesn't, you still own the benchmark
and the null is a real result with power.

**Owner decisions (2026-09-03):** flagship = the unified spine; rigor = two formal tiers; owner
actions accepted = ASR over Sherlock audio, pyannote gated terms, FilmFestival video, Memento as a
first-class corpus. The spine remains the goal; it moves from W1 to W3 so that its test is worth
believing.

---

## W1 — The benchmark and the denominator

**Nothing here needs the spine, perception, video acquisition, or a GPU.** This is the shortest path
to a defensible public claim, and every later workstream is measured on what it builds.

0. **Repair the inert apparatus first — blocking.** Two defects, both verified above, both cheap:
   (a) `codec/recall.scala` must carry `RecallUnit.evidence`, with a schema bump, a
   `lawsFor[RecallUnit]` entry in `CodecSuite`, and an `Arbitrary` that actually generates
   `Some(evidence)` — otherwise W3 can wire charts end to end and lose them silently on the first round
   trip. (b) `TimedSourceView` must either populate `NodeSummary.predicate`/`participants`/`context`
   from the corpus where it can, or the signature path must be **disabled for corpora that cannot
   supply them**, so the mode gate's refusals and the facet verdicts are absent rather than fabricated.
   Typed missingness is the project's own rule and this is the place it is being violated. Mutation
   witness: restore the empty source view and show the named facet test fails.

1. **Two more corpora through the existing aligner.** FilmFestival and Memento each emit
   `TimedSegment`s directly (`embed-bench/.../RecallToVideo.scala:66-75`) and reuse the aligner
   untouched — ~40 Scala lines each, template at `SherlockRecallMapping.scala:154-221`. Memento is
   already staged (`data/memento/`) with an integration plan written 2026-09-03
   (`docs/plans/2026-09-03-memento-integration.md`) — follow it. The real work is Python: Sherlock is
   hardcoded as constants in `gold_scene.py`, `score.py`, `agreement.py`, `matched.py`,
   `within_scene.py`, `run-arm.sh`. **Cheapest first move:** three of five scorers are gold-free,
   including the judge of record `agreement.py`, which pairs recall units *across participants* by
   mutual-best IDF overlap of recall text alone, holds the pairing fixed across arms, and reports the
   median gap in film seconds. A new corpus runs through it with no gold and no legend.
   *Traps:* FilmFestival Run 2 segment numbers need **+106**; annotation times are run-relative
   `min.sec` decimals; Memento's `subs` sheet count (134) does not match the paper's enrolment
   (28/32/32/36) and must be reconciled before condition is used as a factor.
2. **The human ceiling — this is the denominator "supremacy" currently lacks.** 63.8% is
   uninterpretable without knowing what two coders achieve on the same task. FilmFestival ships
   **three independent coders** (JL/KM/RC; 628/489/516 segments, median 17–25 words) plus
   `recall_scenematched` with 3,226 recall utterances labelled with movie + scene + TR. Compute
   inter-coder reliability on both the film-side segmentation and the recall→scene mapping. Days of
   work; without it no phase of this plan has a unit.
3. **The independence ablation — the answer to circularity.** Build the film-side index from the
   8,730 independent crowd descriptions and score against the coders' recall gold. If accuracy holds,
   the objection is answered empirically on six films. If it collapses, that is the most important
   finding the project can produce, and it is worth more than the entire perception stack.
   **Name the branch point now**, because a collapse is not merely interesting — it invalidates
   downstream work. If crowd-built and coder-built indices diverge sharply: the six-film subset becomes
   the primary corpus rather than a check; every perception lane admitted against coder gold reverts to
   Diagnostic until re-courted against an independent source; and the benchmark ships with the
   divergence as a headline column rather than a footnote. Decide that before running it, not after.
4. **A baseline suite that a 2026 reviewer accepts.** Timing-only floor (9.7%, exists); BM25 lexical;
   embedding cosine; LLM event segmentation + cosine; and — the arm a reviewer will actually
   demand — a **long-context model given the source text and the recall and asked to label each unit
   directly**. Each baseline gets a stated tuning budget, because Arm A has had months of
   Sherlock-specific fitting (blend 0.8, prior 1.5, candidates 8, `bare`, `hard`) and an untuned
   comparator against a tuned incumbent is not a fair fight and will be called one.
5. **Risk–coverage as a scored column.** This is the axis where the design genuinely wins and the
   first draft never named it. Plot accuracy against coverage under the model's own abstention, report
   the area, and compare against a baseline thresholding its cosine score. The handoff already reports
   59.4% → 72.5% across confidence quartiles — that is a risk–coverage result waiting to be drawn.
   Abstention rate alone has no direction; a risk–coverage curve does.
6. **Release it.** Corpora adapters behind the `TimedSegment` contract, the de-hardcoded scorers, the
   pre-registration protocol, the baseline suite, the human ceiling, and the risk–coverage axis.
   This converts typed absence, calibrated abstention, distortion-vs-intrusion, and fabula ≠ syuzhet
   from things nobody measures into columns every competitor must fill in.

**Scope limit, stated up front:** a *recall-to-source alignment* benchmark structurally covers two of
`vision.md`'s three settings. The autobiographical interview has no source to align against — that is
its defining property. Either say the benchmark covers two of three and name the interview column as
future work, or define now what an interview column measures (internal/external detail profile,
episode-implied structure) without a source. Do not let the framing quietly drop the third setting.

**Exit:** three corpora scored on the gold-free judge; a published human ceiling; the independence
ablation answered with its branch point honoured; a released benchmark with baselines.

## W2 — Consolidate, re-ratify, declare the tiers

Runs beside W1; it is repo hygiene, not science. **Most of the original consolidation is already
done** — derivation, credence (ADR 0010) and features (ADR 0011) are merged to `main` at `8307316d`,
and pronoun coreference is wired into the compiler in `solo/root-segment` (ADR 0012). What remains:

1. **Land the in-flight branches and audit the schema bumps.** `solo/root-segment` (pronoun coref +
   root segment carrying its own claim, schema heading to 0.6.0) and `perception-first-court`
   (Recall Voyage, ADR 0002 §14), which is on an older `Credence` and needs a rebase.
   *Check:* `codec/ledger.scala` `Migration.steps` is empty, so each bump lands with **no migration**.
   Audit committed artifacts at the older schemas — recordings, study exports, fixtures — and either
   re-emit or declare them unreadable, once, rather than per bump.
2. **ADR 0013 — re-ratify the stage order (owner decision, SD5).** The ratified order is
   `P1 → B0 → C1 → D0/D1 → E0` with C1 "begins only after P1 and B0 close"
   (`docs/plans/2026-08-29-movie-narrative-architecture.md:798,827`). But **C1 is already built** —
   `core/source.scala` carries `SourceBundle`, stream/axis/coordinate/interval-set, `EvidenceSupport`,
   typed boundary layers, `TranscriptAtlas` — and **D0 is already built and tested** as
   `acquire/sherlock.scala`. Two later stages exist while two earlier gates are formally open. Record
   what is built, restate what genuinely remains, and re-ratify rather than drift. The live gate is
   **B0**, whose two real legs are the Sherlock rights/REB basis and the **anti-circularity court** —
   which W1 item 3 is designed to answer, so B0 and W1 should be written together.
   Note E0's own exit criterion (`:801`) is "one exact lawful film source slice and real recall
   produce a `NarrativeCompilation`… one predeclared research question is answered reproducibly
   regardless of sign" — that is W3+W4's deliverable, not a gate in front of them. **W3 does not close
   P1**: P1 wants a real *transcript*, and coder prose is a description. Say which gate each
   workstream targets, and do not let an engineering exit be mistaken for a governance closure.
   Bankable from `docs/design/sherlock-local-pair-admission-court.md:146,160,190`: there is no
   Sherlock-specific licence, consent, REB, or participant-prose hold, and non-video artifact
   eligibility is "Passed as policy" — the annotation TSV and recall CSVs are usable; only the episode
   video stays external. B0's open legs are the source-binding, coordinate, semantic, and comparison
   courts (`:60,78`), which is where W1's independence ablation lands.
3. **ADR 0013 also declares the two evidence tiers**, in existing vocabulary rather than new:
   **Diagnostic** = `FamilyPolicy.Development` (`acquire/.../resolve.scala:133-235`) — Draft
   authority, no mutation witness required, *may never select an arm and may never become gold*;
   **Scientific** = `Ordinary`/`Conservative`. The within-scene machine lane already works this way.
   State the promotion rule: a Diagnostic lane promotes by clearing a named court against
   *independent* gold.

## W3 — The film becomes a model, pilot-gated

Run Sherlock's annotation rows through the same Stage 1b pipeline the text lane uses —
`mission.md:93-96`, "film runs the same chart stage twice."

**Design uses existing C1 vocabulary; no new `MappingFamily`.** The film document is a `StorySource`
whose text is the rendered timed-language track. Each row becomes a `NarrativeProposalUnit`
(`core/.../source.scala:1572`) whose `EvidenceSupport` carries both `EvidenceAnchor.Text` (its span in
that text) and `EvidenceAnchor.MediaTime` (its playback interval), with `surface` into the
`SurfaceAtlas`. `EvidenceSupport.selectedPlaybackAxis` already enforces "no mixed hull." This is what
C1 was built for, and it is why a stream-derived text swaps in cleanly later — only the text changes.

**Decide the document shape before anything else.** `NarrativeCompiler.compile` takes one
`NarrativeCompilerInput` and mints `rootContextId(story)` and `rootSegmentId(story, situations)` per
`StoryId` (`document/.../compiler.scala:2682,2697`), with a single story-summary candidate (`:337`).
So 1,000 rows is either 1,000 separate `StoryModel`s — leaving the cast ontology, scene hierarchy, and
cross-row coreference with no home — or one model over a ~1,000-fragment concatenated atlas, which
yields exactly one root segment and one summary for the whole episode. Under the second reading the
"scene segments" goal is **a new compiler feature, not a wiring job**. Note the `solo/root-segment`
branch is already working this seam ("root segment carries its own claim; summary is a typed
absence"); coordinate with it rather than duplicating it. Decide and cost this explicitly, or W3
discovers it late.

**Pilot gate first (one day).** Run **50 rows**. Measure per-row yield, parse-failure rate,
speech-context rate, and — decisively — whether `d_chart` over those 50 rows separates gold scenes
better than BM25 does on the same rows. The only precedent is sobering: the WOG run turned 50
sentences into 50 calls with **7 failing to parse (14%)** and 155 compilation attempts yielding
**54 model claims (35%)**, on hand-curated literary prose. Nobody knows what terse annotation prose
("Sherlock enters the flat.") yields. The pilot either kills or justifies W3–W4 before the full spend.

Then, if the pilot passes:

1. Source bundle and axes — reuse `SherlockAnnotations.partBundle` (`acquire/.../sherlock.scala:222`).
2. Prose → charts via `ClaudeParseDriver` → `ChartProposalProvider` → `NarrativeCompiler`, as
   `pipeline/.../StoryBuild.scala:239-266`. One live `Record` pass, then free deterministic replay.
   *Cost:* one call per **sentence**, and `Scene Details` holds ~1,716 sentences across 1,000 rows —
   so ~1,700 calls, not 1,000. At the measured WOG rate (72 in + ~300 out tokens plus ~1,275 cached,
   ~4.1 s serial) that is a few dollars, ~2 h serial, ~7 MB of committed recordings.
3. **Cast as declared entities** — the 46-name `Name - All` set as a closed ontology with an alias
   table, layered on the referring-form coreference that ADR 0012 now supplies. The closed set is the
   part that is new: it bounds what may be minted and makes cast closure a checkable court, which
   open-domain coreference cannot give.
4. **Dialogue as speech contexts** — `QuotationScan` (`core/.../quotation.scala:122`) yields the
   **753 quoted spans in 698 rows** (verified by paired-quote regex; `Name - Speaking` is non-empty in
   exactly 698 rows, 683 overlap, 15 quoted-with-no-speaker, 15 speaker-with-no-quote). Each becomes a
   `ContextFrame` with `ContextKind.Speech(holder)`, holder `Named(entity)` where the column names one
   and `Unattributed(NoCandidate)` on the 15 without. **No new types.**
   *`requireAgreement` is set to 1 here, not 2*, and the reason is recorded: there is exactly one
   coder behind this annotation, and pretending otherwise would manufacture corroboration. A second
   provider becomes available only in W5, when ASR and closed-set speaker ID are genuinely independent
   of the coder — and that is when the family may be raised to Conservative.
5. **Location and co-presence** — `Location` (40 distinct) → `EntityType.Location` plus
   `HierarchyKind.LocationThread`, declared at `story/.../relations.scala:167` and constructed nowhere
   today. `Name - All` → co-presence edges.
6. **Scene structure** — the 50 markers → `SegmentKind.Scene`. The compiler emits exactly one
   `SegmentKind.Story` segment today (`document/.../compiler.scala:1904-1917`).
7. **Measured tracks** — `Camera Angle`, `Music Presence`, arousal/valence as `FeatureTrack`s.
   `FeatureTarget.Turn(TurnId)` exists; add `FeatureTarget.Shot(ShotId)` (`ShotId` exists at
   `core/.../source.scala:23-24`) with its `rank`/`key` entry and `TargetFamily.of` case.

**Courts (Scientific).** The 15/15 disagreement rows must yield `Unattributed` and off-screen speech —
mutation witness: remove the abstention and those rows mint a wrong speaker. Cast closure: no entity
outside the 46. Two-run clock: segment 13 has zero duration, rows 481–482 are scan-break rows with no
TR, seconds reset at row 483; model the stitch and fail closed.

**Exit is a yield-and-abstention profile, not a volume count.** The first draft's "≥46 entities, ≥700
speech contexts" was invented, and a volume gate creates pressure not to abstain — in direct tension
with the claim that abstention is an advantage. Report yield per row, parse failures, abstention rate
by claim family, and the coverage of each declared layer, against the pilot's prediction.

## W4 — The thesis test, with power

**A new pre-registration, written and committed before any arm is scored.** The existing one
pre-specified *exactly one* comparison and binds later work: no arm selected by gold score, later
configs chosen on development participants only, every comparison counted. The ledger already stands
at nine comparisons against a 15-participant gold, and the untouched five have been read four times.
Running five arms against it silently would spend the only asset in the study that can say "correct."
The new pre-registration names the primary comparison, the partition, the multiplicity budget, and
the analysis.

**Factorial, not a ladder.** The first draft's additive arms confounded representation with decode and
handed the incumbent a 24-point gift. Instead:

{cosine, BM25, semantic+lexical, +`d_chart`, chart-only} × {per-unit argmax, monotone HSMM}

Report the decode's contribution and the representation's contribution **separately**. Run it on
**three corpora** — FilmFestival (15 subjects × 10 films) and Memento (133 subjects) make the +2.5-point
regime detectable in a way 15 Sherlock participants never will.

**Compare against the right literature.** LLM-segmentation + cosine is one baseline. The sharper one
is the atomic-claim/factuality line — FactScore, AlignScore, SummaC, MiniCheck — which decomposes text
into atomic claims and verifies each against a source. That is `d_chart` in mature, heavily
benchmarked form, and a reviewer who knows it will ask what a typed `PropositionChart` buys over
atomic-claim decomposition plus an NLI checker at a fraction of the cost. Answer it in the design, not
in rebuttal.

**Plumbing this actually requires** (understated in the first draft): a `codec/recall.scala` change to
carry `evidence`, a schema bump, a new codec law and generator, a `StructuralDistance` injection
(`RecallToVideo.scala:458` leaves it `missing` and `BenchChannels.grakern` is never called), and a
`NodeSummary.evidence` population path in two constructors.

**Also here:** run the human lane of the within-scene adjudication. Packet, key, and 200 diagnostic
machine-lane labels exist; under M1 Law I1 the machine lane may not select an arm or be quoted until
it clears median range-Jaccard ≥ 0.5 against a human lane that has never been run.

**Memento's within-dataset control.** E1 (nonlinear cut) vs E4 (restitched linear cut, same film, same
runtime, same free-recall instruction) isolates presentation-order from story-order — the confound
Sherlock cannot separate. Fit the monotone prior on E4 and it should behave like Sherlock; fit on E1
and it should not. If it behaves the same on both, the prior tracks something other than narrative
order. Memento also carries explicit `FalseMemory` annotation — **the only gold for confabulation
anywhere in this project.** `RecallSignature.distortedMass` claims to measure distortion and has never
been validated against anything.

## W5 — Perception, where it has a consumer

The first draft put four Python-worker integrations on Sherlock, where every court is "agreement with
the annotation" and the best possible outcome is reproducing a TSV that already exists. Cut to what
earns its place.

**Now — shot boundaries only.** `BoundarySearch`/`BoundaryLocalizationProposal`
(`media/.../boundary.scala:435`) is built, tested, and has zero consumers outside `media/`;
`BoundaryLocalizationProposal` is `private[media]`, so an adapter is required either way. Cheapest
lane, feeds W6's boundary evidence, no gated weights, no acquisition.

**After FilmFestival's video lands — ASR, closed-set speaker ID, VLM captions.** This is where they
have a real consumer: FilmFestival's annotation is thin (3 coders, ~490–630 segments, median 17–25
words, **no character, speaker, or location columns**), and **five of its ten films have no speech at
all** — the only place in these corpora where visual evidence must carry the answer. The study log's
own conclusion says scene captions were a measured null on Sherlock *because the annotation is
unusually complete*, and that "on a film with sparse or absent human annotation the argument reverses
completely."

**Blocker to fix before any captioning at scale.** `Ffmpeg.extract` (`media/.../frames.scala:49`)
decodes the **entire** picture stream at full resolution to raw BGR24 with no selection and no
scaling — Sherlock part 1 alone is ~35,650 frames × 691,200 B ≈ **24.6 GB**. Add declared selection
and scaling to `outputOptions` (the recipe already carries `minPixels`/`maxPixels` = 16384/65536),
keep the argument vector in the receipt, and re-record every affected envelope together.

**Two rules that were violated in the first draft.** (a) A lane gated on annotation agreement cannot
then be used to audit the annotation — the disagreeing rows are exactly what was selected against.
Pick one role per lane and say which. (b) Do not report cast F1 or speaker accuracy against the same
annotation the model was built from; that is self-consistency reported as measurement. Perception
metrics belong on corpora where the perception source and the gold are independent.

*E0 note:* the governing sentence is `movie-narrative-architecture.md:821` — "**runtime decoder records
remain draft through D0 and D1**, and only separately authorized E0 adapter-owned issued
invocation/result integration may mint runtime packet/extractor **provenance**" — repeated in D0's row.
Running a decoder at `Draft` authority is explicitly permitted pre-E0; E0 gates *observation
authority*, not decoding. The caption lane already threads a decoded `FrameSet` at `Draft`; audio is
the same shape. Cite `:821` and D0, not the caption precedent alone — the precedent is corroboration,
the line is the rule.

## W6 — Structure, only what the arms consume

- **Event coreference.** `situationPartition` is literally `CorefPartition.empty[SituationK]`
  (`document/.../compiler.scala:1420`) — always. For film this is central: an event is shown, referred
  to in dialogue, and recalled, and those must be one node. Feeds `d_chart`.
- **Scene segmentation from multi-layer evidence** → `BoundaryBelief` (`story/.../hierarchy.scala:9-15`,
  never populated outside fixtures). **With the ablation `mission.md` commitment 11 requires**: shot
  cuts, location change, cast turnover, and dialogue gap cannot be scored against the 50 human markers
  when location and cast come from the same annotation family — the plan must supply an ablation,
  cross-fitting, or an independent boundary set. FilmFestival's three coders supply the last of these.
- **Leave-story-out harness** (`mission.md` commitment 12: "establish… before any learned component
  ships"). Never previously proposed; with three corpora it finally becomes possible.
- **Deferred as unmeasurable for now:** `GoalEdge`, `StateChangeEdge`, `ReferenceEdge`, `EntityEdge`,
  `HierarchyKind.GoalArc`/`Theme`. Declared, never constructed, and feeding no arm. They return when a
  metric consumes them.

## W7 — Long-lead work

- **The instrument.** storyatlas4s renders the film model: cast lanes, speech-context bands, scene
  bands, the Recall Voyage over film time. Per the `view` contract each layer needs a `VisualChannel` +
  `ChannelMeaning`, a `VisualPrimitive` case, an `AtlasCompiler.build` block, a `checkEvidence` case, a
  `GapTarget`/`AbsencePlacement` case, an `AtlasSpec`/`configurationFields` entry, and an
  `AtlasTextualTwin.render` case. Its deliverable is scoped to one measurable thing — the blind-critic
  margins already run — so it can satisfy the "ends with a number" rule.
- **Autobiographical interview.** `interview/` (2,956 lines) has only ever seen one synthetic fixture.
  Real data closes `vision.md`'s third leg, but `docs/design/story-text-admission-checklist.md` §3
  requires an REB/IRB basis for participant recall text **checked by someone other than the
  proposer** — which single-developer mode structurally cannot supply. Treat this as a multi-month
  external dependency with an owner action, not a slice.

---

## The scoreboard

- **Human ceiling first** — FilmFestival three-coder reliability on film-side segmentation and on
  recall→scene. Without it, no number here has a unit.
- **Risk–coverage curve with area**, against a baseline thresholding its own score. The axis where
  this design wins.
- **The independence ablation** — coder-built vs. crowd-built film-side index, same recall gold.
- **Factorial decode × representation**, reported separately, on three corpora, with bootstrap CIs and
  a stated tuning budget per arm.
- **Fidelity by facet** using the taxonomy that already exists — `FidelityFacets`
  (`align/.../facets.scala:17-18`): Actor, Action, Object, Location, Outcome, Cause, Context,
  RoleReversal, Polarity, Modality, each Correct/Wrong/Unspecified, mass-weighted over the posterior.
- **Distortion validated against Memento's `FalseMemory` gold** — nobody else can do this.
- **Typed-missingness and abstention counts**, reported as numbers.
- **Cut from the first draft:** the NEST comparison. Citing published baselines (<8% trigger
  detection, <11% argument extraction across 1,005 films) and then reporting our own numbers "where
  the schema maps" is a different corpus, ontology, and matching rule — incomparable, and it reads as
  cherry-picking a weak comparator. Either run on NEST's data with NEST's scorer as a separate
  exercise, or leave it out.

---

## Risks

- **W4 may find charts add nothing.** With three corpora that is a real, powered result. On Sherlock
  alone it would have been uninterpretable, which is why it moved.
- **W3's pilot may kill W3.** That is the pilot's purpose and it costs one day.
- **The independence ablation may collapse.** That is the most valuable outcome available.
- **Schema bump has no migration.** Audit before merging W2.
- **P1/B0 are governance gates.** Closing them is a documented act with the rights leg recorded.
- **Ethics for interview data is months, not weeks**, and needs a second party the mode cannot supply.
- **Scope creep is the named single-developer failure mode** (`AGENTS.md` SD7; the salvage found 170
  candidates proposed and 12 landed). Seven workstreams is already near the limit — W1 is the only one
  on the critical path to a public claim, and if a week passes with no landed non-`.mote` commit, the
  obstacle is this plan, not the queue.
- **The repository is moving under this plan.** During the few hours it took to write, `main` advanced
  six commits, three worktrees merged, pronoun coreference landed as ADR 0012, and a `solo/root-segment`
  branch appeared working the exact compiler seam W3 depends on. Re-verify every "does not exist yet"
  claim against `main` before starting a workstream; several may already be done. Work in linked
  worktrees, copy `zz-worktree-local.sbt` in, run gates on `git archive` exports without it.
- **Disk.** Gate exports ~2.8 GB each; the volume has hit 100% before; 158 GB free. Check `df`; delete
  only completed exports with a scoped Python `rmtree`.

---

## Verification

- `sbt checkAll` green on the merge result (SD2).
- Every new guard has a named mutation witness (SD1); commit messages carry the mutant ledger, as the
  four in-flight worktrees do (7/7, 8/8, 9/9 killed by name).
- New codecs: `lawsFor[NewType]` in `codec/.../CodecSuite.scala` plus a `CodecGens` generator; round
  trip, canonical fixed point, sorted keys, unknown-`schemaVersion` refusal.
- New addressable refs: `AddressableLaws.addressable[NewRef]` with a hand-built foil proving the law is
  not vacuous.
- Provider and worker calls replay deterministically under `DriverMode.Replay` and recorded envelopes;
  live courts `assume`-skip when tools or weights are absent.
- **A cold-read pass per workstream (SD6)** — a fresh-context agent reading for *shape*, not
  correctness. The first draft of this plan passed every mechanical check and had five shape defects;
  mutation catches wrong code, reading catches wrong design. This step was missing and is now required.
- **Every workstream ends with a number on real data**, in the scoreboard, with its gold, its
  partition, its tuning budget, and its bootstrap CI. A workstream that ends with only a passing test
  suite is not finished.
