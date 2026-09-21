# G1 mapping records: implementation plan (revision 3)

Mote `bd-01M2WVENSB0P955Y0B603CC20Y`. **Plan only.** Nothing named below exists yet. Type, file
and suite names are acceptance specifications, not implemented or executed code. Governing
documents: [PLAN](../refactor/PLAN.md) §§2–3, [ADR 0019](../adr/0019-mapping-measurement-policy.md),
and the [analysis contract](../refactor/ANALYSIS_CONTRACT.md) ("the contract"). Code references
are `path:line` at main `f49bec1b`; support-honesty references are at `solo/support-honesty`
`b8d02822`.

**Revision 3 (2026-09-21).** Folds [cold review 2](../refactor/evidence/g1-mapping-records-plan-20260921/cold-review-2.json)
of revision 2 (`990c162d`): N1–N15 plus the residuals it lists under `priorFindings`. §12 maps
every finding to where it is handled.

| Finding | Change |
|---|---|
| N1 | One derivation binding per record: recall checksum, `ViewFingerprint` and a per-target propositional-scope digest (§3 AC2). Each derived value records its binding; `checked` refuses mismatches; the binding fields are mandatory wire match fields. |
| N2, N15 | Ten slices, starting with vocabulary. The old Slice 5 is split. Each mutation sits in the slice that adds its check. The ticket lands on main once, as one reviewed branch, together with its ADR and contract amendments. |
| N3 | No construction door for `Derived` or `Calibrated` in G1, and `checked`'s unreachable refusals are deleted. The falsifier moves to the codec's `Reserved` refusal. |
| N4 | New `Abstain` request and `NoDecision` mass, and a stated localization derivation. |
| N5–N14 | Adopted as proposed. |
| Residuals | Tagged timebase and axis origin on the wire. BACKLOG G1 row updated at landing. |

Revision 2 (`990c162d`) folded [cold review 1](../refactor/evidence/g1-mapping-records-plan-20260921/cold-review-1.json)
(B1–B3, M1–M8, m1–m8) and two coordinator rulings: `TermSupportStatus` instead of `Option`, and
a total encoder.

## 1. Goal and non-goals

**Goal.** One checked, versioned `MappingResult` for one recall against one checked source
representation, owned by existing modules, with a 0.x JSON codec. It accounts for every requested
unit and word, every target and its physical-support status, alternatives, missingness, external
outcomes, processing failures and decomposition status. It keeps the five measure kinds, raw
argmax, decoded choice, calibration and fidelity distinct, and binds every derived value to one
recall and one source view. It carries the stage provenance that compatibility checks read;
historical artifacts are fixed at unknown provenance.

| Excluded work | Owner |
|---|---|
| Raw local score/cost extraction; candidate/render/scoring receipts | `bd-01M2TACM78289S4TECE91GT5K2` |
| Producing `NormalizedScoreMass`; reference inference; opaque target renaming | `bd-01M2WVF86T8QEEA1TK8Z0ASJHW` |
| Compatibility outcomes; `Derived` producers and their decode | `bd-01M2WVFV2WGF5BVQHX7JTYYWRH` (with the evidence and reference tickets) |
| Word-ID stability across resegmentation; timing; per-axis unions, coverage, hull; time grid | `bd-01M2WVH1DC8ZPDC992G4MX3TXG` |
| TSV exchange, file hashes, readiness/publication, Python/R reader | `bd-01M2WVHF4B5DAXJYY4W91VK4WV`, `bd-01M2TADC4VKSDZ2S9SXETH2MYM` |
| `MonotoneScene`/fill in the library; fill origin at the decoder | `bd-01M2TAD04SR823TQVG9VPNH6R3` |

**G1/G2 split.** G1 fixes word identity under one segmentation, unit membership, and the binding
of unit identity to that unitization. G2 AC1 owns word-ID stability and timing. G1's
`supportCoverage` is target-level child completeness only; G2 AC3 owns per-axis unions, their
coverage, and the hull.

**Also out of scope:** inference numerics; StoryModel/HSMM wire changes (Slice 9 only extracts a
codec-private helper); embed-bench outputs, which stay byte-identical with their known
conflations; a decomposition detector; calibration; new modules.

## 2. Decisions

| # | Decision | Basis |
|---|---|---|
| D1 | **Support honesty lands first.** It provides `SupportAssessment` = `Assessed` \| `Unestablished(EmptyEligibility \| ZeroEligibleWeight)` \| `NotApplicable(ExternalState \| Unreachable)` behind a sealed `CostBreakdown.support` (branch `cost.scala:159-176,273,538`), and hsmm/v4, which carries no `TypedSupport`. This schema is therefore the only wire for physical support and ticks. Every link carries `TermSupportStatus`: `Evaluated(CostBreakdown.support)` or `NotComputed(NoCostBreakdown)`. No `Option` and no `supportWeight: Double`. | Owner ruling on the support ticket; main returns 1.0 on an empty eligible set (`align/.../cost.scala:842-851`). |
| D2 | **PLAN §3 bundle representation.** A part-bundle inventory with per-target, per-axis membership. D1B's composed bundle enters only as a declared composition (binding rule, §3 AC4). Cross-part order comes only from that declaration, never from part IDs; without one, adjacencies are `Incomparable` and a cross-part target is refused. `StoryModel` stays one bundle under D1A. | PLAN.md:149-154; ANALYSIS_CONTRACT.md:37-40; `SherlockSourceAtlas.scala:31-181`. |
| D3 | **Decomposition:** every unit carries `NotAssessed(NoDecompositionDetector)` in 0.x. It is disclosed and does not by itself block compatibility. | Coordinator ruling; ANALYSIS_CONTRACT.md:13. |
| D4 | **Three supports, three names in new fields:** `support_status` + `source_support` (physical), `term_support` (`TermSupportStatus`), `support_coverage` (group). No existing type is renamed. | Coordinator ruling. |
| D5 | **Contract vocabulary wins.** Deviations are in §2.1 and go into the contract amendment. | ANALYSIS_CONTRACT.md:3. |
| D6 | **One slice at a time; no new module.** `recall` owns units/words, `align` records/measures/provenance, `codec` the wire. The schema is separate from StoryModel/HSMM. | PLAN.md:95-101. |
| D7 | **Authority-bearing sum types are sealed traits with bare-private cases, never enum cases.** Covers `GateOutcome`, `FidelityStatus`, `TermSupportStatus`, `StageProvenance`, `DecodedTargetMass`, `DecisionCalibration`, `SourceSupportStatus`, `SupportCoverage`. Non-authority cases get public *checked factories* (not `apply`/`copy`/`Mirror`): `SourceSupportStatus.located`/`.unlocated`, `MappingLink.ungated`. `StageProvenance.Derived`, `DecisionCalibration.Calibrated` and `CalibratedProbability` have **no construction door at all in G1**: bare-private, no factory, no `private[align]` exception. Attack probes assert this, and the codec refuses their wire tags as `Reserved`. | Support-honesty precedent; `CostBreakdown` `fromProduct` forgery at `codec/.../align.scala:52-57`; N3 option (b). |
| D8 | **ADR 0019 and contract amendments** record the new public vocabulary (including `MappingMiniature` as experimental test support in the published laws artifact), D3, D7, §2.1 and §2.2. | AGENTS.md:199-204 (SD5); ADR 0019:26-27. |
| D9 | **Lands on main once**, as one reviewed branch. Slices are commits on it, each green with its own focused totals. The D8 amendments land in the same landing, so no vocabulary reaches main without its ADR line. | N15; SD5. |

### 2.1 Deviations from the contract (for the contract amendment)

| Topic | 0.1 resolution | Why |
|---|---|---|
| Artifact name | `MappingResult` (the contract's `RecallMapping`); `MappingRun` envelope deferred | `HsmmResult` naming; the envelope is facade work |
| Link rows | Typed Scala measures; the wire flattens them to `mapping_links` rows tagged `measure_kind` | AC2 separation (ANALYSIS_CONTRACT.md:84-90) |
| Reserved values | `partial`, `ambiguous`, `manual-review`, `measurement_compatibility` and non-`evidence-support` relations have no Scala case. `Derived` and `Calibrated` have cases but no construction door (D7). The decoder refuses all their tags as `Reserved`. `encode` is total. | No producer means no forgery door (coordinator ruling) |
| Gap-fill value | Outside candidate support ⇒ exact 0 with `decision_in_candidate_support=false`; an inside-support 0 is legal. For a non-mass basis (`RawScore`), 0 is a **flagged sentinel** that must be read with the flag, never as a score. | ANALYSIS_CONTRACT.md:98 |
| No decision | `decoded_target_mass = {"status":"no-decision"}` and no `decoded_target_id` | Contract has no form (N4) |
| Fidelity | `fidelity_status` / `fidelity_facets` come only from `FidelityReport`. The new `gate_outcome` field holds the ModeGate result. The unit-level `fidelity_assessment_status` is the chosen link's status. | ANALYSIS_CONTRACT.md:118 |
| Content-term support | Required, tagged `term_support` (`evaluated` + hsmm/v4 assessment \| `not-computed` + reason) replaces the undefined `assessment_support_id` | D1, D4 |
| Derivation binding | New mandatory `derivation_source`: `none` \| `bound` + `recall_checksum`, `view_fingerprint`, `scope_digest` | N1 |
| Optional values | Every optional value is a tagged object; the codec never emits an absent key. This includes policy absences, the asserted receipt, and the timebase (`PresentationAxis.timebase` is `Option`, `core/.../source.scala:576`). | Coordinator ruling: absence carries a reason |
| Axis origin | The contract's axis `origin` is the playback extent's start tick (decimal string); a text axis's origin is character 0 | `AxisExtent.PlaybackTicks.start`; no core "origin" field |
| Target identity | `target_id` = `SourceNodeRef.key` under `target-id/source-node-ref/v1` | D4; opaque IDs are the reference ticket's |
| Words / manifest | ID, index, UTF-16 span, membership; no timing. No `file_hashes`, `readiness` or `MappingRun`. | G1/G2 split; exports/facade tickets |

### 2.2 Rejected alternatives (for the ADR amendment)

| Rejected alternative | Reason |
|---|---|
| Reusing the HSMM wire | hsmm/v4 refuses non-text support |
| The composed bundle as *the* representation | PLAN §3 |
| Decoding `Derived` from embedded or caller-supplied receipts | Self-certification (ANALYSIS_CONTRACT.md:164-170) |
| A `private[align]` door for `Derived` with a stated residual | It is reachable from `storymodel4s.align.attack` (N3 option a) |
| Enum cases for authority types | They get public `apply`/`copy`/`Mirror` |
| Threshold `Ambiguous` | The cutoff would itself be the claim |
| Numeric or `Option` term support | Absence must carry a reason |
| A fallible encoder | Refusal belongs in types |
| Mass == 0 as the gap-fill test (`VoyageExport.scala:116`) | An in-support zero is legitimate |
| Per-value derivation contexts, or several derivation sources per record | No single context could re-decode the record (N1) |

## 3. Design by acceptance criterion

### AC1: one checked result accounts for everything

**recall:** `recall/src/main/scala/storymodel4s/recall/inventory.scala`.

```scala
object RecallWordId extends OpaqueId("RecallWordId")
final class WordIdPolicy private (val name: String, val artifact: Checksum) // G0 "input-artifact-sha256+zero-based-parsed-word-index/v1"
final class RecallWord private (val id: RecallWordId, val index: Int, val span: TextSpan) // no public factory
sealed trait WordMembership      // Member(unit) | Unassigned(NotInAnyUnitSpan)
final class SegmentationId private (val digest: Checksum) // transcript checksum + ordered (unit id, span set)
object SegmentationId { def of(graph: RecallGraph[Checked]): SegmentationId }
sealed trait DecompositionStatus // 0.1: NotAssessed(NoDecompositionDetector)
enum MappingSemantics { case CategoricalReferent }
final class InventoryUnit private (val id: RecallUnitId, val ordinal: Int, val span: SpanSet,
    val words: Vector[RecallWordId], val decomposition: DecompositionStatus, val semantics: MappingSemantics)
final class RecallInventory private (val transcript: Checksum, val segmentation: SegmentationId, val idPolicy: WordIdPolicy,
    val words: Vector[RecallWord], val units: Vector[InventoryUnit], val membership: Map[RecallWordId, WordMembership]):
  def digest: Checksum
  def describes(graph: RecallGraph[Checked]): Boolean // transcript checksum and SegmentationId.of(graph) equal
object RecallInventory:
  def of(graph: RecallGraph[Checked], wordSpans: Vector[TextSpan], policy: WordIdPolicy): Either[DomainError, RecallInventory]
```

- **`RecallInventory.of` validates word spans:** nonempty, strictly ordered, disjoint, within the
  transcript, on UTF-16 code-unit boundaries.
- **Word IDs** are policy-derived (`SherlockBaselineCapture.scala:84,102`).
- **Membership** is span overlap (`:120-121`): a word in two units refuses; a word in no unit is
  `Unassigned` (the 94 G0 separators).
- **`SegmentationId`** binds positional unit IDs (`segmenter.scala:435`) to the unitization.

**align:** outcomes in `mapping.scala` (§3 AC2 has decisions).

```scala
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
```

**What `computed` requires:**
- link destinations equal the union of measure keys: every measured alternative has a link, and
  every link has a measure (N13);
- the basis names a present measure;
- `CandidateSetId` is recomputed, never supplied.

**Localization derivation (N4):**

| Case | Status |
|---|---|
| `Failed`, `Excluded`, or an `Abstain` request | `NotComputed` |
| Empty basis | `Unranked` |
| Chosen `External(Unranked)` | `Unranked` |
| Chosen any other `External` | `Nonlocalizable` (p5; all-external rows) |
| Chosen `Target` | `Located` (including p7, where both tied alternatives stay as links) |

`ambiguous` stays reserved. Missingness is always explicit: `Failed`/`Excluded`, `Unranked`,
`Unassigned`, `Unlocated`, `SupportCoverage`. No record reads candidates through the defaulting
`Candidates.set` (`align/.../candidates.scala:80`).

### AC2: measures, gate, fidelity, term support and the derivation binding

New file `mappingmeasures.scala`:

```scala
final class DerivationBinding private (val recallChecksum: Checksum, val viewFingerprint: ViewFingerprint, val scopeDigest: Checksum)
object DerivationBinding:
  def of(recall: RecallGraph[Checked], inventory: RecallInventory, view: SourceView, source: SourceRepresentation)
      : Either[MappingRefusal, DerivationBinding]
  // requires inventory.describes(recall), ViewFingerprint.of(view) == source.viewFingerprint,
  // and the view's scope digest == source.scopeDigest
sealed trait MeasureDerivation   // Supplied | FromResult(binding)
enum MeasureKind { case RawScore, NormalizedScoreMass, TransportMass, ModelPosterior, CalibratedProbability }
enum ScoreDirection { case HigherIsBetter, LowerIsBetter }
final class RawScores private (val channel: String, val direction: ScoreDirection, val scale: String,
    val values: Map[Destination, Double], val stage: StageEntryId, val derivation: MeasureDerivation)
object RawScores:
  def of(...): Either[...]                                          // Supplied
  def fromCosts(result: HsmmResult, unit: RecallUnitId, binding: DerivationBinding, stage: StageEntryId): Either[...]
final class NormalizedScoreMass private (val universe: TargetUniverseId, val prior: ReferencePriorId, val temperature: Double,
    val mass: Map[Destination, Double], val stage: StageEntryId)    // Σ = 1 within MappingTolerance.RowSum = 1e-9
final class TransportMass private (val rowBudget: Double, val mass: Map[Destination, Double], val stage: StageEntryId)
final class ModelPosterior private (val mass: Map[AlignState, Double], val stage: StageEntryId, val binding: DerivationBinding)
object ModelPosterior:
  def of(result: HsmmResult, unit: RecallUnitId, binding: DerivationBinding, stage: StageEntryId): Either[MappingRefusal, ModelPosterior]
sealed trait CalibratedEvent     // ChosenDecisionCorrect(policy)
final class CalibratedProbability private (...) // no door in G1 (D7)
final class UnitMeasures private (val raw: Vector[RawScores], val normalized: Option[NormalizedScoreMass],
    val transport: Option[TransportMass], val posterior: Option[ModelPosterior])
sealed trait GateOutcome         // NotGated | NoContradictionDetected, Contradicted(facets) [derived only]
sealed trait FidelityStatus      // NotAssessed(reason), NotApplicable | Assessed(report) [derived only]
sealed trait TermSupportStatus   // NotComputed(NoCostBreakdown) | Evaluated(SupportAssessment) [derived only]
final class MappingLink private (val destination: Destination, val gate: GateOutcome, val fidelity: FidelityStatus,
    val termSupport: TermSupportStatus, val inferenceStage: StageEntryId, val candidateSet: CandidateSetId,
    val binding: Option[DerivationBinding]) // Some iff gated/Evaluated; the wire never shows an absent key
object MappingLink:
  def ungated(destination: Destination, stages: UnitStageRefs): MappingLink // NotGated, NotAssessed(NoGateEvaluation), NotComputed
  def fromResult(result: HsmmResult, binding: DerivationBinding, recall: RecallGraph[Checked], view: SourceView,
      source: SourceRepresentation, unit: RecallUnitId, state: AlignState, stages: UnitStageRefs): Either[MappingRefusal, MappingLink]
```

**Binding (N1).** `ModelPosterior.of`, `RawScores.fromCosts` and `MappingLink.fromResult` refuse
unless `result.recallChecksum == binding.recallChecksum` and
`result.viewFingerprint == binding.viewFingerprint`, as `CellCoordinates.of` does
(`align/.../trace.scala:108-132`). `fromResult` also requires that `recall` and `view` hash to the
binding.

`ViewFingerprint.of` does not render `NodeSummary.propositional` (`align/.../wire.scala:85-152`).
The binding's scope digest closes that gap: it is sorted by ref key over each target's scope. At
Slice 5 start, confirm that every other `NodeSummary` field `FidelityFacets.assess` reads is
rendered; any that is not joins the scope digest.

**Gate and fidelity.**
- The gate comes from `result.admissibility` (`hsmm.scala:299`) for the link's own unit and anchor.
- Fidelity is read from the **representation's** target scope, never the view node's:
  `FidelityFacets.assess(unit.proposition, node, mode)` (`facets.scala:79-99`) only when that scope
  is `Declared`; otherwise `NotAssessed(ScopeUndeclared)`.
- `termSupport` is `Evaluated(result.costs(unit)(state).support)`; external states get `NotGated`,
  `NotApplicable` and `Evaluated(NotApplicable(ExternalState))`.
- Video targets are `Undeclared` (`RecallToVideo.scala:175,200`), although `AlignState.Source` *is*
  the Faithful mode (`matrix.scala:18,46`).

**Residuals (N12, M4).** `ModelPosterior` and `Evaluated` term support prove shape, gate admission
and binding only. Their source values are caller-controllable: `HsmmResult.validated`
(`hsmm.scala:373-384`) is public, and so are `AlignWire.costBreakdown` and a caller-built posterior.
Inference authority lives only in the ledger's `Inference` stage, and G1 never sets it to `Derived`.
The measure types stay separate (today both kinds are `AlignmentMatrix`,
`sinkhorn.scala:193-228`, `matrix.scala:112`), and every G1 decision is
`Unavailable(NoCalibrationArtifact)`.

**Decisions** (in `mapping.scala`):

```scala
final class DecisionBasis private (val kind: MeasureKind, val channel: Option[String])
sealed trait DecisionRequest     // RawArgmax | ExternalDecode(chosen, policy: DecisionPolicyId) | Abstain(policy: DecisionPolicyId, reason: String)
enum DecisionOrigin { case RawArgmax; case StructuredDecode(p: DecisionPolicyId); case GapFill(p: DecisionPolicyId); case Abstention(reason: String) }
sealed trait DecodedTargetMass   // InCandidateSupport(value) | OutsideCandidateSupport | NoDecision
sealed trait DecisionCalibration // Unavailable(reason) | Calibrated(p) [no door in G1]
final class UnitDecision private (val basis: DecisionBasis, val chosen: Option[Destination], val origin: DecisionOrigin,
    val rawArgmax: Option[(Destination, Double)], val decodedMass: DecodedTargetMass, val calibration: DecisionCalibration)
```

**Candidate support** is the `Destination` projection of the basis measure's keys, externals
included (N11). It is neither physical nor term support.

**Derived, never supplied:** `rawArgmax` covers the full state space (ties by key,
`matrix.scala:179-182`; `RawScore` direction respected). For `ExternalDecode`:
- a choice inside candidate support gives `StructuredDecode`, even when it equals the argmax;
- a choice outside gives `GapFill`, with value 0 and the flag.

`Abstain` and an empty basis give `Abstention`, `chosen = None` and `NoDecision`.

**Legacy agreement.** The legacy `DecodeFilled` rule (`VoyageExport.scala:113-118`) agrees except
on inside-support zeros. The legacy writers keep their conflations (`RecallToVideo.scala:659,682,685`,
`PosteriorSidecar.scala:65`, `MonotoneScene.scala:216-223`); this record cannot express them.

### AC3: policies, universe, receipts and provenance

New files `mappingvocabulary.scala` and `mappingprovenance.scala`:

```scala
enum Destination { case Target(ref: SourceNodeRef); case External(state: ExternalState) }
enum MappingRefusal { ... } // one case per refusal; tests assert the case (N2: lands in Slice 3)
object DecisionPolicyId extends OpaqueId("DecisionPolicyId"); object CandidatePolicyId extends OpaqueId("CandidatePolicyId")
object ReferencePriorId extends OpaqueId("ReferencePriorId"); object CalibrationArtifactId extends OpaqueId("CalibrationArtifactId") // labels, not authority
final class StageEntryId private (val digest: Checksum)   // derived; no string door (N10)
final class CandidateSetId private (val digest: Checksum) // derived
final class TargetUniverseId private (val digest: Checksum)
enum Stage { case Candidates, Rendering, Scoring, Context, Refinement, Inference, Decision, Projection }
final class ProviderIdentity private (val provider: String, val model: String, val artifact: Checksum)
sealed trait ProviderStatus      // Identified(ProviderIdentity) | NotApplicable(reason)
final class StageReceipt private (val stage: Stage, val policy: String, val config: Checksum,
    val inputs: Vector[Checksum], val provider: ProviderStatus)
sealed trait StageProvenance     // Unknown(reason, asserted: Option[StageReceipt]) | Derived(receipt) [no door in G1]
enum UnknownProvenanceReason { case HistoricalArtifact, NominationProvenanceUnproven, CallerSuppliedDecision, NotRun }
final class StageEntry private (val id: StageEntryId, val stage: Stage, val provenance: StageProvenance)
final class StageLedger private (val entries: Vector[StageEntry]) // ≥1 per Stage; unique ids; canonical order (stage ordinal, id)
final class UnitStageRefs private (val inference: StageEntryId, val candidates: StageEntryId, val decision: StageEntryId)
sealed trait CandidatePolicy     // Declared(id, coverage: CandidateCoverage) | Unknown(reason)
sealed trait CandidateCoverage   // Truncated(perLevel) | Complete | Unknown(reason)
sealed trait ContextPolicy       // 0.1: Unspecified(reason)
sealed trait InferencePolicy     // 0.1: HistoricalReconstruction(label) | Unspecified(reason)
sealed trait ReferencePrior      // Declared(id) | NotApplicable(reason)   (N13: no Option)
sealed trait DecisionPolicy      // Declared(id) | NotApplicable(reason)
final class DeclaredUniverse private (val id: TargetUniverseId, val targets: Vector[SourceNodeRef], val grain: TargetGrain)
final class MappingPolicies private (val inference: InferencePolicy, val context: ContextPolicy, val candidate: CandidatePolicy,
    val referencePrior: ReferencePrior, val decision: DecisionPolicy, val universe: DeclaredUniverse)
enum AnalysisGrain { case InferenceUnit(s: SegmentationId); case Word; case Targets(g: TargetGrain) }
final class UnitRoles private (val inference: AnalysisGrain, val organization: AnalysisGrain, val projection: AnalysisGrain)
```

**Identifier derivations (N10).** All are canonical-token SHA-256 digests (the `Render.Tokens`
precedent, `hsmm.scala:190-204`), and none is constructible from a string.

| Identifier | Digest over |
|---|---|
| `StageEntryId` | `stage-entry/v1`, the stage, and the provenance render (reason plus asserted-receipt fields, or receipt fields). Receipt-less entries digest their reason. Identical entries are duplicates and refuse. |
| `CandidateSetId` | `candidate-set/v1`, the candidates entry ID, the unit, and the sorted basis destination keys |
| `TargetUniverseId` | `target-universe/v1`, the sorted refs, and the grain |

`computed`, `checked` and the decoder recompute every identifier and refuse a mismatch.

**Other rules.** Policy fields use contract §8 names (ANALYSIS_CONTRACT.md:164-170); links carry
`inference_stage_id` and `candidate_set_id` (`:88`). No G1 path produces `Derived`.

### AC4: multipart sources and exact coordinates

New file `mappingsource.scala`:

```scala
sealed trait BundleEntry         // Media(bundle: SourceBundle) | TextSource(canonicalText: Checksum); factories media/text
final class DeclaredComposition private (val composed: SourceBundle)
object DeclaredComposition { def of(composed: SourceBundle, parts: NonEmptyVector[SourceBundle]): Either[MappingRefusal, DeclaredComposition] }
sealed trait SourceSupportStatus // Located(support: TypedSupport) | Unlocated(reason); factories located/unlocated (N5)
enum UnlocatedReason { case NoLocusInSource }; enum SupportRelation { case EvidenceSupport }
enum BundleRole { case Part(bundle: SourceBundleId); case Composition }
sealed trait SupportCoverage     // Complete | Partial(missing: NonEmptySet[SourceNodeRef]) | Unknown(reason); derived only
enum TargetGrain { case SingleLevel(level: Int); case Hierarchy(levels: Vector[Int]) }
enum PartOrder { case Before, Same, After, Incomparable }
final class MappingTarget private (val ref: SourceNodeRef, val level: Int, val parent: Option[SourceNodeRef],
    val sourceSupport: SourceSupportStatus, val axisMembership: Map[PresentationAxisId, BundleRole],
    val supportCoverage: SupportCoverage, val propositional: PropositionalScope)
final class SourceRepresentation private (val bundles: NonEmptyVector[BundleEntry], val composition: Option[DeclaredComposition],
    val targets: Vector[MappingTarget], val viewFingerprint: ViewFingerprint, val scopeDigest: Checksum):
  def digest: Checksum           // canonical over ref-key order
  def order(a: PresentationAxisId, b: PresentationAxisId): PartOrder
object SourceRepresentation:
  def of(view: SourceView, bundles: NonEmptyVector[BundleEntry], composition: Option[DeclaredComposition],
      physical: Map[SourceNodeRef, SourceSupportStatus]): Either[MappingRefusal, SourceRepresentation]
```

**Physical input.** Physical support is an explicit per-target input, total over the view's nodes.
`NodeSummary.support` is not assumed to be physical: in the video path it is annotation text
(`RecallToVideo.scala:165,192`), and only Sherlock swaps in anchored support
(`SherlockRecallMapping.scala:315-317`).

**Coverage (N6):**

| Target | Coverage |
|---|---|
| Group (has declared children), all children `Located` | `Complete` |
| Group, some or all children `Unlocated` | `Partial(missing)`; `g1` = `Partial({e4})` |
| Leaf, `Located` | `Complete` |
| Leaf, `Unlocated` | `Unknown(TargetUnlocated)` |

A group's own `support_status` is reported separately.

**Binding rule (M3, N8):**
- **Bundle identity.** `support.bundleIdentity` must be a listed part's identity or
  `composition.composed.identity`.
- **Native axes.** An axis equal to a listed part's `primaryAxis.id` resolves to `Part`, whatever
  `anchor.bundle` says. Sherlock names the composed bundle on both anchors
  (`SherlockSourceAtlas.scala:130-158`).
- **Composed axis.** The composed primary axis resolves to `Composition`.
- **Refusals.** Any other axis refuses as foreign; an axis matching twice refuses as ambiguous. A
  target whose anchors span two parts refuses when no composition is declared.
- **Composition check.** `DeclaredComposition.of` requires the `TrackComposition` mappings
  (`core/.../source.scala:985,1570-1600`) to cover exactly the given parts, each once, all
  targeting the composed primary axis. `SourceRepresentation.of` re-checks that coverage against
  the listed `Media` entries (N8).
- **Order.** `order` reads composition segments only.

Decode rebuilds support with `EvidenceSupport.of` (`core/.../source.scala:1422`) on the bundle
this rule names. Today the codec writes decimal ticks (`codec/.../core.scala:110-116,142`) but
cannot decode anchored support (`:170-177`).

### AC5: refusal, forgery, round-trips and falsification

§7 lists the probes and §8 the courts. The named falsifications are:
- the decoded value set to the argmax value (Slice 6);
- dropping the `p6` row (Slices 7 and 9);
- twin-view `Assessed`, a foreign-recall posterior, and a mixed-result record (Slices 5, 7 and 9).

**`MappingResult`** (`mapping.scala`):

```scala
sealed trait DerivationSource    // NoDerivedValues | Bound(binding)
final class MappingResult private (val inventory: RecallInventory, val source: SourceRepresentation, val policies: MappingPolicies,
    val roles: UnitRoles, val ledger: StageLedger, val derivation: DerivationSource, val outcomes: Vector[UnitOutcome]):
  def digest: Checksum
object MappingResult:
  def checked(inventory: RecallInventory, source: SourceRepresentation, policies: MappingPolicies, roles: UnitRoles,
      ledger: StageLedger, outcomes: Vector[UnitOutcome]): Either[MappingRefusal, MappingResult]
```

`checked` refuses each of the following, with a specific `MappingRefusal` case:
1. outcomes that are not exactly the inventory's units, in order;
2. destinations outside `policies.universe`, or a universe not contained in the representation;
3. dangling, duplicate, non-canonical or non-recomputing ledger and candidate-set IDs;
4. `roles` whose `SegmentationId` is not the inventory's (N13);
5. a `NormalizedScoreMass` whose universe or prior differs from `policies` (N13);
6. **binding (N1)** — more than one distinct binding among derived values; any bound value while
   the inventory and representation disagree with the binding; `Assessed` on a target whose
   representation scope is not `Declared`.

The derivation source is derived: `Bound(b)` when any value is bound, otherwise
`NoDerivedValues`. There are no `Calibrated` or `Derived` checks, because neither can exist (D7).
Every constructible record is encodable.

## 4. Codec design

New file `codec/src/main/scala/storymodel4s/codec/mapping.scala`, object `MappingCodecs`. It also
touches `codec/src/main/scala/storymodel4s/codec/align.scala` to extract a codec-private shared
`SupportAssessment` codec from `HsmmResultCodec`'s private `SupportWire` (branch
`codec/.../align.scala:125,461,486,822`). The hsmm/v4 WOG golden bytes must stay unchanged as the
control (N7).

- **Schema.** `"schema": "storymodel4s.mapping-record"`, `"schemaVersion": "mapping-record/v0.1"`.
  The version is checked first (`UnsupportedSchema`). There is no migration.
- **Canonical form.** Canonical printing and exact hex Doubles (`canonical.scala:22-37,79`).
  Text that does not re-encode identically is refused (`VoyageCodecs.decode`, `voyage.scala:40`).
  `encode(r): String` is total.
- **Exact integers.** Ticks, rationals, extents and `Long` IDs are decimal strings; a JSON number
  is refused by the tick decoder with `NumericTick`. The golden round-trips `"9007199254740993"`
  (2^53+1) as a tick and as a timebase denominator on JVM, JS and Native.
- **Manifest sections:**
  - `bundles`: id, identity, edition, source kind, role;
  - `axes`: id, bundle, kind, extent, `origin` (§2.1), tagged `timebase`;
  - `coordinate_mappings`: composition segments, occurrence, receipt;
  - `targets`: `support_status`, `source_support` with `support_relation`, `support_coverage`, scope;
  - `inventory`, `policies`, `roles`, `ledger`, `derivation_source`, `outcomes`.

**Contextual decode order:** `decode(text, context: ExpectedMappingContext)`.
1. Check the version.
2. Parse, with tagged-value and `NumericTick` checks.
3. Require `inventoryDigest` and `sourceDigest` to equal the context's (`DigestMismatch`).
4. If `derivation_source` is `bound` (N1), require `DerivationContext(result, recall, view)`, then:
   - `context.inventory.describes(recall)`;
   - the context view's fingerprint and scope digest equal the representation's;
   - `result.recallChecksum` and `result.viewFingerprint` equal the wire's `recall_checksum` and
     `view_fingerprint`.
5. Re-derive each authority value.
6. Finish through `MappingResult.checked`.
7. Apply the canonical guard.

| Wire value | 0.1 rule |
|---|---|
| Posterior rows | Must equal `ModelPosterior.of(result, …)` bit for bit |
| Gated `gate_outcome`, `Assessed` | Must equal `MappingLink.fromResult(result, binding, recall, view, source, …)` |
| `term_support` `evaluated` | Must equal `Evaluated(result.costs(unit)(state).support)` |
| `term_support` `not-computed` | Only on a link equal to `MappingLink.ungated(destination, stages)`. A missing key refuses. |
| `derived`, `calibrated`, reserved statuses, `measurement_compatibility` | `Reserved` |
| `Unknown(reason, asserted)` | Decoded exactly |

## 5. Historical HsmmResult adapter

New file `mappinghistory.scala`:

```scala
object HistoricalMapping:
  def of(result: HsmmResult, recall: RecallGraph[Checked], view: SourceView, inventory: RecallInventory,
      source: SourceRepresentation, decode: HistoricalDecode): Either[MappingRefusal, MappingResult]
enum HistoricalDecode { case ArgmaxOnly; case Decoded(chosen: Map[RecallUnitId, Option[SourceNodeRef]], label: String) }
```

**Binding.** The binding comes from `DerivationBinding.of(recall, inventory, view, source)`. That
covers the source-built-from-view and same-recall checks (m6, N1). The result must also match the
binding, and `Decoded` must cover exactly the recall's units.

**Ledger.** One `Unknown(HistoricalArtifact, asserted = None)` entry per stage. The adapter
asserts no receipts; this is the M4 witness that replaces the uncompilable `Derived` mutant. The
policy is `HistoricalReconstruction(label)` with `CandidatePolicy.Unknown`, `ReferencePrior` and
`DecisionPolicy` both `NotApplicable`, and a declared universe.

**Measures and links.** `ModelPosterior.of` and `RawScores.fromCosts` (channel `local-cost`,
`LowerIsBetter`) supply the measures; links come from `fromResult`. A state with no breakdown
refuses.

**Decisions.**
- The basis is `ModelPosterior`.
- A `Decoded(Some)` choice gives `StructuredDecode` or `GapFill`.
- A `Decoded(None)` choice gives `Abstain` (N4): the legacy fallback to the argmax is not
  reproduced.
- Calibration is `Unavailable`.

**Outcomes.** Every unit is `Complete`, since `validated` guarantees rows equal units. No
embed-bench file changes.

## 6. Fixtures

**Miniature.** `tools/recall-study/fixtures/baseline-miniatures.json` (4,089 bytes, sha256
`be3f8c3d8b4595cd4e58ba0ef8135f26b702cc9c470f4d0a0b2ed8f17d742fc3`): parts at 10 ticks/s, instant
`e8`, unlocated `e4`, `g1` = {`e2`, `e4`}, `p5` external, `p6` failure, argmax `e3` versus decoded
`e7` on `p3`, `p7` tied, `p8` a revisit.

**Transcription and builder.** Slice 1 adds the plain-data transcription to
`laws/src/main/scala/storymodel4s/laws/MappingMiniature.scala`. Slice 7 adds the public-factory
builder `MappingMiniature.record` to the same laws main file (`build.sbt:412,476-480`); it is
experimental test support in the published laws artifact (D8). Authored numbers are `RawScores`
with ungated `NotComputed` links, so the record is `NoDerivedValues`. e2→e7 is `Incomparable`
until the test declares a composition.

**Digest court.** `codec/.jvm/src/test/scala/storymodel4s/codec/MappingMiniatureDigestSuite.scala`,
on the precedent of `fixtures/.jvm/.../WarOfTheGhostsCodecGoldenResourceSuite.scala`, walks up to
`build.sbt`, checks the sha256, and compares the JSON to the transcription field by field.

**Sherlock-shaped fixture.** `align/src/test/scala/storymodel4s/align/SherlockShapedSource.scala`
uses core APIs only, because corpus-intake depends only on `corpus.jvm`/`core.jvm`
(`build.sbt:451`). It has:
- two parts;
- a composed bundle from `SourceBundle.editionPlaybackAxis` (`source.scala:1098`),
  `TrackComposition.of` and `SourceBundle.of`;
- composed-bound rows with native plus composed anchors;
- a scene spanning both parts.

**Historical fixture.** A tiny `GraphHsmm` run from existing align fixtures, plus a Declared
**twin view** built with `NodeSummary.copy(propositional = Declared)`, which keeps the same
`ViewFingerprint` (N1 witness).

## 7. Probe and external-consumer suites

**Probe suites** live outside the owning package (`align/src/test/scala/storymodel4s/probes/MappingUnforgeableSuite.scala`,
`recall/src/test/scala/storymodel4s/probes/RecallInventoryUnforgeableSuite.scala`,
`codec/src/test/scala/storymodel4s/codecprobe/MappingCodecProbeSuite.scala`); the
`storymodel4s.align.attack` and `storymodel4s.recall.attack` suites cover qualified-private widening.

**Refused doors** for every D7 type: `new`, `apply`, `copy`, `fromProduct`, `Mirror.ProductOf`.
Specific probes assert:
- **no door at all** for `StageProvenance.Derived`, `DecisionCalibration.Calibrated` or
  `CalibratedProbability`, from outside or from `align.attack` (N3);
- no `StageEntryId`, `CandidateSetId` or `TargetUniverseId` from a string;
- no caller-chosen `RecallWord`;
- no gated `GateOutcome`, `Assessed`, `Evaluated` or `DerivationBinding` outside align;
- no `ModelPosterior` from an `AlignmentMatrix` (narrow; the indirect door is the §3 residual);
- `TransportMass` is not assignable to `ModelPosterior`, including via an alias.

Positive controls come first, with `classOf` dependencies (`CellCoordinatesUnforgeableSuite.scala`;
`ConstructionProbeSuite.scala`).

**Aliasing** (mutable arrays; ADR 0018:102, `VerifySuite.scala:60-183`). Mutate every accessor
result, then compare re-read values against an independent snapshot plus a fresh canonical
re-encoding, never a cached `digest`.

**Consumer suite.** `laws/src/test/scala/storymodel4s/laws/MappingContractSuite.scala` runs outside
align on all three platforms, using `MappingMiniature.record`. It asserts accounting; `p6`
`Failed`; `p5` `Nonlocalizable`; the `p3` disagreement; both `p7` links; `g1` `Partial`; `e4`
`Unlocated`; every unit `NotAssessed`; and foreign-axis, ambiguous-axis, cross-part,
duplicate-target and missing-unit refusals.

## 8. Slices

One branch, one landing (D9). Slice 0 (precondition): support honesty is on main; record the base
SHA and re-read `CostBreakdown.support` and the `SupportAssessment` codec.

Rules for every slice:
- Each commit is green with its focused totals on JVM, JS and Native unless stated.
- Each mutation is a compiled production edit whose named test fails while the slice's accepting
  control passes.
- Refusal tests assert the specific `MappingRefusal` or `CodecError` case.
- Before each compile-door mutant, and before its restored control, run `Compile/clean` and
  `Test/clean` on every platform (N14; `CellCoordinatesUnforgeableSuite.scala:64-67`).

| Slice | Changed paths | Falsifying mutations → rejecting test | Accepting control |
|---|---|---|---|
| 1 Miniature | `laws/.../MappingMiniature.scala` (data); `codec/.jvm/.../MappingMiniatureDigestSuite.scala` | e7 endTick "30"→"31" → "transcription equals fixture"; drop p6 → same. (JVM court) | Transcription equals JSON |
| 2 Inventory | `recall/.../inventory.scala`; `RecallInventorySuite`; probe + `attack` | Filter `Unassigned` → "every parsed word is accounted". First-unit overlap → "word in two units refuses". Digest over transcript only → "unitization changes SegmentationId". Skip span validation → "malformed word spans refuse". `describes` ignores segmentation → "inventory does not describe a resegmented graph". Public `RecallWord.of` → probe. Missing decomposition → "every unit NotAssessed". `private[recall]` → attack. | Eight-packet inventory with an `Unassigned` separator |
| 3 Vocabulary, provenance | `align/.../mappingvocabulary.scala`, `mappingprovenance.scala`; `MappingVocabularySuite`; probes | `StageEntryId.from(String)` → probe. Duplicate entries accepted → "duplicate stage entries refuse". Missing stage → "ledger total over stages". Order unchecked → "ledger order canonical". Any `Derived` door → attack probe "no Derived door". | Historical-shaped ledger of eight `Unknown` entries |
| 4 Source | `align/.../mappingsource.scala`; `SherlockShapedSource.scala`; `SourceRepresentationSuite` | No foreign-axis refusal → "axis outside inventory refuses". First match → "ambiguous axis refuses". Via `anchor.bundle` → "Sherlock-shaped rows resolve to part bundles". Skip `of` coverage → "composition covers exactly its parts". Skip re-check → "composition over an unlisted part refuses" (N8). Allow cross-part without composition → "cross-part target refuses without composition" (N8). Part-ID order → "e2→e7 Incomparable". No duplicate refusal → "duplicate target ref refuses". e4 support from parent → "g1 is Partial". Unlocated leaf `Complete` → "unlocated leaf is Unknown". View-order digest → "permuted view, same digest". Drop scope digest → "Declared twin changes scopeDigest". | Miniature two-part and Sherlock-shaped sources; `Before` under a declared composition |
| 5 Measures, links, binding | `align/.../mappingmeasures.scala`; `MappingMeasuresSuite`; probes | Drop Σ=1 → "unnormalized mass refuses". `ModelPosterior.of(AlignmentMatrix)` → probe. `TransportMass` alias → probe. Fidelity from `AlignState.mode` → "undeclared scope NotAssessed". Unit-0 sketch → "fidelity on the link's own unit". Scope from the view node → "twin view yields NotAssessed" (N1). Skip `recallChecksum` check → "foreign recall sharing unit IDs refuses" (N1). Skip binding check in `ModelPosterior.of` → "posterior from a foreign result refuses". Binding skips scope → "Declared twin view refuses binding". `ungated` gives `Evaluated` → "authored links NotComputed(NoCostBreakdown)". Gated `GateOutcome`, `Evaluated`, `Calibrated` doors → probes. | Declared node gives `Assessed` equal to `FidelityFacets.assess`; `Evaluated` equals the breakdown |
| 6 Outcomes, decisions | `align/.../mapping.scala` (outcomes); `MappingOutcomeSuite` | `decodedMass := rawArgmax` → "p3 keeps e7's value" (e3 .9, e7 .4). Outside as in-support 0 → "fill is OutsideCandidateSupport". Zero ⇒ outside → "in-support zero stays". Candidates from links → "listed fill target is still GapFill". Agreeing decode as `RawArgmax` → "stays StructuredDecode". Unranked→Nonlocalizable → "unranked stays". Insertion-order tie → "p7 tie by key, both kept". Empty basis falls back → "empty basis is Abstention, NoDecision, Unranked" (N4, N9). `Abstain` ignored → "Abstain yields NoDecision, NotComputed". External chosen `Located` → "external choice Nonlocalizable" (p5, all-external; N9). Unequal links and measures accepted → "links equal measured alternatives" (N13). Candidate-set ID not recomputed → "forged candidate-set id refuses". | `RawArgmax` on the same row |
| 7 Result, builder | `align/.../mapping.scala` (`checked`); `MappingResultSuite`; `MappingMiniature.record` | Accept missing unit → "missing unit refuses". Filter failed → "p6 retained and counted". Dangling ledger ref → "unknown stage entry refuses". Skip universe subset → "universe outside dictionary refuses". Skip roles check → "roles bound to the inventory segmentation" (N13). Skip normalized check → "normalized mass matches universe and prior" (N13). Skip binding count → "record mixing two results refuses" (N1). Skip scope check → "Assessed on non-Declared target refuses" (N1). No order check → "reordered outcomes refuse". `Truncated` normalized to `Unknown` → "Truncated(8) retained" (N9). | Miniature and Sherlock-shaped records accepted |
| 8 Historical | `align/.../mappinghistory.scala`; `MappingHistoricalSuite` | Argmax via `mapSource` → "Unranked row argmax is external". External `Evaluated(Assessed)` → "external links NotApplicable". Default missing breakdown → "missing breakdown refuses". Skip binding → "foreign recall (shared unit IDs) refuses", "source from another view refuses". `Decoded(None)` → argmax → "Decoded None is Abstention" (N4). Asserted receipts attached → "historical ledger asserts no receipts" (M4). | Posterior bit-equal to `result.posterior` |
| 9 Codec | `codec/.../mapping.scala`, `codec/.../align.scala` (extraction); `MappingCodecSuite`; `MappingCodecProbeSuite` | Numeric ticks → golden (2^53+1). Decoder accepts numbers → "numeric tick refuses with NumericTick". `derived` demoted → "derived refuses with Reserved" (N3). `calibrated` accepted → "calibrated refuses with Reserved" (N3). `Unknown` demoted → "historical text round-trips identically". Posterior without result → "posterior needs result context". Rounded masses → "historical round trip bit-equal". `Assessed` without context → "assessed needs context". Missing `term_support` accepted → "missing term_support refuses". `evaluated` without result → "evaluated needs result". `not-computed` on a gated link → "not-computed equals ungated" (N11). Encoder omits `not-computed` → golden. No context-view check → "Declared twin context refuses" (N1). No result/binding check → "foreign result context refuses" (N1). Missing `recall_checksum` accepted → "match fields mandatory" (N1). Absent timebase as missing key → "absent timebase is tagged". Version check removed → "v0.2 refuses with UnsupportedSchema". Digest skipped (shared IDs) → "foreign inventory refuses with DigestMismatch". Failed outcomes dropped on encode → "p6 round trip". Guard removed → "unknown field refuses". | Miniature, Sherlock-shaped and historical records round-trip identically with context; hsmm/v4 WOG golden bytes unchanged (N7) |
| 10 Consumer, aliasing, landing docs | `laws/src/test/.../MappingContractSuite.scala`; aliasing court; ADR 0019 amendment; contract amendment; `codec/README.md`; `docs/api-stability.md`; BACKLOG G1 row | Exposed `Array[Double]` → "accessor mutation leaves re-read values and re-encoding unchanged". | Public-factory miniature build |

**Rough size:** 1,900–2,300 main lines and 2,800–3,400 test lines. Slices 4, 5 and 9 are the
largest.

## 9. Evidence and gates

The evidence directory is `docs/refactor/evidence/g1-mapping-records-<date>/`. It records:
- **Identity and scope:** base, per-slice and result SHAs, and `bash tools/reference-scope.sh BASE
  HEAD` as a union over every commit.
- **Focused totals** per slice on `recall`, `align`, `codec` and `laws`, each on JVM, JS and Native.
- **Mutations:** `mutations.json` and `guard-witness-inventory.json`, one row per §8 mutation, with
  source and mutant hashes, the specific failing test and error, the passing control, and
  per-platform clean flags for both mutant and control.
- **Artifact digests (N14):** SHA-256 of the golden text, the canonical encodings of the
  miniature, Sherlock-shaped and historical records, and the unchanged hsmm/v4 WOG golden.
- **Final gate:** `sbt -batch checkAll` in a clean standalone clone, with bound totals and named
  skips, then `scalafmtCheckAll`.
- **Review:** a separate fresh-context SD6 review.

**Compatibility is additive.** No existing type or wire format changes; the `codec/align.scala`
extraction is codec-private. S0 values, the WOG golden and frozen Sherlock artifacts stay
unchanged; any numeric movement is a defect.

**The landing (D9) carries** the ADR 0019 and contract amendments (D8), `codec/README.md`,
`docs/api-stability.md` (`mapping-record/v0.1` and `MappingMiniature` as experimental 0.x), and
the BACKLOG.md G1 row updated to list the support-honesty prerequisite.

## 10. Risks

| Risk | Mitigation |
|---|---|
| R1 Support API drift | Re-read at Slice 0 (verified at `b8d02822`) |
| R2 False probe passes | Enumerate D7 cases; clean every platform before mutants and controls |
| R3 Composed-bundle binding | Binding rule plus the Sherlock-shaped fixture; real-data replay is optional |
| R4 Text sources | `TypedSupport.Text` names no bundle; check `TextSource` against S4c's `TextAlignmentSource` before Slice 4 |
| R5 Missing breakdowns | The adapter refuses; verify at Slice 8, never default |
| R6 Unrendered fidelity inputs | If `FidelityFacets` reads `NodeSummary` fields that `ViewFingerprint` does not render, add them to the scope digest at Slice 5 |
| R7 Scope creep | Refuse timing, unions, normalized-mass production and bench rewrites |
| R8 Record size | 1,050-target JSON is acceptable for 0.x |
| R9 Concurrent edits | G1 touches no embed-bench file |
| R10 Decode needs full context | Bound records need result, recall and view; the accepted cost of refusing self-certification |

## 11. What this will not establish

Localization accuracy, candidate recall or specificity; calibration (every record is
`Unavailable`); reference compatibility, or that inference ran — posteriors and `Evaluated` term
support are caller-controllable through public `HsmmResult.validated` and
`AlignWire.costBreakdown`, and the binding proves which recall and view they claim, not how they
were computed (N12); behavioral recovery; content support for historical choices; that
`NotAssessed` decomposition is harmless; event-level precision from exact source support;
story-world order from a declared composition; real-data parity (an optional NN03 replay would be
a demonstration, not a gate). A synthetic pass supports no efficacy claim.

## 12. Disposition of review findings

| ID | Disposition | Where |
|---|---|---|
| B1 | Contextual re-derivation; reserved refusals; historical round trip (binding residual closed by N1) | §4; Slices 5, 9 |
| B2, M1 | `derived` refused; no expectation; `Unknown` decoded exactly | §4; Slice 9 |
| B3 | `SourceSupportStatus`, explicit physical input, `support_status` | §3 AC4; Slice 4 |
| M2 | Specific errors; shared-ID foreign fixtures; snapshot/re-encode aliasing | §7; §8 rules |
| M3 | Binding rule; `DeclaredComposition.of`; Sherlock-shaped fixture | §3 AC4; Slice 4 |
| M4 | Claim withdrawn; ledger authority. The witness is now "historical ledger asserts no receipts" because a `Derived` mutant cannot compile (D7). | §3 AC2; Slice 8 |
| M5 | `ungated` / `fromResult`, now with identity checks (N1) | §3 AC2; Slice 5 |
| M6 | One-way rule; basis-key candidates; `DecisionBasis` | §3 AC2; Slice 6 |
| M7 | Several ID'd entries per stage; provider identity; link and measure IDs | §3 AC3 |
| M8 | ADR and contract amendments | D8, D9 |
| m1–m8 | As in revision 2. m2's BACKLOG row is updated at landing (§9). m3 is completed by N9. m5 is completed by N13. m6 is folded into N1. m7 is completed by the tagged timebase and axis origin. | §§2.1, 3, 9 |
| N1 | `DerivationBinding` with scope digest; identity checks in `fromResult` and `ModelPosterior.of`; single-binding, scope and mixed-source refusals in `checked`; mandatory wire match fields; decoder context checks; three falsifiers | §3 AC2, AC5; §4; Slices 5, 7, 9 |
| N2 | Vocabulary first; old Slice 5 split into Slices 4 and 7; mutations co-located | §8 |
| N3 | Option (b): no `Derived` or `Calibrated` door; `checked`'s refusals deleted; codec `Reserved` falsifiers; attack probes | D7; §2.1; Slices 3, 9 |
| N4 | `Abstain`, `NoDecision` and its wire form; localization table; falsifiers | §3 AC1, AC2; Slices 6, 8 |
| N5 | Checked factories; `TermSupportStatus` in D7 | D7; §3 AC4 |
| N6 | Coverage table | §3 AC4; Slice 4 |
| N7 | `codec/align.scala` extraction; WOG golden control | §4; Slice 9 |
| N8 | Coverage re-check; cross-part refusal | §3 AC4; Slice 4 |
| N9 | `Truncated`, all-external and empty-basis tests | Slices 6, 7 |
| N10 | Derived, string-less IDs; recomputation; uniqueness; canonical order | §3 AC3; Slices 3, 6, 7 |
| N11 | Result required for gated values; `not-computed` equals `ungated`; externals in candidate support; flagged sentinel | §2.1; §3 AC2; §4 |
| N12 | Term-support residual stated | §3 AC2; §11 |
| N13 | Tagged policy absences; universe/prior, segmentation, links = measures and span-validation checks | §3; Slices 2, 6, 7 |
| N14 | Artifact digests; clean on every platform for mutants and controls | §8; §9 |
| N15 | Single landing; `MappingMiniature` recorded as experimental | D8, D9; §9 |
| Residuals | BACKLOG row at landing; tagged timebase; axis origin | §2.1; §4; §9 |
