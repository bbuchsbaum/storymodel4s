# Toward 1.0: handoff, 2026-09-17

Written against `f1c17e76`. Every file:line below was read at that commit by this audit; nothing
here is quoted from a bead without a source check. Where a bead's own text is now false, this
document says so and the bead should be corrected before anyone acts on it.

## 1. What is actually true about the tree

Measured, not reported:

- `sbt -Dstorymodel4s.grakern.build=<path> checkAll` is **green**: 18m01s, **5,971 tests** across
  JVM, Scala.js and Native, 0 failures, 5 environment-gated skips, scalafmt clean, 0 errors.
- 22 modules (15 portable × 3 platforms + 7 JVM-only), 75,205 src LOC / 57,863 test LOC.
- 7 `[E198] Unused Symbol` warnings, in `document/kinds.scala`, `document/mentionform.scala`,
  `document/coreference.scala`, `document/propose.scala:990`, `pipeline/Features.scala`.
- 0 `@nowarn`, 0 `@SuppressWarnings`, 1 `-Wconf` silencer, 6 TODO markers (all in one WOG suite).

The code is in good shape. **The weaknesses are in the verification perimeter and in a single
recurring defect class, not in the algorithms.**

## 2. The organising insight — read this before picking up any item

The independent findings below are not thirteen unrelated chores. Six of them are one defect,
at six sites, and it is the defect this project's own design contract is named after
(`AGENTS.md:344-469`, rule 7): **a published value that cannot be told apart from a different
value it must be told apart from.**

| Site | The two things it conflates |
|---|---|
| `CostBreakdown.supportWeight` (`align/cost.scala:847`) | "fully measured" vs "nothing was eligible" — both publish `1.0` |
| `MissingValuePolicy.RequireMinCoverage` (`features/window.scala:399`) | "coverage cleared the bar" vs "the bar was NaN so nothing was excluded" |
| `AlignError.SizeMismatch` (`align/population.scala:331,:357`) | "your vectors differ in length" vs "you gave me nobody" |
| `RecallSignature` bare `Double` fields | "measured 0.0" vs "measured nothing and defaulted" |
| `MassRatio.unsafe` (`align/signature.scala:222`) | a real ratio vs `Some(NaN)` |
| `PlacementResolution` (`interview/scoring/Resolution.scala:72`) | counted resolved mass vs resolved-but-outside-every-counted-class |

Mission commitment 6 forbids exactly this: *"an unresolved build must return alternatives or
`Unresolved`, never manufacture precision merely to finish."* Every one of these manufactures
precision to finish. Fixing them is not release hygiene — it is the mission's central claim
becoming true in the code.

The seventh theme is the perimeter: there is no CI, so **every** assertion that the above holds is
self-reported (`AGENTS.md:1061-1064` says so in those words).

## 3. Ordered work

Do Tier 1 first. It is five small changes, roughly a day together, and four of the five become
*breaking* changes the moment an artifact is published.

### Tier 1 — small, high value, breaking or misleading if deferred

**T1. Revert the `hsmm/v4` documentation to v3.** `bd-01M1D214VVRE118RTEQQ09P7SA`

`codec/README.md:29` and `docs/adr/0001-embedding-contract-and-hard-gate.md:288` document schema
`hsmm/v4` with a required tagged `support` assessment and a result-level `supportBasis`, describe it
as "the live version", and cite a committed `hsmm/v4` golden. The code emits `"hsmm/v3"`
(`codec/align.scala:59`); `SupportAssessment` and `SupportBasis` exist in no main source; the only
golden is `fixtures/src/test/resources/golden/hsmm-v3-wog.json`. Cause is recorded in-tree at
`docs/design/salvage-2026-09-01-manifest.md:91-92` — the 2026-09-01 salvage kept the v4 docs while
the v4 source never landed.

A consumer reading the README builds a decoder for a schema this library does not emit, and
`HsmmResultCodec.SchemaVersion` is cited by name as evidence for a value it does not hold. Revert
the docs now; land v4 later under T10. **Effort S. Highest value-per-hour on this list.**

**T2. Guard `RequireMinCoverage` at the wire boundary.** `bd-01M19NYZT593MXWEERP80RHY8J`

`codec/features.scala:334` decodes the threshold straight off untrusted JSON with no validation:
`field[Double](c, "fraction").map(MissingValuePolicy.RequireMinCoverage.apply)`. At
`features/window.scala:399` the gate is `case MissingValuePolicy.RequireMinCoverage(f) if cov.fraction < f`,
falling through at `:401` to the reduce. `cov.fraction < NaN` is false and `cov.fraction < -1.0` is
false, so a NaN or negative threshold makes the coverage gate never exclude anything — **including
`cov = 0.0`, where a value derived from zero observed samples publishes as measured.**

This is the only *reachable-from-untrusted-input* soundness hole the audit found. Add a checked
constructor (finite, in `[0,1]`) and refuse at the decoder. Four sibling enum cases were flagged as
unread and should be checked in the same pass: `FeatureValueSchema.Vector(dimension)`,
`TruncationPolicy.KeepHead(maxTokens)`, `LeakageVerdict.Clear(threshold)`, `ThreadPolicy.All(max)`.
**Effort S.**

**T3. Add `AlignError.EmptyPopulation`.** `bd-01M16B4NE7R5HHXT70GFW629ZT`

`align/population.scala:331` and `:357` both return
`Left(AlignError.SizeMismatch("population has no subjects"))`. `AlignError`
(`align/matrix.scala:325-365`) has no `EmptyPopulation` case. `AlignError` is a published sum type;
adding a case after 1.0 breaks every exhaustive match downstream, and costs nothing now.

Keep the refusal itself — it was ratified. Update `align/src/test/.../PopulationSuite.scala:118-122`
and keep an `assertNotEquals` against the old `SizeMismatch` string so a revert goes red. Extend the
align/codec/laws/embed-bench reference scope. **Effort S.**

**T4. Land the nine probe-anchor cherry-picks.** `bd-01M1836WDBB72KXXPKG23PSSWT` and its children

Only **3 of 27** construction-boundary suites carry a compiler-visible reference to the type they
guard; the other 24 name their subjects only inside `typeCheckErrors("...")` string literals. Zinc
invalidates by used name, so those suites do not recompile when their subject changes. The measured
consequence is recorded at `acquire/src/test/scala/storymodel4s/acquireprobe/ConstructionBoundarySuite.scala:9-18`:
un-sealing `CandidateLedger` and re-running without `Test/clean` left the old macro result baked in
and **the court passed.**

Nine approved, independently reviewed, test-only candidates exist and `git apply --check` clean
against HEAD (~104 insertions total). They were blocked only by an `ancestor_blocked` reducer ghost
whose base `e584ad6d` is now an ancestor of HEAD, so the blocker has expired:

```
git cherry-pick 0573692458 92c8782193 69d1d20a88 e3745e2024 b1a3287e0d \
                f47177cbde c0525f0ffb e5283b5160 f352b43dd8
```

Then run the module gates. Four suites also still test only the companion `fromProduct` door and not
the `summon[Mirror.ProductOf[T]]` door: `acquireprobe/ConstructionProbeSuite`,
`align/consumer/PopulationBoundarySuite`, `benchprobe/ConstructionProbeSuite`,
`recall/probes/RecallGraphUnforgeableSuite`.

Scope note: this is false-green *during development*, not a shipped-correctness defect — release
gates run in fresh clones. It is still the cheapest way to stop 24 courts reporting green through a
regression. **Effort S.**

**T5. Flip the `MassRatio.unsafe` polarity.** `bd-01M174W3D9AZG2FGSRM11FZ7ZQ`

`align/signature.scala:222` is `val v = if conditioning <= 0.0 then None else Some(numerator / conditioning)`.
NaN fails `<= 0.0`, takes the else branch, stores `Some(NaN)`. Same fail-open shape at `:192`
(`MassRatio.support`). `tools/nan-polarity.sh` still lists both as REPORTED.

**Correct scope, and do not overstate it in the commit message:** all 8 call sites are unreachable
for NaN *through the validated door* — `align/hsmm.scala:427` rejects any flow step with NaN mass
inside `HsmmResult.validated`. Reachability is by forgery only: `FlowStep` is a bare public case
class (`align/matrix.scala:295`) and `HsmmResult`'s constructor is `private[align]`, so a file
declaring `package storymodel4s.align.attack` can mint a NaN-flow result and feed
`RecallSignature.compute`. Fix it because `MassRatio`'s entire contract is "refuses unsupported
numbers", not because a live number is wrong.

`if conditioning > 0.0 then Some(...) else None` is semantics-preserving by trichotomy for every
non-NaN input. **Effort S.**

### Tier 2 — the verification and release perimeter

**T6. Stand up CI.** `bd-01M19G69RQCHMT2EMG11XFT4WX`

There is no `.github/` in HEAD, on any of 1367 refs, or on the remote —
`gh api repos/bbuchsbaum/storymodel4s/actions/workflows` returns `{"total_count":0}`. Not one
workflow has ever run. Yet `build.sbt:26-29` sets
`githubWorkflowJavaVersions := Seq(temurin("17"), temurin("21"))` and sbt-typelevel 0.8.7 ships the
generator. Someone specified a two-JDK matrix and `githubWorkflowGenerate` was never run or never
committed; `.gitignore` does not ignore `.github`.

Two consequences to handle in the same change:

- `tlFatalWarnings` is `false` on all 53 projects because it is gated on `githubIsWorkflowBuild`,
  which is false without a workflow. `AGENTS.md:279` says "Warnings are errors in spirit" — the
  intended enforcement mechanism **cannot fire**. Turning CI on today fails the build on the 7
  E198 warnings. Fix those first or the first CI run is red.
- The only JDK ever exercised is the developer's (25.0.1 via the Homebrew sbt shim). Neither
  declared CI JDK has been tested.

CI must resolve the grakern/gale/graph4s source pins before the build starts; that is the real
scope blocker, and it is why this is M and not S.

**Proof that CI is needed, not optional:** `docs-site`'s own gate has been failing for ~2 weeks and
nobody noticed. `npm run verify` → `verify:examples` byte-compares 13 documented examples against
recorded transcripts; `model-a-story` drifts at byte 186 — recorded `auditable claims: 462`, actual
`475`. The likely cause is `c3ba84bd` (2026-09-04), which changed `WarOfTheGhostsModel.scala` and
touched zero docs-site files. Published documentation has understated the fixture by 13 for two
weeks. Fix the transcript and put the docs gate in CI. **Effort M.**

**T7. Make the build publishable.** (unfiled — open a bead)

A publish attempt would be rejected today:

- `scmInfo` and `homepage` are `None` on all 53 projects. The remote is
  `git@github-bbuchsbaum:bbuchsbaum/storymodel4s.git`, a custom SSH host alias that sbt-typelevel
  cannot parse as a github.com URL, so it derives nothing. Sonatype requires both.
- `mimaPreviousArtifacts` and `tlMimaPreviousVersions` are both empty. The only tag is
  `archive/calibration-3979f508`, not a version tag, so there is no binary-compatibility baseline.
  MiMa is wired up and inert.
- `organization` is `io.github.canardlapin` with developer `canardlapin`, while the remote is
  `bbuchsbaum/storymodel4s`. For an `io.github.*` coordinate, Sonatype namespace verification is
  tied to the GitHub account. Resolve before the first release, not after.

**Effort S–M.**

**T8. A manifest-driven data-root verifier.** (unfiled — open a bead)

The data design is sound and should not change: `/data/*` is gitignored except `README.md`
(`.gitignore:21-22`), identities are committed under `docs/data/<corpus>/source-manifest.json`,
`tools/data-root.sh` resolves one copy across every worktree through the git common dir, and
`data/README.md` states the read-only / content-free rules. All four manifests (sherlock,
filmfestival, memento, friends) carry sha256; three are `admissionStatus.state = proposed`.

What is missing is verification. `tools/data-root.sh --check` tests **presence only**, of six
hardcoded paths, and does not mention filmfestival, memento or friends at all. Nothing compares
on-disk bytes to the committed digests. Write a verifier that walks every
`docs/data/*/source-manifest.json`, checks each declared file exists and its sha256 matches, and
reports drift. That is what makes 1.4 GB of deliberately uncommitted data trustworthy without git.
**Effort S–M.**

### Tier 3 — published-API soundness; all cheaper before 1.0 than after

**T9. Close the `Mirror` door on `CostBreakdown` and `Admissibility`.** `bd-01M17ZNXY6AS1CMBQJRH3JMNVX`

Verified at bytecode, not inferred: `javap -p` on `align/.jvm/target/scala-3.7.4/classes/` shows
`CostBreakdown$` and `Admissibility$` both `implements scala.deriving.Mirror$Product` with a public
`fromProduct`. The bead says six types; **four are already repaired** — `StructuralMemberEstimate`,
`StructuralMemberExclusion`, `StructuralReductionReceipt`, `StructuralReduction` are now
`final class ... private[align]` with no companion class file at all.

Severity splits, and the bead does not make the split:

- `CostBreakdown` (`align/cost.scala:293`) is **accepted as a parameter** by `HsmmResult.validated`
  (`hsmm.scala:377`), so a forged one flows into a published result. Its own Scaladoc at `:317-319`
  admits the hole and cites this bead.
- `Admissibility` (`cost.scala:667`) is re-derived inside `validated` rather than accepted, so its
  forgeability is materially mitigated.

The fix pattern is already in this repo: `SignatureProjection` (`align/signature.scala:723`) is a
`final class` with a hand-written `equals`/`hashCode` **specifically** for this reason — its comment
at `:725` explains it. Convert both, add both to `align/src/test/scala/storymodel4s/consumer/AlignBoundarySuite.scala`
(which today covers `AlignmentRow`, `AlignmentMatrix`, `HsmmConfig`, `CostWeights`,
`WeightedCoverage`, `ImportanceWeight`, `NodeSummary` and neither of these), and rewrite the
`.copy(...)` sites in tests, e.g. `align/src/test/.../WireSuite.scala:74`.

**Trap:** `align/src/test/scala/storymodel4s/probes/CellCoordinatesUnforgeableSuite.scala:26` uses
that same summon as a *positive control*, pinning the door open. It must be re-based on a different
same-shape control or it will fail when you close the door.

**Second trap:** a 2026-08-31 disposition assumed `CostBreakdown` was absorbed into
`bd-01M19956MFSG7076QE4J66T7E9`. It was not. Do not close this bead on that basis. **Effort M.**

**T10. `supportWeight` must not publish "fully supported" for an empty eligible set.**
`bd-01M19956MFSG7076QE4J66T7E9`

`align/cost.scala:847` is `if eligible.isEmpty then 1.0`, and `:850` is
`if !(wEligible > 0.0) then 1.0`. Both publish the numeric claim "nothing was assumed" for a cell
that measured nothing. `CostBreakdown.supportWeight` is still a bare `Double = 1.0` (`cost.scala:309`).

This is the v4 schema bump: a tagged `SupportAssessment` (`Assessed` / `Unestablished` /
`NotApplicable`), an explicit `SupportBasis`, `hsmm/v3` → `hsmm/v4`, a regenerated golden, a public
shape change to `CostBreakdown`, and a documented estimand movement (the WOG numbers move).

An implementation exists as local branch `scout/support-assessment-v4` (29 paths) but was never
authorised for landing and is ~2.5 weeks behind a main that has since gained `media`, `pipeline`,
`provider-agent` and ADRs 0008–0017. Assess rebase-vs-redo before committing to either. Doing this
also discharges T1 properly and unblocks `bd-01M1DA6NJXYT4NEA18745FM3KY`. **Effort L.**

**T11. Finish the `RecallSignature` carrier migration.** `bd-01M16E05TWX28QKJF2GFY67ZZV`

The bead's title says 14; **roughly 10 remain.** Migrated since it was written:
`importanceWeightedCoverage: WeightedCoverage` plus `specificityMass`, `discourseChronology`,
`worldChronology`, `causalPreservation`, `semanticFlowCoherence` as `MassRatio`.

Still bare (`align/signature.scala:24-47`): `uniformCoverage` (`:25`); the seven mass terms
`associationMass`, `intrusionMass`, `commentaryMass`, `sourceConsistentInferenceMass`,
`uninterpretableMass`, `unrankedMass`, `distortedMass` (`:35-41`); `distortedMassByFacet` (`:42`);
`perUnitLocalizability` (`:45`).

The underlying defects are live: `signature.scala:408` `val uniform = if leaves.isEmpty then 0.0`,
and `:606-609` where `extMean`/`distorted`/`byFacet` each do `if p.rows.isEmpty then 0.0` over
`val n = math.max(1, p.rows.size)`, with `.filter(_._2 > 0.0)` at `:610` collapsing no-rows into a
true zero.

**Trap:** the subnormalized-rows issue on the external terms is not the same shape as the
specificity fix. Copying that fix produces a plausible wrong number. Also note
`docs/design/adr-0003-migration-status.md` has internal drift — line 81 says specificity is "next
and ruled but not yet built" against line 36's "migrated". Re-derive it, do not trust it.
**Effort L.**

**T12. Carry a privacy witness through `RecallGraph`.** `bd-01M168TZ3VRQYSE7TNT5JKJTSJ`

`recall/src/main/scala/storymodel4s/recall/graph.scala:26-31` is
`final class RecallGraph[S] private (transcript: StorySource, atlas: SurfaceAtlas, units: ..., relations: ...)`
— no witness. `segmenter.scala:400` is `def segment(transcript: StorySource): RecallGraph[Checked]`.
Grep for `privacy|certif|PolicyId|KeyId` across `recall/src/main` returns nothing.
`PseudonymizedTranscript` is `private[interview]` (`interview/privacy.scala:26`).

**The bead's own blocking condition is already tripped.** It says it must close "before any artifact
renders or exports recall text"; `codec/recall.scala:258` already writes
`"transcript" -> g.transcript.asJson` and `:140` writes `"text" -> u.text.asJson`.
`codec/README.md:31` concedes it: "transcript as plain `StorySource` until the `PseudonymizedText`
split lands."

Mission commitment 14 and the README's marketed guarantee both depend on this. The bead's caution
stands: not a boolean, not a metadata string — a certified input that travels with the graph, or a
codec that refuses without one. **Effort M.**

**T13. A non-scalar declared projection — the embedding / trajectory seam.** (unfiled — open a bead)

This is a vision bullet, not a nice-to-have. `vision.md:46-47` asks: *"How do conclusions change
when familiar totals, population analyses, or **neural regressors** carry the underlying alignment
uncertainty forward?"* Nothing in the repo serves that today, and the owner has confirmed the
requirement: refusing a *canonical* embedding is right, but it must be possible to **extract** one
on demand under a named, versioned policy.

Most of the machinery already exists and is unwired:

- **The serialization half is built and tested.** `SidecarCodec.encodeVectorTrack`
  (`codec/sidecar.scala:665`), `encodeBlockedVectorTrack` (`:682`), `materializeVectorTrack`
  (`:703`), formats `SM4SFT01`/`SM4SFT02` with manifest-rooted block digests. `SidecarValue` has a
  closed `Vector[Double]` instance. **It has no producer in main** — every caller is a test.
- **A per-position trajectory already exists.** `align/density.scala:12`
  `Density(unit, grid, values)` computes `a_i(t) = Σ_v P_iv h_v(t)` — the row posterior smoothed
  over each anchor's support interval — so the uncertainty is in the *shape*, not collapsed.
  `Density.toTrack` (`:32`) lowers it with a named versioned `FeatureDerivation`
  (`support-density-2`, Gaussian kernel bw 0.03) and a `Coverage`. The chain
  `HsmmResult → SupportDensity.discourse → Density.toTrack → FeatureMaterializer.materialize → SM4SFT02`
  **type-checks end to end today and nothing in main calls it.**
- **`P` and `F` are already the probabilistic mapping.** `AlignmentMatrix` (`align/matrix.scala:244`)
  is `Vector[AlignmentRow]`, each `(RecallUnitId, Map[AlignState, Double])`; `TransitionFlow`
  (`:321`) is a joint over consecutive steps whose marginals are provably `P` within tolerance.

What is genuinely missing, in dependency order:

1. **A declared column universe and canonical index for `P`.** Nothing fixes "these are the columns,
   in this order, for this view"; every consumer re-sorts by `AlignState.key` locally. Module
   `align`. S–M.
2. **A projection abstraction that is not `Double`-shaped.** `SignatureProjection.apply`
   (`signature.scala:737`) returns `SupportedScalar` and its component table is 18 literals against
   `RecallSignature`'s fields (`:738-757`) — it cannot be widened. Needed: a parameterised declared
   projection whose version is **derived from the code** like `RecallSignature.estimandVersion`
   (`:68`) rather than caller-asserted, keeping the two refusals the scalar one earned
   (`UnknownComponent`, `MissingComponent` — never a silent `0.0`). M–L.
3. **A `FeatureTarget` case for a recall unit.** The enum (`features/space.scala:34`) has
   Situation/Segment/Turn/Window — a recall-side track has nowhere to attach. Plus a vector-capable
   `FeatureMaterializer` (`codec/materialize.scala:42` is `Double`-only) and view seam
   (`view/feature.scala:33`). M.
4. **Codecs for `RecallSignature` and `PopulationAggregate`.** Neither appears anywhere in
   `codec/src/main`. Without them nothing downstream of the aligner can be serialized or
   round-tripped. M.

**Sequencing constraint: do T11 first.** The signature migration changes the exact component table a
vector projection reads. Building the extraction before it is building on a moving target — that
coupling already broke `embed-bench/report.scala:215` once.

**Trap:** `SparseMatrix` (`align/population.scala:79`) is an existing export shape, constructed once
at `:285` under an `// ---- export ----` banner and consumed nowhere. It is a plain case class whose
`fromProduct` admits mismatched `rowIds`/`colIds` and out-of-range `entries` keys — harden it or
replace it before making it the carrier.

The precedent worth copying is `AiScoringPolicy` (`interview/scoring/Scoring.scala:42`): the only
place in the repo where a declared, versioned, policy-as-data projection emits a multi-slot numeric
output that carries its uncertainty forward (`ExpectedCount` with a point, an `Interval`, and a
`calibrationModel` distinguishing calibrated from raw).

## 4. Tracker hygiene — do this before planning anything

The candidate ledger and the tree diverged badly around 2026-09-01. **Twelve beads are stale rows
for landed work.** Closing them unblocks three dependents and removes most of the apparent p0 load:

`bd-01M17H99VC3WG42Q6MJ67QH1NV` (Sinkhorn validation — landed across `178b20c4`, `11a66857`,
`28ef67e2` with courts), `bd-01M1DB1ZNFMNRJ7J40F4HXMZF9` (`scaleToEligible` overflow — repair and
four courts present, incl. the `Double.MaxValue` controls), `bd-01M1CH1SW9K3ZW8D0HBTH9JVRP` (B0),
`bd-01M1CQ5YS5M25F7HD9NN5YVAYP` (B0R — all six records verified, counts machine-checked),
`bd-01M1CQEWA4GWY5NW0QAGWYP31H` (C1 — all seven paths present),
`bd-01M1CHWCRKMPNK6RARGT7JXVXX` (S1–S4 output takeover — all 11 paths, both HOLDs repaired),
`bd-01M1DDY44PMSEB6FN227S56G45` (premerge), `bd-01M1EAE4X7GPERQHJZYQJQXH11` (PRD),
`bd-01M18844CA1BFTNXR0HS8WBWB6` (movie plan now tracked), `bd-01M19947QTCAYJJYTC003HVDB5`
(calibration court — exact hash match), `bd-01M1C8WSZ49M7SHG2HMQCWRK6E` (viz court — exact hash
match), `bd-01M17EY6Z1TB87MN742K3808MF` (tolerance constants — all six now declare their precision).

Also: `bd-01M19XM9W7GJ3HZSRPW8E0VVF8` (S1), `bd-01M19XMDFSDQFFN77K9GNZA73K` (S2) and
`bd-01M19XMGH1TFSQTRDBXQMB3JKX` (S3) are DONE with courts; close them.

**One bead is actively harmful.** `bd-01M1CQCSZSTHJQ5PA4PQ4GWKVB` (P1A, "real AMR transport") blocks
two dependents on a local amrlib-subprocess premise that **ADR 0008 superseded on 2026-09-01** — the
runtime is now a hosted model behind `ClaudeParserTransport`, and ADR 0008 states plainly that a
hosted model's weights cannot be pinned. Close as superseded and re-point
`bd-01M1CQSWJ538PAEME49200YARR` and `bd-01M1DB6J4VFETMCFG096713DCY`.

**Two beads carry false numbers** and must be rewritten before anyone acts on them:
`bd-01M183K7YXA46F66NMTEF8T3ZR` says storyatlas4s pins 148 commits behind and main is unpushed —
actually **50** behind (`storyatlas4s/build.sbt:36` pins `3a6d73d8`) and `origin/main == HEAD`;
`bd-01M175Y5FEZJ661PPBSGJFT8RX` says rule 4 is 322 lines / 42% — it is **548 lines / 37%**, and
AGENTS.md has grown from 765 to 1491 lines.

## 5. Traps that will cost you a session

- **`sbt checkAll` ran scalafmt FIRST until 2026-09-21**, against the *Run the format check LAST*
  rule in `AGENTS.md`: sbt's `;` aborts on first failure, so a whitespace nit destroyed the
  correctness signal. Fixed: the alias (`build.sbt`, `addCommandAlias("checkAll", …)`) now runs
  `compileAll;testAll` before `scalafmtCheckAll;scalafmtSbtCheck`. The chain still stops at the
  first failure, so a failing test hides any formatting finding until the tests pass, and within
  `testAll` the first failing module hides the modules after it.
- **sbt does not build in a linked git worktree** (jgit `NoWorkTreeException`, `AGENTS.md:281-291`).
  The `zz-worktree-local.sbt` shim works for some tasks and is not reliable for all. Gate in a clone.
- **`embed-grakern` needs `-Dstorymodel4s.grakern.build=<path>`** (or `STORYMODEL4S_GRAKERN_BUILD`)
  or the build tries to resolve grakern over the network from a repo with no remote. It is part of
  `compileAll`/`testAll`/`testJVM`.
- **A compile-time probe needs a clean recompile.** Any mutation changing a type's shape — `case` to
  non-`case`, constructor visibility — must be proved after `Test/clean`, or Zinc may reuse stale
  test bytecode and the court passes against the old shape (`AGENTS.md:1379-1390`). This bites T4
  and T9 directly.
- **A negative compile assertion needs a same-shape positive control.** `typeChecks` returns false
  for *any* error. A control must be a live private-constructor case class, not a tuple
  (`AGENTS.md:1177-1192`).
- **17 GB sits in `.worktrees/` across 10 directories; only 4 are registered with `git worktree list`.**
  Six are orphaned. Disk was at 91%. Reclaim before a gate export.
- **`AdmittedViewBasis.fromAcquisition` returns `Left` on every path** (`view/output.scala:1009-1018`),
  so the output-authority surface ships types no caller can construct. This is **deliberate** — the
  chief required honest deferral over forged issuance — but it will look like a bug. Do not "fix" it.
- **`README.md:80` says seventeen modules; there are 22.** `AGENTS.md:247-267`'s layout table is
  also wrong in the other direction (omits `view`, `embed-onnx`, `embed-bench`). Gate scope is
  derived per-module, so fix both inventories.

## 6. The acceptance bar in this repo

`AGENTS.md` rule 4 is 548 lines. The operative summary for anything landed here: **a runnable
falsifier, mutated by name, with a shape-matched positive control, asserted on a fixture that can
tell the hypotheses apart, run in a clean clone with captured exit status and bound test totals,
reported at the chain link actually reached, and labelled `LocallyObserved` unless someone else
reproduced it.**

Specifically, for each item above:

- Delete the guard you added; show a test go red; restore it. "The guard exists" and "the guard is
  load-bearing" are different claims (`AGENTS.md:936-938`).
- Assert equality against an independently recomputed expected value, not `!=`/`<=`/bounds — those
  are satisfied by both the correct implementation and the mutant (`AGENTS.md:1009-1019`).
- Capture the exit status into the log (`... > log 2>&1; echo "GATE_EXIT=$?" >> log`); a trailing
  success line is not an exit status (`:1164-1175`). Never pipe a gate (`:1122`).
- Grep the log for `Passed: Total` before reporting anything. No totals means it did not run
  (`:1270-1280`).
- Say which chain link you reached: *permits* (read it) / *occurs* (measure) / *produces* (run it) /
  *consumes* (trace forward). Reporting one link as the whole chain is the most common failure here
  (`:895-916`). T5 above is a worked example — *permits*, not *occurs*.

Single-developer mode is live (`AGENTS.md:111`): `sbt checkAll` green on the merge result is the
authority to land, the author lands their own work, and **SD6 — never approve in the same breath as
authoring.** Review cold, in a separate pass, with a fresh-context agent.

## 7. What to leave alone

Defensible work, none of it a library 1.0:

- **The whole Sherlock D0 → D1A → D1B → V1 → E0 chain** (L/L/XL/XL/XL). Unstarted beyond the `core/`
  substrate and a partial annotation adapter. It dominates the open p0 rows and is a research
  demonstration. **However:** D1A and D1B rewrite `StoryModel` and `SourceView`, the two most central
  published types. If a heterogeneous-source 1.x is intended, do not freeze those signatures at 1.0
  without a stated deprecation path — or say plainly that 1.0 is text-only.
- **OSF 5qxkh, NFRD Baseball part 2, the Ost/gptkz corpus.** All blocked on external data or owner
  decisions (`bd-01M184XP908JX51S3ZYFX20HC7` holds four dispositions only the owner can make).
- **The S4 HTML renderer.** No HTML emitter exists in any `src/main`; the sibling `storyatlas4s`
  has one but nothing binds it to the bundle contract. The typed contract is the published surface
  and it is already sound.
- **The progressive facade** (`bd-01M1DD1KVSVNK35C4Y9XPMSEHX`). Measured: 13 public workflows, ~713
  non-blank lines, median 45, heaviest `UseEmbeddings.scala` at 114. It is what people will judge
  the release on — but it is **additive and non-breaking**, so it can genuinely follow 1.0, and
  everything in Tier 3 cannot.

## 8. Recommended first session

1. Close the twelve stale beads and re-point P1A's dependents (§4). Rewrite the two false-number
   beads. This alone changes what the board says the project's priorities are.
2. T1 — revert the `hsmm/v4` docs. One commit, removes a false public claim.
3. T4 — the nine cherry-picks, with `Test/clean` before the gate.
4. T2, T3, T5 — three small guards, each with its mutation kill.
5. Run `checkAll` on the merge result, capture `GATE_EXIT`, and label the evidence `LocallyObserved`.

That is a day's work that removes one reachable soundness hole, one false public statement, one
breaking-if-deferred enum change, and the staleness that lets 24 courts report green through a
regression — and it leaves the tracker describing the project that actually exists.

Then T6 (CI), because until it exists every sentence in §1 of this document is self-reported,
including this one.
