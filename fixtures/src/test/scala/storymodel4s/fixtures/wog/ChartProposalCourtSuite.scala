package storymodel4s.fixtures.wog

import cats.data.{NonEmptySet, NonEmptyVector}
import munit.FunSuite
import storymodel4s.acquire.*
import storymodel4s.core.*
import storymodel4s.document.*
import storymodel4s.proposition.{Polarity as ChartPolarity, *}
import storymodel4s.story.{Polarity as StoryPolarity, *}

/** The multi-sentence chart-to-compilation court for ADR 0005 phase 1.3.
  *
  * It drives the public-domain WOG source through `SurfaceAnalyzer`, supplies hand-built checked
  * charts for seven sentences (four admissible, two inadmissible, one empty) as silver test inputs,
  * and runs [[ChartProposalProvider]] and [[NarrativeCompiler]]. Like the vertical suite it never
  * imports the hand-authored WOG narrative model: it is a mechanical reachability and ledger court,
  * not a claim of WOG narrative coverage or scientific accuracy.
  */
class ChartProposalCourtSuite extends FunSuite:
  private val source = StorySource
    .fromText(
      WarOfTheGhostsText.text,
      Some(WarOfTheGhostsText.title),
      metadata = Map("source" -> WarOfTheGhostsText.provenance)
    )
    .fold(e => fail(e.message), identity)
  private val atlas = SurfaceAnalyzer.analyze(source)
  private val parser = Fingerprint.unsafe("wog:test-chart-parser:1")
  private val parserStage = StageId.unsafe("wog-test-chart-parser")

  private def sentence(prefix: String): SurfaceUnit =
    atlas.sentences
      .find(u => atlas.text(u).startsWith(prefix))
      .getOrElse(fail(s"no sentence starts with '$prefix'"))
  private def sentenceContaining(fragment: String): SurfaceUnit =
    atlas.sentences
      .find(u => atlas.text(u).contains(fragment))
      .getOrElse(fail(s"no sentence contains '$fragment'"))

  private val sEgulac = sentence("There were people at Egulac")
  private val sHunt = sentence("One night two young men")
  private val sRiver = sentence("They came down the river")
  private val sFog = sentence("It became foggy")
  private val sPaddle = sentence("While they were paddling")
  private val sThought = sentence("They thought")
  private val sArrows = sentenceContaining("I have no arrows")

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
        Provenance.deterministic("test", Checksum.ofText("wog-test-chart-parser"))
      )
    )

  private def chartCall(unit: SurfaceUnit): ProviderCall =
    ProviderCall(
      "wog-test-chart-parser",
      "hand-built-silver",
      "1",
      None,
      source.canonicalChecksum,
      Checksum.ofText(s"chart:${unit.id.value}"),
      Map("sentence" -> unit.id.value),
      None,
      cached = false
    )

  private def checked(
      unit: SurfaceUnit,
      focus: Option[ConceptId],
      concepts: Map[ConceptId, Concept],
      relations: Vector[PropositionRelation] = Vector.empty,
      polarity: Map[ConceptId, ChartPolarity] = Map.empty,
      embedded: Vector[EmbeddedProposition] = Vector.empty,
      alignments: Vector[PropositionAlignment] = Vector.empty
  ): (SurfaceUnitId, PropositionEvidence) =
    val unchecked = PropositionChart.unchecked(
      focus,
      concepts,
      relations,
      polarity,
      embedded,
      alignments,
      ChartProvenance(ChartOrigin.Parser(parser), Vector(chartCall(unit)), Vector.empty),
      Some(unit.id)
    )
    unit.id -> PropositionEvidence.of(
      ChartValidator.check(unchecked).fold(v => fail(v.toString), identity)
    )

  private val c0 = ConceptId.unsafe("c0")
  private val c1 = ConceptId.unsafe("c1")
  private val c2 = ConceptId.unsafe("c2")
  private val c3 = ConceptId.unsafe("c3")
  private def arg(from: ConceptId, index: Int, to: ConceptId): PropositionRelation =
    PropositionRelation(from, RoleAssignment.arg(index), ConceptTarget.Node(to))

  private val egulac = checked(
    sEgulac,
    Some(c0),
    Map(
      c0 -> Concept.predicate("be-located-at", Some(FrameRef("amr", "be-located-at-91", None))),
      c1 -> Concept.entity("people"),
      c2 -> Concept.name("Egulac")
    ),
    Vector(arg(c0, 1, c1), arg(c0, 2, c2)),
    polarity = Map(c0 -> ChartPolarity.Positive),
    alignments =
      Vector(align(sEgulac, "were", c0), align(sEgulac, "people", c1), align(sEgulac, "Egulac", c2))
  )
  private val hunt = checked(
    sHunt,
    Some(c0),
    Map(
      c0 -> Concept.predicate("go", Some(FrameRef("propbank", "go-02", None))),
      c1 -> Concept.entity("man"),
      c2 -> Concept.predicate("hunt", Some(FrameRef("propbank", "hunt-01", None))),
      c3 -> Concept.entity("seal")
    ),
    Vector(
      arg(c0, 0, c1),
      PropositionRelation(c0, RoleAssignment.named("purpose"), ConceptTarget.Node(c2)),
      arg(c2, 1, c3)
    ),
    polarity = Map(c0 -> ChartPolarity.Positive),
    alignments = Vector(
      align(sHunt, "went", c0),
      align(sHunt, "men", c1),
      align(sHunt, "hunt", c2),
      align(sHunt, "seals", c3)
    )
  )
  private val river = checked(
    sRiver,
    Some(c1),
    Map(c0 -> Concept.predicate("come"), c1 -> Concept.entity("river")),
    Vector(arg(c0, 1, c1)),
    alignments = Vector(align(sRiver, "river", c1))
  )
  private val fog = checked(
    sFog,
    Some(c0),
    Map(
      c0 -> Concept.predicate("become"),
      c1 -> Concept.property("foggy"),
      c2 -> Concept.property("calm")
    ),
    Vector(arg(c0, 1, c1), arg(c0, 1, c2)),
    alignments =
      Vector(align(sFog, "became", c0), align(sFog, "foggy", c1), align(sFog, "calm", c2))
  )
  private val paddle = checked(sPaddle, None, Map.empty)
  private val thought = checked(
    sThought,
    Some(c1),
    Map(c0 -> Concept.predicate("think"), c1 -> Concept.predicate("be")),
    Vector(arg(c0, 1, c1)),
    embedded = Vector(EmbeddedProposition(c0, EmbeddingKind.Belief, c1)),
    alignments = Vector(align(sThought, "thought", c0))
  )
  private val arrows = checked(
    sArrows,
    Some(c0),
    Map(
      c0 -> Concept.predicate("have", Some(FrameRef("propbank", "have-03", None))),
      c1 -> Concept.entity("i"),
      c2 -> Concept.entity("arrow")
    ),
    Vector(arg(c0, 0, c1), arg(c0, 1, c2)),
    polarity = Map(c0 -> ChartPolarity.Negative),
    alignments = Vector(align(sArrows, "have", c0), align(sArrows, "arrows", c2))
  )

  private val charts = Vector(egulac, hunt, river, fog, paddle, thought, arrows)

  private def ref(unit: SurfaceUnit, concept: ConceptId): ChartNodeRef =
    ChartNodeRef(unit.id, concept)

  test("chart proposals compile the WOG source into a multi-situation partial compilation") {
    assertEquals(atlas.sentences.size, 50)
    val proposals =
      ChartProposalProvider.propose(source, atlas, charts).fold(e => fail(e.message), identity)

    assertEquals(proposals.counts, CoverageCounts(4, 2, 1, 43))
    assertEquals(proposals.coverage.size, 50)
    assertEquals(proposals.summaryCoverage, SummaryCoverage.Proposed("The War of the Ghosts"))
    val rows = proposals.coverage.map(row => row.sentence -> row).toMap
    assertEquals(rows(sEgulac.id), SentenceCoverage.Proposed(sEgulac.id, ref(sEgulac, c0)))
    assertEquals(rows(sHunt.id), SentenceCoverage.Proposed(sHunt.id, ref(sHunt, c0)))
    assertEquals(
      rows(sRiver.id),
      SentenceCoverage.Abstained(
        sRiver.id,
        ref(sRiver, c1),
        AbstentionReason.FocusNotPredicate(ConceptKind.Entity)
      )
    )
    assertEquals(rows(sFog.id), SentenceCoverage.Proposed(sFog.id, ref(sFog, c0)))
    assertEquals(rows(sPaddle.id), SentenceCoverage.EmptyChart(sPaddle.id))
    assertEquals(
      rows(sThought.id),
      SentenceCoverage.Abstained(sThought.id, ref(sThought, c1), AbstentionReason.FocusEmbedded)
    )
    assertEquals(rows(sArrows.id), SentenceCoverage.Proposed(sArrows.id, ref(sArrows, c0)))
    assertEquals(proposals.situations.size, 6)
    assertEquals(proposals.calls.size, 19)

    val values = proposals.situations
      .flatMap(a => a.bundle.proposals.flatMap(_.value).map(a.source -> _))
      .toMap
    assertEquals(values(ref(sEgulac, c0)).kind, SituationKind.State)
    assertEquals(values(ref(sEgulac, c0)).description, "be-located-at people Egulac")
    assertEquals(values(ref(sHunt, c0)).kind, SituationKind.Event)
    assertEquals(values(ref(sHunt, c0)).description, "go man (purpose: hunt)")
    assertEquals(values(ref(sFog, c0)).description, "become foggy calm")
    assertEquals(values(ref(sEgulac, c0)).polarity, StoryPolarity.Positive)
    assertEquals(values(ref(sFog, c0)).polarity, StoryPolarity.Unknown)
    assertEquals(values(ref(sArrows, c0)).polarity, StoryPolarity.Negative)
    assertEquals(values(ref(sArrows, c0)).description, "not have i arrow")

    val input = ChartProposalProvider
      .input(source, atlas, charts, Some(parserStage -> Checksum.ofText("wog-silver-charts")), 0L)
      .fold(e => fail(e.message), identity)
    val compiled = NarrativeCompiler.compile(input).fold(e => fail(e.message), identity)

    assertEquals(compiled.validated, None)
    assert(compiled.isPartial)
    assertEquals(compiled.draft.graph.situations.size, 4)
    assertEquals(compiled.draft.hierarchy.primary.size, 4)
    assertEquals(compiled.draft.graph.segments.size, 1)
    assert(!compiled.validation.report.byLaw.contains("hierarchy.member-within-parent"))

    val noProposal = DerivationGapReason.Unresolved(ResolutionFailure.NoProposal)
    val trajectory = DerivationGapReason.UnsupportedTrajectoryInputs(
      Vector(ClaimFamily.ParticipantRole, ClaimFamily.TemporalRelation)
    )
    val story = source.id
    val expectedGaps: Set[(NarrativeCandidateAddress, DerivationGapReason)] =
      Vector(ref(sRiver, c1), ref(sThought, c1)).flatMap { anchor =>
        Vector(
          NarrativeCandidateAddress.Situation(anchor) -> noProposal,
          NarrativeCandidateAddress.ContextAssignment(anchor) -> noProposal,
          NarrativeCandidateAddress.SegmentMembership(story, anchor) -> noProposal
        )
      }.toSet ++ Set(
        NarrativeCandidateAddress.TrajectoryStep(ref(sEgulac, c0), ref(sHunt, c0)) -> trajectory,
        NarrativeCandidateAddress.TrajectoryStep(ref(sHunt, c0), ref(sFog, c0)) -> trajectory,
        NarrativeCandidateAddress.TrajectoryStep(ref(sFog, c0), ref(sArrows, c0)) -> trajectory
      )
    assertEquals(compiled.derivation.gaps.size, 9)
    assertEquals(compiled.derivation.gaps.map(g => g.target -> g.reason).toSet, expectedGaps)

    assertEquals(compiled.provenance.configHash, Checksum.ofText(ChartProposalProvider.RulesText))
    assertEquals(compiled.provenance.calls.size, 26)
    assertEquals(
      compiled.receipt.stages.map(_._1),
      Vector(parserStage, ChartProposalProvider.Stage)
    )
  }

  test("replaying the same charts reproduces the compilation fingerprint") {
    def run: NarrativeCompilation =
      val input = ChartProposalProvider
        .input(source, atlas, charts.reverse, None, 0L)
        .fold(e => fail(e.message), identity)
      NarrativeCompiler.compile(input).fold(e => fail(e.message), identity)
    val first = run
    val second = run
    assertEquals(second.fingerprint, first.fingerprint)
    assertEquals(second, first)
  }
