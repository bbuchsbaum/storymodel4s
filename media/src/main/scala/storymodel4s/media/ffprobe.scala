package storymodel4s.media

import io.circe.{ACursor, Json}
import java.nio.file.Path
import storymodel4s.core.DomainError

/** One stream as ffprobe reported it. Optional fields are absent when the tool omitted them; this
  * module never substitutes a default for an omitted value.
  */
final case class RawStream(
    index: Int,
    codecType: String,
    codecName: String,
    timeBase: String,
    pixFmt: Option[String],
    width: Option[Int],
    height: Option[Int],
    sampleRate: Option[Int],
    channels: Option[Int],
    startPts: Option[Long]
)

/** ffprobe's three packet flags, typed. `raw` keeps the exact string for the identity preimage; the
  * booleans are derived from it by `parse` and cannot disagree with it.
  */
final class PacketFlags private (
    val keyframe: Boolean,
    val discard: Boolean,
    val corrupt: Boolean,
    val raw: String
):
  override def equals(other: Any): Boolean = other match
    case that: PacketFlags => raw == that.raw
    case _                 => false
  override def hashCode(): Int = raw.hashCode
  override def toString: String = s"PacketFlags($raw)"

object PacketFlags:
  /** ffprobe prints three positions: `K` or `_`, `D` or `_`, `C` or `_`. Anything else refuses. */
  def parse(raw: String): Either[DomainError, PacketFlags] =
    raw.toList match
      case List(k, d, c) if Set('K', '_')(k) && Set('D', '_')(d) && Set('C', '_')(c) =>
        Right(new PacketFlags(k == 'K', d == 'D', c == 'C', raw))
      case _ =>
        Left(DomainError.InvalidFormat("packets[].flags", raw, "expected three of K/_ D/_ C/_"))

/** One packet as ffprobe reported it. `pts`, `dts` and `duration` are `None` exactly when the JSON
  * omitted the field, which is how ffprobe renders `AV_NOPTS_VALUE`. A literal `INT64_MIN` in the
  * JSON is refused at parse: it is neither a present coordinate nor the typed absence, so it cannot
  * be allowed to become either downstream.
  */
final case class RawPacket(
    streamIndex: Int,
    pts: Option[Long],
    dts: Option[Long],
    duration: Option[Long],
    size: Long,
    flags: PacketFlags
)

final case class FfprobeOutput(streams: Vector[RawStream], packets: Vector[RawPacket])

/** Parser for `ffprobe -of json -show_streams -show_packets`. Numeric fields that ffprobe prints as
  * JSON numbers are read as 64-bit integers; the ones it prints as strings (`size`) are parsed.
  */
object FfprobeJson:
  private val Sentinel: Long = Long.MinValue

  def parse(stdout: String): Either[DomainError, FfprobeOutput] =
    for
      json <- MediaJson.parseDocument("ffprobe-json", stdout)
      c = json.hcursor
      streamsJson <- MediaJson.array(c.downField("streams"), "streams")
      streams <- MediaJson.traverse(streamsJson)(parseStream)
      packetsJson <- MediaJson.array(c.downField("packets"), "packets")
      packets <- MediaJson.traverse(packetsJson)(parsePacket)
    yield FfprobeOutput(streams, packets)

  private def parseStream(json: Json): Either[DomainError, RawStream] =
    val c = json.hcursor
    for
      index <- MediaJson.int(c.downField("index"), "streams[].index")
      codecType <- MediaJson.string(c.downField("codec_type"), "streams[].codec_type")
      codecName <- MediaJson.string(c.downField("codec_name"), "streams[].codec_name")
      timeBase <- MediaJson.string(c.downField("time_base"), "streams[].time_base")
      pixFmt <- optString(c.downField("pix_fmt"), "streams[].pix_fmt")
      width <- MediaJson.optionalInt(c.downField("width"), "streams[].width")
      height <- MediaJson.optionalInt(c.downField("height"), "streams[].height")
      sampleRate <- optIntString(c.downField("sample_rate"), "streams[].sample_rate")
      channels <- MediaJson.optionalInt(c.downField("channels"), "streams[].channels")
      startPts <- timestamp(c.downField("start_pts"), "streams[].start_pts")
    yield RawStream(
      index,
      codecType,
      codecName,
      timeBase,
      pixFmt,
      width,
      height,
      sampleRate,
      channels,
      startPts
    )

  private def parsePacket(json: Json): Either[DomainError, RawPacket] =
    val c = json.hcursor
    for
      streamIndex <- MediaJson.int(c.downField("stream_index"), "packets[].stream_index")
      pts <- timestamp(c.downField("pts"), "packets[].pts")
      dts <- timestamp(c.downField("dts"), "packets[].dts")
      duration <- MediaJson.optionalLong(c.downField("duration"), "packets[].duration")
      size <- MediaJson
        .string(c.downField("size"), "packets[].size")
        .flatMap(parseLong("packets[].size"))
      flags <- MediaJson.string(c.downField("flags"), "packets[].flags").flatMap(PacketFlags.parse)
    yield RawPacket(streamIndex, pts, dts, duration, size, flags)

  private def timestamp(c: ACursor, field: String): Either[DomainError, Option[Long]] =
    MediaJson.optionalLong(c, field).flatMap {
      case Some(v) if v == Sentinel =>
        Left(
          DomainError.InvalidFormat(
            field,
            v.toString,
            "a literal AV_NOPTS_VALUE is not a present coordinate; absence is the typed missing"
          )
        )
      case other => Right(other)
    }

  private def optString(c: ACursor, field: String): Either[DomainError, Option[String]] =
    if c.focus.isEmpty then Right(None) else MediaJson.string(c, field).map(Some(_))

  private def optIntString(c: ACursor, field: String): Either[DomainError, Option[Int]] =
    if c.focus.isEmpty then Right(None)
    else
      MediaJson.string(c, field).flatMap(parseLong(field)).flatMap { l =>
        if l >= 0L && l <= Int.MaxValue.toLong then Right(Some(l.toInt))
        else
          Left(DomainError.InvalidFormat(field, l.toString, "outside the admitted integer range"))
      }

  private def parseLong(field: String)(raw: String): Either[DomainError, Long] =
    raw.toLongOption.toRight(DomainError.InvalidFormat(field, raw, "expected an integer string"))

/** The ffprobe invocation this module admits. The argument vector is fixed so that a recorded
  * envelope and a live run are comparable; the input path is appended last and is never read from
  * the media. `-protocol_whitelist file` asserts, in the invocation itself, that no
  * media-controlled reference can widen the input set (ledger §4 input-security policy).
  */
object Ffprobe:
  val ToolName: String = "ffprobe"
  val EnvOverride: String = "STORYMODEL4S_FFPROBE"
  val CanonicalArgs: Vector[String] =
    Vector(
      "-v",
      "error",
      "-hide_banner",
      "-protocol_whitelist",
      "file",
      "-of",
      "json",
      "-show_streams",
      "-show_packets"
    )

  /** The ffprobe on this machine, if any. Never downloads, builds, or installs one. */
  def locate(): Option[Path] = Subprocess.locate(ToolName, EnvOverride)

  /** Run the canonical probe over `input` and return its stdout. A non-zero exit refuses with the
    * tool's stderr; partial output is never returned.
    */
  def invoke(tool: Path, input: Path): Either[DomainError, String] =
    Subprocess.run((tool.toString +: CanonicalArgs) :+ input.toString).flatMap { outcome =>
      if outcome.exitCode == 0 then Right(outcome.stdout)
      else
        Left(
          DomainError.InvariantViolation(
            "ffprobe/exit",
            s"ffprobe exited ${outcome.exitCode}: ${outcome.stderr.trim.take(400)}"
          )
        )
    }
