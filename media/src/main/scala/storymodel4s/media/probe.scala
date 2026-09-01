package storymodel4s.media

import storymodel4s.core.{
  CallerRuntimePacketRecord,
  Checksum,
  ContentAddress,
  DomainError,
  FixturePacketNormalizer,
  MediaDuration,
  ObservationAuthority,
  PacketTimeFields,
  RationalTimebase,
  SourceDerivationReceipt,
  StreamKind,
  TimestampField
}

/** One packet as the join typed it: the checked timestamp fields (present or missing, never a
  * sentinel), the typed duration (known positive or unknown), the flags, and the `Draft` record
  * core mints. The record's raw `Long`s mirror libavformat's sentinel because core's constructor
  * asks for `Long`s; nothing downstream reads missingness from them. `fields` is the authority.
  */
final class ProbedPacket private[media] (
    val fields: PacketTimeFields,
    val duration: MediaDuration,
    val durationReported: Option[Long],
    val flags: PacketFlags,
    val record: CallerRuntimePacketRecord
):
  def pts: TimestampField = fields.pts
  def dts: TimestampField = fields.dts
  override def toString: String = s"ProbedPacket(${fields.pts}, ${fields.dts}, ${flags.raw})"

/** One declared stream joined with what the tool observed about it. Packets stay in file order. */
final class ProbedStream private[media] (
    val declared: DeclaredStream,
    val codecName: String,
    val startPts: Option[Long],
    val packets: Vector[ProbedPacket]
):
  def index: Int = declared.index
  def timebase: RationalTimebase = declared.timebase
  override def toString: String =
    s"ProbedStream(${declared.index}, $codecName, ${packets.size} packets)"

/** Presentation-order packet index for one stream: an explicit packet-to-PTS table over the packets
  * the container presents. Why: a variable-frame-rate lookup must consult this table, never
  * `frame / nominalFps`. Discard-flagged packets are counted but not presented; corrupt packets
  * refuse the index. All arithmetic is exact or refuses.
  */
final class PacketIndex private (
    val streamIndex: Int,
    val timebase: RationalTimebase,
    val entries: Vector[PacketIndex.Entry],
    val discarded: Int,
    val deltas: Vector[Long],
    val contiguous: Boolean
):
  /** More than one distinct successive delta. Constant spacing is not asserted from a rate field.
    */
  def variableFrameRate: Boolean = deltas.distinct.size > 1

  def firstPts: Long = entries.head.pts

  /** Exclusive end when the last duration is known; unknown is not zero. */
  def endExclusive: Option[Long] = entries.last.durationTicks.map(d => entries.last.pts + d)

  override def toString: String = s"PacketIndex($streamIndex, ${entries.size} entries)"

object PacketIndex:
  /** `presentationOrdinal` counts in PTS order; `packetOrdinal` is the file position. */
  final case class Entry(
      presentationOrdinal: Int,
      packetOrdinal: Int,
      pts: Long,
      durationTicks: Option[Long]
  )

  /** Build the index. Refuses when the stream is `Unsupported`, when a packet is corrupt, when any
    * presented packet lacks a PTS (the index cannot invent one), when two share a PTS, when no
    * packet is presented, or when tick arithmetic would overflow.
    */
  def of(stream: ProbedStream): Either[DomainError, PacketIndex] =
    for
      _ <-
        if stream.declared.disposition == StreamDisposition.Unsupported then
          Left(
            DomainError.InvariantViolation(
              "index/disposition",
              s"stream ${stream.index} is declared Unsupported and is not indexed"
            )
          )
        else Right(())
      _ <- stream.packets.indexWhere(_.flags.corrupt) match
        case -1 => Right(())
        case k  =>
          Left(
            DomainError.InvariantViolation(
              "index/corrupt",
              s"stream ${stream.index} packet $k is flagged corrupt; the index does not repair it"
            )
          )
      presented = stream.packets.zipWithIndex.filterNot(_._1.flags.discard)
      _ <-
        if presented.nonEmpty then Right(())
        else
          Left(
            DomainError.InvariantViolation(
              "index/packets",
              s"stream ${stream.index} presents no packets"
            )
          )
      _ <- presented.find { case (p, _) => p.pts == TimestampField.Missing } match
        case None         => Right(())
        case Some((_, k)) =>
          Left(
            DomainError.InvariantViolation(
              "index/pts",
              s"stream ${stream.index} packet $k has no PTS; a packet-to-PTS index cannot invent one"
            )
          )
      withPts = presented.flatMap { case (p, k) =>
        p.pts match
          case TimestampField.Present(v) => Some((v, k, p))
          case TimestampField.Missing    => None
      }
      ordered = withPts.sortBy(_._1)
      _ <- ordered.iterator.sliding(2).collectFirst {
        case Seq((a, _, _), (b, _, _)) if a == b => a
      } match
        case Some(pts) =>
          Left(
            DomainError.InvariantViolation(
              "index/pts",
              s"stream ${stream.index} has two presented packets at PTS $pts"
            )
          )
        case None => Right(())
      entries = ordered.zipWithIndex.map { case ((pts, fileOrdinal, p), presentation) =>
        Entry(
          presentation,
          fileOrdinal,
          pts,
          p.duration match
            case known: MediaDuration.KnownPositive => Some(known.ticks)
            case MediaDuration.Unknown              => None
        )
      }
      deltas <- exactDeltas(stream.index, entries)
      contiguous <- exactContiguity(stream.index, entries)
    yield new PacketIndex(
      stream.index,
      stream.timebase,
      entries,
      stream.packets.size - presented.size,
      deltas,
      contiguous
    )

  private def exactDeltas(
      streamIndex: Int,
      entries: Vector[Entry]
  ): Either[DomainError, Vector[Long]] =
    try
      Right(
        entries.iterator
          .sliding(2)
          .collect { case Seq(a, b) => Math.subtractExact(b.pts, a.pts) }
          .toVector
      )
    catch
      case _: ArithmeticException =>
        Left(
          DomainError.InvariantViolation(
            "index/overflow",
            s"stream $streamIndex: PTS delta overflows"
          )
        )

  /** Every presented packet's known duration reaches exactly the next PTS. False when any duration
    * is unknown; unknown is not zero.
    */
  private def exactContiguity(
      streamIndex: Int,
      entries: Vector[Entry]
  ): Either[DomainError, Boolean] =
    try
      Right(entries.iterator.sliding(2).forall {
        case Seq(a, b) => a.durationTicks.exists(d => Math.addExact(a.pts, d) == b.pts)
        case _         => true
      })
    catch
      case _: ArithmeticException =>
        Left(
          DomainError.InvariantViolation(
            "index/overflow",
            s"stream $streamIndex: PTS plus duration overflows"
          )
        )

/** The joined draft outcome of one probe: the verified input, the tool that ran, the exact
  * arguments, every declared stream with its observed packets, and the derivation receipt that
  * binds them. Authority is `Draft` (ADR 0007 C1): nothing here is runtime-observed evidence, and
  * no operation on this type can make it so. The join does not itself read the input bytes; the
  * caller supplies the verified identity, and a recorded envelope supplies it for replay.
  */
final class MediaProbe private (
    val manifest: FixtureManifest,
    val input: Checksum,
    val tool: ToolRealization,
    val args: Vector[String],
    val streams: Vector[ProbedStream],
    val receipt: SourceDerivationReceipt,
    val identity: Checksum
):
  def authority: ObservationAuthority = ObservationAuthority.Draft
  def stream(index: Int): Option[ProbedStream] = streams.find(_.index == index)
  override def equals(other: Any): Boolean = other match
    case that: MediaProbe => identity == that.identity
    case _                => false
  override def hashCode(): Int = identity.hashCode()
  override def toString: String = s"MediaProbe(${manifest.fixtureId}, draft, ${identity.short()})"

object MediaProbe:
  /** Algorithm name carried by every receipt this join issues. */
  val Algorithm: String = "ffprobe-packet-index/v2"

  /** Canonical parameter string: the tool identity and the exact argument vector, each argument
    * length-prefixed so the encoding is injective.
    */
  def parameters(tool: ToolRealization, args: Vector[String]): String =
    (Vector("tool", tool.identity.hex, "args") ++ args.map(a => s"${a.length}:$a")).mkString(" ")

  /** Join a verified input, a realized tool, the arguments it ran with, and its parsed output
    * against the manifest. Refuses on any of: input identity mismatch; an observed stream with no
    * declaration; a declared stream the tool did not report; codec, kind, timebase, picture format
    * and geometry, or audio rate and channels disagreeing with the declaration; a packet on an
    * undeclared stream; a present PTS before a present DTS; a negative duration. Refusal preserves
    * the tool's raw fields in the message and repairs nothing.
    */
  def join(
      manifest: FixtureManifest,
      input: Checksum,
      tool: ToolRealization,
      args: Vector[String],
      output: FfprobeOutput
  ): Either[DomainError, MediaProbe] =
    for
      _ <- verifyInput(manifest, input)
      _ <- args
        .find(_.contains('\u0000'))
        .fold[Either[DomainError, Unit]](Right(()))(_ =>
          Left(DomainError.InvariantViolation("probe/args", "an argument contains a NUL byte"))
        )
      receipt <- SourceDerivationReceipt.of(Algorithm, parameters(tool, args), Vector(input))
      _ <- undeclaredStreams(manifest, output)
      _ <- undeclaredPackets(manifest, output)
      streams <- manifest.streams
        .foldLeft[Either[DomainError, Vector[ProbedStream]]](Right(Vector.empty)) {
          (acc, declared) =>
            acc.flatMap(v => joinStream(declared, output, receipt).map(v :+ _))
        }
    yield new MediaProbe(
      manifest,
      input,
      tool,
      args,
      streams,
      receipt,
      computeIdentity(receipt, streams)
    )

  private def verifyInput(manifest: FixtureManifest, input: Checksum): Either[DomainError, Unit] =
    if input == manifest.sha256 then Right(())
    else
      Left(
        DomainError.InvariantViolation(
          "probe/input",
          s"input ${input.short()} is not fixture ${manifest.fixtureId} (${manifest.sha256.short()})"
        )
      )

  private def undeclaredStreams(
      manifest: FixtureManifest,
      output: FfprobeOutput
  ): Either[DomainError, Unit] =
    output.streams.find(s => manifest.stream(s.index).isEmpty) match
      case Some(s) =>
        Left(
          DomainError.InvariantViolation(
            "probe/stream",
            s"stream ${s.index} (${s.codecType}/${s.codecName}) is not declared by ${manifest.fixtureId}; ignoring a stream is forbidden"
          )
        )
      case None => Right(())

  private def undeclaredPackets(
      manifest: FixtureManifest,
      output: FfprobeOutput
  ): Either[DomainError, Unit] =
    output.packets.find(p => manifest.stream(p.streamIndex).isEmpty) match
      case Some(p) =>
        Left(
          DomainError.InvariantViolation(
            "probe/packet",
            s"a packet on undeclared stream ${p.streamIndex} was reported"
          )
        )
      case None => Right(())

  private def joinStream(
      declared: DeclaredStream,
      output: FfprobeOutput,
      receipt: SourceDerivationReceipt
  ): Either[DomainError, ProbedStream] =
    for
      raw <- output.streams
        .find(_.index == declared.index)
        .toRight(
          DomainError.InvariantViolation(
            "probe/stream",
            s"declared stream ${declared.index} was not reported by the tool"
          )
        )
      _ <- checkKind(declared, raw)
      _ <- checkCodec(declared, raw)
      _ <- checkTimebase(declared, raw)
      _ <- checkPicture(declared, raw)
      _ <- checkAudio(declared, raw)
      packets <- output.packets
        .filter(_.streamIndex == declared.index)
        .zipWithIndex
        .foldLeft[Either[DomainError, Vector[ProbedPacket]]](Right(Vector.empty)) {
          case (acc, (p, k)) =>
            acc.flatMap(v => probedPacket(declared.index, k, p, receipt).map(v :+ _))
        }
    yield new ProbedStream(declared, raw.codecName, raw.startPts, packets)

  private def checkKind(declared: DeclaredStream, raw: RawStream): Either[DomainError, Unit] =
    val expected = declared.kind match
      case StreamKind.Picture  => Some("video")
      case StreamKind.Audio    => Some("audio")
      case StreamKind.Subtitle => Some("subtitle")
      case _                   => None
    expected match
      case Some(t) if t == raw.codecType => Right(())
      case _                             =>
        Left(
          DomainError.InvariantViolation(
            "probe/kind",
            s"stream ${declared.index}: tool reports codec_type ${raw.codecType}, manifest declares ${declared.kind}"
          )
        )

  private def checkCodec(declared: DeclaredStream, raw: RawStream): Either[DomainError, Unit] =
    if raw.codecName == declared.codec then Right(())
    else
      Left(
        DomainError.InvariantViolation(
          "probe/codec",
          s"stream ${declared.index}: tool reports ${raw.codecName}, manifest declares ${declared.codec}"
        )
      )

  private def checkTimebase(declared: DeclaredStream, raw: RawStream): Either[DomainError, Unit] =
    FixtureManifest.parseTimebase(raw.timeBase).flatMap { observed =>
      if observed == declared.timebase then Right(())
      else
        Left(
          DomainError.InvariantViolation(
            "probe/timebase",
            s"stream ${declared.index}: tool reports ${raw.timeBase}, manifest declares ${declared.timebase.scale.numerator}/${declared.timebase.scale.denominator}"
          )
        )
    }

  private def checkPicture(declared: DeclaredStream, raw: RawStream): Either[DomainError, Unit] =
    declared.picture match
      case None      => Right(())
      case Some(pic) =>
        val observed = (raw.pixFmt, raw.width, raw.height)
        val expected = (Some(pic.pixFmt), Some(pic.geometry.width), Some(pic.geometry.height))
        if observed == expected then Right(())
        else
          Left(
            DomainError.InvariantViolation(
              "probe/picture",
              s"stream ${declared.index}: tool reports ${raw.pixFmt.getOrElse("?")} ${raw.width.getOrElse("?")}x${raw.height.getOrElse("?")}, manifest declares ${pic.pixFmt} ${pic.geometry.width}x${pic.geometry.height}"
            )
          )

  private def checkAudio(declared: DeclaredStream, raw: RawStream): Either[DomainError, Unit] =
    declared.audio match
      case None    => Right(())
      case Some(a) =>
        if raw.sampleRate.contains(a.sampleRate) && raw.channels.contains(a.channels) then Right(())
        else
          Left(
            DomainError.InvariantViolation(
              "probe/audio",
              s"stream ${declared.index}: tool reports ${raw.sampleRate.getOrElse("?")} Hz x ${raw.channels.getOrElse("?")}, manifest declares ${a.sampleRate} Hz x ${a.channels}"
            )
          )

  private def field(raw: Option[Long]): TimestampField =
    raw.fold[TimestampField](TimestampField.Missing)(TimestampField.Present(_))

  /** A packet is typed before its draft record is minted: present PTS before present DTS is a typed
    * refusal that keeps the raw values and repairs none; a negative duration refuses; an absent or
    * zero duration is `Unknown`, never a measured empty interval.
    */
  private def probedPacket(
      streamIndex: Int,
      ordinal: Int,
      p: RawPacket,
      receipt: SourceDerivationReceipt
  ): Either[DomainError, ProbedPacket] =
    for
      fields <- PacketTimeFields
        .of(field(p.pts), field(p.dts))
        .left
        .map(e =>
          DomainError.InvariantViolation(
            "probe/packet-timestamps",
            s"stream $streamIndex packet $ordinal: ${e.message}"
          )
        )
      _ <- p.duration match
        case Some(d) if d < 0L =>
          Left(
            DomainError.InvariantViolation(
              "probe/packet-duration",
              s"stream $streamIndex packet $ordinal: negative duration $d is neither known-positive nor unknown"
            )
          )
        case _ => Right(())
      duration = MediaDuration.ofPacketTicks(p.duration.getOrElse(0L))
      record <- CallerRuntimePacketRecord.draft(
        p.pts.getOrElse(FixturePacketNormalizer.AvNoptsValue),
        p.dts.getOrElse(FixturePacketNormalizer.AvNoptsValue),
        p.duration.getOrElse(0L),
        receipt.identity
      )
    yield new ProbedPacket(fields, duration, p.duration, p.flags, record)

  private def computeIdentity(
      receipt: SourceDerivationReceipt,
      streams: Vector[ProbedStream]
  ): Checksum =
    val parts = Vector("media-probe", receipt.identity.hex) ++ streams.flatMap { s =>
      Vector("stream", s.index.toString, s.codecName, s.declared.disposition.toString) ++
        s.packets.flatMap { p =>
          Vector(
            p.record.identity.hex,
            p.fields.pts.toString,
            p.fields.dts.toString,
            p.durationReported.fold("absent")(_.toString),
            p.flags.raw
          )
        }
    }
    ContentAddress.digest(parts)
