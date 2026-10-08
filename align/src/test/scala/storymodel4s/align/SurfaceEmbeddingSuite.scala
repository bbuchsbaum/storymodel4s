package storymodel4s.align

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.embed.*
import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.recall.*

/** Independent unit vectors and synthetic text discriminate association, scale and binding. */
class SurfaceEmbeddingSuite extends FunSuite:
  private def value[A, E](a: Either[E, A]): A = a.fold(e => fail(s"refused: $e"), identity)
  private val dimension = Dimension.unsafe(2)
  private val provider = ProviderFingerprint(Checksum.ofText("synthetic surface provider"))
  private def space(
      role: Role,
      dim: Dimension = dimension,
      normalization: Normalization = Normalization.L2
  ) =
    value(
      EmbeddingSpace.of(
        provider,
        role,
        SemanticView.Surface,
        None,
        dim,
        normalization,
        TruncationPolicy.Reject
      )
    )
  private val querySpace = space(Role.Query)
  private val documentSpace = space(Role.Document)
  private def observed(x: Double, y: Double): SurfaceEmbedding.Outcome =
    Right(Estimate.observed(value(ValidatedVector.l2(dimension, Vector(x, y)))))

  private def context(
      sourceText: String = "red blue",
      duplicate: Boolean = false,
      empty: Boolean = false,
      renamed: Boolean = false,
      reverse: Boolean = false
  ) =
    val transcript = value(StorySource.fromText("red"))
    val ra = SurfaceAnalyzer.analyze(transcript)
    val unit = RecallUnit(
      RecallUnitId.unsafe(if renamed then "renamed" else "unit"),
      0,
      SpanSet.one(TextSpan.unsafe(0, if empty then 0 else 3)),
      if empty then "" else "red",
      DiscourseFunction.EpisodicAssertion,
      ExpressedUncertainty.Unmarked,
      PropositionSketch.empty,
      None
    )
    val recall = RecallGraph
      .validated(transcript, ra, Vector(unit), RecallRelations.empty)
      .fold(e => fail(s"recall refused: $e"), identity)
    val source = value(StorySource.fromText(sourceText))
    val atlas = SurfaceAnalyzer.analyze(source)
    val ranges = if duplicate then Vector(0 -> 3, 0 -> 3) else Vector(0 -> 3, 4 -> 8)
    val nodes = ranges.zipWithIndex.map { (r, i) =>
      NodeSummary(
        SourceNodeRef.Situation(SituationId.unsafe(if renamed then s"new-$i" else s"node-$i")),
        0,
        None,
        i,
        SpanSet.one(TextSpan.unsafe(r._1, r._2)),
        None,
        Vector.empty,
        ContextTag.NarratedWorld,
        PolarityTag.Unknown,
        ModalityTag.Unknown,
        Vector.empty,
        Set.empty
      )
    }
    val view = InMemorySourceView(
      if reverse then nodes.reverse else nodes,
      Map.empty,
      None,
      sourceText.length
    )
    val representation = value(
      SourceRepresentation.of(
        view,
        NonEmptyVector.one(BundleEntry.text(source.canonicalChecksum)),
        None,
        nodes.map(n => n.ref -> SourceSupportStatus.located(n.support)).toMap
      )
    )
    value(SurfaceRendering.prepare(recall, view, representation, atlas))

  private def session(
      ctx: SurfaceScoringContext,
      q: Vector[SurfaceEmbedding.Outcome] = Vector(observed(1, 0)),
      d: Vector[SurfaceEmbedding.Outcome] = Vector(observed(0, 1), observed(-1, 0))
  ) = value(
    SurfaceEmbedding.declared(
      ctx,
      querySpace,
      documentSpace,
      ctx.queries.map(_._2).zip(q),
      ctx.documents.map(_._2).zip(d),
      SurfaceEmptyPolicy.EmptyIsIneligible
    )
  )
  private val config = value(StrictCandidateConfig.of(1, TieBudgetRequest.AtMost(8), false, None))

  test("full rendered keys distinguish equal semantic content with different text") {
    val ctx = context()
    assertEquals(ctx.documents(0)._2.content, ctx.documents(1)._2.content)
    assertNotEquals(ctx.documents(0)._2, ctx.documents(1)._2)
    val s = session(ctx)
    assertEquals(
      value(s.evaluate(ctx.queries.head._1, ctx.documents(0)._1)).estimate,
      Estimate.observed(0.5)
    )
    assertEquals(
      value(s.evaluate(ctx.queries.head._1, ctx.documents(1)._1)).estimate,
      Estimate.observed(1.0)
    )
    assertEquals(s.channel.data.queryCount, 1)
    assertEquals(s.channel.data.documentCount, 2)
  }

  test("complete ordered endpoint association refuses missing extra and reordered keys") {
    val ctx = context()
    val q = ctx.queries.map(_._2).map(_ -> observed(1, 0))
    val d = ctx.documents.map(_._2).zip(Vector(observed(0, 1), observed(-1, 0)))
    for bad <- Vector(d.take(1), d :+ d.head, d.reverse) do
      assertEquals(
        SurfaceEmbedding
          .declared(ctx, querySpace, documentSpace, q, bad, SurfaceEmptyPolicy.EmptyIsIneligible),
        Left(SurfaceEmbeddingRefusal.EndpointAssociation)
      )
  }

  test("identical rendered keys refuse conflicting outcomes rather than insertion order") {
    val ctx = context(duplicate = true)
    assertEquals(ctx.documents(0)._2, ctx.documents(1)._2)
    val q = ctx.queries.map(_._2).map(_ -> observed(1, 0))
    val d = ctx.documents.map(_._2).zip(Vector(observed(0, 1), observed(-1, 0)))
    assertEquals(
      SurfaceEmbedding
        .declared(ctx, querySpace, documentSpace, q, d, SurfaceEmptyPolicy.EmptyIsIneligible),
      Left(SurfaceEmbeddingRefusal.ConflictingEndpoint)
    )
    assertEquals(
      session(ctx, d = Vector(observed(0, 1), observed(0, 1))).channel.data.documentCount,
      1
    )
  }

  test("endpoint vectors must match their checked role geometry") {
    val ctx = context()
    val q = ctx.queries.map(_._2).map(_ -> observed(1, 0))
    val d = ctx.documents.map(_._2).map(_ -> observed(0, 1))
    assert(
      SurfaceEmbedding
        .declared(ctx, documentSpace, querySpace, q, d, SurfaceEmptyPolicy.EmptyIsIneligible)
        .isLeft
    )
    assert(
      SurfaceEmbedding
        .declared(
          ctx,
          querySpace,
          space(Role.Document, Dimension.unsafe(3)),
          q,
          d,
          SurfaceEmptyPolicy.EmptyIsIneligible
        )
        .isLeft
    )
    val wrong =
      Right(Estimate.observed(value(ValidatedVector.l2(Dimension.unsafe(3), Vector(1, 0, 0)))))
    assertEquals(
      SurfaceEmbedding.declared(
        ctx,
        querySpace,
        documentSpace,
        ctx.queries.map(_._2).map(_ -> wrong),
        d,
        SurfaceEmptyPolicy.EmptyIsIneligible
      ),
      Left(SurfaceEmbeddingRefusal.VectorGeometry)
    )
  }

  test("both endpoint failures survive a truthful coarse engine summary") {
    val ctx = context()
    val queryFailure = ExecutionFailure.TooLong(7, 4)
    val documentFailure = ExecutionFailure.ProviderError("synthetic-failure", 1)
    val s = session(ctx, Vector(Left(queryFailure)), Vector(Left(documentFailure), observed(1, 0)))
    val score = value(s.evaluate(ctx.queries.head._1, ctx.documents.head._1))
    assertEquals(score.estimate, Estimate.missing(SurfaceEmbedding.ExecutionFailed))
    assertEquals(
      score.failures,
      Vector(Role.Query -> queryFailure, Role.Document -> documentFailure)
    )
    assertEquals(score.query, Left(queryFailure))
    assertEquals(score.document, Left(documentFailure))
    assertEquals(score.calculationError, None)
  }

  test("missing and ineligible endpoint outcomes remain distinguishable") {
    val ctx = context()
    val missingQ = Right(Estimate.missing(MissingReason.OutOfVocabulary))
    val missingD = Right(Estimate.missing(MissingReason.AllMissing))
    val score = value(
      session(ctx, Vector(missingQ), Vector(missingD, observed(1, 0)))
        .evaluate(ctx.queries.head._1, ctx.documents.head._1)
    )
    assertEquals(score.estimate, Estimate.missing(MissingReason.OutOfVocabulary))
    assertEquals(score.document, missingD)
    val absent = value(
      session(
        ctx,
        Vector(Right(Estimate.Ineligible)),
        Vector(Left(ExecutionFailure.Transport("synthetic")), observed(1, 0))
      )
        .evaluate(ctx.queries.head._1, ctx.documents.head._1)
    )
    assertEquals(absent.estimate, Estimate.Ineligible)
    assertEquals(absent.failures, Vector(Role.Document -> ExecutionFailure.Transport("synthetic")))
  }

  test("calculation failure is retained separately from endpoint execution failure") {
    val ctx = context()
    val q = space(Role.Query, normalization = Normalization.Unnormalized)
    val d = space(Role.Document, normalization = Normalization.Unnormalized)
    val zero = Right(
      Estimate.observed(
        value(ValidatedVector.of(dimension, Normalization.Unnormalized, Vector(0, 0)))
      )
    )
    val s = value(
      SurfaceEmbedding.declared(
        ctx,
        q,
        d,
        ctx.queries.map(_._2).map(_ -> zero),
        ctx.documents.map(_._2).map(_ -> zero),
        SurfaceEmptyPolicy.EmptyIsIneligible
      )
    )
    val score = value(s.evaluate(ctx.queries.head._1, ctx.documents.head._1))
    assertEquals(score.failures, Vector.empty)
    assert(score.calculationError.nonEmpty)
    assert(!score.estimate.isObserved)
  }

  test("literal empty text must be ineligible and cannot acquire a vector") {
    val ctx = context(empty = true)
    assertEquals(
      SurfaceEmbedding.declared(
        ctx,
        querySpace,
        documentSpace,
        ctx.queries.map(_._2).map(_ -> observed(1, 0)),
        ctx.documents.map(_._2).map(_ -> observed(1, 0)),
        SurfaceEmptyPolicy.EmptyIsIneligible
      ),
      Left(SurfaceEmbeddingRefusal.EmptyInputPolicy)
    )
    val s = session(ctx, Vector(Right(Estimate.Ineligible)))
    assertEquals(
      value(s.evaluate(ctx.queries.head._1, ctx.documents.head._1)).estimate,
      Estimate.Ineligible
    )
  }

  test("context-free generation and pricing refuse a factorized channel") {
    val ctx = context()
    val s = session(ctx)
    assertEquals(
      StrictCandidateGenerator.canonical(s.channel, config, ctx.recall, ctx.source),
      Left(CandidateRefusal.SurfaceContextRequired)
    )
    assert(StrictCostModel.of(s.channel).isLeft)
    assert(StrictCostModel.of(StrictSemanticChannel.Lexical, surfaceSession = Some(s)).isLeft)
  }

  test("nomination and pricing use exactly the same normalized endpoint evaluation") {
    val ctx = context()
    val s = session(ctx)
    val candidates = value(SurfaceCandidateGenerator.generate(s, config))
    val model = value(StrictCostModel.of(s.channel, surfaceSession = Some(s)))
    val evidence = value(LocalEvidence.compute(ctx.recall, ctx.source, candidates, model))
    val nomination = candidates.get(ctx.queries.head._1).get.set.nominations.head
    assertEquals(nomination.ref, ctx.documents.head._1)
    assertEquals(nomination.rawScore, Some(0.5))
    val price = evidence.breakdowns.head(AlignState.anchored(nomination.ref, FidelityMode.Faithful))
    assertEquals(price.term(CostTerm.Semantic), 0.5)
    assertEquals(
      value(s.evaluate(ctx.queries.head._1, nomination.ref)).estimate,
      Estimate.observed(0.5)
    )
  }

  test("matching numerical tables cannot substitute a different source text context") {
    val first = context("red blue unused")
    val other = context("red blue change")
    assertEquals(first.documents, other.documents)
    val a = session(first)
    val b = session(other)
    assertEquals(a.channel, b.channel)
    assertNotEquals(first, other)
    val candidates = value(SurfaceCandidateGenerator.generate(a, config))
    val model = value(StrictCostModel.of(b.channel, surfaceSession = Some(b)))
    assert(LocalEvidence.compute(first.recall, first.source, candidates, model).isLeft)
  }

  test("renaming identifiers and reversing storage preserve content scores and tie completion") {
    val original = context()
    val changed = context(renamed = true, reverse = true)
    val a = session(original, d = Vector(observed(0, 1), observed(0, 1)))
    val b = session(changed, d = Vector(observed(0, 1), observed(0, 1)))
    val ac = value(SurfaceCandidateGenerator.generate(a, config)).get(original.queries.head._1).get
    val bc = value(SurfaceCandidateGenerator.generate(b, config)).get(changed.queries.head._1).get
    assertEquals(ac.set.nominations.map(_.rawScore), Vector(Some(0.5), Some(0.5)))
    assertEquals(bc.set.nominations.map(_.rawScore), ac.set.nominations.map(_.rawScore))
    assertEquals(ac.uniformSemantic.map(_.scoredCount), Vector(2))
    assertEquals(bc.uniformSemantic, ac.uniformSemantic)
    val bounded = value(StrictCandidateConfig.of(1, TieBudgetRequest.AtMost(1), false, None))
    val ao = value(SurfaceCandidateGenerator.generate(a, bounded)).get(original.queries.head._1).get
    val bo = value(SurfaceCandidateGenerator.generate(b, bounded)).get(changed.queries.head._1).get
    assertEquals(ao.overflow, bo.overflow)
    assertEquals(ao.overflow.map(_.unionSize), Vector(2))
    assertEquals(ao.set.nominations, Vector.empty)
  }

  test("empty declared populations are refused before constructing an execution inventory") {
    val ctx = context()
    val emptyRecall = RecallGraph
      .validated(ctx.recall.transcript, ctx.recall.atlas, Vector.empty, RecallRelations.empty)
      .fold(e => fail(s"empty recall refused: $e"), identity)
    val source = value(StorySource.fromText("red blue"))
    val atlas = SurfaceAnalyzer.analyze(source)
    assertEquals(
      SurfaceRendering.prepare(emptyRecall, ctx.source, ctx.representation, atlas),
      Left(SurfaceRenderingRefusal.EmptyRecall)
    )
    val emptySource = InMemorySourceView(Vector.empty, Map.empty, None, source.canonicalText.length)
    assertEquals(
      SurfaceRendering.prepare(ctx.recall, emptySource, ctx.representation, atlas),
      Left(SurfaceRenderingRefusal.EmptySource)
    )
  }
