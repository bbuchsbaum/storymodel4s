package storymodel4s.align

import storymodel4s.core.*
import storymodel4s.proposition.GraphOrder
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Rendering refusals carry no submitted text or plain digest of it. */
enum SurfaceRenderingRefusal:
  case UnknownUnit, UnknownTarget, SourceIdentity, SourceViewBinding, PhysicalSupport
  case UnsupportedSupport, InvalidUnicode, InvalidSpan, InvalidAnchor, InvalidHierarchy
  case EmptyRecall, EmptySource
  case Projection(reason: storymodel4s.proposition.ProjectionRefusal)

/** Exact rendered strings accompany content without handing coordinates back to a scorer. */
type RenderedContentPair = SurfaceRendering.Pair

/** A query endpoint holds content/text without the original recall identifier or coordinates. */
type RenderedUnitInput = SurfaceRendering.UnitInput

/** A document endpoint holds content/text without its source reference or coordinates. */
type RenderedTargetInput = SurfaceRendering.TargetInput

/** One complete checked inventory binds rendering and orchestration before provider requests. */
type SurfaceScoringContext = SurfaceRendering.Context

/** Library-owned surface rendering joins declared source identity to actual canonical text. It
  * establishes a checked text/view join, not acquisition authority or provider execution.
  */
object SurfaceRendering:
  /** Versioned policies preserve recall hull text and avoid filling gaps in source support. */
  val Policy: String = "surface-render/recall-hull-source-union-newline/v1"

  /** Timed descriptions render their checked encoder payloads without manufacturing text support.
    */
  val TimedPolicy: String = "surface-render/timed-source-encoder-payload/v1"

  /** Unforgeable query payloads keep exact text separate from coordinate-bearing originals. */
  final class UnitInput private[SurfaceRendering] (
      val content: UnitContent[GraphOrder.Canonical],
      val text: String,
      val policy: String = Policy
  ):
    override def equals(other: Any): Boolean = other match
      case p: UnitInput => content == p.content && text == p.text && policy == p.policy
      case _            => false
    override def hashCode: Int = content.hashCode
    override def toString: String = s"RenderedUnitInput(chars=${text.length})"

  /** Unforgeable document payloads keep exact text separate from source references. */
  final class TargetInput private[SurfaceRendering] (
      val content: TargetContent[GraphOrder.Canonical],
      val text: String,
      val policy: String = Policy
  ):
    override def equals(other: Any): Boolean = other match
      case p: TargetInput => content == p.content && text == p.text && policy == p.policy
      case _              => false
    override def hashCode: Int = content.hashCode
    override def toString: String = s"RenderedTargetInput(chars=${text.length})"

  /** A checked scoring pair distinguishes equal semantic content with different submitted text. */
  final class Pair private[SurfaceRendering] (
      val unit: UnitContent[GraphOrder.Canonical],
      val target: TargetContent[GraphOrder.Canonical],
      val grain: ContentGrain,
      val unitText: String,
      val targetText: String,
      val policy: String = Policy
  ):
    lazy val query: RenderedUnitInput = new UnitInput(unit, unitText, policy)
    lazy val document: RenderedTargetInput = new TargetInput(target, targetText, policy)
    private def parts = (unit, target, grain, unitText, targetText, policy)
    override def equals(other: Any): Boolean = other match
      case p: Pair => parts == p.parts
      case _       => false
    // Exact equality retains text; the public hash does not add a text-derived digest.
    override def hashCode: Int = (unit, target, grain).hashCode
    override def toString: String =
      s"RenderedContentPair(unitChars=${unitText.length}, targetChars=${targetText.length})"

  /** Complete declared populations exclude caller-selected coordinate retrieval. Coordinates stay
    * in this orchestration envelope; the channel receives only UnitInput/TargetInput/Pair values.
    */
  final class Context private[SurfaceRendering] (
      val recall: RecallGraph[Checked],
      val source: SourceView,
      val representation: SourceRepresentation,
      private val sourceIdentity: Either[SurfaceAtlas, bridge.TimedSourceView],
      val queries: Vector[(RecallUnitId, RenderedUnitInput)],
      val documents: Vector[(SourceNodeRef, RenderedTargetInput)],
      val policy: String = Policy
  ):
    val grain: ContentGrain = new ContentGrain(source.maxLevel)
    private val binding = StrictBinding.of(recall, source)
    private val queryIndex = queries.toMap
    private val documentIndex = documents.toMap

    /** Compare full recall/view binding once at a public generation/evidence entrypoint. */
    def binds(otherRecall: RecallGraph[Checked], otherSource: SourceView): Boolean =
      binding == StrictBinding.of(otherRecall, MappingBindingRender.snapshot(otherSource))

    private[align] def contains(unit: RecallUnit, node: NodeSummary): Boolean =
      recall.byId.get(unit.id).contains(unit) && source.node(node.ref).contains(node)

    def pair(
        unit: RecallUnitId,
        target: SourceNodeRef
    ): Either[SurfaceRenderingRefusal, RenderedContentPair] =
      for
        q <- queryIndex.get(unit).toRight(SurfaceRenderingRefusal.UnknownUnit)
        d <- documentIndex.get(target).toRight(SurfaceRenderingRefusal.UnknownTarget)
      yield new Pair(q.content, d.content, grain, q.text, d.text, policy)

    override def equals(other: Any): Boolean = other match
      case c: Context =>
        binding == c.binding && representation.digest == c.representation.digest &&
        sourceIdentity == c.sourceIdentity && queries == c.queries && documents == c.documents &&
        policy == c.policy
      case _ => false
    // No new hash of private submitted strings or endpoint vectors.
    override def hashCode: Int = (queries.size, documents.size, grain, policy).hashCode
    override def toString: String =
      s"SurfaceScoringContext(queries=${queries.size}, documents=${documents.size})"

  /** Prepare every declared unit and node once, retaining no dense unit/target pair matrix. */
  def prepare(
      recall: RecallGraph[Checked],
      view: SourceView,
      representation: SourceRepresentation,
      sourceAtlas: SurfaceAtlas
  ): Either[SurfaceRenderingRefusal, SurfaceScoringContext] =
    val snapshot = MappingBindingRender.snapshot(view)
    for
      _ <- if recall.units.nonEmpty then Right(()) else Left(SurfaceRenderingRefusal.EmptyRecall)
      _ <- if snapshot.nodes.nonEmpty then Right(()) else Left(SurfaceRenderingRefusal.EmptySource)
      _ <- sourceCheck(snapshot, representation, sourceAtlas)
      _ <- validUnicode(recall.atlas)
      _ <- validUnicode(sourceAtlas)
      queries <- traverse(recall.ordered)(u => unitInput(recall, u).map(u.id -> _))
      documents <- traverse(snapshot.nodes)(n =>
        targetInput(snapshot, representation, sourceAtlas, n).map(n.ref -> _)
      )
    yield new Context(recall, snapshot, representation, Left(sourceAtlas), queries, documents)

  /** Prepare every checked timed-source node with its declared encoder payload. */
  def prepareTimed(
      recall: RecallGraph[Checked],
      source: bridge.TimedSourceView
  ): Either[SurfaceRenderingRefusal, SurfaceScoringContext] =
    val snapshot = MappingBindingRender.snapshot(source)
    for
      _ <- if recall.units.nonEmpty then Right(()) else Left(SurfaceRenderingRefusal.EmptyRecall)
      _ <- if snapshot.nodes.nonEmpty then Right(()) else Left(SurfaceRenderingRefusal.EmptySource)
      _ <- timedSourceCheck(snapshot, source.representation)
      _ <- validUnicode(recall.atlas)
      queries <- traverse(recall.ordered)(u => unitInput(recall, u, TimedPolicy).map(u.id -> _))
      payloads <- timedPayloads(source, snapshot)
      documents <- traverse(snapshot.nodes)(n =>
        timedTargetInput(snapshot, n, payloads.get(n.ref), TimedPolicy).map(n.ref -> _)
      )
    yield new Context(
      recall,
      snapshot,
      source.representation,
      Right(source),
      queries,
      documents,
      TimedPolicy
    )

  /** A single checked pair retains its historical per-target validation behavior. */
  def pair(
      recall: RecallGraph[Checked],
      unitId: RecallUnitId,
      view: SourceView,
      representation: SourceRepresentation,
      sourceAtlas: SurfaceAtlas,
      targetRef: SourceNodeRef
  ): Either[SurfaceRenderingRefusal, RenderedContentPair] =
    val snapshot = MappingBindingRender.snapshot(view)
    for
      unit <- recall.byId.get(unitId).toRight(SurfaceRenderingRefusal.UnknownUnit)
      node <- snapshot.node(targetRef).toRight(SurfaceRenderingRefusal.UnknownTarget)
      _ <- sourceCheck(snapshot, representation, sourceAtlas)
      _ <- validUnicode(recall.atlas)
      _ <- validUnicode(sourceAtlas)
      q <- unitInput(recall, unit)
      d <- targetInput(snapshot, representation, sourceAtlas, node)
    yield new Pair(q.content, d.content, new ContentGrain(snapshot.maxLevel), q.text, d.text)

  private def traverse[A, B](xs: Vector[A])(f: A => Either[SurfaceRenderingRefusal, B]) =
    xs.foldLeft[Either[SurfaceRenderingRefusal, Vector[B]]](Right(Vector.empty))((done, x) =>
      done.flatMap(v => f(x).map(v :+ _))
    )

  private def sourceCheck(
      snapshot: SourceView,
      representation: SourceRepresentation,
      atlas: SurfaceAtlas
  ): Either[SurfaceRenderingRefusal, Unit] =
    for
      _ <- representation.bundles.toVector match
        case Vector(t: BundleEntry.TextSource)
            if t.canonicalText == atlas.source.canonicalChecksum =>
          Right(())
        case _ => Left(SurfaceRenderingRefusal.SourceIdentity)
      _ <-
        if representation.viewFingerprint == ViewFingerprint.of(snapshot) &&
          representation.scopeDigest == MappingSourceRender.scope(snapshot.nodes)
        then Right(())
        else Left(SurfaceRenderingRefusal.SourceViewBinding)
      _ <-
        if acyclic(snapshot.nodes) then Right(())
        else Left(SurfaceRenderingRefusal.InvalidHierarchy)
    yield ()

  private def timedSourceCheck(
      snapshot: SourceView,
      representation: SourceRepresentation
  ): Either[SurfaceRenderingRefusal, Unit] =
    for
      _ <-
        if representation.viewFingerprint == ViewFingerprint.of(snapshot) &&
          representation.scopeDigest == MappingSourceRender.scope(snapshot.nodes)
        then Right(())
        else Left(SurfaceRenderingRefusal.SourceViewBinding)
      _ <-
        if acyclic(snapshot.nodes) then Right(())
        else Left(SurfaceRenderingRefusal.InvalidHierarchy)
    yield ()

  private def unitInput(recall: RecallGraph[Checked], unit: RecallUnit, policy: String = Policy) =
    for
      _ <- validate(recall.atlas, unit.span)
      content <- ContentProjection
        .canonicalUnit(unit)
        .left
        .map(SurfaceRenderingRefusal.Projection.apply)
      text = recall.transcript.canonicalText.substring(
        unit.span.minSpan.start,
        unit.span.minSpan.endExclusive
      )
      _ <- validPayload(text)
    yield new UnitInput(content, text, policy)

  private def targetInput(
      snapshot: SourceView,
      representation: SourceRepresentation,
      atlas: SurfaceAtlas,
      node: NodeSummary
  ) =
    for
      _ <- representation.target(node.ref).map(_.sourceSupport) match
        case Some(p: SourceSupportStatus.Located) if p.support == node.support => Right(())
        case _ => Left(SurfaceRenderingRefusal.PhysicalSupport)
      spans <- node.support match
        case TypedSupport.Text(spans) => Right(spans)
        case _                        => Left(SurfaceRenderingRefusal.UnsupportedSupport)
      _ <- validate(atlas, spans)
      content <- ContentProjection
        .canonicalTarget(node, snapshot)
        .left
        .map(SurfaceRenderingRefusal.Projection.apply)
      text = union(spans)
        .map(s => atlas.source.canonicalText.substring(s.start, s.endExclusive))
        .mkString("\n")
      _ <- validPayload(text)
    yield new TargetInput(content, text)

  private def timedPayloads(
      source: bridge.TimedSourceView,
      snapshot: SourceView
  ): Either[SurfaceRenderingRefusal, Map[SourceNodeRef, String]] =
    val leaves = snapshot.nodes.filter(_.level == 0).sortBy(_.discoursePosition)
    val groups = snapshot.nodes.filter(_.level == 1).sortBy(_.discoursePosition)
    val sourceGroups = source.segments.flatMap(_.group).distinct.sortBy(_.ordinal)
    if leaves.size != source.segments.size || groups.size != sourceGroups.size then
      Left(SurfaceRenderingRefusal.SourceViewBinding)
    else
      val leafPayloads = source.segments.zip(leaves).map { case (segment, node) =>
        node.ref -> segment.embedText.getOrElse(segment.text)
      }
      val groupPayloads = sourceGroups.zip(groups).map { case (group, node) =>
        node.ref -> group.embedText.getOrElse(group.label)
      }
      Right((leafPayloads ++ groupPayloads).toMap)

  private def timedTargetInput(
      snapshot: SourceView,
      node: NodeSummary,
      payload: Option[String],
      policy: String
  ) =
    for
      text <- payload.toRight(SurfaceRenderingRefusal.SourceViewBinding)
      _ <- validPayload(text)
      content <- ContentProjection
        .canonicalTarget(node, snapshot)
        .left
        .map(SurfaceRenderingRefusal.Projection.apply)
    yield new TargetInput(content, text, policy)

  private def acyclic(nodes: Vector[NodeSummary]): Boolean =
    val parents = nodes.map(n => n.ref -> n.parent).toMap
    var checked = Set.empty[SourceNodeRef]
    nodes.forall { node =>
      var seen = Set.empty[SourceNodeRef]
      var at = Option(node.ref)
      var valid = true
      while at.nonEmpty && !checked.contains(at.get) && valid do
        val ref = at.get
        if seen.contains(ref) then valid = false
        else
          seen += ref
          at = parents.get(ref).flatten
      checked ++= seen
      valid
    }

  private def validUnicode(atlas: SurfaceAtlas): Either[SurfaceRenderingRefusal, Unit] =
    if unicode(atlas.source.canonicalText) then Right(())
    else Left(SurfaceRenderingRefusal.InvalidUnicode)

  private def validPayload(text: String): Either[SurfaceRenderingRefusal, Unit] =
    if unicode(text) then Right(()) else Left(SurfaceRenderingRefusal.InvalidUnicode)

  private def unicode(text: String): Boolean =
    var i = 0
    var valid = true
    while i < text.length && valid do
      val c = text.charAt(i)
      if Character.isHighSurrogate(c) then
        valid = i + 1 < text.length && Character.isLowSurrogate(text.charAt(i + 1))
        i += 2
      else
        valid = !Character.isLowSurrogate(c)
        i += 1
    valid

  private def boundary(text: String, at: Int): Boolean =
    at == 0 || at == text.length ||
      !(Character.isHighSurrogate(text.charAt(at - 1)) && Character.isLowSurrogate(text.charAt(at)))

  private def validate(atlas: SurfaceAtlas, spans: SpanSet): Either[SurfaceRenderingRefusal, Unit] =
    val text = atlas.source.canonicalText
    if spans.spans.toVector.exists(s =>
        s.endExclusive > text.length ||
          !boundary(text, s.start) || !boundary(text, s.endExclusive)
      )
    then Left(SurfaceRenderingRefusal.InvalidSpan)
    else if spans.refs.toVector.exists(r =>
        r.unit.exists(id => !atlas.byId.get(id).exists(_.span.contains(r.span)))
      )
    then Left(SurfaceRenderingRefusal.InvalidAnchor)
    else Right(())

  private def union(spans: SpanSet): Vector[TextSpan] =
    spans.spans.toVector.filterNot(_.isEmpty).foldLeft(Vector.empty[TextSpan]) { (done, span) =>
      done.lastOption.flatMap(_.union(span)) match
        case Some(merged) => done.init :+ merged
        case None         => done :+ span
    }
