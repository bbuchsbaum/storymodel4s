# ADR 0002 — Visualization contract: one compiler family, typed projections, no view-side inference

- Status: **Draft, checkpoint 1 + two accepted corrections** (2026-08-28). Deliberated on mote topic
  `narrative-atlas-intaglio` by `claude-storymodel4s` (author) and
  `codex-storyatlas-root` (reviewer/implementer). Owner proposals:
  `docs/plans/2026-08-28-narrative-atlas-proposal.md` (Atlas, rev 0) and
  `docs/plans/2026-08-28-narrative-codex-proposal.md` (Narrative Codex, rev 0).
- Bead: `bd-01M14K851VRNCCDG3VS2KT4HHQ`. Depends on ADR 0001 only for the
  Local Semantic Field (embedding `GeometryId`, ADR 0001 P0-1).
- Supersedes nothing. Constrains every future visualization module.

## 1. Context

storymodel4s makes the exact surface text the observational coordinate system
and requires traversal from words to windows to narrative units and back
(mission commitments 1–2). Two owner proposals describe the instrument that
realizes this: the **Narrative Atlas** (navigable, multi-projection map) and
the **Narrative Codex** (paginated, model-annotated edition). Both are
canonical views. Neither is a graph viewer, a global embedding plot, or a
dashboard.

The deliberation established that the proposals are right in their governing
ideas and wrong in three recurring ways: they re-sketch vocabularies the model
already has; in several places a projection would *manufacture precision* the
artifact does not contain (mission commitment 6); and the engineering stack is
sized for a corpus browser when the first slices need a single-story static
artifact. This ADR fixes the contract that prevents all three.

Two facts about the current repository shape the decisions:

- Every module is `crossProject(JVM, JS, Native)` with `CrossType.Pure`.
  Module graph (from `build.sbt`):

  ```text
  core
  ├── proposition ──┐
  ├── features ─────┤
  ├── acquire ──────┤  (acquire: core, proposition)
  └── story ────────┤  (story: core, proposition, features)
       ├── document │  (document: core, proposition, features, acquire, story)
       ├── recall ──┤  (recall: core, proposition, features, story)
       │    └── align  (align: core, proposition, features, story, recall)
       │         └── interview
  codec, laws: core, proposition, amrInterop, features, acquire, story, recall, align, interview
  (note: codec and laws do NOT depend on document today)
  ```
- Intaglio (workspace sibling, `io.github.canardlapin`, source-only, JVM+JS,
  no Native) provides one renderer-neutral `Scene` with Canvas/SVG/Java2D/
  JavaFX backends under a conformance contract.

## 2. Decisions

### D1 Canonical means semantic source flow and identity, not page numbers
The canonical Codex artifact is an annotation-bearing **source flow** over
`canonicalText` offsets. Pages, lines, rectangles, and fragments are derived
presentation geometry. Reflow changes geometry, never identity.

### D2 Codex and Atlas are sibling artifacts of one compiler family
```scala
enum ViewArtifact:
  case Codex(flow: CodexFlow)        // exact source runs + semantic annotations
  case Atlas(scene: NarrativeScene)  // placed geometric marks under a ProjectionContract

trait ViewCompiler[Spec, Out]:
  def compile(model: StoryModel[Validated], state: CommonViewState, spec: Spec): Either[ViewError, Out]
```
Both consume the same `CommonViewState` (selection, focus, epistemic horizon,
active relation layers, feature derivation, uncertainty policy, comparison
policy, audit trail). `AtlasSpec` adds projection, camera, level of detail;
`CodexSpec` adds reading mode, pagination policy, annotation-lane policy.
Codex↔Atlas synchronization is therefore true by construction: both compile
from one selection and one visibility result. `TextAnnotation` is **not** a
`VisualPrimitive`; typography is not a coordinate projection.

### D3 Pagination consumes a complete, receipted text-layout capability
```scala
trait TextLayoutCapability:      // measurement + line-breaking contract, not advance widths alone
  def receipt: LayoutReceipt      // font asset hashes, fallback, shaping engine+version, hyphenation/bidi/language policy, page box
  def layout(flow: CodexFlow, spec: PaginationSpec): Either[LayoutError, PlacedCodex]
```
`CodexFlow → PlacedCodex` is a separate step from compilation. The browser
adapter implements it over live DOM geometry (fonts awaited, measured after
layout, invalidated on reflow) and guarantees **identity stability only**.
Publication mode designates one owned deterministic layout backend and
guarantees stable pages under a full `LayoutReceipt`. Continuous scroll and
fixed edition are two layouts of one `CodexFlow`.

### D4 No view creates a claim absent from the model ledger (the no-inference law)
A view compiler renders the artifact's actual epistemic state. If a projection
needs a quantity the model lacks, it renders the lack. Specific consequences:

| Projection need | Artifact state | Rendered as |
|---|---|---|
| total world-time coordinate | `TemporalEdge` Allen relations per `ContextId`; Hasse cover | layered poset layout (rank = longest path in cover); `Overlaps/During` as intervals; `WorldTimeTransition.Unresolved` as a fan; incomparable events ordered by discourse position with legend "layout, not data"; one loom per narrated-world context, embedded contexts (`Speech/Belief/Memory…`) as insets anchored to their holder situation |
| discourse x of a unit with discontinuous `SpanSet` | `NarrativeGraph.discoursePosition` (first span) | anchor at first span; further spans as portals typed by `NarrativeReference` / `ProjectionMode` |
| event underline extent | `ProjectionIndex.situationSources` with `ProjectionMode` | underline only `DirectMention / EventRealization / StateRealization` sources; `Prospective/Retrospective/Summary/Inferred` render as typed links |
| entity present but unnamed; pronoun vs name | no presence claim; no mention-form field | not drawn (gap beads `bd-01M14K85CDPPTSBXG4VTXJ9P4R`) unless a `DescriptorClaim`/presence claim exists |
| scene boundary | `NarrativeHierarchy.containment` vs unselected `BoundaryBelief` | accepted containment: solid with probability shown; unselected belief: ghost/dashed, never labelled "scene" |
| context band over a passage | `ContextFrame.support` may be discontinuous | bands distinguish exact scope evidence, contextual membership, and inferred continuation; never a hull-synthesized contiguous band |
| reader-at-time *t* | ledger with `Evidence.upstream` | horizon = transitive evidence closure over `ClaimLedger`, computed only by `view.EvidenceVisibility` (shared by Codex and Atlas — no compiler re-derives it); `CorefPartition` restricted to mentions ≤ *t*; windows crossing *t* are `SurfaceWindow.complete = false` (partial, not a value); the boundary at *t* is "not yet assessable"; later reinterpretation is a second `ClaimMeta`, never mutation |
| population recall map | per-recall `AlignmentMatrix` only | not drawn until a cross-subject aggregate type exists (`bd-01M14K85E0D4GS0CTFBNYZJENH`) |

### D5 Narrative Codex is slice 1; the WOG two-level Atlas tracer is the interaction boundary experiment
The static Codex edition of *The War of the Ghosts* is the first acceptance
artifact (it absorbs the M1 `inspect` HTML). Narrative annotations come only
from the researcher-reviewed narrative acceptance fixture or a validated
acquired model, labelled as such on the page. The tracer (two levels, one
thread, one portal, one transition with selection persistence) exists to
discover which interaction mechanics Intaglio must grow; it does not outrank
the Codex.

### D6 DOM exact text + Intaglio SVG overlay is the initial backend
DOM owns prose, selection, copy, search, accessibility. Intaglio SVG owns
gutters, brackets, underlines, arcs, portals, context bands, uncertainty marks,
export and figures. Intaglio Canvas owns dense scalar underlays **only after a
benchmark shows need**. Intaglio never owns canonical text, pagination truth,
DOM selection, model semantics, or inference. The app talks to renderers
through a minimal protocol (§6); no story concept enters Intaglio.

### D7 deck.gl, tiles, GPU, Arrow IPC, ELK/MSAGL, and a general 2D layer are benchmark-triggered, not defaults
Rationale recorded in §7. Trigger = a measured failure of the owned backend
against the mark budget on a novel-length model or population recall data,
recorded as a review artifact.

### D8 One reference seam, assembled at the top (the A4 correction)
A closed universal reference ADT cannot live in `core` or `story`: it would
have to name `features`, `recall`, and `align` types, reversing existing edges.
Instead (§4): `core` defines an **address protocol**; each module defines its
own typed reference and an instance; `view` and `codec` assemble the closed
coproduct where every module is visible. Concrete wire encoding is an explicit
checkpoint (§9) until the codec round-trip is demonstrated.

### D9 Uncertainty is five typed states, never one "fog"
`Estimate.Missing(reason)` · `Credence` with an uncalibrated or unmeasured
score (ADR 0010: `score = Unmeasured | Raw(v, scorer)`, `basis = Uncalibrated |
Determined(rule)`) · calibrated `Probability` (`basis = Calibrated(p, model)`) ·
`Resolved.alternatives` / `ResolutionState.Alternatives` ·
`ResolutionState.Unresolved`; plus align `Exclusion`. Each has its own mark
(§5, V-U laws). Raw scores never share a visual scale with probabilities.
`EpistemicStatus` has six values and each is distinguishable by a non-colour
channel.

### D10 Recall visual vocabulary is generated from `AlignState`
Off-map provinces = the six `ExternalState`s. Ribbon width = row source mass;
sharpness = `localizability(K)`; branching = `topK` above τ; gist = mass on a
`Segment` ref; backtracking = backward transitions in `TransitionFlow`;
omission = `columnMass` below threshold; elaboration = recall-side
`ElaborationEdge`. Law: no visual category without a type in `align`.

### D11 Feature colour follows `FeatureTarget` and `FeatureDerivation` exactly
Token → word underlay; Sentence → sentence step-wash; `Window(TokenRange)` →
one resampled scalar field with a declared sampling rule (no alpha-stacking of
overlapping windows); Situation/Segment → field over the `SpanSet`. The scale
control is the shared `FeatureScale`: `SurfaceUnit(Token | Sentence | Clause |
Paragraph)` or `NarrativeUnit(Situations | Segments(Scene | Episode | Story))`.
It selects only the exact checked `FeatureTarget` case and kind; it never coerces
paragraph observations into sentences or situations into segments. Windowed
scales arrive only with a real `WindowBasis` and named `FeatureDerivation`; no
visual smoothing occurs without a `Kernel` reducer in the receipt. Coverage and
missingness are separate masks, and every displayed aggregate carries its
`FeatureUseLedger` circularity status.

### D12 Every artifact has a textual twin
`ViewArtifact → String` is deterministic and total. It is the screen-reader
form, the snapshot-diff form across model versions, and the JVM/Native test
form of every compiler.

### D13 Layout is a pure function of `(model identity, CommonViewState, spec)`
Model identity = `BuildReceipt.contentChecksum` when a receipt exists; for the
researcher-reviewed fixture it is the source checksum plus the `ViewBasis`
label (`ViewProvenance.modelReceiptChecksum` stays `Option` and explicit —
never aliased to the source checksum). A true `StoryModel.contentChecksum`
arrives with codec canonical JSON (codec milestone §2).
Any non-deterministic or JS-only layout is an experimental artifact, seeded,
versioned, receipted, and excluded from publication paths. Start with the
simplest deterministic constraint layout that passes the laws; adopt
Sugiyama/StoryFlow-style algorithms only behind golden and property tests.

Range loading under D7 uses codec `SM4SFT02`, one blocked monolith whose blocks
contain complete compact observed rows. Storage blocks are deliberately not
semantic `(projection, level, tokenRange)` tiles: a target range can be sparse
after explicit missingness, and one sidecar serves both Codex and Atlas, so a
projection change must not rewrite storage. `FeatureRef.row` remains global and
Missing consumes no row; a resolver maps the required rows to derived block
ordinals and may coalesce adjacent byte ranges above the portable codec. The
trusted manifest, never the fetched file, supplies the checksum of the exact
header/digest-index prelude. Each independently verifiable block digest binds
its ordinal, first row, row count, and bytes; offsets and lengths are derived
from checked shape/granularity fields and are never read from a table. The
whole-file checksum retains its original meaning. Re-blocking is a physical
rewrite with a different sidecar and containing model identity, even when the
logical values are unchanged. Plain checksums provide integrity relative to a
trusted manifest only, not authenticity, confidentiality, or a safe non-public
receipt identity.

The Codex compiler configuration uses the canonical rendering
`codex-compiler-config/v2`. It is a `|`-delimited sequence of `key=value`
fields in this exact order: `rendering`, `horizon`, `focus`, `feature`, `scale`,
`selection.count`, indexed selections, `relation.count`, indexed relations,
`channel.count`, indexed channels, `budget.annotationKinds`,
`budget.relationLayers`, and `lanes.maxPerKind`. Selections are sorted by
canonical `Address.render`, relations by enum name, and channels by the checked
`CodexSpec` order. In both keys and values, escaping is applied left to right:
`\` becomes `\\`, `|` becomes `\|`, LF becomes `\n`, and NUL becomes `\0`.
Horizon values are `omniscient` or `reader:<nonnegative-offset>`; feature
values are `none`, `raw:<space-id>`, or
`derived:<derivation-hex>:basis:<basis-hex|none>`; scale values are
`surface:<surface-kind>` or `narrative:<narrative-basis>`; and each
`channel.i` value is `<annotation-wire-name>:<priority>`.
`CodexCompiler.configurationChecksum` hashes exactly this rendering. Any field,
ordering, or escaping change requires a new rendering version.

The Atlas configuration uses the same escaping and the canonical rendering
`atlas-compiler-config/v2`. Its exact field order is `rendering`, `horizon`,
`focus`, `feature`, `scale`, `selection.count`, indexed selections,
`relation.count`, indexed relations, `zoom.narrative`, `zoom.surface`, `threads`,
and `projection`. Horizon, focus, feature, scale, selection, and relation values
use the exact Codex grammars above. `zoom.narrative` is
`story | episode | scene | event`;
`zoom.surface` is `hidden | sentences | tokens`; `threads` is `selected` or
`all:<positive-int>`; and the only current projection value is
`discourse-atlas`. `AtlasCompiler.configurationChecksum` hashes exactly this
rendering; changing any field, order, grammar, or escaping requires a new
version.

### D14a `SurfaceDetail` is compiled into the scene, not projected by the renderer

**Decision.** `storymodel4s.view` compiles surface primitives into `NarrativeScene.marks`.
`SurfaceDetail` is **not** a renderer-layer projection that a consumer applies to `SurfaceAtlas`
itself.

**Why, and the argument is not aesthetic.** `EvidenceHorizon` is "the one evidence-horizon
computation shared by every view compiler" (D4, row 7) and it lives in `view`. `V-L1` (every child
has a visible ancestor at a coarser level), `V-L2` (a selected address survives as `OnMark`,
`ViaAncestor` with a descent path, or `OffProjection`) and `selectionPlacements` are all properties
of `NarrativeScene.marks`. **Marks a renderer adds on its own are covered by none of them.** A
consumer-side projection could therefore render a token belonging to a narrative node the horizon
hides — the precise leak class already fixed once when the Atlas ancestor climb could reveal a
placement reachable only through horizon-hidden evidence.

**This does not weaken the no-inference law (D4).** `SurfaceUnit(id, kind, span, ordinal, parent)`
is canonical, already carries stable ids, and already carries the parent chain. Compiling it is
*selection and placement of existing units*, not derivation of new claims. A surface mark asserts
nothing the `SurfaceAtlas` did not already assert.

**Acceptance.** Until surface marks are compiled, `SurfaceDetail` must not be exposed as a control.
Measured on `main` at the time of this decision: compiling the same scene at `Hidden`, `Sentences`
and `Tokens` yields identical marks (83, 83, 83), identical navigation and identical
`selectionPlacements`, but **three distinct configuration checksums**. Exposing a selector over an
inert axis would mint three identities for one representation and look like the feature had
shipped. The axis is receipted but semantically inert, and a receipt that distinguishes what is not
different is the mirror of every defect this project has been correcting.

### D14 Module placement
- `view` (new, portable, cross-built JVM/JS/Native, depends on all domain
  modules, **not** on codec, DOM, Intaglio, or any JVM-only layout): owns
  `CommonViewState`, `ViewArtifact`, `CodexFlow`, `NarrativeScene`,
  `ProjectionContract`, annotations, audit records, lane allocation, layouts,
  laws. No module is named `codex` (collides with `codec`) or `scene`/`folio`.
- `codec` gains `dependsOn(view)` to serialize view state, specs, artifacts,
  and receipts. View compilation never depends on codec.
- `storyatlas4s` (sibling repo, later, git-SHA pinned per workspace policy):
  Laminar app, DOM text rail, `TextLayoutCapability` over DOM, Intaglio
  adapters. Scaffolded only when the tracer needs it. storymodel4s never
  depends on it.
- Intaglio changes: only mechanisms proven generic by the tracer (§6).

## 3. Type contract (as implemented in `view` at checkpoint 1; normative)

```scala
// view/ref.scala — the closed coproduct (D8)
enum ViewRef:
  case Core(CoreRef); case Feature(FeatureAddress); case Story(StoryRef)
  case Doc(DocRef);   case Recall(RecallRef);       case Align(AlignRef)
  def address: Address                      // via each module's Addressable
object ViewRef: def parse(a: Address): Option[ViewRef]   // dispatch on tag

// view/codex.scala
object AnnotationId extends OpaqueId       // content-addressed from (target, kind, support); priority excluded
enum AnnotationKind(wireName)              // Feature | Hierarchy | Entity | Relation | Context | Claim | Recall — channel families, not a relation ontology
opaque AnnotationPriority = Int in [0, 1000]
enum ViewBasis                             // ValidatedBuild | HumanAdjudicated | ResearcherReviewedFixture (D5 label)
final case class ViewProvenance private (sourceChecksum, modelReceiptChecksum: Option[Checksum], basis, compilerVersion, configChecksum)
  // ValidatedBuild/HumanAdjudicated REQUIRE a receipt checksum; fixture may omit it; never aliased to the source checksum
final case class AuditRecord private (upstream: Vector[Address] /* sorted, distinct */, provenance: Provenance)
final case class SourceRun private (span: TextSpan)          // nonempty; runs tile canonicalText (V-T1)
final case class TextAnnotation private (id, target: Address, support: SpanSet, kind, priority, audit)
  // construction rejects targets outside ViewRef and empty support; no status field (D9); no copied text
final case class NavigationIndex private (byTarget: Map[Address, Vector[AnnotationId]], targetByAnnotation,
  ancestorsByTarget: Map[Address, Vector[Address]])
  // exactAnnotationsFor never falls back; annotationsFor resolves exact first, then the nearest
  // visible annotated primary ancestor
final case class CodexFlow private (source, runs, annotations /* sorted by (minSpan, kind, -priority, id) */,
  lanes: LaneAllocation, navigation, selectionPlacements: Map[Address, SelectionPlacement[AnnotationId]],
  contract: CodexContract, provenance)
  // CodexFlow.of validates: provenance.sourceChecksum == source.canonicalChecksum; runs tile with no gap/overlap and
  // no cut code point; every support span in-text and on code-point boundaries; unique AnnotationIds; because an
  // externally constructed flow has no model hierarchy, its missing targets are OffProjection, never ViaAncestor
  def textualTwin: String                                    // deterministic (V-D2); a rendering, not an artifact

// view/compiler.scala + view/atlas.scala
final case class CommonViewState private (selection: Set[Address], focus: Option[Address], horizon: EpistemicHorizon,
  relationLayers: Set[RelationLayer], feature: Option[FeatureSelection] /* Raw(FeatureSpaceId) | Derived(derivationId: Checksum) */)
enum FeatureScale: case SurfaceUnit(kind: SurfaceUnitKind); case NarrativeUnit(basis: NarrativeUnitBasis)
final case class AtlasFeatureLayer private (scale: FeatureScale, state: FeatureChannelState,
  observations: Vector[FeatureObservationPlacement] /* exact horizon-clipped SpanSets + audit; no numeric values */)
final case class ProjectionContract(kind: ProjectionKind, x: AxisMeaning, y: AxisMeaning, distance: DistanceMeaning,
  area: Option[MeasureMeaning], legend: Vector[ChannelMeaning], invariants: Set[VisualInvariant])
enum SelectionPlacement[+Mark]: case OnMark(marks: NonEmptyVector[Mark]); case ViaAncestor(ancestor: Address); case OffProjection
final case class NarrativeScene(contract, zoom, state: CommonViewState,
  featureLayer: AtlasFeatureLayer,
  marks: Vector[VisualPrimitive] /* Region | Landmark | Thread | Portal | Route, each with VisualIdentity(address, level, MarkId) */,
  navigation, selectionPlacements: Map[Address, SelectionPlacement[MarkId]], provenance)
object EvidenceVisibility:
  validateHorizon(text, horizon); visibleClaims(offset, ledger); visibleUnder(horizon, ledger)
  clipSupport(support, horizon); stateParts(state)   // the single horizon implementation
final case class ZoomLevel(narrative: NarrativeLevel, surface: SurfaceDetail)   // two axes, not one linear scale
```

Checkpoint-1 compilers accept `StoryModel[Validated]`; an adjudicated entry
point remains a later, explicitly typed extension. The Atlas gained a second,
explicitly typed entry point in the V0 amendment below; the Codex has none yet.
Evidence law for compiled annotations (V-E3):
`annotation.support == horizonRestrict(model.supporting(target), horizon)` for
narrative-object targets (`horizonRestrict` is the identity when omniscient and
drops spans ending after *t* under `ReaderAt(t)`; equality in both modes);
the object's claim and evidence addresses go in `audit.upstream`; a
`core/claim/…` target is reserved for claim-marker annotations.

## 4. Reference seam and dependency proof (D8)

```scala
// core — knows no upper type
opaque type ModuleTag = String                      // smart-constructed, registry-checked unique
final case class Address(tag: ModuleTag, kind: String, key: AddressKey)  // AddressKey: canonical, no copied text
trait Addressable[A]:
  def tag: ModuleTag
  def address(a: A): Address
  def parse(addr: Address): Option[A]               // total on own tag, None otherwise

// each module — its own closed typed ref + one instance
// core:     CoreRef    = SurfaceUnit(id) | Tokens(TokenRange) | Span(SpanSet) | Claim(id) | Evidence(id)
// features: FeatureAddress = Obs(space, target) | Derivation(id) | Boundary(afterUnit)
// story:    StoryRef   = Situation | Segment | Entity | Context | Edge(layer, from, to, context?)
// document: DocRef     = Mention(ChartNodeRef) | Cluster(CanonicalId[K])
// recall:   RecallRef  = Unit(id) | Edge(...)
// align:    AlignRef   = Cell(unit, AlignState) | Flow(from, to)

// view (sees everything) — the closed coproduct
enum ViewRef:
  case Core(r: CoreRef); case Feature(r: FeatureAddress); case Story(r: StoryRef)
  case Doc(r: DocRef);   case Recall(r: RecallRef);       case Align(r: AlignRef)
object ViewRef:
  def parse(a: Address): Option[ViewRef]   // dispatch on tag
```

**Proof of acyclicity.** The only edges introduced are `X → core` for the
`Addressable[X]` instances (edges that already exist for every module) and
`view → X` for the coproduct (new module, no in-edges except from `codec`,
`laws`, `fixtures`). No existing edge is reversed; `core` gains no dependency;
`story` still cannot name `recall`/`align`. `codec` serializes `Address`
(universal) and re-types at the `view` boundary through `ViewRef.parse`.
Laws: per-module `parse(address(x)) == Some(x)`; cross-module tag uniqueness;
`ViewRef.parse` total over the union of module tags; `Address` canonical string
round-trips through codec.

`story.Violation` now carries `address: Option[Address]` resolved from its
component path (positions and unknown ids honestly stay `None`);
`acquire.TargetRef` migrates when the critic protocol is next touched (acquire
cannot name `StoryRef`, so it will carry a `core.Address` re-typed in `view`);
the CLI `inspect` selector is an `Address` from the start. The **read API**
lands beside the data it reads: `NarrativeGraph.covering(span)`,
`NarrativeGraph.supporting(ref)`, `FeatureTrack.restrict` (exists),
`ProjectionIndex.byChartNode` (exists); `view` composes, never duplicates.
The existing `story.AlignmentSource` / `align.SourceView` duplication is
resolved in the same bead (one read contract).

## 5. Visual laws (normative; tested on generated models and the WOG fixture)

Projection — **V-P1** discourse: `d(a) < d(b) ⇒ x(a) ≤ x(b)`; **V-P2**
hierarchy: `ChildOf(a,b) ⇒ R_a ⊆ R_b`, containment by construction (parent
region = hull of children at the finer level); **V-P3** chronology:
`Before(a,b) ⇒ rank(a) < rank(b)` with no converse implied; **V-P4** no
projection implies a distance semantics its contract does not declare; **V-P5**
the page carpet declares that row breaks and 2-D adjacency are layout only.

Identity — **V-I1** one `Address` per narrative object across zoom levels,
projections, lenses, comparisons, and Codex↔Atlas; **V-I2** `AnnotationId`
survives reflow; fragments derive ids from `(AnnotationId, page, line)`;
**V-I3** selection is `Set[Address]`, never per-view ids.

Evidence — **V-E1** every interpretive mark resolves to a claim, its evidence,
its `EpistemicStatus`, and build provenance; **V-E2** exact text → mark → exact
text round trip; **V-E3** no mark on text that does not support it (D4 rows
2–3).

Text — **V-T1** coverage: runs tile `canonicalText` with no gaps, overlaps, or
cut code points; **V-T2** DOM round trip: concatenated text nodes equal
`canonicalText`; `TextSpan ↔ DOM Range` round-trips under randomized Unicode
fixtures; wrappers insert no characters; **V-T3** no copied canonical text in
any artifact.

Level of detail — **V-L1** every child has a visible ancestor at a coarser
level; **V-L2** `SelectionPreserved`: every selected or focused address stays
in `CommonViewState` and has exactly one typed placement — `OnMark` when it has
ordinary marks, `ViaAncestor` only when the object's own claim is horizon-visible
and it has a marked primary ancestor reachable through an entirely horizon-visible
primary-parent chain; traversal truncates at the first hidden ancestor.
`OffProjection` therefore covers both an off-horizon object and a horizon-visible
object with no reachable ordinary mark; the textual twin's `Horizon` line distinguishes
the active evidence boundary rather than inventing a second placement state. The shared
`HorizonShared` invariant requires Codex and Atlas to use only
`EvidenceVisibility`. `CodexFlow.selectionPlacements` uses annotation ids for
`OnMark`; `NavigationIndex.exactAnnotationsFor` never climbs, while
`NavigationIndex.annotationsFor` performs the same exact-then-nearest-visible-ancestor
resolution; **V-L3** label priority monotone; **V-L4** bounded
visible mark count; **V-L5** lane allocation deterministic with bounded overflow,
never dropped annotations.

Uncertainty and style — **V-U1** missingness is a distinct mask, never zero
and never faded; **V-U2** raw and calibrated quantities never share a scale;
**V-U3** alternatives are a fan/split, unresolved an explicit placeholder;
**V-U4** accepted-low-probability ≠ unselected hypothesis; **V-U5** relation
type and epistemic status are never colour-only; **V-U6** arrows only for
directed relations, causal-looking arrows only for `CausalRelation`; **V-U7** a
feature wash covers exactly its `FeatureTarget` support; no interpolation
without a kernel in the receipt; **V-U8** circularity status travels with every
displayed aggregate.

Determinism — **V-D1** layout and textual twin are pure functions of
`(contentChecksum, CommonViewState, spec)`; **V-D2** every artifact has a
deterministic textual twin; **V-D3** saved views record model hash, spec,
contract, camera, filters, normalization, selection, software version, and
(for placed pages) the `LayoutReceipt`.

Recall — **V-R1** every visual category in a recall view corresponds to an
`AlignState` case or a named derived quantity of `AlignmentMatrix` /
`TransitionFlow` / `RecallSignature`.

## 6. Renderer protocol and Intaglio boundary

The app depends on a protocol of about six operations, not on Intaglio:

```scala
trait Renderer[Plate]:
  def lower(artifact: ViewArtifact, placed: Option[PlacedCodex]): Either[RenderError, Plate]
  def boundsOf(plate: Plate, id: GraphicsName): Option[Bounds]
  def hitTest(plate: Plate, at: Point | Rect): Vector[GraphicsName]     // z-ordered
  def withCamera(plate: Plate, camera: Affine): Plate
  def visible(plate: Plate, viewport: Rect): Vector[GraphicsName]
  def export(plate: Plate, format: ExportFormat): Either[RenderError, Bytes]
```

`GraphicsName` is derived from the rendered **mark or fragment identity**
(`AnnotationId`/`MarkId` + fragment), not from `Address`: one `Address` yields
many annotations, placed fragments, and Atlas marks, so `Address` is not
injective within one rendered artifact. The `NavigationIndex` maps
`GraphicsName → Address` many-to-one; semantic selection stays `Set[Address]`.
Marker identity never enters the portable `CodexFlow` foundation.
(Checkpoint-1 correction by codex-storyatlas-root, accepted.) Candidates for promotion into Intaglio **only when the tracer proves
them**: name-keyed device bounds, hit-test, Scene-level affine camera,
viewport culling, min/max-scale visibility on grobs, smooth path primitive,
scalar field with coverage mask. Never: `Address`, claims, contexts, clocks,
projection kinds.

## 7. Libraries (D7 rationale)

- **deck.gl — no** before slice 4, and not by default then. Scale mismatch (a
  viewport under the mark budget holds thousands of marks; Canvas 2D suffices
  at 60 fps); poor fit for nested labelled regions, brackets, collision-aware
  arcs, parent→children morphs; SDF text weaker than DOM; per-layer colour
  picking; no JVM path, so figures and law tests would run on a different
  renderer than the view; a Scala.js facade over deck.gl + luma.gl would be the
  largest, most fragile dependency in the workspace. If a benchmark fails, the
  first response is an owned WebGL backend for Intaglio behind §6.
- **Laminar — yes** (Airstream signals fit `CommonViewState`).
- **ELK / MSAGL — no** in the portable compiler (JS-only, nondeterministic
  unless seeded). Portable deterministic layouts only.
- **Arrow IPC — later**; JSON via codec + typed numeric sidecars first.
- **graph4s** — JVM-only pin (ADR 0001 D4a); cannot enter `view`.
- **mesh4s** — no demonstrated geometry need for slices 1–3.
- **Tiles / Web Workers** — the Discourse Atlas index is one-dimensional
  `(projection, level, tokenRange)`; keep a tile-compatible boundary, build no
  pyramid; semantic tiles map sparse `FeatureRef` rows to independently checked
  `SM4SFT02` storage blocks as specified in D13, never define storage identity;
  workers only if a layout exceeds ~50 ms after caching.

## 8. Sequencing

**Slice 1 — Narrative Codex (static edition of WOG).** (1) exact selectable
DOM source with offset round trips; (2) pure `CodexFlow` compiler and
inspectable static HTML artifact (textual twin first); (3) one fixed-edition
`PaginationSpec` plus continuous-scroll layout; (4) event/scene/episode
fragments only from validated data; (5) one feature derivation with scale
recipes, coverage, missingness, circularity audit; (6) entity mention
navigation without between-mention presence; (7) claim/proposition inspector
with local evidence highlighting; (8) reader-horizon build vs omniscient build;
(9) one typed nonlocal relation as paired portal tabs; (10) saved
`CommonViewState` + Codex, layout, and model receipts.

**Boundary experiment — WOG two-level Atlas tracer** (Codex): Story level with
three episode regions, one entity thread, one retrospective portal; Scene level
of episode 3 with `ContextFrame`s; the transition tests V-L2, V-P2, V-I1 and
produces the Intaglio gap report.

**Slice 2 — semantic-zoom Atlas** (after M2 hierarchy induction). **Slice 3 —
structural projections** (Loom as poset layout; Story-World; Causal/Goal;
epistemic playhead; model comparison). **Slice 4 — Recall Voyage** (needs the
cross-subject aggregate type). The task-based user study in the Atlas
proposal §15 is a paper, not a gate.

**Codec milestone** (prerequisite for any browser/static consumer, bead
`bd-01M14K855QC1VB6H17S4W9FCS2`): encode `StoryModel[Validated]` with feature
sidecars and `HsmmResult`; encode `Address`, `CommonViewState`, specs,
`ViewArtifact`, receipts; laws: `decode(encode(x)) == x` and
`compile(decode(encode(m)), s, spec) == compile(m, s, spec)`.

## 9. Checkpoints and open questions

1. **Reference encoding** (D8): concrete `AddressKey` grammar and `codec`
   round-trip demonstrated before `TargetRef`/`Violation.path` migrate.
2. **`WindowBasis.Events` and a surface-unit `FeatureTarget`** before the
   scale selector offers event or clause recipes.
3. **Mention form** and **presence claims** (document gaps) before any entity
   lens styles pronouns or draws between-mention activity.
4. **Cross-subject aggregate** (align gap) before any population view.
5. **Local Semantic Field** anchors must be the aligner's retrieval candidates
   and declare the ADR 0001 `GeometryId`.
6. **Benchmark protocol** for D7 triggers: mark budget, novel-length model,
   hundreds of recall overlays; defined with the tracer, not guessed.
7. **Sidecar resolution:** numeric sidecars use `codec.SidecarTrack[T, V]`; an Atlas resolver
   materializes checked blocks without teaching `NarrativeScene` about binary storage.

## 10. Consequences

- The Atlas and Codex proposals remain the design record; their type sketches
  (`NarrativeScale` as one linear enum, `SemanticLink`, `AnnotatedPages` from
  the compiler, `VisualIdentity.pickId`, `EvidenceRef` fields on annotations)
  are superseded by §3–§4. Rev 1 of each proposal will mark these explicitly.
- Every visualization claim in a paper is reproducible from a saved view and
  a `LayoutReceipt`, on the JVM, without a browser.
- Three stringly-typed addressing schemes collapse into one.
- Nothing spectacular is rendered that the ledger cannot defend.

## 11. Amendment — V0: the draft Atlas path (2026-09-02)

Decided by the owner's agent in single-developer mode (AGENTS.md SD5: the
requirement was always the written record, not the approval). Implements the
first half of `docs/plans/2026-09-03-visualization-recovery-plan.md` §V0.

### A1 The Atlas compiles a draft, and a draft scene cannot pass for a validated one

**The problem.** `AtlasCompiler.compile` took `StoryModel[Validated]`. The model
the pipeline actually builds from real text does not validate: replaying the
fifty captured War of the Ghosts recordings with no caller-supplied title
produces 70 derivation gaps and 135 violations, because with no established
title the `Summary` family abstains, no story segment is derived, and 65 segment
memberships lose their upstream. So the viewer had never rendered a
machine-built model and could not.

**The alternative rejected.** Relaxing the title or summary rule so the model
promotes. That moves the falsehood out of the picture and into the artifact,
which is the exact defect the 2026-09 slice removed. A researcher needs to see a
partial model *and* see where it is partial.

**Decision.** `AtlasCompiler.compileDraft(draft: DraftModel, state, spec)` takes
a `StoryModel[Draft]` bound to the exact evidence of its own incompleteness:

```scala
enum DerivationRecord:
  case Reported(gaps: Vector[DerivationGap], coverage: Vector[SentenceCoverage])
  case NotSupplied

final class DraftModel private (model, promotion, derivation, violations)
object DraftModel:
  def of(model: StoryModel[Draft], outcome: ValidationOutcome,
         derivation: DerivationRecord): DraftModel
  def withoutDerivationRecord(model: StoryModel[Draft], outcome: ValidationOutcome): DraftModel
```

`of` sorts every vector, so a scene stays a pure function of its inputs (V-D1)
whatever order a caller assembled them in.

**The model does not carry its own gaps, and must not.** A derivation gap is a
statement about the *derivation*, not about the story; a `StoryModel` that
recorded claims about its own construction would be the wrong shape. The
narrative compiler writes the gaps and the coverage ledger to the sibling
`compilation-report.json`, and the view is entitled to consume both artifacts.
That is why `DraftModel` binds a model to a record rather than reading one out
of the model.

**`NotSupplied` is not an empty `Reported`.** A consumer holding only a decoded
`storymodel.json` — the storyatlas4s case — has no derivation record at all.
Without the distinction its receipt reads "0 derivation gaps", which says the
compiler derived everything, when the truth is that nobody told the view
anything. `DraftPromotion.gapCount` is therefore `Option[Int]` in which `None`
is never `Some(0)`, and the textual twin prints "derivation gaps: record not
supplied".

**The two inputs give different, both-honest violation sets, and the receipt
names the laws rather than labelling the source.** Measured on the real model:
the compilation's own outcome has 135 violations across three laws; re-validating
the same draft on its own has 66, all hierarchy laws, because the 69
`compiler.required-derivation` violations only the narrative compiler can raise.
A field naming *which validator ran* was considered and rejected: `ValidationOutcome`
carries no provenance, so that field could only be caller-asserted, which is the
fabricated-license defect. The named laws in the receipt are derived and make the
difference visible in the data.

The statement that a scene is a draft lives in the receipt, not in a flag.
`ViewBasis` gains `DraftBuild`, and `ViewProvenance` gains
`draft: Option[DraftPromotion]` under a biconditional its constructor enforces:
a promotion record is admitted **exactly when** the basis is `DraftBuild`, and
required there. No receipt can therefore read "validated build" beside a
promotion record, and none can read "draft build" while staying silent about
which laws went unsatisfied. `DraftPromotion` is a `final` non-case class whose
`promoted`, `unsatisfiedLaws` and `gapCount` are **derived** from the outcome by
`DraftPromotion.from`; a caller who could state them could publish a scene
declaring a clean promotion over a model with none.

`compile` refuses a `DraftBuild` receipt and `compileDraft` refuses any other,
so the two paths cannot be swapped; `compileDraft` also refuses a receipt whose
promotion does not describe the bundle in hand.

A draft build has **no `BasisAuthority`** and so can never become an
`AdmittedViewBasis`: it is legible, cited and reproducible, and it is not
admissible as the basis of a scientific output. The codec round-trips the tag
(`draft_build`) rather than dropping it, so an untrusted wire claim naming it is
refused downstream by re-derivation against the acquisition account, not made
unreadable here.

### A2 Absence is a mark, and D9's states carry a non-colour channel

Three new `VisualPrimitive` cases, one per kind of recorded absence:
`Gap` (a derivation the compiler attempted and could not make, carrying the
typed `ClaimFamily`, `NarrativeCandidateAddress` and `DerivationGapReason`),
`Abstention` (a sentence that admitted no situation root, carrying the
provider's own reason), and `UnsatisfiedLaw` (carrying the validator's own
`story.Violation`).

Every one carries an `EpistemicPlacement`, which is either `AtSpans` — exact
spans, never a hull — or `NoDiscoursePosition` with one of four typed reasons
(`WholeWork`, `UnitAbsentFromAtlas`, `SubjectCitesNoSpans`, `BeyondHorizon`). An
absence carries **no lane**: the vertical axis is the context lane, and an
unresolved context assignment is precisely a candidate whose context is not
established, so lane 0 would draw it in the narrated world.

**Which of D9's five uncertainty states are implemented.**

| D9 state | status | mark | non-colour channel |
|---|---|---|---|
| `Estimate.Missing(reason)` | **implemented** | `Gap`, `Abstention` | `OpenHatch` |
| `Credence` unmeasured or raw-scored, basis uncalibrated or rule-determined (ADR 0010) | **not implemented** | — | — |
| calibrated `Probability` (`CredenceBasis.Calibrated`) | **not implemented** | — | — |
| `Resolved.alternatives` / `ResolutionState.Alternatives` | **implemented** | `Gap` | `Fan` (V-U3) |
| `ResolutionState.Unresolved` | **implemented** | `Gap` | `Placeholder` (V-U3) |
| align `Exclusion` | **not implemented** | — | — |

Raw credence and calibrated probability are properties of a mark that carries a
*value*, and no mark carries one until the D11 feature layer materializes
numeric sidecars (`featureSpaces` and `sidecars` are empty). Their vocabulary is
deliberately **not** minted: D14a records what happens when an axis is receipted
but semantically inert — it looks exactly like a shipped feature. They arrive
with the marks that can bear them, and adding them is additive.

An unsatisfied promotion law is **not** a D9 state — D9 classifies uncertainty
about a value and a violated law is a structural defect of the build — so it is
excluded from `UncertaintyState` and carries its own channel, `Bracket`. The
state-to-channel assignment is total and injective and a court kills any
collision. Two new `VisualInvariant`s declare this on the contract:
`AbsenceIsMarked` and `EpistemicChannelIsNonColour`.

**V-E3 extends unchanged to the new marks.** An absence that names words must
name words the model records: a `Gap` or `Abstention` may sit only on the exact
span of a surface unit the atlas contains, and an `UnsatisfiedLaw` only on spans
its subject's own claim cites. `AtlasCompiler.checkEvidence` is `private[view]`
so a court can hand it a forged mark; a check no test can make fail is
decoration.

`AtlasTextualTwin` renders the draft receipt (promotion state, gap count,
violation count, and each unsatisfied law with its occurrence count) and every
new mark with its channel, because the twin is the audit surface (D12).

### A3 A situation's context is reachable from its own mark

`VisualPrimitive.Landmark` gains `context: ContextId`, derived from the
situation's own node. Without it a renderer cannot band by context and draws all
sixty-five War of the Ghosts situations the same way, putting the survivor's
retelling back into the narrated world.

A new `ContextBand` mark draws a frame's scope: **one extent per span of the
frame's own support**, never a hull, on the frame's lane, tagged
`ContextBandBasis.ExactScopeEvidence`. D4 row 6 names three band classes;
only exact scope evidence is compiled. Contextual membership and inferred
continuation need claims the model does not yet make, and a band standing in for
them would draw a continuous speech frame across text nobody attributed to a
speaker. The one-case enum states which class the band is, so adding the other
two is additive rather than silent.

Measured on the real model: the narrated world's band carries **281** separate
extents; a hull would be one, and would swallow the five speech frames inside
it. The survivor's retelling bands at `[1724,1900)`, the offsets
`StoryBuildSuite` measured independently, holding six situations.

### A4 Module dependency

None added. `view` already `dependsOn(document)`, and `pipeline` already sees
`view` transitively through `codec`, which D14 put there. The real-model court
lives in `pipeline` because the only fifty-sentence compilation this repository
has is the one `recordings/wog-captured` replays, and `view` cannot see the
transport or the provider.

### A5 What this amendment does not do

The remaining half of plan §V0 — storyatlas4s reading `storymodel.json` through
a `codec` dependency, and the pin bump — is untouched. Nothing here changes the
validated path, the Codex compiler, or any existing mark.

## 12. Amendment — V0: the draft Codex path (2026-09-03)

Decided by the owner's agent in single-developer mode (AGENTS.md SD5). Completes
the compiler half of `docs/plans/2026-09-03-visualization-recovery-plan.md` §V0:
§11 gave the Atlas a draft path, this gives the reading view one.

### B1 The Codex compiles a draft, under the same receipt rules as the Atlas

**The problem.** After §11, `AtlasCompiler` rendered a machine-built model and
`CodexCompiler.compile` still took `StoryModel[Validated]`. A draft edition
therefore wrote atlases and **no HTML at all**, because an empty Codex would
claim the reading view had been compiled and had nothing to say. The Codex is
where the words are, so that left the surface `vision.md` names first —
"move between the words of a transcript, the propositions expressed by those
words, the events and states that make up a story" — unavailable for every model
the pipeline actually builds.

**Decision.** `CodexCompiler.compileDraft(draft: DraftModel, state, spec)`,
mirroring `AtlasCompiler.compileDraft` and reusing its vocabulary: the same
`DraftModel`, `DerivationRecord`, `DraftPromotion`, `UncertaintyState`,
`EpistemicChannel`, `EpistemicPlacement` and `NoPositionReason`. `compile`
refuses a `DraftBuild` receipt, `compileDraft` refuses any other and refuses a
receipt whose promotion does not describe the bundle in hand. The Codex's
receipt-free-model rule gains `DraftBuild` beside the researcher-reviewed
fixture, exactly as the Atlas's did.

Nothing on the validated path changes. `candidates`, `proposal`,
`visibleAncestorChains`, `compileFeature` and `validateProvenance` now take
`StoryModel[?]` because none of what they read is guarded by the promotion
phantom, and a draft's words deserve the same annotations from the same rules.
What separates the two paths is the receipt, the disclosure channels and the
ledger — not a second compiler.

### B2 Absence is an annotation of its own kind, and the annotation carries the record

`AnnotationKind` gains three cases in the closed enum — `Gap` (`"gap"`),
`Abstention` (`"abstention"`), `UnsatisfiedLaw` (`"unsatisfied-law"`) — rather
than being smuggled through a free-form tag. A channel nothing can enumerate is
a channel a renderer can silently omit, and omitting it puts unmarked prose back
in front of a reader who will take it for prose the model understood.

**A kind alone is not enough, because annotation identity would coalesce two
failures into one.** `AnnotationId` is the content address of
`(target, kind, support)`, and `TextAnnotation.coalesce` is required to merge
repeats of that triple. Two claim families failing at one chart node over one
sentence agree on all three. So `TextAnnotation` gains
`absence: Option[DraftAbsence]`, which participates in the content address:

```scala
enum AbsenceContent:
  case UnresolvedFamily(gap: DerivationGap)
  case AbstainedSentence(unit: SurfaceUnitId, reason: SentenceAbstention)
  case UnsatisfiedLaw(violation: Violation)

final case class DraftAbsence private (content: AbsenceContent, occurrence: Int)
```

Each case carries its producer's record whole; the view restates none of it.
`occurrence` is part of the identity because a validator may state one violation
twice, and a reading view showing one mark where the model recorded two absences
reports less than the model knows — the same defect as reporting more, pointed
the other way. `absence` and `kind` are one statement: `Some` **exactly when**
the kind marks an absence, so a bare `"gap"` annotation naming no family cannot
be minted and a failure cannot be dressed as a finding on the claim channel.
The absence key is **appended** to the content address and never interleaved, so
no validated flow's annotation identities move.

**An annotation cannot carry `NoDiscoursePosition`, so the unplaced absences get
a ledger.** An Atlas mark may claim no text and still be drawn; a
`TextAnnotation`'s support is a nonempty `SpanSet` by construction, because an
annotation over no words is not an annotation. Dropping those absences would
make the reading view quietly smaller than the model, so `CodexFlow` gains
`draft: Option[DraftAbsenceLedger]` holding `marked: Vector[AnnotationId]` and
`unplaced: Vector[UnplacedAbsence]`. The compiler refuses to build a flow unless
`marked.size + unplaced.size` equals `DraftModel.absences.size`; that check is
the mechanised form of "absence is annotated, not omitted" (plan §2.3). The
ledger is present **exactly when** the basis is `DraftBuild`, and its `marked`
ids are exactly the flow's absence-bearing annotations — the same biconditional
shape `ViewProvenance` uses for the promotion record, so a validated flow cannot
carry a disclosure and a draft flow cannot omit one.

Which words an absence concerns is decided once, in `AbsencePlacement`, and both
projections call it. Two implementations of that rule would eventually disagree,
at which point one of the two pictures would be lying about the other's subject.

### B3 Context in the reading view is an annotation kind — the one that already exists

The model has six contexts, five of them speech frames, and a reader must be
able to see that the survivor's retelling at `[1724,1900)` is reported speech
rather than narration. Three shapes were available.

**Chosen: an annotation of kind `Context`, which the validated path already
emits.** A context frame is a model object with a claim, evidence and exact
support, and the Codex's channel for "these words are the support of this model
object" is a `TextAnnotation`. Nothing new is needed, and minting a parallel
representation would give one model object two, which is how two pictures start
to disagree. The frame's *kind* is not restated on the annotation: the target
address resolves through `NavigationIndex` to the frame, where the model states
it. That is the no-inference law (D4) applied to a label — the view points at
the model's statement instead of copying it, so a corrected frame corrects the
reading view with no view-side change.

**Rejected: a lane.** A lane is `LaneAllocation`'s deterministic output, a
rendering resource bounded by `LanePolicy`. Making context a lane would make the
meaning of a mark depend on how many other marks competed for gutters, and
`LaneSlot.Overflow` would erase the speech/narration distinction exactly when a
page is busiest.

**Rejected: a property of the run.** `SourceRun`s tile the canonical text
contiguously and exhaustively; they are the pagination-neutral segmentation of
the *source* and deliberately carry no claims. Frames nest — `ContextFrame` has
a `parent`, and every speech frame here sits under the narrated root — and a
flat tiling cannot represent a frame inside a frame. Worse, the narrated world's
support is 281 separate runs, so run boundaries would have to encode a scope
claim the runs exist to stay clear of.

### B4 The disclosure channels are entailed by the basis, not chosen by the caller

A draft flow declares all three absence kinds in its contract whether or not it
has anything to put in one: a declared empty channel says "no sentence was
abstained on", while an undeclared one says nothing and is indistinguishable
from a renderer that dropped it — the same distinction `NotSupplied` draws
against a count of zero.

They do **not** consume `ChannelBudget.maxAnnotationKinds`. That budget bounds
how many lenses a caller may switch on at once, and a caller who could spend a
draft's disclosure out of the budget could compile a machine-built story as
unmarked prose. For the same reason their `AnnotationPriority` is fixed by the
compiler at `Maximum`, one value for all three, so no absence outranks another —
a ranking nothing in the model supports.

`configurationRendering` stays at `codex-compiler-config/v2`. It commits to the
caller's requested policy; what a draft discloses about itself follows from the
basis, which the receipt already states.

### B5 Measured on the real model

Replaying the fifty captured War of the Ghosts recordings (no model call, no
spend) under `CodexLens.Overview` and `ChannelBudget.All`:

| channel | annotations |
|---|---|
| `claim` | 65 |
| `entity` | 29 |
| `context` | 6 |
| `gap` | 69 |
| `unsatisfied-law` | 65 |
| `abstention` | 1 |
| `hierarchy`, `relation` | declared, empty |

235 annotations, 135 of them failures. The ledger accounts for all **206**
absences the compilation and the validator recorded: 135 on exact sentence
spans, 71 that honestly claim no words (the story summary, and the 70
`compiler.required-derivation` violations whose candidate the validator cannot
resolve to an address). The retelling's sentences carry the speech frame's
annotation and no other frame's; no sentence outside a quotation carries any
speech annotation.

**One thing found and not fixed here.** The narrated-world frame's support is
word-level, and 63 of its 281 runs lie inside a speech frame — 22 of them inside
this retelling, the content words of the quoted passage. So both frames annotate
those words. That belongs to the frame's scope evidence in `document`, not to
the view, and the view may neither add it nor hide it. It is pinned in
`WarOfTheGhostsDraftCodexSuite` so a fix upstream arrives as a visible change to
that court rather than a silent one.

### B6 What this amendment does not do

No module dependency is added. The validated path's behaviour and annotation
identities are unchanged, which a court pins. The remaining half of plan §V0 —
storyatlas4s reading `storymodel.json` through `codec`, and the pin bump — is
still untouched, and no renderer is written here: this is the compiler and its
textual twin, which is the audit surface a renderer will be checked against.

## 13. Amendment — the landmark carries its own claim status (2026-09-03)

Decided by the owner's agent in single-developer mode (AGENTS.md SD5).

### C1 A situation's mark states how the situation is licensed

**The problem.** §11 A3 gave `Landmark` a `context` so a renderer could stop
drawing the survivor's retelling as narration. It left a second uniformity in
place: every one of the sixty-five War of the Ghosts situation marks carried
identity, anchor, label, kind and context and **no epistemic status**, so a
reader could not tell an event the text states from one the compiler derived.
`VisualPrimitive.Route` directly below it has carried `status: EpistemicStatus`
since checkpoint 1 for exactly this reason, and the model has always held the
answer — `SituationNode.meta.status` — which the compiler already reads for
contexts and edges in the same file. D9 requires that `EpistemicStatus` has six
values and each is distinguishable by a non-colour channel; that is
undeliverable for situations while the mark omits the field.

**Decision.** `VisualPrimitive.Landmark` gains `status: EpistemicStatus`, read
from the situation node's own `ClaimMeta`. There is no default and no fallback.
The compiler's existing `g.situations.get(id)` is the typed absence: a situation
the graph does not hold produces **no landmark**, rather than a landmark whose
status was filled in because nothing else was known. A mark that said
`SurfaceExplicit` for want of an answer would be the one lie this whole layer
exists to prevent.

One construction site serves both compile paths, because `compile` and
`compileDraft` share a single `build`. `AtlasTextualTwin` renders `status=` on
every landmark line, since the twin is the audit surface (D12). No module
dependency is added, no existing mark changes, and V-E3 is untouched: this is an
addition to what a mark says about its own claim, not a change to what evidence
a mark may name.

### C2 Measured on both models

| model | landmarks | statuses |
|---|---|---|
| WOG acceptance fixture (validated path) | 71 | 70 `SurfaceExplicit`, 1 `Hypothesized` |
| WOG machine-built draft (replay of the 50 captured recordings) | 65 | 65 `SurfaceExplicit` |

**The real machine-built model is uniform, and the field is still required.**
Every situation this provider proposed is `SurfaceExplicit`, so one status is
the honest picture of this model today. That is a fact the viewer can only
report because the mark now carries the claim; without the field a uniform model
and a mixed one draw identically, and the day a `StructurallyDerived` situation
arrives nothing would notice. The fixture, which a researcher curated and which
already holds one `Hypothesized` situation, is the court where a defaulted field
goes red — it is the discriminating evidence for both paths, since both compile
through the same construction.

### C3 What this amendment does not do

It does not mint the non-colour channel assignment for `EpistemicStatus`. D9's
channel requirement is now *deliverable* for situations and is not yet
*delivered*: `Landmark` and `Route` both carry the status and neither declares a
channel for it, and `PrimitiveChannel`-style vocabulary is deliberately not
invented here for the same reason A2 refused to mint raw credence and calibrated
probability — a channel enum with no renderer reading it looks exactly like a
shipped feature (D14a). The six-way assignment arrives with the renderer that
draws it, and adding it is additive.

## 14. Amendment — the Recall Voyage (2026-09-03)

The owner asked for the recall-to-video mapping, with its uncertainty, in the
visualization module. The plan had sequenced a Recall Voyage under V3 behind an
"align lowering"; the blocker was narrower than it read. D4 row 12 permits a
per-recall `AlignmentMatrix` view and forbids only the population map, and the
aligner's typed result already carries everything a voyage draws. What was
missing was a consumer, and this amendment is that consumer.

### D1 New vocabulary, all additive

- `ProjectionKind.RecallVoyage`; `AxisMeaning.RecallClock` (seconds into the
  recall's own audio) and `AxisMeaning.SourceClock` (seconds into a timed
  source's presentation); `MeasureMeaning.AnchorMass` (area encodes
  `AlignmentRow.anchorMass` on the drawn anchor, a named aligner quantity).
- `ViewBasis.AlignmentRun`: an aligner's posterior over a source view is neither
  a promoted model nor a draft of one, and a view of it must say so. It needs no
  model receipt and carries no `BasisAuthority`; on the wire it is
  `alignment_run`.
- `view/voyage.scala`: `Seconds`, `ClockSpan`, `AnchorOrigin`, `SourceTimeline`,
  `VoyageUnit`, `VoyageDecision`, `IndependentCoding`, the proven join
  `RecallVoyageInput`, the marks `VoyageMark` (`UnitAnchor`, `Alternative`,
  `Unanchored`, `Untimed`), `VoyageScene`, `VoyageCompiler`, `VoyageTextualTwin`.
- No new `VisualChannel`. The voyage declares meanings for eight existing
  channels, so the Discourse Atlas legend is untouched.

### D2 What the marks are, and the law that holds them

Every visual category is an `AlignState` case or a named derived quantity
(V-R1): the drawn anchor and its `anchorMass`; every other admitted anchor with
its mass, ranked (the posterior as a list, which is the honest picture of
uncertainty and the only one this ADR mints); `sourceMass` and `externalMass`;
`localizability`; the posterior argmax kept beside a decode-moved anchor. The
one new epistemic category, `AnchorOrigin`, names a fact about the aligner's
procedure — argmax, decode-bound, decode-filled — and is drawn as shape, never
colour (V-U5). A decode-filled anchor is drawn with mass exactly zero: the decode
went outside the posterior, and a mark that showed a small amount of evidence
there would be the lie this layer exists to prevent. Measured on the Sherlock
default run: the decode moves 57% of anchors and fills 19%.

The evidence law is `VoyageCompiler.checkEvidence`: every number on a mark is
recomputed from the row it claims to draw, every span from the timeline, every
origin against the decision; a forged mark is refused at compile time, and the
law is `private[view]` so a court can hand it one.

`RecallVoyageInput` is the proven join (rows ↔ units ↔ timeline ↔ decisions ↔
coding); its constructor is private and `of` refuses any strand that does not
hold, including an origin that does not describe its row. Reading `HsmmResult`
directly was rejected: it carries costs, admissibility and likelihoods a view
must not draw, and would let a renderer reach past the posterior.

### D3 What it does not do

No reader horizon: `HorizonShared` is not declared, and no horizon is
re-derived either. No population voyage (§9 checkpoint 4 stands). No credence
axis, no calibrated probability, no "blur" (§11 A2 stands): the spread of the
alternatives *is* the uncertainty shown. An independent human coding rides
beside the marks with its name and checksum and never becomes a model address.
The twin lists marks and never recall prose.

### D4 Consequences for storyatlas4s

It owns no science, so it lowers `VoyageScene` and renders it: a static lowering
for the edition, and an app pane whose hover, selection and keyboard walk are
DOM behaviour over `data-name = MarkId`. Recall prose reaches the inspector from
`VoyageUnit.text` through the scene, never through intaglio (D6). The pin moves
with this change in the same slice.

Rejected alternatives, recorded the same day: reusing `NarrativeScene` and
`VisualPrimitive` (they require a zoom, a feature layer and a discourse axis the
voyage does not have, and `Landmark` carries a claim status no alignment has);
adding columns to the study TSV (it would change the identity of every landed
run; the sidecar and the scene leave it byte-identical); a Laminar-only page in
storyatlas4s (it would draw numbers the model never compiled, which is the one
thing that repository's contract forbids).
