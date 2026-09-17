package storymodel4s.core

import munit.FunSuite

/** A timed annotation is not an edition, and until now it had no lawful axis.
  *
  * The consequence was measurable: the one corpus with no admitted video forged an edition --
  * `EditionId("filmfestival-annotation-<sha>-<part>")` into `SourceBundle.filmEdition` -- while its
  * own Scaladoc said "This is deliberately not a film edition". `SourceKind.AnnotationTable` was
  * declared and unreachable because every axis constructor was kind-specific.
  */
class AnnotationAxisSuite extends FunSuite:
  private val sha = Checksum.ofText("annotation bytes")
  private val tb = RationalTimebase.of(1L, 1000L).fold(e => fail(e.message), identity)

  test("an annotation table gets a bundle with NO edition and no media claim") {
    val bundle = SourceBundle
      .annotationTable(sha, 0L, 2_518_500L, tb)
      .fold(e => fail(e.message), identity)
    assertEquals(bundle.edition, None)
    assertEquals(bundle.sourceKind, SourceKind.AnnotationTable)
    assertEquals(bundle.primaryAxis.kind, AxisKind.AnnotationTimeline)
    assertEquals(bundle.primaryAxis.edition, None)
    assertEquals(bundle.primaryAxis.sourceKind, SourceKind.AnnotationTable)
  }

  test("its identity is the annotation's checksum, so two readings of one annotation agree") {
    val a = SourceBundle.annotationTable(sha, 0L, 1000L, tb).fold(e => fail(e.message), identity)
    val b = SourceBundle.annotationTable(sha, 0L, 1000L, tb).fold(e => fail(e.message), identity)
    assertEquals(a.id, b.id)
    assertEquals(a.primaryAxis.id, b.primaryAxis.id)
    val other = SourceBundle
      .annotationTable(Checksum.ofText("different"), 0L, 1000L, tb)
      .fold(e => fail(e.message), identity)
    assertNotEquals(a.id, other.id)
  }

  test("an annotation axis is NOT an edition playback axis, and does not compare as one") {
    val ann = SourceBundle.annotationTable(sha, 0L, 1000L, tb).fold(e => fail(e.message), identity)
    val film = SourceBundle
      .filmEdition(EditionId.unsafe("some-film"), sha, 0L, 1000L, tb)
      .fold(e => fail(e.message), identity)
    assertNotEquals(ann.primaryAxis.kind, film.primaryAxis.kind)
    assertNotEquals(ann.primaryAxis.id, film.primaryAxis.id)
    assertNotEquals(ann.id, film.id)
  }

  test("a non-positive extent is refused, as for any playback-shaped axis") {
    assert(SourceBundle.annotationTable(sha, 10L, 10L, tb).isLeft)
    assert(SourceBundle.annotationTable(sha, 10L, 5L, tb).isLeft)
  }
