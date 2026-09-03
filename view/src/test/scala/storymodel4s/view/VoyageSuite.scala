package storymodel4s.view

import munit.FunSuite
import storymodel4s.align.{
  AlignError,
  AlignState,
  AlignmentMatrix,
  AlignmentRow,
  ExternalState,
  SourceNodeRef
}
import storymodel4s.core.*
import storymodel4s.recall.{RecallRef, RecallUnitId}

/** Fixture-free Recall Voyage courts: the contract, the proven join, the evidence law, the twin. */
class VoyageSuite extends FunSuite:
  private def ok[A](e: Either[DomainError, A]): A = e.fold(err => fail(err.message), identity)
  private def okA[A](e: Either[AlignError, A]): A = e.fold(err => fail(err.toString), identity)
  private def sit(id: String) = SourceNodeRef.Situation(SituationId.unsafe(id))
  private def seg(id: String) = SourceNodeRef.Segment(SegmentId.unsafe(id))
  private def span(a: Double, b: Double) = ok(ClockSpan.of(a, b))
  private def secs(v: Double) = ok(Seconds.of(v))
  private val a = sit("a")
  private val b = sit("b")
  private val c = sit("c")
  private val g1 = seg("g1")
  private val g2 = seg("g2")
  private val u1 = RecallUnitId.unsafe("u1")
  private val u2 = RecallUnitId.unsafe("u2")
  private val u3 = RecallUnitId.unsafe("u3")

  private val timeline = ok(
    SourceTimeline.of(
      Vector(
        SourceTimelineNode(a, 0, Some(1), span(0, 10), "a"),
        SourceTimelineNode(b, 0, Some(1), span(10, 20), "b"),
        SourceTimelineNode(c, 0, Some(2), span(20, 30), "c"),
        SourceTimelineNode(g1, 1, Some(1), span(0, 20), "group 1"),
        SourceTimelineNode(g2, 1, Some(2), span(20, 30), "group 2")
      ),
      Vector(
        SourceTimelineGroup(1, "group 1", span(0, 20)),
        SourceTimelineGroup(2, "group 2", span(20, 30))
      )
    )
  )
  private val units = Vector(
    VoyageUnit(u1, 0, "first", Some(secs(1.0)), Some(secs(2.0))),
    VoyageUnit(u2, 1, "second", Some(secs(5.0)), None),
    VoyageUnit(u3, 2, "third", None, None)
  )
  private val rows = okA(
    AlignmentMatrix.of(
      Vector(
        okA(
          AlignmentRow.of(
            u1,
            Map(
              AlignState.Source(a) -> 0.5,
              AlignState.Source(b) -> 0.2,
              AlignState.External(ExternalState.Association) -> 0.3
            )
          )
        ),
        okA(AlignmentRow.of(u2, Map(AlignState.External(ExternalState.Unranked) -> 1.0))),
        okA(AlignmentRow.of(u3, Map(AlignState.Source(c) -> 1.0)))
      )
    )
  )
  private def decisions(first: VoyageDecision) = Vector(
    first,
    VoyageDecision(u2, None, None, AnchorOrigin.PosteriorArgmax),
    VoyageDecision(u3, Some(c), Some(2), AnchorOrigin.PosteriorArgmax)
  )
  private val argmaxFirst = VoyageDecision(u1, Some(a), Some(1), AnchorOrigin.PosteriorArgmax)
  private val provenance = ok(
    ViewProvenance.of(
      Checksum.ofText("source"),
      None,
      ViewBasis.AlignmentRun,
      VoyageCompiler.compilerVersion,
      Checksum.ofText("config")
    )
  )
  private def input(first: VoyageDecision = argmaxFirst, coding: Option[IndependentCoding] = None) =
    RecallVoyageInput.of(units, rows, timeline, decisions(first), coding, secs(60.0))
  private val unitAddress = Addressable[RecallRef].address(RecallRef.Unit(u1))

  test("the Recall Voyage contract puts clocks on both axes and anchor mass in area"):
    val k = ProjectionContractVoyage.recallVoyage
    assertEquals(k.kind, ProjectionKind.RecallVoyage)
    assertEquals(k.x, AxisMeaning.RecallClock)
    assertEquals(k.y, AxisMeaning.SourceClock)
    assertEquals(k.area, Some(MeasureMeaning.AnchorMass))
    assertEquals(k.distance, DistanceMeaning.NoMeaning)
    assert(k.invariants.contains(VisualInvariant.EvidenceBacked))
    assert(k.invariants.contains(VisualInvariant.AbsenceIsMarked))
    assert(k.invariants.contains(VisualInvariant.EpistemicChannelIsNonColour))
    assert(!k.invariants.contains(VisualInvariant.HorizonShared))

  test("an aligner run is a basis that needs no model receipt"):
    assertEquals(provenance.basis, ViewBasis.AlignmentRun)
    assert(provenance.draft.isEmpty)

  test("the join is proven: rows, decisions, timeline and origins must agree"):
    assert(input().isRight)
    val wrongOrder =
      RecallVoyageInput.of(units.reverse, rows, timeline, decisions(argmaxFirst), None, secs(60))
    assert(wrongOrder.isLeft, "rows must be the units in order")
    val offTimeline = ok(
      SourceTimeline.of(timeline.nodes.filterNot(_.ref == b), timeline.groups)
    )
    assert(
      RecallVoyageInput.of(units, rows, offTimeline, decisions(argmaxFirst), None, secs(60)).isLeft
    )
    val filledWithMass = VoyageDecision(u1, Some(a), Some(1), AnchorOrigin.DecodeFilled)
    assert(input(filledWithMass).isLeft, "a filled anchor carries no posterior mass")
    val boundWithoutMass = VoyageDecision(u1, Some(c), Some(2), AnchorOrigin.DecodeBound)
    assert(input(boundWithoutMass).isLeft, "a bound anchor carries posterior mass")
    val undeclaredGroup = VoyageDecision(u1, Some(a), Some(9), AnchorOrigin.PosteriorArgmax)
    assert(input(undeclaredGroup).isLeft)
    val lateUnit = units.updated(0, units(0).copy(onset = Some(secs(61.0))))
    assert(
      RecallVoyageInput.of(lateUnit, rows, timeline, decisions(argmaxFirst), None, secs(60)).isLeft
    )

  test("compile draws the row's own numbers: anchor, alternatives by rank, absences"):
    val scene = ok(VoyageCompiler.compile(ok(input()), Set(unitAddress), provenance))
    val anchors = scene.marks.collect { case m: VoyageMark.UnitAnchor => m }
    assertEquals(anchors.map(_.unit), Vector(u1), "u3 has no onset and is untimed, not anchored")
    val first = anchors.head
    assertEquals(first.anchor, a)
    assertEquals(first.mass, 0.5)
    assertEquals(first.sourceMass, 0.7)
    assertEquals(first.externalMass, 0.3)
    assertEquals(first.origin, AnchorOrigin.PosteriorArgmax)
    assertEquals(first.span, span(0, 10))
    assertEquals(first.group, Some(1))
    val alternatives = scene.marks.collect { case m: VoyageMark.Alternative if m.unit == u1 => m }
    assertEquals(alternatives.map(m => (m.rank, m.anchor, m.mass)), Vector((1, b, 0.2)))
    assert(scene.marks.exists {
      case VoyageMark.Unanchored(_, `u2`, _, at, ext) => at == secs(5.0) && ext == 1.0;
      case _                                          => false
    })
    assert(scene.marks.exists { case VoyageMark.Untimed(_, `u3`, 2) => true; case _ => false })
    assertEquals(
      scene.selectionPlacements.get(unitAddress).map(_.isInstanceOf[SelectionPlacement.OnMark[?]]),
      Some(true)
    )

  test("a decode-filled anchor is drawn with mass zero and its argmax kept beside it"):
    val filled = VoyageDecision(u1, Some(c), Some(2), AnchorOrigin.DecodeFilled)
    val scene = ok(VoyageCompiler.compile(ok(input(filled)), Set.empty, provenance))
    val first = scene.marks.collectFirst { case m: VoyageMark.UnitAnchor if m.unit == u1 => m }.get
    assertEquals(first.anchor, c)
    assertEquals(first.mass, 0.0)
    assertEquals(first.origin, AnchorOrigin.DecodeFilled)
    assertEquals(first.argmax, Some(a))
    val alternatives = scene.marks.collect { case m: VoyageMark.Alternative if m.unit == u1 => m }
    assertEquals(alternatives.map(m => (m.rank, m.anchor)), Vector((1, a), (2, b)))

  test("court: a forged mass is refused by the evidence law"):
    val in = ok(input())
    val scene = ok(VoyageCompiler.compile(in, Set.empty, provenance))
    val genuine = scene.marks.collectFirst {
      case m: VoyageMark.UnitAnchor if m.unit == u1 => m
    }.get
    assert(VoyageCompiler.checkEvidence(in, genuine).isRight)
    assert(VoyageCompiler.checkEvidence(in, genuine.copy(mass = genuine.mass + 0.1)).isLeft)
    assert(VoyageCompiler.checkEvidence(in, genuine.copy(origin = AnchorOrigin.DecodeBound)).isLeft)
    assert(VoyageCompiler.checkEvidence(in, genuine.copy(span = span(0, 11))).isLeft)
    val alt = scene.marks.collectFirst { case m: VoyageMark.Alternative if m.unit == u1 => m }.get
    assert(VoyageCompiler.checkEvidence(in, alt.copy(mass = 0.21)).isLeft)

  test("court: duplicate mark identities are refused"):
    val scene = ok(VoyageCompiler.compile(ok(input()), Set.empty, provenance))
    val m = scene.marks.head
    assert(VoyageNavigation.from(Vector(m, m)).isLeft)

  test("a selected address off this projection is placed honestly"):
    val foreign = Addressable[CoreRef].address(CoreRef.Claim(ClaimId.unsafe("c1")))
    val scene = ok(VoyageCompiler.compile(ok(input()), Set(foreign), provenance))
    assertEquals(scene.selectionPlacements.get(foreign), Some(SelectionPlacement.OffProjection))

  test("the coding must name declared groups and rides beside the marks"):
    val coding =
      IndependentCoding("coding", Checksum.ofText("coding"), Vector(CodedInterval(span(0, 4), 1)))
    val scene = ok(VoyageCompiler.compile(ok(input(coding = Some(coding))), Set.empty, provenance))
    assertEquals(scene.coding.map(_.intervals.size), Some(1))
    assertEquals(scene.codedGroupAt(secs(1.0)), Some(1))
    assertEquals(scene.codedGroupAt(secs(9.0)), None)
    assertEquals(scene.summary, VoyageSummary(3, 1, 1, 1, 1, 0, 0, 0, Some((1, 1))))
    assert(
      scene.textualTwin.contains(
        "Summary: 3 units, 1 anchored (1 argmax, 0 decode-bound, 0 decode-filled), 1 unanchored, " +
          "1 untimed, 0 external-dominant; coded group agreement 1/1"
      )
    )
    val bad =
      IndependentCoding("coding", Checksum.ofText("coding"), Vector(CodedInterval(span(0, 4), 7)))
    assert(input(coding = Some(bad)).isLeft)

  test("the textual twin is deterministic, total, and carries no recall prose"):
    val scene = ok(VoyageCompiler.compile(ok(input()), Set(unitAddress), provenance))
    val twin = scene.textualTwin
    assertEquals(twin, scene.textualTwin)
    assert(twin.startsWith("Recall Voyage — RecallVoyage\n"))
    assert(twin.contains("Basis: aligner run, raw posterior\n"))
    assert(
      twin.contains(
        "voyage/anchor/u1 unit 0 at 1.0 -> sit:a level 0 group 1 span 0.0..10.0 mass 0.5000"
      )
    )
    assert(twin.contains("voyage/none/u2 unit 1 at 5.0 unanchored, external 1.0000"))
    assert(twin.contains("voyage/untimed/u3 unit 2 untimed"))
    assert(!twin.contains("first") && !twin.contains("second"))

  test("court: the law reads every field, not only the masses"):
    val in = ok(input())
    val scene = ok(VoyageCompiler.compile(in, Set.empty, provenance))
    val genuine = scene.marks.collectFirst {
      case m: VoyageMark.UnitAnchor if m.unit == u1 => m
    }.get
    assert(VoyageCompiler.checkEvidence(in, genuine.copy(at = secs(50.0))).isLeft, "at")
    assert(VoyageCompiler.checkEvidence(in, genuine.copy(unitOrdinal = 9)).isLeft, "ordinal")
    assert(VoyageCompiler.checkEvidence(in, genuine.copy(externalDominant = true)).isLeft, "hollow")
    assert(VoyageCompiler.checkEvidence(in, genuine.copy(argmax = Some(b))).isLeft, "argmax")
    val alt = scene.marks.collectFirst { case m: VoyageMark.Alternative if m.unit == u1 => m }.get
    assert(VoyageCompiler.checkEvidence(in, alt.copy(rank = 7)).isLeft, "rank")
    assert(VoyageCompiler.checkEvidence(in, alt.copy(mass = -1.0)).isLeft, "sentinel")
    assert(
      VoyageCompiler
        .checkEvidence(in, alt.copy(anchor = c, span = span(20, 30), group = Some(2)))
        .isLeft,
      "massless ref"
    )
    val none = scene.marks.collectFirst { case m: VoyageMark.Unanchored => m }.get
    assert(VoyageCompiler.checkEvidence(in, none.copy(at = secs(6.0))).isLeft, "unanchored at")
    val untimed = scene.marks.collectFirst { case m: VoyageMark.Untimed => m }.get
    assert(
      VoyageCompiler.checkEvidence(in, untimed.copy(unitOrdinal = 5)).isLeft,
      "untimed ordinal"
    )

  test("the join refuses what the origin law, the coding and the clocks cannot hold"):
    val boundIsArgmax = VoyageDecision(u1, Some(a), Some(1), AnchorOrigin.DecodeBound)
    assert(input(boundIsArgmax).isLeft, "a bound anchor equal to the argmax is the argmax")
    val wrongGroup = VoyageDecision(u1, Some(a), Some(2), AnchorOrigin.PosteriorArgmax)
    assert(input(wrongGroup).isLeft, "the decided group must be the anchor's")
    val overlap = IndependentCoding(
      "coding",
      Checksum.ofText("coding"),
      Vector(CodedInterval(span(0, 4), 1), CodedInterval(span(3, 6), 2))
    )
    assert(input(coding = Some(overlap)).isLeft, "overlapping coded intervals")
    val late =
      IndependentCoding("coding", Checksum.ofText("coding"), Vector(CodedInterval(span(0, 61), 1)))
    assert(
      input(coding = Some(late)).isRight,
      "a coding may run past the last word; renderers clip"
    )
    val reversed = units.updated(0, units(0).copy(lastWordOnset = Some(secs(0.5))))
    assert(
      RecallVoyageInput.of(reversed, rows, timeline, decisions(argmaxFirst), None, secs(60)).isLeft
    )
    val disordered = units.updated(1, units(1).copy(ordinal = 0))
    assert(
      RecallVoyageInput
        .of(disordered, rows, timeline, decisions(argmaxFirst), None, secs(60))
        .isLeft
    )

  test("an external-dominant unit is a fact about the row's two sums, carried on the mark"):
    val heavy = okA(
      AlignmentMatrix.of(
        Vector(
          okA(
            AlignmentRow.of(
              u1,
              Map(AlignState.Source(a) -> 0.2, AlignState.External(ExternalState.Intrusion) -> 0.8)
            )
          ),
          rows.rows(1),
          rows.rows(2)
        )
      )
    )
    val in =
      ok(RecallVoyageInput.of(units, heavy, timeline, decisions(argmaxFirst), None, secs(60)))
    val scene = ok(VoyageCompiler.compile(in, Set.empty, provenance))
    val first = scene.marks.collectFirst { case m: VoyageMark.UnitAnchor if m.unit == u1 => m }.get
    assert(first.externalDominant)
    assertEquals(scene.summary.externalDominant, 1)
    assert(scene.textualTwin.contains("external-dominant posterior argmax"))
