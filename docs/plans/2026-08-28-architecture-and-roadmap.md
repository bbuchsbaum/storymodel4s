# storymodel4s — Architecture and Roadmap

**Status:** Synthesized implementation plan (supersedes the per-part proposals in
`NARRATIVE_PROCESS_ALIGNMENT_NOTES.md` §33, §53, §72 where they conflict).
**Date:** 2026-08-28.
**Goal:** the reference library for neurosymbolic representation of written
stories, human recall of those stories, the alignment between the two, and the
Autobiographical Interview as a latent-source instance of the same problem.

## 0. The one-paragraph architecture

```
canonical text ─▶ exact surface atlas (A)
              ─▶ checked local AMR charts + exact alignments        [amr]
              ─▶ mention graph ⊔ Aᵢ, exact-coreference quotient π   [document]
              ─▶ narrative graph G, contexts, hierarchy H, trajectory X, ledger Γ   [story]
recall text   ─▶ same local stack ─▶ recall graph ℛ                  [recall]
(ℛ, 𝒮)        ─▶ unbalanced, hierarchical, directed alignment P, flow F, signature m   [align]
interview     ─▶ latent target episode ℰ* inferred jointly with P; AI-compatible scores as a derived projection   [interview]
```

Division of labour (design record §38): **LLMs** propose typed patches;
**embeddings** retrieve and give graded geometry; **AMR** fixes local
propositional structure (frames, role direction, polarity, reentrancy,
embedded propositions); **Scala laws** decide admissibility and reproducibility.

## 1. Why this can be better than what exists

| Existing tool | What it does | What it cannot do (and we do) |
|---|---|---|
| Penman (Python) | PENMAN parse/print, AMR graph model | No checked types, no story layer, no alignment to exact source offsets as evidence |
| Smatch | Triple-overlap F1 under variable mapping | Not partial, not hierarchical, no role-reversal/negation penalties, no external mass |
| amrlib / SPRING / IBM parser | Text→AMR | Providers only; no document identity, contexts, or narrative structure |
| Event-segmentation HMMs | Fixed ordered event sequences | No skips, backtracking, external states, hierarchy, or typed relations |
| Segment-max-embedding gist scoring | Scalable gist coverage | No detail fidelity, role/polarity errors invisible, no trajectory |
| DistilBERT / LLaMA AI scorers | Internal/external word proportions | No detail identity, no target episode, no external subtypes, no relations, no probe provenance |

storymodel4s keeps every one of those as a *baseline* it can reproduce
(Smatch, segment-max similarity, traditional AI totals) and adds the typed,
evidence-backed, uncertainty-preserving structure above them.

## 2. Modules (all `CrossType.Pure`, JVM/JS/Native unless noted)

| Module | Depends on | Owns |
|---|---|---|
| `core` | cats | opaque IDs with `NarrativeKind` phantoms, UTF-16 `TextSpan`/`SpanSet`, `StorySource`, `SurfaceAtlas` + deterministic `SurfaceAnalyzer`, `EpistemicStatus`, `Credence`, `Evidence`, `ClaimMeta`, `Resolved[A]`, `ClaimLedger`, provenance, receipts, pure SHA-256 content addressing, `DomainError` |
| `amr` | core, cats-parse | PENMAN tree (lossless profile), `AmrGraph[Check, RoleForm]`, validator, role inversion/canonicalization, reification table, alpha-renaming isomorphism, canonical form, Smatch baseline, `FrameLexicon`, `AmrAlignment` sidecar |
| `story` | core | entity/situation/segment/context nodes, `RelationLayers` (participant, temporal, causal, goal, state-change, reference, containment), `NarrativeHierarchy`, `BoundaryBelief`, `DiscourseTrajectory`, `FeatureSpace`/`FeatureRef`, `StoryModel[S <: ModelStatus]`, `StoryValidator`, indexes, text/DOT renderer, `AlignmentSource` |
| `document` | core, amr, story | `MentionGraph = ⊔ Aᵢ`, typed `ExactCorefCluster[K]`, quotient map π, `NarrativeReference` modes, `Projection[K]` from AMR nodes to narrative nodes, `ContextualAssertion` |
| `recall` | core, story | `RecallUnit(z,q,ψ,κ)`, `DiscourseFunction`, grounding α, recall relation layers, `RecallGraph`, recall-side idea-unit protocol |
| `align` | core, story, recall | `LocalCost` algebra (semantic, propositional, entity, sensory, granularity, contradiction), `AlignmentMatrix` with external states, unbalanced Sinkhorn, sparse graph-HSMM forward–backward with typed transition costs, `SupportDensity` a_i(t), `RecallSignature` |
| `interview` | core, story, recall, align | transcript atlas with speakers/phases/probes, `DetailAtom`, `MemoryAddress`, `EpisodeScope`, `DetailAssessment`, `EpisodeModel` (latent, `Inferred`-status), target-episode induction, `AiScoringPolicy` → traditional counts (expected + hard), rich profile (density, purity, integration, fragmentation, probe gain), relational pseudonymization |
| `codec` | all | canonical circe JSON, JSONL ledgers, schema version + migrations |
| `fixtures` | all | *The War of the Ghosts* hand model + hand AMRs + recall paraphrases; Anna/cellar example; birthday interview |
| `laws` | all | Discipline suites + generators (published testkit) |
| later, JVM-only | | `provider-api`, `provider-llm` (http4s/circe, patch-only), `provider-onnx` (embeddings), `cli`, `bench`, `population` |

Sibling workspace libraries (`graph4s`, `grakern`, `gale`, `linop4s`,
`frame4s`) are *not* consumed in v0.1: none is published and git-SHA pinning
on day one adds fragility without value. Adapter modules (`story-graph4s`
views, `align-gale` dense kernels) are planned once the core API stabilizes.

## 3. Decisions on the open reconciliation questions

### Part 2 (§37)
1. **Notation.** `(A,G,H,X,Γ)` is authoritative. Part 1's Φ = sidecar feature views; U = claim ledger Γ.
2. **Soft support h_v(t).** Phase-1 artifacts store exact `SpanSet`s. Soft support is *derived* in `align` (`SupportDensity`) from span sets, hierarchy membership, and a configurable kernel; it is never stored as source truth.
3. **Epistemic vocabulary.** One enum. Causal ladder maps losslessly: explicit→`SurfaceExplicit`, entailed→`LinguisticallyEntailed`, strong story support→`StructurallyDerived`, commonsense→`WorldKnowledgeInferred`, hypothesis→`Hypothesized`.
4. **Context vocabulary.** `NarratedWorld | Speech(source) | Belief(holder) | Desire(holder) | Intention(holder) | Hypothetical | Counterfactual | Memory(holder) | Imagination(holder)`. Thought = `Belief`; report = `Speech`; dream = `Imagination`.
5. **Temporal inverses.** Only the forward Allen-style relations are stored; "after" is `Before` with swapped endpoints. `TemporalEdge.inverse` is total and an involution (law).
6. **Validated-with-warnings.** Phantom statuses stay `Draft | Validated | Adjudicated`; warnings live in `ValidationReport`; `ValidationPolicy` decides whether warnings block promotion.
7. **Segment ownership.** Segments are nodes in `NarrativeGraph.segments`; `NarrativeHierarchy` owns only containment edges and boundary beliefs.
8. **Entity continuity.** A derived relation layer computed from participant edges; exposed via `AlignmentSource.relationMatrix(RelationLayer.EntityContinuity)`.
9. **P0/P1.** `BuildReceipt.layerCoverage: Map[LayerId, NotAttempted | Attempted]`. Absence of goal edges with `NotAttempted` is valid; with `Attempted` it is a finding.
10. **Canonical IDs.** `ContentAddress(storyChecksum, kind, sorted member mention IDs, schemaVersion)`. Patches never rewrite IDs; a merge/split produces new IDs with `supersedes` links in the ledger.
11. **Claims.** `ClaimMeta` is carried inline on every `Resolved` value and edge; `ClaimLedger` is a derived, normalized index (rebuildable from the graph) used for adjudication history.
12. **Event roles.** `RelationLayers.participants` is the sole source of truth; nodes hold no role fields.
13. **Feature ownership.** Graph-derived scalars (centrality, turnover) and sensory/affect profiles are canonical JSON; vectors are sidecar rows referenced by `FeatureRef`.
14. **Summaries/themes.** Separate `DescriptorClaim` family (`Summary | Theme | Motif`) attached to segments; never situations.
15. **WOG text.** Bartlett (1932), *Remembering*, reproducing Boas (1901). Public domain. Canonical text and checksum live in `fixtures`.
16. **Gates.** Versioned in `docs/design/gates.md`; revised after five gold stories relative to annotator ceilings.
17. **Artifacts.** Every artifact carries `schemaVersion`; `codec` owns migrations; writes are atomic per file.
18. **Cats in core.** Intentional: `NonEmptyVector`, `ValidatedNec`, type-class instances are public API of `core`.
19. **Population/fMRI.** Downstream module `population`, not core.

### Part 3 (§55) — with one emphasis shift
**AMR is an adapter, not the foundation.** The `amr` module's primary object is a
minimal *propositional chart*: concept (PropBank frame **or** bare lemma — frame
optional), numbered/named roles, polarity, reentrancy, embedded propositions,
exact source alignment, with partial/underspecified charts first-class (no
frame, hedged arguments). Standards-compatible AMR via PENMAN is one decoder
into that chart; schema-constrained LLM extraction is another and is expected
to be the primary acquisition path for spoken recall and interview transcripts,
where text→AMR parsers will be weakest. Reification, UMR import/export, and
full Smatch optimization are deferred until the §82 discriminative spike shows
structure beating embeddings on role/polarity/context foils. Frame *sense*
identity is treated as weak evidence; role structure and polarity are the hard
evidence. No narrative or interview API may depend on a frame-lexicon lookup
succeeding.

1. **Build order.** Parallel tracks: the pure AMR kernel and the hand-modeled WOG ontology spike proceed simultaneously; `document` joins them.
2. **Scope.** `amr`/`document` are internal modules with clean boundaries, extractable to a standalone artifact later.
3. **PENMAN profile.** Tree preserves variables, branch order, inverse spellings, comments, metadata lines, and `~e.N` alignment markers; whitespace is not preserved. Graph preserves none of these except through provenance.
4. **Graph identity.** `Eq[AmrGraph]` is exact (node ids = tree variables). `AmrIsomorphism` is alpha-renaming + edge-order invariant. `Canonical.form` relabels nodes by deterministic DFS from top with sorted edges, so `iso(g1,g2) ⇔ form(g1) == form(g2)`.
5. **Shape typing.** `AmrGraph[Checked, R]` carries a computed `Shape` (`Connected`, `Acyclic`) as an evidence value; algorithms that need acyclicity take `Acyclic.Evidence`.
7. **ArgIndex.** Syntactic bound 0–9; semantic validity is lexicon-checked.
8. **PropBank.** A small hand-curated `FrameLexicon` for fixture frames ships in v0.1; a pinned-commit generator is M3 work.
12. **Reference ontology.** Three families: `AmrAlignment` (text↔AMR), `ExactCorefCluster[K]` (identity partition), `NarrativeReference` (`Anaphoric | Prospective | Retrospective | Summary | Partial | Bridging | Thematic`).
13. **Kinds.** `SituationNode = Event | State`; one `SituationK` marker. `StateK` is dropped.
14. **Quotient.** Materialized canonical graph + stored π; provenance stays on mentions.
21. **Parser.** v0.1 parses PENMAN only; text→AMR is a provider interface; fixtures ship hand AMRs.
22. **Oracle.** Python Penman 1.3.1 runs in a dev venv and CI job; its outputs are committed as golden files so released tests are Python-free.
24. **Smatch.** Deterministic seeded hill-climb with fixed restarts, documented as a baseline.
25. **Soft matching.** AMR compatibility is a term in `align.LocalCost`, not a competing aligner.

### Part 4 (§75)
1. **Scope.** `interview` is a sibling consumer of the same kernel, built in the same repository.
2/3. **Naming.** Story Phase vs Interview Phase; `AutobiographicalInterview*` types; `AiCompatible` only inside `interview.scoring`.
5. **Latent typing.** `EpisodeModel` nodes carry `Hypothesized`/`StructurallyDerived` status and a distinct type from `AlignmentSource`; a staged event becomes an `AlignmentSource` via the story builder.
6. **Joint inference.** v0.1: seed → candidate clusters (shared time/place/participants/event coreference) → target selection by cue compatibility + coherence → routing. EM/variational refinement is Interview Phase 3.
23/24. **Audio / population.** Separate later modules.
26. **Privacy.** Relational pseudonymization (`[PERSON_1]`, `[SISTER_OF_SPEAKER]`) at atlas stage; provider receipts mandatory; no default remote provider.
27. **Language.** English in v0.1; the atlas carries a language tag for later.

## 4. Milestones (cross-part synthesis)

| Milestone | Delivers | Exit gate |
|---|---|---|
| **M0 Foundation (this sprint)** | scaffold; `core`; AMR kernel Phase A; `story` ontology + validators + renderer; hand WOG fixture; `recall`/`align` with worked examples (Anna/cellar, WOG paraphrases); `interview` ADTs + policy projection + birthday fixture; `laws` | all difficult WOG phenomena encode with no untyped escape hatch; G0 laws green on JVM/JS/Native |
| M1 Artifacts | `codec` round-trip, receipts, graph diff, docs site, CI | exact WOG JSON round trip; G0–G1 |
| M2 Acquisition protocol | `provider-api`, stage cache, candidate ledger, LLM patch adapter (JVM), ONNX embedding adapter | malformed references rejected; cache replay exact |
| M3 Local semantics | PropBank-pinned lexicon, `document` composition from provider AMRs, projection to story nodes, contexts | G2–G4 on dev stories; §52.3 WOG gates |
| M4 Relations | sparse candidates, temporal closure/conflicts, conservative causality, state changes | G5–G6 |
| M5 Hierarchy & features | trajectory, boundary evidence, constrained segmentation, feature sidecars | G7 + flow sanity |
| M6 Alignment | full graph-HSMM + unbalanced transport, calibration, RecallReadiness, leave-story-out eval | G9; ablation ladder §14.4 |
| M7 Interview | AI Phase 1–3: compatible scorer at expert agreement, factorized external taxonomy, latent episode graph | category-specific ICC vs. raters |
| M8 Population & neural | population flow, hierarchical accessibility, fMRI regressors; release hardening | v0.1.0 |

## 5. M0 sprint plan (parallel tracks, disjoint modules)

- **core** — as specified in AGENTS.md; done first because everything depends on it.
- **amr** — PENMAN parser/printer (cats-parse), graph, validator, inversion, canonical form, isomorphism, Smatch, lexicon, alignment sidecar, Penman-oracle golden tests, ScalaCheck laws.
- **story + fixtures/wog** — ontology ADTs, graph, indexes, validators for every structural law in §32.6, text/DOT renderer, `AlignmentSource`; hand-coded WOG with the ten §27.1 phenomena and §27.2 prohibitions as tests; ten manual recall paraphrases with intended targets.
- **recall + align** — recall ADTs, local cost algebra, alignment matrix with external columns, unbalanced Sinkhorn, graph-HSMM forward–backward with typed transitions, support density, signature; Anna/cellar worked example reproduced as a test; ablation harness stub.
- **interview + fixtures/interview** — transcript atlas, detail atoms, addresses, assessments, policy projection with expected/hard counts, profile measures; birthday example reproduced as a test.
- **laws** — generators + Discipline suites collected from each track.
- **review** — fresh-context review pass per track; `sbt checkAll`.

## 6. Non-negotiables carried into every module

See AGENTS.md "Design contract". Additionally: leave-story-out evaluation
harnesses must exist before any learned component is added; every numeric
threshold is provisional and versioned; a scalar score is only ever a declared
projection of a signature vector.
