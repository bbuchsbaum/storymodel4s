package storymodel4s.amr.graph

import storymodel4s.amr.graph.CheckState.Checked
import storymodel4s.amr.graph.RoleForm.CanonicalRoles

/** Structural compatibility between two canonical charts (design record §49.1, §50).
  *
  * These are the hard checks embeddings cannot make: frame identity, argument agreement, actor–
  * patient reversal, and polarity conflict. No vectors here; `align` combines this report with
  * dense similarity into a local cost.
  */
object SoftCompatibility:
  private type G = AmrGraph[Checked, CanonicalRoles]

  enum FrameMatchKind:
    case ExactFrame
    case SameLemma
    case Unmatched

  /** How an event node on the left relates to its best counterpart on the right. */
  final case class FrameMatch(
      left: NodeId,
      right: Option[NodeId],
      kind: FrameMatchKind,
      /** Core-argument agreement in `[0,1]` over the union of `ARGn` roles used by either side. */
      argumentAgreement: Double,
      sharedArguments: Vector[ArgIndex],
      conflictingArguments: Vector[ArgIndex]
  )

  /** Two core arguments whose fillers are swapped between the sides. */
  final case class RoleReversal(left: NodeId, right: NodeId, first: ArgIndex, second: ArgIndex)

  /** Same event, opposite `:polarity`. */
  final case class PolarityConflict(left: NodeId, right: NodeId, leftNegated: Boolean)

  final case class Report(
      frames: Vector[FrameMatch],
      roleReversals: Vector[RoleReversal],
      polarityConflicts: Vector[PolarityConflict]
  ):
    def frameOverlap: Double =
      if frames.isEmpty then 1.0
      else frames.count(_.kind != FrameMatchKind.Unmatched).toDouble / frames.size
    def argumentAgreement: Double =
      val matched = frames.filter(_.kind != FrameMatchKind.Unmatched)
      if matched.isEmpty then 0.0 else matched.map(_.argumentAgreement).sum / matched.size
    def hasStructuralConflict: Boolean = roleReversals.nonEmpty || polarityConflicts.nonEmpty

  /** A filler is compared by the concept it denotes (or literal spelling), not by variable name. */
  private def filler(g: G, v: AmrValue): String = v match
    case AmrValue.Node(id)   => g.concepts.get(id).map(_.render).getOrElse(id.value)
    case AmrValue.Literal(l) => l.render

  private def coreArgs(g: G, n: NodeId): Map[ArgIndex, String] =
    g.canonicalTriples.collect { case (s, Role.Arg(i), t) if s == n => i -> filler(g, t) }.toMap

  def report(left: G, right: G): Report =
    val rightFrames = right.frameNodes
    var usedRight = Set.empty[NodeId]
    val reversals = Vector.newBuilder[RoleReversal]
    val polarity = Vector.newBuilder[PolarityConflict]

    val matches = left.frameNodes.map { (ln, lf) =>
      val exact = rightFrames.filter((rn, rf) => rf == lf && !usedRight.contains(rn))
      val lemma = rightFrames.filter((rn, rf) => rf.lemma == lf.lemma && !usedRight.contains(rn))
      val (kind, candidates) =
        if exact.nonEmpty then (FrameMatchKind.ExactFrame, exact)
        else if lemma.nonEmpty then (FrameMatchKind.SameLemma, lemma)
        else (FrameMatchKind.Unmatched, Vector.empty)
      val la = coreArgs(left, ln)
      // choose the candidate with the best argument agreement
      val scored = candidates.map { (rn, _) =>
        val ra = coreArgs(right, rn)
        val keys = (la.keySet ++ ra.keySet).toVector.sorted
        val shared = keys.filter(k => la.get(k).exists(v => ra.get(k).contains(v)))
        val conflicting = keys.filter(k => la.contains(k) && ra.contains(k) && la(k) != ra(k))
        val agreement = if keys.isEmpty then 1.0 else shared.size.toDouble / keys.size
        (rn, ra, agreement, shared, conflicting)
      }
      scored.sortBy(-_._3).headOption match
        case None => FrameMatch(ln, None, kind, 0.0, Vector.empty, Vector.empty)
        case Some((rn, ra, agreement, shared, conflicting)) =>
          usedRight += rn
          // reversal: two args whose fillers are swapped
          for
            i <- la.keys.toVector.sorted
            j <- la.keys.toVector.sorted if i.value < j.value
          do
            if ra.get(i).contains(la(j)) && ra.get(j).contains(la(i)) && la(i) != la(j) then
              reversals += RoleReversal(ln, rn, i, j)
          val ln1 = left.isNegated(ln)
          val rn1 = right.isNegated(rn)
          if ln1 != rn1 then polarity += PolarityConflict(ln, rn, ln1)
          FrameMatch(ln, Some(rn), kind, agreement, shared, conflicting)
    }
    Report(matches, reversals.result(), polarity.result())
