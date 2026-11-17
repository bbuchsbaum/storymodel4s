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
| Build computational API/CLI paths first, with a static inspector to follow. | Owner prioritizes representations and algorithms; offline inspection needs no service. | Spending the initial slice on UI polish, FastAPI, a job queue or database. |
| Strict wire schemas plus explicit contextual validation. | Field types cannot prove source identity, containment, context or provenance relationships. | Assuming Pydantic, a frozen object or generated TypeScript makes data valid. |
| Retain legacy identity/coordinate meaning through an explicit compatibility adapter. | Existing recordings, outputs and tests are assets. | Silently replacing UTF-16 with Python indices or rewriting all artifact IDs. |
| Version rich pilot bundles separately from the legacy categorical profile. | Enables new semantics without misrepresenting old-reader compatibility. | Adding fields to legacy v2 and assuming all consumers understand them. |
| Preserve source/recall event structure in the first discriminating fixture. | Tests whether the redesign can carry the actual product ambition. | A scene-only mapping demo that avoids contexts, relationships and detail grouping. |
| Use files and deterministic snapshots as the initial persistence boundary. | Existing bundles and receipts support offline replay and inspection. | An ORM or graph database as a prerequisite for the first useful result. |

Tooling defaults for S1.0: `uv` with a locked Python environment; typed Python with one
type checker; pytest and focused property tests; Ruff for formatting/lint; React, TypeScript
and Vite; npm with a committed lockfile. The first slice uses Pydantic 2 for strict wire
records, with ordinary contextual validators, and mypy for static checks. Alternatives
rejected for this slice: hand-maintained JSON Schema and duplicate validation with a second
model library. Exact installed versions are recorded in `uv.lock` and `web/package-lock.json`.
Pydantic is not a relational proof system.

The initial rich account uses a small experimental `storymodel.account/v1` vocabulary and
content-derived IDs, separate from legacy Scala IDs. Rejected alternative: pretending a
new representation is byte-compatible with StudyBundle v2. Legacy reads retain raw fields
and require an independent caller-supplied byte digest; rich-to-legacy writing is deferred.
Explicit per-unit extraction inventories distinguish completed zero-detail results from
missing processing. Missing inventories must not become evidence of source omission.

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

First computational workflow (2026-11-16): port the strict film/time interpretation using
supplied recall units and independent numerical source registries, with recorded raw text
and exact arithmetic. Rejected alternative: converting derived golden rows back into supposed
input replies. The fixture copies the original Scala raw answer constants; computation never
reads expected outputs. A distinct result profile preserves coarse categorical semantics
without inventing detail atoms, fidelity or source-row coverage. The new prompt/framing recipe
rejects duplicate JSON keys and nonfinite values; historical harness prose extraction is not
silently claimed as compatible. Source/recall keys and all execution settings bind replay.

Input preparation (2026-11-16): add a checked preparation artifact containing native source
timing and a raw recall receipt, with a derived property that produces the existing replay
request. Rejected alternatives: a second mapping pipeline, or trusting a stored request next
to unrelated raw inputs. Keep the earlier supplied-request schema/digest unchanged. Native
timing accepts exact numeric strings and derives film/scene target membership from rows;
reject final intervals reversed by zero anchoring even where Scala's pre-anchor guard permits
them. Port only surface/clause segmentation now, with an explicit recipe and retained gaps;
do not turn lexical heuristics into asserted event/detail semantics.

Recorded extraction (2026-11-16): use an extractive, unit-local proposal contract with exact
quotes, explicit context scopes and atomic per-unit admission. Rejected alternatives: model
responses containing trusted complete accounts, or guessing missing roles/contexts/addresses.
The resolver establishes structural and evidence consistency, not semantic entailment.
Retain unresolved claims outside the accepted graph and withhold completed inventory status.
Source graphs remain independent; extraction makes no source identity or fidelity assertions.

Account v2 expands the existing entity origin to recall documents; retain the historical
`source_document` field and content IDs. Rejected alternatives: silently weakening v1,
renaming identity-bearing fields, or adding a parallel recall-entity class hierarchy.
V1 admission remains unchanged. Recall-origin entities must remain in their originating
session's events; the extractor only accepts local mention references. Scope belongs in the
extraction proposal/receipt, separately from the account context's attribution evidence.
