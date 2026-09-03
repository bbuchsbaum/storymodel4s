package storymodel4s.view

import cats.{Hash, Order, Show}
import cats.data.NonEmptyVector
import storymodel4s.core.*

/** Stable identity of one semantic annotation before pagination fragments it.
  *
  * Priority is view policy rather than identity. A compiler that produces the same target, support,
  * and kind more than once must coalesce those proposals and retain their maximum priority before
  * constructing a [[CodexFlow]].
  */
object AnnotationId extends OpaqueId("AnnotationId")
type AnnotationId = AnnotationId.T

/** Annotation families allocate and style independent visual channels.
  *
  * The last three are the draft build's disclosure channels. They are in this closed enum rather
  * than smuggled through a free-form tag because a reader must be able to tell a failure from a
  * finding at a glance and because a channel nothing can enumerate is a channel a renderer can
  * silently omit — which would put the unmarked prose back, and with it the reader's assumption
  * that the model understood it.
  */
enum AnnotationKind(val wireName: String):
  case Feature extends AnnotationKind("feature")
  case Hierarchy extends AnnotationKind("hierarchy")
  case Entity extends AnnotationKind("entity")
  case Relation extends AnnotationKind("relation")
  case Context extends AnnotationKind("context")
  case Claim extends AnnotationKind("claim")
  case Recall extends AnnotationKind("recall")

  /** A claim family the compiler nominated at a chart node and could not derive. */
  case Gap extends AnnotationKind("gap")

  /** A sentence the provider abstained on, so no situation was read out of these words. */
  case Abstention extends AnnotationKind("abstention")

  /** A promotion law this model does not satisfy, drawn on the words its subject cites. */
  case UnsatisfiedLaw extends AnnotationKind("unsatisfied-law")

  /** True for the three channels that exist only to disclose a draft's own incompleteness.
    *
    * Total by construction rather than a set literal, so a new kind cannot be added without the
    * author deciding, here, which side of the line it falls on.
    */
  def marksAbsence: Boolean = this match
    case Gap | Abstention | UnsatisfiedLaw                                  => true
    case Feature | Hierarchy | Entity | Relation | Context | Claim | Recall => false

object AnnotationKind:
  /** The channels every draft reading view declares, whether or not it has anything to put in one.
    *
    * A declared empty channel says "no sentence was abstained on"; an undeclared one says nothing,
    * and a reader cannot tell the second from a renderer that dropped it. That is the same
    * distinction [[DerivationRecord.NotSupplied]] draws against a count of zero.
    */
  val absence: Set[AnnotationKind] = values.iterator.filter(_.marksAbsence).toSet

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

  /** A `StoryModel[Draft]` rendered with its own gaps, abstentions and unsatisfied promotion laws.
    *
    * Why a basis and not a flag on the scene: a draft view differs from a validated one in what it
    * is entitled to claim, and that entitlement is exactly what a basis records. A flag would sit
    * beside a basis that still said "validated build", which is the shape of every defect this
    * project has been correcting. A draft build has no [[BasisAuthority]] and therefore cannot
    * become an [[AdmittedViewBasis]]: it is legible, cited and reproducible, and it is not
    * admissible as the basis of a scientific output.
    */
  case DraftBuild

  /** An aligner's posterior over a source view, drawn as the raw quantity it is (M1 L6 `raw`).
    *
    * Why a basis of its own: a recall alignment is neither a promoted story model nor a draft of
    * one; it is a second process scored against a source, and nothing in it is a claim about the
    * story. A view of it must not read "validated build", and reading "draft build" would promise a
    * promotion record no aligner produces. It carries no [[BasisAuthority]].
    */
  case AlignmentRun

  def label: String = this match
    case ValidatedBuild            => "validated build"
    case HumanAdjudicated          => "human-adjudicated model"
    case ResearcherReviewedFixture => "researcher-reviewed narrative acceptance fixture"
    case DraftBuild                => "draft build"
    case AlignmentRun              => "aligner run, raw posterior"

/** Reproducibility record for a view without pretending a source checksum hashes the full model.
  *
  * `draft` and `basis` are one statement, not two: construction admits a [[DraftPromotion]] only
  * under [[ViewBasis.DraftBuild]] and requires one there, so no receipt can read "validated build"
  * beside a promotion record, and none can read "draft build" while staying silent about which laws
  * went unsatisfied.
  */
final case class ViewProvenance private (
    sourceChecksum: Checksum,
    modelReceiptChecksum: Option[Checksum],
    basis: ViewBasis,
    compilerVersion: String,
    configChecksum: Checksum,
    draft: Option[DraftPromotion]
)

object ViewProvenance:
  def of(
      sourceChecksum: Checksum,
      modelReceiptChecksum: Option[Checksum],
      basis: ViewBasis,
      compilerVersion: String,
      configChecksum: Checksum,
      draft: Option[DraftPromotion] = None
  ): Either[DomainError, ViewProvenance] =
    def built(): ViewProvenance =
      new ViewProvenance(
        sourceChecksum,
        modelReceiptChecksum,
        basis,
        compilerVersion,
        configChecksum,
        draft
      )
    if compilerVersion.trim.isEmpty then
      Left(
        DomainError.InvalidFormat(
          "ViewProvenance.compilerVersion",
          compilerVersion,
          "empty"
        )
      )
    else if draft.isDefined != (basis == ViewBasis.DraftBuild) then
      Left(
        DomainError.InvariantViolation(
          "view/provenance/draft-basis",
          s"a draft promotion record and ${ViewBasis.DraftBuild.label} accompany each other; " +
            s"${basis.label} was given ${if draft.isDefined then "one" else "none"}"
        )
      )
    else
      basis match
        case ViewBasis.ResearcherReviewedFixture | ViewBasis.DraftBuild | ViewBasis.AlignmentRun =>
          Right(built())
        case ViewBasis.ValidatedBuild | ViewBasis.HumanAdjudicated =>
          modelReceiptChecksum match
            case Some(_) => Right(built())
            case None    =>
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

  /** Bind a draft view to the promotion state of the exact bundle it will render.
    *
    * Why derived from the bundle: the promotion record is a claim about that draft's own outcome,
    * and a caller who could type it separately could publish a scene declaring a clean promotion
    * over a model that has none.
    */
  def draftBuild(
      draft: DraftModel,
      compilerVersion: String,
      configChecksum: Checksum
  ): Either[DomainError, ViewProvenance] =
    of(
      draft.model.source.canonicalChecksum,
      draft.model.receipt.map(_.contentChecksum),
      ViewBasis.DraftBuild,
      compilerVersion,
      configChecksum,
      Some(draft.promotion)
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

/** One semantic annotation over exact support, independent of pages and rendered marks.
  *
  * `absence` and `kind` are one statement, not two: it is `Some` exactly when the kind is one of
  * the draft disclosure channels, and it then carries the producer's own record of the failure. So
  * a bare "gap" annotation that names no family cannot be minted, and a claim annotation cannot be
  * dressed as a failure. It also separates two failures that concern the same words, which a
  * target/kind/support content address alone would coalesce into one mark.
  */
final case class TextAnnotation private (
    id: AnnotationId,
    target: Address,
    support: SpanSet,
    kind: AnnotationKind,
    priority: AnnotationPriority,
    audit: AuditRecord,
    absence: Option[DraftAbsence]
)

object TextAnnotation:
  def of(
      target: Address,
      support: SpanSet,
      kind: AnnotationKind,
      priority: AnnotationPriority,
      audit: AuditRecord,
      absence: Option[DraftAbsence] = None
  ): Either[DomainError, TextAnnotation] =
    for
      _ <- checkTarget(target)
      _ <- checkAbsence(kind, absence)
      _ <- checkSupport(support)
    yield new TextAnnotation(
      expectedId(target, support, kind, absence),
      target,
      support,
      kind,
      priority,
      audit,
      absence
    )

  private def checkTarget(target: Address): Either[DomainError, Unit] =
    ViewRef.parse(target) match
      case Some(_) => Right(())
      case None    =>
        Left(
          DomainError.InvalidFormat(
            "TextAnnotation.target",
            target.render,
            "not a recognized typed view reference"
          )
        )

  private def checkSupport(support: SpanSet): Either[DomainError, Unit] =
    support.refs.toVector.find(_.span.isEmpty) match
      case None      => Right(())
      case Some(ref) =>
        Left(
          DomainError.InvalidSpan(
            ref.span.start,
            ref.span.endExclusive,
            "annotation support is empty"
          )
        )

  private def checkAbsence(
      kind: AnnotationKind,
      absence: Option[DraftAbsence]
  ): Either[DomainError, Unit] = absence match
    case Some(detail) if detail.kind != kind =>
      Left(
        DomainError.InvariantViolation(
          "view/codex/annotations/absence-kind",
          s"a ${detail.kind.wireName} absence cannot be drawn on the ${kind.wireName} channel"
        )
      )
    case None if kind.marksAbsence =>
      Left(
        DomainError.InvariantViolation(
          "view/codex/annotations/absence-content",
          s"the ${kind.wireName} channel draws a failure and must carry the record of one"
        )
      )
    case _ => Right(())

  def validated(
      id: AnnotationId,
      target: Address,
      support: SpanSet,
      kind: AnnotationKind,
      priority: AnnotationPriority,
      audit: AuditRecord,
      absence: Option[DraftAbsence] = None
  ): Either[DomainError, TextAnnotation] =
    of(target, support, kind, priority, audit, absence).flatMap { annotation =>
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
                annotation.kind == head.kind && annotation.absence == head.absence
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
                AuditRecord.of(upstream, provenances.head),
                head.absence
              ).map(result :+ _)
          }
      }

  /** The absence key is appended, never interleaved, so an ordinary annotation's content address is
    * exactly what it was before the draft channels existed and no validated flow's identities move.
    */
  private def expectedId(
      target: Address,
      support: SpanSet,
      kind: AnnotationKind,
      absence: Option[DraftAbsence]
  ): AnnotationId =
    val supportParts = support.refs.toVector.flatMap { ref =>
      Vector(
        ref.unit.fold("unit:none")(unit => s"unit:some:${unit.value}"),
        ref.span.start.toString,
        ref.span.endExclusive.toString
      )
    }
    val absenceParts = absence.toVector.map(detail => s"absence:${detail.key}")
    AnnotationId.unsafe(
      ContentAddress.of(
        "annotation",
        (Vector(target.render, kind.wireName) ++ supportParts ++ absenceParts)*
      )
    )

/** Bidirectional semantic navigation that never uses page or renderer identifiers. */
final case class NavigationIndex private (
    byTarget: Map[Address, Vector[AnnotationId]],
    targetByAnnotation: Map[AnnotationId, Address],
    ancestorsByTarget: Map[Address, Vector[Address]]
):
  /** Find annotations whose target is exactly this address, without ancestor fallback. */
  def exactAnnotationsFor(target: Address): Vector[AnnotationId] =
    byTarget.getOrElse(target, Vector.empty)

  /** Find exact annotations or, when absent, those of the nearest annotated primary ancestor. */
  def annotationsFor(target: Address): Vector[AnnotationId] =
    resolutionFor(target).fold(Vector.empty)(_._2.toVector)

  def targetOf(annotation: AnnotationId): Option[Address] =
    targetByAnnotation.get(annotation)

  private[view] def placementFor(target: Address): SelectionPlacement[AnnotationId] =
    resolutionFor(target) match
      case Some((resolved, annotations)) if resolved == target =>
        SelectionPlacement.OnMark(annotations)
      case Some((resolved, _)) => SelectionPlacement.ViaAncestor(resolved)
      case None                => SelectionPlacement.OffProjection

  private def resolutionFor(
      target: Address
  ): Option[(Address, NonEmptyVector[AnnotationId])] =
    (target +: ancestorsByTarget.getOrElse(target, Vector.empty)).iterator
      .flatMap(address =>
        byTarget
          .get(address)
          .flatMap(NonEmptyVector.fromVector)
          .map(address -> _)
      )
      .nextOption()

object NavigationIndex:
  val empty: NavigationIndex = new NavigationIndex(Map.empty, Map.empty, Map.empty)

  def from(annotations: Vector[TextAnnotation]): Either[DomainError, NavigationIndex] =
    from(annotations, Map.empty)

  private[view] def from(
      annotations: Vector[TextAnnotation],
      ancestorsByTarget: Map[Address, Vector[Address]]
  ): Either[DomainError, NavigationIndex] =
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
        val ancestors = ancestorsByTarget.map { (target, chain) =>
          target -> chain.filterNot(_ == target).distinct
        }
        Right(new NavigationIndex(byTarget, reverse, ancestors))

/** Reflow-independent Narrative Codex source flow compiled from exact spans and addresses.
  *
  * `draft` is `Some` exactly when the receipt declares [[ViewBasis.DraftBuild]], and its `marked`
  * ids are exactly the flow's absence-bearing annotations. A validated flow therefore cannot carry
  * a disclosure and a draft flow cannot omit one, which is the same biconditional
  * [[ViewProvenance]] already enforces between the basis and the promotion record.
  */
final case class CodexFlow private (
    source: StorySource,
    runs: Vector[SourceRun],
    annotations: Vector[TextAnnotation],
    lanes: LaneAllocation,
    navigation: NavigationIndex,
    selectionPlacements: Map[Address, SelectionPlacement[AnnotationId]],
    contract: CodexContract,
    provenance: ViewProvenance,
    draft: Option[DraftAbsenceLedger]
):
  def text(run: SourceRun): Either[DomainError, String] =
    CodexFlow
      .validateSpan(source.canonicalText, run.span, "view/codex/text")
      .flatMap(_ => run.span.slice(source.canonicalText))

  def textualTwin: String =
    CodexTextualTwin.render(this)

object CodexFlow:
  /** Construct a semantic flow without model ancestry; missing targets remain off-projection. */
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

  /** Construct a semantic flow under an explicit contract; only a compiler-built flow can place a
    * missing target via an ancestor because an external flow has no model hierarchy.
    */
  def of(
      source: StorySource,
      runs: Vector[SourceRun],
      annotations: Vector[TextAnnotation],
      lanePolicy: LanePolicy,
      contract: CodexContract,
      provenance: ViewProvenance
  ): Either[DomainError, CodexFlow] =
    compiled(
      source,
      runs,
      annotations,
      lanePolicy,
      contract,
      Map.empty,
      provenance,
      draft = None
    )

  private def compiled(
      source: StorySource,
      runs: Vector[SourceRun],
      annotations: Vector[TextAnnotation],
      lanePolicy: LanePolicy,
      contract: CodexContract,
      ancestorsByTarget: Map[Address, Vector[Address]],
      provenance: ViewProvenance,
      draft: Option[DraftAbsenceLedger]
  ): Either[DomainError, CodexFlow] =
    val orderedRuns = runs.sortBy(_.span)
    val orderedAnnotations = annotations.sortBy(annotationSortKey)
    for
      _ <- validateProvenance(source, provenance)
      _ <- validateDraft(provenance, draft, orderedAnnotations)
      _ <- validateRuns(source.canonicalText, orderedRuns)
      _ <- validateAnnotations(source.canonicalText, orderedAnnotations)
      _ <- validateContract(orderedAnnotations, lanePolicy, contract)
      navigation <- NavigationIndex.from(orderedAnnotations, ancestorsByTarget)
      lanes <- LaneAllocation.allocate(orderedAnnotations, lanePolicy)
      placements = (contract.selection ++ contract.focus).toVector
        .sortBy(_.render)
        .map(address => address -> navigation.placementFor(address))
        .toMap
    yield new CodexFlow(
      source,
      orderedRuns,
      orderedAnnotations,
      lanes,
      navigation,
      placements,
      contract,
      provenance,
      draft.map(ledger => ledger.copy(marked = ledger.marked.sorted))
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

  private[view] def compiledExact(
      source: StorySource,
      annotations: Vector[TextAnnotation],
      lanePolicy: LanePolicy,
      contract: CodexContract,
      ancestorsByTarget: Map[Address, Vector[Address]],
      provenance: ViewProvenance,
      draft: Option[DraftAbsenceLedger] = None
  ): Either[DomainError, CodexFlow] =
    for
      span <- TextSpan.of(0, source.canonicalText.length)
      run <- SourceRun.of(span)
      flow <- compiled(
        source,
        Vector(run),
        annotations,
        lanePolicy,
        contract,
        ancestorsByTarget,
        provenance,
        draft
      )
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

  /** A draft flow discloses every absence it carries; no other flow may carry one at all. */
  private def validateDraft(
      provenance: ViewProvenance,
      draft: Option[DraftAbsenceLedger],
      annotations: Vector[TextAnnotation]
  ): Either[DomainError, Unit] =
    val marks = annotations.filter(_.absence.isDefined).map(_.id).sorted
    if draft.isDefined != (provenance.basis == ViewBasis.DraftBuild) then
      Left(
        DomainError.InvariantViolation(
          "view/codex/draft/basis",
          s"an absence ledger and ${ViewBasis.DraftBuild.label} accompany each other; " +
            s"${provenance.basis.label} was given ${if draft.isDefined then "one" else "none"}"
        )
      )
    else
      draft match
        case None if marks.nonEmpty =>
          Left(
            DomainError.InvariantViolation(
              "view/codex/draft/absence-annotations",
              s"${marks.size} absence annotations under ${provenance.basis.label}"
            )
          )
        case Some(ledger) if ledger.marked.sorted != marks =>
          Left(
            DomainError.InvariantViolation(
              "view/codex/draft/ledger",
              s"the ledger marks ${ledger.marked.size} absences and the flow carries ${marks.size}"
            )
          )
        case _ => Right(())

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
    renderPromotion(flow, out)
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
    if flow.selectionPlacements.nonEmpty then
      out.append("Selection placements\n")
      flow.selectionPlacements.toVector
        .sortBy(_._1.render)
        .foreach { (address, placement) =>
          val rendered = placement match
            case SelectionPlacement.OnMark(annotations) =>
              s"on-mark(${annotations.toVector.map(_.value).mkString(",")})"
            case SelectionPlacement.ViaAncestor(ancestor) =>
              s"via-ancestor(${ancestor.render})"
            case SelectionPlacement.OffProjection => "off-projection"
          out.append("- ").append(address.render).append(" -> ").append(rendered).append('\n')
        }
      out.append('\n')
    out.append("Exact canonical text\n---\n")
    // The textual twin is a rendered accessibility/snapshot form, not copied CodexFlow source data.
    out.append(flow.source.canonicalText).append("\n---\n\n")
    out.append("Annotations\n")
    if flow.annotations.isEmpty then out.append("(none)\n")
    else flow.annotations.foreach(annotation => renderAnnotation(annotation, flow.lanes, out))
    renderUnplaced(flow, out)
    out.result()

  /** The promotion block a draft carries, word for word the one the Atlas twin prints. */
  private def renderPromotion(flow: CodexFlow, out: StringBuilder): Unit =
    flow.provenance.draft.foreach { promotion =>
      out.append("Draft promotion\n")
      out
        .append("  promotable: ")
        .append(promotion.promoted)
        .append("; derivation gaps: ")
        .append(promotion.gapCount.fold("record not supplied")(_.toString))
        .append("; violations: ")
        .append(promotion.violationCount)
        .append('\n')
      if promotion.unsatisfiedLaws.isEmpty then out.append("  unsatisfied laws: (none)\n")
      else
        out.append("  unsatisfied laws\n")
        promotion.unsatisfiedLaws.foreach(law =>
          out
            .append("  - ")
            .append(law.law)
            .append(' ')
            .append(law.severity)
            .append(" x")
            .append(law.count.value)
            .append('\n')
        )
    }

  /** The absences that concern no words, listed so the twin accounts for every one the draft has.
    */
  private def renderUnplaced(flow: CodexFlow, out: StringBuilder): Unit =
    flow.draft.foreach { ledger =>
      out.append("\nUnplaced absences\n")
      if ledger.unplaced.isEmpty then out.append("(none)\n")
      else
        ledger.unplaced.foreach { entry =>
          out
            .append("- ")
            .append(entry.subject.render)
            .append(' ')
            .append(entry.absence.render)
            .append(" channel=")
            .append(entry.absence.channel)
            .append(" at=unplaced:")
            .append(entry.reason.render)
            .append('\n')
        }
    }

  private def renderFeatureSelection(state: FeatureChannelState): String =
    state.selectedFeature.fold("none")(_.canonicalString)

  private def renderFeatureState(state: FeatureChannelState): String = state.canonicalString

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
    annotation.absence.foreach { absence =>
      out
        .append("  absence=")
        .append(absence.render)
        .append(" state=")
        .append(absence.uncertainty.fold("-")(_.toString))
        .append(" channel=")
        .append(absence.channel)
        .append('\n')
    }
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
