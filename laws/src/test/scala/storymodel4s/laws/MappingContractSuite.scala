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

  private object MultipartConsumer:
    val base = MappingMiniature.record
    val parts = base.source.bundles.map(_.asInstanceOf[BundleEntry.Media].bundle)
    val a = parts.head
    val streams = parts.toVector.flatMap(_.streams)
    val edition = EditionId.unsafe("external-consumer-composition")
    val axis = SourceBundle
      .editionPlaybackAxis(
        edition,
        streams,
        streams.map(_.id),
        0L,
        80L,
        RationalTimebase.of(1L, 10L).toOption.get
      )
      .toOption
      .get
    val receipt = SourceDerivationReceipt
      .of("external-consumer/v1", "a then b", parts.toVector.map(_.identity))
      .toOption
      .get
    val mappings = parts.toVector.zipWithIndex.map { (part, index) =>
      val segment = CompositionSegment
        .of(
          PlaybackInterval.on(part.primaryAxis, 0L, 40L).toOption.get,
          PlaybackInterval.on(axis, index * 40L, (index + 1) * 40L).toOption.get,
          OccurrenceId.unsafe(s"occurrence-$index")
        )
        .toOption
        .get
      TrackComposition.of(part.primaryAxis.id, axis.id, Vector(segment), receipt).toOption.get
    }
    val composed = SourceBundle
      .of(Some(edition), SourceKind.FilmEdition, streams, axis, streams.map(_.id), mappings)
      .toOption
      .get
    val composition = DeclaredComposition.of(composed, parts).toOption.get
    val ref = target("consumer-cross-part")
    val node = NodeSummary(
      ref,
      0,
      None,
      0,
      SpanSet.one(TextSpan.unsafe(0, 1)),
      None,
      Vector.empty,
      ContextTag.NarratedWorld,
      PolarityTag.Unknown,
      ModalityTag.Unknown,
      Vector.empty,
      Set.empty
    )
    val view = InMemorySourceView(Vector(node), Map.empty, None, 1)
    val anchors = parts.toVector.map(part =>
      EvidenceAnchor.MediaTime(
        composed.id,
        part.streams.head.id,
        part.primaryAxis.id,
        PlaybackIntervalSet.one(PlaybackInterval.on(part.primaryAxis, 0L, 10L).toOption.get)
      )
    )
    val support = SourceSupportStatus.located(
      TypedSupport.Anchored(EvidenceSupport.of(composed, anchors).toOption.get)
    )
    val physical = Map(ref -> support)
    def checked = SourceRepresentation.of(view, base.source.bundles, Some(composition), physical)

  test("external consumer refuses foreign axes and admits their declared composition") {
    import cats.data.NonEmptyVector
    val f = MultipartConsumer
    val refused = SourceRepresentation.of(
      f.view,
      NonEmptyVector.one(BundleEntry.media(f.composed)),
      None,
      f.physical
    )
    refused match
      case Left(MappingRefusal.ForeignAxis(axis)) =>
        assert(f.parts.toVector.map(_.primaryAxis.id).contains(axis))
      case other => fail(s"expected foreign-axis refusal, got $other")
    assert(f.checked.isRight)
  }
  test("external consumer refuses ambiguous axes") {
    import cats.data.NonEmptyVector
    val f = MultipartConsumer
    val physical = Map(f.ref -> SourceSupportStatus.unlocated(UnlocatedReason.NoLocusInSource))
    val refused = SourceRepresentation.of(
      f.view,
      NonEmptyVector.of(BundleEntry.media(f.a), BundleEntry.media(f.a)),
      None,
      physical
    )
    assertEquals(refused.left.toOption, Some(MappingRefusal.AmbiguousAxis(f.a.primaryAxis.id)))
    assert(
      SourceRepresentation
        .of(f.view, NonEmptyVector.one(BundleEntry.media(f.a)), None, physical)
        .isRight
    )
  }
  test("external consumer refuses undeclared cross-part support") {
    import cats.data.NonEmptyVector
    val f = MultipartConsumer
    val listed =
      NonEmptyVector.fromVector(f.base.source.bundles.toVector :+ BundleEntry.media(f.composed)).get
    val refused = SourceRepresentation.of(f.view, listed, None, f.physical)
    assertEquals(refused.left.toOption, Some(MappingRefusal.CrossPartWithoutComposition(f.ref)))
    assert(f.checked.isRight)
  }
  test("external consumer refuses duplicate targets") {
    val f = MultipartConsumer
    val refused = SourceRepresentation.of(
      f.view.copy(nodes = Vector(f.node, f.node)),
      f.base.source.bundles,
      Some(f.composition),
      f.physical
    )
    assertEquals(refused.left.toOption, Some(MappingRefusal.DuplicateTarget(f.ref)))
    assert(f.checked.isRight)
  }
  test("external consumer refuses omitted failed units") {
    val record = MappingMiniature.record
    val omitted = record.outcomes.filterNot(_.unit.value == "p6")
    assertEquals(
      MappingResult
        .checked(
          record.inventory,
          record.source,
          record.policies,
          record.roles,
          record.ledger,
          omitted
        )
        .left
        .toOption,
      Some(MappingRefusal.OutcomeInventoryMismatch)
    )
  }
