# ADR 0001 — Embedding contract, anchor/fidelity modes, and the non-bypassable gate

**Status:** Proposed, revision 3 (co-authored on mote topic `embeddings`; addresses
Codex's consolidated adversarial review P0-1…P0-5 and P1 hardening).
**Date:** 2026-08-28
**Deciders:** claude-storymodel4s (retrieval engine, alignment), codex-storymodel-release
(gates, privacy, adapters, evaluation independence)

## Context

Alignment, boundary evidence, interview repetition/clustering, and coreference
candidates need graded semantic geometry. The M0 alignment suite closes its
`TODO(M5)` items only with real semantic distance. The design record (§7.1, §9,
§49, §67.2) and the mission fix the role of embeddings: **retrieve and grade;
never adjudicate.** The design record also fixes what a contradiction *means*:
a role-reversed or negated recall of an event is **distortion of the correct
source event** (§9 "Distortion: strong event-identity match but wrong actor,
object, location, causal role, or outcome"), not omission plus intrusion.
Revision 2 of this ADR got that wrong by excluding the source anchor; revision 3
corrects it.

The project owner directs that in-house libraries (grakern, graph4s, gale) be
dogfooded and improved rather than re-implemented.

## Decision

### D1. Modules

| Module | Platform | Owns |
|---|---|---|
| `embed-core` | JVM/JS/Native | contracts (`Embedder[F]`, `EmbedRequest`, `BatchResult`), `EmbeddingSpace`/`GeometryId`, `ValidatedVector`, `SemanticView`, free portable baselines (hashed n-gram, TF-IDF) with fingerprinted corpora/seeds, `EmbeddingCache` interface + in-memory impl, sensitivity-aware digests, laws |
| `embed-grakern` | JVM-only (until grakern's WL compiler leaves `engine-jvm`) | chart → grakern `GraphSample` adapter by **relation reification with explicit source/target incidence**, WL subtree/OA features, `structural.*` spaces; provider id `structural.wl.grakern` |
| `embed-onnx` | JVM | ONNX Runtime execution of open sentence encoders; DJL HuggingFace tokenizer with committed differential goldens; optional token-embedding output for the late-pooled view |
| `embed-transport` | JVM | abstract `Transport` (HTTP, retries, budgets) + per-provider **codecs**; only `AuthorizedRemoteRequest`s reach it |
| `embed-bench` | JVM | benchmark harness, frozen-fixture loader, report generator (see spike spec) |

Cache identity, receipts, and policy decisions reuse `core.Checksum`,
`core.ProviderCall`, `acquire.StageCacheKey`, and `acquire.PromptPackageRef`
patterns where their semantics fit; where they do not (sensitivity), new typed
values are introduced rather than overloading `Checksum` (D6).

### D2. Identity (P0-2)

Two identities, never conflated:

```scala
final case class ProviderFingerprint(value: Checksum)   // static: model artifact, tokenizer artifact,
                                                        // implementation version, runtime
final case class EmbeddingSpace(                        // per recipe; a FeatureSpace is derived from it
  id: GeometryId,                                       // = sha256(provider, role, view, instructionDigest,
  provider: ProviderFingerprint,                        //          dimension, normalization, truncation,
  role: Role, view: SemanticView,                       //          pooling/window/coverage policy for late pooling)
  instruction: Option[InstructionDigest],
  dimension: Dimension, normalization: Normalization,
  truncation: TruncationPolicy,
  latePooling: Option[LatePoolingRecipe])
final case class GeometryPair(query: GeometryId, document: GeometryId)   // validated compatible pair
```

- `Embedder.info: EmbedderInfo(provider, capabilities)` is static; `Embedder.spaces`
  enumerates the recipes it can serve; a request references a `GeometryId`;
  every result returns the `GeometryId` it was produced in.
- Query/Document compatibility is a validated `GeometryPair`, not prose.
- Matryoshka truncations are **derived re-normalized spaces** with their own
  `GeometryId` (parent recorded in the derivation).
- Free baselines fingerprint their corpus (TF-IDF), hash function, seed, and
  dimension (hashed n-gram).

### D3. Contract and result algebra (P0-2)

```scala
opaque type RequestId                                    // smart-constructed, unique within a batch
final case class EmbedRequest(id: RequestId, payload: EmbedPayload, space: GeometryId)
enum EmbedPayload:                                      // (D6) raw vs remote-safe are different types
  case Raw(text: String, sensitivity: Sensitivity)
  case Sanitized(text: PseudonymizedText)
enum ExecutionFailure { case TooLong(tokens, max); case ProviderError(code, attempt);
                        case PolicyDenied(decision: PolicyDecision); case Transport(...) }
final case class EmbedOutcome(id: RequestId, space: GeometryId,
                              value: Either[ExecutionFailure, Estimate[ValidatedVector]])
final case class BatchResult(outcomes: Vector[EmbedOutcome], receipt: AttemptReceipt)
final case class AttemptReceipt(providerCalls: Vector[ProviderCall],   // zero or more
                                cacheDecisions: Vector[CacheDecision],
                                policyDecisions: Vector[PolicyDecision],
                                digest: SensitiveDigest)
trait Embedder[F[_]]:
  def info: EmbedderInfo
  def spaces: Vector[EmbeddingSpace]
  def embed(batch: EmbedBatch): F[BatchResult]     // EmbedBatch validates unique ids and known spaces
```

- **Valid absence** is `Estimate.Missing(reason)` (provider abstained, coverage
  policy); **execution failure** is `ExecutionFailure`. They are never the same
  representation.
- A preflight denial or cache hit produces an `EmbedOutcome` with **no**
  `ProviderCall`; the `AttemptReceipt` records the policy/cache decision instead.
- `ValidatedVector(dimension: Dimension, normalization: Normalization)` enforces
  dimension, finiteness, and declared normalization at construction; distances
  are `ValidatedDistance` (never clamped doubles).
- Law L4: one outcome per request id, in order; failures are per item.

### D4. Views

Per alignable node: `Surface` (aligned span text), `Gloss` (conservative chart
gloss), `Contextual.template` (gloss + template context), `Contextual.latepooled`
(pooled over the node's `SpanSet` from token embeddings; distinct space; identity
includes document, support, tokenizer offsets, model context limit, window/stride,
overlap-merge, pooling rule, uncovered support, Matryoshka dimension; pooling only
over covered token ranges; truncation ⇒ partial `Coverage` or `Missing`), `Segment`
(segment summary). Recall units embed as `Query`, source nodes as `Document`.

### D4a. Dependency boundary

```
embed-core (portable) ──▶ core, features                       no grakern / graph4s types
embed-onnx (JVM)      ──▶ embed-core, onnxruntime, djl-tokenizers
embed-transport (JVM) ──▶ embed-core, acquire, http client
embed-grakern (JVM)   ──▶ embed-core, proposition,
                          grakern-standard@SHA ──▶ grakern-graph4s ──▶ graph4s{core,indexed,data}@SHA
                          grakern-engine-jvm@SHA ──▶ gale-core@SHA
embed-bench (JVM)     ──▶ all of the above
align / interview     ──▶ embed-core only (providers injected)
```

No grakern or graph4s type appears in `embed-core`, narrative APIs,
`EmbeddingSpace` identity, or serialized `StoryModel` contracts. Pins are
immutable SHAs (`-Dstorymodel4s.grakern.build` override for development only);
the transitive SHA set (grakern, graph4s, gale) is recorded in the build receipt;
the release path proves a clean clone builds without sibling checkouts. Pins
become published artifacts when grakern 0.1 / graph4s releases exist.

### D4b. Three structural/semantic estimands (P0-4)

| Distance | Input | Availability | Space family |
|---|---|---|---|
| `d_sem` | dense LM geometry over views | when an `Embedder` is configured | `semantic.*` |
| `d_chart` | `ChartCompatibility` over checked `PropositionChart`s | when both sides have `PropositionEvidence` | — (structural score) |
| `d_wl` | grakern WL features over reified charts | when both sides have `PropositionEvidence` and `embed-grakern` is present | `structural.*` |
| `d_sketch` | `PropositionSketch` compatibility (M0 baseline) | always | — |

- **`PropositionEvidence`** is an explicit channel on both `RecallUnit` and
  `NodeSummary`/`AlignmentSource`: `Option[PropositionChart[Checked]]` with
  provenance; `Missing` when absent. `d_chart`/`d_wl` return `Missing` without
  it; `d_sketch` remains a distinct, labelled fallback and is never called AMR
  or grakern distance.
- Composition is lawful and declared: `LocalCost` takes the available distances
  with per-distance weights and reports which were `Missing`; no distance is
  imputed from another.
- Structural coverage is defined per level: leaf nodes use their own chart;
  segments use the multiset of member charts (documented reducer), never a
  fabricated segment chart.

### D4c. grakern laws required before `embed-grakern` ships

- **G1 (direction).** Relation reification encodes source and target incidence
  explicitly (reified relation vertex `r` with distinct keyed incidences
  `pred→r` and `r→arg`); reversing a relation or swapping fillers across two
  differently-keyed relations yields non-isomorphic reified graphs, and the
  features/kernel differ. A test proves reversal non-isomorphism, not just
  feature inequality.
- **G2 (invariance).** Alpha-renaming and enumeration order leave features and
  kernel values bit-identical.
- **G3 (query overlay).** Prepared source state immutable; no dictionary
  contamination; cost scales with query size plus sparse output; cross values
  equal full recompilation on small oracles (tolerance stated).
- **G4 (kernel algebra, extensional with tolerances).** For the kernel families
  that satisfy them: normalized subtree/OA kernels are PSD (eigen-min ≥ −ε) and
  bounded in [0, 1] with k(a,a)=1±ε; `+` associative/commutative; `weighted`
  homogeneous. Claims are scoped to the family that holds them.

### D5. Anchor admissibility, fidelity modes, and the non-bypassable gate (P0-1)

The alignment state space becomes **(source anchor, fidelity mode)**:

```scala
enum FidelityMode:
  case Faithful
  case Distorted(facets: NonEmptySet[Facet])   // RoleReversal, Polarity, Context, Modality, Outcome, …
```

- A contradiction between recall unit `u` and source node `c` does **not**
  remove `c` as an anchor; it makes `(c, Faithful)` inadmissible and leaves
  `(c, Distorted(facets))` admissible. Context conflict is a facet, never an
  external-state gate. Distortion is therefore measured as anchored recall
  with facet errors (design record §9), not as omission plus intrusion.
- **L1 (non-bypassable mode gate).** Once `(c, mode)` is inadmissible for `u`,
  no semantic distance, weight, temperature, candidate channel, refinement pass,
  or cost model can resurrect it. `GraphHsmm` owns the gate as a typed prepass
  that every `LocalCostModel` passes through; there is no production
  `gating = false`. Ungated behaviour exists only behind an explicit
  `AblationResult` type that cannot be consumed by `RecallSignature`.
- **L2.** Reciprocal-rank fusion output is a rank, never a mass, probability, or
  cost. Every candidate nomination is preserved with channel, rank, raw score,
  space, and receipt.
- **L3.** Graded cost is evaluated only over admissible `(anchor, mode)` pairs
  (lexicographic: mode gate → scope facets → graded cost).
- **L4.** One outcome per request id, in order; failures per item.
- **L5.** Reranker / LLM-judge outputs enter as `acquire.AgentProposal` with
  `RawScore`, calibrated separately or not at all; never bypass L1.
- **L6.** A `Credence` derived from any distance is **raw** until a named
  calibration model fitted against **adjudicated** targets (leave-story-out) is
  recorded; outputs fitted on non-adjudicated material are labelled
  `benchmark-tuned`, never `calibrated`. Retrieval calibration and open-world
  rejection calibration are separate models with separate ECE/Brier.

### D6. Privacy, cache, receipts (P0-3)

- `Pseudonymizer` returns two separately held values: `PseudonymizedText`
  (remote-safe; carries `PolicyId`, `KeyId`, sanitized text, offset map) and
  `ReidentificationKey` (never leaves the local store; rotation by `KeyId`).
- Raw and authorized-remote requests are **different types**:
  `EmbedPayload.Raw` can only be served by `Locality.Local` embedders;
  `AuthorizedRemoteRequest` is constructible only by `RemotePolicy.evaluate`,
  which binds provider, model, purpose, `PolicyId`, expiry, budget, and the
  exact `PseudonymizedText` digest into a `RemoteCapability`.
- `SensitiveDigest`: an HMAC-SHA256 under a store-local key (identified by
  `KeyId`) used for cache identity and receipts of `Sensitive` inputs; it is a
  distinct type from `Checksum` and is what `EmbeddingReceipt` carries.
- Cache key = (`GeometryId`, exact rendered provider material digest); sensitive
  inputs use `SensitiveDigest`. In-memory portable impl; file-backed JVM impl
  behind `EncryptedStore`.
- Errors and logs are sanitized (no echo of payloads); sidecars and receipts for
  sensitive inputs carry no raw text or reversible identifiers.

### D7. Defaults by evidence only, from frozen adjudicated fixtures (P0-5)

Selection and calibration use **frozen, adjudicated** fixtures (source charts,
hierarchy, summaries, acceptable target sets, alternatives, external labels)
built independently of every tested channel, with story-family development /
calibration / untouched test partitions and a natural human-recall panel.
Synthetic or machine-built material is **diagnostic only**. WOG is a regression
fixture. Details in the spike spec.

## Consequences

- Closes `TODO(M5)` ×4 only after W4 produces calibrated defaults from
  adjudicated fixtures; until then defaults remain labelled provisional.
- W1 refactors `align` to (anchor, mode) states; the WOG foil assertions change
  from "excluded" to "anchored as `Distorted(RoleReversal|Polarity)`, never
  `Faithful`".
- Three git-SHA pins in one JVM-only module; portable modules keep zero sibling
  dependencies.

## Alternatives rejected

- Excluding contradicted anchors (rev 2): contradicts NPA §9; converts distortion
  into omission + intrusion.
- Pure-Scala BPE/WordPiece in the first slice; in-tree hashed-WL vector; a
  single HTTP adapter with provider logic; cosine as a probability; overloading
  `Checksum` for sensitive digests; machine-built selection fixtures.
