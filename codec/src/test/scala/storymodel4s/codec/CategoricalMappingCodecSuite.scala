package storymodel4s.codec

import io.circe.Json
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.laws.MappingMiniature
import storymodel4s.recall.*

/** A score-free producer choice is saved and reloaded from outside `storymodel4s.align`. */
class CategoricalMappingCodecSuite extends FunSuite:
  private lazy val miniature = MappingMiniature.record
  private lazy val row = miniature.outcomes.head
  private lazy val stages = row.stages.get
  private lazy val chosen = Destination.Target(miniature.policies.universe.targets.head)
  private val policy = DecisionPolicyId.unsafe("synthetic-llm-producer/v1")
  private def candidates(unit: RecallUnitId = row.unit): CandidateSetId =
    CandidateSetId.categorical(stages.candidates, unit)
  private def link(
      destination: Destination = chosen,
      unit: RecallUnitId = row.unit,
      refs: UnitStageRefs = stages
  ): MappingLink = MappingLink.ungated(destination, refs, candidates(unit))
  private def choice(destination: Destination = chosen): UnitOutcome =
    UnitOutcome
      .categorical(
        row.unit,
        DecisionRequest.ExternalDecode(destination, policy),
        Vector(link(destination)),
        stages
      )
      .fold(e => fail(e.message), identity)
  private lazy val abstention: UnitOutcome =
    UnitOutcome
      .categorical(
        row.unit,
        DecisionRequest.Abstain(policy, "model reported no narrative assignment"),
        Vector.empty,
        stages
      )
      .fold(e => fail(e.message), identity)
  private def record(outcome: UnitOutcome): Either[MappingRefusal, MappingResult] =
    MappingResult.checked(
      miniature.inventory,
      miniature.source,
      miniature.policies,
      miniature.roles,
      miniature.ledger,
      miniature.outcomes.updated(0, outcome)
    )
  private def context(value: MappingResult): ExpectedMappingContext =
    ExpectedMappingContext(value.inventory, value.source)
  private def version(text: String): Option[String] =
    Canonical.parse(text).toOption.flatMap(_.hcursor.get[String]("schemaVersion").toOption)
  private def roundTrip(value: MappingResult): MappingResult =
    val encoded = MappingCodecs.encode(value)
    assertEquals(version(encoded), Some(MappingCodecs.CategoricalSchemaVersion))
    val decoded =
      MappingCodecs.decode(encoded, context(value)).fold(e => fail(e.message), identity)
    assertEquals(decoded.digest, value.digest)
    assertEquals(MappingCodecs.encode(decoded), encoded)
    decoded
  private def categorical(decision: UnitDecision): DecisionSupport.Categorical =
    decision.support match
      case support: DecisionSupport.Categorical => support
      case _: DecisionSupport.Numerical         => fail("expected categorical support")

  test("score-free categorical choice round-trips as mapping-record/v0.2") {
    val value = record(choice()).fold(e => fail(e.message), identity)
    val decoded = roundTrip(value).outcomes.head
    val decision = decoded.decision.get
    assertEquals(categorical(decision).policy, policy)
    assertEquals(decision.numerical.isEmpty, true)
    assertEquals(decision.chosen, Some(chosen))
    assertEquals(decision.origin, DecisionOrigin.ProducerChoice(policy))
    assertEquals(decision.request, DecisionRequest.ExternalDecode(chosen, policy))
    assertEquals(decision.policy, Some(policy))
    assert(decision.calibration.isInstanceOf[DecisionCalibration.Unavailable])
    assertEquals(decoded.localization, LocalizationStatus.Located)
    assertEquals(decoded.measures.destinations, Set.empty[Destination])
    assertEquals(decoded.links.map(_.destination), Vector(chosen))
    assertEquals(decoded.links.map(_.candidateSet), Vector(candidates()))
  }

  test("explicit categorical abstention round-trips as mapping-record/v0.2") {
    val value = record(abstention).fold(e => fail(e.message), identity)
    val decoded = roundTrip(value).outcomes.head
    val decision = decoded.decision.get
    assertEquals(categorical(decision).policy, policy)
    assertEquals(decision.chosen, None)
    assertEquals(
      decision.origin,
      DecisionOrigin.Abstention("model reported no narrative assignment")
    )
    assertEquals(decoded.localization, LocalizationStatus.NotComputed)
    assertEquals(decoded.links, Vector.empty)
  }

  test("categorical choice, abstention and the numerical row have distinct digests") {
    val digests = Vector(
      miniature.digest,
      record(choice()).toOption.get.digest,
      record(abstention).toOption.get.digest
    )
    assertEquals(digests.distinct.size, 3)
  }

  test("a record without categorical decisions is still written as v0.1") {
    assertEquals(version(MappingCodecs.encode(miniature)), Some(MappingCodecs.SchemaVersion))
  }

  test("categorical decision encodes its support kind and no numerical fields") {
    val json = MappingCodecs.toJson(record(choice()).toOption.get)
    val decision = json.hcursor
      .downField("outcomes")
      .downN(0)
      .downField("decision")
      .downField("value")
    assertEquals(
      decision.downField("support").get[String]("status").toOption,
      Some("categorical")
    )
    Vector("basis", "raw_argmax", "decoded_target_mass").foreach { key =>
      assert(decision.downField(key).focus.isEmpty, key)
    }
  }

  test("a categorical decision inside a document claiming v0.1 refuses") {
    val json = MappingCodecs
      .toJson(record(choice()).toOption.get)
      .mapObject(_.add("schemaVersion", Json.fromString(MappingCodecs.SchemaVersion)))
    assertEquals(
      MappingCodecs.decode(MappingJson.print(json), context(miniature)).left.toOption,
      Some(
        MappingCodecError.ValueMismatch(
          "categorical decision support requires mapping-record/v0.2"
        )
      )
    )
  }

  test("a numerical-only document relabelled v0.2 refuses as non-canonical") {
    val json = MappingCodecs
      .toJson(miniature)
      .mapObject(_.add("schemaVersion", Json.fromString(MappingCodecs.CategoricalSchemaVersion)))
    assertEquals(
      MappingCodecs.decode(MappingJson.print(json), context(miniature)).left.toOption,
      Some(MappingCodecError.ValueMismatch("canonical-record"))
    )
  }

  test("categorical exchange is written as mapping-exchange/v0.2 and decodes exactly") {
    val value = record(choice()).toOption.get
    val bundle = MappingExchange.encode(value).fold(e => fail(e.toString), identity)
    assertEquals(version(bundle.manifest), Some(MappingExchange.CategoricalSchemaVersion))
    val decoded =
      MappingExchange.decode(bundle.files, context(value)).fold(e => fail(e.toString), identity)
    assertEquals(decoded.digest, value.digest)
    val numerical = MappingExchange.encode(miniature).toOption.get
    assertEquals(version(numerical.manifest), Some(MappingExchange.SchemaVersion))
  }

  test("a raw argmax request has no categorical meaning and refuses") {
    assertEquals(
      UnitOutcome.categorical(row.unit, DecisionRequest.RawArgmax, Vector(link()), stages),
      Left(
        MappingRefusal.InvalidValue(
          "decision.request",
          "a categorical decision needs an explicit choice or abstention"
        )
      )
    )
  }

  test("an empty abstention reason refuses") {
    assertEquals(
      UnitOutcome
        .categorical(row.unit, DecisionRequest.Abstain(policy, "  "), Vector.empty, stages),
      Left(MappingRefusal.InvalidValue("decision.abstention", "reason must be nonempty"))
    )
  }

  test("a link minted for a foreign unit refuses by candidate set") {
    val foreign = miniature.outcomes(1).unit
    assertNotEquals(foreign, row.unit)
    assertEquals(
      UnitOutcome.categorical(
        row.unit,
        DecisionRequest.ExternalDecode(chosen, policy),
        Vector(link(unit = foreign)),
        stages
      ),
      Left(MappingRefusal.CandidateSetMismatch(row.unit))
    )
  }

  test("a result-bound link refuses: a categorical outcome carries only supplied links") {
    val f = MappingCodecFixture
    val base = f.record()
    val own = base.outcomes.head
    val refs = own.stages.get
    val foreign = base.outcomes(1).unit
    assertNotEquals(foreign, own.unit)
    val binding =
      DerivationBinding.of(f.result, f.recall, f.inventory, f.view, f.source).toOption.get
    def bound(unit: RecallUnitId): (Destination, MappingLink) =
      val state = f.result.costs(unit).keys.toVector.sorted.head
      Destination.of(state) -> MappingLink
        .fromResult(
          f.result,
          binding,
          f.recall,
          f.view,
          f.source,
          unit,
          state,
          refs,
          CandidateSetId.categorical(refs.candidates, own.unit)
        )
        .toOption
        .get
    def attempt(unit: RecallUnitId) =
      val (destination, l) = bound(unit)
      UnitOutcome.categorical(
        own.unit,
        DecisionRequest.ExternalDecode(destination, policy),
        Vector(l),
        refs
      )
    val (destination, _) = bound(own.unit)
    val supplied = UnitOutcome.categorical(
      own.unit,
      DecisionRequest.ExternalDecode(destination, policy),
      Vector(
        MappingLink.ungated(destination, refs, CandidateSetId.categorical(refs.candidates, own.unit))
      ),
      refs
    )
    assert(supplied.isRight, "same-shape control: a supplied link to that destination is admitted")
    assertEquals(attempt(own.unit), Left(MappingRefusal.BindingMismatch("categorical.link")))
    assertEquals(attempt(foreign), Left(MappingRefusal.BindingMismatch("categorical.link")))
  }

  test("two categorical policies give different digests") {
    val other = DecisionPolicyId.unsafe("synthetic-llm-producer/v2")
    val second = UnitOutcome
      .categorical(
        row.unit,
        DecisionRequest.ExternalDecode(chosen, other),
        Vector(link(chosen)),
        stages
      )
      .flatMap(record)
      .fold(e => fail(e.message), identity)
    assertNotEquals(second.digest, record(choice()).toOption.get.digest)
  }

  test("a destination outside the source refuses") {
    val ref = SourceNodeRef.Situation(SituationId.unsafe("not-in-this-source"))
    assert(!miniature.source.targets.exists(_.ref == ref))
    assertEquals(record(choice(Destination.Target(ref))), Left(MappingRefusal.UnknownTarget(ref)))
  }

  test("a link to a destination other than the choice refuses") {
    val other = Destination.Target(miniature.policies.universe.targets(1))
    assertNotEquals(other, chosen)
    assertEquals(
      UnitOutcome.categorical(
        row.unit,
        DecisionRequest.ExternalDecode(chosen, policy),
        Vector(link(other)),
        stages
      ),
      Left(MappingRefusal.LinkDestinationsMismatch)
    )
    assertEquals(
      UnitOutcome.categorical(
        row.unit,
        DecisionRequest.ExternalDecode(chosen, policy),
        Vector.empty,
        stages
      ),
      Left(MappingRefusal.LinkDestinationsMismatch)
    )
    assertEquals(
      UnitOutcome.categorical(
        row.unit,
        DecisionRequest.Abstain(policy, "no assignment"),
        Vector(link()),
        stages
      ),
      Left(MappingRefusal.LinkDestinationsMismatch)
    )
  }

  test("a link on another inference stage refuses") {
    val ledger = miniature.ledger
    val inference = StageEntry
      .of(
        Stage.Inference,
        StageProvenance.unknown(UnknownProvenanceReason.HistoricalArtifact, None)
      )
      .toOption
      .get
    assertNotEquals(inference.id, stages.inference)
    val other = StageLedger
      .of(
        (ledger.entries.filterNot(_.stage == Stage.Inference) :+ inference)
          .sortBy(e => (e.stage.ordinal, e.id.digest.hex))
      )
      .toOption
      .get
    val refs =
      UnitStageRefs.of(other, inference.id, stages.candidates, stages.decision).toOption.get
    assertEquals(
      UnitOutcome.categorical(
        row.unit,
        DecisionRequest.ExternalDecode(chosen, policy),
        Vector(link(refs = refs)),
        stages
      ),
      Left(MappingRefusal.BindingMismatch("outcome.inferenceStage"))
    )
  }

  test("temporal preparation of a categorical outcome refuses with MissingMeasure") {
    val value = record(choice()).toOption.get
    val selection = TemporalSupport.Selection.Part(Checksum.ofText("unused-selection"))
    TemporalQuery.Measure.values.foreach { measure =>
      assertEquals(
        TemporalQuery.prepare(value, row.unit, measure, selection, Vector.empty),
        Left(TemporalQuery.Refusal.MissingMeasure),
        measure.toString
      )
    }
  }
