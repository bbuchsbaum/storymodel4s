package storymodel4s.view

import cats.data.NonEmptyVector
import cats.syntax.all.*
import storymodel4s.core.*
import storymodel4s.features.{FeatureAddress, FeatureTargetKey, SupportResolver}
import storymodel4s.story.*

/** Evidence horizon used to distinguish an omniscient model view from a reader-time view. */
enum EpistemicHorizon:
  case Omniscient
  case ReaderAt(offset: Int)

/** Why a requested derived feature cannot yet be resolved to a concrete feature space. */
enum FeatureResolutionIssue:
  /** The basis-aware output-space rule is unavailable until `features.BasisId` lands. */
  case BasisIdentityUnavailable(basisId: Checksum)

  /** The legacy derived-space rendering no longer satisfies feature-space identifier rules. */
  case InvalidDerivedOutputSpace(derivation: Checksum)

  def canonicalString: String = this match
    case BasisIdentityUnavailable(basisId)     => s"basis-identity-unavailable:${basisId.hex}"
    case InvalidDerivedOutputSpace(derivation) =>
      s"invalid-derived-output-space:${derivation.hex}"

/** One requested feature view, preserving raw-space identity versus a derived recipe identity.
  *
  * `basisId` is the typed migration slot for basis-aware derivations. Until `features.BasisId`
  * lands, a populated slot fails closed as [[FeatureChannelState.Unresolved]] rather than aliasing
  * the legacy no-basis output space.
  */
enum FeatureSelection:
  case Raw(space: FeatureSpaceId)
  case Derived(derivation: Checksum, basisId: Option[Checksum] = None)

  def canonicalString: String = this match
    case Raw(space)                   => s"raw:${space.value}"
    case Derived(derivation, basisId) =>
      s"derived:${derivation.hex}:basis:${basisId.fold("none")(_.hex)}"

  private[view] def resolveSpace: Either[FeatureResolutionIssue, FeatureSpaceId] = this match
    case Raw(space)                => Right(space)
    case Derived(derivation, None) =>
      FeatureSpaceId
        .from("derived:" + derivation.short(32))
        .leftMap(_ => FeatureResolutionIssue.InvalidDerivedOutputSpace(derivation))
    case Derived(_, Some(basisId)) =>
      Left(FeatureResolutionIssue.BasisIdentityUnavailable(basisId))

/** The narrative-unit axis a portable feature scale may select. */
enum NarrativeUnitBasis:
  /** Atomic event/state situations in discourse order. */
  case Situations

  /** Composite units of exactly one declared segment kind. */
  case Segments(kind: SegmentKind)

  /** Versioned configuration value shared by every view compiler. */
  def canonicalString: String = this match
    case Situations     => "situations"
    case Segments(kind) => s"segments:${NarrativeUnitBasis.segmentKindName(kind)}"

  /** Human-readable description used by textual twins. */
  def label: String = this match
    case Situations     => "situations"
    case Segments(kind) => s"${NarrativeUnitBasis.segmentKindName(kind)} segments"

object NarrativeUnitBasis:
  private def segmentKindName(kind: SegmentKind): String = kind match
    case SegmentKind.Scene   => "scene"
    case SegmentKind.Episode => "episode"
    case SegmentKind.Story   => "story"
    case SegmentKind.Arc     => "arc"

/** The exact target family from which any feature-bearing view is compiled. */
enum FeatureScale:
  /** A surface axis; token and sentence use their canonical dedicated target cases. */
  case SurfaceUnit(kind: SurfaceUnitKind)

  /** A story-model axis resolved from situations or typed segments. */
  case NarrativeUnit(basis: NarrativeUnitBasis)

  /** Versioned configuration value shared by Codex and Atlas receipts. */
  def canonicalString: String = this match
    case SurfaceUnit(kind)   => s"surface:${FeatureScale.surfaceKindName(kind)}"
    case NarrativeUnit(axis) => s"narrative:${axis.canonicalString}"

  /** Human-readable scale description used by textual twins. */
  def label: String = this match
    case SurfaceUnit(kind)   => s"surface ${FeatureScale.surfaceKindName(kind)}"
    case NarrativeUnit(axis) => s"narrative ${axis.label}"

object FeatureScale:
  /** The stable default for callers that have not yet exposed the shared scale selector. */
  val Default: FeatureScale = FeatureScale.SurfaceUnit(SurfaceUnitKind.Sentence)

  private def surfaceKindName(kind: SurfaceUnitKind): String = kind match
    case SurfaceUnitKind.Paragraph => "paragraph"
    case SurfaceUnitKind.Sentence  => "sentence"
    case SurfaceUnitKind.Clause    => "clause"
    case SurfaceUnitKind.Token     => "token"

/** Shared semantic state that remains stable when Codex and Atlas projections change. */
final case class CommonViewState private (
    selection: Set[Address],
    focus: Option[Address],
    horizon: EpistemicHorizon,
    relationLayers: Set[RelationLayer],
    feature: Option[FeatureSelection]
)

object CommonViewState:
  /** Construct shared state only when every selected or focused address belongs to the view seam.
    */
  def of(
      selection: Set[Address] = Set.empty,
      focus: Option[Address] = None,
      horizon: EpistemicHorizon = EpistemicHorizon.Omniscient,
      relationLayers: Set[RelationLayer] = Set.empty,
      feature: Option[FeatureSelection] = None
  ): Either[DomainError, CommonViewState] =
    val addresses = (selection.toVector ++ focus.toVector).distinct.sortBy(_.render)
    addresses.find(address => ViewRef.parse(address).isEmpty) match
      case Some(address) =>
        Left(
          DomainError.InvalidFormat(
            "CommonViewState.address",
            address.render,
            "not a recognized typed view reference"
          )
        )
      case None => Right(new CommonViewState(selection, focus, horizon, relationLayers, feature))

  val empty: CommonViewState =
    new CommonViewState(Set.empty, None, EpistemicHorizon.Omniscient, Set.empty, None)

/** Maximum number of simultaneously active semantic channels and relation families. */
final case class ChannelBudget private (
    maxAnnotationKinds: Int,
    maxRelationLayers: Int
)

object ChannelBudget:
  private val KindCount = AnnotationKind.values.length
  private val RelationCount = RelationLayer.values.length

  /** Construct a budget bounded by the closed annotation and relation vocabularies. */
  def of(
      maxAnnotationKinds: Int,
      maxRelationLayers: Int
  ): Either[DomainError, ChannelBudget] =
    if maxAnnotationKinds < 0 || maxAnnotationKinds > KindCount then
      Left(
        DomainError.InvalidFormat(
          "ChannelBudget.maxAnnotationKinds",
          maxAnnotationKinds.toString,
          s"expected an integer in [0, $KindCount]"
        )
      )
    else if maxRelationLayers < 0 || maxRelationLayers > RelationCount then
      Left(
        DomainError.InvalidFormat(
          "ChannelBudget.maxRelationLayers",
          maxRelationLayers.toString,
          s"expected an integer in [0, $RelationCount]"
        )
      )
    else Right(new ChannelBudget(maxAnnotationKinds, maxRelationLayers))

  val Normal: ChannelBudget = new ChannelBudget(4, 2)
  val All: ChannelBudget = new ChannelBudget(KindCount, RelationCount)

/** Bounded lane policy; excess annotations remain represented in an explicit overflow slot. */
final case class LanePolicy private (maxLanesPerKind: Int)

object LanePolicy:
  val Maximum: Int = 64

  /** Construct a per-channel lane bound suitable for interactive rendering. */
  def of(maxLanesPerKind: Int): Either[DomainError, LanePolicy] =
    if maxLanesPerKind < 0 || maxLanesPerKind > Maximum then
      Left(
        DomainError.InvalidFormat(
          "LanePolicy.maxLanesPerKind",
          maxLanesPerKind.toString,
          s"expected an integer in [0, $Maximum]"
        )
      )
    else Right(new LanePolicy(maxLanesPerKind))

  val Default: LanePolicy = new LanePolicy(4)

/** One requested annotation channel and its view-policy priority. */
final case class AnnotationChannel(kind: AnnotationKind, priority: AnnotationPriority)

/** Coherent named presets keep the page readable without weakening the underlying model. */
enum CodexLens:
  case Reading
  case Structure
  case Entity
  case Epistemic
  case Relations
  case Overview

  def channels: Vector[AnnotationChannel] = this match
    case Reading   => Vector.empty
    case Structure =>
      Vector(
        AnnotationChannel(AnnotationKind.Hierarchy, AnnotationPriority.unsafe(500)),
        AnnotationChannel(AnnotationKind.Claim, AnnotationPriority.unsafe(300))
      )
    case Entity =>
      Vector(
        AnnotationChannel(AnnotationKind.Entity, AnnotationPriority.unsafe(500)),
        AnnotationChannel(AnnotationKind.Relation, AnnotationPriority.unsafe(300))
      )
    case Epistemic =>
      Vector(
        AnnotationChannel(AnnotationKind.Context, AnnotationPriority.unsafe(500)),
        AnnotationChannel(AnnotationKind.Claim, AnnotationPriority.unsafe(300))
      )
    case Relations =>
      Vector(AnnotationChannel(AnnotationKind.Relation, AnnotationPriority.unsafe(500)))
    case Overview =>
      Vector(
        AnnotationChannel(AnnotationKind.Hierarchy, AnnotationPriority.unsafe(500)),
        AnnotationChannel(AnnotationKind.Entity, AnnotationPriority.unsafe(400)),
        AnnotationChannel(AnnotationKind.Context, AnnotationPriority.unsafe(400)),
        AnnotationChannel(AnnotationKind.Relation, AnnotationPriority.unsafe(300)),
        AnnotationChannel(AnnotationKind.Claim, AnnotationPriority.unsafe(200))
      )

/** Validated Codex compilation policy, independent of pagination and rendering backends. */
final case class CodexSpec private (
    channels: Vector[AnnotationChannel],
    channelBudget: ChannelBudget,
    lanePolicy: LanePolicy,
    scale: FeatureScale
):
  def activeKinds: Set[AnnotationKind] = channels.iterator.map(_.kind).toSet

object CodexSpec:
  /** Construct a policy only when its distinct annotation channels fit the declared budget. */
  def of(
      channels: Vector[AnnotationChannel],
      channelBudget: ChannelBudget = ChannelBudget.Normal,
      lanePolicy: LanePolicy = LanePolicy.Default,
      scale: FeatureScale = FeatureScale.Default
  ): Either[DomainError, CodexSpec] =
    val ordered = channels.sortBy(channel => (channel.kind.wireName, -channel.priority.value))
    val active = ordered.iterator.map(_.kind).toSet
    if active.size > channelBudget.maxAnnotationKinds then
      Left(
        DomainError.InvariantViolation(
          "view/codex/channel-budget/annotation-kinds",
          s"${active.size} active kinds exceed budget ${channelBudget.maxAnnotationKinds}"
        )
      )
    else Right(new CodexSpec(ordered, channelBudget, lanePolicy, scale))

  /** Build one named lens under an explicit, serializable channel and lane policy. */
  def forLens(
      lens: CodexLens,
      channelBudget: ChannelBudget = ChannelBudget.Normal,
      lanePolicy: LanePolicy = LanePolicy.Default,
      scale: FeatureScale = FeatureScale.Default
  ): Either[DomainError, CodexSpec] =
    of(lens.channels, channelBudget, lanePolicy, scale)

/** Feature-channel state distinguishes absence from a numeric zero or a faint mark. */
enum FeatureChannelState:
  case NotRequested

  /** A typed selection whose output space cannot yet be derived without changing its identity. */
  case Unresolved(selection: FeatureSelection, issue: FeatureResolutionIssue)

  /** A resolvable selection with no checked sidecar observations at the requested scale. */
  case Missing(selection: FeatureSelection, resolvedSpace: FeatureSpaceId)

  /** Checked sidecar material is required for the observations placed at this scale. */
  case SidecarRequired(
      selection: FeatureSelection,
      resolvedSpace: FeatureSpaceId,
      /** Number of checked references at this scale before the reader horizon is applied. */
      observationCount: Int
  )

  def resolvedSpaceId: Option[FeatureSpaceId] = this match
    case Missing(_, space)               => Some(space)
    case SidecarRequired(_, space, _)    => Some(space)
    case NotRequested | Unresolved(_, _) => None

  /** Selected feature identity, if this channel was requested. */
  def selectedFeature: Option[FeatureSelection] = this match
    case NotRequested                    => None
    case Unresolved(selected, _)         => Some(selected)
    case Missing(selected, _)            => Some(selected)
    case SidecarRequired(selected, _, _) => Some(selected)

  /** Stable textual-twin rendering of materialization state, distinct from magnitude. */
  def canonicalString: String = this match
    case NotRequested                    => "not-requested"
    case Unresolved(_, issue)            => s"unresolved:${issue.canonicalString}"
    case Missing(_, _)                   => "missing"
    case SidecarRequired(_, _, refCount) => s"sidecar-required:observations=$refCount"

/** One exact, horizon-visible feature observation planned identically for every projection.
  *
  * The placement deliberately carries no numeric value. Its checked sidecar row is materialized by
  * a downstream resolver; [[xExtents]] preserves every discontinuous evidence span independently.
  */
final case class FeatureObservationPlacement private[view] (
    space: FeatureSpaceId,
    target: FeatureTarget,
    support: SpanSet,
    audit: AuditRecord
):
  /** Typed semantic address used to synchronize this observation across canonical views. */
  def address: Address =
    Addressable[FeatureAddress].address(FeatureAddress.Observation(space, target))

  /** Exact half-open source extents; no hull is synthesized across discontinuous support. */
  def xExtents: NonEmptyVector[TextSpan] = support.spans

/** Shared feature-planning result before a projection chooses page lanes or map geometry. */
private[view] final case class PlannedFeatureLayer(
    state: FeatureChannelState,
    observations: Vector[FeatureObservationPlacement]
)

/** Auditable semantic contract carried by a Codex flow independently of placed page geometry. */
final case class CodexContract private (
    selection: Set[Address],
    focus: Option[Address],
    horizon: EpistemicHorizon,
    scale: FeatureScale,
    activeKinds: Set[AnnotationKind],
    relationLayers: Set[RelationLayer],
    feature: FeatureChannelState,
    channelBudget: ChannelBudget,
    lanePolicy: LanePolicy
)

object CodexContract:
  /** `absenceKinds` are declared beside the requested channels and never inside them.
    *
    * They deliberately do not consume [[ChannelBudget.maxAnnotationKinds]]: that budget bounds how
    * many lenses a caller may switch on at once, and a caller who could spend a draft's disclosure
    * out of the budget could compile a machine-built story as unmarked prose. What a draft
    * discloses about itself follows from its basis, which the receipt already states, not from view
    * policy.
    */
  private[view] def compiled(
      state: CommonViewState,
      spec: CodexSpec,
      feature: FeatureChannelState,
      absenceKinds: Set[AnnotationKind] = Set.empty
  ): CodexContract =
    new CodexContract(
      state.selection,
      state.focus,
      state.horizon,
      spec.scale,
      spec.activeKinds ++ absenceKinds,
      state.relationLayers,
      feature,
      spec.channelBudget,
      spec.lanePolicy
    )

  private[view] def foundation(annotations: Vector[TextAnnotation]): CodexContract =
    new CodexContract(
      Set.empty,
      None,
      EpistemicHorizon.Omniscient,
      FeatureScale.Default,
      annotations.iterator.map(_.kind).toSet,
      Set.empty,
      FeatureChannelState.NotRequested,
      ChannelBudget.All,
      LanePolicy.Default
    )

/** Nonnegative deterministic lane number within one annotation family. */
object LaneIndex:
  opaque type LaneIndex = Int

  def from(value: Int): Either[DomainError, LaneIndex] =
    if value < 0 then
      Left(DomainError.InvalidFormat("LaneIndex", value.toString, "expected a nonnegative integer"))
    else Right(value)

  private[view] def trusted(value: Int): LaneIndex = value

  extension (index: LaneIndex) def value: Int = index

type LaneIndex = LaneIndex.LaneIndex

/** Bounded placement result; overflow is explicit and never means an annotation was discarded. */
enum LaneSlot:
  case Lane(index: LaneIndex)
  case Overflow

/** Placement of one stable semantic annotation in its annotation-family gutter. */
final case class LanePlacement(
    annotation: AnnotationId,
    kind: AnnotationKind,
    slot: LaneSlot
)

/** Versioned identity of the deterministic interval-colouring algorithm and its configuration. */
final case class LaneAllocatorReceipt(
    algorithm: LaneAlgorithm,
    algorithmVersion: Int,
    policy: LanePolicy,
    configChecksum: Checksum
)

/** Closed lane algorithm vocabulary prevents renderer labels from becoming algorithm identity. */
enum LaneAlgorithm:
  case IntervalFirstFit

/** Complete lane assignment for a Codex flow, including every overflowed annotation. */
final case class LaneAllocation private (
    placements: Vector[LanePlacement],
    receipt: LaneAllocatorReceipt
):
  def slotOf(annotation: AnnotationId): Option[LaneSlot] =
    placements.find(_.annotation == annotation).map(_.slot)

  def overflow: Vector[AnnotationId] =
    placements.collect { case LanePlacement(annotation, _, LaneSlot.Overflow) => annotation }

object LaneAllocation:
  private val AlgorithmVersion = 1

  /** Allocate non-overlapping supports by deterministic first fit, retaining overflow explicitly.
    */
  def allocate(
      annotations: Vector[TextAnnotation],
      policy: LanePolicy
  ): Either[DomainError, LaneAllocation] =
    val duplicate = annotations
      .groupMapReduce(_.id)(_ => 1)(_ + _)
      .collect { case (id, count) if count > 1 => id }
      .toVector
      .sorted
      .headOption
    duplicate match
      case Some(id) => Left(DomainError.DuplicateId("AnnotationId", id.value))
      case None     =>
        val placements = AnnotationKind.values.toVector.flatMap { kind =>
          allocateKind(annotations.filter(_.kind == kind).sortBy(sortKey), policy)
        }
        val checksum = ContentAddress.digest(
          Vector(
            "lane-allocation",
            LaneAlgorithm.IntervalFirstFit.toString,
            AlgorithmVersion.toString,
            policy.maxLanesPerKind.toString
          )
        )
        Right(
          new LaneAllocation(
            placements.sortBy(_.annotation),
            LaneAllocatorReceipt(
              LaneAlgorithm.IntervalFirstFit,
              AlgorithmVersion,
              policy,
              checksum
            )
          )
        )

  private def allocateKind(
      annotations: Vector[TextAnnotation],
      policy: LanePolicy
  ): Vector[LanePlacement] =
    val occupied = Array.fill(policy.maxLanesPerKind)(Vector.empty[SpanSet])
    annotations.map { annotation =>
      val lane = occupied.indices.find { index =>
        occupied(index).forall(existing => !overlaps(existing, annotation.support))
      }
      lane match
        case Some(index) =>
          occupied(index) = occupied(index) :+ annotation.support
          LanePlacement(annotation.id, annotation.kind, LaneSlot.Lane(LaneIndex.trusted(index)))
        case None => LanePlacement(annotation.id, annotation.kind, LaneSlot.Overflow)
    }

  private def overlaps(left: SpanSet, right: SpanSet): Boolean =
    left.refs.toVector.exists(a => right.refs.toVector.exists(b => a.span.overlaps(b.span)))

  private def sortKey(annotation: TextAnnotation): (Int, Int, Int, String) =
    val span = annotation.support.minSpan
    (span.start, span.endExclusive, -annotation.priority.value, annotation.id.value)

/** Pure compiler that exposes only model-backed annotations and never invents view-side claims. */
final class CodexCompiler private (provenance: ViewProvenance):
  import CodexCompiler.Candidate

  /** Compile a validated story under shared semantic state and a checked Codex policy. */
  def compile(
      model: StoryModel[ModelStatus.Validated],
      state: CommonViewState,
      spec: CodexSpec
  ): Either[DomainError, CodexFlow] =
    if provenance.basis == ViewBasis.DraftBuild then
      Left(
        DomainError.InvariantViolation(
          "view/codex/provenance/draft-basis",
          "a validated model may not be compiled under a draft-build receipt; " +
            "use compileDraft, whose receipt names the promotion state it renders"
        )
      )
    else run(model, None, state, spec)

  /** Compile a draft's words together with the exact evidence of its own incompleteness.
    *
    * Why the reading view needs this and not only the Atlas: the Codex is where the words are, and
    * a researcher looking at a machine-built story reads the story's prose there. A draft edition
    * that shipped an Atlas and no Codex would leave the most important surface in `vision.md`
    * unavailable for every model the pipeline actually builds; a draft edition that shipped a Codex
    * compiled as though the model were validated would show unmarked prose, which reads as prose
    * the model understood. So the failures are drawn on the words they concern:
    * [[AnnotationKind.Gap]], [[AnnotationKind.Abstention]] and [[AnnotationKind.UnsatisfiedLaw]],
    * each carrying the producer's own record of what went wrong.
    *
    * The flow cannot be mistaken for a validated one: its receipt carries [[ViewBasis.DraftBuild]]
    * and a [[DraftPromotion]] derived from this exact bundle, it carries a [[DraftAbsenceLedger]]
    * that no other basis may carry, and [[compile]] refuses that receipt.
    */
  def compileDraft(
      draft: DraftModel,
      state: CommonViewState,
      spec: CodexSpec
  ): Either[DomainError, CodexFlow] =
    if provenance.basis != ViewBasis.DraftBuild then
      Left(
        DomainError.InvariantViolation(
          "view/codex/provenance/draft-basis",
          s"a draft flow requires a ${ViewBasis.DraftBuild.label} receipt, " +
            s"and this one declares ${provenance.basis.label}"
        )
      )
    else if !provenance.draft.contains(draft.promotion) then
      Left(
        DomainError.InvariantViolation(
          "view/codex/provenance/draft-promotion",
          s"receipt promotion ${provenance.draft.fold("none")(_.label)} " +
            s"does not describe this draft (${draft.promotion.label})"
        )
      )
    else run(draft.model, Some(draft), state, spec)

  // `StoryModel[?]`: nothing this compiler reads is guarded by the promotion phantom, and the
  // draft path must get the same annotations from the same rules as the validated one. What
  // separates the two is the receipt, the absence channels and the ledger, not a second compiler.
  private def run(
      model: StoryModel[?],
      draft: Option[DraftModel],
      state: CommonViewState,
      spec: CodexSpec
  ): Either[DomainError, CodexFlow] =
    for
      _ <- EvidenceVisibility.validateHorizon(model.source.canonicalText, state.horizon)
      _ <- validateRelationBudget(state, spec)
      _ <- validateProvenance(model, state, spec)
      ledger <- model.ledger
      visibleClaims = EvidenceVisibility.visibleUnder(state.horizon, ledger)
      proposals <- CodexCompiler
        .candidates(model)
        .flatMap { candidate =>
          spec.channels
            .filter(_.kind == candidate.kind)
            .filter(_ => candidate.layer.forall(state.relationLayers.contains))
            .map(channel =>
              proposal(model, visibleClaims, state.horizon, candidate, channel.priority)
            )
        }
        .sequence
      feature <- compileFeature(model, state, spec)
      absences <- compileAbsences(model, draft, state.horizon)
      annotations <- TextAnnotation.coalesce(
        proposals.flatten ++ feature.annotations ++ absences.annotations
      )
      disclosure <- discloseAbsences(draft, absences, annotations)
      contract = CodexContract.compiled(
        state,
        spec,
        feature.state,
        draft.fold(Set.empty[AnnotationKind])(_ => AnnotationKind.absence)
      )
      ancestors = visibleAncestorChains(model, visibleClaims, state.horizon)
      flow <- CodexFlow.compiledExact(
        model.source,
        annotations,
        spec.lanePolicy,
        contract,
        ancestors,
        provenance,
        disclosure
      )
    yield flow

  /** Every absence the draft carries, split into the ones that name words and the ones that cannot.
    *
    * The placement rule is [[AbsencePlacement]], the same one the Atlas draws its marks with, so a
    * failure claims the same words in both projections or is unplaced in both.
    */
  private def compileAbsences(
      model: StoryModel[?],
      draft: Option[DraftModel],
      horizon: EpistemicHorizon
  ): Either[DomainError, CodexCompiler.AbsenceCompilation] =
    def clip(support: SpanSet): Option[SpanSet] =
      EvidenceVisibility.clipSupport(support, horizon)
    draft.fold(Right(CodexCompiler.AbsenceCompilation(Vector.empty, Vector.empty))) { bundle =>
      bundle.absences
        .traverse { absence =>
          val subject = absence.subject(model.source.id)
          AbsencePlacement.of(model, absence, clip) match
            case EpistemicPlacement.NoDiscoursePosition(reason) =>
              Right(Left(UnplacedAbsence(absence, subject, reason)))
            case EpistemicPlacement.AtSpans(spans) =>
              TextAnnotation
                .of(
                  subject,
                  spans,
                  absence.kind,
                  CodexCompiler.AbsencePriority,
                  AuditRecord.deterministic(
                    provenance.compilerVersion,
                    provenance.configChecksum,
                    absence.upstream
                  ),
                  Some(absence)
                )
                .map(Right(_))
        }
        .map { placed =>
          CodexCompiler.AbsenceCompilation(
            placed.collect { case Right(annotation) => annotation },
            placed.collect { case Left(unplaced) => unplaced }
          )
        }
    }

  /** Absence is annotated, not omitted: the ledger must account for every absence the draft has. */
  private def discloseAbsences(
      draft: Option[DraftModel],
      absences: CodexCompiler.AbsenceCompilation,
      annotations: Vector[TextAnnotation]
  ): Either[DomainError, Option[DraftAbsenceLedger]] = draft match
    case None         => Right(None)
    case Some(bundle) =>
      val ledger = DraftAbsenceLedger(absences.annotations.map(_.id), absences.unplaced)
      val survived = annotations.count(_.absence.isDefined)
      if ledger.total != bundle.absences.size then
        Left(
          DomainError.InvariantViolation(
            "view/codex/draft/absence-census",
            s"the draft carries ${bundle.absences.size} absences and the flow accounts " +
              s"for ${ledger.total}"
          )
        )
      else if survived != absences.annotations.size then
        Left(
          DomainError.InvariantViolation(
            "view/codex/draft/absence-coalescing",
            s"${absences.annotations.size} absence annotations coalesced to $survived"
          )
        )
      else Right(Some(ledger))

  // `StoryModel[?]`: the ancestor chains read the hierarchy and the claim ledger, neither of which
  // the promotion phantom guards, and a draft's reading view needs the same navigation a validated
  // one gets.
  private def visibleAncestorChains(
      model: StoryModel[?],
      visibleClaims: Option[Set[ClaimId]],
      horizon: EpistemicHorizon
  ): Map[Address, Vector[Address]] =
    val graph = model.graph
    val addressable = Addressable[StoryRef]
    def claimVisible(meta: ClaimMeta): Boolean = visibleClaims.forall(_.contains(meta.id))
    def supportVisible(ref: StoryRef): Boolean =
      model.supporting(ref).flatMap(EvidenceVisibility.clipSupport(_, horizon)).nonEmpty
    def situationVisible(id: SituationId): Boolean =
      graph.situations
        .get(id)
        .exists(node => claimVisible(node.meta) && supportVisible(StoryRef.Situation(id)))
    def segmentVisible(id: SegmentId): Boolean =
      graph.segments
        .get(id)
        .exists(node => claimVisible(node.summary.meta) && supportVisible(StoryRef.Segment(id)))

    val primaryParent = model.hierarchy.primary
      .filter(edge => claimVisible(edge.meta))
      .map(edge => edge.member -> edge.parent)
      .toMap
    def ancestors(member: NarrativeMember): Vector[Address] =
      VisibleAncestorChain.from(member, primaryParent, segmentVisible)

    val situations = graph.situations.keysIterator.collect {
      case id if situationVisible(id) =>
        addressable.address(StoryRef.Situation(id)) -> ancestors(NarrativeMember.Situation(id))
    }
    val segments = graph.segments.keysIterator.collect {
      case id if segmentVisible(id) =>
        addressable.address(StoryRef.Segment(id)) -> ancestors(NarrativeMember.Segment(id))
    }
    (situations ++ segments).toMap

  private def proposal(
      model: StoryModel[?],
      visibleClaims: Option[Set[ClaimId]],
      horizon: EpistemicHorizon,
      candidate: Candidate,
      priority: AnnotationPriority
  ): Either[DomainError, Option[TextAnnotation]] =
    val visible = visibleClaims.forall(_.contains(candidate.meta.id))
    if !visible then Right(None)
    else
      model.supporting(candidate.ref).flatMap(EvidenceVisibility.clipSupport(_, horizon)) match
        case None          => Right(None)
        case Some(support) =>
          val claimAddresses =
            (candidate.meta.id +: candidate.meta.evidence.toVector
              .flatMap(_.upstream)
              .sorted).distinct
              .map(id => Addressable[CoreRef].address(CoreRef.Claim(id)))
          val evidenceAddresses = candidate.meta.evidence.toVector.map(evidence =>
            Addressable[CoreRef].address(CoreRef.Evidence(evidence.id))
          )
          TextAnnotation
            .of(
              Addressable[StoryRef].address(candidate.ref),
              support,
              candidate.kind,
              priority,
              AuditRecord.of(claimAddresses ++ evidenceAddresses, candidate.meta.provenance)
            )
            .map(Some(_))

  private def validateRelationBudget(
      state: CommonViewState,
      spec: CodexSpec
  ): Either[DomainError, Unit] =
    if state.relationLayers.size > spec.channelBudget.maxRelationLayers then
      Left(
        DomainError.InvariantViolation(
          "view/codex/channel-budget/relation-layers",
          s"${state.relationLayers.size} active relation layers exceed budget " +
            spec.channelBudget.maxRelationLayers
        )
      )
    else Right(())

  private def validateProvenance(
      model: StoryModel[?],
      state: CommonViewState,
      spec: CodexSpec
  ): Either[DomainError, Unit] =
    val expectedConfig = CodexCompiler.configurationChecksum(state, spec)
    if provenance.configChecksum != expectedConfig then
      Left(
        DomainError.InvariantViolation(
          "view/codex/provenance/config-checksum",
          s"${provenance.configChecksum.hex} does not match ${expectedConfig.hex}"
        )
      )
    else
      model.receipt match
        case Some(receipt) if provenance.modelReceiptChecksum.contains(receipt.contentChecksum) =>
          Right(())
        case Some(receipt) =>
          Left(
            DomainError.InvariantViolation(
              "view/codex/provenance/model-receipt",
              s"expected model receipt ${receipt.contentChecksum.hex}"
            )
          )
        case None
            if provenance.modelReceiptChecksum.isEmpty &&
              (provenance.basis == ViewBasis.ResearcherReviewedFixture ||
                provenance.basis == ViewBasis.DraftBuild) =>
          Right(())
        case None =>
          Left(
            DomainError.InvariantViolation(
              "view/codex/provenance/model-receipt",
              "a receipt-free model must be labelled as a researcher-reviewed fixture or a " +
                "draft build"
            )
          )

  private def compileFeature(
      model: StoryModel[?],
      state: CommonViewState,
      spec: CodexSpec
  ): Either[DomainError, CodexCompiler.FeatureCompilation] =
    FeaturePlanner
      .plan(model, state.feature, spec.scale, state.horizon, provenance)
      .flatMap { planned =>
        val priorities = spec.channels.collect {
          case AnnotationChannel(AnnotationKind.Feature, priority) => priority
        }
        planned.observations
          .flatMap(observation => priorities.map(priority => observation -> priority))
          .traverse(featureAnnotation)
          .map(CodexCompiler.FeatureCompilation(planned.state, _))
      }

  private def featureAnnotation(
      observation: FeatureObservationPlacement,
      priority: AnnotationPriority
  ): Either[DomainError, TextAnnotation] =
    TextAnnotation.of(
      observation.address,
      observation.support,
      AnnotationKind.Feature,
      priority,
      observation.audit
    )

/** One pure feature planner shared by every canonical view compiler. */
private[view] object FeaturePlanner:
  // `StoryModel[?]`: a feature plan reads `featureRefs` and the atlas, neither of which the
  // promotion phantom guards, and the draft path needs the same plan the validated path gets.
  def plan(
      model: StoryModel[?],
      selection: Option[FeatureSelection],
      scale: FeatureScale,
      horizon: EpistemicHorizon,
      provenance: ViewProvenance
  ): Either[DomainError, PlannedFeatureLayer] = selection match
    case None => Right(PlannedFeatureLayer(FeatureChannelState.NotRequested, Vector.empty))
    case Some(selected) =>
      selected.resolveSpace match
        case Left(issue) =>
          Right(
            PlannedFeatureLayer(
              FeatureChannelState.Unresolved(selected, issue),
              Vector.empty
            )
          )
        case Right(space) =>
          validateFeatureRefs(space, model.featureRefs.filter(_.space == space)).flatMap {
            spaceRefs =>
              val refs = spaceRefs
                .filter(ref => atScale(model, scale, ref.target))
                .sortBy(ref => (ref.target, ref.row))
              val available =
                model.featureSpaces.contains(space) &&
                  model.sidecars.contains(space) && refs.nonEmpty
              if !available then
                Right(
                  PlannedFeatureLayer(
                    FeatureChannelState.Missing(selected, space),
                    Vector.empty
                  )
                )
              else
                val resolver = supportResolver(model)
                refs
                  .traverse(ref => placement(selected, space, ref, resolver, horizon, provenance))
                  .map(observations =>
                    PlannedFeatureLayer(
                      FeatureChannelState.SidecarRequired(selected, space, refs.size),
                      observations.flatten
                    )
                  )
          }

  private def placement(
      selection: FeatureSelection,
      space: FeatureSpaceId,
      ref: FeatureRef,
      resolver: SupportResolver,
      horizon: EpistemicHorizon,
      provenance: ViewProvenance
  ): Either[DomainError, Option[FeatureObservationPlacement]] =
    resolver
      .support(ref.target)
      .toRight(
        DomainError.InvariantViolation(
          "view/features/support",
          s"unresolved ${FeatureTargetKey.parts(ref.target).mkString("/")}"
        )
      )
      .map(EvidenceVisibility.clipSupport(_, horizon))
      .map(
        _.map { visibleSupport =>
          val upstream =
            Vector(Addressable[FeatureAddress].address(FeatureAddress.Space(space))) ++
              (selection match
                case FeatureSelection.Raw(_)                 => Vector.empty
                case FeatureSelection.Derived(derivation, _) =>
                  Vector(
                    Addressable[FeatureAddress].address(
                      FeatureAddress.Derivation(derivation)
                    )
                  ))
          FeatureObservationPlacement(
            space,
            ref.target,
            visibleSupport,
            AuditRecord.of(
              upstream,
              Provenance.deterministic(provenance.compilerVersion, provenance.configChecksum)
            )
          )
        }
      )

  private def supportResolver(
      model: StoryModel[?]
  ): SupportResolver =
    SupportResolver(
      SurfaceSequence(model.atlas),
      situation = id => model.graph.situations.get(id).map(_.support),
      segment = id => model.graph.segments.get(id).map(_.support)
    )

  private def validateFeatureRefs(
      space: FeatureSpaceId,
      refs: Vector[FeatureRef]
  ): Either[DomainError, Vector[FeatureRef]] =
    val duplicateTarget = refs
      .groupBy(_.target)
      .toVector
      .sortBy(_._1)
      .collectFirst { case (target, matches) if matches.size > 1 => target }
    val duplicateRow = refs
      .groupBy(_.row)
      .toVector
      .sortBy(_._1)
      .collectFirst { case (row, matches) if matches.size > 1 => row }
    duplicateTarget match
      case Some(target) =>
        Left(
          DomainError.InvariantViolation(
            s"view/features/${space.value}",
            s"duplicate target ${FeatureTargetKey.parts(target).mkString("/")}"
          )
        )
      case None =>
        duplicateRow match
          case Some(row) =>
            Left(
              DomainError.InvariantViolation(
                s"view/features/${space.value}",
                s"sidecar row $row is referenced by more than one target"
              )
            )
          case None => Right(refs)

  private[view] def atScale(
      model: StoryModel[?],
      scale: FeatureScale,
      target: FeatureTarget
  ): Boolean = scale match
    case FeatureScale.SurfaceUnit(kind) =>
      kind match
        case SurfaceUnitKind.Token =>
          target match
            case FeatureTarget.Token(_) => true
            case _                      => false
        case SurfaceUnitKind.Sentence =>
          target match
            case FeatureTarget.Sentence(id) =>
              model.atlas.byId.get(id).exists(_.kind == SurfaceUnitKind.Sentence)
            case _ => false
        case expected @ (SurfaceUnitKind.Clause | SurfaceUnitKind.Paragraph) =>
          target match
            case FeatureTarget.SurfaceUnit(id) =>
              model.atlas.byId.get(id).exists(_.kind == expected)
            case _ => false
    case FeatureScale.NarrativeUnit(basis) =>
      basis match
        case NarrativeUnitBasis.Situations =>
          target match
            case FeatureTarget.Situation(_) => true
            case _                          => false
        case NarrativeUnitBasis.Segments(kind) =>
          target match
            case FeatureTarget.Segment(id) =>
              model.graph.segments.get(id).exists(_.kind == kind)
            case _ => false

/** Shared one-pass escaping for versioned view-compiler configuration receipts. */
private[view] object ViewConfigurationRendering:
  def horizonValue(horizon: EpistemicHorizon): String = horizon match
    case EpistemicHorizon.Omniscient       => "omniscient"
    case EpistemicHorizon.ReaderAt(offset) => s"reader:$offset"

  def featureValue(feature: Option[FeatureSelection]): String =
    feature.fold("none")(_.canonicalString)

  def selections(state: CommonViewState): Vector[String] =
    state.selection.toVector.sortBy(_.render).map(_.render)

  def relations(state: CommonViewState): Vector[String] =
    state.relationLayers.toVector.sortBy(_.toString).map(_.toString)

  def render(fields: Vector[(String, String)]): String =
    fields
      .map((key, value) => s"${escape(key)}=${escape(value)}")
      .mkString("|")

  def escape(value: String): String =
    value.iterator
      .foldLeft(new java.lang.StringBuilder(value.length)) { (builder, character) =>
        character match
          case '\\'     => builder.append("\\\\")
          case '|'      => builder.append("\\|")
          case '\n'     => builder.append("\\n")
          case '\u0000' => builder.append("\\0")
          case other    => builder.append(other)
      }
      .toString

object CodexCompiler:
  /** The priority every draft disclosure channel carries.
    *
    * Why one fixed value and not a caller's choice: [[AnnotationPriority]] is view policy that a
    * caller states per requested channel, and no caller requests these — a draft discloses its own
    * failures whether or not anyone asked. One value for all three also means no absence outranks
    * another, which would be a claim about which failure matters more that nothing in the model
    * supports.
    */
  private[view] val AbsencePriority: AnnotationPriority =
    AnnotationPriority.unsafe(AnnotationPriority.Maximum)

  private final case class FeatureCompilation(
      state: FeatureChannelState,
      annotations: Vector[TextAnnotation]
  )

  private final case class AbsenceCompilation(
      annotations: Vector[TextAnnotation],
      unplaced: Vector[UnplacedAbsence]
  )

  private final case class Candidate(
      ref: StoryRef,
      meta: ClaimMeta,
      kind: AnnotationKind,
      layer: Option[RelationLayer]
  )

  /** Bind compilation to a model/config provenance receipt before inspecting any story data. */
  def apply(provenance: ViewProvenance): CodexCompiler = new CodexCompiler(provenance)

  /** Canonical checksum of the semantic state and spec, independent of collection iteration order.
    */
  def configurationChecksum(state: CommonViewState, spec: CodexSpec): Checksum =
    Checksum.ofText(configurationRendering(state, spec))

  private val ConfigurationRenderingVersion = "codex-compiler-config/v2"

  /** Versioned canonical rendering that the compiler checksum commits to (ADR 0002 D13). */
  private[view] def configurationRendering(
      state: CommonViewState,
      spec: CodexSpec
  ): String =
    ViewConfigurationRendering.render(configurationFields(state, spec))

  private[view] def escapeConfiguration(value: String): String =
    ViewConfigurationRendering.escape(value)

  private def configurationFields(
      state: CommonViewState,
      spec: CodexSpec
  ): Vector[(String, String)] =
    val focus = state.focus.fold("none")(_.render)
    val selections = ViewConfigurationRendering.selections(state)
    val relations = ViewConfigurationRendering.relations(state)
    val channels =
      spec.channels.map(channel => s"${channel.kind.wireName}:${channel.priority.value}")
    Vector(
      "rendering" -> ConfigurationRenderingVersion,
      "horizon" -> ViewConfigurationRendering.horizonValue(state.horizon),
      "focus" -> focus,
      "feature" -> ViewConfigurationRendering.featureValue(state.feature),
      "scale" -> spec.scale.canonicalString,
      "selection.count" -> selections.size.toString
    ) ++ selections.zipWithIndex.map((value, index) => s"selection.$index" -> value) ++ Vector(
      "relation.count" -> relations.size.toString
    ) ++ relations.zipWithIndex.map((value, index) => s"relation.$index" -> value) ++ Vector(
      "channel.count" -> channels.size.toString
    ) ++ channels.zipWithIndex.map((value, index) => s"channel.$index" -> value) ++ Vector(
      "budget.annotationKinds" -> spec.channelBudget.maxAnnotationKinds.toString,
      "budget.relationLayers" -> spec.channelBudget.maxRelationLayers.toString,
      "lanes.maxPerKind" -> spec.lanePolicy.maxLanesPerKind.toString
    )

  // `StoryModel[?]`: the candidate enumeration reads the graph and the hierarchy, neither of which
  // the promotion phantom guards. A draft's words get exactly the annotations a validated model's
  // words would get from the same claims.
  private def candidates(model: StoryModel[?]): Vector[Candidate] =
    val graph = model.graph
    val nodes =
      graph.situations.valuesIterator
        .map(node => Candidate(StoryRef.Situation(node.id), node.meta, AnnotationKind.Claim, None))
        .toVector ++
        graph.segments.valuesIterator
          .map(node =>
            Candidate(StoryRef.Segment(node.id), node.summary.meta, AnnotationKind.Hierarchy, None)
          )
          .toVector ++
        graph.entities.valuesIterator
          .map(node => Candidate(StoryRef.Entity(node.id), node.meta, AnnotationKind.Entity, None))
          .toVector ++
        graph.contexts.valuesIterator
          .map(node =>
            Candidate(StoryRef.Context(node.id), node.meta, AnnotationKind.Context, None)
          )
          .toVector
    val relations =
      graph.relations.participants.map(edge =>
        Candidate(
          StoryRef.Participant(edge.situation, edge.role, edge.entity),
          edge.meta,
          AnnotationKind.Relation,
          Some(RelationLayer.Participant)
        )
      ) ++
        graph.relations.temporal.map(edge =>
          Candidate(
            StoryRef.Temporal(edge.from, edge.relation, edge.to, edge.context),
            edge.meta,
            AnnotationKind.Relation,
            Some(RelationLayer.WorldTime)
          )
        ) ++
        graph.relations.causal.map(edge =>
          Candidate(
            StoryRef.Causal(edge.cause, edge.relation, edge.effect),
            edge.meta,
            AnnotationKind.Relation,
            Some(RelationLayer.Causal)
          )
        ) ++
        graph.relations.goals.map(edge =>
          Candidate(
            StoryRef.Goal(edge.from, edge.relation, edge.to),
            edge.meta,
            AnnotationKind.Relation,
            Some(RelationLayer.Goal)
          )
        ) ++
        graph.relations.stateChanges.map(edge =>
          Candidate(
            StoryRef.StateChange(edge.event, edge.change, edge.state),
            edge.meta,
            AnnotationKind.Relation,
            Some(RelationLayer.StateChange)
          )
        ) ++
        graph.relations.references.map(edge =>
          Candidate(
            StoryRef.Reference(edge.from, edge.mode, edge.to),
            edge.meta,
            AnnotationKind.Relation,
            Some(RelationLayer.Reference)
          )
        ) ++
        graph.relations.entityRelations.map(edge =>
          Candidate(
            StoryRef.EntityLink(edge.from, edge.relation, edge.to),
            edge.meta,
            AnnotationKind.Relation,
            Some(RelationLayer.EntityContinuity)
          )
        ) ++
        model.hierarchy.containment.map(edge =>
          Candidate(
            StoryRef.Containment(edge.member, edge.parent, edge.kind),
            edge.meta,
            AnnotationKind.Hierarchy,
            None
          )
        )
    val addressable = Addressable[StoryRef]
    (nodes ++ relations).sortBy(candidate => addressable.address(candidate.ref).render)
