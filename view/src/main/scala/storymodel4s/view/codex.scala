package storymodel4s.view

import cats.{Hash, Order, Show}
import storymodel4s.core.*

/** Stable identity of one semantic annotation before pagination fragments it.
  *
  * Priority is view policy rather than identity. A compiler that produces the same target, support,
  * and kind more than once must coalesce those proposals and retain their maximum priority before
  * constructing a [[CodexFlow]].
  */
object AnnotationId extends OpaqueId("AnnotationId")
type AnnotationId = AnnotationId.T

/** Annotation families allocate and style independent visual channels. */
enum AnnotationKind(val wireName: String):
  case Feature extends AnnotationKind("feature")
  case Hierarchy extends AnnotationKind("hierarchy")
  case Entity extends AnnotationKind("entity")
  case Relation extends AnnotationKind("relation")
  case Context extends AnnotationKind("context")
  case Claim extends AnnotationKind("claim")
  case Recall extends AnnotationKind("recall")

/** Nonnegative priority used to resolve annotation-channel pressure deterministically. */
object AnnotationPriority:
  opaque type AnnotationPriority = Int

  val Default: AnnotationPriority = 0
  val Maximum: Int = 1000

  def from(value: Int): Either[DomainError, AnnotationPriority] =
    if value < 0 || value > Maximum then
      Left(
        DomainError.InvalidFormat(
          "AnnotationPriority",
          value.toString,
          s"expected an integer in [0, $Maximum]"
        )
      )
    else Right(value)

  def unsafe(value: Int): AnnotationPriority =
    from(value).fold(error => throw new IllegalArgumentException(error.message), identity)

  extension (priority: AnnotationPriority) def value: Int = priority

  given Show[AnnotationPriority] = Show.show(_.toString)
  given Order[AnnotationPriority] = Order[Int]
  given Ordering[AnnotationPriority] = Ordering.Int
  given Hash[AnnotationPriority] = Hash[Int]
type AnnotationPriority = AnnotationPriority.AnnotationPriority

/** Declared scientific basis of a rendered view, kept distinct from model validation status. */
enum ViewBasis:
  case ValidatedBuild
  case HumanAdjudicated
  case ResearcherReviewedFixture

  def label: String = this match
    case ValidatedBuild            => "validated build"
    case HumanAdjudicated          => "human-adjudicated model"
    case ResearcherReviewedFixture => "researcher-reviewed narrative acceptance fixture"

/** Reproducibility record for a view without pretending a source checksum hashes the full model. */
final case class ViewProvenance private (
    sourceChecksum: Checksum,
    modelReceiptChecksum: Option[Checksum],
    basis: ViewBasis,
    compilerVersion: String,
    configChecksum: Checksum
)

object ViewProvenance:
  def of(
      sourceChecksum: Checksum,
      modelReceiptChecksum: Option[Checksum],
      basis: ViewBasis,
      compilerVersion: String,
      configChecksum: Checksum
  ): Either[DomainError, ViewProvenance] =
    if compilerVersion.trim.isEmpty then
      Left(
        DomainError.InvalidFormat(
          "ViewProvenance.compilerVersion",
          compilerVersion,
          "empty"
        )
      )
    else
      basis match
        case ViewBasis.ResearcherReviewedFixture =>
          Right(
            new ViewProvenance(
              sourceChecksum,
              modelReceiptChecksum,
              basis,
              compilerVersion,
              configChecksum
            )
          )
        case ViewBasis.ValidatedBuild | ViewBasis.HumanAdjudicated =>
          modelReceiptChecksum match
            case Some(_) =>
              Right(
                new ViewProvenance(
                  sourceChecksum,
                  modelReceiptChecksum,
                  basis,
                  compilerVersion,
                  configChecksum
                )
              )
            case None =>
              Left(
                DomainError.InvariantViolation(
                  "view/provenance/model-receipt",
                  s"${basis.label} requires a model build receipt checksum"
                )
              )

  def fixture(
      sourceChecksum: Checksum,
      compilerVersion: String,
      configChecksum: Checksum
  ): Either[DomainError, ViewProvenance] =
    of(
      sourceChecksum,
      modelReceiptChecksum = None,
      ViewBasis.ResearcherReviewedFixture,
      compilerVersion,
      configChecksum
    )

/** Canonical audit dependencies and provenance for one annotation. */
final case class AuditRecord private (
    upstream: Vector[Address],
    provenance: Provenance
)

object AuditRecord:
  def of(upstream: Iterable[Address], provenance: Provenance): AuditRecord =
    new AuditRecord(upstream.toVector.distinct.sorted, provenance)

  def deterministic(
      softwareVersion: String,
      configChecksum: Checksum,
      upstream: Iterable[Address] = Vector.empty
  ): AuditRecord =
    of(upstream, Provenance.deterministic(softwareVersion, configChecksum))

/** One nonempty source run that references canonical text without copying it. */
final case class SourceRun private (span: TextSpan)

object SourceRun:
  def of(span: TextSpan): Either[DomainError, SourceRun] =
    if span.isEmpty then
      Left(DomainError.InvalidSpan(span.start, span.endExclusive, "source run is empty"))
    else Right(new SourceRun(span))

  def unsafe(span: TextSpan): SourceRun =
    of(span).fold(error => throw new IllegalArgumentException(error.message), identity)

/** One semantic annotation over exact support, independent of pages and rendered marks. */
final case class TextAnnotation private (
    id: AnnotationId,
    target: Address,
    support: SpanSet,
    kind: AnnotationKind,
    priority: AnnotationPriority,
    audit: AuditRecord
)

object TextAnnotation:
  def of(
      target: Address,
      support: SpanSet,
      kind: AnnotationKind,
      priority: AnnotationPriority,
      audit: AuditRecord
  ): Either[DomainError, TextAnnotation] =
    ViewRef.parse(target) match
      case None =>
        Left(
          DomainError.InvalidFormat(
            "TextAnnotation.target",
            target.render,
            "not a recognized typed view reference"
          )
        )
      case Some(_) =>
        support.refs.toVector.find(_.span.isEmpty) match
          case Some(ref) =>
            Left(
              DomainError.InvalidSpan(
                ref.span.start,
                ref.span.endExclusive,
                "annotation support is empty"
              )
            )
          case None =>
            Right(
              new TextAnnotation(
                expectedId(target, support, kind),
                target,
                support,
                kind,
                priority,
                audit
              )
            )

  def validated(
      id: AnnotationId,
      target: Address,
      support: SpanSet,
      kind: AnnotationKind,
      priority: AnnotationPriority,
      audit: AuditRecord
  ): Either[DomainError, TextAnnotation] =
    of(target, support, kind, priority, audit).flatMap { annotation =>
      if annotation.id == id then Right(annotation)
      else
        Left(
          DomainError.InvalidId(
            "AnnotationId",
            id.value,
            s"expected content address ${annotation.id.value}"
          )
        )
    }

  /** Coalesce repeated semantic proposals at maximum priority without losing audit dependencies. */
  def coalesce(
      annotations: Vector[TextAnnotation]
  ): Either[DomainError, Vector[TextAnnotation]] =
    annotations
      .groupBy(_.id)
      .toVector
      .sortBy(_._1)
      .foldLeft[Either[DomainError, Vector[TextAnnotation]]](Right(Vector.empty)) {
        case (acc, (id, group)) =>
          acc.flatMap { result =>
            val head = group.head
            val sameSemanticIdentity = group.forall(annotation =>
              annotation.target == head.target && annotation.support == head.support &&
                annotation.kind == head.kind
            )
            val provenances = group.map(_.audit.provenance).distinct
            if !sameSemanticIdentity then
              Left(
                DomainError.InvariantViolation(
                  s"view/codex/annotations/${id.value}",
                  "content-address collision across different semantic annotations"
                )
              )
            else if provenances.size != 1 then
              Left(
                DomainError.InvariantViolation(
                  s"view/codex/annotations/${id.value}/audit",
                  "duplicate semantic proposals carry incompatible provenance"
                )
              )
            else
              val priority = group.maxBy(_.priority).priority
              val upstream = group.flatMap(_.audit.upstream)
              of(
                head.target,
                head.support,
                head.kind,
                priority,
                AuditRecord.of(upstream, provenances.head)
              ).map(result :+ _)
          }
      }

  private def expectedId(
      target: Address,
      support: SpanSet,
      kind: AnnotationKind
  ): AnnotationId =
    val supportParts = support.refs.toVector.flatMap { ref =>
      Vector(
        ref.unit.fold("unit:none")(unit => s"unit:some:${unit.value}"),
        ref.span.start.toString,
        ref.span.endExclusive.toString
      )
    }
    AnnotationId.unsafe(
      ContentAddress.of("annotation", (Vector(target.render, kind.wireName) ++ supportParts)*)
    )

/** Bidirectional semantic navigation that never uses page or renderer identifiers. */
final case class NavigationIndex private (
    byTarget: Map[Address, Vector[AnnotationId]],
    targetByAnnotation: Map[AnnotationId, Address]
):
  def annotationsFor(target: Address): Vector[AnnotationId] =
    byTarget.getOrElse(target, Vector.empty)

  def targetOf(annotation: AnnotationId): Option[Address] =
    targetByAnnotation.get(annotation)

object NavigationIndex:
  val empty: NavigationIndex = new NavigationIndex(Map.empty, Map.empty)

  def from(annotations: Vector[TextAnnotation]): Either[DomainError, NavigationIndex] =
    val duplicates = annotations
      .groupMapReduce(_.id)(_ => 1)(_ + _)
      .collect { case (id, count) if count > 1 => id }
      .toVector
      .sorted
    duplicates.headOption match
      case Some(id) => Left(DomainError.DuplicateId("AnnotationId", id.value))
      case None     =>
        val byTarget = annotations
          .groupMap(_.target)(_.id)
          .view
          .mapValues(_.distinct.sorted)
          .toMap
        val reverse =
          annotations.iterator.map(annotation => annotation.id -> annotation.target).toMap
        Right(new NavigationIndex(byTarget, reverse))

/** Reflow-independent Narrative Codex source flow compiled from exact spans and addresses. */
final case class CodexFlow private (
    source: StorySource,
    runs: Vector[SourceRun],
    annotations: Vector[TextAnnotation],
    lanes: LaneAllocation,
    navigation: NavigationIndex,
    contract: CodexContract,
    provenance: ViewProvenance
):
  def text(run: SourceRun): Either[DomainError, String] =
    CodexFlow
      .validateSpan(source.canonicalText, run.span, "view/codex/text")
      .flatMap(_ => run.span.slice(source.canonicalText))

  def textualTwin: String =
    CodexTextualTwin.render(this)

object CodexFlow:
  def of(
      source: StorySource,
      runs: Vector[SourceRun],
      annotations: Vector[TextAnnotation],
      provenance: ViewProvenance
  ): Either[DomainError, CodexFlow] =
    of(
      source,
      runs,
      annotations,
      LanePolicy.Default,
      CodexContract.foundation(annotations),
      provenance
    )

  /** Construct a semantic flow under an explicit lane and view contract. */
  def of(
      source: StorySource,
      runs: Vector[SourceRun],
      annotations: Vector[TextAnnotation],
      lanePolicy: LanePolicy,
      contract: CodexContract,
      provenance: ViewProvenance
  ): Either[DomainError, CodexFlow] =
    val orderedRuns = runs.sortBy(_.span)
    val orderedAnnotations = annotations.sortBy(annotationSortKey)
    for
      _ <- validateProvenance(source, provenance)
      _ <- validateRuns(source.canonicalText, orderedRuns)
      _ <- validateAnnotations(source.canonicalText, orderedAnnotations)
      _ <- validateContract(orderedAnnotations, lanePolicy, contract)
      navigation <- NavigationIndex.from(orderedAnnotations)
      lanes <- LaneAllocation.allocate(orderedAnnotations, lanePolicy)
    yield new CodexFlow(
      source,
      orderedRuns,
      orderedAnnotations,
      lanes,
      navigation,
      contract,
      provenance
    )

  def exact(
      source: StorySource,
      annotations: Vector[TextAnnotation],
      provenance: ViewProvenance
  ): Either[DomainError, CodexFlow] =
    exact(
      source,
      annotations,
      LanePolicy.Default,
      CodexContract.foundation(annotations),
      provenance
    )

  /** Construct an exact-source flow under an explicit lane and view contract. */
  def exact(
      source: StorySource,
      annotations: Vector[TextAnnotation],
      lanePolicy: LanePolicy,
      contract: CodexContract,
      provenance: ViewProvenance
  ): Either[DomainError, CodexFlow] =
    for
      span <- TextSpan.of(0, source.canonicalText.length)
      run <- SourceRun.of(span)
      flow <- of(source, Vector(run), annotations, lanePolicy, contract, provenance)
    yield flow

  private def annotationSortKey(
      annotation: TextAnnotation
  ): (Int, Int, String, Int, String) =
    val span = annotation.support.minSpan
    (
      span.start,
      span.endExclusive,
      annotation.kind.wireName,
      -annotation.priority.value,
      annotation.id.value
    )

  private def validateProvenance(
      source: StorySource,
      provenance: ViewProvenance
  ): Either[DomainError, Unit] =
    if source.canonicalChecksum == provenance.sourceChecksum then Right(())
    else
      Left(
        DomainError.InvariantViolation(
          "view/provenance/source-checksum",
          s"${provenance.sourceChecksum.hex} does not match ${source.canonicalChecksum.hex}"
        )
      )

  private def validateContract(
      annotations: Vector[TextAnnotation],
      lanePolicy: LanePolicy,
      contract: CodexContract
  ): Either[DomainError, Unit] =
    val undeclared = annotations.iterator.map(_.kind).toSet -- contract.activeKinds
    if undeclared.nonEmpty then
      Left(
        DomainError.InvariantViolation(
          "view/codex/contract/annotation-kinds",
          s"annotations use undeclared kinds ${undeclared.toVector.map(_.wireName).sorted.mkString(",")}"
        )
      )
    else if lanePolicy != contract.lanePolicy then
      Left(
        DomainError.InvariantViolation(
          "view/codex/contract/lane-policy",
          "allocation policy does not match the declared Codex contract"
        )
      )
    else Right(())

  private def validateRuns(text: String, runs: Vector[SourceRun]): Either[DomainError, Unit] =
    if runs.isEmpty then
      Left(DomainError.InvariantViolation("view/codex/runs", "source flow has no runs"))
    else
      var expectedStart = 0
      var index = 0
      var problem: Option[DomainError] = None
      while index < runs.length && problem.isEmpty do
        val span = runs(index).span
        validateSpan(text, span, s"view/codex/runs/$index") match
          case Left(error) => problem = Some(error)
          case Right(_)    =>
            if span.start != expectedStart then
              problem = Some(
                DomainError.InvariantViolation(
                  s"view/codex/runs/$index",
                  s"expected start $expectedStart, found ${span.start}"
                )
              )
            else expectedStart = span.endExclusive
        index += 1
      problem match
        case Some(error)                          => Left(error)
        case None if expectedStart != text.length =>
          Left(
            DomainError.InvariantViolation(
              "view/codex/runs",
              s"runs end at $expectedStart, canonical text ends at ${text.length}"
            )
          )
        case None => Right(())

  private def validateAnnotations(
      text: String,
      annotations: Vector[TextAnnotation]
  ): Either[DomainError, Unit] =
    var annotationIndex = 0
    var problem: Option[DomainError] = None
    while annotationIndex < annotations.length && problem.isEmpty do
      val annotation = annotations(annotationIndex)
      val refs = annotation.support.refs.toVector
      var refIndex = 0
      while refIndex < refs.length && problem.isEmpty do
        validateSpan(
          text,
          refs(refIndex).span,
          s"view/codex/annotations/${annotation.id.value}/support/$refIndex"
        ) match
          case Left(error) => problem = Some(error)
          case Right(_)    => ()
        refIndex += 1
      annotationIndex += 1
    problem.toLeft(())

  private def validateSpan(
      text: String,
      span: TextSpan,
      path: String
  ): Either[DomainError, Unit] =
    if span.isEmpty then
      Left(DomainError.InvalidSpan(span.start, span.endExclusive, s"$path is empty"))
    else if span.endExclusive > text.length then
      Left(
        DomainError.InvalidSpan(
          span.start,
          span.endExclusive,
          s"$path exceeds canonical text length ${text.length}"
        )
      )
    else if !isCodePointBoundary(text, span.start) then
      Left(
        DomainError.InvalidSpan(
          span.start,
          span.endExclusive,
          s"$path starts inside a UTF-16 surrogate pair"
        )
      )
    else if !isCodePointBoundary(text, span.endExclusive) then
      Left(
        DomainError.InvalidSpan(
          span.start,
          span.endExclusive,
          s"$path ends inside a UTF-16 surrogate pair"
        )
      )
    else Right(())

  private def isCodePointBoundary(text: String, offset: Int): Boolean =
    offset >= 0 && offset <= text.length &&
      (offset == 0 || offset == text.length ||
        !(Character.isHighSurrogate(text.charAt(offset - 1)) &&
          Character.isLowSurrogate(text.charAt(offset))))

/** Deterministic plain-text representation used for audit, accessibility, and snapshots. */
object CodexTextualTwin:
  def render(flow: CodexFlow): String =
    val out = new StringBuilder
    out.append("Narrative Codex\n")
    out.append("Story: ").append(flow.source.title.getOrElse(flow.source.id.value)).append('\n')
    out.append("Basis: ").append(flow.provenance.basis.label).append('\n')
    out.append("Source checksum: ").append(flow.provenance.sourceChecksum.hex).append('\n')
    out
      .append("Model receipt checksum: ")
      .append(flow.provenance.modelReceiptChecksum.fold("not available")(_.hex))
      .append('\n')
    out.append("Compiler: ").append(flow.provenance.compilerVersion).append('\n')
    out.append("Configuration: ").append(flow.provenance.configChecksum.hex).append("\n\n")
    out
      .append("Horizon: ")
      .append(
        flow.contract.horizon match
          case EpistemicHorizon.Omniscient       => "omniscient"
          case EpistemicHorizon.ReaderAt(offset) => s"reader-at-$offset"
      )
      .append('\n')
    out.append("Scale: ").append(flow.contract.scale.label).append('\n')
    out
      .append("Feature selection: ")
      .append(renderFeatureSelection(flow.contract.feature))
      .append('\n')
    out
      .append("Resolved feature space: ")
      .append(flow.contract.feature.resolvedSpaceId.fold("none")(_.value))
      .append('\n')
    out
      .append("Feature channel: ")
      .append(renderFeatureState(flow.contract.feature))
      .append('\n')
    out
      .append("Channels: ")
      .append(flow.contract.activeKinds.toVector.map(_.wireName).sorted.mkString(","))
      .append('\n')
    out
      .append("Lane allocator: ")
      .append(flow.lanes.receipt.algorithm.toString)
      .append(" v")
      .append(flow.lanes.receipt.algorithmVersion)
      .append(" max-per-kind=")
      .append(flow.lanes.receipt.policy.maxLanesPerKind)
      .append(" overflow=")
      .append(flow.lanes.overflow.size)
      .append("\n\n")
    out.append("Exact canonical text\n---\n")
    // The textual twin is a rendered accessibility/snapshot form, not copied CodexFlow source data.
    out.append(flow.source.canonicalText).append("\n---\n\n")
    out.append("Annotations\n")
    if flow.annotations.isEmpty then out.append("(none)\n")
    else flow.annotations.foreach(annotation => renderAnnotation(annotation, flow.lanes, out))
    out.result()

  private def renderFeatureSelection(state: FeatureChannelState): String = state match
    case FeatureChannelState.NotRequested                     => "none"
    case FeatureChannelState.Unresolved(selection, _)         => selection.canonicalString
    case FeatureChannelState.Missing(selection, _)            => selection.canonicalString
    case FeatureChannelState.SidecarRequired(selection, _, _) => selection.canonicalString

  private def renderFeatureState(state: FeatureChannelState): String = state match
    case FeatureChannelState.NotRequested         => "not-requested"
    case FeatureChannelState.Unresolved(_, issue) => s"unresolved:${issue.canonicalString}"
    case FeatureChannelState.Missing(_, _)        => "missing"
    case FeatureChannelState.SidecarRequired(_, _, observationCount) =>
      s"sidecar-required:observations=$observationCount"

  private def renderAnnotation(
      annotation: TextAnnotation,
      lanes: LaneAllocation,
      out: StringBuilder
  ): Unit =
    val spans = annotation.support.refs.toVector.map { ref =>
      val unit = ref.unit.fold("")(id => s"@${id.value}")
      s"[${ref.span.start},${ref.span.endExclusive})$unit"
    }
    out
      .append("- ")
      .append(annotation.id.value)
      .append(" ")
      .append(annotation.kind.wireName)
      .append(" target=")
      .append(annotation.target.render)
      .append(" support=")
      .append(spans.mkString(","))
      .append(" priority=")
      .append(annotation.priority.value)
      .append(" lane=")
      .append(renderLane(lanes.slotOf(annotation.id)))
      .append('\n')
    out
      .append("  upstream=")
      .append(annotation.audit.upstream.map(_.render).mkString(","))
      .append('\n')
    renderProvenance(annotation.audit.provenance, out)

  private def renderLane(slot: Option[LaneSlot]): String = slot match
    case Some(LaneSlot.Lane(index)) => index.value.toString
    case Some(LaneSlot.Overflow)    => "overflow"
    case None                       => "unallocated"

  private def renderProvenance(provenance: Provenance, out: StringBuilder): Unit =
    out
      .append("  provenance software=")
      .append(provenance.softwareVersion)
      .append(" config=")
      .append(provenance.configHash.hex)
      .append('\n')
    provenance.calls.zipWithIndex.foreach { case (call, index) =>
      out
        .append("  call[")
        .append(index)
        .append("] provider=")
        .append(call.provider)
        .append(" model=")
        .append(call.model)
        .append(" version=")
        .append(call.version)
        .append(" input=")
        .append(call.inputChecksum.hex)
        .append(" output=")
        .append(call.outputChecksum.hex)
        .append(" cached=")
        .append(call.cached)
        .append(" params=")
        .append(call.params.toVector.sorted.map((key, value) => s"$key=$value").mkString(","))
        .append('\n')
    }
