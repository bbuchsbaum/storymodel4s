package storymodel4s.align

import storymodel4s.recall.{PropositionSketch, SketchRole}

/** Detail facets assessed conditionally on the inferred target event. */
enum Facet:
  case Actor, Action, Object, Location, Outcome, Cause

/** `Unspecified` means the recall did not commit to the facet; it is not an error. */
enum FacetVerdict:
  case Correct, Wrong, Unspecified

final case class FidelityReport(verdicts: Map[Facet, FacetVerdict]):
  def apply(f: Facet): FacetVerdict = verdicts.getOrElse(f, FacetVerdict.Unspecified)
  def specified: Int = verdicts.values.count(_ != FacetVerdict.Unspecified)
  def correct: Int = verdicts.values.count(_ == FacetVerdict.Correct)

  /** Fraction of specified facets that are correct; `None` when nothing was specified. */
  def fidelity: Option[Double] =
    if specified == 0 then None else Some(correct.toDouble / specified.toDouble)

/** Given that a unit denotes `node`, is each reportable facet right, wrong, or unspecified? Kept
  * separate from target uncertainty so "confidently event 5 but wrong patient" is representable.
  */
object FidelityFacets:
  def assess(sketch: PropositionSketch, node: NodeSummary): FidelityReport =
    def names(p: Option[storymodel4s.recall.SketchParticipant]): Option[Set[String]] =
      p.filter(_.specified).map(_.names)
    def compareParticipant(
        recalled: Option[Set[String]],
        source: Option[ParticipantSummary]
    ): FacetVerdict =
      (recalled, source) match
        case (None, _)          => FacetVerdict.Unspecified
        case (Some(_), None)    => FacetVerdict.Wrong
        case (Some(r), Some(s)) =>
          if Names.overlap(r, s.names) then FacetVerdict.Correct else FacetVerdict.Wrong
    val actor = compareParticipant(names(sketch.agent), node.agent)
    val obj = compareParticipant(names(sketch.patient), node.patient)
    val action = (sketch.predicate, node.predicate) match
      case (None, _)          => FacetVerdict.Unspecified
      case (Some(a), Some(b)) => if a == b then FacetVerdict.Correct else FacetVerdict.Wrong
      case (Some(_), None)    => FacetVerdict.Wrong
    val location =
      if sketch.locations.isEmpty then FacetVerdict.Unspecified
      else
        val sourceLocs = node.locations.map(_.toLowerCase).toSet ++
          node.byRole(SketchRole.Location).toVector.flatMap(_.names) ++
          node.byRole(SketchRole.Destination).toVector.flatMap(_.names)
        if sketch.locations.exists(l => sourceLocs.contains(l.toLowerCase)) then
          FacetVerdict.Correct
        else FacetVerdict.Wrong
    def text(a: Option[String], b: Option[String]): FacetVerdict = (a, b) match
      case (None, _)          => FacetVerdict.Unspecified
      case (Some(x), Some(y)) =>
        if x.equalsIgnoreCase(y) then FacetVerdict.Correct else FacetVerdict.Wrong
      case (Some(_), None) => FacetVerdict.Wrong
    FidelityReport(
      Map(
        Facet.Actor -> actor,
        Facet.Action -> action,
        Facet.Object -> obj,
        Facet.Location -> location,
        Facet.Outcome -> text(sketch.outcome, node.outcome),
        Facet.Cause -> text(sketch.cause, node.cause)
      )
    )
