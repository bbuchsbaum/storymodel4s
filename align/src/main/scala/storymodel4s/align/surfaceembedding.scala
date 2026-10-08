package storymodel4s.align

import storymodel4s.embed.*
import storymodel4s.features.{Estimate, MissingReason, UndefinedReason}
import storymodel4s.recall.*

/** Literal empty text is outside this surface measure; skipped inputs receive no request receipt.
  */
enum SurfaceEmptyPolicy:
  case EmptyIsIneligible

/** Invalid declared data or a mismatched orchestration context cannot become a missing score. */
enum SurfaceEmbeddingRefusal:
  case Geometry(error: EmbedError)
  case EndpointAssociation, ConflictingEndpoint, VectorGeometry, EmptyInputPolicy, ContextMismatch

/** A score retains both endpoint outcomes separately from the engine's coarse missing summary. */
type SurfaceScore = SurfaceEmbedding.Score

/** Declared vectors and bindings establish data consistency, never provider execution authority. */
type SurfaceScoringSession = SurfaceEmbedding.Session

/** Factorized declared data store O((U+N)d) vectors and calculate pair distances on demand.
  * Complete nomination performs O(UNd) distance arithmetic; endpoint lookups, hierarchy projections
  * and retained candidates have their own costs. No callback, source coordinate or all-pairs cache
  * belongs to the evaluator.
  */
object SurfaceEmbedding:
  type Outcome = Either[ExecutionFailure, Estimate[ValidatedVector]]

  /** This fixed domain summary does not collapse an execution failure into provider abstention. */
  val ExecutionFailed: MissingReason =
    MissingReason.Custom("embedding", "endpoint-execution-failed")

  /** Both endpoints and any distance-calculation error survive the scalar engine projection. */
  final class Score private[SurfaceEmbedding] (
      val query: Outcome,
      val document: Outcome,
      val estimate: Estimate[Double],
      val calculationError: Option[EmbedError]
  ):
    def failures: Vector[(Role, ExecutionFailure)] =
      query.left.toOption.map(Role.Query -> _).toVector ++
        document.left.toOption.map(Role.Document -> _).toVector
    override def equals(other: Any): Boolean = other match
      case s: Score =>
        query == s.query && document == s.document && estimate == s.estimate &&
        calculationError == s.calculationError
      case _ => false
    override def hashCode: Int =
      (
        query.isRight,
        document.isRight,
        estimate.isEligible,
        estimate.isObserved,
        calculationError.isDefined
      ).hashCode
    override def toString: String =
      s"SurfaceScore(observed=${estimate.isObserved}, failures=${failures.size}, calculationError=${calculationError.isDefined})"

  /** Immutable endpoint keys contain full canonical content and exact submitted text. */
  final class Data private[SurfaceEmbedding] (
      val querySpace: EmbeddingSpace,
      val documentSpace: EmbeddingSpace,
      val geometry: GeometryPair,
      val emptyPolicy: SurfaceEmptyPolicy,
      private val queries: Map[RenderedUnitInput, Outcome],
      private val documents: Map[RenderedTargetInput, Outcome]
  ):
    val metric: EmbeddingMetric = EmbeddingMetric.HalfCosineDistance
    def queryCount: Int = queries.size
    def documentCount: Int = documents.size

    def evaluate(pair: RenderedContentPair): Either[SurfaceEmbeddingRefusal, SurfaceScore] =
      for
        q <- queries.get(pair.query).toRight(SurfaceEmbeddingRefusal.EndpointAssociation)
        d <- documents.get(pair.document).toRight(SurfaceEmbeddingRefusal.EndpointAssociation)
      yield score(q, d)

    override def equals(other: Any): Boolean = other match
      case d: Data =>
        querySpace == d.querySpace && documentSpace == d.documentSpace &&
        geometry == d.geometry && emptyPolicy == d.emptyPolicy && queries == d.queries && documents == d.documents
      case _ => false
    // Equality keeps values/text, but public hashing adds no plain private vector/text digest.
    override def hashCode: Int =
      (querySpace.id, documentSpace.id, emptyPolicy, queryCount, documentCount).hashCode
    override def toString: String =
      s"SurfaceEmbeddingData(queries=$queryCount, documents=$documentCount, metric=${metric.tag})"

  /** The orchestration context remains outside the coordinate-free declared channel. */
  final class Session private[SurfaceEmbedding] (
      val context: SurfaceScoringContext,
      val channel: StrictSemanticChannel.FactorizedSurface
  ):
    def evaluate(
        unit: RecallUnitId,
        target: SourceNodeRef
    ): Either[SurfaceEmbeddingRefusal, SurfaceScore] =
      context
        .pair(unit, target)
        .left
        .map(_ => SurfaceEmbeddingRefusal.ContextMismatch)
        .flatMap(channel.data.evaluate)

    private[align] def evaluate(
        unit: RecallUnit,
        node: NodeSummary
    ): Either[SurfaceEmbeddingRefusal, SurfaceScore] =
      if context.contains(unit, node) then evaluate(unit.id, node.ref)
      else Left(SurfaceEmbeddingRefusal.ContextMismatch)

    override def equals(other: Any): Boolean = other match
      case s: Session => context == s.context && channel == s.channel
      case _          => false
    override def hashCode: Int = (context, channel).hashCode
    override def toString: String = s"SurfaceScoringSession($context)"

  /** Validate complete ordered endpoint association before forming a declared factorized channel.
    * Identical keys with different outcomes are refused rather than chosen by insertion order.
    */
  def declared(
      context: SurfaceScoringContext,
      querySpace: EmbeddingSpace,
      documentSpace: EmbeddingSpace,
      queries: Vector[(RenderedUnitInput, Outcome)],
      documents: Vector[(RenderedTargetInput, Outcome)],
      emptyPolicy: SurfaceEmptyPolicy
  ): Either[SurfaceEmbeddingRefusal, SurfaceScoringSession] =
    def valid(outcome: Outcome, space: EmbeddingSpace): Boolean = outcome match
      case Right(Estimate.Observed(v, _)) =>
        v.dimension == space.dimension && v.normalization == space.normalization
      case _ => true
    def empty(text: String, outcome: Outcome): Boolean =
      text.nonEmpty || outcome == Right(Estimate.Ineligible)
    for
      geometry <- GeometryPair
        .validated(querySpace, documentSpace, GeometryPairRule.IdenticalModelling)
        .left
        .map(SurfaceEmbeddingRefusal.Geometry.apply)
      _ <-
        if querySpace.view == SemanticView.Surface && documentSpace.view == SemanticView.Surface
        then Right(())
        else Left(SurfaceEmbeddingRefusal.VectorGeometry)
      _ <-
        if queries.map(_._1) == context.queries.map(_._2) &&
          documents.map(_._1) == context.documents.map(_._2)
        then Right(())
        else Left(SurfaceEmbeddingRefusal.EndpointAssociation)
      _ <-
        if queries.forall((_, v) => valid(v, querySpace)) && documents
            .forall((_, v) => valid(v, documentSpace))
        then Right(())
        else Left(SurfaceEmbeddingRefusal.VectorGeometry)
      _ <-
        if queries.forall((k, v) => empty(k.text, v)) && documents
            .forall((k, v) => empty(k.text, v))
        then Right(())
        else Left(SurfaceEmbeddingRefusal.EmptyInputPolicy)
      _ <-
        if queries.groupMap(_._1)(_._2).values.forall(_.distinct.size <= 1) &&
          documents.groupMap(_._1)(_._2).values.forall(_.distinct.size <= 1)
        then Right(())
        else Left(SurfaceEmbeddingRefusal.ConflictingEndpoint)
      data = new Data(
        querySpace,
        documentSpace,
        geometry,
        emptyPolicy,
        queries.toMap,
        documents.toMap
      )
    yield new Session(context, StrictSemanticChannel.FactorizedSurface.declared(data))

  private def score(query: Outcome, document: Outcome): SurfaceScore =
    val ineligible = query == Right(Estimate.Ineligible) || document == Right(Estimate.Ineligible)
    if ineligible then new Score(query, document, Estimate.Ineligible, None)
    else if query.isLeft || document.isLeft then
      new Score(query, document, Estimate.missing(ExecutionFailed), None)
    else
      (query.toOption.get, document.toOption.get) match
        case (Estimate.Observed(a, _), Estimate.Observed(b, _)) =>
          Distances.cosine(a, b) match
            case Left(error) =>
              new Score(
                query,
                document,
                Estimate.missing(MissingReason.Undefined(UndefinedReason.NotFinite)),
                Some(error)
              )
            case Right(distance) =>
              // Cosine validates its output; half-cosine retains the explicit scale conversion.
              EmbeddingMetric.HalfCosineDistance.fromCosine(distance.value) match
                case Left(_) =>
                  new Score(
                    query,
                    document,
                    Estimate.missing(MissingReason.Undefined(UndefinedReason.NotFinite)),
                    Some(EmbedError.InvalidDistance(distance.value))
                  )
                case Right(value) =>
                  new Score(query, document, Estimate.observed(value.value), None)
        case (Estimate.Missing(reason), _) =>
          new Score(query, document, Estimate.missing(reason), None)
        case (_, Estimate.Missing(reason)) =>
          new Score(query, document, Estimate.missing(reason), None)
        case _ => new Score(query, document, Estimate.Ineligible, None)
