package storymodel4s.provider.parser

import cats.Id
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.proposition as p

class ConformingSuite extends FunSuite:
  import TestFixtures.*

  private def provider(transport: ScriptedTransport): JsonAmrCandidateProvider[Id] =
    JsonAmrCandidateProvider(ParserRuntime.Ready(runtime), config, lexicon, transport)

  private def failure(attempt: ParserAttempt): ParserFailure = attempt.result match
    case Left(value) => value
    case Right(_)    => fail(s"expected failure for ${attempt.id.value}")

  private def inputFor(text: String, requestId: String): ParserSentenceInput =
    val source = StorySource.fromText(text).toOption.get
    val localAtlas = SurfaceAnalyzer.analyze(source)
    ParserSentenceInput
      .fromAtlas(ParserRequestId.unsafe(requestId), localAtlas, localAtlas.sentences.head.id)
      .toOption
      .get

  private def runOne(input: ParserSentenceInput, item: WireItem): ParserAttempt =
    val one = ParserBatch.validated(Vector(input)).toOption.get
    val transport = new ScriptedTransport(Vector(Right(response(Vector(item)))))
    provider(transport).parse(one).attempts.head

  test("aligned PENMAN becomes checked evidence with a retained parser call") {
    val transport = new ScriptedTransport(Vector(Right(successResponse)))
    val result = provider(transport).parse(batch)
    assertEquals(transport.calls, 1)
    assertEquals(result.covered, 2)
    assert(result.conforms(batch).isRight)
    result.attempts.foreach { attempt =>
      assert(attempt.receipt.call.nonEmpty)
      val proposal = attempt.result.toOption.get
      assert(proposal.evidence.chart.alignments.nonEmpty)
      assert(
        proposal.evidence.chart.provenance.receipts.exists(_.provider == runtime.provider)
      )
      assert(
        attempt.receipt.decisions.exists {
          case ParserAttemptDecision.ModelInputObserved(observation) =>
            observation.checksum ==
              Checksum.ofText(modelInput(inputs.find(_.id == attempt.id).get))
          case _ => false
        }
      )
      assert(
        attempt.receipt.decisions.exists {
          case ParserAttemptDecision.AlignmentSidecarAccepted(sidecar) =>
            sidecar.dialect == ParserAlignmentDialect.ExplicitIndexListV1 &&
            sidecar.providerNodeIds.nonEmpty
          case _ => false
        }
      )
    }
  }

  test("batch conformance accepts an attempt for its exact input") {
    val admitted = runOne(inputs(0), wantItem())
    val exactBatch = ParserBatch.validated(Vector(inputs(0))).toOption.get

    assert(ParserBatchResult.validated(exactBatch, Vector(admitted)).isRight)
  }

  test("batch conformance rejects an attempt for foreign exact input with the same request id") {
    val admittedFirst = runOne(inputs(0), wantItem())
    val admittedSecond = runOne(inputs(1), sleepItem())
    val foreignInput = inputFor("A foreign sentence.", inputs(1).id.value)
    val foreignBatch = ParserBatch.validated(Vector(inputs(0), foreignInput)).toOption.get

    assertEquals(
      ParserBatchResult
        .validated(foreignBatch, Vector(admittedFirst, admittedSecond))
        .left
        .toOption,
      Some(
        ParserResultError.ExactInputMismatch(
          index = 1,
          expected = foreignInput.checksum,
          found = admittedSecond.receipt.requestChecksum
        )
      )
    )
  }

  test("batch conformance retains the request id and order mismatch court") {
    val admittedSecond = runOne(inputs(1), sleepItem())
    val firstOnly = ParserBatch.validated(Vector(inputs(0))).toOption.get

    assertEquals(
      ParserBatchResult.validated(firstOnly, Vector(admittedSecond)).left.toOption,
      Some(ParserResultError.AttemptMismatch(0, inputs(0).id, admittedSecond.id))
    )
  }

  test("unmarked PENMAN is AlignmentMissing while an aligned sibling survives") {
    val unmarked = proposed(inputs(0), "(b / boy)", Vector.empty)
    val aligned = sleepItem()
    val transport = new ScriptedTransport(Vector(Right(response(Vector(unmarked, aligned)))))
    val result = provider(transport).parse(batch)
    assertEquals(failure(result.attempts(0)), ParserFailure.AlignmentMissing)
    assert(result.attempts(1).result.isRight)
    assertEquals(result.covered, 1)
  }

  test("rewritten provider token echo rejects only that item and retains the sibling") {
    val first = wantItem()
    val wrongToken = first.tokens.head.copy(text = "NOT-ATLAS-TEXT")
    val mismatched = first.copy(tokens = wrongToken +: first.tokens.tail)
    val second = sleepItem()
    val transport = new ScriptedTransport(Vector(Right(response(Vector(mismatched, second)))))
    val result = provider(transport).parse(batch)
    assertEquals(failure(result.attempts(0)), ParserFailure.TokenEchoMismatch(0))
    assert(result.attempts(1).result.isRight)
  }

  test("rewritten model input is rejected independently of an exact token echo") {
    val rawCanary = "copied request, not parser state"
    val mismatched = wantItem().copy(modelInput = rawCanary)
    val transport = new ScriptedTransport(
      Vector(Right(response(Vector(mismatched, sleepItem()))))
    )
    val result = provider(transport).parse(batch)
    assertEquals(failure(result.attempts(0)), ParserFailure.ModelInputMismatch)
    assert(
      result.attempts(0).receipt.decisions.exists {
        case ParserAttemptDecision.ModelInputObserved(observation) =>
          observation.checksum == Checksum.ofText(rawCanary) &&
          !observation.toString.contains(rawCanary)
        case _ => false
      }
    )
    assert(!result.attempts(0).receipt.decisions.mkString.contains(rawCanary))
    assert(result.attempts(1).result.isRight)
  }

  test("court A refuses IBM ISI range markers instead of reading them as explicit indices") {
    val input = inputFor("A B C.", "isi-range")
    assertEquals(input.tokens.take(3).map(_.text), Vector("A", "B", "C"))
    val item = proposed(
      input,
      "(x / thing~0,2)",
      Vector("x" -> Vector(0, 1)),
      ParserAlignmentDialect.IbmIsiRangeV1
    )
    assertEquals(
      failure(runOne(input, item)),
      ParserFailure.AlignmentDialectUnsupported(
        Checksum.ofText(ParserAlignmentDialect.IbmIsiRangeV1.wireName)
      )
    )
  }

  test("court B rejects adjacent structured indices collapsed to one rendered marker") {
    val input = inputFor("A B C.", "adjacent-loss")
    val item = proposed(input, "(x / thing~e.0)", Vector("x" -> Vector(0, 1)))
    assertEquals(
      failure(runOne(input, item)),
      ParserFailure.AlignmentSidecarInvalid(
        AlignmentSidecarIssue.MarkerVectorMismatch(0, Vector(0), Vector(0, 1))
      )
    )
  }

  test("court C rejects gapped structured indices expanded by a rendered range") {
    val input = inputFor("A B C D.", "gapped-loss")
    val item = proposed(input, "(x / thing~e.0,3)", Vector("x" -> Vector(0, 2)))
    assertEquals(
      failure(runOne(input, item)),
      ParserFailure.AlignmentSidecarInvalid(
        AlignmentSidecarIssue.MarkerVectorMismatch(0, Vector(0, 3), Vector(0, 2))
      )
    )
  }

  test("concept and literal-target marker occurrences retain their provider node provenance") {
    val penman = "(b / boy~e.1 :mode imperative~e.3)"
    val item = proposed(
      inputs(0),
      penman,
      Vector("b" -> Vector(1), "provider-literal-7" -> Vector(3))
    )
    val attempt = runOne(inputs(0), item)
    val chart = attempt.result.toOption.get.evidence.chart
    assert(chart.alignments.exists(_.target.conceptIds.nonEmpty))
    assert(
      chart.alignments.exists {
        case p.PropositionAlignment(p.AlignmentTarget.Relation(relation), _, _, _) =>
          relation.role.source.render == "mode"
        case _ => false
      }
    )
    assert(
      attempt.receipt.decisions.exists {
        case ParserAttemptDecision.AlignmentSidecarAccepted(sidecar) =>
          sidecar.providerNodeIds.map(_.value) == Vector("b", "provider-literal-7")
        case _ => false
      }
    )
  }

  test("provider node provenance changes accepted-sidecar and receipt identity") {
    val penman = "(b / boy~e.1)"
    val first = runOne(inputs(0), proposed(inputs(0), penman, Vector("b" -> Vector(1))))
    val second = runOne(
      inputs(0),
      proposed(inputs(0), penman, Vector("provider-renamed-b" -> Vector(1)))
    )
    val firstSidecar = first.receipt.decisions.collectFirst {
      case ParserAttemptDecision.AlignmentSidecarAccepted(sidecar) => sidecar
    }.get
    val secondSidecar = second.receipt.decisions.collectFirst {
      case ParserAttemptDecision.AlignmentSidecarAccepted(sidecar) => sidecar
    }.get
    assertNotEquals(firstSidecar.providerNodeIds, secondSidecar.providerNodeIds)
    assertNotEquals(firstSidecar.checksum, secondSidecar.checksum)
    assertNotEquals(first.receipt.digest, second.receipt.digest)
  }

  test("swapping concept and literal-target sidecar rows is rejected") {
    val penman = "(b / boy~e.1 :mode imperative~e.3)"
    val swapped = proposed(
      inputs(0),
      penman,
      Vector("provider-literal-7" -> Vector(3), "b" -> Vector(1))
    )
    assertEquals(
      failure(runOne(inputs(0), swapped)),
      ParserFailure.AlignmentSidecarInvalid(
        AlignmentSidecarIssue.MarkerVectorMismatch(0, Vector(1), Vector(3))
      )
    )
  }

  test("sidecar token vectors must be nonempty") {
    val item = proposed(inputs(0), "(b / boy~e.1)", Vector("b" -> Vector.empty))
    assertEquals(
      failure(runOne(inputs(0), item)),
      ParserFailure.AlignmentSidecarInvalid(AlignmentSidecarIssue.EmptyIndices(0))
    )
  }

  test("sidecar token vectors must be sorted") {
    val item = proposed(inputs(0), "(b / boy~e.1,2)", Vector("b" -> Vector(2, 1)))
    assertEquals(
      failure(runOne(inputs(0), item)),
      ParserFailure.AlignmentSidecarInvalid(AlignmentSidecarIssue.IndicesUnsorted(0))
    )
  }

  test("sidecar token vectors must not contain duplicate indices") {
    val item = proposed(inputs(0), "(b / boy~e.1,1)", Vector("b" -> Vector(1, 1)))
    assertEquals(
      failure(runOne(inputs(0), item)),
      ParserFailure.AlignmentSidecarInvalid(AlignmentSidecarIssue.DuplicateIndex(0, 1))
    )
  }

  test("sidecar token vectors must stay within the echoed atlas axis") {
    val item = proposed(inputs(0), "(b / boy~e.99)", Vector("b" -> Vector(99)))
    assertEquals(
      failure(runOne(inputs(0), item)),
      ParserFailure.AlignmentSidecarInvalid(
        AlignmentSidecarIssue.TokenIndexOutOfRange(0, 99, inputs(0).tokens.size)
      )
    )
  }

  test("every rendered marker requires exactly one sidecar row") {
    val item = proposed(inputs(0), "(b / boy~e.1)", Vector.empty)
    assertEquals(
      failure(runOne(inputs(0), item)),
      ParserFailure.AlignmentSidecarInvalid(
        AlignmentSidecarIssue.MarkerCountMismatch(1, 0)
      )
    )
  }

  test("alignment sidecars must declare the exact versioned schema") {
    val item = proposed(
      inputs(0),
      wantPenman,
      wantAlignments,
      alignmentSchema = "provider-private-sidecar"
    )
    assertEquals(
      failure(runOne(inputs(0), item)),
      ParserFailure.AlignmentSidecarSchemaMismatch(
        Checksum.ofText("provider-private-sidecar")
      )
    )
  }

  test("explicit index-list markers require the explicit e prefix") {
    val item = proposed(inputs(0), "(b / boy~1)", Vector("b" -> Vector(1)))
    assertEquals(
      failure(runOne(inputs(0), item)),
      ParserFailure.AlignmentSidecarInvalid(AlignmentSidecarIssue.MarkerPrefixMismatch(0))
    )
  }

  test("marker sidecar ordinals must equal rendered occurrence order") {
    val base = proposed(inputs(0), "(b / boy~e.1)", Vector("b" -> Vector(1)))
    val changed = base.result match
      case WireItemResult.Proposed(penman, schema, dialect, rows) =>
        base.copy(
          result = WireItemResult.Proposed(
            penman,
            schema,
            dialect,
            rows.map(_.copy(ordinal = 1))
          )
        )
      case _ => fail("fixture must be proposed")
    assertEquals(
      failure(runOne(inputs(0), changed)),
      ParserFailure.AlignmentSidecarInvalid(AlignmentSidecarIssue.OrdinalMismatch(0, 1))
    )
  }

  test("provider node provenance ids are bounded nonblank tokens") {
    val item = proposed(inputs(0), "(b / boy~e.1)", Vector("not a node" -> Vector(1)))
    assertEquals(
      failure(runOne(inputs(0), item)),
      ParserFailure.AlignmentSidecarInvalid(AlignmentSidecarIssue.ProviderNodeIdInvalid(0))
    )
  }

  test("timeout is a typed recorded absence for every input") {
    val transport = new ScriptedTransport(Vector(Left(TransportFailure.Timeout(5000L))))
    val result = provider(transport).parse(batch)
    assertEquals(transport.calls, 1)
    assertEquals(result.covered, 0)
    result.attempts.foreach { attempt =>
      assertEquals(failure(attempt), ParserFailure.Timeout(5000L))
      assert(attempt.receipt.call.nonEmpty)
      assert(
        attempt.receipt.decisions.exists {
          case ParserAttemptDecision.TransportFailed(TransportFailure.Timeout(5000L)) => true
          case _                                                                      => false
        }
      )
    }
  }

  test("batch materialization failure poisons siblings without isolation") {
    val sum = storymodel4s.core.Checksum.ofText("no-root materialization")
    val transport = new ScriptedTransport(Vector(Left(TransportFailure.Materialization(sum))))
    val result = provider(transport).parse(batch)
    assertEquals(transport.calls, 1)
    assertEquals(result.covered, 0)
    result.attempts.foreach { attempt =>
      assertEquals(failure(attempt), ParserFailure.MaterializationFailed(sum))
    }
  }

  test("sentence isolation preserves a good canonical outcome beside poison in both orders") {
    val sum = storymodel4s.core.Checksum.ofText("no-root materialization")
    val goodOnly = ParserBatch.validated(Vector(inputs(0))).toOption.get
    val aloneTransport = new ScriptedTransport(
      Vector(Right(response(Vector(wantItem()))))
    )
    val alone = provider(aloneTransport).parse(goodOnly).attempts.head.result.toOption.get

    val forwardTransport = new ScriptedTransport(
      Vector(
        Right(response(Vector(wantItem()))),
        Left(TransportFailure.Materialization(sum))
      )
    )
    val forward = SentenceIsolatingParserProvider(provider(forwardTransport)).parse(batch)
    assertEquals(forwardTransport.calls, 2)
    assertEquals(
      forward.attempts(0).result.toOption.map(_.canonicalDigest),
      Some(alone.canonicalDigest)
    )
    assertEquals(failure(forward.attempts(1)), ParserFailure.MaterializationFailed(sum))
    forward.attempts.zip(batch.inputs).foreach { (attempt, input) =>
      assert(ParserAttempt.from(input, attempt.result, attempt.receipt).isRight)
    }

    val reverseBatch = ParserBatch.validated(inputs.reverse).toOption.get
    val reverseTransport = new ScriptedTransport(
      Vector(
        Left(TransportFailure.Materialization(sum)),
        Right(response(Vector(wantItem())))
      )
    )
    val reverse = SentenceIsolatingParserProvider(provider(reverseTransport)).parse(reverseBatch)
    assertEquals(reverseTransport.calls, 2)
    assertEquals(failure(reverse.attempts(0)), ParserFailure.MaterializationFailed(sum))
    assertEquals(
      reverse.attempts(1).result.toOption.map(_.canonicalDigest),
      Some(alone.canonicalDigest)
    )
    reverse.attempts.zip(reverseBatch.inputs).foreach { (attempt, input) =>
      assert(ParserAttempt.from(input, attempt.result, attempt.receipt).isRight)
    }
  }

  test("malformed JSON fails every id instead of fabricating a default chart") {
    val transport = new ScriptedTransport(Vector(Right("{not-json")))
    val result = provider(transport).parse(batch)
    assertEquals(result.covered, 0)
    assert(
      result.attempts.forall(attempt =>
        failure(attempt).isInstanceOf[ParserFailure.MalformedEnvelope]
      )
    )
    assert(result.decisions.exists(_.isInstanceOf[ParserBatchDecision.EnvelopeRejected]))
  }

  test("response must echo the exact parser configuration checksum") {
    val wrong = successResponse.replace(
      config.checksum.hex,
      storymodel4s.core.Checksum.ofText("other-config").hex
    )
    val transport = new ScriptedTransport(Vector(Right(wrong)))
    val result = provider(transport).parse(batch)
    result.attempts.foreach { attempt =>
      assert(failure(attempt).isInstanceOf[ParserFailure.ConfigChecksumMismatch])
    }
  }

  test("wrong schemas retain only a checksum of provider text") {
    val rawSchema = "provider-private-schema-text"
    val wrong = successResponse.replace(ParserEnvelope.ResultSchema, rawSchema)
    val transport = new ScriptedTransport(Vector(Right(wrong)))
    val result = provider(transport).parse(batch)
    result.attempts.foreach { attempt =>
      assertEquals(
        failure(attempt),
        ParserFailure.WrongSchema(storymodel4s.core.Checksum.ofText(rawSchema))
      )
      assert(!failure(attempt).toString.contains(rawSchema))
    }
  }

  test("missing and unexpected output ids are recorded while a valid sibling survives") {
    val unknown = ParserRequestId.unsafe("unexpected")
    val extra = wantItem().copy(id = unknown)
    val second = sleepItem()
    val transport = new ScriptedTransport(Vector(Right(response(Vector(extra, second)))))
    val result = provider(transport).parse(batch)
    assertEquals(failure(result.attempts(0)), ParserFailure.MissingOutput(inputs(0).id))
    assert(result.attempts(1).result.isRight)
    assert(
      result.decisions.contains(ParserBatchDecision.UnexpectedOutput(unknown))
    )
  }

  test("duplicate output id rejects only that request") {
    val first = wantItem()
    val second = sleepItem()
    val transport =
      new ScriptedTransport(Vector(Right(response(Vector(first, first, second)))))
    val result = provider(transport).parse(batch)
    assertEquals(failure(result.attempts(0)), ParserFailure.DuplicateOutput(inputs(0).id, 2))
    assert(result.attempts(1).result.isRight)
    assert(
      result.decisions.contains(ParserBatchDecision.DuplicateOutput(inputs(0).id, 2))
    )
  }

  test("reordered wire results are restored to request order and the repair is recorded") {
    val first = wantItem()
    val second = sleepItem()
    val transport = new ScriptedTransport(Vector(Right(response(Vector(second, first)))))
    val result = provider(transport).parse(batch)
    assertEquals(result.attempts.map(_.id), batch.ids)
    assertEquals(result.covered, 2)
    assertEquals(result.decisions.count(_.isInstanceOf[ParserBatchDecision.Reordered]), 2)
  }

  test("RuntimeDependencyUnpinned produces zero calls and one failure per input") {
    val reason = ParserSetupFailure
      .runtimeDependencyUnpinned(
        Vector(RuntimeComponent.Dependency("bart.large"))
      )
      .toOption
      .get
    val transport = new ScriptedTransport(Vector(Right(successResponse)))
    val unavailable = JsonAmrCandidateProvider[Id](
      ParserRuntime.Unavailable(reason),
      config,
      lexicon,
      transport
    )
    val result = unavailable.parse(batch)
    assertEquals(transport.calls, 0)
    result.attempts.foreach { attempt =>
      assertEquals(
        failure(attempt),
        ParserFailure.RuntimeUnavailable(reason)
      )
      assertEquals(attempt.receipt.call, None)
    }
  }

  test("unavailable runtime reasons remain distinct and perform no provider call") {
    val cases = Vector(
      ParserSetupFailure.artifactMissing(Vector(RuntimeComponent.Checkpoint)).toOption.get,
      ParserSetupFailure
        .platformIncompatible(
          RuntimePlatform.DarwinArm64,
          Vector(RuntimePlatform.LinuxX8664)
        )
        .toOption
        .get,
      ParserSetupFailure
        .resourceBudgetInsufficient(
          RuntimeResource.DiskBytes,
          required = 4_968_135_420L,
          available = 4_000_000_000L
        )
        .toOption
        .get
    )
    cases.foreach { reason =>
      val transport = new ScriptedTransport(Vector(Right(successResponse)))
      val unavailable = JsonAmrCandidateProvider[Id](
        ParserRuntime.Unavailable(reason),
        config,
        lexicon,
        transport
      )
      val result = unavailable.parse(batch)
      assertEquals(transport.calls, 0)
      assertEquals(
        result.attempts.map(failure),
        Vector.fill(batch.size)(ParserFailure.RuntimeUnavailable(reason))
      )
    }
  }

  test("malformed PENMAN is a typed interop rejection and does not erase siblings") {
    val malformed = proposed(inputs(0), "(w / want-01", Vector.empty)
    val second = sleepItem()
    val transport = new ScriptedTransport(Vector(Right(response(Vector(malformed, second)))))
    val result = provider(transport).parse(batch)
    failure(result.attempts(0)) match
      case ParserFailure.InteropRejected(InteropFailureKind.Malformed, _) => ()
      case other => fail(s"expected sanitized malformed interop rejection, got $other")
    assert(result.attempts(1).result.isRight)
  }
