package storymodel4s.align

import storymodel4s.core.*
import storymodel4s.recall.*

/** Recall-time exposure binds inference units to supplied timings without inventing word ends. */
final class TemporalExposure private (
    val mapping: MappingResult,
    val timing: RecallTiming,
    val overlap: TemporalExposure.Overlap,
    val digest: Checksum
):
  import TemporalExposure.*

  /** Display selection returns distinct original inference units, never replicated observations. */
  def unitsForWords(words: Vector[RecallWordId]): Either[Refusal, Vector[RecallUnitId]] =
    if words.exists(w => !timing.inventory.membership.contains(w)) then Left(Refusal.ForeignWord)
    else
      Right(
        words
          .flatMap(w =>
            timing.inventory.membership(w) match
              case WordMembership.Member(unit) => Some(unit)
              case _                           => None
          )
          .distinct
      )

  /** Categorical marginal mappings do not certify simultaneous referents. */
  def simultaneousReferents: Either[Refusal, Nothing] = Left(Refusal.DecompositionNotAssessed)

  /** Half-open query on the exact declared recall clock. Onset events have no inferred duration. */
  def query(
      clock: RecallTiming.Clock,
      start: ExactRational,
      endExclusive: ExactRational
  ): Either[Refusal, Readout] =
    if clock != timing.clock then Left(Refusal.ForeignClock)
    else if !canonical(start) || !canonical(endExclusive) then Left(Refusal.InvalidWindow)
    else queryCanonical(start, endExclusive)

  private def queryCanonical(
      start: ExactRational,
      endExclusive: ExactRational
  ): Either[Refusal, Readout] =
    evaluate(timing, mapping.outcomes.map(o => o.unit -> o.processing).toMap, start, endExclusive)
      .map { (rows, uncovered) =>
        val identity = MappingRender.digest(
          Vector(
            "recall-exposure-window/v1",
            digest.hex,
            Rat.of(start).key,
            Rat.of(endExclusive).key
          )
        )
        new Readout(this, start, endExclusive, rows, uncovered, identity)
      }

  override def equals(other: Any): Boolean = other match
    case that: TemporalExposure => digest == that.digest
    case _                      => false
  override def hashCode(): Int = digest.hashCode
  override def toString: String = s"TemporalExposure(${digest.hex})"

object TemporalExposure:
  /** Sharing is across distinct inference units; overlap inside one unit is always a union. */
  enum Overlap:
    case Refuse, EqualShare

  /** Missing joins and unsupported referent semantics remain distinct from zero exposure. */
  enum Refusal:
    case ForeignInventory, ForeignClock, ForeignWord, InvalidWindow, OverlappingUnits
    case DecompositionNotAssessed, ForeignProcessing

  /** Original status and decomposition survive independently of temporal coverage. Missing-word
    * counts cover the entire unit: an absent timing cannot be localized to the requested window.
    */
  final class UnitReadout private[TemporalExposure] (
      val unit: RecallUnitId,
      val duration: (BigInt, BigInt),
      val onsetEvents: Vector[ExactRational],
      val missingWords: Int,
      val decomposition: DecompositionStatus,
      val processing: ProcessingStatus
  ):
    def durationSeconds: Double = (BigDecimal(duration._1) / BigDecimal(duration._2)).toDouble
    private def fields = (unit, duration, onsetEvents, missingWords, decomposition, processing)
    override def equals(other: Any): Boolean = other match
      case that: UnitReadout => fields == that.fields
      case _                 => false
    override def hashCode(): Int = fields.hashCode
    override def toString: String = s"UnitReadout($unit,$duration,$processing)"

  /** Exact duration fractions and uncovered time remain separate from source-location measures. */
  final class Readout private[TemporalExposure] (
      val exposure: TemporalExposure,
      val start: ExactRational,
      val endExclusive: ExactRational,
      val units: Vector[UnitReadout],
      val uncovered: (BigInt, BigInt),
      val digest: Checksum
  ):
    /** Time with no active inference-unit interval; unassigned words never acquire a unit. */
    def uncoveredSeconds: Double = (BigDecimal(uncovered._1) / BigDecimal(uncovered._2)).toDouble
    def unassignedWords: Vector[RecallWordId] = exposure.timing.inventory.words.collect {
      case word
          if exposure.timing.inventory
            .membership(word.id)
            .isInstanceOf[WordMembership.Unassigned] =>
        word.id
    }
    def uncomputedDuration: (BigInt, BigInt) = units
      .filter(_.processing != ProcessingStatus.Complete)
      .map(u => Rat(u.duration._1, u.duration._2))
      .foldLeft(Rat.zero)(_ + _)
      .pair
    override def equals(other: Any): Boolean = other match
      case that: Readout => digest == that.digest
      case _             => false
    override def hashCode(): Int = digest.hashCode
    override def toString: String = s"TemporalExposure.Readout(${digest.hex})"

  def checked(
      mapping: MappingResult,
      timing: RecallTiming,
      overlap: Overlap
  ): Either[Refusal, TemporalExposure] =
    if mapping.inventory.digest != timing.inventory.digest then Left(Refusal.ForeignInventory)
    else
      val conflict = overlapping(timing)
      if overlap == Overlap.Refuse && conflict then Left(Refusal.OverlappingUnits)
      else
        val identity = MappingRender.digest(
          Vector(
            "recall-exposure/v1",
            mapping.digest.hex,
            timing.digest.hex,
            timing.clock.digest.hex,
            timing.inventory.segmentation.digest.hex,
            overlap.toString
          )
        )
        Right(new TemporalExposure(mapping, timing, overlap, identity))

  /** Supplied processing claims permit exposure arithmetic, never an execution witness. */
  final class Inspection private[TemporalExposure] (
      val timing: RecallTiming,
      val processing: Map[RecallUnitId, ProcessingStatus],
      val overlap: Overlap,
      val digest: Checksum
  ):
    def query(
        clock: RecallTiming.Clock,
        start: ExactRational,
        endExclusive: ExactRational
    ): Either[Refusal, InspectionReadout] =
      if clock != timing.clock then Left(Refusal.ForeignClock)
      else if !canonical(start) || !canonical(endExclusive) then Left(Refusal.InvalidWindow)
      else
        evaluate(timing, processing, start, endExclusive).map { (rows, uncovered) =>
          val identity = MappingRender.digest(
            Vector(
              "imported-recall-exposure-window/v1",
              digest.hex,
              Rat.of(start).key,
              Rat.of(endExclusive).key
            )
          )
          new InspectionReadout(this, start, endExclusive, rows, uncovered, identity)
        }
    override def equals(other: Any): Boolean = other match
      case that: Inspection => digest == that.digest
      case _                => false
    override def hashCode(): Int = digest.hashCode
    override def toString: String = s"TemporalExposure.Inspection(${digest.short()})"

  /** Original unit statuses and missing timing remain visible alongside exact durations. */
  final class InspectionReadout private[TemporalExposure] (
      val exposure: Inspection,
      val start: ExactRational,
      val endExclusive: ExactRational,
      val units: Vector[UnitReadout],
      val uncovered: (BigInt, BigInt),
      val digest: Checksum
  ):
    def uncoveredSeconds: Double = (BigDecimal(uncovered._1) / BigDecimal(uncovered._2)).toDouble
    def unassignedWords: Vector[RecallWordId] = exposure.timing.inventory.words.collect {
      case word
          if exposure.timing.inventory
            .membership(word.id)
            .isInstanceOf[WordMembership.Unassigned] =>
        word.id
    }
    def uncomputedDuration: (BigInt, BigInt) = units
      .filter(_.processing != ProcessingStatus.Complete)
      .map(u => Rat(u.duration._1, u.duration._2))
      .foldLeft(Rat.zero)(_ + _)
      .pair
    override def equals(other: Any): Boolean = other match
      case that: InspectionReadout => digest == that.digest
      case _                       => false
    override def hashCode(): Int = digest.hashCode
    override def toString: String = s"TemporalExposure.InspectionReadout(${digest.short()})"

  def inspect(
      inventory: RecallInventory,
      timing: RecallTiming,
      processing: Map[RecallUnitId, ProcessingStatus],
      overlap: Overlap
  ): Either[Refusal, Inspection] =
    if inventory.digest != timing.inventory.digest then Left(Refusal.ForeignInventory)
    else if processing.keySet != inventory.units.map(_.id).toSet then
      Left(Refusal.ForeignProcessing)
    else if overlap == Overlap.Refuse && overlapping(timing) then Left(Refusal.OverlappingUnits)
    else
      val rows = processing.toVector.sortBy(_._1.value).map { (unit, status) =>
        val fields = status match
          case ProcessingStatus.Complete                      => Vector("complete")
          case ProcessingStatus.ExcludedByInputPolicy(reason) => Vector("excluded", reason)
          case ProcessingStatus.Failed(ProcessingFailure.ProviderFailure(detail)) =>
            Vector("failed", "provider", detail)
          case ProcessingStatus.Failed(ProcessingFailure.InvalidOutput(detail)) =>
            Vector("failed", "invalid", detail)
          case ProcessingStatus.Failed(ProcessingFailure.InferenceRefused(detail)) =>
            Vector("failed", "refused", detail)
        MappingRender.sequence(Vector(unit.value) ++ fields)
      }
      val identity = MappingRender.digest(
        Vector(
          "imported-recall-exposure/v1",
          inventory.digest.hex,
          timing.digest.hex,
          overlap.toString
        ) ++ rows
      )
      Right(new Inspection(timing, processing, overlap, identity))

  private def overlapping(timing: RecallTiming): Boolean =
    val intervals = timing.entries
      .flatMap { e =>
        (timing.inventory.membership(e.word), e.observation) match
          case (WordMembership.Member(unit), RecallTiming.Observation.Interval(a, b, _)) =>
            Some((unit, Rat.of(a), Rat.of(b)))
          case _ => None
      }
      .sortBy(_._2)
    // A sweep, not a dense all-pairs matrix; each interval is compared only with active intervals.
    var active = Vector.empty[(RecallUnitId, Rat)]
    val conflict = intervals.exists { (unit, start, end) =>
      active = active.filter(_._2 > start)
      val other = active.exists(_._1 != unit)
      active = active :+ (unit -> end)
      other
    }
    conflict

  private def evaluate(
      timing: RecallTiming,
      processing: Map[RecallUnitId, ProcessingStatus],
      start: ExactRational,
      endExclusive: ExactRational
  ): Either[Refusal, (Vector[UnitReadout], (BigInt, BigInt))] =
    val lo = Rat.of(start)
    val hi = Rat.of(endExclusive)
    if !(lo < hi) then Left(Refusal.InvalidWindow)
    else
      val intervals = timing.entries.flatMap { e =>
        (timing.inventory.membership(e.word), e.observation) match
          case (WordMembership.Member(unit), RecallTiming.Observation.Interval(a, b, _)) =>
            val left = Rat.of(a).max(lo)
            val right = Rat.of(b).min(hi)
            Option.when(left < right)((unit, left, right))
          case _ => None
      }
      val boundaries =
        (Vector(lo, hi) ++ intervals.flatMap((_, a, b) => Vector(a, b))).distinct.sorted
      var uncovered = Rat.zero
      val durations = scala.collection.mutable.Map.empty[RecallUnitId, Rat]
      boundaries.zip(boundaries.drop(1)).foreach { (a, b) =>
        val active = intervals.collect {
          case (unit, left, right) if left <= a && b <= right => unit
        }.distinct
        if active.isEmpty then uncovered = uncovered + (b - a)
        else
          val share = (b - a) / active.size
          active.foreach(u => durations.update(u, durations.getOrElse(u, Rat.zero) + share))
      }
      val rows = timing.inventory.units.map { unit =>
        val entries = unit.words.flatMap(timing.entry)
        val onsets = entries
          .flatMap(e =>
            e.observation match
              case RecallTiming.Observation.OnsetOnly(at, _)
                  if lo <= Rat.of(at) && Rat.of(at) < hi =>
                Some(at)
              case _ => None
          )
          .distinct
        val missing = entries.count(_.observation.isInstanceOf[RecallTiming.Observation.Missing])
        new UnitReadout(
          unit.id,
          durations.getOrElse(unit.id, Rat.zero).pair,
          onsets,
          missing,
          unit.decomposition,
          processing(unit.id)
        )
      }
      Right(rows -> uncovered.pair)

  private def canonical(r: ExactRational): Boolean =
    r.denominator > 0 && BigInt(r.numerator).gcd(BigInt(r.denominator)) == 1

  private final class Rat private (val n: BigInt, val d: BigInt) extends Ordered[Rat]:
    def compare(that: Rat): Int = (n * that.d).compare(that.n * d)
    def +(that: Rat): Rat = Rat(n * that.d + that.n * d, d * that.d)
    def -(that: Rat): Rat = Rat(n * that.d - that.n * d, d * that.d)
    def /(k: Int): Rat = Rat(n, d * k)
    def max(that: Rat): Rat = if this >= that then this else that
    def min(that: Rat): Rat = if this <= that then this else that
    def pair: (BigInt, BigInt) = n -> d
    def key: String = s"$n/$d"
    override def equals(other: Any): Boolean = other match
      case that: Rat => n == that.n && d == that.d
      case _         => false
    override def hashCode(): Int = pair.hashCode
  private object Rat:
    val zero: Rat = new Rat(0, 1)
    def of(r: ExactRational): Rat = apply(BigInt(r.numerator), BigInt(r.denominator))
    def apply(n: BigInt, d: BigInt): Rat =
      val gcd = n.gcd(d)
      new Rat(n / gcd, d / gcd)
