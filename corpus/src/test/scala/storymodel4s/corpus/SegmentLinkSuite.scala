package storymodel4s.corpus

import storymodel4s.core.PresentationAxisId

import munit.FunSuite

class SegmentLinkSuite extends FunSuite:
  private val axis = PresentationAxisId.unsafe("axis:friends-comb")
  private val work = WorkId.unsafe("friends-s01e16-17")
  private val ev = LinkEvidence.Declared(Citation("FriendsMoreEMs", "shared Time axis"), "measured")

  private def seg(id: String, n: Int, level: GranularityLevel = GranularityLevel.Event) =
    Segmentation
      .of(
        SegmentationId.unsafe(id),
        work,
        level,
        SegmentationAuthority.AuthorAnnotated(CoderId.unsafe("upstream")),
        axis,
        (1 to n).toVector.map(i => Segment(i, i.toLong * 10, s"e$i"))
      )
      .fold(r => fail(r.message), identity)

  private def to(seg: Segmentation, ordinal: Int) = Target.To(SegmentRef(seg.id, ordinal), ev)

  test("a segmentation refuses empty, non-dense ordinals, and non-increasing onsets") {
    val id = SegmentationId.unsafe("s")
    val auth = SegmentationAuthority.Crowd(3)
    def build(segs: Vector[Segment]) =
      Segmentation.of(id, work, GranularityLevel.Event, auth, axis, segs)
    assert(build(Vector.empty).isLeft)
    assert(build(Vector(Segment(1, 0, "a"), Segment(3, 10, "b"))).isLeft)
    // two segments MAY share an onset: measured on the admitted Sherlock annotation, row 3 is
    // zero-duration (start == end == 20) and row 4 also starts at 20. Requiring strict increase
    // refused a corpus this repository has already admitted.
    assert(build(Vector(Segment(1, 10, "a"), Segment(2, 10, "b"))).isRight)
    // a DECREASE is still refused -- the same table has one where run 2 restarts at 0, which is
    // the signal that the annotation is two segmentations, one per media part
    assert(build(Vector(Segment(1, 10, "a"), Segment(2, 5, "b"))).isLeft)
    assert(build(Vector(Segment(1, 0, "a"), Segment(2, 10, "b"))).isRight)
  }

  test("THE COUNTEREXAMPLE: a bijection may not drop a source") {
    // {a -> To(x), b -> Removed} is total, injective on To, and covers every target it reaches.
    // It is not a bijection, and an earlier statement of these laws admitted it.
    val a = seg("a", 2)
    val b = seg("b", 1)
    val mapping = Map(1 -> to(b, 1), 2 -> Target.Removed(ev))
    assertEquals(
      SegmentLink.of(a, b, LinkClaim.Bijection, 1, mapping),
      Left(LinkRefusal.BijectionDropsSources(1))
    )
    // and the same mapping IS a legitimate Edit
    assert(SegmentLink.of(a, b, LinkClaim.Edit, 1, mapping).isRight)
  }

  test("a bijection must be injective and onto") {
    val a = seg("a", 2)
    val b = seg("b", 2)
    assertEquals(
      SegmentLink.of(a, b, LinkClaim.Bijection, 1, Map(1 -> to(b, 1), 2 -> to(b, 1))),
      Left(LinkRefusal.NotInjective)
    )
    val c = seg("c", 3)
    assertEquals(
      SegmentLink.of(a, c, LinkClaim.Bijection, 1, Map(1 -> to(c, 1), 2 -> to(c, 2))),
      Left(LinkRefusal.NotOnto)
    )
    assert(SegmentLink.of(a, b, LinkClaim.Bijection, 1, Map(1 -> to(b, 1), 2 -> to(b, 2))).isRight)
  }

  test("a coarsening is many-to-one, onto, and drops nothing") {
    val fine = seg("fine", 4, GranularityLevel.Fine)
    val coarse = seg("coarse", 2, GranularityLevel.Scene)
    val m = Map(1 -> to(coarse, 1), 2 -> to(coarse, 1), 3 -> to(coarse, 2), 4 -> to(coarse, 2))
    val link =
      SegmentLink.of(fine, coarse, LinkClaim.Coarsening, 1, m).fold(r => fail(r.message), identity)
    // multiplicity is COMPUTED, never declared: no "survivor" is chosen and no sibling is
    // relabelled an editorial merge
    assertEquals(link.multiplicity(SegmentRef(coarse.id, 1)), 2)
    assertEquals(link.multiplicity(SegmentRef(coarse.id, 2)), 2)
    assert(
      SegmentLink.of(fine, coarse, LinkClaim.Coarsening, 1, m.updated(4, Target.Removed(ev))).isLeft
    )
  }

  test("a link must be total over its source, and may not name foreign ordinals") {
    val a = seg("a", 3)
    val b = seg("b", 3)
    assertEquals(
      SegmentLink.of(a, b, LinkClaim.Edit, 1, Map(1 -> to(b, 1))),
      Left(LinkRefusal.NotTotal(Vector(2, 3)))
    )
    val full = Map(1 -> to(b, 1), 2 -> to(b, 2), 3 -> to(b, 3))
    assertEquals(
      SegmentLink.of(a, b, LinkClaim.Edit, 1, full.updated(9, to(b, 1))),
      Left(LinkRefusal.ForeignSource(Vector(9)))
    )
  }

  test("a target must belong to the target segmentation and be in range") {
    val a = seg("a", 1)
    val b = seg("b", 1)
    val elsewhere = seg("elsewhere", 1)
    assert(SegmentLink.of(a, b, LinkClaim.Edit, 1, Map(1 -> to(elsewhere, 1))).isLeft)
    assertEquals(
      SegmentLink.of(a, b, LinkClaim.Edit, 1, Map(1 -> to(b, 7))),
      Left(LinkRefusal.TargetOutOfRange(SegmentRef(b.id, 7)))
    )
  }

  test("composition is function composition, and Removed absorbs") {
    val a = seg("a", 3)
    val b = seg("b", 3)
    val c = seg("c", 2)
    val ab = SegmentLink
      .of(a, b, LinkClaim.Bijection, 1, Map(1 -> to(b, 1), 2 -> to(b, 2), 3 -> to(b, 3)))
      .fold(r => fail(r.message), identity)
    val bc = SegmentLink
      .of(b, c, LinkClaim.Edit, 1, Map(1 -> to(c, 1), 2 -> Target.Removed(ev), 3 -> to(c, 2)))
      .fold(r => fail(r.message), identity)
    val composed =
      SegmentLink.compose(ab, bc).fold(r => fail(r.message), identity)
    assertEquals(composed(1).target, Some(SegmentRef(c.id, 1)))
    assertEquals(composed(2).target, None)
    assertEquals(composed(3).target, Some(SegmentRef(c.id, 2)))
    // composed evidence says it is composed; it cannot invent direct evidence
    assert(composed(1).evidenceOf.isInstanceOf[LinkEvidence.Composed])
  }

  test("composition refuses a mismatched intermediate, which SegmentRef makes checkable") {
    val a = seg("a", 1); val b = seg("b", 1); val c = seg("c", 1); val d = seg("d", 1)
    val ab = SegmentLink.of(a, b, LinkClaim.Bijection, 1, Map(1 -> to(b, 1))).toOption.get
    val cd = SegmentLink.of(c, d, LinkClaim.Bijection, 1, Map(1 -> to(d, 1))).toOption.get
    assertEquals(
      SegmentLink.compose(ab, cd),
      Left(LinkRefusal.IntermediateMismatch(b.id, c.id))
    )
  }

  test("a composed link equals a declared one on the MAPPING, not on the evidence") {
    val a = seg("a", 2); val b = seg("b", 2); val c = seg("c", 2)
    val ab =
      SegmentLink.of(a, b, LinkClaim.Bijection, 1, Map(1 -> to(b, 1), 2 -> to(b, 2))).toOption.get
    val bc =
      SegmentLink.of(b, c, LinkClaim.Bijection, 1, Map(1 -> to(c, 1), 2 -> to(c, 2))).toOption.get
    val composedMap = SegmentLink.compose(ab, bc).toOption.get
    val composed = SegmentLink.of(a, c, LinkClaim.Bijection, 1, composedMap).toOption.get
    val declared =
      SegmentLink.of(a, c, LinkClaim.Bijection, 1, Map(1 -> to(c, 1), 2 -> to(c, 2))).toOption.get
    assert(composed.sameMappingAs(declared))
    assertNotEquals(composed.mapping(1).evidenceOf, declared.mapping(1).evidenceOf)
  }

  test("the Friends 56 -> 52 shape: four removals and four shifts, as an Edit") {
    val em56 = seg("friends.em56", 56)
    val em52 = seg("friends.em52", 52)
    val removed = Set(19, 37, 39, 48)
    def shifted(i: Int): Int = i - removed.count(_ < i)
    val mapping = (1 to 56).map { i =>
      i -> (if removed.contains(i) then Target.Removed(ev) else to(em52, shifted(i)))
    }.toMap
    val link = SegmentLink
      .of(em56, em52, LinkClaim.Edit, 1, mapping)
      .fold(r => fail(r.message), identity)
    assertEquals(link.removed, removed)
    assertEquals(link(1).map(_.ordinal), Some(1))
    assertEquals(link(18).map(_.ordinal), Some(18))
    assertEquals(link(19), None)
    assertEquals(link(20).map(_.ordinal), Some(19))
    assertEquals(link(56).map(_.ordinal), Some(52))
    // every surviving source lands on a distinct target: 52 of them
    assertEquals(link.multiplicity.size, 52)
    // and it is NOT a bijection, because it drops four
    assert(SegmentLink.of(em56, em52, LinkClaim.Bijection, 1, mapping).isLeft)
  }

  /** What `SegmentLink` is NOT for, recorded as a test so the boundary is checkable.
    *
    * Film Festival's `+106` is often described alongside Friends' 56->52 as "another remap". It is
    * not the same shape. Friends has two ANNOTATION SCALES of one stimulus, both globally ordered,
    * and a link between them is exactly this type. Film Festival has ONE scale whose coarse segment
    * numbers RESTART at 1 in run 2, so the source numbering is not a total order over the work at
    * all -- `(run-1, 7)` and `(run-2, 7)` are different segments with the same ordinal.
    *
    * A `Segmentation` requires dense 1..n ordinals, so the run-local numbering cannot BE one until
    * the offset has already been applied. The `+106` therefore belongs to the reader, declared by
    * the profile, and is not a link between two segmentations.
    */
  test("a part-local numbering cannot be a Segmentation, which is why +106 is not a SegmentLink") {
    val runLocal = Vector(
      Segment(1, 0, "run-1 seg 1"),
      Segment(2, 10, "run-1 seg 2"),
      // run 2 restarts at 1 -- and this is what the source actually writes
      Segment(1, 20, "run-2 seg 1")
    )
    assertEquals(
      Segmentation.of(
        SegmentationId.unsafe("filmfest.run-local"),
        WorkId.unsafe("filmfestival"),
        GranularityLevel.Scene,
        SegmentationAuthority.AuthorAnnotated(CoderId.unsafe("JL")),
        axis,
        runLocal
      ),
      Left(SegmentationRefusal.OrdinalsNotDense(SegmentationId.unsafe("filmfest.run-local")))
    )
  }

  test("once the offset IS applied, the global scale is an ordinary Segmentation") {
    val global = seg("filmfest.global", 216, GranularityLevel.Scene)
    assertEquals(global.size, 216)
    // run-2 local 1 is global 107, by the measured run-1 coarse count of 106
    assertEquals(global.segment(107).map(_.ordinal), Some(107))
  }
