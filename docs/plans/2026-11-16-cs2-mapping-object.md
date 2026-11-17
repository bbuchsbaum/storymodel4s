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
  never a destination, unless the reply anchors a reference to film content. Off-task speech is
  the non-film state `off_task`.
  - **What the cached replies cannot separate:** a pass-1 `work` unit may name a film or only
    evaluate one. Two variants are reported: `identifying` (default), where a framed or
    positioned `work` unit is a work-level reference, and `position-only`.
  - **Crosswalk to the gold's codes** (the gold does not define this vocabulary):

    | Our talk / address | FilmFestival gold code |
    |---|---|
    | narration with a scene address | specific (scene set) |
    | identification (work-level) | film-only (`movie*1000`) |
    | metamemory inside a framed film | film-search (scene -1) |
    | metamemory with no framed film | memory search (-1) or gave up (-2) |
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
- **The two-level outline renders an occurrence's parts in order** (`part-order`). The first
  Sherlock two-level run used `start-only`, which sorted rows by seconds only and so interleaved
  Sherlock's two parts. It is kept for exact replay. The two orders agree whenever each
  occurrence has one segment, which is the FilmFestival case.

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
