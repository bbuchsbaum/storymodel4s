# Recall as navigation: what the Antony proposal is worth, and what it costs

*2026-09-03.* An assessment of an external proposal that reads Antony et al. (2024, *JoCN*
36(11):2368) as a reference model for storymodel4s and specifies a "Multiplex Narrative Navigation"
programme against it: four new modules, seven phases, thirteen validation gates.

Every code claim below was checked against `origin/main` `913f3a8e`, and re-checked by an
independent vetting pass on 2026-09-03/04 that corrected this document (§9); data claims cite the
content-addressed admission records under `docs/data/`, or the workbooks read directly with a
dependency-free xlsx reader. The proposal itself is **not** in this repository — it arrived as a
message, so every characterisation of it here is unverifiable against the tree and should be checked
against the source text. Companions: `2026-09-03-memento-integration.md` (what the corpus costs and
buys), `2026-09-03-second-corpus-scouting.md` (Film Festival first),
`2026-09-03-second-dataset-survey.md` (the candidate survey; it repeats three Memento errors that §9
corrects), and `2026-09-03-story-and-viewer-handoff.md` (what the model actually contains).

## Verdict

The proposal's **diagnosis is worth more than its prescription**. It identifies three defects that
are real, that I verified, and that touch numbers this repository has already published. Its
prescription is roughly an order of magnitude larger than the repository can absorb, assumes a
source model that the pipeline does not yet produce, and — in three places — proposes types that are
*weaker* than patterns already standing in this codebase.

There is a three-slice fix inside twenty-three sections. This document separates them.

## 1. The three defects, verified

### 1.1 `CausalNeighbor` loses direction

`align/src/main/scala/storymodel4s/align/hsmm.scala:75-77`:

```scala
        TransitionKind.CausalNeighbor -> ind(
          view.hasEdge(RelationLayer.Causal, s, t) || view.hasEdge(RelationLayer.Causal, t, s)
        ),
```

Moving from a cause to its effect and moving from an effect back to one of its causes are the same
feature, with the same weight. The story side already keeps them apart — `CausalEdge(cause, relation,
effect)` at `story/relations.scala:114-119`, indexed both ways as `causalOut`/`causalIn` at
`story/graph.scala:139-140`. The loss is entirely in the projection, which is where the proposal
places it. `SameEntityThread` is symmetrized the same way; `SemanticNeighbor` takes the `max` of both
directions.

This matters for a specific reason the paper supplies: scene memorability correlates with **inbound**
causal weight (r = .48) but not outbound (r = .12, ns). A feature that cannot tell inbound from
outbound cannot represent that asymmetry, let alone test it.

### 1.2 The two clocks are collinear in every video run

`embed-bench/src/main/scala/storymodel4s/bench/video/RecallToVideo.scala:182-196`:

```scala
    val succession =
      leaves.sliding(2).collect { case Vector(a, b) => (a.ref, b.ref, 1.0) }.toVector ++
        groupNodes.sliding(2).collect { case Vector(a, b) => (a.ref, b.ref, 1.0) }.toVector
    val edges = Map(
      RelationLayer.DiscourseSuccession -> succession,
      RelationLayer.WorldTime -> succession
    )
    val worldOrder: Map[SourceNodeRef, Int] =
      leaves.map(n => n.ref -> n.discoursePosition).toMap ++ ...
```

This is worse than the proposal states. `worldOrder` is *also* discourse position, so three things
collapse onto one axis: `DiscourseSuccessor` (θ = 1.5) and `WorldTimeSuccessor` (θ = 1.0) fire on the
same edges — an effective 2.5 discourse prior with 40% of it labelled "world time" — and
`Backward`/`LongJump`, computed on text midpoints via `SourceView.measuredPosition`, are monotone in
the same order.

And the shipped configuration amplifies it. `RecallToVideo.run` uses
`RecallOrderControl.scaledConfig(1.5)` by default, and `orderingKinds` — the set that scale
multiplies — contains `DiscourseSuccessor`, `WorldTimeSuccessor`, `Backward` and `LongJump`, but
**not** `CausalNeighbor`. So on the shipped path the two collinear order features carry
1.5 × 1.5 = 2.25 and 1.5 × 1.0 = 1.5, summing to **3.75 on a single edge**, while causal structure
sits at 0.8, unscaled. The code is candid about the provenance of the 1.5: "development-only … the
untouched participants were already spent confirming the lexical blend, so this value has not been
checked out of sample."

Every Sherlock number in the study record was produced under this. On Sherlock the two clocks
genuinely do nearly coincide, so the falsehood is cheap there; it is not cheap on any corpus where
they diverge, which is the entire reason Memento is interesting.

The mission already forbids the shape of this. Commitment 5 (`mission.md:78-80`) requires that
discourse time, story-world time and recall time be treated as separate clocks, and ADR 0007
(`docs/adr/0007-film-source-representation.md:230`) rules that "missing alignment is a typed
refusal." A world clock that is silently the discourse clock is neither separate nor refused.

### 1.3 The aligner asserts a navigation prior and then reports results computed under it

`TransitionModel.default` (`hsmm.scala:31-46`, docstring at 27-30) is twelve hand-set constants,
documented "Provisional defaults", including `CausalNeighbor -> 0.8` and `DiscourseSuccessor -> 1.5`.
**Nothing fits them.** There is no EM, no Baum–Welch, and no gradient path over θ anywhere in the
repository; the only optimizer that exists is the 25-line full-batch gradient descent in
`PlattScaling` (`align/calibration.scala:45-69`). `MonotoneScene` compounds it on the video path:
`def backwardPenalty: Option[Double]`
(`embed-bench/src/main/scala/storymodel4s/bench/video/MonotoneScene.scala:79-82`) reads
`STORYMODEL4S_BACKWARD_PENALTY` and matches `case Some("hard") | None => None`, so backward steps
are forbidden **outright** unless someone sets the variable — a decoder whose own docstring
(`:66`) records that "this default is fitted to this corpus and should not travel unexamined."
The two differ in reach: `TransitionModel.default` is repository-wide, while the hard backward
prohibition is bench-lane code confined to the recall-to-video path.

So the proposal's §4.3 point lands harder than it makes it. Its claim is that `GraphHsmm`'s
transition parameters are not cognitive strategy estimates. The truth is that they are not estimates
at all — they are assertions. Any statement of the form *"recall followed causal structure"* computed
over alignments produced under `CausalNeighbor -> 0.8` is circular, and that is the proposal's §14
circularity, live today, in numbers already claimed.

Two mitigating facts: `RecallOrderControl` exists precisely to measure how much of a reported tau
survives shuffling the recall, and `docs/design/gates.md` already *requires* the ablation ladder
`content → +hierarchy → +order → +causality → +external → +sensory`. The ladder is
unimplemented.

## 2. Six things the proposal does not know

Each one changes what should be built.

### 2.1 The firewall already exists, in a stronger form

`FeatureUseLedger.scoreBoundary` (`features/src/main/scala/storymodel4s/features/ledger.scala:39`,
contract stated in its docstring at 32-38) is the only path the normal API offers to a
`BoundaryScore`: the score and the ledger entry are produced together, "so an induction step cannot
forget to declare its inputs." The docstring claims more — "the only way" — and that claim does not
survive the repository's own criterion. `BoundaryScore` (`features/boundary.scala:78`) is a case
class with a `private[features]` constructor, no companion, and all-public field types, so it is
forgeable through `fromProduct` from anywhere and through `copy` inside the package
(`docs/design/unforgeable-types.md:22-38`); no probe suite covers it. `AblationResult`
(`hsmm.scala:483`, docstring 479-482) is deliberately typed with no `AlignmentMatrix` so an ablation
cannot masquerade as a scientific alignment.

The proposal's §14 `final case class AnalysisFirewall(...)` is a record you can forget to fill in.
`docs/design/unforgeable-types.md` opens by explaining why exactly that shape carries no guarantee:
Scala 3 gives every case-class companion a public `fromProduct` through `Mirror.Product`, so a
`private` constructor does not close the door. The same door stands open on `BoundaryScore`.

**The navigation firewall should be a ledger in the `FeatureUseLedger` discipline with the
construction closed — a `final class … private` with a checked constructor, derived where the
alignment's own gate proof is derived — not a receipt case class, and not a copy of
`BoundaryScore`'s shape.** Same discipline the repository applies to feature spaces and boundary
induction, applied to relation layers and alignment, and closed the way `HsmmResult` already is.

### 2.2 The dependent variable already exists, and it is better than the paper's

| Proposal wants | Already in the repository |
|---|---|
| per-transition navigation observations | `FlowStep(from, to, mass: Map[(AlignState, AlignState), Double])`, `align/matrix.scala:294-299` — the *soft* transition posterior `F_i(s,t)` |
| population transition matrix | `PopulationAggregate.populationFlow` = `F̄_vw`, `align/population.scala:219` |
| §19 memorability `Y_sv` | `PopulationAggregate.visitationMatrix`, subject × node, `population.scala:285` |
| directional recall summaries | `RecallSignature.causalPreservation`, `backwardMass`, `worldBackwardMass`, `discourseChronology`, `worldChronology`, `semanticFlowCoherence`, `align/signature.scala:24-48` |
| kernel row laws | `AlignmentLaws` already pins posterior rows as distributions and flow marginals against the posterior both directions, `laws/Laws.scala:186-259` |

This makes the proposal's §8 `NavigationChoice(subject, current, chosen, eligible, …)` a **regression
in this codebase**. Hardening the alignment posterior into a single chosen node discards the
unresolved mass — failure mode 2 in ADR 0003 verbatim: *"Excluding unresolved mass and then
renormalizing changed an attributed probability from 0.10 to 1.00 without adding evidence."*

The right dependent variable is `FlowStep`; the right likelihood is an expectation over it, not a
conditional logit over argmax labels. Note also that §8 never says what the choice set `𝒜_si` is —
and the choice set *is* the denominator that ADR 0003 exists to make you declare.

### 2.3 `navigation-fit` cannot be a Scala module here

`build.sbt` declares no numerics library: cats, circe, munit, scalacheck, onnxruntime,
djl-tokenizers, anthropic-java. No Breeze, Spire, Smile, ND4J, EJML. The one numeric dependency is
grakern, an in-house graph-kernel calculus pinned by SHA as an sbt source dependency rather than a
declared library (`build.sbt:228-242`); it is JVM-only and feeds only `embed-grakern`. Everything
else numeric is `scala.math` over `Vector[Double]`. And `align`/`story`/`features`/`recall`
cross-compile to JVM, Scala.js and Native under mission commitment 9.

Hierarchical random effects, a sticky strategy HSMM, a posterior over causal graphs, and dynamic
logistic-normal mixtures do not fit in that spine. The repository's existing precedent for
statistics is Python without dependencies (`tools/recall-study/*.py`: hand-written Kendall tau-b,
Wilcoxon, seeded percentile and paired bootstraps, and a participant-clustered bootstrap at
`gold_scene.py:81`).

**Split accordingly: kernels, directed features, the ledger and the laws are portable Scala; fitting
is not.** The proposal's four-module family (`navigation-core`, `-fit`, `-memento`, `-bench`)
collapses to one small portable module, one corpus adapter beside `bench/sherlock/`, and Python.

### 2.4 Memento's `visSim` is a colour-strand indicator, not a visual measure

`docs/data/memento/source-manifest.json` records that `MementoStoryBoard.xlsx` carries a `visSim`
sheet alongside `CausalityRatings`, `ImportanceRatings` and `blacktowhite`. The manifest names the
sheet but not its shape. Read directly, `visSim` spans `A1:AT46` with indices 1…44 along both the
first row and the first column — the causal node set — but its 968 filled off-diagonal cells hold
only three values, 1, 0.65 and 0.35, and one rule reproduces every cell without exception: 1 when
two scenes share parity in presented order, blank otherwise, with row and column 44 carrying 0.65
against odd scenes and 0.35 against even. Odd presented scenes are the colour strand and even the
black-and-white strand; 0.65/0.35 is the last scene's own colour split (§7). So `visSim` is a
same-colour-mode indicator and carries no visual information beyond it.

§3.5 argues the paper's semantic representation is text-only and proposes Phase 5 — full audiovisual
reconstruction — to fix it. Nothing multimodal is staged that would give Gate G9 ("multimodal
value") a cheap first version; that gate waits on media, as the proposal says. Two further things
the storyboard is not: its own `CausalityRatings` and `ImportanceRatings` sheets are header-only
templates, and no consensus matrix or vector is stored anywhere — the ratings live in seven separate
rater files (§3).

### 2.5 Film Festival ships a per-event network-metrics table

`docs/data/filmfestival/source-manifest.json` records `derived/NatComm2022_SourceData.xlsx` (Lee &
Chen 2022 *Nat Commun* 13:4235 Source Data, **CC BY 4.0**) with `natCommEvents: 202`. Read directly,
its sheets carry `semanticcentrality`, `causalcentrality`, `indegreecentrality`,
`outdegreecentrality` and `recallprob` over 202 event rows.

That is §19's memorability model and G11's leave-film-out evaluation with a second staged corpus,
and the inbound/outbound asymmetry testable on a *different* film. The values are standardized
scores, not raw degrees. Not counted by the manifest, `SuppFig7c`/`SuppFig7e` carry a second
252-event table (semantic and causal centrality, recall probability) over the ten different films of
the paper's online experiment, which doubles the leave-film-out material. These are per-event
network metrics rather than an adjacency matrix — the 18 coders' causal links are not released — so
they support a memorability replication and not a second navigation kernel, and they land on the
corpus the scouting brief says to do first.

### 2.6 No observer or rater type exists anywhere

`Rater`, `Observer`, `InterRater`, `partial pooling` and `shrinkage` return zero hits in any Scala
source (the eight hits for "rater" and one for "shrinkage" outside the agent message board are
English prose in design notes and plans). `Annotator` matches only two places, neither of them a
type: `Provenance.human(annotator: String, …)` (`core/provenance.scala:141`), which hashes the
annotator's name into a config checksum rather than giving it identity, and a fixture
`Fingerprint`. `hierarchical` matches two comments, one about the narrative hierarchy and one about
partial-versus-hierarchical graph matching — never a statistical hierarchy over judges. Human
identity otherwise enters only as `EpistemicStatus.HumanAdjudicated`.

So §3.4 (modelling rater disagreement rather than averaging seven matrices) is **genuinely new type
work**, and it is the proposal's best-fitting scientific idea: mission commitment 6 already requires
"provider disagreement as explicit outcomes rather than error residue", and the raters' leave-one-out
reliability of r = .10–.70 is exactly the denominator ADR 0003 forbids discarding.

But the vocabulary already exists and should be extended, not duplicated: `acquire`'s
`Weighted[+A](value, providers, proposals, score)` and `ResolutionState[+A] = Accepted(value, basis,
evidence) | Alternatives(NonEmptyVector[Weighted[A]]) | Unresolved(failure) | Rejected(reason)`
(`acquire/resolve.scala:12-62`) are the shape for "several parties disagree about one claim." A parallel `PairObservation` /
`PairMeasureSpace` family that does not reuse them would fork the repository's disagreement
vocabulary in two.

## 3. What the proposal assumes that is not true here

The proposal's target is "uncertain multiscale events + typed multiplex relations + multimodal
evidence + probabilistic recall construction + generative navigation." The typed multiplex relations
exist **as types** and are **unpopulated**. From the handoff, on the fifty-sentence replay:

- causal, goal, state-change, reference and entity-relation layers are all empty;
- the temporal layer is discourse order relabelled — 64 edges, all `Unclear`;
- the hierarchy is one segment with zero boundary beliefs;
- and, from the code rather than the handoff, `RelationLayer.Semantic` defaults to empty in both
  `StorySourceView` constructors (`StorySourceView.scala:295,305`) unless a semantic map is injected.

And of the nine declared `RelationLayer` cases (`align/source.scala:45-74`), **no `TransitionKind`
reads `Goal`, `StateChange` or `Reference`**. `TransitionFeatures.between` (`hsmm.scala:58-89`)
touches five layers by name — `DiscourseSuccession`, `WorldTime`, `Causal`, `EntityContinuity`,
`Semantic` — and reaches hierarchy through `view.isAncestor` rather than `RelationLayer.Hierarchy`.
The three unread layers are not dead elsewhere: `StorySourceView` (`:253-255`),
`story/alignment.scala` (`:164-182`) and the view compiler (`compiler.scala:1152-1168`) all build and
render them. They are invisible *to navigation* specifically.

This cuts both ways, and the cut is the whole argument for sequencing.

**For:** Memento *hands you the graph*. Seven raters' 44×44 directed causal matrices in seven
separate files (rows are causes, columns effects; self-diagonal blacked out; 1,892 off-diagonal
cells, of which each rater fills between 45 and 1,891; r1 holds one out-of-scale value of 36 and r6
alone writes explicit zeros), per-scene importance in the same seven files with no consensus stored
anywhere, `StoryOrderSceneNum` as a per-subscene story-order label whose 44 values are shared within
a broad scene and twice across them (presented scene 16 carries 8 and 37; 37 is also held by scene
15 — a rank with ties, not a permutation), and `NarrativePart` on all 128 subscenes — all recorded
with content digests in `docs/data/memento/source-manifest.json`. The navigation work is tractable
precisely because it does not wait on the M1–M5 induction programme.

**Against:** a navigation result on Memento is a result about *human ratings of Memento*, not about
anything this pipeline can construct unattended. Do not let the two be confused when the numbers are
written down.

## 4. Where the proposal should be overruled

### 4.1 "Exact reproduction first" is the wrong first deliverable

Three reasons.

**The semantic arm cannot be reproduced.** It requires Google's Universal Sentence Encoder at 512
dimensions over a cleaned screenplay. The ratings ship; the embeddings do not. The embedding contract
(`embed-core`, ADR 0001) is provider-neutral and portable; the only neural runtime is ONNX, JVM-only
and firewalled from the portable modules. Adding a TensorFlow-era model to a Scala 3 JVM/JS/Native
build to recover a number that is not the interesting one is a poor trade, and the
memento-integration brief already recorded that our encoder makes the semantic numbers
non-comparable.

**Reimplementing a working notebook duplicates a working artifact.** The paper's code reproduces its
own figures. Doing it again in Scala buys reassurance about our importer at the price of a second
implementation to maintain.

**So move the court.** The reproduction gate belongs on the **importer**, not the figures: the Scala
loader must reproduce the notebook's *inputs* — the seven raw 44×44 rater matrices and seven
importance vectors cell for cell, a *declared* aggregation rule (how r1's 36, r6's explicit zeros
and r5's importance 0 are treated; no consensus is shipped to check against), the per-subscene
story-order labels as a rank with ties, and the recall label table — to declared tolerance, with
every value traceable to a workbook cell. Figures stay the notebook's job.

The genuinely cheapest first move is already in the memento brief and I would not change it: run
Memento through `agreement.py`, the gold-free judge of record. It needs no gold join, but it is not
corpus-neutral as it stands — line 28 hardcodes Sherlock's two-part timeline and line 51 drops rows
whose media part it does not recognise — so a small corpus adapter comes first. 130 index-backed
recall sheets (123 after the population record's own partition rule) against Sherlock's 17.

### 4.2 The M1–M7 ladder is ordered backwards

§3.6 concedes that in *Memento* the strongest causal edge is often the next chronological event, so
the two strategies are barely separable in the stimulus. §13's diagnosticity —
`JSD(K_D(v,·), K_W(v,·), K_C(v,·), K_S(v,·))` per node — is the measurement of exactly that.

Then §17 proceeds through M1→M7 as though fitting were the answer. It is not. Where the kernels
agree, a hierarchical prior does not recover separate coefficients; it relocates the confound into
the prior and reports a posterior anyway.

**Diagnosticity runs first, on the imported matrices, and is allowed to veto the ladder.** If the JSD
is near zero across most transitions, that is the finding, and it is publishable as a design result
about *Memento* — it is also precisely what §3.6 asks future stimuli to fix. This is the same move
that produced `RecallOrderControl`; the repository already knows it.

### 4.3 §12 is the best idea in the document and has no data

Online causal model (what the viewer could know by presentation time *t*) versus retrospective causal
model (reconstructed after the final revelations), compared as predictors of recall. The context
system supports it — `ContextKind` has nine cases (`story/nodes.scala:183-227`), contexts form a
tree enforced by four validator laws (`story/validate.scala:366-387`), and temporal edges are
context-scoped so "chronology asserted inside a speech or belief never leaks into" the root
(`story/alignment.scala:49-50`).

But it requires *Memento* re-rated under a "what did you know at time *t*" protocol. That is new data
collection. Record it as a scientific aim; keep it out of any phase plan.

### 4.4 §18 and §20 are blocked upstream, not by us

Film is an accepted *representation*, not a supported workflow: ADR 0007 accepts typed film
evidence on a presentation axis, and the media module can mint only `ObservationAuthority.Draft`
(`core/source.scala:53-57`), with no promotion path to accepted evidence. And *Memento* has no
video — a commercial feature, not distributed. §20's neural extension cites a July 2026 preprint
that is unverified in this session.

### 4.5 §16's licensing note names the wrong gate

The bytes are already staged correctly and git-ignored, and — since the memento-integration brief
was written — the `docs/data/memento/` source admission record now exists (untracked in this
checkout, alongside `docs/data/filmfestival/`). It records `admissionStatus.state: "proposed"`,
`courtOpened: false`, and names its own three blockers: an owner decision to open an acquisition
court, `docs/design/story-text-admission-checklist.md` §3 (an REB/IRB basis recorded by the owner,
which the checklist's preamble requires to be checked by someone other than the proposer, binding
the moment any participant recall prose is committed), and the upstream repository's missing
LICENSE file. §1 of the same checklist separately bars admitting recall transcripts on a
public-domain basis, so "it is on GitHub" is not a basis.

So the proposal's instinct is right and its gate is wrong: the blocker is not a licence question we
could resolve by reading a repository listing, it is an owner decision and an ethics basis. The
record also closes the memento brief's open reconciliation — `recall-population.json` maps 130 of
the 133 recall sheets against the article's four experiments (three sheets match no condition;
conditions 3 and 4 exact; 1 and 2 each one sheet over enrolled), so condition can now be used as a
factor.

## 5. The argument the proposal should have made

The vision asks:

> Did recall follow presentation order, story-world time, causal structure, or another route
> involving jumps, reversals, revisits, and compression?

That is the paper's question verbatim. It is also the one vision question this repository currently
**cannot answer honestly**, because the aligner asserts the answer as a hand-set prior
(`CausalNeighbor -> 0.8`, `DiscourseSuccessor -> 1.5`, backward steps forbidden) and then reports
results computed under it.

Separating source identification from navigation inference is therefore not scope creep. It is what
makes an existing commitment true. That is a better argument for the programme than anything in the
document, and it is available today, without Memento.

## 6. Recommendation

### Three slices now, each justified without Memento

1. **`WorldOrderInput`** — adopt §4.2 nearly verbatim. `Explicit(rank) | SameAsPresentation(witness)
   | Unknown(reason)`, required by `TimedSourceView.build`. Replaces a silent fallback with a typed
   refusal, which is ADR 0007's own rule (`:230`), and is a precondition for Memento telling us
   anything at all. Small: on `origin/main` there is exactly one production `build` call site
   (`SherlockRecallMapping.scala:192`) plus five across three suites; `TimedSourceView.Built` is
   then consumed by `MonotoneScene`, `PosteriorSidecar`, `VoyageExport` and `RecallToVideo`. Two
   costs the proposal does not name: `worldChronology`/`worldBackwardMass` key off
   `view.worldOrder` while `WorldTimeSuccessor` keys off the `WorldTime` edges, so an unknown order
   must drop both; and `ViewFingerprint` hashes both, so an `Unknown` declaration changes a run's
   identity while `SameAsPresentation` does not — Sherlock keeps every landed fingerprint by
   declaring what it silently assumed, with its exceptions (the annotation's marked flashbacks)
   written into the witness. `Explicit` must accept a rank with ties (§3). Landed as ADR 0013.

2. **Split `CausalNeighbor`** into `CauseToEffect` / `EffectToCause`, keeping the old symmetrized
   case unchanged and leaving the new kinds out of `TransitionModel.default` — an absent key weighs
   0.0 — so the shipped score is bit-identical by construction. Not a deprecated alias: Scala 3 has
   no enum case that aliases two others, the default references the old case (warnings are errors in
   spirit), and reciprocal causal edges are legal (`validate.scala:320` forbids only self-edges),
   where OR and sum differ. Inert on the text lane today — the causal layer is empty — so it costs
   nothing until a graph is supplied, which Memento does.

3. **Make the asserted prior undeclarable by omission.** A relation-layer use ledger in the
   `FeatureUseLedger` *discipline* with the construction closed (§2.1), derived inside
   `HsmmResult.validated` beside the gate proof; a θ checksum in run provenance — today a pure θ
   change is invisible to every identity check, and `configRendering` records `priorScale=1.0` when
   the run actually used 1.5; and the ablation ladder `gates.md` already requires. This is the slice
   that touches published numbers, and it is the one worth doing even if nothing else here ever
   lands.

### Memento, in the order the existing brief already sets

4. `agreement.py` first, after a corpus adapter for its Sherlock-specific timeline (§4.1): gold-free,
   130 participants.
5. E1 vs E4 as the adversarial test of the monotone prior — same film, same runtime, same free-recall
   instruction, differing only in whether presentation order carries the chronology.
6. **Add one item before any fitting: the diagnosticity map over the imported kernels.** It says
   whether the causal-versus-chronological question is answerable in this stimulus.

### Defer

The four-module family, the M1–M7 ladder, Phase 5 (audiovisual) and Phase 7 (neural).

## 7. Two flags

- **`blacktowhite` describes the last scene, and the colour mapping is elsewhere.** The sheet holds
  eight cells: a timecode (01:39:45), a header "Calculated proportion black+white vs color in
  **last scene** by", 347 s black-and-white, 642 s colour, and the proportions 0.3509/0.6491.
  347 + 642 = 989 s is exactly broad scene 44, from its first subscene at 01:33:58 to the credits
  marker at 01:50:27, switching from black-and-white to colour at 01:39:45. (The manifest's
  `lastSubsceneTimecode` is that credits marker, and its `subsceneRows: 129` counts it; there are
  128 subscenes.) The per-scene colour mapping *is* in the release, in `visSim` (§2.4): odd
  presented scenes are colour, even are black-and-white, and 44 is both. That is worse news for the
  control than its absence would be. *Memento*'s colour strand is its backward-running strand, so
  colour coincides with the `NarrativePart` Forward/Backward family on 43 of 44 scenes; a model that
  "recovers narrative structure" from strand membership may be recovering colour, and the two are
  separable in this stimulus only through scene 44.
- The July 2026 preprint cited in §20 was not verified in this session.

## 8. Scorecard

| Proposal section | Verdict |
|---|---|
| §2 name the two clocks separately | Done at the type level (`RelationLayer.DiscourseSuccession` / `WorldTime` in `align`; `WorldTimeTransition` in `features/boundary.scala:60-71`), undone at the value level on the video path (§1.2) |
| §3.4 rater uncertainty | **Strongest scientific fit**; new type work; extend `acquire`'s disagreement vocabulary, do not fork it |
| §4.1 directed causal | **Correct and verified**; slice 2 |
| §4.2 `WorldOrderInput` | **Correct and verified**; slice 1; adopt nearly verbatim |
| §4.3 align ≠ navigation | **Correct, and sharper than stated** — θ is asserted, not estimated |
| §6 pairwise measure algebra | Right instinct; must reuse `Estimate`/`Credence`/`ResolutionState`, and be unforgeable |
| §7 kernels + laws | Nothing like it exists; portable Scala; worth building when a consumer exists |
| §8 conditional-choice model | **Regression as specified** — hard labels discard unresolved mass (ADR 0003); use `FlowStep` |
| §11 availability vs. transition | Good; `visitationMatrix` is the DV already |
| §12 online vs. hindsight causal | **Best idea in the document**; needs new data collection |
| §13 diagnosticity | **Underrated**; should run first and be allowed to veto §17 |
| §14 firewall | Right principle; the standing mechanism is stronger in discipline but has the same open door (§2.1), which slice 3 closes |
| §15 four modules | Over-built; `navigation-fit` cannot be Scala here |
| §16 exact reproduction | **Wrong first deliverable**; move the court to the importer, whose target is the seven raw rater files (no consensus is shipped) |
| §17 M1–M7 ladder | Ordered backwards |
| §18, §20 | Blocked upstream |
| §21 gates | G6 (firewall) and G4 (identification) are the two that bind; G9 has no cheap version (§2.4); the rest presuppose work that is deferred |

## 9. Vetting record (2026-09-03/04)

An independent pass re-checked every claim above against `origin/main` `913f3a8e` (two code
agents, against both the local checkout and a detached `origin/main`) and against the workbooks
(one data agent with a dependency-free xlsx reader). What it changed in this document, and why:

- **Two normative citations pointed at uncommitted text.** The earlier fact-check moved the
  "typed refusal" quote from ADR 0007 to `mission.md:78` and cited `mission.md:269` for film
  status. Committed `mission.md` is 126 lines on every base; both sentences exist only in an
  uncommitted 289-line rewrite of the mission in one checkout, belonging to another workstream. §1.2
  now cites commitment 5 and ADR 0007:230; §4.4 cites ADR 0007 and `ObservationAuthority.Draft`.
- **§2.1 overstated the standing firewall.** `BoundaryScore` is forgeable by the repository's own
  criterion; the section and slice 3 now say so.
- **§2.4 and the first flag of §7 were wrong about the workbook**, and the earlier pass had
  "restored" the wrong reading. `visSim` is a colour-strand parity indicator; the colour mapping is
  therefore in the release, and it is collinear with `NarrativePart` on 43 of 44 scenes.
  `blacktowhite` is eight cells describing scene 44 exactly.
- **Counts and shapes.** 133 participants → 130 index-backed sheets (123 analysable);
  `StoryOrderSceneNum` is a per-subscene rank with ties, not a permutation; no consensus causal
  matrix or importance vector is shipped (the storyboard's rating sheets are empty templates);
  `agreement.py` hardcodes the Sherlock timeline; Film Festival carries a second 252-event table.
- **Attributions.** ADR 0001 pins `embed-core`, not ONNX; `MonotoneScene` is bench-lane, not
  aligner; `WorldTimeTransition` is a `features` enum; the Semantic-layer bullet in §3 is
  code-derived; "zero hits repo-wide" is zero hits in Scala; grakern is a source-pinned numeric
  dependency; six line citations drifted by one to six lines and are corrected in place.
- **Facts the slices needed** that the document had not stated are now in §6: the two world-order
  consumers that must be dropped together, the `ViewFingerprint` consequence of an `Unknown`
  declaration, and the unrecorded θ provenance (including `priorScale=1.0` rendered for a run that
  used 1.5).

Everything else — the three defects, the twelve constants, the absence of any fitting path, the
`FlowStep`/`populationFlow`/`visitationMatrix` inventory, the laws, the admission record and its
blockers, the citation and digests of both corpora — was confirmed. The proposal text is still not in
the tree.
