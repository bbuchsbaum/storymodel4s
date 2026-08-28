# Narrative Process Alignment: Cumulative Discussion Notes

## Record status

- **Purpose:** Preserve the complete substance and evolution of a multipart discussion, ending in a concrete plan for a library implementation.
- **Current coverage:** Parts 1–4 plus AMR reference/fit, automation, autonomous-agent, AMR-boundary, multiscale surface/feature-track, and co-authored vision/mission checkpoints.
- **Status:** Initial conceptual proposal; nothing here is yet a final implementation decision.
- **Note-taking rule:** Later parts should add dated/numbered evolution entries, record agreements and revisions explicitly, and retain superseded ideas with their rationale rather than silently rewriting history.
- **Provisional framework name:** **Narrative Process Alignment (NPA)**.

## Evolution log

### Part 1 — Initial framework

The central shift is away from treating either a story or a recall as one embedding, and away from independently matching recalled sentences to source segments by nearest-neighbour similarity. The proposed object is a structured alignment between two narrative processes:

\[
S \xrightarrow{\text{interpret}} \mathcal N,
\qquad
R_s \xrightarrow{\text{parse}} \mathcal R_s,
\qquad
P_s:\mathcal R_s \rightsquigarrow \mathcal N.
\]

- \(S\): original story or video.
- \(\mathcal N\): hierarchical, multirelational source narrative process.
- \(R_s\): subject \(s\)'s sequential recall.
- \(\mathcal R_s\): structured recall process.
- \(P_s\): soft, partial, possibly nonmonotone alignment from recall to source.

The nonfunctional arrow \(\rightsquigarrow\) is essential: recall can omit, merge, expand, reverse, distort, infer, associate, or intrude, so the alignment is neither total nor one-to-one.

Event-segmentation HMMs supply two foundations:

1. continuous narratives can be modeled as temporally extended event states, often at nested scales;
2. event-specific representations can correspond between movie viewing and spoken recall.

However, a conventional event HMM normally progresses through a fixed ordered event sequence. Unconstrained recall requires external states, skips, merging, revisitation, backward movement, nonchronological links, and uncertainty.

### Part 2 — Phase 1 product requirements for source-story construction

Part 2 narrows the immediate product to construction of a structured story model from English text. It names the working product **`story-model`**, chooses **Scala 3**, makes *The War of the Ghosts* the first acceptance fixture, and defers recall alignment plus video/audio acquisition to later phases.

It operationalizes the Part 1 source process as five coordinated, versioned representations:

\[
\mathcal S=(A,G,H,X,\Gamma),
\]

where \(A\) is the exact evidence atlas, \(G\) the typed multiplex narrative/world graph, \(H\) the abstraction hierarchy, \(X\) the multiview discourse trajectory, and \(\Gamma\) the uncertainty/provenance/alternatives/correction ledger. Build receipts and validation reports accompany this artifact.

The PRD supplies a detailed proposed architecture and development sequence. Those are recorded below as the **Part 2 proposal**, not yet the final cross-part implementation plan.

### Part 3 — AMR selected as the typed local semantic kernel

Part 3 answers a central ontology/acquisition question: **standards-compatible AMR should be the stable local propositional substrate for sentences and clauses, but it must not become the whole story graph.** AMR supplies entities, events/states, predicate–argument structure, negation, modification, and reentrancy. Cross-sentence identity, temporal/modal/epistemic structure, causality/goals, hierarchy, source evidence, and distributional views remain explicit layers above it.

The proposed stack becomes:

\[
\boxed{
\text{StoryModel}
=
\text{AMR atlas}
+
\text{document identity map}
+
\text{temporal/modal/causal layers}
+
\text{narrative hierarchy}
+
\text{distributional views}
}
\]

Part 3 also proposes a standalone typed AMR library under `story-model`, with distinct PENMAN syntax and semantic-graph representations, checked graph states, canonicalized roles, data-driven PropBank schemas, explicit source alignments, law-based testing, UMR-compatible document composition, and patch-only LLM integration.

### Part 4 — Autobiographical Interview as latent-episode alignment

Part 4 applies the framework to the traditional Autobiographical Interview (AI). Traditional “internal versus external” scoring is reinterpreted as a task-relative projection of richer structure: details align to the nominated target episode, other episodes, autobiographical/general knowledge, repeated propositions, or discourse functions.

The essential difference from story recall is source observability. Ordinary autobiographical events have no known source graph, so the system must jointly reconstruct the episode implied by the transcript and align details to it:

\[
(\widehat{\mathcal E^\star},\widehat P)
=
\arg\max_{\mathcal E,P}p(\mathcal E,P\mid R,Q).
\]

This is explicitly **not** reconstruction of historical truth. Target membership, episodic specificity, phenomenological re-experiencing, and veridicality remain separate axes. When the event was staged or prospectively recorded, the known-source alignment developed in Parts 1–3 applies directly.

### Decision checkpoint — AMR reference quality, fit, and usefulness

Primary-source review on 2026-08-28 supports retaining AMR as the local semantic kernel, with qualifications. There is a solid reference **stack**, not one complete immutable standard. The implementation should pin an explicit conformance profile combining AMR 1.2.6 semantics, a specific PropBank frame release, PENMAN syntax behavior, and declared alignment/document extensions.

AMR's highest-value contribution is hard propositional structure—frame identity, participant-role direction, negation, reentrancy, and embedded propositions—where embedding-only matching is predictably unsafe. It should sit between the exact source atlas and document/narrative composition. It should not own cross-sentence identity, story-world time, causal truth, narrative hierarchy, target-episode membership, phenomenology, or veridicality.

### Decision checkpoint — AMR authoring must be automated

The earlier proposal to hand-author WOG AMRs is revised: the researcher should not need AMR annotation expertise. Standards examples and licensed gold corpora can validate the AMR kernel. WOG should be bootstrapped through existing parsers, deterministic Scala validation, alignment, model-assisted criticism, candidate agreement, and review in ordinary narrative language.

Machine-produced WOG AMRs are **silver**, not gold. Task-level expert review can validate narrative facts, roles, contexts, and ambiguity without exposing PENMAN. A scientific claim of new gold AMR annotation still requires qualified AMR adjudication, at least on a representative sample.

### Decision checkpoint — Production must be fully autonomous

The preceding proposal for plain-language researcher review is superseded as an operational requirement. The target product must process pages or entire stories unattended. Human annotation/adjudication is permitted for offline benchmark construction, calibration, prompt development, and external scientific audit, but never as a required production stage.

Runtime uncertainty is represented automatically as accepted claims, weighted alternatives, unresolved claims, or rejected candidates. “Fully automatic” does not mean forcing one answer when evidence is inadequate; it means completing the build without human intervention while preserving uncertainty, provenance, and failure status.

### Decision checkpoint — AMR structure retained; AMR conformance moved behind an adapter

The reviewed proposal is directionally correct but changes architecture, not merely emphasis. The canonical local representation should be a provider-neutral `PropositionChart`; standards-compatible AMR/PENMAN is one adapter and schema-constrained agent extraction is another. `document`, `story`, `recall`, and `interview` should depend on the proposition contract, not directly on AMR conformance or successful PropBank frame lookup.

The acquisition claim that LLM proposition extraction will outperform AMR parsers on spoken recall is plausible but unproven and must be evaluated. The previously proposed hand-AMR spike is rejected: use existing gold standards, automatically acquired charts, plain-language expected facts, and controlled foils. Measure both oracle structural value and fully automatic acquisition value.

### Decision checkpoint — One anchored surface axis, several structures and signals

Fine-grained language, continuous descriptive signals, and higher-order narrative structure should not be forced into one graph or one segmentation. They should share an immutable ordered surface coordinate system. Sentence/clause proposition charts, canonical situations, scenes/episodes, transcript turns, and scalar or vector feature tracks all point back to exact surface support. This permits movement in both directions: words and windows can be summarized into narrative units, while scenes or events can be inspected down to their supporting words.

The current `SurfaceAtlas` already supplies the basic ordered token/sentence traversal and exact span lookup. The missing cross-cutting mechanism is a typed feature-track and window-reduction layer, plus explicit boundary evidence and discourse-to-world-time transition types. Those are now considered fundamental inputs to narrative induction, not merely optional post-hoc analytics. A resolved hierarchy remains a derived interpretation; the raw feature tracks, graph-change tracks, boundary beliefs, and alternative temporal interpretations must remain independently inspectable.

---

## 1. Core abstraction and commitments

At the highest level, recall is a:

> **partial, probabilistic, open-world, lax morphism from a recall graph into a hierarchical narrative process graph.**

- **Partial:** source events can be omitted; recalled material can be external.
- **Probabilistic:** references can be vague, ambiguous, blended, or uncertain.
- **Open-world:** associations, evaluations, commentary, and intrusions need explicit destinations.
- **Lax:** temporal, causal, semantic, and sensory relations need only be approximately preserved.
- **Hierarchical:** gist and detail are mappings at different scales, not competing notions of correctness.
- **Process-valued:** recall includes the route through the remembered world, not only the items mentioned.

The clean summary is:

\[
\boxed{
\text{recall quality}
=
\text{content preservation}
+
\text{relation preservation}
+
\text{source coverage}
-
\text{distortion}
-
\text{unexplained external mass}
}
\]

These components should remain separate in the primary representation. Any scalar score is a later, task-declared projection.

---

## 2. Source representation: a process graph, not one embedding

The source must simultaneously preserve:

1. continuous stimulus flow;
2. event-like states at multiple scales;
3. nonsequential relations among events.

A proposed source object is:

\[
\mathcal N =
\left(
X(t),
V,
H,
\{A^{(r)}\}_{r\in\mathcal K},
\Phi,
U
\right).
\]

The initial proposal names all components but fully elaborates mainly \(X(t)\), the event hierarchy/support \((V,H)\), relation layers \(A^{(r)}\), node/state features, and uncertainty/provenance. The precise responsibilities of \(\Phi\) and \(U\) remain to be made explicit during library design.

### 2.1 Continuous multiview feature field

At each source time, retain:

\[
X(t)=
\left[
X_{\text{semantic}}(t),
X_{\text{visual}}(t),
X_{\text{auditory}}(t),
X_{\text{entity}}(t),
X_{\text{affective}}(t),
X_{\text{predictive}}(t)
\right].
\]

For movies, possible inputs include visual action, faces, objects, scene, motion, dialogue, environmental sound, music, and prosody. For narrated stories, sensory channels may be inferred from language rather than observed directly.

The continuous layer prevents segmentation from erasing slow mood changes, rising tension, gradual spatial motion, musical transitions, and similar trajectories.

### 2.2 Hierarchical, soft event system

Proposed levels:

\[
\text{atomic propositions}
\subset
\text{micro-events}
\subset
\text{events}
\subset
\text{scenes}
\subset
\text{episodes or arcs}.
\]

Examples of scale-sensitive alignment:

- “He picked up the key” can map to a leaf event.
- “He searched the apartment” can map to a parent event.
- “The first half was about him trying to escape” can map to an episode or goal arc.

Every event \(v\) has a soft source-time support:

\[
h_v(t)\in[0,1].
\]

This supports fuzzy boundaries, overlapping events, and discontinuous support for montage, intercutting, repeated motifs, or arcs revisited at multiple times.

The source is fixed, but its decomposition is not uniquely given. Prefer boundary probabilities or a small ensemble:

\[
p(\mathcal N\mid S)
\]

over treating one segmentation as ontological truth. Prior naturalistic-narrative work motivates nested event structures, and geometric work suggests recall can be viewed as a transformed trajectory through semantic event space. Exact references still need to be collected.

### 2.3 Events as state transitions

An event is more than a sentence embedding:

\[
e_v=(s_v^-,a_v,s_v^+),
\]

where \(s_v^-\) is the preceding situation, \(a_v\) the action/occurrence/revelation/mental change, and \(s_v^+\) the resulting situation.

A situation state can include:

\[
s_v=(
\text{entities},
\text{locations},
\text{properties},
\text{goals},
\text{beliefs},
\text{relationships},
\text{affect},
\text{sensory context}
).
\]

This state-space form must be able to distinguish:

- correct event but wrong actor;
- correct action but wrong location;
- correct outcome with omitted cause;
- narrator fact versus character belief;
- actual versus imagined, hypothetical, remembered, or deceptive events.

### 2.4 Multiplex directed relations

Do not collapse narrative structure into one adjacency matrix. Use a common node set with directed relation layers:

\[
\mathcal K=\{
\text{discourse},
\text{world-time},
\text{causal},
\text{goal},
\text{entity},
\text{spatial},
\text{semantic},
\text{sensory}
\}.
\]

Interpretations:

- **Discourse succession:** what was shown or narrated next.
- **Story-world chronology:** what happened next in fictional/real-world time.
- **Causation:** causes, enables, prevents, terminates.
- **Goal structure:** subgoal, attempt, success, failure.
- **Entity continuity:** shared people or objects.
- **Spatial continuity:** same place or movement between places.
- **Semantic/thematic relations:** analogy and motif.
- **Sensory continuity:** gradual visual/auditory flow versus abrupt change.

AMR-like predicate–argument structure is one ingredient. Richer Event Description and CaTeRS are cited as precedents for jointly representing event coreference, subevents, temporal links, and multiple causal relations. Exact bibliographic records remain to be added.

Causal edges require provenance/status:

\[
\text{status}\in\{
\text{explicitly stated},
\text{strongly entailed},
\text{plausibly inferred},
\text{model conjecture}
\}.
\]

A story rarely determines one unique structural causal model. A causal-explanatory interpretation must not be mislabeled as an identified causal data-generating model.

---

## 3. Three distinct clocks

Narrative recall has at least three time axes:

\[
t_D=\text{discourse/presentation time},
\qquad
t_W=\text{story-world time},
\qquad
u=\text{recall time}.
\]

This separation handles flashbacks and other nonlinear presentation. A recall may preserve fictional chronology while ignoring presentation order, or preserve presentation order while misunderstanding story-world chronology.

“Misordering” therefore needs multiple measures:

- reversal relative to discourse/presentation order;
- reversal relative to story-world chronology;
- violation of causal order;
- long but coherent thematic jump;
- return to an earlier event to supply omitted detail.

These must not be collapsed into one ordering error.

---

## 4. Perfect recall is reporting-channel-relative

Define a task/channel-specific quotient:

\[
\mathcal N_c=Q_c(\mathcal N),
\]

where \(c\) could be free verbal recall, verbatim verbal recall, drawing, reenactment, recognition, or scene ordering.

For free verbal recall of a movie, the quotient should preserve reasonably reportable distinctions:

- event identities;
- participants and semantic roles;
- actions and outcomes;
- locations;
- goals and causal links;
- chronology;
- reportable sensory attributes such as “red car,” “dark room,” or “loud explosion.”

It should not require preservation of every pixel, texture, or frame-level value. Rote recall of a spoken story can additionally retain lexical and syntactic layers, allowing near-verbatim correctness at surface and narrative levels.

Therefore:

> Perfect recall is near-identity with the task-appropriate quotient of the source representation, not identity with the raw stimulus.

This is consistent with high-level event correspondence between movie viewing and linguistic recall despite weak correspondence in low-level sensory cortex. The supporting literature needs formal citation.

---

## 5. Constructing the source process

### Stage A — Atomic anchors

- Verbal story: timestamped clauses and propositions.
- Movie: short overlapping windows, shot boundaries, aligned dialogue, audio-description-like captions, entity tracks, and scene-change evidence.
- Shots are anchors, not assumed narrative event units.

### Stage B — Multiview extraction per anchor

Attach:

- dense semantic representation;
- predicate–argument structure;
- entities and coreference;
- action and location descriptions;
- recoverable goals, beliefs, and affect;
- visual and auditory features;
- explicit temporal and causal language;
- uncertainty and epistemic status.

Dense embeddings are retrieval tools, not final semantic judgments. They can miss actor–patient reversals, negation, modality, causal direction, and other structurally decisive differences.

### Stage C — Multiscale soft boundaries

Possible boundary evidence:

- entity changes;
- location changes;
- goal or active-plan changes;
- semantic surprise;
- visual/auditory change;
- prediction error or uncertainty;
- human boundary judgments;
- LLM-generated boundary proposals.

LLM-derived boundaries may approximate human consensus, and segment-level maximum embedding similarity is a scalable gist-scoring baseline. However, the referenced published approach explicitly lacks fine-grained detail analysis and chiefly uses maximum segment similarities. It should be a proposal/baseline component, not the source hierarchy itself. The hierarchy should integrate several signals.

### Stage D — Relation construction

Near-deterministic edges:

- presentation order;
- interval overlap;
- entity continuity;
- parent-event containment.

Inferred edges:

- story-world chronology;
- enabling conditions;
- causal explanation;
- goals and failures;
- thematic parallels.

Each inferred edge stores confidence and source evidence.

### Stage E — Uncertainty preservation

Approximate \(p(\mathcal N\mid S)\), for example by storing:

- alternative boundary analyses;
- edge confidence;
- acceptable abstraction levels;
- provenance for every machine-generated proposition.

The goal is to propagate uncertainty in source interpretation into alignment uncertainty.

---

## 6. Recall as its own structured process

Represent subject \(s\)'s recall as:

\[
\mathcal R_s=(V_s^R,\{B_s^{(r)}\},\Psi_s).
\]

The initial proposal names these components but leaves their exact library interfaces open. Nodes should normally be propositions or idea units, not sentences: one sentence may mention several events; several sentences may elaborate one event.

Recall relations/annotations include:

- sequential chain edge between recall units;
- explicit temporal edges from “before,” “after,” “then,” “while”;
- causal edges from “because,” “so,” and explanation;
- entity-coreference edges;
- summary/elaboration parent–child relations;
- discourse-function labels.

### 6.1 Factor source anchoring from discourse function

For each recall unit:

\[
r_i=(z_i,q_i,\psi_i,\kappa_i),
\]

where:

- \(z_i\): source anchor or distribution over anchors;
- \(q_i\): discourse function;
- \(\psi_i\): propositional content;
- \(\kappa_i\): expressed uncertainty/confidence.

Candidate discourse functions:

\[
q_i\in\{
\text{episodic assertion},
\text{summary},
\text{inference},
\text{association},
\text{evaluation},
\text{source-monitoring},
\text{task commentary}
\}.
\]

The “Stephen King” case illustrates the separation: “It was kind of like a Stephen King story” is neither simply irrelevant nor necessarily an intrusion. It can have a global tone/genre/threat-theme anchor, `association` discourse function, external referent “Stephen King,” and little/no event coverage.

Allow partial grounding:

\[
\alpha_i\in[0,1],
\]

because one unit may combine source-grounded content with external elaboration.

---

## 7. Alignment: partial probabilistic graph morphism

For \(M_s\) recall units and \(K\) possible source event/abstraction nodes:

\[
P_s\in\mathbb R_+^{M_s\times K},
\]

with \((P_s)_{iv}\) the degree/posterior mass mapping recall unit \(i\) to source node \(v\).

Add explicit external columns/states for:

- association;
- commentary;
- unsupported intrusion;
- uninterpretable speech.

Rows need not assign all mass to source nodes, and source columns need not be visited: this is an **unbalanced** alignment.

### 7.1 Hybrid local content cost

\[
C_{iv}=
w_{\mathrm{sem}}d_{\mathrm{sem}}
+w_{\mathrm{prop}}d_{\mathrm{prop}}
+w_{\mathrm{entity}}d_{\mathrm{entity}}
+w_{\mathrm{sens}}d_{\mathrm{sens}}
+w_{\mathrm{gran}}d_{\mathrm{gran}}
+w_{\mathrm{contra}}c_{\mathrm{contra}}.
\]

- \(d_{\mathrm{sem}}\): dense semantic distance.
- \(d_{\mathrm{prop}}\): predicate–argument compatibility.
- \(d_{\mathrm{entity}}\): actor/object/referent correspondence.
- \(d_{\mathrm{sens}}\): visual/auditory detail correspondence.
- \(d_{\mathrm{gran}}\): hierarchy/granularity compatibility.
- \(c_{\mathrm{contra}}\): negation, role reversal, incompatible outcomes, and other contradictions.

Embeddings retrieve candidates; structured comparison adjudicates them.

### 7.2 Relation-preserving global objective

For source relation matrix \(A_{\mathcal N}^{(r)}\) and recall relation matrix \(B_s^{(r)}\), the source relation induced among recall units is:

\[
P_sA_{\mathcal N}^{(r)}P_s^\top.
\]

Proposed objective:

\[
\begin{aligned}
\mathcal L(P_s)
={}&
\underbrace{\langle P_s,C_s\rangle}_{\text{local content}}
\\
&+
\underbrace{\sum_r\lambda_r
D_r\!\left(B_s^{(r)},P_sA_{\mathcal N}^{(r)}P_s^\top\right)}_{\text{relation preservation}}
\\
&+
\underbrace{\rho_R D(P_s\mathbf 1,a_s)
+\rho_S D(P_s^\top\mathbf 1,b)}_{\text{external recall and omitted source mass}}
\\
&-
\underbrace{\epsilon H(P_s)}_{\text{soft uncertainty}}.
\end{aligned}
\]

Interpretation:

- local recall content should match source content;
- recalled relations should approximate source relations;
- recalled units need not all be source-grounded;
- source events need not all be recalled;
- ambiguity remains probabilistic instead of being forced to a hard match.

Fused Gromov–Wasserstein motivates joint feature/relational matching; unbalanced optimal transport motivates unequal/missing mass.

### 7.3 Why vanilla FGW is not the final algorithm

Vanilla FGW is insufficient because:

- narrative time and causality are directed;
- relation types should not collapse into one symmetric distance;
- recall has a privileged sequential chain;
- dense graph matching can be expensive;
- desired outputs include explicit transition probabilities and interpretable backtracking.

For recall order, introduce transition flow \(F_{i,v,w}\): posterior mass moving from source state \(v\) at recall unit \(i\) to source state \(w\) at unit \(i+1\), constrained by:

\[
F_i\mathbf1=P_{i\cdot},
\qquad
F_i^\top\mathbf1=P_{i+1,\cdot}.
\]

Add transition cost:

\[
\sum_{i,v,w}F_{i,v,w}T(v,w),
\]

where:

\[
T(v,w)=
\theta_DT_D(v,w)
+\theta_WT_W(v,w)
+\theta_CT_C(v,w)
+\theta_ST_S(v,w).
\]

The four terms encode discourse order, story-world order, causal connectivity, and semantic connectivity.

Computationally, this can be a sparse graph-structured HMM or semi-Markov model; conceptually it is an unbalanced transport flow. It permits:

- self-transitions;
- skipped source events;
- forward jumps;
- backward returns;
- thematic jumps;
- causally connected but nonchronological transitions;
- external states.

---

## 8. Per-unit alignment output and recall trajectories

Each recall unit induces a source-time density:

\[
a_i(t)=\sum_vP_{iv}h_v(t).
\]

Interpretation:

- sharp, unimodal: highly localizable recall;
- broad: summary or vague reference;
- separated peaks: ambiguity or blending;
- little source mass: commentary, association, or intrusion.

Maintain distinct distributions for presentation and story-world time:

\[
a_i^D(t_D),
\qquad
a_i^W(t_W).
\]

The recall sequence becomes a probabilistic source trajectory:

\[
a_1(t),a_2(t),\ldots,a_{M_s}(t),
\]

supporting measures of recall velocity, compression, leaps, reversals, dwell time, and return.

---

## 9. Phenomena represented by the model

| Recall phenomenon | Proposed representation |
|---|---|
| Precise detail | Sharp posterior on a leaf event; low proposition and attribute cost |
| Correct gist | Posterior on a parent event or broad mass over descendants |
| Vagueness | High posterior entropy and broad source-time support without contradiction |
| Omission | Little/no aggregate alignment mass for a source node |
| Elaboration | Multiple recall units revisit the same source event |
| Compression | One recall unit maps to a high-level node or several events |
| Misordering | Good event matches with backward/otherwise violating transition flow |
| Blending | One recall unit splits mass across two or more, possibly noncontiguous, events |
| Distortion | Strong event-identity match but wrong actor, object, location, causal role, or outcome |
| True incidental detail | Sharp match to a low-centrality leaf: high fidelity, low importance |
| External association | Global thematic anchor plus association-mode external payload |
| Intrusion | Asserted episodic content with high external mass and no credible source anchor |
| Source-consistent inference | Proposition supported by causal/state graph but not explicit source evidence |
| Sensory impoverishment | Good semantic/event match with weak visual/auditory preservation |

Important distinctions:

- vague but correct summary is not error;
- highly specific false detail is more than “low similarity”;
- backward transition does not imply failure to recall the involved events;
- rare accurate detail remains correct even if other people omit it.

---

## 10. Salience and truth must remain separate

Potential event-level quantities:

- source duration;
- narrative centrality;
- causal centrality;
- semantic connectedness;
- perceptual distinctiveness;
- population memorability;
- task relevance.

None should determine whether an exact content match is accepted. A once-mentioned obscure object can be recalled precisely: high alignment likelihood, perhaps low weight in an importance-weighted summary.

Report at least:

\[
\text{uniform coverage}
\qquad\text{and}\qquad
\text{importance-weighted coverage}.
\]

Network centrality may predict naturalistic-event memorability, but accessibility is not correctness. Supporting evidence needs a formal citation.

---

## 11. Multi-subject/population model

Once recalls share source coordinates, define subject/event fuzzy visitation:

\[
Y_{sv}=1-\exp\left(-\sum_iP_{siv}\right).
\]

Hierarchical accessibility model:

\[
\operatorname{logit}E[Y_{sv}]
=
\alpha_v+x_s^\top\beta+u_s,
\]

where \(\alpha_v\) is event memorability, \(x_s\) contains subject/condition/neural predictors, and \(u_s\) is a subject effect.

Subject-specific source transition model:

\[
p_s(w\mid v)\propto
\exp\left[
\theta_{s,D}K_D(v,w)
+\theta_{s,C}K_C(v,w)
+\theta_{s,S}K_S(v,w)
-\theta_{s,J}J(v,w)
\right].
\]

This yields interpretable person-level parameters for:

- dependence on source chronology;
- dependence on causal links;
- semantic/thematic clustering;
- long-jump willingness;
- backtracking;
- compression;
- detail fidelity;
- intrusion tendency.

Population recall flow graph:

\[
\bar F_{vw}=\sum_s\sum_iF_{s,i,v,w}.
\]

This graph describes how the story is reconstructed:

- retrieval hubs;
- surviving causal edges;
- systematic skips;
- summaries replacing details;
- semantic shortcuts dominating chronology.

The source graph remains the reference; population recall flow is its learned deformation, not a replacement.

---

## 12. Output: a recall signature, not one score

Proposed subject signature:

\[
\mathbf m_s=
\begin{bmatrix}
\text{coverage}\\
\text{fidelity}\\
\text{specificity}\\
\text{compression}\\
\text{discourse chronology}\\
\text{world chronology}\\
\text{causal preservation}\\
\text{semantic-flow coherence}\\
\text{sensory reinstatement}\\
\text{association mass}\\
\text{intrusion mass}
\end{bmatrix}.
\]

### 12.1 Localizability

Entropy-based form:

\[
L_i=1-\frac{H(P_{i\cdot})}{\log K}.
\]

Also report the temporal width/duration of \(a_i(t)\), because a confidently matched high-level event can be temporally broad without being ambiguous.

### 12.2 Fidelity conditional on alignment

After inferring the intended event, assess actor, action, object, location, goal, and outcome correctness separately.

### 12.3 Chronological deformation

Report:

- expected backward-transition mass;
- jump-size distribution;
- discourse-time order preservation;
- story-world order preservation.

### 12.4 Causal preservation

Assess retention of source causal, enabling, and goal relations even when the corresponding recalled events are not consecutive in recall speech.

### 12.5 Compression

Estimate source support per recall unit and preferred hierarchy level.

### 12.6 Constructive content

Separate source-consistent inference, thematic association, evaluation, and unsupported intrusion. Constructive content is not automatically error.

A single score is permitted for a declared clinical/predictive objective, but it must be an explicit learned projection of this vector rather than the primary representation.

---

## 13. Worked example

Source events:

1. Anna arrives at an isolated house.
2. She hears a scream from the cellar.
3. She searches upstairs.
4. She enters the cellar.
5. She finds her injured brother.

Recall:

> “A woman goes into this creepy old house. It felt kind of like a Stephen King story. She eventually finds somebody downstairs. Before that there was some kind of noise, I think.”

Expected analysis:

- **“A woman goes into this creepy old house”** — strong but coarse event-1 anchor; “creepy” may additionally map to affect/visual tone.
- **“It felt kind of like a Stephen King story”** — association function; global horror/threat-theme anchor; external “Stephen King” payload; no event-coverage credit.
- **“She eventually finds somebody downstairs”** — strong event-5 anchor; correct action/location; reduced participant specificity because “brother” is omitted.
- **“Before that there was some kind of noise, I think”** — probabilistic event-2 anchor; explicit uncertainty; broad sensory description; sufficiently sharp temporal reference, but produced after event 5 and therefore a backward recall transition.

No clause needs one undifferentiated correct/incorrect label.

---

## 14. Restrained first implementation

This is a proposed proving version, not yet the final library plan.

### 14.1 Source representation

Begin with three levels:

1. proposition/micro-event;
2. event/scene;
3. episode/goal arc.

Begin with four relation layers:

1. discourse order;
2. story-world time;
3. causal/enabling relation;
4. entity continuity.

Initially keep semantic and sensory features as node attributes rather than extra relation machinery.

### 14.2 Recall representation

Segment into propositions and annotate/infer:

- discourse function;
- uncertainty;
- entities and semantic roles;
- explicit temporal/causal connectives;
- sensory language.

### 14.3 Three-stage alignment

1. **Candidate retrieval:** dense embeddings retrieve a small candidate set across hierarchy levels.
2. **Structured reranking:** compare propositions, semantic roles, entities, contradiction, and sensory attributes.
3. **Global inference:** sparse graph-HSMM or dynamic unbalanced transport with external states, skips, backtracking, and hierarchy-level transitions.

Global trajectory context should resolve ambiguous units better than independent matching.

### 14.4 Human validation/gold annotation

Annotators must be allowed to mark:

- multiple acceptable source spans;
- hierarchy level;
- partial source grounding;
- discourse function;
- role/detail correctness;
- confidence.

Evaluation must be **leave-story-out**, not merely leave-subject-out, to test generalization beyond story-specific wording.

Synthetic/adversarial validation cases:

- inserted omission;
- event reordering;
- paraphrase;
- summary compression;
- actor–patient reversal;
- negation;
- blend of two events;
- external association;
- plausible but unsupported inference.

Required ablation sequence:

\[
\text{content only}
\rightarrow +\text{hierarchy}
\rightarrow +\text{order}
\rightarrow +\text{causality}
\rightarrow +\text{open-world states}
\rightarrow +\text{sensory channels}.
\]

Candidate proving datasets:

- **Naturalistic Free Recall:** hundreds of recalls of four spoken narratives.
- **FilmFestival:** timestamped recalls and fMRI from participants who watched and recalled ten short films.

Dataset citations, licenses, schemas, access routes, and exact sizes remain to be verified before implementation planning.

---

## 15. Neural-data use

For fMRI recall, posterior alignment gives a soft source-event design. For event \(v\):

\[
z_{sv}(u_i)=P_{siv}.
\]

After controlling speech production and acoustic features, possible tests include:

- event-specific neural reinstatement;
- sensory versus semantic reinstatement;
- neural signatures of compression;
- backward jumps and returns;
- causal versus chronological transitions;
- source-consistent inference versus literal retrieval;
- uncertainty and low-localizability states.

Posterior regressors propagate uncertainty rather than pretending every recalled sentence has one unquestionably correct scene.

Behavioral output alone cannot identify why an event is absent: it may be unavailable, inaccessible at that moment, deliberately withheld, or simply not verbalized. Recognition, confidence, eye movements, and neural reinstatement may distinguish these latent causes. The alignment describes **recall output**, not memory availability itself.

---

## 16. Scientific and engineering constraints already implied

These are direct consequences of Part 1 and should constrain the eventual plan:

1. **No single-vector core model.** Embeddings can retrieve candidates but cannot define correctness.
2. **No forced one-to-one or monotone matching.** The data model must permit omission, repetition, compression, blending, revisitation, and external content.
3. **Directed typed relations are first-class.** Discourse time, world time, causality, entity continuity, and later relation types cannot be merged silently.
4. **Hierarchy is first-class.** A gist match to a parent is not a failed detail match.
5. **Uncertainty and provenance are stored, not discarded.** This applies to segmentation, propositions, edges, alignments, and externally generated annotations.
6. **Grounding and discourse function are separate.** Association/evaluation/commentary are not automatically intrusion.
7. **Truth and salience are separate.** Importance weighting must not alter match correctness.
8. **Recall output is not memory availability.** The library should not overclaim latent cognitive causes from omission alone.
9. **Primary output is decomposed.** Coverage, fidelity, temporal deformation, causality, sensory retention, association, and intrusion remain separately accessible.
10. **Validation is structural and adversarial.** Similarity-only benchmarks are inadequate; role reversal, negation, reordering, blending, and unsupported inference must be tested.
11. **Generalization is across stories.** Leave-story-out evaluation is a core requirement.
12. **The first build should preserve extensibility without implementing the full ontology.** Three hierarchy levels, four relations, attribute-based semantic/sensory views, and sparse global inference are the initial target.

---

## 17. Open items for later discussion and final planning

These are unresolved or underspecified in Part 1, not criticisms of the framework:

- Define precise meanings/interfaces for \(\Phi\), \(U\), \(\Psi_s\), source/recall masses \(a_s,b\), and divergences \(D,D_r\).
- Decide whether uncertainty is represented by ensembles, probabilistic fields, confidence-weighted edges, or a combination.
- Specify how hierarchy containment, overlapping support, and discontinuous support coexist mathematically and in storage.
- Decide which relations share a generic interface and which deserve specialized types/algorithms.
- Define legal constraints and normalization of \(P\) and \(F\), including external-state mass.
- Choose the first global inference formulation: graph-HMM, HSMM, dynamic unbalanced OT, or a staged hybrid.
- Determine training regime: fully supervised, weakly supervised, modular pretrained components, or probabilistic calibration over heuristic costs.
- Define the channel quotient \(Q_c\) as data/configuration/API rather than only a theoretical object.
- Decide how proposition/state extraction is supplied: library-native, pluggable NLP backends, imported annotations, or all three.
- Make modality/epistemic status and narrator-versus-character belief concrete.
- Define contradiction and source-consistent-inference rules without treating model conjecture as source fact.
- Establish serialization, interchange, provenance, versioning, and reproducibility requirements.
- Select target language/runtime and numerical/graph libraries when forming the implementation plan.
- Verify all literature claims, dataset facts, schemas, licenses, and baselines before turning them into requirements.
- Define gold annotation schema and inter-annotator agreement measures for multi-span, multilevel, partial grounding.
- Determine calibration and evaluation metrics for posterior alignment, event coverage, role/detail fidelity, order, relation retention, and external states.
- Determine computational targets and sparse scaling strategy for long stories and many subjects.
- Clarify whether population models and fMRI design generation belong in the core library, optional modules, or downstream packages.

---

## 18. Evidence/provenance queue from Part 1

Part 1 refers to the following research/method families without full citations. Preserve them for later verification:

- event-segmentation HMMs and nested event structure;
- correspondence of event representations across movie viewing and spoken recall;
- geometric/trajectory analyses of recall in semantic event space;
- AMR;
- Richer Event Description;
- CaTeRS;
- LLM-generated story boundaries approximating human consensus;
- segment-level maximum-embedding-similarity gist scoring and its stated lack of fine-grained detail analysis;
- fused Gromov–Wasserstein matching;
- unbalanced optimal transport;
- network centrality as a predictor of naturalistic event memorability;
- Naturalistic Free Recall dataset;
- FilmFestival dataset.

No literature or dataset claim in these notes has yet been independently verified.

---

# Part 2 Detailed Record: PRD for `story-model`

## 19. Product identity, scope, and executive contract

**PRD title:** Narrative Model Builder — Phase 1: Constructing a Structured Story Representation from Text  
**Status:** Draft for implementation  
**Working name:** `story-model`  
**Language/runtime:** Scala 3/JVM, with likely Scala.js-compatible review surfaces  
**First reference story:** *The War of the Ghosts* (WOG)  
**Primary future consumer:** sequential-recall alignment  
**Phase boundary:** text source construction only; participant-recall alignment and video/audio are later.

The system must not return only an embedding, sentence-embedding sequence, event list, or untyped graph. It returns:

\[
\boxed{\mathcal S=(A,G,H,X,\Gamma)}
\]

- **\(A\), anchored surface atlas:** immutable text plus exact paragraphs, sentences, clauses, tokens, mentions, and offsets.
- **\(G\), typed multiplex graph:** entities, situations, context frames, segments, and typed sparse relations.
- **\(H\), multiscale hierarchy:** atomic situations, scenes, episodes, story root, and eventually overlapping arcs.
- **\(X\), multiview trajectory:** semantic, sensory, affective, entity, location, context, and goal flow in discourse order.
- **\(\Gamma\), claim ledger:** evidence, scores, calibrated probabilities when justified, status, provenance, alternatives, proposals, and human corrections.
- **Accompanying artifacts:** content-addressed build receipt and deterministic validation report.

The source text is fixed; the model is an interpretation. Ambiguity must survive rather than being replaced by false precision.

The representation must let a future recall aligner ask:

1. Which source situation/segment could a statement denote?
2. At what abstraction level?
3. Which exact source spans support the interpretation?
4. Which entities, actions, states, temporal relations, and causal relations participate?
5. How certain is the source-side interpretation?
6. Which semantic, sensory, and affective features characterize the region?
7. How did the discourse arrive there, and what changed at boundaries?

### 19.1 Product objective and intended uses

Create a Scala 3 library and CLI accepting story text and producing a validated `StoryModel` for:

- proposition/event recall alignment;
- gist/summary alignment;
- chronology and causal-order analysis;
- omission, compression, blending, and misordering detection;
- semantic and sensory reinstatement analysis;
- population memory modeling;
- later behavioral and neural time-series linkage.

### 19.2 Product principles

| Principle | Required consequence |
|---|---|
| Evidence first | Every explicit claim cites exact source spans. |
| Typed, not stringly typed | Temporal, causal, participant, context, and hierarchy relations are Scala ADTs. |
| Multiplex | Temporal, causal, semantic, entity, state, and hierarchy relations remain distinguishable. |
| Multiscale | Situations, scenes, episodes, and whole-story summaries coexist. |
| Uncertainty-preserving | Unknown, ambiguous, and alternative interpretations are legitimate. |
| Provider-neutral | Rules, local/remote models, LLMs, and human imports share acquisition algebras. |
| Reproducible | Inputs, configs, code/models, prompts/schemas, and outputs are content-addressed and receipted. |
| Human-revisable | Accept/reject/merge/split/revise operations preserve the machine proposal and provenance. |
| Sparse/scalable | Core algorithms/artifacts do not require dense all-pairs event graphs. |
| Downstream-oriented | Every field must clarify or improve later recall alignment. |

### 19.3 Explicit Phase 1 non-goals

- participant recall alignment;
- video/audio processing;
- uniquely correct philosophical story causation;
- complete executable world simulation;
- exhaustive commonsense implications;
- training a foundation model;
- requiring a graph database;
- one story quality/importance score;
- calling uncalibrated LLM confidence a probability.

### 19.4 Users

- **Researcher:** inspectable structured representation, exact localization, diagnostics.
- **Representation developer:** replace extractors, encoders, hierarchy builders, or causal classifiers without changing core data types.
- **Human adjudicator:** inspect evidence and proposals, then accept/reject/merge/split/revise while preserving history.
- **Recall aligner:** retrieve multilevel nodes, spans, features, and sparse typed relation views.

### 19.5 Scientific precedents

The PRD borrows ideas, not internal APIs, from:

- **AMR:** rooted labeled sentence-meaning graphs abstracting from syntax, but insufficient for cross-sentence identity, hierarchy, chronology, contexts, and uncertainty;
- **RED:** combined entities, event coreference, time, causation, and subevents;
- **CaTeRS:** temporal/causal distinctions for short stories;
- **Story Commonsense:** motivations and character mental states;
- **NarrativeTime:** dense timeline annotation rather than sparse isolated temporal links.

These claims and exact citations remain in the Part 1 evidence-verification queue.

---

## 20. Representation contract

Required layers and acquisition modes:

| Layer | Required content | Acquisition |
|---|---|---|
| Source atlas \(A\) | Text, stable offsets, paragraphs, sentences, clauses, mentions | Deterministic |
| Entities | People/groups/objects/locations, aliases, mentions, context-scoped attributes | Rules + structured extraction + global resolution |
| Situations | Events/states, predicates, roles, polarity, modality, aspect, context | Local/global structured extraction |
| Context frames | Narration, speech, belief, thought, desire, hypothetical, memory, dream | Cues + global inference |
| Relation graph \(G\) | Participant, temporal, causal, goal, state-change, reference, containment | Derived + classified/inferred |
| Hierarchy \(H\) | Situations, scenes, episodes, root; later goal/theme arcs | Boundary evidence + constrained segmentation |
| Trajectory \(X\) | Ordered semantic/sensory/affective/entity/location/context state | Encoders + graph-derived state |
| Claim ledger \(\Gamma\) | Evidence, raw/calibrated credence, status, provenance, alternatives | Every stage |
| Build receipt | Source/config/code/provider/prompt/schema/stage hashes | Deterministic |
| Validation report | Violations, benchmark diagnostics, warnings, gate status | Deterministic validators |

---

## 21. Exact source atlas

### 21.1 Text and offsets

`TextSpan(startUtf16, endExclusiveUtf16)` is:

- zero-based;
- half-open;
- measured in UTF-16 code units;
- interpreted only against stored canonical text.

UTF-16 is an explicit JVM/JavaScript interoperability choice. The artifact also stores:

- raw-source and canonical-text checksums;
- optional raw-to-canonical offset mapping;
- language tag;
- title/user metadata;
- known source-license metadata.

### 21.2 Surface units

`SurfaceUnitKind` has `Paragraph`, `Sentence`, `Clause`, and `Token`. A `SurfaceUnit` has typed ID, kind, span, ordinal, and optional parent ID.

A clause is only an extraction anchor. It may express multiple events, a state, a reference, a summary, reported/hypothetical/intended content, or no important situation.

### 21.3 Discontinuous support

`SpanSet` is a nonempty collection of `SpanRef`s. It supports repeated/discontinuous evidence: repeated battle references, names plus later pronouns, summaries at multiple locations, and recurring motifs.

---

## 22. Narrative graph domain model

### 22.1 Four P0 node families

#### Entity

`EntityNode` contains typed ID, resolved canonical label/type, nonempty mention IDs, and resolved attributes. Entity types/attributes can be context-scoped. A group described first as canoe warriors and later as ghosts in a character's belief need not become unrelated entities.

#### Situation

`SituationNode` is `Event(EventNode)` or `State(StateNode)`.

- Events include actions, occurrences, transitions, perceptions, speech acts, and mental acts.
- States hold over intervals: fog, lacking arrows, not feeling sick, being dead, etc.

#### Segment

`SegmentNode` contains ID, `SegmentKind`, integer level, resolved canonical summary, and `SpanSet`. It represents scenes, episodes, or other composite narrative units.

#### Context frame

`ContextKind` includes:

- `NarratedWorld`;
- `Speech(speaker)`;
- `Belief(holder)`;
- `Desire(holder)`;
- `Hypothetical`;
- `Counterfactual`;
- `Memory(holder)`;
- `Dream(holder)`.

`ContextFrame` has ID, optional parent, kind, and support. The root is deliberately **NarratedWorld**, not “objective reality”: it means what the narrative presents in its main world.

### 22.2 Event/state structure

\[
e=(\text{predicate},\text{roles},\text{context},\text{polarity},\text{modality},\text{aspect},\text{support},\Delta_e).
\]

An `EventNode` has predicate, canonical description, context, polarity, modality, optional aspect, support, and resolved state changes. A `StateNode` has the analogous core fields without event aspect/state-change payload.

`Predicate` contains normalized lemma/concept label, optional external frame IDs, a readable gloss, and optional provider-ontology references. PropBank, VerbNet, AMR, etc. annotate internal concepts; none defines internal identity or is required.

### 22.3 Compact participant roles

Core `ParticipantRole` cases:

`Agent`, `Patient`, `Theme`, `Experiencer`, `Stimulus`, `Instrument`, `Beneficiary`, `Source`, `Destination`, `Location`, `Time`, `Manner`, `Cause`, `Result`, plus `Custom(namespace,label)` for imported ontologies.

A `ParticipantEdge` connects a situation to an entity with one role and claim metadata.

---

## 23. Sparse typed relation layers

`RelationLayers` is a record of typed vectors—participants, temporal, causal, goals, state changes, containment, and references—not generic string triples.

### 23.1 Discourse versus story-world time

Discourse order derives from source mentions. Canonical situations may have several discourse mentions, so positions attach primarily to mentions and only secondarily as node summaries.

Story-world time is a context-scoped partial graph. `TemporalRelation` includes:

- `Before`, `Meets`, `Overlaps`, `During`, `Contains`, `Starts`, `Finishes`, `Equal`, `Unclear`.

`TemporalEdge(from, relation, to, context, meta)` is authoritative. A later real coordinate \(\tau_e\in\mathbb R\) is only a constraint layout/visualization.

Required rules:

- accepted strict `Before` is acyclic within each context;
- closure is derived and distinct from extracted evidence;
- inverse/symmetry rules are validated;
- context-scoped statements do not become root-world facts.

### 23.2 Causality

`CausalRelation` is `Causes`, `Enables`, `Prevents`, or `Terminates`; `CausalEdge` stores typed endpoints and claim metadata.

- Temporal precedence alone never licenses causation.
- Causal transitivity is not assumed.
- Edge metadata distinguishes explicit statement, linguistic entailment, strong story support, commonsense inference, and hypothesis.

### 23.3 Goals and psychology

`GoalRelation` cases: `Motivates`, `IntendedToAchieve`, `SubgoalOf`, `Achieves`, `FailsToAchieve`, `Abandons`.

Full extraction is P1, but the P0 schema includes this family. Belief/desire are primarily represented by contexts; richer emotion/motivation transition chains can be added compatibly.

### 23.4 State change

Lightweight event calculus:

- `StateChangeKind`: `Initiates`, `Terminates`, `Maintains`.
- `StateChangeEdge(event, change, state, meta)`.
- Validation requires the target to be a state situation.

### 23.5 Reference and canonical event identity

Canonical situations can have initial, anaphoric, prospective, retrospective, and summary mentions. `ReferenceMode` includes `Denotes`, `Anaphoric`, `Prospective`, `Retrospective`, `Summarizes`, and `PartiallyCorefers`.

This prevents a character's later retelling from becoming a duplicated occurrence: the retelling is a new speech event whose embedded content references the earlier event.

---

## 24. Narrative hierarchy

\[
H=(H_{\mathrm{seg}},H_{\mathrm{arc}}).
\]

### 24.1 Primary segmentation \(H_{\mathrm{seg}}\)

Principally discourse-contiguous nesting:

\[
\text{situations}\subset\text{scenes}\subset\text{episodes}\subset\text{story}.
\]

This is P0 and the main source of gist-level targets.

### 24.2 Auxiliary arcs \(H_{\mathrm{arc}}\)

Possibly overlapping/discontinuous character, goal, location, motif, conflict, and thematic groupings. P0 must support their schema but need not generate them reliably.

`HierarchyKind` cases are `PrimarySegmentation`, `GoalArc`, `EntityThread`, `LocationThread`, and `Theme`. A `ContainmentEdge` links a situation/segment member to a parent segment with kind, weight, and metadata. Primary membership is normally weight 1; auxiliary membership can be graded.

### 24.3 Boundary beliefs

The chosen tree does not erase rejected candidate boundaries. `BoundaryBelief` stores the surface unit after which the boundary falls, hierarchy level, raw score, optional calibrated probability, and nonempty evidence.

---

## 25. Discourse trajectory and feature views

For atomic units \(u_1,\ldots,u_m\):

\[
x_i=[x_i^{\mathrm{semantic}},x_i^{\mathrm{sensory}},x_i^{\mathrm{affective}},x_i^{\mathrm{entity}},x_i^{\mathrm{location}},x_i^{\mathrm{context}},x_i^{\mathrm{goal}}],
\]

and adjacent view-specific change:

\[
\delta_i^{(k)}=d_k(x_i^{(k)},x_{i+1}^{(k)}).
\]

`FlowStep(from,to,...)` stores optional semantic/sensory/affective change, entity turnover, optional location change, context change, optional world-time jump, and associated boundary beliefs.

It supports gradual development, abrupt scenes, cast/location changes, perceptual-modality changes, shifts into speech/thought/memory, flashbacks, jumps, and recurrence.

### 25.1 Expressed versus evoked sensory content

Keep separate:

- **Expressed:** explicitly invoked visual, auditory, tactile, motor, spatial, olfactory, gustatory, and interoceptive language, grounded to phrases when possible.
- **Evoked:** model-estimated imagery likely to be experienced by a reader, explicitly labeled as an estimate.

`SensoryProfileKind` is `Expressed` or `Evoked`; `SensoryProfile` carries a `ScoreEstimate` for each of the eight modalities.

### 25.2 No canonical embedding

Alignable nodes can have multiple vector spaces:

| Space | Intended sensitivity |
|---|---|
| `semantic.surface` | Original wording |
| `semantic.proposition` | Canonical predicate–argument meaning |
| `semantic.contextual` | Local story context |
| `semantic.segment` | Scene/episode gist |
| `sensory.expressed` | Linguistically expressed sensory profile |
| `sensory.evoked` | Estimated imagery |
| `affect` | Valence, arousal, threat, tension, etc. |

Vectors live outside the graph payload. `FeatureSpace` declares typed ID, dimension, description, model fingerprint, and claimed normalization. `FeatureRef` maps a target and space to a sidecar row.

---

## 26. Claim ledger, uncertainty, and receipts

### 26.1 Claim value

Every nontrivial machine assertion is a claim. `EpistemicStatus`:

- `SurfaceExplicit`;
- `LinguisticallyEntailed`;
- `WorldKnowledgeInferred`;
- `StructurallyDerived`;
- `Hypothesized`;
- `HumanAdjudicated`.

`Credence` separates `rawScore` from optional calibrated `Probability` and optional calibration-model ID. `Resolved[A]` contains value, credence, status, nonempty evidence, and alternative values/credences. A raw score is never labeled a probability without demonstrated calibration.

### 26.2 Evidence

`Evidence` records:

- evidence ID;
- zero or more source spans;
- upstream claim IDs;
- extractor fingerprint;
- build-stage ID.

Explicit claims require source spans. Inferences can cite motivating spans/upstream claims and method while making no direct-expression claim.

### 26.3 Provider/build provenance

Every call records:

- provider/model name and reported version;
- model/artifact checksum where available;
- task-schema and prompt-template versions;
- input/output checksums;
- decoding parameters and seed when supported;
- cache status;
- latency/usage;
- software commit;
- stage configuration hash.

Concise provider evidence/justification may be stored; private model reasoning is neither required nor relied upon.

---

## 27. *The War of the Ghosts* acceptance fixture

The story must be manually modeled **before** automated extraction. A reasonable, non-hard-coded primary episode structure is approximately:

1. river hunting, anomalous sounds, canoe arrival;
2. invitation, journey, battle;
3. return, recounting, transformation, death.

Atomic splits/merges remain annotator decisions, and gold should permit acceptable alternatives.

### 27.1 Required difficult structures

| Phenomenon | Required model behavior |
|---|---|
| Two young men act together | Separate entities, shared participant edges |
| One declines, one joins | Separate decision and participation events |
| Warriors announce a raid | Speech context plus intended future event |
| Battle occurs later | Root/main-world event, not merely speech content |
| Warriors say the man was hit | Speech-scoped/reported injury claim |
| Man does not feel injured | Main-world state conflicting with but not erasing report |
| Man concludes they are ghosts | Belief change or belief-scoped group attribute |
| Man recounts battle at home | New telling event; content corefers with earlier events |
| Man dies later | Main-world event |
| Death cause uncertain | No accepted explicit `injury Causes death`; inference/hypothesis only |

### 27.2 Critical failures prohibited

The system must not:

- duplicate the battle because it is retold;
- treat a future announced battle as already occurring;
- claim the man objectively knows the warriors are ghosts;
- split warriors/ghosts into unrelated groups without an identity hypothesis;
- make the reported wound unquestioned fact;
- label wound-caused-death as explicit;
- collapse discourse order and story-world chronology.

Representative ambiguity: the injury event is supported by the warriors' statement, lives in their speech context, has reported/possible modality and `SurfaceExplicit` status for the report; cross-context truth remains `Hypothesized` with alternatives “injury occurred” and “no ordinary injury occurred.”

---

## 28. Ten-stage construction pipeline

One-shot “read story and output final graph” generation is only a baseline. Production stages emit typed candidates, receipts, and diagnostics.

### Stage 0 — Ingest

Input text/metadata; output `StorySource`. Preserve raw input, establish canonical text, hash both, assign deterministic `StoryId`, validate encoding/length. Gate: exact canonical reproduction and universal offset basis.

### Stage 1 — Surface analysis

Deterministic/no remote model: paragraphs, sentences, tokens, provisional clauses, quotation bounds, stable IDs. Property-test offset integrity.

### Stage 2 — Local semantic candidates

Process overlapping windows of numbered units. Extract entity/situation mentions, predicates/roles/states, polarity/modality, temporal expressions, speech/thought cues, explicit causal markers, locations, and sensory expressions.

Requests reference stable sentence/token IDs, never model-invented character offsets. Builder converts valid token spans deterministically and rejects invalid references.

### Stage 3 — Global canonicalization

Across the full story: entity alias/pronoun clustering, group/individual distinctions, event/state clustering, prospective/retrospective references, canonical descriptions, and alternative clusterings. Canonical IDs are content-addressed from sorted mention IDs and node kind, not random UUIDs, so repeated builds are diffable.

### Stage 4 — Context construction

Build nested narrative, direct/indirect speech, thought/belief, desire/intention, hypothetical/counterfactual, memory/retrospective, and dream/imagination contexts. Every situation must have a context; omission is an error, not null.

### Stage 5 — Sparse relation candidates

Ordinary all-pairs classification is prohibited. Candidate pairs arise from discourse proximity, shared entities/locations, temporal/causal cues, coreference hypotheses, hierarchy membership, semantic nearest neighbors, and a limited global nomination mechanism. Expected ordinary growth is roughly linear in situation count, not \(m^2\).

Classify temporal, causal, goal, reference, state-change relations plus explicitness/inference status.

### Stage 6 — Temporal resolution

Reconcile proposals, separate discourse/world time, detect strict cycles, compute closure, retain unresolved relations, optionally lay out a timeline. Repairs never silently delete high-confidence edges; conflicts/resolutions are explicit records.

### Stage 7 — Hierarchy and flow

Boundary feature vector:

\[
f_i=[\Delta_{semantic},\Delta_{sensory},\Delta_{affective},\text{entity turnover},\text{location change},\text{context change},\text{temporal jump},\text{goal change},\text{discourse cue},\text{model votes}].
\]

Select primary nested segmentation by transparent constrained optimization:

\[
H^\star=\arg\max_H\left[\sum_{b\in H}w_{\ell(b)}^\top f_b-\lambda_{complexity}\operatorname{Complexity}(H)\right]
\]

subject to nestedness, root coverage, no empty segments, configurable size bounds, and preservation of unselected boundary beliefs. Segment summaries are evidence-backed claims and never replace children.

### Stage 8 — Feature construction

Create proposition/contextual/segment embeddings, expressed/evoked sensory features, affective features, centrality components, and flow features. Local inference should be possible via ONNX Runtime or another JVM-compatible backend; remotes share the algebra.

### Stage 9 — Global resolution

Conceptual draft selection:

\[
\hat G=\arg\max_G[\sum_{c\in G}s(c)+\lambda_{coverage}Coverage(G)+\lambda_{coherence}Coherence(G)-\lambda_{dup}Duplication(G)-\lambda_{complex}Complexity(G)]
\]

Hard constraints: typed endpoints, valid spans, acyclic containment and within-context strict precedence, context-scoped contradiction handling, evidence on accepted atomic nodes, members on every segment, declared/dimension-correct feature spaces.

Version 1 uses deterministic transparent selection plus checks. Add a weighted constraint solver only if evidence shows that simpler resolution fails.

### Stage 10 — Validate and serialize

Output draft model, validation report, candidate ledger, receipt, and optionally a validated model when the configured gate passes.

---

## 29. Provider-neutral algebras and configuration

### 29.1 Acquisition interfaces

Effect-polymorphic interfaces:

- `SurfaceAnalyzer[F].analyze(source): F[SurfaceAtlas]`;
- `SituationExtractor[F].extract(source, atlas): F[CandidateSituations]`;
- `Canonicalizer[F].resolve(atlas, candidates): F[CanonicalizationResult]`;
- `ContextExtractor[F].extract(atlas, canonical): F[CandidateContexts]`;
- `RelationExtractor[F].extract(candidateGraph): F[CandidateRelations]`;
- `HierarchyBuilder[F].build(graph, trajectory): F[CandidateHierarchy]`;
- `FeatureEncoder[F].encode(Stream[FeatureTarget]): Stream[EncodedFeature]`.

Implementations can be deterministic rules, JVM NLP, local neural inference, schema-constrained local/remote generative models, human annotation import, or ensembles. Provider identity never leaks into the core graph model.

### 29.2 Parameter classes

- **Structural contract:** relation/role/context vocabularies, hierarchy semantics, offsets, evidence statuses, feature definitions; schema-versioned and not story-tuned.
- **Acquisition:** windows/overlap, provider, schema/decoding, replicated passes, relation radius, semantic neighbors, embedding/sensory models, cache.
- **Resolution:** merge/coreference/edge/review thresholds, contradiction/duplication/coverage/hierarchy-complexity penalties.
- **Learned:** claim-family calibration, boundary weights, pair ranking, relation classifiers, optional coreference scorer. Nothing is trained only on WOG.

`StoryBuildConfig` groups profile, surface, extraction, canonicalization, relations, hierarchy, features, validation, and execution configs.

Profiles:

- **Baseline:** one pass, limited relations, semantic vectors, all validation; debugging only.
- **Research:** overlapping/replicated or cross-checked extraction, global canonicalization, full temporal/causal/hierarchy/features, calibrated thresholds where available.
- **GoldAssist:** Research plus conservative acceptance, review prioritization, and patch/adjudication artifacts.

### 29.3 Confidence policy

Initial raw thresholds are provisional, never scientific probabilities. After initial gold:

1. calibrate each claim family separately;
2. report Brier score and expected calibration error;
3. define accept/review/reject-as-canonical regions;
4. retain rejected candidates;
5. recalibrate after provider/model changes.

---

## 30. Scala 3 formalization and module boundaries

### 30.1 Core typing

Scala 3 enums define closed relation/status families. Opaque types distinguish story, surface, entity, situation, segment, context, claim, evidence, feature-space IDs, and probabilities without ordinary wrapper overhead.

Model-state phantom types:

- `ModelStatus` with `Draft`, `Validated`, `Adjudicated`;
- `StoryModel[S <: ModelStatus]` with private package construction;
- fields: schema version, source, atlas, graph, hierarchy, trajectory, feature manifest, claim ledger, receipt.

`NarrativeGraph` stores entity/situation/segment/context maps and `RelationLayers`.

Validation promotes types:

- `StoryValidator.validate(draft, policy): ValidationOutcome`;
- outcome contains report plus optional `StoryModel[Validated]`;
- downstream scientific APIs require `Validated` or `Adjudicated`.

Core semantic enums include polarity `Positive/Negative/Unknown` and modality `Asserted/Possible/Probable/Necessary/Intended/Desired/Counterfactual/Unknown`.

### 30.2 Proposed modules

| Module | Responsibility |
|---|---|
| `story-model-core` | ADTs, IDs, smart constructors, immutable graph |
| `story-model-laws` | Structural laws/reusable validators |
| `story-model-build` | Orchestration and stage cache |
| `story-model-provider-api` | Extraction/embedding/classification algebras |
| `story-model-provider-llm` | Schema-constrained generative adapters |
| `story-model-provider-onnx` | JVM-local vector/classifier inference |
| `story-model-codec` | Canonical JSON, ledgers, manifests, migrations |
| `story-model-diff` | Graph-aware build comparison |
| `story-model-cli` | Build/validate/inspect/diff/patch |
| `story-model-benchmark` | Gold, metamorphic, performance harnesses |
| `story-model-fixtures` | Hand/machine-built reference stories |

Technology recommendations: Cats Effect for effect/resource/cancellation/concurrency; FS2 for bounded streaming; ONNX Runtime Java API for exported local models; MUnit + ScalaCheck for examples/properties.

The core must not depend on a graph DB, HTTP client, LLM provider, ONNX, UI, or particular serialization library.

---

## 31. Runtime, artifacts, and public APIs

### 31.1 Sparse storage and indexes

Store relation vectors and build indexes lazily: outgoing/incoming temporal edges, events by entity, situations by context, children by segment, mentions by canonical node. No dense adjacency matrices in core. Scientific consumers explicitly request sparse matrix views.

### 31.2 Stage cache

Each key hashes input artifact, stage schema, config, and provider fingerprint. Changing embeddings does not invalidate sentence segmentation; changing hierarchy does not rerun entities.

### 31.3 Deterministic IDs

Derive IDs from story checksum, kind, support, canonicalized member IDs where relevant, and schema version. Stability follows stability of the interpretation.

### 31.4 Vector sidecars

Canonical JSON contains manifests/row references. Benchmarks choose compact row-major float32 or Arrow-compatible binary. Production never writes huge embedding arrays as JSON numbers.

### 31.5 Artifact layout

```text
war-of-the-ghosts/
  source.txt
  story.json
  claims.jsonl
  candidates.jsonl
  features.manifest.json
  features.bin
  build-receipt.json
  validation.json
  patches.jsonl
  render/
    summary.md
    graph.dot
    timeline.json
```

- `story.json`: resolved model.
- `candidates.jsonl`: permits re-resolution without provider reruns.
- `patches.jsonl`: append-only corrections; machine record remains.

### 31.6 Library and CLI

`StoryBuilder[F].build(input, config): F[StoryBuildResult]`; result contains draft, report, diagnostics, and artifact paths.

CLI surface:

- `build source.txt --profile research --out model/`;
- `validate model/story.json`;
- `inspect ... --situation <id>`;
- `timeline`;
- `render --format dot`;
- `diff`;
- `apply-patch ... corrections.jsonl`;
- `benchmark --suite story-gold`.

### 31.7 Phase 2 bridge

`AlignmentSource` exposes:

- `alignableNodes(levels)`;
- `sourceSupport(target)`;
- sparse `relationMatrix(layer)`;
- `featureMatrix(space)`;
- sparse `hierarchyMembership`.

This is the explicit source-to-recall-aligner contract.

---

## 32. Gold construction and validation strategy

### 32.1 Manual schema-first action

Before prompts/extractors:

1. manually encode WOG in Scala;
2. render event list/graph;
3. run laws;
4. try manual recall alignments;
5. revise ontology wherever alignment is awkward or information is absent.

This prevents the acquisition mechanism from accidentally defining the ontology.

### 32.2 Layered annotation

Annotate independently by at least two annotators, then adjudicate:

1. surface units/mentions;
2. entities/coreference;
3. situations/roles;
4. contexts/polarity/modality;
5. world time;
6. causal/goal relations;
7. event identity/retrospective reference;
8. hierarchy;
9. sensory/affective features;
10. adjudication.

Gold permits multiple granularities, alternative times, uncertain cause, partial coreference, context-dependent attributes, and explicit `Unclear`. Inter-annotator agreement is the empirical ceiling; gates cannot rationally exceed attainable human agreement.

### 32.3 Benchmark suites

- **WOG-Gold:** one deep adjudicated fixture for schema, regression, ambiguity/context, demo; not generalization.
- **StoryGold-20:** 20 stories, roughly 500–3,000 words, split by story into 8 development, 4 calibration, 8 held-out. Include linear story, flashback, nested narration, dialogue-heavy, unreliable/uncertain report, unresolved-cause mystery, first person, similar characters, repeated descriptions, figurative language, unfamiliar folktale, psychological low-action story, parallel plots, temporal ellipsis, and explicit summaries.
- **Public component tests:** AMR-compatible proposition evaluation where licensed; RED; CaTeRS; Story Commonsense; NarrativeTime; public general coreference; custom StoryGold human boundaries.
- **NarrativeMetamorphic:** transformations with known structural consequences.
- **LongStory:** at least five 10k–30k-word stories for scaling/cache/memory.
- **RecallReadiness:** human paraphrases, summaries, vague references, and retrospective descriptions; tests source target adequacy, not final alignment.

### 32.4 Metrics

**Surface/evidence:** exact-span precision/recall/F1, token-overlap F1, invalid spans, support completeness, evidence-status correctness.

**Entities:** mention F1, type macro-F1, MUC/\(B^3\)/CEAF-style coreference, purity, catastrophic merge/split counts.

**Situations:** event/state mention F1, predicate accuracy, participant-role macro-F1, polarity/modality/context accuracy, event-coreference accuracy.

**Temporal:** relation macro-F1, closure-aware precision/recall, strict cycles, pair order, optional timeline correlation, explicit/inferred status accuracy.

**Causal/goals:** macro-F1, accepted-edge precision/recall, status accuracy, Brier/ECE, unsupported-cause rate. Accepted causal precision is prioritized over recall.

**Hierarchy:** exact and ±1-clause boundary F1, \(P_k\), WindowDiff, parent/ancestor agreement, tree edit, human coherence and summary faithfulness.

**Trajectory/features:** boundary-versus-within discontinuity, paraphrase stability, sensory-substitution sensitivity, recurrence, human modality agreement.

**End-to-end:** unchanged/minor/major-correction proportions, hallucinated/missed-major situations, graph edit distance, top-\(k\) source-candidate coverage for recall statements.

### 32.5 Provisional quality gates

Thresholds are revisited after five gold stories and compared with human agreement.

| Gate | Pass condition |
|---|---|
| G0 Domain laws | Zero unit/property failures for IDs, spans, endpoints, dimensions, constructors |
| G1 Evidence | 100% of accepted explicit claims have valid spans; no dangling evidence |
| G2 Hallucination | Severe hallucinations \(\le 1\%\) of accepted situations |
| G3 Atomic semantics | Situation F1 \(\ge .90\); role macro-F1 \(\ge .85\); polarity/modality \(\ge .93\) |
| G4 Reference/context | Aggregate entity/coreference \(\ge .88\); context accuracy \(\ge .90\) |
| G5 Temporal | Accepted-edge precision \(\ge .90\); macro-F1 \(\ge .80\); zero strict cycles |
| G6 Causal | Accepted-edge precision \(\ge .85\); macro-F1 \(\ge .70\); unsupported accepted \(\le 2\%\) |
| G7 Hierarchy | ±1-clause boundary F1 \(\ge .80\); ancestor agreement \(\ge .80\) |
| G8 Expert usability | \(\ge90\%\) nodes and \(\ge85\%\) accepted edges need no major correction |
| G9 Recall readiness | Correct target in top 5 for \(\ge95\%\) curated units |
| G10 Reproducibility | Exact cache replay; uncached accepted-graph stability \(\ge .90\), ID-insensitive |
| G11 Scaling | No dense event-pair allocation; 10k words under 1 GB excluding weights |

G0, G1, G2, or temporal-acyclicity failure blocks promotion. Lesser failures can yield a proposed `ValidatedWithWarnings` only when policy permits it.

### 32.6 Structural laws

**Source:** bounds, exact recovery, children within parent spans, ordered unique ordinals, evidence for explicit claims.

**Graph:** unique IDs/existing endpoints; situation-to-entity participants; state-change targets are states; every situation has context; acyclic containment; exactly one primary root; all atomic situations root-reachable; no empty primary segment.

**Temporal:** acyclic strict closure per context; symmetric `Equal`; valid inverses; no accepted both-before-and-after pair; context facts stay scoped; derived transitivity retains derived provenance.

**Features:** dimensions match, finite values, normalization numerically verified, targets exist, fingerprints/versions present.

**Claims:** calibrated values in \([0,1]\) with calibration model; no duplicate selected alternative; adjudication retains proposal; surface-explicit has spans.

### 32.7 Metamorphic tests

| Transformation | Expected localized/invariant behavior |
|---|---|
| Consistent character renaming | Labels change; topology nearly invariant |
| Full paraphrase | Proposition/graph stable; surface vectors change |
| Active to passive | Predicate/roles stable |
| Irrelevant introduction | Local addition; no global reorganization |
| Paragraph breaks only | Minimal surface change; stable event model |
| Remove punctuation | Graceful degradation, not graph collapse |
| Negate event | Local polarity change; affected causal/state links reconsidered |
| Swap agent/patient | Roles change; diff localizes |
| “because” to “after” | Explicit causal evidence vanishes; time may stay |
| “will fight” to “fought” | Modality/world-time changes |
| Move flashback later | Discourse order changes; world order stable |
| Direct to indirect speech | Context form changes; embedded identity stable |
| Repeat as character report | New report event; reference, no duplicate occurrence |
| Duplicate sentence | Mention count changes; no automatic canonical duplication |
| Auditory to visual detail | Local sensory change; event identity stable |
| Add “perhaps” | Modality/credence change; predicate stable |
| Faithful scene summary | Atomic coverage falls; valid segment target remains |

Flow checks: major boundaries have greater semantic discontinuity than within scenes; cast/location signals correspond to changes; distant WOG battle mentions recur semantically; sensory changes respond to content rather than punctuation; higher boundaries generally aggregate more multiview change.

Locality-of-change metric:

\[
\operatorname{Locality}=1-\frac{\text{unaffected nodes changed}}{\text{unaffected nodes}}.
\]

Minimal edits should have high locality; unrelated provider drift is a regression even if aggregate F1 is steady.

### 32.8 Reproducibility and performance

Release candidates run three times each with cached outputs, uncached deterministic locals, and each nondeterministic remote. Report exact hashes, node/edge/hierarchy matches, feature cosine agreement, support stability, calibration drift, cost, and latency. A provider change is a new benchmark condition, never a silent reference replacement.

Separate deterministic graph processing, local inference, remote latency, serialization, and validation.

| Input | Deterministic postprocessing | Memory excluding model weights |
|---|---:|---:|
| 2,000 words | <2 s | <250 MB |
| 10,000 words | <5 s | <1 GB |
| 30,000 words | <20 s | <2 GB |

These are engineering targets, not scientific requirements. Architectural checks—no dense \(m\times m\), bounded provider concurrency, streaming features, cache reuse, validation without loading vectors, near-linear storage—matter more.

---

## 33. Proposed Part 2 development sequence

This sequence is recorded for later comparison; it is not yet the final plan.

### Milestone 0 — Representation RFC and hand fixture

Deliver first ADTs, hand WOG model, deterministic readable renderer, graph visualization, and 20 laws/metamorphic tests. Exit: all difficult phenomena encode without untyped escape hatches.

### Milestone 1 — Core, codecs, laws

Deliver core, opaque IDs/smart constructors, canonical JSON, indexes, validation, receipt, diff. Exit: exact WOG round trip and G0–G1.

### Milestone 2 — Atlas and candidate protocol

Deliver deterministic text processing, exact sentence/token/clause/quote spans, provider-neutral candidate schemas/ledger, cached runner, baseline structured-model adapter. Exit: exact evidence round trips and malformed reference rejection.

### Milestone 3 — Entities, situations, contexts

Deliver extraction, roles, entity/event-reference resolution, speech/belief/intention/hypothetical contexts, WOG regressions. Exit: G2–G4 on development stories.

### Milestone 4 — Temporal, causal, state

Deliver sparse candidates, temporal extractor/classifier, closure/conflicts, conservative causality/enabling, state changes, timeline. Exit: G5–G6 on development/calibration.

### Milestone 5 — Hierarchy, flow, features

Deliver trajectory, semantic/sensory encoders, boundary evidence, constrained hierarchy, summaries, sidecar. Exit: G7 plus flow sanity.

### Milestone 6 — Gold and recall-readiness pilot

Deliver StoryGold-20, adjudication, public adapters, paraphrase set, calibration, report. Exit: G8–G10 held out.

### Milestone 7 — Release hardening

Deliver CLI/API docs, migrations, reproducible example, performance report, privacy/provider guide, v0.1. Exit: mandatory gates and no open P0 criticals.

### 33.1 First sprint

Do **not** optimize prompts. Goal: prove the ontology can faithfully represent one hard story.

1. `StorySource`, `TextSpan`, `SpanSet`, deterministic IDs.
2. Entity/situation/context/segment/relation ADTs.
3. Claim metadata, evidence, credence, alternatives, provenance.
4. Hand-code WOG.
5. Deterministic renderer for entities, ordered situations, contexts, timeline, causal edges, hierarchy.
6. DOT export.
7. Structural validators.
8. Graph-aware diff.
9. WOG critical sanity tests.
10. Ten manual recall paraphrases with suitable source targets/granularities.

Sprint exit question: can the system express what the story says, what characters say/believe, uncertainty, relations, and unfolding without using embeddings/LLMs to conceal ontology gaps? If not, revise schema before acquisition automation.

---

## 34. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Impressive but unreliable one-shot LLM graph | Typed stages, exact references, candidate ledger, deterministic laws |
| Invented events/causes | Conservative thresholds, epistemic statuses, high-precision causal gate |
| Local extraction misses global identity | Whole-story canonicalization/reference pass |
| Speech contaminates main world | Required nested contexts and scoped validation |
| LLM hierarchy is arbitrary | Multiview evidence, constrained segmentation, human boundary benchmark |
| Quadratic relation inference | Sparse candidate generation/indexed layers |
| Provider drift | Fingerprints, cached raw output, regression corpus, graph diff |
| Ontology too rigid | Compact closed core + external concepts/custom roles |
| Ontology zoo | P0/P1 discipline; each field must answer a downstream question |
| Score mislabeled probability | Raw and calibrated values distinct |
| Gold disagreement on cause | Alternatives, uncertainty, human-ceiling-relative gates |
| WOG overfit | StoryGold-20; leave-story-out and leave-family-out evaluation |
| Vectors dominate structure | Sidecar feature spaces; evidence/graph authoritative |
| Themes become events | Separate descriptor claim family |
| Automatic repair hides failure | Conflict and resolution records for every repair |

---

## 35. Phase 1 definition of done

Phase 1 is complete only when:

1. library and CLI ingest arbitrary English stories;
2. output includes atlas, entities, situations, contexts, relations, hierarchy, trajectory, features, ledger, and receipt;
3. every accepted explicit claim is traceable;
4. strict time and containment are valid;
5. ambiguity/alternatives survive serialization;
6. WOG avoids all specified critical errors;
7. held-out StoryGold-20 gates pass;
8. builds are reproducible/diffable;
9. corrections are append-only patches;
10. Phase 2 retrieves multiscale candidates, spans, sparse relations, feature matrices;
11. manually authored precise, vague, summary, sensory, and retrospective recalls all find appropriate source targets;
12. Scala core requires no graph DB, Python runtime, or specific model provider.

---

## 36. Evolution from Part 1 to Part 2

Part 2 concretizes rather than replaces Part 1:

| Part 1 concept | Part 2 operationalization |
|---|---|
| \(\mathcal N=(X,V,H,\{A^{(r)}\},\Phi,U)\) | `StoryModel` \((A,G,H,X,\Gamma)\) plus receipt/report |
| Continuous source support \(h_v(t)\) | Exact UTF-16 `SpanSet`, mention positions, trajectory, hierarchy support; soft support remains relevant for later alignment |
| Event state transitions | Typed event/state situations plus `StateChangeEdge` |
| Multiplex graph | Sparse typed participant/temporal/causal/goal/state/reference/containment families |
| Three clocks | Phase 1 makes discourse/world time explicit; recall time is deferred to Phase 2 |
| Segmentation uncertainty | Retained `BoundaryBelief`s alongside selected hierarchy |
| Source interpretation uncertainty | `Resolved[A]`, evidence, alternatives, status, calibration, append-only patches |
| Multiview field | Discourse trajectory plus sidecar feature spaces |
| Provenance requirement | Claim ledger and content-addressed provider/build receipts |
| First restrained implementation | Concrete staged Scala architecture and WOG-first manual ontology test |

The final architectural recommendation is:

\[
\boxed{
\text{StoryModel}
=
\text{Evidence Atlas}
+
\text{World Graph}
+
\text{Abstraction Hierarchy}
+
\text{Discourse Trajectory}
+
\text{Claim Ledger}
}
\]

- atlas: exact evidence location;
- graph: what happens to whom, in which context and relation;
- hierarchy: detail/scene/episode/gist;
- trajectory: how meaning, sensation, affect, and situation evolve;
- ledger: belief strength, basis, alternatives, and history.

The separation is intended to prevent conceptual collapse and implementation sprawl while leaving each component independently testable, replaceable, and interpretable.

---

## 37. New reconciliation questions exposed by Part 2

These should be resolved during later parts or final planning:

1. **Notation mapping:** decide whether Part 2's \(A,G,H,X,\Gamma\) formally supersedes Part 1 notation and specify any missing semantics formerly carried by \(\Phi,U\).
2. **Soft source support:** Part 2 emphasizes exact span sets and boundary beliefs; decide where fuzzy/discontinuous \(h_v(t)\) lives in the Phase 1 API.
3. **Epistemic vocabulary:** reconcile the general `EpistemicStatus` enum with the causal-specific ladder (“explicit,” “entailed,” “strongly supported,” “commonsense,” “hypothesized”) without lossy mapping.
4. **Context vocabulary:** “thought,” “intention,” “imagination,” and retrospective report appear in pipeline requirements but are not all explicit `ContextKind` cases; decide whether they map to belief/desire/memory or need additional cases.
5. **Temporal inverses:** the proposed enum has `Before` but no `After`; clarify whether inverse direction is represented by swapping endpoints and ensure laws/API say so.
6. **Validated-with-warnings:** gates mention this status, but the model-status sketch contains only Draft/Validated/Adjudicated. Decide whether warnings are a report/policy property or another phantom status.
7. **Segment placement:** segments appear inside `NarrativeGraph` and in `NarrativeHierarchy`; define ownership and eliminate redundant sources of truth.
8. **Entity continuity:** Part 1 listed it as a starting relation layer; Part 2 derives continuity through mentions, participants, and indexes rather than a named edge. Confirm the Phase 2 matrix contract.
9. **P0/P1 boundary:** goals exist in P0 schema but full acquisition is P1; define what a valid P0 artifact may omit and how absence differs from failed extraction.
10. **Canonical IDs:** specify stable content-addressing under alternative interpretations, human patches, merge/split operations, and schema migrations.
11. **Claims versus embedded `Resolved` fields:** determine ledger normalization, claim IDs, and whether values contain full claim metadata or refer to canonical ledger entries.
12. **Event roles:** event structure formula contains roles, while example `EventNode` does not store them directly and participant edges do. Confirm the relation layer is the sole source of truth.
13. **Feature ownership:** distinguish graph-derived centrality/trajectory scalars, declared feature spaces, and nonvector profiles; determine what is sidecar versus canonical JSON.
14. **Status of summaries/themes:** define the proposed separate descriptor claim family so summaries and themes cannot masquerade as literal events.
15. **Manual gold prerequisites:** locate a legally usable canonical WOG text/version; differences in wording/paragraphing affect checksums, offsets, and annotations.
16. **Threshold governance:** all numeric gates are provisional; define the decision process for revising them after five stories and relative to annotator ceilings.
17. **Artifact compatibility:** canonical JSON, JSONL ledgers, binary vectors, and patch operations need schema migration, atomicity, and integrity rules.
18. **Core dependency discipline:** reconcile `NonEmptyVector` in core sketches with the statement that core is provider/UI/codec-neutral; Cats data may still be an intentional core dependency, but this should be explicit.
19. **Phase 1 boundary:** population modeling, fMRI regressors, and alignment outputs remain design constraints only, not Phase 1 modules.
20. **Plan status:** milestones and sprint are substantial proposals from Part 2; the final implementation plan must be synthesized only after all discussion parts are received.

---

# Part 3 Detailed Record: AMR as the Typed Local Semantic Kernel

## 38. Architectural decision and boundary

AMR is selected for **local propositional meaning** at sentence/clause scale because it represents:

- entities, events, properties, and states as neo-Davidsonian nodes;
- PropBank-framed predicates and semantic arguments (“who did what to whom”);
- negation, modification, and reentrancy/shared arguments;
- meaning abstracted from many syntactic differences.

AMR is explicitly **not** the whole narrative model. Standard AMR is primarily sentence-local; sentence-external pronouns can remain generic, the original formalism omits tense, and ordinary AMR alone does not adequately distinguish actual, future, desired, imagined, reported, or hypothetical events.

For sentences \(s_1,\ldots,s_m\), the refined story object is:

\[
\mathcal S=
\left(
\{A_i\}_{i=1}^{m},
M,
\pi,
T,
Q,
C,
H,
\Phi,
\Gamma
\right),
\]

where:

- \(A_i\): standards-compatible AMR of sentence \(i\);
- \(M=\coprod_i A_i\): disjoint-union mention graph;
- \(\pi\): mapping from local AMR mentions to canonical story entities/situations;
- \(T\): story-world temporal structure;
- \(Q\): modality, speech, belief, and epistemic scope;
- \(C\): causal, enabling, and goal structure;
- \(H\): event–scene–episode hierarchy;
- \(\Phi\): embedding/numerical feature family;
- \(\Gamma\): evidence/provenance ledger.

Division of labor:

\[
\boxed{
\begin{aligned}
\text{LLMs} &: \text{typed semantic proposals and interpretations},\\
\text{embeddings} &: \text{retrieval and graded geometry},\\
\text{AMR} &: \text{local propositional structure and hard distinctions},\\
\text{Scala laws} &: \text{admissibility and reproducibility}.
\end{aligned}
}
\]

AMR is neither a disposable feature extractor nor a monolithic “narrative AMR” dialect. The strongest proposal is a standalone standards-compatible AMR library, UMR-compatible document semantics above it, and project-specific causal/sensory/hierarchy/uncertainty/recall-oriented layers above those.

All claims about the original AMR formalism, PropBank, Penman, LEAMR, DocAMR, UMR, Smatch, and current document evaluation remain to be bibliographically verified.

---

## 39. Separate PENMAN concrete syntax from AMR semantics

The library must model two different artifacts:

1. a lossless or nearly lossless **PENMAN concrete syntax tree**;
2. a canonical **AMR semantic graph**.

PENMAN carries representation choices/trivia that are not semantic graph identity:

- variable names;
- branch order;
- nesting choices;
- inverse spellings such as `:ARG0-of`;
- comments/metadata;
- optional alignment markers.

For example:

```text
(b / boy
   :ARG0-of (s / sing-01))
```

and a representation rooted/nested around `sing-01` can encode the same canonical edge:

```text
sing-01 --ARG0--> boy
```

The graph retains the declared PENMAN top/focus separately. The AMR root is sentence/phrase focus—not necessarily the main event, earliest event, or most important narrative event.

### 39.1 Proposed AMR subsystem

```text
amr-syntax     PENMAN tokens, CST/AST, comments, metadata, printer
amr-core       Canonical concepts, roles, nodes, literals, edges
amr-schema     Role inventory, PropBank frames, reifications
amr-align      Text-to-graph alignments
amr-laws       Validation and algebraic laws
amr-document   Cross-sentence composition and coreference
amr-llm        Typed model proposals and repairs
story-model    Narrative projection, chronology, cause, hierarchy
```

Scala parsing/printing should be differentially tested against the established Python Penman implementation because PENMAN has many edge cases despite its apparent simplicity. This is test/reference interoperability, not a Python dependency of the Scala core.

---

## 40. Typed Scala 3 AMR core

### 40.1 Type states and graph representation

Proposed state dimensions:

- check state: `Unchecked` or `Checked`;
- role form: `SurfaceRoles` or `CanonicalRoles`.

Opaque values:

- `NodeId: Long`;
- `FrameId`, `Lemma`, `RoleName: String`;
- `ArgIndex`, `PosIndex: Int`.

Core open-but-typed values:

```scala
enum Concept:
  case Frame(id: FrameId)       // want-01
  case Lexical(lemma: Lemma)    // boy
  case Special(name: String)    // date-entity, amr-unknown

enum AmrLiteral:
  case Text(value: String)
  case Number(value: BigDecimal)
  case Symbol(value: String)    // e.g. - or +

enum Role:
  case Arg(index: ArgIndex)
  case Standard(name: RoleName)
  case Operand(index: PosIndex)
  case Sentence(index: PosIndex)
  case Extension(namespace: String, name: String)

enum AmrValue:
  case Node(id: NodeId)
  case Literal(value: AmrLiteral)
```

`Edge(source, role, target)` points to node or literal. `AmrGraph[C,R]` privately stores top, nonempty node-to-concept map, edge vector, and graph provenance.

All opaques use smart constructors. The illustrative `ArgIndex.from` accepts 0–5 or returns a domain error; the exact bound must ultimately come from verified AMR/PropBank rules rather than an unexamined constant.

### 40.2 Validation and canonicalization

`AmrValidator.validate` accumulates violations in `ValidatedNec` and promotes unchecked surface-role graphs to checked surface-role graphs. `RoleCanonicalizer.canonicalize` converts checked surface roles to checked canonical roles. Downstream story construction accepts no unchecked graph.

### 40.3 Stable structure versus evolving ontology

Do not create one Scala type per PropBank frame. AMR roles/frames are extensive and evolving; frame argument meanings are frame-specific. In particular, `ARG0` is not universally “Agent.” For `describe-01`, the intended example is describer/object/assigned-description across `ARG0/1/2`.

Data-driven schema:

```scala
final case class FrameSpec(
  id: FrameId,
  arguments: Map[ArgIndex, ArgumentSpec],
  aliases: Set[Lemma]
)

final case class ArgumentSpec(
  index: ArgIndex,
  description: String,
  functionalTag: Option[String],
  cardinality: Cardinality
)

trait FrameLexicon[F[_]]:
  def lookup(id: FrameId): F[Option[FrameSpec]]
```

Generate lexicon data from versioned official PropBank resources at build time. Common frames may receive ergonomic generated builders such as `Frames.want01.arg0(...).arg1(...).build`, but the canonical graph remains generic `FrameId` data. Unknown/extension frames must remain representable, and lexicon updates should not force binary incompatibility.

Governing rule:

> Make invalid structural states unrepresentable when rules are genuinely stable; use validated/versioned schema data when ontology is open or evolving.

---

## 41. Inverse roles, reification, and graph shape

### 41.1 Surface orientation, canonical semantic direction

The syntax layer represents `SurfaceRole(base, orientation)` with `Direct` or `Inverse`. Decoding:

```text
x :ARG0-of y
```

produces canonical:

```text
y --ARG0--> x
```

The semantic graph stores canonical roles only.

### 41.2 Reification

Relations can be reified when the relation itself needs modification, negation, time, or another relation.

`ReificationSpec` declares relation, frame, source argument, and target argument. A versioned `ReificationTable` performs lookups. `Reifier.reify` and `dereify` return validated graphs or explicit errors; no silent mutation.

### 41.3 Cycles and refinements

Although guidelines usually characterize AMRs as DAGs, a small class of legitimate cyclic AMRs exists, and some cycles disappear after reification. Therefore DAG cannot be the only inhabitable graph type.

Proposed shape refinements:

- `ConnectedAmr`;
- `AcyclicAmr <: ConnectedAmr`;
- `ReifiedAcyclicAmr <: AcyclicAmr`.

Most algorithms require only connectedness; topological algorithms explicitly require the acyclic refinement. The exact Scala encoding—additional type parameter, refined wrapper, or evidence value—is deferred.

---

## 42. AMR law suite and quality bar

“Typelevel quality” requires laws, generators, and adversarial fixtures, not merely opaque IDs.

### 42.1 Syntax and codec laws

For valid syntax tree \(p\):

\[
\operatorname{parse}(\operatorname{print}(p))=p
\]

modulo explicitly declared nonsemantic trivia.

For valid graph \(g\):

\[
\operatorname{decode}(\operatorname{encode}(g))\cong g,
\]

where \(\cong\) is graph isomorphism, not case-class equality.

Four distinct notions must stay distinct:

- `Eq[PenmanTree]`: syntax artifact equality;
- `Eq[AmrGraph]`: exact graph artifact equality including IDs;
- `AmrIsomorphism`: variable-renaming/edge-order invariant semantic equality;
- `Smatch`: graded triple overlap.

### 42.2 Checked-graph structural laws

1. top node exists;
2. all edge sources exist;
3. all node-valued targets exist;
4. each node has exactly one concept;
5. underlying graph is connected;
6. roles/literals are lexically valid;
7. no unresolved PENMAN variable remains;
8. duplicate semantic triples are removed or deliberately annotation-preserved;
9. alignment references target existing components.

A stricter validation profile also requires acyclicity.

### 42.3 Normalization laws

\[
N(N(g))=N(g)
\]

\[
g\cong\alpha(g)
\]

\[
\operatorname{invert}(\operatorname{invert}(r))=r
\]

Reification/dereification has a **partial** round trip:

\[
D(R(g,e))\cong g
\]

only for canonical reification patterns without extra modifiers that dereification would discard.

### 42.4 Schema laws

For every frame:

- selected-version lexicon entry exists or frame is marked extension;
- core arguments are licensed or violations recorded;
- declared cardinalities hold;
- aliases resolve to the same identity;
- interpretation is lexicon-versioned.

Never impose `ARG0 = Agent` as a universal law.

### 42.5 Composition/coreference laws

Disjoint union is associative up to isomorphism:

\[
(G_1\sqcup G_2)\sqcup G_3\cong G_1\sqcup(G_2\sqcup G_3).
\]

An empty graph is identity where permitted. Sentence injections \(\iota_i:A_i\hookrightarrow M\) preserve local structure.

Exact coreference is reflexive, symmetric, and transitive. The quotient is deterministic for a fixed partition.

### 42.6 Patch laws

Patch application is atomic:

\[
\operatorname{apply}(g,p)=
\begin{cases}
g' & \text{if }g'\text{ validates},\\
\text{error} & \text{otherwise}.
\end{cases}
\]

Failed patches never partly mutate a graph. Empty patch is identity. Composition is associative for nonconflicting patches.

ScalaCheck must generate valid graphs and minimally invalid graphs targeting each independent law. MUnit/ScalaCheck plus Discipline-style reusable suites are recommended.

---

## 43. Standards-compatible source alignment sidecar

AMR itself does not define how graph meaning maps onto exact source characters; Smatch ignores source words/indices. This project needs exact localization for recall and fMRI.

Add `AmrAlignment` **beside**, not inside or as a dialect change to, the standards-compatible graph. Following LEAMR's useful categories, it contains:

- subgraph alignments;
- relation alignments;
- reentrancy alignments;
- duplicate-subgraph/ellipsis alignments.

`SubgraphAlignment` maps a nonempty node set to `SpanSet`, credence, and claim provenance. `RelationAlignment` does the same for an edge.

Required alignment expressivity:

- exact UTF-16 spans plus sentence/token/clause IDs;
- discontinuous spans;
- one span to multiple nodes;
- abstract node with no direct lexical trigger;
- multiple mentions of one canonical story event;
- machine confidence and human adjudication.

Alignment is scientific evidence, not incidental parser metadata.

---

## 44. Sentence AMRs to document mention graph

Construct:

\[
M=\coprod_{i=1}^{m}A_i.
\]

Every sentence AMR remains intact and local node identities become global references such as `AmrNodeRef(sentence, localNode)`.

### 44.1 No destructive mention merging

A battle, its later recounting, and “what happened” must preserve:

- original occurrence;
- later speech event;
- speech content referring to occurrence;
- retrospective phrase referring again.

Store the mapping:

\[
\pi:V_M\to V_{story}.
\]

Typed kinds:

- `NarrativeKind`;
- `EntityK`, `SituationK`, `StateK`, `SegmentK`;
- `MentionId[K]`, `CanonicalId[K]`.

`ExactCorefCluster[K]` holds a nonempty set of same-kind mentions and canonical ID. The kind parameter prevents entity–event exact coreference at compile time.

Reference modes remain distinct:

- `ExactIdentity`;
- `ProspectiveReference`;
- `RetrospectiveReference`;
- `SummaryOf`;
- `PartialIdentity`;
- `Bridging`;
- `ThematicAnalogy`.

Only exact identity contributes to the equivalence partition. This follows the DocAMR concern that both over-merging and under-merging damage document meaning.

### 44.2 Coreference as quotient

Exact identity defines:

\[
u\sim v\Longleftrightarrow u,v\text{ denote the same entity/event},
\]

and:

\[
G_{story}=M/{\sim}.
\]

Retain all three:

- mention graph \(M\);
- canonical graph \(G_{story}\);
- quotient map \(\pi:M\to G_{story}\).

This preserves local linguistic evidence and global identity. In WOG, “the other young man,” “he,” and “the young man” may share one canonical person while remaining separate mentions. “We are going to make war,” the realized battle, and the later report are connected by prospective reference, realization, and retrospective reference—not blindly merged.

---

## 45. UMR-compatible document semantics plus narrative additions

Borrow UMR's architectural division:

```scala
final case class DocumentSemantics(
  entityCoreference: EntityCoreference,
  eventCoreference: EventCoreference,
  temporal: TemporalLayer,
  modal: ModalLayer,
  aspect: AspectLayer
)
```

UMR retains AMR-like sentence graphs and adds entity/event coreference, temporal dependencies, modal dependencies, plus sentence-layer aspect and person/number.

Do not stop at UMR. Add:

```scala
final case class NarrativeSemantics(
  document: DocumentSemantics,
  causal: CausalLayer,
  goals: GoalLayer,
  states: StateTransitionLayer,
  discourse: DiscourseLayer,
  hierarchy: NarrativeHierarchy,
  sensory: SensoryTrajectory,
  affective: AffectiveTrajectory,
  claims: ClaimLedger
)
```

UMR supplies document identity, time, modal source/certainty, and aspect. Project layers add cause/enabling, goals/plans, hierarchy, discourse-versus-world order, sensory/affective flow, exact evidence, alternatives, and recall-oriented granularity.

Compatibility should be pursued where practical, without claiming UMR is a complete cognitive narrative model. Evaluation should retain separate scores for sentence AMRs, modal dependencies, time, and entity/event coreference rather than one undifferentiated document score.

---

## 46. Projection from AMR mentions to narrative nodes

AMR nodes do not directly become the global ontology. Define evidence-backed:

```scala
final case class Projection[K <: NarrativeKind](
  sources: NonEmptySet[AmrNodeRef],
  target: CanonicalId[K],
  mode: ProjectionMode,
  claim: ClaimMeta
)
```

Projection modes:

- `DirectMention`;
- `EventRealization`;
- `StateRealization`;
- `ProspectiveDescription`;
- `RetrospectiveDescription`;
- `Summary`;
- `Inferred`.

Some AMR nodes remain unmapped: grammatical abstractions, conjunctions, names, degree modifiers, auxiliary modal concepts, or narratively irrelevant material. Conversely, one canonical situation may project from multiple nodes/sentences.

At narrative level, stable endpoint types permit strong compile-time guarantees:

- participant: canonical situation + `ParticipantRole` + canonical entity;
- temporal: canonical situation pair plus claim status;
- causal: cause/effect situations + typed causal relation + status.

The AMR layer is lexically/ontologically open; narrative relations remain a small stable algebra, avoiding generic `Triple("x","causes","y")` representations.

---

## 47. Modal and epistemic scope

This is a central limitation of local AMR and a central job of the story layer.

For:

> The warriors said that the young man had been struck. The young man did not feel sick.

Local AMR can represent:

- `say-01` with warriors as speaker and `strike-01` as content;
- young man as strike participant;
- `feel-01` with young man and sick state;
- negative polarity on feeling sick.

The narrative model must additionally represent:

- narrated-world speech event;
- narrated-world negated feel-sick state;
- report-scoped struck proposition;
- open alternatives about whether ordinary injury occurred.

The embedded `strike-01` cannot automatically become a root-world fact.

Part 3 extends the concrete context vocabulary to include:

- `NarratedWorld`;
- `Speech(source)`;
- `Belief(holder)`;
- `Desire(holder)`;
- `Intention(holder)`;
- `Hypothetical`;
- `Counterfactual`;
- `Memory(holder)`.

`ContextualAssertion` binds canonical situation, context, polarity, and epistemic status. This blocks the WOG failure where reported injury becomes unquestioned fact and then an established death cause.

---

## 48. LLM interaction is proposal/patch based

An LLM never directly constructs trusted `AmrGraph[Checked,CanonicalRoles]`. It emits typed candidates/patches such as:

- add a concept with temporary ID and evidence;
- add an edge with proposed value/evidence;
- set top;
- propose merging entity mentions with evidence;
- propose a typed temporal relation with evidence.

Required path:

\[
\text{LLM output}
\to\text{schema decode}
\to\text{ID resolution}
\to\text{law validation}
\to\text{candidate ledger}
\to\text{accepted graph}.
\]

Permitted tasks include AMR generation/comparison/repair, frame selection, pronoun resolution, document relation classification, actual-versus-reported/intended/hypothetical classification, conservative glossing, and alternative analyses.

LLMs cannot bypass span checks, frame-role laws, graph well-formedness, context constraints, temporal consistency, or provenance.

### 48.1 Proposed ensemble

1. dedicated AMR parser emits candidate(s);
2. LLM independently generates/critiques;
3. deterministic syntax and PropBank evidence are supplied;
4. candidates canonicalize;
5. disagreements become candidate claims;
6. global resolver accepts an analysis;
7. low-confidence differences go to adjudication.

This is preferred over either a bare parser or one-shot LLM.

---

## 49. Embeddings as views over AMR fragments

AMR contributes identity, roles, polarity, direction, reentrancy, and composition. Embeddings contribute graded similarity, paraphrase/thematic retrieval, lexical/conceptual generalization, candidate generation, and continuous flow.

For event-like AMR node/fragment \(v\):

\[
\Phi(v)=\{z_v^{surface},z_v^{context},z_v^{gloss},z_v^{graph},z_v^{segment}\}.
\]

- **Surface:** encode exact aligned source text.
- **Target in context:** encode target span with surrounding discourse.
- **Canonical gloss:** conservatively verbalize fragment, e.g. “The second young man joins the warriors.”
- **Graph:** canonical triple serialization, graph encoder, graph kernel, or learned AMR-fragment encoder.
- **Segment:** composite scene/episode representation.

`FeatureView[A]` stores target, feature space, vector reference, model fingerprint, and nonempty evidence. Vectors never participate in node identity or graph equality.

### 49.1 Structural reranking of dense candidates

AMR catches errors embeddings may miss: “warriors struck young man” versus role-swapped “young man struck warriors,” and “did not feel ill” versus “felt ill.”

Hybrid compatibility:

\[
\begin{aligned}
S(r,e)={}&
\theta_1\cos(z_r^{surface},z_e^{surface})
+\theta_2\cos(z_r^{gloss},z_e^{gloss})\\
&+\theta_3 FrameCompatibility(r,e)
+\theta_4 ArgumentMatch(r,e)\\
&-\theta_5 RoleReversal(r,e)
-\theta_6 PolarityConflict(r,e)\\
&-\theta_7 ContextConflict(r,e)
-\theta_8 ModalityConflict(r,e).
\end{aligned}
\]

Summary principle: embeddings locate semantic neighborhoods; AMR decides propositional compatibility.

---

## 50. Recall-side reuse and soft Smatch-like alignment

Later, recall text can use the same local stack:

\[
R\to\{A_i^R\}\to M_R\to\mathcal R.
\]

This yields interpretable recall distinctions:

| Phenomenon | AMR/story diagnosis |
|---|---|
| Faithful paraphrase | Different wording, near-isomorphic predicate–argument fragments |
| Actor/patient reversal | Same frame/entities, incompatible arguments |
| Negation error | Good event identity, polarity conflict |
| Imprecise entity | Matching frame with underspecified participant |
| Gist | Fragment maps to segment aggregate of several AMRs |
| Blending | Recall arguments come from multiple source fragments |
| Report treated as fact | Context mismatch |
| Misordering | Good fragments, altered document-time transitions |
| External association | Thematic vector match without event AMR match |
| Intrusion | Coherent recall AMR without credible source projection |

Standard Smatch is a baseline: variable mapping maximizing triple precision/recall/F1. Recall needs a soft weighted extension with:

- related-concept similarity;
- frame-aware argument matching;
- explicit role-reversal/negation penalties;
- span evidence;
- hierarchy-aware partial/many-to-many mapping;
- external intrusion mass;
- posterior uncertainty.

This local compatibility integrates naturally into Part 1's probabilistic transport/global trajectory model.

---

## 51. AMR-derived segmentation and flow evidence

For adjacent atomic units:

\[
\Delta_i^{AMR}=[\Delta_{frames},\Delta_{entities},\Delta_{roles},\Delta_{location},\Delta_{time},\Delta_{polarity},\Delta_{modality},\Delta_{context}].
\]

Combine with embedding discontinuity:

\[
\Delta_i^{semantic}=1-\cos(z_i,z_{i+1}).
\]

Boundary evidence strengthens when vector change, entity turnover, location change, new goals/predicate complexes, context transition, world-time jump, and new AMR event clusters agree. AMR supports the hierarchy; it does not itself define hierarchy.

---

## 52. Evaluation and gates introduced by Part 3

### 52.1 AMR-kernel suite

1. all AMR-guideline examples;
2. AMR-corpus parse–print–parse round trips;
3. differential Penman tests;
4. alpha-renaming invariance;
5. inverse-role equivalence;
6. reify/dereify fixtures;
7. reentrancy fixtures;
8. rare-cycle fixtures;
9. malformed-PENMAN adversarial cases;
10. PropBank schema validation.

### 52.2 Document/story composition

Measure entity/event coreference, reference mode, context/modal scope, temporal relation, conservative causal precision, scene/episode hierarchy, and preservation of sentence AMRs after document composition.

### 52.3 WOG-specific gates

- resolve repeated young-man references while distinguishing the two men;
- separate announced and realized battle;
- model recounting as speech referencing the earlier battle;
- scope alleged injury under warrior report;
- retain lack-of-felt-illness as distinct negated state;
- never automatically assert injury caused death;
- keep “warriors are ghosts” as belief rather than unqualified root-world fact.

### 52.4 Embedding integration

- faithful paraphrases retrieve correct AMR fragments;
- rare exact details survive hybrid dense/sparse retrieval;
- role-swapped and polarity-swapped foils fail structural reranking;
- actual/intended/reported/imagined events remain distinct;
- summaries retrieve parent segments, not arbitrary children.

---

## 53. Part 3 proposed implementation order

This is another proposal to reconcile with Part 2 before the final plan.

### Phase A — Pure AMR kernel

PENMAN AST/parser, semantic graph, inversion, reification table, validators, canonical printer, graph isomorphism, ScalaCheck laws. No LLM and no story ontology.

### Phase B — Schema and source alignment

Versioned PropBank importer, role checking, source atlas, LEAMR-like alignments, standard fixtures, differential tests.

### Phase C — Document composition

Disjoint-union mention graph, typed entity/event mention IDs, exact-coreference partitions, prospective/retrospective/summary/bridging links, quotient map, UMR-style time/modal layers.

### Phase D — Narrative projection

Canonical entities/situations, contexts, cause/goals, state changes, scenes/episodes, discourse/world-time distinction.

### Phase E — LLM and vector providers

Effectful interfaces:

- `AmrParser[F].candidates(SentenceInput): F[NonEmptyVector[AmrCandidate]]`;
- `AmrCritic[F].review(candidate): F[Vector[AmrProposal]]`;
- `DocumentResolver[F].propose(MentionGraph): F[DocumentProposals]`;
- `FragmentEncoder[F].encode(Stream[AmrFragment]): Stream[FeatureView[AmrFragmentRef]]`.

Recommended toolkit: Cats Effect, FS2, `ValidatedNec`, Cats Parse, MUnit, ScalaCheck, Discipline-style laws.

---

## 54. Evolution from Part 2 to Part 3

| Part 2 element | Part 3 refinement |
|---|---|
| Generic local semantic extraction | Standards-compatible sentence/clause AMR is the local semantic kernel |
| Source atlas + situation candidates | AMR alignments become an evidence sidecar tied to the atlas |
| Predicate + participant roles | Derived/projected from AMR frames/edges, then expressed in stable narrative ADTs |
| Global canonicalization | Disjoint mention graph + typed exact-coreference quotient + nonidentity reference modes |
| Context construction | UMR-style modal/document dependencies plus explicit narrative `ContextualAssertion`s |
| Feature spaces | Multiple views over aligned AMR fragments; vectors excluded from graph identity |
| LLM provider | Typed AMR/document proposals and atomic patches only |
| Structural laws | Expanded into syntax, isomorphism, normalization, schema, composition, and patch law suites |
| `story-model` modules | A reusable AMR subsystem/library becomes an architectural prerequisite |
| WOG sanity fixture | Gains exact AMR/document/coreference/modal-scope gates |

Part 3 also resolves several Part 2 questions provisionally:

- `Intention(holder)` should exist explicitly in the context vocabulary.
- Mentions and canonical entities/events are distinct first-class objects linked by \(\pi\).
- Exact identity alone forms the quotient; prospective, retrospective, summary, partial, bridging, and thematic links do not.
- Participant roles at story level are relations projected from AMR rather than duplicated fields on event nodes.
- AMR alignments supply a natural home for exact/fuzzy source-to-semantic evidence, though the final placement of Part 1's continuous \(h_v(t)\) remains open.

---

## 55. Additional reconciliation questions from Part 3

1. **Build order conflict:** Part 2 says manually encode WOG and test the story ontology before extractor work; Part 3 says build a pure AMR kernel before any story ontology. Decide whether these are parallel RFC tracks, whether the WOG schema spike precedes coding, or whether AMR kernel coding truly comes first.
2. **Standalone scope:** decide repository/package boundaries for the reusable AMR library versus `story-model`; determine whether it is a multi-module build, separate published library, or initially internal modules with clean boundaries.
3. **PENMAN fidelity:** define exact treatment of whitespace, comments, metadata ordering, alignments, quoting, and malformed-but-recoverable syntax; “lossless or nearly lossless” must become a declared profile.
4. **Graph identity:** specify canonical node IDs and exact artifact equality independently of PENMAN variables, semantic isomorphism, and Smatch.
5. **Shape typing:** integrate connected/acyclic/reified-acyclic refinements with the existing `AmrGraph[CheckState,RoleForm]` type without excessive type-state complexity.
6. **Cycle corpus:** verify legitimate AMR cycles and ensure tests distinguish valid rare cycles from invalid structural loops.
7. **Argument ranges:** verify whether hard-bounding `ArgIndex` to 0–5 is correct for all supported lexicon versions/extensions.
8. **PropBank licensing/build:** define acquisition, generation, version pinning, license redistribution, and offline fallback for frame resources.
9. **Generated builders:** decide whether ergonomic per-frame source generation belongs in v0.1 or is deferred; it must not compromise binary/source compatibility.
10. **Schema profiles:** specify behavior for unknown frames, unknown standard roles, extensions, duplicate triples, and corpus-version deviations.
11. **Reification normalization:** choose canonical reified/dereified form for equality/serialization and document precisely when round trips are lossy.
12. **Reference ontology:** reconcile Part 2 `ReferenceMode` (`Denotes`, `Anaphoric`, etc.) with Part 3 (`ExactIdentity`, prospective, retrospective, summary, partial, bridging, thematic). Likely separate mention-to-AMR alignment, coreference, and narrative-reference families.
13. **Kind hierarchy:** clarify whether states are a subtype of situations at the type level; the sketches currently give both `SituationK` and `StateK` sibling markers.
14. **Quotient materialization:** decide whether canonical story graph is physically quotient-built or a derived indexed view over mentions and partitions, especially to preserve provenance and stable IDs.
15. **UMR compatibility target:** pin a UMR version/schema and decide import/export fidelity versus conceptual compatibility only.
16. **AMR versus UMR sentence additions:** decide where tense/aspect/person/number/modality live when importing plain AMR versus UMR-enriched sentence graphs.
17. **Projection multiplicity:** specify cardinalities and conflicts for many AMR nodes to one narrative node and one AMR node to multiple possible projections.
18. **Unmapped nodes:** formalize whether unmapped AMR nodes are expected, ignored, explicitly categorized, or warned on.
19. **Context projection:** define how AMR embedded propositions become contextual assertions without duplicating event identity or treating embedded content as root fact.
20. **Alignment evidence:** reconcile LEAMR-like token/subgraph alignment, Part 2 exact `SpanSet`, and later soft temporal support; preserve both standards compatibility and scientific localization.
21. **Parser strategy:** determine whether v0.1 implements only PENMAN parsing or also semantic AMR parsing from text; the dedicated AMR parser may be local, remote, imported, or multiple.
22. **Differential environment:** Python Penman can be a development/test oracle while the released Scala core remains Python-free; make this boundary explicit in CI and release gates.
23. **Canonical gloss:** define conservative gloss generation, provenance, determinism/caching, and how it avoids introducing facts absent from AMR.
24. **Smatch implementation:** decide whether to implement, port, or call an oracle for baseline Smatch; define determinism and approximate-search tolerances.
25. **Soft structural matching:** keep the Part 1 transport objective as the global method; specify how local weighted Smatch/AMR compatibility becomes its cost rather than a competing aligner.
26. **Law framework:** decide whether Discipline is a public testkit dependency, whether laws are exported for provider implementations, and how invalid generators isolate one violation at a time.
27. **Core Cats dependency:** Part 3 strengthens use of Cats data/parse/validation. Decide which modules intentionally expose Cats types and which APIs remain standard-library-based.
28. **Public naming:** working packages alternate between `narrative.amr`, `amr-*`, and `story-model-*`; settle organization/artifact IDs only in the final plan.
29. **Evidence verification:** all standards and dataset claims in Part 3 need primary-source checks before becoming implementation requirements.
30. **Plan status:** Part 3's phases A–E refine and partly reorder Part 2 milestones; neither sequence is final until remaining discussion parts are incorporated.

---

## 56. Updated clean architectural statement after Part 3

\[
\boxed{\text{AMR}=\text{typed local propositional semantics}}
\]

\[
\boxed{
\text{StoryModel}
=
\text{AMR local charts}
+
\text{cross-sentence identity}
+
\text{modal and temporal structure}
+
\text{causal and hierarchical structure}
+
\text{continuous feature views}
}
\]

The intended implementation is interoperable at the AMR/UMR boundary, strongly typed at stable narrative boundaries, data-driven where semantic inventories evolve, evidence-backed throughout, and open to ambiguity rather than forcing premature canonical truth.

---

# Part 4 Detailed Record: Autobiographical Interview Application

## 57. Central reinterpretation

The traditional Autobiographical Interview already encodes an implicit alignment task. Each informational detail is judged by:

1. whether it belongs to the central/nominated autobiographical episode;
2. if so, whether it concerns event, place, time, perception, or thought/emotion.

Other episodes, semantic facts, repetitions, metacognitive statements, and editorial comments are treated as external.

The richer interpretation is:

\[
\boxed{
\text{AI-internality}(d)
\approx
\text{target-episode membership}(d)
\times
\text{episodic specificity}(d)
}
\]

“Internal/external” is therefore not a fundamental sentence property. It is a task/scoring-policy projection.

Examples exposing the collapse:

- a detailed memory of a different birthday: strongly episodic, but external to the currently scored birthday;
- “We always went out for birthdays”: highly relevant personal knowledge/repeated-event schema, but not one bounded occurrence;
- “I vividly remember the red curtains”: linguistically episodic and possibly phenomenological, but could originate in rehearsed family narrative rather than genuine re-experiencing.

The new system should preserve these distinctions and derive the traditional binary/composite afterward.

---

## 58. Known-source versus latent-source recall

Story/movie recall observes source model \(\mathcal S\) and infers:

\[
P:\mathcal R\rightsquigarrow\mathcal S.
\]

An ordinary autobiographical interview observes:

- cue or requested life period;
- interviewer prompts/probes;
- participant narrative;
- optional subjective ratings;
- sometimes photos, diaries, calendars, or collateral evidence.

The historical event itself is normally unobserved. Therefore infer episode and alignment jointly:

\[
\boxed{
(\widehat{\mathcal E^\star},\widehat P)
=
\arg\max_{\mathcal E,P}
p(\mathcal E,P\mid R,Q)
}
\]

- \(R\): participant narrative;
- \(Q\): cue plus ordered interviewer probes;
- \(\mathcal E^\star\): latent target episode implied by the narrative;
- \(P\): soft detail mapping into target, other episodes, knowledge, or discourse.

This is reconstruction of the **narrated/implied episode**, not historical truth.

If an event is staged, prospectively recorded, lifelogged, or otherwise independently known, replace the latent episode with an observed source representation and use full source-to-recall alignment. Ordinary AI scoring measures event-specific narrative content, not veridicality.

---

## 59. Four axes traditional scoring partly collapses

### 59.1 Target-event membership

\[
\alpha_i=p(d_i\in\mathcal E^\star\mid R,Q).
\]

Does the detail belong to the nominated episode? This most directly controls traditional internality.

### 59.2 Episodic specificity

\[
\sigma_i\in[0,1].
\]

Does the detail denote a unique spatiotemporally bounded occurrence rather than a repeated/categorical event, extended period, autobiographical fact, or general fact? A different one-time event can have high \(\sigma_i\), low \(\alpha_i\).

### 59.3 Phenomenological re-experiencing

Evidence can include:

- explicit subjective ratings;
- remember/know-style judgment;
- first-person experiential language;
- source-monitoring statements;
- optional behavioral/neural measures.

Do **not** infer re-experiencing from internal-detail count. Content satisfying AI criteria can occur without reported re-experiencing; strong re-experiencing can coexist with few verbalized details.

### 59.4 Veridicality

Did the detail occur? Usually unknown without an independent source. Quantity and accuracy can dissociate in controlled real-world recall; Part 4 cites a study with 93–95% accuracy despite quantity declining with age/delay, which needs verification.

Core scientific constraint:

\[
\boxed{
\text{event specificity}
\neq
\text{re-experiencing}
\neq
\text{accuracy}
}
\]

Traditional AI primarily operationalizes target membership and specificity from narrative content.

---

## 60. Autobiographical interview representation

\[
\mathcal A=
\left(
X,Q,M,
\mathcal E^\star,
\{\mathcal E_k\},
K_P,K_G,
D,P,\Gamma
\right).
\]

| Component | Meaning |
|---|---|
| \(X\) | Exact transcript atlas: speakers, spans, audio times, interview phase |
| \(Q\) | Cue, interviewer turns, general probes, specific probes |
| \(M\) | Participant-utterance local semantic mention graph |
| \(\mathcal E^\star\) | Inferred target-episode graph |
| \(\{\mathcal E_k\}\) | Other specific/extended episodes mentioned |
| \(K_P\) | Personal knowledge: facts, self-knowledge, routines/repeated events |
| \(K_G\) | General semantic knowledge |
| \(D\) | Metacognitive/editorial/evaluative/repair/conversational discourse |
| \(P\) | Soft assignment of detail atoms to these destinations |
| \(\Gamma\) | Evidence, confidence, alternatives, provenance, corrections |

### 60.1 Target episode graph

\[
\mathcal E^\star=(V_E,V_S,V_P,V_L,V_T,R)
\]

with events/actions, states, people/objects, locations, temporal anchors, and relations.

Relations include:

- participant roles;
- time/order/overlap;
- spatial containment/movement;
- cause/enabling;
- goals/intentions;
- perceptual access;
- thought/emotion;
- social interaction;
- source monitoring.

Example: “When they brought out the cake, everyone started singing, and I felt horribly embarrassed” yields bring-out, singing, and speaker-embarrassment nodes with temporal ordering and a possible explanatory/causal path from cake/singing to embarrassment. Traditional scoring counts several internal details; the graph also records integration.

### 60.2 Fuzzy episode boundaries

Wedding recall may include preparation, ceremony, reception, drive home, or next-morning conversation. Membership depends on cue, narrative framing, temporal scale, and scoring convention. Preserve \(p(d_i\in\mathcal E^\star)\), including competing boundary interpretations, rather than forcing arbitrary membership.

---

## 61. Traditional AI categories as derived destinations

| Traditional category | Structural destination |
|---|---|
| Internal event | Target event/action/participant/object/state |
| Internal time | Target temporal anchor/relation |
| Internal place | Target location/spatial relation/movement |
| Internal perceptual | Sensory property/experience attached to target |
| Internal thought/emotion | Speaker mental state during target |
| External event | Different specific or extended episode |
| External semantic | Personal or general semantic knowledge |
| Repetition | New mention adding no proposition beyond an existing one |
| Other/editorial | Metacognitive, evaluative, conversational, task discourse |

External content is heterogeneous. Part 4 cites finer external-content work/NExt taxonomy as more sensitive to neurodegenerative profiles than a unitary external score; this needs primary-source verification.

Traditional external total is a **versioned derived sum**, never the primitive ontology.

---

## 62. AMR projection into countable DetailAtoms

The Part 3 AMR kernel transfers directly to participant utterances, providing predicates, roles, entities, states, negation, embedded speech/thought, and reentrancy.

AMR triples are **not** counted directly as AI details: one manual detail has no stable one-edge/one-node equivalence. Instead project AMR fragments into typed atoms:

```scala
enum DetailAtom:
  case EventOccurrence(event: SituationId)
  case ParticipantFact(event: SituationId, role: ParticipantRole, entity: EntityId)
  case AttributeFact(target: NarrativeNodeId, attribute: Attribute)
  case TemporalFact(relation: TemporalClaim)
  case SpatialFact(relation: SpatialClaim)
  case PerceptualFact(
    experiencer: EntityId,
    modality: SensoryModality,
    content: NarrativeNodeId
  )
  case MentalStateFact(holder: EntityId, state: MentalState)
  case RelationalFact(relation: NarrativeRelation)
```

A learned and rule-constrained projection estimates how many traditional details a fragment expresses. Document-level layers—not standard AMR—retain tense, realization versus future/desire/imagination/hypothesis, episode identity, and interview discourse.

---

## 63. Factorized assessment and backward-compatible projection

Each atom/detail receives:

```scala
final case class DetailAssessment(
  atom: DetailAtom,
  address: Distribution[MemoryAddress],
  facets: Distribution[DetailFacet],
  specificity: ScoreEstimate,
  experientialEvidence: ExperientialEvidence,
  epistemicStatus: EpistemicStatus,
  promptContext: PromptContext,
  sourceMonitoring: Option[SourceMonitoring],
  claim: ClaimMeta
)
```

### 63.1 Memory destinations

`MemoryAddress`:

- `Episode(id, scope)`;
- `PersonalKnowledge(kind)`;
- `GeneralKnowledge`;
- `Discourse(function)`;
- `Unresolved`.

`EpisodeScope`:

- `TargetSpecific`;
- `OtherSpecific`;
- `Extended`;
- `RepeatedOrCategoric`.

`PersonalKnowledgeKind`:

- `AutobiographicalFact`;
- `SelfKnowledge`;
- `RelationshipKnowledge`;
- `LifePeriodKnowledge`;
- `HabitOrRoutine`.

`DiscourseFunction`:

- `Metacognitive`;
- `Editorial`;
- `Evaluation`;
- `ConversationalRepair`;
- `Repetition(of: DetailId)`;
- `TaskCommentary`.

Traditional scores derive through a versioned policy:

```scala
def traditionalAiScores(
  model: InterviewModel[Validated],
  policy: AiScoringPolicy
): AiCompatibleScores
```

This preserves comparability without letting the historical scoring scheme define the ontology.

---

## 64. Joint target-episode induction

This is document inference, not independent sentence classification.

### 64.1 Seed

Use cue/requested life period, participant-provided event label/title, and first coherent event cluster.

### 64.2 Candidate clusters

Link details through shared time, place, participants, objects, event coreference, causal continuity, goals, and local narrative continuity.

Embeddings propose candidates but cannot decide identity. “At my wedding we danced until midnight” versus “At my sister's wedding the year before, we danced until midnight” has similar content but distinct episode identity.

### 64.3 Select/retain target interpretations

The target cluster best satisfies cue compatibility, bounded time/place, participant continuity, narrative prominence, causal/goal coherence, and participant event framing. Competing plausible targets remain explicit rather than being discarded.

### 64.4 Route remaining details

Map to another specific episode, extended period, repeated-event schema, personal knowledge, general knowledge, or metacognitive/editorial discourse.

---

## 65. Output products

Return both:

1. strict AI-compatible score sheet;
2. multidimensional autobiographical-memory profile.

### 65.1 Expected compatible counts

Let \(w_i\) be expected manually countable detail mass and \(\alpha_i=p(TargetSpecific\mid d_i)\):

\[
\widehat N_{internal}=\sum_iw_i\alpha_i.
\]

For external class \(c\):

\[
\widehat N_{external,c}=\sum_iw_i p(z_i=c).
\]

Expected counts can be fractional and interval-valued, e.g. \(31.8\,[29.4,34.6]\). Produce conventional hard counts only when required.

### 65.2 Rich profile

| Measure | Interpretation |
|---|---|
| Target-event mass | Content assigned to nominated episode |
| Episodic density | Target mass per participant word or speaking time |
| Event purity | Target detail relative to other specific episodes |
| Spatiotemporal anchoring | Precision/connectivity of time and place |
| Perceptual profile | Visual/auditory/spatial/tactile/motor/olfactory/interoceptive content |
| Mental-state profile | Target thoughts/emotions/intentions/beliefs/uncertainty |
| Relational integration | Target mass in temporal/causal/goal/participant relations |
| Fragmentation | One integrated target graph versus disconnected fragments |
| Semanticization | Personal facts/self-knowledge/routines/repeated/general facts |
| Other-event drift | Mass assigned to other autobiographical episodes |
| Redundancy | Exact/paraphrastic repetition |
| Prompt gain | New target-specific content after interviewer support |
| Phenomenological evidence | Explicit seeing/hearing/feeling/reliving versus knowing |
| Source monitoring | Direct memory/inference/hearsay/photo/family folklore |
| Accuracy/omission | Only when independent source exists |

### 65.3 Episodic density

\[
D_{episodic}=\frac{\widehat N_{internal}}{\text{participant words}}
\]

or a speech-time denominator. Part 4 cites psychometric evidence for reliability/convergent validity, weak simple two-factor structure, and benefit from word-count adjustment; verify before using as a requirement. Report both counts and density; neither silently replaces the other.

### 65.4 Event purity

\[
Purity=\frac{M_{target}}{M_{target}+M_{other-specific}}.
\]

Separates low-output interviews from vivid but episode-drifting interviews.

### 65.5 Relational integration and fragmentation

Two accounts with equal internal counts can differ sharply in time, causality, participant continuity, goals, and state transitions.

\[
G_{integration}
=
\frac{\text{target mass in accepted narrative relations}}
{\text{total target mass}}.
\]

\[
F
=
1-
\frac{|V_{largest\ target\ component}|}{|V_{target\ event}|}.
\]

### 65.6 Probe gain

\[
ProbeGain
=
\frac{M_{new,target,postprobe}}
{M_{target,free}+M_{new,target,postprobe}}.
\]

“New” excludes exact and paraphrastic repeats. Preserve separate spontaneous construction, general-probe response, specific-probe response, modality-specific gain, and prompt-induced repetition.

---

## 66. Birthday worked example

Cue: “Tell me about a specific birthday.”

Participant mentions fortieth-birthday dinner at a small French restaurant on Queen Street; rain and fogged windows; family birthday routine; prior-year Montreal trip; inability to remember food; cake, communal singing, embarrassment.

Assignments:

| Content | Structural interpretation |
|---|---|
| Fortieth birthday | Target episode + temporal anchor |
| Dinner | Target event |
| Small French restaurant/Queen Street | Target location + attributes |
| Rain | Target environmental/perceptual state |
| Fogged windows | Target visual detail |
| Family always goes out | Repeated-event/personal-semantic schema |
| Prior-year Montreal | Other specific episode |
| Cannot remember order | Metacognitive statement about missing target content |
| Waiter brings cake | Target event |
| Everyone sings | Target event/social action |
| Embarrassment | Target mental state |
| Cake/singing → embarrassment | Possible explanatory/causal link |

Traditional projection yields internal/external counts. Rich output additionally records precise place but limited clock time, strong visual/weather content, one episode excursion, one routine statement, explicit retrieval failure, integrated social/causal ending, absent food information, and moderate overall integration.

---

## 67. Responsibility boundaries

### 67.1 LLMs

Propose detail boundaries, canonical propositions, target versus other episode, personal-semantic interpretation, mental states/source monitoring, summaries/metacognition, time/cause, and alternatives.

### 67.2 Embeddings

Retrieve episode-coreference candidates, detect paraphrastic repetition, cluster episode mentions, measure cue drift, find related/nonidentical episodes, and link segment summaries to detail atoms.

Embeddings alone cannot decide role reversal, negation, target versus similar birthday, direct memory versus hearsay, or target detail versus similar other event.

### 67.3 AMR/structured semantics

Local event/state structure, semantic roles, negation, embedded speech/thought, shared participants. Document layers add coreference, clustering, time, modality, target membership, knowledge stores, and interview discourse.

### 67.4 Deterministic code

Own transcript spans, speaker/phase identity, exact repetition, graph/time laws, evidence, arithmetic, calibration representation, receipts, and reproducibility.

### 67.5 Optional audio

Separate retrieval-dynamics features: latency, pauses, rate, self-repair, filled pauses, prompt-response timing, prosodic uncertainty. Never collapse these into content scores; speech/language/hearing/motor/interviewer factors also influence them.

---

## 68. Existing automation and claimed novelty

Part 4 cites:

- a 2024 DistilBERT system estimating per-sentence internal/external proportions and aggregating predicted words, with strong manual-score associations; internal/external word counts served as useful detail proxies, while subtype work still required detail segmentation; punctuation/uninformative speech affected some datasets;
- a 2025 LLaMA-3 system for past/future narratives reporting up to \(r=.87\) internal and \(r=.84\) external correlations.

These studies need exact citation/method verification. They support aggregate-score automation feasibility but do not establish proposition identity, target episodes, heterogeneous external subtypes, relations, paraphrastic repetition, probe effects, causal correctness of totals, phenomenology, or accuracy.

Claimed novelty is therefore not “LLM automation of AI scores.” It is:

\[
\boxed{\text{an evidence-backed structural model of autobiographical recall}}
\]

with traditional totals as one derived view.

---

## 69. Population model

For subject \(s\), memory \(m\), interview phase \(p\), class \(c\):

\[
N_{smpc}\sim NegBin(\mu_{smpc},\phi_c)
\]

\[
\log\mu_{smpc}
=
\log W_{smp}
+\theta_{sc}
+x_{sm}^{\top}\beta_c
+\gamma_{pc}
+u_{site,c}
+u_{interviewer,c}.
\]

- \(W\): words/speaking time offset;
- \(\theta\): subject tendency for dimension;
- \(x\): memory age, importance, emotionality, rehearsal, cue traits;
- \(\gamma\): probe phase;
- random terms: site/interviewer.

This separates content, verbosity, event traits, support, administration, and measurement noise.

Possible empirically learned traits: target-event construction, spatiotemporal anchoring, perceptual reinstatement, semanticization, discourse control, prompt responsiveness. Do not assume two internal/external latent factors exhaust variability.

---

## 70. Validation program

High correlation with totals can arise from verbosity; validation must be multilevel.

### 70.1 Detail level

Evaluate boundaries, target membership, internal/external subtype, repetition identity, event coreference, spans, and calibration using span precision/recall/F1, rare-class macro-F1, Brier/ECE, and performance relative to expert disagreement.

### 70.2 Narrative level

Report absolute count error, ICC, Bland–Altman bias, internal/total ratio, density, phase scores, and participant rank. Correlation alone is insufficient.

### 70.3 Structural level

Evaluate target cluster recovery, entity/event coreference, temporal/cause/goal relations, episode boundaries, fragmentation/integration, and personal-semantic subtypes.

### 70.4 Generalization

Use leave-study/site/interviewer-out tests; held-out age/diagnostic groups; transcription convention shifts; ASR versus manual; dialect/multilingual evaluation. Prevent participant- and dataset-level leakage.

### 70.5 Construct/incremental validity

Compare held-out models:

\[
Y\sim traditional\ AI+word\ count
\]

versus:

\[
Y\sim traditional\ AI+word\ count+graph\ features.
\]

Enhanced measures earn “more powerful” only if graph features improve held-out prediction or theoretical discrimination.

Candidate outcomes: hippocampal/default-network measures, longitudinal cognition, standard episodic memory, vividness/reliving, clinical status, future-event construction, everyday memory.

Diagnostic prediction needs counterfactual paraphrase tests and explicit language/speech covariates to rule out shortcuts through age, vocabulary, syntax, or diagnostic language.

---

## 71. Staged/prospectively recorded event benchmark

Known-source events provide decisive validation:

- scripted tours;
- standardized procedures;
- museum visits;
- prospectively recorded personal events;
- lifelogged events;
- controlled social interactions.

Construct source \(\mathcal S_{event}\) before recall and directly score correct detail, distortion, omission, role error, time-order error, causal error, compression, blending, and conditional accuracy. Part 4 cites controlled-real-world evidence linking temporal recall organization and episodic-detail richness; verify.

Three evidence regimes:

1. **Natural autobiographical:** specificity, target organization, semanticization, phenomenology, narrative structure; no historical-accuracy claim.
2. **Repeated interview of same event:** retention, loss, additions, reframing, consistency, structural evolution; consistency still does not imply truth.
3. **Staged/prospective known source:** all above plus measured accuracy, omission, misorder, distortion.

---

## 72. Part 4 proposed application phases

These phases concern an Autobiographical Interview product/application and must not be confused with Part 2's source-story “Phase 1.”

### AI Phase 1 — Exact backward-compatible scorer

Transcript/prompt atlas, detail segmentation, classic internal subtypes/external categories, span evidence, expected/hard counts, confidence/review. Goal: approximate expert scoring at expert inter-rater levels.

### AI Phase 2 — Factorized external taxonomy

Other specific, extended, repeated/categorical event; autobiographical fact; self/relationship knowledge; general fact; metacognition; editorial/evaluation; repetition/repair.

### AI Phase 3 — Latent episode graph

Entity/event coreference, target induction, fuzzy boundaries, temporal/causal/goal/spatial/mental-state relations, sensory profiles, fragmentation/integration.

### AI Phase 4 — Retrieval dynamics

Free/probe provenance, prompt gain, latency, pauses/repairs, interviewer effects, source-monitoring language.

### AI Phase 5 — Known-source mode

Staged-event source graphs, source-recall alignment, accuracy/omission, chronology deformation, distortion/blending.

---

## 73. Privacy and scientific governance

Autobiographical narratives are highly identifying. Required support:

- local/institution-controlled inference;
- pseudonymized entities preserving coreference;
- relative rather than absolute dates where feasible;
- typed replacements for people/places/organizations/relationships;
- no silent external-provider transmission;
- provider/model receipts;
- encrypted raw transcripts;
- raw text separated from derived graph artifacts;
- explicit provider-retention policies.

Naive redaction destroys structure: replacing all people with `[PERSON]` collapses identity. Prefer stable relational pseudonyms such as `[PERSON_1]`, `[PERSON_2]`, `[SISTER_OF_SPEAKER]`.

---

## 74. Evolution from Parts 1–3 to Part 4

| Existing framework | Autobiographical specialization |
|---|---|
| Known source graph + recall graph | Latent target episode jointly inferred with detail mapping |
| External open-world states | Other episodes, personal knowledge, general knowledge, discourse, unresolved |
| Partial source grounding \(\alpha_i\) | Target-episode probability \(\alpha_i\) |
| Hierarchical gist/detail | Specific, extended, repeated/categorical episode scopes plus detail facets |
| Recall discourse function | Metacognition, evaluation, repair, repetition, task commentary |
| AMR local charts | Participant utterance AMRs projected to DetailAtoms |
| Context/modal layers | Memory source, inference, hearsay, family narrative, prompt context |
| Recall signature | AI-compatible totals plus multidimensional autobiographical profile |
| Known-source validation | Available only for staged/prospectively represented events |
| Population recall flow | Hierarchical counts/traits with word/time offsets and interviewer/site effects |

Part 4 strengthens several prior principles:

- accuracy cannot be inferred without an observed source;
- omitted verbal content does not establish unavailable memory;
- phenomenology is not synonymous with specificity or detail count;
- external content must remain typed/open-world rather than one residual bucket;
- traditional scores should be reproducible projections from richer artifacts;
- prompt/interviewer provenance is part of the process, not nuisance metadata;
- privacy requirements influence the identity and storage model from the start.

---

## 75. Reconciliation questions introduced by Part 4

1. **Product scope:** decide whether the autobiographical system is the first consumer of the reusable AMR/document/narrative libraries, a sibling product, or a new primary application replacing the story-builder-first sequence.
2. **Phase naming:** Part 2 and Part 4 both define “Phase 1.” Use explicit product prefixes (`Story Phase`, `AI Phase`) in the final roadmap.
3. **AI ambiguity:** “AI” can mean Autobiographical Interview or artificial intelligence. Public types/docs should prefer `AutobiographicalInterview`, `AiScoring` only in clearly scoped namespaces, or another unambiguous name.
4. **Shared versus specialized types:** determine which `StoryModel` node/relation types are reusable in `InterviewModel` and which need autobiographical types such as `MemoryAddress`, `EpisodeScope`, `PromptContext`, and source monitoring.
5. **Latent-source typing:** known-source `AlignmentSource` and latent `EpisodeModel` need different evidence semantics; avoid presenting inferred episode nodes as observed source facts.
6. **Joint-inference algorithm:** Part 4 states a joint posterior objective but does not choose EM, variational inference, clustering plus reranking, factor graph, or another algorithm.
7. **Cue/probe representation:** define exact transcript atlas fields, interviewer/participant turn IDs, general versus specific probe taxonomy, phase transitions, and optional audio timing.
8. **Detail segmentation:** define a detail boundary gold protocol and its relation to clauses, AMR fragments, LEAMR alignments, and multi-atom fragments.
9. **Count mass \(w_i\):** specify how learned/rule-constrained AMR-to-detail projection produces expected manual count mass, uncertainty, and subtype facets.
10. **Traditional policy versions:** identify scoring-manual versions/conventions, allowable category differences, phase handling, and backward-compatible projections.
11. **NExt taxonomy:** verify the proposed external subtype scheme and decide whether to adopt, map, or independently define it.
12. **Specificity scale:** operationalize continuous \(\sigma_i\) and its calibration against specific, extended, repeated, personal-semantic, and general-semantic judgments.
13. **Phenomenology evidence:** define explicit evidence types and prevent textual style from being mislabeled as re-experiencing.
14. **Source monitoring:** settle vocabulary for direct memory, present inference, hearsay, photos, diaries, family story, repeated rehearsal, and unresolved origin.
15. **Episode boundary policy:** determine how cue definitions and manual scoring rules constrain fuzzy membership and how multiple plausible target clusters are serialized.
16. **Multiple episodes:** decide whether \(\{\mathcal E_k\}\) shares the complete target graph schema or uses lighter episode summaries until needed.
17. **Knowledge representation:** define how personal and general semantic knowledge relate to AMR mentions and canonical entities without being mistaken for events/states in the target episode.
18. **Repetition:** distinguish exact text repetition, semantic paraphrase, elaboration adding a facet, repair/correction, and interviewer-induced restatement.
19. **Integration metric:** mass-weighted graph participation and largest-component fragmentation require precise node/edge inclusion, confidence thresholds, and handling of isolated but valid details.
20. **Prompt gain denominator:** define zero-free-recall cases, negative corrections, partial repetitions, and confidence propagation.
21. **Density denominators:** specify participant-word counting, disfluencies, ASR normalization, language-specific tokenization, and speaking-time rules.
22. **Expected-count intervals:** choose posterior/bootstrap/calibration method and define how hard counts are derived for compatibility.
23. **Audio boundary:** decide whether audio features live in core artifact, optional sidecar/module, or downstream retrieval-dynamics package.
24. **Population module:** determine whether the negative-binomial/latent-trait models belong to library code, benchmark examples, or a separate statistical package.
25. **Known-source bridge:** define how a staged autobiographical event becomes a `StoryModel`/`AlignmentSource` without forcing text-only story assumptions.
26. **Privacy architecture:** decide encryption boundary, pseudonymization stage, key ownership, reversible/irreversible mappings, provider policies, and whether derived graphs remain identifiable data.
27. **Multilingual scope:** Phase 1 currently says arbitrary English story; Part 4 validation calls for dialect/multilingual evaluation. Decide release scope versus research roadmap.
28. **Evidence verification:** collect primary sources for classic AI scoring, phenomenology dissociation, controlled accuracy, NExt, psychometrics, automated DistilBERT/LLaMA scoring, and temporal organization before encoding scientific claims as gates.
29. **Acceptance thresholds:** “approximately expert inter-rater levels” needs concrete, category-specific agreement and uncertainty targets, not a single correlation.
30. **Final plan status:** Part 4 offers a compelling application roadmap but does not yet determine whether to build AMR, source-story modeling, or AI-compatible scoring first.

---

## 76. Updated overall judgment after Part 4

The traditional AI can be reproduced as:

\[
\boxed{
\begin{aligned}
\text{internal details} &= \text{target-episode alignment},\\
\text{other-event details} &= \text{other-episode alignment},\\
\text{semantic details} &= \text{personal/general knowledge alignment},\\
\text{repetitions} &= \text{repeat mapping to existing propositions},\\
\text{other details} &= \text{metacognitive/discourse alignment}.
\end{aligned}
}
\]

The richer latent dimensions are:

\[
\boxed{
\text{target membership}
+\text{specificity}
+\text{spatiotemporal anchoring}
+\text{sensory/mental content}
+\text{relational integration}
+\text{semanticization}
+\text{retrieval dynamics}
+\text{uncertainty}
}
\]

The primary artifact is an **evidence-backed autobiographical episode graph with probabilistic detail assignments**, not two model-generated numbers. Traditional scoring remains reproducible and backward-compatible, while the system stays explicit that an ordinary interview alone cannot establish genuine re-experiencing or historical truth.

---

# AMR Decision Checkpoint: Reference Material, Fit, and Value

## 77. Verdict

**Yes, AMR has sufficiently solid reference material to support a typed Scala kernel, but only as a pinned project conformance profile.** The ecosystem is mature enough for implementation and differential testing; it is not a single ISO-like standard with one authoritative grammar, ontology, corpus, and document model.

Recommended decision:

- keep AMR as the **canonical local propositional IR**;
- make AMR acquisition provider-pluggable and fallible;
- keep checked AMR charts plus exact alignments as evidence artifacts;
- project them into provider-neutral narrative nodes;
- do not make story/document truth depend on any one parser output;
- validate AMR's incremental value empirically before building every optional feature.

## 78. Verified reference stack

### 78.1 Semantic foundation

- [Banarescu et al. 2013, *Abstract Meaning Representation for Sembanking*](https://aclanthology.org/W13-2322/): conceptual foundation, neo-Davidsonian graph, abstraction from syntax, basic representation goals.
- [AMR 1.2.6 Specification](https://github.com/amrisi/amr-guidelines/blob/master/amr.md) (May 1, 2019): detailed annotation reference for concepts, roles, focus, inverse roles, negation, modality, quantities, reification, and many constructions.

The guidelines explicitly document limitations: English orientation; no cross-sentence coreference; dropped grammatical number, tense, and aspect; no source-token alignment requirement. They also call AMRs DAGs while documenting about 0.3% legitimate cycles, validating the proposed “connected graph first, acyclic refinement” design.

### 78.2 Frame semantics

- [PropBank frame repository](https://github.com/propbank/propbank-frames): versioned role/frame lexicon, including AMR/UMR-specific `-91` rolesets and CC-BY-SA-4.0-licensed frame data.

Pin a particular frame release/commit. Core argument numbers are predicate-specific; never globally equate `ARG0` with Agent. The guidelines list ordinary AMR core arguments through `ARG5`, but current frame resources include examples with `ARG6`, so `ArgIndex` must be lexicon-validated rather than hard-coded to 0–5.

### 78.3 Concrete syntax and implementation oracle

- [Penman 1.3.1 documentation](https://penman.readthedocs.io/en/latest/): mature parser/formatter/graph library with a formal grammar, AMR semantic model, trees, graphs, epigraphs, surface metadata, and transformation behavior.

Penman is a differential oracle, not the Scala runtime. A Scala implementation should compare parse/print/decode/canonicalization behavior against a pinned Penman version in development/CI while remaining Python-free in released core APIs.

### 78.4 Corpus and evaluation

- [AMR Annotation Release 3.0, LDC2020T02](https://catalog.ldc.upenn.edu/LDC2020T02): 59,255 English AMRs, document-preserving splits, multiple domains, guideline examples, and multi-sentence annotation. It is LDC-licensed and cannot simply be vendored or redistributed.
- [Cai & Knight 2013, Smatch](https://aclanthology.org/P13-2131/): baseline graph-overlap metric under variable alignment. Useful but insufficient for scientific source–recall compatibility.

Project testing needs an openly distributable small conformance fixture even if licensed AMR 3.0 is used in authorized development environments.

### 78.5 Text-to-graph evidence

- [LEAMR](https://github.com/ablodge/leamr): explicit subgraph, duplicate-subgraph, relation, and reentrancy alignment layers. This is the best direct precedent for the proposed scientific alignment sidecar.

LEAMR is token/span-oriented. The project should extend its principles to exact UTF-16 `SpanSet`s, sentence/token/clause IDs, discontinuous support, credence, and human adjudication without altering standards-compatible AMR graph identity.

### 78.6 Document semantics

- [UMR 0.9 Specification](https://github.com/umr4nlp/umr-guidelines/blob/master/guidelines.md) (August 8, 2022): sentence representation adapted from AMR plus document coreference, temporal dependencies, and modal dependencies; it also introduces sentence-level aspect and reference features.
- [Naseem et al. 2022, DocAMR](https://aclanthology.org/2022.naacl-main.256/): concrete evidence that document composition must avoid information-losing over-merging and incoherent under-merging.

Use UMR as a pinned compatibility/inspiration target rather than assuming it is a stable final standard or the complete narrative ontology. Preserve sentence AMRs, mention identity, canonical identity, and the quotient/projection map separately.

### 78.7 Parser/provider feasibility

Existing parsers include the [IBM transition-amr-parser](https://github.com/IBM/transition-amr-parser), [amrlib](https://github.com/bjascob/amrlib), and [SPRING](https://github.com/SapienzaNLP/spring). They demonstrate that automatic acquisition is feasible, but they are Python/PyTorch-centered, model-heavy, version-sensitive, and imperfect. They should sit behind `AmrParser[F]`; the project should not implement or train a text-to-AMR neural parser in Scala v0.1.

## 79. Exact architectural fit

```text
canonical text
  -> deterministic surface atlas
  -> sentence/clause windows
  -> one or more AMR candidates
  -> PENMAN decode + AMR validation + role canonicalization
  -> checked local AMR charts + source-alignment sidecars
  -> disjoint-union mention graph
  -> cross-sentence identity/modal/temporal layers
  -> evidence-backed narrative projections
  -> story/episode graph + hierarchy + trajectory
  -> recall alignment or autobiographical scoring
```

Boundary rules:

| AMR should own | AMR should inform | AMR should not own |
|---|---|---|
| Local predicates/frames | Situation candidate construction | Cross-sentence identity truth |
| Local participant arguments | Event/state projection | Story-world timeline |
| Negation and local scope | Context/modal proposals | Causal truth |
| Reentrancy/shared local arguments | Boundary/flow features | Scene/episode hierarchy |
| Local embedded propositions | Hybrid retrieval/reranking | Target autobiographical episode membership |
| Standards-compatible graph artifact | DetailAtom projection | Re-experiencing or historical accuracy |

The story model may retain AMR references as evidence, but its stable scientific APIs should expose narrative types. This prevents changes in AMR parser, frame release, or analysis from becoming changes in the meaning of the global story API.

## 80. Why AMR is useful here

### 80.1 High-value uses

1. **Role direction:** distinguishes “warriors struck the young man” from “young man struck the warriors.”
2. **Polarity:** distinguishes feeling ill from not feeling ill.
3. **Embedded propositions:** preserves that warriors *said* an injury occurred without promoting the injury to narrated-world fact.
4. **Reentrancy:** represents one entity participating in multiple roles/events.
5. **Paraphrase-stable local meaning:** reduces sensitivity to voice and many syntactic alternations.
6. **Common source/recall language:** source and recall can be locally parsed into comparable structures.
7. **Inspectability:** failures can be localized to frame, argument, polarity, context projection, or coreference rather than an opaque similarity score.
8. **Typed proposals:** parser/LLM output can be decoded, checked, patched, and receipted before acceptance.

### 80.2 Secondary uses

- interpretable boundary features from frame/entity/role/context turnover;
- canonical fragment glosses;
- graph-aware candidate retrieval;
- Smatch-like baseline comparisons;
- structured metamorphic tests for passive voice, role swaps, and negation.

### 80.3 What it does not solve

- document identity and event coreference;
- realized versus merely reported/intended/hypothetical truth without extra layers;
- narrative causality;
- event hierarchy;
- fuzzy target-episode boundaries;
- salience or memorability;
- phenomenological re-experiencing;
- accuracy without a known source.

AMR is therefore useful precisely because its scope is narrower than the full problem.

## 81. Main risks

1. **Specification fragmentation:** guidelines, frames, syntax behavior, alignments, and document semantics live in separate evolving resources. Mitigation: project conformance manifest with pinned versions/commits.
2. **Gold-data licensing:** AMR 3.0 is LDC-licensed. Mitigation: small redistributable fixtures plus optional licensed corpus tests.
3. **Parser error:** predicted AMR is evidence, not truth. Mitigation: candidate ledger, ensembles, validation, human review, alternative analyses.
4. **Scope creep:** building a complete parser or UMR implementation can consume the project. Mitigation: provider boundary and minimum useful subset.
5. **Ontology leakage:** global story APIs could become coupled to PropBank/AMR changes. Mitigation: evidence-backed projection into stable narrative ADTs.
6. **Overengineering before value proof:** a perfect AMR library might not improve downstream alignment enough. Mitigation: an early discriminative spike.

## 82. Recommended first proof

Before committing to the full AMR subsystem:

1. pin AMR 1.2.6, Penman 1.3.1, and a PropBank frame snapshot;
2. hand-author checked AMRs and exact alignments for the WOG fixture plus a compact adversarial set;
3. include paraphrases, passive alternations, role swaps, polarity swaps, reported-versus-actual propositions, prospective/realized events, and retrospective references;
4. compare embedding-only retrieval/reranking with embedding + AMR structural checks;
5. measure correct-target ranking, false acceptance of structural foils, failure localization, and annotation burden;
6. retain AMR as the canonical local kernel if it materially improves structural-foil rejection and interpretability.

This proof does not require a production AMR parser. Hand AMRs or imported provider candidates are enough to test whether the representation earns its place.

## 83. Implementation recommendation at this checkpoint

Build a **small but real AMR foundation**, not the entire AMR ecosystem:

- PENMAN parsing/printing sufficient for the pinned fixture/profile;
- canonical graph with direct roles;
- checked versus unchecked states;
- role inversion;
- frame/role validation from pinned data;
- exact source-alignment sidecar;
- isomorphism and essential laws;
- projection hooks into narrative nodes.

Defer:

- training a parser;
- exhaustive generated frame builders;
- full UMR import/export;
- complete Smatch optimization;
- every rare reification transform;
- broad corpus tooling.

The AMR kernel should be **first-class but acquisition-neutral**: a story build can import hand, parser, LLM, or ensemble AMR candidates, but only checked/canonicalized charts enter document composition.

---

# AMR Automation Checkpoint: No Expert Hand-Authoring Required

## 84. Revised decision

Do not make researcher-authored AMR a prerequisite. Separate three artifacts:

1. **Standards conformance gold:** existing examples from the AMR guidelines and, where licensed, AMR 3.0. These test PENMAN, graph laws, roles, reification, and codec behavior.
2. **Project silver AMR atlas:** automatically generated WOG/story AMRs with candidates, scores, evidence, disagreements, and repairs.
3. **Narrative acceptance fixture:** researcher-reviewed events, participants, contexts, temporal distinctions, and ambiguity in plain language. This tests scientific fitness without claiming new gold AMR annotation.

Only a qualified AMR annotator/adjudicator can turn new project AMRs into defensible AMR gold. That can be deferred or limited to an audit sample.

## 85. Automated acquisition pipeline

```text
sentence/clause + exact token IDs
  -> parser candidate(s)
  -> PENMAN decode
  -> role canonicalization
  -> structural + PropBank validation
  -> text/graph alignment
  -> independent model critique
  -> typed repair proposals
  -> candidate agreement/global selection
  -> plain-language review for unresolved cases
  -> checked silver AMR + full receipt
```

### 85.1 Candidate generation

Put current parser implementations behind:

```scala
trait AmrCandidateProvider[F[_]]:
  def candidates(
    input: SentenceInput
  ): F[NonEmptyVector[AmrCandidate]]
```

Initial providers can be:

- an optional local/containerized `transition-amr-parser` service;
- `amrlib`/SPRING as an alternative parser;
- a schema-constrained LLM candidate;
- imported PENMAN from another tool;
- later, a provider ensemble.

The Scala core does not require Python. Python/model runtimes are optional acquisition services returning receipted candidates.

### 85.2 Deterministic rejection and normalization

Before any semantic acceptance:

- decode PENMAN;
- resolve variables;
- normalize inverse roles;
- verify top and endpoints;
- check role/literal syntax;
- validate frames and predicate-specific arguments against the pinned lexicon;
- retain cycles when valid under the chosen profile;
- attach exact source/token support;
- reject or quarantine malformed candidates.

### 85.3 Independent semantic critic

Give a critic the original sentence, canonical candidate graph, relevant frame descriptions, and a narrow checklist:

- predicate/frame appropriate?
- participants assigned to correct roles?
- negation attached to correct proposition?
- speech/thought content embedded correctly?
- actual versus intended/reported/hypothetical status deferred appropriately?
- missing or hallucinated concepts?
- source spans adequate?
- plausible alternative AMR?

The critic emits typed findings/patches, not a trusted replacement graph.

### 85.4 Candidate scoring and selection

Candidate evidence can combine:

- provider score/log probability when meaningful;
- hard-law pass/fail;
- PropBank compatibility;
- agreement with other candidates under isomorphism/Smatch-like overlap;
- alignment completeness;
- critic findings;
- conservative graph-to-text gloss consistency;
- downstream narrative projection consistency.

High-agreement valid candidates can be accepted as silver. Disagreement, structural ambiguity, or context-sensitive cases enter review.

## 86. Human review without AMR expertise

Never ask the researcher to edit this:

```text
(s / strike-01 :ARG0 ... :ARG1 ...)
```

Render review cards such as:

```text
Source: “The warriors said that the young man had been struck.”

Speech event
  Speaker: the warriors
  Reported content: someone struck the young man
  Actual occurrence: unresolved
  Negation: none
  Evidence: exact highlighted source span

Questions
  [ ] Correct
  [ ] Wrong participant
  [ ] Report treated as fact
  [ ] Missing information
  [ ] Alternative interpretation
```

The system translates these task-level decisions into typed patches. Useful renderings include:

- short proposition gloss;
- event/participant table;
- context tree;
- source highlights;
- active versus alternative analysis;
- narrative consequences (for example, “accepting this would make injury a root-world fact”).

Researcher expertise is applied where it belongs: story interpretation and scientific scope, not AMR notation.

## 87. Minimum viable automation

Avoid starting with a costly full ensemble. The first useful stack is:

1. one established parser provider;
2. Scala PENMAN/AMR validation and normalization;
3. exact source alignment;
4. one independent LLM critic only for validation failures or scientifically important sentences;
5. plain-language review of flagged WOG cases;
6. candidate/patch/receipt storage.

Add parser ensembles only if the initial disagreement/error analysis shows value.

For WOG, automatically prioritize sentences containing:

- future battle announcement;
- one young man declining and the other joining;
- reported injury;
- negated felt illness;
- ghost belief/conclusion;
- retrospective recounting;
- death and uncertain cause.

These exercise the distinctions that justify AMR and document contexts.

## 88. Evaluation without newly hand-authored gold

### 88.1 Kernel correctness

Use published guideline examples, open small fixtures, differential Penman behavior, metamorphic laws, and optionally licensed AMR 3.0.

### 88.2 Project usefulness

Use automatically transformed sentence pairs with known expected changes:

- active/passive invariance;
- agent/patient swap;
- polarity flip;
- actual/future tense or modality change;
- direct/indirect speech transformation;
- repeated mention versus new event;
- paraphrase.

These do not require authoring full AMRs; they test whether the pipeline reacts locally and correctly.

### 88.3 Narrative task validation

Evaluate whether the projected model supports correct answers to researcher-readable questions:

- who acted on whom?
- was the proposition asserted, reported, intended, believed, or unresolved?
- is this the same event, a prospective description, or a retrospective reference?
- did polarity change?
- did embedding + AMR reject the structural foil?

### 88.4 Expert audit later

Before publication claims about AMR annotation accuracy, commission/collaborate on a stratified sample audit emphasizing flagged and high-impact cases. Report parser, critic, and adjudicator performance separately.

## 89. Consequence for the implementation plan

Replace “hand-author WOG AMR” with:

1. import official conformance examples;
2. build the checked AMR kernel;
3. integrate one parser provider;
4. auto-generate a receipted WOG silver atlas;
5. render plain-language narrative review cards;
6. adjudicate WOG's critical semantic/context cases at the story level;
7. run adversarial/metamorphic comparisons;
8. seek AMR-expert audit only when scientific claims require it.

This preserves ontology-first discipline: the WOG narrative fixture can still be designed and reviewed before prompt/model optimization, but the low-level AMR charts are machine-bootstrapped rather than manually authored by the researcher.

---

# Autonomous Agent-System Checkpoint

## 90. Non-negotiable product requirement

The production workflow is fully automatic:

\[
\boxed{
\text{source text}
\xrightarrow{\text{unattended build}}
\text{receipted StoryModel with calibrated uncertainty}
}
\]

No AMR expert, narrative annotator, or researcher is required during a build. Human work is restricted to offline activities:

- creating/adjudicating benchmark corpora;
- validating prompt packages and calibration models;
- auditing a release candidate;
- revising standards profiles;
- investigating failures after the system has already produced a complete diagnostic artifact.

The automated system may return `Unresolved` or competing alternatives for a claim. It may fail a validation gate. It must never invent precision merely to avoid asking a person.

## 91. Agent architecture

Agents do not exchange free-form essays or directly mutate the canonical graph. Each receives a bounded task packet and returns typed proposals with evidence.

```text
Build orchestrator
  ├── deterministic surface pipeline
  ├── local semantic workers
  │     ├── parser candidate provider
  │     ├── independent AMR proposal agent
  │     ├── frame/role critic
  │     └── source-alignment critic
  ├── document workers
  │     ├── entity coreference
  │     ├── event identity/reference
  │     ├── context/modal scope
  │     ├── temporal structure
  │     └── causal/goal proposals
  ├── hierarchy/trajectory workers
  ├── global consistency critics
  ├── deterministic resolver and validators
  └── artifact writer + build receipt
```

### 91.1 Deterministic orchestrator

Owns:

- stage dependency graph;
- stable task IDs and retries;
- bounded concurrency;
- cache keys and replay;
- provider budgets/timeouts;
- artifact/checkpoint storage;
- acceptance policy;
- final gate status.

Agents never decide which stages ran or whether their own answer is canonical.

### 91.2 Proposal-only agents

Every agent returns one of:

```scala
enum ProposalDisposition:
  case Proposed
  case Alternative
  case Abstained
  case Unsupported

final case class AgentProposal[A](
  taskId: TaskId,
  value: Option[A],
  disposition: ProposalDisposition,
  evidence: Vector[EvidenceRef],
  rawScore: Option[Double],
  conflicts: Vector[ConflictRef],
  receipt: AgentCallReceipt
)
```

No agent can create `Resolved[A]`, mark its score calibrated, or write directly into `StoryModel`.

### 91.3 Independent critics

Critics are specialized and preferably diverse:

- syntax/graph law critic;
- PropBank frame-role critic;
- source entailment/hallucination critic;
- polarity/modality/context critic;
- document identity critic;
- temporal consistency critic;
- causal overreach critic;
- hierarchy coherence critic.

They receive the candidate and exact evidence, not another agent's hidden reasoning.

## 92. Scaling to pages and long documents

Use a hierarchical map–reconcile–revisit process.

### 92.1 Map: local windows

- Deterministically segment paragraphs, sentences, clauses, quotations, and tokens.
- Build overlapping sentence windows so local context is retained.
- Produce sentence AMR charts independently while recording window context.
- Extract mentions and candidate relations against stable IDs.

### 92.2 Reconcile: document indexes

Build compact indexed artifacts:

- entity mention index;
- event/state mention index;
- locations and time expressions;
- context/speaker tree;
- semantic nearest-neighbor candidates;
- discourse-order neighborhoods;
- unresolved/conflict index.

Document agents retrieve only relevant candidates and source passages. They do not repeatedly reread the whole document or rely on lossy prose summaries.

### 92.3 Revisit: targeted nonlocal passes

After document proposals:

- revisit local AMRs affected by coreference/context decisions;
- reconsider prospective versus realized events;
- resolve retrospective reports;
- repair time/context contradictions;
- recompute only invalidated downstream stages.

Content-addressed stage caching makes this iterative process tractable.

## 93. Standards-grounded prompt packages

“Excellent prompts” are versioned, tested program artifacts rather than handcrafted prose stored in application code.

Each prompt package contains:

```text
task manifest
  role and scientific purpose
  exact typed input schema
  exact typed output schema
  permitted operations
  prohibited inferences
  relevant standards excerpts/IDs
  frame and role lookup tools
  positive examples
  contrastive counterexamples
  abstention and alternative rules
  self-check checklist
  prompt version and checksum
  benchmark suite and expected behavior
```

Prompt requirements:

- refer only to stable sentence/token/node IDs;
- never invent character offsets;
- distinguish explicit text from entailment/inference/hypothesis;
- emit alternatives when evidence supports them;
- treat raw confidence as uncalibrated;
- avoid global claims outside the assigned task;
- quote or cite source spans through IDs, not free-text reconstruction;
- return schema-valid data or explicit abstention.

Standards retrieval should be narrow and deterministic: the agent receives the relevant AMR guideline section, PropBank `FrameSpec`, UMR relation definition, or project policy selected by code. It is not asked to remember the entire standard from model weights.

## 94. Automated resolution

For candidate claim \(c\), collect:

- provider proposals;
- structural validity;
- frame-role compatibility;
- source support;
- agreement/disagreement;
- critic findings;
- document coherence;
- calibrated family-specific evidence model.

The resolver returns:

```scala
enum ResolutionState[+A]:
  case Accepted(value: A, probability: Probability)
  case Alternatives(values: NonEmptyVector[Weighted[A]])
  case Unresolved(reason: ResolutionFailure)
  case Rejected(reason: RejectionReason)
```

Only offline gold data can calibrate probabilities and thresholds. Agents do not vote by simple majority, and an LLM judge does not have unilateral final authority.

High-impact claim families use conservative policy:

- reported proposition promoted to root-world fact;
- event coreference/duplication;
- role reversal;
- polarity;
- strict temporal precedence;
- causal edges;
- target-episode membership.

## 95. Automatic verification loops

Every build executes proportional verification:

1. schema and graph laws;
2. source-span recovery;
3. frame/role checks;
4. context/modal consistency;
5. temporal-cycle and containment checks;
6. duplicate/event-reference checks;
7. targeted adversarial transformations for high-impact claims;
8. cross-agent disagreement analysis;
9. locality-of-change comparison against cached prior build when available;
10. artifact/receipt integrity.

The system can automatically generate controlled foils for a candidate:

- swap roles;
- flip polarity;
- move embedded proposition to root context;
- change intended to realized;
- duplicate a retrospective event;
- remove causal cue while preserving time.

Critics must prefer the source-supported candidate and explain the decision through typed evidence codes. Failure creates `Unresolved` or blocks validation.

## 96. Autonomy versus scientific validity

Fully autonomous inference and scientifically defensible evaluation are compatible if separated:

- **Runtime:** no human intervention.
- **Development:** human-labeled gold and adversarial tests calibrate components.
- **Release:** held-out, leave-story/study/provider-out evidence establishes performance.
- **Artifact:** uncertainty and alternatives are retained.
- **Claim:** do not call automatic output gold or historically true.

The success criterion is not “the system always emits one confident graph.” It is:

> The system always completes with a reproducible artifact whose accepted claims meet calibrated gates and whose ambiguity/failure is explicitly represented.

## 97. Revised implementation consequence

The plain-language review UI becomes an optional debugging/audit tool, not a production dependency.

The first autonomous vertical slice should:

1. ingest several pages unattended;
2. run one parser plus an independent structured proposal/critic agent;
3. validate/canonicalize AMR automatically;
4. compose mentions into entity/event/context candidates;
5. resolve WOG's critical cases through conservative automatic policy;
6. retain alternatives/unresolved claims;
7. build hierarchy/trajectory from accepted and weighted claims;
8. emit the full artifact and report without questions or manual edits;
9. compare against an offline adjudicated fixture and adversarial suite;
10. replay exactly from cached provider outputs.

The final plan must treat autonomous orchestration, prompt-package testing, calibration, failure representation, and long-document incremental processing as P0—not later operational hardening.

---

# AMR Boundary Review Checkpoint

## 98. Overall assessment of the reviewed position

Agreement is high on substance:

- keep role direction, polarity, embedded propositions, and reentrancy;
- use a shared local structure for source and recall;
- make agent output typed proposals rather than free-form story graphs;
- defer costly AMR features until they demonstrate downstream value;
- treat parser performance on spoken/disfluent recall as a serious risk;
- make frame identity optional evidence rather than a universal prerequisite.

Two corrections are required:

1. “AMR is an adapter” is an architectural change if the canonical object is no longer an AMR graph.
2. The discriminative spike cannot require researcher-authored AMRs and must test end-to-end automatic acquisition, not only oracle charts.

## 99. Recommended module boundary

Preferred dependency structure:

```text
core
  ↑
proposition              canonical local semantic contract
  ↑             ↑
amr-interop     provider-agent / provider-parser
  \             /
   acquisition candidates
          ↓
      document
          ↓
   story / recall / interview
          ↓
        align
```

### 99.1 `proposition`

Owns a partial, evidence-backed local chart:

```scala
final case class PropositionChart[C <: CheckState](
  focus: Option[ConceptId],
  concepts: Map[ConceptId, Concept],
  relations: Vector[PropositionRelation],
  embedded: Vector[EmbeddedProposition],
  alignments: Vector[PropositionAlignment],
  provenance: ChartProvenance
)
```

Minimum semantics:

- concept lemma/gloss;
- optional external frame reference and frame sense;
- numbered or named source role;
- optional normalized participant role;
- polarity;
- reentrancy/shared nodes;
- embedded propositions;
- explicit partial/unknown values;
- exact source support;
- proposal evidence and alternatives.

If the frame is absent, a numbered role is not silently interpreted. Either a normalized semantic role is supported separately or the role remains underspecified.

### 99.2 `amr-interop`

Owns:

- lossless-enough PENMAN syntax;
- standards-compatible AMR graph;
- AMR validation/canonical roles;
- conversion to/from `PropositionChart` where defined;
- retained original AMR artifact/provenance;
- differential Penman tests;
- later reification/Smatch/UMR compatibility as justified.

AMR-specific equality, isomorphism, frame lexicon behavior, and serialization do not leak into narrative APIs.

### 99.3 Providers

- clean prose profile: conventional AMR parser candidate plus structured agent critic/proposal;
- spoken recall/interview profile: schema-constrained proposition extraction as primary candidate, parser as optional independent evidence;
- actual routing/weighting learned from benchmark results rather than assumed.

## 100. Utility judgment by layer

| Layer | Judgment |
|---|---|
| Minimal proposition structure | Essential |
| AMR/PENMAN import and provenance | Valuable adapter |
| PropBank role descriptions | Valuable when available, never mandatory |
| Exact frame sense identity | Weak/optional evidence for most alignment tasks |
| Reification transforms | Deferred |
| Full Smatch implementation | Baseline/deferred |
| Full UMR import/export | Deferred; document concepts may still be borrowed |
| Off-the-shelf parser as sole acquisition path | Rejected |
| LLM extractor as unvalidated sole authority | Rejected |
| Multi-provider automatic candidate resolution | P0 direction |

## 101. Parser-skepticism qualification

The claim that existing AMR parsers will degrade on hedged, disfluent, first-person recall is highly plausible from domain shift but has not been established for this corpus in the current discussion. The stronger claim that an LLM schema extractor will outperform them is a benchmark hypothesis, not a design fact.

Measure separately:

- clean written story source;
- clean manually transcribed recall;
- disfluent transcript;
- ASR transcript;
- Autobiographical Interview turns;
- cue/interviewer speech versus participant speech.

The system should support acquisition profiles without hard-coding a winner.

## 102. Frame and role semantics

Frame senses should be optional, but not treated as wholly redundant. They can help:

- interpret numbered argument roles;
- distinguish genuinely different predicate meanings;
- compare paraphrases with different lemmas;
- produce better canonical glosses;
- diagnose parser disagreement.

However:

- parser sense errors must not block a usable chart;
- `DetailAtom`/narrative projection never depends on lexicon success;
- a bare `ARG0` without a frame does not mean Agent;
- normalized roles need their own evidence/credence;
- lemma, frame, role structure, and embeddings remain separate signals.

## 103. Revised discriminative spike

Use two linked experiments.

### 103.1 Oracle-structure value

Use official/gold AMR examples and automatically generated controlled variants to test whether proposition structure detects:

- role reversal;
- polarity change;
- embedded-versus-root assertion;
- reentrant participant identity;
- paraphrase invariance.

This establishes that the representation can express the required distinction.

### 103.2 Automatic-acquisition value

Run parser and schema-agent providers on clean prose and recall-style text. Compare:

1. embeddings only;
2. embeddings + parser-derived chart;
3. embeddings + agent-derived chart;
4. embeddings + automatically resolved multi-provider chart.

Evaluate correct-target ranking, structural-foil rejection, false contradiction, unresolved rate, calibration, stability, cost, latency, and failure localization.

WOG expectations are expressed in plain narrative facts and context constraints, not hand-authored AMR. This experiment tests whether structure survives acquisition—the question that actually determines system utility.

## 104. Current repository implications at this checkpoint

Current verified state on 2026-08-28:

- multi-module scaffold is committed;
- `core` is implemented and committed;
- fast JVM suite passes 47/47 tests;
- architecture/roadmap document is committed;
- `amr` has no source implementation yet.

The checked-in roadmap is internally inconsistent with the latest decision:

- its top-level flow still makes checked AMR charts the canonical local representation;
- its module table gives `amr` full PENMAN/AMR/Smatch/reification ownership;
- a later paragraph redefines `amr` around a minimal proposition chart;
- milestones still require hand WOG AMRs and a large AMR Phase-A implementation;
- provider automation begins later even though unattended acquisition is now P0.

Closure recommendation: reconcile the roadmap, local `AGENTS.md`, module names/dependencies, M0 scope, and fixture policy **before any `amr` source code lands**.

# Reconciliation Record — 2026-08-28 (plan revision 2)

## 105. Closure of the §104 inconsistencies

The checked-in roadmap (`docs/plans/2026-08-28-architecture-and-roadmap.md`, revision 2), `AGENTS.md`, and `build.sbt` were reconciled to §84–104 in one commit (`ef44e3f`):

1. **Canonical local object.** `PropositionChart` (`proposition` module) is the contract; `amr-interop` is an adapter with conversion to/from charts and retained AMR provenance; no narrative, recall, or interview API references an AMR type. Module dependency structure is exactly §99.
2. **Autonomy is P0.** New portable `acquire` module holds task packets, proposal-only agent results, critic findings, typed patches, `ResolutionState`, acceptance policy, stage-cache keys, prompt-package manifests, foil interfaces, and effect-free orchestration contracts; JVM-only providers and the orchestrator runtime are M1 ("autonomous vertical slice"), not later hardening.
3. **Fixture policy.** WOG is a researcher-reviewed *narrative acceptance fixture* in narrative types plus plain-language expectation records; AMR conformance gold comes from published guideline examples only; story charts are machine-generated silver. No hand-authored AMR anywhere in the plan.
4. **WOG text.** Bartlett (1932) is not yet public domain (US: 2028); the fixture uses Boas (1901) *Kathlamet Texts* pp. 182–184 (US government publication), stored with provenance; a lab copy of the Bartlett stimulus may be registered later as a second version with its own checksum.
5. **Deferred from M0:** reification, UMR I/O, Smatch optimization, generated frame builders.
6. **Parser skepticism** is recorded as a benchmark hypothesis with two acquisition profiles (§101), not a design fact.

Superseded: roadmap revision 1's opening diagram, module table, and M0 scope (retained in git history).

---

# Multiscale Surface and Feature-Track Checkpoint — 2026-08-28

## 106. Multiscale structure needs a shared coordinate system, not one universal node type

The fine-grained and higher-order views are complementary projections over the same evidence. The unifying object is an exact, immutable **surface axis**:

\[
w_0,w_1,\ldots,w_{n-1},
\]

where the elements are ordered surface tokens with exact source spans. Sentences, clauses, paragraphs, speaker turns, propositions, situations, segments, and feature observations are all anchored to this axis through a `TextSpan` or discontinuous `SpanSet`.

Above that axis, keep distinct layers:

1. **Surface atlas:** exact text, tokens, sentences, clauses, paragraphs, and later transcript turns.
2. **Local proposition charts:** sentence/clause-level concepts, predicates, arguments, polarity, modality cues, and embedded propositions.
3. **Document identity and context:** cross-sentence entity/event identity, reported speech, belief, memory, and other scopes.
4. **Narrative process graph:** canonical situations plus temporal, causal, goal, entity, spatial, and reference relations.
5. **Hierarchy:** atomic situations grouped into scenes, episodes, and the story root; auxiliary arcs may overlap or be discontinuous.
6. **Feature tracks:** scalar or vector observations aligned to tokens, spans, situations, segments, or gaps between units.
7. **Boundary and transition beliefs:** probabilistic interpretations derived from graph changes, feature changes, and discourse cues.

The key design constraint is bidirectional traceability:

- from a word or window, retrieve covering propositions, situations, segments, claims, and features;
- from a scene or event, recover every supporting word/span and all feature values aggregated over that support.

This avoids two common mistakes. A graph-only system loses gradual flow, and a signal-only system loses predicate roles, identity, scope, and nonlocal relations.

## 107. What the current surface implementation already provides

The checked-in `core` implementation already supplies a useful primitive traversal API:

```scala
atlas.tokens: Vector[SurfaceUnit]
atlas.sentences: Vector[SurfaceUnit]
atlas.paragraphs: Vector[SurfaceUnit]
atlas.childrenOf(id): Vector[SurfaceUnit]
atlas.parentOf(unit): Option[SurfaceUnit]
atlas.unitAt(offset, kind): Option[SurfaceUnit]
atlas.unitsOverlapping(span, kind): Vector[SurfaceUnit]
atlas.text(unit): String
```

Because tokens and sentences are ordered vectors, ordinary Scala traversal is already possible:

```scala
atlas.tokens.iterator
atlas.tokens.sliding(20)
atlas.sentences.zipWithIndex
atlas.unitsOverlapping(segment.support.minSpan, SurfaceUnitKind.Token)
```

The atlas validates that ordinals agree with span/discourse order. `TextSpan` is zero-based, half-open, and measured in UTF-16 coordinates. `SpanSet` represents nonempty, possibly discontinuous support, which is important for event recurrence and later retelling.

Current limitations are explicit:

- `tokens` include punctuation, so there is not yet a dedicated lexical-word view;
- a token does not yet expose a typed class such as word, number, punctuation, or symbol;
- there is no purpose-built cursor API for previous/next/context operations;
- there is no reusable typed window plan or boundary policy;
- transcript speaker turns, prompt phases, and audio-time spans are not yet surface units;
- feature observations and window-derived tracks are not yet implemented.

The existing `Vector` API is sufficient as the lawful primitive. Convenience traversal should be built on it rather than replacing it with a mutable stream or cursor as the authoritative representation.

## 108. Proposed word traversal and window interface

Add a typed lexical view while preserving the original token sequence:

```scala
enum TokenClass:
  case Word, Number, Punctuation, Symbol, Other

final case class TokenView(
  unit: SurfaceUnit,
  tokenClass: TokenClass,
  normalized: Option[String]
)

opaque type TokenIndex = Int
```

The atlas or a derived `SurfaceSequence` should expose:

```scala
trait SurfaceSequence:
  def tokens: IndexedSeq[TokenView]
  def lexicalTokens: IndexedSeq[TokenView]
  def at(index: TokenIndex): Option[TokenView]
  def previous(index: TokenIndex): Option[TokenView]
  def next(index: TokenIndex): Option[TokenView]
  def covering(span: SpanSet): Vector[TokenView]
  def windows(plan: WindowPlan): Iterator[SurfaceWindow]
```

A `SurfaceWindow` is a view, not copied text:

```scala
final case class SurfaceWindow(
  tokenRange: TokenRange,
  lexicalTokenCount: Int,
  support: SpanSet
)
```

The minimal window plan needs:

```scala
final case class WindowPlan(
  width: PositiveInt,
  step: PositiveInt,
  basis: WindowBasis,
  edgePolicy: EdgePolicy
)

enum WindowBasis:
  case AllTokens
  case LexicalTokens
  case Sentences
```

Centered contexts and variable kernels can be added as derived plans. The first API should make the common operations—20 words every 5 words, one sentence at a time, or a centered ±10-word context—obvious and lawful.

A lightweight immutable `SurfaceCursor` may be useful for interactive inspection and parsers, but should remain a convenience wrapper over `(atlas, TokenIndex)`. Scientific computation should prefer explicit ranges/windows so that support and edge behavior are visible in receipts.

## 109. Feature tracks are the general mechanism for imageability and related signals

Imageability is one instance of a general **aligned feature track**. A track binds a declared feature space to observations on a declared target domain:

```scala
final case class FeatureSpace[V](
  id: FeatureSpaceId,
  description: String,
  valueSchema: FeatureValueSchema,
  units: Option[String],
  provider: ModelFingerprint,
  normalized: Boolean
)

final case class FeatureObservation[T, V](
  target: T,
  support: SpanSet,
  estimate: Estimate[V],
  evidence: Vector[EvidenceRef]
)

final case class FeatureTrack[T, V](
  space: FeatureSpace[V],
  observations: Vector[FeatureObservation[T, V]],
  receipt: FeatureReceipt
)
```

Target types may include:

- `TokenTarget` for word-level imageability or lexical frequency;
- `SurfaceUnitTarget` for sentence-level sentiment or embedding vectors;
- `SituationTarget` for proposition/event embeddings and sensory profiles;
- `SegmentTarget` for scene or episode summaries;
- `BoundaryTarget` for change evidence at gaps;
- `AudioIntervalTarget` or recall-time targets in later phases.

The value can be a scalar, dense vector, sparse vector, categorical distribution, or a domain-specific record. Scala type parameters should prevent accidentally treating a scalar imageability track as an embedding matrix. Feature-space identifiers, dimensions, normalization, and provider versions remain runtime data because the spaces are open and versioned.

Missingness must be first-class. Punctuation, out-of-vocabulary words, names, and uncertain lexicon matches must not silently receive zero imageability. `Estimate[V]` should carry value, uncertainty when available, and a typed missing reason or coverage status.

Small scalar and structured profiles can live in canonical artifacts. Large dense vectors belong in sidecars referenced by `FeatureRef`. In either case, the logical track and its provenance are part of the model.

## 110. Windowed and segment-level features are derived tracks with recipes

Given token observations $x_j$, a windowed scalar track can be written:

\[
\tilde x_k
=
\operatorname{Reduce}
\left(
\{(\omega_{kj},x_j):w_j\in W_k\}
\right).
\]

The reducer should be a declared operation, not an anonymous preprocessing step:

```scala
trait WindowReducer[V, O]:
  def reduce(values: NonEmptyVector[WeightedEstimate[V]]): Estimate[O]

enum ScalarReducer:
  case Sum
  case Mean
  case WeightedMean
  case Maximum
  case Variance
  case Slope
```

For an imageability track, useful derived outputs include:

- total imageability mass in a window;
- mean imageability among covered lexical tokens;
- imageability density per word or per second;
- variance or upper quantiles, which distinguish uniformly concrete passages from passages with one vivid word;
- local slope or first difference;
- smoothed convolution using a rectangular, triangular, or Gaussian kernel;
- coverage count and fraction.

Summation alone is confounded by the number of words and lexicon coverage. Therefore every aggregate should retain at least the number of eligible targets, number observed, coverage fraction, and window support. Mean/density and sum answer different questions and should be kept separately.

Every derived track records an executable recipe:

```scala
final case class FeatureDerivation(
  inputs: NonEmptyVector[FeatureSpaceId],
  window: Option[WindowPlan],
  reducer: ReducerId,
  weighting: WeightingPolicy,
  missing: MissingValuePolicy,
  normalization: Option[NormalizationPolicy],
  implementationVersion: String
)
```

This recipe forms a dependency DAG. It supports content-addressed caching, exact replay, graph-aware diffing, and detection of stale descendants after an input/model change.

Aggregation over an event or scene uses the same machinery, replacing a regular window with that node's `SpanSet`. Discontinuous support is valid: a motif or retrospective event can aggregate over several separate surface regions without pretending they are contiguous.

## 111. Sentence-level semantics and narrative hierarchy

Fine-grained structure is captured locally and then composed; it is not discarded when higher-level units are created:

\[
\text{tokens/spans}
\rightarrow
\text{proposition charts}
\rightarrow
\text{canonical situations}
\rightarrow
\text{scenes}
\rightarrow
\text{episodes/story}.
\]

The links are explicit many-to-many projections:

- one sentence may express several propositions;
- one proposition may have discontinuous or cross-sentence support;
- several mentions may project to one canonical situation;
- one situation can participate in a scene and several auxiliary arcs;
- one summary proposition can refer to an entire segment rather than one child.

The primary segmentation hierarchy is nested and primarily discourse-contiguous:

\[
\text{situation}\subset\text{scene}\subset\text{episode}\subset\text{story}.
\]

Auxiliary structures—goal arc, character thread, location thread, motif, theme—are allowed to overlap and to have discontinuous membership. They must not be forced into the primary tree.

Higher levels never replace their children. A scene stores a summary claim, membership edges, evidence/support, alternative boundaries, and derived feature views. The atomic charts and exact source remain available for inspection and alignment.

## 112. Scene changes are boundary beliefs supported by heterogeneous evidence

At each eligible gap $g_i$ between adjacent atomic discourse units, construct a typed evidence vector:

\[
b_i=
[
\Delta_{\mathrm{semantic}},
\Delta_{\mathrm{proposition}},
\Delta_{\mathrm{entity}},
\Delta_{\mathrm{location}},
\Delta_{\mathrm{context}},
\Delta_{\mathrm{world-time}},
\Delta_{\mathrm{goal}},
\Delta_{\mathrm{sensory}},
\Delta_{\mathrm{affect}},
\Delta_{\mathrm{imageability}},
\text{discourse cues},
\text{provider votes}
].
\]

Examples:

- entity turnover can indicate a cast change;
- a new location plus a forward time jump strongly supports a scene boundary;
- return from reported speech or memory to the narrated world changes context;
- semantic/vector discontinuity captures changes not present in the symbolic graph;
- imageability or sensory change can characterize a transition but is not assumed to be a boundary by itself;
- explicit phrases such as “three years later” or “back at the house” provide surface evidence.

Store each component separately, then derive:

```scala
final case class BoundaryEvidence(
  gap: BoundaryTarget,
  components: Map[BoundaryFeatureId, ScoreEstimate],
  claims: Vector[ClaimId]
)

final case class BoundaryBelief(
  gap: BoundaryTarget,
  level: HierarchyLevel,
  rawScore: Double,
  calibratedProbability: Option[Probability],
  evidence: NonEmptyVector[EvidenceRef]
)
```

The resolved hierarchy is selected from these beliefs under constraints such as nestedness, root coverage, and nonempty segments. Unselected boundary beliefs remain in the artifact. Thus a scene tree is one versioned interpretation, not the erased source of truth.

## 113. Jumps, flashbacks, and reversals require two temporal structures

Discourse order and story-world time must remain independent.

Suppose situation $e_a$ is mentioned and the next discourse unit describes $e_b$. The surface atlas gives a deterministic discourse adjacency:

\[
e_a\rightarrow_D e_b.
\]

The temporal graph may infer:

\[
e_b\prec_W e_a.
\]

That combination—not a reversed token sequence—is what identifies a backward world-time jump or flashback.

A discourse `FlowStep` should retain both continuous change and a typed temporal interpretation:

```scala
enum WorldTimeTransition:
  case Continues
  case JumpForward(magnitude: Option[DurationEstimate])
  case JumpBackward(magnitude: Option[DurationEstimate])
  case ReturnFromEarlierFrame
  case SimultaneousThreadSwitch
  case Atemporal
  case Unresolved(alternatives: Vector[TemporalHypothesis])

final case class FlowStep(
  from: AtomicDiscourseTarget,
  to: AtomicDiscourseTarget,
  featureChanges: Map[FeatureSpaceId, ScoreEstimate],
  entityTurnover: ScoreEstimate,
  locationChange: ClaimEstimate[Boolean],
  contextChange: ClaimEstimate[Boolean],
  worldTime: ClaimEstimate[WorldTimeTransition],
  boundaryBeliefs: Vector[BoundaryBelief]
)
```

Story-world time is usually a partial order, not a fully observed numerical axis. A layout coordinate may be derived for visualization, but it cannot replace typed relations such as `Before`, `Overlaps`, `During`, and `Unclear`. The system should preserve alternative temporal readings rather than manufacture a precise jump magnitude.

This representation distinguishes:

- a real flashback from a retrospective mention of an earlier event;
- a backward jump from a return to the main timeline;
- intercut simultaneous threads from chronological reversal;
- presentation-order disruption from causal-order violation;
- a thematic recurrence from an event coreference claim.

## 114. Transcript traversal is an overlay on the same atlas

For recall and interview transcripts, add a `TranscriptAtlas` or typed annotation layer rather than changing the meaning of story-text units:

```scala
final case class TranscriptTurn(
  id: TurnId,
  speaker: SpeakerId,
  support: SpanSet,
  audio: Option[AudioSpan],
  phase: Option[InterviewPhase],
  prompt: Option[PromptId]
)

final case class TranscriptAtlas(
  surface: SurfaceAtlas,
  turns: Vector[TranscriptTurn]
)
```

Tokens and propositions remain addressable in text coordinates, while optional audio timing enables tracks for pause duration, speaking rate, latency, prosody, and retrieval dynamics. Speaker and prompt filters should make it easy to traverse only participant words, free-recall words, or post-probe material.

The same feature-track machinery can then align values to:

- lexical tokens;
- participant speaking time;
- utterances/turns;
- recall propositions;
- interviewer probes;
- gaps and pauses.

Content scores and audio/retrieval-dynamics tracks remain separate because the latter are influenced by language, hearing, motor production, recording conditions, and interviewer behavior.

## 115. Core analytical queries enabled by the design

The combined mechanism should support queries such as:

```scala
features.values(imageability, tokenRange)
features.aggregate(imageability, scene.support, Mean)
features.windowed(imageability, WindowPlan.words(width = 20, step = 5))
features.change(semanticContextual, discourseGap)
story.covering(token.id)
story.supporting(scene.id)
story.worldTimeTransition(flowStep.id)
story.boundaryEvidence(gap.id)
story.segmentFeatureMatrix(level = Scene, spaces = requestedSpaces)
```

Scientifically useful products include:

- imageability, sensory, affective, entity-density, and semantic trajectories over discourse;
- distributions of feature values within and across scenes;
- change-point evidence and uncertainty at every possible boundary;
- segment-by-feature matrices for behavioral or neural analysis;
- comparison of feature peaks with event boundaries;
- recurrence and motif signals at nonadjacent locations;
- source-versus-recall trajectory comparisons;
- feature-conditioned coverage, compression, and omission measures.

The same feature can play three roles, which must be declared:

1. **descriptive:** characterize an already constructed story/segment;
2. **inductive:** provide evidence to the hierarchy or relation resolver;
3. **predictive:** explain behavioral, alignment, or neural outcomes.

## 116. Prevent circular analysis and feature leakage

If imageability helps define scene boundaries and the subsequent analysis asks whether imageability changes at scene boundaries, the result is partly guaranteed by construction. The artifact therefore needs a feature-dependency and use ledger.

At minimum:

- raw provider tracks remain immutable;
- every smoothed or aggregated track records its derivation recipe;
- every boundary/hierarchy build records the exact input feature spaces;
- analyses can request boundaries constructed without the tested feature;
- learned boundary weights are fit and evaluated leave-story-out;
- exploratory and confirmatory feature uses are labeled;
- calibration/model versions and normalization populations are receipted;
- no feature derived from a target outcome is allowed to leak into source induction.

Where a feature is both scientifically interesting and useful for induction, use ablations, held-out estimation, cross-fitting, or an independently annotated boundary set.

## 117. Revised module boundary and implementation priority

Feature tracks are not owned solely by `story`: source text, recall, autobiographical interviews, audio, and later video all need them. Add a provider-neutral cross-cutting module—provisionally `features`—that depends only on `core` and owns:

- typed feature spaces and targets;
- scalar/vector/distribution estimates;
- missingness and coverage;
- aligned tracks;
- window plans and reducers;
- derivation recipes and dependency DAGs;
- sidecar manifests/references;
- feature-level laws.

Suggested dependency direction:

```text
core (source atlas, spans, token coordinates)
  ├── proposition
  ├── features
  └── acquire/provider APIs

story <- core + proposition + features
document/recall/interview <- core + proposition + features + story as needed
provider-embed/provider-onnx -> features
align -> story + recall + features
```

`core` should add only the minimal token-classification and explicit window-coordinate primitives necessary to make traversal stable. Numerical reduction, feature manifests, and derived tracks stay outside `core`.

Implementation priority before narrative induction:

1. preserve the current exact `SurfaceAtlas` laws;
2. add lexical-token classification and typed token ranges;
3. implement generic aligned scalar tracks and window aggregation with coverage;
4. add vector sidecar references and derivation receipts;
5. define `BoundaryTarget`, `BoundaryEvidence`, and `WorldTimeTransition`;
6. build hierarchy inference over symbolic changes plus selected feature tracks;
7. add transcript turn/audio overlays without disturbing text coordinates;
8. validate invariance, locality of change, no silent missing-as-zero, and dependency/provenance laws.

This is not an optional visualization subsystem. It is the mechanism that connects exact words, local meanings, continuous discourse flow, and higher-order narrative structure while keeping each scientifically inspectable.

## 118. Integration of the feature-track checkpoint (§106–117) into plan revision 2

Adopted without modification. Changes applied to the repository on 2026-08-28:

- new portable module `features` (depends only on `core`) owning feature spaces, typed targets, estimates with missingness/coverage, aligned tracks, window plans and declared reducers, derivation recipes (content-addressed DAG), sidecar manifests, `BoundaryTarget`/`BoundaryEvidence`, `WorldTimeTransition`, and the feature-use ledger of §116;
- `core` gains only token classification (`TokenClass`), a lexical-token view, and explicit window coordinates (`TokenIndex`, `TokenRange`);
- `story.FlowStep` adopts `worldTime: WorldTimeTransition` and `featureChanges: Map[FeatureSpaceId, ScoreEstimate]`;
- transcript turns (`TranscriptTurn`, `TranscriptAtlas`) become an overlay in `core` used by `recall` and `interview`;
- module dependency order follows §117: `story`, `document`, `recall`, `align`, `interview` depend on `features`; providers produce raw tracks only.

## 119. Co-authored vision and mission checkpoint

On 2026-08-28, the project established `vision.md` and `mission.md` as concise public statements derived from this design record. The drafts were developed collaboratively by the Codex release coordinator (`codex-storymodel-release`) and the Claude build coordinator (`claude-storymodel4s`) through the Mote topic `storymodel4s-vision`. The detailed notes and roadmap remain authoritative for technical scope; the new files state why the project exists and what it promises.

The principal correction produced by the collaboration was to name the scientific object, not merely the representation technology:

> **Narrative Process Alignment is the measurable transformation between a source narrative process and a recalled narrative process.**

The mapping is partial, probabilistic, hierarchical, open-world, and approximately relation-preserving. The multiscale story and recall representations make that mapping possible, but they are not themselves the complete scientific object. The vision therefore asks which content survived, at what grain and in what order, which roles/facts/contexts/relations remained intact, and what entered from outside.

The mission is summarized by the paired runtime and epistemic requirements:

> **Builds are unattended and auditable.**

This expands into the following public commitments:

1. every accepted explicit claim cites exact UTF-16 code-unit evidence spans, without cutting a Unicode code point, while inferences cite upstream claims;
2. every numeric credence is labeled as a raw score or as a calibrated probability with a named calibration model;
3. provider outputs, configurations, artifacts, and cache keys are receipted so a build can be replayed;
4. similarity retrieves candidates, while typed structure adjudicates role direction, polarity, embedded content, context, and chronology;
5. discourse time, story-world time, and recall time remain separate, as do narrated, reported, believed, intended, and hypothetical scopes;
6. omission, gist, blending, elaboration, backtracking, association, intrusion, ambiguity, and missingness are explicit outcomes rather than residual errors;
7. scalar or legacy scores are versioned projections of richer artifacts rather than definitions of memory.

The final red-team pass added four implementation-governance commitments. An unresolved build returns alternatives or an explicit unresolved state rather than manufacturing precision. Every feature used to infer a boundary or hierarchy is recorded so later analysis can detect circularity and require ablation, cross-fitting, or independent boundaries. Portable modules exclude JVM-only services and runtime-specific parsing features; the repeated regex-lookaround failures discovered during Scala Native release testing motivated making this a mission-level contract. Core processing remains sparse, and no learned component ships before a leave-story-out evaluation harness exists.

The public documents also retain the §106–118 surface/feature decision. Exact word traversal, aligned scalar or vector tracks, declared window reductions, boundary evidence, and higher-order narrative structures share a coordinate system without being collapsed into one layer. A feature such as imageability can characterize a story, help propose a boundary under a recorded induction recipe, or enter a later predictive analysis; the feature-use ledger must make those roles distinguishable.

The vision and mission explicitly reject several overclaims: natural autobiographical interviews do not establish historical veridicality or genuine re-experiencing; omission does not prove memory unavailability; salience does not define correctness; AMR is an interoperability adapter rather than the story ontology; parser output is not ground truth; automatic output is not gold; and the project does not promise one universal memory score. The initial release is English-oriented and does not train a parser or foundation model.

The red-team pass also required present-tense honesty. M0 supplies portable types, laws, validators, exact surface traversal, feature/window machinery, baseline recall alignment, and rule-based uncalibrated interview induction. It does **not** yet construct a complete story model automatically from raw text. Provider adapters, the unattended orchestrator, automatic cross-sentence identity resolution, relation extraction, hierarchy induction, and scientific calibration corpora remain programme work. The vision states the questions the completed system should make answerable; the mission distinguishes those goals from the capabilities already implemented.
