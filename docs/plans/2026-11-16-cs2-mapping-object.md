# Case study 2: the probabilistic recall-to-movie mapping

Status: design, revision 2, 2026-11-16, after linguistics and machine-learning critique of
revision 1 (`addc1b4c6`). No model call has been made. Mote bead
`bd-01M7JRB11CHP7EPC35QQ12DT41`. Scope (owner): the mapping only, with no fMRI and no
reinstatement. Generality (owner): Sherlock and FilmFestival from the start, with nothing
corpus-specific in shared code.

## What the object is for

An analysis-ready account of where in the film material each moment of recall points, with its
uncertainty, at the recall clock's native grain. Analyses of order, coverage, accuracy and so on
are consumers; none is built in. The object keeps the account and declares every projection, as
the [measurement contract][contract] requires.

[contract]: 2026-11-16-detail-mapping-plan.md#measurement-and-projection-contract

## Addressing film material

Revision 1 used (work, part, seconds). Critique showed two missing levels, so the address is now
**(work, occurrence, part, seconds)**:

- **Work** is the identity of a film. FilmFestival has eleven: its "Let's All Go to the Lobby"
  cartoon is one work shown twice.
- **Occurrence** is one presentation slot of a work. The cartoon has two.
- **Part** is a contiguous segment on one playback axis, such as a scan run. A part may hold
  several occurrences, and an occurrence may span parts (Sherlock's two runs).
- **Seconds** are on the part's own axis. No adapter invents a global timeline.

Two destinations sit beside the address:

- **In the work, outside the stimulus.** Two FilmFestival films are excerpts, so a recall of the
  full feature points at the work but not at the stimulus. This is not "external".
- **External**, as already defined.

Each corpus adapter declares its works, occurrences, parts and source units with their playback
support. Stimulus labels follow what participants saw: the cover names and the title-card
seconds are stimulus.

## The account, at native grain

One row per reference, recorded as produced and converted by the landed library path
(`ReferenceConversion`, `ReferenceMeasures`). Revision 2 adds four address classes and two
relations.

**Address classes of a reference:**

- **Moment:** a single, joint or broad reference over source units, as before.
- **Work-level:** the speech identifies a film without a moment ("the one with the shoe"). This
  is its own class, not a broad reference over the film's seconds.
- **Position-level:** the speech points at the composition's order ("the last movie"). It can be
  wrong about the order, and it is scored as such.
- **Non-film state:** memory search, giving up, off task. These are typed, not lumped into
  "not applicable".

**Relations between references:**

- **Comparison:** two references, often work-level, joined by a typed cross-reference relation
  (similar to, contrast). A comparison is neither joint nor alternative.
- **Attribution pair:** for blends and confusions, the framed work (what the speaker says it is
  about) paired with the content source (where the content actually occurred). Source
  attribution is its own quantity (rule 7), never an alternative.

**The discourse film frame.** Which film a person is talking about is held by the discourse
topic across many utterances, not by local content. Each unit therefore records the **framing
work** as its own field, produced by a whole-recall pass. A unit's reference is then produced
content-locally *within* that frame (see the producer section). Every reference also records
the producer condition and its rendering identity, as now.

## Projections, each typed and declared

Revision 1 pooled two different quantities in one matrix. They are now separate types.

1. **`LocationDistribution` (recall bin × film unit).** It holds where the speech points. Only
   single references and alternatives of singles enter it.
   - Inside a sampled bin, mass forms one simplex over: film units at a declared grain, held-out
     broad references, work-level and position-level states, the non-film states, unassigned,
     external, and in-work-outside-stimulus. The row sums to exactly 1.
   - *No sample* is an exclusive bin state outside the simplex.
   - References in a bin combine with a declared reducer: equal weight per distinct reference.
   - Inside a hypothesis, mass is uniform over its source units by default, and optionally over
     seconds (a duration prior, labelled as one).
   - Weights are used as written. Sherlock's fitted tempering does not transfer, and nothing is
     called calibrated.
2. **`CoverageMeasure` (recall bin × film unit).** It holds what the speech covers: joint
   moments each count one, and broad references cover their span. It has no sum constraint and
   never enters a distribution.
   - Broad references at work grain are held out by default. Spreading them over seconds is
     refused, because one mention of a film must not become hundreds of seconds of coverage.
3. **Correspondence table.** The sparse reference × hypothesis × interval table, with the
   relations, for any consumer that wants the account.
4. **Marginals.** Unit, scene, occurrence and work marginals of the `LocationDistribution`.
   Coarsening commutes with the side states.

**Invariants, each a named test:**

- entries are non-negative, and every sampled row sums to 1 within 1e-12;
- a joint reference refuses to enter a `LocationDistribution`;
- coarsening from unit to scene to work commutes, and the side states are unchanged;
- mass for a work lies only on parts that hold that work;
- relabelling ids or reordering batches changes nothing deterministic;
- a weight sum above 1 is refused;
- every row traces back to at least one reference;
- a not-applicable word stays in every denominator.

## Producer on FilmFestival, decided before any call

- **Two-level production.** A whole-recall pass segments each recall into film frames and records
  the framing work. A content-local pass, with a window of 2, shuffled, and opaque ids, then
  localizes within the frame.
  - Work identity is scored from the first pass and reported separately.
  - Scene localization is scored conditional on the framed work.
- **Ordering leaks closed.** Film order is fixed across participants. So each call uses opaque
  per-call scene ids, keeps scene order within a film, and permutes film order across calls. The
  outline uses cover names.
- **Units.** The authors' utterances were cut together with the scene coding, so their boundaries
  leak scene changes. Units come from our own segmenter on the word clock. An utterance-unit run
  is a control.
- **Two samples** with different seeds, batch compositions and id salts, all recorded.

## The yardstick, scoped to what the gold coding can validate

The FilmFestival gold coding gives one work label per author utterance, a set of 0 to 5 coarse
scenes (216-scene grain, with run 2 numbered +106), a specific-scene flag, and non-film labels.
It cannot validate within-scene seconds, breadth, joint versus alternative, anchors, or any
weight below the top one. The scorer refuses those metrics against it.

**Reported per corpus, never pooled.** Participant-cluster bootstrap, since four FilmFestival
participants have two sessions.

- **Hierarchical log loss.** −log P(scene set) = −log P(work) − log P(scene set | work), with
  both terms reported. The scene-given-work term is the one comparable across corpora. For
  Sherlock, work identity is not applicable, not 1.
- **Skill over frozen, corpus-specific reference forecasts:**
  - duration prior;
  - leave-one-participant-out scene marginal (the scored participant excluded, with a mutation
    test);
  - order oracle;
  - TF-IDF.
- **ε and unassigned mass are fixed before scoring.** Unassigned mass is redistributed by a
  declared rule, so a producer that abstains on everything scores exactly the baseline. The
  share of words on the fallback is reported.
- **Strata:**
  - film-transition vs within-film utterances, with F1 on film switches;
  - scene agreement conditional on the correct work;
  - speech vs non-speech films (5 of 10 have no dialogue);
  - the producer's work-level vs scene-located output against the gold's specific flag (the
    gist-versus-detail check);
  - its non-film states against memory search, gave up and off task.
- **Coverage and denominators.** Words vs utterances, covered vs all, and specific vs film-only
  vs non-film, all stated.
- **Dialogue-quote localization is reported per film with its n.** Few FilmFestival films have
  usable dialogue tracks, so there is no corpus column.
- **Boundary sensitivity** across the three film segmentations (JL, KM, RC); the gold coding is
  on JL's scheme. The gold scene numbering is checked against JL before scoring.

**Discriminating controls, with predicted directions recorded before running:**

| Control | Expected effect |
|---|---|
| Id permutation | none |
| Film-order permutation | none |
| Wrong-context swap | hurts |
| Unit-boundary jitter | separates the authors' segmentation from content |

**Provenance.** Every number carries its commit, reply digests, exposed ids and the recorded
producer condition.

FilmFestival is development data: all sessions went through the LLM mapper on 2026-11-09, and
its gold coding is public. Claims are engineering claims unless a subset is frozen now (owner
decision).

## Expected library gaps

1. A source model for multi-work compositions with occurrences and per-part axes, and a declared
   rule for a constituent that spans parts.
2. A declared word-cell rule in recall intake.
3. Typed `LocationDistribution` and `CoverageMeasure`, with an allocation inside a hypothesis's
   support named as such.
4. Address classes (work, position, non-film), and the comparison and attribution relations.
   This needs an ADR: new reference vocabulary beyond ADR 0030.
5. A framing-work field per unit, with its producer condition.
6. FilmFestival adapter and the two-level producer in the harness.

## Declared harness choices (step 2)

The step-2 harness (`tools/case-studies/recall-precision/cs2/`) had to choose where the design
was silent. These are declarations, not owner decisions.

- **Joint references are held out by work.** A joint hypothesis never enters a
  `LocationDistribution`. Its mass goes to a held-out joint state keyed by work, split by unit
  share when its support spans works. Alternatives are routed
  member by member.
- **Broad references are located, not held out** (owner, 2026-11-16, applying ADR 0030). A
  broad reference sits at the smallest declared grain that wholly contains its span:
  - within one scene, it is that scene's mass; at unit grain it is allocated over its own units
    by the within-hypothesis rule, and the allocated share is labelled;
  - across scenes of one work, it is the work node: it counts toward P(work) and is spread
    within the work by the fallback for the scene term;
  - only a span crossing works is held out, and it abstains.

  Gist + stretch is no longer held out as such.
- **Held-out, work-level and position mass in the loss.** It counts toward its work and is
  spread within the work by the fallback forecast. A position with no resolved work abstains.
- **Uncovered words are ledger counts only** (ADR 0031 §3). A bin of uncovered words alone is
  the typed absence *only uncovered*. The scorer abstains on it, as on *no sample*.
- **Three conditional terms.** The terms are film level, work given film, and scene set given
  work. The film term is scored on every corpus, because a conditional scene term would
  otherwise reward hedging into non-film states on a one-work corpus.

- **Units come from the library segmenter.** `agentStudyPrepare` (`recall-segmenter/v1`) runs
  on each development session's word cells joined by single spaces. No author punctuation
  enters the text. Words the segmenter consumes as separators belong to no unit. The
  FilmFestival word onsets are interpolated within the authors' utterances
  (`wordOnsetsAreMeasured: false`), so the word clock still carries the authors' utterance
  timing.

- **The pass-2 prompt is the coverage arm** (owner, 2026-11-16). On the three A/B sessions
  (sub-02, sub-07, sub-08), the total hierarchical log loss is lower for all three participants
  (mean 1.466 vs 1.551 bits). Coverage is higher (12.5% vs 20.4% of words uncovered), and the
  work term is better. The scene term (0.985 vs 0.964) differs within noise at n = 3.
- **Producer states map onto the gold's definitions** (owner, 2026-11-16). The first mapping
  disagreed with how the gold is coded, so the film term lost to the flat baseline.
  - A fragment (no content of its own) is a ledger count, like uncovered words, and never
    non-film mass.
  - Commentary, metamemory or memory search inside a framed film is a work-level reference of
    the framed work.
  - Only memory search between films (no framed work), gave up and off task are non-film
    destinations.
  - A remark with no framed work is the non-film commentary state.

### Revision 2026-11-17 (after the ML, linguistics and visualization critiques)

Each rule below is switched in `scorer-config.json` (`rules`, with the previous values in
`old_rules`) and covered by a named known-answer test and a discriminating mutant.

- **Fallback: the frame film first** (`fallback: frame-film`). A row's unassigned mass is
  spread over the producer's own pass-1 frame for the row's words, then by duration within the
  film. Words without a frame keep the duration prior. A null producer, with no references and
  no frames, scores the baseline exactly. The primary epsilon stays 0.01; 0.001 and 0.05 are a
  sensitivity check only.
- **Talk type is orthogonal to address** (`talk: orthogonal`). Commentary, evaluation and
  metamemory, including memory search, carry no address mass. Giving up counts as metamemory,
  and fragments are treated the same way. Each is typed non-addressed talk, a ledger count and
  never a destination, unless the reply anchors a reference to film content.
  - **Correction, made after seeing the film term** (owner, 2026-11-17). The first build also
    treated memory search between films, giving up and off-task speech as non-addressed talk.
    The FilmFestival film term then fell (skill vs duration 0.145 to 0.053), and the 83
    non-film rows rose to 5.33 bits. These three are non-film *states*: typed destinations
    among the plan's address classes, not talk about a film. They are restored as non-film
    destinations. Rule B applies only to evaluation, commentary and metamemory inside a framed
    film, and to fragments.
  - **What the cached replies cannot separate:** a pass-1 `work` unit may name a film or only
    evaluate one. Two variants are reported: `identifying` (default), where a framed or
    positioned `work` unit is a work-level reference, and `position-only`.
  - **Crosswalk to the gold's codes** (the gold does not define this vocabulary):

    | Our talk / address | FilmFestival gold code |
    |---|---|
    | narration with a scene address | specific (scene set) |
    | identification (work-level) | film-only (`movie*1000`) |
    | metamemory inside a framed film | film-search (scene -1) |
    | non-film state: memory search between films | memory search (-1) |
    | non-film state: gave up | gave up (-2) |
    | off task | off task (0) |
    | commentary, fragment | no code of their own: the gold codes the surrounding stretch |
- **Cross-scene broad references go to their scene set** (`cross_scene_broad: scene-set`).
  Mass is spread within the set by the fallback, restricted to the set. The work node is used
  only when a span cannot be bounded to scenes.
- **The film-level baseline is each participant's LOPO non-film rate**
  (`film_level_baseline: lopo`, alpha 0.5). Every forecast's non-film level uses this rate.
- **Baselines.**
  - The order oracle is relabelled `gold_context_ceiling`; it reads the previous gold row.
  - `gold_context_fitted` is the same ceiling with stay and next-in-presentation-order rates
    fitted leave-one-participant-out, then frozen.
  - `hmm_tfidf` is a sequential non-oracle forecast: an HMM over scenes in presentation order
    with TF-IDF emissions `exp(8 cos)`, filtered on recall words only.
  - `pass1_only` is the frame film, then duration within it.
- **`hmm_tfidf` rates are fitted leave-one-participant-out.** The stay and next-scene rates
  are counted on the other participants' gold scene sequences, consecutive specific rows only,
  and then frozen.
- **Frame weights above 1 are normalised.** A row's frame mix whose weights sum above 1 is
  rescaled to sum to 1.
- **A scene set with no valid scene is the work node.** A set made only of title-only scenes is
  spread over the work's valid scenes by the fallback.
- **Gold-blind windowing is a sensitivity check.** Every forecast is also rescored on fixed
  windows of the median gold-row duration, each labelled by the gold row it overlaps most.
- **Attribution.** These rules were revised after seeing development results. Most of the
  FilmFestival work-term gain comes from the pass-1 frames routed through the frame-film
  fallback: the work-term skill over `pass1_only` is about 0.39–0.40.
- **The two-level outline renders an occurrence's parts in order** (`part-order`). The first
  Sherlock two-level run used `start-only`, which sorted rows by seconds only and so interleaved
  Sherlock's two parts. It is kept for exact replay. The two orders agree whenever each
  occurrence has one segment, which is the FilmFestival case.

### Token efficiency (2026-11-17, stage 1)

- **Layout `prefix`.** The stable film outline goes in the system prompt, ahead of all
  per-call content; units and items go last. Film order, opaque scene labels and the label salt
  are drawn once per sample (seeded and recorded), not per call. A per-sample order still says
  nothing about viewing order.
- **Batches are grouped by framed film**, so a film's calls share one outline prefix. Within
  each film the no-chaining window rule is unchanged.
- **Anchors are word-number spans.** A reference gives `span: [first, last]` over the TARGET's
  words, which are shown numbered `[0] [1] ...`, instead of quoting them. The converter maps a
  span to word cells directly; the rest of the conversion is unchanged.
- **No reply field was removed.** The converter reads every field: `talk`, `refs`, `extent`,
  `occurrence`, `mode`, `alts` and `frame_mismatch`.
- **Cost is notional.** Calls run on the owner's subscription through `claude -p`, so
  `cost_usd` is the CLI's list-price estimate. It is reported beside call and token counts.

### Pass-1 revision (2026-11-17, stage 2; linguistics critique round 2)

One combined change, recorded as a new condition (`--pass1 v2`, run with layout `prefix`).
Each part has a named test and a mutant.

- **Three fields per unit.**
  - **talk**, a closed enum plus Custom: content, evaluation, metamemory, search_within,
    search_next, closing, ending, off_task, fragment.
  - **topic frame:** the film the discourse is about at this unit, or none.
  - **referential address class:** moment, work, position, composition, none.
- **Framing rules.**
  - search_next has an empty topic frame (enforced by the parser).
  - An opening cue is framed with the incoming film.
  - closing is framed with the film it closes.
  - ending is the non-film state gave_up.
  - A film switch is a segment boundary with its opening and closing cues.
- **Converter.**
  - moment content goes to pass 2.
  - A framed work address is a work-level reference.
  - A position address is resolved in its recorded reading: viewing order, or the speaker's
    recall order (distinct frame films in order of first mention). The ledger records the
    phrase, the reading and the resolution.
  - A composition remark is film-level mass spread over all works by the fallback.
  - search_next, ending and off_task are non-film states. So are unframed metamemory and
    search_within.
  - An unframed evaluation is non-film commentary. Framed evaluation, metamemory, search and
    closing are non-addressed talk, as are fragments and Custom.
  - A framed row's fallback reads the topic frame.
- **Two declared variants of `frame_mismatch` as switch evidence**, both on the same calls:
  - A, `clear-frame`: the unit's frame and its pass-2 references are dropped.
  - B, `incoming-frame`: as A, and the unit then takes the next framed unit's film when that
    is different.
- **A floor on the frame fallback** (`frame_floor` 0.01). This was found when the first
  stage-2 scoring failed: a certain frame gave the gold work zero fallback mass. The frame
  fallback is now mixed with the duration prior at 0.01, so every work keeps positive mass. A
  null producer still scores the baseline exactly.
- **Split, recorded before any call**
  (`data/study/recall-precision/cs2/filmfestival/stage2-20261117/split.json`). The ten coded
  development participants are sorted by `sha256("cs2-stage2-split-20261117:" + id)`.
  - Develop on the first five: sub-07, sub-08, sub-14, sub-17, sub-18.
  - Report on the other five: sub-01, sub-02, sub-10, sub-16, sub-19.
- **Selection, fixed before any call.** On the develop five, pick the variant with the lower
  work term. A tie within 0.005 bits goes to the higher ±1 switch F1. The frozen choice must not
  make the scene term worse than the per-call v1 run on the same rows. It is then run once on
  the report five.

### Layout ablation and decision rule (2026-11-17, recorded before the calls)

The stage-2 comparison changed two things at once: pass 1 v2, and the pass-2 prefix layout.
This ablation separates them.

- **The ablation run.** Pass 2 runs in layout `prefix` on the original v1 pass-1 frames, reused
  from full-run sample 1 (`--pass1-from`), with no pass-1 calls. It uses the develop five and
  the v2 run's seed and salt (20261125, `cs2-ff-s2-dev`).
- **Noise reference.** On the develop five, full-run sample 2 minus sample 1 under the current
  rules gives these paired participant differences:

  | Term | Mean | SE | Noise band (±2 SE) |
  |---|---|---|---|
  | Scene | +0.001 | 0.013 | 0.026 |
  | Work | −0.004 | 0.006 | 0.011 |
  | Film | +0.008 | 0.003 | 0.005 |

  A difference is within noise when its paired mean lies inside the band.
- **Rule for the prefix layout.** Adopt it if v1+prefix is within noise of v1+per-call on the
  scene term, or better.
- **Rule for pass 1 v2.** Adopt it only if v2+prefix beats v1+prefix on the scene term (a lower
  paired mean) without worsening the work or film term beyond noise. Otherwise drop v2, keep
  v1, and record the negative result here.

**Outcome (2026-11-17, develop five, applying the rule above).**

| | Scene | Work | Film |
|---|---|---|---|
| v1 + per-call | 1.054 | 0.090 | 0.140 |
| v1 + prefix | **1.013** | 0.088 | 0.140 |
| v2 + prefix | 1.105 | 0.109 | 0.179 |

- **The prefix layout is adopted.** Against v1 + per-call, the scene term is −0.040 (better
  for 4 of 5 participants), which is better than the 0.026 noise band. The work and film terms
  are within noise.
- **Pass 1 v2 is dropped; v1 is kept.** Against v1 + prefix it is worse on all three terms:
  scene +0.092, work +0.022 (band 0.011) and film +0.039 (band 0.005). This is a negative
  result. Its exact switch F1 was higher (0.598 vs 0.541), but its ±1 switch F1 was lower
  (0.897 vs 0.918).
- **New runs use `--layout prefix --pass1 v1`.** The CLI default stays `per-call`, so that
  cache-only replays of earlier runs reproduce exactly.

### Stage 3 controls (2026-11-17; predictions and pass/fail rules recorded before any call)

**Design.**
- Every new call uses `--layout prefix --pass1 v1`, with v1 frames reused from full-run
  sample 1 and the develop five's sessions.
- **Film restriction (a deviation from "3 participants").** Pass 2 runs only for the two films
  with the most framed content items: the_boyfriend (280 items) and bus_stop (218). Batches
  are grouped by film and seeded per film, so restricting films leaves the reference run's
  batches unchanged. The reference (v1 + prefix) is therefore exactly reproducible from cache.
  Restricting participants instead would have needed new reference calls.
- **Comparisons** use the gold rows of those two films on the develop five, paired over
  participants. The noise bands are the ones measured above: scene 0.026, work 0.011.
- **Projection.** 13 calls for each of controls 2–4, plus 2 for the probe: 41 of the remaining
  58.

**Controls**, each with its prediction and pass rule:

1. **Free checks, from cache:** null producer, gold-blind windows, and ε 0.001 and 0.05.
   - Prediction: the null producer equals the baseline; the rest is descriptive.
   - Pass: max abs bits difference = 0.
2. **Id and label permutation:** a new salt (`cs2-ff-s3-perm`) with the same seed and batches.
   With one film per call, film order is moot in pass 2.
   - Prediction: no effect.
   - Pass: |Δscene| ≤ 0.026 and |Δwork| ≤ 0.011.
3. **Wrong-context swap:** each item's CONTEXT is replaced by the context of a seeded random
   item from another session.
   - Prediction: no gain; the scene term may worsen.
   - Pass: Δscene ≥ −0.026.
4. **Content ablation:** unit texts are shuffled across units within each session, seeded. A
   shuffled unit's references cover all its word cells.
   - Prediction: the scene term falls to about the HMM or duration level.
   - Pass: the control's scene term is nearer the HMM baseline than the reference, i.e.
     ≥ (reference + HMM) / 2.
5. **Memorization probe:** 2 calls, each asking for the film and scene codes of 40 develop-five
   gold rows, given only dataset, participant, session and row ids.
   - Prediction: chance.
   - Pass: film accuracy ≤ 1/10 + 2 binomial SE, and specific-row scene accuracy ≤ chance +
     2 SE, where chance is the mean of 1 / (number of valid scenes in the gold film).
**Outcome of control 2 (permutation): FAIL by the recorded rule** (Δscene +0.055 against a
0.026 band). The band was measured on all films, but the control ran on two. On those two films
the sample-to-sample band is ±0.060, so this control cannot separate a label effect from
ordinary sampling noise. The verdict stands as FAIL. A valid test needs a band from two samples
on the same rows.

6. **Utterance-unit control:** deferred. New units need new pass-1 and pass-2 calls beyond the
   budget.

### Hill-climbing round 1 (2026-11-17; protocol recorded before any scoring)

Development data only, cache-only, with no model calls. The base run is full-run sample 1,
rescored under the current rules; sample 2 is used for noise and for ensembling.

**Protocol.**
- Tune on the develop five (sub-07, 08, 14, 17, 18). Confirm on the report five (sub-01, 02,
  10, 16, 19).
- Noise bands are ±2 SE of the paired participant differences between sample 2 and sample 1,
  on the same rows, per half and per term (scene, work, film).
- A lever is kept only if it beats its band on both halves, in the good direction, without
  worsening another term beyond that term's band.
- Parameters are fitted on the tune half only, never on the confirm half. Each half's report
  gives the scene, work and film terms, top scene and switch F1, with the paired sign-flip p
  (two-sided) and wins.

**Levers.**
1. **Calibration.**
   - One λ for the whole forecast: p' = (1 − λ) p + λ F, where F is the row's fallback. It
     mixes every term at once, so it is not fitted per term.
   - A temperature τ on located scene mass: loc_s ∝ loc_s^τ, with the row's located total
     kept.
   - λ is fitted on the tune half by grid search minimising the mean of the scene, work and film
     terms, and τ the same way.
2. **Two-sample ensembling.** The two samples' forecasts are averaged per row (P(w), P(S, w)
   and P(non-film) each averaged), against the current rule of averaging bits. It doubles the
   deployed calls.
3. **Switch repair from pass-2 evidence.** Only `frame_mismatch` can supply such evidence:
   pass-2 rows are restricted to the framed film, and the full runs refused no rows outside
   their frame. The declared rule is `mismatch_rule: incoming-frame`. A flagged unit becomes a
   boundary and takes the next framed unit's film when that film differs.

**Round 1 outcome: no lever kept.** All runs were cache-only at 560542459, with 0 calls. Bands
are ±2 SE of sample 2 minus sample 1 on the same rows:

| Band | Scene | Work | Film |
|---|---|---|---|
| Tune half | 0.026 | 0.011 | 0.005 |
| Confirm half | 0.079 | 0.021 | 0.025 |

Results, as change in bits (tune / confirm):

| Lever | Scene | Work | Film | Verdict |
|---|---|---|---|---|
| Calibration (λ = 0, τ = 1.5) | −0.016 / −0.020 | 0 / 0 | 0 / 0 | DROP |
| Ensemble vs mean of bits | −0.033 / −0.024 | −0.005 / −0.004 | −0.007 / −0.004 | DROP |
| Switch repair | +0.020 / −0.000 | +0.021 / +0.000 | 0 / 0 | DROP |
| Calibration + ensemble | −0.048 / −0.047 | −0.005 / −0.004 | −0.007 / −0.004 | DROP |

- **Calibration.** The tune fit chose λ = 0: any mixing with the fallback hurt. It chose τ = 1.5,
  at the top of the grid: sharpening helped, the opposite of the overconfidence hypothesis.
  The confidently wrong forecasts appear only when content is shuffled.
- **Ensemble.** It wins for 5/5 participants on both halves and is beyond the band on the tune
  half, but within the wide confirm band.
- **Switch repair.** It worsens the work term on the tune half; only 4 and 1 units are flagged.
- **Calibration + ensemble.** It wins for 5/5 on both halves but stays within the confirm band.
- **The binding limit is the confirm half's wide scene band.** With five participants the
  smallest two-sided sign-flip p is 0.0625.

**Gaps for the vocabulary ADR.**

- Memory search inside a framed film is recorded as a work-level reference, which is how the
  gold codes it. The ADR needs a typed *searching within work* state.
- Non-film states (memory search between films, gave up, off task, and commentary with no
  framed work) are typed destinations inside the simplex. A fragment is a ledger count, not a
  destination. That extends ADR 0031 §3, whose onset-point projection
  treats not-applicable references as a typed absence ("only not applicable"). The harness has
  no such absence: a bin of not-applicable words alone is a sampled row whose mass sits on
  non-film states. The vocabulary ADR must decide this too.

## Owner decisions (2026-11-16)

- **Two-level production:** a whole-recall film-frame pass, then content-local localization
  within the frame. Work identity and scene-given-work are scored separately.
- **Frozen FilmFestival participants:** sub-06, sub-09, sub-11, sub-12, sub-13 and sub-20. They
  are sealed at participant level from all case-study-2 design, tuning and inspection until one
  final run.
  - The rule: order participants by `sha256("cs2-filmfestival-holdout-20261116:" + id)` and take
    the first six.
  - Record: `data/study/filmfestival/cs2-partition.json`, SHA-256 of its JSON without the trailing
    newline `7dc59c85407a5813ccb0f6bdb46689387c9166f93b389a4e4e76a13c10dd0447`.
  - These participants were processed by the LLM mapper on 2026-11-09, and the gold coding is
    public. The freeze protects only against tuning this design on them.
- **Units:** the library's own segmenter on the word clock, with an utterance-unit run as a
  control.

## Order of work

1. Owner decisions: recorded above.
2. Harness: FilmFestival adapter, the typed projections and their invariants, and the scorer
   with baselines and controls, all passing known-answer tests on synthetic data. No model call
   yet.
3. Sherlock first through the new object, from case study 1's recorded answers (no model calls),
   with the yardstick.
4. FilmFestival two-level producer runs (two samples), then the yardstick and controls.
5. Progress page and critique round, including visualization.
6. Library gaps through an ADR, then slices.
