# Stage 1: preserve a complete workflow and test a simpler implementation

**Planning date:** 2026-11-16. **Scope:** one Python package and a narrow React/TypeScript
workbench under `port/`. **Planning bead:** `bd-01M7K75VHV71V6GGAXEDD8QNFY`.

Stage 1 must answer two questions: can the new implementation preserve the useful behavior
of StoryModel and StoryAtlas, and can a developer change that behavior substantially faster?
A smaller mapper that omits the hard scientific distinctions cannot answer either question.

The owner authorized the targeted pilot and required preservation of the best existing work.
The architecture and numerical decision thresholds below are pilot choices, not changes to
the current production contracts. The [preservation map](preservation.md) is part of acceptance.

Implementation priority, clarified by the owner on 2026-11-16: computational models,
algorithms and correctness first. Establish the web framework now; defer visual refinement.
The [computational foundation](computational-foundation.md) records the first bounded slice
and remaining acceptance work. The finish below remains the broader Stage 1 target.

## User-visible finish

From a clean checkout, a documented command prepares a small admitted synthetic source and
recall fixture, consumes recorded model proposals, validates the resulting account, and writes
a versioned bundle and analysis tables. A researcher can:

1. Open that bundle in the workbench without a Python service or external network access.
2. Select a recall unit or detail and inspect its exact text support, source evidence,
   reference alternatives, fidelity, processing state and method provenance.
3. Follow the corresponding source occurrence and its context; distinguish repeated
   participants from repeated events and inspect a broad summary without false row credit.
4. Change a declared analysis policy through the Python API/CLI, reload its derived view,
   and inspect what changed without another provider call or modification of the account.
5. Save and reopen an investigation with the same selection, view settings and artifact
   identities; receive a useful refusal when its required artifact has changed.
6. Obtain identical sorted analysis rows through the CLI and a short executable Python
   example suitable for a notebook. Importing the package must not start a service.

Inspection supports keyboard selection and a text alternative to graphical encodings.
Evidence export retains producer-granted permissions: denied or unsupported grants refuse
before constructing a download, even when inspection itself is permitted.

The first workbench reads files and precomputed projections. In-browser policy recomputation
is not required for Stage 1; the UI must identify the selected policy and missing projections.
A local service can follow only when a measured interaction requires it.

## Minimum discriminating fixture

Use existing admitted synthetic material where it fits. Any added narrative or synthetic
recall text follows the repository's [text admission checklist](../../docs/design/story-text-admission-checklist.md)
before commit, including independent review and a header in the text's own file. No real
participant data, sealed evaluation material, provider credentials or paid calls are needed.

Define a small structural packet with hand-checkable expected results, not a large benchmark:

- Two source event occurrences share a participant but differ in action or setting. Several
  recall details belong to one occurrence; an atom is not automatically an event.
- A reported-speech or belief context has its own event, with no promotion to root-world fact.
- A part-whole link and one temporal and one causal relation have explicit evidence and context.
  Each family has a positive example and a malformed or foreign-endpoint refusal.
- Single, joint, broad and alternative reference cases remain distinct. Include external,
  attempted-unresolved, not-attempted, failed and search-limited outcomes.
- Two distinct claims share recall support. Repetition and duplicate-credit rules operate on
  claim identity and declared policy, not a prohibition on overlapping spans.
- Fidelity changes independently of target address; unavailable assessment is not a grade.
  Use the existing ADR 0030 supported-only coverage and counting policies.
- Include discontinuous spans, a non-BMP character, a repeated identical passage in different
  positions, exact rational times, an interval boundary and an integer beyond JavaScript's
  exact integer range. Keep occurrence identity distinct from text-content identity.

Fixtures establish construction, inspection and derivation behavior. Synthetic expected
labels are not evidence of extraction accuracy, calibrated probability or scientific validity.
Include at least one held-level versus distributed-amount temporal projection witness;
discourse order, story-world time, media time and measured recall time remain distinct.

## Ordered implementation slices

Each slice is sized for a coherent reviewed commit. Create or reuse a narrow Mote bead when
starting a slice, reserve its paths, and record the exact result there. Do not populate a
parallel status board in these documents. The indicative one-week budget is an initial
engineering timebox, not a delivery promise; an incomplete slice remains incomplete.

| Slice | Work and retained assets | Exit evidence |
|---|---|---|
| S1.0 — Freeze references and establish the shell | Verify the pinned source files in `references.json`; record expected behavior for every preservation row. Select admitted fixture bytes, freeze expected outputs, inventory affected legacy consumers, and establish the Python package and React app with pinned dependencies. Record the benchmark tasks before either implementation. | Runnable focused check commands; fixture/admission records; reference hashes; explicit legacy support list; benchmark protocol. No model calls. |
| S1.1 — Checked account and exchange | Implement exact coordinates, evidence bindings, source-local identities, occurrence identity, context-qualified source/recall accounts, the bounded relation families, reference variants and separate assessments. Use standard library facilities where they preserve the wire contract. | Positive and adversarial fixtures; deterministic read/write; independent expected values; strict refusal of foreign bindings. The graph witness reaches a saved artifact. |
| S1.2 — Recorded execution and analysis | Reuse or adapt the Python readers and scorer concepts; port the missing authoritative checks from Scala. Replay existing synthetic categorical recordings. Port bounded dispatch, record-before-parse, interruption/resume and atomic output behavior using a loopback provider fixture. Implement the three analysis views and the declared policy witnesses. | Same legacy outcomes and identities within the declared support list; no silent loss of unknown fields or statuses; exact request counts; replay with networking blocked; CLI/API row equality. |
| S1.3 — Inspect, save and reopen | Rebuild the narrow Atlas interaction in React: linked recall/source selection, exact support, alternatives, provenance, context/relation inspection, artifact-bound investigation and permission-checked export. Generate wire types from the Python-owned schema. | Executed browser path from file open through keyboard selection and save/reopen; text alternative; mismatched-artifact refusal; denied export creates no download. Export retains scientific values and policy provenance. Python–JSON–browser–Python coordinate roundtrip. |
| S1.4 — Measure and decide | Perform the matched changes below, cold-review the exact pilot commit and record a preservation audit. Run the installed package and built workbench from outside their source directories. | Reproducible evidence report, unresolved gaps, separate correctness and speed decisions, and a recommendation to continue, revise or stop expansion. |

S1.2 depends on S1.1. The basic workbench shell can develop against frozen fixtures after
S1.0, but its acceptance depends on the real S1.2 output. No synthetic-only UI substitute
counts as an integrated workflow.

## Implementation boundaries

- One installable Python package; ordinary internal modules, not a package per Scala module.
  CLI and notebook use the same public operations. Keep heavy optional model dependencies
  out of bundle inspection and basic imports.
- Python owns scientific admission and derivation. TypeScript owns display and interaction.
  Generated types describe shape; explicit validators establish cross-record relationships.
- Strict boundary validation, checked snapshot construction/updates, immutable collections
  where practical and adversarial tests replace reliance on Scala compile-time boundaries.
  This is a different guarantee: do not call Python objects unforgeable. The pilot does not
  relax rule 8 for the existing Scala library.
- Treat wire records as untrusted until validated against independently supplied expected
  source, recall and run identities. A checksum shipped beside content is not authentication.
- Preserve legacy UTF-16 coordinates and exact time encodings at the compatibility boundary.
  Serialize unsafe-size integers in an explicit lossless form; JavaScript must not round them.
- One versioned bundle is the exchange unit. Legacy categorical v2 inputs have an explicit
  importer/exporter profile. Rich pilot accounts use a separately versioned experimental
  schema; never relabel them as a legacy bundle or silently drop unsupported content.
- Preserve original legacy bytes and identifiers when retained; migrated structures name
  their source hashes and old/new IDs. Specify semantic parity separately from byte equality.
  Invocation accounting may differ while admitted outcomes remain identical.
- Reuse readable Python code after checking its behavior and attribution. Move selected code
  into the package rather than importing `tools/` through path hacks. Keep a small independent
  oracle or literal expected results outside the production validation helpers.

The [decision record](decisions.md) explains the alternatives. No public API or wire extension
to the existing Scala modules is part of this pilot. Experimental vocabulary stays under `port/`.

## Fair development-speed comparison

Historical qualification costs motivate the experiment; they do not establish developer
throughput. The reference F1 producer gate took 2,243.9 seconds and the Atlas gate 194.8 seconds.
Those numbers include broad verification, and neither is an edit-cycle measurement.

At S1.0, freeze two task specifications and their expected checks:

1. A supported semantic validation change: strengthen a source-bound reference rule, carry
   the resulting refusal through the public API and bundle, and expose it in inspection.
2. A supported interaction change: persist and restore one inspection setting while keeping
   exact artifact binding and exported scientific values unchanged.

Choose concrete cases both systems can already express, and write acceptance fixtures before
implementation. The rich-graph extension is a separate feasibility test where the current
end-to-end behavior is incomplete; do not invent a comparative timing for missing functionality.

Compare the port with a warm sbt process and focused JVM tests plus the necessary existing
Atlas UI check. Use a standalone reference clone, never gate the shared primary tree.
This is a credible focused Scala baseline, not a completed JVM-only/React architecture.
If results are close, evaluate that cheaper simplification before recommending a broad rewrite.

For each arm record revisions, machine/tool versions, dependency/cache state, commands,
implementation and review effort, failed attempts, defects found, elapsed time to the verified
visible result, and edit-to-focused-check latency. Repeat the machine feedback measurements
five times after one warmup and report median and range; report cold setup separately.
Counterbalance task order where practical. Human/agent effort from two tasks is descriptive,
not a general productivity estimate. Retain raw timings and the acceptance-equivalence review.

Report pilot construction, fixture migration and UI rebuilding costs separately. Do not
amortize them away or turn a faster small test suite into a claim about the full Scala suite.
An illustrative break-even count is migration effort divided by observed effort saved per
comparable change; report its assumptions and leave it unavailable when savings are uncertain.

## Acceptance and decision

Correctness is a prerequisite, not a weighted score. All Stage 1 preservation rows must have
named positive/refusal evidence, with no unresolved correctness blocker. Deferred assets remain
explicitly retained in Scala; passing this pilot does not retire them.

Required gates:

- The complete workflow above executes from the installed package and built workbench.
- Every requested recall unit is accounted for, including failures and unavailable results.
- CLI/API analysis rows agree exactly after declared ordering; unchanged replayed decisions,
  evidence and identifiers agree with the frozen legacy oracle within the supported profile.
- Context, identity, evidence, reference meaning, coverage and counting adversaries are refused
  or classified exactly as specified. Mutate at least one consequential guard and demonstrate
  that its named discriminating test fails while its positive control passes.
- Interrupted execution preserves recorded replies; invalid preflight dispatches zero requests;
  uncertain remote attempts do not silently retry; offline replay needs no credential or network.
- The UI opens real pilot output, preserves save/reopen state, refuses foreign artifacts and
  passes coordinate/large-number roundtrips. Presentation filtering never rewrites inference.
  Keyboard inspection and its text alternative expose the same selected evidence; denied
  export grants refuse before materializing a download. Unsupported grants never become allowed.
- Local checks include Python tests, one Python type checker, formatting/lint, schema drift,
  TypeScript checks and focused browser tests. Report totals, skips, commands and actual exits.
  Hosted CI and scientific qualification are separate claims.

Proposed speed criterion, to freeze before measurement: at least a twofold reduction in
median warm edit-to-focused-check latency for each matched task, and lower total effort to
reach the reviewed user-visible result for both tasks. Report the ratios independently;
do not hide a regression in one task with an average. This is a decision rule for a small
engineering pilot, not an assertion that the threshold has already been met.

- **Continue selectively:** correctness passes, the speed criterion passes, and migration cost
  looks recoverable for the remaining work. Choose the next user workflow, not a module census.
- **Revise the design:** correctness passes but speed is mixed, or validation/UI duplication
  grows. Adjust the small implementation or compare the focused Scala alternative.
- **Stop expansion:** a required distinction is lost, durable replay/inspection cannot be
  preserved economically, or the measured gain does not justify migration. Preserve the pilot
  and usable imported assets; do not delete the Scala implementations.

Full corpus coverage, provider accuracy, calibrated uncertainty, a complete Atlas replacement,
all AMR/kernel/decoder algorithms and retirement of existing APIs belong to later decisions.
The full event-graph ambition remains intact; Stage 1 proves a small working construction path.

## Evidence and handoff

Store small public commands, input/output hashes, test totals, timing tables and the decision
report in `port/evidence/`. Keep recordings, raw private logs and study material in ignored
`port/data/`. Preserve needed files in durable storage; an ignored directory must never hold
the only copy of source code or a result needed to reproduce the decision.

At handoff record the exact commit, Mote bead, completed acceptance cases, output locations,
open gaps and next slice. Browser work follows the root automation lifecycle and closes its
owned resources. Every dependency lock and task-specific tool stays inside `port/`.
