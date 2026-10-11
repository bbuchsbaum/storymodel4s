package storymodel4s.media

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import storymodel4s.core.{Checksum, ContentAddress, DomainError, RationalTimebase}

/** Executed selection with observed decoder timestamps, so a replay join cannot claim execution. */
final class SampledExtraction private (
    val frames: SampledFrameSet,
    val observedPts: Vector[Long],
    val transcript: String,
    val identity: Checksum
):
  /** Replay envelope for the S2 loader; execution correspondence is recorded separately. */
  def framesEnvelopeJson: String =
    import io.circe.Json
    Json
      .obj(
        "schema" -> Json.fromString(FramesEnvelope.Schema),
        "schemaVersion" -> Json.fromInt(1),
        "fixtureId" -> Json.fromString(frames.probe.manifest.fixtureId),
        "inputSha256" -> Json.fromString(frames.probe.input.hex),
        "streamIndex" -> Json.fromInt(frames.streamIndex),
        "tool" -> Json.obj(
          "name" -> Json.fromString(frames.tool.name),
          "versionLine" -> Json.fromString(frames.tool.versionLine),
          "binarySha256" -> Json.fromString(frames.tool.binarySha256.hex)
        ),
        "args" -> Json.fromValues(frames.args.map(Json.fromString)),
        "frames" -> Json.obj(
          "count" -> Json.fromInt(frames.count),
          "width" -> Json.fromInt(frames.geometry.width),
          "height" -> Json.fromInt(frames.geometry.height),
          "pixelFormat" -> Json.fromString(Ffmpeg.PixelFormat),
          "byteLength" -> Json.fromLong(frames.geometry.frameBytes * frames.count.toLong),
          "sha256" -> Json.fromString(frames.framesSha256.hex)
        )
      )
      .noSpaces

  /** Selection and observed correspondence needed alongside raw frames and the replay envelope. */
  def executionJson: String =
    import io.circe.Json
    Json
      .obj(
        "schema" -> Json.fromString("storymodel4s.media.sampled-execution"),
        "schemaVersion" -> Json.fromInt(1),
        "identity" -> Json.fromString(identity.hex),
        "framesIdentity" -> Json.fromString(frames.identity.hex),
        "probeIdentity" -> Json.fromString(frames.probe.identity.hex),
        "selectedPresentationOrdinals" -> Json.fromValues(frames.selected.map(Json.fromInt)),
        "observedPts" -> Json.fromValues(observedPts.map(Json.fromLong)),
        "transcriptSha256" -> Json.fromString(Checksum.ofText(transcript).hex),
        "transcript" -> Json.fromString(transcript)
      )
      .noSpaces

  override def equals(other: Any): Boolean = other match
    case that: SampledExtraction => identity == that.identity
    case _                       => false
  override def hashCode(): Int = identity.hashCode()
  override def toString: String = s"SampledExtraction(${frames.count}, ${identity.short()})"

object SampledExtraction:
  private val inputOptions = Vector(
    "-hide_banner",
    "-loglevel",
    "info",
    "-nostdin",
    "-y",
    "-copyts",
    "-noautorotate",
    "-protocol_whitelist",
    "file",
    "-format_whitelist",
    "mov,matroska,avi,mpegts,mpeg,ogg"
  )
  private def outputOptions(streamIndex: Int, selected: Vector[Int]): Vector[String] =
    val ranges = selected.foldLeft(Vector.empty[(Int, Int)]) { (acc, n) =>
      acc.lastOption match
        case Some((start, end)) if end.toLong + 1L == n.toLong =>
          acc.updated(acc.size - 1, start -> n)
        case _ => acc :+ (n -> n)
    }
    val selection = ranges
      .map { (start, end) =>
        if start == end then s"eq(n\\,$start)" else s"between(n\\,$start\\,$end)"
      }
      .mkString("+")
    Vector(
      "-map",
      s"0:$streamIndex",
      "-vf",
      s"select=$selection,showinfo,${Ffmpeg.ColourConversion}",
      "-f",
      "rawvideo",
      "-pix_fmt",
      Ffmpeg.PixelFormat,
      "-fps_mode",
      "passthrough"
    )

  /** Exact extraction arguments excluding filesystem locations. Selection uses decoded ordinals. */
  def canonicalArgs(streamIndex: Int, selected: Vector[Int]): Vector[String] =
    inputOptions ++ outputOptions(streamIndex, selected)

  /** Stream a local input through ffmpeg, check each selected PTS against the admitted packet
    * index, and publish the raw bytes only after verification. Existing destinations are refused.
    * The timeout is explicit and no tool or model is downloaded. Evidence remains Draft.
    */
  def run(
      tool: Path,
      input: Path,
      output: Path,
      probe: MediaProbe,
      streamIndex: Int,
      geometry: PictureGeometry,
      selected: Vector[Int],
      timeout: FiniteDuration = 120.seconds
  ): Either[DomainError, SampledExtraction] =
    attempt {
      for
        _ <- requireThat(timeout > Duration.Zero, "timeout", "timeout must be positive")
        _ <- requireThat(!Files.exists(output), "destination", "destination already exists")
        inputHash <- hash(input)
        _ <- requireThat(
          inputHash._1 == probe.input && inputHash._2 == probe.manifest.byteLength,
          "input",
          "input bytes differ from the admitted probe"
        )
        realization <- ToolRealization.observe(Ffmpeg.ToolName, tool)
        args = canonicalArgs(streamIndex, selected)
        // The preflight join refuses invalid selection and geometry before starting ffmpeg.
        preliminary <- SampledFrameSet.join(
          probe,
          streamIndex,
          geometry,
          realization,
          args,
          selected,
          geometry.frameBytes * selected.size.toLong,
          Checksum.ofText("preflight")
        )
        result <- execute(tool, input, output, preliminary, timeout)
      yield result
    }.flatten

  private def execute(
      tool: Path,
      input: Path,
      output: Path,
      preliminary: SampledFrameSet,
      timeout: FiniteDuration
  ): Either[DomainError, SampledExtraction] =
    val temporary = Files.createTempFile(output.toAbsolutePath.getParent, ".sampled-", ".bgr")
    try
      for
        outcome <- Subprocess.run(
          (tool.toString +: inputOptions) ++ Vector("-i", input.toString) ++
            outputOptions(preliminary.streamIndex, preliminary.selected) :+ temporary.toString,
          timeout
        )
        _ <- requireThat(outcome.exitCode == 0, "exit", s"ffmpeg exited ${outcome.exitCode}")
        pts <- correspondence(outcome.stderr, preliminary)
        bytes <- hash(temporary)
        after <- hash(input)
        _ <- requireThat(
          after._1 == preliminary.probe.input &&
            after._2 == preliminary.probe.manifest.byteLength,
          "input",
          "input changed during extraction"
        )
        frames <- SampledFrameSet.join(
          preliminary.probe,
          preliminary.streamIndex,
          preliminary.geometry,
          preliminary.tool,
          preliminary.args,
          preliminary.selected,
          bytes._2,
          bytes._1
        )
        _ <- attempt { val _ = Files.move(temporary, output); () }
      yield new SampledExtraction(
        frames,
        pts,
        outcome.stderr,
        ContentAddress.digest(
          Vector(
            "executed-sampled-frames/v1",
            frames.identity.hex,
            Checksum.ofText(outcome.stderr).hex
          )
        )
      )
    finally
      val _ = Files.deleteIfExists(temporary)

  private def correspondence(
      report: String,
      frames: SampledFrameSet
  ): Either[DomainError, Vector[Long]] =
    val clocks = "(?m)\\[Parsed_showinfo_[^\\]]+\\] config in time_base: ([0-9]+)/([0-9]+),".r
      .findAllMatchIn(report)
      .toVector
    val samples =
      "(?m)\\[Parsed_showinfo_[^\\]]+\\]\\s+n:\\s*([0-9]+)\\s+pts:\\s*(-?[0-9]+)\\s+pts_time:[^\\r\\n]*?\\ss:([0-9]+)x([0-9]+)\\s".r
        .findAllMatchIn(report)
        .toVector
    for
      _ <- requireThat(clocks.size == 1, "clock", "missing or ambiguous decoder timebase")
      clock <- RationalTimebase.of(clocks.head.group(1).toLong, clocks.head.group(2).toLong)
      _ <- requireThat(clock == frames.index.timebase, "clock", "decoder and probe clocks differ")
      ordinals = samples.map(_.group(1).toInt)
      pts = samples.map(_.group(2).toLong)
      shapes = samples.map(m => PictureGeometry(m.group(3).toInt, m.group(4).toInt))
      _ <- requireThat(
        shapes.forall(_ == frames.geometry),
        "geometry",
        "decoded frame dimensions differ"
      )
      expected = frames.selected.map(frames.index.entries(_).pts)
      _ <- requireThat(
        ordinals == frames.selected.indices.toVector && pts == expected,
        "correspondence",
        "decoder output timestamps differ from selected packet timestamps"
      )
    yield pts

  private def hash(path: Path): Either[DomainError, (Checksum, Long)] =
    attempt {
      val digest = MessageDigest.getInstance("SHA-256")
      val in = Files.newInputStream(path)
      var size = 0L
      try
        val buffer = new Array[Byte](65536)
        var n = in.read(buffer)
        while n >= 0 do
          digest.update(buffer, 0, n)
          size = Math.addExact(size, n.toLong)
          n = in.read(buffer)
        (java.util.HexFormat.of().formatHex(digest.digest()), size)
      finally in.close()
    }.flatMap { (hex, size) => Checksum.from(hex).map(_ -> size) }

  private def requireThat(ok: Boolean, code: String, detail: String): Either[DomainError, Unit] =
    if ok then Right(())
    else Left(DomainError.InvariantViolation(s"sampled-extraction/$code", detail))
  private def attempt[A](body: => A): Either[DomainError, A] =
    try Right(body)
    catch
      case NonFatal(e) =>
        Left(DomainError.InvariantViolation("sampled-extraction/io", e.getClass.getSimpleName))
