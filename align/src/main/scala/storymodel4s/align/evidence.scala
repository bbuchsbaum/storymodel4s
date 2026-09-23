package storymodel4s.align

import cats.syntax.all.*
import storymodel4s.core.Checksum
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Local mapping evidence: every nominated anchor gated and every admitted state priced ONCE,
  * before any sequence inference.
  *
  * Why: local reference measurement and [[GraphHsmm]] must read the same nominations and the same
  * local costs, so that any difference between their outputs is attributable to inference and
  * never to re-pricing. Only [[LocalEvidence.compute]] builds one. It records the recall, the
  * view, and whether the mode gate ran, and [[GraphHsmm]] refuses evidence bound to anything else.
  *
  * Candidates that are not nodes of the view are dropped here, exactly as inference always did:
  * they are neither priced nor nominated.
  */
final class LocalEvidence private (
    val recallChecksum: Checksum,
    val viewFingerprint: ViewFingerprint,
    val gated: Boolean,
    val units: Vector[RecallUnitId],
    val nominated: Vector[Vector[SourceNodeRef]],
    val admissibility: Vector[Map[SourceNodeRef, Admissibility]],
    val breakdowns: Vector[Map[AlignState, CostBreakdown]]
):

  /** The states each unit can occupy: its non-excluded priced states, in key order. */
  def states: Vector[Vector[AlignState]] =
    breakdowns.map(m => m.toVector.collect { case (s, b) if !b.excluded => s }.sortBy(_.key))

  override def equals(that: Any): Boolean = that match
    case o: LocalEvidence =>
      recallChecksum == o.recallChecksum && viewFingerprint == o.viewFingerprint &&
      gated == o.gated && units == o.units && nominated == o.nominated &&
      admissibility == o.admissibility && breakdowns == o.breakdowns
    case _ => false

  override def hashCode: Int =
    (recallChecksum, viewFingerprint, gated, units, nominated, admissibility, breakdowns).hashCode

  override def toString: String =
    s"LocalEvidence(units=${units.size}, gated=$gated, " +
      s"view=${viewFingerprint.checksum.short()}, recall=${recallChecksum.short()})"

object LocalEvidence:

  /** Gate (when `gate`) and price every nominated anchor of every unit, plus the external states.
    *
    * The mode gate runs first and is non-bypassable: the cost model is consulted only for the
    * `(anchor, mode)` pairs it admits (law L3). A unit the aligner could not rank has the single
    * state `Unranked`.
    */
  def compute(
      recall: RecallGraph[Checked],
      view: SourceView,
      candidates: Candidates,
      costModel: LocalCostModel,
      gate: Boolean
  ): Either[AlignError, LocalEvidence] =
    val units = recall.ordered
    if units.isEmpty then Left(AlignError.EmptyRecall)
    else
      val admissibility: Vector[Map[SourceNodeRef, Admissibility]] = units.map { u =>
        candidates
          .set(u.id)
          .ranked
          .flatMap { ref =>
            view.node(ref).map { n =>
              ref -> (if gate then ModeGate.assess(u, n, view) else Admissibility.faithfulOnly)
            }
          }
          .toMap
      }
      val breakdowns: Either[AlignError, Vector[Map[AlignState, CostBreakdown]]] =
        units.zip(admissibility).traverse { (u, adm) =>
          val set = candidates.set(u.id)
          val sources = set.ranked.flatMap { ref =>
            view.node(ref).toVector.flatMap { n =>
              adm(ref).modes.map(m => AlignState.anchored(ref, m) -> costModel.cost(u, n, m, view))
            }
          }
          val externals =
            if set.abstained && set.ranked.isEmpty then Vector(AlignState.unranked)
            else AlignState.externals
          val ext = externals.traverse {
            case s @ AlignState.External(x) =>
              CostBreakdown.external(costModel.externalCost(u, x)).map(s -> _)
            case s => Right(s -> CostBreakdown.unreachable)
          }
          ext.map(values => (sources ++ values).toMap)
        }
      val ids = units.map(_.id)
      val nominated = ids.map(id => candidates.anchorsOf(id).filter(ref => view.node(ref).nonEmpty))
      breakdowns.map { b =>
        new LocalEvidence(
          AlignWire.recallChecksum(recall),
          ViewFingerprint.of(view),
          gate,
          ids,
          nominated,
          admissibility,
          b
        )
      }

  /** Refuse evidence computed for another recall, another view, or under the other gate setting. */
  private[align] def bound(
      evidence: LocalEvidence,
      recall: RecallGraph[Checked],
      view: SourceView,
      gate: Boolean
  ): Either[AlignError, Unit] =
    def refuse(detail: String) = Left(AlignError.InconsistentResult(s"local evidence $detail"))
    if evidence.gated != gate then
      refuse(s"was computed with gate=${evidence.gated}; this inference requires gate=$gate")
    else if evidence.units != recall.ordered.map(_.id) then
      refuse("covers different recall units")
    else if evidence.recallChecksum != AlignWire.recallChecksum(recall) then
      refuse("was computed for a different recall")
    else if evidence.viewFingerprint != ViewFingerprint.of(view) then
      refuse("was computed for a different source view")
    else Right(())
