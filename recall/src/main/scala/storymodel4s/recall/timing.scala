package storymodel4s.recall

import storymodel4s.core.*

/** Complete, inventory-bound timing on one declared recall clock. Construction checks accounting
  * and exact coordinates; it does not certify a caller's observation or recording declaration.
  */
final class RecallTiming private (
    val inventory: RecallInventory,
    val clock: RecallTiming.Clock,
    val provenance: RecallTiming.Provenance,
    val entries: Vector[RecallTiming.Entry]
):
  import RecallTiming.*

  private val byWord = entries.iterator.map(e => e.word -> e).toMap
  def entry(word: RecallWordId): Option[Entry] = byWord.get(word)

  /** First/last available onset are witnesses in transcript order, never inferred boundaries. */
  def unitSummaries: Vector[UnitSummary] = inventory.units.map { unit =>
    val members = unit.words.map(byWord)
    val available = members.flatMap(e => onset(e.observation).map(t => Witness(e.word, t)))
    val adjacent = members.zip(members.drop(1))
    val comparable = adjacent.flatMap { (a, b) =>
      for x <- onset(a.observation); y <- onset(b.observation)
      yield (a.word, b.word, compare(x, y))
    }
    UnitSummary(
      unit.id,
      clock,
      members.size,
      available.size,
      members.count(_.observation.isInstanceOf[Observation.Interval]),
      members.count(e => basis(e.observation).exists(_.isInstanceOf[Basis.Estimated])),
      members.headOption,
      members.lastOption,
      available.headOption,
      available.lastOption,
      comparable.size,
      adjacent.size - comparable.size,
      comparable.collect { case (a, b, n) if n > 0 => BackwardPair(a, b) },
      comparable.count(_._3 == 0)
    )
  }

  def digest: Checksum = TimingCanon.digest(
    Vector(
      "recall-timing/v0.1",
      inventory.digest.hex,
      clock.digest.hex,
      provenance.correspondence.hex,
      provenance.recordingLink match
        case RecordingLink.NotEstablished    => TimingCanon.sequence(Vector("unestablished"))
        case RecordingLink.Declared(receipt) =>
          TimingCanon.sequence(Vector("declared", receipt.hex)),
      TimingCanon.sequence(
        entries.map(e =>
          TimingCanon.sequence(Vector(e.word.value, TimingCanon.observation(e.observation)))
        )
      )
    )
  )

object RecallTiming:
  enum DecimalRefusal:
    case Malformed, ResourceLimit, Unrepresentable

  /** Bounded decimal intake. Reduce before testing the exact core representation's Long limits.
    * Seconds may be negative. No rounding, floating conversion or inferred time origin occurs.
    */
  def decimalSeconds(text: String): Either[DecimalRefusal, ExactRational] =
    val decimal = "([+-]?)([0-9]+)(?:\\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?".r
    if text.length > 512 then Left(DecimalRefusal.ResourceLimit)
    else
      text match
        case decimal(sign, whole, fractionOrNull, exponentOrNull) =>
          val fraction = Option(fractionOrNull).getOrElse("")
          val digits = whole + fraction
          val exponent = BigInt(Option(exponentOrNull).getOrElse("0"))
          if digits.length > 128 || exponent.abs > 128 then Left(DecimalRefusal.ResourceLimit)
          else
            val scale = fraction.length - exponent.toInt
            val coefficient = BigInt(digits) * (if sign == "-" then -1 else 1)
            val numerator = coefficient * BigInt(10).pow((-scale).max(0))
            val denominator = BigInt(10).pow(scale.max(0))
            val gcd = numerator.gcd(denominator)
            val n = numerator / gcd
            val d = denominator / gcd
            if n.isValidLong && d.isValidLong then
              ExactRational.of(n.toLong, d.toLong).left.map(_ => DecimalRefusal.Unrepresentable)
            else Left(DecimalRefusal.Unrepresentable)
        case _ => Left(DecimalRefusal.Malformed)

  object ClockKey extends OpaqueId("RecallTiming.ClockKey")
  type ClockKey = ClockKey.T

  enum RecordingIdentity:
    case Unestablished
    case Declared(identity: Checksum)
  enum Origin:
    case Unestablished, RecordingStart
    case Declared(reference: Checksum)
  enum RecordingLink:
    case NotEstablished
    case Declared(receipt: Checksum)

  /** A declaration, not proof of the recording identity, origin, or scanner relationship. The
    * descriptor binds the external record that explains the selected column/coordinate.
    */
  final class Clock private (
      val artifact: Checksum,
      val key: ClockKey,
      val descriptor: Checksum,
      val recording: RecordingIdentity,
      val origin: Origin
  ):
    def digest: Checksum = TimingCanon.digest(
      Vector(
        "recall-clock/seconds/v1",
        artifact.hex,
        key.value,
        descriptor.hex,
        recording match
          case RecordingIdentity.Unestablished => TimingCanon.sequence(Vector("unestablished"))
          case RecordingIdentity.Declared(id)  => TimingCanon.sequence(Vector("declared", id.hex)),
        origin match
          case Origin.Unestablished       => TimingCanon.sequence(Vector("unestablished"))
          case Origin.RecordingStart      => TimingCanon.sequence(Vector("recording-start"))
          case Origin.Declared(reference) =>
            TimingCanon.sequence(Vector("declared", reference.hex))
      )
    )
    override def equals(other: Any): Boolean = other match
      case that: Clock =>
        artifact == that.artifact && key == that.key && descriptor == that.descriptor &&
        recording == that.recording && origin == that.origin
      case _ => false
    override def hashCode(): Int = (artifact, key, descriptor, recording, origin).hashCode

  object Clock:
    def declared(
        artifact: Checksum,
        key: ClockKey,
        descriptor: Checksum,
        recording: RecordingIdentity,
        origin: Origin
    ): Clock = new Clock(artifact, key, descriptor, recording, origin)

  enum MissingReason:
    case NotProvided, BlankSourceCell, NoCorrespondence
  enum Basis:
    case SourceReported(evidence: Checksum)
    case Estimated(recipe: Checksum)

  /** Input observations: intervals are validated by `checked`. A source-reported label is an
    * attributed declaration, not an observation-authority certificate.
    */
  enum Observation:
    case Missing(reason: MissingReason)
    case OnsetOnly(seconds: ExactRational, basis: Basis)
    case Interval(startSeconds: ExactRational, endExclusiveSeconds: ExactRational, basis: Basis)

  final case class Entry(word: RecallWordId, clock: Clock, observation: Observation)
  final case class Provenance(correspondence: Checksum, recordingLink: RecordingLink)
  final case class Witness(word: RecallWordId, seconds: ExactRational)
  final case class BackwardPair(from: RecallWordId, to: RecallWordId)

  /** Derived counts are separate: complete onset availability does not imply interval coverage.
    * Every witness is scoped by `clock`. No unit duration, span, interpolation or exposure follows.
    */
  final case class UnitSummary(
      unit: RecallUnitId,
      clock: Clock,
      totalWords: Int,
      availableOnsets: Int,
      suppliedIntervals: Int,
      estimatedWords: Int,
      firstMember: Option[Entry],
      lastMember: Option[Entry],
      firstAvailableOnset: Option[Witness],
      lastAvailableOnset: Option[Witness],
      comparableAdjacentPairs: Int,
      unobservedAdjacentPairs: Int,
      backwardPairs: Vector[BackwardPair],
      equalAdjacentPairs: Int
  )

  enum Refusal:
    case DuplicateWord(word: RecallWordId)
    case ForeignWord(word: RecallWordId)
    case MissingWord(word: RecallWordId)
    case ForeignClock(word: RecallWordId)
    case InvalidRational(word: RecallWordId)
    case NonPositiveInterval(word: RecallWordId)

  def checked(
      inventory: RecallInventory,
      clock: Clock,
      observations: Vector[Entry],
      provenance: Provenance
  ): Either[Refusal, RecallTiming] =
    val known = inventory.words.map(_.id).toSet
    val seen = scala.collection.mutable.HashSet.empty[RecallWordId]
    val duplicate =
      observations.find(e => !seen.add(e.word)).map(e => Refusal.DuplicateWord(e.word))
    val foreign = observations.find(e => !known(e.word)).map(e => Refusal.ForeignWord(e.word))
    val supplied = observations.map(_.word).toSet
    val missing = inventory.words.find(w => !supplied(w.id)).map(w => Refusal.MissingWord(w.id))
    val invalid = observations.iterator
      .flatMap { e =>
        if e.clock != clock then Some(Refusal.ForeignClock(e.word))
        else
          e.observation match
            case Observation.Missing(_)       => None
            case Observation.OnsetOnly(at, _) =>
              Option.unless(canonical(at))(Refusal.InvalidRational(e.word))
            case Observation.Interval(start, end, _) =>
              if !canonical(start) || !canonical(end) then Some(Refusal.InvalidRational(e.word))
              else Option.when(compare(start, end) >= 0)(Refusal.NonPositiveInterval(e.word))
      }
      .take(1)
      .toVector
      .headOption
    duplicate.orElse(foreign).orElse(missing).orElse(invalid) match
      case Some(error) => Left(error)
      case None        =>
        val byWord = observations.iterator.map(e => e.word -> e).toMap
        Right(
          new RecallTiming(inventory, clock, provenance, inventory.words.map(w => byWord(w.id)))
        )

  private def canonical(value: ExactRational): Boolean =
    value.denominator > 0 &&
      BigInt(value.numerator).gcd(BigInt(value.denominator)) == 1

  private[recall] def compare(a: ExactRational, b: ExactRational): Int =
    (BigInt(a.numerator) * BigInt(b.denominator)).compare(
      BigInt(b.numerator) * BigInt(a.denominator)
    )

  private def onset(value: Observation): Option[ExactRational] = value match
    case Observation.Missing(_)            => None
    case Observation.OnsetOnly(at, _)      => Some(at)
    case Observation.Interval(start, _, _) => Some(start)

  private def basis(value: Observation): Option[Basis] = value match
    case Observation.Missing(_)        => None
    case Observation.OnsetOnly(_, b)   => Some(b)
    case Observation.Interval(_, _, b) => Some(b)

/** Match the lossless UTF-16 token discipline used by the checked inventory. */
private object TimingCanon:
  def sequence(values: Vector[String]): String =
    values.size.toString + ":" + values.map(v => s"${v.length}:$v").mkString
  def digest(values: Vector[String]): Checksum =
    Checksum.ofText(sequence(values).iterator.map(c => f"${c.toInt}%04x").mkString)
  private def seconds(value: ExactRational): String =
    sequence(Vector(value.numerator.toString, value.denominator.toString))
  private def basis(value: RecallTiming.Basis): String = value match
    case RecallTiming.Basis.SourceReported(evidence) =>
      sequence(Vector("source-reported", evidence.hex))
    case RecallTiming.Basis.Estimated(recipe) => sequence(Vector("estimated", recipe.hex))
  def observation(value: RecallTiming.Observation): String = value match
    case RecallTiming.Observation.Missing(reason)  => sequence(Vector("missing", reason.toString))
    case RecallTiming.Observation.OnsetOnly(at, b) =>
      sequence(Vector("onset-only", seconds(at), basis(b)))
    case RecallTiming.Observation.Interval(start, end, b) =>
      sequence(Vector("interval", seconds(start), seconds(end), basis(b)))
