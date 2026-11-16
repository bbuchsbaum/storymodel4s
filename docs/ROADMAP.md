# Roadmap

**Recovery checkpoint, 2026-11-14:** the [restart receipt](refactor/evidence/recovery-20261114/README.md)
records preservation, tracker portability, integration qualification and the remaining queue.
Sherlock and a third synthetic study now use the common recorded pipeline; the
[slice-5 receipt](refactor/evidence/common-pipeline-slice5-20261115/README.md) records qualification
for `bd-01M7E3G6TFG1YTWM9NTSZ4NRKX`. The [live-execution slice](refactor/evidence/live-study-20261115/README.md)
now adds recorded exchanges, bounded retries, interrupted-run resume and offline replay through
that same contract. Synthetic HTTP qualification, frozen replay parity and the full hosted CI
matrix are complete. A [bounded OpenAI smoke](refactor/evidence/live-study-20261115/provider-smoke.json)
now verifies one real request and credential-free replay of identical mappings on two synthetic
units. This qualifies execution and reproducibility, with no new scientific-accuracy claim.

The [ordinary-transcript entry point](guide/ordinary-transcripts.md) now prepares UTF-8 files
with the existing segmenter and carries canonical text, exact spans and source provenance into
that same execution path. Its [engineering receipt](refactor/evidence/transcript-intake-20261115/README.md)
tracks verification. Full delivery 4 still needs a supplied-versus-automatic comparison on
verified common transcript coordinates for both corpora (`bd-01M7H69QWE0NKSNB8XWW6MC02H`).
Independent usability and prospective measurement qualification remain open.

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

**Revised 2026-11-16.** After the scene preview, rich structure arrives as a detail layer
(DL1-DL6) carried in the same bundle: recall detail atoms, a source cast and row record, detail
binding with graded fidelity, derived coverage and omission, an order-independent condition, and
who-did-what. Each field set is qualified against one joint human coding effort. The full event
graph (E1 beyond the cast record, E3, E4) becomes retained research that starts only when a
measured failure or a named analysis needs it. See the
[detail-mapping plan](plans/2026-11-16-detail-mapping-plan.md).

## Next milestone (2026-11-10): scientific preview

A researcher supplies timed film descriptions and recall transcripts, gets auditable
scene-localization results from a documented command, can inspect every assignment and failure,
and can reproduce the saved analysis without another model call. The deliveries, the claim
boundary and the current results are in the
[working plan][preview-plan], in its 2026-11-10 revision.
M1 and M2 below are done. M3 and M4 are replaced by the detail layer, whose joint coding
rubric is written alongside preview delivery 5.

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
- **M3 and M4** (speaker-labelled evidence; who-did-what in recurring scenes) were superseded on
  2026-11-16 by DL2 and DL6 of the detail layer.
- **Detail layer, DL1-DL6** ([plan](plans/2026-11-16-detail-mapping-plan.md)): each adds named
  bundle fields and has one exit. DL1, DL3, DL4 and DL6 have fresh-item exits against blind
  human coding; DL2 and DL5 have engineering exits.
  - DL1: recall detail atoms with exact spans and internal/external kinds.
  - DL2: a source cast and row record, with participants and dialogue speakers.
  - DL3: detail-to-row binding with graded fidelity, ported from the graded verifier.
  - DL4: coverage and omission, derived from DL3.
  - DL5: an order-independent condition, to decide whether order measures can be supported.
  - DL6: who-did-what in recurring scenes, as the hard test of DL2 and DL3.

E1-E4 below describe the full event program. DL2 is E1's first step and DL1 and DL3 are E2's;
the rest is retained research, started only when a measured failure of the detail layer or a
named analysis (for example causal centrality) needs it. E4's structured decoding is pursued
only where an experiment shows it helps.

Each milestone has one exit criterion. Engineering metrics are measured on the Sherlock
development partition, without pre-registration; fresh-item exits use a frozen protocol. Each
metric is reported per stage: retrieval, local ranking and decoding.

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

*DL2 is this layer's first step. The rest is retained research (revised 2026-11-16).*

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

*DL1 and DL3 are this layer's first steps. The rest is retained research (revised 2026-11-16).*

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

*Retained research (revised 2026-11-16): started only on a measured detail-layer failure.*

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

*Retained research (revised 2026-11-16): started only on a measured detail-layer failure.*

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
- multi-positive event references from the joint coding effort (a coding sheet, or the
  rebuilt review workbench if the timing pilot needs it).

**Exit:** a held-out report of local and final metrics, with regressions listed.

## Finish line

"Done" for this phase means all of the following (revised 2026-11-16; the earlier text required
the E1-E4 exits on Sherlock development):

- The scene preview's deliveries are met.
- The DL1, DL3 and DL4 fresh exits and the DL2 engineering exit are met, and DL5 has a
  recorded claim-boundary decision.
- E5 has been run once on untouched Sherlock and once on a second film, with the results
  reported as they come. The untouched Sherlock read is the same pre-registered read as the
  DL fresh exits, not a second use of that data.
- A researcher can run one command from video or annotations plus recall transcripts to a
  bundle. It links each recall detail to film rows and cast members with a graded fidelity, and
  carries the stage report and the claim boundary.

## Side tracks

- **1.0 publication lane** (`refactor/RELEASE-QUEUE.md`): proceeds only where it doesn't slow
  E0-E3. Its API-stability work must not freeze types that E1/E2 will change.
- **Review workbench**: the joint coding effort starts from a coding sheet generated from
  bundles. Rebuild the workbench only if the timing pilot shows the sheet is too slow.
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

1. ADR 0029: adopt NMP as the proposal profile for events (now a prerequisite of DL2's
   producers; full E1 is retained research).
2. Producer budget and transport: subscription CLIs versus a paid API for DL1-DL3 runs and any
   later bulk event compilation.
3. Release lane priority relative to the detail layer.
4. Whether the historical documents should move physically into `docs/archive/` (links would
   be rewritten), or stay in place with banners as now.
5. Ratify ADR 0001 (status "Proposed") and ADR 0019's 2026-09-23 amendment.
6. Rule 7 and missing evidence: E3 replaces constant 0.5 terms. Retiring the imputed-neutral
   design (`MayBeImputed`, `missingSemantic` in `align`) needs its own ADR.
7. An ADR for the detail layer's public vocabulary (detail kinds, fidelity grades, cast
   identifiers), recording the rejected alternative of building the full event graph first.
8. Validation data for the detail layer's fresh items, and who codes them on what budget.
9. Whether evidence binding and the claim-assessment candidates in review are absorbed into
   DL2 and DL3 or kept as matched comparisons.

[preview-plan]: plans/2026-11-09-categorical-mapping-and-transfer-plan.md#revision-2026-11-10-the-scientific-preview
