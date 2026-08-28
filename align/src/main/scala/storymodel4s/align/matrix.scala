package storymodel4s.align

import storymodel4s.recall.RecallUnitId

/** Explicit open-world destinations for recall mass that is not source-grounded.
  *
  * `Unranked` is the destination of a unit the aligner could not rank at all (every semantic
  * provider abstained and no lexical/entity hit produced a candidate). It is a statement about the
  * *aligner's* evidence, never about the rememberer, so it is kept apart from `Intrusion`.
  */
enum ExternalState:
  case Association, Commentary, SourceConsistentInference, Intrusion, Uninterpretable, Unranked

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

  /** The external states available to a unit that has source candidates. */
  val externals: Vector[AlignState] =
    ExternalState.values.toVector.filterNot(_ == ExternalState.Unranked).map(External(_))

  /** The single state available to a unit the aligner could not rank. */
  val unranked: AlignState = External(ExternalState.Unranked)

/** One row of `P`: posterior mass of a recall unit over source nodes and external states. Rows are
  * unbalanced by construction: `sourceMass + externalMass = 1` when produced by the HSMM, but rows
  * from other aligners may sum to less than 1. Masses are nonnegative and finite (see
  * [[AlignmentRow.of]]); the case-class constructor is retained for the aligners in this module.
  */
final case class AlignmentRow(unit: RecallUnitId, mass: Map[AlignState, Double]):
  def apply(state: AlignState): Double = mass.getOrElse(state, 0.0)

  /** Deterministic key order for sums (review #30). */
  private def sortedMass: Vector[(AlignState, Double)] = mass.toVector.sortBy(_._1.key)
  def total: Double = sortedMass.map(_._2).sum
  def sourceMass: Double = sortedMass.collect { case (AlignState.Source(_), m) => m }.sum
  def externalMass: Double = sortedMass.collect { case (AlignState.External(_), m) => m }.sum
  def externalMass(state: ExternalState): Double = apply(AlignState.External(state))
  def sourceMassOn(ref: SourceNodeRef): Double = apply(AlignState.Source(ref))

  /** Entropy (nats) of the normalized row. */
  def entropy: Double =
    val z = total
    if z <= 0.0 then 0.0
    else -sortedMass.map(_._2 / z).filter(_ > 0.0).map(p => p * math.log(p)).sum

  /** Entropy (nats) of the source part of the row, renormalized over source mass. */
  def sourceEntropy: Double =
    val z = sourceMass
    if z <= 0.0 then 0.0
    else
      -sortedMass
        .collect { case (AlignState.Source(_), m) if m > 0.0 => m / z }
        .map(p => p * math.log(p))
        .sum

  /** Localizability `1 − H_source / log K` where `K` is the number of alignable source nodes of the
    * story (design record §12.1), so values are comparable across units. `None` when the unit has
    * no source mass; external mass is reported separately.
    */
  def localizability(sourceNodeCount: Int): Option[Double] =
    if sourceMass <= 0.0 then None
    else if sourceNodeCount <= 1 then Some(1.0)
    else Some(math.max(0.0, 1.0 - sourceEntropy / math.log(sourceNodeCount.toDouble)))

  def topK(n: Int): Vector[(AlignState, Double)] =
    sortedMass.sortBy { case (s, m) => (-m, s.key) }.take(n)

  def argmax: Option[AlignState] = topK(1).headOption.map(_._1)

  /** Highest-mass source node, if any source mass exists. */
  def mapSource: Option[SourceNodeRef] =
    sortedMass
      .collect { case (AlignState.Source(r), m) if m > 0.0 => (r, m) }
      .sortBy { case (r, m) => (-m, r.key) }
      .headOption
      .map(_._1)

  def massAtLevel(view: SourceView, level: Int): Double =
    sortedMass.collect {
      case (AlignState.Source(r), m) if view.node(r).exists(_.level == level) => m
    }.sum

  def normalized: AlignmentRow =
    val z = total
    if z <= 0.0 then this else AlignmentRow(unit, mass.view.mapValues(_ / z).toMap)

  /** Whether every mass is nonnegative and finite. */
  def isWellFormed: Boolean = mass.values.forall(m => m >= 0.0 && !m.isNaN && !m.isInfinite)

object AlignmentRow:
  /** Smart constructor rejecting negative or non-finite mass. */
  def of(unit: RecallUnitId, mass: Map[AlignState, Double]): Either[AlignError, AlignmentRow] =
    val row = AlignmentRow(unit, mass)
    if row.isWellFormed then Right(row)
    else Left(AlignError.MalformedRow(unit, "mass must be nonnegative and finite"))

/** The alignment `P`: one row per recall unit in recall order. */
final case class AlignmentMatrix(rows: Vector[AlignmentRow]):
  lazy val byUnit: Map[RecallUnitId, AlignmentRow] = rows.iterator.map(r => r.unit -> r).toMap
  def row(unit: RecallUnitId): Option[AlignmentRow] = byUnit.get(unit)
  def size: Int = rows.size

  /** Aggregate mass each source node received, `Σ_i P_iv` (deterministic summation order). */
  def columnMass: Map[SourceNodeRef, Double] =
    rows
      .flatMap(_.mass.toVector.collect { case (AlignState.Source(r), m) => (r, m) })
      .sortBy(_._1.key)
      .groupMapReduce(_._1)(_._2)(_ + _)

  /** Fuzzy visitation `Y_v = 1 − exp(−Σ_i P_iv)` (design record §11). */
  def visitation: Map[SourceNodeRef, Double] =
    columnMass.view.mapValues(m => 1.0 - math.exp(-m)).toMap

  def isWellFormed: Boolean = rows.forall(_.isWellFormed)

/** Transition posteriors between consecutive units: `F_i(s, t)`. */
final case class FlowStep(
    from: RecallUnitId,
    to: RecallUnitId,
    mass: Map[(AlignState, AlignState), Double]
):
  def apply(s: AlignState, t: AlignState): Double = mass.getOrElse((s, t), 0.0)

  private def sorted: Vector[((AlignState, AlignState), Double)] =
    mass.toVector.sortBy { case ((a, b), _) => (a.key, b.key) }

  /** Row marginal: should equal `P_i`. */
  def fromMarginal: Map[AlignState, Double] =
    sorted.groupMapReduce(_._1._1)(_._2)(_ + _)

  /** Column marginal: should equal `P_{i+1}`. */
  def toMarginal: Map[AlignState, Double] =
    sorted.groupMapReduce(_._1._2)(_._2)(_ + _)

  /** Mass on source→source moves satisfying `p`. */
  def sourceMass(p: (SourceNodeRef, SourceNodeRef) => Boolean): Double =
    sorted.collect { case ((AlignState.Source(a), AlignState.Source(b)), m) if p(a, b) => m }.sum

  def sourceToSourceMass: Double = sourceMass((_, _) => true)

final case class TransitionFlow(steps: Vector[FlowStep]):
  def size: Int = steps.size

/** Typed failures of the alignment API (no exceptions escape the aligners). */
enum AlignError:
  case EmptyRecall
  case InvalidConfig(field: String, detail: String)
  case MalformedRow(unit: RecallUnitId, detail: String)
  case SizeMismatch(detail: String)

  def message: String = this match
    case EmptyRecall         => "recall has no units"
    case InvalidConfig(f, m) => s"$f: $m"
    case MalformedRow(u, m)  => s"row ${u.value}: $m"
    case SizeMismatch(m)     => m
