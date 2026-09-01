package storymodel4s.media

import io.circe.{ACursor, Json}
import storymodel4s.core.{Checksum, DomainError, RationalTimebase, StreamKind}

/** What an admitted input manifest says may happen to a stream. Why: the ledger forbids silently
  * ignoring an unexpected stream, so every probed stream must match a declaration carrying one of
  * exactly these three dispositions.
  */
enum StreamDisposition:
  case Decoded, PacketIndexedOnly, Unsupported

/** Generator declaration for a picture stream: how many frames were produced at which nominal
  * spacing, and which were dropped with PTS passthrough. From it the expected PTS table follows
  * without consulting any probe; the court compares the two.
  */
final case class DeclaredFrames(
    generatedFrames: Int,
    nominalTicksPerFrame: Long,
    droppedFrames: Vector[Int]
):
  def expectedPts: Vector[Long] =
    val dropped = droppedFrames.toSet
    (0 until generatedFrames).iterator
      .filterNot(dropped.contains)
      .map(n => n.toLong * nominalTicksPerFrame)
      .toVector

/** One declared stream of an admitted fixture. `declaredFrames` and `declaredSamples` are generator
  * ground truth and are present only for project-authored media.
  */
final case class DeclaredStream(
    index: Int,
    kind: StreamKind,
    codec: String,
    timebase: RationalTimebase,
    disposition: StreamDisposition,
    declaredFrames: Option[DeclaredFrames],
    declaredSamples: Option[Long]
)

/** Exact-byte manifest of an admitted media fixture: identity, byte length, container, and the
  * declared stream set. Bytes that do not hash to `sha256` are not this fixture.
  */
final class FixtureManifest private (
    val fixtureId: String,
    val tier: String,
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
      tier <- MediaJson.string(c.downField("tier"), "tier")
      file <- MediaJson.string(c.downField("file"), "file")
      sha <- MediaJson.string(c.downField("sha256"), "sha256").flatMap(Checksum.from)
      byteLength <- MediaJson.long(c.downField("byteLength"), "byteLength")
      container <- MediaJson.string(c.downField("container"), "container")
      streamJson <- MediaJson.array(c.downField("streams"), "streams")
      streams <- MediaJson.traverse(streamJson)(parseStream)
      _ <- distinctIndices(streams)
    yield new FixtureManifest(fixtureId, tier, file, sha, byteLength, container, streams)

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
      declared = c.downField("declared")
      frames <- parseFrames(declared)
      samples <- MediaJson.optionalLong(declared.downField("samples"), "declared.samples")
    yield DeclaredStream(index, kind, codec, timebase, disposition, frames, samples)

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
        _ <-
          if generated <= 0 || ticks <= 0L then
            Left(
              DomainError
                .InvariantViolation("fixture/declared", "frames and ticks must be positive")
            )
          else if dropped.exists(n => n < 0 || n >= generated) then
            Left(
              DomainError
                .InvariantViolation("fixture/declared", "dropped frame outside generated range")
            )
          else Right(())
      yield Some(DeclaredFrames(generated, ticks, dropped))

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
  * admission ledger lets ordinary CI replay instead of executing the tool.
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
      t = c.downField("tool")
      name <- MediaJson.string(t.downField("name"), "tool.name")
      version <- MediaJson.string(t.downField("versionLine"), "tool.versionLine")
      binary <- MediaJson
        .string(t.downField("binarySha256"), "tool.binarySha256")
        .flatMap(Checksum.from)
      tool <- ToolRealization.of(name, version, binary)
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

  def string(c: ACursor, field: String): Either[DomainError, String] =
    c.as[String]
      .left
      .map(_ =>
        DomainError
          .InvalidFormat(field, c.focus.map(_.noSpaces).getOrElse("<absent>"), "expected a string")
      )

  def int(c: ACursor, field: String): Either[DomainError, Int] =
    c.as[Int]
      .left
      .map(_ =>
        DomainError.InvalidFormat(
          field,
          c.focus.map(_.noSpaces).getOrElse("<absent>"),
          "expected an integer"
        )
      )

  def long(c: ACursor, field: String): Either[DomainError, Long] =
    c.as[Long]
      .left
      .map(_ =>
        DomainError.InvalidFormat(
          field,
          c.focus.map(_.noSpaces).getOrElse("<absent>"),
          "expected a 64-bit integer"
        )
      )

  def optionalLong(c: ACursor, field: String): Either[DomainError, Option[Long]] =
    if c.focus.isEmpty then Right(None) else long(c, field).map(Some(_))

  def array(c: ACursor, field: String): Either[DomainError, Vector[Json]] =
    c.as[Vector[Json]]
      .left
      .map(_ =>
        DomainError
          .InvalidFormat(field, c.focus.map(_.noSpaces).getOrElse("<absent>"), "expected an array")
      )

  def traverse[A](
      items: Vector[Json]
  )(f: Json => Either[DomainError, A]): Either[DomainError, Vector[A]] =
    items.foldLeft[Either[DomainError, Vector[A]]](Right(Vector.empty)) { (acc, j) =>
      acc.flatMap(v => f(j).map(v :+ _))
    }
