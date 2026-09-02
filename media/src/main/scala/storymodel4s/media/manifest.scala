package storymodel4s.media

import io.circe.{ACursor, Json}
import storymodel4s.core.{Checksum, DomainError, RationalTimebase, StreamKind}

/** The fixture ladder of the movie plan (§9). */
enum FixtureTier:
  case F0, F1, F2

/** What an admitted input manifest says may happen to a stream. Why: the ledger forbids silently
  * ignoring an unexpected stream, so every probed stream must match a declaration carrying one of
  * exactly these three dispositions. `Decoded` is the only disposition under which frames may be
  * extracted; `Unsupported` streams are probed and named but never indexed.
  */
enum StreamDisposition:
  case Decoded, PacketIndexedOnly, Unsupported

/** Generator declaration for a picture stream: how many frames were produced at which nominal
  * spacing, and which were dropped with PTS passthrough. From it the expected PTS table follows
  * without consulting any probe; the court compares the two.
  */
final class DeclaredFrames private (
    val generatedFrames: Int,
    val nominalTicksPerFrame: Long,
    val droppedFrames: Vector[Int]
):
  def expectedPts: Vector[Long] =
    val dropped = droppedFrames.toSet
    (0 until generatedFrames).iterator
      .filterNot(dropped.contains)
      .map(n => n.toLong * nominalTicksPerFrame)
      .toVector
  override def equals(other: Any): Boolean = other match
    case that: DeclaredFrames =>
      generatedFrames == that.generatedFrames && nominalTicksPerFrame == that.nominalTicksPerFrame &&
      droppedFrames == that.droppedFrames
    case _ => false
  override def hashCode(): Int = (generatedFrames, nominalTicksPerFrame, droppedFrames).hashCode()
  override def toString: String =
    s"DeclaredFrames($generatedFrames x $nominalTicksPerFrame, dropped ${droppedFrames.mkString(",")})"

object DeclaredFrames:
  def of(
      generatedFrames: Int,
      nominalTicksPerFrame: Long,
      droppedFrames: Vector[Int]
  ): Either[DomainError, DeclaredFrames] =
    if generatedFrames <= 0 || nominalTicksPerFrame <= 0L then
      Left(DomainError.InvariantViolation("fixture/declared", "frames and ticks must be positive"))
    else if droppedFrames.exists(n => n < 0 || n >= generatedFrames) then
      Left(
        DomainError.InvariantViolation("fixture/declared", "dropped frame outside generated range")
      )
    else if droppedFrames.distinct.size != droppedFrames.size then
      Left(DomainError.InvariantViolation("fixture/declared", "dropped frame listed twice"))
    else Right(new DeclaredFrames(generatedFrames, nominalTicksPerFrame, droppedFrames.sorted))

/** Declared picture parameters the tool must confirm. */
final case class DeclaredPicture(pixFmt: String, geometry: PictureGeometry)

/** Declared audio parameters the tool must confirm. */
final case class DeclaredAudio(sampleRate: Int, channels: Int)

/** One declared stream of an admitted fixture. A picture stream carries `picture`, an audio stream
  * carries `audio`; `declaredFrames` and `declaredSamples` are generator ground truth and are
  * present only for project-authored media.
  */
final case class DeclaredStream(
    index: Int,
    kind: StreamKind,
    codec: String,
    timebase: RationalTimebase,
    disposition: StreamDisposition,
    picture: Option[DeclaredPicture],
    audio: Option[DeclaredAudio],
    declaredFrames: Option[DeclaredFrames],
    declaredSamples: Option[Long]
)

/** Exact-byte manifest of an admitted media fixture: identity, byte length, container, and the
  * declared stream set. Bytes that do not hash to `sha256` are not this fixture.
  */
final class FixtureManifest private (
    val fixtureId: String,
    val tier: FixtureTier,
    val file: String,
    val sha256: Checksum,
    val byteLength: Long,
    val container: String,
    val streams: Vector[DeclaredStream]
):
  def stream(index: Int): Option[DeclaredStream] = streams.find(_.index == index)

  /** Verify candidate bytes against the manifest; the returned checksum is the verified identity.
    */
  def verify(bytes: Array[Byte]): Either[DomainError, Checksum] =
    val observed = Checksum.ofBytes(bytes)
    if bytes.length.toLong != byteLength then
      Left(
        DomainError.InvariantViolation(
          "fixture/bytes",
          s"$fixtureId: ${bytes.length} bytes, manifest declares $byteLength"
        )
      )
    else if observed != sha256 then
      Left(
        DomainError.InvariantViolation(
          "fixture/sha256",
          s"$fixtureId: bytes hash to ${observed.short()}, manifest declares ${sha256.short()}"
        )
      )
    else Right(observed)

  override def toString: String = s"FixtureManifest($fixtureId, ${sha256.short()})"

object FixtureManifest:
  val Schema: String = "storymodel4s.media.fixture-manifest"

  def parse(text: String): Either[DomainError, FixtureManifest] =
    for
      json <- MediaJson.parseDocument("fixture-manifest", text)
      c = json.hcursor
      _ <- MediaJson.expectSchema(c, Schema, 1)
      fixtureId <- MediaJson.string(c.downField("fixtureId"), "fixtureId")
      tier <- MediaJson.string(c.downField("tier"), "tier").flatMap(parseTier)
      file <- MediaJson.string(c.downField("file"), "file")
      sha <- MediaJson.string(c.downField("sha256"), "sha256").flatMap(Checksum.from)
      byteLength <- MediaJson.long(c.downField("byteLength"), "byteLength")
      container <- MediaJson.string(c.downField("container"), "container")
      streamJson <- MediaJson.array(c.downField("streams"), "streams")
      streams <- MediaJson.traverse(streamJson)(parseStream)
      _ <- distinctIndices(streams)
    yield new FixtureManifest(fixtureId, tier, file, sha, byteLength, container, streams)

  private def parseTier(raw: String): Either[DomainError, FixtureTier] = raw match
    case "F0"  => Right(FixtureTier.F0)
    case "F1"  => Right(FixtureTier.F1)
    case "F2"  => Right(FixtureTier.F2)
    case other => Left(DomainError.InvalidFormat("tier", other, "expected F0, F1, or F2"))

  private def distinctIndices(streams: Vector[DeclaredStream]): Either[DomainError, Unit] =
    val dup = streams.groupBy(_.index).collectFirst { case (i, s) if s.size > 1 => i }
    dup match
      case Some(i)                 => Left(DomainError.DuplicateId("stream index", i.toString))
      case None if streams.isEmpty =>
        Left(
          DomainError.InvariantViolation(
            "fixture/streams",
            "a manifest declares at least one stream"
          )
        )
      case None => Right(())

  private def parseStream(json: Json): Either[DomainError, DeclaredStream] =
    val c = json.hcursor
    for
      index <- MediaJson.int(c.downField("index"), "streams[].index")
      kind <- MediaJson.string(c.downField("kind"), "streams[].kind").flatMap(parseKind)
      codec <- MediaJson.string(c.downField("codec"), "streams[].codec")
      timebase <- MediaJson
        .string(c.downField("timebase"), "streams[].timebase")
        .flatMap(parseTimebase)
      disposition <- MediaJson
        .string(c.downField("disposition"), "streams[].disposition")
        .flatMap(parseDisposition)
      picture <- parsePicture(c, kind)
      audio <- parseAudio(c, kind)
      declared = c.downField("declared")
      frames <- parseFrames(declared)
      samples <- MediaJson.optionalLong(declared.downField("samples"), "declared.samples")
    yield DeclaredStream(
      index,
      kind,
      codec,
      timebase,
      disposition,
      picture,
      audio,
      frames,
      samples
    )

  private def parsePicture(
      c: ACursor,
      kind: StreamKind
  ): Either[DomainError, Option[DeclaredPicture]] =
    kind match
      case StreamKind.Picture =>
        for
          pixFmt <- MediaJson.string(c.downField("pixFmt"), "streams[].pixFmt")
          width <- MediaJson.int(c.downField("width"), "streams[].width")
          height <- MediaJson.int(c.downField("height"), "streams[].height")
          _ <-
            if width > 0 && height > 0 then Right(())
            else Left(DomainError.InvariantViolation("fixture/picture", "non-positive geometry"))
        yield Some(DeclaredPicture(pixFmt, PictureGeometry(width, height)))
      case _ => Right(None)

  private def parseAudio(c: ACursor, kind: StreamKind): Either[DomainError, Option[DeclaredAudio]] =
    kind match
      case StreamKind.Audio =>
        for
          rate <- MediaJson.int(c.downField("sampleRate"), "streams[].sampleRate")
          channels <- MediaJson.int(c.downField("channels"), "streams[].channels")
          _ <-
            if rate > 0 && channels > 0 then Right(())
            else
              Left(DomainError.InvariantViolation("fixture/audio", "non-positive audio parameters"))
        yield Some(DeclaredAudio(rate, channels))
      case _ => Right(None)

  private def parseFrames(declared: ACursor): Either[DomainError, Option[DeclaredFrames]] =
    if declared.downField("generatedFrames").focus.isEmpty then Right(None)
    else
      for
        generated <- MediaJson.int(
          declared.downField("generatedFrames"),
          "declared.generatedFrames"
        )
        ticks <- MediaJson.long(
          declared.downField("nominalTicksPerFrame"),
          "declared.nominalTicksPerFrame"
        )
        droppedJson <- MediaJson.array(
          declared.downField("droppedFrames"),
          "declared.droppedFrames"
        )
        dropped <- MediaJson.traverse(droppedJson)(j =>
          MediaJson.int(j.hcursor, "declared.droppedFrames[]")
        )
        frames <- DeclaredFrames.of(generated, ticks, dropped)
      yield Some(frames)

  private def parseKind(raw: String): Either[DomainError, StreamKind] = raw match
    case "Picture"  => Right(StreamKind.Picture)
    case "Audio"    => Right(StreamKind.Audio)
    case "Subtitle" => Right(StreamKind.Subtitle)
    case other      =>
      Left(
        DomainError.InvalidFormat("streams[].kind", other, "expected Picture, Audio, or Subtitle")
      )

  private def parseDisposition(raw: String): Either[DomainError, StreamDisposition] = raw match
    case "Decoded"           => Right(StreamDisposition.Decoded)
    case "PacketIndexedOnly" => Right(StreamDisposition.PacketIndexedOnly)
    case "Unsupported"       => Right(StreamDisposition.Unsupported)
    case other               =>
      Left(
        DomainError.InvalidFormat(
          "streams[].disposition",
          other,
          "expected Decoded, PacketIndexedOnly, or Unsupported"
        )
      )

  /** Parse an exact `num/den` timebase. Decimal seconds are refused: a timebase is a rational. */
  def parseTimebase(raw: String): Either[DomainError, RationalTimebase] =
    raw.split('/') match
      case Array(n, d) if n.forall(_.isDigit) && d.forall(_.isDigit) && n.nonEmpty && d.nonEmpty =>
        (n.toLongOption, d.toLongOption) match
          case (Some(num), Some(den)) => RationalTimebase.of(num, den)
          case _                      =>
            Left(
              DomainError.InvalidFormat("timebase", raw, "does not fit a signed 64-bit rational")
            )
      case _ =>
        Left(DomainError.InvalidFormat("timebase", raw, "expected <numerator>/<denominator>"))

/** Recorded invocation of an external tool over an admitted fixture: the tool that ran, the exact
  * arguments, and the digest of the stdout it produced. This is the "injected typed receipt" the
  * admission ledger lets ordinary CI replay instead of executing the tool. It is caller-writable on
  * purpose: a recorded envelope is Draft evidence about a past run, never an observation.
  */
final case class ProbeEnvelope(
    fixtureId: String,
    inputSha256: Checksum,
    tool: ToolRealization,
    args: Vector[String],
    stdoutFile: String,
    stdoutSha256: Checksum
):
  /** Verify recorded stdout bytes against the envelope before they are believed. */
  def verifyStdout(bytes: Array[Byte]): Either[DomainError, String] =
    val observed = Checksum.ofBytes(bytes)
    if observed != stdoutSha256 then
      Left(
        DomainError.InvariantViolation(
          "envelope/stdout",
          s"$fixtureId: recorded stdout hashes to ${observed.short()}, envelope declares ${stdoutSha256.short()}"
        )
      )
    else Right(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))

object ProbeEnvelope:
  val Schema: String = "storymodel4s.media.probe-envelope"

  def parse(text: String): Either[DomainError, ProbeEnvelope] =
    for
      json <- MediaJson.parseDocument("probe-envelope", text)
      c = json.hcursor
      _ <- MediaJson.expectSchema(c, Schema, 1)
      fixtureId <- MediaJson.string(c.downField("fixtureId"), "fixtureId")
      input <- MediaJson.string(c.downField("inputSha256"), "inputSha256").flatMap(Checksum.from)
      tool <- MediaJson.tool(c.downField("tool"))
      argsJson <- MediaJson.array(c.downField("args"), "args")
      args <- MediaJson.traverse(argsJson)(j => MediaJson.string(j.hcursor, "args[]"))
      stdoutFile <- MediaJson.string(c.downField("stdoutFile"), "stdoutFile")
      stdoutSha <- MediaJson
        .string(c.downField("stdoutSha256"), "stdoutSha256")
        .flatMap(Checksum.from)
    yield ProbeEnvelope(fixtureId, input, tool, args, stdoutFile, stdoutSha)

/** Small, explicit circe helpers. Every failure names the field so a bad manifest is diagnosable.
  */
private[media] object MediaJson:
  def parseDocument(kind: String, text: String): Either[DomainError, Json] =
    io.circe.parser.parse(text).left.map(e => DomainError.InvalidFormat(kind, "<json>", e.message))

  def expectSchema(c: ACursor, schema: String, version: Int): Either[DomainError, Unit] =
    for
      s <- string(c.downField("schema"), "schema")
      v <- int(c.downField("schemaVersion"), "schemaVersion")
      _ <-
        if s != schema then Left(DomainError.InvalidFormat("schema", s, s"expected $schema"))
        else if v != version then
          Left(DomainError.InvalidFormat("schemaVersion", v.toString, s"expected $version"))
        else Right(())
    yield ()

  /** A recorded tool realization: name, version line, and the digest of the executable. */
  def tool(t: ACursor): Either[DomainError, ToolRealization] =
    for
      name <- string(t.downField("name"), "tool.name")
      version <- string(t.downField("versionLine"), "tool.versionLine")
      binary <- string(t.downField("binarySha256"), "tool.binarySha256").flatMap(Checksum.from)
      tool <- ToolRealization.of(name, version, binary)
    yield tool

  private def absent(c: ACursor): String = c.focus.map(_.noSpaces).getOrElse("<absent>")

  def string(c: ACursor, field: String): Either[DomainError, String] =
    c.as[String].left.map(_ => DomainError.InvalidFormat(field, absent(c), "expected a string"))

  def int(c: ACursor, field: String): Either[DomainError, Int] =
    c.as[Int].left.map(_ => DomainError.InvalidFormat(field, absent(c), "expected an integer"))

  def long(c: ACursor, field: String): Either[DomainError, Long] =
    c.as[Long]
      .left
      .map(_ => DomainError.InvalidFormat(field, absent(c), "expected a 64-bit integer"))

  def optionalLong(c: ACursor, field: String): Either[DomainError, Option[Long]] =
    if c.focus.isEmpty then Right(None) else long(c, field).map(Some(_))

  /** Absent or JSON `null` both read as `None`; a present value must be an integer. */
  def optionalInt(c: ACursor, field: String): Either[DomainError, Option[Int]] =
    if c.focus.forall(_.isNull) then Right(None) else int(c, field).map(Some(_))

  /** A finite double. Why finite: a JSON literal outside the double range parses to an infinity,
    * and an infinity or NaN in a recipe would enter a checksum preimage and, for NaN, break the
    * type's own equality. Both are refused at the wire rather than carried.
    */
  def double(c: ACursor, field: String): Either[DomainError, Double] =
    c.as[Double]
      .left
      .map(_ => DomainError.InvalidFormat(field, absent(c), "expected a number"))
      .flatMap { d =>
        if d.isFinite then Right(canonicalDouble(d))
        else Left(DomainError.InvalidFormat(field, d.toString, "expected a finite number"))
      }

  /** `-0.0` and `0.0` are `==` but render differently, so a preimage built from either would give
    * two identities for one value. Every double that reaches an identity passes through here.
    */
  def canonicalDouble(d: Double): Double = if d == 0.0 then 0.0 else d

  def boolean(c: ACursor, field: String): Either[DomainError, Boolean] =
    c.as[Boolean].left.map(_ => DomainError.InvalidFormat(field, absent(c), "expected a boolean"))

  def stringMap(c: ACursor, field: String): Either[DomainError, Map[String, String]] =
    c.as[Map[String, String]]
      .left
      .map(_ => DomainError.InvalidFormat(field, absent(c), "expected an object of strings"))

  def array(c: ACursor, field: String): Either[DomainError, Vector[Json]] =
    c.as[Vector[Json]]
      .left
      .map(_ => DomainError.InvalidFormat(field, absent(c), "expected an array"))

  def traverse[A](items: Vector[Json])(
      f: Json => Either[DomainError, A]
  ): Either[DomainError, Vector[A]] =
    items.foldLeft[Either[DomainError, Vector[A]]](Right(Vector.empty)) { (acc, j) =>
      acc.flatMap(v => f(j).map(v :+ _))
    }
