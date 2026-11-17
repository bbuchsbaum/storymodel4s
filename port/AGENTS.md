# Port implementation contract

The destination is full working StoryModel4s capability in idiomatic Python, not a permanently
reduced subset. Small slices sequence the work; they do not cut scope. Every capability must
be ported, improved, or excluded with evidence that it is broken, redundant or stale.
Deferred functionality remains owed; passing pilot tests are not proof of full parity.

The owner wants fast, correct progress toward behavioral parity with StoryModel and
StoryAtlas, with freedom to improve the design. Preserve their strongest semantics and
working behavior; use fresh eyes to remove cruft and unnecessary machinery.

## Work priority

1. Computational representations, algorithms, validation and analysis are the first priority.
2. Deliver small executable paths through the Python API and CLI, with meaningful checks.
3. Establish the web scaffold and exchange boundary; defer visual refinement until the
   computational account is useful. A polished mock is not computational progress.

## Fast implementation

- Read the relevant implementation and its behavioral tests, then build. Do not repeatedly
  redesign the plan or turn routine decisions into approval requests.
- Reuse proven Python code and portable fixtures when they fit. Translate behavior, not
  Scala's module tree, typeclass machinery or class count.
- Prefer one straightforward implementation and ordinary modules. Add abstractions,
  dependencies and extension points only when an actual use case needs them.
- Improve mistakes rather than preserving them for superficial parity. State the changed
  behavior, rationale and version boundary, and retain a discriminating test.
- Keep a short record of supported behavior and gaps. Do not claim parity from test counts,
  similarly named types, a UI screenshot or a successful import.
- Use targeted tests, one type checker and formatting/lint for the changed Python scope.
  Do not run Scala's full cross-platform gate for Python-only changes. Use pinned Scala
  outputs and independent oracles where they establish the relevant behavior.
- Measure feedback latency without delaying implementation for a broad benchmarking project.
  A speed claim still needs a fair, separately recorded comparison.
- Commit coherent working slices on the task branch. Review consequential semantics cold;
  do not create a review bureaucracy around reversible formatting or scaffolding.

## Correctness that survives the port

- Evidence, occurrence identity, contexts and coordinate systems stay explicit. Python
  indexing is not UTF-16 indexing; raw text identity is not canonical text identity.
- Keep source and recall accounts distinct. A source account must not depend on one
  participant's recall. Repeated text is not automatically the same occurrence.
- Preserve single/joint/broad/alternative reference and explicit processing outcomes.
  Missing or unresolved evidence is not zero, external content or a neutral score.
- Separate expressed content, target address, fidelity, phenomenology and analysis policy.
  Retain the accepted ADR 0030 semantics and declared aggregation/allocation rules.
- Validate relationships against their actual account at public consumption/update
  boundaries. Strict schemas and frozen objects alone do not establish validity.
  Python does not promise Scala's compile-time unforgeability.
- Preserve recorded replies, input-bound resumability and offline replay. No hidden model
  calls, silent retries of uncertain requests or credentials in artifacts.
- Scientific values belong to Python; the UI inspects them. Preserve artifact-bound
  investigation state and export grants when implementing the corresponding UI features.
- Changes to methods or semantics are versioned and described. Engineering examples do not
  establish accuracy, calibrated uncertainty or scientific validity.

## Scope and evidence

Keep all port source, configuration, locks, documentation, tests and evidence under `port/`.
Use the root Mote store and follow the root rules for data admission, browser ownership,
unrelated changes and publication. The Scala implementation remains a reference; do not
remove it or change its contracts as a side effect of port work.

New narrative fixtures need the existing admission process before commit. Prefer admitted
synthetic examples. No participant data or paid provider call is required for the first
computational slices. Keep independent expected outputs outside production helpers.

The Stage 1 plan is a guide to acceptance. Computational slices precede UI polish by the
owner's instruction on 2026-11-16. Report actual coverage and remaining gaps honestly, and
continue with the next useful computational slice rather than manufacturing infrastructure.
