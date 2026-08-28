# storymodel4s-features

Aligned feature tracks over the exact surface axis (design record §106–117).
Depends only on `core`. Portable: JVM, Scala.js, Native.

## What it owns

| Concept | Type | Notes |
|---|---|---|
| Declared space | `FeatureSpace[V]` | id, value schema, units, provider fingerprint, normalization + population |
| Targets | `FeatureTarget` | `Token`, `Window`, `Sentence`, `Boundary(afterUnit)`, `Turn`, `Situation`, `Segment`; `SupportResolver` maps them to exact `SpanSet`s (narrative targets via injected functions — no dependency on `story`) |
| Values | `Estimate[V]` = `Observed(value, credence?)` \| `Missing(reason)` | missing is never zero; `Coverage(eligible, observed)` travels with every aggregate |
| Tracks | `FeatureTrack[T, V]` | immutable, target-ordered, `derivation = None` for raw provider output |
| Windows | `WindowPlan` (core) + `Windowed` | declared `ScalarReducer`s (sum, mean, weighted mean, max, variance, slope, kernel); each window output carries support and coverage |
| Aggregates | `Aggregate.overTargets` | same reducers over a situation's or scene's possibly discontinuous `SpanSet` |
| Recipes | `FeatureDerivation`, `DerivationGraph` | content-addressed; `staleDescendants` after an input or model change; cycles rejected |
| Boundaries | `BoundarySignal`, `BoundaryEvidence`, `BoundaryBeliefInput` | evidence components stored separately; weights are parameters, never learned here |
| World time | `WorldTimeTransition` | `Continues / JumpForward / JumpBackward / ReturnFromEarlierFrame / SimultaneousThreadSwitch / Atemporal / Unresolved(alternatives)` |
| Leakage control | `FeatureUse`, `FeatureUseLedger`, `CircularityCheck` | flags analyses that test a feature (or a derivative of it) that helped induce the structure they condition on |
| Vector sidecars | `SidecarManifest`, `FeatureRef` | dense vectors live outside canonical JSON; refs validated against row counts |

`core` gained the minimal surface primitives this needs: `TokenClass`, `TokenView`,
`TokenIndex`, `TokenRange`, `SurfaceSequence` (lexical view, previous/next, `covering`,
`windows`, `contextAround`), `WindowPlan`/`SurfaceWindow`, and the transcript overlay
(`TranscriptTurn`, `TranscriptAtlas`, `InterviewPhase`, `AudioSpan`).

## Laws enforced by tests

- reducers over all-missing samples yield `Missing`, never `0`;
- `mean ≤ max`; `sum = mean × observed`; sum scales with count, mean does not;
- slope is antisymmetric under reversal; a zero-bandwidth kernel is the identity;
- windows tile per plan, supports stay inside the text, lexical counts exclude punctuation;
- derivation ids change when any recipe field changes; the derivation graph is acyclic;
- circularity is detected through derived spaces and cleared by explicit exclusion.

## What other modules may assume

- `story` builds `FlowStep.featureChanges: Map[FeatureSpaceId, ScoreEstimate]` and
  `worldTime: WorldTimeTransition`; hierarchy builders consume `BoundaryEvidence` and must
  record a `FeatureUse(Induction(stage), …)`.
- `align` uses `ScoreEstimate`/`Coverage` for support densities and importance weights.
- `recall`/`interview` traverse `TranscriptAtlas` for participant-only and post-probe material.
- Providers (M1) emit **raw** tracks only; all smoothing/aggregation happens here with a recipe.

## Invariants added in the review fix pass

- Doubles inside recipe ids and config hashes are rendered by IEEE-754 bit pattern
  (`CanonicalDouble.render`), so `derivationId` is identical on JVM, Scala.js and Native.
- `FeatureDerivation` includes `eligibility` and `targetFamily`; `outputSpaceId` is a fixed-length
  content address (`derived:<32 hex>`), never a growing path.
- Kernel reducers never substitute a sample outside the kernel support; only a declared
  zero-bandwidth point mass may fall back to the nearest sample. All-missing supports yield
  `Missing(AllMissing)`; undefined operations yield `Missing(Undefined(reason))`.
- `Estimate.score` and `FeatureTrack.validatedScores` keep NaN/±∞ out of observations;
  `Coverage.of` validates counts; negative sample weights fail `Reduction.reduce`.
- `BoundaryEvidence.inputSpaces` is per signal, and a `BoundaryScore` can only be obtained via
  `FeatureUseLedger.scoreBoundary`, which records the feature use it depends on (§116).

