package storymodel4s.interview

import cats.data.NonEmptyVector
import munit.FunSuite

import storymodel4s.core.*
import storymodel4s.features.{Coverage, Estimate, MissingReason}
import storymodel4s.interview.scoring.*
import storymodel4s.recall.*

/** Unit-level phenomenology laws: atomization density must not change any strand (contract 7). */
class InterviewPhenomenologySuite extends FunSuite:

  private def unit(text: String): RecallUnit =
    val src = StorySource.fromText(text).toOption.get
    RecallSegmenter.segment(src).ordered.head

  private lazy val proto: Detail =
    AtomProjection.fromUnit(unit("We ate cake."), TurnId.unsafe("t")).head

  private def metaFor(d: Detail): ClaimMeta =
    ClaimMeta.unsafe(
      ClaimId.unsafe(s"c:${d.id.value}"),
      EpistemicStatus.Hypothesized,
      Credence.unsafeRaw(0.5, ScorerId.unsafe("test-scorer")),
      NonEmptyVector.one(
        Evidence(
          EvidenceId.unsafe(s"e:${d.id.value}"),
          Some(d.support),
          Set.empty,
          Fingerprint.unsafe("test"),
          StageId.unsafe("s")
        )
      ),
      Provenance.deterministic("test", Checksum.ofText("cfg"))
    )

  private def assessments(
      unitId: String,
      n: Int,
      firstPerson: Boolean,
      monitoring: Option[SourceMonitoring]
  ): Vector[DetailAssessment] =
    val exp = ExperientialEvidence(None, firstPerson, monitoring.toVector)
    (0 until n).toVector.map { i =>
      val d = proto.copy(
        id = DetailId.unsafe(s"$unitId:d$i"),
        sourceUnit = RecallUnitId.unsafe(unitId)
      )
      DetailAssessment(
        d,
        Distribution.point(MemoryAddress.Unresolved),
        Distribution.point(DetailFacet.Event),
        Estimate.observed(0.5),
        exp,
        EpistemicStatus.Hypothesized,
        PromptContext(InterviewPhase.FreeRecall, None),
        monitoring,
        metaFor(d)
      )
    }

  test("foil: same units and cues with different atom counts yield identical strands") {
    val sparse = assessments("fp", 1, firstPerson = true, Some(SourceMonitoring.DirectMemory)) ++
      assessments("tp", 1, firstPerson = false, None)
    val dense = assessments("fp", 5, firstPerson = true, Some(SourceMonitoring.DirectMemory)) ++
      assessments("tp", 1, firstPerson = false, None)
    val a = ProfileScoring.phenomenology(sparse, None, 2)
    val b = ProfileScoring.phenomenology(dense, None, 2)
    assertEquals(a.firstPersonRate, Estimate.observed(0.5))
    assertEquals(b.firstPersonRate, a.firstPersonRate)
    assertEquals(a.sourceMonitoring, Map(SourceMonitoring.DirectMemory -> 1))
    assertEquals(b.sourceMonitoring, a.sourceMonitoring)
    assertEquals(a.sourceMonitoringRate, Estimate.observed(0.5))
    assertEquals(b.sourceMonitoringRate, a.sourceMonitoringRate)
    assertEquals(a.firstPersonCoverage, Coverage.unsafe(2, 2))
    assertEquals(b.firstPersonCoverage, a.firstPersonCoverage)
    assertEquals(a.sourceMonitoringCoverage, a.firstPersonCoverage)
    assertEquals(a.explicitRating, None)
  }

  test("strands are invariant under duplicating the whole assessment vector") {
    val once = assessments("fp", 2, firstPerson = true, Some(SourceMonitoring.DirectMemory)) ++
      assessments("tp", 1, firstPerson = false, None)
    val twice = once ++ once
    val a = ProfileScoring.phenomenology(once, None, 2)
    val b = ProfileScoring.phenomenology(twice, None, 2)
    assertEquals(b.firstPersonRate, a.firstPersonRate)
    assertEquals(b.sourceMonitoring, a.sourceMonitoring)
    assertEquals(b.sourceMonitoringRate, a.sourceMonitoringRate)
    assertEquals(b.firstPersonCoverage, a.firstPersonCoverage)
  }

  test("sourceMonitoringUnitCounts counts unique source units, not details") {
    val many = assessments("mem", 4, firstPerson = true, Some(SourceMonitoring.DirectMemory))
    val ev = ProfileScoring.phenomenology(many, None, 1)
    assertEquals(ev.sourceMonitoringUnitCounts, Map(SourceMonitoring.DirectMemory -> 1))
    assertEquals(ev.sourceMonitoring, ev.sourceMonitoringUnitCounts)
    assertEquals(ev.sourceMonitoringRate, Estimate.observed(1.0))
    assertEquals(ev.firstPersonRate, Estimate.observed(1.0))
    assertEquals(ev.firstPersonCoverage, Coverage.unsafe(1, 1))
    assertEquals(ev.sourceMonitoringCoverage, Coverage.unsafe(1, 1))
  }

  test("a mixed-flag unit is first-person by OR, not by atom fraction") {
    val mixed = assessments("u", 1, firstPerson = true, None) ++
      assessments("u", 1, firstPerson = false, None)
    val ev = ProfileScoring.phenomenology(mixed, None, 1)
    assertEquals(ev.firstPersonRate, Estimate.observed(1.0))
    assertEquals(ev.firstPersonCoverage, Coverage.unsafe(1, 1))
    assertEquals(ev.sourceMonitoringUnitCounts, Map.empty)
  }

  test("empty assessments yield missing rates, not zero") {
    val ev = ProfileScoring.phenomenology(Vector.empty, None, 0)
    assertEquals(ev.firstPersonRate, Estimate.missing(MissingReason.Excluded))
    assertEquals(ev.sourceMonitoringRate, Estimate.missing(MissingReason.Excluded))
    assertEquals(ev.sourceMonitoringUnitCounts, Map.empty)
    assertEquals(ev.firstPersonCoverage, Coverage.empty)
    assertEquals(ev.sourceMonitoringCoverage, Coverage.empty)
  }

  test("empty assessments with participant units keep eligible coverage and missing rates") {
    val ev = ProfileScoring.phenomenology(Vector.empty, None, 40)
    assertEquals(ev.firstPersonRate, Estimate.missing(MissingReason.Excluded))
    assertEquals(ev.sourceMonitoringRate, Estimate.missing(MissingReason.Excluded))
    assertEquals(ev.firstPersonCoverage, Coverage.unsafe(40, 0))
    assertEquals(ev.sourceMonitoringCoverage, Coverage.unsafe(40, 0))
  }

  test("unassessed participant units lower coverage and leave rates unchanged") {
    val assessed = assessments("fp", 1, firstPerson = true, Some(SourceMonitoring.DirectMemory)) ++
      assessments("tp", 1, firstPerson = false, None)
    val tight = ProfileScoring.phenomenology(assessed, None, 2)
    val wide = ProfileScoring.phenomenology(assessed, None, 5)
    assertEquals(wide.firstPersonRate, tight.firstPersonRate)
    assertEquals(wide.sourceMonitoringRate, tight.sourceMonitoringRate)
    assertEquals(wide.sourceMonitoringUnitCounts, tight.sourceMonitoringUnitCounts)
    assertEquals(tight.firstPersonCoverage, Coverage.unsafe(2, 2))
    assertEquals(wide.firstPersonCoverage, Coverage.unsafe(5, 2))
    assertEquals(wide.sourceMonitoringCoverage, Coverage.unsafe(5, 2))
    assertEquals(tight.firstPersonRate, Estimate.observed(0.5))
    assertEquals(tight.sourceMonitoringRate, Estimate.observed(0.5))
  }
