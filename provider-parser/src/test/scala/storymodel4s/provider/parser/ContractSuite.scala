package storymodel4s.provider.parser

import cats.Id
import munit.FunSuite
import storymodel4s.amr.interop.AmrCandidates
import storymodel4s.core.*

class ContractSuite extends FunSuite:
  import TestFixtures.*

  test("ParserSentenceInput is derived from the exact atlas sentence and token axis") {
    val input = inputs.head
    val sentence = atlas.sentences.head
    assertEquals(input.sentenceId, sentence.id)
    assertEquals(input.text, atlas.text(sentence))
    assertEquals(input.textChecksum, Checksum.ofText(atlas.text(sentence)))
    assertEquals(
      input.tokens.map(_.id),
      atlas.unitsOverlapping(sentence.span, SurfaceUnitKind.Token).map(_.id)
    )
    input.tokens.foreach { token =>
      assert(input.sentenceSpan.contains(token.span))
      assertEquals(token.text, token.span.slice(source.canonicalText).toOption.get)
    }
  }

  test("ParserSentenceInput refuses a non-sentence atlas unit") {
    val token = atlas.tokens.head
    val result =
      ParserSentenceInput.fromAtlas(ParserRequestId.unsafe("wrong-kind"), atlas, token.id)
    assertEquals(result, Left(ParserInputError.WrongUnitKind(token.id, SurfaceUnitKind.Token)))
  }

  test("ParserBatch rejects duplicate request identities before provider work") {
    val duplicate = ParserBatch.validated(Vector(inputs.head, inputs.head))
    assertEquals(duplicate, Left(ParserInputError.DuplicateRequestId(inputs.head.id)))
  }

  test("PinnedRuntime names every required artifact or fails closed") {
    val incomplete = runtime.artifacts.filterNot(_.component == RuntimeComponent.BaseImage)
    val result = PinnedRuntime.from(
      runtime.provider,
      runtime.model,
      runtime.version,
      incomplete,
      Set(RuntimeComponent.Dependency("bart.large"))
    )
    result match
      case Left(failure) =>
        assertEquals(failure.kind, ParserSetupFailureKind.RuntimeDependencyUnpinned)
        assertEquals(failure.missingComponents, Vector(RuntimeComponent.BaseImage))
      case other => fail(s"expected RuntimeDependencyUnpinned, got $other")
  }

  test("runtime unavailability facts reject empty, contradictory, and invalid claims") {
    assertEquals(
      ParserSetupFailure.runtimeDependencyUnpinned(Vector.empty),
      Left(ParserSetupFailureAdmissionError.MissingComponentsEmpty)
    )
    assertEquals(
      ParserSetupFailure.artifactMissing(
        Vector(RuntimeComponent.Checkpoint, RuntimeComponent.Checkpoint)
      ),
      Left(ParserSetupFailureAdmissionError.DuplicateComponent(1))
    )
    assertEquals(
      ParserSetupFailure.runtimeDependencyUnpinned(
        Vector(RuntimeComponent.Dependency(" "))
      ),
      Left(ParserSetupFailureAdmissionError.InvalidComponent(0))
    )
    assertEquals(
      ParserSetupFailure.platformIncompatible(RuntimePlatform.DarwinArm64, Vector.empty),
      Left(ParserSetupFailureAdmissionError.SupportedPlatformsEmpty)
    )
    assertEquals(
      ParserSetupFailure.platformIncompatible(
        RuntimePlatform.DarwinArm64,
        Vector(RuntimePlatform.DarwinArm64)
      ),
      Left(ParserSetupFailureAdmissionError.CurrentPlatformIncluded)
    )
    assertEquals(
      ParserSetupFailure.platformIncompatible(
        RuntimePlatform.Custom(" ", "arm64"),
        Vector(RuntimePlatform.LinuxArm64)
      ),
      Left(ParserSetupFailureAdmissionError.InvalidCurrentPlatform)
    )
    assertEquals(
      ParserSetupFailure.resourceBudgetInsufficient(
        RuntimeResource.MemoryBytes,
        required = 1L,
        available = 2L
      ),
      Left(ParserSetupFailureAdmissionError.BudgetIsSufficient)
    )
    assertEquals(
      ParserSetupFailure.resourceBudgetInsufficient(
        RuntimeResource.Custom("host", " "),
        required = 2L,
        available = 1L
      ),
      Left(ParserSetupFailureAdmissionError.InvalidResource)
    )
  }

  test("runtime unavailability identity is structural rather than delimiter-rendered") {
    val split = ParserSetupFailure
      .runtimeDependencyUnpinned(
        Vector(RuntimeComponent.Dependency("a"), RuntimeComponent.Dependency("b"))
      )
      .toOption
      .get
    val stuffed = ParserSetupFailure
      .runtimeDependencyUnpinned(
        Vector(RuntimeComponent.Dependency("a,dependency:b"))
      )
      .toOption
      .get
    assertNotEquals(split, stuffed)
    assertNotEquals(split.checksum, stuffed.checksum)
  }

  test("provider identities remain injective when a value contains the old NUL separator") {
    val call = JsonAmrCandidateProvider[Id](
      ParserRuntime.Ready(runtime),
      config,
      lexicon,
      new ScriptedTransport(Vector(Right(successResponse)))
    ).parse(batch).attempts.head.receipt.call.get
    val stuffedId = ProviderNodeId.from("x\u00001\u00001\u0000y").toOption.get
    val x = ProviderNodeId.from("x").toOption.get
    val y = ProviderNodeId.from("y").toOption.get
    val stuffed = AcceptedAlignmentSidecar.fromValidated(
      inputs.head,
      call,
      ParserAlignmentDialect.ExplicitIndexListV1,
      Vector(stuffedId -> Vector(1))
    )
    val split = AcceptedAlignmentSidecar.fromValidated(
      inputs.head,
      call,
      ParserAlignmentDialect.ExplicitIndexListV1,
      Vector(x -> Vector(1), y -> Vector(1))
    )

    assertNotEquals(stuffed.providerNodeIds, split.providerNodeIds)
    assertNotEquals(stuffed.checksum, split.checksum)
    assertNotEquals(
      ParserIdentity.digest("nul-court", Vector("x\u0000y")),
      ParserIdentity.digest("nul-court", Vector("x", "y"))
    )

    val stuffedComponent = ParserSetupFailure
      .runtimeDependencyUnpinned(
        Vector(RuntimeComponent.Dependency("x\u0000component/1\u0000dependency\u0000y"))
      )
      .toOption
      .get
    val splitComponents = ParserSetupFailure
      .runtimeDependencyUnpinned(
        Vector(RuntimeComponent.Dependency("x"), RuntimeComponent.Dependency("y"))
      )
      .toOption
      .get
    assertNotEquals(stuffedComponent.checksum, splitComponents.checksum)

    val leftResource = ParserSetupFailure
      .resourceBudgetInsufficient(RuntimeResource.Custom("a\u0000b", "c"), 2L, 1L)
      .toOption
      .get
    val rightResource = ParserSetupFailure
      .resourceBudgetInsufficient(RuntimeResource.Custom("a", "b\u0000c"), 2L, 1L)
      .toOption
      .get
    assertNotEquals(leftResource.checksum, rightResource.checksum)
  }

  test("set-valued runtime facts use structural canonical order when rendering ties") {
    val left = RuntimePlatform.Custom("a:b", "c")
    val right = RuntimePlatform.Custom("a", "b:c")
    assertEquals(left.render, right.render)

    val forward = ParserSetupFailure
      .platformIncompatible(RuntimePlatform.DarwinArm64, Vector(left, right))
      .toOption
      .get
    val reversed = ParserSetupFailure
      .platformIncompatible(RuntimePlatform.DarwinArm64, Vector(right, left))
      .toOption
      .get

    assertEquals(forward.supportedPlatforms, reversed.supportedPlatforms)
    assertEquals(forward.checksum, reversed.checksum)

    val leftResource = ParserSetupFailure
      .resourceBudgetInsufficient(RuntimeResource.Custom("a:b", "c"), 2L, 1L)
      .toOption
      .get
    val rightResource = ParserSetupFailure
      .resourceBudgetInsufficient(RuntimeResource.Custom("a", "b:c"), 2L, 1L)
      .toOption
      .get
    assertEquals(leftResource.resource.map(_.render), rightResource.resource.map(_.render))
    assertNotEquals(leftResource, rightResource)
    assertNotEquals(leftResource.checksum, rightResource.checksum)
  }

  test("configuration and receipt identities frame supplied key-value fields structurally") {
    val leftConfig = ParserConfig.from(Map("a" -> "b=c"), None, 1L).toOption.get
    val rightConfig = ParserConfig.from(Map("a=b" -> "c"), None, 1L).toOption.get
    assertNotEquals(leftConfig.params, rightConfig.params)
    assertNotEquals(leftConfig.checksum, rightConfig.checksum)

    val leftFailure = ParserFailure.RuntimeFingerprintMismatch(
      Fingerprint.unsafe("a:b"),
      Fingerprint.unsafe("c")
    )
    val rightFailure = ParserFailure.RuntimeFingerprintMismatch(
      Fingerprint.unsafe("a"),
      Fingerprint.unsafe("b:c")
    )
    assertEquals(leftFailure.render, rightFailure.render)
    val leftReceipt = ParserAttemptReceipt.of(
      inputs.head,
      None,
      Vector(ParserAttemptDecision.ResultRejected(leftFailure))
    )
    val rightReceipt = ParserAttemptReceipt.of(
      inputs.head,
      None,
      Vector(ParserAttemptDecision.ResultRejected(rightFailure))
    )
    assertNotEquals(leftReceipt.digest, rightReceipt.digest)
  }

  test("runtime fingerprint changes when any artifact checksum changes") {
    val base = runtime.artifacts.find(_.component == RuntimeComponent.BaseImage).get
    val changed = RuntimeArtifact
      .from(
        base.component,
        base.version,
        base.source,
        Checksum.ofText("changed-image"),
        base.licenseEvidence
      )
      .toOption
      .get
    val rebuilt = PinnedRuntime
      .from(
        runtime.provider,
        runtime.model,
        runtime.version,
        runtime.artifacts.filterNot(_.component == RuntimeComponent.BaseImage) :+ changed,
        Set(RuntimeComponent.Dependency("bart.large"))
      )
      .toOption
      .get
    assertNotEquals(rebuilt.fingerprint, runtime.fingerprint)
  }

  test("runtime fingerprint binds the external wrapper version") {
    val rebuilt = PinnedRuntime
      .from(
        runtime.provider,
        runtime.model,
        version = "wrapper-v2",
        artifacts = runtime.artifacts,
        additionalRequired = Set(RuntimeComponent.Dependency("bart.large"))
      )
      .toOption
      .get
    assertNotEquals(rebuilt.fingerprint, runtime.fingerprint)
  }

  test("successful attempt without a fresh call or bound cache witness is refused") {
    val transport = new ScriptedTransport(Vector(Right(successResponse)))
    val provider =
      JsonAmrCandidateProvider[Id](ParserRuntime.Ready(runtime), config, lexicon, transport)
    val proposal = provider.parse(batch).attempts.head.result.toOption.get
    val noProvenance = ParserAttemptReceipt.of(inputs.head, None, Vector.empty)
    assertEquals(
      ParserAttempt.proposed(inputs.head, proposal, noProvenance),
      Left(ParserFailure.ProvenanceMissing)
    )

  }

  test("fresh-call admission binds the receipt call to proposal provenance") {
    val transport = new ScriptedTransport(Vector(Right(successResponse)))
    val provider =
      JsonAmrCandidateProvider[Id](ParserRuntime.Ready(runtime), config, lexicon, transport)
    val admitted = provider.parse(batch).attempts.head
    val proposal = admitted.result.toOption.get
    val original = admitted.receipt.call.get
    val originalReceipt = ParserAttemptReceipt.of(inputs.head, Some(original), Vector.empty)
    val foreign = original.copy(model = original.model + "-foreign")
    val swapped = ParserAttemptReceipt.of(inputs.head, Some(foreign), Vector.empty)

    assert(ParserAttempt.proposed(inputs.head, proposal, originalReceipt).isRight)
    assertEquals(
      ParserAttempt.proposed(inputs.head, proposal, swapped),
      Left(ParserFailure.ProvenanceMissing)
    )
  }

  test("a successful result cannot carry rejecting or unavailable decisions") {
    val transport = new ScriptedTransport(Vector(Right(successResponse)))
    val provider =
      JsonAmrCandidateProvider[Id](ParserRuntime.Ready(runtime), config, lexicon, transport)
    val admitted = provider.parse(batch).attempts.head
    val proposal = admitted.result.toOption.get
    val call = admitted.receipt.call.get
    val rejected = ParserAttemptReceipt.of(
      inputs.head,
      Some(call),
      Vector(ParserAttemptDecision.ResultRejected(ParserFailure.AlignmentMissing))
    )
    val unavailableReason = ParserSetupFailure
      .runtimeDependencyUnpinned(Vector(RuntimeComponent.Checkpoint))
      .toOption
      .get
    val unavailable = ParserAttemptReceipt.of(
      inputs.head,
      Some(call),
      Vector(ParserAttemptDecision.RuntimeUnavailable(unavailableReason))
    )

    assertEquals(
      ParserAttempt.proposed(inputs.head, proposal, rejected),
      Left(ParserFailure.ProvenanceMissing)
    )
    assertEquals(
      ParserAttempt.proposed(inputs.head, proposal, unavailable),
      Left(ParserFailure.ProvenanceMissing)
    )
  }

  test("a failed result requires one exact matching rejection decision") {
    val failure = ParserFailure.ProviderContractViolation(Checksum.ofText("contract"))
    val missing = ParserAttemptReceipt.of(inputs.head, None, Vector.empty)
    val foreign = ParserAttemptReceipt.of(
      inputs.head,
      None,
      Vector(ParserAttemptDecision.ResultRejected(ParserFailure.AlignmentEscapesSentence))
    )
    val matching = ParserAttemptReceipt.of(
      inputs.head,
      None,
      Vector(ParserAttemptDecision.ResultRejected(failure))
    )

    assertEquals(
      ParserAttempt.from(inputs.head, Left(failure), missing),
      Left(ParserFailure.ProvenanceMissing)
    )
    assertEquals(
      ParserAttempt.from(inputs.head, Left(failure), foreign),
      Left(ParserFailure.ProvenanceMissing)
    )
    assert(ParserAttempt.from(inputs.head, Left(failure), matching).isRight)
  }

  test("runtime and transport failures require their matching branch decisions") {
    val unavailableReason = ParserSetupFailure
      .runtimeDependencyUnpinned(Vector(RuntimeComponent.Checkpoint))
      .toOption
      .get
    val unavailableProvider = JsonAmrCandidateProvider[Id](
      ParserRuntime.Unavailable(unavailableReason),
      config,
      lexicon,
      new ScriptedTransport(Vector.empty)
    )
    val unavailableAttempt = unavailableProvider.parse(batch).attempts.head
    assert(
      ParserAttempt
        .from(inputs.head, unavailableAttempt.result, unavailableAttempt.receipt)
        .isRight
    )
    val unavailableFailure = ParserFailure.RuntimeUnavailable(unavailableReason)
    val unavailableWithoutDecision = ParserAttemptReceipt.of(
      inputs.head,
      None,
      Vector(ParserAttemptDecision.ResultRejected(unavailableFailure))
    )
    assertEquals(
      ParserAttempt.from(inputs.head, Left(unavailableFailure), unavailableWithoutDecision),
      Left(ParserFailure.ProvenanceMissing)
    )

    val timeoutProvider = JsonAmrCandidateProvider[Id](
      ParserRuntime.Ready(runtime),
      config,
      lexicon,
      new ScriptedTransport(Vector(Left(TransportFailure.Timeout(config.timeoutMillis))))
    )
    val timeoutAttempt = timeoutProvider.parse(batch).attempts.head
    assert(ParserAttempt.from(inputs.head, timeoutAttempt.result, timeoutAttempt.receipt).isRight)
    val timeoutFailure = ParserFailure.Timeout(config.timeoutMillis)
    val timeoutWithoutDecision = ParserAttemptReceipt.of(
      inputs.head,
      timeoutAttempt.receipt.call,
      Vector(ParserAttemptDecision.ResultRejected(timeoutFailure))
    )
    assertEquals(
      ParserAttempt.from(inputs.head, Left(timeoutFailure), timeoutWithoutDecision),
      Left(ParserFailure.ProvenanceMissing)
    )
  }

  test("transport failure cannot also claim an accepted alignment sidecar") {
    val successful = JsonAmrCandidateProvider[Id](
      ParserRuntime.Ready(runtime),
      config,
      lexicon,
      new ScriptedTransport(Vector(Right(successResponse)))
    ).parse(batch).attempts.head
    val sidecar = successful.receipt.decisions
      .collectFirst { case ParserAttemptDecision.AlignmentSidecarAccepted(value) =>
        value
      }
      .getOrElse(fail("expected accepted sidecar"))
    val reason = TransportFailure.Timeout(config.timeoutMillis)
    val failure = ParserFailure.Timeout(config.timeoutMillis)
    val failed = JsonAmrCandidateProvider[Id](
      ParserRuntime.Ready(runtime),
      config,
      lexicon,
      new ScriptedTransport(Vector(Left(reason)))
    ).parse(batch).attempts.head
    val contradictory = ParserAttemptReceipt.of(
      inputs.head,
      failed.receipt.call,
      Vector(
        ParserAttemptDecision.TransportFailed(reason),
        ParserAttemptDecision.AlignmentSidecarAccepted(sidecar),
        ParserAttemptDecision.ResultRejected(failure)
      )
    )

    assertEquals(
      ParserAttempt.from(inputs.head, Left(failure), contradictory),
      Left(ParserFailure.ProvenanceMissing)
    )
  }

  test("response failures that arise after provider output require a fresh call") {
    val failure = ParserFailure.MalformedEnvelope(Checksum.ofText("malformed"))
    val callless = ParserAttemptReceipt.of(
      inputs.head,
      None,
      Vector(ParserAttemptDecision.ResultRejected(failure))
    )

    assertEquals(
      ParserAttempt.from(inputs.head, Left(failure), callless),
      Left(ParserFailure.ProvenanceMissing)
    )
  }

  test("a cache miss on a fresh success is bound to this request runtime and configuration") {
    val successful = JsonAmrCandidateProvider[Id](
      ParserRuntime.Ready(runtime),
      config,
      lexicon,
      new ScriptedTransport(Vector(Right(successResponse)))
    ).parse(batch).attempts.head
    val proposal = successful.result.toOption.get
    val foreignKey = ParserCacheKey.of(inputs(1), runtime, config)
    val substituted = ParserAttemptReceipt.of(
      inputs.head,
      successful.receipt.call,
      Vector(ParserAttemptDecision.CacheMiss(foreignKey))
    )

    assertEquals(
      ParserAttempt.proposed(
        inputs.head,
        proposal,
        substituted,
        Some(ParserCacheKey.of(inputs.head, runtime, config))
      ),
      Left(ParserFailure.ProvenanceMissing)
    )
  }

  test("a successful attempt cannot borrow an accepted sidecar from a sibling request") {
    val result = JsonAmrCandidateProvider[Id](
      ParserRuntime.Ready(runtime),
      config,
      lexicon,
      new ScriptedTransport(Vector(Right(successResponse)))
    ).parse(batch)
    val foreign = result.attempts.head.receipt.decisions
      .collectFirst { case ParserAttemptDecision.AlignmentSidecarAccepted(value) =>
        value
      }
      .getOrElse(fail("expected accepted sidecar"))
    val target = result.attempts(1)
    val receipt = ParserAttemptReceipt.of(
      inputs(1),
      target.receipt.call,
      Vector(ParserAttemptDecision.AlignmentSidecarAccepted(foreign))
    )

    assertEquals(
      ParserAttempt.proposed(inputs(1), target.result.toOption.get, receipt),
      Left(ParserFailure.ProvenanceMissing)
    )
  }

  test("a response failure cannot borrow a model-input observation from a sibling request") {
    val result = JsonAmrCandidateProvider[Id](
      ParserRuntime.Ready(runtime),
      config,
      lexicon,
      new ScriptedTransport(Vector(Right(successResponse)))
    ).parse(batch)
    val foreign = result.attempts.head.receipt.decisions
      .collectFirst { case ParserAttemptDecision.ModelInputObserved(value) =>
        value
      }
      .getOrElse(fail("expected model-input observation"))
    val failure = ParserFailure.ProviderFailed(ProviderFailureCode.from("probe").toOption.get)
    val receipt = ParserAttemptReceipt.of(
      inputs(1),
      result.attempts(1).receipt.call,
      Vector(
        ParserAttemptDecision.ModelInputObserved(foreign),
        ParserAttemptDecision.ResultRejected(failure)
      )
    )

    assertEquals(
      ParserAttempt.from(inputs(1), Left(failure), receipt),
      Left(ParserFailure.ProvenanceMissing)
    )
  }

  test("the internal failure path cannot emit a receipt rejected by public reconstruction") {
    val malformed = ParserFailure.MalformedEnvelope(Checksum.ofText("callless"))
    val impossible = ParserAttemptReceipt.of(
      inputs.head,
      None,
      Vector(ParserAttemptDecision.ResultRejected(malformed))
    )
    val emitted = ParserAttempt.failed(inputs.head, malformed, impossible)

    assert(
      emitted.result.left.toOption.exists(_.isInstanceOf[ParserFailure.ProviderContractViolation])
    )
    assert(ParserAttempt.from(inputs.head, emitted.result, emitted.receipt).isRight)
  }

  test("the internal cache failure fallback preserves its checked cache context") {
    val key = ParserCacheKey.of(inputs.head, runtime, config)
    val malformed = ParserFailure.MalformedEnvelope(Checksum.ofText("callless-cache"))
    val impossible = ParserAttemptReceipt.of(
      inputs.head,
      None,
      Vector(ParserAttemptDecision.ResultRejected(malformed))
    )
    val emitted = ParserAttempt.failed(inputs.head, malformed, impossible, Some(key))

    assert(
      emitted.result.left.toOption.exists(_.isInstanceOf[ParserFailure.ProviderContractViolation])
    )
    assert(ParserAttempt.from(inputs.head, emitted.result, emitted.receipt, Some(key)).isRight)
    assertEquals(
      emitted.receipt.decisions.collect { case ParserAttemptDecision.CacheMiss(actual) => actual },
      Vector(key)
    )
  }

  test("attempt admission refuses evidence whose wrapper provenance disagrees with its chart") {
    val transport = new ScriptedTransport(Vector(Right(successResponse)))
    val provider =
      JsonAmrCandidateProvider[Id](ParserRuntime.Ready(runtime), config, lexicon, transport)
    val admitted = provider.parse(batch).attempts.head
    val proposal = admitted.result.toOption.get
    val call = admitted.receipt.call.get
    val mismatched = ParserProposal.of(
      storymodel4s.proposition.PropositionEvidence(
        proposal.evidence.chart,
        storymodel4s.proposition.ChartProvenance.hand
      )
    )
    val receipt = ParserAttemptReceipt.of(inputs.head, Some(call), Vector.empty)

    assertEquals(
      ParserAttempt.proposed(inputs.head, mismatched, receipt),
      Left(ParserFailure.ProvenanceMissing)
    )
  }

  test("public attempt construction re-runs exact alignment admission") {
    val transport = new ScriptedTransport(Vector(Right(successResponse)))
    val provider =
      JsonAmrCandidateProvider[Id](ParserRuntime.Ready(runtime), config, lexicon, transport)
    val admitted = provider.parse(batch).attempts.head
    val call = admitted.receipt.call.get
    val unaligned = AmrCandidates
      .fromPenman("(b / boy)", lexicon, Some(inputs.head.sentenceId), Vector(call))
      .toOption
      .get
    val proposal = ParserProposal.of(storymodel4s.proposition.PropositionEvidence.of(unaligned))
    val receipt = ParserAttemptReceipt.of(inputs.head, Some(call), Vector.empty)
    assertEquals(
      ParserAttempt.proposed(inputs.head, proposal, receipt),
      Left(ParserFailure.AlignmentMissing)
    )
  }

  test("ParserConfig rejects a non-positive timeout and blank parameter key") {
    assertEquals(
      ParserConfig.from(Map.empty, None, 0L),
      Left(ParserSetupFailure.invalidRuntimeField(ParserRuntimeField.TimeoutMillis))
    )
    assertEquals(
      ParserConfig.from(Map(" " -> "value"), None, 1L),
      Left(ParserSetupFailure.invalidRuntimeField(ParserRuntimeField.ParameterKey))
    )
  }

  test("provider failure codes are bounded tokens") {
    assert(ProviderFailureCode.from("parser-timeout").isRight)
    assert(ProviderFailureCode.from("contains whitespace").isLeft)
    assert(ProviderFailureCode.from("x" * 129).isLeft)
  }
