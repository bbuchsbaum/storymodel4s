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
| `embed-grakern` | JVM-only (until grakern's WL compiler leaves `engine-jvm`) | chart → grakern `GraphSample` adapter by **relation reification**, WL subtree/OA features, `structural.*` `FeatureSpace`s; provider id `structural.wl.grakern` |
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

### D4a. Dependency boundary (Codex condition 1–3, 6)

```
embed-core (portable) ──depends──▶ core, features                      no grakern / graph4s types
embed-onnx (JVM)      ──▶ embed-core, onnxruntime, djl-tokenizers
embed-transport (JVM) ──▶ embed-core, acquire, http client
embed-grakern (JVM)   ──▶ embed-core, proposition,
                          grakern-standard@SHA ──▶ grakern-graph4s ──▶ graph4s{core,indexed,data}@SHA
                          grakern-engine-jvm@SHA ──▶ gale-core@SHA
embed-bench (JVM)     ──▶ all of the above
align / interview     ──▶ embed-core only (providers injected)
```

- No grakern or graph4s type appears in `embed-core`, in narrative APIs, in
  `FeatureSpace` identity, or in serialized `StoryModel` contracts; the
  adapter's provider ID is `structural.wl.grakern` and is **labelled JVM-only**
  until grakern's WL compiler leaves `engine-jvm`. There is no hidden in-tree
  replacement under the same provider ID.
- Pins are immutable SHAs (`-Dstorymodel4s.grakern.build` override for
  development only); the transitive SHA set (grakern, graph4s, gale) is
  recorded in the build receipt and in `packages.toml`. The release path must
  prove a clean clone builds without sibling checkouts. Pins become published
  artifacts when grakern 0.1 / graph4s releases exist.
- Preferred upstream direction: a topology-neutral labelled-neighbourhood /
  graph-sample protocol and the WL feature compiler in portable grakern
  modules, with graph4s as one adapter.

### D4b. Two estimands: `d_prop` vs `d_sem` (Codex condition 7)

| Channel | Spaces | Feeds | Foil behaviour |
|---|---|---|---|
| Structural (grakern WL over reified charts) | `structural.*` | `LocalCost.d_prop` (with `ChartCompatibility`) and candidate nomination | deliberately foil-**sensitive** |
| Dense language-model geometry | `semantic.*` | `LocalCost.d_sem` and candidate nomination | expected foil-**indifferent**; gates handle foils |

Both may nominate candidates; RRF produces a rank only, and `Candidates`
retains per-channel provenance. The feature-use ledger records which channels
nominated each candidate and which spaces entered any boundary induction.

### D4c. grakern laws required before `embed-grakern` ships (Codex condition 4–5)

- **G1 (direction).** Reversing a directed proposition relation (swapping
  ARG0/ARG1 fillers, or inverting an edge) changes the structural feature
  vector and the kernel value; orientation is relative to the incident
  endpoint. (Under today's undirected `WLTrace` this holds only through
  relation reification; the upstream bead makes it hold natively.)
- **G2 (invariance).** Alpha-renaming of concept ids and any edge enumeration
  order leave features and kernel values bit-identical.
- **G3 (query overlay).** Prepared source state is immutable; query-only
  colours never enter the source dictionary; batch cost scales with query
  size plus sparse output, not with rebuilding the source Gram; cross values
  equal full recompilation on small oracles.
- **G4 (kernel).** Normalized kernels are PSD and bounded in [0, 1];
  `+` is associative and `weighted` is homogeneous (Discipline suites in
  grakern's currently empty `laws/`).

### D4d. Late-pooled view semantics (Codex condition 8)

`semantic.contextual.latepooled` is a distinct `FeatureSpace`, never a silent
fallback to the template view. Its receipt records tokenizer offsets, model
context limit, window/stride policy, overlap-merge rule, pooling rule,
uncovered support, and Matryoshka dimension. Pooling over a discontinuous
`SpanSet` uses only covered token ranges; truncation yields partial
`Coverage` or `Missing`, never fabricated completeness. Use in hierarchy
induction goes through the feature-use ledger (anti-circularity).

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
