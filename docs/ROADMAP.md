# Roadmap

The one current plan, adopted 2026-11-08 (Mote epic `bd-01M6Y7MWZGP29TNDJ90SG36A45`). It
supersedes the direction in `refactor/PLAN.md`, the best-mapper plan and every dated "next
steps" list. Mote holds status; this page holds direction, milestones and exit criteria.

## Goal

The goal is a rich, structured and symbolic account of three things: a film or story, a
person's recall of it, and the mapping between them. All available information informs both the
representations and the mapping: annotations, speech, captions, frames, identity, and recall
discourse. Embeddings and temporal order are evidence channels, not the representation. See
[VISION.md](../VISION.md#the-event-layer-is-the-core).

## Direction (revised 2026-11-09)

Direct LLM mapping is a primary mapping method. The next milestone is a reproducible categorical
mapping artifact with independent inspection and source-support lookup. Transfer evaluation
follows on verified reference data. Rich source and recall event representations remain a core
objective and are developed through bounded, evaluated capabilities. Structured decoding is
introduced where experiments show a benefit. See the
[working plan](plans/2026-11-09-categorical-mapping-and-transfer-plan.md).

## Where we are (2026-11-08)

- **Direct LLM mapping reaches 75-79% narrative scene agreement** on Sherlock development
  ([mapper plan](plans/2026-11-08-sherlock-mapper-plan.md)), and the library replays it from
  recordings with no model call. This measures narrative scene coding only: referent scenes,
  cited rows, who-did-what and within-scene detail are unmeasured. The model reads the
  surrounding recall, so its labels are not an independent measure of recall order. Adding an
  order prior did not help.

- **Accuracy has not moved since September.** The last registered Sherlock development figure is
  61% scene-exact, participant-averaged (2026-09-23). The earlier pooled figures (63.8%, 65.2%)
  are different estimands of the same pipeline.
- **Content evidence is weak, and order does most of the work.** A
  [decomposition](refactor/evidence/mapping-decomposition-20261108/README.md) of that run gives:
  - local content evidence picks the right scene for 33.7% of units;
  - time-order decoding lifts that to 61%;
  - the right scene is never among the 16 retrieved candidates for 25% of units;
  - a non-source state outweighs every film candidate locally for 86% of units.
- **The film side is thin.** On Sherlock it is 1,000 annotation rows and 50 scenes. It has no
  participants with identity, no roles, no causal links and no dialogue claims (ADR 0007).
  - The entity and proposition cost terms take a constant 0.5 when their evidence is
    missing, which on Sherlock is nearly always.
  - The structural transition kinds have nothing to act on.
  - Legacy scene captions, used as embedded scene text, added nothing measurable (+0.8 pp, CI
    spans zero).
- **Symbolic machinery exists but is not on the mapping path.** It was built as types and
  experiments but never connected:
  - the story ontology;
  - D1A source-to-story stages;
  - cross-modal identity (ADRs 0024-0028);
  - speech promotion;
  - NMP proposals;
  - LLM claim assessment.

The NN03 u92 failure (the recalled body at the crime scene) is the typical case, not an outlier.

## Milestones

The near-term sequence, from the working plan:

- **M1. Categorical mapping artifact** (port step 4): categorical decision support in `align`,
  then recorded responses to a typed, saved bundle that a short Python reader can inspect,
  with every unit accounted for.
- **M2. FilmFestival transfer evaluation**: verify scene correspondence and gold joins, freeze
  the method, report film identity and scene agreement separately. These participants were
  used in development, so it is a transfer evaluation, not a confirmation.
- **M3. Evidence improvements**: speaker-labelled dialogue first, each a new packet version
  scored against the frozen baseline.
- **M4. First rich-event capability: who-did-what in recurring scenes**, on a bounded set of
  difficult scenes, judged on distinctions scene labels cannot make.

E1-E4 below describe the full event program. Each layer is now built as bounded capabilities
that must show their value (grounding, analysis, inspectability or saved computation; scene
accuracy is one possible benefit). E4's structured decoding is pursued only where an
experiment shows it helps.

Each milestone has one exit criterion. Engineering metrics are measured on the Sherlock
development partition, without pre-registration. Each metric is reported per stage:
retrieval, local ranking and decoding.

### E0 — Consolidate (now)

- Make one set of docs: this page, [VISION.md](../VISION.md), a short AGENTS.md, the
  [ADR index](adr/README.md) and the [docs map](README.md).
- Triage the unmerged branches (see below). Merge or harvest what serves E1-E4, and archive the
  rest under `archive/` tags.
- Turn `decompose.py` into a standing **stage report** for any mapping run: candidate recall@k,
  local top-1/top-k, decode rescue and loss, non-source dominance.

**Exit:** `main` carries the consolidated docs and the harvested work, and the stage report
runs on `main`.

### E1 — Film event layer for Sherlock

Compile Sherlock into typed, time-bounded events. Each event has:

- participants, with identity that persists across shots, mentions and modalities;
- an action or state;
- a place;
- speech held in its own context;
- part-whole, temporal and causal links.

How it is built:

- **Producers.** LLM producers (Claude Code, Codex and DeepSeek through NMP, phase 4 of the
  [NMP plan](plans/2026-10-24-nmp-integration.md)) propose events from windows of:
  - annotation rows;
  - Whisper speech;
  - captions;
  - frames, where they help.
- **Admission.** Proposals are admitted by the deterministic stages of the
  [film compiler plan](plans/2026-09-20-film-compiler-phase-plan.md). The film children of
  `bd-01M2T32SGVJXR2RT3RTS2DCN9P` move from P3 to P1.
- **Identity.** Cross-modal identity follows ADRs 0024/0027, generalized beyond the body scenes.
- **Speech.** Speech is promoted under ADR 0026.
- **Decision.** Accept ADR 0029 (the NMP proposal profile) first.

**Exit:** a compiled Sherlock event graph with a coverage report. It must show:

- every annotation row covered by at least one event;
- the share of events with an identified participant;
- the speech and causal link counts;
- an owner spot-check of three scenes.

### E2 — Recall event layer

Parse recall units into the same event schema with the same producers:

- participants, linked to the film's cast through the evidence-binding work
  (`bd-01M5MR1KTXEP898CTN2KE3J219`);
- the action or state;
- the place;
- reported speech.

This becomes the primary recall-side input to typed matching, replacing the regex
`PropositionSketch` there. The lightweight route (anchored units, no event graph) stays available
for basic localization, as VISION commitment 3 requires.

**Exit:** all development participants parsed, with an owner spot-check of two participants.

### E3 — Typed matching

- **Retrieval.** Take the union of the embedding/BM25 candidates with a structured index keyed
  by participant identity, action and place.
- **Local evidence.** Use typed terms: shared participant, action or state agreement, place, and
  reference to an utterance. Similarity is one channel among them.
  - Missing evidence is unavailable, never a constant cost (AGENTS.md rule 7).
  - Codex's semantic-only reference profile (`codex/scoring-evidence-20261021`) is the baseline.
  - An LLM pairwise support judge over the frozen candidate pool is an optional channel.

**Exit:** both conditions, on development, with the decoder unchanged:

- candidate miss below 10% (now 25%);
- local top-1 scene at 55% or above (now 33.7%).

### E4 — Structure-aware decoding

Feed entity threads, causal links and event hierarchy into the graph-HSMM transitions, which
are inert today.

**Exit:** final scene-exact at 75% or above on development (now 61%), with the within-scene
precision check reported.

### E5 — Validation

These are the only steps that use the full pre-registration apparatus:

- one untouched-Sherlock read;
- FilmFestival, the second film corpus;
- datasets not yet seen;
- the owner's within-scene human lane;
- multi-positive event references from the (rebuilt) review workbench.

**Exit:** a held-out report of local and final metrics, with regressions listed.

## Finish line

"Done" for this phase means all of the following:

- E1-E4 exits are met on Sherlock development.
- E5 has been run once on untouched Sherlock and once on a second film, with the results
  reported as they come.
- A researcher can run one command from video plus annotations plus recall transcripts to a
  mapping. That mapping links each recall event to film events through typed evidence, and
  carries the stage report.

## Side tracks

- **1.0 publication lane** (`refactor/RELEASE-QUEUE.md`): proceeds only where it doesn't slow
  E0-E3. Its API-stability work must not freeze types that E1/E2 will change.
- **Review workbench**: rebuild its lost clarity revision when E5 needs human references, not
  before.
- **storyatlas4s**: consumes mapping and event outputs. The sibling seam rule in AGENTS.md
  applies.

## Branch triage

See [branch-triage-20261108.md](branch-triage-20261108.md). In order:

1. Harvest `mapping-audit`'s model-asset commits.
2. Merge `claude/integrate-codex`, which carries the scoring correction, claim assessment and
   mapping summary v2, after a full gate.
3. Harvest `identity-feasibility`.
4. Archive everything else as `archive/<name>` tags.

## Open decisions

1. ADR 0029: adopt NMP as the proposal profile for events (E1 prerequisite).
2. Producer budget and transport: subscription CLIs versus a paid API for bulk event compilation.
3. Release lane priority relative to E1-E3.
4. Whether the historical documents should move physically into `docs/archive/` (links would
   be rewritten), or stay in place with banners as now.
5. Ratify ADR 0001 (status "Proposed") and ADR 0019's 2026-09-23 amendment.
6. Rule 7 and missing evidence: E3 replaces constant 0.5 terms. Retiring the imputed-neutral
   design (`MayBeImputed`, `missingSemantic` in `align`) needs its own ADR.
