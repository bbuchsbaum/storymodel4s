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

### Hill-climbing rounds 2 and 3 (2026-11-17; protocol revised before scoring)

**Protocol revision (owner).** This change was made after round 1 showed that five participants
per half cannot pass small gains.
- **Pooled test.** A lever with no parameters, or with parameters fitted leave-one-participant-
  out, is tested pooled over all ten development participants. It passes if both hold:
  - the exact two-sided sign-flip test gives p ≤ 0.01 (in practice at least 9/10 wins); and
  - its effect exceeds the pooled band, ±2 SE of sample 2 minus sample 1 on the same rows.
- **Prompt or producer changes** keep the tune/confirm split.

**Round 2 (free, cache-only).**
1. **Two-sample ensembling:** the samples' distributions are averaged per row, against the mean
   of their bits. No parameters.
2. **Sharpening:** τ on located mass over the grid 1.0, 1.25, …, 3.0, applied to the ensemble
   forecast. Each participant's τ is the one that minimises the mean of the scene, work and film
   terms over the other nine participants. It is compared against the ensemble at τ = 1.

**Round 3 (paid; budget 100 fresh calls).** Decision rule, recorded before any call.
- **(b) The search-for-next sentence.** Pass 1 `v1b` changes one sentence of v1: the frame holds
  until the speaker moves on, except that speech searching for what came next has no frame.
  - **Isolation.** It uses full-run sample 1's per-call layout, seed and salt, so the sentence
    is the only change. Pass-2 answers are reused per unit where the frame film is unchanged;
    only items whose film changes are called fresh. This departs from "layout prefix" so that
    the comparison stays isolated.
  - **Kept if,** on both the tune five and the report five, the non-film mass on memory-search
    rows rises, or the film term improves, beyond its half band; and the scene term does not
    worsen beyond its band.
- **(a) A context window of 3.** Projected at about 48 calls per half for the change, plus about
  48 for a prefix reference on the report five: about 144 calls. That exceeds the budget left
  after (b), so (a) is not run and is deferred.

**Round 2 outcome** (harness ea448f47a; `hillclimb-r2/results.json`; no model calls). Pooled
bands (±2 SE of sample 2 − sample 1, ten participants): scene 0.041, work 0.012, film 0.012.

| Lever | Term | Result | Reference | Δ | Wins | p | Verdict |
|---|---|---|---|---|---|---|---|
| Ensemble of distributions | scene | 1.034 | 1.062 (mean of bits) | −0.029 | 10/10 | 0.002 | DROP |
| | work | 0.077 | 0.081 | −0.004 | 10/10 | 0.002 | |
| | film | 0.236 | 0.242 | −0.005 | 10/10 | 0.002 | |
| Sharpening τ (LOPO) | scene | 1.011 | 1.034 (ensemble, τ = 1) | −0.023 | 9/10 | 0.014 | DROP |
| | work, film | unchanged | | 0 | | | |

- **Ensemble.** It wins for every participant but stays inside the band, so it fails the lever
  rule. The round-2 instruction adopts it anyway as the pre-registered combination rule: it is a
  dominance on each row's total log loss and costs nothing. Test
  `test_two_sample_ensemble_averages_joint_distributions`, mutant M51 (discriminating).
- **Sharpening.** LOPO chose τ = 2.0 for nine participants and 2.25 for one; the pooled
  objective is flat from 1.75 to 2.25. It fails on both p (0.014 > 0.01) and band. Not kept.

**Round 3 outcome** (harness d5746ddf8; producer run `round3-20261117/v1b`;
`hillclimb-r3/results.json`).
- **Calls.** 17 fresh (14 pass 1, 3 pass 2 for the 13 items whose frame film changed), 0
  retries. Tokens: 34 input, 461,396 cache-creation, 4,011 cache-read and 163,551 output.
  Notional cost $6.96. The budget was 100.
- **Positive control for the reuse path.** A cache-only replay with v1 reproduces every
  reference row (18,256 rows, no field differs).
- **What changed.** Of 3,272 units, 3 lost their frame, 8 changed frame film and 24 changed
  kind. Memory-search units with an empty frame went from 4 of 38 to 3 of 37. The model did not
  act on the sentence.

| Half | Term | v1 | v1b | Δ | Band | p |
|---|---|---|---|---|---|---|
| Tune | scene | 1.054 | 1.057 | +0.004 | 0.026 | 0.56 |
| | film | 0.140 | 0.142 | +0.002 | 0.005 | 0.69 |
| | memory-search non-film mass | 0.100 | 0.100 | 0 | 0 | 1.0 |
| Report | scene | 1.088 | 1.082 | −0.006 | 0.079 | 0.50 |
| | film | 0.336 | 0.339 | +0.003 | 0.025 | 0.75 |
| | memory-search non-film mass | 0.052 | 0.060 | +0.008 | 0.017 | 1.0 |

- **Verdict (b): DROP.** No target moved beyond its band on either half.
- **(a) Window 3: not run,** as recorded before the calls. A per-call run on the fourteen coded
  sessions alone would take about 78 calls; it would fit the 83 left, but it departs from the
  prefix layout and was not the recorded rule.

### Hill-climbing round 4 (2026-11-17; free; protocol recorded before the decoder is scored)

**Why.** The levers so far work on each row alone. The untested stage is sequence decoding over
the producer's own row distributions. No model call is made in this round.

**Step 0: the error budget** (harness 8b890c804; `hillclimb-r4/diagnostics.json`). Rows are
the scene-scored rows, row-weighted. A *fallback* row abstains on all of its mass; a *miss* has
a wrong top scene; a *hit* has a right one.

| Run | Category | Rows | Share | Bits | Loss share | hmm_tfidf | gold_context_fitted |
|---|---|---|---|---|---|---|---|
| FilmFestival s1 (1,975; 1.034) | miss | 324 | 16.4% | 3.39 | 53.8% | 3.03 | 2.37 |
| | hit | 1,629 | 82.5% | 0.52 | 41.6% | 2.17 | 2.15 |
| | fallback | 22 | 1.1% | 4.30 | 4.6% | 2.71 | 1.93 |
| FilmFestival s2 (1,975; 1.028) | miss | 329 | 16.7% | 3.33 | 54.0% | 3.18 | 2.43 |
| | hit | 1,619 | 82.0% | 0.51 | 40.3% | 2.14 | 2.14 |
| | fallback | 27 | 1.4% | 4.31 | 5.7% | 2.52 | 1.91 |
| Sherlock V1 (385; 1.035) | miss | 56 | 14.5% | 3.49 | 49.1% | 4.52 | 4.40 |
| | hit | 327 | 84.9% | 0.58 | 47.8% | 3.23 | 3.38 |
| | fallback | 2 | 0.5% | 6.19 | 3.1% | 5.47 | 0.77 |

**Step 0: where the misses are.** Distance is in scene steps, within the gold work, from the
producer's top scene to the nearest gold scene. A neighbour is the previous or next gold row.

| Of the misses | FilmFestival s1 | FilmFestival s2 | Sherlock V1 |
|---|---|---|---|
| Distance 1 (share of all scene loss) | 71.3% (35.2%) | 67.5% (34.7%) | 60.7% (26.1%) |
| Distance 2–3 | 20.7% (12.9%) | 23.1% (13.3%) | 28.6% (14.6%) |
| Distance 4 or more | 8.0% (5.6%) | 9.4% (6.0%) | 10.7% (8.3%) |
| A gold scene is the second choice | 56.5% | 59.3% | 57.1% |
| The previous or next row's top scene is a gold scene | 53.1% | 53.2% | 7.1% |
| The neighbours' top scenes bracket a gold scene | 68.8% | 67.5% | 58.9% |
| Equal or bracket | 76.2% | 74.5% | 60.7% |
| Isolated jump (share of all scene loss) | 13.0% (6.8%) | 16.4% (8.4%) | 3.6% (2.3%) |

An *isolated jump* is a miss whose neighbours bracket a gold scene while its own top scene lies
outside the neighbours' range.

**Amended before any decoder was scored** (same day): the jump is uniform over the work's
other scenes, not only earlier ones; entry gained a uniform part; and non-film states take the
work's ratio. The first draft of this section, at 78b771e6b, had none of the three.

**Reading, recorded before the decoder is scored.**
- Most misses are one scene away from the gold, and that is where a sequence prior has least to
  say: staying and stepping are both likely there.
- Isolated jumps, the errors a decoder repairs most directly, carry 7–8% of the FilmFestival
  scene loss and 2% of Sherlock's. The ceiling from them is about 0.07–0.09 bits on
  FilmFestival, against a band of 0.041.
- **Prediction:** the decoder gains little on the scene term, and may fail condition 1. It is
  built anyway because the test is free and the result settles the question.

**Step 1: the decoder.** A new forecast `producer_decoded` is reported beside `producer` and
never replaces it. The content-local forecast and the order-informed forecast are different
estimands (ADR 0031 §4). Module `cs2/decoder.py`.
- **Method.** Forward-backward over the gold rows of one session, in session order.
- **States.** A state is a position and a mode.
  - A position is a valid scene of a work, or START before any film is recalled.
  - A mode is *film*, *search* (the between-works memory search) or *other* (gave up, off
    task, external).
  - A non-film row does not move the position. That is how the search state knows which work
    came before it.
- **Cells.** A forecast is read on the scored simplex: one cell per valid (work, scene),
  plus *search* and *other*.
  - P(non-film) is the forecast's own. Its claimed part is split by the producer's label;
    its fallback part is split by ν, the LOPO share of memory search among non-film gold rows.
  - In-work mass that sits on no valid scene (a title-only scene, or in-work outside the
    stimulus) counts as work-level evidence, spread within the work by the duration prior.
    The affected rows are counted in the results.
- **Evidence.** A row's likelihood for a cell is (q / D)^α, where q is the producer's
  content-local row distribution and D is the participant's duration forecast on the same
  cells. A producer that abstains everywhere has q = D, so its evidence is flat.
  - A non-film state at a position inside a work also takes that work's ratio,
    q(work | film) / D(work | film). A non-film row keeps its frame, so the frame is evidence
    about the position and not about the mode. Without this, a framed row that abstains would
    count ten to one against non-film through normalisation alone.
- **Transitions.** Corpus-neutral parameters, fitted leave-one-participant-out on the other
  participants' gold sequences, with additive smoothing 0.5 per outcome (`lopo_alpha`).
  - *Mode:* a 3×3 matrix between consecutive rows, and the first row's mode.
  - *Work,* on entering a film row: same work, next work in presentation order, or any other
    work (uniform). Rates are fitted separately after a film row, after a search row, after an
    other row, and from START. An outcome that does not exist is dropped and the rest
    renormalized.
  - *Scene,* within a work: stay; a forward step, geometric in scene steps; or a jump, uniform
    over the work's other scenes. Forward mass at the last scene goes to stay.
  - *Entry,* on entering a work: a mixture of a geometric in scene steps from the work's first
    scene and a uniform draw over the work's scenes.
  - A forward move may be a step or a jump, so the scene and entry rates are fitted by EM (100
    iterations from equal rates and a geometric parameter of 0.5). The other rates are counts.
  - The uniform parts keep every cell reachable in one row, so one skipped stretch cannot
    cost an unbounded number of bits.
  - A gold row's position is the last valid scene of its set, as `hmm_tfidf` takes it.
- **α.** Grid 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.8, 1.0, 1.25, 1.5. Each participant's α
  minimises the mean of the scene, work and film terms over the other nine. A pick at the
  edge of the grid is reported as such; the grid is not extended after scoring.
- **Output.** The smoothed posterior per row on the same cells, scored by the same scorer.
- **`order_prior_only`.** The same decoder with flat evidence. With a null producer,
  `producer_decoded` must equal it exactly; a test and a mutant cover that.
- **Evidence share.** Each decoded row records, in bits, KL(posterior ‖ the row's own
  distribution) and KL(posterior ‖ the posterior without the row's own evidence).
  - Own share is the second divided by the sum of the two. It has no value when both are zero.
  - Each row also records whether its top cell is the top of its own evidence or was supplied
    by its neighbours. An inferred location is typed as inferred and never enters the account.

**Protocol.** The primary run is FilmFestival sample 1. The decoder is kept only if all four
hold.
1. **FilmFestival, pooled over the ten participants with LOPO fits:** the scene term improves
   with exact two-sided sign-flip p ≤ 0.01, and by more than the pooled band (0.041).
2. **The work and film terms** are not worse beyond their bands (0.012 each).
3. **Out-of-corpus confirmation** on the Sherlock part-order run (ablation V1). α is fixed at
   the value that minimises the same objective over all ten FilmFestival participants;
   transitions are fitted LOPO within Sherlock. The scene term moves in the same direction,
   with one-sided exact sign-flip p ≤ 0.05 over its ten participants.
4. **It beats `hmm_tfidf`, `gold_context_fitted` and `order_prior_only`** on the scene term,
   as participant means, on both corpora.

**Reported for both corpora,** whatever the verdict: producer against decoded on the film,
work and scene terms and top scene; the same split by the producer's row category; switch F1
(exact and ±1) against `pass1_only`; non-film mass on memory-search rows; the gold-blind
windows version (every fitted quantity refitted on the windowed rows); and the decoder on the
two-sample ensemble and on sample 2.

**Round 4 outcome** (harness 0414be9f1; `hillclimb-r4/results.json`; no model calls).

**Verdict: DROP by the recorded rule.** Conditions 1, 2 and 4 hold; condition 3 fails.
`producer_decoded` stays a separate forecast beside `producer`, and the pre-registration draft
is unchanged. The prediction recorded before scoring was wrong about FilmFestival.

| Condition | Result | Holds |
|---|---|---|
| 1. FilmFestival scene term, pooled | −0.191 bits, 10/10, p = 0.002, band 0.041 | yes |
| 2. Work and film terms not worse | work −0.042 (10/10); film −0.018 (7/10, p = 0.39) | yes |
| 3. Sherlock, α from FilmFestival (0.8) | +0.030 bits (worse), one-sided p = 0.61 | **no** |
| 4. Beats the three references on scene | by 1.5 bits or more on both corpora | yes |

**FilmFestival sample 1** (LOPO α: 0.8 for nine participants, 1.0 for one; not at an edge).

| Forecast | Scene | Work | Film | Top scene | Switch F1 (±1) | Non-film on search rows |
|---|---|---|---|---|---|---|
| `producer` | 1.071 | 0.079 | 0.238 | 82.5% | 0.644 (0.900) | 0.110 |
| `producer_decoded` | 0.879 | 0.037 | 0.220 | 81.2% | 0.694 (0.965) | 0.224 |
| `pass1_only` | 4.092 | 0.140 | 0.281 | 9.2% | 0.701 (0.931) | 0.035 |
| `order_prior_only` | 4.305 | 3.453 | 0.282 | 5.5% | 0.020 (0.061) | 0.050 |
| `hmm_tfidf` | 2.579 | 1.282 | 0.281 | 50.9% | 0.188 (0.402) | 0.035 |
| `gold_context_fitted` | 2.392 | 0.459 | 0.281 | 45.6% | 0.022 (0.923) | 0.035 |

- **The log loss improves while top-scene accuracy falls** (82.5% to 81.2%). The decoder
  moves mass, and it flips more right tops to wrong (144) than wrong to right (119).
- **By the producer's row category** (scene bits, producer to decoded):
  - misses, 324 rows: 3.39 to 2.80; the decoded top is right on 34.3% of them;
  - hits, 1,629 rows: 0.52 to 0.39; the decoded top stays right on 91.2%;
  - fallback, 22 rows: 4.30 to 1.68.
- **Replications.** Sample 2: scene −0.189 (9/10, p = 0.008). Two-sample ensemble: 1.034 to
  0.860 (−0.174, 9/10, p = 0.006). Gold-blind 7 s windows, everything refitted (α 0.6): 1.241
  to 1.047 (−0.194, 9/10, p = 0.006), with top scene 75.1% to 76.0%.
- **Null producer.** Its decoded forecast equals `order_prior_only` on every cell of all
  2,282 rows; the largest difference is exactly 0.
- **In-work residual rule.** It touched 4 rows, none of them scene-scored.

**Sherlock part-order run** (α fixed at 0.8; LOPO transitions within Sherlock).

| Forecast | Scene | Film | Top scene |
|---|---|---|---|
| `producer` | 1.065 | 0.001 | 84.9% |
| `producer_decoded` | 1.095 | 0.001 | 78.7% |
| `order_prior_only` | 5.174 | 0.023 | 6.2% |
| `hmm_tfidf` | 3.507 | 0.002 | 54.0% |
| `gold_context_fitted` | 3.516 | 0.002 | 55.8% |

- By category: misses 3.49 to 3.48, hits 0.58 to 0.67, fallback (2 rows) 6.19 to 0.04.
- Gold-blind 25.5 s windows (α 0.6 from the FilmFestival windows): 1.486 to 1.559, worse.
- Switch F1 and non-film mass do not apply: one work, and no non-film gold rows.

**Evidence share.** About 22% of scene-scored rows on both corpora get a top cell their own
evidence did not put first. Those inferred tops are right far less often.

| Run | Mean own share | Top from own evidence: right | Top from neighbours: right |
|---|---|---|---|
| FilmFestival sample 1 | 0.48 | 89.9% (1,534 rows) | 51.0% (441 rows) |
| Sherlock part-order | 0.39 | 93.0% (299 rows) | 29.1% (86 rows) |

**Why Sherlock fails (post hoc; no decision rests on it).** The decoder helps where the speaker
follows the film's order and hurts where the speaker leaves it. Scene bits, producer to
decoded, by the gold step from the previous scene-coded row of the same work:

| Gold step | FilmFestival rows | FilmFestival bits | Sherlock rows | Sherlock bits |
|---|---|---|---|---|
| Stay | 783 | 1.13 to 0.73 | 2 | 0.57 to 2.82 |
| Forward 1 | 639 | 0.95 to 0.60 | 215 | 0.97 to 0.25 |
| Forward 2–3 | 300 | 0.89 to 0.91 | 87 | 1.01 to 1.46 |
| Forward 4 or more | 87 | 0.97 to 1.30 | 31 | 0.96 to 2.08 |
| Backward | 69 | 1.45 to 2.61 | 40 | 1.47 to 3.98 |
| First in the work | 97 | 1.00 to 0.58 | 10 | 1.25 to 0.41 |

- **Mechanism on Sherlock** (`post_hoc_evidence_bound` in the results). A row's distribution
  is a mixture over its words, and the words that abstain or stay at work level put a floor
  under every scene.
  - The floor is 0.17 of the prior on the median Sherlock row, and the top scene stands at
    about 33 times the prior. Content can therefore outweigh order by at most about 7.5 bits,
    or 6 after α = 0.8.
  - One step forward against a jump is worth about 7.3 bits of prior. So the order wins on a
    skip or a return, even when the row is clearly located. It turned 49 right tops wrong and
    25 wrong tops right.
- **On FilmFestival** the floor is the ε floor (0.011) and the bound is about 13 bits against
  6.3 bits of prior, so the bound binds less. Its flipped tops are mostly near-ties: the
  median evidence ratio between the producer's top and the decoded top is 0.17 bits.
- **Backward steps suffer on both.** The decoded top is right on 52% of them on FilmFestival
  (producer 74%) and on 33% on Sherlock (producer 68%).
- **Why the corpora differ.** FilmFestival rows are short (median 7 s), and 76% of its
  within-work steps are a stay or one step forward. A Sherlock row is one recalled scene
  (median 25.5 s), so skips and returns are 42% of its steps.
- **α does not rescue it.** With α fitted LOPO within Sherlock (1.25 or 1.5, at the grid
  edge) the scene term is 1.032 against 1.065: −0.033, 5/10, p = 0.77.
- **What this means for use.** Out-of-order recall is a quantity of interest, and this
  decoder degrades exactly those rows. That argues against it as built, apart from the rule.

**Tests and mutants.** Six tests cover the decoder; mutants M52–M57 each fail their named test
(`mutations/r4-20261117.json` at deb5364e0, 57 mutants, all caught). Five discriminate. M53
(the backward message) is a regression guard, because the inferred-top test fails with it.

### Hill-climbing round 5 (2026-11-17; free; rule recorded before the lever is scored)

**Round 4, as accepted.** The coordinator accepted round 4 as reported.
- The decoder is dropped by the recorded rule.
- Independently of the rule, a forecast that degrades out-of-order recall is unfit as the
  mapping object, because out-of-order recall is a quantity of interest.

No model call is made in this round. The pre-registration draft is not edited.

**Part A: descriptive scores, with no pass/fail bar.** Step 0 of round 4 showed that most
misses are one scene from the gold, so scene identity alone understates the mapper. These are
reported for `producer` and every comparison forecast, on FilmFestival samples 1 and 2 and the
Sherlock part-order run, per participant with participant-cluster CIs.
1. **Top scene within ±1.** The forecast's top scene in the gold work is at most one scene step
   from a gold scene, counted over the work's valid scenes in order.
2. **Film-time error, in seconds.**
   - *Film clock.* Seconds from the start of an occurrence, its segments joined in order. A
     work with several occurrences has no single clock; its rows are left out and counted.
   - *A scene's stretch* runs from its first unit's start to its last unit's end.
   - *The distribution.* The forecast's mass on the gold work's valid scenes, renormalised;
     each scene's mass is spread evenly over the scene's stretch.
   - *The row's value.* The expected distance from that distribution to the gold scene set:
     0 inside a gold scene, otherwise the distance to the nearest gold scene's boundary.
   - *Unlocated mass.* The all-mass version leaves it where the scorer's fallback puts it,
     spread by duration within the work. The located-only version keeps only the producer's
     scene-located and scene-set mass in the gold work, renormalised; a row with none has no
     value and is counted. It exists only for producer forecasts.
   - *Reported* as the mean and the median of a participant's rows, each averaged over
     participants.
3. **How far a distance-1 miss is from the gold.** For the producer's misses at distance 1,
   the located span is the source units of its top scene that carry the row's located mass.
   - Two distances to the gold scene set are given: the expected distance of that mass, each
     unit's share spread evenly over the unit, and the gap from the span's nearest edge.
   - The shares within 5 s, 10 s and 20 s are reported. A miss whose top scene holds no
     unit-located mass has no span and is counted apart.
4. **Gold-step strata.** A scene-scored row is typed by the step from the previous scene-coded
   row of the same work: stay, forward 1, forward 2–3, forward 4 or more, backward, or first in
   the work. A row's position is the last valid scene of its set.
   - Top-scene accuracy, within-±1 accuracy and scene bits are reported per stratum.
   - The step is a field of every scored row, and `by_gold_step` is a standing stratum of every
     run summary and of `summary-both.json`.

**Part B: `producer_order_fallback`.** Order may place only what the producer left unplaced.
- **Principle.** An inferred location must never overrule an observed one. Round 4's decoder
  let order outvote located content.
- **Definition.** p′(s) = located(s) + u · q(s).
  - The producer's located mass is unchanged: scene-located mass, in-work mass outside the
    valid scenes, and claimed non-film mass.
  - q is the decoder's posterior for the row from the other rows' evidence only (round 4's
    posterior without the row's own evidence).
  - Wherever the scorer today spreads mass by the duration prior, the order fallback spreads
    it by q under the same restriction: unassigned mass within the frame film (with the same
    frame floor), work-level mass within its work, scene-set mass within its set.
  - A row with no frame takes q as it is, at the film, work and scene levels.
- **Nothing new is fitted.** Transitions and α are round 4's: transitions LOPO, α as round 4
  picked it (LOPO on the decoder's objective; on Sherlock the FilmFestival value).
- **A null producer equals `order_prior_only` exactly.** A test and a mutant cover it.
- **Each row records how much mass order placed,** split into unassigned mass and mass placed
  within a work or set the producer named.

**Rule for B.** It can be kept only as a secondary forecast, never as the mapping object, and
only if all five hold. The primary run is FilmFestival sample 1.
1. **FilmFestival, pooled over the ten participants:** the scene term improves with exact
   two-sided sign-flip p ≤ 0.01 and by more than the band (0.041).
2. **The work and film terms** are not worse beyond their bands (0.012 each).
3. **Out-of-order rows** (backward, or forward 4 or more) are not worse than `producer`
   beyond the band, on either corpus: the participant-paired difference in scene bits on those
   rows is at most +0.041.
4. **Top scene** is not lower than `producer`'s by more than 1 point, on either corpus.
5. **Sherlock part-order run:** the scene term moves in the same direction, with one-sided
   exact sign-flip p ≤ 0.05.

**Caveat on condition 5.** Sherlock's development rows were used to diagnose round 4, so
condition 5 is a check and not an independent confirmation. Confirmation can only come from
sealed data, as a pre-registered secondary forecast.

**Round 5 outcome** (harness a482fa3f2; `hillclimb-r5/descriptive.json` and
`hillclimb-r5/results.json`; no model calls).

**Part A. Scene identity understates the mapper.** Values are participant means with 95%
participant-cluster intervals. Film-time error is in seconds.

| FilmFestival sample 1 | Top scene | Within ±1 | Time error, mean | Time error, median |
|---|---|---|---|---|
| `producer` | 81.8% [78.9, 84.6] | 93.6% [91.3, 95.6] | 23.5 [22.1, 25.0] | 18.7 |
| `producer`, located mass only | | | 5.2 [4.1, 6.8] | 1.3 |
| `gold_context_ceiling` | 45.8% [40.6, 50.7] | 80.4% [73.4, 86.2] | 16.1 [13.0, 19.8] | 5.5 |
| `gold_context_fitted` | 38.7% [29.6, 47.3] | 76.5% [69.2, 82.9] | 36.4 [34.6, 38.6] | 28.4 |
| `hmm_tfidf` | 46.0% [39.5, 52.7] | 64.7% [56.9, 72.6] | 48.2 [40.6, 56.4] | 38.3 |
| `tfidf` | 47.3% [43.3, 51.7] | 61.5% [57.8, 65.6] | 70.7 [68.0, 73.7] | 60.1 |
| `lopo` | 9.5% [8.4, 11.0] | 21.0% [19.2, 22.6] | 93.1 [90.0, 96.5] | 91.1 |
| `duration`, `pass1_only` | 9.6% [8.1, 11.6] | 19.8% [18.8, 21.0] | 95.5 [92.4, 98.9] | 96.2 |

| Sherlock part-order run | Top scene | Within ±1 | Time error, mean | Time error, median |
|---|---|---|---|---|
| `producer` | 84.1% [80.4, 87.8] | 93.5% [91.9, 95.0] | 215 [202, 227] | 183 |
| `producer`, located mass only | | | 27.7 [22.1, 33.3] | 7.5 |
| `gold_context_ceiling` | 31.5% [27.6, 35.4] | 62.1% [57.2, 66.5] | 214 [192, 237] | 137 |
| `gold_context_fitted` | 55.7% [52.0, 59.2] | 68.5% [62.8, 73.7] | 498 [481, 515] | 468 |
| `hmm_tfidf` | 53.1% [48.1, 57.6] | 69.2% [62.0, 75.6] | 793 [720, 854] | 757 |
| `tfidf` | 54.2% [47.5, 61.6] | 62.6% [56.2, 68.9] | 937 [922, 952] | 940 |
| `lopo` | 3.8% [2.9, 4.8] | 7.0% [5.6, 8.7] | 1,082 [1,070, 1,092] | 1,065 |
| `duration`, `pass1_only` | 1.6% [0.8, 2.4] | 4.8% [3.6, 6.0] | 1,029 [1,010, 1,044] | 1,020 |

- **FilmFestival sample 2** agrees: producer top scene 81.2% [77.6, 84.5], within ±1 92.8%
  [90.4, 94.7], time error 23.4 s (median 18.2), located only 5.3 s (median 1.0).
- **The all-mass time error is mostly unlocated mass.** It is spread over the whole work by
  duration, so a quarter of a row's mass at a film's average distance sets the figure. The
  located-only figure says where the producer actually pointed.
- **Rows left out.** The lobby cartoon has two occurrences and no single clock: 3 rows. The
  located-only figure has no value on 35 FilmFestival rows and 3 Sherlock rows with nothing
  located in the gold work.

**A distance-1 miss is a boundary disagreement on FilmFestival.** The located span is the
source rows the producer named in its top scene.

| Producer's distance-1 misses | FilmFestival s1 | FilmFestival s2 | Sherlock |
|---|---|---|---|
| Misses (with a located span) | 231 (187) | 222 (191) | 34 (33) |
| Expected distance within 5 s | 59.4% | 56.5% | 15.2% |
| Expected distance within 10 s | 90.9% | 86.9% | 36.4% |
| Expected distance within 20 s | 99.5% | 97.9% | 63.6% |
| Nearest edge within 5 s | 95.7% | 92.7% | 90.9% |
| Nearest edge within 10 s | 100% | 97.4% | 93.9% |
| Median expected distance | 4.0 s | 4.0 s | 15.0 s |

- On FilmFestival nine in ten of these misses point within 10 s of the gold scene.
- On Sherlock the nearest named row usually touches the gold scene, but the mass sits deeper
  in the neighbouring scene (median 15 s). Its rows and scenes are longer.
- The 44, 31 and 1 misses without a span have no unit-located mass in the top scene: the top
  came from scene-set or fallback mass.

**Out-of-order recall, the standing stratum.** Each cell gives top scene, within ±1 and scene
bits, row-weighted.

| FilmFestival s1, gold step | Rows | `producer` | `hmm_tfidf` |
|---|---|---|---|
| Stay | 783 | 80.3%, 92.2%, 1.13 | 56.8%, 77.4%, 2.08 |
| Forward 1 | 639 | 82.9%, 97.2%, 0.95 | 54.9%, 75.4%, 2.07 |
| Forward 2–3 | 300 | 88.7%, 96.3%, 0.89 | 33.7%, 54.3%, 2.99 |
| Forward 4 or more | 87 | 82.8%, 90.8%, 0.97 | 25.3%, 37.9%, 3.40 |
| Backward | 69 | 73.9%, 88.4%, 1.45 | 40.6%, 60.9%, 2.83 |
| First in the work | 97 | 83.5%, 92.8%, 1.00 | 60.8%, 73.2%, 2.34 |
| **Out of order** | 156 | 78.8%, 89.7%, 1.18 | 32.1%, 48.1%, 3.15 |

| Sherlock, gold step | Rows | `producer` | `hmm_tfidf` |
|---|---|---|---|
| Stay | 2 | 100%, 100%, 0.57 | 50.0%, 100%, 4.17 |
| Forward 1 | 215 | 87.0%, 95.8%, 0.97 | 75.3%, 86.0%, 2.53 |
| Forward 2–3 | 87 | 88.5%, 95.4%, 1.01 | 28.7%, 60.9%, 4.30 |
| Forward 4 or more | 31 | 80.6%, 87.1%, 0.96 | 38.7%, 54.8%, 4.09 |
| Backward | 40 | 67.5%, 85.0%, 1.47 | 17.5%, 32.5%, 4.90 |
| First in the work | 10 | 90.0%, 90.0%, 1.25 | 10.0%, 10.0%, 6.98 |
| **Out of order** | 71 | 73.2%, 85.9%, 1.25 | 26.8%, 42.3%, 4.55 |

- Out of order means backward or forward 4 or more. The producer loses little there; the
  sequential baseline loses most of its accuracy.
- `by_gold_step` and `out_of_order` are now strata of every run summary, `rows.tsv` carries
  `within_1` and `gold_step`, and `summary-both.json` carries them for the producer and every
  baseline. The main runs were rescored from the call cache into `rev-20261117d` for this.
- The rescoring also brings the main entries up to the current harness. The `rev-20261117c`
  entries predate the frame floor, so FilmFestival sample 1 moves by at most 0.014 bits on a
  row (scene 1.0722 to 1.0707, work 0.0760 to 0.0791). The original call ledgers stay in the
  original run directories.

**Part B. `producer_order_fallback` is kept as a secondary forecast, by the recorded rule.**
All five conditions hold. It is never the mapping object.

| Condition | Result | Holds |
|---|---|---|
| 1. FilmFestival scene term, pooled | 1.071 to 0.798: −0.273, 10/10, p = 0.002, band 0.041 | yes |
| 2. Work and film terms not worse | work −0.024 (10/10); film −0.039 (8/10, p = 0.016) | yes |
| 3. Out-of-order rows, scene bits | FilmFestival +0.006; Sherlock −0.047; limit +0.041 | yes |
| 4. Top scene within 1 point | FilmFestival 82.5% to 84.3%; Sherlock 84.9% to 86.2% | yes |
| 5. Sherlock scene term | 1.065 to 0.748: −0.317, 10/10, one-sided p = 0.001 | yes |

The caveat recorded with the rule applies: condition 5 is a check, not a confirmation.

| Run | Forecast | Scene | Work | Film | Top scene | Within ±1 | Time error (median) |
|---|---|---|---|---|---|---|---|
| FilmFestival s1 | `producer` | 1.071 | 0.079 | 0.238 | 82.5% | 94.3% | 23.5 s (18.7) |
| | `producer_order_fallback` | 0.798 | 0.056 | 0.199 | 84.3% | 97.0% | 6.4 s (3.2) |
| | `order_prior_only` | 4.305 | 3.453 | 0.282 | 5.5% | 10.9% | 100 s (99) |
| FilmFestival s2 | `producer` | 1.054 | 0.082 | 0.245 | 82.0% | 93.4% | 23.4 s (18.2) |
| | `producer_order_fallback` | 0.768 | 0.058 | 0.208 | 84.1% | 96.4% | 6.3 s (3.1) |
| Sherlock | `producer` | 1.065 | | 0.001 | 84.9% | 93.8% | 215 s (183) |
| | `producer_order_fallback` | 0.748 | | 0.001 | 86.2% | 94.0% | 38 s (20) |
| | `order_prior_only` | 5.174 | | 0.023 | 6.2% | 17.9% | 881 s (899) |

Top scene and within ±1 in this table are row-weighted, as in round 4.

- **Sample 2 replicates:** scene −0.286 (10/10, p = 0.002).
- **Other FilmFestival measures, sample 1.** Switch F1 0.644 to 0.651 (±1 row: 0.900 to
  0.926). Non-film mass on memory-search rows 0.110 to 0.198.
- **By the producer's row category** (scene bits): misses 3.39 to 2.46, hits 0.52 to 0.38,
  fallback 4.30 to 1.68. The top stays right on 97.5% of the producer's hits.
- **Null producer.** Its order fallback equals `order_prior_only` exactly on every cell and
  every scored term (largest difference 0.0 on 2,282 and 385 rows), and order places all of
  its mass.

**By gold step** (scene bits, then top scene; producer to lever).

| Gold step | FilmFestival s1 rows | Bits | Top scene | Sherlock rows | Bits | Top scene |
|---|---|---|---|---|---|---|
| Stay | 783 | 1.13 to 0.75 | 80.3% to 83.0% | 2 | 0.57 to 0.49 | 100% to 100% |
| Forward 1 | 639 | 0.95 to 0.65 | 82.9% to 85.3% | 215 | 0.97 to 0.51 | 87.0% to 90.7% |
| Forward 2–3 | 300 | 0.89 to 0.72 | 88.7% to 88.0% | 87 | 1.01 to 0.93 | 88.5% to 87.4% |
| Forward 4+ | 87 | 0.97 to 0.92 | 82.8% to 80.5% | 31 | 0.96 to 0.91 | 80.6% to 80.6% |
| Backward | 69 | 1.45 to 1.46 | 73.9% to 73.9% | 40 | 1.47 to 1.44 | 67.5% to 62.5% |
| First in the work | 97 | 1.00 to 0.63 | 83.5% to 87.6% | 10 | 1.25 to 0.69 | 90.0% to 90.0% |
| **Out of order** | 156 | 1.18 to 1.16 | 78.8% to 77.6% | 71 | 1.25 to 1.21 | 73.2% to 70.4% |

**How much order placed** (`order-rows/*.tsv` holds it per row).

| | FilmFestival s1 | Sherlock |
|---|---|---|
| Mean mass order placed, all rows | 0.376 | 0.220 |
| of which unassigned | 0.252 | 0.176 |
| of which inside a work or set the producer named | 0.124 | 0.044 |
| Median | 0.201 | 0.178 |
| Rows where order placed more than half | 26.1% | 3.6% |

**What to know before relying on it.**
- **Order can still change the top scene.** The located mass is unchanged, but where it is a
  minority of the row, the unplaced mass can put another scene on top.
  - Order exceeds the top of the directly located mass on 6.2% of FilmFestival scene-scored
    rows (123) and 4.4% of Sherlock's (17). The full producer forecast already has a fallback.
    Relative to that forecast, the top changes on 79 and 13 of those rows, respectively.
  - Among those changed tops, FilmFestival gains 26 correct rows and loses 19. Sherlock gains
    six and loses five.
- **Out-of-order rows lose a little top-scene accuracy,** though not scene bits: a net 2 rows
  of 156 on FilmFestival and 2 of 71 on Sherlock. Every flipped row is one where order set
  the top. Condition 3 is on scene bits and holds.
- **On fixed-width windows the gain holds and that loss is larger** (a sensitivity check,
  with parameters refitted on the windowed rows). Width comes from the development gold
  median. Gold overlap sets labels and determines which windows enter the sequence.
  - FilmFestival, 7 s windows: scene 1.241 to 0.951 (−0.290, 10/10, p = 0.002); top scene
    75.1% to 77.0%. Sherlock, 25.5 s windows: 1.486 to 1.189 (−0.297, 9/10); 72.5% to 72.8%.
  - Out-of-order FilmFestival windows: scene bits 1.63 to 1.58, but top scene 62.0% to 55.7%
    (10 of 158 windows).
- **Sherlock's film term** moves by +0.0004 bits; it has no band, and no non-film gold rows.

**Tests and mutants.** Six tests cover the round: within ±1 and the gold step, the film-time
error, and four for the order fallback (null equality, located mass unchanged, the frame
restriction, and the row's own evidence left out). Mutants M58–M65 each fail their named test
(`mutations/r5-20261117.json` at a482fa3f2: 65 mutants, all caught). Seven discriminate; M58
is a regression guard, and M65 is the exactness mutant for the null producer.

### Cold review of rounds 4 and 5

Reviewed the results commit `4a3d40271` and replayed harness `a482fa3f2` on development data
from cache. The round-4 protocol (`38b5be76e`) and round-5 rule (`ac001ee6e`) precede their
respective implementations and outcomes in Git. The round-5 implemented rule places work
and scene-set mass inside its named support in addition to placing unassigned mass.

- **Reproduced.** FilmFestival sample 1 scene loss was 1.0707 → 0.7977 bits and Sherlock
  1.0651 → 0.7482. Each improved for 10/10 participants. Exact sign-flip p was 0.00195
  two-sided and 0.00098 one-sided. Out-of-order deltas were +0.0060 and −0.0471 bits. An
  independent reconstruction checked 963,998 scored-support probabilities over both
  FilmFestival samples and Sherlock, with no loss of directly located scene mass or escape
  from named valid work/scene-set support. The documented title-only work residuals remained
  outside valid scene support. The null control matched the order prior exactly.
- **Alpha selection.** The scored participant's transition fit excludes its labels
  (`cs2/round4.py:320`, `cs2/decoder.py:182`). The alpha objective excludes its score but uses
  other participants' forecasts fitted with its labels (`cs2/round4.py:345–357`). Nested
  refits that exclude the outer held-out participant throughout pick the same alpha in every
  FilmFestival sample-1 fold and reproduce the headline loss. Sherlock uses the transferred
  FilmFestival alpha of 0.8 at gold-row grain. Freeze a training-only alpha and transition
  fit for a new cohort. The leave-row-out calculation skips the row's own likelihood.
  Neighbouring producer outputs can still have overlapping input context.
- **Row boundaries.** The median-width replay reproduces gains of 0.290 and 0.297 bits.
  Fixing width at 7 s in both corpora gives gains of 0.290 and 0.442 bits. The sensitivity
  removes each gold row's exact span but still omits windows with no gold overlap
  (`cs2/run.py:147–171`). The gain survives the change of spans. These runs cannot isolate
  how much gain comes from gold coverage or the coders' labels.
- **Descriptive checks.** Independently recovered participant-mean within-one-scene rates
  of 93.64% and 93.49%. Located-only film-time means were 5.24 and 27.73 s, conditional on
  a single-clock gold work with located mass there. The distance calculation distributes
  scene-set mass within its set. The sample-1 pre-registration draft's development gains
  reproduce to rounding at 1.43 bits on gold spans and 1.40 bits on 7 s windows.
- **Synthetic checks.** 72/72 tests passed. Mutants M58–M65 each failed its named test.
  M65 failed only the exact null-equality test.

The development numbers support retaining the bounded fallback as a secondary forecast.
A sealed run needs a separately frozen training and evaluation recipe. The earlier
gold-context fitted and ceiling comparisons use the preceding gold label by design.

### Harness replay declarations before freeze

The archived round-5 replay applies the following rules. They can affect a row even with
identical recorded replies.

- The harness multiplies pass-2 member weights by the leading pass-1 frame weight
  (`cs2/ff_producer.py:797`). Record this as a forecast calculation with both inputs.
- The harness supplies weight 1 for an unframed position (`cs2/ff_producer.py:882–887`).
  The strict import keeps its weight unavailable.
- The harness resolves equal-length innermost ties by reply order
  (`cs2/ff_producer.py:1055–1060`). Before freeze, make these unavailable as in ADR 0031 and
  rerun affected development rows.
- The default broad allocation retains a multi-scene set within one work
  (`cs2/projections.py:124–140, 168–178`). Preserve the set through projection and replay.

The conversion also clips or normalizes frame weights, treats a missing pass-2 reply as
uncovered, and pools the two passes for scoring. ADR 0032 revision 5 records the strict
library treatment and the replay comparator's responsibilities. Framed metamemory without
an anchored address and unframed commentary contribute ledger counts. Searching between
works, ending recall and off-task speech contribute the three non-work states.

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
