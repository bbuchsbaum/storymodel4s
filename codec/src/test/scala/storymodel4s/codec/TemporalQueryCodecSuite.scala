package storymodel4s.codec

import io.circe.Json
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.laws.MappingMiniature

object TemporalQueryFixture:
  import TemporalQuery.*
  def mapping: MappingResult =
    val m = MappingMiniature.record
    val prior = ReferencePriorId.unsafe("synthetic-query/v1")
    val policies = MappingPolicies
      .of(
        m.policies.inference,
        m.policies.context,
        m.policies.candidate,
        ReferencePrior.Declared(prior),
        m.policies.decision,
        m.policies.universe
      )
      .toOption
      .get
    val outcomes = m.outcomes.map { o =>
      if o.processing != ProcessingStatus.Complete then o
      else
        // Authored values, not normalization of arbitrary raw scores.
        val weights = Map[Destination, Double](
          Destination.Target(SourceNodeRef.Situation(SituationId.unsafe("e2"))) -> 0.5,
          Destination.Target(SourceNodeRef.Situation(SituationId.unsafe("e4"))) -> 0.25,
          Destination.External(ExternalState.Intrusion) -> 0.25
        )
        val stages = o.stages.get
        val normalized = NormalizedScoreMass
          .of(m.policies.universe.id, prior, 1.0, weights, m.ledger.at(Stage.Scoring).head.id)
          .toOption
          .get
        val measures = UnitMeasures.of(Vector.empty, Some(normalized), None, None).toOption.get
        val candidates = CandidateSetId.of(stages.candidates, o.unit, weights.keySet)
        val links = weights.keys.toVector.map(MappingLink.ungated(_, stages, candidates))
        UnitOutcome
          .computed(
            o.unit,
            measures,
            links,
            DecisionBasis.of(MeasureKind.NormalizedScoreMass, None).toOption.get,
            DecisionRequest.RawArgmax,
            stages
          )
          .toOption
          .get
    }
    MappingResult.checked(m.inventory, m.source, policies, m.roles, m.ledger, outcomes).toOption.get
  def readout(m: MappingResult = mapping): Readout =
    val bundle = m.source.bundles.head.asInstanceOf[BundleEntry.Media].bundle
    val p = prepare(
      m,
      m.inventory.units.head.id,
      Measure.NormalizedScoreMass,
      TemporalSupport.Selection.Part(bundle.identity),
      Vector("e2", "e4").map(id =>
        Declaration(
          SourceNodeRef.Situation(SituationId.unsafe(id)),
          Assumption.SuppliedSupportContainsReferent,
          Allocation.UniformIntervals
        )
      )
    ).toOption.get
    p.query(Region.on(bundle.primaryAxis, Vector(10L -> 15L), Vector.empty).toOption.get)
      .toOption
      .get
  def main(args: Array[String]): Unit =
    require(args.isEmpty, "no arguments")
    println("TEMPORAL_QUERY_FIXTURE\t" + TemporalQueryCodecs.encode(readout()))

class TemporalQueryCodecSuite extends FunSuite:
  test("mixture policies roundtrip canonical bits and have portable identity witnesses") {
    import TemporalQuery.*
    val m = TemporalQueryFixture.mapping
    val bundle = m.source.bundles.head.asInstanceOf[BundleEntry.Media].bundle
    val ref = SourceNodeRef.Situation(SituationId.unsafe("e2"))
    val digests = Vector(0.0, -0.0, 0.5, 1.0).map { share =>
      val p = prepare(
        m,
        m.inventory.units.head.id,
        Measure.NormalizedScoreMass,
        TemporalSupport.Selection.Part(bundle.identity),
        Vector(
          Declaration(ref, Assumption.SuppliedSupportContainsReferent, Allocation.Mixture(share))
        )
      ).toOption.get
      val r = p
        .query(Region.on(bundle.primaryAxis, Vector(10L -> 15L), Vector.empty).toOption.get)
        .toOption
        .get
      val text = TemporalQueryCodecs.encode(r)
      assertEquals(
        TemporalQueryCodecs.encode(TemporalQueryCodecs.decode(text, m).toOption.get),
        text
      )
      // Still no point support: a policy label alone cannot create a compatible kernel.
      assertEquals(p.ledger.supportWithoutAllocation, 0.5)
      p.digest.hex
    }
    assertEquals(
      digests,
      Vector(
        "57fc4ac90ca8d20a101ef73357dcf4b57a6f9c48f425e5e8968fef936d97eb05",
        "136c931359482d37f576794871d64d948610fa41938e7a239697cac0f01a4c72",
        "cdd89abad92e3da2be5a547be254e52282defe245d9d72608e52e4b1f0274ef5",
        "92424fb9258d90e67032b788f970d81ad0bc705d32926829d1cd488049d910d2"
      )
    )
    println("TEMPORAL_QUERY_MIXTURE_DIGESTS\t" + digests.mkString("\t"))
  }
  test(
    "real checked posterior states survive query and contextual codec without target conversion"
  ) {
    import TemporalQuery.*
    val f = MappingCodecFixture
    val bundle = MappingCompositionFixture.a
    val physical = f.refs.zipWithIndex.map { (ref, i) =>
      val anchor = EvidenceAnchor.MediaTime(
        bundle.id,
        bundle.streams.head.id,
        bundle.primaryAxis.id,
        PlaybackIntervalSet.one(
          PlaybackInterval.on(bundle.primaryAxis, i * 10L, (i + 1) * 10L).toOption.get
        )
      )
      ref -> SourceSupportStatus.located(
        TypedSupport.Anchored(EvidenceSupport.of(bundle, Vector(anchor)).toOption.get)
      )
    }.toMap
    val source = SourceRepresentation
      .of(f.view, cats.data.NonEmptyVector.one(BundleEntry.media(bundle)), None, physical)
      .toOption
      .get
    val m = f.record(source = source)
    val unit = m.inventory.units.head.id
    val expected = m.outcome(unit).get.measures.posterior.get.mass
    val p = prepare(
      m,
      unit,
      Measure.ModelPosterior,
      TemporalSupport.Selection.Part(bundle.identity),
      expected.keys
        .flatMap(_.anchor)
        .toVector
        .distinct
        .map(ref =>
          Declaration(ref, Assumption.SuppliedSupportContainsReferent, Allocation.UniformIntervals)
        )
    ).toOption.get
    assertEquals(
      p.components.map(c => c.alternative -> c.weight).toMap,
      expected.map((s, w) => (Alternative.Posterior(s): Alternative) -> w)
    )
    val result = p
      .query(Region.on(bundle.primaryAxis, Vector(0L -> 40L), Vector.empty).toOption.get)
      .toOption
      .get
    assertEqualsDouble(result.allocatedRegionMass, expected.filter(_._1.isSource).values.sum, 1e-15)
    val text = TemporalQueryCodecs.encode(result)
    assertEquals(TemporalQueryCodecs.encode(TemporalQueryCodecs.decode(text, m).toOption.get), text)
  }
  test("contextual roundtrip reexecutes known mass with candidate and unavailable ledgers") {
    val m = TemporalQueryFixture.mapping
    val value = TemporalQueryFixture.readout(m)
    val text = TemporalQueryCodecs.encode(value)
    assertEquals(value.allocatedRegionMass, 0.25)
    val decoded = TemporalQueryCodecs.decode(text, m).toOption.get
    assertEquals(TemporalQueryCodecs.encode(decoded), text)
    assertEquals(decoded.prepared.ledger.unavailableLocation, 0.25)
    assertEquals(decoded.prepared.ledger.external, Vector(ExternalState.Intrusion -> 0.25))
    assert(TemporalQueryCodecs.decode(text, MappingMiniature.record).isLeft)
  }
  test("wire mass, domains, axis, unknown fields and duplicate keys cannot fabricate authority") {
    val m = TemporalQueryFixture.mapping
    val json = TemporalQueryCodecs.toJson(TemporalQueryFixture.readout(m))
    val obj = json.asObject.get
    Vector(
      Json.fromJsonObject(obj.add("allocated_region_mass", Json.fromString("1.0"))),
      Json.fromJsonObject(obj.add("domains", Json.arr())),
      Json.fromJsonObject(obj.add("axis", Json.Null)),
      Json.fromJsonObject(obj.add("unexpected", Json.fromBoolean(true)))
    ).foreach(j => assert(TemporalQueryCodecs.decode(j.noSpaces, m).isLeft))
    val text = TemporalQueryCodecs.encode(TemporalQueryFixture.readout(m))
    assert(TemporalQueryCodecs.decode("{\"digest\":\"wrong\"," + text.drop(1), m).isLeft)
    assert(
      TemporalQueryCodecs
        .decode(text.replace("\"start_tick\":\"10\"", "\"start_tick\":10"), m)
        .isLeft
    )
    assert(
      TemporalQueryCodecs
        .decode(text.replace("SuppliedSupportContainsReferent", "EmpiricallyCalibrated"), m)
        .isLeft
    )
  }
