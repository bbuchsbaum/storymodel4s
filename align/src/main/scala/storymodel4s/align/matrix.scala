package storymodel4s.align

import storymodel4s.recall.RecallUnitId

/** Explicit open-world destinations for recall mass that is not source-grounded. */
enum ExternalState:
  case Association, Commentary, SourceConsistentInference, Intrusion, Uninterpretable

/** A column of the alignment: a source node or an external state. */
enum AlignState:
  case Source(ref: SourceNodeRef)
  case External(state: ExternalState)

  def key: String = this match
    case Source(ref)     => ref.key
    case External(state) => s"ext:${state.toString}"

  def isSource: Boolean = this match
    case Source(_) => true
    case _         => false

object AlignState:
  given Ordering[AlignState] = Ordering.by(_.key)
  val externals: Vector[AlignState] = ExternalState.values.toVector.map(External(_))

/** One row of `P`: posterior mass of a recall unit over source nodes and external states. Rows are
  * unbalanced by construction: `sourceMass + externalMass = 1` when produced by the HSMM, but rows
  * from other aligners may sum to less than 1.
  */
final case class AlignmentRow(unit: RecallUnitId, mass: Map[AlignState, Double]):
  def apply(state: AlignState): Double = mass.getOrElse(state, 0.0)
  def total: Double = mass.values.sum
  def sourceMass: Double = mass.collect { case (AlignState.Source(_), m) => m }.sum
  def externalMass: Double = mass.collect { case (AlignState.External(_), m) => m }.sum
  def externalMass(state: ExternalState): Double = apply(AlignState.External(state))
  def sourceMassOn(ref: SourceNodeRef): Double = apply(AlignState.Source(ref))

  /** Entropy (nats) of the normalized row. */
  def entropy: Double =
    val z = total
    if z <= 0.0 then 0.0
    else -mass.values.map(_ / z).filter(_ > 0.0).map(p => p * math.log(p)).sum

  /** `1 − H / log K` over the `K` states with positive mass; 1 when a single state holds it all. */
  def localizability: Double =
    val k = mass.count(_._2 > 0.0)
    if k <= 1 then 1.0 else 1.0 - entropy / math.log(k.toDouble)

  def topK(n: Int): Vector[(AlignState, Double)] =
    mass.toVector.sortBy { case (s, m) => (-m, s.key) }.take(n)

  def argmax: Option[AlignState] = topK(1).headOption.map(_._1)

  /** Highest-mass source node, if any source mass exists. */
  def mapSource: Option[SourceNodeRef] =
    mass
      .collect { case (AlignState.Source(r), m) if m > 0.0 => (r, m) }
      .toVector
      .sortBy { case (r, m) => (-m, r.key) }
      .headOption
      .map(_._1)

  def massAtLevel(view: SourceView, level: Int): Double =
    mass.collect {
      case (AlignState.Source(r), m) if view.node(r).exists(_.level == level) => m
    }.sum

  def normalized: AlignmentRow =
    val z = total
    if z <= 0.0 then this else AlignmentRow(unit, mass.view.mapValues(_ / z).toMap)

/** The alignment `P`: one row per recall unit in recall order. */
final case class AlignmentMatrix(rows: Vector[AlignmentRow]):
  lazy val byUnit: Map[RecallUnitId, AlignmentRow] = rows.iterator.map(r => r.unit -> r).toMap
  def row(unit: RecallUnitId): Option[AlignmentRow] = byUnit.get(unit)
  def size: Int = rows.size

  /** Aggregate mass each source node received, `Σ_i P_iv`. */
  def columnMass: Map[SourceNodeRef, Double] =
    rows
      .flatMap(_.mass.collect { case (AlignState.Source(r), m) => (r, m) })
      .groupMapReduce(_._1)(_._2)(_ + _)

  /** Fuzzy visitation `Y_v = 1 − exp(−Σ_i P_iv)` (design record §11). */
  def visitation: Map[SourceNodeRef, Double] =
    columnMass.view.mapValues(m => 1.0 - math.exp(-m)).toMap

/** Transition posteriors between consecutive units: `F_i(s, t)`. */
final case class FlowStep(
    from: RecallUnitId,
    to: RecallUnitId,
    mass: Map[(AlignState, AlignState), Double]
):
  def apply(s: AlignState, t: AlignState): Double = mass.getOrElse((s, t), 0.0)

  /** Row marginal: should equal `P_i`. */
  def fromMarginal: Map[AlignState, Double] =
    mass.groupMapReduce(_._1._1)(_._2)(_ + _)

  /** Column marginal: should equal `P_{i+1}`. */
  def toMarginal: Map[AlignState, Double] =
    mass.groupMapReduce(_._1._2)(_._2)(_ + _)

  /** Mass on source→source moves satisfying `p`. */
  def sourceMass(p: (SourceNodeRef, SourceNodeRef) => Boolean): Double =
    mass.collect { case ((AlignState.Source(a), AlignState.Source(b)), m) if p(a, b) => m }.sum

  def sourceToSourceMass: Double = sourceMass((_, _) => true)

final case class TransitionFlow(steps: Vector[FlowStep]):
  def size: Int = steps.size
