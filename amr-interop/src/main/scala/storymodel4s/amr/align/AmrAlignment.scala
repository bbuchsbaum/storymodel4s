package storymodel4s.amr.align

import cats.data.{NonEmptySet, ValidatedNec}
import cats.syntax.all.*
import storymodel4s.amr.graph.*
import storymodel4s.core.{Checksum, ClaimMeta, Credence, SpanSet, SurfaceUnitId}

/** A node of a sentence chart addressed globally: which sentence, which local node. */
final case class AmrNodeRef(sentence: SurfaceUnitId, node: NodeId)

/** A nonempty set of local nodes realised by exact source spans (LEAMR "subgraph alignment"). The
  * span set may be discontinuous; one span may appear in several alignments.
  */
final case class SubgraphAlignment(
    nodes: NonEmptySet[NodeId],
    spans: SpanSet,
    credence: Credence,
    meta: ClaimMeta
)

/** An edge realised by source text (e.g. a preposition licensing `:location`). */
final case class RelationAlignment(
    edge: Edge,
    spans: SpanSet,
    credence: Credence,
    meta: ClaimMeta
)

/** A reentrant reference (a second mention of an already aligned node), e.g. a pronoun. */
final case class ReentrancyAlignment(
    node: NodeId,
    viaEdge: Edge,
    spans: SpanSet,
    credence: Credence,
    meta: ClaimMeta
)

/** Nodes duplicated by ellipsis or coordination that share one textual trigger. */
final case class DuplicateSubgraphAlignment(
    nodes: NonEmptySet[NodeId],
    spans: SpanSet,
    credence: Credence,
    meta: ClaimMeta
)

enum AlignmentEntry:
  case Subgraph(a: SubgraphAlignment)
  case Relation(a: RelationAlignment)
  case Reentrancy(a: ReentrancyAlignment)
  case Duplicate(a: DuplicateSubgraphAlignment)

  def meta: ClaimMeta = this match
    case Subgraph(a)   => a.meta
    case Relation(a)   => a.meta
    case Reentrancy(a) => a.meta
    case Duplicate(a)  => a.meta

enum AlignmentViolation:
  case UnknownNode(node: NodeId)
  case UnknownEdge(edge: Edge)
  case GraphMismatch(expected: Checksum, actual: Checksum)

  def message: String = this match
    case UnknownNode(n)      => s"alignment references unknown node '${n.value}'"
    case UnknownEdge(e)      => s"alignment references unknown edge '${e.render}'"
    case GraphMismatch(e, a) =>
      s"alignment was authored for graph ${e.short()} but got ${a.short()}"

/** Exact text-to-graph evidence kept *beside* a standards-compatible chart (design record §43).
  *
  * `graphDigest` pins the chart the entries were authored against (`Canonical.digest`), so a
  * sidecar cannot silently be applied to a different analysis. Nodes with no span (abstract
  * concepts) simply have no entry.
  */
final case class AmrAlignment(
    sentence: SurfaceUnitId,
    graphDigest: Checksum,
    entries: Vector[AlignmentEntry]
):
  def nodesAligned: Set[NodeId] = entries.flatMap {
    case AlignmentEntry.Subgraph(a)   => a.nodes.toSortedSet.toVector
    case AlignmentEntry.Duplicate(a)  => a.nodes.toSortedSet.toVector
    case AlignmentEntry.Reentrancy(a) => Vector(a.node)
    case AlignmentEntry.Relation(_)   => Vector.empty
  }.toSet

  /** All spans that realise `node`, across every entry kind. */
  def spansOf(node: NodeId): Option[SpanSet] =
    entries
      .flatMap {
        case AlignmentEntry.Subgraph(a) if a.nodes.contains(node)  => Some(a.spans)
        case AlignmentEntry.Duplicate(a) if a.nodes.contains(node) => Some(a.spans)
        case AlignmentEntry.Reentrancy(a) if a.node == node        => Some(a.spans)
        case _                                                     => None
      }
      .reduceOption(_ ++ _)

  /** Nodes of `g` that no entry realises (expected for abstract concepts; reported, not rejected).
    */
  def unaligned[C <: CheckState, R <: RoleForm](g: AmrGraph[C, R]): Set[NodeId] =
    g.concepts.keySet -- nodesAligned

object AmrAlignment:
  /** Every referenced node/edge must exist in `g`, and the digest must match when `g` is canonical
    * (`checkDigest = true`).
    */
  def validate[C <: CheckState, R <: RoleForm](
      a: AmrAlignment,
      g: AmrGraph[C, R],
      digest: Option[Checksum] = None
  ): ValidatedNec[AlignmentViolation, AmrAlignment] =
    val nodesOk: ValidatedNec[AlignmentViolation, Unit] = a.entries.traverse_ { e =>
      val nodes: Vector[NodeId] = e match
        case AlignmentEntry.Subgraph(x)   => x.nodes.toSortedSet.toVector
        case AlignmentEntry.Duplicate(x)  => x.nodes.toSortedSet.toVector
        case AlignmentEntry.Reentrancy(x) => Vector(x.node)
        case AlignmentEntry.Relation(_)   => Vector.empty
      nodes.traverse_ { n =>
        if g.concepts.contains(n) then ().validNec else AlignmentViolation.UnknownNode(n).invalidNec
      }
    }
    val edgeSet = g.edges.toSet
    val edgesOk: ValidatedNec[AlignmentViolation, Unit] = a.entries.traverse_ {
      case AlignmentEntry.Relation(x) if !edgeSet.contains(x.edge) =>
        AlignmentViolation.UnknownEdge(x.edge).invalidNec
      case AlignmentEntry.Reentrancy(x) if !edgeSet.contains(x.viaEdge) =>
        AlignmentViolation.UnknownEdge(x.viaEdge).invalidNec
      case _ => ().validNec
    }
    val digestOk: ValidatedNec[AlignmentViolation, Unit] = digest match
      case Some(d) if d != a.graphDigest =>
        AlignmentViolation.GraphMismatch(a.graphDigest, d).invalidNec
      case _ => ().validNec
    (nodesOk, edgesOk, digestOk).tupled.as(a)
