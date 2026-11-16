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
person's recall of it, and the mapping between them. Annotations, speech, captions, frames and
identity evidence inform a reusable source account built independently of recall. Recall words
and discourse inform the recall account; mapping relates the two. Embeddings and temporal order
are evidence channels, not the representation. See
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
who-did-what. Engineering readiness and scientific qualification are separate. The full film
and recall event graph remains a delivery commitment: the detail layer feeds the existing film
compiler, followed by event/context and relation production, exchange and readouts. E3 typed
matching and E4 structured decoding remain experimental consumers, not construction gates. See the
[detail-mapping plan](plans/2026-11-16-detail-mapping-plan.md).

The detail layer and the graph expose their results through one
[measurement and projection contract][contract]. It keeps each annotation and assessment at its
original granularity and derives analysis views from them: a recall-indexed feature table, a
recall-source correspondence table and a source-indexed participant summary, each with declared
aggregation, timing and uncertainty. It prescribes no analysis, temporal unit or list of measures.

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
  bundle fields and has one engineering acceptance artifact. DL1, DL3, DL4 and DL6 retain
  separate fresh-item qualification criteria against blind human coding in the joint evaluation.
  - DL1: recall detail atoms with exact spans, kinds, memory addresses and discourse functions;
    internal/external labels and counts are derived under a named scoring policy.
  - DL2: a source cast and row record, with participants and dialogue speakers.
  - DL3: detail-to-row binding with graded fidelity, ported from the graded verifier.
  - DL4: coverage and omission, derived from DL3.
  - DL5: a unit-only mapping condition, with an input-policy audit, permutation check and direct
    readout-recovery evidence to decide whether order measures can be supported.
  - DL6: who-did-what in recurring scenes, as the hard test of DL2 and DL3.

The [contract][contract] is shared by DL1-DL6 and the graph slices; it adds no bundle fields of
its own. Its engineering exit derives the three analysis views from the same saved synthetic
artifacts without a model call, with discriminating and refusing controls. It carries the
temporal-query, exposure and measure semantics of ADRs 0019 and 0022 to the categorical and
detail bundle, and reuses the `features` tracks and the recall-onset export.

E1 and E2 deliver the full event representation, with DL2 and DL1/DL3 as their first usable
steps. The [construction sequence](plans/2026-11-16-detail-mapping-plan.md#full-event-graph-delivery)
reuses F1-F6, identity/speech contracts, V1 exchange and the terminal walkthrough. E3/E4 test
whether additional matching or decoding structure helps; neither is required to construct a graph.

Each implementation milestone has one acceptance artifact. Engineering metrics are measured on
seen or synthetic inputs, without pre-registration; scientific claims use a frozen protocol. Each
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

*Required delivery. DL2 is the first usable step (reaffirmed 2026-11-16).*

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
- **Decision.** Accept the bounded NMP proposal profile before its producer integration. F0's
  claim-status choice A is already recorded; do not reopen it. Construction fixtures can use
  existing checked proposal inputs while the producer-profile decision is prepared.

**Engineering exit:** one compiled Sherlock graph artifact passes the public construction,
exchange and independent-reader path. It includes a receipt for every source row (represented,
unsupported or unresolved), exact evidence and a functioning path for each declared family:
events/states, participants/roles, places, contexts/speech, hierarchy and temporal/causal/reference
relations. Positive and refusal witnesses cover each family. Missing information is not filled
by inventing an event. Coverage and the three-scene owner review accompany the artifact; counts
or spot-checks alone do not establish event/relation accuracy. E5 qualifies those claims.

### E2 — Recall event layer

*Required delivery. DL1 and DL3 are the first usable steps (reaffirmed 2026-11-16).*

Parse recall units into the same event schema with the same producers:

- participants, linked to the film's cast through the evidence-binding work
  (`bd-01M5MR1KTXEP898CTN2KE3J219`);
- the action or state;
- the place;
- reported speech.

This becomes the primary recall-side input to typed matching, replacing the regex
`PropositionSketch` there. The lightweight route (anchored units, no event graph) stays available
for basic localization, as VISION commitment 3 requires.

**Engineering exit:** one paired source/recall artifact preserves detail-to-event grouping,
summaries spanning events, external content, unresolved references and exact spans through the
common bundle and independent reader. The event bridge uses the same schema and contexts as
the source account; an atom is not automatically a separate event. DL6 supplies the recurring-
scene participant/action witness. Corpus-scale coverage and human accuracy are reported in E5.

### E3 — Typed matching

*Experimental consumer of the delivered graph. Its outcome does not gate E1/E2 construction.*

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

*Experimental consumer of the delivered graph. Its outcome does not gate E1/E2 construction.*

Feed entity threads, causal links and event hierarchy into the graph-HSMM transitions, which
are inert today.

**Exit:** final scene-exact at 75% or above on development (now 61%), with the within-scene
precision check reported.

### E5 — Validation

The existing human protocol/material/execution lane owns one joint frozen evaluation for the
preview, DL1/DL3/DL4/DL6 and any claimed graph/readout accuracy. Freeze every included method
before reading the reserved data. New participants, new films and new input conditions are
different claims. Previously used FilmFestival data is development/transfer evidence, not fresh
confirmation. Coder recruitment and validation-data access retain their existing approval gates.

**Study exit:** one independently recomputed report records each criterion, denominator,
uncertainty, failures and a supported/failed/inconclusive claim decision. A report can complete
without a favorable result; failed criteria do not become scientifically qualified. Implementation
beads do not wait for this report before their consumers can be built.

## Finish line

**Engineering delivery:** a usable representation for film recall studies (owner, 2026-11-16).
For each of three studies (Sherlock, FilmFestival and Friends), one documented command takes
timed film descriptions and recall transcripts through that study's input adapter and produces
the common bundle. The [contract][contract]'s three analysis views are derived from the saved
artifacts without a model call and recomputed by an independent reader. Formats, readers and
scorer are the same for all three; each study reports what is unavailable for it. The
[demonstration][finish] uses development material only and leaves Friends' sealed test side
unopened.

It requires the preview's engineering workflow, DL1-DL6 implementation artifacts, the contract,
E1/E2 graph construction, film exchange and the terminal graph/readout walkthrough to be complete.
A researcher can use one documented command with pinned film evidence and recall inputs, inspect
the common bundle and graph tables, and reproduce the outputs offline. Every required family has
a functioning admission path and explicit coverage/gaps. Existing V1/E0 probabilistic-profile
proof obligations remain intact; categorical bundles do not invent posterior or flow measures.
This finish permits explicitly experimental use and does not claim scientific accuracy.

**Scientific qualification:** preview measurement qualification, E5 and the DL1/DL3/DL4/DL6
criteria are evaluated in one joint held-out read after every included implementation is frozen.
The untouched Sherlock remainder is not used twice. Unfamiliar-film generalization needs an
eligible unfamiliar film; previously used FilmFestival participants cannot supply that claim.
Every required scientific criterion must actually pass for the corresponding qualified finish.
A negative or inconclusive report completes the study work but leaves that qualification unmet.
Independent usability remains a separate executed requirement of the preview.

## Side tracks

- **1.0 publication lane** (`refactor/RELEASE-QUEUE.md`): proceeds only where it doesn't slow
  the detail and graph construction path. Its API-stability work must not freeze types that E1/E2 will change.
- **Review workbench**: the joint coding effort starts from a coding sheet generated from
  bundles. Rebuild the workbench only if the timing pilot shows the sheet is too slow.
- **storyatlas4s**: consumes mapping and event outputs. The sibling seam rule in AGENTS.md
  applies.

## Branch triage

The [November 8 triage](branch-triage-20261108.md) is a historical queue. Use the recovery
receipts linked above, current Git ancestry/worktrees and live Mote when adopting remaining
candidates; do not repeat a historical merge or archive instruction without checking its state.

## Open decisions

1. The bounded NMP proposal profile (planned ADR 0029) and detail/graph vocabulary: select
   only the semantic gaps needed by this delivery. The owner decides the ADRs.
2. Producer budget and transport: subscription CLIs versus a paid API for DL1-DL3 runs and any
   later bulk event compilation.
3. Release lane priority relative to the detail layer.
4. Whether the historical documents should move physically into `docs/archive/` (links would
   be rewritten), or stay in place with banners as now.
5. Ratify ADR 0001 (status "Proposed") and ADR 0019's 2026-09-23 amendment.
6. Rule 7 and missing evidence: E3 replaces constant 0.5 terms. Retiring the imputed-neutral
   design (`MayBeImputed`, `missingSemantic` in `align`) needs its own ADR.
7. The detail-layer ADR must record the core-account/derived-analysis split and the rejected
   alternative of requiring a completed graph before useful detail output. Internal/external is
   a scoring projection; it is not a primitive kind or a fidelity judgment. The same ADR covers
   what the contract adds to ADRs 0019 and 0022: joint and broad reference meaning, new feature
   targets, a level-holding allocation, and whether an empirical response frequency becomes a
   named measure kind.
8. Validation data for the detail layer's fresh items, and who codes them on what budget.
9. Which exact reviewed identity and claim-assessment components satisfy DL2/DL3 and the event
   bridge. Reuse them in the common path; retain alternative methods only as named comparisons.
10. Friends: the basis for running a model on its development recall. Its source set is still
    "proposed", with the licence and REB basis open.

[preview-plan]: plans/2026-11-09-categorical-mapping-and-transfer-plan.md#revision-2026-11-10-the-scientific-preview
[contract]: plans/2026-11-16-detail-mapping-plan.md#measurement-and-projection-contract
[finish]: plans/2026-11-16-detail-mapping-plan.md#finish-line-a-usable-representation-for-film-recall-studies
