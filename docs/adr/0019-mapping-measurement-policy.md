# ADR 0019: mapping evidence, inference policy and scientific use

Accepted direction, 2026-09-19, single-developer mode (SD5), in response to the owner's external
review and backlog-reorganization request. These are design decisions; the APIs are not yet
implemented. Detailed contracts and acceptance gates: [delivery plan](../refactor/PLAN.md).

Reuse existing source, recall, cost and receipt types in existing modules. Introduce a checked,
versioned mapping result with immutable reusable local evidence and named inference policies:
strict local reference measurement and structured reconstruction. Reserve joint scientific
inference for later; it estimates behavioral parameters and must not reuse an arbitrary
discriminative posterior as an observation likelihood.

Reference inference has a declared target universe, grain, prior and external alternatives. It
does not use a behavioral trajectory preference at retrieval, scoring, refinement, inference or
postprocessing. Chronology is available separately to readouts. A source-blind fixed packet
inventory preserves legitimate linguistic context without assuming a recall trajectory.

Derive assumption receipts from executed stages, extending ADR 0016's transition-only scope and
promoting ADR 0017's pre-refinement local evidence. Bind result, configuration, candidates,
provider/rendering, measure semantics and all decisions/projections. Missing provenance cannot
be self-certified as a reference profile by a caller-supplied label.

A readout declares quantity, source axis/order, inference and organization units, resolution,
unknown-transition policy and compatible inference policy. Compatibility distinguishes
`CompatibleWithDeclaredPolicy`, `ModelDependentOnly`, `Incompatible` and `UnknownProvenance`.
These are proposed vocabulary meanings; exact Scala construction follows the checked-boundary
ticket. Compatibility proves declared information use, not empirical recovery or calibration.

Keep raw score mass, model-conditional probabilities, calibrated correctness probabilities,
discrete choices and filled/projection values distinct. Preserve external states and processing
failures separately. Transition summaries use joints, or row products under declared conditional
independence; missing units do not disappear and word projection adds no behavioral observations.
Ambiguity bounds are conditional on the candidate/admissible set and are not confidence intervals.

Rejected: deleting structured inference; calling priorScale=0 independent measurement; separate
pipelines for reference/reconstruction; documentation-only scientific restrictions; mandatory
joint Bayesian modeling before useful delivery; three new modules; treating a successful policy
check as an unbiasedness certificate. The cost is a small checked evidence/result seam and
empirical recovery work. The benefit is preserving useful reconstruction without laundering its
assumptions into observations.

Sherlock integration reuses ADR 0018's `ClockRepair` and existing manifest vocabulary. Committed
JSON is the single admission-pin source, loaded through verified identity and receipted by its
own digest. Duplicating those hashes in Scala is rejected: agreement between two editable copies
does not supply independent evidence. This changes admission maintenance, not the admitted bytes.

The first scoring slice (2026-09-19) introduces only versioned benchmark artifacts in existing
modules: the recall unit manifest, frozen gold support, complete scene outcomes, comparison
result and scoring configuration. It does not implement the proposed public mapping-result API.
Freeze observation identity first and gold eligibility separately, before opening predictions.
The support's canonical digest is supplied independently from the support file and recorded in
the evaluation ledger; a changed support requires a new declared artifact and ledger entry.
Reject silently recomputing the digest during comparison, which would allow denominator edits.

Retain the preregistered uniform unit weights and participant-average paired difference;
report pooled unit-weighted differences separately. Nonlabel outcomes remain wrong when gold
eligible, and no-gold units remain accounted for. Empty populations and singleton bootstrap
intervals are explicitly undefined. Timing, transcript-order and adjacent-transition coverage
remain separate; unresolved units never create a synthetic direct transition across a gap.

The Friends seal rework (2026-09-21) shares cooperative Python guard mechanics in
`tools/recall-study/sealed_split.py`, with corpus-owned fixed paths and a committed split.
Preserve the original seal record; migrate its empty read ledger to the shared schema with
separate final-opening and count-only attempt counts. Reject caller-selected split/ledger
paths and post-seal use of sealing-time APIs. Record aggregate-read attempts before access,
including failures, and require the ledger to be committed before the next attempt. A
content-free `--check` reproduces membership and planning metadata from historical counts;
a recount requires an explicit purpose and ledger entry. Rejected: rereading recall during
ordinary integrity checks, rewriting historical seal digests, and treating an import-only
reader scan as proof of guarded access. This adds no Scala module or dependency and does
not establish the future release-manifest content contract.

G1 construction decision (2026-09-22): implement revision 4 of the mapping-record plan
in existing recall/align/codec modules. `MappingMiniature` is experimental test support
in the published laws artifact; its public plain data transcribes the admitted synthetic
fixture and carries no scientific authority. Bind derived mapping rows to their unit,
inventory, source, presence-sensitive recall and source fidelity inputs, and the actual
HsmmResult content. Each derived producer checks the supplied result against that binding.
Reject an input-only binding: two caller-validated results over identical inputs can
carry different values. Keep existing HSMM fingerprints and wire bytes unchanged.
Checked candidate-set IDs are explicit link inputs and are recomputed from the actual
decision basis by the outcome factory. Owner-created private components use enclosing
owner nesting or complete checked factories; reject package-visible unchecked producers.
The later slices add the detailed vocabulary and interchange amendments in the same
G1 landing. `Derived`, calibrated probabilities and calibrated decisions remain reserved
with no construction door; identity does not establish that inference executed.

The inventory cold review reproduced distinct admitted surrogate code units collapsing in
legacy UTF-8 checksums. New G1 digests therefore hash a lossless ASCII rendering of UTF-16
tokens; segmentation includes canonical text alongside the legacy text checksum. Reject a
hash-only transcript binding and lossy token hashing. This is local to the new identity
contract and does not migrate existing core IDs, fingerprints or wire artifacts.

G1 multipart decision (2026-09-22): a legacy `SourceBundleId` does not identify a full
coordinate-bearing bundle. `SourceRepresentation.of` refuses `AmbiguousBundle` when
one published part ID would name different full identities, and cross-part detection
counts resolved primary axes. Reject deduplication by legacy ID: an executed witness
admitted two different coordinate parts as one. Composition remains explicitly declared.

G1 assessment decision (2026-09-22): capture a source view's node inventory and relation
coordinates once, then validate and assess that immutable snapshot. Reject a later lookup
as fidelity input: an executed counterexample changed Action from Correct to Wrong under
an unchanged binding. Source-representation construction uses the same exact snapshot: a second
executed witness otherwise retained old target levels while accepting a binding to a new
inventory fingerprint. Keep the independent original-lookup consistency refusal. This changes
no inference or existing source-view contract.

The Memento task and seal (2026-09-21) use the same shared guard with fixed corpus paths.
The independent population rule retains 123 participants; the owner chose 63 test and 60
development, stratified 14/17/15/17 test across conditions, with seed 20260921. SHA-256 ranking
uses explicit LF-separated fields and is independent of gold counts and Python RNG versions.
Physical worksheet rows define observations; optional observed time does not define identity.
Accuracy requires transcript, accurate code and valid scene gold; either annotated scene is a
single correct match. Keep no-gold rows and zero-eligible participants explicitly accounted for.
The scorer accepts ID/outcome pairs and refuses duplicate IDs before building its lookup.
Indexed XLSX reads preserve error cells and validate physical coordinates; legacy unindexed
reads retain their prior behavior. Exact-byte inferred header overlays are admitted with
pre-seal no-overlay sensitivity, not as author-confirmed labels. S53 qualification semantics
remain unestablished. Pin task, population and parser identities in the seal. Rejected: merging
repeated text, inventing row clocks, salvaging one valid half of malformed scene gold, treating
missing gold as model error, generic positional header fallbacks, and substituting an
eligibility-selected population. This adds no Scala module, dependency or public Scala type.

The pre-seal audit exposed S72's empty canonical transcript column and populated duplicate
heading with one trailing space. Bind a fifth sheet overlay to that exact header and require
the displaced column to remain empty. Preserve the superseded audit in local evidence; no
model output or split allocation informed this correction.

G1 outcome decision (2026-09-22): retain stage references on every computed outcome and the
declared decision policy even on abstention, plus the original request. An empty external decode
must replay as Unranked while an explicit abstention remains NotComputed. Reject dropping these
as display-only details:
an empty or abstained row still needs ledger validation and reproducible policy identity.
Outcomes derive choices, candidate-support status and localization from an explicit measure
basis; a listed alternative in another measure does not join that basis. No new module or
dependency is introduced.

G1 interchange decision (2026-09-22): `MappingCodecs` is a separate contextual
`mapping-record/v0.1` wire. `ExpectedMappingContext` carries checked inventory/source;
`DerivationContext` supplies the original result, recall and view for bound values.
`MappingCodecError` follows the existing HSMM wrapper precedent: JSON/schema errors
wrap `CodecError`, contract refusals wrap `MappingRefusal`, and numeric-coordinate,
reserved-authority, context, digest and value mismatches remain typed. Reject adding
mapping-specific cases to the shared generic JSON error enum.

The JSON groups typed policy IDs, unit roles and stage-assumption receipts, flattens
measure alternatives into per-outcome `mapping_links`, and keeps link assessment and
chosen-link fidelity separate. Support uses `support_status`, `source_support`,
`term_support` and `support_coverage`. Optional data is explicitly tagged. The codec
rebuilds physical evidence against full checked bundles and re-derives bound measures,
links and decisions before a canonical full-record match. Reject trusting a carried
checksum alone or accepting a serialized assessment as its own authority. Mapping-only
ASCII JSON escaping preserves all UTF-16 code units through byte serialization; the
existing HSMM and generic canonical formats are unchanged.

G1 JSON admission correction (2026-09-22): mapping printing retains nulls so unknown
null-valued fields cannot disappear before the full-record comparison. A portable,
mapping-private scan of syntax-validated input refuses duplicate object keys, including
escaped spellings of the same key, before the parsed object can silently discard them.
Both defects have executed before/after witnesses. Reject changing the shared legacy
printer or treating last-key-wins parsing as evidence that the original record was unique.

The 2026-09-22 [workflow plan](../plans/2026-09-22-recall-encoding-workflow.md) extends
the readout design to continuous-time queries and dynamic uncertainty views.
Discrete mapping records remain authoritative; temporal allocation, recall exposure,
clock transformations and neural response operators have separate provenance.
Support-only bounds, assumed allocation, model localization and empirical calibration
are distinct claims. “A or B” alternatives cannot stand in for “A and B” references.
Reject a single forced time-warp, automatic uniform allocation, display-driven
renormalization and per-frame claims from scene-only evidence. The exact public
projection API follows G1 and its own checked implementation; this planning record
adds no module, dependency or implemented Scala type. StoryAtlas consumes the same
checked queries and exports as Python/R, preserving measure units, missingness and
semantic identity under zoom, selection and replay.

M1 workspace packet decision (2026-09-22): a joined investigation uses a thin
`WorkspaceManifest` in `view`, with existing `ArtifactRef`, `ArtifactRole`, `BundlePath`
and checksum primitives. `WorkspaceRole`, `WorkspaceDisposition`, `WorkspaceEntry` and
content-free `WorkspaceRefusal` distinguish mandatory source/model, recall, inventory,
mappings, derivation, capabilities and receipts from optional features. Supplied bytes
must match every identity exactly; unavailable roles carry no payload. Reject wrapping
this in `BundleManifest` by inventing an acquisition result or a local-open preview
certification: that existing manifest describes a story-build output profile.
`WorkspaceCapabilities` is an explicit, artifact-bound owner declaration with separate
inspection/export grants, not an authenticated license and never an inference from a
checksum. Checked scientific joins and permission enforcement remain separate from byte
admission. This adds no module or dependency and leaves G1 authority reservations intact.

The M1 `MappingMatrix` projection in `view` uses exactly the checked record's declared
target universe. Its private-construction rows and cells retain every inventory outcome,
all supplied external destinations, original measure metadata and separate posterior
fidelity states. Missing values remain absent; literal zero remains zero. Reject a display
cut that recomputes child or parent values, renormalizes a cropped row, or turns a processing
failure into an external probability. Presentation selection does not alter this projection.

`WorkspaceArchive` and `WorkspaceArchiveCodec` provide the M1 single-file offline UTF-8
transport. They verify the manifest and the actual capability artifact before returning
inspectable contents or serializing a packet. The owner declaration binds exact model,
recall and declaration-receipt bytes, with no default grants. Unknown/duplicate fields and
malformed UTF-8 refuse with content-free diagnostics. This is byte and permission admission,
not the semantic join. Packet serialization is content export and requires the export grant;
inspection-only admission never licenses packet writing. Reject a permissive generic archive decoder or byte repair; binary
sidecar transport is outside this first version and needs its own declared encoding.

`WorkspaceCodecs` owns the checked `SourceRecallWorkspace` construction door. Its text
decoder revalidates the actual model and recall, rebuilds the word inventory and source
dictionary, contextually decodes each G1 mapping and its optional HSMM result, and checks
one target cut plus a receipt over every member. `WorkspaceMappingInput` is only unchecked
packaging input. Structural validation licenses the existing source bridge, not scientific
completeness: `DraftModel` retains the actual derivation record, gaps and abstentions. This
version refuses structurally unpromotable source models rather than manufacturing a source
view. `WorkspaceTiming` preserves untimed, onset-only and interval annotations on each unit;
`WorkspaceOrigin` records authored-fixture versus imported-artifact provenance. Qualified
`core.Address` values bind model and recall artifact checksums, independently of mapping
policy. Exact evidence queries preserve each SpanRef and inverse queries retain repeated
references. Reject unchecked source dictionaries, local-ID-only joins, estimator execution
inside decode, inferred recall clocks and silent partial-to-complete promotion.

`WorkspaceSubsetCodec.selected` is the provider-owned deterministic export query. A selected
source expands to every referring recall row; selected recall rows retain every supplied
alternative. An empty selection exports zero outcomes. The subset carries the unchanged
mapping context and original record digest under its own schema, never impersonating a
complete G1 record. CSV and accessible text retain missingness, measure/scale, processing,
decision origin and exact evidence. A receipt binds the archive, policy, query, selection and
all three exported payload digests. Export permission is checked before returning any payload.
Reject UI-side row arithmetic, selected-only normalization and silent export-all defaults.
Selected recall evidence retains the tagged recall clock in JSON and the CSV
`unit_presentation_seconds` column, including explicit untimed and onset-only cases. Clock
coordinates use the same lossless IEEE-754 encoding as the admitted inventory.
These are supplied **unit presentation annotations**, tagged
`declared-unit-presentation/v1` in inventory, subset JSON and CSV. Nonnegative
binary seconds and closed, possibly zero-width intervals retain the existing view
convention. They establish no observation basis, clock origin, recording linkage
or scanner correspondence. They are distinct from exact rational word observations
in `RecallTiming`; no conversion from its unit summaries is supplied or licensed.
Lossless encoding preserves a supplied binary value, not an original decimal measurement.

`WorkspaceFixtures` and the JVM-only `writeWorkspaceFixtures` main in the existing
fixtures module produce the WOG and structurally different bell/clock product courts.
Authored controls, partial derivation, and optional offline lexical HSMM reconstruction
retain their distinct authority. The writer takes an explicit producer revision and emits
byte digests, archives and selected subsets. It adds no process spawning, remote inference,
public user-file workflow, module or dependency.

The timed M1 Voyage witness adds optional `PresentationClocks` with
`WorkspaceClockInput` and checked `WorkspaceClocksCodec` admission. It binds exact
model/recall bytes, inventory and source representation, a complete fixed-cut declared
source timeline, independent `RecallTiming` word observations, a recall extent and a
declaration identity. Missing clocks leave ordinary inspection intact. No unit
presentation annotation is converted to word timing; rational word coordinates remain
exact and a legacy projection must refuse unrepresentable seconds. Closed source spans
are declared presentation support, with recording correspondence explicitly unestablished.
No media, word-duration or scanner authority is inferred.

The legacy Voyage adapter may use only original compatible model-posterior decisions;
unsupported outcomes retain explicit dispositions in the complete shared inventory.
`ViewBasis.SuppliedAlignmentArtifact` records unknown execution provenance and has no
admitted scientific authority. Reject labeling a historical record as an executed stage
merely because its posterior is contextually bound, or relabeling an incompatible G1
decision to fit an older Voyage origin.

The first executable clock slice (2026-09-22) is an independent standard-library
Python intake oracle in `tools/recall-study/recall_clock.py`. Its experimental
`storymodel4s.sherlock.recall-clock-intake/v1` artifact binds declared lineage and
CSV bytes, requires an explicit released clock column, preserves all CSV records
and exact bounded numeric text, and records unavailable scanner/word-duration
authority. Alias identity is checked through the declared digest-bound map.
This supplies an independent validation target for the later Scala adapter;
canonical mapping and temporal contracts retain their existing owners. Rejected:
hard-coding a 7.5-second correction, deriving source identity from filenames,
equating CSV records with canonical word IDs, treating released TR numbers as
verified BIDS volume indices, and adding a second scientific mapper in Python.
This slice adds no Scala module, dependency or public Scala type.

The next independent reader (2026-09-22) consumes the G1 mapping inventory without
replacing contextual `MappingCodecs.decode`. `external-clock-exact-replay/v1`
checks a pinned mapping file and an admitted clock CSV through exact reconstructed
text, UTF-16 words, segmentation/inventory digests and reciprocal membership. The
experimental `storymodel4s.recall-word-clock-join/v1` output preserves every CSV
record, original word IDs, unit membership, timing witnesses and missingness.
External timing-source and original parser-input identities remain distinct;
structural correspondence does not certify shared recording identity. Rejected:
requiring those two artifact digests always to match, matching repeated words by
text, deriving ownership from a hull or empty span, silently repairing text
coordinates, or calling first/last measured onsets unit boundaries. Reunitization
changes the segmentation binding while word identity survives. Scanner authority,
durations, temporal exposure and full mapping validation remain explicitly absent.

Portable recall timing decision (2026-09-22): `RecallTiming.checked` binds exactly
one observation per checked inventory word to an explicit recall `Clock` and
correspondence provenance. `Missing`, `OnsetOnly` and half-open `Interval` stay
distinct; `SourceReported` and `Estimated` carry evidence or recipe identities.
Recording identity, time origin and recording linkage are declarations with explicit
unestablished states. They do not establish scanner authority. Use exact rational
seconds, allowing negative coordinates and refusing nonpositive intervals. Bounded
decimal intake reduces before checking the core Long representation. Reject Double
seconds, guessed timebases, inferred offsets or durations, and reuse of the edition
playback axis for a recall recording. Unit onset diagnostics retain actual boundary
members and available witnesses separately; they are not temporal exposure.

`RecallTimingCodecs` owns the separate contextual `recall-timing/v0.1` sidecar in
existing codec/recall modules, with numerator/denominator decimal strings, complete
inventory accounting, rederived unit diagnostics and strict full-record comparison.
Reject changing mapping-record/v0.1, accepting a digest as its own inventory proof,
or letting unknown/null/duplicate fields disappear. Reuse the proven mapping JSON
printer and duplicate-key scan without changing legacy codecs. Interval union,
queries and scan transformations remain separate work. No new dependency or module.

M1 legacy projection decision (2026-09-22): `WorkspaceVoyage` adapts only supplied
model-posterior decisions whose declared argmax agrees with the legacy source
argmax. Every inventory unit retains a `UnitDisposition`; incompatible decisions,
failed rows, and unsupported clocks stay in the common matrix and inspector.
The complete fixed-cut timeline is retained even when a row cannot project.
`WorkspaceClockOrigin.RecallStart` explicitly binds a supplied clock origin
reference; unestablished and arbitrary origins remain lawful workspace data but
do not license the recall-clock axis. `WorkspaceClockKind` distinguishes synthetic
presentation from other declarations. The emitted document visibly declares
synthetic clocks through `ViewBasis.SuppliedAlignmentSyntheticPresentation`, or
uses `SuppliedAlignmentArtifact` for other declared presentation; neither has an
admitted scientific-authority construction path. Configuration provenance binds
the original mapping and clock payload. Rejected: promoting unknown historical
execution to an alignment-run receipt, translating arbitrary decode semantics,
substituting the first available word for a missing first member, rounding exact
timestamps, deriving word onset from unit annotations, and calling interval end
the last word onset. The Bell profile deliberately uses a higher external cost
to exercise actual source-winning posterior rows; this is a synthetic fixture
control and establishes no scientific efficacy.

Production intake/support decision (2026-09-22): add the JVM adapter
`RecallTimingIntake` to `corpus-intake` with a dependency on `recall.jvm`, and connect
it from `pipeline` through a `corpusIntake` dependency. No new module or third-party
dependency. The adapter verifies caller-pinned bytes, an explicit exact header and
selected onset column, and replays the existing named word correspondence against
a checked graph and inventory. Retain every CSV data record, including excluded
blank word fields and their observations. Reject malformed quoting/UTF-8 and any
canonicalization or word-span mismatch. The stricter CSV parser is separately
versioned. Reject importing the bench parser, which splits quoted fields and picks
the historical clock, or accepting the independent Python envelope as full mapping
authority. The offline `recallTimingIntake` pipeline command reconstructs the
inventory from the existing canonical recall graph and explicit parser spans,
compares its declared inventory digest, and emits the existing timing sidecar plus
an intake receipt and a completion manifest. Recording linkage stays unestablished.

`TemporalSupport.read` and contextual `temporal-support/v0.1` are additive support
query outputs over the existing `SourceRepresentation`, not a second source
dictionary. Select a full-identity part or a mapping-qualified occurrence; read only
supplied geometry on that axis. Preserve exact interval unions, points, and geometry
excluded by an occurrence window. No implicit native-to-composed projection,
duration, kernel or allocation. Traverse descendants with cycle rejection and
publish both unlocated and selected-coordinate-unavailable descendants. These are
locus-accounting lists, not a claim of parent containment or probability coverage.
The legacy `supportCoverage` field only examines immediate children; changing it
would change existing source/mapping digests. Reject silently changing that wire
meaning: temporal consumers use this named transitive derivation, while migration
of the legacy producer field remains a compatibility task.

Conditional temporal-query decision (2026-09-22): add `TemporalQuery` in `align`
and contextual `temporal-query/v0.1` readouts in `codec`. These derive per-unit
region bounds and explicitly declared interval/point allocations from a checked
`MappingResult`; they do not create a new mapping record or inference authority.
`TemporalSupport.select` exposes the existing checked coordinate selection for
rows whose targets lack temporal support.

Keep normalized score mass and model posterior distinct. Preserve posterior
states, supplied totals, stage/binding identity, candidate policy, external states,
unknown-location mass and support awaiting allocation. Read actual measures even
when the decision abstains. Do not accept raw scores as probability mass or enable
reserved calibrated/Derived vocabulary by labeling a query.

Every allocation is conditional on an explicit `SuppliedSupportContainsReferent`
assumption. Supplied support is evidence, not a proof of exhaustive localization.
For this initial contract, multi-coordinate evidence, clipped occurrences,
missing descendants and descendants outside the declared parent domain remain
unavailable. Interval unions use length, point sets use counting measure; mixed
support requires a declared mixture. Points inside intervals retain atomic mass.
Regions are half-open interval unions plus points, checked on the selected axis;
geometry arithmetic uses BigInt before numerical conversion. A region result is
for the resolved component, with unresolved mass retained alongside it.

Rejected: deriving occurrence weights from display selection; source-only
renormalization; hull-based uniform allocation; treating point evidence as duration;
using immediate-child coverage as an exhaustive-support certificate. Future domain
adapters can recognize verified duplicate coordinate representations; the initial
conservative refusal must not be relaxed by inspecting only selected geometry.

Declared scanner-coordinate decision (2026-09-22): add `ScannerCrosswalk` and
`ScannerCrosswalkJson` to `corpus-intake`, and offline `scannerSamples` orchestration
to `pipeline`. Run inventories bind dataset revision, participant/session/task/run,
image and header declarations, exact sample points, origin and applied-history
knowledge. Analysis layouts bind explicit acquired indices, padding and censoring;
dropping an acquisition never compresses the original time coordinate.

Reference clocks bind either the full recall clock, a media part, or a mapping-qualified
media occurrence. Positive affine seconds-to-seconds transforms reuse `ClockRepair`
identity and receipts, with explicit validity domains. BigInt intermediates reduce
before representability checks, and malformed package-internal rationals refuse.
Media domains must fit the actual part/occurrence, and native-to-native mappings
cannot borrow the primary axis timebase. No scanner presentation axis is invented.

These types record checked declarations, not independent data admission. All scanner
wire records explicitly say `declared-not-independently-verified`. Hash identity does
not prove that a NIfTI header, recording/run join or preprocessing history was checked.
The sample CLI leaves scanner binding unestablished. Missing sample exposure and
HRF/lag operators remain separate. Rejected: guessed Sherlock offsets, implicit
rounding to volumes, treating padding as acquired data, inferring duration from TR
points, or promoting declaration labels into verified scientific authority.

The scanner binding also exposes `inverseWindow` for explicitly declared analysis
bins and `mediaWindow` carrying the actual axis, exact playback interval and binding.
Excluded endpoints can coincide with the validity boundary; partial-domain bins and
fractional media ticks refuse. This bridges existing temporal region queries without
adding a corpus-intake dependency on align or placing scientific arithmetic in pipeline.

Temporal view handoff decision (2026-09-22): `TemporalPartitionView` retains a
checked partition and projects its bins/contributions to stable marks and existing
RecallRef addresses. It performs no probability or clock arithmetic and introduces
no second scientific wire schema. Navigation requires the mapping digest; source
targets and original alternatives remain explicit. Reject converting score mass
to faithful posterior cells or labeling supplied measures as an executed aligner.
A full scene awaits the neutral provenance vocabulary on the workspace M1 branch;
its current text-only packet also needs media-aware source admission before these
queries can be joined. A separate declared display timeline is not media support.

### 2026-09-22 — base mapping exchange component

`MappingExchange` packages one checked `MappingResult` as the authoritative
`mapping-record/v0.1` plus lossless, quoted UTF-8 TSV projections and a byte-hashed
`mapping-exchange/v0.1` manifest. Its decoder requires `ExpectedMappingContext`,
rechecks the record, then regenerates the entire package. Matching file hashes alone
was rejected as insufficient: a rehashed table must not change the record's meaning.
Integer coordinates remain exact decimal strings, binary64 measures keep canonical
IEEE bits, and nested support/missingness/state records retain canonical JSON.
Direct TSV strings with malformed UTF-16 refuse publication; escaped JSON retains
the existing wire semantics. No transcript column is added, but caller-provided
labels, reasons and receipts are preserved, so the package is not anonymized.

`MappingExchangeBuild` owns local file I/O and publishes the completed manifest
only after every payload is written. Partial directories are explicitly incomplete.
The independent Python consumer validates package/schema/projection joins; it does
not claim the Scala contextual derivation check. Temporal, scanner, organization,
calibration and paired-profile capabilities remain explicitly unavailable in this
base component. The facade owns the later reference/reconstruction/lambda envelope
and shared evidence identity; the exchange must not invent those semantics. This
component therefore does not close the complete paired exchange acceptance ticket.

`MappingExchangeBuild.exportWorkspace` accepts an ordinary workspace file and an
explicit `view.ArtifactId`. It performs the full scientific join and checks the
actual model/recall-bound export grant before publishing the unchanged selected
record. `WorkspaceExportReceipt` binds exact input bytes, policy, record and
completed manifest. Implicit first-policy selection and inspection-as-export were
rejected: both would hide a consequential choice at the CLI boundary. Diagnostics
remain typed and content-free; the caller owns their presentation.

The JVM entry point `storymodel4s.pipeline.mappingExchangeExport` requires exactly
`WORKSPACE POLICY OUTPUT`, with no implicit policy or output overwrite. Success
prints `workspace-mapping-export-receipt/v0.1` JSON binding `input_sha256`,
`policy_id`, `mapping_digest` and `manifest_sha256`. Refusals produce content-free
error JSON on stderr and exit 2. The command delegates to the same checked producer;
it neither performs inference nor promises the unfinished study-preparation facade.
