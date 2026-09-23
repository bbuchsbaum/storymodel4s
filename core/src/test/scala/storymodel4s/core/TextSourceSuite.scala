package storymodel4s.core

import java.nio.charset.StandardCharsets
import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** Synthetic Unicode/segmentation diagnostics, not narrative or participant material. */
class TextSourceSuite extends FunSuite:
  private def get[A](value: Either[DomainError, A]): A =
    value.fold(e => fail(e.message), identity)

  test("BOM, CRLF, combining mark and supplementary code point keep exact canonical coordinates"):
    val raw = "\uFEFFA\u0301😀; B.  \r\n\r\nC.\r\n"
    val value = get(TextSourcePackage.fromUtf8(raw.getBytes(StandardCharsets.UTF_8)))
    assertEquals(value.source.rawText, raw)
    assertEquals(value.source.canonicalText, "\uFEFFA\u0301😀; B.\n\nC.")
    assertEquals(value.source.rawChecksum, Checksum.ofBytes(raw.getBytes(StandardCharsets.UTF_8)))
    val clauses = value.at(SurfaceUnitKind.Clause).map(_.unit)
    assertEquals(
      clauses.map(u => (u.span.start, u.span.endExclusive)),
      Vector((0, 6), (7, 9), (11, 13))
    )
    assertEquals(clauses.map(_.text(value.source)), Vector("\uFEFFA\u0301😀;", "B.", "C."))
    assertEquals(value.at(SurfaceUnitKind.Paragraph).size, 2)
    assertEquals(value.at(SurfaceUnitKind.Sentence).size, 2)

  test("canonical equivalent inputs have identical target IDs while raw identity differs"):
    val a = get(TextSourcePackage.fromText("A. \r\n\r\nB."))
    val b = get(TextSourcePackage.fromText("A.\n\nB."))
    assertEquals(a.segments.map(_.id), b.segments.map(_.id))
    assertNotEquals(a.source.rawChecksum, b.source.rawChecksum)
    val changed = get(TextSourcePackage.fromText("Z.\n\nB."))
    assertNotEquals(a.segments.map(_.id), changed.segments.map(_.id))

  test("caller story ID cannot masquerade as content identity"):
    val a = get(StorySource.fromText("A; B.", explicitId = Some(StoryId.unsafe("caller-a"))))
    val b = get(StorySource.fromText("A; B.", explicitId = Some(StoryId.unsafe("caller-b"))))
    val x = get(TextSourcePackage.analyze(a))
    val y = get(TextSourcePackage.analyze(b))
    assertEquals(x.segments.map(_.id), y.segments.map(_.id))
    assertEquals(x.segments.map(_.unit.id), y.segments.map(_.unit.id))
    assertEquals(x.source.id, a.id)
    assertEquals(y.source.id, b.id)
    assert(x.segments.forall(_.id.value.stripPrefix("text-segment:").length == 64))

  test("supplied atlas preserves anchors and absence without claiming generated boundaries"):
    val source = get(StorySource.fromText("A; B."))
    val unit = SurfaceUnit(
      SurfaceUnitId.unsafe("manual"),
      SurfaceUnitKind.Paragraph,
      TextSpan.unsafe(0, 5),
      7,
      None
    )
    val atlas = get(SurfaceAtlas.of(source, Vector(unit)))
    val imported = get(TextSourcePackage.fromAtlas(atlas))
    assertEquals(imported.atlas, atlas)
    assertEquals(imported.segments.map(_.unit), Vector(unit))
    assertEquals(imported.profile, TextSegmentationProfile.SuppliedAtlasV1)
    assertEquals(imported.at(SurfaceUnitKind.Clause), Vector.empty)
    assertNotEquals(
      imported.segments.head.id,
      get(TextSourcePackage.analyze(source)).segments.head.id
    )

  test("only character and ordinal capabilities are available"):
    val value = get(TextSourcePackage.fromText("A."))
    assertEquals(
      TextSourceCapability.values.filter(value.supports).toSet,
      Set(TextSourceCapability.CharacterOffsets, TextSourceCapability.DiscourseOrdinals)
    )

  test("malformed UTF-8 and unpaired surrogates are refused"):
    assert(TextSourcePackage.fromUtf8(Array(0xc3.toByte, 0x28.toByte)).isLeft)
    assert(TextSourcePackage.fromText("A\uD83D").isLeft)
    assert(TextSourcePackage.fromText("\uDE00A").isLeft)
    assert(TextSourcePackage.fromText(" \r\n").isLeft)
    assert(TextSourcePackage.fromText("\uFEFF").isLeft)
    assert(TextSourcePackage.fromText("\uFEFF \r\n").isLeft)

  test("package exposes the existing checked source bundle and narrative atlas seam"):
    val value = get(TextSourcePackage.fromText("A; B."))
    assertEquals(value.sourceAtlas.atlas, value.atlas)
    assertEquals(value.sourceAtlas.bundle, get(SourceBundle.writtenText(value.source)))
    assertEquals(value.sourceAtlas.units.size, value.atlas.units.size)

  test("maximum-length caller ID cannot overflow generated unit IDs"):
    val source =
      get(StorySource.fromText("A.", explicitId = Some(StoryId.unsafe("a" * IdRules.MaxLength))))
    val value = get(TextSourcePackage.analyze(source))
    assertEquals(value.source.id, source.id)
    assert(value.atlas.units.forall(_.id.value.length <= IdRules.MaxLength))

  test("source metadata and supplied IDs cannot smuggle unpaired surrogates into UTF-8"):
    val metadata = get(StorySource.fromText("A.", metadata = Map("key" -> "\uD800")))
    assert(TextSourcePackage.analyze(metadata).isLeft)
    val source = get(StorySource.fromText("A."))
    val unit = SurfaceUnit(
      SurfaceUnitId.unsafe("u\uD800"),
      SurfaceUnitKind.Sentence,
      TextSpan.unsafe(0, 2),
      0,
      None
    )
    assert(TextSourcePackage.fromAtlas(get(SurfaceAtlas.of(source, Vector(unit)))).isLeft)

  private def supplied(
      start: Int,
      end: Int,
      ordinal: Int = 0
  ): Either[DomainError, TextSourcePackage] =
    val source = get(StorySource.fromText("A😀B"))
    val unit = SurfaceUnit(
      SurfaceUnitId.unsafe("probe"),
      SurfaceUnitKind.Sentence,
      TextSpan.unsafe(start, end),
      ordinal,
      None
    )
    TextSourcePackage.fromAtlas(get(SurfaceAtlas.of(source, Vector(unit))))

  test("supplied boundaries cannot split a surrogate pair"):
    assert(supplied(1, 3).isRight)
    assert(supplied(1, 2).isLeft)
    assert(supplied(2, 3).isLeft)

  test("empty targets, empty spans and negative ordinals are refused"):
    assert(supplied(1, 1).isLeft)
    assert(supplied(0, 1, -1).isLeft)
    val source = get(StorySource.fromText("A."))
    assert(TextSourcePackage.fromAtlas(get(SurfaceAtlas.of(source, Vector.empty))).isLeft)

  test("supplied combining-mark support is legal on the declared code-unit axis"):
    val source = get(StorySource.fromText("A\u0301"))
    val unit = SurfaceUnit(
      SurfaceUnitId.unsafe("mark"),
      SurfaceUnitKind.Clause,
      TextSpan.unsafe(1, 2),
      0,
      None
    )
    assert(TextSourcePackage.fromAtlas(get(SurfaceAtlas.of(source, Vector(unit)))).isRight)

  test("semicolon punctuation is retained and trailing semicolons do not create empty clauses"):
    val value = get(TextSourcePackage.fromText("A;  B;"))
    assertEquals(
      value.at(SurfaceUnitKind.Clause).map(_.unit.text(value.source)),
      Vector("A;", "B;")
    )

  test("checked factory compiles but package construction, copy and product doors do not"):
    assert(typeCheckErrors("storymodel4s.core.TextSourcePackage.fromText(\"A.\")").isEmpty)
    assert(typeCheckErrors("""import storymodel4s.core.*
      def forge(a: SurfaceAtlas, n: TextNarrativeAtlas) = TextSourcePackage(a, n, TextSegmentationProfile.SuppliedAtlasV1, Vector.empty)
    """).nonEmpty)
    assert(typeCheckErrors("""import storymodel4s.core.*
      def forge(p: TextSourcePackage) = p.copy(segments = Vector.empty)
    """).nonEmpty)
    assert(typeCheckErrors("storymodel4s.core.TextSourcePackage.fromProduct(EmptyTuple)").nonEmpty)
    assert(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.TextSourcePackage]]"
      ).nonEmpty
    )
    assert(typeCheckErrors("""import storymodel4s.core.*
      def forge(id: SegmentId, u: SurfaceUnit) = new TextSourcePackage.Segment(id, u)
    """).nonEmpty)

  test("all four product-door controls compile for an honest product of the same fields"):
    assert(typeCheckErrors("""import storymodel4s.core.*
      case class Control(atlas: SurfaceAtlas, sourceAtlas: TextNarrativeAtlas, profile: TextSegmentationProfile, segments: Vector[TextSourceSegment])
      def make(a: SurfaceAtlas, n: TextNarrativeAtlas) = Control(a, n, TextSegmentationProfile.SuppliedAtlasV1, Vector.empty)
    """).isEmpty)
    assert(typeCheckErrors("""import storymodel4s.core.*
      case class Control(atlas: SurfaceAtlas, sourceAtlas: TextNarrativeAtlas, profile: TextSegmentationProfile, segments: Vector[TextSourceSegment])
      def rebuild(c: Control) = c.copy(segments = Vector.empty)
    """).isEmpty)
    assert(typeCheckErrors("""import storymodel4s.core.*
      case class Control(atlas: SurfaceAtlas, sourceAtlas: TextNarrativeAtlas, profile: TextSegmentationProfile, segments: Vector[TextSourceSegment])
      object Control:
        def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p)
      def rebuild(c: Control) = Control.fromProduct(c)
    """).isEmpty)
    assert(typeCheckErrors("""import storymodel4s.core.*
      case class Control(atlas: SurfaceAtlas, sourceAtlas: TextNarrativeAtlas, profile: TextSegmentationProfile, segments: Vector[TextSourceSegment])
      def rebuild(c: Control) = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(c)
    """).isEmpty)
