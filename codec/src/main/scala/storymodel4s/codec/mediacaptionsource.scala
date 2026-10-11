package storymodel4s.codec

import cats.syntax.all.*
import io.circe.{Decoder, Json}
import storymodel4s.align.CaptionEvidence
import storymodel4s.align.bridge.{MediaLocus, TimedSegment, TimedSourceView}
import storymodel4s.core.*

/** Portable imported caption declarations retain native clocks without claiming JVM media replay.
  */
object MediaCaptionSourceCodec:
  val Schema: String = "prepared-media-caption-source/v1"
  private def render(j: Json): String = Canonical.printer.copy(dropNullValues = false).print(j)

  /** A checked internal join preserves Draft origin and separates sample coverage from hulls. */
  final class Imported private[MediaCaptionSourceCodec] (
      val view: TimedSourceView,
      val document: Json,
      val evidence: Vector[CaptionEvidence],
      private val sampled: Map[String, Vector[Int]],
      private val examined: Map[String, Vector[Int]],
      private val presented: Map[String, Int]
  ):
    val authority: ObservationAuthority = ObservationAuthority.Draft
    def artifact: String = render(document)
    def sampledOrdinals(part: String): Option[Vector[Int]] = sampled.get(part)
    def examinedOrdinals(part: String): Option[Vector[Int]] = examined.get(part)
    def presentedCount(part: String): Option[Int] = presented.get(part)
    def unexaminedOrdinalRanges(part: String): Option[Vector[Range]] = for
      ordinals <- examined.get(part)
      count <- presented.get(part)
    yield (Vector(-1) ++ ordinals :+ count)
      .sliding(2)
      .collect {
        case pair if pair(1).toLong - pair(0).toLong > 1L => (pair(0) + 1) until pair(1)
      }
      .toVector
    override def equals(other: Any): Boolean = other match
      case that: Imported => document == that.document
      case _              => false
    override def hashCode(): Int = document.hashCode()
    override def toString: String = s"ImportedDraftMediaCaptions(${view.artifact.short()})"

  private final case class Sample(ordinal: Int, pts: Long, duration: Option[Long])
  private final case class Part(
      occurrence: String,
      bundle: SourceBundle,
      count: Int,
      samples: Vector[Sample],
      captions: Vector[(String, CaptionEvidence)],
      examined: Vector[Int]
  )
  private def field[A: Decoder](j: Json, key: String): Either[String, A] =
    j.hcursor.get[A](key).left.map(_ => s"invalid media-caption field $key")
  private def check(ok: Boolean, detail: String): Either[String, Unit] = Either.cond(ok, (), detail)
  private def keys(j: Json, names: String*): Either[String, Unit] =
    check(j.asObject.exists(_.keys.toSet == names.toSet), "unknown or missing media-caption fields")
  private def hash(j: Json, key: String): Either[String, Checksum] =
    field[String](j, key).flatMap(Checksum.from(_).left.map(_.message))
  private def long(j: Json, key: String): Either[String, Long] =
    field[String](j, key).flatMap(_.toLongOption.toRight(s"invalid integer $key"))

  /** Replays declarations only. Full file/model/worker verification stays in CaptionPackage.load.
    */
  def decode(text: String): Either[String, Imported] =
    for
      _ <- MappingJson.uniqueObjectKeys(text).left.map(_.toString)
      json <- Canonical.parse(text).left.map(_.toString)
      _ <- keys(json, "schemaVersion", "packageManifest", "packageIdentity", "edition", "parts")
      schema <- field[String](json, "schemaVersion")
      _ <- check(schema == Schema, "unsupported media-caption source")
      _ <- hash(json, "packageManifest")
      _ <- hash(json, "packageIdentity")
      _ <- field[String](json, "edition").flatMap(EditionId.from(_).left.map(_.message))
      rows <- field[Vector[Json]](json, "parts")
      parts <- rows.traverse(part)
      _ <- check(
        parts.nonEmpty && parts.map(_.occurrence).distinct.size == parts.size,
        "empty or duplicate part occurrence"
      )
      _ <- check(
        parts.map(_.bundle.identity).distinct.size == parts.size,
        "repeated local media requires an explicit composition"
      )
      captions = parts.flatMap(p => p.captions.map(c => (p.occurrence, c._1, c._2)))
      groupIds = captions.map(_._2).distinct.zipWithIndex.toMap
      segments = captions.zipWithIndex.map { case ((occurrence, id, evidence), n) =>
        val caption = evidence.captions.head
        TimedSegment(
          n + 1,
          caption.text,
          Some(MediaLocus.Extent(occurrence, caption.support)),
          Some(TimedSegment.Group(groupIds(id) + 1, id))
        )
      }
      view <- TimedSourceView
        .of(segments, parts.map(p => p.occurrence -> p.bundle).toMap, Checksum.ofText(render(json)))
        .left
        .map(_.toString)
    yield new Imported(
      view,
      json,
      captions.map(_._3),
      parts.map(p => p.occurrence -> p.samples.map(_.ordinal)).toMap,
      parts.map(p => p.occurrence -> p.examined).toMap,
      parts.map(p => p.occurrence -> p.count).toMap
    )

  private def part(j: Json): Either[String, Part] =
    for
      _ <- keys(
        j,
        "occurrence",
        "edition",
        "input",
        "start",
        "end",
        "numerator",
        "denominator",
        "presentedCount",
        "samples",
        "framesIdentity",
        "framesChecksum",
        "receipt",
        "model",
        "recipe",
        "worker",
        "request",
        "proposals"
      )
      occurrence <- field[String](j, "occurrence")
      _ <- check(occurrence.trim.nonEmpty, "empty part occurrence")
      edition <- field[String](j, "edition").flatMap(EditionId.from(_).left.map(_.message))
      input <- hash(j, "input")
      start <- long(j, "start")
      end <- long(j, "end")
      numerator <- long(j, "numerator")
      denominator <- long(j, "denominator")
      timebase <- RationalTimebase.of(numerator, denominator).left.map(_.message)
      bundle <- SourceBundle.filmEdition(edition, input, start, end, timebase).left.map(_.message)
      count <- field[Int](j, "presentedCount")
      rows <- field[Vector[Json]](j, "samples")
      samples <- rows.traverse { row =>
        for
          _ <- keys(row, "ordinal", "pts", "duration")
          ordinal <- field[Int](row, "ordinal")
          pts <- long(row, "pts")
          duration <- field[Option[String]](row, "duration")
            .flatMap(_.traverse(d => d.toLongOption.toRight("invalid sample duration")))
          _ <- check(
            ordinal >= 0 && ordinal < count && pts >= start && pts < end &&
              duration.forall(d => d > 0 && BigInt(pts) + BigInt(d) <= BigInt(end)),
            "sample outside declared part or invalid duration"
          )
        yield Sample(ordinal, pts, duration)
      }
      _ <- check(
        samples.nonEmpty && count > 0 && samples
          .sliding(2)
          .forall(w => w.size < 2 || (w(0).ordinal < w(1).ordinal && w(0).pts < w(1).pts)),
        "samples must have increasing ordinals and PTS"
      )
      framesIdentity <- hash(j, "framesIdentity")
      framesChecksum <- hash(j, "framesChecksum")
      model <- hash(j, "model")
      recipe <- hash(j, "recipe")
      worker <- hash(j, "worker")
      request <- field[String](j, "request")
      r <- field[Json](j, "receipt")
      _ <- keys(r, "algorithm", "parameters", "inputs", "identity")
      algorithm <- field[String](r, "algorithm")
      parameters <- field[String](r, "parameters")
      _ <- check(
        algorithm == "vlm-caption-proposals/v3" && parameters ==
          s"worker ${worker.hex} model ${model.hex} recipe ${recipe.hex} request $request edit-list:identity-assumed",
        "caption recipe or worker differs from receipt"
      )
      inputs <- field[Vector[String]](r, "inputs")
        .flatMap(_.traverse(Checksum.from(_).left.map(_.message)))
      identity <- hash(r, "identity")
      receipt <- SourceDerivationReceipt
        .rehydrate(algorithm, parameters, inputs, identity)
        .left
        .map(_.message)
      proposals <- field[Vector[Json]](j, "proposals")
      captions <- proposals.traverse { p =>
        for
          _ <- keys(p, "id", "text", "samples", "start", "end")
          id <- field[String](p, "id")
          text <- field[String](p, "text")
          indices <- field[Vector[Int]](p, "samples")
          _ <- check(
            id.trim.nonEmpty && text.trim.nonEmpty && indices.nonEmpty &&
              indices.distinct.sorted == indices && indices.forall(i => i >= 0 && i < samples.size),
            "caption must name existing increasing samples and nonempty text"
          )
          onset <- long(p, "start")
          until <- long(p, "end")
          last = samples(indices.last)
          duration <- last.duration.toRight("caption end sample has unknown duration")
          _ <- check(
            onset == samples(indices.head).pts && BigInt(until) == BigInt(last.pts) + duration,
            "caption support differs from sampled timestamps"
          )
          support <- PlaybackInterval.on(bundle.primaryAxis, onset, until).left.map(_.message)
          caption <- CaptionEvidence.Caption.of(text, indices, support).left.map(_.message)
          evidence <- CaptionEvidence
            .of(bundle, framesIdentity, framesChecksum, receipt, Vector(caption))
            .left
            .map(_.message)
        yield id -> evidence
      }
      _ <- check(
        captions.nonEmpty && captions.map(_._1).distinct.size == captions.size,
        "empty or duplicate caption extents"
      )
      examined = captions.flatMap(_._2.captions.head.frames).distinct.sorted.map(samples(_).ordinal)
    yield Part(occurrence, bundle, count, samples, captions, examined)
