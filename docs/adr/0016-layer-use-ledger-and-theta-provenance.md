# ADR 0016 — A layer-use ledger derived from θ, θ in provenance, and the ablation ladder

**Status:** Accepted 2026-09-04, single-developer mode (AGENTS.md SD5)

**Date:** 2026-09-04

**Decider:** the owner's agent, single-developer mode

**Plan:** `docs/plans/2026-09-03-navigation-assessment.md` §1.3, §2.1, §6 slice 3;
`docs/design/gates.md` (ablation ladder)

## Context

`TransitionModel.default` is twelve hand-set constants, documented as provisional. Nothing fits
them: there is no EM, no Baum–Welch, no gradient path over θ anywhere in the repository. Every
alignment in the study record was produced under `CausalNeighbor -> 0.8` and
`DiscourseSuccessor -> 1.5` (scaled to 2.25 on the shipped path), and any sentence of the form
"recall followed causal structure" computed over such an alignment is circular unless the prior it
ran under is written where a reader can find it.

Until this ADR nothing recorded which layers the aligner read or what θ it ran under.
`HsmmConfig.toString` omits `transitions` on purpose; `ViewFingerprint` hashes the view, not the
model; and the run's provenance string carried a `priorScale` field that rendered `1.0` for a
run that used `scaledConfig(1.5)`. A pure change to θ was invisible to every identity check, and
the ablation ladder `gates.md` requires — `content → +hierarchy → +order → +causality →
+external → +sensory` — had no way to be expressed, let alone declared.

The assessment proposed deriving the ledger inside `HsmmResult.validated`, beside the gate proof.
That is not available: `validated` takes no configuration, has sixteen call sites (five outside
tests, including the codec's decode path and the gate-proof laws that re-validate a result from
its parts), and a new field on `HsmmResult` would be an `hsmm/v3` schema bump. The ledger is a function of θ alone, and
θ lives on `HsmmConfig`, whose constructor is already private with a checked `of`. That is where a
derived, unforgeable value belongs.

## Decisions

### 1. `LayerUse` is derived on `HsmmConfig`, never supplied

```scala
final class LayerUse private (weighted, withheld: Set[TransitionKind],
                              layersRead: Set[RelationLayer], positionsRead: Boolean,
                              externalIn: Double, externalStay: Double)
object LayerUse: def of(model: TransitionModel): LayerUse

final class HsmmConfig private (...):
  val layerUse: LayerUse = LayerUse.of(transitions)
  def fingerprint: Checksum = HsmmConfigFingerprint.of(this)
```

"Read" means *carries weight*: `TransitionFeatures.between` computes every feature on every move,
and a zero weight makes a feature inert in the score. The map from kinds to layers is the one
`between` implements, including that the hierarchy kinds and `Backward` reach the hierarchy
through `NodeSummary.parent` rather than the `Hierarchy` adjacency, and that `Backward`/`LongJump`
read measured positions. `weighted` and `withheld` partition `TransitionKind.features`, the twelve
kinds `between` computes. The two external logits are not features and have no inert value: a zero
logit is the prior p = 0.5, *stronger* than the shipped σ(−1.5) ≈ 0.18, so the ledger carries them
as the probabilities they set (`external=[pIn=…,pStay=…]`) and never as "withheld". Refinement
(`refinementPasses > 0`) reads layers through `view.reachable` and is outside this ledger, which is
a statement about the transition model only. A ledger exists exactly when a configuration does, and
cannot disagree with it.

### 2. `AblationResult` carries its configuration's ledger

`GraphHsmm.ablationUngated` returns `AblationResult(rows, viterbi, logLikelihood, layerUse)` with
`config.layerUse`, so a rung of the ladder states what it withheld. `HsmmResult` is unchanged.

### 3. θ enters provenance through a content address

`HsmmConfigFingerprint.of(config)` renders temperature, refinement passes and weight, and every
transition weight in declaration order as IEEE-754 bits, in the `ViewFingerprint` style. An absent
kind and an explicit zero render alike because `TransitionModel.apply` reads them alike, and
`HsmmConfig.of` normalizes negative zero in θ and in the refinement weight — `scaledConfig(0.0)`
produces `−0.0` for the negative ordering weights — so one model (equal scores, equal ledger,
equal `==`) has one address. `RecallToVideo.provenanceConfig` takes a `LadderRun` — the ladder
declaration, the effective prior scale and the configuration they yield, bound in one `final
class … private` whose only constructor derives the configuration — and renders
`priorScale=<used> … rung=<label> theta=<fingerprint> layers=[…] positions=… weighted=[…]
withheld=[…] external=[pIn=…,pStay=…]`, so the label beside the ledger cannot disagree with the
model that produced the numbers. The `1.0`-for-`1.5` misrendering is fixed by construction: the
string is built from the value the run used.

### 4. The ladder is a declared rung, refused rather than defaulted

`RecallOrderControl.Rung` names five rungs as cumulative sets of *admitted* feature kinds:
`content` (none), `+hierarchy` (`HierarchyUp`, `HierarchyDown`), `+order` (adds `Stay` and the
four ordering kinds), `+causality` (adds the three causal kinds), `+similarity` (adds
`SemanticNeighbor`, `SameEntityThread`). `Stay` is on the order rung and not in the scale
control's `orderingKinds`: dwelling is a sequential-persistence prior, so a rung without it has no
notion of sequence at all, whereas the scale control scales only the direction of time and leaves
occupancy alone. `RecallOrderControl.Ladder` is a rung plus any extra feature kinds withheld by
hand, parsed from `STORYMODEL4S_RUNG` and `STORYMODEL4S_WITHHOLD_KINDS`; an unknown name, or an
external logit named as withheld, refuses the run. `ladderConfig(scale, withheld)` is the scaled
shipped model with every withheld feature kind at zero and the external logits untouched.

Two of the gate's six rungs are not on this ladder and are named gaps rather than approximated.
**`+external` is not a rung.** The external logits are not features: a zero logit is the prior
p = 0.5, stronger than the shipped σ(−1.5) ≈ 0.18 for entering and σ(−0.85) ≈ 0.30 for staying, so
"withholding" them would give every lower rung *more* external mass than the shipped model, and
the step to `+external` would have been a 2.8× cut in the external entry prior dressed as added
structure. Every rung holds the external prior at its shipped value; a mode without external
states is a change to the state space this ladder does not make. **`+sensory` is not a rung**: the
aligner has no sensory feature, so the last rung admits the source-similarity kinds and sensory
stays a gap in `gates.md`.

## Consequences

- Every provenance checksum produced after this ADR differs from every one before it, for two
  reasons that are both correct: the prior scale is now rendered as used, and θ and the ledger are
  now in the string. No report, sidecar or view fingerprint changes.
- `AblationResult.apply`/`copy` gain a field; the only production constructor is
  `ablationUngated`, and the compile-time proof that an ablation cannot feed a signature is
  unaffected.
- The five rungs can now be run as arms of `tools/recall-study/run-arm.sh` by setting
  `STORYMODEL4S_RUNG` per arm; each arm's voyage document carries its rung, θ and ledger. Running
  the ladder on the development partition and recording it in the study log is the next step,
  not part of this landing.
- Refinement (`RelationPreservation.reweight`) reads layers through `view.reachable` and is
  outside the ledger's kind-to-layer map; it runs only when `refinementPasses > 0`, which the
  study never sets. Recorded here rather than closed.

## Rejected

- **Derive the ledger inside `HsmmResult.validated`.** `validated` has no configuration; giving it
  one touches sixteen call sites, the codec's decode path, the gate-proof laws, and the wire
  schema, to record a value that is a pure function of θ.
- **Return `(HsmmResult, LayerUse)` from `infer`.** A pair a caller can drop is the receipt case
  class the assessment argued against.
- **Withhold a layer by removing it from the view.** Changes the `ViewFingerprint` and the state
  space; a rung would no longer be the same alignment problem under a different prior.
- **Express the external rung as θ = −∞.** `HsmmConfig.of` refuses non-finite weights for good
  reason; a finite large negative would be a mode change disguised as a weight.
- **Express the external rung as θ = 0 and call it "withheld".** The first draft of this ADR did.
  A zero logit is p = 0.5, not absence; the lower rungs would have run with more external mass
  than the shipped model and the ledger would have rendered a strong prior as a withheld feature.
  Caught in cold review before any rung arm was run.
