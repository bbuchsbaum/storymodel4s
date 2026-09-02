package storymodel4s.view

import cats.data.{NonEmptySet, NonEmptyVector}
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.document.*
import storymodel4s.proposition.{Polarity as ChartPolarity, *}
import storymodel4s.story.*

/** The phase 1.4 acceptance target: a machine-built, multi-sentence story reaches the Atlas.
  *
  * Why here: `view` is the first module that can see both the chart-driven provider and the Atlas
  * compiler, and the plan's acceptance criterion is a validated silver model rendered without the
  * hand-authored fixture graph. Three hand-built checked charts stand in for a parser; nothing in
  * this suite reads the WOG fixture model.
  */
class CompiledAtlasSuite extends FunSuite:
  private val source = StorySource
    .fromText("The man went. The man saw. The man returned.", Some("Three steps"))
    .fold(e => fail(e.message), identity)
  private val atlas = SurfaceAnalyzer.analyze(source)
  private val sentences = atlas.sentences
  private val parser = Fingerprint.unsafe("test:atlas-chart-parser:1")
  private val parserStage = StageId.unsafe("test-atlas-chart-parser")

  private val predicate = ConceptId.unsafe("p")
  private val filler = ConceptId.unsafe("e")
  private val words =
    Vector(("go", "go-02", "went"), ("see", "see-01", "saw"), ("return", "return-01", "returned"))

  private def spanOf(unit: SurfaceUnit, word: String): SpanRef =
    val text = atlas.text(unit)
    val at = text.indexOf(word)
    assert(at >= 0, s"'$word' is not in '$text'")
    SpanRef(
      Some(unit.id),
      TextSpan.unsafe(unit.span.start + at, unit.span.start + at + word.length)
    )

  private def align(unit: SurfaceUnit, word: String, concept: ConceptId): PropositionAlignment =
    val spans = SpanSet.one(spanOf(unit, word))
    val evidence = Evidence(
      EvidenceId.unsafe(s"ev:align:${unit.id.value}:$word"),
      Some(spans),
      Set.empty,
      parser,
      parserStage
    )
    PropositionAlignment(
      AlignmentTarget.Concepts(NonEmptySet.one(concept)),
      spans,
      Credence.unsafeRaw(1.0),
      ClaimMeta.unsafe(
        ClaimId.unsafe(s"claim:align:${unit.id.value}:$word"),
        EpistemicStatus.SurfaceExplicit,
        Credence.unsafeRaw(1.0),
        NonEmptyVector.one(evidence),
        Provenance.deterministic("test", Checksum.ofText("test-atlas-chart-parser"))
      )
    )

  private def chart(index: Int): (SurfaceUnitId, PropositionEvidence) =
    val unit = sentences(index)
    val (lemma, frame, word) = words(index)
    val licensedAgent = RoleAssignment(
      SourceRole.Numbered(0),
      Some((ParticipantRole.Agent, Credence.unsafeRaw(0.5)))
    )
    val unchecked = PropositionChart.unchecked(
      Some(predicate),
      Map(
        predicate -> Concept.predicate(lemma, Some(FrameRef("propbank", frame, None))),
        filler -> Concept.entity("man")
      ),
      Vector(PropositionRelation(predicate, licensedAgent, ConceptTarget.Node(filler))),
      Map(predicate -> ChartPolarity.Positive),
      Vector.empty,
      Vector(align(unit, word, predicate), align(unit, "man", filler)),
      ChartProvenance.hand,
      Some(unit.id)
    )
    unit.id -> PropositionEvidence.of(
      ChartValidator.check(unchecked).fold(v => fail(v.toString), identity)
    )

  private val charts = Vector(0, 1, 2).map(chart)

  test("a validated chart-driven compilation compiles into a Discourse Atlas scene") {
    val input = ChartProposalProvider
      .input(source, atlas, charts, Some(parserStage), 0L)
      .fold(e => fail(e.message), identity)
    val compiled = NarrativeCompiler.compile(input).fold(e => fail(e.message), identity)
    val model = compiled.validated.getOrElse(fail(compiled.validation.report.render))
    assertEquals(compiled.derivation.gaps, Vector.empty)
    assertEquals(model.graph.situations.size, 3)
    assertEquals(model.graph.entities.size, 1)
    assertEquals(model.graph.relations.participants.size, 3)
    assertEquals(model.trajectory.steps.map(_.entityTurnover), Vector(0.0, 0.0))

    val receipt = model.receipt.getOrElse(fail("a compiled model carries its build receipt"))
    val state = CommonViewState.empty
    val spec =
      AtlasSpec(ZoomLevel(NarrativeLevel.Scene, SurfaceDetail.Hidden), ThreadPolicy.Selected)
    val provenance = ViewProvenance
      .of(
        source.canonicalChecksum,
        Some(receipt.contentChecksum),
        ViewBasis.ValidatedBuild,
        "compiled-atlas-suite",
        AtlasCompiler.configurationChecksum(state, spec)
      )
      .fold(e => fail(e.message), identity)

    val scene = AtlasCompiler(provenance).compile(model, state, spec) match
      case Right(scene) => scene
      case Left(error)  => fail(error.toString)
    assertEquals(scene.provenance.basis, ViewBasis.ValidatedBuild)
    assert(scene.marks.nonEmpty)
    val situationAddresses =
      model.graph.situations.keys.map(id => Addressable[StoryRef].address(StoryRef.Situation(id)))
    assert(situationAddresses.forall(address => scene.navigation.marksFor(address).nonEmpty))
  }
