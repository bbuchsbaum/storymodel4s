package storymodel4s.acquire

import cats.data.NonEmptyVector
import org.scalacheck.{Arbitrary, Gen}
import storymodel4s.core.*

/** Shared fixtures and generators for the acquire suites. */
object Fixtures:
  val fp: Fingerprint = Fingerprint.unsafe("agent:test:1")
  val stage: StageId = StageId.unsafe("stage-local")
  val task: TaskId = TaskId.unsafe("task-1")
  val cfg: Checksum = Checksum.ofText("config")

  val manifest: PromptPackageManifest = PromptPackageManifest(
    name = "local-semantics",
    version = "1.0.0",
    role = "propose local propositional charts",
    inputSchemaId = "schema:sentence-window:1",
    outputSchemaId = "schema:chart-proposal:1",
    permittedOperations = Vector("add-concept", "add-relation", "set-focus"),
    prohibitedInferences = Vector("world-knowledge", "cross-sentence-identity"),
    standardsRefs = Vector(StandardsRef("amr-guidelines", "1.2.6", "4.3")),
    exampleIds = Vector("ex-1"),
    counterexampleIds = Vector("cx-1"),
    abstentionRules = Vector("abstain when the sentence has no predicate"),
    selfCheck = Vector("every concept cites a token id"),
    benchmarkSuiteId = "bench:local-semantics:1"
  )
  val promptRef: PromptPackageRef = manifest.ref

  val call: ProviderCall = ProviderCall(
    provider = "test",
    model = "stub",
    version = "1",
    promptTemplateVersion = Some("1.0.0"),
    inputChecksum = Checksum.ofText("in"),
    outputChecksum = Checksum.ofText("out"),
    params = Map.empty,
    seed = Some(7L),
    cached = false
  )
  val receipt: AgentCallReceipt = AgentCallReceipt(call, promptRef, task)

  def evidence(id: String, spans: Option[SpanSet] = None): Evidence =
    Evidence(EvidenceId.unsafe(id), spans, Set.empty, fp, stage)
  def ev(id: String): EvidenceRef = EvidenceRef.Inline(evidence(id))
  val someSpans: SpanSet = SpanSet.one(TextSpan.unsafe(0, 4))

  def proposed[A](v: A, score: Double, id: String = "e"): AgentProposal[A] =
    AgentProposal.proposed(
      task,
      v,
      NonEmptyVector.one(ev(id)),
      Some(RawScore.unsafe(score)),
      Vector.empty,
      receipt
    )
  def alternative[A](v: A, score: Double): AgentProposal[A] =
    AgentProposal.alternative(
      task,
      v,
      NonEmptyVector.one(ev("alt")),
      Some(RawScore.unsafe(score)),
      Vector.empty,
      receipt
    )

  def finding(code: FindingCode, family: CriticFamily = CriticFamily.FrameRole): CriticFinding =
    CriticFinding(task, family, code, Vector.empty, Vector.empty, None, None)

  def bundle[A](
      proposals: Vector[AgentProposal[A]],
      findings: Vector[CriticFinding] = Vector.empty,
      structural: StructuralValidity = StructuralValidity.Valid,
      support: Double = 1.0,
      calibrated: Option[Double] = Some(0.95)
  ): EvidenceBundle[A] =
    EvidenceBundle(
      proposals,
      findings,
      structural,
      SourceSupport(support, Some(someSpans)),
      agreementScore = 1.0,
      calibrated.map(Probability.unsafe)
    )

  // --- generators ---------------------------------------------------------------------------

  val idString: Gen[String] =
    Gen.nonEmptyListOf(Gen.oneOf(Gen.alphaNumChar, Gen.oneOf('-', '_'))).map(_.mkString)

  val disposition: Gen[ProposalDisposition] = Gen.oneOf(ProposalDisposition.values.toSeq)
  val findingCode: Gen[FindingCode] = Gen.oneOf(FindingCode.values.toSeq)
  val criticFamily: Gen[CriticFamily] = Gen.oneOf(CriticFamily.values.toSeq)
  val claimFamily: Gen[ClaimFamily] =
    Gen.oneOf(
      ClaimFamily.highImpact.toSeq ++ Seq(
        ClaimFamily.EntityMention,
        ClaimFamily.EntityCoreference,
        ClaimFamily.SituationMention,
        ClaimFamily.ParticipantRole,
        ClaimFamily.Modality,
        ClaimFamily.ContextAssignment,
        ClaimFamily.TemporalRelation,
        ClaimFamily.GoalRelation,
        ClaimFamily.StateChange,
        ClaimFamily.Reference,
        ClaimFamily.Boundary,
        ClaimFamily.Summary,
        ClaimFamily.DetailAtom
      )
    )
  val foilKind: Gen[FoilKind] = Gen.oneOf(FoilKind.values.toSeq)
  val probability: Gen[Probability] = Gen.chooseNum(0.0, 1.0).map(Probability.unsafe)
  val rawScore: Gen[RawScore] = Gen.chooseNum(-5.0, 5.0).map(RawScore.unsafe)

  val proposal: Gen[AgentProposal[Int]] = for
    d <- disposition
    v <- Gen.chooseNum(0, 3)
    s <- Gen.option(rawScore)
  yield d match
    case ProposalDisposition.Proposed    => proposed(v, s.map(_.value).getOrElse(0.0))
    case ProposalDisposition.Alternative => alternative(v, s.map(_.value).getOrElse(0.0))
    case ProposalDisposition.Abstained   => AgentProposal.abstained(task, receipt)
    case ProposalDisposition.Unsupported => AgentProposal.unsupported(task, Vector.empty, receipt)

  val criticFinding: Gen[CriticFinding] = for
    c <- findingCode
    f <- criticFamily
  yield finding(c, f)

  val evidenceBundle: Gen[EvidenceBundle[Int]] = for
    ps <- Gen.listOf(proposal).map(_.toVector)
    fs <- Gen.listOf(criticFinding).map(_.toVector)
    valid <- Gen.frequency(4 -> true, 1 -> false)
    support <- Gen.chooseNum(0.0, 1.0)
    cal <- Gen.option(Gen.chooseNum(0.0, 1.0))
  yield bundle(
    ps,
    fs,
    if valid then StructuralValidity.Valid else StructuralValidity.invalid("x"),
    support,
    cal
  )

  val foilOutcome: Gen[FoilOutcome] = for
    k <- foilKind
    p <- Gen.oneOf(true, false)
    cs <- Gen.listOf(findingCode).map(_.toVector)
  yield FoilOutcome(k, p, cs)

  given Arbitrary[AgentProposal[Int]] = Arbitrary(proposal)
  given Arbitrary[CriticFinding] = Arbitrary(criticFinding)
  given Arbitrary[EvidenceBundle[Int]] = Arbitrary(evidenceBundle)
  given Arbitrary[ClaimFamily] = Arbitrary(claimFamily)
  given Arbitrary[FoilOutcome] = Arbitrary(foilOutcome)
  given Arbitrary[Probability] = Arbitrary(probability)
