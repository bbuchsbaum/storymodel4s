package storymodel4s.align

import munit.FunSuite

import storymodel4s.recall.{RecallGraph, RecallRelations}

/** The coordinate join: its construction boundary, and the statuses it must refuse to invent. */
class TraceSuite extends FunSuite:
  import AnnaFixture.*

  private lazy val result: HsmmResult =
    GraphHsmm
      .infer(AnnaFixture.recall, view, AnnaFixture.candidates, AnnaFixture.costModel)
      .fold(e => fail(e.message), identity)

  /** The first scored source cell in the result, whatever it happens to be. */
  private lazy val (someUnit, someState, someNode) =
    val u = recall.ordered
      .find(u =>
        result.costs
          .get(u.id)
          .exists(_.keys.exists {
            case AlignState.Source(_) => true
            case _                    => false
          })
      )
      .getOrElse(fail("fixture: no unit has a source cell"))
    val s = result.costs(u.id).keys.collectFirst { case st @ AlignState.Source(_) => st }.get
    // collectFirst above already narrowed this to a Source state, so a wildcard arm here is
    // genuinely unreachable and the compiler says so. Destructure instead of pretending to handle
    // a case that cannot occur.
    val AlignState.Source(anchorRef) = s: @unchecked
    val n = view.node(anchorRef).getOrElse(fail("fixture: anchor not in view"))
    (u, s, n)

  private def coords: CellCoordinates =
    CellCoordinates
      .of(result, AnnaFixture.recall, view, someUnit.id, someState)
      .fold(e => fail(e.message), identity)

  test("a cell's coordinates point at exact words on both sides") {
    val c = coords
    assertEquals(c.unit, someUnit.id)
    assertEquals(c.node, someNode.ref)
    assertEquals(c.unitText, someUnit.text)
    // Exact spans, not a hull: a unit assembled from distant mentions keeps all of them, which is
    // why SpanSet is discontinuous in the first place.
    assertEquals(c.unitSpan.refs.toVector, someUnit.span.refs.toVector)
    assertEquals(c.sourceSupport.refs.toVector, someNode.support.refs.toVector)
    assertEquals(c.mode, result.costs(someUnit.id)(someState).mode)
    assertEquals(c.reductions, result.costs(someUnit.id)(someState).reductions)
  }

  test("a recall graph that is not the result's cannot supply the words") {
    // collab's BLOCK. The earlier factory took the RecallUnit and checked its ID, then published
    // whatever TEXT came with it - so `unit.copy(text = ...)` kept the id, passed the check, and
    // produced a trace naming words the cell was never scored against. The unit is now LOOKED UP in
    // a graph proven against result.recallChecksum.
    //
    // THE FIRST VERSION OF THIS COURT WAS CONDITIONALLY VACUOUS AND MUTATION CAUGHT IT. It doctored
    // a unit's TEXT and accepted either outcome - refused at validation, or admitted with a
    // different checksum - on the reasoning that either closes the attack. But the text-at-span law
    // refuses the doctored graph at construction, so the test always took that branch and NEVER
    // reached the checksum: deleting the binding left it green. "Assert the outcome, not which
    // branch" reads as rigour and here it meant the assertion under test never ran.
    //
    // So this uses a graph that genuinely VALIDATES and is genuinely NOT the result's - one unit
    // dropped - which can only be refused by the checksum.
    // Same units - dropping one does not validate, the graph requires them all - but different
    // RELATIONS, which validates and changes the checksum.
    val other = RecallGraph
      .validated(
        AnnaFixture.recall.transcript,
        AnnaFixture.recall.atlas,
        AnnaFixture.recall.units,
        RecallRelations(Vector.empty, Vector.empty, Vector.empty, Vector.empty)
      )
      .toEither
      .fold(e => fail(s"fixture: the relation-free graph must validate, got $e"), identity)
    assertNotEquals(
      AlignWire.recallChecksum(other),
      result.recallChecksum,
      "fixture: the other graph must differ, or this court proves nothing"
    )
    assert(
      other.byId.contains(someUnit.id),
      "fixture: the unit must still be present to be looked up"
    )
    val bad = CellCoordinates.of(result, other, view, someUnit.id, someState)
    assert(bad.isLeft, "coordinates were produced from a graph the result never saw")
  }

  test("a source view that is not the result's cannot supply the spans") {
    // The same substitution one side over: NodeSummary.copy keeps the ref while replacing the
    // SUPPORT SPANS. The node is now looked up in a view proven against result.viewFingerprint.
    val doctored = InMemorySourceView(
      view.nodes.map(n =>
        if n.ref == someNode.ref then n.copy(support = view.nodes.head.support) else n
      ),
      view.edges,
      view.worldOrder,
      view.textLength
    )
    assertNotEquals(
      ViewFingerprint.of(doctored),
      result.viewFingerprint,
      "a substituted node produced an identical view fingerprint"
    )
    val bad = CellCoordinates.of(result, AnnaFixture.recall, doctored, someUnit.id, someState)
    assert(bad.isLeft, "coordinates were produced from a view the result never saw")
  }

  test("a unit the recall graph does not contain is refused") {
    val absent = storymodel4s.recall.RecallUnitId.unsafe("not-in-this-graph")
    val bad = CellCoordinates.of(result, AnnaFixture.recall, view, absent, someState)
    assert(bad.isLeft, "coordinates were produced for a unit outside the graph")
  }

  test("a cell the result never scored is refused") {
    // The lookup half. Every node is scored for every unit in this fixture, so the unscored cell is
    // a state whose anchor is real but which this unit was not scored at.
    // Searched across ALL units rather than assumed of one: an earlier version fixed on someUnit
    // and failed its own precondition, because every node is scored for that unit.
    val pair = AnnaFixture.recall.units.view
      .flatMap(u =>
        view.nodes.view
          .map(n => (u.id, AlignState.Source(n.ref)))
          .filterNot((id, st) => result.costs.getOrElse(id, Map.empty).contains(st))
      )
      .headOption
      .getOrElse(fail("fixture: every (unit, node) pair is scored; cannot court the lookup"))
    val bad = CellCoordinates.of(result, AnnaFixture.recall, view, pair._1, pair._2)
    assert(bad.isLeft, "coordinates were produced for a cell with no scored breakdown")
  }

  // The product-door court lives OUTSIDE this package, in
  // storymodel4s.probes.CellCoordinatesUnforgeableSuite. The boundary is `private[align]`, so it
  // does not bite in here - an in-package probe calls the constructor legitimately and reports
  // "public apply is available" against a correctly sealed type. That is what the first version of
  // this suite did. A court has to stand where the wall is.

  test("the coordinates carry no term status, because the record cannot support one") {
    // A guard on scope. The four-way Measured/Absent/Imputed/Ineligible view is NOT derivable
    // today, on ONE ground: eligibility is computed inside cost() and thrown away, so Ineligible
    // cannot be recovered even as a complement.
    //
    // THIS COMMENT USED TO GIVE A SECOND GROUND THAT WAS ALREADY FALSE: "the provider's
    // MissingReason is discarded before the breakdown exists". 0ca09c5 landed imputedTerms twelve
    // hours before this suite was written. The test still passes and passed for a REASON THAT HAD
    // EXPIRED, which is worse than failing - a green test citing a closed defect is how the defect
    // gets re-argued as a constraint later.
    assert(
      scala.compiletime.testing.typeChecks("coords.facets"),
      "probe context cannot see the value"
    )
    assert(
      !scala.compiletime.testing.typeChecks("coords.termStatus(CostTerm.Semantic)"),
      "a term classifier was added before the eligibility carrier exists"
    )
    // The specific confusion a classifier must not paper over: on a chartless cell Chart is BOTH
    // recorded missing AND excluded from eligibility, so "absent" and "never had this dimension"
    // are the same bytes today.
    val b = result.costs(someUnit.id)(someState)
    assert(b.missingTerms.contains(CostTerm.Chart), "fixture: this cell must record Chart missing")
    assert(!b.terms.contains(CostTerm.Chart), "fixture: Chart must be absent from terms")
  }
