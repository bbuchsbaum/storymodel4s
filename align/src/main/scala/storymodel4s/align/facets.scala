package storymodel4s.align

import cats.Order
import cats.data.NonEmptySet
import storymodel4s.recall.{Lexical, ModalityTag, PropositionSketch, SketchRole}

/** Detail facets assessed conditionally on the inferred source anchor.
  *
  * The first seven are the reportable facets of design record §12.2. The last three name the
  * structural distinctions a recall can get wrong while still denoting the right event: recalling
  * the agent as the patient (`RoleReversal`), negating what was asserted (`Polarity`), or
  * presenting an intended/reported proposition as realized (`Modality`). `Context` records that the
  * recall asserted as narrated fact what the source only reports, believes, or intends — the
  * canonical Bartlett distortion. None of these is ever a gate on the *anchor*: they are the facets
  * of a [[FidelityMode.Distorted]] state (ADR 0001 rev 3 §D5).
  */
enum Facet:
  case Actor, Action, Object, Location, Outcome, Cause, Context, RoleReversal, Polarity, Modality

object Facet:
  given Order[Facet] = Order.by(_.ordinal)
  given Ordering[Facet] = Ordering.by(_.ordinal)

  def parse(s: String): Option[Facet] = values.find(_.toString == s)

/** How a recall unit relates to the source event it is anchored to.
  *
  * `Faithful`: no structural contradiction was detected. `Distorted(facets)`: the unit denotes the
  * anchor but contradicts it on the named facets — recall of the correct event with facet errors
  * (design record §9), not omission plus intrusion. Once `(anchor, mode)` is inadmissible for a
  * unit no score can resurrect it (law L1); the admissible modes are decided by [[ModeGate]] before
  * any graded cost is evaluated.
  */
enum FidelityMode:
  case Faithful
  case Distorted(facets: NonEmptySet[Facet])

  def isFaithful: Boolean = this == Faithful

  def facetSet: Set[Facet] = this match
    case Faithful      => Set.empty
    case Distorted(fs) => fs.toSortedSet.toSet

  /** Canonical rendering: `faithful` or `distorted:<facet>,<facet>` in facet order. */
  def render: String = this match
    case Faithful      => "faithful"
    case Distorted(fs) => "distorted:" + fs.toSortedSet.toVector.map(_.toString).mkString(",")

object FidelityMode:
  given Ordering[FidelityMode] = Ordering.by(_.render)

  def distorted(facets: Iterable[Facet]): Option[FidelityMode] =
    NonEmptySet.fromSet(scala.collection.immutable.SortedSet.from(facets)).map(Distorted(_))

  def parse(s: String): Option[FidelityMode] =
    if s == "faithful" then Some(Faithful)
    else if s.startsWith("distorted:") then
      val names = s.drop("distorted:".length).split(',').toVector.filter(_.nonEmpty)
      val facets = names.map(Facet.parse)
      if facets.forall(_.isDefined) then distorted(facets.flatten) else None
    else None

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
  * separate from anchor uncertainty so "confidently event 5 but wrong patient" is representable.
  */
object FidelityFacets:

  /** Facets conditional on the anchor and its mode: the facets of a `Distorted` mode are `Wrong` by
    * construction (they are what the gate detected), everything else is assessed from the sketch.
    */
  def assess(sketch: PropositionSketch, node: NodeSummary, mode: FidelityMode): FidelityReport =
    val base = assess(sketch, node)
    mode match
      case FidelityMode.Faithful      => base
      case FidelityMode.Distorted(fs) =>
        FidelityReport(base.verdicts ++ fs.toSortedSet.toVector.map(_ -> FacetVerdict.Wrong))

  /** Facets whose verdict is read off the source's propositional content. A view that declares none
    * of it can support no verdict on them, so they abstain wholesale rather than each silently
    * reducing to `Wrong` (actor, action, object, outcome, cause) or to `Correct` (context, whose
    * `ContextTag` has no absent value).
    */
  private val PropositionalFacets: Set[Facet] =
    Set(Facet.Actor, Facet.Action, Facet.Object, Facet.Outcome, Facet.Cause, Facet.Context)

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
    val verdicts = Map(
      Facet.Actor -> actor,
      Facet.Action -> action,
      Facet.Object -> obj,
      Facet.Location -> location,
      Facet.Outcome -> text(sketch.outcome, node.outcome),
      Facet.Cause -> text(sketch.cause, node.cause),
      Facet.Context -> context
    )
    FidelityReport(
      if node.propositional.declares then verdicts
      else
        verdicts.map { (facet, verdict) =>
          facet -> (if PropositionalFacets.contains(facet) then FacetVerdict.Unspecified
                    else verdict)
        }
    )
