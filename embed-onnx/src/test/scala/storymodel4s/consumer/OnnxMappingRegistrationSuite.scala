package onnxmappingconsumer

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.embed.*
import storymodel4s.embed.onnx.*
import storymodel4s.features.Estimate

/** External consumer witnesses require actual attempts, not a table labeled as executed. */
class OnnxMappingRegistrationSuite extends FunSuite:
  private def value[A, E](a: Either[E, A]): A = a.fold(e => fail(s"refused: $e"), identity)
  private def config = value(
    OnnxMappingRegistration.Config.of(
      value(StrictCandidateConfig.of(1, TieBudgetRequest.AtMost(8), false, None)),
      Sensitivity.Public,
      Sensitivity.Public
    )
  )

  test("registered concrete attempts derive the same nomination and pricing values") {
    val encoder = OnnxMappingFixture.open()
    try
      val context = OnnxMappingFixture.context()
      val registration = value(OnnxMappingRegistration.record(encoder, context, config))
      assertEquals(registration.queryRecord.result.receipt.providerCalls.size, 1)
      assertEquals(registration.documentRecord.result.receipt.providerCalls.size, 1)
      assertEquals(registration.evidence.units, context.queries.map(_._1))
      assertEquals(registration.session.context, context)
      // Fixed lookup rows give hello=(1,1,1,0)/sqrt(3), world=(1,1,0,1)/sqrt(3).
      val unit = context.queries.head._1
      val hello = context.documents(0)._1
      val world = context.documents(1)._1
      assertEquals(value(registration.score(unit, hello)).estimate, Estimate.observed(0.0))
      val worldScore = value(registration.score(unit, world)).estimate.toOption.get
      assertEqualsDouble(worldScore, 1.0 / 6.0, 1e-7)
      val nominations = registration.candidates.get(unit).get.set.nominations
      assertEquals(nominations.map(_.ref), Vector(hello))
      assertEquals(nominations.head.rawScore, Some(0.0))
      val price =
        registration.evidence.breakdowns.head(AlignState.anchored(hello, FidelityMode.Faithful))
      assertEquals(price.term(CostTerm.Semantic), 0.0)
    finally encoder.close()
  }

  private def withEncoder[A](f: OnnxSentenceEmbedder => A): A =
    val e = OnnxMappingFixture.open()
    try f(e)
    finally e.close()

  private def sensitive(q: Sensitivity, d: Sensitivity) = value(
    OnnxMappingRegistration.Config
      .of(value(StrictCandidateConfig.of(1, TieBudgetRequest.AtMost(8), false, None)), q, d)
  )

  test("exact replay after encoder closure preserves attempts and derived evidence") {
    val original = withEncoder(e =>
      value(OnnxMappingRegistration.record(e, OnnxMappingFixture.context(), config))
    )
    val replayed = value(
      original.replay(
        original.context,
        config,
        OnnxMappingFixture.model(),
        original.queryRecord.provider
      )
    )
    assertEquals(replayed.basis, OnnxMappingBasis.Replayed)
    assertEquals(replayed.queryRecord, original.queryRecord)
    assertEquals(replayed.documentRecord, original.documentRecord)
    assertEquals(replayed.evidence.identity, original.evidence.identity)
    assertEquals(replayed.session, original.session)
    assertEquals(replayed.queryRecord.result.receipt.providerCalls.size, 1)
    assertEquals(replayed.documentRecord.result.receipt.providerCalls.size, 1)
  }

  test("a genuine unrelated recording cannot be attached to different submitted text") {
    val original = withEncoder(e =>
      value(OnnxMappingRegistration.record(e, OnnxMappingFixture.context(), config))
    )
    val different = OnnxMappingFixture.context(query = "ghost")
    assertEquals(
      OnnxMappingRegistration.replay(
        different,
        config,
        OnnxMappingFixture.model(),
        original.queryRecord.provider,
        original.queryRecord,
        original.documentRecord
      ),
      Left(OnnxMappingRefusal.Replay(OnnxReplayRefusal.Payload))
    )
  }

  test("exact registration replay binds source bytes beyond equal view fields and payloads") {
    val a = OnnxMappingFixture.context(text = "hello world", ranges = Vector(0 -> 5))
    val b = OnnxMappingFixture.context(text = "hello ghost", ranges = Vector(0 -> 5))
    assertEquals(a.documents, b.documents)
    assertEquals(ViewFingerprint.of(a.source), ViewFingerprint.of(b.source))
    val original = withEncoder(e => value(OnnxMappingRegistration.record(e, a, config)))
    assertEquals(
      original.replay(b, config, OnnxMappingFixture.model(), original.queryRecord.provider),
      Left(OnnxMappingRefusal.Context)
    )
  }

  test("registration replay refuses changed configuration model and provider") {
    val original = withEncoder(e =>
      value(OnnxMappingRegistration.record(e, OnnxMappingFixture.context(), config))
    )
    val changed = value(
      OnnxMappingRegistration.Config
        .of(config.candidates, Sensitivity.Public, Sensitivity.Public, missingSemantic = 0.7)
    )
    assertEquals(
      original.replay(
        original.context,
        changed,
        OnnxMappingFixture.model(),
        original.queryRecord.provider
      ),
      Left(OnnxMappingRefusal.Configuration)
    )
    assertEquals(
      original.replay(
        original.context,
        config,
        OnnxMappingFixture.model(5),
        original.queryRecord.provider
      ),
      Left(OnnxMappingRefusal.Replay(OnnxReplayRefusal.Model))
    )
    assertEquals(
      original.replay(
        original.context,
        config,
        OnnxMappingFixture.model(),
        ProviderFingerprint(storymodel4s.core.Checksum.ofText("foreign provider"))
      ),
      Left(OnnxMappingRefusal.Replay(OnnxReplayRefusal.Provider))
    )
  }

  test("query document role substitution cannot become registered execution") {
    withEncoder { e =>
      val ctx = OnnxMappingFixture.context(ranges = Vector(0 -> 5))
      val original = value(OnnxMappingRegistration.record(e, ctx, config))
      val document = e.spaces.find(_.role == Role.Document).get
      val batch = value(
        EmbedBatch.validated(
          Vector(
            EmbedRequest(
              RequestId.unsafe("surface-query-0"),
              EmbedPayload.Raw("hello", Sensitivity.Public),
              document.id
            )
          ),
          e.spaceIds
        )
      )
      val wrong = e.record(batch)
      assertEquals(
        OnnxMappingRegistration
          .replay(ctx, config, e.model, e.info.provider, wrong, original.documentRecord),
        Left(OnnxMappingRefusal.Replay(OnnxReplayRefusal.Geometry))
      )
    }
  }

  test("both TooLong endpoint failures remain distinct from provider abstention") {
    withEncoder { e =>
      val ctx = OnnxMappingFixture.context(
        query = "hello world ghost",
        text = "hello world ghost",
        ranges = Vector(0 -> 17)
      )
      val r = value(OnnxMappingRegistration.record(e, ctx, config))
      val score = value(r.score(ctx.queries.head._1, ctx.documents.head._1))
      val failure = ExecutionFailure.TooLong(5, 4)
      assertEquals(score.query, Left(failure))
      assertEquals(score.document, Left(failure))
      assertEquals(score.failures, Vector(Role.Query -> failure, Role.Document -> failure))
      assertEquals(
        score.estimate,
        storymodel4s.features.Estimate.missing(SurfaceEmbedding.ExecutionFailed)
      )
      assertEquals(
        r.candidates.get(ctx.queries.head._1).get.semanticOutcomes.head.missing,
        Map(SurfaceEmbedding.ExecutionFailed -> 1)
      )
    }
  }

  test("one denied endpoint retains keyless policy and original receipt") {
    withEncoder { e =>
      val ctx = OnnxMappingFixture.context(ranges = Vector(0 -> 5))
      val c = sensitive(Sensitivity.Public, Sensitivity.Sensitive)
      val r = value(OnnxMappingRegistration.record(e, ctx, c))
      val score = value(r.score(ctx.queries.head._1, ctx.documents.head._1))
      assert(score.query.exists(_.isObserved))
      score.document match
        case Left(ExecutionFailure.PolicyDenied(_: PolicyDecision.KeyUnavailable)) => ()
        case other => fail(s"expected key denial, got $other")
      assertEquals(
        score.estimate,
        storymodel4s.features.Estimate.missing(SurfaceEmbedding.ExecutionFailed)
      )
      assertEquals(r.documentRecord.result.receipt.kind, DigestKind.Withheld)
      assertEquals(r.documentRecord.result.receipt.providerCalls, Vector.empty)
      assertEquals(r.queryRecord.result.receipt.providerCalls.size, 1)
      assertEquals(r.score(ctx.queries.head._1, ctx.documents.head._1), Right(score))
    }
  }

  test("private denied strings remain distinguishable without a new public text hash") {
    withEncoder { e =>
      val c = sensitive(Sensitivity.Public, Sensitivity.Sensitive)
      val a = value(
        OnnxMappingRegistration.record(
          e,
          OnnxMappingFixture.context(text = "private original", ranges = Vector(0 -> 16)),
          c
        )
      )
      val b = value(
        OnnxMappingRegistration.record(
          e,
          OnnxMappingFixture.context(text = "private changedx", ranges = Vector(0 -> 16)),
          c
        )
      )
      assertEquals(a.documentRecord.result.receipt, b.documentRecord.result.receipt)
      assertNotEquals(a, b)
      assertEquals(a.hashCode, b.hashCode)
      assert(!a.toString.contains("private original"))
      assert(!a.session.channel.toString.contains("private original"))
    }
  }

  test("empty text is skipped as ineligible without an invented request receipt") {
    withEncoder { e =>
      val ctx = OnnxMappingFixture.context(query = "", text = "hello", ranges = Vector(0 -> 0))
      val r = value(OnnxMappingRegistration.record(e, ctx, config))
      assertEquals(r.skippedQueries, 1)
      assertEquals(r.skippedDocuments, 1)
      assertEquals(r.queryRecord.result.outcomes, Vector.empty)
      assertEquals(r.documentRecord.result.outcomes, Vector.empty)
      assertEquals(r.queryRecord.result.receipt.providerCalls, Vector.empty)
      assertEquals(r.documentRecord.result.receipt.providerCalls, Vector.empty)
      assertEquals(
        value(r.score(ctx.queries.head._1, ctx.documents.head._1)).estimate,
        storymodel4s.features.Estimate.Ineligible
      )
      assertEquals(r.candidates.get(ctx.queries.head._1).get.semanticOutcomes.head.ineligible, 1)
    }
  }

  test("raw cosine cannot silently enter strict pricing and geometry labels are derived") {
    assertEquals(
      OnnxMappingRegistration.Config.of(
        config.candidates,
        Sensitivity.Public,
        Sensitivity.Public,
        metric = EmbeddingMetric.CosineDistance
      ),
      Left(OnnxMappingRefusal.StrictMetric)
    )
    withEncoder { e =>
      val ctx = OnnxMappingFixture.context()
      val bad = value(
        OnnxMappingRegistration.Config.of(
          value(
            StrictCandidateConfig.of(1, TieBudgetRequest.AtMost(8), false, Some("caller-label"))
          ),
          Sensitivity.Public,
          Sensitivity.Public
        )
      )
      assertEquals(
        OnnxMappingRegistration.record(e, ctx, bad),
        Left(OnnxMappingRefusal.CandidateSpace)
      )
      val r = value(OnnxMappingRegistration.record(e, ctx, config))
      assertEquals(r.effectiveCandidates.space, Some(r.session.channel.data.querySpace.id.value))
      assertEquals(r.session.channel.data.metric, EmbeddingMetric.HalfCosineDistance)
    }
  }

  test("identifier rename and storage permutation preserve rekeyed scores ties and overflow") {
    withEncoder { e =>
      val a = value(OnnxMappingRegistration.record(e, OnnxMappingFixture.context(), config))
      val b = value(
        OnnxMappingRegistration.record(
          e,
          OnnxMappingFixture.context(renamed = true, reverseStorage = true),
          config
        )
      )
      val as = a.candidates.get(a.context.queries.head._1).get
      val bs = b.candidates.get(b.context.queries.head._1).get
      assertEquals(
        as.set.nominations.map(_.rawScore).sorted,
        bs.set.nominations.map(_.rawScore).sorted
      )
      assertEquals(as.uniformSemantic, bs.uniformSemantic)
      assertEquals(as.semanticOutcomes, bs.semanticOutcomes)
      val tight = value(
        OnnxMappingRegistration.Config.of(
          value(StrictCandidateConfig.of(1, TieBudgetRequest.AtMost(1), false, None)),
          Sensitivity.Public,
          Sensitivity.Public
        )
      )
      val x = value(OnnxMappingRegistration.record(e, a.context, tight)).candidates
        .get(a.context.queries.head._1)
        .get
      val y = value(OnnxMappingRegistration.record(e, b.context, tight)).candidates
        .get(b.context.queries.head._1)
        .get
      assertEquals(x.overflow, y.overflow)
      def rekey(r: OnnxMappingRegistration.Registered, n: Nomination) =
        val ordinal = r.context.source.node(n.ref).get.discoursePosition
        (ordinal, n.channel, n.rank, n.rawScore, n.space, n.receipt)
      assertEquals(x.set.nominations.map(n => rekey(a, n)), y.set.nominations.map(n => rekey(b, n)))
    }
  }

  test("concrete equal endpoints complete ties and refuse an overflowing nomination union") {
    withEncoder { e =>
      val a = OnnxMappingFixture.context(ranges = Vector(0 -> 5, 0 -> 5))
      val b = OnnxMappingFixture.context(
        ranges = Vector(0 -> 5, 0 -> 5),
        renamed = true,
        reverseStorage = true
      )
      val roomy = value(OnnxMappingRegistration.record(e, a, config))
      assertEquals(roomy.session.channel.data.documentCount, 1)
      assertEquals(
        roomy.candidates.get(a.queries.head._1).get.set.nominations.map(_.rawScore),
        Vector(Some(0.0), Some(0.0))
      )
      val tight = value(
        OnnxMappingRegistration.Config.of(
          value(StrictCandidateConfig.of(1, TieBudgetRequest.AtMost(1), false, None)),
          Sensitivity.Public,
          Sensitivity.Public
        )
      )
      val x =
        value(OnnxMappingRegistration.record(e, a, tight)).candidates.get(a.queries.head._1).get
      val y =
        value(OnnxMappingRegistration.record(e, b, tight)).candidates.get(b.queries.head._1).get
      assertEquals(x.overflow.map(_.unionSize), Vector(2))
      assertEquals(y.overflow, x.overflow)
      assertEquals(x.set.nominations, Vector.empty)
      assertEquals(y.set.nominations, Vector.empty)
    }
  }

  test("both denied endpoints retain exact failures and no provider calls on replay") {
    val c = sensitive(Sensitivity.Sensitive, Sensitivity.Sensitive)
    val r =
      withEncoder(e => value(OnnxMappingRegistration.record(e, OnnxMappingFixture.context(), c)))
    val score = value(r.score(r.context.queries.head._1, r.context.documents.head._1))
    assertEquals(score.failures.map(_._1), Vector(Role.Query, Role.Document))
    assertEquals(score.estimate, Estimate.missing(SurfaceEmbedding.ExecutionFailed))
    assertEquals(r.queryRecord.result.receipt.kind, DigestKind.Withheld)
    assertEquals(r.documentRecord.result.receipt.kind, DigestKind.Withheld)
    val replayed = value(r.replay(r.context, c, OnnxMappingFixture.model(), r.queryRecord.provider))
    assertEquals(replayed.queryRecord.result.receipt.providerCalls, Vector.empty)
    assertEquals(replayed.documentRecord.result.receipt.providerCalls, Vector.empty)
    assertEquals(
      value(replayed.score(r.context.queries.head._1, r.context.documents.head._1)),
      score
    )
  }
