package storymodel4s.align

import cats.syntax.all.*
import storymodel4s.core.Checksum
import storymodel4s.recall.RecallUnitId

/** Conditional transition arithmetic keeps original adjacencies and coherent ambiguity bounds. */
object OrganizationReadout:
  enum Category:
    case Backward, Forward, SameTarget, External, Incomparable, Unresolved, Failed
  enum RowStatus:
    case Available, Unresolved, Failed
  enum Information:
    case UniformRows, DistinguishedRows, Unavailable
  enum Refusal:
    case InvalidRows, InvalidMass, InvalidOrder, InvalidJoint, MissingJoint, CoupledBoundsUnsupported

  /** Supplied numerical input is checked data, never a certificate of reference measurement. */
  final class Row private (
      val unit: RecallUnitId,
      val status: RowStatus,
      val mass: Map[Destination, Double],
      val admissible: Vector[Destination]
  ):
    override def equals(other: Any): Boolean = other match
      case r: Row => unit == r.unit && status == r.status && mass == r.mass && admissible == r.admissible
      case _ => false
    override def hashCode: Int = (unit, status, mass, admissible).hashCode
    override def toString: String = s"OrganizationRow(${unit.value}, $status)"
  object Row:
    def of(unit: RecallUnitId, mass: Map[Destination, Double], admissible: Vector[Destination]): Either[Refusal, Row] =
      if mass.isEmpty || !mass.values.forall(v => v.isFinite && v >= 0) ||
          math.abs(mass.values.sum - 1.0) > MappingTolerance.RowSum then Left(Refusal.InvalidMass)
      else if admissible.isEmpty || admissible.distinct.size != admissible.size ||
          !admissible.forall(mass.contains) then Left(Refusal.InvalidRows)
      else Right(new Row(unit, RowStatus.Available, mass, admissible.sorted))
    def unavailable(unit: RecallUnitId, failed: Boolean = false): Row =
      new Row(unit, if failed then RowStatus.Failed else RowStatus.Unresolved, Map.empty, Vector.empty)

  /** Explicit comparable positions are separate from scoring and from inference. */
  final class Order private (
      val positions: Map[SourceNodeRef, Double],
      val source: Option[SourceRepresentation],
      val axis: MeasurementCompatibility.Axis
  ):
    val digest: Checksum = MappingRender.digest(Vector("organization-order/v1") ++
      Vector(axis.toString, source.fold("")(_.digest.hex)) ++
      positions.toVector.sortBy(_._1.key).flatMap((r, p) => Vector(r.key, p.toString)))
    private[OrganizationReadout] def comparable(a: SourceNodeRef, b: SourceNodeRef): Boolean =
      source.forall { s =>
        val left = s.target(a).toVector.flatMap(_.axisMembership.keys)
        val right = s.target(b).toVector.flatMap(_.axisMembership.keys)
        axis == MeasurementCompatibility.Axis.StoryWorld || left.isEmpty || right.isEmpty ||
          left.forall(x => right.forall(y => s.order(x, y) != PartOrder.Incomparable))
      }
    override def equals(other: Any): Boolean = other match
      case o: Order => digest == o.digest
      case _ => false
    override def hashCode: Int = digest.hashCode
    override def toString: String = s"OrganizationOrder(${positions.size})"
  object Order:
    def declared(entries: Vector[(SourceNodeRef, Double)]): Either[Refusal, Order] =
      if entries.map(_._1).distinct.size != entries.size || !entries.forall(_._2.isFinite) then
        Left(Refusal.InvalidOrder)
      else Right(new Order(entries.toMap, None, MeasurementCompatibility.Axis.Discourse))
    def onSource(entries: Vector[(SourceNodeRef, Double)], source: SourceRepresentation, axis: MeasurementCompatibility.Axis): Either[Refusal, Order] =
      declared(entries).flatMap { _ =>
        if !entries.forall((r, _) => source.target(r).nonEmpty) then Left(Refusal.InvalidOrder)
        else Right(new Order(entries.toMap, Some(source), axis))
      }

  /** A supplied adjacent joint must reproduce both of its actual row marginals. */
  final class Joint private (
      val left: RecallUnitId,
      val right: RecallUnitId,
      val mass: Map[(Destination, Destination), Double]
  )
  object Joint:
    def of(left: Row, right: Row, mass: Map[(Destination, Destination), Double]): Either[Refusal, Joint] =
      def agrees(expected: Map[Destination, Double], actual: Map[Destination, Double]): Boolean =
        (expected.keySet ++ actual.keySet).forall(k =>
          math.abs(expected.getOrElse(k, 0.0) - actual.getOrElse(k, 0.0)) <= MappingTolerance.RowSum)
      if left.status != RowStatus.Available || right.status != RowStatus.Available ||
          !mass.values.forall(v => v.isFinite && v >= 0) ||
          !mass.keys.forall((a, b) => left.mass.contains(a) && right.mass.contains(b)) ||
          !agrees(left.mass, mass.toVector.groupMapReduce(_._1._1)(_._2)(_ + _)) ||
          !agrees(right.mass, mass.toVector.groupMapReduce(_._1._2)(_._2)(_ + _)) then
        Left(Refusal.InvalidJoint)
      else Right(new Joint(left.unit, right.unit, mass))

  /** Independence is a declared numerical assumption; supplied joints retain their dependence. */
  enum Dependence:
    case DeclaredIndependentRows
    case AdjacentJoints(joints: Vector[Joint])

  /** Each transition retains its two original unit IDs, including gaps and processing failures. */
  final class Transition private[OrganizationReadout] (
      val left: RecallUnitId,
      val right: RecallUnitId,
      val counts: Map[Category, Double]
  )
  /** Extrema share one assignment at every middle unit; no pairwise-extrema shortcut. */
  final class Bounds private[OrganizationReadout] (val minimum: Int, val maximum: Int)
  /** An explicitly conditional result; normalized-score counts are not calibrated probabilities. */
  final class Result private[OrganizationReadout] (
      val rows: Vector[Row],
      val order: Order,
      val dependence: Dependence,
      val transitions: Vector[Transition],
      val bounds: Option[Map[Category, Bounds]]
  ):
    val counts: Map[Category, Double] = Category.values.toVector.map(c =>
      c -> transitions.map(_.counts.getOrElse(c, 0.0)).sum).toMap
    val comparable: Double = Vector(Category.Backward, Category.Forward, Category.SameTarget).map(counts).sum
    val backwardRate: Option[Double] = Option.when(comparable > 0)(counts(Category.Backward) / comparable)
    val measuredUnits: Int = rows.count(_.status == RowStatus.Available)
    val measuredTransitions: Int = transitions.count(t =>
      t.counts(Category.Unresolved) == 0 && t.counts(Category.Failed) == 0)
    val information: Information =
      if measuredUnits == 0 then Information.Unavailable
      else if rows.filter(_.status == RowStatus.Available).forall(r =>
          r.mass.size > 1 && r.mass.values.toVector.distinct.size == 1) then Information.UniformRows
      else Information.DistinguishedRows
    override def toString: String = s"OrganizationResult(${rows.size} units, ${transitions.size} adjacencies)"

  def category(a: Destination, b: Destination, order: Order): Category = (a, b) match
    case (Destination.External(ExternalState.Unranked), _) | (_, Destination.External(ExternalState.Unranked)) => Category.Unresolved
    case (Destination.External(_), _) | (_, Destination.External(_)) => Category.External
    case (Destination.Target(x), Destination.Target(y)) =>
      if x == y then Category.SameTarget
      else if !order.comparable(x, y) then Category.Incomparable
      else (order.positions.get(x), order.positions.get(y)) match
        case (Some(p), Some(q)) if q < p => Category.Backward
        case (Some(p), Some(q)) if q > p => Category.Forward
        case _ => Category.Incomparable

  private def zero: Map[Category, Double] = Category.values.toVector.map(_ -> 0.0).toMap

  /** No rows disappear before adjacency construction. Coupled inputs cannot request product bounds. */
  def compute(rows: Vector[Row], order: Order, dependence: Dependence, productBounds: Boolean = true): Either[Refusal, Result] =
    val pairs = rows.zip(rows.drop(1))
    val joints = dependence match
      case Dependence.AdjacentJoints(js) => js.map(j => (j.left -> j.right) -> j).toMap
      case _ => Map.empty[(RecallUnitId, RecallUnitId), Joint]
    if rows.map(_.unit).distinct.size != rows.size then Left(Refusal.InvalidRows)
    else if productBounds && dependence != Dependence.DeclaredIndependentRows then Left(Refusal.CoupledBoundsUnsupported)
    else if dependence match
        case Dependence.AdjacentJoints(js) => js.size != joints.size ||
          joints.keySet != pairs.collect { case (a, b) if a.status == RowStatus.Available && b.status == RowStatus.Available => a.unit -> b.unit }.toSet
        case _ => false
      then Left(Refusal.MissingJoint)
    else
      pairs.traverse { (a, b) =>
        if a.status == RowStatus.Failed || b.status == RowStatus.Failed then
          Right(new Transition(a.unit, b.unit, zero.updated(Category.Failed, 1.0)))
        else if a.status != RowStatus.Available || b.status != RowStatus.Available then
          Right(new Transition(a.unit, b.unit, zero.updated(Category.Unresolved, 1.0)))
        else
          def add(acc: Map[Category, Double], x: Destination, y: Destination, weight: Double): Map[Category, Double] =
            val c = category(x, y, order)
            acc.updated(c, acc(c) + weight)
          dependence match
            case Dependence.DeclaredIndependentRows =>
              val counts = a.mass.toVector.sortBy(_._1.key).foldLeft(zero) { case (acc, (x, p)) =>
                b.mass.toVector.sortBy(_._1.key).foldLeft(acc) { case (next, (y, q)) => add(next, x, y, p * q) }
              }
              Right(new Transition(a.unit, b.unit, counts))
            case _ =>
              Joint.of(a, b, joints(a.unit -> b.unit).mass).map { joint =>
                val counts = joint.mass.toVector.sortBy((k, _) => (k._1.key, k._2.key)).foldLeft(zero) {
                  case (acc, ((x, y), weight)) => add(acc, x, y, weight)
                }
                new Transition(a.unit, b.unit, counts)
              }
      }.map { transitions =>
        val bounds = if !productBounds || rows.exists(_.status != RowStatus.Available) then None
          else Some(Category.values.toVector.map(c => c -> extrema(rows, order, c)).toMap)
        new Result(rows, order, dependence, transitions, bounds)
      }

  private def extrema(rows: Vector[Row], order: Order, wanted: Category): Bounds =
    if rows.isEmpty then new Bounds(0, 0)
    else
      val initial = rows.head.admissible.map(_ -> (0, 0)).toMap
      val last = rows.tail.foldLeft(initial) { (previous, row) =>
        row.admissible.map { b =>
          val candidates = previous.toVector.map { (a, range) =>
            val inc = if category(a, b, order) == wanted then 1 else 0
            (range._1 + inc, range._2 + inc)
          }
          b -> (candidates.map(_._1).min, candidates.map(_._2).max)
        }.toMap
      }
      new Bounds(last.values.map(_._1).min, last.values.map(_._2).max)
