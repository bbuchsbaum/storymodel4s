package storymodel4s.fixtures.wog

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.acquire.*
import storymodel4s.align.*
import storymodel4s.align.bridge.StorySourceView
import storymodel4s.core.*
import storymodel4s.document.*
import storymodel4s.proposition.*
import storymodel4s.recall.RecallSegmenter
import storymodel4s.story.{Polarity as StoryPolarity, *}

/** The raw-source-to-signature court for ADR 0005.
  *
  * It uses the public-domain WOG source and a raw recall-style transcript, but never imports the
  * hand-authored WOG narrative model. A deterministic fixture provider actually derives one
  * sentence chart and the first-slice proposals and records the exact outputs it emitted. This is a
  * mechanical reachability court, not a claim of WOG narrative coverage or scientific accuracy.
  */
class NarrativeCompilerVerticalSuite extends FunSuite:
  private val source = StorySource
    .fromText(
      WarOfTheGhostsText.text,
      Some(WarOfTheGhostsText.title),
      metadata = Map("source" -> WarOfTheGhostsText.provenance)
    )
    .fold(e => fail(e.message), identity)
  private val atlas = SurfaceAnalyzer.analyze(source)
  private val selected = atlas.sentences(3)

  private object DeterministicFixtureProvider:
    val stage: StageId = StageId.unsafe("wog-deterministic-fixture-provider")
    val fingerprint: Fingerprint = Fingerprint.unsafe("fixture:wog-lexical-rule:1")
    val prompt: PromptPackageRef =
      PromptPackageRef("deterministic-fixture-rule", "1", Checksum.ofText("no-prompt-rule-v1"))

    final case class Output(
        chart: PropositionEvidence,
        ref: ChartNodeRef,
        evidence: Evidence,
        situation: SituationAttempt,
        context: ContextAssignmentAttempt,
        summary: StorySummaryAttempt,
        membership: SegmentMembershipAttempt,
        coverage: ParticipantCoverageAttempt,
        calls: Vector[ProviderCall]
    )

    def run(sentence: SurfaceUnit): Either[DomainError, Output] =
      val text = atlas.text(sentence)
      val lowercase = text.toLowerCase
      val lexical = Vector("became" -> "become").find((surface, _) => lowercase.contains(surface))
      lexical
        .toRight(
          DomainError.InvalidFormat("fixture-provider", text, "no deterministic predicate rule")
        )
        .flatMap { (_, lemma) =>
          val evidence = Evidence(
            EvidenceId.unsafe(ContentAddress.of("wog-fixture-evidence", sentence.id.value)),
            Some(SpanSet.one(SpanRef(Some(sentence.id), sentence.span))),
            Set.empty,
            fingerprint,
            stage
          )
          val chartCall = providerCall(
            "lexical-chart-rule",
            s"sentence=${sentence.id.value};predicate=$lemma"
          )
          val concept =
            ConceptId.unsafe(ContentAddress.of("wog-fixture-concept", sentence.id.value))
          val unchecked = PropositionChart.unchecked(
            Some(concept),
            Map(concept -> Concept.predicate(lemma)),
            Vector.empty,
            provenance =
              ChartProvenance(ChartOrigin.Parser(fingerprint), Vector(chartCall), Vector.empty),
            sentence = Some(sentence.id)
          )
          ChartValidator
            .check(unchecked)
            .left
            .map(violations =>
              DomainError.InvariantViolation("fixture-provider/chart", violations.toString)
            )
            .map(PropositionEvidence.of)
            .map { chart =>
              val ref = ChartNodeRef(sentence.id, concept)
              val situationValue = SituationProposal(
                SituationKind.State,
                Predicate(lemma, None, lemma),
                text,
                StoryPolarity.Positive,
                Modality.Asserted,
                None
              )
              val (situation, situationCalls) = attempt(
                "lexical-situation-rule",
                situationValue,
                evidence,
                render = s"state:$lemma:$text"
              )
              val (contextA, contextCallsA) = attempt(
                "unquoted-sentence-context-rule",
                ContextAssignmentProposal.NarratedWorld,
                evidence,
                render = s"NarratedWorld:${sentence.id.value}"
              )
              val (contextB, contextCallsB) = attempt(
                "no-reporting-marker-context-rule",
                ContextAssignmentProposal.NarratedWorld,
                evidence,
                render = s"root-assertion:${sentence.id.value}"
              )
              val contextBundle = contextA.copy(
                proposals = contextA.proposals ++ contextB.proposals
              )
              val (summary, summaryCalls) = attempt(
                "extractive-summary-rule",
                StorySummaryProposal(text),
                evidence,
                render = text
              )
              val (membershipA, membershipCallsA) = attempt(
                "selected-sentence-membership-rule",
                SegmentMembershipProposal.PrimaryStoryMember,
                evidence,
                render = s"primary:${sentence.id.value}"
              )
              val (membershipB, membershipCallsB) = attempt(
                "single-unit-coverage-rule",
                SegmentMembershipProposal.PrimaryStoryMember,
                evidence,
                render = s"only-unit:${sentence.id.value}"
              )
              val membershipBundle = membershipA.copy(
                proposals = membershipA.proposals ++ membershipB.proposals
              )
              // The lexical chart has no entity concept, so the evidenced participant set is
              // empty: a value the compiler may derive turnover from, not an absence.
              val (coverage, coverageCalls) = attempt(
                "no-entity-concept-coverage-rule",
                ParticipantCoverage.empty,
                evidence,
                render = s"coverage:none:${sentence.id.value}"
              )
              Output(
                chart,
                ref,
                evidence,
                SituationAttempt(ref, situation),
                ContextAssignmentAttempt(ref, contextBundle),
                StorySummaryAttempt(summary),
                SegmentMembershipAttempt(ref, membershipBundle),
                ParticipantCoverageAttempt(ref, coverage),
                Vector(chartCall) ++ situationCalls ++ contextCallsA ++ contextCallsB ++
                  summaryCalls ++
                  membershipCallsA ++ membershipCallsB ++ coverageCalls
              )
            }
        }

    private def attempt[A](
        rule: String,
        value: A,
        evidence: Evidence,
        render: String
    ): (EvidenceBundle[A], Vector[ProviderCall]) =
      val task = TaskId.unsafe(ContentAddress.of("wog-fixture-task", rule, evidence.id.value))
      val call = providerCall(rule, render)
      val proposal = AgentProposal.proposed(
        task,
        value,
        NonEmptyVector.one(EvidenceRef.Inline(evidence)),
        Some(RawScore.unsafe(1.0)),
        Vector.empty,
        AgentCallReceipt(call, prompt, task)
      )
      (
        EvidenceBundle(
          Vector(proposal),
          Vector.empty,
          StructuralValidity.Valid,
          SourceSupport(1.0, evidence.spans),
          agreementScore = 1.0,
          Vector(CandidateCalibration(value, Probability.unsafe(1.0), "fixture-rule-v1"))
        ),
        Vector(call)
      )

    private def providerCall(rule: String, output: String): ProviderCall =
      ProviderCall(
        s"deterministic-fixture-$rule",
        "pure-scala-rule",
        "1",
        None,
        source.canonicalChecksum,
        Checksum.ofText(output),
        Map("court" -> "wog-one-sentence-reachability"),
        None,
        cached = false
      )

  private def compilation: NarrativeCompilation =
    val generated = DeterministicFixtureProvider.run(selected).fold(e => fail(e.message), identity)
    val receipt = BuildReceipt(
      source.id,
      source.canonicalChecksum,
      StoryModel.SchemaVersion,
      Vector(
        DeterministicFixtureProvider.stage -> ContentAddress.digest(
          generated.calls.map(_.outputChecksum.hex)
        )
      ),
      0L
    )
    val input = NarrativeCompilerInput
      .of(
        source,
        atlas,
        Vector(selected.id -> generated.chart),
        Vector(generated.evidence),
        Vector(generated.situation),
        Vector(generated.context),
        generated.summary,
        Vector(generated.membership),
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector(generated.coverage),
        Vector.empty,
        AcceptancePolicy.Conservative,
        receipt,
        Provenance(
          generated.calls,
          StoryModel.SchemaVersion,
          Checksum.ofText("wog-deterministic-fixture-provider-v1")
        )
      )
      .fold(e => fail(e.message), identity)
    NarrativeCompiler.compile(input).fold(e => fail(e.message), identity)

  test("raw public source plus a raw recall transcript reaches RecallSignature unattended") {
    val compiled = compilation
    val model = compiled.validated.getOrElse(fail(compiled.validation.report.render))
    val view = StorySourceView.validated(model)

    val rawRecall = StorySource
      .fromText(
        "It was misty and completely still on the water.",
        Some("raw recall-style transcript")
      )
      .fold(e => fail(e.message), identity)
    val recall = RecallSegmenter.segment(rawRecall)
    val semantic = SemanticDistance.lexicalJaccard
    val candidates = CandidateGenerator(semantic).generate(recall.ordered, view)
    val result = GraphHsmm
      .infer(recall, view, candidates, DefaultLocalCostModel(semantic = semantic))
      .fold(e => fail(e.message), identity)
    val signature =
      RecallSignature.compute(result, recall, view).fold(e => fail(e.message), identity)

    assertEquals(compiled.derivation.gaps, Vector.empty)
    assertEquals(view.leaves.size, 1)
    assert(recall.ordered.nonEmpty)
    assert(candidates.totalSize > 0)
    assertEquals(signature.estimandVersion, RecallSignature.EstimandVersion)
    assert(signature.uniformCoverage.isFinite)
    assert(signature.externalMass.attributed.isFinite)
    assert(signature.externalMass.unranked.isFinite)
  }
