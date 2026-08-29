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
final case class GeometryPair(                                           // validated compatible pair
  query: GeometryId, document: GeometryId, rule: GeometryPairRule)
```

- `Embedder.info: EmbedderInfo(provider, capabilities)` is static; `Embedder.spaces`
  enumerates the recipes it can serve; a request references a `GeometryId`;
  every result returns the `GeometryId` it was produced in.
- Query/Document compatibility is a validated `GeometryPair`, not prose; its
  closed `GeometryPairRule` may permit named view or instruction asymmetries,
  but never waive any field of the hard compatibility key.
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
final class AttemptReceipt private (providerCalls: Vector[ProviderCall],   // zero or more
                                    embeddingReceipts: Vector[EmbeddingReceipt],
                                    cacheDecisions: Vector[CacheDecision],
                                    policyDecisions: Vector[PolicyDecision],
                                    resultDecisions: Vector[ResultDecision],
                                    itemSensitivity: Vector[(RequestId, Sensitivity)],
                                    digest: ReceiptDigest)                 // (D6) typed kind
trait Embedder[F[_]]:
  def info: EmbedderInfo
  def spaces: Vector[EmbeddingSpace]
  def embed(batch: EmbedBatch): F[BatchResult]     // EmbedBatch validates unique ids and known spaces
```

- **Valid absence** is `Estimate.Missing(reason)` (provider abstained, coverage
  policy); **execution failure** is `ExecutionFailure`. They are never the same
  representation.
- A complete result-id bijection is normalized into request order. A safely
  associated wrong-space item becomes
  `Missing(Malformed(ProviderResult))`, with its expected and actual spaces in a
  typed `ResultDecision`; valid siblings survive. Missing, duplicate, extra, or
  unknown ids fail the whole batch because no vector may be misassociated.
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
- For both `d_chart` and `d_wl`, segment reduction is the declared
  `StructuralReducer.Minimum` over observed pair estimates from member charts
  that pass the chart-match gate (`ContradictionDetector.detect(unit,
  member).isEmpty`). Incompatible charts are not members; absent charts and
  missing or provider-abstained estimates never enter as neutral constants.
  `CostBreakdown.sourceChartCoverage` retains source-chart availability, while
  each per-term `StructuralReductionReceipt` separately records the reducer,
  canonically ordered compatible member ids and estimates, excluded members
  with contradiction facets, source-chart coverage, and
  `observedEstimateCoverage`.

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

#### D5.1 The gated result as a proof over the nominated candidates; wire renderings

`HsmmResult` is a proof of gate admission **over the nominated candidate set**:
`candidateAnchors: Map[RecallUnitId, Vector[SourceNodeRef]]` (canonical order:
ascending reference key, unique; key set = the recall's units) is construction
input; `admissibility` is derived inside `HsmmResult.validated` by running
`ModeGate.assess` over exactly those anchors — never over all view nodes, never
over the anchors that happen to appear in the parts — and every anchored key of
the posterior, flow, costs, or Viterbi path must be nominated *and* admitted in
exactly that mode (key presence, not mass). A cost record's own `mode`/`exclusion`
must agree with the key it sits under. `viewFingerprint` and `recallChecksum` are
derived inside `validated` and are mandatory match fields on the wire
(`AlignWire.matched`); `AdmissibilityEcho` is carried for drift detection only
(`AlignError.GateDrift`), never as authority. Stated residual: nomination
*provenance* (which channel nominated an anchor) is not proven. Records
(`CostBreakdown`, structural-reduction receipts) are rebuilt only through the
`AlignWire` validating factories, which refuse non-finite/negative values,
term–mode contradictions, and receipt-inconsistent terms (an optional term must
equal the receipt's reducer over its observed members, clamped as the cost model
clamps; the observed/missing partition must agree with `missingTerms`; each
receipt's source-chart coverage must equal the breakdown's).

The wire digests are **versioned canonical renderings** with the same rule as
receipt renderings: a change to what a gate or cost reads is a version bump. All
are `ContentAddress.digest` over an align-local tagged, length-separated token
vector (every value preceded by its tag, every list by its length; composite
values joined by U+0001); evidence identity is `proposition.Canonical.checksum`
(align never reaches the codec).

- `view-fingerprint/v1` — nodes sorted by reference key, each: `ref`, `level`,
  `parent`, `discoursePosition`, `support` (sorted span refs: surface unit id,
  start, end), `predicate`, `participants` (in order: role, label, aliases sorted
  with count), `context`, `polarity`, `modality`, `locations` (in order),
  `lemmas` (sorted), `outcome`, `cause`, `importance` (the full `Estimate`
  variant: observed value + credence raw score / calibrated probability /
  model, or the missing reason), `evidence` (chart checksum or empty); then per
  `RelationLayer` in enum order the sorted `(from, to, weight)` entries with
  weight > 0 (IEEE-754 rendered); then `worldOrder` (sorted) and `textLength`.
- `recall-checksum/v1` — the transcript's canonical checksum; then per unit in
  recall order the **full** unit content anything reads: `id`, `ordinal`,
  `span` (sorted span refs), `text`, `function`, expressed uncertainty (variant
  and cue spans), the whole proposition sketch (`predicate`, `participants` with
  role / entity / label / specified / head / determiner / number / modifiers /
  aliases, `polarity`, `modality`, `locations`, `times`, `sensoryTerms`,
  `lemmas` sorted, `outcome`, `cause`), `grounding`, `evidence` (chart
  checksum); then the explicit `temporal` and `causal` relations (sorted). Same
  boundaries with different content therefore fail `matched`.
- `admissibility-echo/v1` — entry count, then per `(unit, anchor)` sorted:
  contradictions, faithful flag, facets sorted.
- hsmm/v1: JSON object field list owned by codec (HsmmResultCodec); the field list is added below by the codec candidate.

### D6. Privacy, cache, receipts (P0-3)

- `Pseudonymizer` returns two separately held values: `PseudonymizedText`
  (remote-safe; carries `PolicyId`, `KeyId`, sanitized text, offset map) and
  `ReidentificationKey` (never leaves the local store; rotation by `KeyId`).
  `PseudonymizedText` has no public constructor or copy path; its checked factory
  requires a complete, ordered, non-overlapping, bounded, code-point-safe map
  whose replacements do not contain their source-span text and whose unmapped
  gaps remain identical. This proves an explained pseudonymization transformation,
  not the absence of all residual sensitive surface: that trust rests with the
  caller of `checked`, normally the `Pseudonymizer`.
- Raw and authorized-remote requests are **different types**:
  `EmbedPayload.Raw` can only be served by `Locality.Local` embedders;
  `AuthorizedRemoteRequest` is constructible only by `RemotePolicy.evaluate`,
  which binds provider, model, purpose, `PolicyId`, expiry, budget, and the
  exact `PseudonymizedText` keyed digest into a `RemoteCapability`. Evaluation derives
  that value from the request's `EmbedPayload.Sanitized`; its signature has no
  second payload argument that could authorize material different from the
  request, and a raw request is denied.
- A future codec cannot reconstruct the omitted source text and therefore cannot
  rerun `PseudonymizedText.checked`; decoding will require a narrow
  `private[embed]` trusted path that preserves the explicit trust boundary.
- `SensitiveDigest`: an HMAC-SHA256 under a store-local key (identified by
  `KeyId`) used for cache identity and receipts of non-public inputs; it is a
  distinct type from `Checksum`.
- **Receipt identity is a sealed sum, `ReceiptDigest`**, with a typed
  `DigestKind`:
  - `Plain(checksum)` — admissible **only** for `Sensitivity.Public`
    (`ReceiptDigest.plainAdmissible`); rendered `plain:<hex>`.
  - `Keyed(digest: SensitiveDigest)` — every non-public input; the `KeyId` is
    derived from the digest (one key id, never a second field); rendered
    `hmac:<keyId>:<hex>`.
  - `Withheld(missingKey, checksum)` — a material-free identity minted only by
    `AttemptReceipt` when the store key is absent and every non-public item in
    the batch carries a recorded `PolicyDecision.KeyUnavailable`; rendered
    `withheld:<keyId>:<hex>` over the decision vectors only.
  All constructors are `private[embed]`; the only public paths are the checked
  factories `ReceiptDigest.of(sensitivity, material, keys)` (Plain iff Public,
  else keyed, else `Left(EmbedError.NoKey(keyId))`), `ReceiptDigest.keyed`, and
  `ReceiptDigest.keyedUnder(keyId, …)`. `CacheKey` and `ItemDigest` are likewise
  `private[embed]`-constructed. A probe suite outside the package
  (`storymodel4s.embedprobe`) asserts these do not type-check.
- **No plain fallback, anywhere.** A missing key is `EmbedError.NoKey(keyId)`
  at the factory and, on every provider-call surface (baselines, cache, remote
  evaluation), a per-item `ExecutionFailure.PolicyDenied(PolicyDecision.KeyUnavailable(id, keyId))`
  with the decision recorded in `policyDecisions` (`CacheDecision.Denied` on the
  cache surface). `PseudonymizedText.checked(…, keys)` takes the key provider
  and mints the payload's `Keyed` digest under its `KeyId` at construction
  (`InvariantViolation(PseudonymizedText.KeyPath, …)` when unavailable), so no
  `PseudonymizedText` — and hence no `RemoteCapability` — exists without a keyed
  identity. A batch containing any non-public item is atomic with respect to
  receipt keys: if the required key is unavailable, no item is computed,
  delegated, or cached; every outcome is denied with `KeyUnavailable`, and the
  `Withheld` attempt receipt is call-free.
- **Canonical rendering** (`ReceiptRendering`): one escaped, versioned,
  `|`-delimited rendering is the sole HMAC/checksum input on every surface.
  Escapes: `\` → `\\`, `|` → `\|`, newline → `\n`, NUL → `\0`; renderings
  never contain literal NUL bytes. Version tags: `material/v1`
  (`role=…|instruction=…|text=…`, produced by `Material.render` for cache keys
  and provider input), `pseudo/v1` (`policy=…|key=…|text=…` for
  `PseudonymizedText.digest`/`RemoteCapability.payloadDigest`), `items/v1`
  (`item|id|sensitivity|<digest.render>` per item), `outputs/v1`, and
  `attempt/v1` (legacy display-based fields) and `attempt/v2`. Version 2 is the
  receipt identity format: indexed full `ProviderCall` and `EmbeddingReceipt`
  fields, sorted call parameters, typed cache/policy/result/error fields with
  full capability fingerprints and digests, and item sensitivities. Tagged
  options and individually escaped fields make every equality field
  unambiguous; changing any such field changes the attempt digest. The
  golden vector `SensitiveDigest.Golden` hashes the **production** `material/v1`
  rendering and is asserted on JVM, JS and Native.
- **ProviderCall checksums for non-public material are hash-of-keyed-digest,
  never material**: `inputChecksum = sha256(items/v1 rendering of the
  ItemDigests)`, `outputChecksum = sha256(outputs digest render)` where the
  outputs digest is keyed whenever any item is keyed. `EmbeddingReceipt` carries
  the typed `kind` (also in `ProviderCall.params["digest-kind"]` /
  `"digest-key-id"`) and refuses keyed items with plain outputs.
- `AttemptReceipt.digest` is `Keyed` under the policy key when any item is
  non-public, `Plain` only for all-public batches; its HMAC input is the
  `attempt/v2` rendering of the constructed receipt over full validated
  embedding provenance and **all** decision vectors including
  `resultDecisions`, so amending any equality field changes the digest. Provider
  calls must exactly equal the calls of key-consistent `EmbeddingReceipt`s.
- Cache key = (`GeometryId`, `ReceiptDigest` of the exact `material/v1`
  rendering). In-memory portable impl; file-backed JVM impl behind
  `EncryptedStore`.
- Errors and logs are sanitized (no echo of payloads); sidecars and receipts for
  sensitive inputs carry no raw text, key bytes, or reversible identifiers, and
  the same material under two store keys yields different identities on every
  surface (cache, pseudonymized payload, capability, attempt, provider call).

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
