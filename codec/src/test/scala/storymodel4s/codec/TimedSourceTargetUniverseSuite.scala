package storymodel4s.codec

import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.align.bridge.TimedSourceView
import storymodel4s.core.*
import storymodel4s.recall.*

/** A producer's choice of a scene or of a whole film, checked against the source's own targets. */
class TimedSourceTargetUniverseSuite extends FunSuite:
  private val parts =
    """[{"part":"a","start":"0","end":"100","numerator":"1","denominator":"10"}]"""
  private val csv = (TimedSourceArtifactCodec.HeaderV2.mkString(",") +: Vector(
    "1,Anna arrived.,a,10,20,1,scene 1,,Anna,1,Harbour film,",
    "2,A title card.,a,20,30,1,scene 1,,,1,Harbour film,no",
    "3,Another title card.,a,30,40,2,scene 2,,,1,Harbour film,no",
    "4,A mouse dances.,a,40,50,3,scene 3,,,2,Cartoon,no",
    "5,Bob left.,a,50,60,4,scene 4,,Bob,3,Station film,"
  )).mkString("", "\n", "\n")
  private lazy val view: TimedSourceView = TimedSourceArtifactCodec
    .prepare(csv.getBytes(UTF_8), parts.getBytes(UTF_8))
    .fold(fail(_), identity)

  private val texts = Vector("Anna came in.", "Something at the station.", "I forget the rest.")
  private val recallText = texts.mkString("\n")
  private lazy val inventory: RecallInventory =
    val transcript = StorySource.fromText(recallText).toOption.get
    val starts = texts.scanLeft(0)((offset, text) => offset + text.length + 1).dropRight(1)
    val units = texts.zip(starts).zipWithIndex.map { case ((text, start), ordinal) =>
      RecallUnit(
        RecallUnitId.unsafe(s"u${ordinal + 1}"),
        ordinal,
        SpanSet.one(TextSpan.unsafe(start, start + text.length)),
        text,
        DiscourseFunction.EpisodicAssertion,
        ExpressedUncertainty.Unmarked,
        PropositionSketch.empty,
        None
      )
    }
    val recall = RecallGraph
      .validated(transcript, SurfaceAnalyzer.analyze(transcript), units, RecallRelations.empty)
      .toOption
      .get
    RecallInventory
      .of(
        recall,
        "\\S+".r.findAllMatchIn(recallText).map(m => TextSpan.unsafe(m.start, m.end)).toVector,
        WordIdPolicy.inputArtifact(Checksum.ofText(recallText))
      )
      .toOption
      .get
  private lazy val ledger = StageLedger
    .of(Stage.values.toVector.map { stage =>
      StageEntry
        .of(stage, StageProvenance.unknown(UnknownProvenanceReason.NotRun, None))
        .toOption
        .get
    })
    .toOption
    .get
  private lazy val stages = UnitStageRefs
    .of(
      ledger,
      ledger.at(Stage.Inference).head.id,
      ledger.at(Stage.Candidates).head.id,
      ledger.at(Stage.Decision).head.id
    )
    .toOption
    .get
  private val policy = DecisionPolicyId.unsafe("synthetic-categorical-producer/v1")
  private val grain = TargetGrain.Hierarchy(Vector(1, 2))
  private def unit(n: Int): RecallUnitId = inventory.units(n).id

  private def choice(n: Int, ref: SourceNodeRef): UnitOutcome =
    val destination = Destination.Target(ref)
    UnitOutcome
      .categorical(
        unit(n),
        DecisionRequest.ExternalDecode(destination, policy),
        Vector(
          MappingLink
            .ungated(destination, stages, CandidateSetId.categorical(stages.candidates, unit(n)))
        ),
        stages
      )
      .fold(e => fail(e.message), identity)
  private def abstention(n: Int): UnitOutcome = UnitOutcome
    .categorical(unit(n), DecisionRequest.Abstain(policy, "no assignment"), Vector.empty, stages)
    .fold(e => fail(e.message), identity)

  private def record(
      first: SourceNodeRef,
      second: SourceNodeRef,
      targets: Vector[SourceNodeRef] = view.targetGroups ++ view.targetCollections
  ): Either[MappingRefusal, MappingResult] =
    for
      universe <- DeclaredUniverse.of(targets, grain)
      policies <- MappingPolicies.of(
        InferencePolicy.Unspecified("Synthetic categorical choices; no estimator"),
        ContextPolicy.Unspecified("No context model"),
        CandidatePolicy.Declared(
          CandidatePolicyId.unsafe("synthetic-categorical-candidates/v1"),
          CandidateCoverage.Unknown("Nomination recall is unmeasured")
        ),
        ReferencePrior.NotApplicable("Categorical choices carry no scores"),
        DecisionPolicy.Declared(policy),
        universe
      )
      roles <- UnitRoles.of(
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.Targets(grain)
      )
      result <- MappingResult.checked(
        inventory,
        view.representation,
        policies,
        roles,
        ledger,
        Vector(choice(0, first), choice(1, second), abstention(2))
      )
    yield result

  private def scene(n: Int): SourceNodeRef = view.groupRefs(n)
  private def film(n: Int): SourceNodeRef = view.collectionRefs(n)

  test("the source's targets are scenes 1 and 4 and films 1 and 3") {
    assertEquals(view.targetGroups, Vector(scene(1), scene(4)))
    assertEquals(view.targetCollections, Vector(film(1), film(3)))
  }

  test("a choice of a target scene and a choice of a target film are one checked record") {
    val value = record(scene(1), film(3)).fold(e => fail(e.message), identity)
    assertEquals(value.policies.universe.grain, grain)
    assertEquals(value.policies.universe.targets.size, 4)
    val chosen = value.outcomes.map(_.decision.flatMap(_.chosen))
    assertEquals(
      chosen,
      Vector(Some(Destination.Target(scene(1))), Some(Destination.Target(film(3))), None)
    )
    assertEquals(value.outcomes(0).localization, LocalizationStatus.Located)
    assertEquals(value.outcomes(1).localization, LocalizationStatus.Located)
    assertEquals(value.source.target(film(3)).map(_.level), Some(2))
    assertEquals(value.source.target(scene(1)).map(_.level), Some(1))
  }

  test("the record with a film choice round-trips through the mapping codec") {
    val value = record(scene(1), film(3)).fold(e => fail(e.message), identity)
    val encoded = MappingCodecs.encode(value)
    val decoded = MappingCodecs
      .decode(encoded, ExpectedMappingContext(value.inventory, value.source))
      .fold(e => fail(e.message), identity)
    assertEquals(decoded.digest, value.digest)
    assertEquals(
      decoded.outcomes(1).decision.flatMap(_.chosen),
      Some(Destination.Target(film(3)))
    )
  }

  test("a scene that is in the source and is not a target refuses") {
    assert(view.representation.target(scene(2)).nonEmpty)
    assertEquals(record(scene(2), film(3)), Left(MappingRefusal.UniverseMismatch))
    assertEquals(record(scene(3), film(3)), Left(MappingRefusal.UniverseMismatch))
    val everyScene = view.groupRefs.values.toVector ++ view.targetCollections
    assert(
      record(scene(2), film(3), everyScene).isRight,
      "control: the same choice is lawful when the universe declares that scene"
    )
  }

  test("a film that is not a target, and a row, refuse") {
    assertEquals(record(scene(1), film(2)), Left(MappingRefusal.UniverseMismatch))
    val row = view.nodes.filter(_.level == 0).head.ref
    assertEquals(record(row, film(3)), Left(MappingRefusal.UniverseMismatch))
  }

  test("a universe of scenes and films cannot be declared over a row") {
    val row = view.nodes.filter(_.level == 0).head.ref
    assertEquals(
      record(scene(1), film(3), view.targetGroups ++ view.targetCollections :+ row),
      Left(MappingRefusal.UniverseMismatch)
    )
  }
