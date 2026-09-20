package storymodel4s.align

import storymodel4s.core.TypedSupport

import storymodel4s.core.SpanSet
import storymodel4s.recall.{RecallGraph, RecallUnitId}
import storymodel4s.recall.RecallGraphStatus.Checked

/** The textual coordinates of one alignment cell: which words on each side it was computed from.
  *
  * This is the vision's question - "which words support each conclusion" - answered at the level
  * the observational coordinate system already supports. Both sides carry exact spans already:
  * `RecallUnit.span` into the transcript and `NodeSummary.support` into the source. Nothing here is
  * new evidence; the join is what had never been written down.
  *
  * '''NOT A CASE CLASS, AND A PRIVATE CONSTRUCTOR ALONE WOULD NOT HAVE BEEN ENOUGH.''' Lawful unit
  * A, lawful node A and a lawful breakdown B FORM A FALSE TUPLE when B was computed for some other
  * cell, so product construction is inadmissible - and so is a factory that merely takes the three
  * behind a private door. That is the worst version of this defect in this particular type: a trace
  * whose whole job is to say which words support a cell, naming words for a cell it did not come
  * from.
  *
  * [[CellCoordinates.of]] therefore takes the RESULT and looks the breakdown UP, so the costs map
  * is the witness that this breakdown belongs to this unit at this state. A caller cannot pair a
  * breakdown with a cell it did not come from, because it never supplies one.
  *
  * '''DELIBERATELY NOT A CLASSIFICATION OF THE TERMS.''' A four-way `Measured / Absent / Imputed /
  * Ineligible` view per cost term is the eventual artifact and is NOT DERIVABLE from what a cell
  * carries today. ONE gap, measured rather than assumed:
  *
  *   - INELIGIBLE is not recorded and CANNOT be reconstructed as a complement. `EvidenceSuite` pins
  *     a chartless cell whose `missingTerms` is `{Chart, Structural, Sensory}`, while `cost`
  *     excludes Chart and Structural from `eligible` for that same cell. So "absent" and "the cell
  *     never had this dimension" overlap in the published record.
  *
  * '''THIS PARAGRAPH ONCE NAMED A SECOND GAP THAT WAS ALREADY CLOSED WHEN IT WAS WRITTEN.''' It
  * said IMPUTED is not recorded, because `cost` discarded the provider's `MissingReason` and left a
  * measured 0.5 indistinguishable from an abstained one. That was true until 0ca09c5, which landed
  * TWELVE HOURS BEFORE this file did: `CostBreakdown.imputedTerms` carries the reason, on the very
  * breakdown `of` looks up. The conclusion below is unchanged and the evidence for half of it was
  * fiction, which is the more dangerous of the two states - a closed defect cited as a live
  * constraint reads exactly like a live one. Imputed IS derivable here today; whether this type
  * should expose it is a design question, not a capability one.
  *
  * The two sides are asymmetric on purpose. A recall unit carries its own `text`, so the transcript
  * words are here. The source words are not: `align` holds spans into a source text it does not
  * own, and materialising them would mean this module deciding what the source string is.
  */
final class CellCoordinates private[align] (
    val unit: RecallUnitId,
    /** Transcript spans this recall unit was segmented from; discontinuous when the unit is. */
    val unitSpan: SpanSet,
    /** The unit's own surface text, which recall carries directly. */
    val unitText: String,
    val node: SourceNodeRef,
    /** Source spans supporting the node - exact evidence, not a paraphrase. */
    val sourceSupport: TypedSupport,
    /** The state this cell was scored as, which fixes the anchor as well as the mode. */
    val state: AlignState,
    /** The fidelity mode this cell was scored under, absent for an external state. */
    val mode: Option[FidelityMode],
    /** Member-level structural receipts the cell already carries: which source members were
      * compared, which were excluded, and for which contradictions.
      */
    val reductions: Map[CostTerm, StructuralReductionReceipt]
):
  /** Facets contradicted by this cell's mode; empty when faithful or external. */
  def facets: Set[Facet] = mode.map(_.facetSet).getOrElse(Set.empty)

  /** Source members whose charts were excluded before reduction, with their contradictions. */
  def excludedMembers: Vector[StructuralMemberExclusion] =
    reductions.values.toVector.flatMap(_.excludedMembers)

  // Written out because this is deliberately not a case class: a case class with a private
  // constructor still derives Mirror.ProductOf, whose public fromProduct rebuilds the type field by
  // field and would restore exactly the false tuple this type exists to refuse.
  override def equals(other: Any): Boolean = other match
    case that: CellCoordinates =>
      unit == that.unit && unitSpan == that.unitSpan && unitText == that.unitText &&
      node == that.node && sourceSupport == that.sourceSupport && state == that.state &&
      mode == that.mode && reductions == that.reductions
    case _ => false

  override def hashCode: Int =
    (unit, unitSpan, unitText, node, sourceSupport, state, mode, reductions).hashCode

  override def toString: String =
    s"CellCoordinates(${unit.value} -> ${node.key} as ${state.key})"

object CellCoordinates:
  /** The coordinates of the cell `result` scored for `unitId` at `state`.
    *
    * '''NOTHING WITH A PAYLOAD IS ACCEPTED FROM THE CALLER.''' An earlier version took the
    * `RecallUnit` and `NodeSummary` and checked their KEYS — that the id appeared in `costs`, that
    * the ref was the state's anchor — then published whatever payload arrived with them. That is a
    * false tuple by copy: `RecallUnit.copy` keeps the id while substituting the TEXT, and
    * `NodeSummary.copy` keeps the ref while substituting the SUPPORT SPANS. A trace whose entire
    * job is to say which words support a cell could be built naming other words entirely. Found by
    * codex-storymodel-collab with two executable substitution courts.
    *
    * So the unit and the node are LOOKED UP rather than supplied, and the containers they are
    * looked up in must be the ones this result was computed from — proven against
    * `result.recallChecksum` and `result.viewFingerprint`, both of which `HsmmResult.validated`
    * derives rather than accepts. A caller can still hand over a wholly doctored graph, but then
    * the checksum will not match; substituting ONE unit inside an otherwise matching graph is what
    * the checksum makes impossible, and that was the actual attack.
    */
  def of(
      result: HsmmResult,
      recall: RecallGraph[Checked],
      view: SourceView,
      unitId: RecallUnitId,
      state: AlignState
  ): Either[AlignError, CellCoordinates] =
    val anchor = state match
      case AlignState.Source(ref)       => Some(ref)
      case AlignState.Distorted(ref, _) => Some(ref)
      case AlignState.External(_)       => None
    if AlignWire.recallChecksum(recall) != result.recallChecksum then
      Left(
        AlignError.MalformedRecord(
          "CellCoordinates",
          "this recall graph is not the one the result was computed from"
        )
      )
    else if ViewFingerprint.of(view) != result.viewFingerprint then
      Left(
        AlignError.MalformedRecord(
          "CellCoordinates",
          "this source view is not the one the result was computed from"
        )
      )
    else
      (anchor, recall.byId.get(unitId)) match
        case (None, _) =>
          Left(
            AlignError
              .MalformedRecord("CellCoordinates", s"state ${state.key} anchors no source node")
          )
        case (_, None) =>
          Left(
            AlignError.MalformedRecord(
              "CellCoordinates",
              s"unit ${unitId.value} is not in this recall graph"
            )
          )
        case (Some(ref), Some(unit)) =>
          (view.node(ref), result.costs.get(unitId).flatMap(_.get(state))) match
            case (None, _) =>
              Left(
                AlignError
                  .MalformedRecord("CellCoordinates", s"node ${ref.key} is not in this view")
              )
            case (_, None) =>
              Left(
                AlignError.MalformedRecord(
                  "CellCoordinates",
                  s"no cell for unit ${unitId.value} at state ${state.key}"
                )
              )
            case (Some(node), Some(breakdown)) =>
              Right(
                new CellCoordinates(
                  unit.id,
                  unit.span,
                  unit.text,
                  node.ref,
                  result.sourceSupport(node.ref),
                  state,
                  breakdown.mode,
                  breakdown.reductions
                )
              )
