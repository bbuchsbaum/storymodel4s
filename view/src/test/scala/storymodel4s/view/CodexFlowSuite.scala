package storymodel4s.view

import cats.syntax.all.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import storymodel4s.core.*

class CodexFlowSuite extends ScalaCheckSuite:
  private val viewConfig = Checksum.ofText("codex-flow-suite")

  private def source(text: String): StorySource =
    StorySource.fromText(text, Some("test story")).fold(error => fail(error.message), identity)

  private def fixtureProvenance(source: StorySource): ViewProvenance =
    ViewProvenance
      .fixture(source.canonicalChecksum, "codex-flow-suite", viewConfig)
      .fold(error => fail(error.message), identity)

  private def claimAddress(value: String): Address =
    Addressable[CoreRef].address(CoreRef.Claim(ClaimId.unsafe(value)))

  private def annotation(
      target: Address,
      span: TextSpan,
      kind: AnnotationKind = AnnotationKind.Claim,
      priority: AnnotationPriority = AnnotationPriority.Default,
      audit: AuditRecord = AuditRecord.deterministic("codex-flow-suite", viewConfig)
  ): TextAnnotation =
    TextAnnotation
      .of(target, SpanSet.one(span), kind, priority, audit)
      .fold(error => fail(error.message), identity)

  test("source runs tile canonical text without copying it"):
    val story = source("alpha beta")
    val runs = Vector(
      SourceRun.unsafe(TextSpan.unsafe(5, story.canonicalText.length)),
      SourceRun.unsafe(TextSpan.unsafe(0, 5))
    )
    val flow = CodexFlow
      .of(story, runs, Vector.empty, fixtureProvenance(story))
      .fold(error => fail(error.message), identity)

    assertEquals(flow.runs.map(_.span), Vector(TextSpan.unsafe(0, 5), TextSpan.unsafe(5, 10)))
    assertEquals(flow.runs.traverse(flow.text).map(_.mkString), Right(story.canonicalText))

  test("source runs reject gaps, overlaps, empty runs, and incomplete coverage"):
    val story = source("abcd")
    val provenance = fixtureProvenance(story)
    val gap = Vector(
      SourceRun.unsafe(TextSpan.unsafe(0, 1)),
      SourceRun.unsafe(TextSpan.unsafe(2, 4))
    )
    val overlap = Vector(
      SourceRun.unsafe(TextSpan.unsafe(0, 3)),
      SourceRun.unsafe(TextSpan.unsafe(2, 4))
    )
    val incomplete = Vector(SourceRun.unsafe(TextSpan.unsafe(0, 3)))

    assert(SourceRun.of(TextSpan.unsafe(2, 2)).isLeft)
    assert(CodexFlow.of(story, gap, Vector.empty, provenance).isLeft)
    assert(CodexFlow.of(story, overlap, Vector.empty, provenance).isLeft)
    assert(CodexFlow.of(story, incomplete, Vector.empty, provenance).isLeft)
    assert(CodexFlow.of(story, Vector.empty, Vector.empty, provenance).isLeft)

  test("runs and annotation supports cannot split UTF-16 surrogate pairs"):
    val story = source("A🙂B")
    val provenance = fixtureProvenance(story)
    val splitRun = Vector(
      SourceRun.unsafe(TextSpan.unsafe(0, 2)),
      SourceRun.unsafe(TextSpan.unsafe(2, story.canonicalText.length))
    )
    val badAnnotation = annotation(claimAddress("claim:emoji"), TextSpan.unsafe(1, 2))

    assert(CodexFlow.of(story, splitRun, Vector.empty, provenance).isLeft)
    assert(CodexFlow.exact(story, Vector(badAnnotation), provenance).isLeft)

    val goodAnnotation = annotation(claimAddress("claim:emoji"), TextSpan.unsafe(1, 3))
    assert(CodexFlow.exact(story, Vector(goodAnnotation), provenance).isRight)

  test("annotation identity depends only on target, support, and kind"):
    val target = claimAddress("claim:one")
    val support = SpanSet.one(TextSpan.unsafe(0, 4))
    val low = TextAnnotation
      .of(
        target,
        support,
        AnnotationKind.Claim,
        AnnotationPriority.Default,
        AuditRecord.deterministic("first", Checksum.ofText("first"))
      )
      .fold(error => fail(error.message), identity)
    val high = TextAnnotation
      .of(
        target,
        support,
        AnnotationKind.Claim,
        AnnotationPriority.unsafe(100),
        AuditRecord.deterministic("second", Checksum.ofText("second"))
      )
      .fold(error => fail(error.message), identity)
    val relation = TextAnnotation
      .of(
        target,
        support,
        AnnotationKind.Relation,
        AnnotationPriority.Default,
        low.audit
      )
      .fold(error => fail(error.message), identity)
    val other = annotation(claimAddress("claim:two"), TextSpan.unsafe(0, 4))

    assertEquals(low.id, high.id)
    assertNotEquals(low.id, relation.id)
    assertNotEquals(low.id, other.id)
    assert(
      TextAnnotation
        .validated(other.id, target, support, AnnotationKind.Claim, low.priority, low.audit)
        .isLeft
    )

  test("duplicate semantic annotations are rejected rather than silently overwritten"):
    val story = source("one annotation")
    val item = annotation(claimAddress("claim:duplicate"), TextSpan.unsafe(0, 3))
    val result = CodexFlow.exact(story, Vector(item, item), fixtureProvenance(story))

    assertEquals(
      result.left.toOption,
      Some(DomainError.DuplicateId("AnnotationId", item.id.value))
    )

  test("annotations reject addresses outside the closed typed view reference space"):
    val foreign = Address(
      ModuleTag.unsafe("outside"),
      AddressKind.unsafe("object"),
      AddressKey.of("x")
    )
    val result = TextAnnotation.of(
      foreign,
      SpanSet.one(TextSpan.unsafe(0, 1)),
      AnnotationKind.Claim,
      AnnotationPriority.Default,
      AuditRecord.deterministic("codex-flow-suite", viewConfig)
    )

    assert(result.isLeft)

  test("navigation maps targets to annotations and annotations back to targets"):
    val story = source("alpha beta")
    val target = claimAddress("claim:navigation")
    val first = annotation(target, TextSpan.unsafe(0, 5), AnnotationKind.Claim)
    val second = annotation(target, TextSpan.unsafe(6, 10), AnnotationKind.Relation)
    val flow = CodexFlow
      .exact(story, Vector(second, first), fixtureProvenance(story))
      .fold(error => fail(error.message), identity)

    assertEquals(flow.navigation.annotationsFor(target), Vector(first.id, second.id).sorted)
    assertEquals(flow.navigation.targetOf(first.id), Some(target))
    assertEquals(flow.navigation.targetOf(second.id), Some(target))
    assertEquals(flow.navigation.targetOf(AnnotationId.unsafe("annotation:missing")), None)

  test("audit dependencies are sorted and deduplicated"):
    val first = claimAddress("claim:a")
    val second = claimAddress("claim:b")
    val audit = AuditRecord.deterministic(
      "codex-flow-suite",
      viewConfig,
      Vector(second, first, second)
    )

    assertEquals(audit.upstream, Vector(first, second))

  test("view provenance distinguishes a model receipt from a source checksum"):
    val story = source("provenance")
    val missingReceipt = ViewProvenance.of(
      story.canonicalChecksum,
      None,
      ViewBasis.ValidatedBuild,
      "codex-flow-suite",
      viewConfig
    )
    val wrongSource = ViewProvenance
      .fixture(Checksum.ofText("another source"), "codex-flow-suite", viewConfig)
      .fold(error => fail(error.message), identity)

    assert(missingReceipt.isLeft)
    assert(CodexFlow.exact(story, Vector.empty, wrongSource).isLeft)

  test("annotation priorities are bounded domain values"):
    assertEquals(AnnotationPriority.from(0), Right(AnnotationPriority.Default))
    assertEquals(AnnotationPriority.from(AnnotationPriority.Maximum).map(_.value), Right(1000))
    assert(AnnotationPriority.from(-1).isLeft)
    assert(AnnotationPriority.from(1001).isLeft)

  test("the textual twin is deterministic and exposes exact evidence coordinates"):
    val story = source("auditable text")
    val target = claimAddress("claim:audit")
    val item = annotation(target, TextSpan.unsafe(0, 9), AnnotationKind.Claim)
    val flow = CodexFlow
      .exact(story, Vector(item), fixtureProvenance(story))
      .fold(error => fail(error.message), identity)

    assertEquals(flow.textualTwin, flow.textualTwin)
    assert(flow.textualTwin.contains(story.canonicalText))
    assert(flow.textualTwin.contains("researcher-reviewed narrative acceptance fixture"))
    assert(flow.textualTwin.contains(s"target=${target.render}"))
    assert(flow.textualTwin.contains("support=[0,9)"))

  private val unicodeText: Gen[String] =
    val basic = Gen.alphaChar.map(_.toString)
    val supplementary =
      Gen.chooseNum(0x1f300, 0x1f64f).map(codePoint => Character.toChars(codePoint).mkString)
    Gen.nonEmptyListOf(Gen.frequency(8 -> basic, 2 -> supplementary)).map(_.mkString)

  property("valid code-point partitions always tile the exact source"):
    forAll(unicodeText) { raw =>
      val story = source(raw)
      val boundaries = Vector.newBuilder[Int]
      boundaries += 0
      var offset = 0
      while offset < story.canonicalText.length do
        offset += Character.charCount(story.canonicalText.codePointAt(offset))
        boundaries += offset
      val runs = boundaries
        .result()
        .sliding(2)
        .collect { case Vector(start, end) =>
          SourceRun.unsafe(TextSpan.unsafe(start, end))
        }
        .toVector
      val flow = CodexFlow
        .of(story, runs.reverse, Vector.empty, fixtureProvenance(story))
        .fold(error => fail(error.message), identity)

      assertEquals(flow.runs.traverse(flow.text).map(_.mkString), Right(story.canonicalText))
    }
