package storymodel4s.align

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.core.*

class D1bScoringPositionSuite extends FunSuite:
  private val text = AnnaFixture.view
  private val bundle = SourceBundle
    .filmEdition(
      EditionId.unsafe("position-film"),
      Checksum.ofText("picture"),
      0L,
      100L,
      RationalTimebase.Millisecond
    )
    .toOption
    .get
  private def support(at: Long): TypedSupport = TypedSupport.Anchored(
    EvidenceSupport
      .of(
        bundle,
        Vector(
          EvidenceAnchor.MediaPoint(
            bundle.id,
            bundle.streams.head.id,
            PlaybackInstant.on(bundle.primaryAxis, at).toOption.get
          )
        )
      )
      .toOption
      .get
  )
  private def film(position: Option[ScoringPosition], at: Long = 25L): InMemorySourceView =
    text.copy(
      nodes = text.nodes.map(_.copy(support = support(at), scoringPosition = position)),
      scoringLength = 100
    )
  private val feature = ScoringPosition.LegacyAnnotationText(SpanSet.one(TextSpan.unsafe(10, 30)))

  test(
    "accepting control: physical point and declared scoring feature retain separate exact values"
  ):
    val view = film(Some(feature))
    val ref = view.nodes.head.ref
    assertEquals(view.node(ref).get.support, support(25L))
    assertEquals(view.relativeSpan(ref), Some((0.1, 0.3)))
    assertEquals(view.measuredPosition(ref), Some((0.1 + 0.3) / 2.0))
    assert(!view.textWireCompatible)

  test("a present film node without a scoring feature remains absent through transition features"):
    val view = film(None)
    val ref = view.nodes.head.ref
    assert(view.node(ref).nonEmpty)
    assertEquals(view.relativeSpan(ref), None)
    assertEquals(view.measuredPosition(ref), None)
    val features = TransitionFeatures.between(view, ref, view.nodes.last.ref)
    assert(!features.values.contains(TransitionKind.Backward))
    assert(!features.values.contains(TransitionKind.LongJump))
    assert(features.values.contains(TransitionKind.Stay))

  test("physical evidence and scoring coordinates independently affect fingerprint"):
    val base = film(Some(feature))
    assertNotEquals(base.contentFingerprint, film(Some(feature), 26L).contentFingerprint)
    val altered = ScoringPosition.LegacyAnnotationText(SpanSet.one(TextSpan.unsafe(11, 30)))
    assertNotEquals(base.contentFingerprint, film(Some(altered)).contentFingerprint)
    assertNotEquals(base.contentFingerprint, film(None).contentFingerprint)
    assertNotEquals(base.contentFingerprint, base.copy(scoringLength = 101).contentFingerprint)
    assertNotEquals(
      base.contentFingerprint,
      film(Some(ScoringPosition.CanonicalText(feature.spans))).contentFingerprint
    )

  test("measured zero differs from missing and an unmeasured denominator remains absent"):
    val zero = film(Some(ScoringPosition.LegacyAnnotationText(SpanSet.one(TextSpan.unsafe(0, 0)))))
    val ref = zero.nodes.head.ref
    assertEquals(zero.measuredPosition(ref), Some(0.0))
    assertEquals(zero.copy(scoringLength = 0).measuredPosition(ref), None)
    assertNotEquals(zero.contentFingerprint, film(None).contentFingerprint)

  test("text compatibility derives the historical scoring feature without changing arithmetic"):
    assert(text.textWireCompatible)
    text.nodes.foreach { node =>
      val spans = node.support.textSpans.get
      assertEquals(node.scoringPosition, Some(ScoringPosition.CanonicalText(spans)))
      val span = spans.minSpan
      assertEquals(
        text.measuredPosition(node.ref),
        Some(
          (span.start.toDouble / text.scoringLength + span.endExclusive.toDouble / text.scoringLength) / 2.0
        )
      )
    }

  test("public missing-as-zero accessor is absent with a measured-position accepting control"):
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      def position(v: SourceView, n: SourceNodeRef): Option[Double] = v.measuredPosition(n)
    """).isEmpty)
    assert(typeCheckErrors("""
      import storymodel4s.align.*
      def position(v: SourceView, n: SourceNodeRef): Double = v.relativePosition(n)
    """).nonEmpty)
