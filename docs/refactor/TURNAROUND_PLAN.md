# Storymodel4s: delivery turnaround plan

**Prepared:** 18 September 2026, America/Toronto.

**Audit baseline:** `bbuchsbaum/storymodel4s` at `1113f96864a38a2869e49d9c8c5d4e2dc43f5d10`.

**Status:** consultant recommendations and proposed replacement documents; no repository or tracker writes have been made.
**Companion specifications:** `ANALYSIS_CONTRACT.md`, `DELIVERY_BACKLOG.json`, `AUDIT_EVIDENCE.md`, `vision.md`, and `mission.md`.

## 1. Executive decision

Do not rewrite the project. Converge its existing work into one research product.

The product's central object should be a versioned, checked **RecallMapping**: a mapping from identifiable recall words/passages and measured recall times to identifiable source narrative units and their text/playback support, with alternatives, explicit non-assignments, derivation, and uncertainty semantics.

The source story model remains important. The change is that successful delivery is judged at the source-model-to-analysis boundary, not by the completeness of the ontology or the number of validated internal types. Simple localization must be usable before every semantic enrichment is available. Richer story modeling must feed the same mapping product, not become a second product with a different export convention.

The existing film-capable library commitment stays on the stable-release path. An annotation-assisted analysis preview should ship earlier, but it does not complete D1A-film or D1B. The separate benchmark ambition of being the best mapper must not postpone an honest, usable tool indefinitely.

## 2. What this audit establishes

This is a targeted code and delivery audit, not a line-by-line security review or a reproduced benchmark. It inspected the repository structure, build, mission/vision, approved and proposed delivery plans, the text pipeline, source and alignment boundaries, the general video mapper, posterior export, selected tests, the scorer, and live workflow metadata. The repository was not compiled here; local research data and private worktrees were not available. Code paths were inspected statically. Historical performance and local gate counts are identified as repository reports rather than reproduced findings.

### 2.1 Keep these assets

The project already has exact source/edition/timebase types, explicit missingness, a sparse alignment model, source and external states, graph-conditioned inference, record/replay patterns, typed validation, source-side provenance, and a real timed-annotation mapping path. The inspected tests exercise word addressing, missing timing, raw uncertainty, and structured decoding. This is not an empty scaffold. [E1–E8]

### 2.2 The delivery failures are at boundaries

| Finding | Consequence | Required intervention |
|---|---|---|
| Vision and mission are still text-centered; mission/build descriptions disagree about existing orchestration and providers. | A contributor can follow an obsolete account of the product. | Replace vision/mission; maintain implementation status separately and attach evidence to claims. |
| `StorySourceView` reads canonical text and drops nodes without textual source support. | Existing multimodal primitives do not by themselves create a film-capable narrative alignment path. | Complete D1A-types and D1B; remove text coordinates as a universal aligner requirement. |
| General video orchestration, timed source view construction, lexical blending and decoding live under `embed-bench`. | Benchmark settings and I/O act as the product API. | Extract pure logic into existing library modules and orchestration into `pipeline`; benchmarks consume that API. |
| A decoded TSV anchor can be paired with the raw argmax's confidence. `PosteriorSidecar` already documents and partially resolves this. | A downstream analyst can treat another target's mass as confidence in the exported choice. | Promote the sidecar's distinction into a versioned, checked public result; preserve legacy outputs as historical artifacts. |
| `gold_scene.py` loads only rows with an onset and numeric group, and paired comparison intersects participants without matching unit IDs. | Abstentions/failures can disappear from denominators; comparisons need not evaluate the same units. | Freeze an input/gold manifest and refuse identity mismatches before further model comparisons. |
| `TimedSourceView` builds group media hulls from the loci that remain after missing values are dropped. | Partial grounding can look like complete scene support. | Preserve coverage, interval sets, and explicit display hull semantics; reject or mark incomplete grounding. |
| `StoryPipeline` writes files sequentially; exit zero denotes a completed build, not a validated model. | Partial output directories and readiness misunderstandings are possible. | Atomic/completion-marked publication; separate execution, structural validity, and requested analysis readiness. |
| Current workflows have unsuccessful checks; older plans also contain now-stale statements about code not being pushed and CI never running. | Neither an old green local log nor an old hygiene narrative establishes current release readiness. | Reconcile status to the audited commit and obtain a clean-machine gate; inspect failure cause before assigning it. |

Evidence and precise file locations are in `AUDIT_EVIDENCE.md`. The hull and duplicate-ID findings are static control-flow observations and should receive executable regression witnesses before fixes are declared complete. [E2–E11]

### 2.3 Do not confuse the available evidence

The September 18 mapper plan reports 63.8% pooled exact-scene accuracy on Sherlock across 15 participants, and FilmFestival film-identity figures of 67.5% with fill versus 81.5% without fill. These are different tasks and development/exposed-data results, not a common validation score. The plan reports that substantial Sherlock gains came from decoding rather than the core aligner. Its baselines, calibration, and holdout warnings are material. None of those numbers was recomputed in this audit. [E12]

The September 17 session record reports extensive local passing tests on earlier revisions. Live checks on the audited commit are nevertheless unsuccessful. Report both facts, without treating unsuccessful workflow metadata as proof that a source-code test failed. The cause remains unverified. [E10–E11]

## 3. Product charter and scope

### 3.1 Required user journeys

**Written story:** load text, build or import its narrative representation, load recall, map it, export word-addressable decisions and alternative mappings, and reproduce the run.

**Annotated video:** load a pinned source edition or a honestly labeled annotation timeline, ingest time-anchored descriptions, load recall, map it, and obtain valid source coordinates. Edition playback claims require a verified annotation-to-edition relation.

**Compiled film story:** compile anchored film observations into the narrative model, expose it through the shared alignment source boundary, map recall, and export exact playback support. This fulfills the approved D1A-film/D1B library requirement. The input route must declare whether observations were human-authored, transcript-derived, or audiovisual-derived.

**Downstream analysis:** an analyst who has not read Scala internals loads the exchange bundle in R/Python, produces a discrete recall trajectory, computes a clearly named soft assignment summary, and builds an optional time-bin projection under an explicit policy.

### 3.2 What does not gate the first useful preview

Complete event ontology coverage, complete causal/mental-state reconstruction, a polished external viewer, a new transport solver, a universal video-understanding model, and a world-best benchmark claim are not required for an annotation-assisted localization preview.

### 3.3 What remains on the stable library path

Preserve the owner-approved D1A-types S0–S4c sequence, D1A-film, and D1B. One bundle per story model and a separate checksum-bound film proposal surface remain the design decisions. Do not silently adopt the September 18 plan's proposed P1 reduction that would move film compilation out of 1.0. [E13]

The new recommendation is an **earlier generic analysis preview** and a mapping exchange format. It does not require the previously deferred V1 complete film-model file format or E0 corpus-specific terminal interface to become stable immediately. A mapping export carries the checked source target/support view and provenance; it need not serialize the entire film `StoryModel` object graph.

## 4. Target system and code ownership

The tower of abstractions is:

`pinned source + recall inputs → exact addresses → grounded source units + recall units → mapping measures → explicit decisions/projections → analysis artifacts`.

The detailed contracts are in `ANALYSIS_CONTRACT.md`. The essential design is one result with multiple named derivations, not one opaque scalar score or several incompatible exported versions of reality.

| Existing area | Responsibility after convergence | Concrete work |
|---|---|---|
| `core` | Identity, exact coordinates, grounded support, checked source atlases | Finish approved D1A core substrate; reuse `SourceBundle`, axes, rational timebases, anchor support and composition. |
| `corpus` / `corpus-intake` | Corpus contracts and JVM ingestion | Keep corpus-specific schemas/adapters here; standardize recall words and anchored source inputs. Do not make `align` depend on XLSX or corpus names. |
| `media` | Media observation and coordinate evidence | Keep process/runtime responsibilities out of portable domain types; connect observations to the proposal surface with explicit origin and precision. |
| `story` / `document` / `acquire` | Narrative modeling and checked compilation | Implement approved typed support and source envelope changes, then film compilation. Annotation-assisted localization need not traverse every semantic stage. |
| `recall` | Source-blind units, word membership and timing projection | Extract `RecallTiming` from bench; preserve measured versus missing/onset-only timing and transcript identities. |
| `align` | Generic source bridge, sparse mapping, decision policies and pure analysis projections | Extract reusable timed-view and decode logic; complete D1B. Keep scene monotonicity a named optional policy, not a universal assumption. |
| `embed-core` / provider adapters | Feature production and reusable source-side indexes | Explicit provider selection, identities and cache inputs; no silent backend substitution. Use existing adapters where runnable. |
| `codec` | Checked mapping exchange records | Adapt the good parts of `PosteriorSidecar`; include all coordinates, identities, measures, outcomes and completeness rules. |
| `pipeline` | Public orchestration, batch, config, cache and output lifecycle | Replace environment-driven production behavior with a resolved configuration and a small typed facade. |
| `view` | Inspection projections of the same result | Consume canonical mapping output; do not recompute decoding or confidence. |
| `embed-bench` / `tools/recall-study` | Evaluation clients | Keep corpus evaluation, shuffle controls and historical serializers; remove production ownership of inference. |

No new top-level module is required initially. Use packages inside existing modules. If extracting a class creates a dependency cycle, separate its pure input/result types from its I/O wrapper rather than adding a broad service locator.

### 4.1 The public facade

Proposed operations, not current commands: `source-build`, `recall-import`, `recall-map`, `mapping-export`, and `mapping-check`. Their shared run configuration is an ordinary versioned file. A preview can expose these through an existing JVM entry point before packaging a standalone executable.

Preserve a named legacy Sherlock preset for parity. Offer retrieval and graph-based profiles explicitly. Do not make the Sherlock-tuned monotone/fill preset the silent default for arbitrary videos or nonlinear recall. Before the benchmark establishes a justified general default, require an explicit profile in the preview.

Library calls return typed results and failures rather than printing messages and returning `Unit`. Input validation, inference, export, and rendering are independently testable. The CLI is a thin adapter. No normal consumer imports `storymodel4s.bench`.

### 4.2 Source independence and source reuse

Build/freeze a source representation once, then map many participants against it. Cache features by source representation, provider/model, rendering policy, and feature specification. Recall is never allowed to rewrite the source model implicitly. A reviewed source correction creates a new version and invalidates downstream mappings. Evaluation records which source version was frozen before test inspection.

## 5. Delivery sequence and gates

Gate numbers represent acceptance order, not calendar promises. One implementation lead can execute the sequence. Evaluation and source-fixture preparation can run alongside implementation, but no other person is assumed to be available.

### G0 — Establish one truthful project baseline

**Work:** TA-01 through TA-04. Adopt the new charter; reconcile active documents and tracker state; identify a pinned current revision; diagnose live workflow failures; capture a small redistributable fixture set and historical mapping outputs; freeze scoring eligibility and stable unit IDs before another comparison.

**Proof:** a baseline manifest with repository/config/model hashes; a recorded clean checkout command and exit status or a precisely isolated unresolved environmental failure; a single status table distinguishing inspected, executed, reproduced, validated, and published capabilities; adversarial denominator tests that fail on missing/duplicate/changed unit identity.

**Exit rule:** no claim that the baseline is green without execution evidence. Engineering investigation may proceed while infrastructure is repaired, but a preview/release cannot be labeled reproduced. No further efficacy comparison uses the old fail-open scorer.

### G1 — Freeze meanings and preserve existing behavior

**Work:** TA-05 through TA-07, with D1A S0 baseline captured before any source-type refactor. Implement the initial mapping records, checked input manifest, transcript normalization/word identity, raw-versus-decoded distinction, and complete accounting. Retain old serializers solely for historical parity.

**Proof:** one hand-audited miniature example exercises text and video targets, missing timing, an external state, and argmax/decode disagreement. It round-trips through the proposed mapping codec. A transformation that swaps chosen confidence with argmax confidence must fail a regression test. A missing word or unit must not disappear through a zip, filter or map overwrite.

**Exit rule:** every field's meaning and every required key is documented. The format can remain version 0.x; its semantic meaning cannot remain implicit.

### G2 — Deliver the annotation-assisted analysis preview

**Work:** TA-08 through TA-12. Extract pure timed source construction, lexical blending and structured decision logic. Move recall timing to `recall`; create a typed pipeline entry point and resolved config; provide atomic/completion-marked output; produce the exchange bundle and one R/Python consumer example.

**Proof:** a permitted real recall and its permitted source annotations run from ordinary files to analysis tables without editing Scala or relying on undocumented environment state. A second source with a different annotation structure uses the same pipeline. Historical preset results match the frozen baseline; new-format results match the baseline semantically with a prespecified numeric tolerance. Reader tests cover source identity, clocks, missingness and duplicate IDs.

**User-visible result:** word-addressable discrete and model-measure views, raw/decoded alternatives, exact or explicitly unavailable source timing, and a readable quality report. Label this **annotation-assisted preview**, not automatic understanding of arbitrary video.

**Exit rule:** the examples use the public facade and no bench imports. The first ordinary data-analysis job is possible at this gate, before the full film compiler is complete.

### G3 — Complete the approved film-capable type migration

**Work:** TA-13 through TA-19, following the already approved plan rather than replacing it:

- S0: text/compiler/HSMM/view baselines and mutation witnesses.
- S1: ADR amendment recording approved decisions and canonical support vocabulary.
- S2: sealed source atlas, bound proposal surface, stronger evidence support checks, canonical interval union, typed support, additive evidence codec.
- S3: acquire support accessors and support-based acceptance, preserving text verdicts.
- S4a: node support and checked/fallible draft construction while preserving the text envelope.
- S4b: source envelope, identity, receipt joins, text witness, consumer migration and validator laws.
- S4c: sealed `AlignmentSource` split and alignment call-site migration.

S3 and S4a can run after S2 in either order; S4b follows both, then S4c. This ordering is an existing constraint, not a new architectural experiment. [E13]

**Proof:** all applicable S0 pins remain unchanged; foreign bundles/axes/support are refused; same proposal prose on distinct film editions has distinct identity; text-only operations require a text witness; absent text is not manufactured as an empty string or position zero.

**Exit rule:** film-capable public types exist and pass their laws, but this gate is not represented as an executed film compiler.

### G4 — Complete film compilation and the shared film alignment path

**Work:** TA-20 through TA-22. Populate the bound film proposal surface from an explicitly declared observation route; compile film evidence with the existing document/acquisition machinery; carry primary projection and coverage into D1B's source view and result/wire; run through the public facade.

**Minimum scope decisions:** keep unsupported context/causal inferences as typed gaps rather than false observations. Use one composed bundle for a multipart source. Preserve discontiguous occurrence supports and source precision. A supported film input must not require constructing the final story graph by hand in Scala.

**Proof:** an actual permitted film source plus its anchored observations compiles; recall aligns against that compiled model; exported loci resolve to the correct edition/axis. Include a visually meaningful event with no dialogue, an audible event, reordered recall, and an event with ambiguous/repeated presentation support. Use a small deterministic recorded fixture for CI and a separately recorded real-input smoke execution. Empty output or a hand-built final film model does not satisfy the compile proof.

**Origin rule:** a human-annotation path proves film compilation and annotation-assisted analysis. Only an executed media-derived observation path earns the additional `video-derived-source` capability. Do not collapse those two achievements.

**Exit rule:** the owner-approved film library end-to-end requirement is satisfied. Full film-object serialization and a sophisticated viewer remain separate from the mapping exchange artifact.

### G5 — Establish the analysis and uncertainty guarantees

**Work:** TA-23 through TA-26; much of this can be developed while G3–G4 proceed. Enforce support and measure semantics, fix empty-evidence claims where verified by a witness, implement word/time projections and missingness accounting, reconcile fidelity assessment scope, and attach calibrators only where supported.

**Proof:** no source evidence is reported as complete when its coverage is incomplete; no unassessed proposition is counted as correctly recalled; no transport mass is silently renamed a probability; no raw posterior is modified by a display decision; no word-level precision is claimed from inherited unit estimates. A source hierarchy and a time projection conserve the intended quantity or report the unprojectable remainder explicitly.

**Initial analyses:** discrete trajectory; expected mapped-unit counts; optional onset/interval-based recall-by-source-time projection. All have independently authored expected results. The consumer can distinguish event-reference accuracy from recalled-detail fidelity.

**Exit rule:** uncertainty labels and projections are valid for their stated interpretations. Empirical calibration is a named capability, not a mandatory false claim attached to every output. An honestly uncalibrated exploratory profile may ship.

### G6 — Freeze evaluation, choose supported profiles, and release

**Work:** TA-27 through TA-30. Run the corrected evaluation clients against frozen inputs. Compare retrieval, graph-based mapping, the historical decoder/fill preset, and a permitted direct-label model baseline under the same input information and denominator. Complete packaging, documentation, replay and the support matrix.

**Proof:** a clean-machine installation runs the public examples; every published profile has a model card identifying input track, tested source/recall conditions, failures and uncertainty status; the complete required test matrix is green; no hidden corpus-specific setting drives the general pipeline. The stable release also requires G4, not just G2.

The exposed Sherlock/FilmFestival results remain development evidence. Seal and audit eligible Friends/Memento partitions according to the existing protocol before outputs are inspected; record prior exposure and condition-specific eligibility rather than calling them entirely unseen data. The one final test opening must follow the frozen model/prompt/policy decision. Participant holdout is not a substitute for held-out stimulus/edition evaluation when making generalization claims. [E12]

**Exit rule:** release the most defensible implemented profile, which may be a simpler baseline. Failure of a complex model to outperform a baseline is not a reason to withhold all usable software. It is a reason to limit the recommendation and superiority claim.

## 6. Evaluation that serves the product

### 6.1 Separate input tracks

At minimum distinguish: human annotated source descriptions; transcript/subtitle-only evidence; audiovisual-derived evidence; and structured narrative enrichment of a specified base track. Do not compare a language model given sparse subtitles to a system given rich human scene descriptions and call the difference an algorithmic advantage.

### 6.2 Preserve the scored population

Before an arm runs, freeze participant IDs, word/unit identities, permitted timing transforms, gold eligibility, and exclusion reasons. Every arm returns one outcome per requested unit. Missing IDs are an invalid run; explicit abstention/provider failure remains in the eligible primary denominator. Units without gold remain accounted for but are not invented gold errors. Paired comparisons use exactly the same identities and weights. A comparison of different segmenters uses a separate fixed evaluation-support manifest and prespecified projection onto that support; it must not reward a changed denominator or silently replace the preregistered estimand.

Preserve the existing participant-average paired accuracy difference as the primary comparative estimand where its preregistration applies; show pooled unit accuracy separately. Resample participants as paired clusters. For new stimulus-generalization claims, use a corresponding stimulus-level design rather than claiming that more recall units create more independent sources.

### 6.3 Test the output actually used

Measure exact scene/target accuracy at the declared resolution, candidate recall, coverage and selective risk, temporal error conditional on valid gold coordinates, fidelity only where there is fidelity gold, and sensitivity to order/fill policies. Gold interval overlap and boundary conventions must be explicit; the old first-match closed-boundary behavior cannot silently define truth.

Calibration evaluates the final chosen decision when it advertises chosen-decision confidence. Separate reliability diagnostics for the raw posterior. Run an error analysis for missing candidates, source under-description, wrong edition/timebase, bad segmentation, true ambiguity, misleading order prior, and unsupported fidelity. When temporal organization is the downstream dependent variable, an order-enforcing decoder is not an independent measurement of organization. Provide an evidence-only/order-ablated view and a sensitivity analysis. Report mapping and timing coverage by participant and condition.

### 6.4 Avoid unverifiable release promises

This audit supplies no fabricated accuracy threshold, runtime number, or guarantee of superiority. For an exploratory release, require complete reporting and comparators. A high-confidence profile must declare its target error tolerance and minimum useful coverage before calibration/test inspection; with insufficient evidence, omit that certified profile instead of quietly lowering its standard. Preset selection is versioned and cannot use the final test set.

## 7. Operational and engineering controls

Use one resolved configuration per run. Credentials and permitted content-transmission policy remain outside scientific parameter hashes where appropriate, but the selected provider, model identity, prompt/feature rendering and all behavior-changing parameters are recorded. Reject invalid config rather than quietly substituting defaults.

Cache source-side features and provider exchanges. Cache identity includes inputs, model/provider revision, normalization, rendering and parameters. Batch recalls independently; one failed participant must produce a typed failure record rather than erase the cohort. Retries are explicit and budgeted. An offline replay test forbids network access.

Define three independent readiness dimensions: execution completed; artifact structurally valid; requested analysis capability satisfied. A draft can be useful for a limited analysis. A command with `require=playback-localization` must fail its requirement if valid playback coordinates are unavailable, even if files were written successfully.

Publish output via a verified temporary directory and atomic rename where the filesystem allows it, or a manifest completion marker written last. Never overwrite historical run output silently. Keep source and participant prose out of general logs. Hashes of sensitive text are provenance aids, not a claim of anonymization.

Measure runtime, peak memory, candidate count, calls and input/output volume for two fixed workloads. Establish the baseline before imposing numeric budgets. Reject a regression exceeding a declared budget unless the change record names the accuracy/utility benefit. Preserve sparse outputs and never materialize a full word-by-frame matrix merely to write a file.

## 8. Project control

### 8.1 One active authority

Adopt this plan as the delivery index, with the approved D1A technical plan incorporated by reference. Preserve previous scientific records and historical plans, but mark whether each is governing, superseded, background, or evidence. The newest filename is not automatically the highest authority; an approved owner ruling outranks a later unapproved alternative.

Reconcile the tracker rather than opening duplicate D1A/D1B work. The `TA-*` IDs in the supplied backlog are planning keys, not assertions that new tracker items were created. Reuse existing tickets wherever possible and attach these acceptance gates to them.

### 8.2 Responsibilities

The project owner accepts scope and data-use policy. The implementation lead owns integration and the next failing gate. A research/evaluation owner freezes populations, gold and calibration protocols. A reviewer checks the diff and the acceptance evidence without having authored the implementation. In a one-developer project, these are separate responsibilities, not assumed extra staff; a fresh-context automated review is useful but is not independent empirical reproduction.

### 8.3 Work-in-progress rule

One active critical-path implementation slice, plus at most one independent evaluation/fixture slice. Every change names the user journey and gate it advances. A new abstraction requires a concrete consumer and an acceptance test. Ontology expansion, alternative solvers, new corpus adapters, and viewer work wait unless they close the current gate or repair a correctness defect.

The weekly review demonstrates a run from source/recall files to exported analysis, then shows the current blocking witness. The dashboard tracks reproducible journeys, unresolved identity/support failures, output accounting, benchmark coverage, runtime/cost and compatibility—not code volume, document volume, or number of agents.

### 8.4 Definition of done for each ticket

The ticket records baseline commit, exact change boundary, dependencies, an executable acceptance command/test, expected artifacts, failure injection, compatibility impact, and review result. A test that was not executed is recorded as unexecuted. A passing constructor test does not close a real-input demonstration ticket. A feature that exists only in a benchmark script is not a public capability.

## 9. First merge queue

1. **Control and manifest:** corrected charter/status, baseline fixture manifest, corrected scorer with identity/denominator mutations. No model tuning.
2. **Mapping records:** initial checked result and codec; raw/decoded/fill separation; complete outcomes; independent round-trip fixture. No algorithm changes.
3. **Pure extraction:** timed source view, decision policy, recall timing and necessary lexical logic moved behind library boundaries; historical preset parity. No new model.
4. **Analysis preview:** typed pipeline facade, explicit config, safe writes, exchange tables, and an executable R/Python example. No viewer dependency.
5. **D1A migration queue:** land the approved S0/S1/S2/S3/S4a/S4b/S4c slices in their required order, then film compile and D1B proofs. Do not bundle those changes into one broad refactor.

The benchmark track begins after the manifest/scorer correction and proceeds without changing the type-migration order. It informs supported presets and claims, not permission to build the approved film-capable software.

## 10. End state

The turnaround is complete when a new researcher can install a declared release, supply source material or checked source observations and recall transcripts, receive reproducible mappings and analysis tables, and identify exactly what was inferred, what was observed, what remains uncertain, and what was not computed.

That is a stronger delivery criterion than an impressive graph, a successful benchmark run in a private working directory, or an elegant library boundary in isolation. It also gives the project's existing technical depth a coherent purpose.
