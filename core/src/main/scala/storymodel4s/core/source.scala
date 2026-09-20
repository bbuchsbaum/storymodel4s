package storymodel4s.core

import cats.data.NonEmptyVector

object SourceBundleId extends OpaqueId("SourceBundleId")
type SourceBundleId = SourceBundleId.T

object EditionId extends OpaqueId("EditionId")
type EditionId = EditionId.T

object StreamId extends OpaqueId("StreamId")
type StreamId = StreamId.T

object PresentationAxisId extends OpaqueId("PresentationAxisId")
type PresentationAxisId = PresentationAxisId.T

object MappingRelationId extends OpaqueId("MappingRelationId")
type MappingRelationId = MappingRelationId.T

object OccurrenceId extends OpaqueId("OccurrenceId")
type OccurrenceId = OccurrenceId.T

object ShotId extends OpaqueId("ShotId")
type ShotId = ShotId.T

object TrackId extends OpaqueId("TrackId")
type TrackId = TrackId.T

object BoundaryId extends OpaqueId("BoundaryId")
type BoundaryId = BoundaryId.T

object NarrativeProposalUnitId extends OpaqueId("NarrativeProposalUnitId")
type NarrativeProposalUnitId = NarrativeProposalUnitId.T

/** Source medium of a pinned edition. Film is not a subtitle-shaped text source. */
enum SourceKind:
  case WrittenText, FilmEdition, TimedTranscript, AnnotationTable, AudioRendition
  case Custom(namespace: String, label: String)

/** Immutable stream role inside a bundle. Edition playback is not a stream kind. */
enum StreamKind:
  case Picture, Audio, Subtitle, CanonicalText, TimedText, Annotation, DerivedClock
  case Custom(namespace: String, label: String)

/** Audience-facing axis kind. Edition playback is never an elementary stream clock.
  *
  * `AnnotationTimeline` exists because a timed annotation is not an edition and had no lawful axis.
  * Without it the only corpus with no admitted video forged one -- minting
  * `EditionId("filmfestival-annotation-<sha>-<part>")` and calling `SourceBundle.filmEdition`, with
  * its own Scaladoc saying "This is deliberately not a film edition". The type said FilmEdition;
  * the comment said it was not one. `SourceKind.AnnotationTable` was declared and unreachable for
  * exactly this reason: every axis constructor was kind-specific.
  */
enum AxisKind:
  case TextCharacter, EditionPlayback, AnnotationTimeline

/** Declared rounding when a caller asks for an integral lattice. */
enum RoundingPolicy:
  case TowardZero, TowardNegInf, TowardPosInf, NearestEven

/** C1 observation authority. Runtime-observed evidence cannot be minted here. */
enum ObservationAuthority:
  case FixtureScoped, Draft

/** Why a pure rescale refused. Overflow and inexact conversion stay distinct. */
enum RescaleRefusal:
  case Overflow, InexactIntegral, NonPositiveScale

private[core] object SourceCanon:
  def digest(parts: String*): Checksum = ContentAddress.digest(parts)

  def fmt(kind: String, raw: String, reason: String): DomainError =
    DomainError.InvalidFormat(kind, raw, reason)

  def inv(path: String, reason: String): DomainError =
    DomainError.InvariantViolation(path, reason)

/** Exact reduced rational. Why: timebases and rescales may not become floating-point. */
final class ExactRational private[core] (val numerator: Long, val denominator: Long):
  def isPositive: Boolean = numerator > 0L
  def isZero: Boolean = numerator == 0L
  def unary_- : ExactRational = new ExactRational(-numerator, denominator)

  def +(other: ExactRational): Either[DomainError, ExactRational] =
    ExactRational.add(this, other)
  def *(other: ExactRational): Either[DomainError, ExactRational] =
    ExactRational.mul(this, other)

  override def equals(other: Any): Boolean = other match
    case that: ExactRational =>
      numerator == that.numerator && denominator == that.denominator
    case _ => false
  override def hashCode(): Int = (numerator, denominator).hashCode()
  override def toString: String = s"$numerator/$denominator"

object ExactRational:
  val Zero: ExactRational = new ExactRational(0L, 1L)
  val One: ExactRational = new ExactRational(1L, 1L)

  def of(numerator: Long, denominator: Long): Either[DomainError, ExactRational] =
    if denominator == 0L then
      Left(SourceCanon.fmt("ExactRational", s"$numerator/$denominator", "zero denominator"))
    else
      val rawN = BigInt(numerator)
      val rawD = BigInt(denominator)
      val g = rawN.gcd(rawD)
      val n = rawN / g
      val d = rawD / g
      val (nn, dd) = if d < 0 then (-n, -d) else (n, d)
      if nn.isValidLong && dd.isValidLong then Right(new ExactRational(nn.toLong, dd.toLong))
      else Left(SourceCanon.fmt("ExactRational", s"$numerator/$denominator", "reduction overflow"))

  def integer(n: Long): ExactRational = new ExactRational(n, 1L)

  private[core] def add(a: ExactRational, b: ExactRational): Either[DomainError, ExactRational] =
    val num = BigInt(a.numerator) * BigInt(b.denominator) + BigInt(b.numerator) * BigInt(
      a.denominator
    )
    val den = BigInt(a.denominator) * BigInt(b.denominator)
    if num.isValidLong && den.isValidLong then of(num.toLong, den.toLong)
    else Left(SourceCanon.fmt("ExactRational", s"$a+$b", "addition overflow"))

  private[core] def mul(a: ExactRational, b: ExactRational): Either[DomainError, ExactRational] =
    val num = BigInt(a.numerator) * BigInt(b.numerator)
    val den = BigInt(a.denominator) * BigInt(b.denominator)
    if num.isValidLong && den.isValidLong then of(num.toLong, den.toLong)
    else Left(SourceCanon.fmt("ExactRational", s"$a*$b", "multiplication overflow"))

/** Stream or axis timebase: one tick equals this many seconds. */
final class RationalTimebase private (val scale: ExactRational):
  override def equals(other: Any): Boolean = other match
    case that: RationalTimebase => scale == that.scale
    case _                      => false
  override def hashCode(): Int = scale.hashCode()
  override def toString: String = s"RationalTimebase($scale)"

object RationalTimebase:
  val Millisecond: RationalTimebase = new RationalTimebase(new ExactRational(1L, 1000L))

  def of(scale: ExactRational): Either[DomainError, RationalTimebase] =
    if scale.isPositive then Right(new RationalTimebase(scale))
    else
      Left(SourceCanon.fmt("RationalTimebase", scale.toString, "scale must be strictly positive"))

  def of(numerator: Long, denominator: Long): Either[DomainError, RationalTimebase] =
    ExactRational.of(numerator, denominator).flatMap(of)

/** Packet or stream duration. Zero is unknown, never a measured empty interval. */
sealed trait MediaDuration
object MediaDuration:
  final class KnownPositive private[core] (val ticks: Long) extends MediaDuration:
    override def equals(other: Any): Boolean = other match
      case that: KnownPositive => ticks == that.ticks
      case _                   => false
    override def hashCode(): Int = ticks.hashCode()
    override def toString: String = s"KnownPositive($ticks)"

  case object Unknown extends MediaDuration

  def knownPositive(ticks: Long): Either[DomainError, MediaDuration.KnownPositive] =
    if ticks > 0L then Right(new MediaDuration.KnownPositive(ticks))
    else
      Left(SourceCanon.fmt("MediaDuration", ticks.toString, "duration must be strictly positive"))

  def ofPacketTicks(ticks: Long): MediaDuration =
    if ticks > 0L then new MediaDuration.KnownPositive(ticks) else MediaDuration.Unknown

/** Independently present or missing timestamp. No value-only classifier lives here. */
enum TimestampField:
  case Present(tick: Long)
  case Missing

object TimestampField:
  def present(tick: Long): TimestampField.Present = TimestampField.Present(tick)
  def missing: TimestampField = TimestampField.Missing

/** Classified PTS/DTS pair. Present PTS < DTS is a source-contract refusal with no repair. */
final class PacketTimeFields private (val pts: TimestampField, val dts: TimestampField):
  override def equals(other: Any): Boolean = other match
    case that: PacketTimeFields => pts == that.pts && dts == that.dts
    case _                      => false
  override def hashCode(): Int = (pts, dts).hashCode()
  override def toString: String = s"PacketTimeFields(pts=$pts, dts=$dts)"

object PacketTimeFields:
  def of(pts: TimestampField, dts: TimestampField): Either[DomainError, PacketTimeFields] =
    (pts, dts) match
      case (TimestampField.Present(p), TimestampField.Present(d)) if p < d =>
        Left(
          SourceCanon.inv(
            "packet/timestamps",
            s"present PTS $p < DTS $d; ingest may not swap, clamp, or repair"
          )
        )
      case _ => Right(new PacketTimeFields(pts, dts))

/** Synthetic fixture packet. Identity is recomputed; a caller-supplied digest cannot grant it. */
final class FixturePacketWitness private (
    val rawPts: Long,
    val rawDts: Long,
    val durationTicks: Long,
    val identity: Checksum
):
  override def equals(other: Any): Boolean = other match
    case that: FixturePacketWitness =>
      rawPts == that.rawPts && rawDts == that.rawDts && durationTicks == that.durationTicks &&
      identity == that.identity
    case _ => false
  override def hashCode(): Int = (rawPts, rawDts, durationTicks, identity).hashCode()
  override def toString: String = s"FixturePacketWitness(${identity.short()})"

object FixturePacketWitness:
  private def computeIdentity(rawPts: Long, rawDts: Long, durationTicks: Long): Checksum =
    SourceCanon.digest("fixture-packet", rawPts.toString, rawDts.toString, durationTicks.toString)

  /** Project-authored synthetic witness. Not a runtime decoder record. */
  def synthetic(rawPts: Long, rawDts: Long, durationTicks: Long): FixturePacketWitness =
    new FixturePacketWitness(
      rawPts,
      rawDts,
      durationTicks,
      computeIdentity(rawPts, rawDts, durationTicks)
    )

  def rehydrate(
      rawPts: Long,
      rawDts: Long,
      durationTicks: Long,
      claimedIdentity: Checksum
  ): Either[DomainError, FixturePacketWitness] =
    val computed = computeIdentity(rawPts, rawDts, durationTicks)
    if computed == claimedIdentity then Right(synthetic(rawPts, rawDts, durationTicks))
    else
      Left(
        SourceCanon.inv(
          "fixture-packet/identity",
          "claimed identity does not match the recomputed preimage"
        )
      )

/** Fixture-scoped classification. Equal raw INT64_MIN is missing only on this operation. */
final class FixturePacketClassification private[core] (
    val fields: PacketTimeFields,
    val duration: MediaDuration,
    val witnessIdentity: Checksum,
    val identity: Checksum
):
  def authority: ObservationAuthority = ObservationAuthority.FixtureScoped
  override def equals(other: Any): Boolean = other match
    case that: FixturePacketClassification =>
      fields == that.fields && duration == that.duration &&
      witnessIdentity == that.witnessIdentity && identity == that.identity
    case _ => false
  override def hashCode(): Int = (fields, duration, witnessIdentity, identity).hashCode()
  override def toString: String = s"FixturePacketClassification(${identity.short()})"

object FixturePacketNormalizer:
  val AvNoptsValue: Long = Long.MinValue

  private def classifyField(raw: Long): TimestampField =
    if raw == AvNoptsValue then TimestampField.Missing else TimestampField.Present(raw)

  private def computeIdentity(
      fields: PacketTimeFields,
      duration: MediaDuration,
      witnessIdentity: Checksum
  ): Checksum =
    SourceCanon.digest(
      "fixture-classify",
      fields.pts.toString,
      fields.dts.toString,
      duration.toString,
      witnessIdentity.hex
    )

  def classify(witness: FixturePacketWitness): Either[DomainError, FixturePacketClassification] =
    PacketTimeFields.of(classifyField(witness.rawPts), classifyField(witness.rawDts)).map {
      fields =>
        val duration = MediaDuration.ofPacketTicks(witness.durationTicks)
        val identity = computeIdentity(fields, duration, witness.identity)
        new FixturePacketClassification(fields, duration, witness.identity, identity)
    }

  def promoteToRuntime(classified: FixturePacketClassification): Either[DomainError, Nothing] =
    Left(
      SourceCanon.inv(
        "observation/authority",
        s"fixture-scoped ${classified.identity.short()} cannot become runtime-observed"
      )
    )

/** Pure exact rescale. Unrepresentable results refuse; they are not missing timestamps. */
enum RescaleOutcome:
  case Exact(tick: Long, target: RationalTimebase, identity: Checksum)
  case Quantized(
      tick: Long,
      target: RationalTimebase,
      rounding: RoundingPolicy,
      residual: ExactRational,
      identity: Checksum
  )
  case Refused(reason: RescaleRefusal, identity: Checksum)

object ExactRescaler:
  private def identityOf(
      tick: Long,
      source: RationalTimebase,
      target: RationalTimebase,
      rounding: Option[RoundingPolicy],
      tag: String
  ): Checksum =
    SourceCanon.digest(
      "exact-rescale",
      tick.toString,
      source.scale.toString,
      target.scale.toString,
      rounding.map(_.toString).getOrElse("exact"),
      tag
    )

  private def exactQuotient(
      tick: Long,
      source: RationalTimebase,
      target: RationalTimebase
  ): Either[RescaleRefusal, Long] =
    val num = BigInt(tick) * BigInt(source.scale.numerator) * BigInt(target.scale.denominator)
    val den = BigInt(source.scale.denominator) * BigInt(target.scale.numerator)
    if den <= 0 then Left(RescaleRefusal.NonPositiveScale)
    else if num % den == 0 then
      val q = num / den
      if q.isValidLong then Right(q.toLong) else Left(RescaleRefusal.Overflow)
    else Left(RescaleRefusal.InexactIntegral)

  private def roundedQuotient(
      tick: Long,
      source: RationalTimebase,
      target: RationalTimebase,
      rounding: RoundingPolicy
  ): Either[RescaleRefusal, (Long, ExactRational)] =
    val num = BigInt(tick) * BigInt(source.scale.numerator) * BigInt(target.scale.denominator)
    val den = BigInt(source.scale.denominator) * BigInt(target.scale.numerator)
    if den <= 0 then Left(RescaleRefusal.NonPositiveScale)
    else
      val q = num / den
      val r = num % den
      val (adj, residualNum) = rounding match
        case RoundingPolicy.TowardZero =>
          (BigInt(0), r)
        case RoundingPolicy.TowardNegInf =>
          if r != 0 && num.signum < 0 then (BigInt(-1), r - den) else (BigInt(0), r)
        case RoundingPolicy.TowardPosInf =>
          if r != 0 && num.signum > 0 then (BigInt(1), r - den) else (BigInt(0), r)
        case RoundingPolicy.NearestEven =>
          val twice = r.abs * 2
          val up =
            twice > den.abs || (twice == den.abs && !(q % 2 == 0))
          if !up then (BigInt(0), r)
          else if num.signum < 0 then (BigInt(-1), r + den.abs)
          else (BigInt(1), r - den)
      val out = q + adj
      if !out.isValidLong then Left(RescaleRefusal.Overflow)
      else
        ExactRational.of(residualNum.toLong, den.toLong) match
          case Right(res) => Right((out.toLong, res))
          case Left(_)    => Left(RescaleRefusal.Overflow)

  def rescaleExact(
      tick: Long,
      source: RationalTimebase,
      target: RationalTimebase
  ): RescaleOutcome =
    val id = identityOf(tick, source, target, None, "exact")
    exactQuotient(tick, source, target) match
      case Right(out)                           => RescaleOutcome.Exact(out, target, id)
      case Left(RescaleRefusal.InexactIntegral) =>
        RescaleOutcome.Refused(RescaleRefusal.InexactIntegral, id)
      case Left(reason) => RescaleOutcome.Refused(reason, id)

  def rescaleIntegral(
      tick: Long,
      source: RationalTimebase,
      target: RationalTimebase,
      rounding: RoundingPolicy
  ): RescaleOutcome =
    val id = identityOf(tick, source, target, Some(rounding), "integral")
    roundedQuotient(tick, source, target, rounding) match
      case Right((out, residual)) if residual.isZero =>
        RescaleOutcome.Exact(out, target, id)
      case Right((out, residual)) =>
        RescaleOutcome.Quantized(out, target, rounding, residual, id)
      case Left(reason) => RescaleOutcome.Refused(reason, id)

  def substituteIdentity(
      outcome: RescaleOutcome,
      claimed: Checksum
  ): Either[DomainError, RescaleOutcome] =
    val actual = outcome match
      case RescaleOutcome.Exact(_, _, id)           => id
      case RescaleOutcome.Quantized(_, _, _, _, id) => id
      case RescaleOutcome.Refused(_, id)            => id
    if actual == claimed then Right(outcome)
    else
      Left(
        SourceCanon.inv(
          "rescale/identity",
          "claimed rescale identity does not match the recomputed preimage"
        )
      )

/** Caller-authored runtime packet. Internally consistent records remain draft through C1. */
final class CallerRuntimePacketRecord private (
    val rawPts: Long,
    val rawDts: Long,
    val durationTicks: Long,
    val claimedReceipt: Checksum,
    val identity: Checksum
):
  def authority: ObservationAuthority = ObservationAuthority.Draft
  override def equals(other: Any): Boolean = other match
    case that: CallerRuntimePacketRecord =>
      rawPts == that.rawPts && rawDts == that.rawDts && durationTicks == that.durationTicks &&
      claimedReceipt == that.claimedReceipt && identity == that.identity
    case _ => false
  override def hashCode(): Int =
    (rawPts, rawDts, durationTicks, claimedReceipt, identity).hashCode()
  override def toString: String = s"CallerRuntimePacketRecord(draft, ${identity.short()})"

object CallerRuntimePacketRecord:
  private def computeIdentity(
      rawPts: Long,
      rawDts: Long,
      durationTicks: Long,
      receipt: Checksum
  ): Checksum =
    SourceCanon.digest(
      "runtime-packet-draft",
      rawPts.toString,
      rawDts.toString,
      durationTicks.toString,
      receipt.hex
    )

  def draft(
      rawPts: Long,
      rawDts: Long,
      durationTicks: Long,
      claimedReceipt: Checksum
  ): Either[DomainError, CallerRuntimePacketRecord] =
    val identity = computeIdentity(rawPts, rawDts, durationTicks, claimedReceipt)
    Right(new CallerRuntimePacketRecord(rawPts, rawDts, durationTicks, claimedReceipt, identity))

  def promoteToRuntime(record: CallerRuntimePacketRecord): Either[DomainError, Nothing] =
    Left(
      SourceCanon.inv(
        "observation/authority",
        s"draft runtime record ${record.identity.short()} cannot become runtime-observed in C1"
      )
    )

/** Checked receipt for a source-axis derivation (clock repair, packet records). Identity is
  * computed from the preimage, never accepted as a label. Named `Source…` so it cannot shadow
  * `document.DerivationReceipt` under a wildcard import (ADR 0007 amendment 2026-09-01).
  */
final class SourceDerivationReceipt private (
    val algorithm: String,
    val parameters: String,
    val inputChecksums: Vector[Checksum],
    val identity: Checksum
):
  /** Safe full payload binding; preserves the historical `identity` preimage. */
  lazy val bindingIdentity: Checksum = SourceIdentity.receipt(this)
  override def equals(other: Any): Boolean = other match
    case that: SourceDerivationReceipt =>
      algorithm == that.algorithm && parameters == that.parameters &&
      inputChecksums == that.inputChecksums && identity == that.identity
    case _ => false
  override def hashCode(): Int = (algorithm, parameters, inputChecksums, identity).hashCode()
  override def toString: String = s"SourceDerivationReceipt($algorithm, ${identity.short()})"

object SourceDerivationReceipt:
  private def computeIdentity(
      algorithm: String,
      parameters: String,
      inputChecksums: Vector[Checksum]
  ): Checksum =
    SourceCanon.digest(
      ("derivation" +: algorithm +: parameters +: inputChecksums.map(_.hex))*
    )

  def of(
      algorithm: String,
      parameters: String,
      inputChecksums: Vector[Checksum]
  ): Either[DomainError, SourceDerivationReceipt] =
    if algorithm.trim.isEmpty then
      Left(SourceCanon.fmt("SourceDerivationReceipt", algorithm, "empty algorithm"))
    else
      Right(
        new SourceDerivationReceipt(
          algorithm,
          parameters,
          inputChecksums,
          computeIdentity(algorithm, parameters, inputChecksums)
        )
      )

  def rehydrate(
      algorithm: String,
      parameters: String,
      inputChecksums: Vector[Checksum],
      claimedIdentity: Checksum
  ): Either[DomainError, SourceDerivationReceipt] =
    of(algorithm, parameters, inputChecksums).flatMap { receipt =>
      if receipt.identity == claimedIdentity then Right(receipt)
      else
        Left(
          SourceCanon.inv(
            "derivation/identity",
            "claimed receipt identity does not match the recomputed preimage"
          )
        )
    }

/** Lawful axis extent. Unknown cannot normalize to a relative position. */
sealed trait AxisExtent
object AxisExtent:
  final class TextChars private[core] (val length: Int) extends AxisExtent:
    override def equals(other: Any): Boolean = other match
      case that: TextChars => length == that.length
      case _               => false
    override def hashCode(): Int = length.hashCode()
    override def toString: String = s"TextChars($length)"

  final class PlaybackTicks private[core] (
      val start: Long,
      val endExclusive: Long,
      val timebase: RationalTimebase
  ) extends AxisExtent:
    override def equals(other: Any): Boolean = other match
      case that: PlaybackTicks =>
        start == that.start && endExclusive == that.endExclusive && timebase == that.timebase
      case _ => false
    override def hashCode(): Int = (start, endExclusive, timebase).hashCode()
    override def toString: String = s"PlaybackTicks([$start,$endExclusive), $timebase)"

  case object Unknown extends AxisExtent

  def textChars(length: Int): Either[DomainError, AxisExtent.TextChars] =
    if length > 0 then Right(new AxisExtent.TextChars(length))
    else
      Left(SourceCanon.fmt("AxisExtent", length.toString, "text extent must be strictly positive"))

  def playbackTicks(
      start: Long,
      endExclusive: Long,
      timebase: RationalTimebase
  ): Either[DomainError, AxisExtent.PlaybackTicks] =
    if endExclusive > start then Right(new AxisExtent.PlaybackTicks(start, endExclusive, timebase))
    else
      Left(
        SourceCanon.fmt(
          "AxisExtent",
          s"[$start,$endExclusive)",
          "playback extent must be nonempty and finite"
        )
      )

/** Path-distinguished presentation axis. Kind and identity participate in fingerprints. */
final class PresentationAxis private[core] (
    val id: PresentationAxisId,
    val bundle: SourceBundleId,
    val edition: Option[EditionId],
    val kind: AxisKind,
    val sourceKind: SourceKind,
    val extent: AxisExtent,
    val timebase: Option[RationalTimebase],
    val fingerprint: Checksum
):
  override def equals(other: Any): Boolean = other match
    case that: PresentationAxis =>
      id == that.id && bundle == that.bundle && edition == that.edition && kind == that.kind &&
      sourceKind == that.sourceKind && extent == that.extent && timebase == that.timebase &&
      fingerprint == that.fingerprint
    case _ => false
  override def hashCode(): Int =
    (id, bundle, edition, kind, sourceKind, extent, timebase, fingerprint).hashCode()
  override def toString: String = s"PresentationAxis(${id.value}, $kind)"

object PresentationAxis:
  private def fingerprintOf(
      bundle: SourceBundleId,
      edition: Option[EditionId],
      kind: AxisKind,
      sourceKind: SourceKind,
      extent: AxisExtent,
      timebase: Option[RationalTimebase]
  ): Checksum =
    SourceCanon.digest(
      "axis",
      bundle.value,
      edition.map(_.value).getOrElse(""),
      kind.toString,
      sourceKind.toString,
      extent.toString,
      timebase.map(_.scale.toString).getOrElse("")
    )

  private def axisId(fingerprint: Checksum): Either[DomainError, PresentationAxisId] =
    PresentationAxisId.from(ContentAddress.of("axis", fingerprint.hex))

  def textCharacter(
      bundle: SourceBundleId,
      length: Int
  ): Either[DomainError, PresentationAxis] =
    AxisExtent.textChars(length).flatMap { extent =>
      val fp =
        fingerprintOf(bundle, None, AxisKind.TextCharacter, SourceKind.WrittenText, extent, None)
      axisId(fp).map { id =>
        new PresentationAxis(
          id,
          bundle,
          None,
          AxisKind.TextCharacter,
          SourceKind.WrittenText,
          extent,
          None,
          fp
        )
      }
    }

  def editionPlayback(
      bundle: SourceBundleId,
      edition: EditionId,
      start: Long,
      endExclusive: Long,
      timebase: RationalTimebase
  ): Either[DomainError, PresentationAxis] =
    AxisExtent.playbackTicks(start, endExclusive, timebase).flatMap { extent =>
      val fp = fingerprintOf(
        bundle,
        Some(edition),
        AxisKind.EditionPlayback,
        SourceKind.FilmEdition,
        extent,
        Some(timebase)
      )
      axisId(fp).map { id =>
        new PresentationAxis(
          id,
          bundle,
          Some(edition),
          AxisKind.EditionPlayback,
          SourceKind.FilmEdition,
          extent,
          Some(timebase),
          fp
        )
      }
    }

  /** The playback-shaped axis of a timed ANNOTATION, identified by the annotation's own checksum.
    *
    * It takes no `EditionId`, because there is no edition: the extent is the annotation's, not a
    * film's. Nothing downstream may read it as a claim about media.
    */
  def annotationTable(
      bundle: SourceBundleId,
      start: Long,
      endExclusive: Long,
      timebase: RationalTimebase
  ): Either[DomainError, PresentationAxis] =
    AxisExtent.playbackTicks(start, endExclusive, timebase).flatMap { extent =>
      val fp = fingerprintOf(
        bundle,
        None,
        AxisKind.AnnotationTimeline,
        SourceKind.AnnotationTable,
        extent,
        Some(timebase)
      )
      axisId(fp).map { id =>
        new PresentationAxis(
          id,
          bundle,
          None,
          AxisKind.AnnotationTimeline,
          SourceKind.AnnotationTable,
          extent,
          Some(timebase),
          fp
        )
      }
    }

  def inventedEditionPlayback(
      bundle: SourceBundleId,
      sourceKind: SourceKind,
      start: Long,
      endExclusive: Long,
      timebase: RationalTimebase
  ): Either[DomainError, PresentationAxis] =
    sourceKind match
      case SourceKind.FilmEdition =>
        Left(
          SourceCanon.inv(
            "axis/edition",
            s"bundle ${bundle.value} [$start,$endExclusive) ${timebase.scale} invents no named edition"
          )
        )
      case other =>
        Left(
          SourceCanon.inv(
            "axis/edition",
            s"$other cannot invent an edition playback axis on ${bundle.value}"
          )
        )

/** UTF-16 offset on one text axis. Not comparable to a playback tick. */
final class TextCoordinate private (val axis: PresentationAxisId, val offset: Int):
  override def equals(other: Any): Boolean = other match
    case that: TextCoordinate => axis == that.axis && offset == that.offset
    case _                    => false
  override def hashCode(): Int = (axis, offset).hashCode()
  override def toString: String = s"TextCoordinate(${axis.value}, $offset)"

object TextCoordinate:
  def on(axis: PresentationAxis, offset: Int): Either[DomainError, TextCoordinate] =
    if axis.kind != AxisKind.TextCharacter then
      Left(SourceCanon.inv("coordinate/text", "axis is not a text-character axis"))
    else if offset < 0 then
      Left(SourceCanon.fmt("TextCoordinate", offset.toString, "negative offset"))
    else
      axis.extent match
        case tc: AxisExtent.TextChars if offset >= tc.length =>
          Left(
            SourceCanon.fmt(
              "TextCoordinate",
              offset.toString,
              s"offset escapes extent ${tc.length}"
            )
          )
        case _: AxisExtent.TextChars => Right(new TextCoordinate(axis.id, offset))
        case AxisExtent.Unknown      =>
          Left(SourceCanon.inv("coordinate/text", "unknown extent cannot host a coordinate"))
        case _ =>
          Left(SourceCanon.inv("coordinate/text", "playback extent cannot host a text offset"))

  def compare(a: TextCoordinate, b: TextCoordinate): Either[DomainError, Int] =
    if a.axis != b.axis then Left(SourceCanon.inv("coordinate/axis", "foreign text axis identity"))
    else Right(a.offset.compareTo(b.offset))

/** Signed playback tick on one edition axis. Not comparable to a text offset. */
final class PlaybackCoordinate private (val axis: PresentationAxisId, val tick: Long):
  override def equals(other: Any): Boolean = other match
    case that: PlaybackCoordinate => axis == that.axis && tick == that.tick
    case _                        => false
  override def hashCode(): Int = (axis, tick).hashCode()
  override def toString: String = s"PlaybackCoordinate(${axis.value}, $tick)"

object PlaybackCoordinate:
  def on(axis: PresentationAxis, tick: Long): Either[DomainError, PlaybackCoordinate] =
    if axis.kind != AxisKind.EditionPlayback then
      Left(SourceCanon.inv("coordinate/playback", "axis is not an edition-playback axis"))
    else
      axis.extent match
        case pt: AxisExtent.PlaybackTicks if tick < pt.start || tick >= pt.endExclusive =>
          Left(
            SourceCanon.fmt(
              "PlaybackCoordinate",
              tick.toString,
              s"tick escapes [${pt.start},${pt.endExclusive})"
            )
          )
        case _: AxisExtent.PlaybackTicks =>
          Right(new PlaybackCoordinate(axis.id, tick))
        case AxisExtent.Unknown =>
          Left(SourceCanon.inv("coordinate/playback", "unknown extent cannot host a coordinate"))
        case _: AxisExtent.TextChars =>
          Left(SourceCanon.inv("coordinate/playback", "text extent cannot host a playback tick"))

  def compare(a: PlaybackCoordinate, b: PlaybackCoordinate): Either[DomainError, Int] =
    if a.axis != b.axis then
      Left(SourceCanon.inv("coordinate/axis", "foreign playback axis identity"))
    else Right(java.lang.Long.compare(a.tick, b.tick))

/** Finite relative position on one axis. Missing support never becomes 0.0. */
final class RelativePosition private (val axis: PresentationAxisId, val value: Double):
  override def equals(other: Any): Boolean = other match
    case that: RelativePosition => axis == that.axis && value == that.value
    case _                      => false
  override def hashCode(): Int = (axis, value).hashCode()
  override def toString: String = s"RelativePosition(${axis.value}, $value)"

object RelativePosition:
  def of(axis: PresentationAxisId, value: Double): Either[DomainError, RelativePosition] =
    if value.isNaN || value.isInfinite then
      Left(SourceCanon.fmt("RelativePosition", value.toString, "non-finite relative position"))
    else if value < 0.0 || value > 1.0 then
      Left(
        SourceCanon.fmt("RelativePosition", value.toString, "relative position must be in [0,1]")
      )
    else Right(new RelativePosition(axis, value))

object AxisNormalization:
  def text(axis: PresentationAxis, offset: Int): Either[DomainError, RelativePosition] =
    axis.extent match
      case tc: AxisExtent.TextChars if axis.kind == AxisKind.TextCharacter =>
        TextCoordinate.on(axis, offset).flatMap { _ =>
          RelativePosition.of(axis.id, offset.toDouble / tc.length.toDouble)
        }
      case AxisExtent.Unknown =>
        Left(
          SourceCanon.inv(
            "normalize/text",
            "unknown extent cannot normalize; missing stays missing"
          )
        )
      case _ =>
        Left(SourceCanon.inv("normalize/text", "axis cannot normalize a text offset"))

  def playback(axis: PresentationAxis, tick: Long): Either[DomainError, RelativePosition] =
    axis.extent match
      case pt: AxisExtent.PlaybackTicks if axis.kind == AxisKind.EditionPlayback =>
        PlaybackCoordinate.on(axis, tick).flatMap { _ =>
          val span = pt.endExclusive - pt.start
          RelativePosition.of(axis.id, (tick - pt.start).toDouble / span.toDouble)
        }
      case AxisExtent.Unknown =>
        Left(
          SourceCanon.inv(
            "normalize/playback",
            "unknown extent cannot normalize; missing stays missing"
          )
        )
      case _ =>
        Left(SourceCanon.inv("normalize/playback", "axis cannot normalize a playback tick"))

/** Instant on a playback axis. Hard cuts carry instants, not unmeasured durations. */
final class PlaybackInstant private (val axis: PresentationAxisId, val at: Long):
  override def equals(other: Any): Boolean = other match
    case that: PlaybackInstant => axis == that.axis && at == that.at
    case _                     => false
  override def hashCode(): Int = (axis, at).hashCode()
  override def toString: String = s"PlaybackInstant(${axis.value}, $at)"

object PlaybackInstant:
  def on(axis: PresentationAxis, at: Long): Either[DomainError, PlaybackInstant] =
    PlaybackCoordinate.on(axis, at).map(c => new PlaybackInstant(c.axis, c.tick))

/** Half-open playback interval on exactly one axis. Empty intervals are unrepresentable. */
final class PlaybackInterval private[core] (
    val axis: PresentationAxisId,
    val start: Long,
    val endExclusive: Long
):
  def contains(tick: Long): Boolean = tick >= start && tick < endExclusive
  def overlaps(other: PlaybackInterval): Either[DomainError, Boolean] =
    if axis != other.axis then
      Left(SourceCanon.inv("interval/axis", "foreign playback axis identity"))
    else Right(start < other.endExclusive && other.start < endExclusive)

  override def equals(other: Any): Boolean = other match
    case that: PlaybackInterval =>
      axis == that.axis && start == that.start && endExclusive == that.endExclusive
    case _ => false
  override def hashCode(): Int = (axis, start, endExclusive).hashCode()
  override def toString: String = s"PlaybackInterval(${axis.value}, [$start,$endExclusive))"

object PlaybackInterval:
  def on(
      axis: PresentationAxis,
      start: Long,
      endExclusive: Long
  ): Either[DomainError, PlaybackInterval] =
    if axis.kind != AxisKind.EditionPlayback then
      Left(SourceCanon.inv("interval/playback", "axis is not an edition-playback axis"))
    else if endExclusive <= start then
      Left(
        SourceCanon.fmt(
          "PlaybackInterval",
          s"[$start,$endExclusive)",
          "media interval must be nonempty"
        )
      )
    else
      PlaybackCoordinate.on(axis, start).flatMap { _ =>
        axis.extent match
          case pt: AxisExtent.PlaybackTicks if endExclusive > pt.endExclusive =>
            Left(
              SourceCanon.fmt(
                "PlaybackInterval",
                s"[$start,$endExclusive)",
                "interval escapes axis extent"
              )
            )
          case _ => Right(new PlaybackInterval(axis.id, start, endExclusive))
      }

/** Nonempty, sorted, deduplicated, same-axis playback intervals. */
final class PlaybackIntervalSet private (
    val axis: PresentationAxisId,
    val intervals: NonEmptyVector[PlaybackInterval]
):
  override def equals(other: Any): Boolean = other match
    case that: PlaybackIntervalSet =>
      axis == that.axis && intervals.toVector == that.intervals.toVector
    case _ => false
  override def hashCode(): Int = (axis, intervals.toVector).hashCode()
  override def toString: String = s"PlaybackIntervalSet(${axis.value}, ${intervals.length})"

object PlaybackIntervalSet:
  def of(intervals: Vector[PlaybackInterval]): Either[DomainError, PlaybackIntervalSet] =
    NonEmptyVector.fromVector(intervals) match
      case None => Left(SourceCanon.fmt("PlaybackIntervalSet", "[]", "empty support interval set"))
      case Some(nev) =>
        val axis = nev.head.axis
        if nev.toVector.exists(_.axis != axis) then
          Left(SourceCanon.inv("interval-set/axis", "mixed-axis interval set"))
        else
          val sorted = nev.toVector.distinct.sortBy(i => (i.start, i.endExclusive))
          val overlap = sorted.sliding(2).collectFirst {
            case Vector(a, b) if a.endExclusive > b.start =>
              SourceCanon.inv("interval-set/overlap", s"$a overlaps $b")
          }
          overlap match
            case Some(err) => Left(err)
            case None      =>
              NonEmptyVector
                .fromVector(sorted)
                .toRight(
                  SourceCanon.fmt("PlaybackIntervalSet", "[]", "empty after canonicalization")
                )
                .map(canon => new PlaybackIntervalSet(axis, canon))

  def one(interval: PlaybackInterval): PlaybackIntervalSet =
    new PlaybackIntervalSet(interval.axis, NonEmptyVector.one(interval))

/** Immutable stream manifest. Access rights live in a separate build envelope. */
final class SourceStream private (
    val id: StreamId,
    val kind: StreamKind,
    val checksum: Checksum,
    val nativeAxis: PresentationAxisId,
    val extent: AxisExtent,
    val timebase: Option[RationalTimebase],
    val derivedFrom: Vector[StreamId]
):
  override def equals(other: Any): Boolean = other match
    case that: SourceStream =>
      id == that.id && kind == that.kind && checksum == that.checksum &&
      nativeAxis == that.nativeAxis && extent == that.extent && timebase == that.timebase &&
      derivedFrom == that.derivedFrom
    case _ => false
  override def hashCode(): Int =
    (id, kind, checksum, nativeAxis, extent, timebase, derivedFrom).hashCode()
  override def toString: String = s"SourceStream(${id.value}, $kind)"

object SourceStream:
  def of(
      id: StreamId,
      kind: StreamKind,
      checksum: Checksum,
      nativeAxis: PresentationAxisId,
      extent: AxisExtent,
      timebase: Option[RationalTimebase],
      derivedFrom: Vector[StreamId]
  ): Either[DomainError, SourceStream] =
    extent match
      case tc: AxisExtent.TextChars if tc.length <= 0 =>
        Left(SourceCanon.fmt("SourceStream", id.value, "non-positive text extent"))
      case pt: AxisExtent.PlaybackTicks if pt.endExclusive <= pt.start =>
        Left(SourceCanon.fmt("SourceStream", id.value, "empty playback extent"))
      case _ =>
        Right(new SourceStream(id, kind, checksum, nativeAxis, extent, timebase, derivedFrom))

/** Content-addressed bundle of immutable streams and one declared primary axis. */
final class SourceBundle private (
    val id: SourceBundleId,
    val edition: Option[EditionId],
    val sourceKind: SourceKind,
    val streams: Vector[SourceStream],
    val primaryAxis: PresentationAxis,
    val authorityTracks: Vector[StreamId],
    val mappings: Vector[CheckedMapping]
):
  /** Full stream, coordinate and mapping identity; the legacy anchor-facing ID is unchanged. */
  lazy val identity: Checksum = SourceIdentity.bundle(this)
  lazy val streamById: Map[StreamId, SourceStream] = streams.iterator.map(s => s.id -> s).toMap
  def stream(id: StreamId): Option[SourceStream] = streamById.get(id)
  override def equals(other: Any): Boolean = other match
    case that: SourceBundle =>
      id == that.id && edition == that.edition && sourceKind == that.sourceKind &&
      streams == that.streams && primaryAxis == that.primaryAxis &&
      authorityTracks == that.authorityTracks && mappings == that.mappings
    case _ => false
  override def hashCode(): Int =
    (id, edition, sourceKind, streams, primaryAxis, authorityTracks, mappings).hashCode()
  override def toString: String = s"SourceBundle(${id.value}, $sourceKind)"

object SourceBundle:
  private[core] def computeId(
      edition: Option[EditionId],
      sourceKind: SourceKind,
      streams: Vector[SourceStream],
      axisKind: AxisKind,
      authorityTracks: Vector[StreamId]
  ): Either[DomainError, SourceBundleId] =
    val parts =
      Vector(
        "bundle",
        edition.map(_.value).getOrElse(""),
        sourceKind.toString,
        axisKind.toString
      ) ++
        streams
          .sortBy(_.id.value)
          .flatMap(s => Vector(s.id.value, s.kind.toString, s.checksum.hex)) ++
        authorityTracks.map(_.value)
    SourceBundleId.from(ContentAddress.of("bundle", parts*))

  private def validate(
      edition: Option[EditionId],
      sourceKind: SourceKind,
      streams: Vector[SourceStream],
      primaryAxis: PresentationAxis,
      authorityTracks: Vector[StreamId],
      mappings: Vector[CheckedMapping]
  ): Either[DomainError, Unit] =
    val ids = streams.map(_.id)
    if streams.isEmpty then Left(SourceCanon.fmt("SourceBundle", "[]", "bundle requires a stream"))
    else if ids.distinct.size != ids.size then
      Left(SourceCanon.inv("bundle/streams", "duplicate stream identity"))
    else if !authorityTracks.forall(id => ids.contains(id)) then
      Left(SourceCanon.inv("bundle/authority", "authority track is not a stream in the bundle"))
    else
      val kindOk = (sourceKind, primaryAxis.kind, edition) match
        case (SourceKind.WrittenText, AxisKind.TextCharacter, None) => Right(())
        case (SourceKind.WrittenText, AxisKind.EditionPlayback, _)  =>
          Left(
            SourceCanon.inv("bundle/axis", "written text cannot invent an edition playback axis")
          )
        case (SourceKind.FilmEdition, AxisKind.EditionPlayback, Some(_)) => Right(())
        case (SourceKind.FilmEdition, AxisKind.EditionPlayback, None)    =>
          Left(SourceCanon.inv("bundle/edition", "film edition playback requires a named edition"))
        case (SourceKind.FilmEdition, AxisKind.TextCharacter, _) =>
          Left(
            SourceCanon.inv(
              "bundle/axis",
              "film edition primary axis must be audience-facing playback"
            )
          )
        case (other, AxisKind.EditionPlayback, None) =>
          Left(SourceCanon.inv("bundle/axis", s"$other cannot invent an edition playback axis"))
        case _ => Right(())
      kindOk.flatMap { _ =>
        if mappings.exists(m =>
            !m.axes.forall(ax => ax == primaryAxis.id || streams.exists(_.nativeAxis == ax))
          )
        then Left(SourceCanon.inv("bundle/mapping", "mapping axis is foreign to the bundle"))
        else Right(())
      }

  def of(
      edition: Option[EditionId],
      sourceKind: SourceKind,
      streams: Vector[SourceStream],
      primaryAxis: PresentationAxis,
      authorityTracks: Vector[StreamId],
      mappings: Vector[CheckedMapping]
  ): Either[DomainError, SourceBundle] =
    validate(edition, sourceKind, streams, primaryAxis, authorityTracks, mappings).flatMap { _ =>
      computeId(edition, sourceKind, streams, primaryAxis.kind, authorityTracks).flatMap { id =>
        if primaryAxis.bundle != id then
          Left(
            SourceCanon.inv(
              "bundle/axis",
              "primary axis bundle identity does not match the content-addressed bundle"
            )
          )
        else
          Right(
            new SourceBundle(
              id,
              edition,
              sourceKind,
              streams,
              primaryAxis,
              authorityTracks,
              mappings
            )
          )
      }
    }

  def writtenText(source: StorySource): Either[DomainError, SourceBundle] =
    for
      streamId <- StreamId.from(ContentAddress.of("stream", source.canonicalChecksum.hex))
      extent <- AxisExtent.textChars(source.canonicalText.length)
      placeholderAxis <- PresentationAxis.textCharacter(
        SourceBundleId.unsafe("text-bundle-placeholder"),
        source.canonicalText.length
      )
      stream <- SourceStream.of(
        streamId,
        StreamKind.CanonicalText,
        source.canonicalChecksum,
        placeholderAxis.id,
        extent,
        None,
        Vector.empty
      )
      bundleId <- computeId(
        None,
        SourceKind.WrittenText,
        Vector(stream),
        AxisKind.TextCharacter,
        Vector(streamId)
      )
      axis <- PresentationAxis.textCharacter(bundleId, source.canonicalText.length)
      bound <- SourceStream.of(
        streamId,
        StreamKind.CanonicalText,
        source.canonicalChecksum,
        axis.id,
        extent,
        None,
        Vector.empty
      )
      bundle <- of(
        None,
        SourceKind.WrittenText,
        Vector(bound),
        axis,
        Vector(streamId),
        Vector.empty
      )
    yield bundle

  /** A bundle over a timed annotation table: no edition, no picture stream, no media claim.
    *
    * This is what Film Festival and Friends need and did not have. The annotation's checksum is its
    * identity, exactly as the Film Festival adapter already does by hand -- but without asserting a
    * film edition that does not exist.
    */
  def annotationTable(
      annotationChecksum: Checksum,
      start: Long,
      endExclusive: Long,
      timebase: RationalTimebase
  ): Either[DomainError, SourceBundle] =
    for
      streamId <- StreamId.from(ContentAddress.of("stream", annotationChecksum.hex))
      extent <- AxisExtent.playbackTicks(start, endExclusive, timebase)
      placeholder <- SourceBundleId.from(
        ContentAddress.of("bundle", "annotation-placeholder", annotationChecksum.hex)
      )
      placeholderAxis <- PresentationAxis.annotationTable(
        placeholder,
        start,
        endExclusive,
        timebase
      )
      draft <- SourceStream.of(
        streamId,
        StreamKind.Annotation,
        annotationChecksum,
        placeholderAxis.id,
        extent,
        Some(timebase),
        Vector.empty
      )
      bundleId <- computeId(
        None,
        SourceKind.AnnotationTable,
        Vector(draft),
        AxisKind.AnnotationTimeline,
        Vector(streamId)
      )
      axis <- PresentationAxis.annotationTable(bundleId, start, endExclusive, timebase)
      stream <- SourceStream.of(
        streamId,
        StreamKind.Annotation,
        annotationChecksum,
        axis.id,
        extent,
        Some(timebase),
        Vector.empty
      )
      bundle <- of(
        None,
        SourceKind.AnnotationTable,
        Vector(stream),
        axis,
        Vector(streamId),
        Vector.empty
      )
    yield bundle

  def filmEdition(
      edition: EditionId,
      pictureChecksum: Checksum,
      start: Long,
      endExclusive: Long,
      timebase: RationalTimebase
  ): Either[DomainError, SourceBundle] =
    for
      streamId <- StreamId.from(ContentAddress.of("stream", pictureChecksum.hex))
      extent <- AxisExtent.playbackTicks(start, endExclusive, timebase)
      placeholder <- SourceBundleId.from(
        ContentAddress.of("bundle", "film-placeholder", edition.value)
      )
      placeholderAxis <- PresentationAxis.editionPlayback(
        placeholder,
        edition,
        start,
        endExclusive,
        timebase
      )
      draft <- SourceStream.of(
        streamId,
        StreamKind.Picture,
        pictureChecksum,
        placeholderAxis.id,
        extent,
        Some(timebase),
        Vector.empty
      )
      bundleId <- computeId(
        Some(edition),
        SourceKind.FilmEdition,
        Vector(draft),
        AxisKind.EditionPlayback,
        Vector(streamId)
      )
      axis <- PresentationAxis.editionPlayback(bundleId, edition, start, endExclusive, timebase)
      stream <- SourceStream.of(
        streamId,
        StreamKind.Picture,
        pictureChecksum,
        axis.id,
        extent,
        Some(timebase),
        Vector.empty
      )
      bundle <- of(
        Some(edition),
        SourceKind.FilmEdition,
        Vector(stream),
        axis,
        Vector(streamId),
        Vector.empty
      )
    yield bundle

/** Typed evidence anchor. Mixed-axis algebra is not defined on this type. */
enum EvidenceAnchor:
  case Text(
      bundle: SourceBundleId,
      stream: StreamId,
      spans: SpanSet
  )
  case MediaTime(
      bundle: SourceBundleId,
      stream: StreamId,
      axis: PresentationAxisId,
      intervals: PlaybackIntervalSet
  )
  case MediaPoint(bundle: SourceBundleId, stream: StreamId, at: PlaybackInstant)
  case Shot(
      bundle: SourceBundleId,
      stream: StreamId,
      shot: ShotId,
      interval: PlaybackInterval
  )
  case Track(
      bundle: SourceBundleId,
      stream: StreamId,
      track: TrackId,
      intervals: PlaybackIntervalSet
  )

  def axisId: Option[PresentationAxisId] = this match
    case EvidenceAnchor.Text(_, _, _)            => None
    case EvidenceAnchor.MediaTime(_, _, axis, _) => Some(axis)
    case EvidenceAnchor.MediaPoint(_, _, at) => Some(at.axis)
    case EvidenceAnchor.Shot(_, _, _, interval)  => Some(interval.axis)
    case EvidenceAnchor.Track(_, _, _, ivs)      => Some(ivs.axis)

  def anchorBundle: SourceBundleId = this match
    case EvidenceAnchor.Text(b, _, _)         => b
    case EvidenceAnchor.MediaTime(b, _, _, _) => b
    case EvidenceAnchor.MediaPoint(b, _, _) => b
    case EvidenceAnchor.Shot(b, _, _, _)      => b
    case EvidenceAnchor.Track(b, _, _, _)     => b

  def anchorStream: StreamId = this match
    case EvidenceAnchor.Text(_, s, _)         => s
    case EvidenceAnchor.MediaTime(_, s, _, _) => s
    case EvidenceAnchor.MediaPoint(_, s, _) => s
    case EvidenceAnchor.Shot(_, s, _, _)      => s
    case EvidenceAnchor.Track(_, s, _, _)     => s

/** Nonempty heterogeneous support. Hull, overlap, and order require one selected axis. */
final class EvidenceSupport private (
    val anchors: NonEmptyVector[EvidenceAnchor],
    val bundleIdentity: Checksum
):
  /** Recheck the complete bundle binding before joining this support to a model or atlas. */
  def checkedOn(bundle: SourceBundle): Either[DomainError, EvidenceSupport] =
    if bundle.identity != bundleIdentity then
      Left(SourceCanon.inv("support/bundle-identity", "support belongs to a different full bundle identity"))
    else EvidenceSupport.of(bundle, anchors.toVector)

  /** Complete primary-axis support, retaining both explicit points and interval gaps. */
  def playbackOn(axis: PresentationAxisId): Either[DomainError, PlaybackSupport] =
    val intervals = anchors.toVector.flatMap {
      case EvidenceAnchor.MediaTime(_, _, a, set) if a == axis => set.intervals.toVector
      case EvidenceAnchor.Shot(_, _, _, interval) if interval.axis == axis => Vector(interval)
      case EvidenceAnchor.Track(_, _, _, set) if set.axis == axis => set.intervals.toVector
      case _ => Vector.empty
    }
    val points = anchors.toVector.collect {
      case EvidenceAnchor.MediaPoint(_, _, at) if at.axis == axis => at
    }
    PlaybackSupport.of(axis, intervals, points)

  def selectedPlaybackAxis: Either[DomainError, PresentationAxisId] =
    val axes = anchors.toVector.flatMap(_.axisId).distinct
    axes match
      case Vector(one) => Right(one)
      case Vector()    =>
        Left(SourceCanon.inv("support/axis", "support has no playback axis; text-only stays text"))
      case _ =>
        Left(SourceCanon.inv("support/axis", "mixed-axis support has no generic order or hull"))

  def hullOn(axis: PresentationAxisId): Either[DomainError, PlaybackInterval] =
    val ivs = anchors.toVector.collect {
      case EvidenceAnchor.MediaTime(_, _, a, set) if a == axis             => set.intervals.toVector
      case EvidenceAnchor.Shot(_, _, _, interval) if interval.axis == axis => Vector(interval)
      case EvidenceAnchor.Track(_, _, _, set) if set.axis == axis          => set.intervals.toVector
    }.flatten
    if anchors.toVector.exists {
      case EvidenceAnchor.MediaPoint(_, _, at) => at.axis == axis
      case _ => false
    } then Left(SourceCanon.inv("support/hull-point", "point-bearing support has no interval-only hull; use playbackOn"))
    else NonEmptyVector.fromVector(ivs) match
      case None =>
        Left(SourceCanon.inv("support/hull", "no intervals on the requested axis"))
      case Some(nev) =>
        val start = nev.toVector.map(_.start).min
        val end = nev.toVector.map(_.endExclusive).max
        Right(new PlaybackInterval(axis, start, end))

  /** A canonical interval union on exactly the requested axis; gaps are retained. */
  def intervalsOn(axis: PresentationAxisId): Either[DomainError, PlaybackIntervalSet] =
    val intervals = anchors.toVector
      .flatMap {
        case EvidenceAnchor.MediaTime(_, _, a, set) if a == axis => set.intervals.toVector
        case EvidenceAnchor.Shot(_, _, _, interval) if interval.axis == axis => Vector(interval)
        case EvidenceAnchor.Track(_, _, _, set) if set.axis == axis => set.intervals.toVector
        case _                                                      => Vector.empty
      }
      .sortBy(i => (i.start, i.endExclusive))
    val merged = intervals.foldLeft(Vector.empty[PlaybackInterval]) { (out, interval) =>
      out.lastOption match
        case Some(last) if interval.start <= last.endExclusive =>
          out.init :+ new PlaybackInterval(
            axis,
            last.start,
            last.endExclusive.max(interval.endExclusive)
          )
        case _ => out :+ interval
    }
    PlaybackIntervalSet.of(merged)

  /** Text coordinates from distinct streams are never unioned implicitly. */
  def textSpans(stream: StreamId): Option[SpanSet] =
    val sets = anchors.toVector.collect {
      case EvidenceAnchor.Text(_, s, spans) if s == stream => spans
    }
    sets.reduceOption(_ ++ _)

  override def equals(other: Any): Boolean = other match
    case that: EvidenceSupport => anchors.toVector == that.anchors.toVector && bundleIdentity == that.bundleIdentity
    case _                     => false
  override def hashCode(): Int = (anchors.toVector, bundleIdentity).hashCode()
  override def toString: String = s"EvidenceSupport(${anchors.length})"

object EvidenceSupport:
  def of(
      bundle: SourceBundle,
      anchors: Vector[EvidenceAnchor]
  ): Either[DomainError, EvidenceSupport] =
    NonEmptyVector.fromVector(anchors) match
      case None      => Left(SourceCanon.fmt("EvidenceSupport", "[]", "empty support"))
      case Some(nev) =>
        nev.toVector
          .foldLeft[Either[DomainError, Unit]](Right(())) { (result, anchor) =>
            result.flatMap(_ => SourceSupportChecks.anchor(bundle, anchor))
          }
          .map(_ => new EvidenceSupport(nev, bundle.identity))

  def text(
      bundle: SourceBundle,
      stream: StreamId,
      spans: SpanSet
  ): Either[DomainError, EvidenceSupport] =
    of(bundle, Vector(EvidenceAnchor.Text(bundle.id, stream, spans)))

  def media(
      bundle: SourceBundle,
      stream: StreamId,
      intervals: PlaybackIntervalSet
  ): Either[DomainError, EvidenceSupport] =
    of(bundle, Vector(EvidenceAnchor.MediaTime(bundle.id, stream, intervals.axis, intervals)))

/** Serializable mapping families. A mapping is data, not a Scala function field. */
enum MappingFamily:
  case ClockRepair, TrackComposition, EditionCorrespondence

/** Directed mapping relation identity, distinct from the derivation receipt. */
final class MappingRelation private (
    val id: MappingRelationId,
    val family: MappingFamily,
    val sourceAxis: PresentationAxisId,
    val targetAxis: PresentationAxisId,
    val identity: Checksum
):
  override def equals(other: Any): Boolean = other match
    case that: MappingRelation =>
      id == that.id && family == that.family && sourceAxis == that.sourceAxis &&
      targetAxis == that.targetAxis && identity == that.identity
    case _ => false
  override def hashCode(): Int = (id, family, sourceAxis, targetAxis, identity).hashCode()
  override def toString: String = s"MappingRelation($family, ${id.value})"

object MappingRelation:
  def of(
      family: MappingFamily,
      sourceAxis: PresentationAxisId,
      targetAxis: PresentationAxisId
  ): Either[DomainError, MappingRelation] =
    if sourceAxis == targetAxis then
      Left(SourceCanon.inv("mapping/relation", "source and target axes must be distinct"))
    else
      val identity =
        SourceCanon.digest("mapping", family.toString, sourceAxis.value, targetAxis.value)
      MappingRelationId.from(ContentAddress.of("map", identity.hex)).map { id =>
        new MappingRelation(id, family, sourceAxis, targetAxis, identity)
      }

sealed trait CheckedMapping:
  def family: MappingFamily
  def axes: Vector[PresentationAxisId]
  def receipt: SourceDerivationReceipt

  /** Binds the mapping payload and receipt, not merely its relation endpoints. */
  final lazy val identity: Checksum = SourceIdentity.mapping(this)

/** Partial monotone exact-rational clock repair for one source. */
final class ClockRepair private (
    val relation: MappingRelation,
    val scale: ExactRational,
    val offset: ExactRational,
    val receipt: SourceDerivationReceipt
) extends CheckedMapping:
  def family: MappingFamily = MappingFamily.ClockRepair
  def axes: Vector[PresentationAxisId] = Vector(relation.sourceAxis, relation.targetAxis)
  override def equals(other: Any): Boolean = other match
    case that: ClockRepair =>
      relation == that.relation && scale == that.scale && offset == that.offset &&
      receipt == that.receipt
    case _ => false
  override def hashCode(): Int = (relation, scale, offset, receipt).hashCode()
  override def toString: String = s"ClockRepair(${relation.id.value})"

object ClockRepair:
  def of(
      sourceAxis: PresentationAxisId,
      targetAxis: PresentationAxisId,
      scale: ExactRational,
      offset: ExactRational,
      receipt: SourceDerivationReceipt
  ): Either[DomainError, ClockRepair] =
    if !scale.isPositive then
      Left(SourceCanon.fmt("ClockRepair", scale.toString, "repair scale must be strictly positive"))
    else
      MappingRelation.of(MappingFamily.ClockRepair, sourceAxis, targetAxis).map { relation =>
        new ClockRepair(relation, scale, offset, receipt)
      }

  def projectRunLocalSeconds(
      seconds: ExactRational,
      sourceAxis: PresentationAxisId,
      targetAxis: PresentationAxisId,
      repair: ClockRepair
  ): Either[DomainError, ExactRational] =
    if repair.relation.sourceAxis != sourceAxis || repair.relation.targetAxis != targetAxis then
      Left(SourceCanon.inv("clock-repair/axis", "repair receipt does not bind these axes"))
    else seconds.*(repair.scale).flatMap(_ + repair.offset)

  def projectRunLocalSeconds(
      seconds: ExactRational,
      sourceAxis: PresentationAxisId,
      targetAxis: PresentationAxisId
  ): Either[DomainError, ExactRational] =
    Left(
      SourceCanon.inv(
        "clock-repair/receipt",
        s"run-local seconds ${seconds} on ${sourceAxis.value} cannot enter ${targetAxis.value} without a declared repair"
      )
    )

/** One occurrence-scoped composition segment. */
final class CompositionSegment private (
    val source: PlaybackInterval,
    val target: PlaybackInterval,
    val occurrence: OccurrenceId
):
  override def equals(other: Any): Boolean = other match
    case that: CompositionSegment =>
      source == that.source && target == that.target && occurrence == that.occurrence
    case _ => false
  override def hashCode(): Int = (source, target, occurrence).hashCode()
  override def toString: String = s"CompositionSegment(${occurrence.value})"

object CompositionSegment:
  def of(
      source: PlaybackInterval,
      target: PlaybackInterval,
      occurrence: OccurrenceId
  ): Either[DomainError, CompositionSegment] =
    if source.axis == target.axis then
      Left(SourceCanon.inv("composition/segment", "source and target intervals share an axis"))
    else Right(new CompositionSegment(source, target, occurrence))

/** Track composition into the edition playback axis. Targets must be disjoint. */
final class TrackComposition private (
    val relation: MappingRelation,
    val segments: NonEmptyVector[CompositionSegment],
    val receipt: SourceDerivationReceipt
) extends CheckedMapping:
  def family: MappingFamily = MappingFamily.TrackComposition
  def axes: Vector[PresentationAxisId] = Vector(relation.sourceAxis, relation.targetAxis)
  override def equals(other: Any): Boolean = other match
    case that: TrackComposition =>
      relation == that.relation && segments.toVector == that.segments.toVector &&
      receipt == that.receipt
    case _ => false
  override def hashCode(): Int = (relation, segments.toVector, receipt).hashCode()
  override def toString: String = s"TrackComposition(${relation.id.value})"

object TrackComposition:
  def of(
      sourceAxis: PresentationAxisId,
      targetAxis: PresentationAxisId,
      segments: Vector[CompositionSegment],
      receipt: SourceDerivationReceipt
  ): Either[DomainError, TrackComposition] =
    NonEmptyVector.fromVector(segments) match
      case None      => Left(SourceCanon.fmt("TrackComposition", "[]", "empty composition"))
      case Some(nev) =>
        val axisOk = nev.toVector.forall { s =>
          s.source.axis == sourceAxis && s.target.axis == targetAxis
        }
        if !axisOk then Left(SourceCanon.inv("composition/axis", "segment axis is foreign"))
        else
          val targets = nev.toVector.map(_.target).sortBy(i => (i.start, i.endExclusive))
          val overlap = targets.sliding(2).collectFirst {
            case Vector(a, b) if a.endExclusive > b.start =>
              SourceCanon.inv("composition/target", "target intervals must be disjoint")
          }
          overlap match
            case Some(err) => Left(err)
            case None      =>
              MappingRelation.of(MappingFamily.TrackComposition, sourceAxis, targetAxis).map {
                relation => new TrackComposition(relation, nev, receipt)
              }

/** Evidential edition correspondence. It is not a coordinate cast. */
final class EditionCorrespondence private (
    val relation: MappingRelation,
    val sourceEdition: EditionId,
    val targetEdition: EditionId,
    val pairs: NonEmptyVector[(OccurrenceId, OccurrenceId)],
    val receipt: SourceDerivationReceipt
) extends CheckedMapping:
  def family: MappingFamily = MappingFamily.EditionCorrespondence
  def axes: Vector[PresentationAxisId] = Vector(relation.sourceAxis, relation.targetAxis)
  override def equals(other: Any): Boolean = other match
    case that: EditionCorrespondence =>
      relation == that.relation && sourceEdition == that.sourceEdition &&
      targetEdition == that.targetEdition && pairs.toVector == that.pairs.toVector &&
      receipt == that.receipt
    case _ => false
  override def hashCode(): Int =
    (relation, sourceEdition, targetEdition, pairs.toVector, receipt).hashCode()
  override def toString: String = s"EditionCorrespondence(${sourceEdition.value})"

object EditionCorrespondence:
  def of(
      sourceAxis: PresentationAxisId,
      targetAxis: PresentationAxisId,
      sourceEdition: EditionId,
      targetEdition: EditionId,
      pairs: Vector[(OccurrenceId, OccurrenceId)],
      receipt: SourceDerivationReceipt
  ): Either[DomainError, EditionCorrespondence] =
    if sourceEdition == targetEdition then
      Left(SourceCanon.inv("correspondence/edition", "editions must be distinct"))
    else
      NonEmptyVector.fromVector(pairs) match
        case None => Left(SourceCanon.fmt("EditionCorrespondence", "[]", "empty correspondence"))
        case Some(nev) =>
          MappingRelation.of(MappingFamily.EditionCorrespondence, sourceAxis, targetAxis).map {
            relation =>
              new EditionCorrespondence(relation, sourceEdition, targetEdition, nev, receipt)
          }

/** Shot transition morphology. Unlawful extent pairings are unrepresentable. */
enum ShotMorphology:
  case HardCut(at: PlaybackInstant)
  case Dissolve(interval: PlaybackInterval)
  case Fade(interval: PlaybackInterval)

object ShotMorphology:
  def hardCut(at: PlaybackInstant): ShotMorphology.HardCut = ShotMorphology.HardCut(at)
  def dissolve(interval: PlaybackInterval): ShotMorphology.Dissolve =
    ShotMorphology.Dissolve(interval)
  def fade(interval: PlaybackInterval): ShotMorphology.Fade = ShotMorphology.Fade(interval)

/** Distinct boundary layers on a compatible axis. Coincidence does not convert claims. */
enum BoundaryLayer:
  case Shot, CodedScene, NarrativeEvent

enum BoundarySearchCoverage:
  case NotExamined
  case ExaminedNoCandidate
  case NegativeProposal(claim: BoundaryClaim)

/** Typed boundary claim. Empty detector output cannot construct a negative proposal. */
final class BoundaryClaim private (
    val id: BoundaryId,
    val layer: BoundaryLayer,
    val axis: PresentationAxisId,
    val morphology: Option[ShotMorphology],
    val instant: Option[PlaybackInstant],
    val interval: Option[PlaybackInterval]
):
  override def equals(other: Any): Boolean = other match
    case that: BoundaryClaim =>
      id == that.id && layer == that.layer && axis == that.axis && morphology == that.morphology &&
      instant == that.instant && interval == that.interval
    case _ => false
  override def hashCode(): Int = (id, layer, axis, morphology, instant, interval).hashCode()
  override def toString: String = s"BoundaryClaim(${id.value}, $layer)"

object BoundaryClaim:
  def shot(id: BoundaryId, morphology: ShotMorphology): Either[DomainError, BoundaryClaim] =
    val (axis, instant, interval) = morphology match
      case ShotMorphology.HardCut(at)  => (at.axis, Some(at), None)
      case ShotMorphology.Dissolve(iv) => (iv.axis, None, Some(iv))
      case ShotMorphology.Fade(iv)     => (iv.axis, None, Some(iv))
    Right(new BoundaryClaim(id, BoundaryLayer.Shot, axis, Some(morphology), instant, interval))

  def codedScene(
      id: BoundaryId,
      at: PlaybackInstant
  ): Either[DomainError, BoundaryClaim] =
    Right(new BoundaryClaim(id, BoundaryLayer.CodedScene, at.axis, None, Some(at), None))

  def narrativeEvent(
      id: BoundaryId,
      interval: PlaybackInterval
  ): Either[DomainError, BoundaryClaim] =
    Right(
      new BoundaryClaim(id, BoundaryLayer.NarrativeEvent, interval.axis, None, None, Some(interval))
    )

object BoundarySearchCoverage:
  def negativeFromEmptyDetector(layer: BoundaryLayer): Either[DomainError, BoundarySearchCoverage] =
    Left(
      SourceCanon.inv(
        "boundary/coverage",
        s"empty $layer detector output cannot construct a negative-boundary proposal"
      )
    )

/** Proposal unit carried by a checked narrative atlas. */
final class NarrativeProposalUnit private (
    val id: NarrativeProposalUnitId,
    val support: EvidenceSupport,
    val surface: Option[SurfaceUnitId]
):
  override def equals(other: Any): Boolean = other match
    case that: NarrativeProposalUnit =>
      id == that.id && support == that.support && surface == that.surface
    case _ => false
  override def hashCode(): Int = (id, support, surface).hashCode()
  override def toString: String = s"NarrativeProposalUnit(${id.value})"

object NarrativeProposalUnit:
  def of(
      id: NarrativeProposalUnitId,
      support: EvidenceSupport,
      surface: Option[SurfaceUnitId]
  ): NarrativeProposalUnit =
    new NarrativeProposalUnit(id, support, surface)
