# Story → representation → visualization: solo-mode assessment and plan

- **Date:** 2026-09-01
- **Mode:** single-developer (AGENTS.md "Single-developer mode"): owner plus one agent, land on
  `sbt checkAll` green, mutation proofs stand in for reviewers, the board is a notebook.
- **Scope:** the pipeline *story text → StoryModel → view marks → rendered instrument*. The
  Sherlock recall-to-video line (the four most recent landings) is out of scope except where
  §5 phase 4 reconnects it.
- **Reading of "mark":** the ADR 0002 `VisualPrimitive` marks (`Region`, `Landmark`, `Thread`,
  `Portal`, `Route`, `SurfaceUnit`) that `AtlasCompiler`/`CodexCompiler` emit from a
  `StoryModel[Validated]`. "Internal representation" covers both the semantic model (`story`,
  `document`, `proposition`) and those marks.
- **Evidence:** four read-only investigations (front half, view layer, storyatlas4s, roadmap and
  board) plus a clean-export JVM gate run at 638e7a26 with grakern pinned at 0329c43.

## 0. Verdict

The pipeline exists end to end for exactly one input, the hand-authored War of the Ghosts
fixture, and the two ends are where it is weakest. Nothing in the repository turns English into
semantic content: `acquire` is pure algebra, `provider-parser` is a transport contract with no
implementation, and there is no HTTP client, LLM SDK, or subprocess call in any main source. At
the other end, storyatlas4s has a sound contract layer (identity protocol, receipts, paginator
laws, Playwright checks) and a picture that was never designed: the atlas is a grayscale ribbon
with overlapping labels, and the shell is black-on-white default fonts. The middle is the
strongest stage: `view` is a solid, fully tested Discourse Atlas and Codex compiler, but it emits
only that projection, carries no feature values or uncertainty on marks, and consumes nothing
from `recall`/`align`.

The plan below is *thin end-to-end first, then widen*: make the front half real for one story,
render gold and silver War of the Ghosts side by side as the milestone artifact, then make the
picture match the v2 mockup, then let marks carry more of the model.

## 1. State by stage

| Stage | What exists | Tested | What is missing |
|---|---|---|---|
| Text → surface | `StorySource.fromText`, `SurfaceAnalyzer.analyze` → `SurfaceAtlas` (core/atlas.scala) | yes | nothing |
| Text → charts | `AmrCandidates.fromPenman` → `PropositionChart[Checked]` (amr-interop); `ParserTransport` contract (provider-parser/contract.scala:741) with only a test fake | AMR side yes | **any producer of PENMAN or charts from English** |
| Charts + proposals → StoryModel | ADR 0005 `NarrativeCompiler` (document/compiler.scala:732); vertical court drives one sentence with one hand lemma rule (fixtures NarrativeCompilerVerticalSuite:31-48) | 22 + 1 | proposal provider; participant and temporal edges; non-empty trajectory; multi-situation compile refuses by design (CompilerSuite:771) |
| StoryModel from real text | hand-authored WOG (fixtures, 1,526 lines) | yes | any machine-built validated model |
| StoryModel → marks | `AtlasCompiler`, `CodexCompiler`, `EvidenceVisibility`, V-L2 placements, scale selector, textual twins (view/, 3,924 lines main) | 71 view + 39 WOG | feature values on marks; D9 uncertainty marks; context bands; discontinuous portals; Loom / Story-World / Recall Voyage; view artifact codecs; BasisId path fails closed although `features.BasisId` landed |
| Marks → SVG/app | storyatlas4s: `AtlasLowering` (242 lines), `Paginator`, `edition --out`, Laminar shell, Playwright | 6k Scala + 1.3k JS | the visual design; pan/zoom/minimap; readable labels; the v2 mockup is untracked on main and lives on `repair/pixel-dpr2` |
| Recall on the picture | `AlignRef`/`RecallRef` parse in view; frozen court docs/design/recall-alignment-visualization-court.md | — | any lowering of `HsmmResult`; Sherlock emits a TSV, not a view artifact |

Pins: storyatlas4s pins storymodel4s 3cf704a and intaglio 596b398. The consumed `view` files are
byte-identical since the pin (only `view/output.scala` is new); intaglio's three used call shapes
survive on its main. Bumping both is expected to be compile-clean and gains PatternPaint and
typed strokes.

## 2. Hygiene findings (verified this session)

1. **main is red.** In a clean export at 638e7a26, `documentJVM/Test/compile` fails: the
   2026-09-01 salvage (12f15173) added `storymodel4s.core.DerivationReceipt`
   (core/source.scala:468), which collides with the pre-existing
   `storymodel4s.document.DerivationReceipt` (document/compiler.scala:145). In
   `document/CompilerSuite.scala` the wildcard `import storymodel4s.core.*` outranks the
   package-mate, so the test resolves the wrong `of`. Every other JVM module is green
   (recall 47, align 238, interview 104, embed-core 144, view 71, codec 133, fixtures 117,
   laws 85, provider-parser 28, embed-grakern 6, embed-bench 98 with 1 env-gated skip).
   The previous session's scoped gate (embed-bench only) could not see this.
2. **235 commits on main are unpushed** (origin/main = 7385cf93, 2026-08-29). SD3 says
   unreferenced work is invisible; unpushed work is one disk away from that.
3. **Fleet residue:** 174 branches, 22 linked worktrees (14 under `.claude/worktrees` and
   `.worktrees`, 5 under `/private/tmp`), 94 dangling commits. The salvage manifest says the
   dangling graph was swept on 2026-09-01; re-sweep before pruning anything.
4. **storyatlas4s has no git remote at all**, an unfinished uncommitted `preview.html` diff
   (+177/-30 over five files), and its last smoke run failed (only a failure PNG survives).
5. **84 open beads**, many fleet-era P0 rows overtaken by landed work (D0 adapter, S1–S4
   output takeover, C1 film contracts, product PRD). The owner asked to see that number fall.
6. grakern now has a remote (canardlapin/grakern) carrying the pinned SHA, so CI is no longer
   blocked on it.

## 3. Strategy

- **One falsifiable milestone artifact:** an edition showing the gold (hand) and silver
  (machine-built) War of the Ghosts atlases side by side, with a pinned agreement table. It
  exercises every arrow of the pipeline and turns the front half into a measured quantity.
- **Serial slices, each landable in one to three days,** each ending with a commit whose
  message carries the gate totals and the mutation that was run (SD1, SD2, §4).
- **Widen only after the thin path is real.** No new projection, feature layer, or UI polish
  before a machine-built model flows through the existing one.
- **The gold fixture stays the oracle**, never the input the machine is tuned on: the bench
  compares structure (entities by span, situations, containment, causal edges), not text.
  WOG's obscurity is load-bearing (fame is contamination); the silver build is *diagnostic*,
  never calibrated, and the docs say so.

## 4. Phases

### Phase 0: make main trustworthy (about one day)

| # | Slice | Falsifier / evidence | Notes |
|---|---|---|---|
| 0.1 | Rename `core.DerivationReceipt` → `SourceDerivationReceipt` (18 refs in core main, two core suites; ADR 0007 line) | `documentJVM/test` compiles and passes; then full `checkAll` on a clean export, three platforms | the alternative (qualify the import in the test) leaves a public-vocabulary collision across modules; rejected |
| 0.2 | Record the baseline: totals per module from the `checkAll` log in the commit message | the log with `GATE_EXIT=0` | first fully-gated commit of solo mode |
| 0.3 | Push main; prune the 22 worktrees (`git worktree remove`), leave branches until the owner reviews `git branch --no-merged main` | `git fsck --dangling` sweep before and after, counts recorded | pushes need the owner's go (§6 D3) |
| 0.4 | storyatlas4s: add a remote; merge `repair/pixel-dpr2` so `mockups/` is on main; commit the preview.html work as a WIP branch; bump both pins; regenerate the edition; get the smoke suite green; fix the README pin citation | `compileAll testAll` with the three `-D` overrides pointing at scratchpad clones at the pinned SHAs; Playwright smoke totals | expected near-zero fallout from the bumps |
| 0.5 | Bead hygiene: close overtaken fleet rows with the landing SHA in the note | count of open beads before/after | notebook use only (SD4) |
| 0.6 | Optional: GitHub Actions running `testJVM` with grakern fetched from its remote | a green run on origin | owner decision; needs repo access for the grakern fetch |

### Phase 1: story text → validated StoryModel, automatically (one to two weeks)

Goal: **silver WOG**, a `StoryModel[Validated]` built from
`docs/design/war-of-the-ghosts-boas1901.txt` with no hand edits, benchmarked against gold.
Closes bd-01M1CQCSZSTHJQ5PA4PQ4GWKVB (real transport) and bd-01M17MET0E4TWW304YKXPHBSBG
(50 of 50 sentences); supplies the "silver atlas" the roadmap's M1 names.

| # | Slice | Falsifier / evidence | ADR |
|---|---|---|---|
| 1.1 | New JVM-only `provider-agent` module: a `ParserTransport` backed by an LLM over HTTPS (`java.net.http`, circe; no new dependencies). Per sentence: PENMAN plus the `ExplicitIndexListV1` token-index sidecar the admission court requires; `ProviderCall` receipts; `acquire` stage-cache keys so reruns replay from cache; prompt-package manifest with checksum | replay suite over recorded responses (the scripted-transport pattern already in provider-parser tests); one live smoke gated on an env var (the `STORYMODEL4S_ONNX_MODEL` pattern); mutation: strip the index sidecar → admission refuses | new module + a readiness case for remote providers (see §6 D1) |
| 1.2 | `build` driver: `@main storyBuild(textPath, outDir)` → `SurfaceAtlas` → transport → `JsonAmrCandidateProvider` → `AmrCandidates.fromPenman` → charts + validator outcomes as codec JSON under a git-ignored dir | coverage table: charts per sentence, validator refusals per sentence, pinned literal counts once stable | new module (`build`, per roadmap M1) |
| 1.3 | Chart-driven proposal provider, generalising `DeterministicFixtureProvider`: one event situation per chart root predicate, NarratedWorld root context, root-segment membership, title summary, explicit abstention where a chart has no event root; feed `NarrativeCompiler` | multi-situation `NarrativeCompilation` with recorded gaps for all 50 sentences; mutation: remove the abstention → named test red | none (uses existing `acquire` vocabulary) |
| 1.4 | Compiler extension so a multi-sentence Draft validates: `ParticipantEdge` from chart roles through exact coref; `DiscourseTrajectory` from situation order; temporal edges from `:time` roles where present; resolve the CompilerSuite:771 refusal by evidence, not defaults | `StoryValidator.validate(silver)` is `Right`; one invariant test per new edge type; mutation per invariant | ADR 0005 amendment line |
| 1.5 | Gold-vs-silver bench: entity overlap by mention span, situation count and span coverage, containment agreement, causal precision/recall; a dated `docs/design/measured-baseline-*.md` with literal values | numbers pinned as literals (§4: recomputed expectations prove nothing); mutation-test the arithmetic | none |

Rejected alternative for 1.1: a local AMR parser subprocess (amrlib). Deterministic, but it
needs the `Ready(PinnedRuntime)` checkpoint-and-licence evidence, a Python runtime, and AMR
parsers are weak on 1901 prose. The transport contract keeps that door open for later.

### Phase 2: the picture (about one week)

Input: gold WOG immediately, silver WOG as soon as 1.4 lands; the side-by-side edition is the
milestone artifact. Design target: `storyatlas4s/mockups/v2/review/main.png` and the brief
(`mockups/mockup-v2-design-brief.md`), which is honest about what the model can show today
(x = discourse offset, y = context lane, no metric distance).

| # | Slice | Falsifier / evidence |
|---|---|---|
| 2.1 | `AtlasLowering` to the v2 design: region hull bands with labels placed inside via intaglio `TextMetrics`, landmark glyphs by situation kind, thread/portal styling, priority-budgeted label collision avoidance (mission item 10), discourse-offset axis guide, minimum surface-rail row height, PatternPaint hatching for missing | static laws: identity (`data-name` ↔ `Address`), counts per mark kind, no overlapping label boxes above the budget; screenshots diagnostic only |
| 2.2 | App loads `storymodel.json` through `codec` plus `StoryValidator`, not only the linked fixture; silver and gold selectable | smoke: same address selected in both editions resolves to the same placement text |
| 2.3 | Shell: v2 typography and panel CSS, three panes, whole-work minimap, breadcrumb, bottom control bar, viewBox pan/zoom on the atlas, Codex lanes in the gutter with a 3 px minimum row | Playwright shell suite graduates from `smoke.cjs` (one spec per law family) |
| 2.4 | Cut: the 9-state atlas cross product in the static edition (keep story/hidden, scene/sentences, scene/tokens), the flow-level codex SVGs, the AWT measurer | edition file count drops; receipts still complete |

### Phase 3: marks that carry the model (one to two weeks)

| # | Slice | Falsifier / evidence | ADR |
|---|---|---|---|
| 3.1 | Unstick the BasisId fail-closed path (view/compiler.scala:14-47) and add a sidecar resolver producing `AtlasFeatureLayer` with values, coverage and missing masks; first feature = the free deterministic lexical-novelty baseline per sentence (portable, no model artifacts), MiniLM second | WOG codex suite's stale refusal pin flips to a positive control; a masked value must render as missing, never as a low score (mutation) | ADR 0002 checkpoint 7 |
| 3.2 | D9 uncertainty marks: credence, alternatives, unresolved on Region/Landmark/Thread from `ClaimMeta`, validator gaps and chart alternatives. Silver WOG gives real uncertainty; gold gives the positive control of none | five typed states, each with a fixture and a mutation | ADR 0002 D9 line |
| 3.3 | Context bands and discontinuous-SpanSet portals (D4 rows 2 and 6) | WOG atlas suite laws | — |
| 3.4 | Chronology Loom as a second `ProjectionKind` with a `WorldRank` axis meaning; poset layout per context; proves `ProjectionContract` is pluggable | textual twin; the no-inference law (no rank without a `TemporalRelation` edge) | ADR 0002 line |
| 3.5 | Codecs for `CommonViewState`, `CodexSpec`, `AtlasSpec`, `NarrativeScene`, `CodexFlow` (saved views, V-D3) | `compile(decode(encode(m))) == compile(m)` law | codec milestone |

### Phase 4: widen the inputs, reconnect recall (after the above)

- 4.1 A second admitted text through the whole pipeline. Text enters the repo only after the
  owner signs `docs/design/story-text-admission-checklist.md`; Joyce's *Araby* is approved
  diagnostic-only (retrospective narrator stresses the reader-horizon machinery).
- 4.2 Recall Voyage: `view/recall.scala` lowering `(RecallGraph, SourceView, HsmmResult)` to
  the packet the frozen court specifies (full P rows, F steps, Viterbi beside, six external
  provinces, no renormalisation), tested on the Anna fixture against about six of the court's
  §7 fixture courts first. This is the seam the Sherlock product PRD's `preview.html` also
  needs, so the two lines re-join here.

## 5. Land procedure in solo mode

```
S=<scratchpad>
git archive <sha> | tar -x -C $S/gate                      # never gate a dirty tree
git clone /Users/bbuchsbaum/code/scala/grakern $S/grakern-pin && git -C $S/grakern-pin checkout 0329c43c88a0b71e9aa4456723bb16bac2fa3841
bash tools/reference-scope.sh "$(git merge-base main CAND)" CAND   # scoped gate for the loop
(cd $S/gate && sbt -Dstorymodel4s.grakern.build=$S/grakern-pin checkAll > $S/gate.log 2>&1); echo GATE_EXIT=$?
```

Each landing commit records: modules gated, TEST TOTALS per module, `GATE_EXIT`, the mutation
run (what was deleted, which named test went red), and what the evidence does not establish
(`LocallyObserved`). Cold review in a separate pass with a fresh-context agent (SD6) before the
merge, never in the authoring session. Delete the gate export once the slice lands.

## 6. Decisions for the owner

- **D1. Transport for the front half.** Recommended: an LLM over HTTPS producing PENMAN plus
  token indices, behind the existing `ParserTransport` contract, with a new readiness case for
  remote providers (a remote model has no checksummed checkpoint or licence file, so
  `Ready(PinnedRuntime)` cannot describe it). Alternative: local AMR parser subprocess. Also:
  which model, and the environment variable that carries the key.
- **D2. Where the no-JS `preview.html` emitter lives.** The bundle spec assigns rendering to
  storyatlas4s. Recommended: keep the interactive app there now, and defer a pure-string HTML
  emitter in `view` until phase 4.2 needs it for the terminal Sherlock command (it would need an
  ADR line and a rejected-alternative entry).
- **D3. Outward actions.** Push the 235 commits; create a GitHub remote for storyatlas4s; CI
  on or off.
- **D4. Priority.** This plan pauses the Sherlock recall-to-video line until phase 4.2. If the
  owner wants that line first, phases 2 and 3 slide and 4.2 moves ahead of 1.
- **D5. Pruning.** Removing the 22 worktrees is safe (commits stay); deleting fleet branches is
  the owner's call after a `--no-merged` review.

## 7. Out of scope for this plan

Critic execution loops, non-exact coreference, hierarchy induction (ADR 0004 level
coordinates), the recall-construction integrity contract rev 3, Story-World and Local Semantic
Field projections, GPU or deck.gl rendering, PDF/Java2D figure export.
