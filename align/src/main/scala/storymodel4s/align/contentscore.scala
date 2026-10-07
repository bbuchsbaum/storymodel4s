package storymodel4s.align

import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.proposition.{ContentCompatibilityReport, GateReading, GraphOrder}
import storymodel4s.proposition.SemanticCompatibility
import storymodel4s.recall.{Lexical, ModalityTag, PolarityTag, SketchRole}

/** The structural scorers over content types (ADR 0019 S2; mote bd-01M379MH86VMN6SNVNRH32G3G6).
  *
  * Why here: each scorer is implemented once, over [[UnitContent]] and [[TargetContent]]. The
  * historical entry points ([[ContradictionDetector]]) project under `Source` and delegate, so the
  * historical output is this code's output. The strict path projects under `Canonical`.
  */
object ContentScoring:

  /** Chart facets: the contradictions a chart comparison decides when both charts exist. */
  private val ChartFacets: Set[Contradiction] =
    Set(Contradiction.RoleReversal, Contradiction.PolarityConflict, Contradiction.ContextConflict)

  private def fromReading(g: GateReading): Vector[Contradiction] =
    Vector(
      Option.when(g.roleReversal)(Contradiction.RoleReversal),
      Option.when(g.polarityConflict)(Contradiction.PolarityConflict),
      Option.when(g.embeddingConflict)(Contradiction.ContextConflict)
    ).flatten

  /** The chart comparison, when both sides carry a chart. */
  def report[O <: GraphOrder](
      u: UnitContent[O],
      t: TargetContent[O]
  ): Option[ContentCompatibilityReport] =
    for
      a <- u.graph
      b <- t.graph
    yield SemanticCompatibility.compare(a, b)

  /** Contradictions between a unit and one node: chart facets from the charts when both exist,
    * sketch facets otherwise; modality and outcome always from the sketch.
    *
    * `Left` carries the gate readings when equally good chart readings disagree on which gates are
    * raised. Choosing one reading would read an order, and taking every gate would claim conflicts
    * no single reading has, so the pair is not scored (Fray #107 question (c), pending). Under
    * `Source` every comparison has one reading, so the historical path never sees `Left`.
    */
  def contradictions[O <: GraphOrder](
      u: UnitContent[O],
      t: TargetContent[O]
  ): Either[Set[GateReading], Vector[Contradiction]] =
    report(u, t) match
      case Some(r) if r.gatesAmbiguous => Left(r.gateReadings)
      case Some(r)                     =>
        val fromSketch = sketchContradictions(u, t).filterNot(ChartFacets.contains)
        Right((fromReading(r.gateReadings.head) ++ fromSketch).distinct)
      case None => Right(sketchContradictions(u, t))

  /** Whether the unit engages the node at all: charts with any matched head engage; otherwise the
    * sketch shares the node's predicate or contradicts it. Decides which leaves count when a
    * segment inherits contradictions.
    */
  def engages[O <: GraphOrder](
      u: UnitContent[O],
      t: TargetContent[O]
  ): Either[Set[GateReading], Boolean] =
    report(u, t) match
      case Some(r) => contradictions(u, t).map(cs => r.matched || cs.nonEmpty)
      case None    =>
        Right(
          u.predicate.exists(p => t.predicate.contains(p)) || sketchContradictions(u, t).nonEmpty
        )

  private def sequence[L, R](xs: Vector[Either[L, R]]): Either[L, Vector[R]] =
    xs.foldLeft[Either[L, Vector[R]]](Right(Vector.empty))((acc, x) =>
      acc.flatMap(v => x.map(v :+ _))
    )

  /** Which modes a unit may occupy on a target. A leaf takes its own contradictions. A segment
    * inherits contradictions only when every member the unit engages contradicts it; otherwise the
    * unit may occupy it faithfully.
    *
    * `arrange` fixes the order of the inherited contradictions: the historical path keeps the
    * members' storage order, and the canonical path orders them by content.
    */
  private[align] def modeGateWith[O <: GraphOrder](
      u: UnitContent[O],
      t: TargetContent[O],
      arrange: Vector[Contradiction] => Vector[Contradiction]
  ): Either[Set[GateReading], Admissibility] =
    if t.isLeaf then contradictions(u, t).map(cs => Admissibility.of(arrange(cs)))
    else
      for
        flags <- sequence(t.members.leaves.map(m => engages(u, m).map(m -> _)))
        engaged = flags.collect { case (m, true) => m }
        reports <- sequence(engaged.map(contradictions(u, _)))
      yield
        if engaged.nonEmpty && reports.forall(_.nonEmpty) then
          Admissibility.of(arrange(reports.flatten))
        else Admissibility.faithfulOnly

  /** The strict mode gate. Inherited contradictions are ordered by content, never by member storage
    * order.
    */
  def modeGate(
      u: UnitContent[GraphOrder.Canonical],
      t: TargetContent[GraphOrder.Canonical]
  ): Either[Set[GateReading], Admissibility] =
    modeGateWith(u, t, _.sortBy(_.ordinal))

  /** One structural reduction before the caller names its members: the aggregate, the members that
    * were scored, and the members excluded by a contradiction, each under the caller's handle.
    */
  private[align] final case class Reduced[H](
      estimate: Estimate[Double],
      scored: Vector[(H, Estimate[Double])],
      excluded: Vector[(H, Set[Contradiction])]
  )

  /** Structural reduction, the one implementation. A charted member that contradicts the unit is
    * excluded; the others are scored by `estimate` and reduced by the minimum. `H` is the caller's
    * handle on a member: the historical path passes the node, to key its receipt and feed an
    * injected provider; the strict path passes the content itself.
    */
  private[align] def reduceMembers[O <: GraphOrder, H](
      u: UnitContent[O],
      members: Vector[(H, TargetContent[O])],
      estimate: (H, TargetContent[O]) => Estimate[Double]
  ): Either[Set[GateReading], Reduced[H]] =
    if u.graph.isEmpty then
      Right(Reduced(Estimate.missing(MissingReason.ProviderAbstained), Vector.empty, Vector.empty))
    else
      val charted = members.filter(_._2.hasEvidence)
      sequence(charted.map((h, m) => contradictions(u, m).map(cs => (h, m, cs.toSet)))).map {
        classified =>
          val scored = classified.collect {
            case (h, m, cs) if cs.isEmpty => h -> finite(estimate(h, m))
          }
          val excluded = classified.collect { case (h, _, cs) if cs.nonEmpty => h -> cs }
          val observed = scored.flatMap(_._2.toOption)
          val aggregate =
            if charted.isEmpty || scored.isEmpty then Estimate.missing(MissingReason.Excluded)
            else if scored.forall(!_._2.isEligible) then Estimate.Ineligible
            else
              Reducer
                .reduce(observed)
                .fold[Estimate[Double]](Estimate.missing(MissingReason.ProviderAbstained))(
                  Estimate.observed
                )
          Reduced(aggregate, scored, excluded)
      }

  /** The member reducer, named in every structural receipt. */
  private[align] val Reducer: StructuralReducer = StructuralReducer.Minimum

  private[align] def finite(estimate: Estimate[Double]): Estimate[Double] = estimate match
    case Estimate.Observed(value, credence) => Estimate.score(value, credence)
    case missing @ Estimate.Missing(_)      => missing
    case Estimate.Ineligible                => Estimate.Ineligible

  /** The strict `d_chart`: one minus the chart comparison's structural score, reduced over the
    * target's members. A function of the members' multiset: the minimum does not read their order.
    */
  def chartReduction(
      u: UnitContent[GraphOrder.Canonical],
      t: TargetContent[GraphOrder.Canonical]
  ): Either[Set[GateReading], Estimate[Double]] =
    reduceMembers(
      u,
      t.members.structural.map(m => m -> m),
      (_, m) =>
        (for
          a <- u.graph
          b <- m.graph
        yield Estimate.observed(1.0 - SemanticCompatibility.compare(a, b).structuralScore))
          .getOrElse(Estimate.missing(MissingReason.ProviderAbstained))
    ).map(_.estimate)

  /** The recall slots the sketch rules read, whatever they were projected from. */
  private[align] final case class RecallSlots(
      predicate: Option[String],
      agent: Option[Set[String]],
      patient: Option[Set[String]],
      polarity: PolarityTag,
      modality: ModalityTag,
      outcome: Option[String]
  )

  /** The source slots the sketch rules read. */
  private[align] final case class SourceSlots(
      predicate: Option[String],
      agent: Option[Set[String]],
      patient: Option[Set[String]],
      context: ContextTag,
      polarity: PolarityTag,
      modality: ModalityTag,
      outcome: Option[String]
  )

  private def patientOf[P](byRole: SketchRole => Option[P]): Option[P] =
    byRole(SketchRole.Patient)
      .orElse(byRole(SketchRole.Theme))
      .orElse(byRole(SketchRole.Beneficiary))

  private[align] def slots[O <: GraphOrder](u: UnitContent[O]): RecallSlots =
    RecallSlots(
      u.predicate,
      u.byRole(SketchRole.Agent).map(_.names),
      patientOf(u.byRole).map(_.names),
      u.polarity,
      u.modality,
      u.outcome
    )

  private[align] def slots[O <: GraphOrder](t: TargetContent[O]): SourceSlots =
    SourceSlots(
      t.predicate,
      t.byRole(SketchRole.Agent).map(_.names),
      patientOf(t.byRole).map(_.names),
      t.context,
      t.polarity,
      t.modality,
      t.outcome
    )

  private val Unrealized =
    Set(ModalityTag.Intended, ModalityTag.Desired, ModalityTag.Counterfactual)

  private def sketchContradictions[O <: GraphOrder](
      u: UnitContent[O],
      t: TargetContent[O]
  ): Vector[Contradiction] = sketchRules(slots(u), slots(t))

  /** The sketch rules, the one implementation. Each fires only when the compared slots are both
    * specified.
    */
  private[align] def sketchRules(u: RecallSlots, t: SourceSlots): Vector[Contradiction] =
    val predicateMatch = u.predicate.exists(p => t.predicate.exists(_ == p))
    val out = Vector.newBuilder[Contradiction]

    // Role reversal: the recalled patient is the source agent (and the recalled agent is not),
    // or the recalled agent is the source patient (and the recalled patient is not).
    def overlaps(x: Option[Set[String]], y: Option[Set[String]]) =
      (x, y) match
        case (Some(p), Some(q)) => Names.overlap(p, q)
        case _                  => false
    val patientIsNodeAgent = overlaps(u.patient, t.agent)
    val agentIsNodeAgent = overlaps(u.agent, t.agent)
    val agentIsNodePatient = overlaps(u.agent, t.patient)
    val patientIsNodePatient = overlaps(u.patient, t.patient)
    val reversed =
      (patientIsNodeAgent && u.agent.nonEmpty && !agentIsNodeAgent) ||
        (agentIsNodePatient && u.patient.nonEmpty && !patientIsNodePatient)
    val bothInverted = patientIsNodeAgent && agentIsNodePatient
    if bothInverted || (reversed && predicateMatch) then out += Contradiction.RoleReversal

    if predicateMatch && u.polarity != PolarityTag.Unknown &&
      t.polarity != PolarityTag.Unknown && u.polarity != t.polarity
    then out += Contradiction.PolarityConflict

    if predicateMatch && u.modality == ModalityTag.Asserted &&
      t.context != ContextTag.NarratedWorld
    then out += Contradiction.ContextConflict

    val modalityConflict =
      (u.modality == ModalityTag.Asserted && Unrealized.contains(t.modality)) ||
        (Unrealized.contains(u.modality) && t.modality == ModalityTag.Asserted)
    if predicateMatch && modalityConflict then out += Contradiction.ModalityConflict

    (u.outcome, t.outcome) match
      case (Some(a), Some(b)) if predicateMatch && Lexical.lower(a) != Lexical.lower(b) =>
        out += Contradiction.OutcomeConflict
      case _ => ()

    out.result()
