# Python port: next phases and full-capability finish

Owner direction, 2026-11-16: make all working, non-redundant, non-stale StoryModel4s
capabilities available in idiomatic Python, improving the implementation using lessons learned.
Small slices are delivery order, not scope cuts. Deferred work remains owed.

This is the current **port** execution direction, refreshed on 2026-11-17 through
rich-consumer integration and the claim-assessment implementation in progress.
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
extend the sparse local rule with separate qualifier diagnostics. Finish the fixed-population
claim-assessment slice, then deliver a discriminating correspondence workflow. Playback, attribution,
richer graph families, semantic channels and account-derived inference remain owed. Mote owns
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

These checkpoints organize the next work across P2–P6; they do not rename the phases or
replace their full exits. The supplied-transition kernel and rich consumer integrations are
already implemented locally. Do not restart them. Claim assessment is in progress under
`bd-01M7M0916GDS07X2Q0M0TCRQTK`; its text profile does not yet close chart/playback assessment.
Mote owns verification and landing status.

### 1. Finish assessment and demonstrate useful correspondence — P3

**Deliver:** one runnable Python workflow and CLI equivalent that constructs a reusable source,
pairs two independent recalls, maps reference, assesses expressed claims and produces the
existing analysis views. Researchers should not assemble internal IDs or intermediate classes
by hand.

- Finish fixed-population claim assessment: exactly one outcome per declared question,
  supporting and counterevidence retained, source ownership checked, absence distinguished
  from extraction/reference failure, and receipts bound to the actual inputs.
- Connect the retained graded assessor to explicit reference hypotheses and source support.
  Keep fixed-question accounting distinct from graded fidelity; neither automatically changes
  the other. Define the checked link before a downstream analysis consumes an assessment.
- Extend target grain to retained event/scene/episode references and add semantic evidence
  channels through the same candidate contract, exposing each channel and its search limits.
  Keep expensive backends optional; checkpoint 4 completes their operational coverage.
- Exercise recurring actors in different actions, actor swaps, changed attributes, broad and
  joint recall, alternative targets, explicit external content, reordering and revisiting.
  Include both direct unit-only mapping and structural candidates, with separate recipes.
- Close API friction discovered by this workflow using the existing construction, pairing,
  recording and analysis functions. Consolidate repeated mechanisms when demonstrated; avoid
  a second account model or universal workflow framework.

**Done when:** installed API and CLI recover the same saved results offline; the controls
separate reference errors from fidelity errors; pending/unresolved evidence earns no supported
coverage; source identity and bytes remain unchanged across recalls. Validate refusal paths,
exact input binding and the relevant scientific distinctions independently. Recorded model
replies establish reproducibility, not model accuracy.

**Next action:** complete verification and cold review of the current claim-assessment slice,
then add the correspondence workflow and hypothesis-conditional graded assessment.

### 2. Complete the representations that downstream computation needs — P2

**Deliver:** construction and consumption paths for the remaining evidence and graph families,
in small connected slices rather than one schema expansion.

1. Playback evidence and explicit attribution/holders, preserving context and clock ownership.
2. Narrative hierarchy and the retained temporal, causal, referential and entity relations;
   distinguish hierarchy from a generic situation part-of edge.
3. Cross-unit identity/construction, recall grouping, summaries, repetition and discourse
   functions, preserving alternatives and unresolved outcomes.

Each slice must have an actual builder, checked admission, roundtrip and consumer. Extend
claim assessment to playback and later chart evidence when those ownership contracts exist.
Keep independent source/recall construction and the lightweight direct mapping path usable.
Do not require all enrichments before advancing a numerical method that already has its inputs.

**Done when:** the same independently constructed source supports multiple recalls and all
retained graph/evidence families have positive and adversarial examples through a real consumer.
The preservation ledger identifies every remaining gap; a serialized unused collection does
not count as a delivered capability.

### 3. Connect structured inference and scientific readouts — P4

**Deliver in this order:**

1. Build checked local evidence, admissible states and sparse transitions from accounts; feed
   the existing sequence kernel. Keep retrieval, local ranking and decoding diagnostics apart.
2. Port supported mode gates, refinement, reconstruction, duration behavior and other retained
   alignment/transport methods from their actual implementations and callers. Do not infer
   capabilities merely from the Graph-HSMM name.
3. Add feature tracks, windows and derivation dependencies, followed by native-coordinate
   temporal projection/exposure and retained trajectory/calibration/evaluation readouts.

Use ordinary numerical arrays and sparse structures where justified. Preserve the tested
large-cost cancellation behavior when optimizing; a faster implementation must pass the same
independent numerical controls. Raw score, transport mass, model-conditional posterior and
empirically calibrated probability remain distinct outputs.

**Done when:** account-derived examples reach reconstruction/readouts through the installed
package; tiny exhaustive oracles and pinned reference outputs where available discriminate
errors; named sparse-size measurements expose runtime/memory costs. Missing observations and
unmeasured time remain explicit. Engineering availability is separate from demonstrated
scientific benefit.

**Dependency:** reuse the existing kernel now; add each account producer as its needed P2
representation becomes available. Avoid a full P2 completion barrier.

### 4. Take one study through the installed library, then widen — P5

**Deliver:** the first retained study's intake → source construction → recall construction →
mapping/assessment → analysis → saved artifact/replay path. Start with Sherlock's existing
contracts and admitted synthetic adapter fixtures; actual restricted data requires the recorded
permission basis. Then carry FilmFestival and Friends through the same public path.

Bring specialized capabilities in through their consumers: provider recovery and execution,
media observations, proposition/AMR, embedding/cache backends, and interview/detail/episode
workflows. Keep optional tools and dependencies optional. Preserve existing artifact/cache
compatibility or supply explicit nondestructive adapters. This checkpoint does not authorize
paid provider calls or new participant-data use.

**Done when:** installed commands work outside the checkout, offline replay reproduces outputs,
and interruption/malformed/foreign-input cases preserve accounting and provenance. Every
retained specialized family has a construction and consuming workflow, not merely an import.
Use the first study path to expose API problems before cloning adapters for the others.

### 5. Inspect the artifacts and close the parity ledger — P6

**Deliver:** a React inspector over the stable Python outputs: linked source/recall selection,
evidence, hypotheses, contexts, missingness, timing and channel diagnostics; then saved
artifact-bound investigations, permission-checked exports and specialized views.

A minimal inspector can follow checkpoint 1 when useful for debugging. Look-and-feel work
stays late. Python owns scientific values; TypeScript handles interaction and rendering.

**Done when:** each working, nonredundant, nonstale Scala capability has an installed Python
workflow, a meaningful behavioral check and an executed consumer. Each changed behavior has
a rationale and compatibility/version contract. Proposed exclusions require evidence; uncertain
capabilities remain owed. Run old-artifact and workflow comparisons, not class-count comparisons.

## Keep development fast and the design coherent

- Work one connected delivery at a time. Expand the next Mote bead when its input contract is
  known; avoid creating a large speculative ticket tree or repeatedly replanning the phases.
- Read the relevant Scala producer, caller and tests; specify the behavior to preserve or
  improve; implement the shortest useful Python path; verify and commit a coherent slice.
- Reuse one package, ordinary functions/modules and shared admission/execution mechanisms.
  Keep compatibility in adapters. Add dependencies or abstractions only for demonstrated needs.
- Revalidate public input/update boundaries, then reuse validated data inside computation;
  avoid repeated whole-account serialization in inner loops. Preserve deterministic identity
  through harmless Python refactors.
- Use targeted independent checks and cold review for consequential semantics. Run the required
  broader checks when shared behavior changes. Do not substitute process or test volume for
  discriminating evidence, or rerun unchanged passing gates without a reason.
- At each connected workflow, improve the public API and remove proven duplication before
  adding another layer. Retain provenance and scientific distinctions while reducing ceremony.
- Keep feedback latency visible. Compare matched Python/Scala edits with identical behavioral
  acceptance before claiming a measured productivity gain; fast Python tests alone do not
  establish that claim. This measurement must not become a prerequisite for useful delivery.
- Preserve the full P2–P6 obligations above. A successful checkpoint is progress toward parity,
  not permission to declare the remainder unnecessary.
