package storymodel4s.laws

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*

class MappingContractSuite extends FunSuite:
  private def target(id: String): SourceNodeRef = SourceNodeRef.Situation(SituationId.unsafe(id))
  private def outcome(record: MappingResult, id: String): UnitOutcome =
    record.outcome(RecallUnitId.unsafe(id)).get

  test("public miniature accounts for every packet and unassigned word") {
    val record = MappingMiniature.record
    assertEquals(record.outcomes.map(_.unit.value), MappingMiniature.packets.map(_.id))
    assertEquals(
      record.inventory.membership.values.count(_.isInstanceOf[WordMembership.Unassigned]),
      7
    )
    assertEquals(record.inventory.membership.size, record.inventory.words.size)
    assert(
      record.inventory.units.forall(
        _.decomposition ==
          DecompositionStatus.NotAssessed(DecompositionReason.NoDecompositionDetector)
      )
    )
    assertEquals(record.derivation, DerivationSource.NoDerivedValues)
  }
  test("p6 retained and counted") {
    val record = MappingMiniature.record
    assertEquals(record.outcomes.size, 8)
    val failed = outcome(record, "p6")
    assert(failed.processing.isInstanceOf[ProcessingStatus.Failed])
    assertEquals(failed.localization, LocalizationStatus.NotComputed)
    assertEquals(failed.decision, None)
  }
  test("p5 remains external and p3 retains the authored disagreement") {
    val record = MappingMiniature.record
    assertEquals(outcome(record, "p5").localization, LocalizationStatus.Nonlocalizable)
    val decision = outcome(record, "p3").decision.get
    assertEquals(decision.rawArgmax, Some(Destination.Target(target("e3")) -> 0.9))
    assertEquals(decision.chosen, Some(Destination.Target(target("e7"))))
    assertEquals(decision.decodedMass.asInstanceOf[DecodedTargetMass.InCandidateSupport].value, 0.4)
    assert(decision.origin.isInstanceOf[DecisionOrigin.StructuredDecode])
  }
  test("p7 retains both tied alternatives and every authored link is unassessed") {
    val record = MappingMiniature.record
    assertEquals(
      outcome(record, "p7").links.map(_.destination).toSet,
      Set(Destination.Target(target("e3")), Destination.Target(target("e8")))
    )
    assert(
      record.outcomes
        .flatMap(_.links)
        .forall(l =>
          l.gate == GateOutcome.NotGated && l.fidelity.isInstanceOf[FidelityStatus.NotAssessed] &&
            l.termSupport.isInstanceOf[TermSupportStatus.NotComputed]
        )
    )
  }
  test("g1 is Partial, e4 unlocated and independent parts incomparable") {
    val source = MappingMiniature.record.source
    val group = source.target(SourceNodeRef.Segment(SegmentId.unsafe("g1"))).get
    assertEquals(
      group.supportCoverage.asInstanceOf[SupportCoverage.Partial].missing.toSortedSet.toSet,
      Set(target("e4"))
    )
    assert(
      source.target(target("e4")).get.sourceSupport.isInstanceOf[SourceSupportStatus.Unlocated]
    )
    val a = source.target(target("e2")).get.axisMembership.keys.head
    val b = source.target(target("e7")).get.axisMembership.keys.head
    assertEquals(source.order(a, b), PartOrder.Incomparable)
  }
  test("fresh public builds have the same content identity") {
    assertEquals(MappingMiniature.record.digest, MappingMiniature.record.digest)
  }
