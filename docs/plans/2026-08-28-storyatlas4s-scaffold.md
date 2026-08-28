# storyatlas4s — application scaffold plan (post-commit)

- Status: plan, 2026-08-28 (claude-storymodel4s). Executes after the narrow
  visualization commit (compiler + atlas + visibility) lands on `main`.
- Governed by ADR 0002 D6/D7/D14 and workspace policy (`packages.toml`;
  sibling deps by full git-SHA `ProjectRef` pin; no `../sibling` composites,
  no `-SNAPSHOT`).
- Precedent for a Scala.js app in the workspace: `cardsaplenty` (Laminar
  17.2.1, scalajs-dom).

## 1. What the repo is

`/Users/bbuchsbaum/code/scala/storyatlas4s` — the **application and renderer
adapters** for the Narrative Codex and Narrative Atlas. It owns nothing
scientific: every artifact it draws is a `view.ViewArtifact` compiled in
`storymodel4s`; every address it selects is a `core.Address`; every figure it
exports carries the `ViewProvenance` + `LayoutReceipt` it was given.
storymodel4s never depends on it.

## 2. Modules

| Module | Platforms | Depends on | Owns |
|---|---|---|---|
| `storyatlas4s-intaglio` | JVM, JS | storymodel4s `view` (SHA pin), intaglio `core`/`svg` (SHA pin) | pure lowering `NarrativeScene → intaglio.Scene` and `PlacedCodex overlay → intaglio.Scene`; `GraphicsName` = `MarkId`/fragment id; the §6 renderer protocol implemented over Intaglio SVG (JVM figures + JS) and, later, Canvas |
| `storyatlas4s-layout` | JVM, JS | storymodel4s `view` | `TextLayoutCapability` implementations: JVM deterministic paginator (owned, receipted — the publication backend); JS DOM measurer (identity-stable only) |
| `storyatlas4s-app` | JS | all above, Laminar, scalajs-dom | shell: DOM text rail (runs → text nodes with an offset→node map, V-T2), inspector, lenses, camera, selection = `Set[Address]`, keyboard/ARIA, static-edition loader |
| `storyatlas4s-cli` | JVM | intaglio + layout | `edition <model.json> --view <spec.json> --out <dir>`: writes `codex.html` (DOM + inline SVG), `atlas.svg`, twins, receipts — the slice-1 acceptance artifact |

No module cross-builds Native (Intaglio has none); the scientific compilers
stay Native-clean inside storymodel4s.

## 3. Pins and registration

- `storymodel4s` pinned by full SHA: the visualization slice landed at
  `8492e43`, but the pin must be the first commit at or after the grakern
  fix-forward (bead `bd-01M14XH4033NZHJ9DY8DBNXGYW`), because a `ProjectRef`
  consumer loads storymodel4s's `build.sbt`, whose grakern fallback URL does
  not resolve until grakern has a remote;
  `intaglio` pinned at `596b398` (the SHA Codex evaluated); override flags
  `-Dstoryatlas4s.storymodel4s.build=<path>` / `-Dstoryatlas4s.intaglio.build=<path>`
  per workspace convention.
- Register `[[package]] id = "storyatlas4s"` (kind app, stage prototype,
  platforms JVM + Scala.js) and two `[[dependency]]` blocks in
  `packages.toml`; run `python3 tools/workspace.py check|render`; never
  hand-edit generated workspace docs.
- Scala 3.7.4, sbt 1.12.14, sbt-typelevel 0.8.7, munit 1.3.4; org
  `io.github.canardlapin`; Apache-2.0; `startYear 2026`.

## 4. First deliverable (slice 1, ADR §8)

`storyatlas4s-cli edition` on the WOG fixture:

1. decode `model.json` (codec) → revalidate → `StoryModel[Validated]`;
2. `CodexCompiler` with the `Reading` lens → `CodexFlow`; `AtlasCompiler` at
   Story and Scene levels → two `NarrativeScene`s;
3. JVM paginator → `PlacedCodex` with `LayoutReceipt`;
4. lower overlays and scenes to Intaglio SVG; emit `codex.html` whose DOM
   text-node concatenation equals `canonicalText` (V-T2 law as a CLI test),
   `atlas-story.svg`, `atlas-scene.svg`, both textual twins, `receipt.json`;
5. golden checksums of the SVGs and twins per platform.

Acceptance: the edition builds from the fixture with the
"researcher-reviewed narrative acceptance fixture" basis printed on the page;
every SVG `data-name` maps through `NavigationIndex`/`SceneNavigation` to an
`Address`; no mark or annotation lacks evidence (compilers already enforce).

## 5. Then

- Browser app: load the static edition; selection round trip Codex ↔ Atlas via
  `Set[Address]`; reader-horizon caret; lens switching; keyboard navigation
  next/previous mention (needs `MentionForms` from `document`).
- Benchmark protocol (D7): mark budget and frame time on a novel-length
  synthetic model from `laws.StoryGens` before any Canvas/GPU work.
- Two-level WOG Atlas tracer in the app (V-L2 across a live zoom transition;
  Intaglio promotion candidates recorded from measured need).

## 6. Not in scope

Tiles, GPU, Arrow, ELK/MSAGL, a general 2-D engine, population recall views,
Local Semantic Field (needs ADR 0001 `GeometryId`), anything that draws a claim
the ledger lacks.
