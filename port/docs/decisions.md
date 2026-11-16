# Stage 1 design decisions

These are provisional choices for the authorized pilot, dated 2026-11-16. They do not
supersede the production ADRs. Revisit a choice when a concrete fixture or workflow refutes
it, and record the reason here. The owner decides changes to the product commitments.

| Decision | Rationale | Rejected alternative |
|---|---|---|
| Keep all pilot material in `port/`, with the existing Mote store. | Gives the experiment a visible boundary and preserves one source of work status. | Scattered scripts in existing modules or a second tracker. |
| One Python distribution and one React/TypeScript app in one repository. | Lets a behavior change carry its computation, schema, tests and inspection together. | Translating every Scala module into a separately released Python package. |
| Preserve contracts and behavioral fixtures before simplifying implementations. | The valuable scientific and operational lessons already exist. | Treating smaller code or a clean rewrite as evidence of correctness. |
| Python owns admission and analysis; TypeScript owns interaction. | Keeps one authority for scientific values. | Independent frontend estimators, renormalization or hidden confidence filtering. |
| Start with a static bundle inspector and precomputed projections. | Preserves offline inspection and avoids requiring an application service for the pilot. | Introducing FastAPI, a job queue or database before an interaction needs it. |
| Strict wire schemas plus explicit contextual validation. | Field types cannot prove source identity, containment, context or provenance relationships. | Assuming Pydantic, a frozen object or generated TypeScript makes data valid. |
| Retain legacy identity/coordinate meaning through an explicit compatibility adapter. | Existing recordings, outputs and tests are assets. | Silently replacing UTF-16 with Python indices or rewriting all artifact IDs. |
| Version rich pilot bundles separately from the legacy categorical profile. | Enables new semantics without misrepresenting old-reader compatibility. | Adding fields to legacy v2 and assuming all consumers understand them. |
| Preserve source/recall event structure in the first discriminating fixture. | Tests whether the redesign can carry the actual product ambition. | A scene-only mapping demo that avoids contexts, relationships and detail grouping. |
| Use files and deterministic snapshots as the initial persistence boundary. | Existing bundles and receipts support offline replay and inspection. | An ORM or graph database as a prerequisite for the first useful result. |

Tooling defaults for S1.0: `uv` with a locked Python environment; typed Python with one
type checker; pytest and focused property tests; Ruff for formatting/lint; React, TypeScript
and Vite; one Node package manager with a committed lockfile. Pydantic is a candidate for
wire schemas, not a relational proof system. Pin compatible versions when scaffolding and
record the chosen versions in the locks. No dependency is installed by this planning change.

Generated JSON Schema and TypeScript declarations cover exchanged shapes. Keep generation
deterministic and check drift. Offline browser loading also needs runtime validation and
the applicable artifact/relationship checks; TypeScript's erased types cannot validate a file.
Their acceptance fixtures come from the same contract, with independent expected values.

Python cannot reproduce the existing Scala compile-refusal guarantees. The experiment uses
checked admission and update operations plus validation at trusted consumption boundaries,
including notebook-generated objects. Private names and frozen models are conventions, not
security or unforgeability. This tradeoff is explicit and limited to the pilot.

Domain records should be concrete and small. Prefer immutable IDs and ordinary record
collections over a universal graph framework. Use sparse supported hypotheses, never all
possible referent sets. Errors at public boundaries have stable categories and useful
locations; do not force every internal function into a new monadic framework.

Freeze expected behavior before timing. Keep exact parity cases separate from deliberately
changed schemas/estimators. An unsupported legacy feature is a typed refusal with a reason,
not dropped data or an invented neutral value.

The first expansion decision belongs after Stage 1 evidence. Passing the pilot authorizes
a recommendation; it does not itself merge, publish, retire APIs, move data or replace Atlas.
