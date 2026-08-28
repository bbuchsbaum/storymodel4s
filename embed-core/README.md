# embed-core

Portable embedding contract for storymodel4s (ADR 0001 rev 3). Embeddings
**retrieve and grade; they never adjudicate** — admissibility is decided by the
`align` mode gate, not by any distance defined here.

## What this module owns

- **Identity (D2).** `ProviderFingerprint` (static: model/tokenizer/implementation/
  runtime) is never conflated with `EmbeddingSpace` (one recipe: role, view,
  instruction digest, dimension, normalization, truncation, late-pooling recipe).
  A space's `GeometryId` is content-addressed from the recipe; Matryoshka
  truncations are derived spaces with a recorded parent; `GeometryPair.validated`
  is the only way to declare query/document vectors comparable.
- **Contract (D3).** `EmbedBatch.validated` (unique smart-constructed `RequestId`s,
  known spaces) → `Embedder.embed` → `BatchResult` with exactly one `EmbedOutcome`
  per request in order (law L4; `Embedder.conforming` enforces it), each either an
  `ExecutionFailure` or an `Estimate[ValidatedVector]`. Valid absence is
  `Estimate.Missing`; execution failure is a different type. `AttemptReceipt`
  carries zero or more `ProviderCall`s plus every cache and policy decision.
- **Vectors.** `ValidatedVector.of` checks dimension, finiteness and the declared
  normalization; `Distances.cosine/euclidean` return `ValidatedDistance`
  (finite, non-negative) or a typed error — never a clamped double.
- **Views (D4).** `SemanticView` with `ContextualTemplate` and
  `ContextualLatePooled` as distinct cases; late pooling carries a validated
  `LatePoolingRecipe` in identity.
- **Free baselines.** `HashedNgramEmbedder` (char 3–5-grams + word uni/bigrams,
  FNV-1a sign hashing; fingerprint = hash function, seed, dimension) and
  `TfIdfEmbedder.fit(corpus)` (fingerprint includes the corpus). Both are
  deterministic, local, and run on JVM/JS/Native.
- **Cache (D6).** `CacheKey(GeometryId, MaterialDigest)`; `Sensitive` inputs use a
  keyed `SensitiveDigest` (portable HMAC-SHA256, RFC 4231 vectors tested) under a
  `KeyId` from a `SensitiveKeyProvider`; `CachingEmbedder` records `Hit/Miss/
  Bypassed` decisions in the receipt.
- **Privacy (D6).** `EmbedPayload.Raw` vs `EmbedPayload.Sanitized(PseudonymizedText)`
  are different constructors; `Embedder.preflight` refuses raw sensitive text for
  remote providers; `AuthorizedRemoteRequest` is constructible only via
  `RemotePolicy.evaluate`, which binds a `RemoteCapability` (provider, model,
  purpose, policy, expiry, budget, payload digest).

## What it does not own

No HTTP, ONNX, tokenizers, grakern, or graph4s — those are JVM-only adapters
(`embed-onnx`, `embed-transport`, `embed-grakern`). No calibration: a `Credence`
derived from a distance is raw until a named model fitted on adjudicated targets
is recorded (law L6, `core.Credence.from`).

`interview` should adopt `PseudonymizedText` as its remote-safe half and keep the
re-identification key separately.
