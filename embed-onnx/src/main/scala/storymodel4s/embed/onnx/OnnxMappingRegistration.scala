package storymodel4s.embed.onnx

import storymodel4s.align.*
import storymodel4s.embed.*
import storymodel4s.features.Estimate
import storymodel4s.recall.RecallUnitId

/** Registration errors do not promote failed association or raw-scale pricing to evidence. */
enum OnnxMappingRefusal:
  case StrictMetric, CandidateSpace, Configuration, Context
  case Geometry(error: EmbedError)
  case OutcomeAssociation
  case Replay(reason: OnnxReplayRefusal)
  case Surface(reason: SurfaceEmbeddingRefusal)
  case Candidates(reason: CandidateRefusal)
  case Pricing(reason: AlignError)

/** Replay derives data from original attempts and never represents a fresh native call. */
enum OnnxMappingBasis:
  case Recorded, Replayed

/** Concrete ONNX registration owns execution authority; portable declared vectors do not. */
object OnnxMappingRegistration:
  /** Checked static parameters make scale, sensitivity and pricing decisions explicit. */
  final class Config private[OnnxMappingRegistration] (
      val candidates: StrictCandidateConfig,
      val querySensitivity: Sensitivity,
      val documentSensitivity: Sensitivity,
      val metric: EmbeddingMetric,
      val emptyPolicy: SurfaceEmptyPolicy,
      val pricing: StrictCostModel
  ):
    private def parts = (
      candidateSignature(candidates),
      querySensitivity,
      documentSensitivity,
      metric,
      emptyPolicy,
      pricingSignature(pricing)
    )
    override def equals(other: Any): Boolean = other match
      case c: Config => parts == c.parts
      case _         => false
    override def hashCode: Int = parts.hashCode
    override def toString: String = s"OnnxMappingConfig(metric=${metric.tag}, empty=$emptyPolicy)"

  object Config:
    def of(
        candidates: StrictCandidateConfig,
        querySensitivity: Sensitivity,
        documentSensitivity: Sensitivity,
        metric: EmbeddingMetric = EmbeddingMetric.HalfCosineDistance,
        emptyPolicy: SurfaceEmptyPolicy = SurfaceEmptyPolicy.EmptyIsIneligible,
        weights: CostWeights = CostWeights.default,
        functionPrior: FunctionPrior = FunctionPrior.default,
        externalFloor: Double = 1.0,
        externalMismatch: Double = 0.5,
        missingSemantic: Double = 0.5,
        distortionPenalty: Double = 0.3
    ): Either[OnnxMappingRefusal, Config] =
      if metric != EmbeddingMetric.HalfCosineDistance then Left(OnnxMappingRefusal.StrictMetric)
      else
        StrictCostModel
          .of(
            StrictSemanticChannel.Unavailable,
            weights,
            functionPrior,
            externalFloor,
            externalMismatch,
            missingSemantic,
            distortionPenalty
          )
          .left
          .map(OnnxMappingRefusal.Pricing.apply)
          .map(p =>
            new Config(candidates, querySensitivity, documentSensitivity, metric, emptyPolicy, p)
          )

  /** Only actual concrete recordings or their exact capability replay derive this envelope. It
    * keeps execution identity separate from the engine's numerical evidence identity.
    */
  final class Registered private[OnnxMappingRegistration] (
      val context: SurfaceScoringContext,
      val config: Config,
      val queryRecord: OnnxSentenceEmbedder.RecordedBatch,
      val documentRecord: OnnxSentenceEmbedder.RecordedBatch,
      val session: SurfaceScoringSession,
      val effectiveCandidates: StrictCandidateConfig,
      val candidates: StrictCandidates,
      val pricing: StrictCostModel,
      val evidence: LocalEvidence,
      val basis: OnnxMappingBasis
  ):
    def skippedQueries: Int = context.queries.count(_._2.text.isEmpty)
    def skippedDocuments: Int = context.documents.count(_._2.text.isEmpty)

    def score(
        unit: RecallUnitId,
        target: SourceNodeRef
    ): Either[SurfaceEmbeddingRefusal, SurfaceScore] =
      session.evaluate(unit, target)

    /** Exact registration replay checks context/configuration before original capability replay. */
    def replay(
        expectedContext: SurfaceScoringContext,
        expectedConfig: Config,
        expectedModel: OnnxSentenceModel,
        expectedProvider: ProviderFingerprint
    ): Either[OnnxMappingRefusal, Registered] =
      if expectedContext != context then Left(OnnxMappingRefusal.Context)
      else if expectedConfig != config then Left(OnnxMappingRefusal.Configuration)
      else
        OnnxMappingRegistration.replay(
          expectedContext,
          expectedConfig,
          expectedModel,
          expectedProvider,
          queryRecord,
          documentRecord
        )

    override def equals(other: Any): Boolean = other match
      case r: Registered =>
        context == r.context && config == r.config && queryRecord == r.queryRecord &&
        documentRecord == r.documentRecord && session == r.session && candidates.binding == r.candidates.binding &&
        candidateSignature(effectiveCandidates) == candidateSignature(r.effectiveCandidates) &&
        pricingSignature(pricing) == pricingSignature(
          r.pricing
        ) && evidence == r.evidence && basis == r.basis
      case _ => false
    // Never introduce a plain hash of private captured text or endpoint vectors.
    override def hashCode: Int = (config, queryRecord, documentRecord, basis).hashCode
    override def toString: String =
      s"OnnxMappingRegistration(basis=$basis, queries=${context.queries.size}, documents=${context.documents.size})"

  /** Build requests from the complete bound inventory and invoke the concrete adapter itself. */
  def record(
      encoder: OnnxSentenceEmbedder,
      context: SurfaceScoringContext,
      config: Config
  ): Either[OnnxMappingRefusal, Registered] =
    for
      spaces <- geometry(encoder.spaces)
      effective <- candidateConfig(config, spaces._1)
      query <- batch(
        context.queries.map(_._2.text),
        spaces._1,
        "query",
        config.querySensitivity,
        encoder.spaceIds
      )
      document <- batch(
        context.documents.map(_._2.text),
        spaces._2,
        "document",
        config.documentSensitivity,
        encoder.spaceIds
      )
      registered <-
        val q = encoder.record(query)
        val d = encoder.record(document)
        derive(
          context,
          config,
          encoder.model,
          encoder.info.provider,
          q,
          d,
          spaces,
          effective,
          query,
          document,
          OnnxMappingBasis.Recorded
        )
    yield registered

  /** Genuine unrelated capabilities cannot be attached to an arbitrary declared table/evidence.
    * Expected requests are rebuilt here, including exact text, sensitivity, IDs and geometry.
    */
  private def replay(
      context: SurfaceScoringContext,
      config: Config,
      expectedModel: OnnxSentenceModel,
      expectedProvider: ProviderFingerprint,
      queryRecord: OnnxSentenceEmbedder.RecordedBatch,
      documentRecord: OnnxSentenceEmbedder.RecordedBatch
  ): Either[OnnxMappingRefusal, Registered] =
    for
      _ <-
        if queryRecord.spaces == documentRecord.spaces &&
          queryRecord.runtimeIdentity == documentRecord.runtimeIdentity
        then Right(())
        else Left(OnnxMappingRefusal.OutcomeAssociation)
      spaces <- geometry(queryRecord.spaces)
      effective <- candidateConfig(config, spaces._1)
      known = queryRecord.spaces.map(_.id).toSet
      query <- batch(
        context.queries.map(_._2.text),
        spaces._1,
        "query",
        config.querySensitivity,
        known
      )
      document <- batch(
        context.documents.map(_._2.text),
        spaces._2,
        "document",
        config.documentSensitivity,
        known
      )
      registered <- derive(
        context,
        config,
        expectedModel,
        expectedProvider,
        queryRecord,
        documentRecord,
        spaces,
        effective,
        query,
        document,
        OnnxMappingBasis.Replayed
      )
    yield registered

  private def geometry(
      spaces: Vector[EmbeddingSpace]
  ): Either[OnnxMappingRefusal, (EmbeddingSpace, EmbeddingSpace)] =
    (spaces.filter(_.role == Role.Query), spaces.filter(_.role == Role.Document)) match
      case (Vector(query), Vector(document)) =>
        GeometryPair
          .validated(query, document, GeometryPairRule.IdenticalModelling)
          .left
          .map(OnnxMappingRefusal.Geometry.apply)
          .map(_ => query -> document)
      case _ => Left(OnnxMappingRefusal.OutcomeAssociation)

  private def candidateConfig(
      config: Config,
      query: EmbeddingSpace
  ): Either[OnnxMappingRefusal, StrictCandidateConfig] =
    if config.candidates.space.exists(_ != query.id.value) then
      Left(OnnxMappingRefusal.CandidateSpace)
    else
      val budget = config.candidates.policy.budget match
        case TieBudget.Unbounded => TieBudgetRequest.Unbounded
        case b: TieBudget.AtMost => TieBudgetRequest.AtMost(b.n)
      StrictCandidateConfig
        .of(
          config.candidates.perLevel,
          budget,
          config.candidates.lexicalOverlap,
          Some(query.id.value)
        )
        .left
        .map(OnnxMappingRefusal.Candidates.apply)

  private def batch(
      texts: Vector[String],
      space: EmbeddingSpace,
      side: String,
      sensitivity: Sensitivity,
      known: Set[GeometryId]
  ): Either[OnnxMappingRefusal, EmbedBatch] =
    val requests = texts.zipWithIndex.collect {
      case (text, i) if text.nonEmpty =>
        EmbedRequest(
          RequestId.unsafe(s"surface-$side-$i"),
          EmbedPayload.Raw(text, sensitivity),
          space.id
        )
    }
    EmbedBatch.validated(requests, known).left.map(OnnxMappingRefusal.Geometry.apply)

  private def associate(
      texts: Vector[String],
      batch: EmbedBatch,
      result: BatchResult
  ): Either[OnnxMappingRefusal, Vector[SurfaceEmbedding.Outcome]] =
    if result.outcomes.map(_.id) != batch.ids ||
      result.outcomes.map(_.space) != batch.requests.map(_.space)
    then Left(OnnxMappingRefusal.OutcomeAssociation)
    else
      var at = 0
      Right(texts.map { text =>
        if text.isEmpty then Right(Estimate.Ineligible)
        else
          val value = result.outcomes(at).value
          at += 1
          value
      })

  private def derive(
      context: SurfaceScoringContext,
      config: Config,
      expectedModel: OnnxSentenceModel,
      expectedProvider: ProviderFingerprint,
      queryRecord: OnnxSentenceEmbedder.RecordedBatch,
      documentRecord: OnnxSentenceEmbedder.RecordedBatch,
      spaces: (EmbeddingSpace, EmbeddingSpace),
      effective: StrictCandidateConfig,
      queryBatch: EmbedBatch,
      documentBatch: EmbedBatch,
      basis: OnnxMappingBasis
  ): Either[OnnxMappingRefusal, Registered] =
    for
      q <- queryRecord
        .replay(queryBatch, expectedModel, expectedProvider)
        .left
        .map(OnnxMappingRefusal.Replay.apply)
      d <- documentRecord
        .replay(documentBatch, expectedModel, expectedProvider)
        .left
        .map(OnnxMappingRefusal.Replay.apply)
      queryValues <- associate(context.queries.map(_._2.text), queryBatch, q)
      documentValues <- associate(context.documents.map(_._2.text), documentBatch, d)
      session <- SurfaceEmbedding
        .declared(
          context,
          spaces._1,
          spaces._2,
          context.queries.map(_._2).zip(queryValues),
          context.documents.map(_._2).zip(documentValues),
          config.emptyPolicy
        )
        .left
        .map(OnnxMappingRefusal.Surface.apply)
      candidates <- SurfaceCandidateGenerator
        .generate(session, effective)
        .left
        .map(OnnxMappingRefusal.Candidates.apply)
      pricing <-
        val p = config.pricing
        StrictCostModel
          .of(
            session.channel,
            p.weights,
            p.functionPrior,
            p.externalFloor,
            p.externalMismatch,
            p.missingSemantic,
            p.distortionPenalty,
            Some(session)
          )
          .left
          .map(OnnxMappingRefusal.Pricing.apply)
      evidence <- LocalEvidence
        .compute(context.recall, context.source, candidates, pricing)
        .left
        .map(OnnxMappingRefusal.Pricing.apply)
    yield new Registered(
      context,
      config,
      queryRecord,
      documentRecord,
      session,
      effective,
      candidates,
      pricing,
      evidence,
      basis
    )

  private def candidateSignature(config: StrictCandidateConfig) =
    (config.perLevel, config.policy, config.lexicalOverlap, config.space)

  private def pricingSignature(model: StrictCostModel) =
    (
      model.weights,
      model.functionPrior,
      model.externalFloor,
      model.externalMismatch,
      model.missingSemantic,
      model.distortionPenalty
    )
