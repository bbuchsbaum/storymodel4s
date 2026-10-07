package storymodel4s.codec

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.recall.*
import FeatureCodecs.given

/** Contextual wire proof must run the same canonical gate as strict evidence and inference. */
class StrictContentCodecSuite extends FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(s"$e"), identity)
  private val original = MappingCodecFixture.view
  private val recalled = MappingCodecFixture.recall
  private val segment = SourceNodeRef.Segment(storymodel4s.core.SegmentId.unsafe("strict-segment"))
  private val first = original.nodes.head.copy(
    parent = Some(segment),
    predicate = Some("run"),
    participants = Vector.empty,
    modality = ModalityTag.Intended,
    lemmas = Set("run")
  )
  private val second = original
    .nodes(1)
    .copy(
      parent = Some(segment),
      predicate = Some("run"),
      participants = Vector.empty,
      polarity = PolarityTag.Negative,
      lemmas = Set("run")
    )
  private val parent =
    first.copy(ref = segment, level = 1, parent = None, modality = ModalityTag.Asserted)
  private val view = original.copy(nodes = Vector(parent, first, second))
  private val recall = right(
    RecallGraph
      .validated(
        recalled.transcript,
        recalled.atlas,
        recalled.units
          .take(1)
          .map(
            _.copy(proposition =
              PropositionSketch.empty.copy(
                predicate = Some("run"),
                polarity = PolarityTag.Positive,
                modality = ModalityTag.Asserted,
                lemmas = Set("run")
              )
            )
          ),
        RecallRelations.empty
      )
      .toEither
  )
  private val config = right(StrictCandidateConfig.of(2, TieBudgetRequest.Unbounded, false, None))
  private val candidates = right(
    StrictCandidateGenerator.canonical(StrictSemanticChannel.Lexical, config, recall, view)
  )
  private val model = right(StrictCostModel.of(StrictSemanticChannel.Lexical))
  private val evidence = right(LocalEvidence.compute(recall, view, candidates, model))
  private val result = right(GraphHsmm.infer(recall, view, evidence, HsmmConfig.default))
  private val json = right(HsmmResultCodec.toJson(result))

  test("canonical inferred result roundtrips through v5 and the canonical contextual gate") {
    assertEquals(
      result.admissibility(recall.ordered.head.id)(segment).contradictions,
      Vector(Contradiction.PolarityConflict, Contradiction.ModalityConflict)
    )
    assertEquals(json.hcursor.get[String]("schemaVersion"), Right("hsmm/v5"))
    assertEquals(json.hcursor.get[String]("gateSemantics"), Right("CanonicalContent"))
    assertEquals(HsmmResultCodec.decodeJson(json, recall, view), Right(result))
    assertEquals(
      HsmmResultCodec.decode(right(HsmmResultCodec.encode(result)), recall, view),
      Right(result)
    )
  }

  test("canonical wire cannot erase, downgrade or invent its gate semantics") {
    val absent = json.mapObject(_.remove("gateSemantics"))
    val wrong = json.mapObject(_.add("gateSemantics", Json.fromString("Historical")))
    val v4WithClaim = json.mapObject(_.add("schemaVersion", Json.fromString("hsmm/v4")))
    Vector(absent, wrong, v4WithClaim).foreach(j =>
      assert(HsmmResultCodec.decodeJson(j, recall, view).isLeft)
    )
    val downgraded = v4WithClaim.mapObject(_.remove("gateSemantics"))
    val refused = HsmmResultCodec.decodeJson(downgraded, recall, view)
    assert(
      refused.left.exists {
        case HsmmCodecError.Rejected(_: AlignError.GateDrift) => true
        case _                                                => false
      },
      refused
    )
  }

  test("historical artifacts retain v4 and omit canonical claims") {
    val historical = right(HsmmResultCodec.toJson(MappingCodecFixture.result))
    assertEquals(historical.hcursor.get[String]("schemaVersion"), Right("hsmm/v4"))
    assert(!historical.hcursor.downField("gateSemantics").succeeded)
    assertEquals(
      HsmmResultCodec.decodeJson(historical, recalled, original),
      Right(MappingCodecFixture.result)
    )
  }

  test("unavailable, abstained, ineligible and observed zero remain distinct portable JSON") {
    val values: Vector[Estimate[Double]] = Vector(
      Estimate.missing(MissingReason.ChannelUnavailable),
      Estimate.missing(MissingReason.ProviderAbstained),
      Estimate.Ineligible,
      Estimate.observed(0.0)
    )
    val encoded = values.map(_.asJson)
    assertEquals(encoded.distinct.size, 4)
    assertEquals(MissingReason.ChannelUnavailable.asJson, Json.fromString("ChannelUnavailable"))
    values
      .zip(encoded)
      .foreach((value, wire) => assertEquals(wire.as[Estimate[Double]], Right(value)))
  }

  test(
    "all-unranked strict inference roundtrips; outcome provenance belongs to originating evidence"
  ) {
    val channels = Vector(
      StrictSemanticChannel.Unavailable,
      right(StrictSemanticChannel.ContentTable.of(Vector.empty)),
      right(StrictSemanticChannel.ContentTable.of(Vector.empty, Estimate.Ineligible))
    )
    val evidence = channels.map { channel =>
      val generated = right(StrictCandidateGenerator.canonical(channel, config, recalled, original))
      right(
        LocalEvidence.compute(recalled, original, generated, right(StrictCostModel.of(channel)))
      )
    }
    assertEquals(evidence.map(_.identity).distinct.size, 3)
    val results =
      evidence.map(e => right(GraphHsmm.infer(recalled, original, e, HsmmConfig.default)))
    val artifacts = results.map(r => right(HsmmResultCodec.encode(r)))
    results.zip(artifacts).foreach { (result, artifact) =>
      assertEquals(result.gateSemantics, GateSemantics.CanonicalContent)
      assert(result.posterior.rows.forall(_.mass == Map(AlignState.unranked -> 1.0)))
      assertEquals(HsmmResultCodec.decode(artifact, recalled, original), Right(result))
    }
    // No execution-certified summary is invented when contextual decoding reconstructs a gate proof.
    assertEquals(artifacts.distinct.size, 1)
  }
