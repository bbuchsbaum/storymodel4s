package docsprobe

import cats.data.NonEmptyVector
import storymodel4s.acquire.*
import storymodel4s.acquire.ResolutionState.*
import storymodel4s.core.*
import storymodel4s.proposition.Polarity

@main def resolveAcquisition(): Unit =
  val task = TaskId.unsafe("task-polarity-1")
  val spans = SpanSet.one(TextSpan.unsafe(0, 20))
  val prompt = PromptPackageRef("polarity-demo", "1.0.0", Checksum.ofText("polarity-demo"))

  def receipt(provider: String, output: String): AgentCallReceipt =
    AgentCallReceipt(
      ProviderCall(
        provider = provider,
        model = "local-demo",
        version = "1",
        promptTemplateVersion = None,
        inputChecksum = Checksum.ofText("He did not feel sick."),
        outputChecksum = Checksum.ofText(output),
        params = Map.empty,
        seed = Some(7L),
        cached = false
      ),
      prompt,
      task
    )

  def proposal(value: Polarity, provider: String, score: Double): AgentProposal[Polarity] =
    val evidence = Evidence(
      EvidenceId.unsafe(s"evidence-$provider"),
      Some(spans),
      Set.empty,
      Fingerprint.unsafe(s"extractor:$provider:1"),
      StageId.unsafe("stage-local-semantics")
    )
    AgentProposal.proposed(
      task,
      value,
      NonEmptyVector.one(EvidenceRef.Inline(evidence)),
      Some(RawScore.unsafe(score, ScorerId.unsafe("test-scorer"))),
      Vector.empty,
      receipt(provider, value.toString)
    )

  def bundle(
      proposals: Vector[AgentProposal[Polarity]],
      structural: StructuralValidity = StructuralValidity.Valid,
      bases: Vector[CandidateBasis[Polarity]] = Vector.empty
  ): EvidenceBundle[Polarity] =
    EvidenceBundle(
      proposals,
      findings = Vector.empty,
      structural,
      SourceSupport(score = 1.0, spans = Some(spans)),
      agreementScore = 1.0,
      bases
    )

  val parserNegative = proposal(Polarity.Negative, "parser", 0.91)
  val agentNegative = proposal(Polarity.Negative, "agent", 0.87)
  val agentPositive = proposal(Polarity.Positive, "agent", 0.71)
  val negativeBasis = Vector(
    CandidateBasis(Polarity.Negative, AcceptanceBasis.Calibrated(Probability.unsafe(0.96), CalibrationModelId.unsafe("polarity-calibration-v1")))
  )

  val cases = Vector(
    "accepted" -> bundle(Vector(parserNegative, agentNegative), bases = negativeBasis),
    "alternatives" -> bundle(Vector(parserNegative, agentPositive)),
    "unresolved" -> bundle(Vector(parserNegative), bases = negativeBasis),
    "rejected" -> bundle(
      Vector(parserNegative, agentNegative),
      structural = StructuralValidity.invalid("polarity target is not a predicate"),
      bases = negativeBasis
    )
  )

  def render(state: ResolutionState[Polarity]): String = state match
    case Accepted(value, basis, evidence) =>
      s"Accepted(value=$value, basis=${basis.render}, evidence=${evidence.length})"
    case Alternatives(values) =>
      val rows = values.toVector.map { weighted =>
        val score = weighted.score.fold("missing")(s => f"${s.value}%.2f")
        s"${weighted.value}:providers=${weighted.providers}:raw=$score"
      }
      s"Alternatives(${rows.mkString(", ")})"
    case Unresolved(reason) => s"Unresolved($reason)"
    case Rejected(reason)   => s"Rejected($reason)"

  cases.foreach { case (label, evidence) =>
    println(s"$label: ${render(Resolver.resolve(ClaimFamily.Polarity, evidence, AcceptancePolicy.Conservative))}")
  }
