package storymodel4s.acquire

import cats.data.NonEmptyVector
import storymodel4s.core.*

private[acquire] object D1aAcquireFixtures:
  val film = SourceBundle
    .filmEdition(EditionId.unsafe("acquire-film"), Checksum.ofText("film"), 0L, 100L,
      RationalTimebase.Millisecond)
    .toOption.get
  val anchored = EvidenceSupport.media(
    film,
    film.streams.head.id,
    PlaybackIntervalSet.one(PlaybackInterval.on(film.primaryAxis, 0L, 20L).toOption.get)
  ).toOption.get
  val surface = SurfaceAnalyzer.analyze(StorySource.fromText("Alpha beta. Gamma.").toOption.get)
  val bound = BoundProposalSurface.of(surface,
    SourceDerivationReceipt.of("captions", "test", Vector(Checksum.ofText("film"))).toOption.get)
  val atlas = AnchoredNarrativeAtlas.of(film, Vector.empty, Some(bound)).toOption.get
  val noSurface = AnchoredNarrativeAtlas.of(film, Vector.empty).toOption.get

  def inline(id: String, text: Boolean = false, anchors: Boolean = false): EvidenceRef =
    EvidenceRef.Inline(Fixtures.evidence(id, Option.when(text)(Fixtures.someSpans))
      .copy(anchors = Option.when(anchors)(anchored)))

  def proposal(value: Int, evidence: EvidenceRef, provider: String = "one",
      alternative: Boolean = false, score: Option[Double] = Some(0.8)): AgentProposal[Int] =
    val raw = score.map(RawScore.unsafe(_, ScorerId.unsafe("d1a-s3")))
    if alternative then AgentProposal.alternative(Fixtures.task, value,
      NonEmptyVector.one(evidence), raw, Vector.empty, Fixtures.receiptFor(provider))
    else AgentProposal.proposed(Fixtures.task, value,
      NonEmptyVector.one(evidence), raw, Vector.empty, Fixtures.receiptFor(provider))

  val determined = AcceptanceBasis.Determined(RuleId.unsafe("d1a-s3-rule"))
  def calibrated(p: Double): AcceptanceBasis =
    AcceptanceBasis.Calibrated(Probability.unsafe(p), CalibrationModelId.unsafe("d1a-s3-fit"))

  def bundle(proposals: Vector[AgentProposal[Int]], support: Option[TypedSupport] = None,
      basis: AcceptanceBasis = determined): EvidenceBundle[Int] =
    EvidenceBundle(proposals, Vector.empty, StructuralValidity.Valid,
      SourceSupport(1.0, support), 1.0, Vector(CandidateBasis(1, basis)))

