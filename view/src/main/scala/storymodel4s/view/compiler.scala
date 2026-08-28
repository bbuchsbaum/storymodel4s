package storymodel4s.view

import cats.syntax.all.*
import storymodel4s.core.*
import storymodel4s.story.*

/** Evidence horizon used to distinguish an omniscient model view from a reader-time view. */
enum EpistemicHorizon:
  case Omniscient
  case ReaderAt(offset: Int)

/** One requested feature view, preserving raw-space identity versus a derived recipe identity. */
enum FeatureSelection:
  case Raw(space: FeatureSpaceId)
  case Derived(derivation: Checksum)

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
    lanePolicy: LanePolicy
):
  def activeKinds: Set[AnnotationKind] = channels.iterator.map(_.kind).toSet

object CodexSpec:
  /** Construct a policy only when its distinct annotation channels fit the declared budget. */
  def of(
      channels: Vector[AnnotationChannel],
      channelBudget: ChannelBudget = ChannelBudget.Normal,
      lanePolicy: LanePolicy = LanePolicy.Default
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
    else Right(new CodexSpec(ordered, channelBudget, lanePolicy))

  /** Build one named lens under an explicit, serializable channel and lane policy. */
  def forLens(
      lens: CodexLens,
      channelBudget: ChannelBudget = ChannelBudget.Normal,
      lanePolicy: LanePolicy = LanePolicy.Default
  ): Either[DomainError, CodexSpec] =
    of(lens.channels, channelBudget, lanePolicy)

/** Feature-channel state distinguishes absence from a numeric zero or a faint mark. */
enum FeatureChannelState:
  case NotRequested
  case Missing(selection: FeatureSelection)
  case SidecarRequired(selection: FeatureSelection, observationCount: Int)

/** Auditable semantic contract carried by a Codex flow independently of placed page geometry. */
final case class CodexContract private (
    selection: Set[Address],
    focus: Option[Address],
    horizon: EpistemicHorizon,
    activeKinds: Set[AnnotationKind],
    relationLayers: Set[RelationLayer],
    feature: FeatureChannelState,
    channelBudget: ChannelBudget,
    lanePolicy: LanePolicy
)

object CodexContract:
  private[view] def compiled(
      state: CommonViewState,
      spec: CodexSpec,
      feature: FeatureChannelState
  ): CodexContract =
    new CodexContract(
      state.selection,
      state.focus,
      state.horizon,
      spec.activeKinds,
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
      annotations <- TextAnnotation.coalesce(proposals.flatten)
      feature = featureState(model, state.feature)
      contract = CodexContract.compiled(state, spec, feature)
      flow <- CodexFlow.exact(
        model.source,
        annotations,
        spec.lanePolicy,
        contract,
        provenance
      )
    yield flow

  private def proposal(
      model: StoryModel[ModelStatus.Validated],
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
      model: StoryModel[ModelStatus.Validated],
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
              provenance.basis == ViewBasis.ResearcherReviewedFixture =>
          Right(())
        case None =>
          Left(
            DomainError.InvariantViolation(
              "view/codex/provenance/model-receipt",
              "a receipt-free model must be labelled as a researcher-reviewed fixture"
            )
          )

  private def featureState(
      model: StoryModel[ModelStatus.Validated],
      requested: Option[FeatureSelection]
  ): FeatureChannelState = requested match
    case None                                          => FeatureChannelState.NotRequested
    case Some(selection @ FeatureSelection.Raw(space)) =>
      val count = model.featureRefs.count(_.space == space)
      if !model.featureSpaces.contains(space) || !model.sidecars.contains(space) || count == 0 then
        FeatureChannelState.Missing(selection)
      else FeatureChannelState.SidecarRequired(selection, count)
    case Some(selection @ FeatureSelection.Derived(_)) =>
      FeatureChannelState.Missing(selection)

object CodexCompiler:
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
    val channels =
      spec.channels.map(channel => s"channel:${channel.kind.wireName}:${channel.priority.value}")
    ContentAddress.digest(
      Vector("codex-compiler-config-v1") ++ EvidenceVisibility.stateParts(state) ++ channels ++
        Vector(
          s"budget:kinds:${spec.channelBudget.maxAnnotationKinds}",
          s"budget:relations:${spec.channelBudget.maxRelationLayers}",
          s"lanes:${spec.lanePolicy.maxLanesPerKind}"
        )
    )

  private def candidates(model: StoryModel[ModelStatus.Validated]): Vector[Candidate] =
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
