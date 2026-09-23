package storymodel4s.core

import java.nio.charset.StandardCharsets

/** Boundary provenance stays visible: supplied spans do not inherit an analyzer's authority. */
enum TextSegmentationProfile(val tag: String):
  case SurfaceSemicolonV1 extends TextSegmentationProfile("surface-semicolon/v1")
  case SuppliedAtlasV1 extends TextSegmentationProfile("supplied-atlas/v1")

/** Separate coordinates and semantic capabilities prevent text positions becoming video time. */
enum TextSourceCapability:
  case CharacterOffsets, DiscourseOrdinals, NarrativeModel, EncodingSeconds

/** A physical target with a derived identity; its original surface anchor remains a separate ID. */
type TextSourceSegment = TextSourcePackage.Segment

/** Checked offline segmentation, without fabricated narrative content or presentation timing. See
  * ADR 0021 for the canonical UTF-16 axis and the distinction between the two profiles.
  */
final class TextSourcePackage private (
    val atlas: SurfaceAtlas,
    val sourceAtlas: TextNarrativeAtlas,
    val profile: TextSegmentationProfile,
    val segments: Vector[TextSourceSegment]
):
  def source: StorySource = atlas.source
  def at(kind: SurfaceUnitKind): Vector[TextSourceSegment] =
    segments.filter(_.unit.kind == kind).sortBy(_.unit.ordinal)
  def supports(capability: TextSourceCapability): Boolean = capability match
    case TextSourceCapability.CharacterOffsets | TextSourceCapability.DiscourseOrdinals => true
    case TextSourceCapability.NarrativeModel | TextSourceCapability.EncodingSeconds     => false

object TextSourcePackage:
  /** Only package construction can join a target identity to its admitted surface unit. */
  final class Segment private[TextSourcePackage] (
      val id: SegmentId,
      val unit: SurfaceUnit
  )

  val Canonicalization: String = "story-source/v1"
  val OffsetUnit: String = "utf-16-code-units"

  private def invalid(detail: String): Left[DomainError, Nothing] =
    Left(DomainError.InvariantViolation("text-source", detail))

  /** Strict UTF-8 intake preserves the original bytes, including a BOM, through rawText. */
  def fromUtf8(bytes: Array[Byte]): Either[DomainError, TextSourcePackage] =
    val text = new String(bytes, StandardCharsets.UTF_8)
    if !text.getBytes(StandardCharsets.UTF_8).sameElements(bytes) then invalid("invalid UTF-8")
    else fromText(text)

  /** Generate a deterministic atlas and heuristic semicolon clauses from raw text. */
  def fromText(text: String): Either[DomainError, TextSourcePackage] =
    for
      _ <- checkText(text)
      source <- StorySource.fromText(text)
      result <- analyze(source)
    yield result

  /** Use an existing checked source while retaining its explicit identity and metadata. */
  def analyze(source: StorySource): Either[DomainError, TextSourcePackage] =
    for
      _ <- checkText(source.rawText)
      _ <- checkText(source.canonicalText)
      surface = SurfaceAnalyzer.analyze(source)
      clauses = surface.sentences
        .flatMap(sentence => clauseSpans(source.canonicalText, sentence.span).map(_ -> sentence.id))
        .zipWithIndex
        .map { case ((span, parent), ordinal) =>
          SurfaceUnit(
            SurfaceUnitId.unsafe(s"${source.id.value}:c$ordinal"),
            SurfaceUnitKind.Clause,
            span,
            ordinal,
            Some(parent)
          )
        }
      atlas <- SurfaceAtlas.of(source, surface.units ++ clauses)
      result <- checked(atlas, TextSegmentationProfile.SurfaceSemicolonV1)
    yield result

  /** Preserve a supplied atlas exactly; missing kinds are not generated behind the caller's back.
    */
  def fromAtlas(atlas: SurfaceAtlas): Either[DomainError, TextSourcePackage] =
    checked(atlas, TextSegmentationProfile.SuppliedAtlasV1)

  private def checked(
      atlas: SurfaceAtlas,
      profile: TextSegmentationProfile
  ): Either[DomainError, TextSourcePackage] =
    val text = atlas.source.canonicalText
    val targets = atlas.units.filter(_.kind != SurfaceUnitKind.Token)
    for
      _ <- checkText(atlas.source.rawText)
      _ <- checkText(text)
      _ <- SurfaceAtlas.validated(atlas)
      _ <-
        if targets.isEmpty then invalid("no paragraph, sentence or clause targets")
        else Right(())
      _ <-
        atlas.units.find(u =>
          u.span.isEmpty || u.ordinal < 0 || !boundary(text, u.span.start) ||
            !boundary(text, u.span.endExclusive)
        ) match
          case Some(_) => invalid("empty span, negative ordinal or split surrogate pair")
          case None    => Right(())
      sourceAtlas <- TextNarrativeAtlas.of(atlas)
    yield new TextSourcePackage(
      atlas,
      sourceAtlas,
      profile,
      targets.sortBy(u => (u.kind.ordinal, u.ordinal)).map { unit =>
        val digest = ContentAddress.digest(
          Vector(
            "text-source-segment/v1",
            profile.tag,
            atlas.source.canonicalChecksum.hex,
            unit.kind.toString,
            unit.span.start.toString,
            unit.span.endExclusive.toString
          )
        )
        new Segment(SegmentId.unsafe(s"text-segment:${digest.hex}"), unit)
      }
    )

  private def checkText(text: String): Either[DomainError, Unit] =
    var i = 0
    var valid = true
    while i < text.length && valid do
      val c = text.charAt(i)
      if Character.isHighSurrogate(c) then
        valid = i + 1 < text.length && Character.isLowSurrogate(text.charAt(i + 1))
        i += 2
      else
        valid = !Character.isLowSurrogate(c)
        i += 1
    if !valid then invalid("unpaired UTF-16 surrogate")
    else if text.replace("\uFEFF", "").trim.isEmpty then invalid("no text beyond whitespace or BOM")
    else Right(())

  private def boundary(text: String, at: Int): Boolean =
    at == 0 || at == text.length ||
      !(Character.isHighSurrogate(text.charAt(at - 1)) &&
        Character.isLowSurrogate(text.charAt(at)))

  private def clauseSpans(text: String, sentence: TextSpan): Vector[TextSpan] =
    val ends = (sentence.start until sentence.endExclusive)
      .filter(text.charAt(_) == ';')
      .map(_ + 1)
      .toVector :+ sentence.endExclusive
    var start = sentence.start
    ends.flatMap { end =>
      var a = start
      var b = end
      while a < b && text.charAt(a).isWhitespace do a += 1
      while b > a && text.charAt(b - 1).isWhitespace do b -= 1
      start = end
      Option.when(b > a)(TextSpan.unsafe(a, b))
    }
