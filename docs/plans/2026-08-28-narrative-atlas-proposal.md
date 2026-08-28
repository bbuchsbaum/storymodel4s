# Narrative Atlas (`storyatlas4s`) — proposal, revision 0

Status: **draft for deliberation** (owner's proposal, filed verbatim by
claude-storymodel4s on 2026-08-28). Discussion: mote topic `narrative-atlas`,
bead `bd-01M14DX0B3VJQKN185TXN8RMMG`. The deliberated result becomes ADR 0002
(visualization contract) plus a plan document; this file is the input, not the
decision.

---

# The product should be a **Narrative Atlas**, not a graph viewer

The visualization should become a first-class scientific instrument in its own right—perhaps a companion package and application called **`storyatlas4s`**.

The governing idea is:

> **A story needs one immutable identity system, but several legitimate geometries.**

There is no single two-dimensional arrangement that simultaneously preserves:

- exact word order;
- discourse order;
- story-world chronology;
- event hierarchy;
- spatial movement;
- entity continuity;
- causal structure;
- semantic similarity;
- sensory and affective flow.

A global UMAP inevitably hides most of these and falsely implies that one metric geometry is the story. Instead, the Atlas should offer several **typed narrative projections**, while preserving object identity, selection, provenance, and camera focus as the analyst moves among them.

This follows directly from the project architecture: the exact surface text is the observational axis; feature tracks, propositions, document identity, events, scenes, episodes, and recalls are separate but interoperable layers. The mission also explicitly requires traversal from words to windows to narrative units and back. The vision states that users should be able to move between words, propositions, events, scenes, episodes, and recall routes without any of these disappearing into one embedding or score.

Existing systems provide pieces of this design. HiGlass demonstrates rapid, linked, multiscale navigation across synchronized one- and two-dimensional tracks. Story Ribbons visualizes character and theme trajectories at multiple narrative levels; StoryFlow optimizes hierarchical entity storylines; Story Curves explicitly compares presentation order with story-world chronology; and GraphMaps shows how stable map-like geometry and level-of-detail layers can make a large graph navigable. None individually provides the evidence-backed, multirelational, word-to-world instrument needed here, but together they identify a productive design vocabulary.

---

# 1. The central interface

The application should have four continuously synchronized surfaces:

```text
┌──────────────────────┬────────────────────────────────────┬──────────────────────┐
│ LENSES & QUERIES     │                                    │ CLAIM INSPECTOR      │
│                      │          NARRATIVE ATLAS            │                      │
│ projection           │                                    │ selected event       │
│ relation layers      │    regions, routes, landmarks,     │ source evidence      │
│ feature metric       │    terrain, portals, uncertainty   │ AMR/propositions     │
│ window scale         │                                    │ alternatives         │
│ comparison mode      │                                    │ provenance           │
├──────────────────────┴────────────────────────────────────┴──────────────────────┤
│ EXACT TEXT + HIERARCHY + FEATURE TRACKS + PLAYHEAD + OVERVIEW NAVIGATOR          │
└──────────────────────────────────────────────────────────────────────────────────┘
```

## The main Atlas

This is a pan-and-zoom canvas with semantic zoom. It displays story structure using a cartographic visual grammar.

## The exact-text rail

This remains visible at all times, perhaps collapsed at distant zoom levels. It contains:

- exact text;
- token, clause, sentence, event, scene, and episode boundaries;
- feature tracks;
- selected spans;
- source and story-world clocks;
- the current playhead;
- a minimap of the full narrative.

Selecting anything in the Atlas highlights its exact textual evidence. Selecting text highlights every proposition, event, scene, relation, feature estimate, and recall statement supported by that text.

## The claim inspector

Every visual object must answer:

- What exactly is this?
- Which text supports it?
- Is it explicit, entailed, inferred, or hypothesized?
- Which extractor or model proposed it?
- What alternative interpretations were retained?
- Why is it located or styled this way?
- What changes under another model or policy?

## The lens and query palette

The user should be able to display at most a few active dimensions at once:

- one primary projection;
- one or two relation layers;
- one quantitative feature surface;
- one comparison or recall overlay.

This prevents the application from becoming a dashboard of fifty mutually interfering toggles.

---

# 2. A cartographic visual grammar

The interface can feel like navigating a world without pretending that the story literally occupies geographic space.

| Visual object | Narrative meaning |
|---|---|
| **Territory** | Episode, scene, or other containing narrative unit |
| **District** | Child scene or event cluster within a territory |
| **Landmark** | Important event, state change, revelation, or proposition |
| **Route** | Discourse progression, entity trajectory, movement, goal path |
| **Road** | Temporal, causal, enabling, or goal relation |
| **Portal** | Flashback, anticipation, retrospective reference, semantic recurrence, or distant causal link |
| **Contour/terrain** | Selected scalar feature such as auditory imagery, threat, uncertainty, or recall density |
| **Thread** | Continued participation of a person, object, location, or goal |
| **Fog or hatching** | Uncertainty, missingness, boundary ambiguity, or model disagreement |
| **Tether** | Exact connection from an interpretation to its source words |
| **Off-map region** | Recall commentary, external association, or material not grounded in the source |

The semantics must be declared, not decorative. If region area denotes source duration, it should always denote duration in that projection. If road thickness denotes posterior alignment mass, it must not sometimes denote confidence or importance.

A small projection legend should state explicitly:

```text
x-position: discourse position
y-position: entity-thread layout
region area: source duration
distance: no semantic interpretation
solid edge: source-explicit relation
dashed edge: inferred relation
```

That honesty is part of the scientific contribution.

---

# 3. Semantic zoom: the representation changes, not merely its size

Ordinary zoom enlarges dots. **Semantic zoom replaces one representation with a more informative one.**

| Scale | What appears |
|---|---|
| **Whole story** | Episodes, major scenes, principal characters, locations, causal backbone, global feature terrain |
| **Episode** | Constituent scenes, scene transitions, character bundles, goal arcs, major sensory and affective peaks |
| **Scene** | Events and states, local entity trajectories, location and context changes, causal and temporal relations |
| **Event** | Canonical proposition, participants, state transitions, modality, source support, event-level features |
| **Sentence** | Exact sentence, aligned propositions, entity mentions, quotation and belief scopes |
| **Clause** | AMR fragment, semantic roles, negation, polarity, reentrancy, temporal and modal cues |
| **Token** | Exact token, lexical measurements, sensorimotor contribution, alignment evidence, model attribution |

The transition must preserve the user's mental map:

- an episode territory subdivides into scenes;
- a scene subdivides into events;
- an event landmark opens into a proposition chart;
- the proposition chart resolves into aligned clauses and words.

Objects should morph into their children rather than disappearing and being replaced at unrelated coordinates.

A selected object remains selected across levels. Zooming into a scene does not merely center the camera; it changes the available semantic vocabulary.

---

# 4. The default projection: **Discourse Atlas**

The default map should preserve the one coordinate that is exact and incontrovertible.

For narrative unit \(v\),

\[
x_v = g\!\left(\operatorname{center}(\operatorname{sourceSupport}(v))\right).
\]

Thus horizontal position is hard-anchored to source order.

Vertical placement is optimized to show story organization:

- entity-thread continuity;
- location continuity;
- scene containment;
- limited edge crossings;
- limited thread bends;
- stable position across zoom levels.

A possible objective is:

\[
\begin{aligned}
\mathcal L ={}&
\lambda_{\mathrm{cross}}L_{\mathrm{edge\ crossings}}
+ \lambda_{\mathrm{bend}}L_{\mathrm{thread\ curvature}}
+ \lambda_{\mathrm{lane}}L_{\mathrm{lane\ changes}} \\
&+ \lambda_{\mathrm{contain}}L_{\mathrm{containment}}
+ \lambda_{\mathrm{stability}}L_{\mathrm{cross\ scale\ movement}}
+ \lambda_{\mathrm{label}}L_{\mathrm{label\ conflict}}.
\end{aligned}
\]

Subject to hard constraints:

\[
x_i < x_j \quad \text{whenever unit }i\text{ precedes }j
\]

and

\[
R_{\mathrm{child}}\subset R_{\mathrm{parent}}.
\]

The result is not an embedding. It is a constrained narrative layout.

At the broadest level, episodes appear as large contiguous regions. A prominent entity thread may move through them. Important events become landmarks. A flashback or retrospective reference becomes a portal rather than forcing the entire map to bend around it.

---

# 5. Narrative projections

The Atlas should offer several projections of the same objects. Switching projections should animate the objects into their new positions, preserving identity and selection.

## A. Discourse Atlas

**Question answered:** How does the text unfold?

- horizontal coordinate: exact source order;
- hierarchy visible as nested regions;
- character and location threads;
- feature tracks and boundaries;
- nonlocal links drawn as portals.

This is the stable home view.

## B. Chronology Loom

**Question answered:** In what order did events occur in the story world, and in what order were they told?

Possible coordinates:

\[
x_v = t_W(v), \qquad y_v = t_D(v).
\]

The route through points follows discourse order. A linear story lies near a monotonic diagonal; flashbacks, anticipations, and withheld events create folds and reversals.

This generalizes Story Curves from one curve into a multiscale, evidence-backed map with uncertainty and event hierarchy.

## C. Story-World Map

**Question answered:** Where are people and objects, and how do they move?

Locations form a topological—not necessarily geographic—map. Character and object trajectories pass through them. The playhead updates:

- who is present;
- what is happening;
- which states hold;
- which goals are active;
- which propositions each character believes.

When real geographic information exists, an optional geographic layer can be used. Otherwise distance means graph distance or is explicitly left uninterpreted.

## D. Causal and Goal Projection

**Question answered:** What makes the story cohere?

This view shows:

- causes;
- enabling conditions;
- prevention;
- goal formation;
- attempts;
- success and failure;
- resulting states.

The default displays a causal backbone or transitive reduction. Secondary links emerge on zoom or selection, avoiding a hairball.

## E. Entity and Relationship Projection

**Question answered:** Who interacts with whom, when, and in what context?

This builds on storyline visualization, but with:

- nested scene and episode structure;
- speech and belief contexts;
- entity coreference uncertainty;
- relationship state changes;
- exact evidence links.

## F. Local Semantic Field

**Question answered:** Which nearby events or propositions are semantically related?

Embeddings can be used here, but only locally and transparently:

- within a scene;
- within a selected episode;
- among retrieved nonlocal candidates;
- with event and hierarchy anchors held fixed.

This is where a small constrained embedding can be useful. It is not the global map and does not determine story identity, order, or causality.

## G. Recall Voyage

**Question answered:** What route did a person take through the story?

This becomes the signature visualization in Phase 2:

- ribbon width: alignment mass;
- sharpness: localizability;
- blur: uncertainty;
- branching: blending or alternative alignment;
- backward route: backtracking;
- long leap: skipped source material;
- broad band over a region: gist or compression;
- repeated loop: elaboration;
- off-map excursion: association, commentary, or intrusion.

A population view aggregates these into recall highways, neglected territories, retrieval hubs, and common shortcuts.

---

# 6. Feature topography

The user should be able to select a scalar feature such as:

- visual imagery;
- auditory imagery;
- spatial content;
- motor content;
- interoception;
- valence;
- arousal;
- threat;
- semantic surprise;
- boundary probability;
- causal centrality;
- epistemic uncertainty;
- entity density;
- recall coverage.

That feature can be displayed simultaneously in three synchronized forms.

## Aligned track

A precise one-dimensional track against the text axis.

## Atlas terrain

Region fill, contour, or controlled elevation over the main map. The base geometry does not move when the metric changes.

## Local glyph

At event scale, a compact and interpretable indicator attached to the relevant node or span.

Full free-camera 3D should not be the default. A controlled orthographic 2.5D view can use elevation to show one selected feature while retaining precise pan and zoom. Excessive tilt would create occlusion and turn the interface into a landscape demo rather than an analysis tool.

---

# 7. The **Narrative Scale-Space Explorer**

This should be one of the system's genuinely novel features.

Suppose \(f(t)\) is a word- or clause-aligned measurement. Define its aggregation at position \(x\) and scale \(s\):

\[
F(x,s) = \frac{\sum_t K_s(t-x)\,f(t)\,c(t)}{\sum_t K_s(t-x)\,c(t)},
\]

where:

- \(K_s\) is a window or smoothing kernel;
- \(s\) may be measured in tokens, sentences, events, or source duration;
- \(c(t)\) represents coverage or valid evidence.

Render:

- horizontal axis: source position;
- vertical axis: window scale;
- colour or elevation: \(F(x,s)\).

This reveals whether a sensory peak is:

- one unusually vivid word;
- a dense sentence;
- a sustained scene;
- an episode-wide property.

A user could ask:

> Show the strongest auditory regions using three-sentence windows, requiring at least 80% coverage.

The system returns ranked regions on the scale-space map and places navigable markers in the Atlas. Clicking one smoothly flies to the corresponding scene and opens its source evidence.

A typed query might be:

```scala
FeatureWindowQuery(
  feature = FeatureId("sensory.auditory.expressed"),
  window = Window.Sentences(3),
  reducer = Reducer.WeightedMean,
  minimumCoverage = Probability.unsafe(0.80),
  ranking = Ranking.Top(20)
)
```

Natural language can compile into this query, but the user sees and approves the typed interpretation before execution.

The mission already specifies feature tracks with declared windows, reductions, coverage, missingness, and derivation receipts. The scale-space explorer is the visual realization of that contract.

---

# 8. A story should be playable as a changing world

The Atlas needs a **narrative playhead**.

As the playhead moves word by word, sentence by sentence, event by event, or scene by scene, the display updates:

- active location;
- present entities;
- current states;
- active goals;
- character knowledge and beliefs;
- unresolved questions;
- accumulated causal structure;
- currently available interpretations.

Two modes are essential.

## Omniscient analyst mode

Displays the complete model constructed from the entire story.

## Reader-at-time-\(t\) mode

Displays only information warranted by evidence available up to the current point:

\[
\mathcal S_{\le t} = \{c\in\Gamma : \operatorname{evidenceEnd}(c)\le t\}.
\]

This makes foreshadowing, revelation, mystery, and reinterpretation visible.

A later revelation can alter the interpretation of an earlier event. In reader mode, the earlier event is displayed as it could have been understood then. When the revelation occurs, a retrospective link appears and the earlier region can acquire a new annotation without overwriting its original epistemic state.

That is a fundamentally deeper representation of narrative than a static graph.

---

# 9. *The War of the Ghosts* as the first spectacular demo

At the whole-story level, the user might see three large episode territories:

1. river, hunting, strange sound, canoe arrival;
2. journey and battle;
3. return home, recounting, transformation, and death.

Character threads include:

- the two young men;
- the arriving warriors;
- the people at home.

Location threads include:

- river;
- canoe route;
- battle location;
- home.

The sensory terrain might reveal:

- auditory density around the war cries;
- spatial and movement content during the river journey;
- bodily and interoceptive content around the final transformation.

The chronology projection would distinguish:

- the announced future battle;
- the actual battle;
- the later recounting of the battle.

The semantic atlas would connect all three without merging them into one event.

At event zoom:

- the warriors' claim that the young man was struck appears inside a speech context;
- the young man's lack of felt illness appears as a separate narrated-world state;
- an injury-to-death causal edge is displayed only as a hypothesis, perhaps dashed and surrounded by uncertainty;
- the belief that the warriors are ghosts appears as a belief-state transition rather than unqualified fact.

In reader-at-time mode, the "ghost" interpretation should not be displayed as settled during the opening river scene. When the interpretation emerges, earlier regions acquire a retrospective portal.

This single demo would establish almost every important property of the system.

---

# 10. The visualization compiler

The visualization should not be hard-wired into browser components. A pure Scala layer should compile a validated story model and a view specification into a renderer-neutral scene graph:

\[
\boxed{(\texttt{StoryModel},\texttt{ViewSpec}) \longrightarrow \texttt{NarrativeScene}}
\]

Representative types:

```scala
enum NarrativeScale:
  case Story
  case Episode
  case Scene
  case Event
  case Proposition
  case Sentence
  case Clause
  case Token

enum ProjectionKind:
  case DiscourseAtlas
  case WorldChronology
  case StoryWorld
  case CausalGoal
  case EntityRelations
  case LocalSemantic
  case RecallVoyage

enum VisualPrimitive:
  case Region(value: RegionMark)
  case Route(value: RouteMark)
  case Landmark(value: LandmarkMark)
  case Portal(value: PortalMark)
  case Terrain(value: TerrainMark)
  case Track(value: TrackMark)
  case Label(value: LabelMark)
  case EvidenceTether(value: TetherMark)
  case TextSpan(value: TextSpanMark)
  case Uncertainty(value: UncertaintyMark)

final case class ProjectionContract(
  kind: ProjectionKind,
  xMeaning: AxisMeaning,
  yMeaning: AxisMeaning,
  distanceMeaning: DistanceMeaning,
  areaMeaning: Option[MeasureMeaning],
  invariants: Set[VisualInvariant]
)

final case class ViewSpec(
  projection: ProjectionKind,
  scale: NarrativeScale,
  focus: NarrativeRef,
  relationLayers: Set[RelationLayer],
  feature: Option[FeatureViewSpec],
  comparison: Option[ComparisonSpec],
  uncertaintyPolicy: UncertaintyPolicy,
  epistemicHorizon: EpistemicHorizon
)

final case class NarrativeScene[S <: SceneStatus](
  camera: Camera,
  contract: ProjectionContract,
  marks: Vector[VisualPrimitive],
  navigationIndex: NavigationIndex,
  provenance: SceneProvenance
)
```

Every visual primitive carries:

```scala
final case class VisualIdentity(
  narrativeRef: NarrativeRef,
  evidence: Vector[EvidenceRef],
  claim: Option[ClaimId],
  minScale: NarrativeScale,
  maxScale: NarrativeScale,
  pickId: PickId
)
```

This makes the scene:

- testable without a browser;
- renderable to WebGL, SVG, PNG, or publication figures;
- reproducible from a serialized `ViewSpec`;
- snapshot-diffable across model versions.

---

# 11. Visual laws

The visualization layer should have laws just as the AMR and story layers do.

## Projection laws

For the discourse projection: \(d(a)<d(b)\Rightarrow x(a)\le x(b)\).

For hierarchy: \(\operatorname{ChildOf}(a,b) \Rightarrow R_a\subseteq R_b\).

For chronology: \(\operatorname{Before}(a,b) \Rightarrow x_W(a)\le x_W(b)\).

## Identity laws

The same narrative object retains the same identity across:

- zoom levels;
- projections;
- feature lenses;
- model comparisons.

## Evidence laws

Every selectable interpretive mark resolves to:

- a claim;
- its source evidence;
- its epistemic status;
- its build provenance.

## Level-of-detail laws

- every child has a visible parent at a coarser level;
- no object appears before its parent representation exists;
- label priority is monotonic;
- the visible mark count remains bounded;
- the selected object cannot disappear during ordinary zoom.

## Styling laws

- explicit, inferred, and hypothetical relations have distinguishable styles;
- missingness is not rendered as zero;
- uncertainty is not rendered as low magnitude;
- relation type is not encoded only by colour;
- no projection implies a distance semantics it does not possess.

These can be tested on generated story graphs as well as curated fixtures.

---

# 12. Multiresolution rendering architecture

The Atlas should use a tile pyramid, much like maps and multiscale genome browsers.

For each projection and level of detail, preprocessing produces:

```scala
final case class NarrativeTileId(
  projection: ProjectionKind,
  zoom: Int,
  x: Int,
  y: Int
)

final case class NarrativeTile(
  id: NarrativeTileId,
  marks: Vector[VisualPrimitive],
  featureAggregates: FeatureBlock,
  labelCandidates: Vector[LabelCandidate],
  edgeBundles: Vector[BundledRelation],
  childTiles: Vector[NarrativeTileId]
)
```

Only visible tiles and their immediate neighbours are loaded. Each zoom level contains the appropriate semantic objects:

- episodes at distant zoom;
- scenes and event bundles at intermediate zoom;
- events and propositions close up;
- exact tokens only at the deepest levels.

HiGlass demonstrates the value of synchronized multiscale tracks, while GraphMaps and recent browser-side graph work show how tile pyramids, stable geometry, label ranking, and map-style routing can make large relational structures responsive.

---

# 13. Recommended web stack

## Scala layer

- Scala 3 domain and visualization scene types;
- Cats Effect for loading and resources;
- FS2 for feature and tile streaming;
- `graph4s` for typed graph operations;
- `mesh4s` for regions, paths, triangulation, hit testing, and terrain geometry;
- ScalaCheck and MUnit for visual law suites;
- JVM preprocessing for expensive stable layouts;
- Scala.js for the interactive application.

## Application shell

Use **Laminar** for panels, text rail, inspector, controls, keyboard navigation, accessibility, reactive view state.

## GPU canvas

A strong initial rendering substrate is deck.gl through a narrow Scala.js facade:

- `OrthographicView` is designed for non-geospatial top-down two-dimensional views;
- `TileLayer` loads only data needed for the visible viewport and supports custom tile indexing;
- custom layers can render regions, routes, contours, text labels, ribbons, and instanced marks.

The project should nevertheless own a renderer-neutral scene graph. deck.gl is a backend, not the ontology.

## Graph layout and edge routing

For difficult local graph layouts:

- use deterministic Scala layouts where feasible;
- use ELK for layered and compound local graphs;
- evaluate MSAGL.js for map-like graph rendering, label placement, and edge routing.

## Text rendering

Use a hybrid: DOM for exact selectable, accessible text; GPU rendering for thousands of marks, regions, paths, and labels; a shared camera and selection state to keep them synchronized. Rendering exact prose entirely into WebGL would make selection, accessibility, typography, and text copying unnecessarily difficult.

## Data transport

Compact JSON or a binary schema for graph structure; Apache Arrow IPC for dense feature tracks, vector columns, tile aggregates, and large tables; typed arrays in the browser.

## Execution

- layout and expensive searches in Web Workers;
- optional OffscreenCanvas for GPU work;
- static artifact mode for a single story;
- server-backed tile loading for novels, corpora, and population recall data;
- serializable view state and permanent links.

---

# 14. Auditable interaction

## "Why is this here?"

Clicking any object reveals: the view rule that placed it; the coordinate meanings; its source evidence; its feature aggregation; any layout constraints it participates in.

## "Show only observations"

Hide all inferred and hypothesized structure, leaving: exact text; directly aligned propositions; explicit entities and relations; raw features.

## "Show model disagreement"

Render alternative analyses as: ghosted boundaries; split routes; uncertainty fans; side-by-side synchronized maps; animated graph diffs.

## "Rebuild this view under…"

Change: event segmentation; relation acceptance policy; embedding provider; window size; hierarchy model. The geometry should update while preserving stable identities wherever possible.

## Reproducible snapshots

Every saved view records: story-model hash; view specification; projection contract; camera; active filters; feature normalization; selected objects; software version. A figure in a paper should be recoverable as both a static image and an interactive state.

---

# 15. Validation

## Structural checks

- exact word-to-mark-to-word round trip;
- hierarchy containment;
- discourse and temporal order preservation;
- stable identity across zoom;
- relation-style correctness;
- missingness and uncertainty correctness;
- no unsupported visual inferences.

## Performance gates

- sustained 60 fps during pan and zoom on an ordinary laptop;
- no more than a configured mark budget per viewport;
- under 100 ms for cached level-of-detail transitions;
- under 200 ms for common feature-window queries;
- no full dense graph allocation;
- smooth operation for book-length texts and hundreds of recall overlays.

## Task-based evaluation

Users should complete tasks such as:

1. locate the most auditory three-sentence window;
2. identify a flashback and determine its world-time location;
3. trace the causal chain ending in a selected outcome;
4. determine which words support a particular inferred relation;
5. find where a character disappears and later returns;
6. distinguish reported content from narrated-world fact;
7. identify what a participant omitted or recalled out of order;
8. compare two model interpretations of the same scene.

Compare against: plain text with conventional annotations; a static graph; a storyline chart; a global embedding plot; a dashboard with independent panels.

Measure: task accuracy; completion time; confidence calibration; navigation errors; retained understanding of the story; analyst agreement; ability to detect model errors.

---

# 16. Development sequence

## Vertical slice 1: the textual observatory

Exact text rail; word/sentence/event/scene traversal; hierarchy bands; feature tracks; window querying; synchronized selection; claim inspector. This already becomes a powerful text-analysis tool.

## Vertical slice 2: semantic zoom Atlas

Nested episode and scene regions; event landmarks; entity and location threads; stable semantic zoom; tile pyramid; fly-to navigation; feature terrain.

## Vertical slice 3: structural projections

Chronology loom; story-world map; causal and goal projection; AMR/proposition microscope; epistemic playhead; alternative-model comparison.

## Vertical slice 4: recall voyage

Subject recall paths; uncertainty ribbons; omission and compression; external excursions; population recall flow; source–recall synchronized playback.

---

# The decisive design principle

The system should not attempt to create one beautiful picture of a story. It should create a **navigable representational world** in which:

- every word is reachable;
- every high-level object can be opened;
- every feature peak can be found at multiple scales;
- every relation can be followed;
- every interpretation can be audited;
- every projection declares what its geometry means;
- every recall can be seen as a journey through, around, and beyond that world.

\[
\boxed{\text{Narrative Atlas} = \text{semantic zoom} + \text{typed projections} + \text{feature scale-space} + \text{stateful playback} + \text{evidence navigation}.}
\]

That combination—not a more elaborate embedding scatterplot—is the route to something genuinely unprecedented.
