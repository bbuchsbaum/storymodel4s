package storymodel4s.align.bridge

import cats.data.NonEmptyVector
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.features.MissingReason
import storymodel4s.recall.{Lexical, ModalityTag, PolarityTag}

/** Flat surface targets share the existing alignment contract without claiming narrative depth. */
final class TextSourceView private (
    val sourcePackage: TextSourcePackage,
    val kind: SurfaceUnitKind,
    private val view: InMemorySourceView,
    val representation: SourceRepresentation
) extends SourceView:
  val nodes: Vector[NodeSummary] = view.nodes
  def node(ref: SourceNodeRef): Option[NodeSummary] = view.node(ref)
  def adjacency(layer: RelationLayer): Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
    view.adjacency(layer)
  val worldOrder: Option[Map[SourceNodeRef, Int]] = None
  val scoringLength: Int = view.scoringLength

object TextSourceView:
  /** Select a present granularity; a supplied atlas never silently falls back to generated units.
    */
  def of(
      source: TextSourcePackage,
      kind: SurfaceUnitKind
  ): Either[MappingRefusal, TextSourceView] =
    val segments = source.at(kind)
    if segments.isEmpty then
      Left(MappingRefusal.InvalidValue("text-source.granularity", s"unavailable: $kind"))
    else
      val nodes = segments.zipWithIndex.map { (segment, rank) =>
        val unit = segment.unit
        NodeSummary(
          ref = SourceNodeRef.Segment(segment.id),
          level = 0,
          parent = None,
          discoursePosition = rank,
          support = SpanSet.one(SpanRef(Some(unit.id), unit.span)),
          predicate = None,
          participants = Vector.empty,
          // Required by the legacy view shape; Undeclared makes this placeholder unscorable.
          context = ContextTag.NarratedWorld,
          polarity = PolarityTag.Unknown,
          modality = ModalityTag.Unknown,
          locations = Vector.empty,
          lemmas = Lexical.stems(unit.text(source.source)).toSet,
          importance = ImportanceWeight.unmeasured,
          propositional = PropositionalScope.Undeclared(MissingReason.Excluded)
        )
      }
      val succession = nodes.zip(nodes.drop(1)).map((a, b) => (a.ref, b.ref, 1.0))
      val view = InMemorySourceView(
        nodes,
        Map(RelationLayer.DiscourseSuccession -> succession),
        None,
        source.source.canonicalText.length
      )
      SourceRepresentation
        .of(
          view,
          NonEmptyVector.one(BundleEntry.text(source.source.canonicalChecksum)),
          None,
          nodes.map(n => n.ref -> SourceSupportStatus.located(n.support)).toMap
        )
        .map(representation => new TextSourceView(source, kind, view, representation))
