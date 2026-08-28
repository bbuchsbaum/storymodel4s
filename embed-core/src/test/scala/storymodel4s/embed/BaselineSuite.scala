package storymodel4s.embed

import cats.Id
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

class BaselineSuite extends ScalaCheckSuite:
  private val keys =
    SensitiveKeyProvider.static(KeyId.unsafe("k1"), "store-local-secret".getBytes("UTF-8"))

  private def docSpace(e: Embedder[Id]) =
    e.spaces.find(s => s.role == Role.Document && s.view == SemanticView.Surface).get
  private def querySpace(e: Embedder[Id]) =
    e.spaces.find(s => s.role == Role.Query && s.view == SemanticView.Surface).get

  private def batch(e: Embedder[Id], texts: Vector[String], space: EmbeddingSpace): EmbedBatch =
    EmbedBatch
      .validated(
        texts.zipWithIndex.map { case (t, i) =>
          EmbedRequest(RequestId.unsafe(s"r$i"), EmbedPayload.Raw(t, Sensitivity.Public), space.id)
        },
        e.spaceIds
      )
      .toOption
      .get

  property(
    "hashed n-gram embedder is deterministic and its fingerprint is stable across instances"
  ) {
    forAll(Gens.text) { t =>
      val a = HashedNgramEmbedder[Id](64, 7L)
      val b = HashedNgramEmbedder[Id](64, 7L)
      val ra = a.embed(batch(a, Vector(t), docSpace(a)))
      val rb = b.embed(batch(b, Vector(t), docSpace(b)))
      a.info.provider == b.info.provider &&
      a.spaces.map(_.id) == b.spaces.map(_.id) &&
      ra.outcomes.head.value == rb.outcomes.head.value &&
      ra.outcomes.head.value.exists(_.isObserved)
    }
  }

  test("different seed or dimension ⇒ different provider fingerprint and spaces") {
    val a = HashedNgramEmbedder[Id](64, 1L)
    val b = HashedNgramEmbedder[Id](64, 2L)
    val c = HashedNgramEmbedder[Id](128, 1L)
    assertNotEquals(a.info.provider, b.info.provider)
    assertNotEquals(a.info.provider, c.info.provider)
    assert(a.spaces.map(_.id).intersect(b.spaces.map(_.id)).isEmpty)
  }

  test("paraphrase-ish neighbours are closer than unrelated text; empty input abstains") {
    val e = HashedNgramEmbedder[Id](256, 0L)
    val space = docSpace(e)
    val r = e.embed(
      batch(
        e,
        Vector(
          "the young man hid behind a log",
          "the young man hid behind the log",
          "blood came from his mouth",
          "   "
        ),
        space
      )
    )
    val vs = r.outcomes.map(_.value.toOption.get)
    val v0 = vs(0).toOption.get
    val near = Distances.cosine(v0, vs(1).toOption.get).toOption.get.value
    val far = Distances.cosine(v0, vs(2).toOption.get).toOption.get.value
    assert(near < far, s"near=$near far=$far")
    assert(!vs(3).isObserved)
    assertEquals(r.receipt.providerCalls.size, 1)
    assert(!r.receipt.providerCalls.head.cached)
  }

  test("tf-idf fingerprint includes the corpus; out-of-vocabulary text abstains") {
    val c1 = TfIdfEmbedder.fit[Id](
      Vector("the young man went to the river", "the river was foggy and calm")
    )
    val c2 =
      TfIdfEmbedder.fit[Id](Vector("the young man went to the river", "canoes came up the river"))
    val (a, b) = (c1.toOption.get, c2.toOption.get)
    assertNotEquals(a.info.provider, b.info.provider)
    assertEquals(TfIdfEmbedder.fit[Id](Vector("", "  ")).isLeft, true)
    val r = a.embed(batch(a, Vector("foggy river", "zebra"), docSpace(a)))
    assert(r.outcomes(0).value.exists(_.isObserved))
    assert(r.outcomes(1).value.exists(e => !e.isObserved))
  }

  test("query/document spaces of one baseline form a validated pair") {
    val e = HashedNgramEmbedder[Id](32, 0L)
    assert(GeometryPair.validated(querySpace(e), docSpace(e)).isRight)
  }

  test("caching embedder: second batch hits, receipts record decisions, sensitive keys are HMAC") {
    val inner = HashedNgramEmbedder[Id](64, 0L, keys)
    val cache = EmbeddingCache.inMemory[Id]
    val e = new CachingEmbedder[Id](inner, cache, keys)
    val space = docSpace(inner)
    val b1 = EmbedBatch
      .validated(
        Vector(
          EmbedRequest(
            RequestId.unsafe("a"),
            EmbedPayload.Raw("the young man", Sensitivity.Public),
            space.id
          ),
          EmbedRequest(
            RequestId.unsafe("b"),
            EmbedPayload.Raw("my sister's wedding", Sensitivity.Sensitive),
            space.id
          )
        ),
        e.spaceIds
      )
      .toOption
      .get
    val r1 = e.embed(b1)
    assert(r1.conforms(b1).isRight)
    assertEquals(r1.receipt.cacheDecisions.collect { case CacheDecision.Miss(_, _) => 1 }.sum, 2)
    assertEquals(r1.receipt.providerCalls.size, 1)
    assertEquals(cache.size, 2)
    val sensitiveKey = r1.receipt.cacheDecisions.collectFirst {
      case CacheDecision.Miss(id, k) if id.value == "b" => k
    }.get
    assert(sensitiveKey.render.startsWith("hmac:k1:"), sensitiveKey.render)
    assert(!sensitiveKey.render.contains("wedding"))
    val r2 = e.embed(b1)
    assert(r2.conforms(b1).isRight)
    assertEquals(r2.receipt.cacheDecisions.collect { case CacheDecision.Hit(_, _) => 1 }.sum, 2)
    assertEquals(r2.receipt.providerCalls.size, 0)
    assertEquals(r2.outcomes.map(_.value), r1.outcomes.map(_.value))
  }

  test("caching embedder never caches abstentions and keys by role/instruction via the space") {
    val inner = HashedNgramEmbedder[Id](64, 0L, keys)
    val cache = EmbeddingCache.inMemory[Id]
    val e = new CachingEmbedder[Id](inner, cache, keys)
    val q = querySpace(inner)
    val d = docSpace(inner)
    val b = EmbedBatch
      .validated(
        Vector(
          EmbedRequest(RequestId.unsafe("q"), EmbedPayload.Raw("river", Sensitivity.Public), q.id),
          EmbedRequest(RequestId.unsafe("d"), EmbedPayload.Raw("river", Sensitivity.Public), d.id),
          EmbedRequest(RequestId.unsafe("e"), EmbedPayload.Raw("   ", Sensitivity.Public), d.id)
        ),
        e.spaceIds
      )
      .toOption
      .get
    val r = e.embed(b)
    assertEquals(cache.size, 2)
    val keysRendered = r.receipt.cacheDecisions.collect { case CacheDecision.Miss(_, k) =>
      k.render
    }
    // Three distinct keys were looked up (query/river, document/river, document/blank)…
    assertEquals(keysRendered.distinct.size, 3)
    // …but only the two observed vectors were stored; the abstention was not cached.
    assert(r.outcomes(2).value.exists(x => !x.isObserved))
  }

  test(
    "remote-locality provider refuses raw sensitive text at preflight, recorded in the receipt"
  ) {
    val inner = HashedNgramEmbedder[Id](16, 0L)
    val remote = new Embedder[Id]:
      val info: EmbedderInfo = inner.info.copy(locality = Locality.Remote)
      val spaces: Vector[EmbeddingSpace] = inner.spaces
      def embed(batch: EmbedBatch): BatchResult =
        val outcomes = batch.requests.map { r =>
          Embedder.preflight(info, r) match
            case Left(f)   => EmbedOutcome(r.id, r.space, Left(f))
            case Right(()) =>
              inner
                .embed(EmbedBatch.validated(Vector(r), inner.spaceIds).toOption.get)
                .outcomes
                .head
        }
        val policy = outcomes.flatMap(_.value.left.toOption).collect {
          case ExecutionFailure.LocalOnly(d) => d
        }
        BatchResult(outcomes, AttemptReceipt.public(Vector.empty, Vector.empty, policy))
    val space = docSpace(inner)
    val b = EmbedBatch
      .validated(
        Vector(
          EmbedRequest(
            RequestId.unsafe("s"),
            EmbedPayload.Raw("my sister's wedding", Sensitivity.Sensitive),
            space.id
          )
        ),
        remote.spaceIds
      )
      .toOption
      .get
    val r = remote.embed(b)
    assert(r.outcomes.head.value.isLeft)
    assertEquals(r.receipt.policyDecisions.size, 1)
    assert(r.receipt.providerCalls.isEmpty)
    assert(!r.receipt.policyDecisions.head.render.contains("wedding"))
  }
