# Python port: next phases and full-capability finish

Owner direction, 2026-11-16: make all working, non-redundant, non-stale StoryModel4s
capabilities available in idiomatic Python, improving the implementation using lessons learned.
Small slices are delivery order, not scope cuts. Deferred work remains owed.

This is the current **port** execution direction, refreshed after rich-detail integration at
`2da708d7c`. It extends [Stage 1](stage-1.md); it does not supersede the parent project's
[VISION](../../VISION.md), scientific contracts or Scala roadmap. Mote holds live status.
The [preservation map](preservation.md) remains the behavioral contract. Phase numbering starts
at P2 to distinguish this expansion from the initial Stage 1 pilot.

## Starting point and the next decision

The pilot has checked accounts and independent pairing, UTF-16 recall intake, exact native
clocks, recorded execution, a bounded extractive proposal resolver, sparse lexical/role
alignment, three analysis views, exact temporal allocation, legacy reads, and an API/CLI.
These are verified bounded capabilities, not full module parity. The React app is a scaffold.

The reviewed local slices now include recorded source construction, event-relation admission,
text identity, and [rich state/circumstance construction](rich-accounts.md). The connected
[rich-detail workflow](rich-detail-workflow.md) reuses one source across two recalls, admits
expressed details with explicit inventories, and feeds [direct mapping](direct-mapping.md)
and the three analysis views. Its recorded checks are in
[rich-detail-workflow.json](../evidence/rich-detail-workflow.json). This is local engineering
evidence, not a claim that the branch has landed or that the full phases are complete.

Important gaps remain: relation admission, identity and structural alignment still use the
legacy account boundary; they must gain explicit rich-account support. Playback, attribution,
richer graph families, semantic channels and probabilistic inference are still owed. The next
algorithmic slice is the sparse sequence kernel, followed by those rich-consumer seams and a
discriminating correspondence workflow. Mote owns live status; the preservation map records
each slice's behavioral coverage and limitations.

Do not require a complete event graph for basic categorical localization. Conversely, graph
construction is owed independently of whether it improves localization accuracy.

## Phases at a glance

| Phase | Researcher-visible delivery | Exit artifact |
|---|---|---|
| P2 — Construct rich accounts | Build source and recall graphs from observations/proposals, without manually assembling internal objects. | Independently built source and recall artifacts, paired readback, positive/refusal examples for every declared graph family. |
| P3 — Map reference and assess fidelity | Use direct recorded model mapping and structured candidates through common reference semantics; assess details separately. | Single/joint/broad/alternative/external outcomes, evidence-bound conditional fidelity, recurring-scene who-did-what demonstration. |
| P4 — Recover inference and measurement | Port working probabilistic/structured algorithms and richer scientific readouts. | Independent numerical oracles, stage-separated diagnostics, native-coordinate feature/projection artifacts. |
| P5 — Operate studies and close specialized coverage | Reuse study adapters, providers, perception, semantic representations and interview workflows. | Documented installed commands for retained studies and specialized capabilities, replay/recovery and interoperability evidence. |
| P6 — Inspect and demonstrate full parity | Finish the React inspection workflow and close the capability-level audit. | Saved investigations, permission-checked exports, executed Python equivalents of all retained Scala workflows. |

P2 → P3 is sequencing guidance: start P3's lightweight direct route as soon as its
source/account boundary is stable, without waiting for every P2 enrichment. P4 numerical ports
can begin once their representations are stable; they need not wait for live provider integration. P5 adapter work starts alongside
P2 for the first corpus, then expands after P3. A minimal P6 artifact inspector starts after P3;
visual refinement stays late. Full completion requires all retained capabilities, including
those scheduled later—not just P2–P3 or the initial synthetic path.

## P2 — Source and recall graph construction

Deliver in bounded slices:

1. **Source compiler from anchored rows and recorded proposals.** Input document/edition,
   source rows and optional independently declared timing; local entity mentions, events/states,
   roles and contexts; deterministic admission; a result for every input row. Emit a reusable
   source-only account. Source input never depends on a participant's recall.
2. **Shared graph families and evidence.** Add explicit place/state/event-time representation,
   discontinuous support and playback evidence; part-whole, temporal, causal and referential
   relation proposals with checked endpoints. Preserve text, playback, story-world and recall
   clocks separately. Do not attach a plausible time where none was measured.
3. **Identity, attribution and cross-unit construction.** Add evidence-backed mention/coreference
   resolution, cross-unit references and attributed speech/content. Keep identity proposals,
   accepted identity, ambiguous identities and cross-account links distinct. Reuse the
   existing identity/speech ADRs rather than inventing a new promotion rule.
4. **Recall completeness.** Extend the current extractive unit profile to those same graph
   families, detail/discourse/address proposals, repetition, summaries and event grouping.
   Provider proposals may normalize predicates only under an explicit versioned contract;
   verbatim evidence remains available. Preserve alternative and unresolved constructions.

**Exit:** two recall sessions reuse the same source artifact unchanged. A paired artifact
contains all declared graph families with functioning construction paths, not only classes or
unknown placeholders. Every family has a positive and adversarial witness, exchange roundtrip
and analysis/inspection readout. Row and unit accounting distinguishes represented, partial,
unsupported, failed and unattempted work. An independent reader recovers identities, supports
and contexts from the saved artifact. No extraction accuracy claim follows from this exit.

**Reuse first:** `story` nodes/relations/hierarchy/validators, `document` mention/coreference
and projection, `acquire` proposals/patch/resolution, `recall`, film compiler stages and
ADRs 0024–0028. `FilmOutline` is an evidence rendering, not a source truth oracle.

## P3 — Reference, fidelity and useful correspondence

1. Keep the existing simple localizer. Add input-bound **direct recorded model mapping** as
   another method, including the existing categorical/narrative-versus-referent path. A rich
   graph must not become a prerequisite for a working lightweight route.
2. Extend structured candidates with the retained semantic/embedding channels and explicitly
   scoped event, row, scene and episode targets. Expose each channel independently; retain
   candidates lost to budgets and incomplete source representations as limitations.
3. Construct joint and broad references explicitly. Alternatives express uncertainty;
   joint targets express combined reference. Neither is simulated by duplicating singleton
   credit. External destinations require their own evidence/decision, never a failed search.
4. Port the working graded assessor into **hypothesis-conditional fidelity** with source support,
   raw proposals and checked admission. Retain supported, contradicted, gist-detail error,
   recaller inference, unverifiable, misread and other existing distinctions. Reference,
   address, fidelity and phenomenology stay separate. Existing assessments are not silently
   overwritten when a method changes.
5. Derive coverage and omission under the accepted declared policy. Provide the unit-only /
   order-independent condition and input audit needed for organization analyses.

**Exit:** the same source cast occurs in distinct scenes/actions; swapped actors, changed
attributes, a broad summary, a joint recall, an ambiguous reference, reordering/revisiting and
explicit external content produce discriminating saved results. Pending fidelity earns no
supported coverage. CLI and Python recover the same three views without a provider call.
Direct and structural methods have separately named recipes and comparable diagnostics,
without pretending they measure the same uncertainty.

**Reuse first:** `align/claimassessment.scala`, mapping vocabulary/binding/run/provenance,
`tools/sherlock_mapper/verify_claims.py`, connected assessment probes and ADR 0030. Locate actual
callers/tests and distinguish a working path from an abandoned experiment before selecting code.

## P4 — Numerical inference, features and temporal analysis

Port functioning algorithms with independent small-case evidence, using standard numerical
arrays/sparse structures where appropriate; do not translate collection/typeclass machinery.
Pin new dependencies only when an algorithm requires them.

- Graph-HSMM and its supported structured/partial alignment operations: transitions, duration
  semantics, revisits, external/unranked states, inference outputs and uncertainty contracts.
- Other retained transport/alignment methods and multiscale/trajectory summaries found in the
  capability audit. Preserve their assumptions and distinguish them from categorical results.
- Feature spaces, aligned tracks, derivation DAGs, windowing, ledgers and sidecars; exact
  support, coordinate and missingness semantics through tables and sparse outputs.
- Temporal query/exposure/projection, aggregation and eligibility, recall-onset linkage,
  concentration/trajectory measures and measured-time exports. No hidden HRF, binning,
  normalization or imputation policy.
- Existing calibration/evaluation machinery, with a strict distinction between raw scores,
  transport mass, model-conditional posterior and empirically calibrated probability.

**Exit:** brute-force/exhaustive tiny cases where feasible, pinned Scala outputs on controlled
inputs, invariant/metamorphic checks and targeted mutations discriminate numerical errors.
Report retrieval, local ranking and decoding separately; order priors must not hide weak
content evidence. Compare sparse runtime/memory on declared sizes. A working algorithm is not
excluded merely because it failed to improve one dataset; scientific benefit and engineering
availability are separate. Frozen/new-data qualification is required only for the corresponding
scientific claims, not every engineering port.

## P5 — Study operations and retained specialized capabilities

- Adapt Sherlock, FilmFestival and Friends intake to the same source/recall/account contracts,
  including row/scene identity, clocks, measured recall timing, manifests and receipts.
  Start with admitted synthetic adapter fixtures and local authorized recordings. Observe
  current data-admission rules and study split guards; do not expose participant text in Git.
- Port the functioning parser/provider execution surface: model/input/prompt pins, budgets,
  retries, record-before-parse, interrupted-run handling and offline recovery. Add an optional
  live adapter only after deterministic fake-provider tests. A paid/live study run is a
  separate operational action, not implied by this planning task.
- Reuse media/process tooling where sound: probe/ingest, speech, captions, frames, scenes and
  cross-modal observations. Keep external tools optional and provenance visible.
- Carry proposition charts and AMR/PENMAN adapters, embedding contracts/caches, ONNX and WL
  channels, interview/detail/episode workflows, and retained corpus/feature sidecars into
  Python modules or optional integrations. Their later scheduling is not retirement.

**Exit:** each retained study has an installed, documented command and offline replay, including
malformed input, foreign recording and interrupted-run cases. Each specialized family has a
Python consumer and a real construction/analysis path. Publish a compatibility table for
existing artifacts, caches and model assets; no destructive migration is required. Scientific
qualification and data permissions remain explicit and distinct from local integration proof.

## P6 — Inspect, compare and close full coverage

The React app reads Python-derived artifacts: linked source/recall/detail/event selection,
contexts, alternatives, missingness, timing, provenance and channel diagnostics. Provide
keyboard access, text alternatives, saved artifact-bound investigations and permission-checked
exports. Renderer state never recomputes scientific values. Initial inspection follows P3;
full temporal/trajectory and specialized views follow their producers.

**Full-port exit:** every retained capability has an installed Python path, a named compatibility
or deliberately improved-behavior contract, independent checks and an executed consumer.
The old/new artifact and user-workflow comparisons have no unexplained losses. Changed
semantics are versioned, documented and tested; class-for-class parity is not required.
Measure matched developer changes in Python and Scala only when making a productivity claim.
Passing tests, type counts or a smaller codebase alone cannot establish the exit.

## Capability coverage, exclusions and execution rules

The table below assigns responsibility; it is not a verification status dashboard. Before
implementing each family, expand its [preservation-map](preservation.md) entry with current
symbols/callers/tests, Python entry point, reusable assets, independent oracle, phase/bead,
compatibility/change contract and remaining limitations. Mote records actual status.

| Scala capability family | Phase responsibility |
|---|---|
| `core` identities, evidence, contexts, claims, provenance | Foundation retained; P2 full evidence/claim construction; P6 closure |
| `story`, `document`, `recall`, `acquire` | P2 graph/identity/proposals; P3 reference/assessment consumers |
| `align` categorical/direct, typed reference, fidelity | P3 |
| `align` numerical inference, uncertainty, trajectory, calibration | P4 |
| `features` and scientific view compilation | P4; P6 consumers |
| `corpus`, `corpus-intake`, study-specific inputs/timing | First adapter during P2; complete retained coverage P5 |
| `provider-parser`, `provider-agent`, orchestration/recovery | Existing replay retained; richer contracts P2/P3; operational adapters P5 |
| `proposition`, `amr-interop` | P2 representation seam; P5 complete retained adapters |
| `embed-core`, `embed-onnx`, `embed-grakern` | P3 semantic evidence; P5 all retained backends |
| `media`, cross-modal observations | P2 evidence seam; P5 acquisition/construction integration |
| `interview` | P5 |
| `codec`, `view`, exchange/history | Every producing phase; P6 compatibility closure |
| `fixtures`, `laws`, `embed-bench`, evaluation utilities | Each phase's oracles; P4 measurement; P6 coverage audit |
| StoryAtlas interaction/rendering/investigations/export | Minimal P6 work after P3; full retained functionality in P6 |

No capability is currently excluded by this plan. A proposed exclusion must cite current code,
callers and tests and show that it is broken, redundant with a named replacement, or stale.
Uncertain cases stay owed pending investigation. A broken implementation may still represent
a useful promised capability: repair/reimplement that capability rather than silently dropping
it. No whole-tree audit is a prerequisite to the next useful slice; inventory expands with it.

Keep one package and a small public API. Prefer standard Python tools, explicit numerical
arrays and sparse indexes over a hierarchy translated from Scala. Consolidate repeated
mechanisms when two real consumers demonstrate the need. Avoid a framework, service, database
or universal graph abstraction as a prerequisite. Keep receipts sufficient to explain and
reproduce results, without requiring researchers to manage internal IDs by hand.

Each bead should deliver one executable path and its relevant checks, usually one session to
one working day of scope. If it grows, split at an artifact boundary. Review consequential
semantics cold; rerun only affected checks unless the change has broader reach. Engineering
proof precedes scientific claims, but scientific qualification must not block unrelated ports.

## Immediate sequence

The next three deliveries are computational. They do not rename or replace the full phase
exits above. Finish each executable slice before adding another public abstraction.

| Order | Delivery | Concrete completion evidence |
|---|---|---|
| 1 — P4.1 | Sparse sequence inference over supplied, checked local costs and transition distributions. | Posterior marginals, adjacent flow, deterministic best path and log normalizer agree with exhaustive enumeration; installed API/CLI artifact roundtrip. |
| 2 — Next P2 integration | Rich accounts through relation admission, text-identity consumers and structural candidate generation, one consumer at a time. | States, circumstances, contexts and existing IDs survive every update; state/event endpoints and foreign-context refusals have discriminating examples; legacy recordings remain readable. |
| 3 — Next P3 integration | Recurring-scene correspondence with explicit target grain and hypothesis-conditional assessment. | Repeated actors in different actions, actor swaps, changed attributes, broad/joint/alternative references and external content produce distinct saved outcomes through the same analysis API. |

### First delivery: the numerical kernel

Next implementation bead: `bd-01M7KVBD5K6101NP5DG2P2QK1M` (Mote owns status).

Use the working numerical seam in `align/hsmm.scala` (`GraphHsmm.run`), separating inference
from account-specific evidence production. Inputs declare ordered units, admissible source,
distorted and external states, finite local costs, positive temperature, and sparse stochastic
transitions. Unranked is an explicit state, not a substitute for a failed calculation. The
initial state prior is uniform, as in the reference implementation.

Implement stable forward/backward and Viterbi computation with deterministic ordering. Center
or scale the calculation so a large shared cost cannot corrupt normalized posterior mass;
refuse unrepresentable numerical ranges. Do not allocate a dense full-source pair matrix.
Keep the first implementation dependency-light; add numerical libraries when measured needs
justify them. Public consumption revalidates input relationships and binds saved results to
the exact input. Posterior mass is conditional on this model, not calibrated confidence or
automatic fidelity credit.

The independent starting oracle is the three-unit, seven-state, 343-path enumeration in
`align/src/test/scala/storymodel4s/align/StructuralWeightSuite.scala`. Compare every marginal,
every adjacent flow, the normalizer and the best path. Add one-unit, absent-edge, deterministic
tie, reordered-state, extreme-cost and malformed-input cases, plus a targeted mutation and
cold review. An executed Scala comparison, when recorded, is separate evidence from porting
the oracle; neither establishes empirical accuracy.

This first slice accepts supplied transitions. Graph-derived transition features, admissibility
mode gates, refinement and reconstruction are subsequent P4 work, not implied by the kernel.
Audit the actual duration behavior supported by Scala rather than inferring it from the HSMM
name. Save the example, schema, commands and results under `port/`.

### Finish the phases after those deliveries

1. **Close P2/P3 construction and correspondence.** Add remaining playback/context/attribution,
   identity, hierarchy and recall-grouping paths in bounded slices. Extend target granularity,
   semantic channels and the retained fixed-question assessment receipts. Keep the direct
   unit-only route usable while richer structural methods develop. The phase exit must consume
   every retained collection, not merely serialize it.
2. **Finish P4 inference and measurement.** Connect checked account evidence to transitions,
   refinement and reconstruction; then port retained feature tracks, windows, temporal
   projections and trajectory measures. Report retrieval, local ranking and decoding separately.
   Use tiny independent numerical cases and declared sparse size measurements. Preserve raw
   score, posterior, transport mass and calibrated probability as different quantities.
3. **Finish P5 operational coverage.** Take one retained study through installed intake,
   construction, mapping, analysis and replay before widening to the other studies. Add media,
   provider, proposition/AMR, embedding and interview capabilities against their real consumers.
   Fake-provider and recorded paths precede live operations; data permissions remain separate.
4. **Finish P6 inspection and parity closure.** Build the useful React artifact inspector once
   the producers are stable, then saved investigations, exports and specialized views. Run the
   capability ledger against installed workflows and old artifacts. Resolve every gap with a
   port, a documented improvement, or an evidence-backed exclusion; no silent retirement.

### Keep development fast and the design coherent

- Extend the existing account, resolver and execution mechanisms; avoid parallel model trees,
  provider engines or estimator copies. Preserve scientific distinctions while removing
  incidental Scala machinery.
- For each slice, identify the actual Scala producer and consumer, specify the Python behavior,
  implement the smallest connected path, run the relevant independent checks, and commit it.
  Record changed semantics and compatibility at that point, rather than postponing them to P6.
- Keep focused-test and installed-example turnaround visible. Make a comparative productivity
  claim only after matched edits with the same behavioral acceptance in both implementations;
  a fast Python test run alone does not prove a development speedup.
- Do not turn phase planning into a prerequisite for every change. Expand the next Mote bead
  when its input contract is known; the remaining phase obligations above already hold scope.
