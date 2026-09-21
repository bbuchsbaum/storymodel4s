# G1 mapping records: implementation plan (revision 2)

Mote `bd-01M2WVENSB0P955Y0B603CC20Y`. **Plan only.** Nothing named below exists yet. Type, file
and suite names are acceptance specifications, not implemented or executed code. Governing
documents: [PLAN](../refactor/PLAN.md) §§2–3, [ADR 0019](../adr/0019-mapping-measurement-policy.md),
and the [analysis contract](../refactor/ANALYSIS_CONTRACT.md) ("the contract"). Code references
are `path:line` at main `f49bec1b`. The support-honesty references are at `solo/support-honesty`
`b8d02822`.

**Revision 2 (2026-09-21).** Folds the cold review of revision 1 (`4102b049`), stored at
[cold-review-1.json](../refactor/evidence/g1-mapping-records-plan-20260921/cold-review-1.json):
3 blocking, 8 major and 8 minor findings; §12 maps each to its disposition. Changes:
authority-bearing values decode only by contextual re-derivation or are refused as reserved, a
`derived` stage is refused, `Unknown` stages round-trip exactly and the stage-ledger expectation
is gone (B1, B2, M1); `SourceSupportStatus` (`Located` | `Unlocated`) with explicit per-target
input and `support_status` (B3); a stated composed-bundle binding rule with a Sherlock-shaped
core-API fixture (M3); the "only door" claim withdrawn in favour of ledger authority (M4); links
gated-derived or explicitly ungated (M5); a one-way gap-fill rule with candidates from a declared
decision basis (M6); several ID'd receipts per stage with provider identity, referenced by links
and measures (M7); ADR 0019 and contract amendments in the landing slice (M8); all minors folded.

**Revision 2 rulings (coordinator, 2026-09-21), applied.**
- **Term support is a typed status, not `Option`:** `TermSupportStatus = Evaluated(SupportAssessment)
  | NotComputed(reason)`, a required tagged value on the wire.
- **Refusal moves to construction:** `MappingResult.checked` refuses reserved values, so
  `MappingCodecs.encode` is total (`String`).

## 1. Goal and non-goals

**Goal.** One checked, versioned `MappingResult` for one recall against one checked source
representation, owned by existing modules, with a 0.x JSON codec. It accounts for every requested
unit and word, every target and its physical-support status, alternatives, missingness, external
outcomes, processing failures and decomposition status. It keeps the five measure kinds, raw
argmax, decoded choice, calibration and fidelity distinct. It carries the policy and stage
provenance that later compatibility checks read. Historical artifacts are fixed at unknown
provenance.

**Non-goals, each owned elsewhere:**

| Excluded work | Owner |
|---|---|
| Extracting raw local scores/costs and candidate/render/scoring receipts | `bd-01M2TACM78289S4TECE91GT5K2` |
| Producing `NormalizedScoreMass` (local normalization); reference inference; opaque target renaming | `bd-01M2WVF86T8QEEA1TK8Z0ASJHW` |
| Compatibility outcomes; restoring `Derived` stage provenance on decode | `bd-01M2WVFV2WGF5BVQHX7JTYYWRH` |
| Word-ID stability across resegmentation; timing; per-axis interval unions, coverage and hull; time grid | `bd-01M2WVH1DC8ZPDC992G4MX3TXG` |
| TSV exchange tables, file hashes, readiness/publication, Python/R reader | `bd-01M2WVHF4B5DAXJYY4W91VK4WV`, `bd-01M2TADC4VKSDZ2S9SXETH2MYM` |
| Moving `MonotoneScene`/fill into the library; recording fill origin at the decoder | `bd-01M2TAD04SR823TQVG9VPNH6R3` |

**G1/G2 split.** G1 fixes word identity under one segmentation, unit membership and the binding
of unit identity to that unitization; G2 AC1 owns word-ID stability across resegmentation and
timing. G1's `supportCoverage` is target-level child completeness only; G2 AC3 owns per-axis
interval unions, their coverage and the display hull.

**Also out of scope:** inference numerics; StoryModel/HSMM schemas; embed-bench outputs (TSV,
sidecars, voyage stay byte-identical with their known conflations); a decomposition detector;
calibration artifacts; new modules. The record *cannot express* those conflations; it does not
repair legacy writers.

## 2. Decisions

| # | Decision | Basis |
|---|---|---|
| D1 | **Support honesty lands first** on `solo/support-honesty`. It ships `SupportAssessment` = `Assessed` \| `Unestablished(EmptyEligibility \| ZeroEligibleWeight)` \| `NotApplicable(ExternalState \| Unreachable)` behind a sealed `CostBreakdown.support` (branch `cost.scala:159-176,273,538`), plus hsmm/v4. hsmm/v4 carries no `TypedSupport` and keeps refusing non-text support, so this schema is the only wire for physical support and ticks. Every link carries a `TermSupportStatus`: `Evaluated(CostBreakdown.support)` or `NotComputed(NoCostBreakdown)`. There is never a silent absence, an `Option`, or a `supportWeight: Double`. | Owner ruling 2026-09-21 on the support ticket. Main still returns 1.0 support on an empty eligible set (`align/.../cost.scala:842-851`). The coordinator adds the Mote dependency edge. |
| D2 | **PLAN §3 bundle representation.** A checked inventory of part bundles, with per-target, per-axis membership. D1B's composed bundle enters only as a *declared composition* (§3 AC4 binding rule). Cross-part order comes only from that declaration, never from part IDs; without it, adjacencies are `Incomparable`. `StoryModel` stays one bundle under D1A. | PLAN.md:149-154; ANALYSIS_CONTRACT.md:37-40; `corpus-intake/.../SherlockSourceAtlas.scala:31-181`. |
| D3 | **Decomposition:** every unit carries `NotAssessed(NoDecompositionDetector)` in 0.x. This is disclosed in the record and readouts and does not by itself block compatibility. No detector is built. | Coordinator ruling; ANALYSIS_CONTRACT.md:13. |
| D4 | **Three supports, three names in new fields:** `sourceSupport` / `support_status` + `source_support` (physical); `termSupport` / `term_support` (`TermSupportStatus` over `SupportAssessment`); `supportCoverage` / `support_coverage` (group completeness). No existing type is renamed, including core's physical `EvidenceSupport` (`core/.../source.scala:1324`). | Coordinator ruling. |
| D5 | **The contract's vocabulary wins.** Deviations are listed in §2.1 and recorded in the landing contract amendment (D8). | ANALYSIS_CONTRACT.md:3. |
| D6 | **One slice at a time; no new module.** `recall` owns units and words, `align` owns records, measures and provenance, `codec` owns the wire. The schema is separate from StoryModel and HSMM schemas. | PLAN.md:95-101. |
| D7 | **Authority-bearing sum types are sealed traits with bare-private cases, never enum cases** (enum cases get public `apply`, `copy` and `Mirror`). This covers `GateOutcome`, `FidelityStatus`, `StageProvenance`, `DecodedTargetMass`, `DecisionCalibration`, `SourceSupportStatus` and `SupportCoverage`. | Support-honesty precedent; `CostBreakdown` `fromProduct` forgery acknowledged at `codec/.../align.scala:52-57`. |
| D8 | **The landing slice amends ADR 0019 and the contract.** It records the new public vocabulary, D3/D7, the deviations in §2.1 and the rejected alternatives in §2.2. | AGENTS.md:199-204 (SD5); ADR 0019:26-27 leaves Scala construction to this ticket. |

### 2.1 Deviations from the contract (to be recorded in the contract amendment)

| Topic | 0.1 resolution | Why |
|---|---|---|
| Artifact name | `MappingResult` (the contract's `RecallMapping`); `MappingRun` envelope deferred to the facade ticket | Matches the existing `HsmmResult` naming; the run envelope is publication work. |
| Link rows | Typed Scala measure classes; the wire flattens them to the contract's `mapping_links` rows tagged `measure_kind` | AC2 compile separation (ANALYSIS_CONTRACT.md:84-90). |
| Reserved values | `partial`, `ambiguous`, `manual-review`, `measurement_compatibility` and non-`evidence-support` `support_relation` values have **no Scala case**, so they are refused by type. `Calibrated` decisions and `Derived` stages exist as vocabulary for dependent tickets, but `MappingResult.checked` refuses them in 0.1. The decoder refuses all of these on the wire. Every constructible record is therefore encodable, and `encode` is total. | A value with no producer is a forgery door; refusals belong in types, not in a fallible encoder (coordinator ruling). |
| Gap-fill mass | One-way rule: outside candidate support ⇒ exact zero with `decision_in_candidate_support=false`. An inside-support zero is legal. | ANALYSIS_CONTRACT.md:98 requires only the outside direction. |
| Fidelity | `fidelity_status` / `fidelity_facets` come only from `FidelityReport`. The new `gate_outcome` field holds the ModeGate result. The unit-level `fidelity_assessment_status` equals the chosen link's status. | ANALYSIS_CONTRACT.md:118. |
| Content-term support | A required, tagged `term_support` replaces the undefined `assessment_support_id`: `{"status":"evaluated","assessment":<hsmm/v4 SupportAssessment>}` or `{"status":"not-computed","reason":"no-cost-breakdown"}`. A missing key refuses. | D1, D4; coordinator ruling that absence must carry a reason. |
| Target identity | `target_id` = `SourceNodeRef.key` under policy `target-id/source-node-ref/v1` | D4; opaque IDs arrive with the reference ticket. |
| Word fields | ID, index, UTF-16 span and membership; no timing columns | G1/G2 split. |
| Manifest | Semantic identity, bundles/axes/coordinate_mappings (§4) and §8 policy fields; no `file_hashes`, `readiness` or `MappingRun` | Exports and facade tickets. |

### 2.2 Rejected alternatives (for the ADR amendment)

Rejected: reusing the HSMM wire (hsmm/v4 refuses non-text support, D1); the composed bundle as
*the* representation (PLAN §3); decoding `Derived` from embedded or caller-supplied receipts
(self-certification, ANALYSIS_CONTRACT.md:164-170); enum cases for authority-bearing types (D7);
a threshold-defined `Ambiguous` (the cutoff would itself be the claim); a numeric term-support
placeholder (D1); `Option` term support (it reads as either "not computed" or "omitted");
a fallible encoder (reserved values are refused at construction instead); mass == 0 as the gap-fill test (`VoyageExport.scala:116`), because an
in-support zero is legitimate, so membership in the decision basis's keys is used instead.

## 3. Design by acceptance criterion

### AC1: one checked result accounts for everything

**recall:** new `recall/src/main/scala/storymodel4s/recall/inventory.scala`.

```scala
object RecallWordId extends OpaqueId("RecallWordId")
final class WordIdPolicy private (val name: String, val artifact: Checksum) // G0: "input-artifact-sha256+zero-based-parsed-word-index/v1"
final class RecallWord private (val id: RecallWordId, val index: Int, val span: TextSpan) // no public factory: IDs are derived
sealed trait WordMembership      // Member(unit) | Unassigned(NotInAnyUnitSpan)
final class SegmentationId private (val digest: Checksum) // transcript checksum + ordered (unit id, span set)
sealed trait DecompositionStatus // 0.1: NotAssessed(NoDecompositionDetector) only
enum MappingSemantics { case CategoricalReferent }
final class InventoryUnit private (val id: RecallUnitId, val ordinal: Int, val span: SpanSet,
    val words: Vector[RecallWordId], val decomposition: DecompositionStatus, val semantics: MappingSemantics)
final class RecallInventory private (val transcript: Checksum, val segmentation: SegmentationId, val idPolicy: WordIdPolicy,
    val words: Vector[RecallWord], val units: Vector[InventoryUnit], val membership: Map[RecallWordId, WordMembership]):
  def digest: Checksum
object RecallInventory:
  def of(graph: RecallGraph[Checked], wordSpans: Vector[TextSpan], policy: WordIdPolicy): Either[DomainError, RecallInventory]
```

Word IDs are derived by the policy (`embed-bench/.../SherlockBaselineCapture.scala:84,102`).
Membership uses span overlap (`SherlockBaselineCapture.scala:120-121`): a word in two units
refuses; a word in no unit is `Unassigned` (the 94 G0 separators). `SegmentationId` binds unit
identity to the unitization, because segmenter unit IDs are positional (`recall/.../segmenter.scala:435`).

**align:** new `align/src/main/scala/storymodel4s/align/mapping.scala`.

```scala
enum Destination { case Target(ref: SourceNodeRef); case External(state: ExternalState) }
sealed trait ProcessingStatus // Complete | Failed(ProcessingFailure) | ExcludedByInputPolicy(reason)
enum ProcessingFailure { case ProviderFailure(d: String); case InvalidOutput(d: String); case InferenceRefused(d: String) }
enum LocalizationStatus { case Located, Nonlocalizable, Unranked, NotComputed }
final class UnitOutcome private (val unit: RecallUnitId, val processing: ProcessingStatus, val localization: LocalizationStatus,
    val links: Vector[MappingLink], val measures: UnitMeasures, val decision: Option[UnitDecision])
object UnitOutcome:
  def computed(unit: RecallUnitId, measures: UnitMeasures, links: Vector[MappingLink], basis: DecisionBasis,
      request: DecisionRequest, stages: UnitStageRefs): Either[MappingRefusal, UnitOutcome]
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
duplicate or extra unit; failed and excluded units retained). It refuses destinations outside the
policy's declared universe (AC3), stage or candidate-set IDs absent from the ledger, duplicate
destinations within a unit, measures keyed by unknown destinations, and the 0.1 reserved values
that have Scala cases: any `DecisionCalibration.Calibrated` and any `StageProvenance.Derived`.
This makes every constructible `MappingResult` encodable. No dependent ticket needs an encodable
record carrying them before the schema version that introduces their producers, so no case keeps
a fallible encoder: the evidence and reference tickets (first `Derived` producers) and calibration
work each bump the schema and relax `checked` together.

**Missingness is always explicit:** `Failed`/`Excluded` outcomes, `Unranked`, `Unassigned` words,
`Unlocated` targets and `SupportCoverage`. No unit may vanish through a defaulted lookup:
`Candidates.set` defaults a missing unit to `unranked` (`align/.../candidates.scala:80`), so
records never read candidates through it.

**Fixture `p7`** (e3, e8 tied) is `Located`. Both alternatives are kept as links and asserted. The
argmax tie is broken by key. The `ambiguous` label stays reserved until a declared producer exists.

### AC2: five measure kinds, gate and fidelity stay distinct

New `align/src/main/scala/storymodel4s/align/mappingmeasures.scala`:

```scala
enum MeasureKind { case RawScore, NormalizedScoreMass, TransportMass, ModelPosterior, CalibratedProbability }
enum ScoreDirection { case HigherIsBetter, LowerIsBetter }
final class RawScores private (val channel: String, val direction: ScoreDirection, val scale: String,
    val values: Map[Destination, Double], val stage: StageReceiptId)
final class NormalizedScoreMass private (val universe: TargetUniverseId, val prior: ReferencePriorId, val temperature: Double,
    val mass: Map[Destination, Double], val stage: StageReceiptId)   // Σ = 1 within MappingTolerance.RowSum = 1e-9
final class TransportMass private (val rowBudget: Double, val mass: Map[Destination, Double], val stage: StageReceiptId) // no Σ = 1
final class ModelPosterior private (val mass: Map[AlignState, Double], val stage: StageReceiptId)                      // Σ = 1 incl. externals
object ModelPosterior { def of(result: HsmmResult, unit: RecallUnitId, stage: StageReceiptId): Option[ModelPosterior] }
sealed trait CalibratedEvent // ChosenDecisionCorrect(policy: DecisionPolicyId); no G1 producer
final class CalibratedProbability private (val event: CalibratedEvent, val p: Probability, val artifact: CalibrationArtifactId, val domain: String)
  // public checked factory (vocabulary only); a record holding one is refused by `checked` in 0.1
sealed trait TermSupportStatus   // Evaluated(assessment: SupportAssessment) [fromResult only] | NotComputed(reason)
enum TermSupportNotComputed { case NoCostBreakdown }
final class UnitMeasures private (val raw: Vector[RawScores], val normalized: Option[NormalizedScoreMass],
    val transport: Option[TransportMass], val posterior: Option[ModelPosterior])
sealed trait GateOutcome     // NotGated [public] | NoContradictionDetected, Contradicted(facets) [derived only]
sealed trait FidelityStatus  // NotAssessed(reason), NotApplicable [public] | Assessed(report) [derived only]
final class MappingLink private (val destination: Destination, val gate: GateOutcome, val fidelity: FidelityStatus,
    val termSupport: TermSupportStatus, val inferenceStage: StageReceiptId, val candidateSet: CandidateSetId)
object MappingLink:
  def ungated(destination: Destination, stages: UnitStageRefs): MappingLink // NotGated, NotAssessed, NotComputed(NoCostBreakdown)
  def fromResult(result: HsmmResult, recall: RecallGraph[Checked], view: SourceView, unit: RecallUnitId,
      state: AlignState, stages: UnitStageRefs): Either[MappingRefusal, MappingLink]
```

**Measures.** No shared numeric supertype: a `TransportMass` never stands where a `ModelPosterior`
is required (today both are `AlignmentMatrix`, `align/.../sinkhorn.scala:193-228`, `matrix.scala:112`).
`ModelPosterior` guarantees shape and gate admission only. Revision 1's "only door" claim is
withdrawn: the public `HsmmResult.validated` (`hsmm.scala:373-384`) accepts a caller-built
posterior and does not prove inference ran. Inference authority lives in the ledger's `Inference`
stage, which no G1 path sets to `Derived`. Public raw/normalized/transport factories check shape
only. Every G1 decision carries `DecisionCalibration.Unavailable(NoCalibrationArtifact)`.

**Gate, fidelity and term support.** `MappingLink.ungated` fixes `NotGated`,
`NotAssessed(NoGateEvaluation)` and `NotComputed(NoCostBreakdown)`; the miniature's authored-score
links are of this kind.
`MappingLink.fromResult` derives the gate from `result.admissibility` (`hsmm.scala:299`) for the
link's own unit and anchor, and derives fidelity with `FidelityFacets.assess(unit.proposition,
node, mode)` (`facets.scala:79-99`) only for `PropositionalScope.Declared` nodes
(`align/.../source.scala:102-115`), otherwise `NotAssessed(ScopeUndeclared)`. `termSupport` is
`Evaluated(result.costs(unit)(state).support)`, always present on historical links; externals get `NotGated` and `NotApplicable`. Video
targets are `Undeclared` (`RecallToVideo.scala:175,200`), so they are never `Assessed`, although
`AlignState.Source` *is* the Faithful mode (`matrix.scala:18,46`). No caller can supply a gate or
fidelity verdict, or another unit's report.

**Decisions** (in `mapping.scala`):

```scala
final class DecisionBasis private (val kind: MeasureKind, val channel: Option[String]) // names one measure in UnitMeasures
sealed trait DecisionRequest     // RawArgmax | ExternalDecode(chosen: Destination, policy: DecisionPolicyId)
enum DecisionOrigin { case RawArgmax; case StructuredDecode(p: DecisionPolicyId); case GapFill(p: DecisionPolicyId); case Abstention(reason: String) }
sealed trait DecodedTargetMass   // InCandidateSupport(value: Double) | OutsideCandidateSupport
sealed trait DecisionCalibration // Unavailable(reason) | Calibrated(p) [constructible; `checked` refuses it in 0.1]
final class UnitDecision private (val basis: DecisionBasis, val chosen: Option[Destination], val origin: DecisionOrigin,
    val rawArgmax: Option[(Destination, Double)], val decodedMass: DecodedTargetMass, val calibration: DecisionCalibration)
```

"Candidate support" means the anchors of the decision-basis measure's keys: not physical support,
not term support, and no separate caller list. `rawArgmax`, `decodedMass` and `origin` are
derived, never supplied. The argmax covers the full state space including externals (ties by key,
`matrix.scala:179-182`; RawScores direction respected). `ExternalDecode` becomes `StructuredDecode`
inside candidate support and `GapFill` outside it; a decode that agrees with the argmax stays
`StructuredDecode`. Outside support the wire mass is exactly 0; inside support any value, 0
included, is legal. The historical `DecodeFilled` rule (mass == 0, `VoyageExport.scala:113-118`)
agrees except on inside-support zeros. The legacy writers keep their conflations (decoded anchor
beside the argmax's mass and mode, `RecallToVideo.scala:659,682,685`; 0.0 fill mass,
`PosteriorSidecar.scala:65`; `constrained=true` for bound and filled choices,
`MonotoneScene.scala:216-223`); this record simply cannot express them.

### AC3: policies, universe, receipts and provenance

New `align/src/main/scala/storymodel4s/align/mappingprovenance.scala`:

```scala
enum Stage { case Candidates, Rendering, Scoring, Context, Refinement, Inference, Decision, Projection }
object StageReceiptId extends OpaqueId("StageReceiptId") // derived digest of the receipt fields
final class StageReceipt private (val id: StageReceiptId, val stage: Stage, val policy: String, val config: Checksum,
    val inputs: Vector[Checksum], val provider: Option[ProviderIdentity])  // provider/model identity (manifest provider_and_model_identities)
final class ProviderIdentity private (val provider: String, val model: Option[String], val artifact: Option[Checksum])
sealed trait StageProvenance // Derived(receipt) [constructor private[align]; no G1 producer; `checked` refuses it in 0.1] | Unknown(reason, asserted: Option[StageReceipt])
enum UnknownProvenanceReason { case HistoricalArtifact, NominationProvenanceUnproven, CallerSuppliedDecision, NotRun }
final class StageLedger private (val entries: Map[Stage, NonEmptyVector[StageEntry]]) // total over Stage.values; several per stage
final class StageEntry private (val id: StageReceiptId, val provenance: StageProvenance)
final class UnitStageRefs private (val inference: StageReceiptId, val candidates: CandidateSetId, val decision: Option[StageReceiptId])
object CandidateSetId extends OpaqueId("CandidateSetId") // digest(candidates-stage entry id, unit, basis keys)
sealed trait CandidatePolicy // Declared(id: CandidatePolicyId, coverage: CandidateCoverage) | Unknown(reason)
sealed trait CandidateCoverage // Truncated(perLevel) | Complete | Unknown(reason)
sealed trait ContextPolicy   // 0.1: Unspecified(reason) only; the contextual profile is later work
sealed trait InferencePolicy // 0.1: HistoricalReconstruction(label) | Unspecified; later tickets add cases
final class DeclaredUniverse private (val targets: Vector[SourceNodeRef], val grain: TargetGrain) // subset of the dictionary
final class MappingPolicies private (val inference: InferencePolicy, val context: ContextPolicy, val candidate: CandidatePolicy,
    val referencePrior: Option[ReferencePriorId], val decision: Option[DecisionPolicyId], val universe: DeclaredUniverse)
enum AnalysisGrain { case InferenceUnit(s: SegmentationId); case Word; case Targets(g: TargetGrain) }
final class UnitRoles private (val inference: AnalysisGrain, val organization: AnalysisGrain, val projection: AnalysisGrain)
```

**Policy fields** use contract §8 names (ANALYSIS_CONTRACT.md:164-170). Links carry
`inference_stage_id` and `candidate_set_id` (ANALYSIS_CONTRACT.md:88). Measures carry their stage
receipt ID.

No G1 path produces `Derived`: the historical adapter gives `Unknown(HistoricalArtifact)`,
caller-supplied decisions `Unknown(CallerSuppliedDecision)` and stages that did not run
`Unknown(NotRun)`. Restoring `Derived` on decode belongs to the compatibility ticket.

**Universe.** The policy's `DeclaredUniverse` must be a subset of the representation's target
dictionary; its grain is declared, not inferred. A level-0 reference run can therefore declare
`SingleLevel(0)` while the historical Sherlock record declares `Hierarchy(0,1)`.
`measurement_compatibility` is reserved (§2.1).

### AC4: multipart sources and exact coordinates

New `align/src/main/scala/storymodel4s/align/mappingsource.scala`:

```scala
sealed trait BundleEntry         // Media(bundle: SourceBundle) | TextSource(canonicalText: Checksum)
final class DeclaredComposition private (val composed: SourceBundle) // TrackCompositions read from composed.mappings
object DeclaredComposition { def of(composed: SourceBundle, parts: NonEmptyVector[SourceBundle]): Either[MappingRefusal, DeclaredComposition] }
sealed trait SourceSupportStatus // Located(support: TypedSupport) | Unlocated(reason: UnlocatedReason)
enum UnlocatedReason { case NoLocusInSource }
enum SupportRelation { case EvidenceSupport } // other contract values reserved (G2 owns hull/extent)
final class MappingTarget private (val ref: SourceNodeRef, val level: Int, val parent: Option[SourceNodeRef],
    val sourceSupport: SourceSupportStatus, val axisMembership: Map[PresentationAxisId, BundleRole],
    val supportCoverage: SupportCoverage, val propositional: PropositionalScope)
enum BundleRole { case Part(bundle: SourceBundleId); case Composition }
sealed trait SupportCoverage     // Complete | Partial(missing: NonEmptySet[SourceNodeRef]) | Unknown(reason)
enum TargetGrain { case SingleLevel(level: Int); case Hierarchy(levels: Vector[Int]) }
enum PartOrder { case Before, Same, After, Incomparable }
final class SourceRepresentation private (val bundles: NonEmptyVector[BundleEntry], val composition: Option[DeclaredComposition],
    val targets: Vector[MappingTarget]):  // stored sorted by ref key
  def digest: Checksum                  // canonical over ref-key order, independent of view storage order
  def order(a: PresentationAxisId, b: PresentationAxisId): PartOrder // composition segments only
object SourceRepresentation:
  def of(view: SourceView, bundles: NonEmptyVector[BundleEntry], composition: Option[DeclaredComposition],
      physical: Map[SourceNodeRef, SourceSupportStatus]): Either[MappingRefusal, SourceRepresentation]
```

**Physical support is an explicit per-target input**, total over the view's nodes; a missing entry
refuses. `NodeSummary.support` is not assumed physical: in the `RecallToVideo` path it is
annotation-document text (`RecallToVideo.scala:165,192`), and only the Sherlock view swaps in
anchored support (`SherlockRecallMapping.scala:315-317`). Fixture `e4` is `Unlocated(NoLocusInSource)`.
`g1` is then `Partial({e4})`. `Complete` needs every declared child `Located`; a supported leaf is
`Complete`; all other cases are `Unknown`.

**Binding rule (M3).** A target's `support.bundleIdentity` must be a listed part bundle's identity
or `composition.composed.identity`. An anchor axis equal to a listed part's `primaryAxis.id`
resolves to `Part(that bundle)` whatever `anchor.bundle` says (Sherlock rows name the composed
bundle on both anchors, `SherlockSourceAtlas.scala:130-158`); the composed primary axis resolves
to `Composition`; any other axis refuses as foreign, and an axis matched twice refuses as
ambiguous. `DeclaredComposition.of` requires the composed bundle's `TrackComposition` mappings
(`core/.../source.scala:985,1570-1600`) to have source axes exactly equal to the listed parts'
primary axes, each covered once, all targeting the composed primary axis. `order` reads
composition segment targets only. Decode rebuilds support with `EvidenceSupport.of(bound bundle,
anchors)` (`core/.../source.scala:1422`) against the bundle this rule names.

**Coordinates** stay exact in `TypedSupport`. Today the codec writes decimal ticks
(`codec/.../core.scala:110-116,142`) but cannot decode anchored support (`core.scala:170-177`).

### AC5: refusal, forgery, round-trips and falsification

Probes are in §7 and courts in §8. The two named falsifications:
- setting the decoded value to the raw-argmax value fails `MappingOutcomeSuite`;
- dropping the `p6` failure row fails `MappingResultSuite` and `MappingCodecSuite`.

## 4. Codec design

New `codec/src/main/scala/storymodel4s/codec/mapping.scala`, object `MappingCodecs`.

- **Schema.** `"schema": "storymodel4s.mapping-record"`, `"schemaVersion": "mapping-record/v0.1"`.
  Version is checked before the body is decoded; any other version is refused with
  `CodecError.UnsupportedSchema`. There is no migration. The schema is independent of hsmm/v4 and
  StoryModel.
- **Canonical form.** Canonical printing (`codec/.../canonical.scala:22-37`), Doubles as exact
  IEEE-754 hex (`canonical.scala:79`), and a canonical-text guard as in `VoyageCodecs.decode`
  (`codec/.../voyage.scala:40`).
- **Exact integers.** Ticks, rational numerators and denominators, extents and `Long` identifiers
  are JSON strings of base-10 digits. A JSON number there is refused by a named tick decoder with
  its own error, before the canonical guard. A hand-authored golden text with
  `"startTick":"9007199254740993"` (2^53+1) and an equal timebase denominator must round-trip
  byte-for-byte on JVM, JS and Native.
- **Manifest sections** (m7): `bundles` (id, full identity, edition, source kind, role); `axes`
  (id, owning bundle, kind, extent start/end, timebase numerator/denominator);
  `coordinate_mappings` (each `TrackComposition`: source/target axis, segments, occurrence,
  receipt id); `targets` (ref, level, parent, `support_status`, `source_support` loci with
  `support_relation`, `support_coverage`, propositional scope); `inventory` (words, units,
  membership, `segmentation_id`, `decomposition_status`); `policies`; `roles`; `ledger`; `outcomes`.
- **Encode is total.** `encode(r: MappingResult): String`. `checked` refuses every reserved value
  that has a Scala case, and the rest have none, so every constructible record is encodable and
  re-decodes under full context. This matches the repo precedent that refusals are by type.

**Contextual decoding.** `decode(text, context: ExpectedMappingContext)`. The context carries the
expected `RecallInventory`, the expected `SourceRepresentation`, and an optional
`DerivationContext(result: Option[HsmmResult], recall: RecallGraph[Checked], view: SourceView)`.
There is no stage-ledger expectation. The wire's `inventoryDigest` and `sourceDigest` must equal
the context's, or decode refuses with `DigestMismatch`. Loci are re-bound by the §3 AC4 rule and
must equal the context's.

**Authority-bearing values:**

| Value | 0.1 decode rule |
|---|---|
| `ModelPosterior` | Only when `result` is present and `ModelPosterior.of(result, unit, ·)` is bit-equal to the wire rows; otherwise refused. |
| Gated `GateOutcome`, `FidelityStatus.Assessed` | Only when `recall` and `view` are present and `MappingLink.fromResult` re-derives the same value; otherwise refused. |
| `term_support` `evaluated` | Only with `result` in context; the assessment, decoded by support honesty's checked `SupportAssessment` decoder, must equal `result.costs(unit)(state).support` bit-for-bit. Otherwise refused. |
| `term_support` `not-computed` | Only with reason `no-cost-breakdown`, and only on an ungated link. A missing `term_support` key refuses; it is never read as `NotComputed`. |
| `derived` stage, `calibrated`, reserved statuses, `measurement_compatibility` | Refused as reserved. |
| `Unknown(reason, asserted)` stage | Decoded exactly as written, so a record round-trips to the same canonical text. |

`HsmmResultCodec` needs its recall and view in the same way (`codec/.../align.scala:30-36`).
Non-Scala readers use the self-describing loci (G2).

## 5. Historical HsmmResult adapter

New `align/src/main/scala/storymodel4s/align/mappinghistory.scala`:

```scala
object HistoricalMapping:
  def of(result: HsmmResult, recall: RecallGraph[Checked], view: SourceView, inventory: RecallInventory,
      source: SourceRepresentation, decode: HistoricalDecode): Either[MappingRefusal, MappingResult]
enum HistoricalDecode { case ArgmaxOnly; case Decoded(chosen: Map[RecallUnitId, Option[SourceNodeRef]], label: String) }
```

The adapter refuses unless `result.recallChecksum == AlignWire.recallChecksum(recall)`
(`align/.../wire.scala:256`), `result.viewFingerprint == ViewFingerprint.of(view)` (`wire.scala:77`),
the inventory was built from the same `recall`, and `Decoded` covers exactly the recall's units.
The ledger holds one `Unknown(HistoricalArtifact)` entry per stage; the policy is
`HistoricalReconstruction(label)` with `CandidatePolicy.Unknown` and a declared universe.
`ModelPosterior.of` supplies bit-identical masses; `RawScores` come from `CostBreakdown.total`
(channel `local-cost`, `LowerIsBetter`); links come from `MappingLink.fromResult`, each carrying `Evaluated` term support
(externals `Evaluated(NotApplicable(ExternalState))`); a posterior state with no breakdown refuses (support is never
defaulted). The decision basis is `ModelPosterior`; `Decoded` yields `StructuredDecode` or `GapFill`
from the basis keys; no anchor is `Abstention`; calibration is `Unavailable`. Every unit is
`Complete` because `HsmmResult.validated` guarantees rows equal the recall's units, so the adapter
invents no failures. No embed-bench file changes; moving `MonotoneScene` belongs to
`bd-01M2TAD04SR823TQVG9VPNH6R3`.

## 6. Fixtures

**Independent miniature.** `tools/recall-study/fixtures/baseline-miniatures.json`: 4,089 bytes,
sha256 `be3f8c3d8b4595cd4e58ba0ef8135f26b702cc9c470f4d0a0b2ed8f17d742fc3` at `f49bec1b`, admitted
by a fresh-context reviewer. It contains two parts at 10 ticks/s with the instant `e8` and the
unlocated `e4`; partial group `g1` = {`e2`, `e4`}; `p5` external; `p6` failure control; argmax `e3`
versus decoded `e7` on `p3`; `p7` tied between `e3` and `e8`; `p8` a revisit.

**Transcription and builder.** Slice 1 adds the plain-data transcription in
`laws/src/main/scala/storymodel4s/laws/MappingMiniature.scala` (ticks as strings). Slice 5 adds the
public-factory builder `MappingMiniature.record` to the same laws **main** file, so codec tests can
use it (`build.sbt:412`; laws depends on align and recall, `build.sbt:476-480`). Authored numbers
are `RawScores` with channel `miniature-authored`, never posteriors, and their links are ungated
with `NotComputed(NoCostBreakdown)` term support. The fixture declares no
composition, so e2→e7 is `Incomparable`, and `Before` only under a composition the test declares.

**JVM digest court.** `codec/.jvm/src/test/scala/storymodel4s/codec/MappingMiniatureDigestSuite.scala`
follows the precedent of `fixtures/.jvm/src/test/.../WarOfTheGhostsCodecGoldenResourceSuite.scala`.
It walks up from the working directory to `build.sbt`, checks the pinned sha256, and then compares
the parsed JSON to the transcription field by field.

**Sherlock-shaped fixture (M3).** `align/src/test/scala/storymodel4s/align/SherlockShapedSource.scala`
is built from core APIs only, because corpus-intake cannot host it (it depends only on `corpus.jvm`
and `core.jvm`, `build.sbt:451`) and embed-bench is out of bounds. It has two part bundles; a
composed bundle from `SourceBundle.editionPlaybackAxis` (`core/.../source.scala:1098`),
`TrackComposition.of` and `SourceBundle.of`; rows whose `EvidenceSupport` binds the composed
bundle with native plus composed anchors; and a scene spanning both parts.

**Historical record.** Slice 6 builds a tiny `GraphHsmm` run from existing align test fixtures. A
posterior authored through `HsmmResult.validated`, not inferred, serves as the M4 witness.

## 7. Probe and external-consumer suites

**Placement.** Probes sit outside the owning package: `align/src/test/scala/storymodel4s/probes/MappingUnforgeableSuite.scala`,
`recall/src/test/scala/storymodel4s/probes/RecallInventoryUnforgeableSuite.scala` and
`codec/src/test/scala/storymodel4s/codecprobe/MappingCodecProbeSuite.scala`; qualified-private
widening is caught from `storymodel4s.align.attack` and `storymodel4s.recall.attack`.

**Refusals.** For each sealed type: `new`, `apply`, `copy`, `fromProduct`, `Mirror.ProductOf`, and
enum-case construction of authority cases. Specifically: no `RecallWord` from a caller-chosen ID;
no gated `GateOutcome`, `FidelityStatus.Assessed` or `TermSupportStatus.Evaluated` outside align; no `ModelPosterior` from an
`AlignmentMatrix` (a narrow probe; the indirect door is §3's stated residual); no
`StageProvenance.Derived`; no `DecisionCalibration.Calibrated` from a `Double`; no `TransportMass`
where a `ModelPosterior` is required, including via a type alias.

**Controls** follow `align/src/test/scala/storymodel4s/probes/CellCoordinatesUnforgeableSuite.scala`:
positive controls first, and `classOf` dependencies (precedent:
`codec/src/test/.../codecprobe/ConstructionProbeSuite.scala`).

**Aliasing** means mutable arrays (ADR 0018:102; `corpus/src/test/.../VerifySuite.scala:60-183`).
The court mutates every accessor result and then compares re-read values against an independent
snapshot, plus a fresh canonical re-encoding in codec tests. It never compares against a cached
`digest`.

**External-consumer construction.** `laws/src/test/scala/storymodel4s/laws/MappingContractSuite.scala`
runs outside align on all three platforms and uses `MappingMiniature.record`. It asserts unit, word
and target accounting; `p6` `Failed`; `p5` `Nonlocalizable`; the `p3` disagreement; `p7`'s two
retained alternatives; `g1` `Partial`; `e4` `Unlocated`; every unit `NotAssessed`; and
foreign-axis, ambiguous-axis, duplicate-target and missing-unit refusals.

## 8. Slices

**Slice 0 (precondition, not G1 work).** Support honesty lands on main. Record the base SHA and
re-read `CostBreakdown.support` and the `SupportAssessment` wire API.

**Rules for the table:**
- Every mutation is a compiled production edit whose named rejecting test fails while the slice's
  accepting control passes.
- Every refusal test asserts the **specific** `MappingRefusal` or `CodecError` case, never
  `isLeft` (M2).
- Compile-door mutants need `Compile/clean` and `Test/clean` first
  (`CellCoordinatesUnforgeableSuite.scala:64-67`).
- Platforms are JVM, JS and Native unless stated.

| Slice | Changed paths | Falsifying mutations → rejecting test | Accepting control |
|---|---|---|---|
| 1 Miniature | `laws/.../MappingMiniature.scala` (data); `codec/.jvm/.../MappingMiniatureDigestSuite.scala` | Change `e7` endTick "30"→"31" → "transcription equals fixture". Drop `p6` → same test. (JVM court.) | Unchanged transcription equals parsed JSON. |
| 2 Inventory | `recall/.../inventory.scala`; `RecallInventorySuite`; probe + `attack` suites | Filter `Unassigned` → "every parsed word is accounted". First-unit-wins overlap → "a word in two units refuses". Digest from transcript only → "changed unitization changes SegmentationId". Public `RecallWord.of(id, …)` → probe. A unit built without decomposition status → "every unit is NotAssessed(NoDecompositionDetector)". `private[recall]` constructor → attack probe. | Inline eight-packet inventory with one `Unassigned` separator. |
| 3 Measures, gate, fidelity | `align/.../mappingmeasures.scala`; `MappingMeasuresSuite`; `MappingUnforgeableSuite` | Drop Σ=1 check → "unnormalized mass refuses". Public `ModelPosterior.of(AlignmentMatrix)` → probe. `type TransportMass = ModelPosterior` → probe. Fidelity from `AlignState.mode` → "undeclared scope yields NotAssessed on a Source state". `fromResult` using unit 0's sketch → "fidelity is assessed on the link's own unit". Public gated `GateOutcome` → probe. `Calibrated` from `Double` → probe. `ungated` maps `NotComputed` to `Evaluated(...)` → "authored-score links are NotComputed(NoCostBreakdown)". Public `TermSupportStatus.Evaluated` → probe. | Declared-scope node gives `Assessed` equal to `FidelityFacets.assess`; `fromResult` links carry `Evaluated` equal to the breakdown's support. |
| 4 Outcomes, decisions | `align/.../mapping.scala` (outcome/decision); `MappingOutcomeSuite` | `decodedMass := rawArgmax` → "p3 decoded keeps e7's value" (authored e3 .9, e7 .4). Outside as in-support 0.0 → "fill outside basis keys is OutsideCandidateSupport". Zero ⇒ outside → "in-support zero stays in support". Candidate support from link destinations → "listed fill target is still GapFill". Agreeing decode as `RawArgmax` → "agreeing decode stays StructuredDecode". `Unranked`→`Nonlocalizable` → "unranked stays unranked". Tie by insertion order → "p7 tie resolves by key and keeps both". | `RawArgmax` on the same row. |
| 5 Source, provenance, result | `align/.../mappingsource.scala`, `mappingprovenance.scala`, `mapping.scala` (`checked`); `SherlockShapedSource.scala`; `SourceRepresentationSuite`, `MappingResultSuite`; `MappingMiniature.record` in laws main | Remove foreign-axis refusal → "axis outside inventory refuses". First match on a doubly listed axis → "ambiguous axis refuses". Membership via `anchor.bundle` → "Sherlock-shaped rows resolve to part bundles". Skip composition coverage → "composition must cover exactly the listed parts". Part-ID order → "e2→e7 Incomparable without declaration". Remove duplicate-target refusal → "duplicate target ref refuses". Accept missing unit → "missing unit refuses". Filter failed before accounting → "p6 retained and counted". Fabricate `e4` support from parent → "g1 is Partial". Digest in view order → "permuted view storage, same digest". Skip universe subset check → "declared universe outside dictionary refuses". Skip ledger ID check → "unknown stage receipt id refuses". Default coverage `Complete` → "unknown coverage stays Unknown". Public `Derived` door → probe. `checked` accepts a `Calibrated` decision → "calibrated decision refuses in 0.1". `checked` accepts a `Derived` entry (built in-package) → "derived stage refuses in 0.1". | Two-part miniature and Sherlock-shaped source accepted; `Before` under the declared composition. |
| 6 Historical adapter | `align/.../mappinghistory.scala`; `MappingHistoricalSuite` | Raw argmax via `mapSource` → "Unranked row: argmax is external". Any stage `Derived` → "historical ledger all Unknown". `Inference` marked `Derived` when `logLikelihood` is finite → "validated-but-not-inferred posterior keeps Unknown inference" (M4). External `termSupport` as `Assessed` → "external links NotApplicable". Default support for a missing breakdown → "missing breakdown refuses". Skip checksum check (foreign recall sharing positional unit IDs) → "foreign recall refuses". | `ModelPosterior` bit-equal to `result.posterior`. |
| 7 Codec | `codec/.../mapping.scala`; `MappingCodecSuite`; `MappingCodecProbeSuite` | Ticks encoded as numbers → golden (2^53+1). Decoder accepts numeric ticks → "numeric tick refuses with NumericTick". Decoder demotes a `derived` wire stage to `Unknown` → "derived stage refuses with Reserved". Encoder omits `term_support` for `NotComputed` → "authored-score link encodes term_support not-computed" (golden). Decoder reads a missing `term_support` as `NotComputed` → "missing term_support refuses". Decoder accepts `evaluated` without `result` → "evaluated term support without result context refuses". Demote `Unknown` to another reason → "historical record round-trips to identical text". Posterior decoded without `result` → "posterior without result context refuses". Rounded masses → "historical round trip bit-equal". `Assessed` decoded without recall/view → "assessed fidelity without context refuses". Accept `ambiguous`/`calibrated` → "reserved value refuses". Remove version check → "v0.2 refuses with UnsupportedSchema". Skip digest check (foreign inventory sharing unit IDs and target refs, differing only in transcript) → "foreign inventory refuses with DigestMismatch". Drop failed outcomes on encode → "p6 round trip". Remove canonical guard → "unknown field refuses". | Miniature, Sherlock-shaped and Slice 6 historical records round-trip to identical canonical text with full context. |
| 8 Consumer, aliasing, landing docs | `laws/src/test/.../MappingContractSuite.scala`; aliasing court; ADR 0019 amendment; contract amendment; `codec/README.md`; `docs/api-stability.md` | Exposed `Array[Double]` → "accessor mutation leaves re-read values and re-encoding unchanged". Remove outcome-order check → "reordered outcomes refuse". | Full public-factory miniature build. |

**Rough size (estimate):** 1,600–2,000 main lines and 2,300–2,900 test lines. Slices 5 and 7 are
the largest.

## 9. Evidence and gates

The evidence directory is `docs/refactor/evidence/g1-mapping-records-<date>/`. It records:
- base and result SHA, and `bash tools/reference-scope.sh BASE HEAD` as a union over every commit;
- focused commands with bound totals per slice on `recall`, `align`, `codec` and `laws`, each on
  JVM, JS and Native;
- `mutations.json` and `guard-witness-inventory.json`: one row per §8 mutation, with source and
  mutant hashes, the failing test and specific error, the passing control, and clean-recompile
  flags;
- the final `sbt -batch checkAll` in a **clean standalone clone** with bound totals and named
  skips, and `scalafmtCheckAll` last;
- a separate fresh-context SD6 review of the implementation (`review.json`).

**Compatibility impact is additive.** No existing type, schema or wire changes. S0 values, WOG
HSMM golden bytes (hsmm/v4 after D1) and frozen Sherlock artifacts stay unchanged. Any numeric
movement is a defect.

**Landing documents (D8):** the ADR 0019 amendment (vocabulary, D3/D7, §2.2), the contract
amendment (§2.1), and `codec/README.md` plus `docs/api-stability.md` (`mapping-record/v0.1` as
experimental 0.x).

## 10. Risks

- **R1 Support API drift:** re-read at Slice 0 (names verified at `b8d02822`). **R2–R3 False probe
  passes:** enumerate every authority case (D7); clean-recompile rule for stale `typeChecks`.
- **R4 Composed-bundle binding:** mitigated by the binding rule and the Sherlock-shaped fixture;
  a real-data replay is optional (§11). **R5 Text sources:** `TypedSupport.Text` names no bundle;
  check `TextSource` against S4c's `TextAlignmentSource` before Slice 5.
- **R6 Missing breakdowns:** if validated results can hold posterior states without one, the
  adapter refuses; verify at Slice 6 and report, never default.
- **R7 Scope creep** (timing, unions, normalized-mass production, bench rewrites): refuse.
  **R8 Record size:** 1,050-target JSON is acceptable for 0.x. **R9 Concurrent edits:** G1 touches
  no embed-bench file.
- **R10 Decode demands full context:** HSMM-derived records need their result, recall and view to
  decode. This is the accepted cost of refusing self-certification.

## 11. What this will not establish

Localization accuracy, candidate recall or specificity; calibration (every record says
`Unavailable`); reference-measurement compatibility, or that inference ran at all (a posterior
validated through `HsmmResult.validated` shows shape and gate admission only); behavioral or
organizational recovery; that historical choices are content-supported; that `NotAssessed`
decomposition is harmless; that exact source support implies event-localization precision; that a
declared composition's order is story-world order; or real-data parity. An optional
development-only replay against the frozen NN03 baseline would be a demonstration, not a gate. A
synthetic pass supports no efficacy claim.

## 12. Disposition of cold-review findings (revision 1 → 2)

| ID | Disposition | Where |
|---|---|---|
| B1 | Adopted: contextual re-derivation for `ModelPosterior`, the gate and `Assessed`; reserved refusals; historical round-trip court | §4; Slice 7 |
| B2 | Adopted: `derived` refused; no expectation; owner is `bd-01M2WVFV2…` | §3 AC3; §4; Slice 7 |
| B3 | Adopted: `SourceSupportStatus`, explicit physical input, `support_status`, e4 falsifier | §3 AC4; Slice 5 |
| M1 | Adopted: `Unknown(reason, asserted)` preserved exactly | §4; Slice 7 |
| M2 | Adopted: specific errors, shared-ID foreign fixtures, snapshot/re-encode aliasing | §7; §8 rules; Slices 6–8 |
| M3 | Adopted: binding rule, `DeclaredComposition.of` coverage check, Sherlock-shaped fixture | §3 AC4; §6; Slice 5 |
| M4 | Adopted: claim withdrawn; ledger authority; witness | §3 AC2; Slice 6; §11 |
| M5 | Adopted: `ungated` / `fromResult` links; sealed `GateOutcome` | §3 AC2; Slice 3 |
| M6 | Adopted: one-way rule; candidates from basis keys; `DecisionBasis`; `DecodeFilled` reconciled | §3 AC2; §2.1–2.2; Slice 4 |
| M7 | Adopted: several receipts per stage with IDs; provider identity; link/measure stage and candidate-set IDs | §3 AC3 |
| M8 | Adopted: ADR 0019 and contract amendments in Slice 8 | D8; §2.1–2.2; §9 |
| m1 | Adopted: `CandidatePolicy`, defined `ContextPolicy`, `measurement_compatibility` reserved | §3 AC3; §2.1 |
| m2 | Adopted: owner corrected; the coordinator adds the Mote edge | §1; D1 |
| m3 | Adopted: each named check has a mutation; targets sorted; permuted-view witness | Slices 2, 4, 5, 7 |
| m4 | Adopted: p7 `Located` with both alternatives; `ambiguous` reserved | §3 AC1; Slice 4 |
| m5 | Adopted: `DeclaredUniverse` at policy level, subset-checked | §3 AC3; Slice 5 |
| m6 | Adopted: identity refusal; `Decoded` keyed by unit; no invented failures | §5; Slice 6 |
| m7 | Adopted: manifest sections; typed `SupportRelation`; unit fidelity status; tolerance; builder in laws main | §4; §3; §6 |
| m8 | Adopted: conflation wording; lines re-anchored at `f49bec1b`; G1/G2 split | §1; §3 AC2 |
