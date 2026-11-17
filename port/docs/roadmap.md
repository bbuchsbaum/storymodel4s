# Python port: next phases and full-capability finish

Owner direction, 2026-11-16: make all working, non-redundant, non-stale StoryModel4s
capabilities available in idiomatic Python, improving the implementation using lessons learned.
Small slices are delivery order, not scope cuts. Deferred work remains owed.

This is the current **port** execution direction, refreshed on 2026-11-17 through
the account-derived inference, reconstruction, organization, native playback, chronology and
context-aware fidelity and native-source assessment workflows. The execution queue below was
refreshed against the checkout on 2026-11-17.
It extends [Stage 1](stage-1.md); it does not supersede the parent project's
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

The sparse sequence kernel and rich relation path have local implementation and independent
evidence; see [sequence inference](sequence-inference.md) and [relations](relations.md).
Rich identity extends the same sidecar path to state participants; [rich structural candidates](alignment.md)
extend the sparse local rule with separate qualifier diagnostics. Fixed-population claim
assessment, conditional fidelity and the discriminating [correspondence workflow](correspondence-workflow.md)
now have local implementation and evidence. [Source-derived transitions](source-transitions.md)
and [account-derived local evidence](local-evidence.md) connect checked accounts to the sequence
kernel, with sparse nominations, shallow mode gates and measured-support accounting. Richer
chart/outcome/cause evidence and refinement remain owed. [Explicit reconstruction](reconstruction.md)
now consumes these results while retaining the original evidence. [Conditional organization](organization.md)
consumes adjacent joint flow, with a separate explicitly independent-row path. Richer evidence
construction, native media ordering and registered measurement eligibility remain owed.
Recorded [context-holder resolution](attribution.md) now connects existing text contexts to
verified identity and event/state inspection. [Holder-dependent identity](held-identity.md)
now supplies a checked second pass and final role readout. [Full context vocabulary](contexts.md)
adds explicit v5 construction and holderless-scope behavior. [Native playback](playback.md)
now adds supplied media observations, document-free v6 construction and exact support readouts.
The explicit [native assessment path](native-assessment.md) now maps and grades text recall
against supplied source observations and feeds row coverage. Native recall, holder-aware native
assessment, full clock/anchor coverage and semantic channels remain owed. Mote owns
live status; the preservation map records each slice's behavioral coverage and limitations.

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

## Next deliveries: execution order from the current branch

Refreshed 2026-11-17 through native-source assessment source `8fdd57013` and its
local qualification, following contextual fidelity `3e8e7367c` / `5a98a3d59`.
This is the next-work plan within P2–P6,
not a second phase numbering system. Mote owns live status. Completed checkpoints move
out of the future queue; capability obligations remain until their complete exit is met.

### Baseline we build on

Reuse independent rich construction, contexts and holder-dependent identity, native playback
construction/readouts, direct mapping, conditional fidelity, and the account → local evidence
→ sequence inference → reconstruction → organization path. The latest
[cross-unit construction slice](construction-links.md), source `b6d81b56e`, connects recorded
row-local references to the existing relation/projection/inference consumers. Its
[evidence receipt](../evidence/construction-links.json) records 827 passing tests, installed
API/CLI agreement, four discriminating mutations and independent review. These are recorded
local results, not checks rerun for this planning update or a full-parity claim.

[Primary hierarchy](hierarchy.md), source `b229cbddd`, now adds identity-preserving grouping,
exact text/native support, checked queries and v2 projection/local-evidence consumers.
[Evidence](../evidence/hierarchy.json) records the full suite, installed workflow, mutations and
independent review. This closes primary grouping, not auxiliary memberships or boundary beliefs.
The historical `scenes.py` operation keeps its documented reidentification behavior; new grouping
uses the checked hierarchy artifact. Native construction and queries work; native numerical
inference remains unsupported. The qualified observation-based assessment path is described below.

[Story-world chronology](chronology.md), source `f2610fcb1`, now adds v7 temporal claims,
sparse interval consistency, actual cited-premise entailment and dependency-sensitive queries.
V3 source projection consumes explicit narrated before/meets edges in text inference; native
media construction and temporal queries also run. [Evidence](../evidence/temporal.json) records
863 passing tests, five discriminating mutations, installed API/CLI agreement, byte-identical
legacy workflows and cold review. These are local engineering results, not full Scala parity.

[Context-aware fidelity](context-fidelity.md), source `3e8e7367c` plus correction `5a98a3d59`,
now consumes verified context/holder and identity evidence through the existing assessment engine.
Required unresolved dependencies block substantive grades; explicitly independent judgments
remain possible. This is an expanded information condition, not the unit-only profile.
[Evidence](../evidence/context-fidelity.json) records 877 passing tests, installed API/CLI
agreement, five compiled mutations and cold review. Review found and closed a CRLF normalization
defect; exact input recordings now survive CLI preparation. Legacy v1 artifacts remain identical.
These checks establish admission and preservation behavior, not semantic grading accuracy.

[Native-source/text-recall assessment](native-assessment.md), source `8fdd57013`, now completes
the supplied-observation path through mapping, conditional fidelity, replay and row coverage.
V8 retains exact playback supports; older versions refuse native grade evidence. One unchanged
source feeds two recorded recalls. [Evidence](../evidence/native-assessment.json) records 894
tests, installed API/CLI agreement, legacy replay compatibility and cold review. This is symbolic
observation assessment, not raw audiovisual perception or native holder-aware reasoning.

**Next implementation action:** add one retained semantic channel to the existing sparse
candidate/local-evidence interface, with model/space/cache identity and a real inference consumer.
Use the connected workflows to simplify public calls where repetition is demonstrated.

### Immediate work queue

These are the next bounded work units inside the deliveries below, not additional phases.

1. **Add one semantic channel with a real consumer.** Bind model/space/cache identity, nominate
   candidates sparsely, and retain channel-specific evidence and unavailable/search-limited
   outcomes. Compare lexical-only and semantic variants on the same saved inputs, with separate
   retrieval, ranking and decoding diagnostics. Use recorded/local inputs for engineering
   qualification; a live provider or demonstrated accuracy gain is not an acceptance condition.
2. **Complete connected graph and target coverage.** Continue causal/referential/entity
   relations, auxiliary hierarchy, recall grouping/discourse and retained event/episode targets.
   Add each through a producer and an actual consumer. Consolidate repeated public-call patterns
   in the connected text/native journeys; do not add another orchestration layer.

Between these workflow deliveries, complete causal/referential/entity relations, auxiliary
hierarchy and recall grouping/discourse in bounded slices using the same examples. Move a family
earlier when it is required by the next consumer. Their deferred status is explicit; successful
chronology or assessment does not close P2.

### Checkpoints for the next few phases

| Checkpoint | Concrete result | Completion decision |
|---|---|---|
| Finish connected structure (P2) | Chronology plus the retained graph/identity/recall families, each constructed and consumed. | Every declared family has a working producer, query/analysis consumer and positive/refusal evidence; unimplemented families keep P2 open. |
| Make the library useful as one workflow (P2/P3) | One installed input-to-analysis journey for text and native evidence, with a compact public API. | Two recalls reuse one source; contextual assessment, joint/broad/alternative/external reference and missing evidence survive saved readback. |
| Deepen computation (P3/P4) | Semantic and structured evidence, remaining working inference methods, feature tracks and measured-time outputs. | Each method has an independent numerical witness, an account-to-output consumer and declared sparse runtime/memory measurements. |
| Complete study and inspection coverage (P5/P6) | Retained study adapters and specialized modules, offline recovery, and React inspection of real artifacts. | Every retained Scala/Atlas workflow has an executed Python/TS equivalent or a documented, evidence-backed replacement/exclusion. |

Use these checkpoints to assess progress by researcher tasks completed. Avoid percentages based
on modules, classes or test totals. One connected example should grow across the checkpoints;
specialized examples are added only when they exercise a distinct capability.

### Delivery 1 — Connected narrative structure (P2, then P3/P4 consumers)

**Primary hierarchy checkpoint implemented:** see the [contract and evidence](hierarchy.md).
Its acceptance contract remains below. Build checked situation/segment membership and nested
scene/episode grouping for independently constructed source and recall accounts. Preserve
occurrence identity when changing grouping. Primary nesting, auxiliary membership and generic
`part_of` have different meanings; one must not silently become another.

Use exact support unions, single primary parents, acyclic nesting and explicit root and
completeness rules. A partial construction must expose unattached/unprocessed content rather
than pretend the whole account is rooted. Text and native media cases need explicit support
and source/edition checks. Cover children, ancestors, descendants and situations-under queries;
connect the hierarchy to source projection and the existing inference workflow where supported.
Native hierarchy/query support must not be advertised as native inference before that consumer
exists.

Read `story/hierarchy.scala`, containment types and validator tests before choosing the storage
shape. Prefer membership outside occurrence identity; decide whether it belongs in a checked
account-bound artifact or a versioned account field using actual query/projection consumers.
Do not create a second graph model. Record the compatibility decision before implementation.
The Scala validator currently compares text support hulls: Python should retain evidence gaps
and reject a child in an unsupported gap, documenting this as a deliberate correction.

**Following slices:** complete retained temporal/causal/referential and entity-relation
semantics, scoped/epistemic evidence, native link construction, and recall grouping/summary/
repetition/discourse producers. Audit auxiliary memberships and boundary beliefs explicitly;
the first primary-tree slice does not retire them. Keep story chronology separate from
observation order and playback coordinates. Extend one relation family at a time with a real
construction path and consumer; do not silently widen the existing four-kind recipe.

**Exit:** an installed workflow constructs and queries nested source/recall structure, reuses
one unchanged source across two recalls, and consumes declared graph families in projection,
candidates or inspection. Independent expected trees and refusal cases cover duplicate parents,
cycles, root completeness, foreign supports, discontinuous gaps and stable occurrence IDs.
Every newly claimed relation family has positive and adversarial evidence. P2 remains open
for any other retained family without a producer and consumer.

### Delivery 2 — One coherent construction-to-analysis API (P2/P3)

Use the existing rich-detail, correspondence and connected-construction examples as one
researcher journey: prepare observations → admit recorded construction → resolve identity and
attribution → admit details → map references → assess conditional fidelity → derive the three
analysis views. Keep the lightweight annotation/direct route usable independently of rich graphs.

Context/holder evidence is now connected to assessment under an explicit expanded condition.
Native-source/text-recall reference, assessment and row-coverage consumers are now connected
under the explicit supplied-observation profile. Cross-modal identity, native recall and native
holder-aware grading remain unsupported rather than inferred. Complete event/episode target handling
and retained identity/number rules in bounded slices as their consumers require them.

Consolidate the public API at this point. Researchers should supply observations, proposals and
policies, not manufacture IDs or sequence private validators. Use one set of Python functions
for notebooks and CLI. Share validation and recording machinery; keep historical formats in
adapters. Do not add another orchestration framework or mirror the Scala module tree. Existing
completed assessments and original mapping evidence survive changes of method/profile.

**Exit:** one documented installed example runs offline from inputs through all three views,
with one source and two recalls. Include recurring occurrences, changed actors, joint and broad
reference, uncertain alternatives, external content, unresolved holders and pending fidelity.
Add a native-source/text-recall case through an actual assessment consumer, not pairing alone.
A reader can trace a result through saved intermediates without private API calls. Compatibility
replay stays intact. Scripted controls establish contracts, not extraction or model accuracy.

### Delivery 3 — Richer evidence through existing algorithms (P3/P4)

Add one retained semantic channel to the existing candidate/local-cost interface, with explicit
model/space identity and cache provenance. Then add proposition/chart comparison, outcome and
cause evidence, and retained refinement/mode rules as their representations become available.
Move the necessary chart/AMR subset forward from P5 when a real consumer needs it; keep optional
backends optional. Avoid a dense all-pairs semantic matrix in the core path.

Preserve each channel's evidence and missingness. Backend absence, budget exhaustion,
inadmissibility and a measured low score are distinct. Expand retained assessment profiles
with their actual information conditions, including whole-source or neighboring-recall context
where supported; preserve the order-independent measurement condition.

**Exit:** the same saved inputs run through lexical-only, semantic and structured variants.
Report retrieval, local ranking and decoding separately, including candidates lost to budgets.
Each added channel reaches a computation or assessment consumer. Verify expected tiny examples,
sparse behavior, missing-channel cases and replay; do not require a claimed accuracy gain to
make a working method available. Any accuracy comparison needs its own evaluation design.

### Delivery 4 — Remaining numerical methods and scientific outputs (P4)

Inventory each remaining method from its working Scala implementation, callers and tests.
Port duration/refinement/transport or other retained methods into the current computational
interfaces instead of replacing the verified sequence kernel. Start an independent method
as soon as its inputs are stable; it need not wait for every enrichment in Delivery 3.

Connect aligned feature tracks, windows and derivation dependencies to native-coordinate
exposure, temporal projection, recall-onset linkage and trajectory/concentration readouts.
Complete clock maps, native media ordering and declared measurement eligibility. Reuse exact
allocation already implemented. Port calibration/evaluation machinery while keeping raw scores,
transport mass, model-conditional posteriors and calibrated probabilities distinct.

**Exit per method:** installed account-to-output example; independent exhaustive or analytic
small-case oracle; pinned Scala comparison where executable; explicit support, denominator and
missingness rules; runtime/memory measurements on declared sparse input sizes. Behavior fixes
have a named version boundary and regression witness. No inferred duration from a point, hidden
imputation or order prior masquerading as independently measured organization.

### Delivery 5 — Studies, specialized capabilities and inspection (P5/P6)

Start a complete synthetic Sherlock-contract workflow after Delivery 2, then adapt the retained
FilmFestival and Friends paths to the same API. Reuse intake, manifests, recordings and model
assets. Complete retained provider recovery, corpus ingestion, media acquisition, proposition/
AMR, embedding caches/backends and interview/detail/episode workflows. Each has an executable
producer and consumer; later scheduling is not exclusion. Live paid runs and new participant
data are separate operational decisions, not prerequisites for this plan's engineering checks.

Add a minimal React inspector against real saved Python artifacts when it helps this workflow:
linked source/recall/detail selection, exact evidence, contexts, alternatives, processing gaps,
timing and diagnostic tables. Python owns scientific values; TypeScript owns interaction.
Then restore artifact-bound saved investigations, keyboard access and permission-checked export.
Visual refinement follows computational usefulness.

**Exit:** installed study commands, offline reproduction and input-bound interrupted-run recovery;
compatibility evidence for existing artifacts; actual consumers for every retained specialized
family; and executed Scala-to-Python workflow coverage with no unexplained losses. Check the
StoryAtlas workflows as well as StoryModel. Uncertain exclusions remain owed; a useful capability
with a broken old implementation needs repair or replacement, not silent deletion.

## Execution rules for these deliveries

- **Next bead:** one retained semantic channel through sparse candidates/local evidence and
  an actual inference consumer from Delivery 3. Keep missing channels, budget loss and raw
  scores explicit. The larger graph/API, numerical, study and inspection deliveries remain open.
  Chronology, contextual fidelity and native-source assessment are qualified local checkpoints.
- Use one bounded bead at a time, usually one session to one working day. Open subsequent
  beads when the input contract is clear; do not create a speculative ticket tree or assign
  an unsupported calendar estimate to full parity.
- For each family, add source/caller/test pointers, the Python entry point, compatibility or
  corrected behavior, consumer and independent evidence to the existing preservation map.
  Do not use test count, similarly named types or imported schemas as a parity measure.
- Validate at public boundaries and reuse checked values inside computation. Prefer ordinary
  modules/functions, sparse indexes and standard numerical arrays. Add dependencies and
  abstractions only for a demonstrated need; consolidate repeated mechanisms at workflow exits.
- Run the smallest meaningful tests plus required type/lint checks. Review consequential
  semantics cold and use discriminating mutations where required by the evidence contract.
  Run the broader suite when shared behavior changes; do not repeatedly gate unchanged code.
- Every delivery ends in an installed runnable workflow, a short evidence receipt and an honest
  remaining-gap list. Reuse the same connected examples rather than accumulating isolated demos.
- Record feedback latency during normal work. A substantial productivity claim requires matched
  Python/Scala changes with equivalent acceptance, including implementation/debugging effort;
  fast test execution alone is insufficient. Do not turn that comparison into a prerequisite
  for useful implementation.
- Preserve all P2–P6 exits above. The sequence can change when a dependency or newly verified
  behavior warrants it; the full working-capability destination does not shrink.
