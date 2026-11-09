package storymodel4s.pipeline.agentmapping

import io.circe.Json
import io.circe.jawn.JawnParser
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import scala.util.chaining.*
import storymodel4s.corpus.intake.SherlockAnnotations

/** Renders the whole-film evidence packet an agent mapper reads: every annotation row with its
  * time, description, place and cast, the transcribed dialogue attached to the nearest row, and a
  * per-scene visual summary.
  *
  * Why: an agent mapping must be replayable, so the packet is rendered from the checked annotation
  * atlas, the recognizer's transcript and the caption file, never from a hand-kept text. Row times
  * come from each row's media locus on its part's playback axis, not from the raw annotation clock;
  * the packet is a rendering for a model, it asserts nothing about the film, and it carries no
  * scores.
  */
object FilmOutline:
  private val parser = new JawnParser(maxValueSize = None, allowDuplicateKeys = false)
  private val SceneLabel = """(\d+)\.\s*(.*)""".r

  /** Whitespace as the recorded harness stripped it (Python `str.isspace`). Java's `trim` and
    * `strip` both keep U+00A0 and other separators that Python removes, which changes the bytes.
    */
  private[agentmapping] def isHarnessSpace(ch: Char): Boolean =
    (ch >= '\t' && ch <= '\r') || (ch >= '\u001c' && ch <= '\u001f') || ch == ' ' ||
      ch == '\u0085' || ch == '\u00a0' || ch == '\u1680' || (ch >= '\u2000' && ch <= '\u200a') ||
      ch == '\u2028' || ch == '\u2029' || ch == '\u202f' || ch == '\u205f' || ch == '\u3000'

  private[agentmapping] def harnessStrip(s: String): String =
    s.dropWhile(isHarnessSpace).reverse.dropWhile(isHarnessSpace).reverse

  /** One recognised speech segment on a part's playback axis, in seconds. */
  final case class Speech(startSeconds: Double, endSeconds: Double, text: String)

  /** Why rendering refused; each case names the input that failed. */
  enum Refusal:
    case InvalidTranscript(detail: String)
    case InvalidCaptions(detail: String)
    case SceneLabelMismatch(scene: Int)
    case MissingPart(partId: String)

  private def json(bytes: Array[Byte]): Option[Json] =
    parser.parse(new String(bytes, StandardCharsets.UTF_8)).toOption

  /** Speech segments of one recognizer transcript, dropping segments the recognizer marked suspect.
    * A segment without a finite start, end or text refuses the whole transcript.
    */
  def transcript(bytes: Array[Byte]): Either[Refusal, Vector[Speech]] =
    for
      doc <- json(bytes).toRight(Refusal.InvalidTranscript("not JSON"))
      segs <- doc.hcursor
        .get[Vector[Json]]("segments")
        .left
        .map(_ => Refusal.InvalidTranscript("no segments"))
      parsed <- segs.zipWithIndex.foldLeft[Either[Refusal, Vector[Speech]]](Right(Vector.empty)) {
        case (acc, (s, i)) =>
          acc.flatMap { done =>
            val c = s.hcursor
            val one = for
              start <- c.get[Double]("start_seconds").toOption.filter(_.isFinite)
              end <- c.get[Double]("end_seconds").toOption.filter(_.isFinite)
              text <- c.get[String]("text").toOption
              suspect <- c.get[Boolean]("suspect").toOption
            yield Option.when(!suspect)(Speech(start, end, harnessStrip(text)))
            one.toRight(Refusal.InvalidTranscript(s"segment $i")).map(done ++ _)
          }
      }
    yield parsed

  /** Per-scene visual summaries from a `{"captions": {"<scene>": "<text>"}}` file. */
  def captions(bytes: Array[Byte]): Either[Refusal, Map[Int, String]] =
    for
      doc <- json(bytes).toRight(Refusal.InvalidCaptions("not JSON"))
      obj <- doc.hcursor
        .downField("captions")
        .focus
        .flatMap(_.asObject)
        .toRight(Refusal.InvalidCaptions("no captions object"))
      pairs <- obj.toVector.foldLeft[Either[Refusal, Map[Int, String]]](Right(Map.empty)) {
        case (acc, (k, v)) =>
          acc.flatMap { m =>
            (k.toIntOption, v.asString) match
              case (Some(n), Some(s)) => Right(m.updated(n, s))
              case _                  => Left(Refusal.InvalidCaptions(s"entry $k"))
          }
      }
    yield pairs

  /** `m:ss` of a tick position, rounding half to even as the recorded harness did. */
  private[agentmapping] def clock(ticks: Long, ticksPerSecond: Long): String =
    val s = java.math.BigDecimal
      .valueOf(ticks)
      .divide(java.math.BigDecimal.valueOf(ticksPerSecond), 0, RoundingMode.HALF_EVEN)
      .longValueExact
    f"${s / 60}%d:${s % 60}%02d"

  private final case class Placed(
      row: SherlockAnnotations.Row,
      part: String,
      start: Long,
      end: Long,
      tps: Long
  ):
    def startSeconds: Double = start.toDouble / tps
    def endSeconds: Double = end.toDouble / tps

  /** Attach each speech segment to the row containing its midpoint, else the nearest row by
    * boundary distance; ties go to the earlier row.
    */
  private def attach(rows: Vector[Placed], speech: Vector[Speech]): Map[Int, Vector[String]] =
    speech.foldLeft(Map.empty[Int, Vector[String]]) { (m, s) =>
      val mid = (s.startSeconds + s.endSeconds) / 2
      def distance(r: Placed): Double =
        if r.startSeconds <= mid && mid <= r.endSeconds then 0.0
        else math.min(math.abs(mid - r.startSeconds), math.abs(mid - r.endSeconds))
      rows.headOption.fold(m) { first =>
        val best = rows.foldLeft(first)((b, r) => if distance(r) < distance(b) then r else b)
        m.updated(best.row.row, m.getOrElse(best.row.row, Vector.empty) :+ s.text)
      }
    }

  /** Render the packet. `speech` is keyed by part id; `captions` by scene ordinal. */
  def render(
      atlas: SherlockAnnotations.Atlas,
      speech: Map[String, Vector[Speech]],
      captions: Map[Int, String]
  ): Either[Refusal, String] =
    val parts = Vector(atlas.manifest.partA -> "a", atlas.manifest.partB -> "b")
    val placed = atlas.rows.map { r =>
      val locus = atlas.mediaByRow(r.row)
      val (identity, letter) = parts.find(_._1.partId == locus.part).get
      Placed(r, letter, locus.startTick, locus.endTick, identity.ticksPerSecond)
    }
    for
      said <- parts.foldLeft[Either[Refusal, Map[Int, Vector[String]]]](Right(Map.empty)) {
        case (acc, (identity, letter)) =>
          acc.flatMap { m =>
            speech
              .get(identity.partId)
              .toRight(Refusal.MissingPart(identity.partId))
              .map(s => m ++ attach(placed.filter(_.part == letter), s))
          }
      }
      headers <- atlas.scenes.foldLeft[Either[Refusal, Map[Int, String]]](Right(Map.empty)) {
        (acc, scene) =>
          acc.flatMap { m =>
            scene.label match
              case SceneLabel(n, title) if n.toIntOption.contains(scene.ordinal) =>
                val first = placed(scene.firstRow - 1)
                val last = placed(scene.lastRow - 1)
                val head =
                  s"\n=== SCENE ${scene.ordinal}: ${harnessStrip(title)} (part ${first.part}, " +
                    s"${clock(first.start, first.tps)}-${clock(last.end, last.tps)})"
                val withCaption =
                  captions.get(scene.ordinal).fold(head)(c => s"$head\n[visual summary] $c")
                Right(m.updated(scene.firstRow, withCaption))
              case _ => Left(Refusal.SceneLabelMismatch(scene.ordinal))
          }
      }
    yield placed
      .flatMap { p =>
        val r = p.row
        val names = harnessStrip(r.namesAllCell.getOrElse(""))
        val meta =
          Vector(
            harnessStrip(r.location.getOrElse("")),
            if names.nonEmpty then s"people: $names" else ""
          )
            .filter(_.nonEmpty)
            .mkString("; ")
        val line =
          s"r${r.row} ${clock(p.start, p.tps)} ${harnessStrip(r.description)}" +
            (if meta.nonEmpty then s"  ($meta)" else "")
        headers.get(r.row).toVector ++ Vector(line) ++
          said.get(r.row).toVector.map(s => "    dialogue: " + s.mkString(" "))
      }
      .mkString("\n")
      .pipe(harnessStrip)
