# ADR 0005 — Compile proposal evidence into a narrative model

**Status:** Accepted architecture (chief ruling 2026-08-29); first slice under review

**Date:** 2026-08-29

**Decider:** `claude-storymodel4s`

**Author:** `codex-storymodel-new-engineer`

## Context

The repository has working contracts on both sides of one missing transformation.

- `core` turns a `StorySource` into a checked `SurfaceAtlas`.
- `proposition` represents checked local semantic charts.
- `acquire` represents bounded tasks, provider proposals, critic findings, deterministic
  `ResolutionState`s, stage plans, and cache identities.
- `document` composes local charts into a `MentionGraph`, represents exact-coreference partitions,
  and records projections from chart nodes to canonical narrative nodes.
- `story` defines `NarrativeGraph`, `NarrativeHierarchy`, `StoryModel`, structural validation, and
  the validated `AlignmentSource` consumed by `align`.

No production code turns the first group into the second. The hand-authored fixtures construct
`NarrativeGraph` and `NarrativeHierarchy` directly. As a result, a researcher can analyze an
existing model, but cannot build that model unattended from source text.

The missing operation is sometimes abbreviated as:

```text
SurfaceAtlas -> NarrativeGraph + NarrativeHierarchy
```

That abbreviation is not a valid API contract. A `SurfaceAtlas` contains exact surface units and
spans. It does not determine predicates, entity or event identity, contexts, relations, or segment
membership. A function with only an atlas as input would have to hide a provider call, fabricate
semantics, or return an uninformative empty graph. All three choices violate the design contract.

The actual transformation must combine the atlas with checked local charts, typed provider
proposals, independent critic findings, calibrated evidence where required, and an explicit
acceptance policy. It must preserve alternatives and unresolved claims even when it cannot produce
a valid story model.

## Decision

### 1. `document` owns the pure compiler boundary

The deterministic transformation belongs in `document`.

`document` is already the only portable module that depends on both `acquire` and `story`. It also
owns the two structures immediately before narrative construction: `MentionGraph` and
`ProjectionIndex`. This placement keeps the dependency direction:

```text
core + proposition + acquire + story
                  |
                  v
              document
```

`acquire` remains a generic proposal and resolution protocol. It does not depend on `story` and
does not construct narrative nodes. This matters beyond build hygiene: `embed-core` also depends on
`acquire`, so adding `story` to `acquire` would widen unrelated portable code without providing the
missing compiler.

Provider execution, network clients, retry loops, and persistent stage caches remain outside this
portable compiler. A later JVM `build` orchestrator may run providers and call the compiler, but it
does not own the scientific transformation.

### 2. The compiler accepts evidence, not raw text alone

The public input must make each source of information visible. The exact names remain an
implementation decision, but the contract has this shape:

```scala
final case class NarrativeCompilerInput(
  source: StorySource,
  atlas: SurfaceAtlas,
  localCharts: Vector[(SurfaceUnitId, PropositionEvidence)],
  situations: Vector[SituationAttempt],
  contexts: Vector[ContextAssignmentAttempt],
  summary: StorySummaryAttempt,
  memberships: Vector[SegmentMembershipAttempt],
  causal: Vector[CausalAttempt],
  policy: AcceptancePolicy,
  receipt: BuildReceipt
)
```

The input validates at least these facts before compilation starts:

1. the atlas belongs to `source`;
2. every chart key names a unit in the atlas;
3. every evidence span belongs to the source and, when unit-addressed, stays inside that declared
   atlas unit;
4. every proposal references only declared surface, chart, node, and upstream-claim identifiers;
5. every situation has an explicit root-context and primary-membership attempt, including when the
   provider abstained or the resolver could not accept it;
6. the receipt identifies the provider outputs, prompt packages, software, and configuration used
   to create the input.

A JVM orchestrator may offer a convenience operation from raw source text. That operation first
creates this explicit input; it does not bypass it.

### 3. `NarrativeCompilation` is a durable scientific artifact

The compiler returns a `NarrativeCompilation`, not a naked
`(NarrativeGraph, NarrativeHierarchy)` tuple and not only a `StoryModel`.

The artifact retains:

- the `MentionGraph` used for document composition;
- entity and situation mention tables and exact-coreference partitions;
- projections from chart nodes to canonical narrative nodes;
- the candidate and resolution record for every attempted claim;
- the canonical input evidence ledger and base provenance needed to interpret retained references;
- accepted graph, context, relation, segment, and containment values that could be assembled;
- all `Alternatives`, `Unresolved`, and `Rejected` outcomes;
- a derivation receipt that identifies every sparse candidate the compiler evaluated, which
  candidates it emitted, the complete `ClaimMeta` for those emissions, and why it did not emit the
  others;
- structural and scientific gate findings;
- a `StoryModel[Draft]` assembled from the accepted values, including when the draft is partial;
- the corresponding `ValidationOutcome`;
- the build receipt and deterministic fingerprints of the inputs and outputs.

`NarrativeCompilation` and `StoryModel[Validated]` answer different questions.

- The compilation records what the unattended process proposed, accepted, rejected, or could not
  decide.
- The validated model proves that one assembled narrative artifact satisfies the structural laws
  required by downstream scientific APIs.

For that reason, the compilation remains useful when validation fails. A successful build contains
both artifacts. A partial build contains the compilation, its draft and validation report, and no
forged validated model. Invalid compiler input returns a typed input error before a compilation is
created; valid input always produces the durable compilation record.

The implementation must use smart construction rather than a public case-class constructor if its
fields have cross-field invariants.

### 4. Compilation is staged and deterministic

The compiler runs these logical stages in order:

1. validate the source, atlas, local charts, references, and receipts;
2. form the `MentionGraph` as a disjoint union of checked local charts;
3. resolve entity and situation mentions and exact identity;
4. resolve the mandatory root context and context assignment before emitting a situation;
5. create canonical entities and situations with deterministic IDs and evidence;
6. resolve participant, entity, temporal, causal, goal, state-change, and reference relations;
7. resolve boundaries, segments, summaries, and containment;
8. derive the discourse trajectory only when its participant and temporal inputs are supported;
   otherwise retain typed trajectory gaps and refuse promotion;
9. assemble `StoryModel[Draft]` from the accepted components, leaving unresolved components absent;
10. call `StoryValidator.validate` and retain its full `ValidationOutcome`.

Stages consume only declared upstream artifacts. An agent cannot choose the stage order, mark its
own proposal accepted, construct `Resolved[A]`, or write directly into a story model.

Ordering is part of the contract. Every map-to-vector transition uses canonical identifier order,
provider-call ledgers are canonicalized once at input construction, and every content address uses
a versioned rendering. Reordering equivalent provider responses or base provenance calls therefore
leaves compilation structure, fingerprints, and accepted IDs unchanged.

### 5. Missing and disputed claims remain explicit

The compiler applies `Resolver.resolve` under the declared family policy. It does not convert an
abstention into a negative claim, select the first alternative, or lower a threshold so that a
draft validates.

An accepted explicit narrative claim carries exact span evidence. An accepted inferred claim cites
accepted upstream claims. Raw provider scores remain raw unless a named calibration model supplies
the `Probability` required by the policy. Provider proposals do not carry `EpistemicStatus`: the
compiler assigns a restricted status by claim family, so provider output cannot call itself human
adjudication, structural derivation, or root-world truth.

The compiler also may not invent an epistemic license merely because a proposal was accepted.
Provider-proposed causal, context-assignment, and primary-membership claims without a typed
linguistic licensing basis are `Hypothesized`, not `LinguisticallyEntailed`. Calibration states
confidence; it does not establish what licenses the claim. A richer typed licensing basis is a
separate design and is not inferred in this slice.

Some unresolved claims prevent only a field or edge from being emitted. Others prevent a usable
draft or its promotion. For example, an unresolved causal relation can remain absent with its
resolution record retained, while an unresolved context assignment, primary membership, situation,
summary, or required trajectory input prevents a validated model. The compiler reports that
distinction through typed stage outcomes and an augmented `ValidationReport`; it does not treat
every unresolved claim as a process crash.

### 6. Non-derivable relations are absent, never weak defaults

The compiler emits a narrative edge only from an accepted relation claim. If it cannot determine a
relation, it emits no edge. It never substitutes a default weight, raw score, empty label, root
context, identity merge, or containment link.

The distinction must remain machine-readable. `NarrativeCompilation` therefore carries a
`DerivationReceipt` whose exact public names may vary but whose information does not:

```scala
final class DerivationReceipt private (
  candidateSet: Checksum,
  attempts: Vector[DerivationAttempt],
  emittedClaims: Map[ClaimId, ClaimMeta],
  gaps: Vector[DerivationGap]
)

final case class DerivationGap(
  stage: StageId,
  family: ClaimFamily,
  target: NarrativeCandidateAddress,
  outcome: NonAcceptedResolution,
  upstreamClaims: Set[ClaimId],
  evidence: Vector[EvidenceRef]
)
```

The receipt covers the sparse candidate set actually nominated for a stage. It does not materialize
dense all-pairs relation candidates. A pair outside that set is **not evaluated**, not a negative or
zero-weight relation. A nominated pair with an `Alternatives`, `Unresolved`, or `Rejected` outcome
is **evaluated but not emitted**. Downstream code can condition only on recorded support or refuse
the operation; it cannot average a manufactured value into an estimand.

The compilation retains the canonical input `Evidence` ledger and base `Provenance` alongside this
receipt. Smart construction enforces closure: every emitted attempt resolves to a retained
`ClaimMeta`; every gap `ById` reference resolves to retained evidence; and every upstream claim
named by a gap or retained evidence resolves in the emitted-claim ledger. Hashing a missing record
would prove identity but not auditability, so a checksum is never accepted as a substitute for the
record itself.

The candidate-set checksum, attempts, full emitted `ClaimMeta` ledger, and gaps all participate in
the compilation fingerprint. Two compilations with the same emitted graph but different missing
support, epistemic status, credence, evidence, or provenance are not the same artifact.

### 7. Review for absence-erasing derivations

**Absence-erasing derivation** is a required ADR and code-review category. It means an operation
turns unavailable, non-nominated, alternative, unresolved, or rejected evidence into an ordinary
value that downstream code cannot distinguish from a measurement.

Reviewers must trace every fallback such as `getOrElse`, empty collection, zero, midpoint, default
weight, or default enum through the compiler. Each use must be one of:

1. a genuine algebraic identity whose support is still explicit;
2. a display-only rendering that cannot enter a scientific result; or
3. a defect that must become a typed gap or refusal.

The compiler candidate must include a mutation that replaces one non-accepted relation with a
default emitted edge. The acceptance suite must kill that mutation and prove the derivation receipt
still names the missing relation.

### 8. Only validated models enter alignment

`AlignmentSource.apply` continues to accept only `StoryModel[ModelStatus.Validated]` (or the
separately adjudicated status). The compiler does not add an escape hatch from a partial
compilation or draft model into `align`.

This gives the end-to-end path a clear success boundary:

```text
raw source text
  -> SurfaceAtlas
  -> provider outputs and checked local charts
  -> NarrativeCompilation
  -> StoryModel[Validated]
  -> AlignmentSource

raw recall transcript
  -> RecallGraph[Checked]

AlignmentSource + RecallGraph[Checked]
  -> RecallSignature
```

If source compilation remains partial, the run still publishes its compilation artifact and a
typed reason that alignment did not run.

## Required laws and acceptance tests

The first implementation must make the boundary executable before adding broad provider support.

1. **No atlas-only semantics.** The compiler cannot construct a nonempty narrative graph from an
   atlas without semantic proposal evidence.
2. **Proposal-only agents.** Replacing a proposal disposition or raw score cannot directly turn an
   agent value into an accepted story claim; acceptance always passes through the resolver and
   policy.
3. **Evidence preservation.** Every accepted explicit claim recovers its cited source spans.
4. **Alternative preservation.** A resolver `Alternatives` result remains present in the
   compilation and does not appear as an accepted graph value.
5. **Unresolved root blocks promotion.** Missing or disputed root-context evidence produces no
   `StoryModel[Validated]`.
6. **No default relation.** A nominated but non-accepted relation produces no graph edge, and its
   typed gap remains in the derivation receipt.
7. **Support identity.** Two compilations with the same emitted graph but different candidate sets
   or derivation gaps have different fingerprints.
8. **Reference integrity.** No accepted relation, projection, or containment edge references a
   node absent from its accepted upstream artifact.
9. **Hierarchy follows graph identity.** Segment membership never creates or merges situations.
10. **Permutation invariance.** Permuting proposal, finding, chart, or independent stage-result
   order leaves canonical outputs and fingerprints unchanged.
11. **Content replay.** Replaying the same provider outputs, source, prompt packages, policy,
   software, configuration, and exact `BuildReceipt` produces structurally equal values and the
   same content fingerprint. Canonical byte replay is deferred until `codec` owns a versioned wire
   format.
12. **Durable audit closure.** After compiler input disposal, every `ById` evidence reference and
    every evidence-upstream claim remains resolvable within `NarrativeCompilation`.
13. **No fabricated epistemic license.** An accepted provider causal proposal without a typed
    linguistic basis is retained as `Hypothesized`, never `LinguisticallyEntailed` by default.
14. **No fixture backdoor.** The vertical acceptance path does not import the hand-authored WOG
    graph or hierarchy into production compilation.

The first end-to-end acceptance fixture supplies raw source text and a raw recall transcript. It
must reach `RecallSignature` without a hand-authored source model. The WOG narrative fixture checks
plain-language prohibitions and acceptable alternatives; it does not become provider input or
machine-generated semantic gold.

## First implementation slice

The first slice is deliberately smaller than the full M1 provider stack. It adds:

1. the typed compiler input and durable compilation result in `document`;
2. deterministic assembly from pre-recorded, schema-valid provider proposal artifacts;
3. one source sentence whose accepted evidence is sufficient to construct and validate a source
   model, generated by an honestly identified deterministic fixture provider;
4. one recall transcript that reaches `RecallSignature` through that model;
5. one unresolved variant that still emits a complete compilation record but cannot reach
   `AlignmentSource`;
6. one non-accepted relation variant that emits no default edge and records the typed gap;
7. durable evidence/claim-ledger closure after input disposal;
8. conservative causal epistemic status; and
9. content-fingerprint replay, provider-order, and absence-erasing mutation tests.

This slice proves the transformation and its failure semantics. Live provider adapters, CLI
commands, long-document scheduling, and calibration quality remain later slices with separate
evidence. The slice deliberately refuses to promote a multi-situation compilation: its input does
not yet represent participant and temporal proposals, so calling `DiscourseTrajectory.derive`
would turn missing participant support into zero turnover and missing temporal support into an
ordinary resolved value. A single-situation story has no adjacent trajectory step and can lawfully
exercise the full vertical path; multi-situation support is a subsequent compiler extension, not a
default-filled shortcut in this one.

## Rejected alternatives

### Make `acquire` depend on `story`

Rejected. It couples a generic proposal protocol to one consumer ontology, widens unrelated
dependencies, and still does not identify who composes mentions and applies accepted proposals.

### Put the transformation only in a JVM `build` module

Rejected. Provider execution is JVM work, but deterministic scientific compilation is portable and
must be testable without network, credentials, or a provider runtime.

### Return only `NarrativeGraph` and `NarrativeHierarchy`

Rejected. The tuple discards proposal provenance, alternatives, unresolved outcomes, and validation
evidence. It would make a failed or partial unattended run scientifically unauditable.

### Fail the whole build on the first unresolved claim

Rejected. Unresolved and alternative outcomes are legitimate artifacts. Only failures of a named
gate should block the corresponding downstream operation.

### Let a provider emit `StoryModel` directly

Rejected. It gives the provider control of identity, acceptance, context promotion, and hierarchy,
contrary to the proposal-only rule.

### Use the hand-authored fixture as the compiler

Rejected. Fixtures are acceptance courts. Importing their graph or hierarchy into the production
path would prove only that the expected answer can be copied.

## Consequences

- `document` gains a larger responsibility: it becomes the explicit bridge from local semantic
  evidence to the story ontology. Its public types must therefore receive the same construction
  boundary and cross-platform scrutiny as `story`.
- A build can succeed operationally while producing no validated model. Reports and CLI exit
  semantics must distinguish provider execution, compilation completeness, structural validation,
  and alignment execution.
- The derivation receipt becomes part of every downstream support decision. Consumers may not infer
  that an absent edge was tested and found false, or replace a recorded gap with a neutral value.
- Codecs must preserve the compilation artifact, including non-accepted outcomes, before exact
  replay can be claimed.
- The vertical slice requires a small recorded provider-output fixture. That fixture is silver
  acquisition evidence with receipts, not hand-authored AMR and not narrative gold.
- Film support can later supply a different source atlas and evidence anchors to the same compiler
  pattern, but ADR 0005 does not widen the current text evidence contract.

## Status of this decision

The chief assigned the severed compiler link as the current critical path and explicitly froze the
type shape in direct ruling `msg-01M179ADY7TH5TDYGJ0EDEZK15` on 2026-08-29: `document` owns the
compiler, `NarrativeCompilation` is durable, every valid input emits a typed partial through
`StoryModel[Draft]` plus `ValidationOutcome`, and only the optional validated model enters
alignment. The same ruling requires non-derivable relations to stay absent with a durable
derivation receipt and makes absence-erasing derivation a review category. Those architectural
decisions are frozen. Public names and the smallest executable slice remain subject to
implementation review and the end-to-end acceptance court.
