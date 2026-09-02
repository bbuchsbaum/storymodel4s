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
evidence. Multi-situation compilation is supported since the 2026-09-02 amendment below, under one
rule: a trajectory step between adjacent emitted situations is derived only when both endpoints
carry an accepted, complete participant coverage and the pair carries an accepted temporal
relation (`Unclear` included). Otherwise the trajectory stays empty and each blocked pair is a
typed `MissingUpstream` gap. `DiscourseTrajectory.derive` is never called on absent inputs, so
"no participants evaluated" can never read as zero turnover and "no temporal claim" can never read
as an ordinary unresolved transition. A single-situation story has no adjacent pair and exercises
the vertical path exactly as before.

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

## Amendments

### 2026-09-01: chart-driven proposal provider (solo plan phase 1.3)

The first slice's only provider was the one-sentence lexical fixture in
`NarrativeCompilerVerticalSuite`. This amendment adds `ChartProposalProvider` in `document`
(`document/propose.scala`): a deterministic, receipted provider that reads one checked
`PropositionEvidence` per sentence and emits, for every sentence of a story, the situation,
context-assignment, and segment-membership attempts plus one title summary attempt, with typed
abstention wherever a chart has no admissible root and a per-sentence coverage ledger. It does not
touch `NarrativeCompiler`; phase 1.4 does. Decisions taken, with the alternative rejected:

1. **Placement in `document`, not `acquire` or a JVM module.** The proposal ADTs live in
   `document`, and `acquire` cannot name them without depending on `story`. The cost is that the
   compiler's `private[document]` factories (`DerivationReceipt.of`, `NarrativeCompilation.of`)
   are in scope for the provider. A static court (`ChartProposalStaticCourtSuite`, JVM-only
   because it reads source files) refuses those names and every other `private[document]` member
   in the provider source, with a positive control that the scan finds a planted name. Rejected:
   a `provider` sub-package with its own privacy boundary, which would have moved the ADTs out of
   the compiler file for no gain in this slice.
2. **Root = the chart focus, and nothing else.** A situation is proposed only when the focus is a
   `Predicate`-kind concept held by no embedding, which is exactly what the compiler accepts at
   emission. Rejected: falling back to another predicate of the chart when the focus is
   inadmissible. That would propose a situation the chart did not put at its root, which is
   fabrication, not extraction.
3. **Abstention is an emitted attempt, not silence.** An inadmissible root yields one
   `AgentProposal.abstained` attempt per family at the anchor (the focus, else the lowest concept
   id), so the input binding checks hold and the compiler records `Unresolved(NoProposal)` gaps
   that a reader can count. An empty chart or a sentence with no chart yields a coverage row and
   no attempt; the coverage denominator is `atlas.sentences.size`. Rejected: omitting the sentence,
   which would make "not proposed" indistinguishable from "not run" (design contract rule 7).
4. **Kind: `State` only for the closed set of AMR `-91` reification frames, else `Event`.** The
   set is enumerated in `ChartProposalProvider.StateFrames` and printed into the rules text.
   Lexical statives without such a frame (`become`, `be`) are `Event` in this version; that is a
   recorded limitation, not a classification. Rejected: a lexical stative list, which no chart
   field licenses.
5. **Description and support come from the chart, never the sentence text.** The description is
   `Gloss.predicate` at the root; support is the union of alignment spans of every alignment that
   names a non-embedded concept, recorded on the receipt as `span-source=chart-alignments`. Only a
   chart with no such alignment falls back to the sentence span, recorded as
   `span-source=sentence`, so the two cannot be confused downstream.
6. **The summary is the source title with evidence spanning the whole canonical text**, so
   `hierarchy.member-within-parent` holds for every emitted situation; no title yields an
   abstained summary attempt and a `NoTitle` row.
7. **No causal attempts.** Absent pairs are "not evaluated", per §6 of this ADR.
8. **Policy: `AcceptancePolicy.Conservative`, except `ContextAssignment` and
   `SegmentMembership` at `requireAgreement = 1`.** The resolver counts agreement by provider
   identity, and one deterministic program is one provider; the fixture's trick of naming two
   rules as two providers was a workaround, not a policy. The relaxation is stated in the rules
   text, so it is in every config hash.
9. **Receipts.** One `ProviderCall("chart-proposal-provider", "chart-rules", "1")` per rule
   application, input checksum = the source's canonical checksum, output checksum = a
   NUL-separated render of what was emitted, params naming the sentence, chart checksum, rule,
   and span source. The prompt-package checksum and the provenance config hash are both
   `Checksum.ofText(RulesText)`, so a rule change changes every receipt. Provenance calls include
   every chart's own receipts. The build receipt carries the optional parser stage and this
   provider's stage digest over its call outputs.
10. **Order independence.** Every emitted vector is sorted by content key; chart order and
    alignment order do not change the proposals or the compilation fingerprint (tested).

Two shapes deviate from the phase brief, on purpose. `AbstentionReason` carries only the three
root reasons; an empty chart is its own `SentenceCoverage.EmptyChart` row because an abstention
row names an anchor and an empty chart has none, and the summary has its own `SummaryCoverage`
because it is not a sentence. `ChartProposals` is a final non-case class with a
`private[document]` factory, per the cartesian-product test in the design contract.

Evidence: `ChartProposalProviderSuite` (document, all three platforms) courts the provider on a
three-sentence text; `ChartProposalCourtSuite` (fixtures) drives the WOG source through the
analyzer, supplies seven hand-built silver charts, and pins the ledger counts, the recorded gaps,
and `validated == None` for the multi-situation compilation.

### 2026-09-02: compiler extension for multi-situation drafts (solo plan phase 1.4)

The first slice refused to promote any compilation with two or more situations because its input
carried no participant or temporal proposals. This amendment extends `NarrativeCompilerInput`,
`NarrativeCompiler`, and `ChartProposalProvider` so that a multi-sentence draft validates on
evidence, never on defaults. The slice's refusal paragraph above is replaced by the supported rule.
Decisions, with the alternative rejected:

1. **Four new attempt families, one new claim family.** `EntityMentionAttempt(mention, bundle)`
   with `EntityMentionProposal(label, entityType)`; `ParticipantAttempt(situation, filler, bundle)`
   valued in `ParticipantRole`; `ParticipantCoverageAttempt(situation, bundle)` valued in
   `ParticipantCoverage` (the sorted, deduplicated filler set a provider evaluated, possibly
   empty); and `TemporalAttempt(from, to, bundle)` valued in `TemporalRelation`. `acquire` gains
   `ClaimFamily.ParticipantCoverage` (ordinary policy). Each family has its own
   `NarrativeCandidateAddress` case and its own resolution record, so the receipt names every
   nominated candidate. Rejected: deriving participants inside the compiler from chart roles
   without an attempt. A role the provider never proposed would then become an edge with no
   provider receipt, which is exactly the fabricated licence §7 of the design contract names.
2. **Coverage is a value, not the absence of participants.** `DiscourseTrajectory.derive` computes
   entity turnover from participant sets, and an empty set yields 0.0. Without a coverage claim,
   "no participants evaluated" and "evaluated and found none" produce the same number. An accepted
   empty coverage is the evidenced statement that the chart reaches no licensed participant from
   the root (the root's own support is its evidence); it yields turnover 0.0 legitimately. An
   unresolved coverage blocks every step touching the situation. The input binding requires exactly
   one coverage attempt per situation attempt, every coverage filler to have a participant attempt,
   and every participant attempt to be named by a coverage candidate. Rejected: inferring coverage
   from the participant attempts present. A provider that proposed two of three fillers would then
   read as complete.
3. **The step gate.** For each adjacent pair of emitted situations in discourse order the compiler
   requires an emitted coverage claim at both endpoints (a coverage claim is emitted only when
   every filler it names has an emitted participant edge) and an emitted temporal claim for the
   pair. If every pair passes, the whole trajectory is derived and each step's flow claim is the
   `Emitted` disposition of its `TrajectoryStep` candidate; if any pair fails, the trajectory is
   empty, each failing pair is `MissingUpstream(coverage or temporal addresses)`, and each passing
   pair is `MissingUpstream(the failing steps)`, because `trajectory.complete` is all-or-nothing.
   `DerivationGapReason.UnsupportedTrajectoryInputs` is removed; nothing else referenced it.
   Rejected: deriving steps for the passing pairs only, which the validator would refuse and which
   would leave a partially derived trajectory in the draft.
4. **Exact coreference by case-folded label and type.** Accepted mentions are grouped by
   `(TextNorm.lower(label), entityType)`; the `EntityId` is
   `ExactCorefCluster.canonicalFor[EntityK]` over the sorted member mention ids, so no provider can
   mint one. The node claim and the label claim are separate `StructurallyDerived` claims with the
   member mention claims upstream (the model ledger is keyed by claim id). The label is the earliest
   member's spelling; other spellings are alternatives on the `Resolved` label. Mention claims are
   `SurfaceExplicit`, by the same argument as situation mentions: the compiler verifies the chart
   node is an entity, name, or quantity concept and the claim cites its spans. Participant,
   coverage, and temporal claims are `Hypothesized`, the conservative floor. Rejected: grouping by
   exact label. "Man" and "man" are one lemma in every chart this repository produces, and the
   fold is locale-independent.
5. **Provider rules.** For each admissible root, every relation from the root to an entity-kind
   concept with exactly one licensed participant role yields one participant attempt and one
   entity-mention attempt (label = lemma, type = `Custom("chart", kind lowercased)`). A numbered
   argument is licensed only by a normalized role already on the chart (the lexicon's); a named
   role is licensed by the chart's own normalized role, else by the standard table mirrored from
   the AMR adapter (`location`, `time`, `manner`, `cause`, `purpose`, `instrument`,
   `beneficiary`, `source`, `destination`). A filler reached by no licensed role, or by two
   different ones, is counted `unlicensed` on `SentenceCoverage.Proposed(sentence, root, fillers,
   unlicensed)` and never proposed. One coverage attempt per admissible root lists exactly the
   proposed fillers; an abstained root yields an abstained coverage attempt. One `Unclear` temporal
   attempt per consecutive pair of admissible roots in sentence order. Never `Before` or `Meets`:
   a `:time` filler is a concept of its own chart, not a preceding root, so nothing in a chart
   licenses strict precedence between roots; and `StrictPrecedence` is a high-impact family whose
   conservative policy this single provider could not satisfy alone. Both facts are in
   `RulesText`, so they are in every receipt. Rejected: proposing `Before` from sentence order.
   Discourse order is not story-world order (design contract rule 5).
6. **Evidence.** Mention evidence is the filler's alignment spans (`span-source=filler-alignments`),
   else the root support (`span-source=root-support`), so an entity's support is the words that
   mention it rather than the whole sentence; this deviates from the phase brief, which unioned
   filler and root spans for both, because the entity support is what the Atlas draws. Participant
   evidence is the union of the filler's alignment spans and the root support. Temporal evidence
   spans both roots' support.
7. **Identity.** The candidate-set tag is `narrative-candidates/v4` and the compilation tag
   `narrative-compilation/v3`; the fingerprint now covers entity nodes, participant and temporal
   edges, and flow steps (turnover, transition, context). The rules checksum is pinned as a
   literal in `ChartProposalProviderSuite`.

The 1.3 cold review's findings are closed in the same slice, because they live in the same
files:

8. **Alignment spans lie inside the chart's own sentence, on both sides.** The provider refuses a
   chart whose alignment span lies outside its sentence unit whatever surface unit the span
   names (`None`, another sentence, or a holder that is not the sentence or a unit inside it),
   and the compiler refuses evidence cited by a chart-anchored attempt (situation, context,
   membership, mention, participant, coverage) that lies outside the attempt's sentence, and by
   a pair attempt (temporal, causal) that lies outside both endpoint sentences. The summary and
   the shared ledger are unrestricted. A participant's filler must be in its situation's sentence.
   Rejected: repairing the span to the sentence, which would make a mislocated alignment look
   like a measured one.
9. **Receipts identify their output.** Every evidence id is
   `chart-proposal-evidence/v2(scope, chart checksum, rendered span set)`, so two charts that
   differ only in spans (invisible to `Canonical.checksum`) have different evidence, and every
   call render ends with the evidence id it cites, so their receipts and the provider-stage
   digest differ too. The parser stage's digest on the build receipt is
   `ChartProposalProvider.chartsDigest` (sentence, canonical checksum, rendered alignments with
   credence, chart receipt outputs), never typed by the caller; `input` takes the stage id only.
   Every chart receipt must have hashed the canonical text or its sentence's text, else the
   chart is refused; the chart origin is a receipt parameter (`hand`, `parser:<fingerprint>`,
   ...). State frames match namespace and id (`amr:be-located-at-91`), not the id alone.
10. **Chart credence propagates only as the raw score.** Each proposal's raw score is the minimum
    alignment credence among the alignments that supply its support (a sentence-fallback support
    carries 1.0, which `span-source=sentence` distinguishes from a measured value); calibration
    is probability 1.0 under `chart-rule-v1` for the rules that are total functions of the chart,
    under `narrated-world-default-v1` for the context rule, and under `title-rule-v1` for the
    summary. Recorded limitation: `NarratedWorld` is the absence-of-embedding default at sentence
    grain (the focus is held by no embedding, so the sentence is taken to assert it at root); the
    chart licenses no context positively, and chart credence reaches no probability.
11. **The vertical suite's fixture provider is retired.** `NarrativeCompilerVerticalSuite` now
    builds one hand chart for its sentence and drives `ChartProposalProvider`; the two-rules-as-
    two-providers agreement fiction is gone with it. The coverage ledger
    (`ChartProposals.coverage`) is not carried into `NarrativeCompilerInput`; it is derivable from
    the input: a sentence with a situation attempt whose bundle has a proposed value is
    `Proposed`, one whose attempt is abstained is `Abstained`, a chart with no concepts is
    `EmptyChart`, and an atlas sentence with no chart is `NoChart`.
12. **`-91` reification roots arrive as `ConceptKind.Special`.** The AMR adapter classifies every
    `-91` roleset as `Special` under its `propbank` frame namespace, so the 1.3 state rule (which
    required a `Predicate` focus and, as first amended here, an `amr` namespace) was unreachable
    from real charts: "There were people at Egulac" abstained with
    `focus-not-predicate:Special`. A focus of kind `Special` whose frame is in the closed state
    set under `propbank` is now an admissible State root under the same evidence rules; any other
    `Special` focus (a frameless AMR special such as `date-entity`, or a `-91` frame outside the
    set) still abstains with the typed reason. The compiler admits a `Special` focus only when it
    carries a frame. `ChartProposalCourtSuite` pins the namespace equal to
    `InteropTables.FrameNamespace` and every state frame as `isSpecialFrame` to the adapter, and
    the WOG Egulac chart now mirrors the adapter's classification. Rejected: treating every
    `Special` focus as a situation, which would make `date-entity` a state.

Evidence: `TrajectoryCompilerSuite` (document, all three platforms) is the court: three hand-built
sentences each with a licensed `ARG0 → man` compile into one entity with three mentions, three
participant edges, two `Unclear` temporal edges, and two steps with `Unresolved` world time and
turnover 0.0, and validate; withholding one coverage, one participant, or one mention yields the
named gaps and no step; `After` is refused at the input; the case fold and the zero-turnover court
are pinned. `ChartProposalProviderSuite` pins the provider counts and the rules checksum;
`ChartProposalCourtSuite` (fixtures) pins the WOG ledger (one licensed filler, six coverage
attempts, three `Unclear` pairs, eight gaps, `validated == None` while two sentences abstain);
`CompiledAtlasSuite` (view) compiles the three-sentence silver model through `AtlasCompiler` on
the `ValidatedBuild` basis. Mutation ledger: see the landing commit.


### 2026-09-02: coordinated, predicative and existential roots (solo plan slice 1.5, classes B, C, D)

Measured on `main` over the fifty captured War of the Ghosts replies
(`pipeline/src/test/resources/recordings/wog-captured`), 43 sentences reached the provider and 27
became situations. The sixteen abstentions were not diffuse: fourteen were a coordinating focus,
one was a property with a `:domain`, and one was an entity with neither a place nor a frame. That
is a defect in the root rule, not in the model's output: `(a / and :op1 (l / land-01 ...) :op2
(g / go-02 ...))` is two events in one sentence, and refusing it because the focus is not a
predicate loses a true reading of the sentence to fit our compiler.

1. **A coordinating focus's branches are the roots.** `ChartRoots` (new, `document`) names the
   closed set `{and, or, multi-sentence}` by *lemma*, on a concept carrying no frame. No concept
   kind separates a coordinator from an ordinary entity: the AMR adapter makes `and` and `or`
   `Entity` and `multi-sentence` `Special`. Each direct branch — a relation under `:opN` (`and`,
   `or`) or `:sntN` (`multi-sentence`) whose target is a concept of the chart — is evaluated as a
   root in its own right, in branch order (operands before sentences, then by index). An admitted
   branch yields its own situation, context, membership and participant-coverage attempts, its
   support drawn from its own subtree, its description from its own gloss, and its polarity from
   its own chart polarity.
   - *Rejected: descending into a nested coordinator.* An `and` under an `and` abstains with
     `coordination-branch-nested`. One `:op` index states an order among siblings and nothing
     about an order across levels, and inventing one would be an ordering the chart does not
     carry. The predicates under the inner coordinator are therefore not roots, and the ledger
     says so rather than losing them silently.
   - *Rejected: emitting one `Proposed` coverage row per branch.* `CoverageCounts` must sum to the
     number of atlas sentences; several rows would claim the story had more sentences than it has.
     `SentenceCoverage.Coordinated` is one row carrying every branch, admitted or abstained with
     its reason, and `CoverageCounts` gains a `coordinated` counter that counts sentences.
   - *Rejected: `Before` between siblings.* `and` asserts conjunction, not sequence. Coordinated
     siblings get the same `Unclear` temporal attempt any adjacent pair of roots gets. Their
     discourse order is their branch index; that is a discourse fact and not a story-world one.
   - **Reentrancy is one mention.** `:op1 (c / carry :ARG0 (t / they)) :op2 (p / put :ARG0 t)`
     licenses `they` from both branches. The first branch in branch order mentions it; every
     branch that licenses it takes it as a participant. Two mention attempts at one chart node
     are two claims that a word occurs out of one occurrence, and the compiler refuses them as
     duplicates — which is how this was found.
2. **The compiler accepts a non-focus source only as a coordination branch.**
   `NarrativeCompiler.admissibleSituationSource` admits the chart focus, or a direct `:op`/`:snt`
   branch of a focus that `ChartRoots.isCoordinator` accepts, and nothing else. The refusal
   message states the rule. *Rejected: admitting any non-embedded predicate of the chart.* A
   predicate reached by `:time` or `:ARG1` is an argument of something, not an assertion of the
   sentence; the guard test drives exactly that case through the compiler.
3. **A predicative root is a State.** A frameless concept of kind `Property` or `Entity` whose
   `:domain` reaches a concept of the chart — `(d / dead :domain (h / he))` — is a State whose
   predicate is the property lemma, with no frame, and whose `:domain` filler is a participant.
   `ChartProposalProvider.NamedRoles` gains `domain -> Custom("amr", "domain")`; the AMR adapter's
   standard-role table does not normalize `:domain`, and this is the only entry in that table that
   is not the adapter's verbatim. *Rejected: `Theme` or `Patient` for `:domain`.* `:domain` says
   which concept the head is predicated of and nothing about how that concept participates, so a
   thematic name would assert what the chart did not.
4. **An existential root is a State.** A frameless concept of kind `Entity` whose `:location`
   reaches a concept of the chart — `(p / person :quant many :location (e / egulac))` — is a State
   of existence at that place, with the locative filler a participant at `Location`. *Rejected:
   requiring the existential quantifier.* `:quant many` and `:quant 5` are literals in these
   charts and the reading is the locative one; requiring the quantifier would fit two examples
   rather than state a rule. *Rejected: inventing an `exist` predicate.* The predicate lemma is
   the concept's own, because no other lemma is in the chart.
   - `KindWitness.situation` gains `acceptsAt`: an `Entity` concept may be a situation mention
     exactly where the chart places it. The kind-only answer is unchanged, so this licenses one
     shape and not every entity, and which sources actually become situations remains the
     compiler's rule.
5. **Rules 3 and 4 need a compiler change beyond rule 2.** `situationRoot` previously admitted a
   predicate or a framed `Special`, so a frameless `Property` or `Entity` root would have been
   refused after the provider proposed it. It now also admits the two shapes above, structurally,
   from the chart. The compiler stays looser than the provider by design: it refuses what cannot
   be a situation at all, and the provider decides what it will propose.
6. **What moved, measured on the fifty captured replies.** These three rules were written and
   first measured while class A (a marker on a role, a reentrancy, or a constant made the
   transport refuse the whole reply) still cost the story seven charts. At that point coverage
   went from `proposed 27 / abstained 16 / noChart 7` to `proposed 28 / coordinated 14 /
   abstained 1 / noChart 7`, and **the existential rule moved nothing**: both of the story's
   existential sentences ("There were people at Egulac", "There were five men in the canoe") were
   among the seven the transport refused, so the rule was provable only against hand-built charts.
   With class A landed the transport mirrors every decoded marker into the sidecar, all fifty
   replies yield charts, and the combined state is `proposed 33 / coordinated 16 / abstained 1 /
   noChart 0`: gaps 64 → 4, required-derivation errors 48 → 3, situations 27 → 65. The rule that
   admitted each of the 65 roots is on its receipt and pinned: 61 predicate (32 of them
   coordination branches), 1 state roleset, 1 predicative, **2 existential** — the two sentences
   above, which is the delta this ADR could not measure when it was written.

   The one remaining abstention is "It was nearly daylight when he became quiet", whose focus
   `daylight` is an entity with a `:degree` and a `:time` and no place: a class we decided not to
   admit, not one we failed to notice.

Evidence: `CoordinatedRootSuite` (document) is the court for all three shapes and their negative
cases — branches that are not roots, a nested coordinator, a coordinator with no branch, an
embedded branch, a `:domain` reaching a literal or nothing, an entity with no `:location`, and a
lexical entity outside the coordination set. `CompilerSuite` drives the non-branch source through
the compiler. `StoryBuildSuite`'s captured court pins the fifty-sentence ledger. Mutation ledger:
see the landing commit.

## Amendment, 2026-09-02 — slice 1.7: referentiality, and a title is not a filename

**Decider:** `claude-storymodel4s` (single-developer mode, SD5: the author writes and decides the
ADR, and records the rejected alternative on the day).

Two things the built model **said** that were not true. Both are closed by rules with names, both
rules are written into `ChartProposalProvider.RulesText` so the prompt-package checksum and the
provenance config hash move with them, and both carry a mutation proof.

### 7. Referentiality is decided by the role and the concept together

`role-referentiality-rule`. A chart filler is a referent of its root — and so mints an entity
mention and a participant edge — only when **both** hold:

- the **role takes a referent**: `Agent`, `Patient`, `Theme`, `Experiencer`, `Stimulus`,
  `Instrument`, `Beneficiary`, `Source`, `Destination`, `Location`, plus this provider's own
  `Custom("amr", "domain")` (below);
- the **concept can denote one**: `ConceptKind.Entity` or `ConceptKind.Name`.

`Time` and `Manner` license a **circumstance**, not a participant. `Cause` and `Result` relate
eventualities. Every other `Custom` role is `Unestablished` and licenses nothing.

*Why the rule was needed.* `scanFillers` admitted any entity-kind filler whose role normalized to
exactly one `ParticipantRole`, and `Time` and `Manner` normalize like any other role. So the model
carried `then`, `now`, `midnight`, `night`, `thus` and `together` as entities holding participant
roles. A time is not a participant and an adverb is not a cast member, and every measurement over
the entity layer — turnover above all — was counting them.

*Rejected: deciding referentiality from the role alone.* That is the rule that produced the defect.
It also lets a `Quantity` filler into the cast, where a measure of a referent would be published as
one.

*Rejected: deciding it from the concept kind alone.* `KindWitness.entity` already does exactly that
and admits `Entity | Name | Quantity`; kind cannot see that `:time (n / night)` is a time.

*Rejected: `Location` admits an entity only when the filler "is a place".* This is what the slice
plan asked for, and it is not derivable. No chart signal separates a place from an entity standing
in for one, and a word list deciding it would be world knowledge asserted by the layer that exists
to refuse world knowledge. `Location` is therefore referential on the same footing as the others: a
canoe, a log and a named village are referents. The cost is that the deictic `there` stays an
entity, which is stated here rather than hidden.

*Rejected: refusing every `Custom` role, `amr:domain` included.* Tried, and it dropped a real
referent. `:domain` is minted by `NamedRoles` for the predicative shape `(d / dead :domain (h / he))`
and its filler is the concept the state holds of. `:domain` is a `Custom` because it names no
*thematic* role, which is a different question from whether it takes a referent. The exception is
named, `Referentiality.PredicationSubject`, and every other `Custom` — `amr:purpose` included —
stays refused, fail-closed.

### 8. A `:time` or `:manner` filler is recorded, not discarded

`situation-circumstance-rule`, `ClaimFamily.SituationCircumstance`, ordinary policy. The filler
becomes a `story.CircumstanceEdge` on its situation, carrying `CircumstanceKind` (`Time`/`Manner`),
the **source's own word**, its own `SpanSet` from the filler's alignments, and a `ClaimMeta`. The
input court refuses a filler proposed as both a participant and a circumstance of one situation.

*Rejected: dropping the filler.* Trades one falsehood for a silence; the words are evidence the
source really carries.

*Rejected: normalizing the label to an instant, a date or an interval.* `midnight` and `then` are
what the source said. Placing a circumstance on a timeline is a different claim with a different
licence, and this edge asserts only that the situation's own words said this much.

*Rejected: recording it only in the coverage ledger and the receipts.* That was the fallback if a
`story` type change proved too wide. It did not: `RelationLayers` gains one defaulted field, and
the family follows the participant family's existing shape end to end.

`SentenceCoverage.Proposed` and `CoordinatedBranch.Admitted` now carry `FillerCounts(referents,
circumstances, nonReferential, unlicensed)`, whose four counters sum to every filler the scan saw.
*Rejected: one "not proposed" counter.* Four states with one number is the defect this file is
mostly about.

### 9. A filename is not a title

`title-summary-rule` publishes a summary only from `StorySource.establishedTitle`, which returns a
title **and** the recorded basis for carrying it. `TitleProvenance` has one case, `CallerSupplied`,
because a caller stating it is the only basis this project has ever established. `StoryTitle` is a
checked non-case type; `StorySource.titled` is the only way to record value and provenance
together. The pipeline no longer reads `textPath.getFileName`; `storyBuild` and `claudeParse` take
an optional trailing title argument.

*Why.* The pipeline passed the input file's name as the title, the provider proposed it as the
story summary, and the compiler accepted it at credence 1.0 under calibration model
`title-rule-v1`. The model asserted, with a receipt, that the narrative is called `wog.txt`.

*Rejected: deriving a title from the file and marking it low-confidence.* A default epistemic
status is a fabricated license (design contract item 7). The problem is not the weight; it is that
nothing entitles the claim.

*Rejected: a vararg-free `title: String = ""`.* An absent title and an empty one are different
facts about what the caller said, and one `String` cannot carry both.

*Rejected: making `StorySource.title` itself a `StoryTitle`.* Wider than the slice, and it would
give one field a provenance the others do not have. The provenance lives in `metadata` under
`TitleProvenance.MetadataKey`, so a source built before this rule reads back as a title with **no
recorded provenance** — which is what it is — rather than one silently promoted.

**Consequence, accepted.** With no title the summary family is unresolved, there is no story
segment, no situation sits under a primary root, and the model does not promote to validated. That
is the honest outcome for a bare text file. The fix for it is a real summary rule that reads the
story, not a better filename.

### Schema

`StoryModel.SchemaVersion` and `codec.SchemaVersions.Current` move `0.1.0` → `0.2.0`, because
`circumstances` is a required encoded field. **`Migration.steps` deliberately has no step for
0.1.0**: a 0.1.0 model has no circumstance layer because the rule that fills it did not exist, so
reading one as `circumstances: []` would publish "evaluated and found none" for a model that never
evaluated. A 0.1.0 artifact is refused with a typed `UnsupportedSchema` and rebuilt from its source.

### What moved, measured by replaying the fifty captured replies

With the story's title stated by the caller, so the measurement isolates the entity rule:

| | before | after |
|---|---|---|
| entities | 35 | 29 |
| participant edges | 68 | 57 |
| circumstance edges | 0 | 11 |
| claims | 398 | 386 |

The six entities that left are `then`, `now`, `midnight`, `night`, `thus`, `together`. Nothing was
discarded: the eleven fillers behind them are the nine `:time` and two `:manner` ones, all recorded
as circumstances with their own spans.

**Not closed, and stated rather than hidden.** Two entities the slice plan names as defects survive,
because the charts do not support removing them. `other` ("the other went home") is a nominal
referent under `Agent` and is correctly an entity. `sick` ("He did not feel sick") arrives as
`ConceptKind.Entity` under `:ARG1` in the one recording that produced it, while another recording
writes the same word as `sick :domain i2`, which `ToChart` classifies `Property` and the entity
witness already excludes. The disagreement is in the charts, not in this rule; the fix belongs to
the parser prompt or `ToChart`'s kind rule and is a different defect.

Evidence: `ReferentialitySuite` (document) proves the closed role list complete against
`ParticipantRole`'s own mirror and pins every licence. `ChartProposalProviderSuite` courts the time
filler becoming a circumstance, the quantity filler refused by the concept coordinate with its
reason on the receipt, and the unestablished title abstaining under its own reason.
`TrajectoryCompilerSuite` courts the circumstance edge, the both-roles refusal, and the unknown
endpoint. `StoryBuildSuite` pins the fifty-sentence entity count, the entity label set, the
circumstance histogram, the no-title consequence, and that no bundle file names the input file.
Mutation ledger: see the landing commits.
