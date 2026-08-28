# storymodel4s — Architecture and Roadmap

**Status:** Synthesized implementation plan, revision 2 (2026-08-28). Supersedes
the per-part proposals in `NARRATIVE_PROCESS_ALIGNMENT_NOTES.md` §33, §53, §72
where they conflict, and incorporates the automation (§84–89), autonomous
agent-system (§90–97), and AMR boundary review (§98–104) checkpoints.
**Goal:** the reference library for neurosymbolic representation of written
stories, human recall of those stories, the alignment between the two, and the
Autobiographical Interview as a latent-source instance of the same problem.

## 0. The architecture in one diagram

```
canonical text ─▶ exact surface atlas (A)                                        [core]
              ─▶ local propositional charts + exact alignments                   [proposition]
                    ◀── decoded from AMR/PENMAN candidates                       [amr-interop]
                    ◀── proposed by parser / LLM agents, critiqued, resolved     [acquire + providers]
              ─▶ mention graph ⊔ charts, exact-coreference quotient π            [document]
              ─▶ narrative graph G, contexts, hierarchy H, trajectory X, ledger Γ [story]
recall text   ─▶ same local stack ─▶ recall graph ℛ                              [recall]
(ℛ, 𝒮)        ─▶ unbalanced, hierarchical, directed alignment P, flow F, signature m   [align]
interview     ─▶ latent target episode ℰ* inferred jointly with P; AI-compatible scores derived   [interview]
```

Division of labour: **agents (LLMs, parsers)** emit typed proposals with
evidence; **embeddings** retrieve and give graded geometry; **propositional
structure** (concept, role direction, polarity, reentrancy, embedded
propositions) fixes the hard local distinctions; **deterministic Scala code**
resolves, validates, receipts, and is the only thing that can accept a claim.

Three invariant commitments follow:

1. **The canonical local object is the `PropositionChart`, not an AMR graph.**
   AMR is a valuable adapter (standards, parsers, guideline gold); its types
   never leak past `amr-interop`.
2. **Builds are unattended.** Source text → receipted `StoryModel` with
   calibrated uncertainty, with no human in the loop. Humans work offline: gold
   corpora, calibration, audits, release review.
3. **The *War of the Ghosts* fixture is a narrative acceptance fixture**, not
   hand-authored AMR. Charts for it are machine-generated silver.

## 1. Why this can be better than what exists

| Existing tool | What it does | What it cannot do (and we do) |
|---|---|---|
| Penman (Python) | PENMAN parse/print, AMR graph model | No checked types, no story layer, no exact-offset evidence |
| Smatch | Triple-overlap F1 under variable mapping | Not partial, not hierarchical, no role-reversal/negation penalties, no external mass |
| amrlib / SPRING / IBM parser | Text→AMR | Providers only; no identity, contexts, or narrative structure; untested on spoken recall |
| Event-segmentation HMMs | Fixed ordered event sequences | No skips, backtracking, external states, hierarchy, typed relations |
| Segment-max-embedding gist scoring | Scalable gist coverage | No detail fidelity; role/polarity errors invisible; no trajectory |
| DistilBERT / LLaMA AI scorers | Internal/external word proportions | No detail identity, target episode, external subtypes, relations, probe provenance |

Every one of those is reproducible inside storymodel4s as a *baseline*
(Smatch, segment-max similarity, balanced FGW, traditional AI totals).

## 2. Modules

All portable modules are `CrossType.Pure`, JVM/JS/Native, and depend only on
cats(-parse/-collections). JVM-only adapters are separate sbt projects.

| Module | Depends on | Owns |
|---|---|---|
| `core` | cats | opaque IDs with `NarrativeKind` phantoms, UTF-16 `TextSpan`/`SpanSet`, `StorySource`, `SurfaceAtlas` + deterministic `SurfaceAnalyzer`, `EpistemicStatus`, `Credence`, `Evidence`, `ClaimMeta`, `Resolved[A]`, `ClaimLedger`, provenance, receipts, pure SHA-256 content addressing, `DomainError` |
| `proposition` | core | **`PropositionChart[C <: CheckState]`**: focus, concepts (lemma/gloss, *optional* frame ref + sense), relations (numbered or named source role, *optional* normalized `ParticipantRole` with its own credence), polarity, reentrancy, `EmbeddedProposition`, explicit `Unknown`/partial values, `PropositionAlignment` to exact spans, provenance + alternatives; validator; chart isomorphism; `ChartCompatibility` (frame/lemma match, argument match, role reversal, polarity/embedding conflict) |
| `amr-interop` | core, proposition | PENMAN tree (lossless profile), `AmrGraph[Check, RoleForm]`, validator, role inversion/canonicalization, `Shape` evidence, alpha-renaming isomorphism + canonical form, Smatch *baseline*, `FrameLexicon` + `SchemaChecker` (unknown frame = warning), `AmrAlignment` sidecar, `ToChart`/`FromChart`, Penman-oracle golden tests. Reification, UMR I/O, and Smatch optimization are deferred |
| `acquire` | core, proposition | autonomous acquisition protocol: `TaskPacket`, `AgentProposal[A]` with `ProposalDisposition`, `CriticFinding` codes, typed `Patch` ops, `ResolutionState[A]` (Accepted/Alternatives/Unresolved/Rejected), `AcceptancePolicy` per claim family (conservative for the high-impact families in §94), stage-cache keys, `PromptPackageManifest`, foil generators (§95), `CandidateLedger` |
| `story` | core, proposition | entity/situation/segment/context nodes, `RelationLayers`, `NarrativeHierarchy`, `BoundaryBelief`, `DiscourseTrajectory`, `FeatureSpace`/`FeatureRef`, `DescriptorClaim`, `StoryModel[S <: ModelStatus]`, `StoryValidator`, indexes, renderer, `AlignmentSource` |
| `document` | core, proposition, acquire, story | `MentionGraph = ⊔ charts`, typed `ExactCorefCluster[K]`, quotient π, `NarrativeReference` modes, `Projection[K]`, `ContextualAssertion`, map–reconcile–revisit indexes (§92) |
| `recall` | core, proposition, story | `RecallUnit(z,q,ψ,κ)` with a `PropositionChart` as ψ, `DiscourseFunction`, grounding α, recall relations, `RecallGraph`, baseline `RecallSegmenter` |
| `align` | core, proposition, story, recall | `SourceView`, `LocalCost` algebra with gating contradictions, `CandidateGenerator`, `AlignmentMatrix` with external states, `TransitionFlow`, `GraphHsmm`, `UnbalancedSinkhorn` baseline, `RelationPreservation` refinement, `SupportDensity`, `FidelityFacets`, `RecallSignature`, calibration + leave-story-out helpers |
| `interview` | core, proposition, story, recall, align | transcript atlas (speakers, phases, probes), `DetailAtom`, `MemoryAddress`, `EpisodeScope`, `DetailAssessment`, latent `EpisodeModel`, target-episode induction, `AiScoringPolicy` → traditional counts (expected + hard), rich profile, relational pseudonymization |
| `codec` | all | canonical circe JSON, JSONL ledgers, schema version + migrations |
| `fixtures` | all | *War of the Ghosts* text (Boas 1901, PD) + narrative acceptance fixture + plain-language expectations + recall paraphrases; Anna/cellar example; birthday interview |
| `laws` | all | Discipline suites + generators |
| **M1, JVM-only** | | `build` (orchestrator: stage DAG, cache, budgets, gates), `provider-parser` (AMR parser services over HTTP/subprocess), `provider-agent` (schema-constrained LLM proposal/critic agents, prompt packages), `provider-embed` (ONNX/remote embeddings), `cli` |

Sibling workspace libraries are not consumed in v0.1 (none is published);
adapter modules are planned once the API stabilizes.

## 3. Decisions on the open reconciliation questions

### Part 2 (§37)
1. **Notation.** `(A,G,H,X,Γ)` is authoritative; Φ = sidecar feature views; U = Γ.
2. **Soft support h_v(t).** Derived in `align` (`SupportDensity`) from exact `SpanSet`s and hierarchy; never stored as source truth.
3. **Epistemic vocabulary.** One enum; the causal ladder maps losslessly (explicit→`SurfaceExplicit`, entailed→`LinguisticallyEntailed`, strong story support→`StructurallyDerived`, commonsense→`WorldKnowledgeInferred`, hypothesis→`Hypothesized`).
4. **Context vocabulary.** `NarratedWorld | Speech(source) | Belief(holder) | Desire(holder) | Intention(holder) | Hypothetical | Counterfactual | Memory(holder) | Imagination(holder)`.
5. **Temporal inverses.** Forward relations only; `TemporalEdge.inverse` is a total involution.
6. **Validated-with-warnings.** Statuses stay `Draft | Validated | Adjudicated`; warnings live in the report; policy decides promotion.
7. **Segment ownership.** Segments are graph nodes; hierarchy owns containment + boundary beliefs.
8. **Entity continuity.** Derived layer from participant edges.
9. **P0/P1.** `BuildReceipt.layerCoverage` distinguishes NotAttempted from Attempted-but-empty.
10. **Canonical IDs.** Content-addressed from (story checksum, kind, sorted member mention IDs, schema version); patches produce new IDs with `supersedes` links.
11. **Claims.** `ClaimMeta` inline; `ClaimLedger` is a derived index.
12. **Roles.** `RelationLayers.participants` is the sole source of truth.
13. **Features.** Scalars/profiles canonical; vectors sidecar.
14. **Summaries/themes.** `DescriptorClaim` family on segments.
15. **WOG text.** Boas (1901) *Kathlamet Texts* pp. 182–184 — public domain. Bartlett's (1932) edited stimulus text is *not* yet public domain (US: 2028) and is not vendored; a lab copy may be registered as a second fixture version with its own checksum.
16. **Gates.** `docs/design/gates.md`, revised after five gold stories.
17. **Artifacts.** `schemaVersion` everywhere; `codec` owns migrations.
18. **Cats in core.** Intentional public dependency.
19. **Population/fMRI.** Downstream `population` module.

### Part 3 (§55) and the boundary review (§98–104)
1. **Build order.** Parallel: `proposition` first (small), then `amr-interop` and the story ontology spike concurrently; `acquire` alongside.
2. **Module boundary.** Exactly §99: `proposition` is the contract; `amr-interop` converts to/from it and retains the original AMR artifact as provenance; providers produce chart *candidates*. No narrative, recall, or interview API references an AMR type.
3. **PENMAN profile.** Tree preserves variables, branch order, inverse spellings, comments, metadata, `~e.N` markers; whitespace not preserved.
4. **Graph identity.** `Eq[AmrGraph]` exact; `AmrIsomorphism` alpha/edge-order invariant; `Canonical.form` deterministic — all internal to `amr-interop`. `proposition` has its own chart isomorphism.
5. **Shape typing.** `Shape` evidence value (`Connected`, `Acyclic`) on checked graphs.
7. **ArgIndex.** Syntactic bound 0–9; semantics lexicon-checked; **a bare `ARG0` without a frame is not Agent** — normalized roles carry separate credence.
8. **PropBank.** Small curated lexicon in v0.1; pinned-commit generator in M2. Frame sense is optional evidence; never a prerequisite for a usable chart; `DetailAtom`/projection never depend on lexicon success.
12. **Reference families.** `PropositionAlignment` (text↔chart), `ExactCorefCluster[K]`, `NarrativeReference` modes.
13. **Kinds.** `SituationNode = Event | State`; one `SituationK`.
14. **Quotient.** Materialized canonical graph + stored π.
21. **Acquisition.** No parser is trained; no parser is the sole path; no LLM extractor is an unvalidated sole authority. Two acquisition *profiles*: clean prose (parser candidate + agent critic/proposal) and spoken recall/interview (schema-constrained agent extraction primary, parser optional). Routing/weighting is learned from benchmarks, not assumed (§101).
22. **Oracle.** Python Penman 1.3.1 in dev/CI; golden outputs committed.
24. **Smatch.** Seeded hill-climb baseline only.
25. **Soft matching.** `ChartCompatibility` is a term in `align.LocalCost`.
— **Deferred:** reification, UMR import/export, Smatch optimization, generated frame builders.

### Part 4 (§75)
1. `interview` is a sibling consumer of the same kernel.
2/3. Story Phase vs Interview Phase; `AutobiographicalInterview*` names; `AiCompatible` only inside `interview.scoring`.
5. Latent `EpisodeModel` carries `Hypothesized`/`StructurallyDerived` status; distinct type from `AlignmentSource`.
6. v0.1 joint inference: seed → candidate clusters → target selection → routing; EM/variational is Interview Phase 3.
23/24. Audio and population are later modules.
26. Relational pseudonymization at the atlas stage; provider receipts mandatory; no default remote provider.
27. English in v0.1.

### Autonomy (§90–97) — P0, not hardening
- Deterministic orchestrator owns stage DAG, retries, concurrency, cache keys, budgets, acceptance policy, gate status. Agents never decide what ran or whether their answer is canonical.
- Every agent returns `AgentProposal` (Proposed/Alternative/Abstained/Unsupported) with evidence and a receipt; no agent constructs `Resolved`, marks a score calibrated, or writes into `StoryModel`.
- Independent critics per family (syntax/graph law, frame-role, entailment/hallucination, polarity/modality/context, identity, temporal, causal overreach, hierarchy).
- Resolution → `Accepted(p) | Alternatives | Unresolved | Rejected`; calibrated only from offline gold; conservative policy for reported→root promotion, coreference, role reversal, polarity, strict precedence, causal edges, target-episode membership.
- Prompt packages are versioned, tested artifacts with typed I/O schemas, standards excerpts selected by code, contrastive examples, abstention rules, and benchmark suites. They reference sentence/token/node IDs only.
- Long documents: map (windows) → reconcile (indexes) → revisit (targeted nonlocal passes), content-addressed caching.
- Verification loops per build (§95), including automatically generated foils for high-impact claims and locality-of-change against the prior cached build.
- The plain-language review UI (§86) is an optional audit tool, not a build dependency.

## 4. Fixture and evaluation policy

| Artifact | Source | Purpose |
|---|---|---|
| AMR conformance gold | Published guideline examples; licensed AMR 3.0 in authorized environments only | PENMAN/graph laws, roles, codec |
| Project silver charts | Machine-generated with candidates, scores, receipts, disagreements | Story builds; regression |
| Narrative acceptance fixture (WOG) | Researcher-reviewed narrative types + plain-language expectations (§27.1/§27.2/§52.3, §88.3 questions) | Scientific fitness of the ontology and of automatic acquisition |
| Metamorphic pairs | Automatically transformed sentences with known expected changes (§88.2) | Local correctness without new gold |
| Expert AMR audit | Stratified sample, later | Publication claims only |

Discriminative spike (§103), two linked experiments: (a) oracle-structure value
on published examples + controlled variants; (b) automatic-acquisition value —
embeddings only vs. + parser chart vs. + agent chart vs. + resolved multi-provider
chart — on clean prose *and* recall-style text, measuring target ranking,
foil rejection, false contradiction, unresolved rate, calibration, stability,
cost, latency, and failure localization. Structure must survive acquisition.

## 5. Milestones

| Milestone | Delivers | Exit gate |
|---|---|---|
| **M0 Foundation** | `core` ✔; `proposition`; `amr-interop`; `acquire` types; `story` + validators + renderer; WOG narrative acceptance fixture + expectations; `recall`/`align` with worked examples; `interview` ADTs + policy projection + birthday fixture; `laws` | all WOG phenomena encode with no untyped escape hatch; G0 laws green on JVM/JS/Native; Anna/cellar and foil tests pass |
| **M1 Autonomous vertical slice** | JVM `build` orchestrator with stage cache and receipts; `provider-parser` (one parser service) + `provider-agent` (one proposal/critic agent) + `provider-embed`; prompt packages v1 with tests; `document` composition; automatic WOG silver atlas; `codec` round-trip; CLI `build/validate/inspect/diff` | several pages ingest unattended; exact cache replay; WOG §27.2 prohibitions hold under conservative automatic policy; G0–G1 |
| M2 Local semantics hardening | pinned PropBank lexicon generator, acquisition profiles, disagreement analysis, metamorphic pairs, discriminative spike (a)+(b) | G2–G4 on dev stories; spike report |
| M3 Relations | sparse candidates, temporal closure/conflicts, conservative causality, state changes | G5–G6 |
| M4 Hierarchy & features | trajectory, boundary evidence, constrained segmentation, feature sidecars | G7 + flow sanity |
| M5 Alignment | full graph-HSMM + relation refinement, calibration, RecallReadiness, ablation ladder, leave-story-out | G9 |
| M6 Interview | Interview Phase 1–3 | category-specific agreement vs. raters |
| M7 Population & neural; release | population flow, accessibility model, fMRI regressors; docs site; v0.1.0 | mandatory gates, no open P0 |

## 6. M0 sprint plan (parallel tracks, disjoint modules)

- **core** ✔ (47 tests, JVM/JS; Native compiles).
- **proposition** — chart ADT, validator, isomorphism, compatibility, alignment, generators/laws.
- **amr-interop** — as in §2 minus deferred items; guideline-example fixtures only; `ToChart`/`FromChart` added at integration.
- **acquire** — protocol types, resolution states, acceptance policy, foil generator interfaces, cache keys, prompt-package manifest; pure.
- **story + fixtures/wog** — ontology, validators, renderer, `AlignmentSource`; WOG narrative acceptance fixture from the Boas text with the §27.1 phenomena, §27.2 prohibitions as tests, and plain-language expectation records; recall paraphrases as data.
- **recall + align** — as in §2 with the Anna/cellar worked example and foil/ablation tests against an in-memory `SourceView`; bridged to `story.AlignmentSource` at integration.
- **interview + fixtures/interview** — after story lands.
- **integration & review** — bridges, `laws` consolidation, fresh-context review per track, `sbt checkAll`.

## 7. Non-negotiables

See AGENTS.md "Design contract" (items 1–14). In addition: leave-story-out
harnesses exist before any learned component; every numeric threshold is
provisional and versioned; a scalar score is only ever a declared projection
of a signature vector; automatic output is never called gold or historically true.
