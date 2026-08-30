package storymodel4s.align

import cats.data.NonEmptyVector
import storymodel4s.core.{Checksum, OpaqueId}
import storymodel4s.features.{Coverage, Estimate, MissingReason}
import storymodel4s.recall.RecallGraph
import storymodel4s.recall.RecallGraphStatus.Checked

/** Identity of one rememberer in a population analysis. */
object SubjectId extends OpaqueId("SubjectId")
type SubjectId = SubjectId.T

/** One subject's alignment against the shared source, plus an optional verbosity offset.
  *
  * The subject carries the recall its proof was made from, so the aggregate can check that the
  * proof's `recallChecksum` is the checksum of *this* recall: a population is a set of proofs each
  * bound to its own transcript and all bound to one view.
  */
final case class SubjectAlignment(
    subject: SubjectId,
    recall: RecallGraph[Checked],
    result: HsmmResult,
    wordCount: Option[Int]
)

/** One subject-to-recall binding retained by a [[PopulationReceipt]].
  *
  * `unitPresence` reports only whether the supplied recall contains any segmented units. It does
  * not claim that a participant was silent when none are present.
  */
final case class PopulationMemberReceipt(
    subject: SubjectId,
    recallChecksum: Checksum,
    unitPresence: Boolean
)

/** What a population artifact is bound to, with each recall checksum kept beside its subject.
  *
  * The constructor is private and the canonical members are non-empty and sorted by subject, so the
  * subject count, recall checksums, and no-unit set cannot drift as parallel public fields.
  * Downstream population models cite this receipt so a result cannot be read against a view or
  * recall collection it was not computed on.
  */
final class PopulationReceipt private (
    val viewFingerprint: ViewFingerprint,
    val members: NonEmptyVector[PopulationMemberReceipt]
):
  /** Number of subject bindings in this receipt. */
  def subjectCount: Int = members.length

  /** Recall checksums in the same canonical subject order as [[members]]. */
  def recallChecksums: Vector[Checksum] = members.toVector.map(_.recallChecksum)

  /** Subjects whose supplied recall contains no segmented units. */
  def subjectsWithNoRecallUnits: Vector[SubjectId] =
    members.toVector.collect { case m if !m.unitPresence => m.subject }

  override def equals(other: Any): Boolean = other match
    case that: PopulationReceipt =>
      viewFingerprint == that.viewFingerprint && members == that.members
    case _ => false

  override def hashCode(): Int = 31 * viewFingerprint.hashCode + members.hashCode

  override def toString: String =
    s"PopulationReceipt(view=${viewFingerprint.checksum.short()}, subjects=$subjectCount, " +
      s"noRecallUnits=${subjectsWithNoRecallUnits.size})"

object PopulationReceipt:
  private[align] def from(
      viewFingerprint: ViewFingerprint,
      members: NonEmptyVector[PopulationMemberReceipt]
  ): PopulationReceipt =
    val ordered =
      NonEmptyVector.fromVector(members.toVector.sortBy(_.subject.value)).getOrElse(members)
    new PopulationReceipt(viewFingerprint, ordered)

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
final class PopulationAggregate private (
    val view: SourceView,
    private val nonEmptySubjects: NonEmptyVector[SubjectAlignment]
):
  /** Subject proofs in canonical subject-id order. */
  val subjects: Vector[SubjectAlignment] = nonEmptySubjects.toVector

  /** Subjects in deterministic order. */
  lazy val subjectIds: Vector[SubjectId] = subjects.map(_.subject).sorted

  /** The view fingerprint every subject's proof carries, the subject count, and the sorted recall
    * checksums (design record §11; bead same-view law).
    */
  lazy val receipt: PopulationReceipt =
    PopulationReceipt.from(
      view.contentFingerprint,
      nonEmptySubjects.map { s =>
        PopulationMemberReceipt(
          s.subject,
          s.result.recallChecksum,
          unitPresence = s.recall.units.nonEmpty
        )
      }
    )

  override def equals(other: Any): Boolean = other match
    case that: PopulationAggregate => view == that.view && subjects == that.subjects
    case _                         => false

  override def hashCode(): Int = 31 * view.hashCode + subjects.hashCode

  override def toString: String =
    s"PopulationAggregate(view=${view.contentFingerprint.checksum.short()}, " +
      s"subjects=${subjects.size})"

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
          step.mass.toVector.flatMap { case ((x, y), m) =>
            x.anchor.zip(y.anchor).filter(_ => m > 0.0).map(ab => (ab, m))
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
    // Was `r => Some(view.relativePosition(r))`: an Option whose None was structurally
    // unreachable, because relativePosition substitutes 0.0 for an unresolvable ref. Here that
    // reaches further than in `signature` -- discoursePos feeds isBackward, so a node the view
    // could not place counted as the START OF THE DISCOURSE and inflated a PUBLISHED
    // BackwardFlow fraction. `worldPos` on the next line always used its absence channel
    // correctly. Paired with signature.scala at 3b246ea; see SourceView.measuredPosition.
    val discoursePos: SourceNodeRef => Option[Double] = view.measuredPosition
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

  /** Aggregate alignments that all target `view`.
    *
    * Fails when there are no subjects; when subject ids repeat; when a subject's proof was gated
    * against a different view (its `viewFingerprint` is not this view's content fingerprint — same
    * node ids with different content count as a different view); when a subject's proof was made
    * from a different recall than the one it carries (`recallChecksum` mismatch); when a result
    * references a source node absent from the view; or when a posterior row is malformed. The
    * fingerprint checks come first: a proof from another view is refused even if its nodes happen
    * to exist here.
    */
  def of(
      view: SourceView,
      subjects: Vector[SubjectAlignment]
  ): Either[AlignError, PopulationAggregate] =
    val ids = subjects.map(_.subject)
    val dup = ids.groupBy(identity).collect { case (id, xs) if xs.size > 1 => id }.toVector.sorted
    val known = view.nodes.map(_.ref).toSet
    def sourceRefs(r: HsmmResult): Vector[SourceNodeRef] =
      val fromRows = r.posterior.rows.flatMap(_.mass.keys.toVector.flatMap(_.anchor))
      val fromFlow = r.flow.steps.flatMap(_.mass.keys.toVector.flatMap { case (a, b) =>
        Vector(a, b).flatMap(_.anchor)
      })
      (fromRows ++ fromFlow).distinct.sortBy(_.key)
    val unknown = subjects.flatMap { s =>
      sourceRefs(s.result).filterNot(known.contains).map(r => (s.subject, r))
    }
    val malformed = subjects.flatMap { s =>
      s.result.posterior.rows.filterNot(_.isWellFormed).map(row => (s.subject, row.unit))
    }
    val expectedView = view.contentFingerprint
    val ordered = subjects.sortBy(_.subject.value)
    val foreignView = ordered.find(_.result.viewFingerprint != expectedView)
    val foreignRecall = ordered.find { s =>
      s.result.recallChecksum != AlignWire.recallChecksum(s.recall)
    }
    if subjects.isEmpty then Left(AlignError.SizeMismatch("population has no subjects"))
    else if dup.nonEmpty then
      Left(AlignError.SizeMismatch(s"duplicate subject ids: ${dup.map(_.value).mkString(", ")}"))
    else if foreignView.nonEmpty then
      val s = foreignView.get
      Left(
        AlignError.PopulationViewMismatch(s.subject, s.result.viewFingerprint, expectedView)
      )
    else if foreignRecall.nonEmpty then
      val s = foreignRecall.get
      Left(
        AlignError.PopulationRecallMismatch(
          s.subject,
          s.result.recallChecksum,
          AlignWire.recallChecksum(s.recall)
        )
      )
    else if unknown.nonEmpty then
      val (s, r) = unknown.head
      Left(AlignError.SizeMismatch(s"subject ${s.value} references ${r.key}, absent from the view"))
    else if malformed.nonEmpty then
      val (s, u) = malformed.head
      Left(AlignError.MalformedRow(u, s"subject ${s.value}: mass must be nonnegative and finite"))
    else
      NonEmptyVector.fromVector(ordered) match
        case Some(nonEmpty) => Right(new PopulationAggregate(view, nonEmpty))
        case None           => Left(AlignError.SizeMismatch("population has no subjects"))
