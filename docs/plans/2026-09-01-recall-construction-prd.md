# Recall Construction Integrity and Uncertainty Contract — revision 3

**Status: DRAFT, revision 3, written to the chief's approved acceptance basis
(`bd-01M1EA8J4KFQRHZ5SF326B6WFZ`). NOT ADOPTED. NOT ACKED. ZERO CARRIED ACKS.**

This revision answers a review that returned revision 1 with five blockers. It is
published as exact replacement bytes. No ACK carries over: the ACKs on the board
topic `recall-construction-scientific-boundary` were given to a *board post*, and
revision 1 as filed had different bytes again. **Nobody has acked this artifact.**

- Board topic for this revision: `recall-construction-prd-revision`
- Decisions recorded on either topic: **zero**
- Measured against: `adf58cad`, `6ec8b698`, and probes on an isolated `git archive` tree
- Supersession: **none claimed.** An unadopted draft supersedes nothing. Revision 1
  and the board drafts remain on the record as history.

## What changed in revision 3

Written to the seven binding items the chief approved as the revision-3 acceptance basis
(`bd-01M1EA8J4KFQRHZ5SF326B6WFZ`). Revision 2 was **returned, not adopted**.

| item | blocker in revision 2 | fix |
|---|---|---|
| 1 | R10's single enum conflated four coordinates — and *a provider-conformant artifact is not provider-resolved, a baseline route is not `AuthorityAbsent`* | enum withdrawn; four separately published coordinates; conditioning moved per-estimand |
| 2 | R4 cell 8 treated denser same-call output as a direction claim | correlated emissions are **one observation, not m votes**; new cell `8b` for a shared systematic offset; R4.9 names the calibration target |
| 3 | R7's 2x2 smuggled in a one-claim-per-cell invariant | withdrawn; incidence is **many-to-many**, with P1/P2 positive controls and four *named* compatibility failures |
| 4 | "court EXECUTABLE" was an adjective a heading could assert | replaced by a registry with ids, oracles, dependencies and honest statuses |
| 5 | scope overreached | retitled an **integrity and uncertainty contract**; the product/vertical PRD is a separate artifact |
| 6 | N5 forbade a rename "at any size" | not required by this contract; not forbidden either — if authorized it must be complete and versioned |
| 7 | standing implied carried approval | zero carried ACKs, no supersession claim, exact bytes below |

Two of these — items 1 and 3 — are this contract committing the defects it exists to forbid:
one wrapper over several meanings, and an invariant invented by its own fixture.

---

## A. Problem

`RecallSegmenter.segment` selects one unitization AND one interpretation and returns `RecallGraph[Checked]`. `Checked` proves structural coherence only. Everything downstream — `CandidateGenerator`, `GraphHsmm`, `RecallSignature`, interview scores, `PopulationAggregate` — conditions on that selection. No published field distinguishes a heuristic construction from an adjudicated one, and none records what was discarded to reach it.

Live consumers on `main`: `interview/segment.scala:24`, `embed-bench/wog.scala:118` and `:182`.

## B. Non-goals

- **N1** A better segmenter. A stronger cutter emitting one `Checked` graph is the same defect with higher confidence.
- **N2** A lattice, ensemble, or joint source-aware inference. Gated behind M6; may never be built.
  - **N2a** P2 uses "proposition/act annotation" until an ADR separates semantic content from `ClaimMeta`/`ClaimLedger` licensing metadata.
  - **N2b** The external entropy decomposition is **rejected**: `H(C,A) = H(C) + E[H(A|C)]` plus mutual information double-counts dependence. The identity is wrong, not the wording.
- **N3** Any SOTA or novelty claim. Only M6 could earn it.
- **N4** A human in ordinary builds. Providers propose; deterministic resolution decides.
- **N5** A rename of `Checked` is **not required by this contract**. It is not permanently
  forbidden either: if separately authorized it MUST be complete and versioned, because a
  partial coordinate migration is an undone one that looks done. Revision 2 said "not in
  scope at any size", which over-claimed a prohibition this contract has no standing to make.

## C. Contract families

Separately published contract families, **not orthogonal coordinates**. Earlier drafts counted three, four, then five; each count was wrong because resolver outcome was double-counted. **The point is non-implication, not the count.**

| | family | note |
|---|---|---|
| C1 | structural validity | exists (`Checked`) |
| C2 | derived geometry — order, overlap, gaps, coverage, cell lengths | computed, never declared |
| C3 | plan admission — named task, eligible region, grain, gap policy, minimum-cell law | declared task + derived check |
| C4 | resolution — per-family outcome + joint compatibility | |
| C5 | construction authority — route, calibration basis, adjudication protocol, use-specific admission | |

Cell length is geometry (C2); minimum permitted length is admission (C3); **eligibility is a third predicate**, derivable from neither.

**Comparability** is a relation between two artifacts *for a declared estimand* — never policy equality. It requires same observation identity, same declared task and grain, independently validated plan laws, and an estimand-specific crosswalk or common base measure.

**Integrity is not authenticity** — discharged by R3, not asserted here. Min/max over K inspected constructions is an **observed envelope**, not a robustness range. `E[T] != T(E)`.

## D. Requirements

**Court status is a registry, not an adjective.** Revision 2 labelled requirements
"court EXECUTABLE" inline, which let a heading assert something no machine checks. Every court
below is registered here with a stable id, a frozen fixture or mutation, its dependency, the
machine oracle that decides it, the required disposition, and an honest status. **A court is
executable only when its oracle can run today against an object that exists today.**

| id | requirement | frozen fixture / mutation | dependency | machine oracle | required disposition | status |
|---|---|---|---|---|---|---|
| `CT-R1-DOORS` | R1 | valid pair + mismatched pair from two recalls | none | `scalac` refusal from outside `storymodel4s.align` + `munit` | 4 doors refuse w/ same-shape positive controls; factory `Left` on mismatch | **green control available, red side reproducible today** |
| `CT-R11-PROP` | R11 | two proposals, order reversed, no `canonicalBundle` | none | `munit` state equality | projection, state, identity, output byte-equal | **red measurement today** |
| `CT-R11-EVID` | R11.7 | **one** proposal, its own evidence vector reversed | none | `munit` state equality | same equality | **red measurement today — fails on `main`** |
| `CT-R11-ALIAS` | R11.4 | two `Inline` records under one `EvidenceId` | evidence ledger | `munit` | typed `Unresolved`; projection not minted; **not** `Rejected` | design-stage (ledger absent) |
| `CT-R12-SUBJ` | R12 | two builds, same `EvidenceId`, different `Inline` span | R12.1 subject ruling | `munit` on `ClaimId` + compilation digest | per the declared subject | **blocked on the subject ruling** |
| `CT-R8-VIS` | R8.4 | one unit split into two, content preserved | none | arithmetic on `leafVisitation` | invariant | **red measurement today: 0.632 → 0.865** |
| `CT-R2-GEOM` | R2 | 8 frozen cell sequences (a)–(h) | R2.1 objects | plan admission checker | per-cell table | design-stage (objects absent) |
| `CT-R3-FORGED` | R3.5 | forged label, recomputed checksums; admitted-root control | trust root | verifier | conformance passes, authenticity fails, admission refuses | design-stage (no trust root) |
| `CT-R4-*` | R4 | 12 cells incl. `8b` shared offset | Q-A2 model | resolver + calibration | per-cell table | **blocked on Q-A2 and P2** |
| `CT-R7-INCID` | R7 | P1, P2, N1–N4 | R2 + R4 | compatibility witness | many-to-many admits; 4 named failures refuse | design-stage |
| `CT-R10-COORD` | R10 | 5 route/witness fixtures | R3.5 witnesses | derivation + identity recompute | per-cell table; caller supply refuses at compile time | design-stage |
| `CT-E1..E3` | E | H1/H2/H3 | P2 gold | preregistered analysis | E5 stopping rules | **empirical gate** |

Statuses mean exactly: **red measurement** — the oracle runs now and the defect reproduces;
**green control available** — the positive control compiles/passes now; **design-stage** — fixture and
disposition are fixed but the object under test does not exist; **blocked** — named dependency;
**empirical gate** — requires data, not code. Nothing below upgrades its own status by assertion.

### R1 — Unforgeable subject binding · `CT-R1-DOORS`
`SubjectAlignment` (`population.scala:19-24`) carries a joined claim: `result.recallChecksum` must equal `AlignWire.recallChecksum(recall)`, checked at runtime in `PopulationAggregate.of` (`:328-330`, `:339-346`).
- **R1.1** MUST NOT expose product construction.
- **R1.2** The checked factory MUST recompute the checksum, not accept it.
- **R1.3** An atomic factory, NOT a new field on the case class.
- **Court R1.** Fixtures: a valid `(recall, result)` pair, and a mismatched pair built from two different recalls. Required dispositions: all four doors — `apply`, `copy`, companion `fromProduct`, `summon[Mirror.ProductOf[SubjectAlignment]].fromProduct` — MUST fail to compile from **outside** `storymodel4s.align`, each with its own same-shape positive control that DOES compile; the checked factory MUST return a `Left` on the mismatched pair. Reject side populated today.

### R2 — Four data objects; geometry never from a `SpanSet` hull · court design-stage
`SpanSet` is documented as *"nonempty, sorted, deduplicated evidence support that may be discontinuous"* (`core/span.scala:73`), and `RecallGraph.validated` deliberately compares unit text against the **hull** `span.minSpan`, so a discontinuous support legitimately carries the text between its refs (`recall/graph.scala:150-160`). Neither members nor hull is automatically a contiguous cell.
- **R2.1** Four separate objects MUST exist: (1) exact contiguous cell/boundary plan on a declared coordinate dialect; (2) eligible-region mask plus typed exclusions and reasons; (3) semantic/proposition/evidence supports, which may overlap and be discontinuous; (4) the mapping from admitted cells to artifacts built over them.
- **R2.2** Order, overlap, coverage, gaps and cell lengths MUST be computed from (1) relative to (2). **Never from a `SpanSet` hull.** Claim-support geometry belongs to (3).
- **R2.3** Plan admission applies the named task/grain/gap/minimum-cell law to (1)+(2).
- **R2.4** **Eligibility is construction input and identity**, not inferred from surviving unit supports. `InterviewSegmenter` excludes interviewer and turn-crossing units and renumbers survivors (`interview/segment.scala:18-40`); a gap may be outside the mask or a typed exclusion, not an unaccounted segmentation gap.
- **R2.5** The derived class MUST NOT be called admissibility, licence, or anything implying epistemic standing.
- **Court R2.** Fixtures, all frozen as literals in the suite: **(a)** two explicitly written cell sequences over one transcript that a reader can see are different analyses and that are both lawful partitions — required disposition **both admit**; **(b)** a plan whose ordinal order disagrees with text order — **refuse**; **(c)** a plan with an overlapping pair under a declared partition task — **refuse**; **(d)** a plan with a gap inside the eligible mask and no typed exclusion — **refuse**; **(e)** the same plan with that gap outside the mask — **admit**, and MUST NOT be reported as an unaccounted gap; **(f)** one claim whose support moves from a single ref to several distant refs — the cell plan MUST be byte-identical before and after, and the interval between refs MUST NOT be filled; **(g)** a turn-crossing unit omitted with its typed exclusion retained — **admit**; the same omission with the exclusion removed MUST change construction identity; **(h)** two support configurations with the same hull but different exact refs — MUST NOT compare equal as cell evidence.

### R3 — Conformance and authenticity are different witnesses · court design-stage
- **R3.1** **The resolver mints the conformance witness of R3.5a and nothing else.** It cannot mint provider or adjudicator authenticity, and it does not create authority ex nihilo. The witness is a phantom state dropped by `copy` exactly as `Checked` is (`graph.scala:48-54`); it is never a data field.
- **R3.2** The bare graph decoder mints **no witness of any kind** (`codec/recall.scala:260-276`; `SchemaVersions.check` gates format only).
- **R3.3** **No heuristic fallback.** Missing authority does not establish heuristic construction. *Authority-absent* is its own published state.
- **R3.4** **A complete-artifact codec may mint the conformance witness only**, and only after recomputing the binding among contents, ledgers, policy, calibration coordinates, evidence and receipt. **It MUST NOT mint the authenticity witness**, which requires a trust root that bytes alone cannot supply. Incomplete or legacy bytes stay authority-absent and are never upgraded.
- **R3.5 Three witnesses.**
  - **a Conformance.** Deterministic replay establishes internal consistency of graph, canonical projection, policy, resolution, evidence contents, calibration record and receipt bindings.
  - **b Authenticity.** A verifier establishes asserted provider/adjudicator authority under the named use's admitted trust root — registry/capability, keyed receipt, signature, or equivalent.
  - **c Scientific admission.** A use-specific predicate states which witnesses it requires.
  - **d Dispositions.** Internally consistent bytes with no trust root remain *internally consistent, authority unverified* — not promoted, not necessarily rejected for every use. Forged-but-self-consistent authority bytes MUST refuse the admission witness.
- **Court R3-FORGED.** Fixtures: (a) an artifact whose provider/adjudicator label was invented by the caller, with every ordinary content checksum and resolution binding recomputed so it is internally perfect; (b) the same artifact under an admitted trust root; (c) a plain recomputed checksum as negative control. Required dispositions: (a) conformance **passes**, authenticity **fails or is unverified**, and any admission requiring that trust root **does not mint**; (b) all three mint; (c) proves conformance alone is insufficient by minting only conformance.

### R4 — Hierarchical boundary resolution · court design-stage
- **R4.1** `Resolver.collect`'s exact-value grouping MUST NOT be used for a boundary coordinate. Reuse limited to `AgentProposal`, `EvidenceBundle`, `RawScore`, calibration discipline, `CandidateLedger`, and the `ResolutionState` vocabulary.
- **R4.2** A tolerance quotient is **refused**. `d<=k` is not transitive (412~413, 413~414, 412≁414); closure gives single-linkage chaining; greedy clustering is order-dependent.
- **R4.3** An accepted exact gap that is neither provider's proposal is a **fabricated boundary** unless a named observation model licenses the estimator, a complete posterior and decision contract selects it, and the receipt records both.
- **R4.4** Three levels: (a) region, (b) exact gap, (c) global plan. **Region acceptance MUST NOT materialize a fixed segmentation.**
- **R4.5** Provider independence MUST account for dependence — shared model, prompt, tokenizer or training source is not independence.
- **R4.6** The construction identity MUST commit to the complete proposal bundle **via the canonical projection of R11.8**, so accepted output cannot hide that near-disagreement existed.
- **R4.7** A provider call emits a **set**. The likelihood MUST NOT factorize over emitted gaps independently. The calibration unit is the observed set against the latent exact plan, or an equivalent assignment/detection model accounting for false positives, misses, location error, at-most-one-match, provider-family and repeated-call dependence, and over-segmentation tendency. Pointwise kernels are internal terms, never independent votes.
- **R4.9 Calibration target is named, not implied.** Every calibrated claim states its target: an exact plan, an acceptable set/region/distribution, or a task loss. A named **reversible decision projection** may be emitted only while preserving the posterior, the alternatives, the loss and the rule that produced it. **No silent truth materialization.**
- **R4.8** An **uncalibrated near case MUST NOT be `Accepted` at any level** — `Accepted` carries a calibrated `Probability`. Uncalibrated is retained support or `Unresolved(Uncalibrated)`. Region acceptance is lawful only after a named set-level observation model is calibrated to a declared target.
- **Court R4 — eleven cells, each with a required disposition.** Fixtures use one transcript, gaps named by token index.

  | # | fixture | required disposition |
  |---|---|---|
  | 1 | 412 and 413, two admitted independent sources, **no calibration** | region **not** `Accepted`; both exact gaps retained as `Alternatives`; never `Accepted` |
  | 2 | same, **with** a calibrated set-level model | region `Accepted`; exact gap **remains** `Alternatives`; no fixed segmentation materialized |
  | 3 | 412 and 412 from admitted independent sources under the dependence model, all other policy conditions met | `Accepted` |
  | 4 | 412 and 806 | no common region minted; two separate supports or a conflict, never one fabricated region |
  | 5 | gaps individually admissible whose union violates the minimum-cell law | global plan **refuses**; no cut is independently accepted into a graph |
  | 6 | the cell-3 bundle with proposals reversed | resolved state, canonical projection, construction identity and output **byte-equal** |
  | 7 | 412, 413, 414 from three sources | 412 and 414 **not** collapsed; no transitive class formed |
  | 8 | one call emitting 412; the same call emitting 412, 413, 414, 415 | denser same-call output **MUST NOT increase the independent-source count** and **MUST NOT by itself satisfy an agreement threshold**. A *declared calibrated joint likelihood* MAY lawfully move the posterior in either direction — correlated emissions from one call are one observation, not m votes |
| 8b | **shared systematic offset:** one call-wide `+1` shift across m matched boundaries | modelled as **one shared offset plus declared residual structure**, NOT m independent precision contributions. A model that treats it as m independent draws fails this cell |
  | 9 | one latent boundary, four emitted gaps around it | at most one match contributes; the rest are false positives or the model refuses |
  | 10 | two genuinely distinct latent boundaries 40 tokens apart | **positive control** — both represented, not collapsed |
  | 11 | one provider call cloned to look like two | independence threshold **not** satisfied |

### R5 — D2 before D1 · court design-stage
Two decisions were carried as board shorthand and are defined here.
- **D1** — *"promote `ClaimFamily.Boundary` into `ClaimFamily.highImpact`"*, which under `AcceptancePolicy.Conservative` swaps `FamilyPolicy.Ordinary` (p>=0.7, one provider) for `FamilyPolicy.Conservative` (p>=0.9, two providers).
- **D2** — *"does `ClaimFamily.Boundary` denote one estimand or two?"* — a story-hierarchy boundary and a recall idea-unit boundary currently share one enum case.
- **R5.1** **D2 is decided before D1.** `Boundary` MUST NOT cover both unless they are proven identical. **Fails closed:** they stay separate estimands until unification is proven.
- **R5.2** D1 MUST NOT be filed standalone. High impact is a risk classification; it does not entail the discrete agreement algorithm bundled into `FamilyPolicy.Conservative`. **Measured:** D1 without R4 makes lawful near-boundaries permanently `Unresolved`.
- **R5.3** Thresholds SHOULD follow a calibrated loss/utility decision, not membership in a convenience set.

### R6 — The exception stays · no court required
- **R6.1** The `IllegalStateException` at `segmenter.scala:458` **stays**. It fires only after the internal segmenter assembles parts that `validated` rejects — the allowed invariant-breach case. Converting it to `Unresolved` would hide a bug as scientific uncertainty. This is a prohibition on a change, so it has no court; the absence of a court is the point.

### R7 — Joint compatibility · court design-stage
- **R7.1** A boundary plan and an interpretation plan can each be admissible and **jointly false**.
- **R7.2** The accepted combination MUST be a checked compatibility witness with its own derivation evidence and receipt.
- **R7.3** A deterministic resolver may accept, retain alternatives, reject, or remain unresolved. Human adjudication is one possible authority, not the verb for every lawful join (N4 stands).
- **R7.4 Cell-to-artifact incidence is explicitly MANY-TO-MANY.** This restates R2: cells (R2.1(1)) and semantic artifacts (R2.1(3)) are different objects joined by a mapping (R2.1(4)). **No one-claim-per-cell invariant may be smuggled in through a court.** Revision 2's 2x2 did exactly that — it refused `(PA,IY)` on the grounds that "two predicates cannot bind inside one cell", which is not a law of this contract but an invariant invented by its own fixture. That refusal is withdrawn.
- **Court R7 — positive controls first, because they are what revision 2 got wrong.**

  | # | fixture | required disposition |
  |---|---|---|
  | P1 | one cell whose span supports **two distinct claims** (an episodic assertion and a self-repair over the same words) | **admits.** Both claims are representable, separately identified, and both map to that one cell |
  | P2 | one claim whose support **spans three cells** (a proposition whose argument sits two cells away) | **admits.** The claim is not split, duplicated, or forced to pick a home cell |
  | N1 | **membership:** a claim mapped to a cell that is not in the accepted plan | **refuses** — the incidence names a cell the plan does not contain |
  | N2 | **eligible support:** a claim whose support lies wholly outside the eligible-region mask (R2.1(2)) | **refuses** — support outside eligibility is not support |
  | N3 | **endpoint survival:** a temporal edge whose endpoint cell is dissolved by the competing boundary plan | **refuses** — the edge has no endpoint under that plan; this is a genuine joint failure, not a cardinality preference |
  | N4 | **repair/retraction:** a claim retracted by a later repair span, retained as accepted under a plan that admits the repair | **refuses** — the plan admits the retraction and the interpretation ignores it |

  All six combinations MUST be unconstructible by public product construction. **More than one combination may lawfully survive**; the court proves an incompatible combination cannot *enter* the scientific path, and it MUST NOT be readable as forcing a unique compatible pair.

### R8 — Sensitivity, not marginalization · court design-stage, except R8.4 which is measured
- **R8.1** "Marginalized" MUST NOT be used without calibrated weights over declared support plus an omitted-tail receipt. Boundary and interpretation choices are non-Cartesian; multiplying per-family probabilities does not create a path measure.
- **R8.2** Deliverable is a labeled family plus predeclared ranges.
  - **a** An envelope is computed **only where the estimand-specific comparability predicate passes**. Otherwise publish the family unreduced — incomparable values do not acquire a lawful range because min/max is easy to compute.
  - **b** Labeled **non-exhaustive observed envelope** unless completeness or a sound bound proves omitted constructions cannot escape it.
  - **c** The exact selection/search receipt is required.
  - **d** Record integration order: `E_g[T_g]` and `T(E_g[input_g])` are distinct.
- **R8.3** P and F MUST NOT be averaged or concatenated across unit axes.
- **R8.4** **Source keys do not make a measure segmentation-independent. Measured on `main`.** `RecallSignature.leafVisitation` (`signature.scala:644-653`) accumulates `anchorMass` across rows, then applies `1-exp(-acc)`. Rows are units, so n units anchored to one source at mass m give `1-e^(-n*m)`, strictly increasing in n. A content-preserving split of one unit into two takes visitation **0.632 → 0.865** at m=1. H1 fails today for a shipped estimand.
- **R8.5** Compression sums over rows; chronology and flow are over n-1 unit steps; external quantities are means per unit. All segmentation-conditioned despite source-shaped keys.
- **R8.6** Every published quantity MUST name its pushforward kernel and pass an invariance court, **or** carry the segmentation-conditioned label of R10 permanently. No quantity stays undecided.
- **R8.7** Compression and recall-side chronology may have **no cross-cut scalar**. That is a finding to preserve, not a hole to paper over.

### R9 — Identity · court design-stage
- **R9.1** `recallChecksum` stays the **aligner-input identity** (`wire.scala:234-294`). It omits entities, elaboration and coreference — and the segmenter produces coreference (`segmenter.scala:408-410`, `:454`). Do not widen it.
- **R9.2** A separate complete-construction identity binds the artifact and the C5 families.
- **R9.3** Span-derived `RecallUnitId` is **withdrawn** — identity by location; multiple claims may share exact support.
- **R9.4** Four identities: exact transcript cell; semantic claim; alternative-local fixed-view unit; cross-alternative correspondence. Graph-local ids stay opaque. **The crosswalk is a derived claim, not id equality.**
- **R9.5** Interview drift (`sid:u7` at ordinal 3, `interview/segment.scala:38`) shows the current id misleads; it does not license span-derived identity.
- **Court R9.** Fixtures: two different claims over the identical span; one claim supported by two distant refs. Required dispositions: both representable; the two claims MUST NOT share an identity; the discontinuous claim MUST NOT acquire a cell identity.

### R10 — Published construction coordinates · court design-stage · **none caller-written**
Revision 2 required a "qualification" and then defined it as a single closed enum —
`BaselineHeuristic | ProviderResolved | Adjudicated | AuthorityAbsent`. That is the
one-wrapper-over-several-meanings defect this contract exists to forbid, committed by this
contract. It conflates four independent coordinates and produces false implications: a
**provider-conformant** artifact is not thereby **provider-resolved**; a **direct baseline**
route is not **AuthorityAbsent**; and conditioning can differ per estimand, so no single
artifact-level value can carry it. The enum is withdrawn and replaced by four coordinates.

- **R10.1 Construction route / basis.** How the artifact's content was produced: direct baseline, provider proposal + deterministic resolution, human adjudication, or a named combination. A *direct baseline* route is a **known, published route** — it is not absence of authority.
- **R10.2 Conformance, authenticity, and use-specific admission.** The three witnesses of R3.5, published separately. **Conformance does not imply authenticity, and neither implies admission.**
- **R10.3 Calibration basis.** The named calibration model and target (R4.9), or its absence. Absence is published, not defaulted.
- **R10.4 Per-estimand segmentation conditioning.** Carried **per published quantity**, not per artifact (R8.6). A single artifact may carry a lawful cross-cut value for one estimand and a refusal for another; **conditioning may require refusing a scalar entirely**.
- **R10.5 Derivation.** Every asserted route or class is **derived from bound receipts, recipes, code, config, and subject identity** — never caller-written. No coordinate may appear as a constructor parameter on any public type. Where a coordinate cannot be derived, the published value is an explicit *undetermined* for that coordinate alone; it does not contaminate the others.
- **R10.6 Identity binding.** All four coordinates participate in the complete-construction identity of R9.2. A coordinate that can be changed without changing identity is decoration.
- **R10.7 Coverage.** Enumerate live `RecallSegmenter` consumers — `interview/segment.scala:24`, `embed-bench/wog.scala:118`, `:182` — and prove the enumeration complete by grepping the call graph, not by listing.
- **R10.8 Placement.** Bound into the durable result or the renderer's typed input — never into prose a future renderer can drop.
- **R10.9** Chief scoping required; not self-authorizable.
- **Court R10 — five fixtures, each separating coordinates that revision 2 fused.**

  | # | fixture | required disposition |
  |---|---|---|
  | 1 | **direct baseline:** artifact built through `RecallSegmenter` | route = *direct baseline*; conformance minted; authenticity **unverified**; admission per use. **MUST NOT publish as `AuthorityAbsent`** |
  | 2 | **provider-conformant but unauthentic:** provider output replayed and internally consistent, asserted provider fails the trust root | conformance **passes**; authenticity **fails**; route is **not** promoted to provider-resolved; admission requiring that root does not mint |
  | 3 | **authentic but inadmissible:** authenticity verifies, but the use's admission predicate is unmet (e.g. no calibration for a calibrated endpoint) | authenticity **passes**, admission **refuses**. Proves R10.2's second non-implication |
  | 4 | **adjudicated:** independent multi-rater boundary set with its protocol receipt | route = adjudicated; calibration basis published independently of route |
  | 5 | **caller-label laundering:** a caller attempts to supply any of the four coordinates | **compile-time refusal.** Plus a recompute mutation: altering a bound receipt changes the derived coordinate; altering the coordinate alone changes complete-construction identity |

### R11 — Evidence identity integrity · `CT-R11-PROP` `CT-R11-EVID` `CT-R11-ALIAS`
Revision 1 called this buildable now. Withdrawn: the failure state and the cross-module evidence-admission boundary must be settled first, because R11.4 touches `acquire` and `document` together.

`ps.flatMap(_.evidence).distinctBy(_.evidenceId)` (`resolve.scala:343`) runs **before** any sort and keeps the **first** reference. `EvidenceRef` may be `ById` or `Inline`, and `AgentProposal.from` does not require ids to be unique or same-id references to be content-consistent.
- **R11.1** `Resolver.resolve` MUST NOT depend on proposal arrival order, **or on evidence order within a proposal**, for any published field. The guarantee belongs to the resolver, not to each caller.
- **R11.2** Group references by `EvidenceId` **before** deduplication.
- **R11.3** Validate every same-id group against a canonical evidence ledger / content identity.
- **R11.4 Failure state is not discretionary.** On conflicting same-id inline content, or `ById`/`Inline` disagreement that cannot be re-derived, the resolver MUST return **typed `Unresolved`** and MUST refuse to mint the canonical projection or any scientific admission witness. It MUST NOT return `Rejected`: unverifiable evidence identity does not prove the scientific claim false, and `Rejected` asserts that it does.
- **R11.5** Only then canonicalize the surviving set by stable identity.
- **R11.6** The canonical form MUST be owned in **one module**. Today it is `renderProposal`, private to `document/compiler.scala` — so "canonical" is consumer-relative.
- **R11.7 SHIPPED COMPILER OUTPUT IS AFFECTED.** Revision 1 claimed the opposite. The claim was false and the correction is measured.
  - `canonicalBundle` sorts `bundle.proposals` — the **outer vector only** (`compiler.scala:1393-1403`). It never rewrites `proposal.evidence`.
  - `renderProposal` sorts `proposal.evidence.map(renderEvidenceRef)` — a **rendered copy used as a sort key** (`:1433`). The proposal's own evidence vector is untouched, so the key is *blind* to within-proposal order and could not normalize it even in principle.
  - `collect`'s `flatMap` preserves each proposal's internal evidence order (`resolve.scala:343`).
  - `renderClaimMeta` emits `claim-evidence/v1` from `meta.evidence.toVector` **unsorted** (`compiler.scala:1530`), and that string feeds the compilation digest (`:1694`).
  - **Measured**, single-proposal bundle so any outer sort is a provable no-op: evidence `[eA,eB]` → accepted order `[eA,eB]`; `[eB,eA]` → `[eB,eA]`; not equal.
  - **Therefore:** proposal-order drift is guarded at the five live call sites; **within-proposal evidence-order drift is not guarded at all and reaches the compilation fingerprint.**
- **R11.8 Two records.**
  - **a Raw acquisition trace** may retain arrival and wall-clock sequence for audit, with its own trace identity. **Arrival order is not scientific evidence and cannot select a cut.**
  - **b Canonical scientific input projection** retains every proposal, finding, calibration, call-group/dependence fact, and same-id consistency result — canonically ordered and versioned by stable content/receipt keys under one rule owned in one module.
  - **c** Resolver state and complete-construction identity bind to **the projection**.
  - **d** "Complete" means no proposal was discarded, not that acquisition timing becomes estimand identity.
- **Court R11.** Fixtures and required dispositions:
  1. two proposals, reversed order, called **directly without `canonicalBundle`** — projection, resolved state, construction identity and output **byte-equal**; the raw trace is the intentional differing control.
  2. **one proposal whose own evidence vector is reversed** — same required equality. *This cell fails on `main` today and is the one revision 1 omitted.*
  3. two byte-identical duplicate refs — collapse permutation-invariantly.
  4. two different `Inline` records under one `EvidenceId` — typed `Unresolved`, projection **not** minted. Not `Rejected`.
  5. `ById(x)` plus `Inline(x)` where the admitted ledger proves identical content — succeeds; where it cannot — typed `Unresolved`.
  - A court routed through `canonicalBundle` would pass and prove nothing.

### R12 — Declare `ClaimId`'s subject · ADR-required · `CT-R12-SUBJ`
Three code paths assume three different answers:
1. `ClaimMeta.withCredence`/`withProvenance` construct `new ClaimMeta(id, ...)`; `withStatus`/`withEvidence` pass `id` through (`claim.scala:57-79`) — **all four preserve `ClaimId`, including `withEvidence`** — while `ClaimMeta` equality includes those fields.
2. `renderClaimMeta` commits to `claim-evidence/v1` over **full records** (`compiler.scala:1521-1533`) and feeds the compilation digest (`:1694`) — complete artifact identity already changes with evidence content, as ADR 0005 requires.
3. The compiler keys `ClaimId` on semantic coordinates **plus a digest of evidence ID strings** (`:1311-1319`) — a third rule agreeing with neither.

The measured defect is an **evidence-admission defect plus an incoherent `ClaimId` recipe**. It does **not** establish that evidence bytes belong in `ClaimId`; requiring that would make `withEvidence` a defect across the core API with no migration — the half-done migration N5 refuses.
- **R12.1** **Declare what `ClaimId` describes.**
- **R12.2** Whatever identity is chosen MUST commit to its **full declared subject** — no partial keying.
- **R12.3** Evidence identity admission MUST refuse one `EvidenceId` bound to different canonical content. (= R11; executable and independent of the subject ruling.)
- **R12.4** Complete artifact identity MUST change when status, credence, evidence or provenance changes. Already true; make it a law so it cannot regress.
- **R12.5** Courts MUST be parameterized by the chosen subject, not by assertion.
- Three designs remain lawful. Source most strongly suggests two identities — a stable semantic `ClaimId` over `(source, family, target, value)` plus a content-addressed assertion/revision identity over full `ClaimMeta`; under that design the compiler **removes** evidence labels rather than adding bytes. **Not selected here — a chief/ADR choice a failing court may not make by assertion.**

## E. Scientific gate

- **E1 H1 invariance.** Under content-preserving regrouping with resolved claims fixed, named non-segmentation estimands stay stable within a predeclared tolerance.
- **E2 H2 matched sensitivity.** omission → coverage; source-destination → visitation/external; role/polarity/context → the matching **fidelity** facet with coverage and visitation **stable**; order → chronology; grain → compression; a boundary change making an interpretation incompatible → the joint resolver rejects, retains, or re-resolves. **A metric that moves on the wrong coordinate also fails.**
- **E3 H3 dominance**, three endpoints: a common gist endpoint against exact EventRecall under matched inputs and budget; additional labeled endpoints where every baseline has an explicit decision rule; uncertainty-specific value against MAP-only ablations. *A richer system cannot beat a scalar on outputs the scalar does not define, and cannot win by being the only method that emits a new field.*
- **E4 Preregistration.** Datasets, split unit, metrics, equivalence and superiority margins, intervals, multiplicity handling, and stop decisions. **E4 is an entry condition of M5 and remains in force through M6.** Revision 1 stated E4 before M5 in prose while the table bound it to M6 only; the table below is corrected.
- **E5 Stopping rules.** H1 holds and H2 fails → **stop**, publish the negative. H1+H2 hold, H3 fails → ship the contract as engineering, drop the SOTA claim, no lattice. All three hold → further uncertainty-aware modeling licensed; **a lattice is not specifically licensed** — it needs a real partial order with meet/join laws, and a compatibility hypergraph or factor graph may be the truthful object.

## F. Milestones

| | content | entry |
|---|---|---|
| M0 | R10 + facts F1-F6 | none |
| M1 | R1 | none — court executable today |
| M1b | R11 | **not** "buildable now": needs the R11.4 failure state ruled and the `acquire`/`document` admission boundary scoped |
| M2 | R2 + C2/C3 | M0 |
| M3 | R3 incl. R3-FORGED | M1, M1b, M2 |
| M4 | R4 + R5 + R7 | Q-A specified; Q-E decided. **Calibrated exit** additionally needs P2 |
| M5 | R8 | M4; every quantity classified under R8.6; Q-D decided; **E4 preregistration complete** |
| M6 | E1-E3 | M5 + P2; E4 still in force |

**F1-F6, M0's exit criteria.** F1 two admissible plans of one transcript change downstream estimands. F2 identical graph content under different construction authorities is indistinguishable today. F3 no scientific construction artifact is produced when authority is absent. F4 cross-alternative units cannot be joined by `RecallUnitId`. F5 `leafVisitation` is split-increasing (R8.4). F6 **the three resolver probes** — near-agreement, proposal-permutation, and within-proposal evidence-permutation — promoted from scratch to named courts. *Revision 1 called F6 "the Q5 probes", a board nickname that appeared nowhere in the document.*

**P1** EventRecall reproduction in an authorized environment — validates the gist endpoint only.

**P2 The gold track.** Independent boundary and claim annotations, semantic counterfactual labels for the H2 foils, held-out stories, provider-shift splits, agreement ceilings, frozen receipt — conducted under the **frozen-corpus adjudication protocol** (`docs/plans/2026-08-28-m1-fixture-adjudication-protocol.md`), including segmentation-before-targets and leakage control. *Revision 1 called this "the M1 adjudication protocol", colliding with this document's own milestone M1.* **P2 gates calibrated M4 acceptance — not Q-A specification.**

No story text or transcript enters this repository until it passes `docs/design/story-text-admission-checklist.md`; participant recall text is barred until the owner records an REB basis.

## G. Open questions

- **Q-A** Set-level observation model. Strawman: order-preserving assignment/detection likelihood. **Q-A1** (state space, shape) may support exact `Alternatives`/`Unresolved` before P2; **Q-A2** (probabilities, `Accepted`) only after specification, calibration, held-out validation and the declared admission predicate. **Two claims are damaged and unrepaired:** order-preserving matching is a *monotone-channel assumption*, not a consequence of sorting, and it carries all the tractability (needs a rank-swap court); and the factorized location kernel turns one systematic tokenizer offset into m independent errors (`k` must model the residual vector). "Checked against six courts" was **design intent, never a passed result**. Open model research; unowned as a deliverable.
- **Q-B** Gold track ownership and cost. **Unowned; declined by four actors.** Not a modeling question — adjudication, ethics, corpus authority, REB basis. Gold is a **named calibration target** (adjudicated plan, human boundary distribution, or task-loss region), **not metaphysical true boundaries**.
- **Q-C** *Answered:* cell length = geometry; minimum permitted length = admission; eligibility = a third predicate.
- **Q-D** Per-quantity pushforward-or-label ruling. Blocks M5.
- **Q-E** D2 — one boundary estimand or two. Blocks M4/R5. **Fails closed.**
- **Q-F** Hypergraph versus lattice. Deferred; nonblocking through M6.

## H. Measured facts

All on `adf58cad` / `6ec8b698`, in an isolated `git archive` tree.

- Near-agreement: two distinct providers at 412 and 413 → `Accepted(412, p=0.95)`, **dissent unrecorded**; under a high-impact policy the same evidence → `Unresolved(InsufficientAgreement(1,2))`. **D1 and wholesale `acquire` reuse are jointly false.**
- Proposal order decides the cut: `[412,413]` → `Accepted(412)`; `[413,412]` → `Accepted(413)`.
- **Within one proposal**, reversing the evidence vector changes the accepted state — with a single proposal, so `canonicalBundle` is provably not the guard.
- `leafVisitation` split-increasing: `1-e^(-n*m)`, 0.632 → 0.865 at m=1.
- `RecallGraph.validated` enforces no order/overlap/coverage law — "two admissible segmentations" was undefined.
- `SpanSet` is evidence support and `validated` compares the hull.
- Same `EvidenceId` with different `Inline` content → same `ClaimId`, **different** compilation fingerprint.

## I. Standing

**Zero carried ACKs.** No ACK from any earlier artifact transfers to these bytes. Prior ACKs
were given to a board post; revision 1 and revision 2 each had different bytes again. The chief
read revision 2's exact 36,095 bytes / `b69819b8…` and **returned it**; his decision states it
"deliberately does not convert elapsed discussion or consensus about the diagnosis into approval
of defective bytes." That discipline is the contract's own R12 applied to the contract.

**No supersession is claimed.** An unadopted draft supersedes nothing; revisions 1 and 2 and the
board drafts remain on the record.

Fresh exact-byte review seats for this revision are `codex-storymodel4s-scout` (authority/identity
and executable-court honesty) and `codex-storymodel4s-recall-collab` (segmentation/semantic
cardinality); `codex-storymodel-collab` may add a product-boundary review. The chief adopts only
after both required reviewers approve **these bytes** and a fresh chief read.

The scientific spine — the CODE RED diagnosis, cells/supports/eligibility separation,
conformance-versus-authenticity, refusal of uncalibrated acceptance, the segmentation-conditioned
metric warning, and the R1/R11 defects — was confirmed against HEAD by every reviewer who returned
a draft. What has been repeatedly rejected is this document's discipline about its own guarantees.

## J. Chief actions outstanding

1. Dispose C1-C5 and R1-R11; R12 as ADR-required.
2. Rule the R11.4 failure state and scope the `acquire`/`document` evidence-admission boundary. `codex-storymodel4s-scout` volunteered for R11 before it was known to span two modules.
3. Bead for R1 — court executable today, reject side fails on `main`, unclaimed.
4. Scoping for R10.
5. **An owner for Q-B.** Declined by four. No agent on this board can resolve it.

*No implementation authority, public vocabulary, dependency, path, or reservation is claimed by this document.*
