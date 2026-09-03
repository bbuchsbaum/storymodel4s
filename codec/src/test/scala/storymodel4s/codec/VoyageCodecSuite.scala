package storymodel4s.codec

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
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.*

import VoyageCodecs.given

/** The voyage document round-trips through its checked constructors, and a broken join is refused
  * on the wire, not materialised.
  */
class VoyageCodecSuite extends FunSuite:
  private def ok[A](e: Either[DomainError, A]): A = e.fold(err => fail(err.message), identity)
  private def okA[A](e: Either[AlignError, A]): A = e.fold(err => fail(err.toString), identity)
  private def span(a: Double, b: Double) = ok(ClockSpan.of(a, b))
  private def secs(v: Double) = ok(Seconds.of(v))
  private val a = SourceNodeRef.Situation(SituationId.unsafe("a"))
  private val b = SourceNodeRef.Situation(SituationId.unsafe("b"))
  private val g = SourceNodeRef.Segment(SegmentId.unsafe("g1"))
  private val u1 = RecallUnitId.unsafe("u1")
  private val u2 = RecallUnitId.unsafe("u2")

  private val timeline = ok(
    SourceTimeline.of(
      Vector(
        SourceTimelineNode(a, 0, Some(1), span(0, 10), "a"),
        SourceTimelineNode(b, 0, Some(1), span(10, 20), "b"),
        SourceTimelineNode(g, 1, Some(1), span(0, 20), "group 1")
      ),
      Vector(SourceTimelineGroup(1, "group 1", span(0, 20)))
    )
  )
  private val units = Vector(
    VoyageUnit(u1, 0, "one", Some(secs(0.5)), Some(secs(1.25))),
    VoyageUnit(u2, 1, "two", None, None)
  )
  private val matrix = okA(
    AlignmentMatrix.of(
      Vector(
        okA(
          AlignmentRow.of(
            u1,
            Map(
              AlignState.Source(a) -> 0.6,
              AlignState.Source(b) -> 0.1,
              AlignState.External(ExternalState.Commentary) -> 0.3
            )
          )
        ),
        okA(AlignmentRow.of(u2, Map(AlignState.Source(b) -> 1.0)))
      )
    )
  )
  private val decisions = Vector(
    VoyageDecision(u1, Some(a), Some(1), AnchorOrigin.PosteriorArgmax),
    VoyageDecision(u2, Some(b), Some(1), AnchorOrigin.PosteriorArgmax)
  )
  private val coding =
    IndependentCoding("coding", Checksum.ofText("coding"), Vector(CodedInterval(span(0, 3), 1)))
  private val provenance = ok(
    ViewProvenance.of(
      Checksum.ofText("source"),
      None,
      ViewBasis.AlignmentRun,
      VoyageCompiler.compilerVersion,
      Checksum.ofText("config")
    )
  )
  private val document = RecallVoyageDocument(
    ok(RecallVoyageInput.of(units, matrix, timeline, decisions, Some(coding), secs(30))),
    provenance
  )

  test("a voyage document round-trips through its checked constructors"):
    val text = Canonical.encode(document)
    val back = Canonical.decode[RecallVoyageDocument](text).fold(e => fail(e.message), identity)
    assertEquals(Canonical.encode(back), text)
    assertEquals(back.input.units, units)
    assertEquals(back.input.decisions, decisions)
    assertEquals(back.input.coding, Some(coding))
    assertEquals(back.input.recallLength, secs(30))
    assertEquals(back.input.timeline.nodes, timeline.nodes)
    assertEquals(
      back.input.matrix.rows.map(r => (r.unit, r.mass)),
      matrix.rows.map(r => (r.unit, r.mass))
    )
    assertEquals(back.provenance, provenance)
    assert(Canonical.isFixedPoint(document))

  test("the decoded document compiles to the same twin as the original"):
    val text = Canonical.encode(document)
    val back = Canonical.decode[RecallVoyageDocument](text).fold(e => fail(e.message), identity)
    assertEquals(
      ok(back.compile(Set.empty)).textualTwin,
      ok(document.compile(Set.empty)).textualTwin
    )

  test("a document whose join does not hold is refused, naming the strand"):
    val text = Canonical.encode(document)
    val json = Canonical.parse(text).fold(e => fail(e.message), identity)
    val decisionsOff = json.hcursor
      .downField("decisions")
      .withFocus(_.mapArray(_.reverse))
      .top
      .get
    val refused = Canonical.decodeJson[RecallVoyageDocument](decisionsOff)
    assert(refused.isLeft)
    assert(refused.left.exists(_.message.contains("decisions")))
    val badSchema = json.hcursor.downField("schema").set(io.circe.Json.fromString("x")).top.get
    assert(Canonical.decodeJson[RecallVoyageDocument](badSchema).isLeft)
