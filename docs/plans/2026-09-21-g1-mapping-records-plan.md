# G1 mapping records: implementation plan

Mote `bd-01M2WVENSB0P955Y0B603CC20Y`. Written 2026-09-21 against main `632ddd80`. **Plan only.**
Nothing named below exists yet. Type, file and suite names are acceptance specifications, not
implemented or executed code. Governing documents: [PLAN](../refactor/PLAN.md) §§2–3,
[ADR 0019](../adr/0019-mapping-measurement-policy.md), and the
[analysis contract](../refactor/ANALYSIS_CONTRACT.md) (below, "the contract"). Code references
are `path:line` at `632ddd80`.

## 1. Goal and non-goals

**Goal.** One checked, versioned `MappingResult` for one recall against one checked source
representation, owned by existing modules, with a 0.x JSON codec. It accounts for every requested
unit and word, every target and its exact physical support, alternatives, missingness, external
outcomes, processing failures and decomposition status. It keeps the five measure kinds, raw
argmax, decoded choice, calibration and fidelity distinct. It carries the policy and stage
provenance that later compatibility checks read. Historical artifacts are fixed at unknown
provenance.

**Non-goals, each owned elsewhere:**

| Excluded work | Owner |
|---|---|
| Extracting local evidence; producing `NormalizedScoreMass`; candidate/render/scoring receipts | `bd-01M2TACM78289S4TECE91GT5K2` |
| Reference inference; opaque target renaming | `bd-01M2WVF86T8QEEA1TK8Z0ASJHW` |
| Compatibility outcomes (`CompatibleWithDeclaredPolicy`, …); G1 computes none | `bd-01M2WVFV2WGF5BVQHX7JTYYWRH` |
| Word timing, `RecallTiming` extraction, per-axis interval unions, display hull, time grid | `bd-01M2WVH1DC8ZPDC992G4MX3TXG` |
| TSV exchange tables, file hashes, readiness/publication, Python/R reader | `bd-01M2WVHF4B5DAXJYY4W91VK4WV`, `bd-01M2TADC4VKSDZ2S9SXETH2MYM` |
| Moving `MonotoneScene`/fill into the library; recording fill origin at the decoder | `bd-01M2TAD04SR823TQVG9VPNH6R3` |

Also out of scope: inference numerics, StoryModel/HSMM schemas, embed-bench outputs (TSV,
sidecars, voyage stay byte-identical, with their known conflations), a decomposition detector,
calibration artifacts and new modules. This ticket adds the correct path; it does not repair
legacy writers.

## 2. Decisions

| # | Decision | Basis |
|---|---|---|
| D1 | **Support honesty lands first** on `solo/support-honesty` (`bd-01M19956MFSG7076QE4J66T7E9`). It ships `SupportAssessment` = `Assessed` (share derived from measured terms, a nonempty eligible set and exact eligible weights), `Unestablished(reason)` or `NotApplicable(ExternalState \| Unreachable)`, plus a sealed non-case `CostBreakdown` and hsmm/v4 with tagged support. hsmm/v4 carries no `TypedSupport` intervals and keeps refusing non-text support. The mapping-records schema is therefore the only wire for physical support and ticks. Record links consume `SupportAssessment`. No `supportWeight: Double` and no placeholder anywhere. | Owner ruling 2026-09-21, recorded on the support ticket (commit `632ddd80`). Today's numeric `supportOf` returns 1.0 when nothing is eligible (`align/.../cost.scala:842-851`). |
| D2 | **Bundle representation follows PLAN §3.** A checked source representation holds a bundle inventory (the part bundles) and per-target, per-axis bundle membership. D1B's `sherlock-nn2017-composed` bundle enters only as a *declared composition* carrying its `TrackComposition` receipts. Cross-part order comes only from a verified presentation-order declaration, never from lexicographic part IDs. Without one, cross-part adjacencies are `Incomparable`. The canonical `StoryModel` stays one bundle under D1A. | PLAN.md:149-154; contract `manifest.json` (ANALYSIS_CONTRACT.md:37-40); composition in `corpus-intake/.../SherlockSourceAtlas.scala:31-181`. |
| D3 | **Decomposition:** every unit carries `NotAssessed(reason)` explicitly in 0.x. This does not by itself block reference compatibility, but the record and readouts disclose it. No detector is built here. | Coordinator ruling; ANALYSIS_CONTRACT.md:13 forbids silently normalizing a multi-referent passage. |
| D4 | **Three supports keep three names in new fields:** `sourceSupport` / `source_support` (physical, `TypedSupport`), `termSupport` / `term_support` (content-term `SupportAssessment`), `supportCoverage` / `support_coverage` (group completeness). Existing core/align types are not renamed, including core's physical `EvidenceSupport` (`core/.../source.scala:1324`). | Coordinator ruling. |
| D5 | **The contract's vocabulary wins.** Where it differs from the earlier sketch, §2.1 records which wins and why. | Coordinator ruling; ANALYSIS_CONTRACT.md:3. |
| D6 | **One slice at a time; no new module.** `recall` owns units and word membership, `align` owns records, measures and provenance, `codec` owns the wire. The 0.x mapping schema is separate from StoryModel and HSMM schemas. | PLAN.md:95-101 (§2.7–2.8); ticket boundary. |
| D7 | **Authority-bearing sum types are sealed traits with bare-private `final class` cases, not enum cases.** A Scala 3 enum case gets a public `apply`, `copy` and `Mirror`. Payload-free vocabularies (`Stage`, `ScoreDirection`, `LocalizationStatus`) may be enums. | Same pattern as the support-assessment successor (b33fe5b5); `CostBreakdown` forgery via `fromProduct` is acknowledged at `codec/.../align.scala:52-57`. |

### 2.1 Where the contract and the earlier sketch differ

| Topic | Resolution | Why |
|---|---|---|
| Link rows | Scala uses separate typed measure classes. The wire flattens them to the contract's long-form `mapping_links` rows tagged `measure_kind`. | AC2 needs compile-time separation; the contract governs wire names (ANALYSIS_CONTRACT.md:84-90). |
| Status vocabularies | Contract names are used exactly. `partial`, `ambiguous` and `manual-review` are **reserved**: no G1 producer exists and the 0.1 decoder refuses them. | A value with no producer is a forgery door from the wire. |
| Gap-fill mass | Contract wins on the wire: `decoded_target_mass` is exact zero with `decision_in_candidate_support=false`. Scala holds `OutsideCandidateSupport` with no `Double`, and the codec requires zero ⇔ outside. | ANALYSIS_CONTRACT.md:98; the type couples flag and value. |
| Fidelity | `fidelity_status` / `fidelity_facets` come only from `FidelityReport`. A new `gate_outcome` field (not in the contract) holds the ModeGate result. | ANALYSIS_CONTRACT.md:118 forbids exporting inherited `Faithful` as an assessment. |
| Content-term support | Contract `assessment_support_id` is undefined anywhere. It is replaced by an inline tagged `term_support`. | D1, D4. |
| Target identity | `target_id` = `SourceNodeRef.key` under declared policy `target-id/source-node-ref/v1`. Opaque IDs arrive with the reference ticket. | D4 (no renames); reference ticket AC3. |
| Word and timing fields | G1 carries word ID, index, UTF-16 span and membership only. Timing columns belong to G2. | G2 boundary (recall timing extraction). |
| Manifest | G1 carries semantic identity plus the §8 policy fields. `file_hashes`, `readiness` and publication are omitted. | Exports and facade tickets. |
| `measurement_compatibility` | Absent in 0.1. G1 carries the stage ledger it will be derived from. | Compatibility ticket owns the outcome. |

## 3. Design by acceptance criterion

### AC1: one checked result accounts for everything

**recall:** new `recall/src/main/scala/storymodel4s/recall/inventory.scala`.

```scala
object RecallWordId extends OpaqueId("RecallWordId")
final class WordIdPolicy private (val name: String, val artifact: Checksum) // G0: "input-artifact-sha256+zero-based-parsed-word-index/v1"
final class RecallWord private (val id: RecallWordId, val index: Int, val span: TextSpan)
sealed trait WordMembership      // Member(unit: RecallUnitId) | Unassigned(NotInAnyUnitSpan)
final class SegmentationId private (val digest: Checksum) // transcript checksum + ordered (unit id, span set)
sealed trait DecompositionStatus // 0.1: only NotAssessed(reason = NoDecompositionDetector)
enum MappingSemantics { case CategoricalReferent }
final class InventoryUnit private (val id: RecallUnitId, val ordinal: Int, val span: SpanSet,
    val words: Vector[RecallWordId], val decomposition: DecompositionStatus, val semantics: MappingSemantics)
final class RecallInventory private (val transcript: Checksum, val segmentation: SegmentationId,
    val idPolicy: WordIdPolicy, val words: Vector[RecallWord], val units: Vector[InventoryUnit],
    val membership: Map[RecallWordId, WordMembership]):
  def digest: Checksum
object RecallInventory:
  def of(graph: RecallGraph[Checked], wordSpans: Vector[TextSpan], policy: WordIdPolicy): Either[DomainError, RecallInventory]
```

Word IDs are derived by the policy, never supplied (`embed-bench/.../SherlockBaselineCapture.scala:84,102`).
Membership is derived by span overlap (the rule at `SherlockBaselineCapture.scala:120-121`); a word
in two units refuses; a word in no unit is `Unassigned` (the G0 run's 94 conjunction separators).
`SegmentationId` binds unit identity to the unitization, because segmenter unit IDs are
positional (`recall/.../segmenter.scala:435`).

**align:** new `align/src/main/scala/storymodel4s/align/mapping.scala`.

```scala
enum Destination { case Target(ref: SourceNodeRef); case External(state: ExternalState) }
sealed trait ProcessingStatus // Complete | Failed(ProcessingFailure) | ExcludedByInputPolicy(reason); Partial reserved
enum ProcessingFailure { case ProviderFailure(d: String); case InvalidOutput(d: String); case InferenceRefused(d: String) }
enum LocalizationStatus { case Located, Nonlocalizable, Unranked, NotComputed } // Ambiguous reserved
final class MappingLink private (val destination: Destination, val gate: GateOutcome,
    val fidelity: FidelityStatus, val termSupport: SupportAssessment)
final class UnitOutcome private (val unit: RecallUnitId, val processing: ProcessingStatus,
    val localization: LocalizationStatus, val links: Vector[MappingLink],
    val measures: UnitMeasures, val decision: Option[UnitDecision])
object UnitOutcome:
  def computed(unit: RecallUnitId, measures: UnitMeasures, links: Vector[MappingLink],
      request: DecisionRequest, candidates: Vector[SourceNodeRef]): Either[MappingRefusal, UnitOutcome]
  def failed(unit: RecallUnitId, failure: ProcessingFailure): UnitOutcome
  def excluded(unit: RecallUnitId, reason: String): UnitOutcome
final class MappingResult private (val inventory: RecallInventory, val source: SourceRepresentation,
    val policies: MappingPolicies, val roles: UnitRoles, val ledger: StageLedger, val outcomes: Vector[UnitOutcome]):
  def digest: Checksum
object MappingResult:
  def checked(inventory: RecallInventory, source: SourceRepresentation, policies: MappingPolicies,
      roles: UnitRoles, ledger: StageLedger, outcomes: Vector[UnitOutcome]): Either[MappingRefusal, MappingResult]
```

`checked` requires outcomes to be exactly the inventory's units in inventory order (no missing,
duplicate or extra unit; failed/excluded units retained). It refuses destinations outside the
target universe, unknown candidates, duplicate destinations within a unit and measures keyed by
unknown destinations. Missingness is always explicit: `Failed`/`Excluded` outcomes, `Unranked`,
`Unassigned` words and `SupportCoverage`. No unit may vanish through a defaulted lookup:
`Candidates.set` currently turns a missing unit into `unranked` (`align/.../candidates.scala:77-79`),
so records take candidates per unit and refuse a missing entry.

### AC2: five measure kinds and fidelity stay distinct

New `align/src/main/scala/storymodel4s/align/mappingmeasures.scala`:

```scala
enum MeasureKind { case RawScore, NormalizedScoreMass, TransportMass, ModelPosterior, CalibratedProbability }
enum ScoreDirection { case HigherIsBetter, LowerIsBetter }
final class RawScores private (val channel: String, val direction: ScoreDirection, val scale: String, val values: Map[Destination, Double])
final class NormalizedScoreMass private (val universe: TargetUniverseId, val prior: ReferencePriorId, val temperature: Double, val mass: Map[Destination, Double]) // Σ = 1 ± tol
final class TransportMass private (val rowBudget: Double, val mass: Map[Destination, Double]) // no Σ = 1
final class ModelPosterior private (val stateSpace: Checksum, val mass: Map[AlignState, Double]) // Σ = 1 incl. externals
object ModelPosterior { def of(result: HsmmResult, unit: RecallUnitId): Option[ModelPosterior] } // the only door
sealed trait CalibratedEvent // ChosenDecisionCorrect(policy: DecisionPolicyId)
final class CalibratedProbability private (val event: CalibratedEvent, val p: Probability, val artifact: CalibrationArtifactId, val domain: String)
final class UnitMeasures private (val raw: Vector[RawScores], val normalized: Option[NormalizedScoreMass],
    val transport: Option[TransportMass], val posterior: Option[ModelPosterior])
enum GateOutcome { case NoContradictionDetected; case Contradicted(facets: NonEmptySet[Facet]); case NotGated }
sealed trait FidelityStatus  // NotAssessed(reason) | Assessed(report: FidelityReport) | NotApplicable (external)
```

**Measures.** There is no shared numeric supertype, so a `TransportMass` can never stand where a
`ModelPosterior` is required; today both are `AlignmentMatrix` (`align/.../sinkhorn.scala:193-228`,
`matrix.scala:112`). `ModelPosterior` is reachable only from a gated `HsmmResult`, so ungated
transport output cannot be relabelled. Public factories for raw, normalized and transport kinds
check shape only; authority comes from the stage ledger (AC3). No calibration artifact exists, so
every G1 record carries `DecisionCalibration.Unavailable(NoCalibrationArtifact)`.

**Fidelity.** `Assessed` is derived only via `FidelityFacets.assess` (`facets.scala:79-99`) when
the node's scope is `PropositionalScope.Declared` (`align/.../source.scala:102-115`). Video
targets are `Undeclared` (`RecallToVideo.scala:175,200`) and yield `NotAssessed`, although
`AlignState.Source` *is* the Faithful mode (`matrix.scala:18,46`). `GateOutcome` never renders as
"faithful".

**Decisions** (in `mapping.scala`):

```scala
sealed trait DecisionRequest     // RawArgmax | ExternalDecode(chosen: Destination, policy: DecisionPolicyId)
enum DecisionOrigin { case RawArgmax; case StructuredDecode(p: DecisionPolicyId); case GapFill(p: DecisionPolicyId); case Abstention(reason: String) }
sealed trait DecodedTargetMass   // InCandidateSupport(kind: MeasureKind, value: Double) | OutsideCandidateSupport
sealed trait DecisionCalibration // Unavailable(reason) | Calibrated(p: CalibratedProbability)
final class UnitDecision private (val chosen: Option[Destination], val origin: DecisionOrigin,
    val rawArgmax: Option[(Destination, MeasureKind, Double)], val decodedMass: DecodedTargetMass,
    val calibration: DecisionCalibration)
```

`rawArgmax`, `decodedMass` and `origin` are **derived**, never supplied, from the unit's single
declared decision-basis measure and its candidate list. The argmax covers the full state space
including externals (ties by key as in `matrix.scala:179-182`; RawScores direction respected).
`ExternalDecode` becomes `StructuredDecode` inside candidate support and `GapFill` outside it. A
decode that agrees with the argmax stays `StructuredDecode`, fixing the coincidence rule in
`embed-bench/.../VoyageExport.scala:113-118`. These types remove three current conflations: the
TSV's decoded anchor beside the argmax's mass and mode (`RecallToVideo.scala:654,677,680`), 0.0
sidecar mass for fills (`PosteriorSidecar.scala:65`), and `constrained=true` for both bound and
filled choices (`MonotoneScene.scala:216-223`).

### AC3: policies, universe, receipts and provenance

New `align/src/main/scala/storymodel4s/align/mappingprovenance.scala`:

```scala
enum Stage { case Candidates, Rendering, Scoring, Context, Refinement, Inference, Decision, Projection }
final class StageReceipt private (val stage: Stage, val policy: String, val config: Checksum, val inputs: Vector[Checksum])
sealed trait StageProvenance  // Derived(receipt) [library producers only] | Unknown(reason, asserted: Option[StageReceipt])
enum UnknownProvenanceReason { case HistoricalArtifact, WireAssertedOnly, NominationProvenanceUnproven, CallerSuppliedDecision }
final class StageLedger private (val stages: Map[Stage, StageProvenance]) // total over Stage.values
sealed trait InferencePolicy   // 0.1: HistoricalReconstruction(label) | Unspecified; later tickets add Reference/Structured
sealed trait CandidateCoverage // Truncated(perLevel) | Complete | Unknown(reason)
final class MappingPolicies private (val inference: InferencePolicy, val context: ContextPolicy,
    val candidate: CandidateCoverage, val referencePrior: Option[ReferencePriorId], val decision: Option[DecisionPolicyId])
enum AnalysisGrain { case InferenceUnit(s: SegmentationId); case Word; case Targets(g: TargetGrain) }
final class UnitRoles private (val inference: AnalysisGrain, val organization: AnalysisGrain, val projection: AnalysisGrain)
```

Policy fields use contract §8 names (ANALYSIS_CONTRACT.md:164-170). No caller can mint `Derived`;
in G1 nothing produces it (the adapter and codec produce `Unknown`), and the first producers are
the evidence and reference tickets. Existing precedents are narrow: `LayerUse` is derived but
transition-only (`hsmm.scala:134-187`), and the bench `provenanceConfig` is a caller label
(`RecallToVideo.scala:432`). Target universe and grain are derived from the source representation;
Sherlock's 1,000 level-0 rows plus 50 level-1 scenes become `TargetGrain.Hierarchy(0,1)`.

### AC4: multipart sources and exact coordinates

New `align/src/main/scala/storymodel4s/align/mappingsource.scala`:

```scala
sealed trait BundleEntry   // Media(bundle: SourceBundle) | TextSource(canonicalText: Checksum)
final class DeclaredComposition private (val composed: SourceBundle, val compositions: NonEmptyVector[TrackComposition])
final class MappingTarget private (val ref: SourceNodeRef, val level: Int, val parent: Option[SourceNodeRef],
    val sourceSupport: TypedSupport, val axisMembership: Map[PresentationAxisId, SourceBundleId],
    val supportCoverage: SupportCoverage, val propositional: PropositionalScope)
sealed trait SupportCoverage // Complete | Partial(missing: NonEmptySet[SourceNodeRef]) | Unknown(reason)
enum TargetGrain { case SingleLevel(level: Int); case Hierarchy(levels: Vector[Int]) }
enum PartOrder { case Before, Same, After, Incomparable }
final class SourceRepresentation private (val bundles: NonEmptyVector[BundleEntry],
    val composition: Option[DeclaredComposition], val targets: Vector[MappingTarget], val grain: TargetGrain):
  def digest: Checksum
  def universe: TargetUniverseId
  def order(a: PresentationAxisId, b: PresentationAxisId): PartOrder // from composition segments only
object SourceRepresentation:
  def of(view: SourceView, bundles: NonEmptyVector[BundleEntry], composition: Option[DeclaredComposition]): Either[MappingRefusal, SourceRepresentation]
```

Every anchor axis must resolve to exactly one listed bundle (a part's primary axis or the declared
composition's primary axis), or the target refuses as a foreign axis; duplicate target refs
refuse. Sherlock rows bind the composed bundle ID while carrying part-native axes
(`SherlockSourceAtlas.scala:130-156`), so membership resolves through the stream's native axis,
not `anchor.bundle` (risk R4). Part bundles come from `corpus-intake/.../SherlockAnnotations.scala:98-99`
and compositions from `SherlockSourceAtlas.compositions`. `supportCoverage` is `Partial` when a
declared child lacks support (miniature `g1`), `Complete` for a supported leaf and `Unknown`
otherwise; unions and hulls are G2 work. Exact coordinates stay in `TypedSupport`
(`core/.../support.scala:4`). Today the codec writes decimal ticks (`codec/.../core.scala:110-116,142`)
but cannot decode anchored support (`core.scala:170-177`), and bench output uses float seconds.

### AC5: refusal, forgery, round-trips and falsification

Probes are in §7 and courts in §8. The two named falsifications: setting the decoded value to the
raw-argmax value fails `MappingOutcomeSuite`; dropping the `p6` failure row fails
`MappingResultSuite` and `MappingCodecSuite`.

## 4. Codec design

New `codec/src/main/scala/storymodel4s/codec/mapping.scala`, object `MappingCodecs`.

- **Schema.** `"schema": "storymodel4s.mapping-record"`, `"schemaVersion": "mapping-record/v0.1"`;
  any other version refuses (`CodecError.UnsupportedSchema`). No migration: 0.x records are
  re-derived. Independent of hsmm/v4 and StoryModel.
- **Canonical form.** Canonical printing (`codec/.../canonical.scala:22-37`), Doubles as exact
  IEEE-754 hex (`canonical.scala:79`), and a canonical-text guard on decode (precedent:
  `VoyageCodecs.decode`, `codec/.../voyage.scala:40`).
- **Exact integers.** Ticks, rational numerators/denominators, extents and `Long` identifiers are
  JSON **strings** of base-10 digits; a JSON number there refuses (Scala.js circe parses numbers
  through `Double`; the JVM would silently accept them). A hand-authored golden text with
  `"startTick":"9007199254740993"` (2^53+1) and an equal rational denominator must round-trip
  byte-for-byte on all three platforms.
- **Contextual decoding:** `decode(text, expected: ExpectedMappingContext)`, with the expected
  `RecallInventory`, `SourceRepresentation` and an optional `StageLedgerExpectation`. The wire's
  `inventoryDigest` and `sourceDigest` must equal the expected digests (blocking a foreign recall,
  source or axis). Anchored support is rebuilt with `EvidenceSupport.of(bundle, anchors)` against
  the expected bundles, and decoding finishes through `MappingResult.checked`. This mirrors
  `HsmmResultCodec` (`codec/.../align.scala:30-36`); non-Scala readers use the self-describing loci.
- **Provenance never decodes to `Derived` on the wire's word.** Without an expectation, every
  stage decodes as `Unknown(WireAssertedOnly, asserted = Some(r))`. With an independently supplied
  expectation, a stage becomes `Derived` only on an exact receipt match; a mismatch refuses. This
  follows the hsmm/v4 ruling that an embedded basis proves internal consistency only.
- **Content-term support** reuses support honesty's `SupportAssessment` encoding under
  `term_support`. There is no parallel vocabulary.
- **Wire field names** follow the contract (`processing_status`, `localization_status`,
  `raw_argmax_*`, `decoded_target_*`, `decision_origin`, `decision_in_candidate_support`,
  `calibrated_decision_confidence`, `measure_kind`, `normalization_scope`, `fidelity_status`,
  `support_relation`, `decomposition_status`, …) plus `gate_outcome`, `term_support`,
  `source_support` and `support_coverage` (§2.1). Reserved values refuse.

## 5. Historical HsmmResult adapter

New `align/src/main/scala/storymodel4s/align/mappinghistory.scala`:

```scala
object HistoricalMapping:
  def of(result: HsmmResult, recall: RecallGraph[Checked], inventory: RecallInventory,
      source: SourceRepresentation, view: SourceView, decode: HistoricalDecode): Either[MappingRefusal, MappingResult]
enum HistoricalDecode { case ArgmaxOnly; case Decoded(chosen: Vector[Option[SourceNodeRef]], label: String) }
```

The ledger is `Unknown(HistoricalArtifact)` for all eight stages, because `HsmmResult` does not
carry its `HsmmConfig`; the policy is `HistoricalReconstruction(label)`. `ModelPosterior.of`
supplies bit-identical masses, and `RawScores` come from `CostBreakdown.total` (channel
`local-cost`, `LowerIsBetter`). `termSupport` is read from the sealed breakdown per state;
externals must be `NotApplicable(ExternalState)`, and a posterior state with no breakdown refuses
(support is never defaulted). Candidates come from `result.candidateAnchors` (`hsmm.scala:301`).
`Decoded` yields `StructuredDecode` or `GapFill` derived from candidate support; no chosen anchor
is `Abstention`; calibration is `Unavailable`. Every inventory unit is `Complete` because
`GraphHsmm.infer` (`hsmm.scala:652`) is all-or-nothing; requested-but-absent units enter
`MappingResult.checked` as explicit `failed` outcomes. No embed-bench file changes; moving
`MonotoneScene` belongs to `bd-01M2TAD04SR823TQVG9VPNH6R3`.

## 6. Independent miniature

**Source file.** `tools/recall-study/fixtures/baseline-miniatures.json`: 4,089 bytes, sha256
`be3f8c3d8b4595cd4e58ba0ef8135f26b702cc9c470f4d0a0b2ed8f17d742fc3` at `632ddd80`, admitted by a
fresh-context reviewer. It already contains two parts at 10 ticks/s with the instant `e8` and the
unlocated `e4`; partial group `g1` = {`e2`, `e4`}; external packet `p5`; failure control `p6`;
argmax `e3` versus decoded `e7` on `p3` (which also lacks timing); ambiguous `p7`; revisit `p8`.

**Transcription.** A plain-data Scala transcription (ticks kept as strings) goes in
`laws/src/main/scala/storymodel4s/laws/MappingMiniature.scala`. Laws is cross-built and depends
on align and recall (`build.sbt:476-480`); codec tests see it (`build.sbt:412`). Tests author
numeric rows as `RawScores` with channel `miniature-authored`, because the fixture has no masses
and must not be relabelled as a posterior. The fixture declares no composition, so e2→e7 is
`Incomparable`. It becomes `Before` only under a composition that the test declares explicitly.

**JVM digest court.** `codec/.jvm/src/test/scala/storymodel4s/codec/MappingMiniatureDigestSuite.scala`
(JVM-only precedent: `fixtures/.jvm/src/test/.../WarOfTheGhostsCodecGoldenResourceSuite.scala`)
finds the repo root by walking up to `build.sbt`, because forked tests run from the module
directory. It checks the pinned sha256, then field-by-field equality of the parsed JSON against
the transcription. The JS and Native suites use the transcription only.

## 7. Probe and external-consumer suites

**Placement.** Probes sit outside the owning package: `align/src/test/scala/storymodel4s/probes/MappingUnforgeableSuite.scala`,
`recall/src/test/scala/storymodel4s/probes/RecallInventoryUnforgeableSuite.scala` and
`codec/src/test/scala/storymodel4s/codecprobe/MappingCodecProbeSuite.scala`. Qualified-private
widening is caught from sibling `storymodel4s.align.attack` and `storymodel4s.recall.attack`
packages.

**Refusals.** For each sealed type: `new`, `apply`, `copy`, `fromProduct`, `Mirror.ProductOf`, and
enum-case construction of authority cases. Specifically: no `ModelPosterior` from an
`AlignmentMatrix`, no `StageProvenance.Derived` from a label, no `DecisionCalibration.Calibrated`
from a `Double`, no `TransportMass` where a `ModelPosterior` is required (including via a type
alias), and no decoded `Derived` without an expectation.

**Controls** follow `align/src/test/scala/storymodel4s/probes/CellCoordinatesUnforgeableSuite.scala`:
positive controls first (an align case class *does* derive a Mirror; every checked factory stays
reachable), plus `classOf` dependencies so Zinc invalidates the court (precedent:
`codec/src/test/.../codecprobe/ConstructionProbeSuite.scala`).

**Aliasing** in this repo means mutable arrays (ADR 0018:102; `corpus/src/test/.../VerifySuite.scala:60-183`).
Records hold only immutable collections; a runtime court mutates every accessor result and
re-checks `digest`.

**External-consumer construction.** `laws/src/test/scala/storymodel4s/laws/MappingContractSuite.scala`
(outside align; JVM, JS and Native) builds the full miniature record from public factories only.
It asserts unit, word and target accounting; `p6` retained as `Failed`; `p5` `Nonlocalizable`; the
`p3` disagreement; `g1` `Partial`; `e4` without support; and foreign-axis, duplicate-target and
missing-unit refusals.

## 8. Slices

**Slice 0 (precondition, not G1 work).** Support honesty lands on main. Record the G1 base SHA
and confirm the `SupportAssessment` and `CostBreakdown` accessor names before Slice 3.

Every mutation below is a compiled production edit whose named rejecting test fails while its
named accepting control passes. Compile-door mutants require `Compile/clean` and `Test/clean`
first (S4c practice; stale `typeChecks` warning at `CellCoordinatesUnforgeableSuite.scala:64-67`).
Platforms are JVM, JS and Native unless stated.

| Slice | Changed paths | Falsifying mutations → rejecting test | Accepting control |
|---|---|---|---|
| 1 Miniature | `laws/.../MappingMiniature.scala`; `codec/.jvm/.../MappingMiniatureDigestSuite.scala` | Change the `e7` end tick "30"→"31" → digest-equality test. Drop `p6` from the transcription → the same test. (JVM court; the transcription compiles on all three.) | Unchanged transcription equals the parsed JSON. |
| 2 Inventory | `recall/.../inventory.scala`; `RecallInventorySuite`; probe + `attack` suites | Filter `Unassigned` words → "every parsed word is accounted". First-unit wins on an overlap → "a word in two units refuses". Segmentation digest from transcript only → "changed unitization changes SegmentationId". Constructor widened to `private[recall]` → attack probe. | Inline eight-packet inventory with one separator word `Unassigned`. |
| 3 Measures and fidelity | `align/.../mappingmeasures.scala`; `MappingMeasuresSuite`; `MappingUnforgeableSuite` | Drop Σ=1 in `NormalizedScoreMass` → "unnormalized mass refuses". Public `ModelPosterior.of(AlignmentMatrix)` → probe. `type TransportMass = ModelPosterior` → probe. Fidelity from `AlignState.mode` → "undeclared scope yields NotAssessed on a Source state". `CalibratedProbability` from a bare `Double` → probe. | Declared-scope node gives `Assessed` equal to `FidelityFacets.assess`. |
| 4 Outcomes and decisions | `align/.../mapping.scala` (outcome/decision part); `MappingOutcomeSuite` | `decodedMass := rawArgmax` → "p3: decoded keeps e7's own value" (authored row e3 .9, e7 .4). Outside-support fill as `InCandidateSupport(0.0)` → "gap fill is labelled outside support". Decode agreeing with the argmax labelled `RawArgmax` → "agreeing decode stays StructuredDecode". `Unranked` → `Nonlocalizable` → "unranked stays unranked". | `RawArgmax` decision on the same row. |
| 5 Source and result | `align/.../mappingsource.scala`, `mappingprovenance.scala`, `mapping.scala` (`checked`); `SourceRepresentationSuite`, `MappingResultSuite` | Remove foreign-axis refusal → "axis outside inventory refuses". Part-ID compare without a composition → "e2→e7 Incomparable without declaration". Remove duplicate-target refusal. Accept a missing unit → "missing unit refuses". Filter failed outcomes before accounting → "p6 retained and counted". Public `Derived` door → probe. `g1` coverage `Complete` → "g1 is Partial". | Two-part miniature accepted; `Before` under the declared composition. |
| 6 Historical adapter | `align/.../mappinghistory.scala`; `MappingHistoricalSuite` (tiny `GraphHsmm` run from existing align fixtures) | Raw argmax via `mapSource` → "all-external (Unranked) row: argmax is external". Any stage `Derived` → "historical ledger all Unknown". External `termSupport` as `Assessed` → "external links NotApplicable". Default support for a posterior state with no breakdown → "missing breakdown refuses". | `ModelPosterior` masses bit-equal to `result.posterior`. |
| 7 Codec | `codec/.../mapping.scala`; `MappingCodecSuite`; `MappingCodecProbeSuite` | Encode ticks as numbers → golden-text court (2^53+1). Decoder accepts numeric ticks → "numeric tick refuses". Embedded receipts decode to `Derived` → "no expectation ⇒ Unknown(WireAssertedOnly)". Remove canonical guard → "unknown field refuses". Skip digest comparison → "foreign inventory refuses". Drop failed outcomes on encode → p6 round trip. Remove version check → "v0.2 refuses". | Miniature round trip exact and canonical fixed point. |
| 8 Consumer and aliasing | `laws/src/test/.../MappingContractSuite.scala`; aliasing court in `MappingResultSuite` | Store link masses in an exposed `Array[Double]` → "accessor mutation leaves digest unchanged". Remove the outcome-order check → "reordered outcomes refuse". | Full public-factory miniature build. |

**Rough size (estimate):** about 1,300–1,700 main lines and 1,800–2,300 test lines. Slices 5
and 7 are the largest; 3, 4 and 6 are medium; 1, 2 and 8 are small.

## 9. Evidence and gates

The evidence directory is `docs/refactor/evidence/g1-mapping-records-<date>/`. It records:
- base and result SHA, and `bash tools/reference-scope.sh BASE HEAD` as a union over every commit;
- focused commands with bound totals per slice on `recall`, `align`, `codec` and `laws`, each on
  JVM, JS and Native;
- `mutations.json` and `guard-witness-inventory.json`: one row per §8 mutation with source and
  mutant hashes, the failing test, the passing control and clean-recompile flags for compile doors;
- the final `sbt -batch checkAll` in a **clean standalone clone** (linked worktrees are unreliable
  for sbt) with bound totals and named skips, `scalafmtCheckAll` last, and docs examples only if
  docs change;
- a separate fresh-context SD6 review (`review.json`).

**Compatibility impact is additive:** no existing type, schema or wire changes; S0 frozen values,
WOG HSMM golden bytes (hsmm/v4 after D1) and frozen Sherlock artifacts stay unchanged, and any
numeric movement is a defect. The landing also updates the `codec/README.md` schema table and
`docs/api-stability.md` (`mapping-record/v0.1` as experimental 0.x).

## 10. Risks

- **R1 Support API drift.** Accessor names from D1 may differ from this plan. Re-read at Slice 0.
- **R2–R3 False probe passes.** Enum-case authority leaks (D7) need every authority case
  enumerated; stale `typeChecks` expansions need the clean-recompile rule.
- **R4 Composed-bundle join.** Membership by `anchor.bundle` would attribute Sherlock rows to the
  composed bundle and hide the parts; resolve by stream and native axis (record any extra
  corpus-intake test path). **R5 Text sources:** `TypedSupport.Text` names no bundle; check the
  `TextSource` entry against S4c's `TextAlignmentSource` before Slice 5.
- **R6 Unguaranteed invariant.** If `HsmmResult.validated` does not guarantee a breakdown for
  every posterior state, the adapter's refusal may reject real results. Verify at Slice 6 and
  report rather than default.
- **R7 Scope creep** into timing, unions, normalized-mass production or bench rewrites: refuse it
  and point to the owning ticket. **R8 Record size:** real Sherlock records (1,050 targets, 173
  units) are large JSON; acceptable for 0.x. **R9 Concurrent edits:** G1 touches no embed-bench file.

## 11. What this will not establish

Localization accuracy, candidate recall or specificity on any real recall; calibration (every
record says `Unavailable`); reference-measurement compatibility, or that any run used a reference
policy (historical records are unknown provenance by construction); behavioral or organizational
recovery; that historical choices are content-supported; that `NotAssessed` decomposition is
harmless; that exact source support implies event-localization precision; that a declared
composition's order is story-world order; or real-data parity. An optional development-only
adapter replay against the frozen NN03 baseline would be a demonstration, not a gate. A synthetic
pass supports no efficacy claim.
