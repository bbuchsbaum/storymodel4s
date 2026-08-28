package storymodel4s.align

import storymodel4s.core.OpaqueId
import storymodel4s.features.{Coverage, Estimate, MissingReason}

/** Identity of one rememberer in a population analysis. */
object SubjectId extends OpaqueId("SubjectId")
type SubjectId = SubjectId.T

/** One subject's alignment against the shared source, plus an optional verbosity offset. */
final case class SubjectAlignment(subject: SubjectId, result: HsmmResult, wordCount: Option[Int])

/** A sparse row-major matrix with string row/column identities; only nonzero entries are stored. */
final case class SparseMatrix(
    rowIds: Vector[String],
    colIds: Vector[String],
    entries: Map[(Int, Int), Double]
):
  def apply(row: Int, col: Int): Double = entries.getOrElse((row, col), 0.0)
  def nnz: Int = entries.size

  /** Entries in deterministic (row, col) order. */
  def sorted: Vector[((Int, Int), Double)] = entries.toVector.sortBy(_._1)

/** Visitation rate of one node over the subjects that had any source mass at all. */
final case class VisitationRate(rate: Estimate[Double], coverage: Coverage)

/** Source relations of a layer that survive in the population (design record §11). */
final case class SurvivingRelations(
    layer: RelationLayer,
    threshold: Double,
    surviving: Vector[(SourceNodeRef, SourceNodeRef, Double)],
    total: Int
):
  def fraction: Estimate[Double] =
    if total == 0 then Estimate.Missing(MissingReason.AllMissing)
    else Estimate.observed(surviving.size.toDouble / total)

/** Backward mass of the population recall flow as a fraction of all source→source flow. */
final case class BackwardFlow(discourse: Estimate[Double], world: Option[Estimate[Double]])

/** Multi-subject aggregate over alignments of the same source (design record §11).
  *
  *   - fuzzy visitation `Y_sv = 1 − exp(−Σ_i P_siv)`;
  *   - population recall flow `F̄_vw = Σ_s Σ_i F_s,i,v,w`;
  *   - retrieval hubs, surviving relations, and a sparse subject×node matrix for downstream
  *     population models.
  *
  * Everything is computed from the per-subject posteriors and flows only; the source graph is the
  * reference and the population flow is its learned deformation, never a replacement. All sums use
  * sorted keys so results are bit-reproducible. No dense subject×node×node structure is allocated.
  */
final case class PopulationAggregate private (view: SourceView, subjects: Vector[SubjectAlignment]):

  /** Subjects in deterministic order. */
  lazy val subjectIds: Vector[SubjectId] = subjects.map(_.subject).sorted

  /** Alignable nodes in deterministic order. */
  lazy val nodeRefs: Vector[SourceNodeRef] = view.nodes.map(_.ref).sortBy(_.key)

  private lazy val bySubject: Map[SubjectId, SubjectAlignment] =
    subjects.iterator.map(s => s.subject -> s).toMap

  private lazy val columnMassBySubject: Map[SubjectId, Map[SourceNodeRef, Double]] =
    subjectIds.map(s => s -> bySubject(s).result.posterior.columnMass).toMap

  private def subjectSourceMass(s: SubjectId): Double =
    bySubject(s).result.posterior.rows.map(_.sourceMass).sum

  /** Subjects that placed any mass on any source node. */
  lazy val groundedSubjects: Vector[SubjectId] = subjectIds.filter(subjectSourceMass(_) > 0.0)

  // ---- per node -----------------------------------------------------------------------------

  /** `Σ_s Σ_i P_siv`. */
  def columnMass(v: SourceNodeRef): Double =
    subjectIds.map(s => columnMassBySubject(s).getOrElse(v, 0.0)).sum

  /** `Y_sv = 1 − exp(−Σ_i P_siv)` for every subject (zero when the subject put no mass on `v`). */
  def visitation(v: SourceNodeRef): Map[SubjectId, Double] =
    subjectIds.map { s =>
      val m = columnMassBySubject(s).getOrElse(v, 0.0)
      s -> (if m <= 0.0 then 0.0 else 1.0 - math.exp(-m))
    }.toMap

  /** `Σ_s Y_sv`, bounded by the number of subjects. */
  def expectedVisits(v: SourceNodeRef): Double =
    visitation(v).toVector.sortBy(_._1.value).map(_._2).sum

  /** Mean visitation over grounded subjects; `Missing` when no subject had source mass. */
  def visitationRate(v: SourceNodeRef): VisitationRate =
    val eligible = groundedSubjects
    val coverage = Coverage.unsafe(subjectIds.size, eligible.size)
    if eligible.isEmpty then VisitationRate(Estimate.Missing(MissingReason.AllMissing), coverage)
    else
      val y = visitation(v)
      VisitationRate(Estimate.observed(eligible.map(y).sum / eligible.size), coverage)

  /** Total posterior mass placed at a hierarchy level, per subject. */
  def massAtLevelBySubject(level: Int): Map[SubjectId, Double] =
    subjectIds.map { s =>
      s -> bySubject(s).result.posterior.rows.map(_.massAtLevel(view, level)).sum
    }.toMap

  /** Total posterior mass placed at a hierarchy level over the population. */
  def massAtLevel(level: Int): Double =
    massAtLevelBySubject(level).toVector.sortBy(_._1.value).map(_._2).sum

  // ---- per subject --------------------------------------------------------------------------

  def sourceMass(s: SubjectId): Option[Double] = bySubject.get(s).map(_ => subjectSourceMass(s))

  /** Mass on each explicit external state, including `Unranked`. */
  def externalMass(s: SubjectId): Option[Map[ExternalState, Double]] =
    bySubject.get(s).map { a =>
      ExternalState.values.toVector.map { e =>
        e -> a.result.posterior.rows.map(_.externalMass(e)).sum
      }.toMap
    }

  // ---- flow ---------------------------------------------------------------------------------

  /** `F̄_vw = Σ_s Σ_i F_s,i,v,w` over source→source moves, sparse. */
  lazy val populationFlow: Map[(SourceNodeRef, SourceNodeRef), Double] =
    subjectIds
      .flatMap { s =>
        bySubject(s).result.flow.steps.flatMap { step =>
          step.mass.toVector.collect {
            case ((AlignState.Source(a), AlignState.Source(b)), m) if m > 0.0 => ((a, b), m)
          }
        }
      }
      .sortBy { case ((a, b), _) => (a.key, b.key) }
      .groupMapReduce(_._1)(_._2)(_ + _)

  private lazy val sortedFlow: Vector[((SourceNodeRef, SourceNodeRef), Double)] =
    populationFlow.toVector.sortBy { case ((a, b), _) => (a.key, b.key) }

  /** Total source→source flow mass in the population. */
  lazy val totalFlow: Double = sortedFlow.map(_._2).sum

  /** In-flow per node from *other* nodes (self-transitions are dwell, not retrieval). */
  lazy val inFlow: Map[SourceNodeRef, Double] =
    sortedFlow.collect { case ((a, b), m) if a != b => (b, m) }.groupMapReduce(_._1)(_._2)(_ + _)

  /** The `k` nodes receiving the most in-flow; ties broken by key. */
  def hubs(k: Int): Vector[(SourceNodeRef, Double)] =
    inFlow.toVector.sortBy { case (r, m) => (-m, r.key) }.take(math.max(0, k))

  /** Source edges of `layer` whose endpoints both have visitation rate ≥ `threshold`. */
  def survivingRelations(layer: RelationLayer, threshold: Double): SurvivingRelations =
    val edges = view.adjacency(layer).toVector.sortBy(_._1.key).flatMap { case (a, m) =>
      m.toVector.sortBy(_._1.key).collect { case (b, w) if w > 0.0 => (a, b, w) }
    }
    def rate(r: SourceNodeRef): Option[Double] = visitationRate(r).rate.toOption
    val surviving = edges.filter { case (a, b, _) =>
      (rate(a), rate(b)) match
        case (Some(x), Some(y)) => x >= threshold && y >= threshold
        case _                  => false
    }
    SurvivingRelations(layer, threshold, surviving, edges.size)

  /** Backward mass of the population flow in discourse order and (when known) world order, as a
    * fraction of all source→source flow; moves to an ancestor do not count as backward (§12.3).
    */
  lazy val backwardFlowMass: BackwardFlow =
    def isBackward(pos: SourceNodeRef => Option[Double])(a: SourceNodeRef, b: SourceNodeRef) =
      a != b && !view.isAncestor(b, a) && ((pos(a), pos(b)) match
        case (Some(x), Some(y)) => y < x
        case _                  => false)
    def fraction(pos: SourceNodeRef => Option[Double]): Estimate[Double] =
      if totalFlow <= 0.0 then Estimate.Missing(MissingReason.AllMissing)
      else
        val bw = sortedFlow.collect { case ((a, b), m) if isBackward(pos)(a, b) => m }.sum
        Estimate.observed(bw / totalFlow)
    val discoursePos: SourceNodeRef => Option[Double] = r => Some(view.relativePosition(r))
    val worldPos: Option[SourceNodeRef => Option[Double]] =
      view.worldOrder.map(o => r => o.get(r).map(_.toDouble))
    BackwardFlow(fraction(discoursePos), worldPos.map(fraction))

  // ---- export -------------------------------------------------------------------------------

  /** Subject × node visitation `Y_sv` as a sparse matrix (rows subjects, columns nodes). */
  lazy val visitationMatrix: SparseMatrix =
    val colIndex = nodeRefs.zipWithIndex.toMap
    val entries = subjectIds.zipWithIndex.flatMap { case (s, i) =>
      columnMassBySubject(s).toVector.sortBy(_._1.key).flatMap { case (v, m) =>
        colIndex.get(v).filter(_ => m > 0.0).map(j => (i, j) -> (1.0 - math.exp(-m)))
      }
    }.toMap
    SparseMatrix(subjectIds.map(_.value), nodeRefs.map(_.key), entries)

object PopulationAggregate:

  /** Aggregate alignments that all target `view`. Fails when subject ids repeat, when a result
    * references a source node absent from the view, or when a posterior row is malformed.
    */
  def of(
      view: SourceView,
      subjects: Vector[SubjectAlignment]
  ): Either[AlignError, PopulationAggregate] =
    val ids = subjects.map(_.subject)
    val dup = ids.groupBy(identity).collect { case (id, xs) if xs.size > 1 => id }.toVector.sorted
    val known = view.nodes.map(_.ref).toSet
    def sourceRefs(r: HsmmResult): Vector[SourceNodeRef] =
      val fromRows = r.posterior.rows.flatMap(_.mass.keys.toVector.collect {
        case AlignState.Source(ref) => ref
      })
      val fromFlow = r.flow.steps.flatMap(_.mass.keys.toVector.flatMap { case (a, b) =>
        Vector(a, b).collect { case AlignState.Source(ref) => ref }
      })
      (fromRows ++ fromFlow).distinct.sortBy(_.key)
    val unknown = subjects.flatMap { s =>
      sourceRefs(s.result).filterNot(known.contains).map(r => (s.subject, r))
    }
    val malformed = subjects.flatMap { s =>
      s.result.posterior.rows.filterNot(_.isWellFormed).map(row => (s.subject, row.unit))
    }
    if subjects.isEmpty then Left(AlignError.SizeMismatch("population has no subjects"))
    else if dup.nonEmpty then
      Left(AlignError.SizeMismatch(s"duplicate subject ids: ${dup.map(_.value).mkString(", ")}"))
    else if unknown.nonEmpty then
      val (s, r) = unknown.head
      Left(AlignError.SizeMismatch(s"subject ${s.value} references ${r.key}, absent from the view"))
    else if malformed.nonEmpty then
      val (s, u) = malformed.head
      Left(AlignError.MalformedRow(u, s"subject ${s.value}: mass must be nonnegative and finite"))
    else Right(new PopulationAggregate(view, subjects.sortBy(_.subject.value)))
