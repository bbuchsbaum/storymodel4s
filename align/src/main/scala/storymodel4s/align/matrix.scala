package storymodel4s.align

import cats.data.NonEmptySet
import storymodel4s.core.Checksum
import storymodel4s.recall.RecallUnitId

/** Explicit open-world destinations for recall mass that is not source-grounded.
  *
  * `Unranked` is the destination of a unit the aligner could not rank at all (every semantic
  * provider abstained and no lexical/entity hit produced a candidate). It is a statement about the
  * *aligner's* evidence, never about the rememberer, so it is kept apart from `Intrusion`.
  */
enum ExternalState:
  case Association, Commentary, SourceConsistentInference, Intrusion, Uninterpretable, Unranked

/** A column of the alignment: an `(anchor, mode)` pair or an external state (ADR 0001 rev 3 §D5).
  *
  *   - `Source(ref)` is the anchor in [[FidelityMode.Faithful]] mode.
  *   - `Distorted(ref, facets)` is the same anchor in [[FidelityMode.Distorted]] mode: the unit
  *     denotes `ref` but contradicts it on `facets`. Distortion is anchored recall of the event.
  *   - `External(state)` is an open-world destination.
  *
  * `Source(ref)` keeps its one-field shape so existing extractors (`case Source(ref)`) still match
  * faithful anchors; code that means "any anchor" uses [[anchor]].
  */
enum AlignState:
  case Source(ref: SourceNodeRef)
  case Distorted(ref: SourceNodeRef, facets: NonEmptySet[Facet])
  case External(state: ExternalState)

  /** Canonical key: `situation/<id>`, `segment/<id>`, `<anchor key>/distorted/<facets>`, or
    * `ext:<state>`. Parts are exposed by [[AlignState.keyParts]].
    */
  def key: String = this match
    case Source(ref)        => ref.key
    case Distorted(ref, fs) => s"${ref.key}/distorted/${AlignState.renderFacets(fs)}"
    case External(state)    => s"ext:${state.toString}"

  /** The source node this state is anchored to, in either mode. */
  def anchor: Option[SourceNodeRef] = this match
    case Source(ref)       => Some(ref)
    case Distorted(ref, _) => Some(ref)
    case External(_)       => None

  def mode: Option[FidelityMode] = this match
    case Source(_)        => Some(FidelityMode.Faithful)
    case Distorted(_, fs) => Some(FidelityMode.Distorted(fs))
    case External(_)      => None

  /** Anchored to a source node (faithful or distorted). */
  def isSource: Boolean = anchor.nonEmpty
  def isFaithful: Boolean = this match
    case Source(_) => true
    case _         => false
  def isDistorted: Boolean = this match
    case Distorted(_, _) => true
    case _               => false
  def isExternal: Boolean = !isSource

object AlignState:
  given Ordering[AlignState] = Ordering.by(_.key)

  /** The external states available to a unit that has source candidates. */
  val externals: Vector[AlignState] =
    ExternalState.values.toVector.filterNot(_ == ExternalState.Unranked).map(External(_))

  /** The single state available to a unit the aligner could not rank. */
  val unranked: AlignState = External(ExternalState.Unranked)

  /** Build the state for an anchor in a given mode. */
  def anchored(ref: SourceNodeRef, mode: FidelityMode): AlignState = mode match
    case FidelityMode.Faithful      => Source(ref)
    case FidelityMode.Distorted(fs) => Distorted(ref, fs)

  private[align] def renderFacets(fs: NonEmptySet[Facet]): String =
    fs.toSortedSet.toVector.map(_.toString).mkString(",")

  /** Key parts (for address encoders): `situation/<id>`, `segment/<id>`, `external/<state>`, and
    * for distorted anchors the anchor parts followed by `distorted`, `<facet,facet>`. Existing
    * two-part keys are unchanged, so decoders written for them keep parsing faithful states.
    */
  def keyParts(s: AlignState): Vector[String] = s match
    case Source(SourceNodeRef.Situation(id)) => Vector("situation", id.value)
    case Source(SourceNodeRef.Segment(id))   => Vector("segment", id.value)
    case Distorted(ref, fs)                  =>
      keyParts(Source(ref)) ++ Vector("distorted", renderFacets(fs))
    case External(e) => Vector("external", e.toString)

  def parseKeyParts(parts: Vector[String]): Option[AlignState] =
    import storymodel4s.core.{SegmentId, SituationId}
    def anchorOf(kind: String, id: String): Option[SourceNodeRef] = kind match
      case "situation" => SituationId.from(id).toOption.map(SourceNodeRef.Situation(_))
      case "segment"   => SegmentId.from(id).toOption.map(SourceNodeRef.Segment(_))
      case _           => None
    parts match
      case Vector(kind, id) if kind != "external" => anchorOf(kind, id).map(Source(_))
      case Vector(kind, id, "distorted", facets)  =>
        for
          ref <- anchorOf(kind, id)
          fs <- {
            val names = facets.split(',').toVector.filter(_.nonEmpty).map(Facet.parse)
            if names.nonEmpty && names.forall(_.isDefined) then
              NonEmptySet.fromSet(scala.collection.immutable.SortedSet.from(names.flatten))
            else None
          }
        yield Distorted(ref, fs)
      case Vector("external", e) => ExternalState.values.find(_.toString == e).map(External(_))
      case _                     => None

/** One row of `P`: posterior mass of a recall unit over `(anchor, mode)` states and external
  * states. Rows are unbalanced by construction: `sourceMass + externalMass = 1` when produced by
  * the HSMM, but rows from other aligners may sum to less than 1. Masses are nonnegative and finite
  * (see [[AlignmentRow.of]]). Not a case class: `fromProduct` would mint negative or non-finite
  * mass from any package. In-module construction stays `private[align]` for the aligners.
  */
final class AlignmentRow private[align] (
    val unit: RecallUnitId,
    val mass: Map[AlignState, Double]
):
  def apply(state: AlignState): Double = mass.getOrElse(state, 0.0)

  /** Deterministic key order for sums (review #30). */
  private def sortedMass: Vector[(AlignState, Double)] = mass.toVector.sortBy(_._1.key)
  def total: Double = sortedMass.map(_._2).sum

  /** Mass anchored to a source node in either mode. */
  def sourceMass: Double = sortedMass.collect { case (s, m) if s.isSource => m }.sum
  def faithfulMass: Double = sortedMass.collect { case (s, m) if s.isFaithful => m }.sum
  def distortedMass: Double = sortedMass.collect { case (s, m) if s.isDistorted => m }.sum

  /** Distorted mass whose facet set contains `facet`. */
  def distortedMass(facet: Facet): Double =
    sortedMass.collect { case (AlignState.Distorted(_, fs), m) if fs.contains(facet) => m }.sum

  def externalMass: Double = sortedMass.collect { case (s, m) if s.isExternal => m }.sum
  def externalMass(state: ExternalState): Double = apply(AlignState.External(state))

  /** Mass anchored to `ref` in either mode. */
  def sourceMassOn(ref: SourceNodeRef): Double =
    sortedMass.collect { case (s, m) if s.anchor.contains(ref) => m }.sum
  def faithfulMassOn(ref: SourceNodeRef): Double = apply(AlignState.Source(ref))
  def distortedMassOn(ref: SourceNodeRef): Double =
    sortedMass.collect { case (AlignState.Distorted(r, _), m) if r == ref => m }.sum

  /** Mass per anchor, both modes summed. */
  def anchorMass: Map[SourceNodeRef, Double] =
    sortedMass
      .flatMap { case (s, m) => s.anchor.map(_ -> m) }
      .groupMapReduce(_._1)(_._2)(_ + _)

  /** Entropy (nats) of the normalized row. */
  def entropy: Double =
    val z = total
    if z <= 0.0 then 0.0
    else -sortedMass.map(_._2 / z).filter(_ > 0.0).map(p => p * math.log(p)).sum

  /** Entropy (nats) of the anchored part of the row over *anchors* (modes summed), renormalized
    * over source mass.
    */
  def sourceEntropy: Double =
    val z = sourceMass
    if z <= 0.0 then 0.0
    else
      -anchorMass.toVector
        .sortBy(_._1.key)
        .collect { case (_, m) if m > 0.0 => m / z }
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

  /** Highest-mass anchored state (faithful or distorted), if any source mass exists. */
  def mapState: Option[AlignState] =
    sortedMass
      .collect { case (s, m) if s.isSource && m > 0.0 => (s, m) }
      .sortBy { case (s, m) => (-m, s.key) }
      .headOption
      .map(_._1)

  /** Highest-mass source anchor (modes summed), if any source mass exists. */
  def mapSource: Option[SourceNodeRef] =
    anchorMass.toVector
      .filter(_._2 > 0.0)
      .sortBy { case (r, m) => (-m, r.key) }
      .headOption
      .map(_._1)

  /** The mode of the highest-mass state on the MAP anchor. */
  def mapMode: Option[FidelityMode] =
    mapSource.flatMap { ref =>
      sortedMass
        .collect { case (s, m) if s.anchor.contains(ref) => (s, m) }
        .sortBy { case (s, m) => (-m, s.key) }
        .headOption
        .flatMap(_._1.mode)
    }

  def massAtLevel(view: SourceView, level: Int): Double =
    sortedMass.collect {
      case (s, m) if s.anchor.exists(r => view.node(r).exists(_.level == level)) => m
    }.sum

  def normalized: AlignmentRow =
    val z = total
    if z <= 0.0 then this else AlignmentRow(unit, mass.view.mapValues(_ / z).toMap)

  /** Whether every mass is nonnegative and finite. */
  def isWellFormed: Boolean = mass.values.forall(m => m >= 0.0 && !m.isNaN && !m.isInfinite)

  override def equals(other: Any): Boolean = other match
    case that: AlignmentRow => unit == that.unit && mass == that.mass
    case _                  => false

  override def hashCode(): Int = (unit, mass).hashCode

  override def toString: String = s"AlignmentRow($unit, states=${mass.size})"

object AlignmentRow:
  private[align] def apply(unit: RecallUnitId, mass: Map[AlignState, Double]): AlignmentRow =
    new AlignmentRow(unit, mass)

  /** Smart constructor rejecting negative or non-finite mass. */
  def of(unit: RecallUnitId, mass: Map[AlignState, Double]): Either[AlignError, AlignmentRow] =
    val row = AlignmentRow(unit, mass)
    if row.isWellFormed then Right(row)
    else Left(AlignError.MalformedRow(unit, "mass must be nonnegative and finite"))

/** The alignment `P`: one row per recall unit in recall order. Construction is `private[align]`
  * (aligners) or via [[AlignmentMatrix.of]], which rejects malformed rows and repeated units. Not a
  * case class: `fromProduct` would accept a vector of malformed rows.
  */
final class AlignmentMatrix private[align] (val rows: Vector[AlignmentRow]):
  lazy val byUnit: Map[RecallUnitId, AlignmentRow] = rows.iterator.map(r => r.unit -> r).toMap
  def row(unit: RecallUnitId): Option[AlignmentRow] = byUnit.get(unit)
  def size: Int = rows.size

  /** Aggregate mass each source anchor received in either mode, `Σ_i P_iv` (deterministic summation
    * order).
    */
  def columnMass: Map[SourceNodeRef, Double] =
    rows
      .flatMap(_.mass.toVector.flatMap { case (s, m) => s.anchor.map(_ -> m) })
      .sortBy(_._1.key)
      .groupMapReduce(_._1)(_._2)(_ + _)

  /** Aggregate distorted mass per anchor. */
  def distortedColumnMass: Map[SourceNodeRef, Double] =
    rows
      .flatMap(_.mass.toVector.collect { case (AlignState.Distorted(r, _), m) => (r, m) })
      .sortBy(_._1.key)
      .groupMapReduce(_._1)(_._2)(_ + _)

  /** Fuzzy visitation `Y_v = 1 − exp(−Σ_i P_iv)` (design record §11). */
  def visitation: Map[SourceNodeRef, Double] =
    columnMass.view.mapValues(m => 1.0 - math.exp(-m)).toMap

  def isWellFormed: Boolean = rows.forall(_.isWellFormed)

  override def equals(other: Any): Boolean = other match
    case that: AlignmentMatrix => rows == that.rows
    case _                     => false

  override def hashCode(): Int = rows.hashCode

  override def toString: String = s"AlignmentMatrix(rows=${rows.size})"

object AlignmentMatrix:
  private[align] def apply(rows: Vector[AlignmentRow]): AlignmentMatrix =
    new AlignmentMatrix(rows)

  /** Smart constructor: every row well-formed and no unit repeated. */
  def of(rows: Vector[AlignmentRow]): Either[AlignError, AlignmentMatrix] =
    rows.find(!_.isWellFormed) match
      case Some(bad) =>
        Left(AlignError.MalformedRow(bad.unit, "mass must be nonnegative and finite"))
      case None =>
        val dup = rows.map(_.unit).groupBy(identity).collect { case (u, xs) if xs.size > 1 => u }
        dup.toVector.sortBy(_.value).headOption match
          case Some(u) => Left(AlignError.MalformedRow(u, "unit appears in more than one row"))
          case None    => Right(AlignmentMatrix(rows))

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

  /** Mass on anchor→anchor moves (either mode) satisfying `p`. */
  def sourceMass(p: (SourceNodeRef, SourceNodeRef) => Boolean): Double =
    sorted.collect {
      case ((a, b), m) if a.anchor.zip(b.anchor).exists((x, y) => p(x, y)) => m
    }.sum

  def sourceToSourceMass: Double = sourceMass((_, _) => true)

final case class TransitionFlow(steps: Vector[FlowStep]):
  def size: Int = steps.size

/** Typed failures of the alignment API (no exceptions escape the aligners). */
enum AlignError:
  case EmptyRecall
  case InvalidConfig(field: String, detail: String)
  case MalformedRow(unit: RecallUnitId, detail: String)
  case SizeMismatch(detail: String)

  /** A gated result would carry mass, a cost, or a path step on an `(anchor, mode)` pair that the
    * recorded admissibility does not admit (ADR 0001 rev 3 L1).
    */
  case GateViolation(unit: RecallUnitId, state: AlignState, detail: String)

  /** The parts of a result do not fit together (units, flow endpoints, path length). */
  case InconsistentResult(detail: String)

  /** The admissibility echo carried with a result is not the digest of what the [[ModeGate]]
    * derives now: the gate that produced the record is not the gate being run (wire drift), as
    * distinct from a forged key.
    */
  case GateDrift(recorded: AdmissibilityEcho, derived: AdmissibilityEcho)

  /** A fingerprint carried on the wire (`viewFingerprint`, `recallChecksum`) differs from the value
    * the proof derived from the recall and view in hand.
    */
  case FingerprintMismatch(field: String, recorded: String, derived: String)

  /** An in-memory population member proof was gated against a different source view. */
  case PopulationViewMismatch(
      subject: SubjectId,
      proof: ViewFingerprint,
      aggregate: ViewFingerprint
  )

  /** An in-memory population member proof was derived from a different supplied recall. */
  case PopulationRecallMismatch(
      subject: SubjectId,
      proof: Checksum,
      suppliedRecall: Checksum
  )

  /** A record rebuilt through [[AlignWire]] is malformed or internally inconsistent. */
  case MalformedRecord(record: String, detail: String)

  def message: String = this match
    case EmptyRecall            => "recall has no units"
    case InvalidConfig(f, m)    => s"$f: $m"
    case MalformedRow(u, m)     => s"row ${u.value}: $m"
    case SizeMismatch(m)        => m
    case GateViolation(u, s, m) => s"unit ${u.value}, state ${s.key}: $m"
    case InconsistentResult(m)  => m
    case GateDrift(r, d)        =>
      s"admissibility echo ${r.checksum.short()} differs from the gate's ${d.checksum.short()}"
    case FingerprintMismatch(f, r, d) => s"$f on the wire ($r) differs from the derived value ($d)"
    case PopulationViewMismatch(s, p, a) =>
      s"subject ${s.value}: proof view ${p.checksum.short()} differs from aggregate view ${a.checksum.short()}"
    case PopulationRecallMismatch(s, p, r) =>
      s"subject ${s.value}: proof recall ${p.short()} differs from supplied recall ${r.short()}"
    case MalformedRecord(r, m) => s"$r: $m"
