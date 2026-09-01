package storymodel4s.provider.agent

import cats.Id
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import storymodel4s.amr.schema.StarterLexicon
import storymodel4s.provider.parser.*

/** Each mutation names the exact `ParserFailure` variant the real court must answer with. */
class MutationSuite extends FunSuite:
  import AgentFixtures.*

  private val first = inputs.head
  private val single = ParserBatch.validated(Vector(first)).toOption.get

  private def attemptFor(reply: ModelReply): ParserAttempt =
    val provider = replayProvider(
      new ModelExchange.Recorded(recordingsWith(Map(first -> reply)))
    )
    val result = provider.parse(single)
    assertEquals(result.total, 1)
    result.attempts.head

  /** A transport whose JSON is rewritten after the honest adapter produced it. */
  private final class JsonMutatingTransport(inner: ParserTransport[Id], mutate: Json => Json)
      extends ParserTransport[Id]:
    def exchange(requestJson: String, timeoutMillis: Long): Either[TransportFailure, String] =
      inner.exchange(requestJson, timeoutMillis).map { raw =>
        parse(raw).fold(error => fail(error.message), json => mutate(json).noSpaces)
      }

  private def attemptWithMutatedJson(mutate: Json => Json): ParserAttempt =
    val recordings = recordingsWith(Map(first -> reply(penman(0))))
    val honest = transportOver(new ModelExchange.Recorded(recordings))
    val provider = JsonAmrCandidateProvider[Id](
      ParserRuntime.Remote(honest.runtime),
      config,
      StarterLexicon.lexicon,
      new JsonMutatingTransport(honest, mutate)
    )
    val result = provider.parse(single)
    assertEquals(result.total, 1)
    result.attempts.head

  private def firstItem(json: Json)(edit: Json => Json): Json =
    json.hcursor
      .downField("items")
      .downArray
      .withFocus(edit)
      .top
      .getOrElse(fail("no items array"))

  test("the honest recording is admitted, so every mutation below has a positive control") {
    val attempt = attemptFor(reply(penman(0)))
    assert(attempt.result.isRight, attempt.result.toString)
  }

  test("(a1) stripping every marker from the PENMAN fails as AlignmentMissing") {
    val stripped = penman(0).replaceAll("~e\\.[0-9,]+", "")
    assertEquals(failureOf(attemptFor(reply(stripped))), ParserFailure.AlignmentMissing)
  }

  test("(a2) stripping the sidecar from the emitted JSON fails as MarkerCountMismatch") {
    val attempt = attemptWithMutatedJson { json =>
      firstItem(json) { item =>
        item.hcursor
          .downField("result")
          .downField("alignments")
          .withFocus(_ => Json.arr())
          .top
          .getOrElse(fail("no alignments"))
      }
    }
    assertEquals(
      failureOf(attempt),
      ParserFailure.AlignmentSidecarInvalid(AlignmentSidecarIssue.MarkerCountMismatch(4, 0))
    )
  }

  test("(b) shifting one marker index out of range fails as TokenIndexOutOfRange") {
    val shifted = penman(0).replace("city~e.4", "city~e.44")
    assertEquals(
      failureOf(attemptFor(reply(shifted))),
      ParserFailure.AlignmentSidecarInvalid(AlignmentSidecarIssue.TokenIndexOutOfRange(2, 44, 6))
    )
  }

  test("(b') an unsorted marker vector is passed through and fails as IndicesUnsorted") {
    val unsorted = penman(0).replace("city~e.4", "city~e.4,3")
    assertEquals(
      failureOf(attemptFor(reply(unsorted))),
      ParserFailure.AlignmentSidecarInvalid(AlignmentSidecarIssue.IndicesUnsorted(2))
    )
  }

  test("(c) altering one echoed token text fails as TokenEchoMismatch at that index") {
    val attempt = attemptWithMutatedJson { json =>
      firstItem(json) { item =>
        item.hcursor
          .downField("tokens")
          .downN(4)
          .downField("text")
          .withFocus(_ => Json.fromString("Egulak"))
          .top
          .getOrElse(fail("no token 4"))
      }
    }
    assertEquals(failureOf(attempt), ParserFailure.TokenEchoMismatch(4))
  }

  test("(d) an ABSTAIN recording is an abstained attempt with the abstain code") {
    assertEquals(
      failureOf(attemptFor(reply("ABSTAIN\n"))),
      ParserFailure.ProviderAbstained(AgentFailureCodes.Abstain)
    )
  }

  test("(d') a refusal stop reason is an abstained attempt with the refusal code") {
    assertEquals(
      failureOf(attemptFor(reply("", ModelStopReason.Refusal))),
      ParserFailure.ProviderAbstained(AgentFailureCodes.Refusal)
    )
  }

  test("(e) an unparsable response is a failed attempt with the penman-unparsable code") {
    assertEquals(
      failureOf(attemptFor(reply("(b / be-located-at-91~e.1 :ARG1"))),
      ParserFailure.ProviderFailed(AgentFailureCodes.PenmanUnparsable)
    )
  }

  test("(e') a max_tokens stop is a failed attempt with the max-tokens code") {
    assertEquals(
      failureOf(attemptFor(reply(penman(0), ModelStopReason.MaxTokens))),
      ParserFailure.ProviderFailed(AgentFailureCodes.MaxTokens)
    )
  }

  test("(e'') an unexpected stop reason is a failed attempt, never a proposal") {
    assertEquals(
      failureOf(attemptFor(reply(penman(0), ModelStopReason.Other("tool_use")))),
      ParserFailure.ProviderFailed(AgentFailureCodes.UnexpectedStop)
    )
  }

  test("a fenced reply is admitted after the fence is stripped, and the recording is untouched") {
    val fenced = "```penman\n" + penman(0) + "\n```\n"
    val store = recordingsWith(Map(first -> reply(fenced)))
    val attempt = replayProvider(new ModelExchange.Recorded(store)).parse(single).attempts.head
    assert(attempt.result.isRight, attempt.result.toString)
    assertEquals(store.read(keyFor(first), runtime.model).map(_.text), Right(fenced))
  }
