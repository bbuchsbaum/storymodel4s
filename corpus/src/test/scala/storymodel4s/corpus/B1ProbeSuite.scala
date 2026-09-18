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
