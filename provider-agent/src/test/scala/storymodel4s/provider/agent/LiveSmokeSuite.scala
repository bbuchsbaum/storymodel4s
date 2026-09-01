package storymodel4s.provider.agent

import java.nio.file.Files
import munit.FunSuite
import storymodel4s.provider.parser.*

/** Runs only when the environment opts into spend; otherwise skipped, never failed. */
class LiveSmokeSuite extends FunSuite:
  import AgentFixtures.*

  test("live smoke: one WOG sentence through the model, recorded, and judged by the court") {
    val authorization = AgentCredentials.liveAuthorization(sys.env)
    assume(
      authorization.isRight,
      s"${AgentCredentials.LiveVariable}=1 and a nonblank ${AgentCredentials.PrimaryKeyVariable} " +
        s"(or ${AgentCredentials.FallbackKeyVariable}) are required"
    )
    val client = LiveModelClient.from(authorization.toOption.get)
    val store = Recordings
      .at(Files.createTempDirectory("provider-agent-live-smoke"))
      .fold(error => fail(error.message), identity)
    val sentence = inputs(2)
    val single = ParserBatch.validated(Vector(sentence)).toOption.get
    val provider = replayProvider(new ModelExchange.RecordingLive(client, store))
    val result = provider.parse(single)
    assertEquals(result.total, 1)
    val attempt = result.attempts.head
    assert(attempt.receipt.call.nonEmpty, "no provider call was minted")
    assert(store.contains(keyFor(sentence)), "the live reply was not recorded")
    val replayed = replayProvider(new ModelExchange.Recorded(store)).parse(single)
    val report = ParserDeterminism
      .compare(single, result, replayed)
      .fold(error => fail(error.toString), identity)
    assert(report.isStable, "replaying the just-made recording changed the outcome")
    println(
      s"live smoke: covered=${result.covered} failure=${attempt.result.swap.toOption.map(_.render)}"
    )
  }
