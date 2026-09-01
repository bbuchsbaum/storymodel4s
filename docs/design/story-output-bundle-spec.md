# Story Output Bundle and Browser Report Specification

Status: board-synthesis draft for `bd-01M199WBM2YVZ3GVC2GA61TYCJ`; proposals are labelled

Primary discussion: `story-output-format-and-visualization`

Normative keywords: MUST, MUST NOT, SHOULD, MAY

## 1. Purpose

This specification defines the durable output of a storymodel4s invocation and the contract of its browser-readable report. It separates four questions that must never be collapsed:

1. What input bytes were admitted and how were they decoded and canonicalized?
2. What semantic result did the scientific pipeline establish?
3. Which reports were successfully lowered from that result?
4. Whether the complete bundle was published to its requested destination.

The output is a bundle of role-labelled artifacts, not one overloaded file. A report failure MUST NOT erase a valid semantic model. A provider or validation refusal MUST NOT mint an empty successful model. A missing or unestablished denominator MUST NOT become a coverage rate. A filesystem publication failure MUST NOT rewrite any scientific or report outcome.

## 2. Authority and ownership

### 2.1 Owner-derived local-open minimum

The following clauses are owner requirements:

- the bundle has an obvious `preview.html` entry point;
- after obtaining the bundle, a reader can open it directly from the filesystem by ordinary browser action;
- opening requires no build step, terminal command, local web server, or installation;
- the canonical preview is pure, self-contained HTML/CSS, with inline SVG permitted;
- the canonical preview contains no JavaScript and automatically loads no external resource.

Canonical conformance therefore forbids script elements, inline event handlers, runtime JavaScript
data records, stylesheet imports, and resource-bearing elements or CSS URLs that load bytes outside
`preview.html`. Ordinary outbound hyperlinks MAY identify publications because following them is a
separate reader action. All essential initial content, status, evidence, projection inventories,
navigation targets, and textual twins are present in the self-contained document.

### 2.2 Repository ownership

- `acquire` owns the renderer-independent acquisition account: source identities, target-universe
  establishment, stage attempts, resolution outcomes, and semantic completion state.
- `view` owns projection capability and per-request accounting, provenance-derived `ViewBasis`, and
  renderer-neutral report outcomes. It composes acquisition state but does not mint claims.
- `codec` owns canonical encoders and decoders for the deliberately closed result, report, and
  manifest roots after the dependency decision in §2.3.
- The manifest vocabulary owns artifact roles, relative paths, media types, byte lengths, and
  checksums. It references scientific artifacts; it does not contain their payloads.
- StoryAtlas owns renderer adapters, browser interaction, layout, transactional bundle publication,
  and CLI presentation. It consumes compiled scientific state and MUST NOT manufacture a
  scientific judgment. Its delivery outcome is separate from scientific and report outcomes.
- `StoryModelCodec` remains the sole canonical JSON encoding of a `StoryModel`. No bundle artifact
  may duplicate or redefine that semantic payload.
- The sibling repositories remain independently versioned. Cross-repository acceptance evidence
  MUST name the exact provider and consumer SHAs.

### 2.3 Proposed dependency decision

The dependency-safe baseline is:

- `acquire` defines the acquisition account and its source, universe, stage, and semantic outcome
  values;
- `view` defines projection/report outcomes and the composed story-output result, which contains an
  acquisition account and provenance-derived view state;
- `codec` intentionally depends on `view` and provides the canonical result and manifest codecs;
- StoryAtlas consumes the encoded roots and owns byte/file assembly.

This edge is acyclic in the current build graph and avoids a new module. It still requires the
chief's explicit dependency/ADR ruling before implementation. A dedicated output-codec module is
the fallback only if that ruling rejects `codec -> view`. Implementations MUST NOT replace typed
basis, projection, or outcome values with strings or erased JSON merely to avoid the ruling.

The output contract does not introduce an `acquire -> embed-core` dependency. Acquisition failures
refer to stage records, provider calls, and a renderer-independent output receipt/ref defined in
`core`/`acquire`; report failures use a report receipt defined in `view`. The similarly named
`embed-core.AttemptReceipt` remains specific to embedding attempts.

Here, "closed" means that the schema names the finite payload families it accepts, or provides an
explicit typed extension case. It does not accept arbitrary `Any` values or unclassified JSON.

### 2.4 Decision status in this draft

The board has converged on the bundle/report split, reuse of `StoryModelCodec`, the three text
identities, typed universe state, independent semantic/report/invocation outcomes,
provenance-derived view basis, canonical standoff annotation, separate coordinate projections, and
HTML as a report rather than an interchange parser. The owner-derived local-open minimum is settled
separately in §2.1.

The following details remain proposals until the chief records them in the epic or an ADR:

- the exact seven conventional paths in the minimal bundle;
- any future interactive profile in §3.2;
- the exact public ADT names and approval of the proposed module dependency edge;
- the print profile and canonical browser matrix;
- the exact noncanonical interaction and data packaging strategy, if later authorized.

Normative keywords in a proposed section describe what adoption would require. They do not imply
that the proposal has already been ratified.

## 3. Bundle profiles

### 3.1 Base local-open profile

Every successful base-profile outcome satisfies the owner-derived clauses in §2.1. Its
`preview.html` is self-contained HTML/CSS with optional inline SVG, contains no JavaScript, and
automatically loads no external resource. Other bundle artifacts remain separately addressable
scientific and report records; the preview does not fetch them at runtime. The base profile is
always requested, but it is not always satisfied: a failed or nonconforming `preview.html`
lowering produces an auditable bundle with a failed local-open profile outcome, not a false
conformance claim.

Every local-open outcome carries a disposition receipt, including `not_attempted`. The receipt binds
the profile schema and policy versions, verifier identity and version, execution identity, typed
causal prerequisites, the exact satisfied/failed/not-attempted decision content, and enumerated
court outcomes. A satisfied receipt additionally binds the
exact `browser_preview` role, `preview.html` path, media type, checksum, and every required local
asset role and checksum. Produced preview bytes are necessary but insufficient: a renderer may
produce `preview.html` while a court fails, in which case the report remains produced and the
profile outcome is failed.

### 3.2 Deferred interactive profile

An interactive JavaScript-capable profile is deferred and noncanonical. It requires new owner and
ADR authority before it can be specified, implemented, or required for conformance. If admitted
later, it is a separate report/profile outcome and cannot weaken, replace, or become a prerequisite
of the canonical pure-HTML/CSS preview. Browser-side code can never become semantic authority.

## 4. Artifact roles and presence

### 4.1 Minimal text-success bundle

A minimal validated text invocation has these seven top-level roles:

| Conventional path | Role | Presence | Authority |
|---|---|---|---|
| `manifest.json` | bundle manifest | always, when bundle finalization succeeds | artifact inventory and integrity |
| `source.txt` | admitted original bytes | when input bytes were obtained | byte identity only |
| `canonical.txt` | canonical model-coordinate text as UTF-8 | whenever `StorySource` construction succeeded | standoff coordinate source |
| `result.json` | invocation/result account | always, when result serialization succeeds | scientific pipeline and report outcomes |
| `storymodel.json` | semantic model | for validated semantics, or optionally for a coherent receipted draft under `Partial` | canonical `StoryModelCodec` interchange; validation status remains in `result.json` |
| `preview.html` | browser report | only when that report is `Produced` | human report, never semantic interchange |
| `preview.txt` | deterministic textual report twin | only when independently `Produced` | accessible and diffable report |

Optional sidecars, report assets, figures, PDFs, and declared-loss exports add manifest entries. They
do not change the authority of the seven roles above.

### 4.2 Independent report outcomes

`preview.html` and `preview.txt` are independent report products. Failure of either MUST leave every already-produced artifact intact and auditable. If semantic validation succeeded and HTML lowering failed, `storymodel.json` remains present and unchanged while `result.json` and `manifest.json` record the HTML failure.

If semantic validation was partial, `storymodel.json` MAY preserve a coherent receipted draft in
the canonical `StoryModelCodec` form; its presence does not promote that draft to validated. The
partial result branch remains authoritative and carries nonempty gaps. If no coherent draft was
produced, the role is absent. If semantics were refused, the role is absent. A produced report may
still explain partiality or refusal, show admitted source text, and render proposal, alternative,
rejection, unresolved, or gap inventories without borrowing the visual grammar of accepted
narrative claims. A parallel draft graph MUST NOT be smuggled through a generic payload when this
declared role is absent.

### 4.3 Finalization and checksum cycles

The manifest is finalized after all other artifact outcomes are known. It binds final produced
bytes and failed/not-produced roles but MUST NOT require another artifact to embed the manifest's
own byte checksum. Because `result.json` records report checksums, report bytes MUST NOT embed the
checksum or canonical bytes of `result.json` or `manifest.json`; that would create a cycle. Report
packets use stable invocation, acquisition, model, view, layout, and software identities instead.
Manifest finalization then closes over the emitted files.

### 4.4 Proposed core role tags

The closed core artifact-role family uses these wire tags:

- `original_source` for `source.txt`;
- `canonical_source` for `canonical.txt`;
- `invocation_result` for `result.json`;
- `semantic_model` for `storymodel.json`;
- `browser_preview` for `preview.html`;
- `text_preview` for `preview.txt`;
- `report_asset`, `projection_packet`, `optional_report`, or `declared_loss_export`, each with a
  separate stable artifact identity, for extensions to the minimal bundle.

`bundle_manifest` identifies `manifest.json` at the root but is not a self-checksummed entry. Core
tags are enum cases, not free strings. A custom artifact role requires an explicit namespace and
label and never acquires the authority of a core role.

## 5. Three text identities

The bundle MUST carry and distinguish:

1. **Original byte identity**: exact input bytes, byte length, byte digest, declared/observed media type, charset decision, and BOM disposition. `source.txt` preserves these bytes exactly.
2. **Decoded raw-string identity**: the string produced by the admitted decoding decision, with its own digest and explicit decode receipt.
3. **Canonical model-string identity**: `StorySource.canonicalText`, its checksum, and the named/versioned canonicalization policy.

The manifest and result account bind all three identities and their transformation receipts. Byte
digests use the existing lowercase-hex SHA-256 `Checksum` contract. String digests apply that same
contract to the exact UTF-8 encoding used by `Checksum.ofText`; they do not hash an unspecified
platform encoding. The report may claim equality between `source.txt` and the canonical DOM text
only when canonicalization is proved to be the identity transform for that invocation.

Whenever `StorySource` construction succeeds, `canonical.txt` contains the exact UTF-8 encoding of
`StorySource.canonicalText`, independent of semantic resolution and every report outcome. If intake
fails before `StorySource` construction, the role is absent and that absence is accounted by the
acquisition outcome. This remains required when semantics are partial/refused or report lowering
fails, because standoff coordinates are uninterpretable if only the checksum survives. It
duplicates no semantic graph or claim authority. The file has no BOM, gains no terminal newline,
and undergoes no filesystem line-ending conversion. Its byte SHA-256 MUST equal
`StorySource.canonicalChecksum` from `Checksum.ofText`.

The proposed v1 canonicalization policy id is `storysource-canonical-text/v1`, matching the current
`StorySource.canonicalize` contract exactly: convert CRLF and CR to LF; remove trailing spaces and
tabs from each line; collapse runs of three or more LF characters to two; then remove leading and
trailing LF characters. It performs no Unicode normalization and does not otherwise trim or rewrite
characters. A policy implementation change requires a new id.

Decoding is strict by default: malformed or unmappable byte sequences produce a typed refusal with
the byte position and charset receipt. A profile may admit replacement only through a separately
named policy that records every replacement position and original byte range; the default must not
silently substitute U+FFFD.

Every semantic span uses zero-based half-open UTF-16 code-unit offsets into the strict-UTF-8-decoded
`canonical.txt` string. They are not UTF-8 byte offsets or Unicode code-point indices. The canonical
identity records both emitted byte length and UTF-16 code-unit length, and decoding verifies both.
Original byte offsets, decoded-string offsets, canonical UTF-16 offsets, and rendered device
coordinates MUST NOT be substituted for one another.

Equal checksums do not collapse identity roles. Original bytes, decoded raw text, and canonical
text retain distinct typed identities and receipts even when two exact contents happen to match.

## 6. Canonical standoff markup

### 6.1 Semantic contract

Canonical annotation is standoff against `StorySource.canonicalText`.

- Discontinuous `SpanSet` support remains one semantic support value.
- Overlapping annotations and multiple claims over the same text do not require a nesting order.
- Reentrancy and graph relations remain graph identity, not tag structure.
- Presentation wrappers, SVG marks, and fragment anchors are regenerated views and are never parsed as semantic authority. Runtime JavaScript data records are forbidden in the canonical profile.

### 6.2 Inline and external exports

An inline markup export MAY be offered only as a declared-loss exporter. Its loss record MUST state every split, dropped overlap, flattened relation, rewritten offset, unsupported identity, or refused construct. It MUST NOT replace `storymodel.json`, source identity, or canonical evidence coordinates.

## 7. Result and outcome model

Exact public type names require ADR approval. The distinctions below are normative.

### 7.1 Invocation account

The renderer-independent acquisition account records:

- invocation identity and schema version;
- the three source identities and transformation receipts;
- requested configuration and provider fingerprints;
- stage attempts, alternatives, rejections, unresolved items, exclusions, failures, and receipts admitted by the closed result schema;
- universe state;
- semantic outcome;
- software, configuration, and dependency identities.

The composed story-output result adds every requested projection/report outcome and the
provenance-derived view basis. The distinction is structural: `acquire` does not depend on `view`,
and a report failure cannot rewrite the acquisition account.

A generic `CandidateLedger[A]` is not by itself a wire specification. The result schema MUST define a closed admitted-payload family or a typed extension boundary with canonical encodings.

### 7.2 Universe state

The target universe is either:

- `Established(ids, definitionIdentity)`, or
- `Unestablished(reason, receipt)`.

When established, every member id MUST be accounted exactly once by a typed disposition under that
universe definition. When unestablished, downstream absence, success, failure, eligibility, and
coverage rates MUST NOT be computed. The report states that the denominator is unavailable.

`Established(empty, definitionIdentity)` is a valid zero-eligible result and is distinct from `Unestablished`.

The validating constructor for an established universe MUST enforce these laws:

- `definitionIdentity` identifies the exact eligibility definition and is never synthesized from
  the member count;
- target ids are unique under their domain equality;
- an empty id vector is accepted;
- no failure reason is stored in the established branch;
- the unestablished branch carries a typed reason and optional attempt receipt, but no ids.

Any coverage helper accepts only the established branch. It divides by the established member count
only when that count is positive. For an established empty universe it returns a typed
not-applicable result, not `0 / 0`, `0%`, or `100%`. No such helper exists on `Unestablished`.

### 7.3 Semantic outcome

The semantic outcome distinguishes at least:

- a validated model reference;
- a partial scientific result with explicit nonempty gaps and an optional coherent draft-model
  reference; the optional artifact uses canonical `StoryModelCodec` bytes but is never described as
  validated;
- refusal with typed errors and receipts.

No CLI exit code substitutes for this content.

### 7.4 Report outcome

Each requested report has exactly one independent outcome that distinguishes at least:

- produced artifact identity, checksum, media type, and receipt;
- failed lowering/verification with typed error and receipt;
- not attempted, with a typed reason and any available attempt receipt.

Reports that were not requested do not acquire outcomes. The request set itself is carried and
canonically ordered, so omission cannot masquerade as either `NotAttempted` or failure.

A report outcome MUST NOT be inferred from whether a file happens to exist.

### 7.4.1 Cross-field construction laws

The result root constructor MUST reject these combinations:

- `SemanticOutcome.Validated` without a canonical semantic-model checksum and schema identity;
- partial semantics whose optional draft reference and produced `storymodel.json` disagree, or
  refused semantics that claim a produced `storymodel.json` role;
- two outcomes for the same requested report identity;
- a produced report without an artifact checksum and receipt;
- a failed report that also claims produced bytes;
- a provenance basis unsupported by the admitted invocation provenance;
- coverage or absence summaries when the universe is unestablished;
- an established universe whose target dispositions omit or duplicate an id.

The constructor returns accumulated domain errors when independent fields are invalid. It does not
throw, silently normalize, choose one duplicate, or keep the last map entry.

### 7.5 Projection and requested-layer accounting

Every requested projection/layer pair is accounted exactly once. The result must distinguish these situations even if final enum names differ:

- produced, with mark/inventory identity and counts;
- established empty, when the admissible source relation set is known and empty;
- filtered or suppressed, with reason and counts for horizon, zoom, endpoint visibility, or layout policy;
- unsupported projection/layer pair;
- failed compilation/lowering, with error and receipt.

Retaining a request bit or changing a configuration checksum is provenance, not proof that the request was fulfilled visually.

### 7.6 Illustrative type shape, not approved public names

The following sketch shows the required separation. The names and module placement remain subject
to the ADR decision in §2.3.

```scala
enum TargetUniverse[+Id]:
  case Established(value: EstablishedUniverse[Id])
  case Unestablished(failure: UniverseFailure)

enum RefusedSourceProgress:
  case BeforeIntake
  case Admitted(original: OriginalSourceIdentity)
  case Decoded(original: OriginalSourceIdentity, decoded: DecodedSourceIdentity)

final class DecodeReceipt private (
    val id: OutputReceiptId,
    val decoder: DecoderId
)

final class DecodedSourceIdentity private (
    val utf16Length: Int,
    val checksum: Checksum,
    val decodeReceipt: DecodeReceipt
):
  def decoder: DecoderId = decodeReceipt.decoder

enum SourceOutcome:
  case Constructed(identities: SourceIdentities)
  case Refused(progress: RefusedSourceProgress, failure: OutputFailure)

enum SemanticOutcome:
  case Validated(model: SemanticModelRef)
  case Partial(gaps: NonEmptyVector[ResultGap], draft: Option[SemanticModelRef])
  case Refused(errors: NonEmptyVector[OutputFailure])

enum ReportOutcome:
  case Produced(artifact: ArtifactRef, receipt: ReportReceipt)
  case Failed(error: OutputFailure, receipt: ReportReceipt)
  case NotAttempted(reason: NotAttemptedReason)

final class AcquisitionAccount private (
    val source: SourceOutcome,
    val universe: TargetUniverse[TargetId],
    val semantic: SemanticOutcome,
    val provenance: InvocationProvenance
)

final class StoryOutputResult private (
    val acquisition: AcquisitionAccount,
    val reports: Vector[RequestedReportOutcome],
    val basis: Option[ViewBasis]
)

final class PreparedBundle private (
    val result: StoryOutputResult,
    val manifest: BundleManifest,
    val artifacts: Vector[PreparedArtifact]
)

enum BundleDeliveryOutcome:
  case Published(bundleId: BundleId, target: TargetIdentity, receipt: DeliveryReceipt)
  case Refused(code: DeliveryFailureCode, receipt: DeliveryReceipt)
```

`EstablishedUniverse`, `AcquisitionAccount`, `StoryOutputResult`, and manifest roots are validating non-case classes when
they enforce cross-field invariants. A bare or qualified-private case-class constructor is not an
acceptable boundary: generated construction or `copy` machinery can bypass validation.

`PreparedBundle` is also a validating non-case root. `BundleDeliveryOutcome` is returned by the
publisher and is not embedded back into the immutable prepared bytes.

### 7.7 Proposed `result.json` wire root

The proposed canonical root has these fields, in this logical structure:

```json
{
  "schemaVersion": "story-output-result/v1",
  "invocationId": "...",
  "source": { "status": "constructed", "identities": {} },
  "scientificArtifacts": {
    "originalSource": {},
    "canonicalSource": {},
    "semanticModel": {}
  },
  "acquisition": {
    "universe": {},
    "semantic": {},
    "targets": [],
    "payloads": [],
    "buildReceipt": null
  },
  "viewBasis": null,
  "projectionOutcomes": [],
  "reportRequests": [],
  "reportOutcomes": []
}
```

This example fixes field roles, not pretty-printed bytes. Canonical bytes come from the sorted-key
codec. The source branch carries the three identities and receipts from §5; the separate
`scientificArtifacts` object closes their emitted artifact references without embedding payload
bytes. `viewBasis` is `null` only when no scientific view requiring a basis was produced; it is
never a caller-supplied free label.

Filesystem target and publication status are deliberately absent from this root. They belong to
the publisher's `BundleDeliveryOutcome` (§11.1), so retrying publication at a different target
cannot change scientific or report bytes.

The source identity objects have these minimum fields:

- `original`: byte length, checksum, admitted `mediaType`, declared charset if any, selected
  charset, BOM disposition, and the intake receipt;
- `decoded`: UTF-16 code-unit length, UTF-8 string checksum, and a typed decode receipt that binds
  its receipt id to the admitted decoder implementation, policy, and configuration identity. The
  exposed decoder identity is derived from that receipt; it is never a caller-selected parallel
  label. If the wire repeats the decoder identity for readability, checked reconstruction requires
  exact equality with the receipt binding and rejects a mismatch. Its admitted original identity
  retains the selected charset and BOM decision alongside it;
- `canonical`: UTF-8 byte length, UTF-16 code-unit length, UTF-8 string checksum,
  canonicalization policy identity, and canonicalization receipt.

Strict decoding and `StorySource` construction are separate fallible stages. In particular,
strict UTF-8 whitespace bytes establish a decoded string identity before `StorySource.fromText`
refuses empty canonical content. The refused branch therefore carries a closed progress type:
before intake, admitted original bytes, or admitted original plus successfully decoded identity and
receipt. Its construction laws make decoded progress imply original admission and forbid canonical
identity. The constructed branch alone carries all three identities. No completed identity or
receipt disappears merely because a later stage refused.

The original and canonical objects refer to their artifact roles rather than embedding bytes.
The decoded raw string is not duplicated in `result.json`; it remains recoverable from original
bytes plus the admitted decoding decision and is independently checksum-bound.

A complete artifact reference contains stable artifact id, role, byte length, checksum, media
type, and schema identity when the artifact has a schema. `scientificArtifacts` contains the
complete `original_source`, `canonical_source`, and, for validated semantics or a preserved partial
draft, `semantic_model` references. These are cross-root references, not informational copies:
§11.1 requires them to resolve bijectively against `manifest.json`.

Closed alternatives use a `status` discriminator plus the fields of that branch:

- source: `constructed` with all three identities; or `refused` with typed progress
  (`before_intake`, `admitted`, or `decoded`) plus typed failure. Decoded refusal carries the
  admitted original identity, decoded length/checksum, decoder, and decode receipt. Only
  constructed source carries canonical identity, and only present source artifact references
  participate in cross-root closure;
- universe: `established` with `definitionIdentity`, ordered `members`, and ordered
  `dispositions`; or `unestablished` with a typed `reason` and receipt reference;
- semantic: `validated` with the canonical model source/schema/checksum reference plus the complete
  artifact reference in `scientificArtifacts`; `partial` with non-empty gaps; or `refused` with
  non-empty failures;
- report: `produced`, `failed`, or `not_attempted` as specified in §7.4;
- projection/layer: `produced`, `established_empty`, `filtered_or_suppressed`, `unsupported`, or
  `failed` as specified in §7.5.

Arrays whose members carry identities are canonically sorted by those identities unless their
domain contract declares order meaningful. Stage attempts retain execution order and also carry a
stable stage/attempt identity. Decoders reject unknown discriminator values, duplicate identities,
missing branch fields, and fields belonging to a different branch.

The `payloads` array is not arbitrary JSON. Each entry is either a payload family explicitly named
by this schema or a typed extension carrying a namespace, schema id, canonical payload checksum,
requiredness, and payload storage. Payload storage is one of:

- exact opaque canonical bytes encoded reversibly in the extension entry; or
- a manifest-bound artifact reference carrying stable artifact id, role, byte length, checksum,
  media type, and schema identity.

A registered codec for the namespace/schema pair may interpret those bytes. An unregistered codec
produces
`UnsupportedExtension(namespace, schemaId, checksum, requiredness, payloadStorage)`; the decoder
MUST retain the exact bytes or artifact reference and MUST NOT interpret, normalize, discard, or
replace them. Re-encoding an unsupported extension reproduces the same canonical payload bytes and
checksum. A value that retains only namespace, schema, checksum, and requiredness is
recognized-but-discarded and MUST NOT be described as preserved.

Unknown root schema versions and unknown closed-core tags fail decoding of that root. Known core
state and a valid `StoryModel` remain available when an unsupported extension is retained. When an
unsupported extension is required by a requested report or projection, that outcome is explicitly
unsupported or failed; an optional extension does not erase otherwise valid delivery.

## 8. Provenance-derived view basis

View basis is an authority claim, distinct from model validation status. It MUST be derived from admitted build/adjudication/fixture provenance, not accepted as a free CLI label.

- A researcher-reviewed fixture basis is available only through the admitted fixture path.
- A human-adjudicated basis requires the corresponding adjudication receipt.
- A validated/acquired build basis requires the corresponding build receipt and provider provenance.
- An incompatible or unsupported basis must be unconstructible or refused with a typed error.

One optional scalar basis may describe only a single homogeneous admitted provenance root. V1 does
not define a precedence ordering between fixture, acquired, and adjudicated authority. If a view
combines heterogeneous roots, construction therefore refuses with a typed mixed-basis error. A
future composite basis may replace that refusal only if it losslessly retains every component
basis and receipt; it MUST NOT relabel the mixture as whichever basis appears first or seems
strongest.

The current `ViewBasis` cases are precedent, not proof that the derivation boundary is closed.

## 9. Projection contracts and multiple coordinate systems

### 9.1 Coordinate separation

The browser report may be one interactive document, but it does not have one scientific geometry.

- Codex uses canonical discourse position and exact text support.
- Chronology uses context-specific temporal relations and a declared partial-order/interval layout. Incomparable events remain incomparable; device placement used for legibility is labelled non-metric.
- Causal/goal views use declared topology or layering. Edge length carries no magnitude unless the projection contract explicitly says otherwise.
- Recall time and other axes remain separate from discourse and story-world time.

Shared stable addresses link these projections. Canonical navigation uses ordinary document links
and fragment targets. A separately authorized future interactive profile may synchronize
selection, but it may not equate the coordinate systems.

### 9.2 Current capability boundary

The current implementation provides only `ProjectionKind.DiscourseAtlas`, `DiscourseOffset`, `ContextLane`, and `DistanceMeaning.NoMeaning`. Chronology and causal projection types/compilers therefore precede their StoryAtlas lowering. Until a requested projection is supported, the result records it as unsupported or otherwise unproduced; a successful discourse scene must not silently satisfy it.

### 9.3 Codex portals and relation inventories

The Codex rail may show local relation marks and paired portal endpoints for nonlocal relations. Portals are evidence/navigation aids, not the semantic geometry of chronology or causality. Every relation also has a complete addressable textual inventory entry.

### 9.4 Bounded graph-over-text comparison spike

The default graph-over-text lowering remains an empirical design question, not a conclusion hidden
inside the executable contract. One repeated-endpoint, cross-page fixture whose discourse,
world-time, and causal orders disagree is compiled once into a common typed packet. Two renderers
consume those exact packet bytes and stable identities:

1. a Codex-first rail with lanes and paired portals for cross-page relations; and
2. coordinated Codex plus separate chronology/causal structural views.

Both conditions owe executable courts for identity, exact evidence, bidirectional focus/selection,
suppression accounting, portal occlusion and continuity, keyboard operation, non-colour status, and
textual equivalence. Those courts establish correctness and operability only. Comparative task
accuracy, completion time, navigation/focus loss, accessibility strata, and user preference require
a separately governed human study with a declared sample, analysis plan, and owner REB disposition.
The build MUST NOT claim that either rendering is superior from executable checks alone.

## 10. Browser report contract

### 10.1 First view and semantic state

The initial document visibly distinguishes validated, partial, refused, and universe-unestablished outcomes before presenting scientific graphics. State is carried by literal text and at least one non-colour structural treatment.

When canonical text exists, it remains readable DOM text. When it does not exist, the report shows original byte identity, decoding attempt, typed failure, and receipt rather than an empty rail.

An unavailable projection is an explicit panel or inventory outcome, not a blank canvas, `0 events`, or absence of ink.

### 10.2 Scientific no-inference boundary

The report renders precompiled scientific state. Browser code MUST NOT:

- promote a proposal into an accepted claim;
- derive evidence or coverage from display presence;
- infer chronology, causality, identity, or hierarchy from geometry;
- bridge a missing coordinate mapping;
- turn filtered/off-projection state into scientific absence;
- equate measured zero with missing, imputed, failed, ineligible, or unestablished.

### 10.3 Canonical text in the DOM

Concatenating the designated canonical text fragments in document order yields `StorySource.canonicalText` exactly. Presentation wrappers insert no characters. Unencodable canonical input is refused with an exact offset/reason rather than silently substituted.

### 10.4 Determinism and receipts

For identical manifest-bound scientific input, view state, layout specification, renderer/software identity, and owned deterministic backend, produced artifact bytes and textual twins are deterministic. Experimental or browser-dependent layouts are explicitly identified, seeded where applicable, receipted, and excluded from claims of publication-byte stability.

Interaction state may change what is visible, but it cannot change the underlying scientific packet. Saved views record their exact state and receipt.

### 10.5 Accessibility and textual equivalence

- Canonical text is real DOM text in reading order.
- Every figure has a concise caption and printed projection contract.
- Every scientific mark/relation has an addressable textual representation with the same identity, status, evidence, basis, and render disposition.
- Every textual scientific item is accounted as drawn, bundled, filtered, off-projection, unsupported, failed, or deliberately text-only.
- Missing, measured, imputed, ineligible, unresolved, failed, excluded, filtered, and off-projection states are expressed as text and by non-colour structure.
- Keyboard order and visible focus are deterministic.
- Dense SVGs do not create thousands of duplicate tab stops when an adjacent structured inventory is the complete navigation surface.
- Bundled/ancestor proxies are labelled as proxies and never receive the exact selected object's accessible identity.
- The report targets WCAG 2.2 AA contrast, 200-percent zoom/reflow without loss, grayscale distinction, and reduced-motion behavior.

`preview.html` and `preview.txt` may have independent lowering outcomes only when each produced
artifact is independently complete. A produced `preview.html` MUST contain its complete accessible
in-document textual twin even if `preview.txt` fails. The separate text file is a deterministic,
diffable convenience artifact; it is not the only accessibility surface for the HTML report.

### 10.6 Proposed print profile

The HTML includes a print stylesheet that preserves semantic completeness: state/coverage headers, figure captions and projection contracts, non-colour distinctions, relation inventories, evidence identifiers, and readable source text. A pinned canonical print court may constrain publication geometry; other supported browsers owe legibility and semantic completeness, not byte-identical pagination. A generated PDF is an optional checksummed report, never semantic authority.

## 11. Manifest contract

Each entry records at least:

- typed artifact role;
- normalized relative path;
- media type and schema/version where applicable;
- byte length and checksum when bytes were produced;
- required/optional status for the selected bundle profile;
- produced, failed, not attempted, or absent-by-semantic-contract disposition;
- the receipt or error reference that justifies non-production.

Paths MUST NOT rely on an absolute workspace location. Required asset references remain valid when the bundle directory is moved or renamed.

The manifest does not confer scientific authority on arbitrary files. Authority comes from role and the governing artifact codec.

The manifest separately records each requested bundle profile and exactly one profile outcome:

- `satisfied`, with the certification receipt;
- `failed`, with a closed typed error, evidence references, and receipt; or
- `not_attempted`, with a typed reason and mandatory disposition receipt.

Failure values never contain raw exception or log text. Their closed codes and typed evidence
references preserve deterministic decoding and prevent an output failure from becoming an
unbounded disclosure channel.

The base local-open profile is always requested. A failed HTML report therefore leaves a valid
manifest and partial delivery while the local-open profile is `failed`. No JavaScript-capable
profile can substitute for or be required by canonical local-open conformance.

All artifact checksums use the existing lowercase-hex SHA-256 `Checksum` contract over the exact
emitted bytes. JSON artifacts use canonical sorted-key encoding from `codec`; pretty-printing,
filesystem newline conversion, or re-encoding after checksum calculation is non-conforming.

`manifest.json` is identified by the manifest root and is not listed as a checksummed entry inside
itself. This is the only self-reference exception. Every other promised role has one entry.

### 11.1 Manifest construction laws

The manifest constructor validates the complete artifact set at once:

- the local-open profile has exactly one outcome;
- every requested profile has exactly one outcome, and no unrequested profile acquires one;
- every profile disposition has a receipt binding its schema/policy, verifier, execution, causal
  prerequisites, exact decision/reason/code, and court outcomes; `not_attempted` has no
  receipt-less branch;
- a satisfied local-open receipt resolves to the produced `browser_preview` at exactly
  `preview.html` and matches its role, media type, checksum, and all required local assets;
- a satisfied profile has a nonempty, duplicate-free enumerated court set and every court passed;
- produced `preview.html` may coexist with a failed local-open profile, preserving auditable partial
  delivery when bytes exist but verification fails;
- any future interactive profile is a distinct outcome and cannot imply canonical conformance
  unless the same pure-HTML/CSS preview independently satisfies the canonical courts;
- every path is a non-empty portable relative path using `/` separators and ASCII segments made of
  letters, digits, `.`, `_`, and `-`;
- no segment is empty, `.`, `..`, ends in a dot/space, or is a case-insensitive Windows device
  name; backslashes, colons, roots, drive prefixes, and URI schemes are forbidden;
- paths are unique both byte-for-byte and under ASCII case folding, preventing collisions on
  case-insensitive filesystems;
- exactly one required `invocation_result` entry exists, whether produced or failed;
- each produced entry has a positive or zero byte length derived from the actual bytes and a
  checksum recomputed from those bytes;
- a conditional core role appears at most once;
- every requested report identity has exactly one result disposition and every report disposition
  names a requested identity;
- a produced result disposition resolves to one produced manifest entry with the same role,
  stable artifact id, byte length, checksum, media type, and schema identity;
- each present `original_source` or `canonical_source` reference in a produced `result.json`
  resolves to exactly one produced manifest entry with identical stable artifact id, role, byte
  length, checksum, media type, and schema identity; admitted and decoded refusal progress retain
  the original reference, before-intake refusal retains neither source reference, every refusal
  forbids canonical bytes, and a constructed source requires both;
- a validated semantic-model reference resolves to exactly one produced `semantic_model` entry
  with identical stable artifact id, role, byte length, checksum, media type, and schema identity;
- an optional partial-draft reference obeys the same exact resolution law without acquiring
  validated status; a partial branch with no draft and every refused branch require semantic-model
  absence;
- no produced entry with a closed singleton role
  (`original_source`, `canonical_source`, or `semantic_model`) is orphaned from the corresponding
  result reference; conditional absence is represented by the result branch, never by an
  unreferenced file;
- a failed or not-attempted disposition does not resolve to placeholder bytes;
- when `result.json` serialization fails, its manifest failure disposition is self-contained or
  cites a separately produced, manifest-bound receipt artifact; it MUST NOT cite evidence that
  exists only inside the absent result bytes;
- `storymodel.json`, when present, is byte-for-byte the existing `StoryModelCodec` output;
- manifest ordering is canonical and does not depend on map iteration or filesystem enumeration;
- the manifest never includes its own checksum as an input field.

Before any filesystem write, the assembler validates every artifact path, role, media type,
schema, byte length, checksum, result-to-artifact reference, profile binding, and cross-root
reference. An initial target-absence check is advisory only and MUST NOT be treated as the
no-overwrite proof. An existing target is refused even when it is an empty directory; the assembler
never merges into, cleans, or overwrites a destination.

The assembler publishes transactionally:

1. Acquire a stable handle to the resolved parent directory before creating a private sibling
   staging directory on the same filesystem as the final target. Bind the handle and pathname to
   one directory identity. Staging operations stay confined to the create-new private tree; the
   final commit is relative to the held parent handle. Replacing or renaming the pathname parent
   before commit therefore causes a typed refusal or a commit into the originally anchored parent,
   never a redirected commit into the substitute.
2. Create every internal directory and non-manifest artifact with no-follow and create-new
   semantics. Reject symlinks, hard links, device files, stale staging entries, and any path whose
   resolved parent escapes the staging root.
3. Re-read the staged regular files and independently verify their byte lengths and checksums.
4. Encode and write `manifest.json` last, after all other artifact dispositions and bytes are
   fixed. Re-read those exact bytes, verify their length and checksum, decode and validate the
   manifest against the prepared result, and compute the external `BundleId` before commit.
5. Commit the complete staging directory with one parent-anchored atomic **exclusive**
   no-replace operation. The commit itself, not a preceding existence check, proves target absence.
   A competitor-created target causes an existing-target refusal and survives byte-for-byte.

The implementation MUST NOT fall back to a multi-step or copy-based publication when the platform
cannot guarantee that final rename is both atomic and exclusive. Generic atomic move without a
documented no-replace guarantee is insufficient. A runtime or filesystem lacking the combined
capability returns `UnsupportedAtomicPublication`; it never attempts ordinary rename as a
fallback. Existing-target conflict, nonempty-target conflict, unsupported atomic publication,
stale or symlinked internal target, and injected mid-write failure are closed delivery error codes
with a delivery receipt. A failed publication may leave only a private staging residue eligible for
safe cleanup; after commit-stage conflict or parent-identity loss, it retains that stage rather than
performing pathname cleanup that could touch a substitute parent's unrelated entry. It never
exposes a partial final target. After the exclusive atomic rename succeeds,
the directory is committed and the outcome is `Published`.
Failures observed after that commit point MUST NOT be reported as `Refused` with an absent target;
they are post-publication verification findings against the published receipt.

Moving or renaming a successfully published enclosing bundle directory, including to a parent path
with spaces or non-ASCII characters, does not change any internal reference.

Bundle preparation and bundle delivery are separate values. A validating `PreparedBundle` owns
the immutable artifact bytes and canonical manifest. Publishing it returns a typed
`BundleDeliveryOutcome`, for example `Published(bundleId, targetIdentity, receipt)` or
`Refused(code, receipt)`. Delivery state is not written back into `result.json`, report outcomes,
or the manifest being published, because doing so would change the bytes after preparation. The CLI
maps the delivery outcome to a process exit status separately from the scientific and report
outcomes. A delivery failure therefore cannot turn valid semantics into refusal or a failed report
into absence.

An assembler may expose a convenience constructor from artifact bytes. Its canonical expansion is:
validate paths and roles, compute byte lengths and checksums, validate every cross-root reference,
sort entries canonically, and encode. The convenience form MUST return the same value, errors,
ordering, and canonical bytes as those explicit steps.

### 11.2 Proposed `manifest.json` wire root

The proposed manifest root is:

```json
{
  "schemaVersion": "story-output-manifest/v1",
  "profileOutcomes": [
    {
      "profile": { "status": "local_open" },
      "disposition": {
        "status": "satisfied",
        "receipt": {
          "id": "...",
          "schema": "local-open/v1",
          "verifier": "storyatlas-profile-verifier/v1",
          "execution": "...",
          "policy": "story-output-profile-policy/v1",
          "decision": { "status": "satisfied" },
          "prerequisites": ["report-html"],
          "preview": {
            "role": { "status": "browser_preview" },
            "path": "preview.html",
            "mediaType": "text/html",
            "checksum": "..."
          },
          "requiredAssets": [],
          "courts": [
            {
              "court": "direct-file-open",
              "disposition": { "status": "passed" }
            }
          ]
        }
      }
    }
  ],
  "entries": [
    {
      "role": { "status": "invocation_result" },
      "path": "result.json",
      "mediaType": "application/json",
      "schemaVersion": "story-output-result/v1",
      "requirement": { "status": "required" },
      "disposition": {
        "status": "produced",
        "artifact": {
          "id": "invocation-result",
          "role": { "status": "invocation_result" },
          "mediaType": "application/json",
          "schemaVersion": "story-output-result/v1",
          "byteLength": 1234,
          "checksum": "..."
        }
      }
    }
  ]
}
```

`profileOutcomes` always contains the requested base local-open profile. Any future interactive
profile is separate and cannot replace that entry or relax its courts. Exactly one required
`invocation_result` entry locates the output account by typed role. It may carry a produced or
failed disposition, so a result-codec failure can still be recorded without placeholder
`result.json` bytes.

`BundleId` is the SHA-256 of canonical `manifest.json` bytes, computed by the consumer or an outer
container. It is not stored inside `manifest.json`; doing so would be recursive. It changes whenever
the manifest records different content or a different promised failure disposition.

Each entry contains a typed `role`, normalized `path`, `mediaType`, optional `schemaVersion`, profile
requirement, and one tagged disposition. A produced disposition contains `byteLength` and
`checksum`; failed and not-attempted dispositions contain typed error/reason and receipt references;
absent-by-semantic-contract contains the semantic branch that forbids bytes. Roles with an
additional identity, such as named projections or optional exports, encode that identity as a
separate field rather than concatenating it into a path or free-form role string.

## 12. Acceptance courts

Each court states its own capacity-to-fail precondition and, where a value is compared, independently derives the expected result.

### C1 Canonical semantic artifact

Encode a validated model with `StoryModelCodec`, place those exact bytes at the semantic-model role,
decode to Draft, revalidate, and prove content identity. Repeat with a partial result that preserves
the same canonical bytes as an explicitly non-validated draft plus nonempty gaps. A partial result
without a coherent draft and a refused result each have no semantic-model bytes. Mutating the
bundle assembler to wrap, reformat, duplicate, promote, or smuggle the semantic JSON fails.

### C2 Three text identities

Use strict UTF-8 input containing a BOM, CRLF, a trailing tab, and an astral character before a
supported span. Original bytes, BOM-consumed decoded `StorySource.rawText`, and canonical text are
all intentionally distinguishable. Independently verify `source.txt`, decoder identity/receipt, raw
text checksum, `canonical.txt`, canonical checksum, byte length, UTF-16 length, and policy. A fixture
on which canonicalization is identity is insufficient by itself, and equal checksums never merge
typed roles. Force failure before intake: neither source artifact reference exists. Force
strict-decode failure after admission but before successful decode: the original reference and
intake receipt remain, but decoded and canonical identities are absent. Decode whitespace-only
strict UTF-8 successfully and
then let `StorySource.fromText` refuse empty content: original plus decoded
length/checksum/decoder/receipt remain, canonical identity and bytes are absent. A constructed
control carries all three. Each absence is carried by the typed source branch rather than
placeholder bytes. Mutations that create decoded progress without admitted original identity,
erase a completed decoded receipt, add canonical identity to refusal, or make the manifest disagree
with any present original/canonical reference fail construction or decoding. Holding original
bytes, selected charset/BOM, decoded content/length/checksum, and the typed decode receipt fixed,
mutate only a repeated wire decoder label: decoding fails because decoder identity is derived from
and bound inside the receipt rather than accepted as authoritative decoration. A mutation that
lets the parallel label through must make this court red.

### C3 Standoff expressivity

Use overlapping annotations, one discontinuous `SpanSet`, multiple claims over one support, and a reentrant/cross-layer relation. Canonical round-trip preserves every identity and span. A lossy inline exporter either records the exact loss or refuses.

### C4 Universe refusal

Force planning/eligibility failure. The report and result carry `Unestablished`; no coverage fraction, percentage, success rate, absence count, or empty-progress bar is present. Separately prove `Established(empty)` renders as zero eligible, not unknown.

### C5 Partial delivery

After `StorySource` construction, semantic validation, and at least one independent report success,
force HTML lowering failure. `canonical.txt`, `storymodel.json`, and the successful report remain
byte-identical and manifest-bound; HTML and the local-open profile are failed with receipts.
Replacing the independent
outcomes with the current all-or-nothing `Either` composition fails.

### C6 Provenance-derived basis

Exercise fixture, acquired-build, and human-adjudicated roots with distinct receipts. Attempt to
label a general acquired input as a researcher-reviewed fixture; construction or validation fails.
Changing only a free label must not be sufficient to change basis. Combine acquired plus
adjudicated material, then fixture plus acquired material: V1 refuses both with a typed mixed-basis
error. Mutating derivation to choose the first or strongest-looking basis fails.

### C7 Requested projection accounting

Separately test produced, established-empty, filtered/suppressed, unsupported, and failed outcomes. Removing the outcome while retaining a distinct request/checksum fails. Cover every supported `RelationLayer`, not only WorldTime.

### C8 Coordinate divergence

Use a fixture where discourse order, world-time order, and causal order disagree and one relation crosses a page boundary. Changing page breaks affects only Codex/portal device geometry; it does not change chronology or causal facts. If all views agree by construction, the fixture is invalid.

### C9 Local file opening

Place the final bundle under a directory containing spaces and non-ASCII characters. Open `preview.html` under `file://` in each supported browser without a build, terminal, server, or install step. Assert a nonblank initial document with visible semantic/result state and required source/inventory/projection content.

Only after these assertions pass is the local-open profile outcome `satisfied`.

Mutation courts flip local-open to satisfied without produced preview bytes, remove the
`not_attempted` disposition receipt, and keep preview bytes produced while a direct-open court
fails. The first two refuse; the last preserves the produced bytes and records failed profile
certification.

### C10 Pure self-contained HTML/CSS

Inspect the exact produced DOM and resource references, then disable network and repeat C9. Insert
a script element, an inline event handler, and an automatically loaded external resource one at a
time. Each mutant refuses canonical-profile satisfaction; it cannot be treated as produced
canonical HTML, and valid scientific/result/text-report artifacts remain unchanged. Also mutate
inline CSS to add `@import`, `url(https://invalid.example/x)`, and
`url(sibling-asset.svg#mark)`; each fails. Positive controls prove same-document SVG references such
as `url(#local-id)` and embedded `data:` URLs remain self-contained. The positive report contains
all essential state, evidence, inventories, navigation targets, and its complete textual twin in
the initial document.

### C11 DOM text reconstruction

Extract the designated canonical DOM fragments, concatenate in order, and compare exactly to
`canonical.txt` and `StorySource.canonicalChecksum`. Place an astral character before a supported
span and prove the span resolves by UTF-16 code units rather than UTF-8 bytes or code points. Also
cover newlines, escaping, U+0000, and unpaired-surrogate refusal.

### C12 Graphical/textual equivalence

Delete one relation from the textual inventory while leaving its visual edge, and then the reverse. Certification fails in both directions. Prove identity, status, evidence, basis, and disposition equality, not only equal counts.

### C13 Accessibility and print

Test keyboard navigation, visible focus, non-colour distinction, 200-percent zoom/reflow, grayscale status discrimination, reduced motion, and a pinned-browser print output retaining state banners, legends, evidence ids, and relation inventories.

### C14 Determinism

Generate twice from identical exact inputs and compare every deterministic artifact byte-for-byte. Then change one declared view/layout/software input and prove the receipt and affected artifact identity change. Distinguishability must exceed any comparison tolerance.

Also mutate produced HTML bytes and reassemble: its checksum changes `result.json`, both changes
change `manifest.json`, and no earlier artifact must be regenerated from the new result/manifest
checksum. This proves finalization terminates without a hidden checksum cycle.

Derive `BundleId` externally from the final canonical manifest bytes and prove no `bundleId` or
self-checksum field appears inside the manifest.

### C15 Sibling seam

Build and test StoryAtlas against the exact storymodel4s candidate SHA whenever bundle/report/projection types or consumed dependencies change. Record both SHAs and run the consumer suite before chief acknowledgment.

### C16 Extension compatibility

An unknown result root version and an unknown closed-core tag each fail decoding. An unknown
optional extension becomes a typed unsupported-extension outcome while known core and canonical
`StoryModelCodec` bytes remain available. Re-encode it and prove its exact opaque canonical bytes
or manifest-bound artifact reference, checksum, namespace, schema id, and requiredness are
unchanged. Mark the same extension required by a requested report; that report is accounted
unsupported/failed rather than produced, and the valid core still remains auditable. Dropping,
normalizing, replacing, or interpreting the extension fails the court.

### C17 Cross-root artifact closure

Build a result with distinct original, canonical, and semantic artifact identities and bind it to a
manifest containing exactly those singleton roles. Independently recompute each byte length and
checksum. Mutate, in turn, the stable artifact id, role, byte length, checksum, media type, and
schema on each side; add an orphan singleton entry; duplicate a singleton role; and point a failed
`invocation_result` entry at evidence available only inside absent result bytes. Every mutation
fails construction or decoding. A partial/refused semantic branch with no semantic-model entry and
a self-contained result-serialization failure each remain valid. Repeat the complete stable-id,
role, byte-length, checksum, media-type, schema, orphan, and duplicate-role mutation set with a
partial branch whose optional draft reference closes over semantic-model bytes. Every mutation
fails; removing the draft reference and semantic-model entry together remains a valid partial
result, and the branch never acquires validated status.

### C18 Transactional publication and CLI separation

Prevalidate a complete prepared bundle, then publish it to an absent target and independently
verify every final byte plus external `BundleId`; re-read, decode, and validate the staged
manifest before commit. Repeat with an existing empty directory, an
existing nonempty directory, a symlink target, a case-fold collision, a stale symlink inside
staging, an injected write failure before `manifest.json`, an injected failure after
`manifest.json` but before commit, and a filesystem that refuses atomic exclusive directory
rename. Every case returns the specified typed delivery outcome, exposes no partial final target,
and leaves the prepared scientific/result/report bytes unchanged. Mutating the publisher to
overwrite, merge, follow a link, write the manifest early, use check-then-ordinary-rename, or fall
back to copy-and-delete fails.

Add a barrier after final staged-byte/manifest validation and before commit. While held, a
competitor creates the empty final target; then release the publisher. Publication refuses with an
existing-target conflict, the competing target survives byte-for-byte, no partial bundle appears,
and prepared bytes plus retained staging evidence are unchanged. A check-then-ordinary-atomic-rename
mutant must replace the competitor and therefore fail this court. In a separate barrier court,
rename or substitute the pathname parent after the publisher acquires its anchor; commit either
lands in the originally anchored parent or refuses safely and never appears in the substitute. Put
an unrelated same-named staging directory in the substitute and prove it survives byte-for-byte;
cleanup after anchor loss must not follow the replaced pathname.

Inject a finding strictly after the atomic commit and prove the outcome remains `Published` with a
post-publication verification finding; it must not claim refusal or target absence after bytes are
visible.

Run the CLI over a valid semantic result with failed HTML lowering and over a valid prepared bundle
whose publication is refused. Prove process exit mapping is derived from the declared CLI policy
without changing semantic, report, profile, or delivery values.

### C19 Graph-over-text comparison boundary

Use the same repeated-endpoint cross-page packet for the Codex-first and coordinated structural
conditions in §9.4. Independently prove shared identity, evidence, selection round-trip,
suppression accounting, portal continuity/occlusion disclosure, keyboard operation, non-colour
status, and complete textual equivalence in both conditions. Change only renderer/layout state and
prove semantic packet bytes do not change. The executable court records no task-performance,
preference, or superiority conclusion; adding such a conclusion without a separately governed
human-study receipt fails.

## 13. Sequencing

1. Amend the epic so its body reflects the owner correction and the explicit O1–O4 round.
2. Approve the module/dependency decision and public outcome vocabulary.
3. Specify and implement renderer-independent result, universe, source-identity, provenance, report, and manifest roots with codecs.
4. Specify and implement projection capability/outcome accounting and chronology/causal contracts in storymodel4s.
5. Adapt StoryAtlas bundle preparation, transactional publication, browser report, and CLI mapping
   to consume those roots without conflating delivery with scientific/report outcomes.
6. Run local-open, partial-delivery, publication, extension-preservation, cross-root,
   graph-over-text, accessibility, print, determinism, and sibling-seam courts.
7. Connect arbitrary text CLI input only after Stage 1b can produce a real validated model; a second admitted fixture may test existing generality earlier without pretending to solve acquisition.

## 14. Non-goals and deferred profiles

- This specification does not make embeddings part of model identity.
- It does not make HTML or SVG an interchange parser.
- It does not authorize browser-side scientific inference.
- It does not require a total story-world timeline.
- It does not define movie/media packaging. A future media profile must bind editions, renditions, streams, axes, rights/admission, and unavailable media without generalizing `source.txt` into a fake universal artifact.
- It does not authorize an arbitrary-input CLI before Stage 1b.
- It does not choose a general graph-layout dependency without benchmark evidence.

## 15. Open ADR/spec decisions

- exact module home and dependency edge for result/report codecs;
- exact public ADT names and extension boundary for admitted result payloads;
- supported browser matrix and pinned canonical print renderer;
- exact public representation of a separately authorized future interactive profile;
- exact manifest schema id/version and path-normalization rules;
- whether a produced refusal report is required by the selected profile or merely requested with an independent failure outcome;
- chronology and causal projection vocabulary, compilers, and layout receipts;
- external inline/export formats and their loss-record schemas.
