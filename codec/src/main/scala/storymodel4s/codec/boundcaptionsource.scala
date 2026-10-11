package storymodel4s.codec

import cats.syntax.all.*
import io.circe.{Decoder, Json}
import storymodel4s.align.bridge.{MediaLocus, TimedSourceView}
import storymodel4s.core.*

/** Explicit correspondence preserves annotation identity while deriving exact film coordinates. */
object BoundCaptionSourceCodec:
  val Schema: String = "prepared-annotation-video-source/v1"
  val CorrespondenceSchema: String = "annotation-film-correspondence/v1"

  /** Imported declarations prove consistency, never empirical synchronization or JVM execution. */
  final class Imported private[BoundCaptionSourceCodec] (
      val view: TimedSourceView,
      val annotations: TimedSourceView,
      val media: MediaCaptionSourceCodec.Imported,
      val artifact: String
  ):
    val identity: Checksum = Checksum.ofText(artifact)
    val authority: ObservationAuthority = ObservationAuthority.Draft
    override def equals(other: Any): Boolean = other match
      case that: Imported => artifact == that.artifact
      case _              => false
    override def hashCode(): Int = artifact.hashCode
    override def toString: String = s"ImportedAnnotationVideo(${identity.short()})"

  private def check(ok: Boolean, message: String): Either[String, Unit] =
    Either.cond(ok, (), message)
  private def field[A: Decoder](j: Json, key: String): Either[String, A] =
    j.hcursor.get[A](key).left.map(_ => s"invalid correspondence field $key")
  private def keys(j: Json, names: String*): Either[String, Unit] =
    check(
      j.asObject.exists(_.keys.toSet == names.toSet),
      "unknown or missing correspondence fields"
    )
  private def parse(text: String): Either[String, Json] = for
    _ <- MappingJson.uniqueObjectKeys(text).left.map(_.toString)
    j <- Canonical.parse(text).left.map(_.toString)
  yield j
  private def tick(j: Json, key: String): Either[String, Long] =
    field[String](j, key).flatMap(_.toLongOption.toRight("invalid correspondence tick"))
  private def rational(n: BigInt, d: BigInt): Either[String, ExactRational] =
    if d == 0 then Left("zero correspondence denominator")
    else
      val g = n.gcd(d)
      val sign = if d < 0 then -1 else 1
      val a = n / g * sign
      val b = d / g * sign
      if a.isValidLong && b.isValidLong then
        ExactRational.of(a.toLong, b.toLong).left.map(_.message)
      else Left("correspondence rational overflow")
  private def extent(b: SourceBundle): AxisExtent.PlaybackTicks =
    b.primaryAxis.extent.asInstanceOf[AxisExtent.PlaybackTicks]

  private final case class Projection(
      occurrence: String,
      source: SourceBundle,
      target: SourceBundle,
      low: Long,
      high: Long,
      repair: ClockRepair
  ):
    def apply(value: Long): Either[String, Long] = for
      _ <- check(value >= low && value <= high, "annotation outside correspondence domain")
      seconds <- ExactRational.integer(value).*(extent(source).timebase.scale).left.map(_.message)
      mapped <- ClockRepair
        .projectRunLocalSeconds(seconds, source.primaryAxis.id, target.primaryAxis.id, repair)
        .left
        .map(_.message)
      scale = extent(target).timebase.scale
      n = BigInt(mapped.numerator) * scale.denominator
      d = BigInt(mapped.denominator) * scale.numerator
      _ <- check(
        n % d == 0 && (n / d).isValidLong,
        "projection requires exact representable film ticks"
      )
    yield (n / d).toLong

  private def projection(
      j: Json,
      annotations: TimedSourceView,
      media: MediaCaptionSourceCodec.Imported,
      receipt: Checksum
  ): Either[String, (String, Projection)] =
    for
      _ <- keys(j, "annotationPart", "filmOccurrence", "annotationAxis", "filmAxis", "landmarks")
      name <- field[String](j, "annotationPart")
      occurrence <- field[String](j, "filmOccurrence")
      source <- annotations.parts.get(name).toRight("unknown annotation part")
      target <- media.view.parts.get(occurrence).toRight("unknown film occurrence")
      a <- field[String](j, "annotationAxis")
      b <- field[String](j, "filmAxis")
      _ <- check(
        a == source.primaryAxis.id.value && b == target.primaryAxis.id.value,
        "correspondence axes differ"
      )
      raw <- field[Vector[Json]](j, "landmarks")
      points <- raw.traverse(p =>
        for
          _ <- keys(p, "annotationTick", "filmTick")
          x <- tick(p, "annotationTick")
          y <- tick(p, "filmTick")
        yield x -> y
      )
      _ <- check(
        points.size >= 2 && points.sliding(2).forall(p => p(0)._1 < p(1)._1 && p(0)._2 < p(1)._2),
        "ordered distinct correspondence landmarks required"
      )
      _ <- check(
        points.forall((x, y) =>
          x >= extent(source).start && x <= extent(source).endExclusive &&
            y >= extent(target).start && y <= extent(target).endExclusive
        ),
        "landmark outside declared axis"
      )
      dx = BigInt(points.last._1) - points.head._1
      dy = BigInt(points.last._2) - points.head._2
      _ <- check(
        points.forall((x, y) =>
          (BigInt(y) - points.head._2) * dx ==
            (BigInt(x) - points.head._1) * dy
        ),
        "landmarks disagree with one affine correspondence"
      )
      at = extent(source).timebase.scale
      bt = extent(target).timebase.scale
      scale <- rational(dy * bt.numerator * at.denominator, dx * bt.denominator * at.numerator)
      offset <- rational(
        (BigInt(points.head._2) * dx - BigInt(points.head._1) * dy) * bt.numerator,
        dx * bt.denominator
      )
      derivation <- SourceDerivationReceipt
        .of(
          "declared-annotation-film-correspondence/v1",
          Canonical.print(j),
          Vector(receipt, source.identity, target.identity)
        )
        .left
        .map(_.message)
      repair <- ClockRepair
        .of(source.primaryAxis.id, target.primaryAxis.id, scale, offset, derivation)
        .left
        .map(_.message)
    yield name -> Projection(occurrence, source, target, points.head._1, points.last._1, repair)

  /** The caller supplies an independent pin; equal numbers or matching part names grant no join. */
  def bind(
      annotationArtifact: String,
      mediaArtifact: String,
      correspondence: String,
      expected: Checksum
  ): Either[String, Imported] = for
    _ <- check(Checksum.ofText(correspondence) == expected, "correspondence checksum differs")
    annotations <- TimedSourceArtifactCodec.decode(annotationArtifact)
    media <- MediaCaptionSourceCodec.decode(mediaArtifact)
    c <- parse(correspondence)
    _ <- keys(
      c,
      "schemaVersion",
      "annotationArtifact",
      "packageManifest",
      "packageIdentity",
      "edition",
      "basis",
      "parts"
    )
    schema <- field[String](c, "schemaVersion")
    _ <- check(schema == CorrespondenceSchema, "unsupported correspondence")
    annotationPin <- field[String](c, "annotationArtifact")
    _ <- check(
      annotationPin == Checksum.ofText(annotationArtifact).hex,
      "annotation artifact differs"
    )
    _ <- Vector("packageManifest", "packageIdentity", "edition").traverse_(key =>
      for
        givenPin <- field[String](c, key)
        actual <- field[String](media.document, key)
        _ <- check(givenPin == actual, "media correspondence pin differs")
      yield ()
    )
    basis <- field[String](c, "basis")
    _ <- check(basis.trim.nonEmpty, "correspondence needs a stated evidence basis")
    rows <- field[Vector[Json]](c, "parts")
    entries <- rows.traverse(projection(_, annotations, media, expected))
    _ <- check(
      entries.map(_._1).distinct.size == entries.size &&
        entries.map(_._1).toSet == annotations.parts.keySet &&
        entries.map(_._2.occurrence).distinct.size == entries.size,
      "correspondence must uniquely cover annotation parts"
    )
    mappings = entries.toMap
    segments <- annotations.segments.traverse { s =>
      s.locus
        .traverse { locus =>
          val p = mappings(locus.part)
          for
            start <- p(locus.startTick)
            end <- p(locus.endTick)
            out <- locus match
              case _: MediaLocus.Extent =>
                PlaybackInterval
                  .onTimeAxis(p.target.primaryAxis, start, end)
                  .left
                  .map(_.message)
                  .map(MediaLocus.Extent(p.occurrence, _))
              case _: MediaLocus.Instant =>
                PlaybackInstant
                  .onTimeAxis(p.target.primaryAxis, start)
                  .left
                  .map(_.message)
                  .map(MediaLocus.Instant(p.occurrence, _))
          yield out
        }
        .map(locus => s.copy(locus = locus))
    }
    captions = media.evidence.flatMap(_.captions)
    _ <- check(
      captions.map(_.support).distinct.size == captions.size,
      "ambiguous captions on the same film interval"
    )
    _ <- captions.traverse_(caption =>
      check(
        segments.count(_.locus.exists {
          case MediaLocus.Extent(_, interval) => interval == caption.support
          case _                              => false
        }) == 1,
        "caption needs one unique exact annotation interval"
      )
    )
    view <- TimedSourceView
      .of(segments, media.view.parts, annotations.artifact)
      .left
      .map(_.toString)
    artifact = Canonical.print(
      Json.obj(
        "schemaVersion" -> Json.fromString(Schema),
        "annotations" -> Json.fromString(annotationArtifact),
        "media" -> Json.fromString(mediaArtifact),
        "correspondence" -> Json.fromString(correspondence),
        "correspondenceSha256" -> Json.fromString(expected.hex)
      )
    )
  yield new Imported(view, annotations, media, artifact)

  /** Portable replay retains the declared basis; the JVM registration must reverify media bytes. */
  def decode(text: String): Either[String, Imported] = for
    j <- parse(text)
    _ <- keys(j, "schemaVersion", "annotations", "media", "correspondence", "correspondenceSha256")
    schema <- field[String](j, "schemaVersion")
    _ <- check(schema == Schema, "unsupported annotation-video source")
    a <- field[String](j, "annotations")
    m <- field[String](j, "media")
    c <- field[String](j, "correspondence")
    pin <- field[String](j, "correspondenceSha256").flatMap(Checksum.from(_).left.map(_.message))
    result <- bind(a, m, c, pin)
  yield result
