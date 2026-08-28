package storymodel4s.align

import storymodel4s.recall.{Lexical, ModalityTag, PropositionSketch, SketchRole}

/** Detail facets assessed conditionally on the inferred target event. `Context` records whether the
  * recall asserted as narrated fact what the source only reports, believes, or intends (or the
  * reverse): the canonical Bartlett distortion, measured here rather than gated (review #10).
  */
enum Facet:
  case Actor, Action, Object, Location, Outcome, Cause, Context

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
        val sourceLocs = node.locations.map(Lexical.lower).toSet ++
          node.byRole(SketchRole.Location).toVector.flatMap(_.names) ++
          node.byRole(SketchRole.Destination).toVector.flatMap(_.names)
        val sourceTokens = Names.tokens(sourceLocs)
        if sketch.locations.exists { l =>
            val ll = Lexical.lower(l)
            sourceLocs.contains(ll) || sourceTokens.contains(ll)
          }
        then FacetVerdict.Correct
        else FacetVerdict.Wrong
    def text(a: Option[String], b: Option[String]): FacetVerdict = (a, b) match
      case (None, _)          => FacetVerdict.Unspecified
      case (Some(x), Some(y)) =>
        if Lexical.lower(x) == Lexical.lower(y) then FacetVerdict.Correct else FacetVerdict.Wrong
      case (Some(_), None) => FacetVerdict.Wrong
    val context = sketch.modality match
      case ModalityTag.Asserted =>
        if node.context == ContextTag.NarratedWorld then FacetVerdict.Correct
        else FacetVerdict.Wrong
      case ModalityTag.Reported =>
        if node.context == ContextTag.Speech then FacetVerdict.Correct
        else if node.context == ContextTag.NarratedWorld then FacetVerdict.Wrong
        else FacetVerdict.Unspecified
      case ModalityTag.Intended | ModalityTag.Desired =>
        if node.context == ContextTag.Intention || node.context == ContextTag.Desire ||
          node.modality == ModalityTag.Intended || node.modality == ModalityTag.Desired
        then FacetVerdict.Correct
        else if node.context == ContextTag.NarratedWorld && node.modality == ModalityTag.Asserted
        then FacetVerdict.Wrong
        else FacetVerdict.Unspecified
      case _ => FacetVerdict.Unspecified
    FidelityReport(
      Map(
        Facet.Actor -> actor,
        Facet.Action -> action,
        Facet.Object -> obj,
        Facet.Location -> location,
        Facet.Outcome -> text(sketch.outcome, node.outcome),
        Facet.Cause -> text(sketch.cause, node.cause),
        Facet.Context -> context
      )
    )
