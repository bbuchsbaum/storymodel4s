# Intaglio gap evaluation for the Narrative Codex and Atlas

Date: 2026-08-28

Evaluator: `codex-storyatlas-root`

Work item: `bd-01M14K85783PMTBFP8GJYXMQDJ`
Intaglio revision: `596b398af380079e4b251535230d0bc03cd88c51` (clean `main`)

## Verdict

Intaglio is already sufficient for the first static WOG Atlas tracer and for
SVG annotation overlays and publication export in the Narrative Codex. It
does not yet provide a complete interactive Canvas runtime, but that is not a
reason to build another general visualization layer now.

The initial adapter should use Intaglio unchanged for:

- episode and scene regions;
- event landmarks;
- routes, tethers, brackets, local arrows, and portal polylines;
- labels and grouped marks;
- clipped native-coordinate viewports;
- deterministic SVG output with stable caller-supplied names;
- Canvas and Java2D lowering when those backends become necessary.

The application or story-specific scene layer should initially own camera
state, visible-set selection, semantic level of detail, selection persistence,
and the navigation index. The first tracer does **not** require a new Intaglio
camera, tile, morph, or scene-diff API.

Three genuine generic gaps should be re-evaluated after the tracer:

1. device-space bounds and hit testing by name for Canvas interaction;
2. scalar fields paired with an explicit coverage or validity mask;
3. native curved paths only if adapter-side deterministic tessellation proves
   inadequate for portals and recall ribbons.

## Evidence boundary

This evaluation is against the local Intaglio checkout at the exact revision
above, not against a published artifact. Intaglio is currently
`0.1.0-SNAPSHOT`, cross-built for JVM and Scala.js rather than Scala Native
(`intaglio/build.sbt:6-7,56-127`). The portable `view` module in storymodel4s
therefore cannot depend on Intaglio. The lowering adapter must be a JVM/Scala.js
boundary.

Focused verification on this revision passed:

```text
coreJVM/test: 221 passed, 0 failed
svgJVM/test:   24 passed, 0 failed
```

Those suites include device lowering, scene laws, scalar fields, SVG XML,
deterministic rendering, name-marker preservation, and the shared renderer
conformance contract. They do not establish browser interaction performance.

## Capability matrix

| Need | Current capability | Initial owner | Evidence and qualification |
|---|---|---|---|
| Immutable renderer-neutral scene | Ready | Intaglio | `Scene` is an ordered immutable vector with associative composition (`Scene.scala:567-585`). |
| Regions and districts | Ready | Intaglio | Rectangles, polygons, and compound polygons are portable grobs (`Scene.scala:335-370`). |
| Landmarks | Ready | Intaglio | Points and circles lower to device marks (`Scene.scala:317-326,371-378`). |
| Roads, routes, tethers, brackets | Ready | Adapter + Intaglio | Lines and segments are available. Arrowheads and bracket caps are small ordinary grob groups. |
| Portals and ribbons | Ready with tessellation | Adapter | A polyline or filled polygon can represent them. There is no cubic-path primitive. |
| Labels | Ready for the tracer | Intaglio | Portable text marks exist. Collision avoidance and narrative label priority remain scene concerns. |
| Stable renderer marker | Partial | Adapter + Intaglio | `GraphicsName` is a checked nonblank string and survives lowering. It is not unique and is not a scientific identity (`GraphicsTypes.scala:3-13`). |
| SVG picking | Ready for first slice | App + Intaglio | SVG groups and primitives emit escaped `data-name` attributes (`SvgRenderer.scala:156,200,213-242`). DOM event delegation can recover the marker. |
| Canvas picking | Missing | Adapter, later Intaglio candidate | Canvas programs retain names, but drawing discards them and no hit-test API exists (`CanvasRenderer.scala:95-204,390-411`). |
| Pan and zoom | Ready for first tracer | App + adapter | `Viewport` provides native x/y scales, frame placement, clipping, y direction, and rotation (`Scene.scala:275-308`). Camera state can lower to one root viewport. |
| General affine camera and inverse | Partial | App, later generic candidate | The existing viewport covers translation, independent axis scale, clipping, and rotation, but exposes no inverse screen-to-world transform or camera object. |
| Bounds by name | Missing | Adapter for known marks; later Intaglio candidate | Device primitives are public numeric values, but Intaglio exposes no named bounds index and text/stroke/clip/rotation make a correct generic implementation nontrivial. |
| Viewport culling | Missing but nonblocking | Narrative scene | `DeviceScene.fromScene` lowers the complete supplied scene. The story compiler can supply only visible marks before lowering. |
| Semantic LOD | Correctly absent | Narrative scene | Narrative parent/child visibility and selected-ancestor behavior must not enter Intaglio. |
| Incremental scene diff | Missing but nonblocking | App | Initial SVG artifacts and small WOG scenes can be replaced whole. Add a diff only after measurement. |
| Dense scalar field | Ready only for total finite data | Intaglio | `ScalarField2D` is dense, regular, finite, and immutable (`ScalarField.scala:94-209`). |
| Coverage-aware scalar field | Missing | Later Intaglio candidate | A Boolean `MaskColorizer` exists for raster coloring, but no scalar field carries coverage or missingness through heatmap and contour computation (`Display.scala:200-205`). |
| Deterministic backend contract | Ready | Intaglio | `RendererHarness` checks repeated equality, markers, requirements, and backend validation (`RendererConformance.scala:70-123`). |
| JVM/JS SVG and JVM publication rendering | Ready | Intaglio | SVG is shared JVM/JS; Java2D is JVM-only. |

## Important corrections to the initial gap list

### A camera primitive is not required for the first tracer

The initial discussion described camera transforms as wholly absent. Intaglio
does not expose a named `Camera` value, but a root `Viewport` already maps a
native x/y domain into a clipped device frame. Rebuilding this one immutable
wrapper is enough for the first Discourse Atlas pan and zoom.

The app should own a small camera value and lower it into the root viewport:

```scala
final case class AtlasCamera(
  visibleX: Interval,
  visibleY: Interval
)

def cameraViewport(camera: AtlasCamera): Viewport =
  Viewport.unsafe(
    xScale = camera.visibleX,
    yScale = camera.visibleY,
    clip = Clip.On
  )
```

An inverse screen-to-world mapping is still needed for Canvas pointer input,
but it can initially be computed by the app from the same camera and device
rectangle. It does not justify changing Intaglio before the tracer.

### SVG already supplies a viable first picking path

Every named SVG group or primitive has `data-name`. The app can delegate a
pointer or keyboard event from the overlay root, find the nearest named
element, and resolve that marker through the adapter navigation index. This is
appropriate for the Codex SVG overlay and the small Atlas tracer.

The adapter must nevertheless enforce two laws that Intaglio does not:

1. every interactive marker maps to exactly one view identity;
2. marker encoding is injective for the identities in one lowered artifact.

`GraphicsName` itself only rejects blank strings. Duplicate names are valid
Intaglio scenes, so the adapter cannot treat name construction as sufficient
proof of identity.

### Bounds and hit testing are a Canvas concern, not an immediate blocker

Intaglio exposes the fully resolved `DeviceScene`, including device points,
rectangles, clips, rotations, styles, images, and names
(`Device.scala:203-273`). This makes a reusable bounds and hit-test facility
possible without changing backend contracts. A correct implementation must
still account for:

- stroke width and cap/join geometry;
- point shapes and radii;
- text metrics and rotation;
- group clipping and nested rotation;
- image rectangles;
- z-order and duplicate group/mark names.

For the first SVG tracer, browser hit testing is already available. For an
initial Canvas tracer, the narrative compiler can retain exact bounds for its
own regions and landmarks. A generic Intaglio facility should be promoted only
when at least one other consumer needs the same complete semantics.

### Missingness is the scalar-field blocker

`ScalarColorizer` can turn a non-finite scalar into a transparent pixel, but
`ScalarField2D` rejects non-finite values at construction. More importantly,
transparent replacement would not tell the contour algorithm which cells are
unsupported. A story feature field needs value, coverage, and validity to
remain distinct through aggregation and rendering.

The WOG tracer should therefore omit terrain from its first lowering or render
one feature track as explicit supported rectangles plus a separate missingness
mask. It must not feed missing observations to a field as zero. A future
generic API could pair a field with a same-shaped validity mask, but its
contour-boundary semantics need a separate design and law suite.

## Responsibility boundary

### Portable storymodel4s `view` module

Own:

- typed narrative or annotation identity;
- projection contracts and visual laws;
- exact evidence, claims, status, and provenance;
- parent/child LOD choice and selected-ancestor behavior;
- deterministic narrative layout;
- the visible semantic mark set;
- Codex annotation and page-fragment identity;
- textual twins.

They do not import Intaglio.

### Intaglio lowering adapter

Own:

- injective encoding from view identity or fragment identity to
  `GraphicsName`;
- a navigation index from `GraphicsName` back to typed identity;
- lowering of portable marks to grob groups;
- camera-to-root-viewport conversion;
- deterministic polyline tessellation for curves;
- composite arrowheads, hatching, and portal tabs;
- backend selection and renderer error translation.

The adapter never infers a narrative relation or alters epistemic status.

### Application shell

Own:

- exact DOM text and DOM selection;
- pointer and keyboard event delegation;
- camera state and interaction gestures;
- shared selection state between Codex and Atlas;
- measurement-driven visible-set requests;
- page mounting and browser text rectangles;
- accessibility and inspector behavior.

### Intaglio candidates after measurement

Potentially promote:

- named device-space bounds and z-ordered point/rectangle hit testing;
- a reusable screen/world transform with an inverse;
- a scalar field with a coverage mask;
- a cubic path primitive;
- a generic scale-visibility range.

Do not promote `Address` or `ViewRef`, semantic zoom levels, claims, evidence, narrative
tiles, projection contracts, or selection persistence.

## Minimal lowering sketch

This is a boundary sketch, not the final adapter API. ADR 0002 and the reference-seam bead own the
actual `Address` and `ViewRef` identity types.

```scala
final case class LoweredAtlas(
  scene: intaglio.Scene,
  navigation: Map[intaglio.GraphicsName, ViewIdentity]
)

def lowerStoryLevel(
  artifact: NarrativeScene,
  camera: AtlasCamera
): Either[AtlasLoweringError, LoweredAtlas] =
  for
    named <- assignInjectiveNames(artifact.marks)
    grobs <- named.traverse {
      case (name, RegionMark(bounds, style), _) =>
        lowerRegion(name, bounds, style)
      case (name, LandmarkMark(point, style), _) =>
        lowerLandmark(name, point, style)
      case (name, RouteMark(points, style), _) =>
        lowerPolyline(name, points, style)
      case (name, PortalMark(path, style), _) =>
        lowerTessellatedPortal(name, path, style)
      case (name, mark, _) =>
        Left(AtlasLoweringError.Unsupported(mark.kind))
    }
    root = Grob.group(
      grobs,
      viewport = Some(cameraViewport(camera)),
      name = Some(GraphicsName.unsafe("atlas-root"))
    )
  yield LoweredAtlas(
    Scene(Vector(root)),
    named.iterator.map { case (name, _, identity) => name -> identity }.toMap
  )
```

For a Codex page overlay, the same adapter lowers already placed annotation
fragments rather than measuring text:

```text
Placed Codex fragment rectangles
  -> Intaglio bracket, underline, tether, and portal grobs
  -> SVG with data-name = encoded FragmentId
  -> DOM event
  -> navigation index
  -> stable AnnotationId and typed Address/ViewRef target
```

Intaglio does not own the source text, page layout, or DOM ranges.

## WOG tracer acceptance path

The first tracer is explicitly labelled as rendering the researcher-reviewed
WOG narrative acceptance fixture.

### Level 1: story

- three episode regions anchored to discourse support;
- one young-men entity route;
- one battle landmark;
- one paired retrospective portal from the home recounting to the battle;
- exact DOM evidence selection outside the SVG scene;
- SVG marker round trip for every interactive mark.

### Level 2: episode 3 scene detail

- situation marks for reported injury, lack of felt illness, ghost belief,
  recounting, transformation, and death;
- contexts and epistemic status are supplied by the narrative artifact, never
  inferred by lowering;
- selection of the ghost-belief identity persists from the episode ancestor
  to its own situation mark;
- parent containment and discourse anchoring are checked before lowering.

### Required laws at the adapter boundary

1. **Identity injection:** distinct interactive identities receive distinct
   `GraphicsName` values.
2. **Marker preservation:** every navigation marker appears in SVG output.
3. **Navigation totality:** every interactive SVG marker resolves to exactly
   one typed identity.
4. **Order preservation:** grob order matches portable mark z-order.
5. **Camera consistency:** screen/world mapping round-trips within tolerance.
6. **Style preservation:** explicit, inferred, hypothetical, missing, and
   selected states lower to their declared non-color-only channels.
7. **No inference:** lowering cannot add a mark absent from the portable
   artifact.
8. **Backend determinism:** repeated lowering and SVG rendering are equal.

## Benchmark plan

Do not use guessed corpus sizes or a one-shot timing to choose a backend.

### Fixtures

1. the actual WOG story-level and episode-level tracer artifacts;
2. generated viewports at increasing **visible** mark counts, with separate
   region, landmark, path, text, and mixed workloads;
3. a large total artifact whose narrative spatial index supplies the same
   bounded visible mark sets;
4. feature rasters with explicit supported and missing regions;
5. long portal and recall-ribbon paths with recorded tessellation density.

### Measurements

- portable scene compilation time;
- Intaglio lowering time and allocations;
- `DeviceScene` resolution time;
- SVG serialization time and output size;
- Canvas full draw and camera-interaction frame time in the installed project
  browser;
- navigation-index construction and SVG delegated-pick latency;
- later, Canvas bounds-index construction and point/rectangle query latency;
- byte equality across repeated SVG renders.

### Adoption gates

- WOG correctness laws pass before performance is considered;
- ordinary pan and zoom sustains the proposal target of 60 frames per second;
- cached semantic LOD transitions remain under 100 ms;
- visible marks stay within a declared viewport budget;
- a new Intaglio primitive or GPU backend is adopted only when a named workload
  misses its gate and the candidate demonstrably fixes that miss;
- publication output continues to use the same portable artifact and preserves
  the navigation markers and projection contract.

## Immediate next steps

1. Claude completes ADR 0002 and fixes the portable identity/reference shape.
2. This evaluation is checked against that ADR; terminology is updated without
   changing the responsibility boundary.
3. The adapter bead adds the smallest SVG lowering and identity laws. No
   Intaglio source change is required for that step.
4. The WOG two-level tracer is compiled through the portable Atlas scene and
   rendered as deterministic SVG.
5. Only then is Canvas interaction benchmarked and any generic Intaglio gap
   promoted to its own issue.

The present evidence supports dogfooding Intaglio directly. It does not support
either replacing Intaglio or creating a new general visualization layer.
