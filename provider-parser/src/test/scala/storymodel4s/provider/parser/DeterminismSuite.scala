package storymodel4s.provider.parser

import cats.Id
import munit.FunSuite

class DeterminismSuite extends FunSuite:
  import TestFixtures.*

  private def provider(transport: ScriptedTransport): JsonAmrCandidateProvider[Id] =
    JsonAmrCandidateProvider(ParserRuntime.Ready(runtime), config, lexicon, transport)

  test("PENMAN variable renaming and relation order do not create false nondeterminism") {
    val equivalentWant =
      "(x / want-01~e.2 :ARG1 (z / go-02~e.4 :ARG0 (y / boy~e.1)) :ARG0 y)"
    val secondResponse = response(
      Vector(
        proposed(
          inputs(0),
          equivalentWant,
          Vector("x" -> Vector(2), "z" -> Vector(4), "y" -> Vector(1))
        ),
        sleepItem()
      )
    )
    val transport = new ScriptedTransport(Vector(Right(successResponse), Right(secondResponse)))
    val parser = provider(transport)
    val first = parser.parse(batch)
    val second = parser.parse(batch)
    val report = ParserDeterminism.compare(batch, first, second).toOption.get
    assertEquals(transport.calls, 2)
    assert(report.isStable)
    assertEquals(report.failures, Vector.empty)
  }

  test("nondeterministic second call is detected while an unchanged sibling stays stable") {
    val changedWant =
      "(w / want-01~e.2 :polarity - :ARG0 (b / boy~e.1) :ARG1 (g / go-02~e.4 :ARG0 b))"
    val secondResponse = response(
      Vector(proposed(inputs(0), changedWant, wantAlignments), sleepItem())
    )
    val transport = new ScriptedTransport(Vector(Right(successResponse), Right(secondResponse)))
    val parser = provider(transport)
    val first = parser.parse(batch)
    val second = parser.parse(batch)
    val report = ParserDeterminism.compare(batch, first, second).toOption.get
    assertEquals(transport.calls, 2)
    assert(!report.isStable)
    assertEquals(report.failures.map(_._1), Vector(inputs(0).id))
    assert(
      report.checks.contains(ParserDeterminismCheck.Stable(inputs(1).id)),
      "stable sibling was erased by the changed result"
    )
  }
