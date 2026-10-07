package storymodel4s.align

import storymodel4s.core.*
import storymodel4s.proposition.GraphOrder
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Rendering refusals carry no submitted text or plain digest of it. */
enum SurfaceRenderingRefusal:
  case UnknownUnit, UnknownTarget, SourceIdentity, SourceViewBinding, PhysicalSupport
  case UnsupportedSupport, InvalidUnicode, InvalidSpan, InvalidAnchor, InvalidHierarchy
  case Projection(reason: storymodel4s.proposition.ProjectionRefusal)

/** Exact rendered strings accompany content without handing coordinates back to a scorer. */
type RenderedContentPair = SurfaceRendering.Pair

/** Library-owned surface rendering joins declared source identity to actual canonical text. It
  * establishes a checked text/view join, not acquisition authority or provider execution.
  */
object SurfaceRendering:
  /** Versioned policies preserve recall hull text and avoid filling gaps in source support. */
  val Policy: String = "surface-render/recall-hull-source-union-newline/v1"

  /** A checked scoring pair distinguishes equal semantic content with different submitted text. */
  final class Pair private[SurfaceRendering] (
      val unit: UnitContent[GraphOrder.Canonical],
      val target: TargetContent[GraphOrder.Canonical],
      val grain: ContentGrain,
      val unitText: String,
      val targetText: String
  ):
    val policy: String = Policy
    private def parts = (unit, target, grain, unitText, targetText, policy)
    override def equals(other: Any): Boolean = other match
      case p: Pair => parts == p.parts
      case _       => false
    // No public text-derived hash: private material must not acquire an unkeyed digest surface.
    override def hashCode: Int = (unit, target, grain).hashCode
    override def toString: String =
      s"RenderedContentPair(unitChars=${unitText.length}, targetChars=${targetText.length})"

  /** Capture the view once; all validation, lookup and projection use the captured inventory.
    * Source identity is a checked declaration-to-text join. Anchored media/caption rendering needs
    * its own admitted stream binding and is refused here.
    */
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
      _ <- representation.bundles.toVector match
        case Vector(t: BundleEntry.TextSource)
            if t.canonicalText == sourceAtlas.source.canonicalChecksum =>
          Right(())
        case _ => Left(SurfaceRenderingRefusal.SourceIdentity)
      _ <-
        if representation.viewFingerprint == ViewFingerprint.of(snapshot) &&
          representation.scopeDigest == MappingSourceRender.scope(snapshot.nodes)
        then Right(())
        else Left(SurfaceRenderingRefusal.SourceViewBinding)
      _ <- representation.target(targetRef).map(_.sourceSupport) match
        case Some(p: SourceSupportStatus.Located) if p.support == node.support => Right(())
        case _ => Left(SurfaceRenderingRefusal.PhysicalSupport)
      _ <-
        if acyclic(snapshot.nodes) then Right(())
        else Left(SurfaceRenderingRefusal.InvalidHierarchy)
      sourceSpans <- node.support match
        case TypedSupport.Text(spans) => Right(spans)
        case _                        => Left(SurfaceRenderingRefusal.UnsupportedSupport)
      _ <- validate(recall.atlas, unit.span)
      _ <- validate(sourceAtlas, sourceSpans)
      // RecallGraph.Checked already proves this is exactly the transcript's hull substring.
      unitText = recall.transcript.canonicalText.substring(
        unit.span.minSpan.start,
        unit.span.minSpan.endExclusive
      )
      targetText = union(sourceSpans)
        .map(s => sourceAtlas.source.canonicalText.substring(s.start, s.endExclusive))
        .mkString("\n")
      content <- ContentProjection
        .canonical(unit, node, snapshot)
        .left
        .map(SurfaceRenderingRefusal.Projection.apply)
    yield new Pair(content._1, content._2, content._3, unitText, targetText)

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
      !(Character.isHighSurrogate(text.charAt(at - 1)) &&
        Character.isLowSurrogate(text.charAt(at)))

  private def validate(
      atlas: SurfaceAtlas,
      spans: SpanSet
  ): Either[SurfaceRenderingRefusal, Unit] =
    val text = atlas.source.canonicalText
    if !unicode(text) then Left(SurfaceRenderingRefusal.InvalidUnicode)
    else if spans.spans.toVector.exists(s =>
        s.endExclusive > text.length || !boundary(text, s.start) ||
          !boundary(text, s.endExclusive)
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
