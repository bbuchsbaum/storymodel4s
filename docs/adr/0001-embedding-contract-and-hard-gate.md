# ADR 0001 — Embedding contract and the hard-gate invariant

**Status:** Proposed (co-authored on mote topic `embeddings`; Codex adversarial review pending)
**Date:** 2026-08-28
**Deciders:** claude-storymodel4s (retrieval engine), codex-storymodel-release (gates, privacy, adapters)

## Context

Alignment, boundary evidence, interview repetition/clustering, and coreference
candidates all need graded semantic geometry. The M0 alignment suite closes
its `TODO(M5)` items only with real semantic distance. The design record
(§7.1, §49, §67.2) and the mission fix the role of embeddings: **retrieve and
grade; never adjudicate.** Structure (role direction, polarity, embedded
propositions, context, chronology) decides admissibility.

The project owner directs that in-house libraries (grakern, graph4s, gale) be
dogfooded and improved rather than re-implemented.

## Decision

### D1. Modules

| Module | Platform | Owns |
|---|---|---|
| `embed-core` | JVM/JS/Native | contracts (`Embedder[F]`, `EmbedRequest`, `EmbedResult`), `ValidatedVector`, identity/fingerprints, `SemanticView`, free portable baselines (hashed n-gram, TF-IDF), `EmbeddingCache` interface + in-memory impl, `PrivacyPolicy` types, laws |
| `embed-structural` | JVM (until grakern gains a portable emitter) | chart → grakern `GraphSample` adapter by **relation reification**, WL subtree/OA features, structural `FeatureSpace`s |
| `embed-onnx` | JVM | ONNX Runtime execution of open sentence encoders; DJL HuggingFace tokenizer binding with committed differential goldens; optional token-embedding output for late pooling |
| `embed-transport` | JVM | abstract `Transport` (HTTP client, retries, budgets) + per-provider **codecs** (OpenAI, Voyage, Cohere, Gemini, Jina, Ollama/llama.cpp/TEI/vLLM); no provider logic in the transport |
| `embed-bench` | JVM | benchmark harness and metamorphic corpus tooling (see spike spec) |

Cache identity, receipts, and privacy permits reuse `core.Checksum`,
`core.ProviderCall`, `acquire.StageCacheKey`, and `acquire.PromptPackageRef`
patterns. Nothing is reinvented per adapter.

### D2. Contract

```scala
final case class RequestId(value: String)                       // stable, caller-supplied
enum Role { case Query, Document }
enum SemanticView { case Surface, Gloss, Contextual, Segment, Custom(ns, name) }
final case class EmbedRequest(id: RequestId, text: String, role: Role,
                              instruction: Option[String], view: SemanticView,
                              sensitivity: Sensitivity)
enum EmbedFailure { case TooLong(tokens, max); case ProviderError(code, receipt);
                    case PolicyDenied(policyId); case Abstained(reason) }
final case class EmbedResult(id: RequestId, value: Either[EmbedFailure, Estimate[ValidatedVector]],
                             receipt: ProviderCall)
trait Embedder[F[_]]:
  def info: EmbedderInfo
  def embed(batch: Vector[EmbedRequest]): F[Vector[EmbedResult]]   // same length, same ids, same order
```

- **Per-item failure.** One bad item never poisons a batch (law L4).
- **`ValidatedVector`** enforces declared dimension, finiteness, and the
  declared normalization at construction; construction is `Either`.
- **Identity.** `EmbedderInfo.fingerprint = sha256(model artifact, tokenizer
  artifact, truncation policy, role, instruction, output dimension, view,
  normalization, implementation version)`. `FeatureSpaceId` derives from the
  fingerprint; `Role` and `SemanticView` are part of identity, never call
  metadata. Query and Document vectors of the same model live in **paired**
  spaces declared compatible by the `EmbedderInfo`.
- **Capabilities.** `EmbedderInfo(locality: Local|Remote, privacyClass,
  maxTokens, supportsInstructions, matryoshkaDims: Option[Vector[Int]],
  tokenEmbeddings: Boolean)`.
- **Abstention** is `Estimate.Missing(reason)` and propagates to
  `align.Candidates` as `Unranked` (never `Intrusion`).

### D3. Views

Per alignable node: `Surface` (aligned span text), `Gloss` (conservative chart
gloss — structure-derived, paraphrase-stable), `Contextual` (gloss + template
context, or **late-pooled** over the node's `SpanSet` when the runtime exposes
token embeddings), `Segment` (segment summary). Recall units embed as `Query`,
source nodes as `Document`. Each view is its own `FeatureSpace`; the bench
reports per view.

### D4. Structural channel (grakern)

A `PropositionChart` becomes a grakern `GraphSample` by **reifying every
relation as a vertex** keyed by `(source role spelling, normalized role,
embedding kind, direction marker)` between a predicate vertex keyed by
`(lemma | frame, kind, polarity)` and an argument vertex keyed by
`(lemma | frame, kind)`. This makes role swaps, polarity flips, and
embedded-vs-root distinguishable under undirected WL and sidesteps grakern's
no-parallel-edge rule without upstream changes. Output spaces are
`structural.wl.{subtree,oa}` — a distinct family, never labelled semantic.
Because it encodes structure it is deliberately **not** foil-indifferent; it
joins the candidate union and ablations, and the no-model profile may choose
it as its retrieval default. Upstream grakern work (directed graphs, portable
emitter, sparse rows, laws, publication) is tracked in grakern's own tracker.

### D5. The hard-gate invariant (law)

> **L1 (hard gate).** For every recall unit `u` and source candidate `c`, if
> `ContradictionDetector(u, c)` yields a gating contradiction (role reversal,
> polarity conflict, outcome conflict), then `c` is not an admissible state for
> `u` under `GraphHsmm.infer`, for **all** semantic distances, cost weights,
> temperatures, candidate channels, and refinement passes.

Corollaries, also laws:

- **L2.** Reciprocal-rank-fusion output is a rank; it is never a mass, a
  probability, or a cost term. Candidate provenance (which channel proposed
  `c`) is retained on `Candidates` so ablations can drop channels.
- **L3.** Graded semantic cost is evaluated only over admissible candidates
  (lexicographic objective: gates → scope facets → graded cost).
- **L4.** `embed` returns exactly one result per request id, in order; any
  failure is per item.
- **L5.** Reranker / LLM-judge outputs enter as `acquire.AgentProposal` with
  `RawScore`; they are calibrated separately or not at all and never bypass L1.
- **L6.** A `Credence` derived from cosine is raw until a named calibration
  model (fitted leave-story-out on positives, hard within-story negatives,
  other-event neighbours, associations, and null/intrusion cases) is recorded.
  Retrieval calibration and open-world rejection calibration are separate
  models with separate ECE/Brier reports.

### D6. Cache, receipts, privacy

- `EmbeddingCache` key = `(fingerprint, instruction, normalized text)`;
  for `Sensitivity.Sensitive` inputs the text component is an **HMAC-SHA256**
  under a store-local key held outside the artifact (dictionary-attack
  resistant). In-memory portable implementation; file-backed JVM
  implementation behind an `EncryptedStore` interface.
- Every call is receipted as `core.ProviderCall` (provider, model, version,
  input/output checksums, params, cached flag); builds replay from receipts.
- `PrivacyPolicy`: `Sensitive` inputs are `Local`-only by default. `Remote`
  requires a `RemoteCapability` token bound to a `Pseudonymized` result id and
  a recorded `PrivacyPolicyId`; the receipt records both. Sidecars and
  receipts for sensitive inputs carry no raw text or reversible identifiers.

### D7. Defaults by evidence

No default provider or view is chosen by taste. `embed-bench` (spike spec)
selects per tier (portable, local, remote) from measured candidate recall@k by
hierarchy level, post-retrieval structural-gate rejection, calibration,
latency, memory, and privacy mode.

## Consequences

- Closes `TODO(M5)` ×4 once a local provider and calibrated `d_sem` exist;
  `TODO(M3)` (segmenter participant separation) is scheduled alongside.
- Adds three git-SHA pins (grakern → graph4s, gale) to a JVM-only module;
  portable modules keep zero sibling dependencies.
- Tokenization stays JVM-bound until a JS/Native runtime justifies a portable
  interpreter.

## Alternatives rejected

- Pure-Scala BPE/WordPiece in the first slice (conformance surface without a
  portable runtime to serve).
- In-tree hashed-WL vector (owner directs dogfooding grakern).
- Single "HTTP adapter" with embedded provider logic (untestable, unauditable).
- Bundle-level calibration or fused cosine as a probability (violates L2/L6).
