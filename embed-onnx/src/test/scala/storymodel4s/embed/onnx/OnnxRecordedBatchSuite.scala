package storymodel4s.embed.onnx

import java.nio.file.{Path, Paths}

import munit.FunSuite

import storymodel4s.core.Checksum
import storymodel4s.embed.*

/** Synthetic adapter attempts distinguish replay authority from submitted receipts. */
class OnnxRecordedBatchSuite extends FunSuite:
  private val modelChecksum =
    Checksum.unsafe("3e21121e42719ab61e888e8dbb9559591278701bec9193729bd2d1de38daca02")
  private val tokenizerChecksum =
    Checksum.unsafe("0237b6bbc55d00142f9fa04557641e8ce5ea3abaf89e957aac5bca7dbd3bbf9a")

  private def fixture(maxTokens: Int = 4): OnnxSentenceModel =
    OnnxSentenceModel.testFixture(modelChecksum, tokenizerChecksum, dimension = 4, maxTokens)

  private def resource(name: String): Path =
    Paths.get(getClass.getResource(s"/fixture/$name").toURI)

  private def withEncoder[A](f: OnnxSentenceEmbedder => A): A =
    val encoder = OnnxSentenceEmbedder
      .open(fixture(), OnnxSentenceArtifacts(resource("model.onnx"), resource("tokenizer.json")))
      .fold(e => fail(e.message), identity)
    try f(encoder)
    finally encoder.close()

  private def batch(e: OnnxSentenceEmbedder, requests: Vector[EmbedRequest]): EmbedBatch =
    EmbedBatch.validated(requests, e.spaceIds).fold(error => fail(error.message), identity)

  private def requests(e: OnnxSentenceEmbedder): Vector[EmbedRequest] =
    val query = e.spaces.find(_.role == Role.Query).getOrElse(fail("no query space"))
    Vector("hello world", "ghost").zipWithIndex.map { (text, i) =>
      EmbedRequest(
        RequestId.unsafe(s"item-$i"),
        EmbedPayload.Raw(text, Sensitivity.Public),
        query.id
      )
    }

  test("exact replay after closing native resources retains original results and receipts"):
    val (record, submitted, model, provider) = withEncoder { e =>
      val submitted = batch(e, requests(e))
      val record = e.record(submitted)
      assertEquals(record.result.outcomes.map(_.id), submitted.ids)
      assertEquals(record.result.receipt.providerCalls.size, 1)
      val vector = record.result.outcomes.head.value.toOption
        .flatMap(_.toOption)
        .getOrElse(fail("expected observed fixture vector"))
      vector.values.foreach(v => assertEqualsDouble(v, 0.5, 1e-7))
      assertEquals(record.runtimeIdentity, e.runtimeIdentity)
      assertEquals(record.spaces, e.spaces)
      (record, submitted, e.model, e.info.provider)
    }
    assertEquals(record.replay(submitted, model, provider), Right(record.result))
    assertEquals(record.replay(submitted, fixture(), provider), Right(record.result))
    assertEquals(record.result.receipt.providerCalls.size, 1, "replay invented another call")

  test("replay refuses changed exact payload and sensitivity"):
    withEncoder { e =>
      val original = requests(e)
      val record = e.record(batch(e, original))
      val payloads = Vector(
        EmbedPayload.Raw("world hello", Sensitivity.Public),
        EmbedPayload.Raw("hello world", Sensitivity.Sensitive)
      )
      payloads.foreach { payload =>
        val changed = batch(e, original.updated(0, original.head.copy(payload = payload)))
        assertEquals(
          record.replay(changed, e.model, e.info.provider),
          Left(OnnxReplayRefusal.Payload)
        )
      }
    }

  test("replay refuses changed request IDs and order"):
    withEncoder { e =>
      val original = requests(e)
      val record = e.record(batch(e, original))
      val changes = Vector(
        original.updated(0, original.head.copy(id = RequestId.unsafe("replacement"))),
        original.reverse,
        original.take(1)
      )
      changes.foreach { rs =>
        assertEquals(
          record.replay(batch(e, rs), e.model, e.info.provider),
          Left(OnnxReplayRefusal.RequestIds)
        )
      }
    }

  test("replay refuses changed role geometry"):
    withEncoder { e =>
      val original = requests(e)
      val record = e.record(batch(e, original))
      val document = e.spaces.find(_.role == Role.Document).getOrElse(fail("no document space"))
      val changed = batch(e, original.updated(0, original.head.copy(space = document.id)))
      assertEquals(
        record.replay(changed, e.model, e.info.provider),
        Left(OnnxReplayRefusal.Geometry)
      )
    }

  test("replay refuses a foreign provider"):
    withEncoder { e =>
      val submitted = batch(e, requests(e))
      val record = e.record(submitted)
      val foreign = ProviderFingerprint(Checksum.ofText("foreign provider"))
      assertEquals(record.replay(submitted, e.model, foreign), Left(OnnxReplayRefusal.Provider))
    }

  test("replay refuses changed model configuration even with the same provider fingerprint"):
    withEncoder { e =>
      val submitted = batch(e, requests(e))
      val record = e.record(submitted)
      assertEquals(
        record.replay(submitted, fixture(5), e.info.provider),
        Left(OnnxReplayRefusal.Model)
      )
    }

  test("all-too-long attempts replay typed failure rather than successful inference"):
    withEncoder { e =>
      val query = requests(e).head
      val submitted = batch(
        e,
        Vector(query.copy(payload = EmbedPayload.Raw("hello world ghost", Sensitivity.Public)))
      )
      val record = e.record(submitted)
      assertEquals(record.result.outcomes.head.value, Left(ExecutionFailure.TooLong(5, 4)))
      assertEquals(record.replay(submitted, e.model, e.info.provider), Right(record.result))
      assert(record.result.outcomes.forall(_.value.isLeft))
    }

  test("denied payloads remain exactly bound without publishing a new input digest"):
    withEncoder { e =>
      val query = requests(e).head
      val request =
        query.copy(payload = EmbedPayload.Raw("private original", Sensitivity.Sensitive))
      val submitted = batch(e, Vector(request))
      val record = e.record(submitted)
      assertEquals(record.result.receipt.kind, DigestKind.Withheld)
      assertEquals(record.result.receipt.providerCalls, Vector.empty)
      assertEquals(record.result.receipt.embeddingReceipts, Vector.empty)
      record.result.outcomes.head.value match
        case Left(ExecutionFailure.PolicyDenied(_: PolicyDecision.KeyUnavailable)) => ()
        case other => fail(s"expected key-unavailable denial, got $other")
      assertEquals(record.replay(submitted, e.model, e.info.provider), Right(record.result))
      val changed = batch(
        e,
        Vector(
          request.copy(payload = EmbedPayload.Raw("private replacement", Sensitivity.Sensitive))
        )
      )
      assertEquals(
        record.replay(changed, e.model, e.info.provider),
        Left(OnnxReplayRefusal.Payload)
      )
      val other = e.record(changed)
      assertEquals(
        record.result.receipt,
        other.result.receipt,
        "control needs equal denied receipts"
      )
      assertNotEquals(record, other, "receipt equality lost the exact denied payload binding")
      assertEquals(record.hashCode, other.hashCode, "denied text entered a public hash")
      assert(!record.toString.contains("private original"))
      assert(!record.toString.contains(query.id.value))
    }

  test("empty attempts retain zero outcomes and zero provider calls"):
    withEncoder { e =>
      val submitted = batch(e, Vector.empty)
      val record = e.record(submitted)
      assertEquals(record.result.outcomes, Vector.empty)
      assertEquals(record.result.receipt.providerCalls, Vector.empty)
      assertEquals(record.replay(submitted, e.model, e.info.provider), Right(record.result))
    }

  test("equal concrete records have structural equality and equal hashes"):
    withEncoder { e =>
      val submitted = batch(e, requests(e))
      val a = e.record(submitted)
      val b = e.record(submitted)
      assertEquals(a, b)
      assertEquals(a.hashCode, b.hashCode)
    }
