package storymodel4s.corpus.intake

import cats.syntax.all.*
import storymodel4s.core.*
import storymodel4s.recall.RecallTiming

/** Exact run/sample coordinates under explicit declarations. Shape checking is not data admission.
  */
object ScannerCrosswalk:
  val Version: String = "declared-scanner-crosswalk/v1"

  enum Refusal:
    case InvalidIdentity, InvalidSamples, InvalidLayout, InvalidDomain, InvalidScale
    case BindingMismatch, MissingEvidence, UnknownOccurrence, AmbiguousOccurrence
    case ForeignClock, OutOfDomain, Unrepresentable, InvalidIndex, InvalidRational

  final case class RunKey(
      dataset: String,
      revision: String,
      participant: String,
      session: Option[String],
      task: String,
      run: String
  )
  enum Origin:
    case FirstStoredSample
    case Declared(reference: Checksum)
  enum AppliedHistory:
    case Unknown
    case Declared(receipts: Vector[Checksum])

  /** Neither a checksum nor a sample-count declaration certifies that a header was inspected. */
  final class Run private (
      val key: RunKey,
      val image: Checksum,
      val header: Checksum,
      val sampleSeconds: Vector[ExactRational],
      val origin: Origin,
      val history: AppliedHistory,
      val digest: Checksum
  ):
    def sampleCount: Int = sampleSeconds.size
    def at(seconds: ExactRational): RunTime = new RunTime(digest, seconds)
    def sample(index: Int): Either[Refusal, RunTime] =
      sampleSeconds.lift(index).map(at).toRight(Refusal.InvalidIndex)

  object Run:
    def declared(
        key: RunKey,
        image: Checksum,
        header: Checksum,
        sampleCount: Int,
        sampleSeconds: Vector[ExactRational],
        origin: Origin,
        history: AppliedHistory
    ): Either[Refusal, Run] =
      val labels = Vector(
        key.dataset,
        key.revision,
        key.participant,
        key.task,
        key.run
      ) ++ key.session.toVector
      if !sampleSeconds.forall(canonical) then Left(Refusal.InvalidRational)
      else if labels.exists(_.trim.isEmpty) then Left(Refusal.InvalidIdentity)
      else if sampleCount <= 0 || sampleSeconds.size != sampleCount ||
        sampleSeconds.sliding(2).exists {
          case Vector(a, b) => compare(a, b) >= 0; case _ => false
        } ||
        (origin == Origin.FirstStoredSample && sampleSeconds.head != ExactRational.Zero)
      then Left(Refusal.InvalidSamples)
      else
        val identity = digest(
          Vector(
            Version,
            "run",
            sequence(labels.take(3)),
            optional(key.session),
            key.task,
            key.run,
            image.hex,
            header.hex,
            sequence(sampleSeconds.map(rational)),
            origin.toString,
            history match
              case AppliedHistory.Unknown            => "unknown"
              case AppliedHistory.Declared(receipts) =>
                sequence(Vector("declared") ++ receipts.map(_.hex))
          )
        )
        Right(new Run(key, image, header, sampleSeconds, origin, history, identity))

    /** A declared sampling period; sample points do not imply measured acquisition-duration bins.
      */
    def regular(
        key: RunKey,
        image: Checksum,
        header: Checksum,
        sampleCount: Int,
        period: ExactRational,
        origin: Origin,
        history: AppliedHistory
    ): Either[Refusal, Run] =
      if !canonical(period) then Left(Refusal.InvalidRational)
      else if sampleCount <= 0 || !period.isPositive then Left(Refusal.InvalidSamples)
      else
        (0 until sampleCount).toVector
          .traverse(i => reduced(BigInt(i) * period.numerator, BigInt(period.denominator)))
          .flatMap(times => declared(key, image, header, sampleCount, times, origin, history))

  enum Censoring:
    case Included
    case Censored(evidence: Checksum)
    case Unknown
  enum Slot:
    case Acquired(storedIndex: Int, censoring: Censoring)
    case Padding(reason: String)

  /** An ordered subset of stored volumes plus explicit padding; no implicit reindexing of time. */
  final class Layout private (
      val run: Run,
      val analysisArray: Checksum,
      val receipt: Checksum,
      val slots: Vector[Slot],
      val droppedStoredIndices: Vector[Int],
      val digest: Checksum
  ):
    def sample(index: Int): Either[Refusal, Option[RunTime]] =
      slots.lift(index).toRight(Refusal.InvalidIndex).flatMap {
        case Slot.Acquired(stored, _) => run.sample(stored).map(Some(_))
        case Slot.Padding(_)          => Right(None)
      }
  object Layout:
    def declared(
        run: Run,
        analysisArray: Checksum,
        expectedLength: Int,
        slots: Vector[Slot],
        receipt: Checksum
    ): Either[Refusal, Layout] =
      val acquired = slots.collect { case Slot.Acquired(index, _) => index }
      if expectedLength != slots.size || expectedLength < 0 ||
        acquired.exists(i => i < 0 || i >= run.sampleCount) ||
        acquired != acquired.distinct.sorted || slots.exists {
          case Slot.Padding(reason) => reason.trim.isEmpty
          case _                    => false
        }
      then Left(Refusal.InvalidLayout)
      else
        val missing = (0 until run.sampleCount).filterNot(acquired.toSet).toVector
        val identity = digest(
          Vector(
            Version,
            "layout",
            run.digest.hex,
            analysisArray.hex,
            receipt.hex,
            sequence(slots.map {
              case Slot.Acquired(i, censoring) =>
                sequence(Vector("acquired", i.toString, censoring.toString))
              case Slot.Padding(reason) => sequence(Vector("padding", reason))
            })
          )
        )
        Right(new Layout(run, analysisArray, receipt, slots, missing, identity))

  enum ReferenceKind:
    case RecallClock, MediaPart, MediaOccurrence
  final class Reference private (
      val kind: ReferenceKind,
      val digest: Checksum,
      val artifactIdentity: Checksum,
      val mediaAxis: Option[PresentationAxis],
      val occurrence: Option[(Checksum, CompositionSegment)]
  ):
    def at(seconds: ExactRational): ReferenceTime = new ReferenceTime(digest, seconds)

    /** Media seconds use the admitted timebase; no origin subtraction or cartoon correction. */
    def tick(tick: Long): Either[Refusal, ReferenceTime] =
      mediaAxis.toRight(Refusal.ForeignClock).flatMap { axis =>
        for
          _ <- PlaybackInstant.on(axis, tick).left.map(_ => Refusal.OutOfDomain)
          timebase <- axis.timebase.toRight(Refusal.ForeignClock)
          value <- reduced(
            BigInt(tick) * timebase.scale.numerator,
            BigInt(timebase.scale.denominator)
          )
        yield at(value)
      }
  object Reference:
    def recall(clock: RecallTiming.Clock): Reference = new Reference(
      ReferenceKind.RecallClock,
      digest(Vector(Version, "recall", clock.digest.hex)),
      clock.digest,
      None,
      None
    )
    def mediaPart(bundle: SourceBundle): Either[Refusal, Reference] =
      if bundle.primaryAxis.kind != AxisKind.EditionPlayback then Left(Refusal.ForeignClock)
      else
        Right(
          new Reference(
            ReferenceKind.MediaPart,
            digest(Vector(Version, "part", bundle.identity.hex)),
            bundle.identity,
            Some(bundle.primaryAxis),
            None
          )
        )
    def mediaOccurrence(
        bundle: SourceBundle,
        mapping: Checksum,
        occurrence: OccurrenceId
    ): Either[Refusal, Reference] =
      val segments = bundle.mappings
        .collect {
          case m: TrackComposition
              if m.identity == mapping && m.relation.targetAxis == bundle.primaryAxis.id =>
            m
        }
        .flatMap(_.segments.toVector.filter(_.occurrence == occurrence))
      segments match
        case Vector(segment) =>
          Right(
            new Reference(
              ReferenceKind.MediaOccurrence,
              digest(
                Vector(Version, "occurrence", bundle.identity.hex, mapping.hex, occurrence.value)
              ),
              bundle.identity,
              Some(bundle.primaryAxis),
              Some(mapping -> segment)
            )
          )
        case Vector() => Left(Refusal.UnknownOccurrence)
        case _        => Left(Refusal.AmbiguousOccurrence)

  final class ReferenceTime private[ScannerCrosswalk] (
      val reference: Checksum,
      val seconds: ExactRational
  )
  final class RunTime private[ScannerCrosswalk] (val run: Checksum, val seconds: ExactRational)
  final class Window private (val start: ExactRational, val endExclusive: ExactRational):
    def contains(value: ExactRational): Boolean =
      compare(start, value) <= 0 && compare(value, endExclusive) < 0
  object Window:
    def of(start: ExactRational, endExclusive: ExactRational): Either[Refusal, Window] =
      if !canonical(start) || !canonical(endExclusive) then Left(Refusal.InvalidRational)
      else
        Either.cond(
          compare(start, endExclusive) < 0,
          new Window(start, endExclusive),
          Refusal.InvalidDomain
        )

  /** A byte-bound declaration of the join. It records an assumption, not independent verification.
    */
  final case class Evidence(
      reference: Checksum,
      run: Checksum,
      declaration: Checksum,
      supportingArtifacts: Vector[Checksum]
  )

  final class Binding private (
      val reference: Reference,
      val run: Run,
      val domain: Window,
      val repair: ClockRepair,
      val evidence: Evidence,
      val digest: Checksum
  ):
    def project(time: ReferenceTime): Either[Refusal, RunTime] =
      if !canonical(time.seconds) then Left(Refusal.InvalidRational)
      else if time.reference != reference.digest then Left(Refusal.ForeignClock)
      else if !domain.contains(time.seconds) then Left(Refusal.OutOfDomain)
      else affine(time.seconds, repair.scale, repair.offset).map(run.at)
    def inverse(time: RunTime): Either[Refusal, ReferenceTime] =
      if !canonical(time.seconds) then Left(Refusal.InvalidRational)
      else if time.run != run.digest then Left(Refusal.ForeignClock)
      else
        val t = time.seconds
        val b = repair.offset
        val a = repair.scale
        reduced(
          (BigInt(t.numerator) * b.denominator - BigInt(
            b.numerator
          ) * t.denominator) * a.denominator,
          BigInt(t.denominator) * b.denominator * a.numerator
        ).flatMap { value =>
          if domain.contains(value) then Right(reference.at(value)) else Left(Refusal.OutOfDomain)
        }
  object Binding:
    def declared(
        reference: Reference,
        run: Run,
        domain: Window,
        scale: ExactRational,
        offset: ExactRational,
        evidence: Evidence
    ): Either[Refusal, Binding] =
      for
        _ <- Either.cond(
          evidence.reference == reference.digest && evidence.run == run.digest,
          (),
          Refusal.BindingMismatch
        )
        _ <- Either.cond(evidence.supportingArtifacts.nonEmpty, (), Refusal.MissingEvidence)
        _ <- Either.cond(canonical(scale) && canonical(offset), (), Refusal.InvalidRational)
        _ <- Either.cond(scale.isPositive, (), Refusal.InvalidScale)
        _ <- reference.mediaAxis match
          case Some(axis) =>
            val tb = axis.timebase.get.scale
            val extent = axis.extent.asInstanceOf[AxisExtent.PlaybackTicks]
            val bounds = reference.occurrence.fold(extent.start -> extent.endExclusive)(
              (_, segment) => segment.target.start -> segment.target.endExclusive
            )
            for
              start <- reduced(BigInt(bounds._1) * tb.numerator, BigInt(tb.denominator))
              end <- reduced(BigInt(bounds._2) * tb.numerator, BigInt(tb.denominator))
              _ <- Either.cond(
                compare(domain.start, start) >= 0 && compare(domain.endExclusive, end) <= 0,
                (),
                Refusal.InvalidDomain
              )
            yield ()
          case None => Right(())
        receipt <- SourceDerivationReceipt
          .of(
            Version,
            "declared reference seconds to first-stored/run-origin seconds; no HRF",
            Vector(
              reference.digest,
              run.digest,
              evidence.declaration
            ) ++ evidence.supportingArtifacts
          )
          .left
          .map(_ => Refusal.MissingEvidence)
        repair <- ClockRepair
          .of(
            PresentationAxisId.unsafe("reference-seconds:" + reference.digest.hex),
            PresentationAxisId.unsafe("scanner-seconds:" + run.digest.hex),
            scale,
            offset,
            receipt
          )
          .left
          .map(_ => Refusal.InvalidScale)
        // Both boundary images must be representable, even though the upper endpoint is excluded.
        _ <- affine(domain.start, scale, offset)
        _ <- affine(domain.endExclusive, scale, offset)
        id = digest(
          Vector(
            Version,
            "binding",
            reference.digest.hex,
            run.digest.hex,
            repair.identity.hex,
            rational(domain.start),
            rational(domain.endExclusive)
          )
        )
      yield new Binding(reference, run, domain, repair, evidence, id)

  private def canonical(value: ExactRational): Boolean =
    value.denominator > 0L && BigInt(value.numerator).gcd(BigInt(value.denominator)) == 1

  private def compare(a: ExactRational, b: ExactRational): Int =
    (BigInt(a.numerator) * b.denominator).compare(BigInt(b.numerator) * a.denominator)
  private def reduced(n: BigInt, d: BigInt): Either[Refusal, ExactRational] =
    if d == 0 then Left(Refusal.Unrepresentable)
    else
      val divisor = n.gcd(d)
      val sign = if d < 0 then -1 else 1
      val nn = n / divisor * sign
      val dd = d / divisor * sign
      if nn.isValidLong && dd.isValidLong then
        ExactRational.of(nn.toLong, dd.toLong).left.map(_ => Refusal.Unrepresentable)
      else Left(Refusal.Unrepresentable)
  private def affine(
      t: ExactRational,
      a: ExactRational,
      b: ExactRational
  ): Either[Refusal, ExactRational] =
    reduced(
      BigInt(t.numerator) * a.numerator * b.denominator + BigInt(
        b.numerator
      ) * t.denominator * a.denominator,
      BigInt(t.denominator) * a.denominator * b.denominator
    )
  private def rational(value: ExactRational): String = sequence(
    Vector(value.numerator.toString, value.denominator.toString)
  )
  private def optional(value: Option[String]): String = sequence(
    value.fold(Vector("none"))(s => Vector("some", s))
  )
  private def sequence(values: Vector[String]): String =
    values.size.toString + ":" + values.map(s => s"${s.length}:$s").mkString
  private def digest(values: Vector[String]): Checksum =
    Checksum.ofText(sequence(values).iterator.map(c => f"${c.toInt}%04x").mkString)
