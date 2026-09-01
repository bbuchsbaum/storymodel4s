package storymodel4s.provider.parserprobe

import munit.FunSuite
import scala.compiletime.testing.{typeCheckErrors, typeChecks}
import scala.deriving.Mirror
import storymodel4s.provider.parser.*

class ConstructionBoundarySuite extends FunSuite:
  test("external code can use the atlas-only construction path") {
    assert(
      typeChecks("""
        import storymodel4s.provider.parser.*
        import storymodel4s.core.*
        def build(id: ParserRequestId, atlas: SurfaceAtlas, sentenceId: SurfaceUnitId) =
          ParserSentenceInput.fromAtlas(id, atlas, sentenceId)
      """)
    )
  }

  test("external code cannot construct ParserSentenceInput directly") {
    val errors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      import storymodel4s.core.*
      new ParserSentenceInput(
        ParserRequestId.unsafe("request"),
        SurfaceUnitId.unsafe("sentence"),
        TextSpan.unsafe(0, 1),
        "x",
        Checksum.ofText("x"),
        Vector.empty,
        Checksum.ofText("forged")
      )
    """)
    assert(errors.nonEmpty)
  }

  test("external code has no generated copy or fromProduct escape hatch") {
    val copyErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      def forge(input: ParserSentenceInput) = input.copy(text = "replacement")
    """)
    val productErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      ParserSentenceInput.fromProduct(Tuple0)
    """)
    assert(copyErrors.nonEmpty)
    assert(productErrors.nonEmpty)
  }

  test("external code cannot retain or pair raw model input in a receipt decision") {
    val constructorErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      import storymodel4s.core.*
      new ModelInputObservation(Checksum.ofText("participant text"))
    """)
    val factoryErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      ModelInputObservation.fromRaw("participant text")
    """)
    val oldDecisionErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      import storymodel4s.core.*
      ParserAttemptDecision.ModelInputObserved(
        "participant text",
        Checksum.ofText("different text")
      )
    """)
    assert(constructorErrors.nonEmpty)
    assert(factoryErrors.nonEmpty)
    assert(oldDecisionErrors.nonEmpty)
  }

  test("external code cannot pair accepted sidecar provenance with an unrelated checksum") {
    val constructorErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      import storymodel4s.core.*
      val nodeId = ProviderNodeId.from("b").toOption.get
      new AcceptedAlignmentSidecar(
        ParserAlignmentDialect.ExplicitIndexListV1,
        Vector(nodeId),
        Vector(Vector(1)),
        Checksum.ofText("unrelated")
      )
    """)
    val oldDecisionErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      import storymodel4s.core.*
      ParserAttemptDecision.AlignmentSidecarAccepted(
        ParserAlignmentDialect.ExplicitIndexListV1,
        Vector("b"),
        Checksum.ofText("unrelated")
      )
    """)
    assert(constructorErrors.nonEmpty)
    assert(oldDecisionErrors.nonEmpty)
  }

  test("external code can derive but cannot mint or copy a remote runtime identity") {
    assert(
      typeChecks("""
        import storymodel4s.provider.parser.*
        import storymodel4s.acquire.PromptPackageRef
        import storymodel4s.core.*
        def build(ref: PromptPackageRef, text: Checksum) =
          RemoteRuntime.from("anthropic", "claude-sonnet-5", "sdk", ref, text, "schema")
      """)
    )
    val constructorErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      import storymodel4s.acquire.PromptPackageRef
      import storymodel4s.core.*
      new RemoteRuntime(
        "anthropic",
        "claude-sonnet-5",
        "sdk",
        PromptPackageRef("p", "v1", Checksum.ofText("p")),
        Checksum.ofText("t"),
        "schema",
        PromptTemplateVersion.unsafe("p@v1#x"),
        Checksum.ofText("forged"),
        Fingerprint.unsafe("remote-runtime:forged")
      )
    """)
    val copyErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      def forge(runtime: RemoteRuntime) = runtime.copy(model = "claude-other")
    """)
    val productErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      RemoteRuntime.fromProduct(Tuple0)
    """)
    assert(constructorErrors.nonEmpty)
    assert(copyErrors.nonEmpty)
    assert(productErrors.nonEmpty)
  }

  test("external code can create only validated runtime-unavailability facts") {
    assert(
      typeChecks("""
        import storymodel4s.provider.parser.*
        val reason = ParserSetupFailure.runtimeDependencyUnpinned(
          Vector(RuntimeComponent.Checkpoint)
        )
        val failure = reason.map(ParserFailure.RuntimeUnavailable(_))
      """)
    )
    val setupMirrorErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      ParserSetupFailure.RuntimeDependencyUnpinned(Vector.empty)
    """)
    val publicFailureMirrorErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      ParserFailure.RuntimeArtifactMissing(Vector.empty)
    """)
    assert(setupMirrorErrors.nonEmpty)
    assert(publicFailureMirrorErrors.nonEmpty)
  }

  test("external cache implementations can retain but cannot mint cache witnesses") {
    assert(
      typeChecks("""
        import cats.Id
        import storymodel4s.provider.parser.*
        final class ExternalCache extends ParserCache[Id]:
          private var value: Option[CachedParserProposal] = None
          def get(key: ParserCacheKey): Option[CachedParserProposal] = value
          def put(key: ParserCacheKey, next: CachedParserProposal): Unit = value = Some(next)
      """)
    )
    val constructorErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      import storymodel4s.core.*
      def forge(key: ParserCacheKey, proposal: ParserProposal) =
        new CachedParserProposal(
          key,
          Checksum.ofText("request"),
          proposal,
          Checksum.ofText("receipt"),
          Checksum.ofText("cache")
        )
    """)
    val applyErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      import storymodel4s.core.*
      def forge(key: ParserCacheKey, proposal: ParserProposal) =
        CachedParserProposal(
          key,
          Checksum.ofText("request"),
          proposal,
          Checksum.ofText("receipt"),
          Checksum.ofText("cache")
        )
    """)
    val copyErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      def forge(value: CachedParserProposal) = value.copy(sourceReceipt = value.checksum)
    """)
    val productErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      import storymodel4s.core.*
      def forge(
          key: ParserCacheKey,
          request: Checksum,
          proposal: ParserProposal,
          receipt: Checksum,
          checksum: Checksum
      ) = CachedParserProposal.fromProduct((key, request, proposal, receipt, checksum))
    """)
    val mirrorErrors = typeCheckErrors("""
      import scala.deriving.Mirror
      import storymodel4s.provider.parser.*
      import storymodel4s.core.*
      def forge(
          key: ParserCacheKey,
          request: Checksum,
          proposal: ParserProposal,
          receipt: Checksum,
          checksum: Checksum
      ) = summon[Mirror.ProductOf[CachedParserProposal]]
        .fromProduct((key, request, proposal, receipt, checksum))
    """)
    val authorityErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      import storymodel4s.core.*
      def forge(key: ParserCacheKey) =
        ParserAttemptDecision.CacheHit(key, Checksum.ofText("fabricated-source-receipt"))
    """)
    assert(constructorErrors.nonEmpty)
    assert(applyErrors.nonEmpty)
    assert(copyErrors.nonEmpty)
    assert(productErrors.nonEmpty)
    assert(mirrorErrors.nonEmpty)
    assert(authorityErrors.nonEmpty)
  }

  test("external code can read but cannot mint atlas tokens") {
    assert(
      typeChecks("""
        import storymodel4s.provider.parser.*
        def read(input: ParserSentenceInput) =
          input.tokens.map(token => (token.id, token.span, token.text))
      """)
    )
    val constructorErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      import storymodel4s.core.*
      new AtlasToken(SurfaceUnitId.unsafe("token"), TextSpan.unsafe(0, 1), "x")
    """)
    val applyErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      import storymodel4s.core.*
      AtlasToken(SurfaceUnitId.unsafe("token"), TextSpan.unsafe(0, 1), "x")
    """)
    val copyErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      def forge(value: AtlasToken) = value.copy(text = "replacement")
    """)
    val productErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      import storymodel4s.core.*
      def forge(id: SurfaceUnitId, span: TextSpan, text: String) =
        AtlasToken.fromProduct((id, span, text))
    """)
    val mirrorErrors = typeCheckErrors("""
      import scala.deriving.Mirror
      import storymodel4s.provider.parser.*
      import storymodel4s.core.*
      def forge(id: SurfaceUnitId, span: TextSpan, text: String) =
        summon[Mirror.ProductOf[AtlasToken]].fromProduct((id, span, text))
    """)
    assert(constructorErrors.nonEmpty)
    assert(applyErrors.nonEmpty)
    assert(copyErrors.nonEmpty)
    assert(productErrors.nonEmpty)
    assert(mirrorErrors.nonEmpty)
  }

  test("external code can read but cannot mint a complete determinism report") {
    assert(
      typeChecks("""
        import storymodel4s.provider.parser.*
        def read(report: ParserDeterminismReport) =
          (report.checks, report.isStable, report.failures)
      """)
    )
    val constructorErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      new ParserDeterminismReport(Vector.empty)
    """)
    val applyErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      ParserDeterminismReport(Vector.empty)
    """)
    val copyErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      def forge(value: ParserDeterminismReport) = value.copy(checks = Vector.empty)
    """)
    val productErrors = typeCheckErrors("""
      import storymodel4s.provider.parser.*
      def forge(checks: Vector[ParserDeterminismCheck]) =
        ParserDeterminismReport.fromProduct(Tuple1(checks))
    """)
    val mirrorErrors = typeCheckErrors("""
      import scala.deriving.Mirror
      import storymodel4s.provider.parser.*
      def forge(checks: Vector[ParserDeterminismCheck]) =
        summon[Mirror.ProductOf[ParserDeterminismReport]].fromProduct(Tuple1(checks))
    """)
    assert(constructorErrors.nonEmpty)
    assert(applyErrors.nonEmpty)
    assert(copyErrors.nonEmpty)
    assert(productErrors.nonEmpty)
    assert(mirrorErrors.nonEmpty)
  }

  test("same-shape case classes expose every generated product mechanism") {
    val companionErrors = typeCheckErrors("""
      storymodel4s.core.SurfaceUnit.fromProduct((
        storymodel4s.core.SurfaceUnitId.unsafe("u"),
        storymodel4s.core.SurfaceUnitKind.Token,
        storymodel4s.core.TextSpan.unsafe(0, 1),
        0,
        None
      ))
    """)
    assertEquals(
      companionErrors,
      Nil,
      "the already-compiled companion fromProduct control must compile"
    )

    val errors = typeCheckErrors("""
        import scala.deriving.Mirror
        import storymodel4s.core.*
        import storymodel4s.provider.parser.*
        import storymodel4s.provider.parserprobe.*
        def cacheShape(
            key: ParserCacheKey,
            request: Checksum,
            proposal: ParserProposal,
            receipt: Checksum,
            checksum: Checksum
        ) =
          val tuple = (key, request, proposal, receipt, checksum)
          val value = CacheBoundaryShape(key, request, proposal, receipt, checksum)
          val constructed = new CacheBoundaryShape(key, request, proposal, receipt, checksum)
          (constructed, value.copy(), summon[Mirror.ProductOf[CacheBoundaryShape]].fromProduct(tuple))
        def tokenShape(id: SurfaceUnitId, span: TextSpan, text: String) =
          val tuple = (id, span, text)
          val value = TokenBoundaryShape(id, span, text)
          val constructed = new TokenBoundaryShape(id, span, text)
          (constructed, value.copy(), summon[Mirror.ProductOf[TokenBoundaryShape]].fromProduct(tuple))
        def reportShape(checks: Vector[ParserDeterminismCheck]) =
          val tuple = Tuple1(checks)
          val value = ReportBoundaryShape(checks)
          val constructed = new ReportBoundaryShape(checks)
          (constructed, value.copy(), summon[Mirror.ProductOf[ReportBoundaryShape]].fromProduct(tuple))
      """)
    assert(errors.isEmpty, errors.map(_.message).mkString("\n"))
  }

  test("JVM case-class fromProduct bytecode has a positive control and sealed records omit it") {
    val mirrorForged = summon[Mirror.ProductOf[CompanionFromProductBoundaryShape]]
      .fromProduct((0, -1))
    assertEquals(mirrorForged, CompanionFromProductBoundaryShape(0, -1))

    val method = classOf[CompanionFromProductBoundaryShape]
      .getMethod("fromProduct", classOf[Product])
    val forged = method.invoke(null, (0, -1)) match
      case value: CompanionFromProductBoundaryShape => value
      case other                                    => fail(s"unexpected control result: $other")
    assertEquals(forged, CompanionFromProductBoundaryShape(0, -1))

    Vector(
      classOf[CachedParserProposal],
      classOf[AtlasToken],
      classOf[ParserDeterminismReport]
    ).foreach { runtimeClass =>
      assert(
        !runtimeClass.getMethods.exists(_.getName == "fromProduct"),
        s"${runtimeClass.getName} exposes JVM fromProduct"
      )
    }
  }
