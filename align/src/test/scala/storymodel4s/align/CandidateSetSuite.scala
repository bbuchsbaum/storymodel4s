package storymodel4s.align

import munit.FunSuite

/** Historical abstention must remain distinct from a ranking that retained no anchors. */
class CandidateSetSuite extends FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private val first = Nomination(AnnaFixture.e1, "first", 0, Some(0.2), None, None)
  private val second = Nomination(AnnaFixture.e2, "second", 0, Some(0.3), None, None)
  private val emptyRanked = right(CandidateSet.of(Vector.empty, abstained = false))

  test("abstention refuses nonempty nominations") {
    assertEquals(
      CandidateSet.of(Vector(first), abstained = true),
      Left(AlignError.InvalidConfig("candidates", "abstained candidates must have no nominations"))
    )
  }

  test("all three lawful declaration states remain distinct") {
    val nominated = right(CandidateSet.of(Vector(first), abstained = false))
    val abstained = right(CandidateSet.of(Vector.empty, abstained = true))
    assertEquals(nominated.ranked, Vector(first.ref))
    assert(!nominated.abstained)
    assert(emptyRanked.isEmpty && !emptyRanked.abstained)
    assert(abstained.isEmpty && abstained.abstained)
    assertEquals(abstained, CandidateSet.unranked)
    assertNotEquals(emptyRanked, abstained)
    assertEquals(nominated, right(CandidateSet.of(Vector(first), abstained = false)))
  }

  test("zero retention budget preserves observed ranking rather than declaring abstention") {
    val result = CandidateGenerator(AnnaFixture.semantic, perLevel = 0, lexicalOverlap = false)
      .forUnit(AnnaFixture.u0, AnnaFixture.view)
    assertEquals(result, emptyRanked)
  }

  test("channel ablation declares effective abstention only after removing the last nomination") {
    val original = right(CandidateSet.of(Vector(first, second), abstained = false))
    val remaining = original.without("first")
    assertEquals(remaining.nominations, Vector(second))
    assert(!remaining.abstained)
    assertEquals(remaining.without("second"), CandidateSet.unranked)
    assertEquals(original.without("absent"), original)
    assertEquals(emptyRanked.without("absent"), emptyRanked)
    assertEquals(CandidateSet.unranked.without("absent"), CandidateSet.unranked)
  }

  test("fusion preserves all-abstained versus empty-but-ranked declarations") {
    assertEquals(CandidateSet.fuse(Vector(CandidateSet.unranked)), CandidateSet.unranked)
    assertEquals(CandidateSet.fuse(Vector(CandidateSet.unranked, emptyRanked)), emptyRanked)
    val nominated = right(CandidateSet.of(Vector(first), abstained = false))
    assertEquals(CandidateSet.fuse(Vector(CandidateSet.unranked, nominated)), nominated)
  }

  test("historical empty declarations produce different external supports and remain unattested") {
    def evidence(set: CandidateSet): LocalEvidence = right(
      LocalEvidence.compute(
        AnnaFixture.recall,
        AnnaFixture.view,
        Candidates(AnnaFixture.recall.ordered.map(u => u.id -> set).toMap),
        AnnaFixture.costModel,
        gate = true
      )
    )
    val abstained = evidence(CandidateSet.unranked)
    val ranked = evidence(emptyRanked)
    assertEquals(abstained.provenance, CandidateProvenance.Unattested)
    assertEquals(ranked.provenance, CandidateProvenance.Unattested)
    assertEquals(abstained.states, Vector.fill(abstained.units.size)(Vector(AlignState.unranked)))
    assertEquals(ranked.states, Vector.fill(ranked.units.size)(AlignState.externals.sortBy(_.key)))
    assertNotEquals(abstained.identity, ranked.identity)
  }
