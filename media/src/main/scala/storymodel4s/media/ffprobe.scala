package storymodel4s.media

import io.circe.Json
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
    channels: Option[Int]
)

/** One packet as ffprobe reported it. `pts`, `dts` and `duration` are `None` exactly when the JSON
  * omitted the field, which is how ffprobe renders `AV_NOPTS_VALUE`. Nothing here decides what a
  * missing field means; that is the join's job, and it is typed there.
  */
final case class RawPacket(
    streamIndex: Int,
    pts: Option[Long],
    dts: Option[Long],
    duration: Option[Long],
    size: Long,
    flags: String
)

final case class FfprobeOutput(streams: Vector[RawStream], packets: Vector[RawPacket])

/** Parser for `ffprobe -of json -show_streams -show_packets`. Numeric fields that ffprobe prints as
  * JSON numbers are read as 64-bit integers; the ones it prints as strings (`size`) are parsed.
  */
object FfprobeJson:
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
      width <- optInt(c.downField("width"), "streams[].width")
      height <- optInt(c.downField("height"), "streams[].height")
      sampleRate <- optIntString(c.downField("sample_rate"), "streams[].sample_rate")
      channels <- optInt(c.downField("channels"), "streams[].channels")
    yield RawStream(
      index,
      codecType,
      codecName,
      timeBase,
      pixFmt,
      width,
      height,
      sampleRate,
      channels
    )

  private def parsePacket(json: Json): Either[DomainError, RawPacket] =
    val c = json.hcursor
    for
      streamIndex <- MediaJson.int(c.downField("stream_index"), "packets[].stream_index")
      pts <- MediaJson.optionalLong(c.downField("pts"), "packets[].pts")
      dts <- MediaJson.optionalLong(c.downField("dts"), "packets[].dts")
      duration <- MediaJson.optionalLong(c.downField("duration"), "packets[].duration")
      size <- MediaJson
        .string(c.downField("size"), "packets[].size")
        .flatMap(parseLong("packets[].size"))
      flags <- MediaJson.string(c.downField("flags"), "packets[].flags")
    yield RawPacket(streamIndex, pts, dts, duration, size, flags)

  private def optString(c: io.circe.ACursor, field: String): Either[DomainError, Option[String]] =
    if c.focus.isEmpty then Right(None) else MediaJson.string(c, field).map(Some(_))

  private def optInt(c: io.circe.ACursor, field: String): Either[DomainError, Option[Int]] =
    if c.focus.isEmpty then Right(None) else MediaJson.int(c, field).map(Some(_))

  private def optIntString(c: io.circe.ACursor, field: String): Either[DomainError, Option[Int]] =
    if c.focus.isEmpty then Right(None)
    else MediaJson.string(c, field).flatMap(parseLong(field)).map(l => Some(l.toInt))

  private def parseLong(field: String)(raw: String): Either[DomainError, Long] =
    raw.toLongOption.toRight(DomainError.InvalidFormat(field, raw, "expected an integer string"))

/** The ffprobe invocation this module admits. The argument vector is fixed so that a recorded
  * envelope and a live run are comparable; the input path is appended last and is never read from
  * the media.
  */
object Ffprobe:
  val ToolName: String = "ffprobe"
  val EnvOverride: String = "STORYMODEL4S_FFPROBE"
  val CanonicalArgs: Vector[String] =
    Vector("-v", "error", "-hide_banner", "-of", "json", "-show_streams", "-show_packets")

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
