package docsprobe

import cats.Id

import storymodel4s.core.TextSpan
import storymodel4s.embed.*
import storymodel4s.features.Estimate

/** Public deterministic baseline plus the remote-policy authorization boundary. */
@main def useEmbeddings(): Unit =
  val embedder = HashedNgramEmbedder[Id](dimension = 32, seed = 7L)
  val documentSpace = embedder.spaces
    .find(space => space.role == Role.Document && space.view == SemanticView.Surface)
    .getOrElse(sys.error("document surface space is unavailable"))
  val batch = EmbedBatch
    .validated(
      Vector(
        EmbedRequest(
          RequestId.unsafe("story-line"),
          EmbedPayload.Raw("One night two young men left Egulac.", Sensitivity.Public),
          documentSpace.id
        ),
        EmbedRequest(
          RequestId.unsafe("empty-line"),
          EmbedPayload.Raw("   ", Sensitivity.Public),
          documentSpace.id
        )
      ),
      embedder.spaceIds
    )
    .fold(error => sys.error(error.message), identity)
  val result = embedder.embed(batch)

  println(s"baseline: ${embedder.info.name} dimension=${documentSpace.dimension.value} seed=7")
  result.outcomes.foreach { outcome =>
    outcome.value match
      case Right(Estimate.Observed(vector, _)) =>
        println(
          f"${outcome.id.value}: observed dimension=${vector.dimension.value} norm=${vector.norm}%.4f"
        )
      case Right(Estimate.Missing(reason)) =>
        println(s"${outcome.id.value}: missing reason=$reason")
      case Left(failure) =>
        println(s"${outcome.id.value}: failed ${failure.render}")
  }
  println(
    s"batch receipt: kind=${result.receipt.kind} calls=${result.receipt.providerCalls.size} " +
      s"outcomes=${result.outcomes.size}"
  )

  val keyId = KeyId.unsafe("docs-key")
  val keys = SensitiveKeyProvider.static(keyId, "example-store-key".getBytes("UTF-8"))
  val policyId = PrivacyPolicyId.unsafe("docs-remote-policy")
  val source = "Jane Smith"
  val sanitized = "[PERSON_1]"
  val sourceSpan = TextSpan.unsafe(0, source.length)
  val sanitizedSpan = TextSpan.unsafe(0, sanitized.length)
  val detector = PseudonymizationDetector
    .wholeWordTable(Vector(PseudonymizationTableEntry(source, sanitized)))
    .fold(error => sys.error(error.message), identity)
  val detection =
    detector.detect(source, keyId, keys).fold(error => sys.error(error.message), identity)
  val payload = PseudonymizedText
    .checked(
      policyId,
      keyId,
      source,
      sanitized,
      Vector(sourceSpan -> sanitizedSpan),
      detection,
      detector,
      keys
    )
    .fold(error => sys.error(error.message), identity)
  val querySpace = embedder.spaces
    .find(space => space.role == Role.Query && space.view == SemanticView.Surface)
    .getOrElse(sys.error("query surface space is unavailable"))
  val sanitizedRequest = EmbedRequest(
    RequestId.unsafe("remote-sensitive"),
    EmbedPayload.Sanitized(payload),
    querySpace.id
  )
  val policy = RemotePolicy(
    policyId,
    allowedProviders = Set(embedder.info.provider),
    allowedModels = Set(embedder.info.policyModelIdentity),
    allowedPurposes = Set("candidate-retrieval"),
    allowedDetectors = Set(detection.policyIdentity),
    maxBudgetTokens = 100,
    ttlMillis = 60000
  )

  val rawRequest = sanitizedRequest.copy(payload = EmbedPayload.Raw(source, Sensitivity.Sensitive))
  val rawDenied = RemotePolicy
    .evaluate(policy, rawRequest, embedder, "candidate-retrieval", 1000L, 12L)
    .left
    .toOption
    .getOrElse(sys.error("raw remote request was unexpectedly authorized"))
  println(s"raw remote request: denied (${rawDenied.reason})")

  val purposeDenied = RemotePolicy
    .evaluate(policy, sanitizedRequest, embedder, "training", 1000L, 12L)
    .left
    .toOption
    .getOrElse(sys.error("unlisted purpose was unexpectedly authorized"))
  println(s"unlisted purpose: denied (${purposeDenied.reason})")

  val authorized = RemotePolicy
    .evaluate(policy, sanitizedRequest, embedder, "candidate-retrieval", 1000L, 12L)
    .fold(decision => sys.error(decision.reason), identity)
  println(s"authorized purpose: ${authorized.capability.purpose}")
  println(s"authorized expiry: ${authorized.capability.expiresAtEpochMillis}")
  println(s"authorized budget: ${authorized.capability.budgetTokens}")
  println(s"payload digest: ${authorized.capability.payloadDigest.kind}")
  println(s"safe rendering redacts payload: ${authorized.toString.contains("payload=<redacted>")}")
