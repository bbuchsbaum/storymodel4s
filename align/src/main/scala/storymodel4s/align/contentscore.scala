package storymodel4s.align

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
