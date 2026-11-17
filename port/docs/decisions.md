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

The owner has authorized expansion toward full working capability; the roadmap sequences it.
Pilot speed criteria no longer gate that commitment. Evidence still determines correctness
and scientific/productivity claims. Expansion does not itself authorize merging, publication,
API retirement, data moves or replacing the deployed Atlas.

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

Partial alignment (2026-11-16): invert predicate/role indexes and admit only candidates with
declared predicate, participant, root-context and unique-row support. Preserve every qualifying
alternative; withhold resolution after retrieval overflow. Rejected alternatives: dense pair
matrices, a confidence-looking weighted score, automatic synonyms, or top-one selection that
hides ambiguity. Alias groups are explicit application inputs and not validated linguistic
equivalences. The method is a named heuristic, not a calibrated inference engine.
Reuse existing bindings and projections without changing address or fidelity; never infer
external content from no-match. Preserve existing references rather than overwriting decisions.
Keep event ambiguity unresolved when the row-level reference vocabulary cannot express it.

Python consolidation (2026-11-16): one domain model gains v3 independent accounts and an explicit
pairing operation; v1/v2 remain readable under their existing rules. Rejected alternatives:
dummy opposite-side worlds in new APIs or parallel SourceAccount/RecallAccount class trees.
Use an honest Python entity-origin name with a compatibility wire name, and explicit stable
identity namespaces rather than deriving identity from class names. Share strict JSON handling
and reuse already validated snapshots for hashing. Keep the existing common RecordedRun engine;
do not introduce another generic workflow layer solely to unify superficially similar profiles.

P2.1 source construction (2026-11-16): add anchored source requests and atomic per-row
extractive proposals, sharing quote/role/context admission with recall. Rejected alternatives:
hand-assembling more trusted fixture graph objects, accepting a provider-generated Account,
or coupling source construction to a participant. Keep source processing receipts in the
versioned SourceResult rather than silently changing Account v3's recall-inventory meaning.
The result and raw recording are the construction artifact; the account is its reusable graph.
This profile checks declared evidence/scopes, not semantic entailment, model agreement or
calibrated acceptance. States, relation families, identity and richer evidence remain P2 work.

P2.2 first relation slice (2026-11-16): a separate input-bound batch admits existing event
relation vocabulary over a checked account. Rejected alternatives: silently adding fields to
old provider schemas (which changes content-keyed replay prompts), or picking the first edge
when a proposed batch contains a cycle. Atomic batch rejection keeps contradictions visible.
The separate profile is a graph-enrichment operation, not a second account hierarchy or a
provider engine. Richer Scala interval/entity/hierarchy/causal semantics require a later explicit
version boundary; the current four kinds are not advertised as replacements for them.

P2.3 first text identity slice (2026-11-16): consume explicit form/type annotations in an
account-bound sidecar and expose verified identity through role rows. Rejected alternatives:
renaming local mentions in place, treating every non-pronoun label as a nominal without Scala's
checked chart classification, or treating omitted antecedent metadata as evidence of uniqueness.
Unknown types introduce separate singletons; unresolved mentions mint no referent. Identity is
within a document and does not move events between contexts. Number acquisition and accepted
holder inputs remain separate required extensions; this profile makes their absence explicit.

P3.1 recorded direct mapping (2026-11-16): private account-bound recordings and a separately
versioned unit-only prompt, with local detail aliases and conditional fidelity. Rejected alternatives:
whole-recall/global-index prompt leakage, graph-required localization and a second execution engine.
Indistinguishable visible details refuse rather than using hidden content to order their aliases.

P2.4 first rich accounts (2026-11-16): a distinct v4 envelope shares flat fields and relational
validation with v1–v3; explicit v3 upgrade preserves occurrence IDs. Rejected alternatives:
conditional serialization of new fields into old versions, duplicated validation engines, silently
dropping states in legacy consumers, or converting literal time phrases into plausible clocks.
Locations remain entity roles; time/manner are situation circumstances. Existing consumers must
explicitly support or refuse v4. This provisional migration boundary is not a permanent second
scientific model or a claim that all rich consumers have already been ported.

P2.5 rich detail integration (2026-11-16): admit recorded detail inventories against an existing
rich graph, keeping address/discourse and later reference/fidelity independent. Explicit v2
mapping envelopes share the resolver while preserving the v1 wire contract. Rejected alternatives:
downgrading away states, replacing prior details implicitly, labeling limited work complete, or
concatenating discontinuous quotes into words not present in the input. The acquisition profile
declares its full-snapshot input; it does not borrow the later mapper's unit-only claim.

P4.1 sparse sequence kernel (2026-11-17): separate supplied numerical states/costs/transitions
from account-specific evidence and graph transition builders. Use typed anchored modes and
external states, sparse edge lists, and content-derived state identity. Rejected alternatives:
requiring another graph framework or translating Scala's collection/typeclass hierarchy.
Keep wide Decimal log messages with centered binary64 log-sum-exp so a costly prefix can recover
after later evidence; float conversion precedes neither that cancellation nor Viterbi comparison.
Rejected alternative: ordinary float log-space alone, which erases 3:1 odds when equal-total-cost
paths trade a cost of 1e20 across units. This improves a numerical weakness without changing the
path-weight model. The versioned recipe declares near-tie handling, roundoff normalization and
finite binary64 absolute-log output limits. Posterior output is not calibrated confidence;
account mode gates, graph-derived transitions and refinement remain separate required work.

Public object validation (2026-11-17): validate supplied Pydantic instances recursively with
`model_validate` before serialization, then check canonical JSON and UTF-8 representability
through one private helper. Raw JSON retains duplicate-key/nonfinite parsing and strict schema
validation. Rejected alternative: serializing copied/constructed objects and
then validating the JSON. That can coerce Boolean numeric fields into integers/floats and drop
unknown fields before a validator sees them. The earlier sequence review exposed the defect;
`AlignmentPolicy.max_candidates=True` reproduced it in an older consumer. This repair changes
invalid-object refusal, not valid artifact schemas, identities or replay hashes. Typed mapping
instances retain their declared version instead of disguising a forged version through JSON.
Direct Python validation alone was also rejected: bare strings can contain lone surrogates that
the previous JSON reader refused, so type-only admission could reach recording I/O before a
later UTF-8 failure. Valid ASCII and supplementary Unicode remain accepted.

Rich relation consumers (2026-11-17): extend the existing admission/verification/table path
using explicit v2 proposal and result envelopes for v4 accounts; reuse the same atomic graph
checks. Rich inspection rows distinguish event and state endpoints. Rejected alternatives:
silently broadening the v1 event vocabulary, dropping rich collections to fit legacy receipts,
or maintaining a second graph engine. The four pilot relation rules remain deliberately
bounded; situation `part_of` does not replace Scala narrative segment hierarchy. Its full
construction and semantic contract remain owed.

Rich identity consumers (2026-11-17): include event and state participants in the existing
context classification and verified role readout. Annotation and identity-result schemas stay
unchanged because they refer to mentions and bind the full account digest without embedding an
event-only graph. Rich role rows explicitly name situation kind. Rejected alternatives: a second
identity resolver, dropping state placement, calling a state an event, or adding a new sidecar
version with no changed identity rule. Full accepted-holder, quantity and cross-modal contracts
remain owed; this extension does not infer them from nearby roles.

Rich structural candidates (2026-11-17): reuse the sparse local engine with same-kind predicate
and role postings before the retrieval budget. Expose separate state-attribute and circumstance
diagnostics without using them to choose reference or assign fidelity. Rejected alternatives:
filtering wrong-kind candidates after they consume the budget; treating unknown/unknown as
agreement; choosing an alternative by matching qualifiers; or labeling equal time phrases as
equal measured times. Rich v2 receipts preserve situation IDs/kinds and complete accounts while
legacy v1 artifacts stay unchanged. The conservative role/context/row gates are a named local
recipe, not a replacement for Scala distorted anchors or full semantic alignment.

Fixed-population claim assessment (2026-11-17): bind a separately declared question population
to the complete checked account snapshot, including existing mappings; derive accounting states
from attributed recall availability, support, counterevidence and unresolved reasons. Rejected
alternatives: deriving the denominator from successful extraction, changing existing fidelity,
turning an evidence-ownership check into semantic truth, or inventing accuracy fractions. Source
citation origin may differ from the assessed target; the semantic link remains attributed.
Content-derived question/citation IDs reject duplicate descriptions; adjacent citation spans
share identity while original UTF-16 boundaries are still checked. The first explicit text profile
refuses chart/playback fields pending their checked representations. Their Scala capabilities
remain owed, not retired. This avoids copying the Scala mapping class hierarchy into Python.

## Separate conditional fidelity over existing hypotheses (P3, 2026-11-17)

Provisional implementation choice: add a unit-local graded review that fills only absent/pending
assessment slots in the existing account. Reuse the same quote/grade validator as direct mapping,
with new request/reply/result/recording envelopes and the existing recorded executor. Expose
extracted claim content for the misread check, hide prior grades, and preserve reference and all
nonpending assessments. Rejected alternatives: converting fixed-question claim statuses into
grades, remapping during assessment, overwriting prior judgments, copying the validator, or
silently relaxing evidence ownership to reproduce a whole-film study script. Neighbor-aware and
cross-target graded assessment remain explicit future contracts. See [fidelity](fidelity.md).

## Explicit source scene grouping and correspondence control (2026-11-17)

Provisional implementation choice: group existing unparented source rows into exact-support
scene containers before pairing. Return an input-bound receipt with old-to-new row IDs because
parent membership is already part of unit identity. Preserve non-unit graph collections and
expose the resulting account to existing mapping/analysis consumers. Rejected alternatives:
manually building internal units in every workflow, using a hull that invents gap evidence,
changing the existing identity rule, or silently rewriting paired/mapped artifacts. This is a
small source grouping operation; full narrative hierarchy remains owed. The connected
[correspondence control](correspondence-workflow.md) executes both reference routes separately
and records which results are computed versus scripted.

## Source-derived transitions (P4, 2026-11-17)

Provisional implementation choice: one checked projection/transition module feeds the existing
numerical kernel, binding independent source and recall snapshots and supplied local costs.
Preserve explicit primary containment and optional canonical-text scoring; distinguish
discourse order from the narrated-world precedence cover. Use sparse dictionaries and
bounded-memory reachability rather than copying Scala's stored transitive closure. Rejected
alternatives: inferring hierarchy from generic part-of, implicitly using evidence offsets as
scoring features, comparing unrelated document coordinates, rebuilding the sequence kernel,
or claiming supplied costs establish admissibility. The versioned projection names its
first-occurrence ordering and cross-coordinate entity-neighbor rule. Refuse destination
populations missing the external component required by the mixture rather than silently losing
mass or changing the model. See [source transitions](source-transitions.md).

## Account-derived local evidence (P4, 2026-11-17)

Provisional implementation choice: one content producer derives sparse nominations, shallow
mode gates and support-aware costs, then reuses the existing transition producer and sequence
kernel. Reuse Scala lexical assets and default arithmetic; record unknown slots and imputation
explicitly. Aggregate all same-role participants and use set-valued sensory terms to remove
incidental ordering and duplicate-term effects. Use wide intermediates for weighted scaling.
Rejected alternatives: requiring hand-built cost matrices, admitting faithful modes through
a cost override, selecting the first of multiple recall situations, treating absent charts as
zero distance, equating candidate failure with categorical external reference, or adding an
embedding/service dependency before the content contract works. The named profile and its
remaining capability obligations are in [local evidence](local-evidence.md).
