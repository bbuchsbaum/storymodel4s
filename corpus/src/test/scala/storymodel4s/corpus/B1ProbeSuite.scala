package storymodel4s.corpus

import storymodel4s.core.PresentationAxisId

import munit.FunSuite

/** Two defects a cold review found in the COMPOSITION path, after the rest of the slice had been
  * reviewed and fixed once.
  *
  * Both come from the same root: a `SegmentLink` stored only the two `SegmentationId`s, and a
  * `SegmentationId` is a string. `compose` knew ids were untrustworthy -- it says so in its own
  * comment and checks intermediate COVERAGE because of it -- but coverage only catches an
  * intermediate that is too small. Two segmentations of the same size that are simply different
  * were invisible, and so was the difference between a Friends segmentation and a Sherlock one.
  */
class SegmentLinkCompositionSuite extends FunSuite:
  private val axis = PresentationAxisId.unsafe("axis:x")
  private val work = WorkId.unsafe("w")
  private val ev = LinkEvidence.Declared(Citation("c", "n"), "measured")

  private def seg(
      id: String,
      onsets: Vector[Long],
      w: WorkId = work,
      ax: PresentationAxisId = axis
  ) =
    Segmentation
      .of(
        SegmentationId.unsafe(id),
        w,
        GranularityLevel.Event,
        SegmentationAuthority.AuthorAnnotated(CoderId.unsafe("u")),
        ax,
        onsets.zipWithIndex.map((o, i) => Segment(i + 1, o, s"s${i + 1}"))
      )
      .fold(r => fail(r.message), identity)

  private def bijection(from: Segmentation, to: Segmentation) =
    SegmentLink
      .of(
        from,
        to,
        LinkClaim.Bijection,
        1,
        from.ordinals.toVector.sorted
          .map(o => o -> Target.To(SegmentRef(to.id, o), ev))
          .toMap
      )
      .fold(r => fail(r.message), identity)

  /** The defect, as the reviewer demonstrated it. */
  test("compose REFUSES two different segmentations that share a name and a size") {
    val a = seg("a", Vector(0, 10, 20))
    val b1 = seg("b", Vector(0, 10, 20))
    val b2 = seg("b", Vector(5000, 6000, 7000)) // same id, same size, different segmentation
    val c = seg("c", Vector(0, 10, 20))
    assertNotEquals(b1, b2, "the two intermediates must really differ, or this proves nothing")

    SegmentLink.compose(bijection(a, b1), bijection(b2, c)) match
      case Left(LinkRefusal.IntermediateDiffers(id, x, y)) =>
        assertEquals(id.value, "b")
        assertNotEquals(x, y)
      case Left(other) => fail(s"refused for the wrong reason: ${other.message}")
      case Right(m)    =>
        fail(s"composed ${m.size} sources across two different intermediates")
  }

  test("compose still succeeds when the intermediate really is the same one") {
    val a = seg("a", Vector(0, 10, 20))
    val b = seg("b", Vector(0, 10, 20))
    val c = seg("c", Vector(0, 10, 20))
    val composed = SegmentLink
      .compose(bijection(a, b), bijection(b, c))
      .fold(r => fail(s"a genuine composition must succeed: ${r.message}"), identity)
    assertEquals(composed.size, 3)
    // an equal-but-separately-built intermediate is the SAME segmentation, and must still compose
    val bAgain = seg("b", Vector(0, 10, 20))
    assertEquals(b, bAgain)
    assert(SegmentLink.compose(bijection(a, b), bijection(bAgain, c)).isRight)
  }

  /** The second defect, found while reproducing the first. */
  test("a Friends segmentation is NOT equal to a Sherlock one with the same ordinals") {
    val friends = seg("s", Vector(0, 10), WorkId.unsafe("friends"))
    val sherlock = seg("s", Vector(0, 10), WorkId.unsafe("sherlock"))
    assertNotEquals(friends, sherlock, "different works were judged equal")
    assertNotEquals(friends.identity, sherlock.identity)
  }

  test("the axis, the level and the authority are all part of a segmentation's identity") {
    val base = seg("s", Vector(0, 10))
    assertNotEquals(
      base.identity,
      seg("s", Vector(0, 10), ax = PresentationAxisId.unsafe("axis:other")).identity
    )
    def withLevel(l: GranularityLevel) = Segmentation
      .of(
        SegmentationId.unsafe("s"),
        work,
        l,
        SegmentationAuthority.AuthorAnnotated(CoderId.unsafe("u")),
        axis,
        Vector(Segment(1, 0, "s1"))
      )
      .fold(r => fail(r.message), identity)
    assertNotEquals(
      withLevel(GranularityLevel.Event).identity,
      withLevel(GranularityLevel.Scene).identity
    )
    def withAuthority(a: SegmentationAuthority) = Segmentation
      .of(
        SegmentationId.unsafe("s"),
        work,
        GranularityLevel.Event,
        a,
        axis,
        Vector(Segment(1, 0, "s1"))
      )
      .fold(r => fail(r.message), identity)
    assertNotEquals(
      withAuthority(SegmentationAuthority.Crowd(3)).identity,
      withAuthority(SegmentationAuthority.ParticipantConsensus(3)).identity
    )
  }

  /** `GranularityLevel.Custom` and `SegmentationAuthority` both render with a colon and have
    * variable arity -- the exact shape that collided in `CorpusProfile.render`. The identity frames
    * and nests them rather than calling `render`, and this is the probe that says so.
    */
  test("a colon in a custom level cannot move the boundary between its parts") {
    def customLevel(ns: String, label: String) = Segmentation
      .of(
        SegmentationId.unsafe("s"),
        work,
        GranularityLevel.Custom(ns, label),
        SegmentationAuthority.AuthorAnnotated(CoderId.unsafe("u")),
        axis,
        Vector(Segment(1, 0, "s1"))
      )
      .fold(r => fail(r.message), identity)
    assertNotEquals(customLevel("a", "b:c").identity, customLevel("a:b", "c").identity)
  }

  /** The reviewer's own repro, kept because it is the REALISTIC shape of this defect.
    *
    * The version above differs the intermediates by onset, which is easy to dismiss as contrived.
    * This one differs them by AXIS, which is the case this module exists for: `link.scala` says a
    * cross-axis link "is the crosswalk this module exists to carry". So the two "mid" segmentations
    * are an annotation-second scale and a playback-tick scale -- exactly the pair a real crosswalk
    * sits between, and exactly the pair that must never be silently identified with each other.
    *
    * Before the fix this composed and built `SegmentLink(a -> c v1, bijection, 3 sources)` carrying
    * `LinkEvidence.Composed(declared, declared)` -- evidence asserting a chain through a shared
    * intermediate that nothing had established.
    */
  test("compose REFUSES an intermediate that differs only by AXIS") {
    val w = WorkId.unsafe("sherlock-a-study-in-pink")
    val annotationAxis = PresentationAxisId.unsafe("annotation-seconds")
    val playbackAxis = PresentationAxisId.unsafe("part-a-playback-ticks")

    val a = seg("a", Vector(0, 10, 20), w, annotationAxis)
    val midAnnotation = seg("mid", Vector(0, 10, 20), w, annotationAxis)
    val midPlayback = seg("mid", Vector(0, 25000, 50000), w, playbackAxis)
    val c = seg("c", Vector(0, 25000, 50000), w, playbackAxis)

    assertNotEquals(midAnnotation, midPlayback, "the two intermediates must really differ")

    val ab = bijection(a, midAnnotation)
    val bc = bijection(midPlayback, c)
    // both links are individually legal -- a cross-axis link is exactly what this module carries
    assertEquals(ab.from.value, "a")
    assertEquals(bc.to.value, "c")

    SegmentLink.compose(ab, bc) match
      case Left(LinkRefusal.IntermediateDiffers(id, _, _)) => assertEquals(id.value, "mid")
      case Left(other) => fail(s"refused for the wrong reason: ${other.message}")
      case Right(_)    =>
        fail("composed an annotation-second scale onto a playback-tick scale through a fiction")
  }
