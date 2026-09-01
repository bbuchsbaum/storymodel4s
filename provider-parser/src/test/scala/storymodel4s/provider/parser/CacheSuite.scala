package storymodel4s.provider.parser

import cats.Id
import munit.FunSuite

class CacheSuite extends FunSuite:
  import TestFixtures.*

  private def failure(attempt: ParserAttempt): ParserFailure = attempt.result match
    case Left(value) => value
    case Right(_)    => fail(s"expected failure for ${attempt.id.value}")

  test("cache replay performs zero additional transport calls and preserves proposal provenance") {
    val transport = new ScriptedTransport(Vector(Right(successResponse)))
    val delegate = JsonAmrCandidateProvider[Id](
      ParserRuntime.Ready(runtime),
      config,
      lexicon,
      transport
    )
    val cache = new MemoryCache
    val cached = CachingParserProvider.from[Id](delegate, cache).toOption.get

    val first = cached.parse(batch)
    assertEquals(transport.calls, 1)
    assert(first.attempts.forall(_.receipt.call.nonEmpty))
    assert(
      first.attempts.forall(
        _.receipt.decisions.exists(_.isInstanceOf[ParserAttemptDecision.CacheMiss])
      )
    )
    first.attempts.zip(batch.inputs).foreach { (attempt, input) =>
      val key = ParserCacheKey.of(input, runtime, config)
      assert(ParserAttempt.from(input, attempt.result, attempt.receipt, Some(key)).isRight)
    }

    val second = cached.parse(batch)
    assertEquals(transport.calls, 1)
    assert(second.attempts.forall(_.receipt.call.isEmpty))
    assert(second.attempts.forall(_.receipt.isCacheHit))
    assertEquals(second.attempts.map(_.result), first.attempts.map(_.result))
    second.attempts.foreach { attempt =>
      val evidence = attempt.result.toOption.get.evidence
      assert(evidence.chart.provenance.receipts.exists(_.provider == runtime.provider))
    }
    second.attempts.zip(batch.inputs).foreach { (attempt, input) =>
      val key = ParserCacheKey.of(input, runtime, config)
      assert(ParserAttempt.from(input, attempt.result, attempt.receipt, Some(key)).isRight)
    }
  }

  test("cache-wrapped transport failures re-enter through the checked attempt constructor") {
    val reason = TransportFailure.Timeout(config.timeoutMillis)
    val transport = new ScriptedTransport(Vector(Left(reason)))
    val delegate = JsonAmrCandidateProvider[Id](
      ParserRuntime.Ready(runtime),
      config,
      lexicon,
      transport
    )
    val cached = CachingParserProvider.from[Id](delegate, new MemoryCache).toOption.get
    val result = cached.parse(batch)

    result.attempts.zip(batch.inputs).foreach { (attempt, input) =>
      val key = ParserCacheKey.of(input, runtime, config)
      assertEquals(attempt.result, Left(ParserFailure.Timeout(config.timeoutMillis)))
      assert(ParserAttempt.from(input, attempt.result, attempt.receipt, Some(key)).isRight)
    }
  }

  test("corrupt cache entry fails closed without erasing a valid cached sibling") {
    val transport = new ScriptedTransport(Vector(Right(successResponse)))
    val delegate = JsonAmrCandidateProvider[Id](
      ParserRuntime.Ready(runtime),
      config,
      lexicon,
      transport
    )
    val cache = new MemoryCache
    val cached = CachingParserProvider.from[Id](delegate, cache).toOption.get
    val first = cached.parse(batch)
    assert(first.attempts.forall(_.result.isRight))
    val key = ParserCacheKey.of(inputs.head, runtime, config)
    val foreignKey = ParserCacheKey.of(inputs(1), runtime, config)
    val foreign = cache.get(foreignKey).getOrElse(fail("expected second cached proposal"))
    cache.overwrite(key, foreign)

    val replay = cached.parse(batch)
    assertEquals(transport.calls, 1)
    assert(failure(replay.attempts.head).isInstanceOf[ParserFailure.CacheCorrupt])
    assert(
      ParserAttempt
        .from(
          inputs.head,
          replay.attempts.head.result,
          replay.attempts.head.receipt,
          Some(key)
        )
        .isRight
    )
    assert(replay.attempts(1).result.isRight)
    assertEquals(replay.attempts(1).receipt.call, None)
  }

  test("a cache witness for another request cannot authorize a no-call success") {
    val transport = new ScriptedTransport(Vector(Right(successResponse)))
    val delegate = JsonAmrCandidateProvider[Id](
      ParserRuntime.Ready(runtime),
      config,
      lexicon,
      transport
    )
    val cache = new MemoryCache
    val cached = CachingParserProvider.from[Id](delegate, cache).toOption.get
    val first = cached.parse(batch)
    val proposal = first.attempts.head.result.toOption.get
    val foreignKey = ParserCacheKey.of(inputs(1), runtime, config)
    val foreign = cache.get(foreignKey).getOrElse(fail("expected second cached proposal"))
    val receipt = ParserAttemptReceipt.of(
      inputs.head,
      None,
      Vector(ParserAttemptDecision.CacheHit(foreign))
    )

    assertEquals(
      ParserAttempt.proposed(inputs.head, proposal, receipt),
      Left(ParserFailure.ProvenanceMissing)
    )
  }

  test("a fresh provider call cannot be combined with cache-hit authority") {
    val transport = new ScriptedTransport(Vector(Right(successResponse)))
    val delegate = JsonAmrCandidateProvider[Id](
      ParserRuntime.Ready(runtime),
      config,
      lexicon,
      transport
    )
    val cache = new MemoryCache
    val cached = CachingParserProvider.from[Id](delegate, cache).toOption.get
    val first = cached.parse(batch)
    val proposal = first.attempts.head.result.toOption.get
    val call = first.attempts.head.receipt.call.get
    val key = ParserCacheKey.of(inputs.head, runtime, config)
    val witness = cache.get(key).getOrElse(fail("expected cached proposal"))
    val receipt = ParserAttemptReceipt.of(
      inputs.head,
      Some(call),
      Vector(ParserAttemptDecision.CacheHit(witness))
    )

    assertEquals(
      ParserAttempt.proposed(inputs.head, proposal, receipt),
      Left(ParserFailure.ProvenanceMissing)
    )
  }

  test("cache key changes with parser configuration") {
    val changed = ParserConfig.from(Map("beam" -> "2"), Some(42L), 5000L).toOption.get
    assertNotEquals(
      ParserCacheKey.of(inputs.head, runtime, config),
      ParserCacheKey.of(inputs.head, runtime, changed)
    )
  }

  test("cache wrapper cannot be constructed around an unavailable delegate") {
    val reason = ParserSetupFailure
      .runtimeDependencyUnpinned(Vector(RuntimeComponent.Checkpoint))
      .toOption
      .get
    val transport = new ScriptedTransport(Vector(Right(successResponse)))
    val unavailable = JsonAmrCandidateProvider[Id](
      ParserRuntime.Unavailable(reason),
      config,
      lexicon,
      transport
    )
    assertEquals(CachingParserProvider.from[Id](unavailable, new MemoryCache), Left(reason))
  }
