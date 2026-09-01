package storymodel4s.media

import java.nio.file.Path
import storymodel4s.core.{
  Checksum,
  ContentAddress,
  DomainError,
  SourceDerivationReceipt,
  StreamKind
}

/** The one frame-extraction invocation this module admits: decode one picture stream to raw BGR24
  * frames with PTS passthrough (no frame duplication or dropping), no scaling, no cropping. The
  * preprocessing identity is exactly these arguments; nothing is left to a library default.
  */
object Ffmpeg:
  val ToolName: String = "ffmpeg"
  val EnvOverride: String = "STORYMODEL4S_FFMPEG"
  val PixelFormat: String = "bgr24"
  val BytesPerPixel: Int = 3

  /** Arguments between the tool and the input path; the input and output paths are appended by
    * `extract` and never read from the media.
    */
  def canonicalArgs(streamIndex: Int): Vector[String] =
    Vector(
      "-hide_banner",
      "-loglevel",
      "error",
      "-y",
      "-map",
      s"0:$streamIndex",
      "-f",
      "rawvideo",
      "-pix_fmt",
      PixelFormat,
      "-fps_mode",
      "passthrough"
    )

  /** The ffmpeg on this machine, if any. Never downloads, builds, or installs one. */
  def locate(): Option[Path] = Subprocess.locate(ToolName, EnvOverride)

  /** Decode `streamIndex` of `input` into `out`. A non-zero exit refuses with the tool's stderr. */
  def extract(tool: Path, input: Path, streamIndex: Int, out: Path): Either[DomainError, Unit] =
    val args = canonicalArgs(streamIndex)
    val command =
      (Vector(tool.toString, args(0), args(1), args(2), args(3), "-i", input.toString) ++
        args.drop(4)) :+ out.toString
    Subprocess.run(command).flatMap { outcome =>
      if outcome.exitCode == 0 then Right(())
      else
        Left(
          DomainError.InvariantViolation(
            "ffmpeg/exit",
            s"ffmpeg exited ${outcome.exitCode}: ${outcome.stderr.trim.take(400)}"
          )
        )
    }

/** Picture geometry the extraction was declared against. */
final case class PictureGeometry(width: Int, height: Int):
  def frameBytes: Long = width.toLong * height.toLong * Ffmpeg.BytesPerPixel.toLong

/** Recorded frame extraction over an admitted fixture: the tool that ran, the exact arguments, the
  * stream, the geometry, and the digest of the decoded bytes. Ordinary CI replays this instead of
  * decoding; the live court decodes and compares the digest.
  */
final case class FramesEnvelope(
    fixtureId: String,
    inputSha256: Checksum,
    streamIndex: Int,
    tool: ToolRealization,
    args: Vector[String],
    geometry: PictureGeometry,
    count: Int,
    byteLength: Long,
    framesSha256: Checksum
)

object FramesEnvelope:
  val Schema: String = "storymodel4s.media.frames-envelope"

  def parse(text: String): Either[DomainError, FramesEnvelope] =
    for
      json <- MediaJson.parseDocument("frames-envelope", text)
      c = json.hcursor
      _ <- MediaJson.expectSchema(c, Schema, 1)
      fixtureId <- MediaJson.string(c.downField("fixtureId"), "fixtureId")
      input <- MediaJson.string(c.downField("inputSha256"), "inputSha256").flatMap(Checksum.from)
      streamIndex <- MediaJson.int(c.downField("streamIndex"), "streamIndex")
      t = c.downField("tool")
      name <- MediaJson.string(t.downField("name"), "tool.name")
      version <- MediaJson.string(t.downField("versionLine"), "tool.versionLine")
      binary <- MediaJson
        .string(t.downField("binarySha256"), "tool.binarySha256")
        .flatMap(Checksum.from)
      tool <- ToolRealization.of(name, version, binary)
      argsJson <- MediaJson.array(c.downField("args"), "args")
      args <- MediaJson.traverse(argsJson)(j => MediaJson.string(j.hcursor, "args[]"))
      f = c.downField("frames")
      count <- MediaJson.int(f.downField("count"), "frames.count")
      width <- MediaJson.int(f.downField("width"), "frames.width")
      height <- MediaJson.int(f.downField("height"), "frames.height")
      pix <- MediaJson.string(f.downField("pixelFormat"), "frames.pixelFormat")
      _ <-
        if pix == Ffmpeg.PixelFormat then Right(())
        else
          Left(
            DomainError.InvalidFormat("frames.pixelFormat", pix, s"expected ${Ffmpeg.PixelFormat}")
          )
      byteLength <- MediaJson.long(f.downField("byteLength"), "frames.byteLength")
      sha <- MediaJson.string(f.downField("sha256"), "frames.sha256").flatMap(Checksum.from)
    yield FramesEnvelope(
      fixtureId,
      input,
      streamIndex,
      tool,
      args,
      PictureGeometry(width, height),
      count,
      byteLength,
      sha
    )

/** Decoded frames bound to the packet index that names each frame's PTS. Why: a detector sees frame
  * ordinals only; the ordinal-to-PTS table here is the sole lawful route back to time, and it comes
  * from the probe, never from a nominal rate.
  */
final class FrameSet private (
    val probe: MediaProbe,
    val streamIndex: Int,
    val index: PacketIndex,
    val geometry: PictureGeometry,
    val framesSha256: Checksum,
    val tool: ToolRealization,
    val args: Vector[String],
    val receipt: SourceDerivationReceipt,
    val identity: Checksum
):
  def count: Int = index.entries.size
  def ptsOf(ordinal: Int): Option[Long] = index.entries.lift(ordinal).map(_.pts)
  override def toString: String =
    s"FrameSet(${probe.manifest.fixtureId}, $count frames, ${identity.short()})"

object FrameSet:
  val Algorithm: String = "ffmpeg-bgr24-frames/v1"

  def parameters(tool: ToolRealization, args: Vector[String], geometry: PictureGeometry): String =
    (Vector(
      "tool",
      tool.identity.hex,
      "geometry",
      s"${geometry.width}x${geometry.height}",
      "args"
    ) ++ args)
      .mkString(" ")

  /** Join decoded bytes to the probe. Refuses when the stream is not a picture stream, when the
    * packet index cannot be built, or when the byte length is not exactly one frame per indexed
    * packet: a decoder that emitted more or fewer frames than the container has packets is not
    * describing these packets.
    */
  def join(
      probe: MediaProbe,
      streamIndex: Int,
      geometry: PictureGeometry,
      tool: ToolRealization,
      args: Vector[String],
      byteLength: Long,
      framesSha256: Checksum
  ): Either[DomainError, FrameSet] =
    for
      stream <- probe
        .stream(streamIndex)
        .toRight(
          DomainError.InvariantViolation("frames/stream", s"probe has no stream $streamIndex")
        )
      _ <-
        if stream.declared.kind == StreamKind.Picture then Right(())
        else
          Left(
            DomainError.InvariantViolation(
              "frames/stream",
              s"stream $streamIndex is ${stream.declared.kind}, not a picture stream"
            )
          )
      _ <-
        if geometry.width > 0 && geometry.height > 0 then Right(())
        else Left(DomainError.InvariantViolation("frames/geometry", "non-positive geometry"))
      index <- PacketIndex.of(stream)
      expected = geometry.frameBytes * index.entries.size.toLong
      _ <-
        if byteLength == expected then Right(())
        else
          Left(
            DomainError.InvariantViolation(
              "frames/bytes",
              s"$byteLength decoded bytes; ${index.entries.size} indexed packets of ${geometry.frameBytes} bytes require $expected"
            )
          )
      receipt <- SourceDerivationReceipt.of(
        Algorithm,
        parameters(tool, args, geometry),
        Vector(probe.input, probe.identity)
      )
    yield new FrameSet(
      probe,
      streamIndex,
      index,
      geometry,
      framesSha256,
      tool,
      args,
      receipt,
      ContentAddress.digest(
        Vector("frame-set", receipt.identity.hex, framesSha256.hex, index.entries.size.toString)
      )
    )
