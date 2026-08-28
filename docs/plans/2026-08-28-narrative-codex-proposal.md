# Narrative Codex — paginated, model-annotated text as a canonical view; proposal, revision 0

Status: **draft for deliberation** (owner's proposal, filed verbatim by
claude-storymodel4s on 2026-08-28). Companion to
`2026-08-28-narrative-atlas-proposal.md`. Discussion: mote topic
`narrative-atlas-intaglio`, bead `bd-01M14DX0B3VJQKN185TXN8RMMG`. Naming note:
"Codex" collides with the Codex agents on this board and with the `codec`
module; the deliberation may rename the module (see findings).

---

Yes. The **paginated, model-annotated text should be one of the canonical views of the system**, not merely a transcript panel attached to the "real" visualization.

I would call it the **Narrative Codex**: a readable edition of the story in which every annotation is generated from—and remains linked to—the formal story model.

The exact text is already the observational coordinate system of `storymodel4s`; every proposition, event, scene, boundary, and feature estimate must point back to it. The Codex is the most direct visual realization of that principle. It also realizes the project's requirement that a user be able to move from words to windows to narrative units and back again.

# 1. The page is an evidence surface

The underlying relationship should be:

\[
\boxed{\text{StoryModel} + \text{CodexViewSpec} \longrightarrow \text{AnnotatedPages}}
\]

The page itself contains no improvised decorations. Every visible mark corresponds to one of:

- an exact source span;
- a feature-track value;
- a proposition or AMR fragment;
- an entity or event mention;
- a document-level identity relation;
- a temporal, causal, spatial, or goal relation;
- a scene or episode boundary;
- a context such as speech, belief, or intention;
- an accepted claim, alternative claim, or uncertainty estimate;
- later, a recall-to-source alignment.

The interface should always allow the user to ask:

> Why is this word coloured?
> Why are these passages connected?
> Why does this scene begin here?
> What model and evidence produced this annotation?

The answer opens the corresponding feature, node, relation, claim, and provenance receipt.

---

# 2. Anatomy of an annotated page

A page might be organized approximately as follows:

```text
┌─────────────────────────────────────────────────────────────────────┐
│ Episode 2 · Journey and Battle              discourse 32% · page 7 │
│                                                                     │
│  EPISODE ┐                                                          │
│          │   SCENE 2.3 ┐                           ENTITY THREADS    │
│          │             │                            young man ───●   │
│          │      The other young man joined the warriors and         │
│          │      climbed into the canoe. They travelled upriver      │
│          │      through the fog toward Kalama.                       │
│          │                                                           │
│          │      ───────────── event: join journey ─────────────      │
│          │         ╰── ARG0 ──╯        ╰── destination ──╯          │
│          │                                                           │
│          │      Soon they heard war cries from beyond the bank.      │
│          │      ███████████████████ auditory imagery ████████        │
│          │                                                           │
│          │             └ SCENE 2.3                                   │
│          └ EPISODE                                                   │
│                                                                     │
│ sensory tracks      visual ━━━━━ auditory ━━━━━ motor ━━━━━         │
│ boundary evidence   semantic ↑ entity ↑ location — context —        │
└─────────────────────────────────────────────────────────────────────┘
```

The page has several coordinated annotation zones.

## Main text field

This remains genuine selectable text, not labels drawn onto a canvas. The reader can:

- select and copy passages;
- search exact words;
- navigate sentence by sentence;
- use keyboard and screen-reader access;
- switch into an almost unannotated reading mode.

## Left structural gutter

The left gutter carries nested structure:

```text
episode
  scene
    event
      proposition
```

Episode and scene brackets may continue across several pages. A continuation marker shows that a region began on an earlier page or continues onto the next.

## Right relational gutter

The right side carries:

- entity threads;
- event-reference links;
- location threads;
- temporal and causal portals;
- links to repeated or retrospectively described events;
- later, links from source passages to participant recall passages.

## Feature tracks

Thin aligned tracks show continuous measurements by word, clause, sentence, or selected window:

- visual imagery; auditory imagery; motor or kinesthetic content; spatial content; interoception; valence; arousal; threat; semantic surprise; boundary probability; uncertainty; causal or semantic centrality; recall coverage.

One selected track can colour the text itself. Additional tracks remain in the margin as compact sparklines.

## Header and footer

The page header can show: episode and scene; discourse progress; estimated story-world time; active character and location; current analysis lens.

The footer can show: page and source offsets; model version; feature aggregation scale; confidence or missingness legend.

---

# 3. A disciplined visual grammar

The same graphical channel must always have the same kind of meaning.

| Visual channel | Meaning |
|---|---|
| **Text background wash** | One selected continuous feature |
| **Underline** | Local semantic unit, proposition, or event support |
| **Left brackets** | Containment: event, scene, episode |
| **Right-side threads** | Entity, location, object, or goal continuity |
| **Arrows** | Typed directed relations |
| **Link tabs or portals** | Nonlocal or cross-page connections |
| **Hatching** | Uncertainty or model disagreement |
| **Dashed boundary** | Uncertain scene or event boundary |
| **Solid boundary** | Accepted boundary |
| **Small superscript marker** | Claim, alternative, warning, or provenance |
| **Muted text** | Outside current focus, not "unimportant" |
| **Missing track segment** | Missing evidence—not a zero value |

There should be an annotation-channel budget. In normal reading mode, the page might show:

- one scalar feature;
- one structural hierarchy;
- one relation family;
- one selected entity or goal thread.

The analyst can activate more, but the interface should resist becoming an illegible illuminated manuscript.

---

# 4. Colouring text by metrics

Colour should be attached to the exact level at which the value was computed.

## Word-level values

Examples: lexical imageability; concreteness; sensorimotor norms; surprisal; entity salience. These can produce subtle word-specific underlays or underlines.

## Clause- or sentence-level values

Examples: contextual auditory content; affective intensity; proposition certainty; local semantic novelty. The whole sentence can receive a restrained background wash, with boundaries between sentence estimates visually softened.

## Windowed values

Suppose auditory imagery is reduced over a three-sentence window:

\[
F_i = \frac{\sum_{j\in W_i}w_j f_j}{\sum_{j\in W_i}w_j}.
\]

The colour should appear as a continuous translucent field behind the corresponding window, not as though every word independently received the same rating.

Clicking the coloration opens its derivation:

```text
Feature: sensory.auditory.expressed
Window: 3 sentences, centred
Reducer: coverage-weighted mean
Value: 0.81
Coverage: 96%
Provider: sensory-model-v2
Evidence: sentences 41–43
```

## Scale slider

A scale control changes the aggregation level:

```text
word → clause → sentence → 3 sentences → scene → episode
```

As scale changes, the user sees whether a peak is: one striking word; a vivid sentence; a sustained passage; a scene-level property; an episode-wide characteristic. The text itself remains fixed. Only the measurement field changes.

---

# 5. Hierarchy directly on the page

The event–scene–episode hierarchy should be legible without leaving the text.

## Event underlines

An event can be represented by an underline spanning all of its textual support. Different event mentions can overlap when necessary.

Hovering reveals:

```text
Event E17
"The second young man joins the warriors."

Participants
  Agent: young-man-2
  Group: canoe-warriors

Context
  Narrated world

Support
  sentence 8, tokens 3–10
```

## Scene brackets

A scene bracket in the left gutter spans all lines belonging to the scene. The label might be:

```text
Scene 2.1
Invitation and Decision
```

The scene boundary itself can carry evidence:

```text
location change       0.12
entity turnover       0.34
semantic change       0.51
goal change           0.88
model boundary vote   0.79
resolved probability  0.84
```

## Episode bands

Episodes can appear as a subtle page-edge band. When an episode changes midway through a page, the band changes at the precise line.

At a distant zoom, the individual pages become thumbnails in which episode colours and scene boundaries remain visible. This creates a navigable "page carpet" of the entire story.

---

# 6. Hyperlinks should be semantic and typed

A hyperlink in the Codex does not merely connect one page to another. It specifies *why* two spans are connected.

```scala
enum SemanticLink:
  case SameEntity
  case SameEvent
  case ProspectiveReference
  case RetrospectiveReference
  case SummaryOf
  case Before
  case Causes
  case Enables
  case GoalContinuation
  case SameLocation
  case SemanticRecurrence
  case EvidenceFor
```

Examples:

- clicking "the young man" cycles through every mention of that canonical entity;
- clicking "make war" previews the later realized battle;
- clicking the recounting at home previews the earlier battle it describes;
- clicking a scene summary opens its constituent events;
- clicking an inferred causal edge reveals the passages supporting the proposed cause and effect.

## Hover preview

Hovering over a cross-page link shows a small destination card:

```text
Page 11 · Event E34
"He told the people everything that had happened."

Relation:
Retrospective description of Battle E21
```

## Click

A normal click moves to the destination page.

## Modified click

A modified click opens a split view so the source and destination remain visible simultaneously.

## Cross-page arrows

Long arrows should not snake across several invisible pages. Instead, the line reaches the page margin and terminates in a **portal tab**:

```text
↗ Battle E21 · page 9
```

The matching destination tab appears on page 9. Selecting the tab displays the complete connection.

---

# 7. Local arrows and AMR structure

At ordinary reading scale, AMR should be mostly latent. At clause or proposition scale, it becomes visible.

Suppose the sentence is:

> The other young man joined the warriors.

Selecting "show proposition" could display:

```text
The other young man       joined       the warriors
╰──────── ARG0 ───────────╯ ╰── ARG1 ────────╯
```

Or more accurately, depending on the chosen frame:

```text
join-01
  ARG0 → young-man-2
  ARG1 → warrior-group
```

The page view highlights the aligned words, while a small proposition card appears in the margin.

For more complex sentences:

- arcs above the line show local semantic-role relations;
- arcs below can show temporal or discourse relations;
- reentrancy is displayed by one participant connecting to several predicates;
- negation receives an explicit marker;
- quoted or believed propositions appear inside a labelled context band.

For example:

> The warriors said that the young man had been struck.

The embedded injury proposition should be visually enclosed within a context labelled:

```text
reported by warriors
```

It should not receive the same styling as an unqualified narrated-world event.

---

# 8. Speech, belief, memory, and hypothetical context

Context scope is difficult to understand in a detached graph and unusually intuitive on the page.

A passage can receive a thin side band:

```text
│ warrior speech
│
│ "We are going to make war against the people."
│
└ end speech
```

Nested content can carry additional scope:

```text
warrior speech
  └ intended future event
```

Other bands might indicate: character belief; intention; memory; hypothetical; dream; narrator uncertainty; explicit counterfactual.

The band should follow the exact text that introduces and sustains the context. Clicking it opens the document-level context node and every proposition scoped beneath it.

This would make one of the most important distinctions in the system immediately legible:

\[
\text{the text mentions proposition }p \not\Rightarrow \text{the story asserts }p\text{ as fact}.
\]

---

# 9. Entity and object threads through pages

When a user selects a character, object, place, or goal, its occurrences become a navigable thread.

For an entity, the interface can show:

- exact mentions in stronger text;
- pronouns in a lighter related treatment;
- a narrow right-margin trajectory;
- entry and exit points;
- periods when the entity remains active but is not named;
- uncertain coreference as a split or dotted thread.

The user can press:

```text
next mention
previous mention
next action
next state change
next speech
next location
```

The thread can continue across pages without drawing a permanent line through the text. Page-edge continuity markers maintain the route.

For locations, the thread can show transitions:

```text
river → canoe route → battle site → home
```

Selecting a transition highlights the language supporting movement and opens the corresponding story-world relation.

---

# 10. Page navigation should itself be multiscale

The Codex can provide several ways of moving through the document.

## Page turn

Traditional page-by-page navigation for close reading.

## Continuous scroll

Useful for searching and tracking long spans.

## Page strip

A thumbnail strip shows: scene boundaries; episode bands; active feature colouring; selected entity appearances; relation portals.

## Page carpet

At a more distant zoom, all pages form a two-dimensional overview. Pages remain in discourse order, but their thumbnails reveal the story's feature topography.

## Structural contents

A left-hand contents tree shows:

```text
Story
  Episode 1
    Scene 1.1
    Scene 1.2
  Episode 2
    Scene 2.1
    Scene 2.2
```

Clicking a scene turns directly to its first supporting page.

## Search by structure

Queries can include:

```text
show all auditory peaks above 0.8
find every location change
show causal claims involving the young man
find reported events not asserted by the narrator
find scene boundaries with high model disagreement
```

Results appear as page markers rather than only as a separate result list.

---

# 11. Reader-time versus omniscient annotation

The page view becomes especially interesting when it can be read under two epistemic modes.

## Omniscient analyst mode

All accepted structure derived from the complete story is available.

## Reader-at-this-point mode

Only claims supported by text already encountered are shown:

\[
\mathcal S_{\le t} = \left\{ c : \operatorname{evidenceEnd}(c)\le t \right\}.
\]

Suppose a later passage leads the young man to conclude that the warriors were ghosts.

Before that revelation:

- the earlier warriors appear as mysterious warriors;
- the "ghost" interpretation is absent or only a latent alternative.

After the revelation:

- a retrospective semantic link appears;
- earlier pages gain an optional "later reinterpretation" marker;
- the user can toggle between the earlier and later understanding.

This allows the Codex to visualize: foreshadowing; mystery; delayed identification; reinterpretation; unreliable narration; changes in character knowledge.

---

# 12. Comparison with recall

Later, the same page design can support recall alignment.

## Side-by-side source and recall

```text
SOURCE STORY                         PARTICIPANT RECALL
────────────                         ──────────────────
page 4                               transcript page 2
```

Ribbons connect recalled clauses to source passages.

- narrow sharp ribbon: precise alignment;
- broad ribbon: gist or vague reference;
- divided ribbon: blending;
- backward crossing: misordering;
- repeated ribbon: elaboration;
- no source destination: external association or intrusion;
- source passage without incoming ribbon: omission.

## Overlay on source text

The source page can also display: recall frequency across subjects; average precision; omission probability; common misorderings; common distortions; sensory-detail preservation.

A passage could become a population memory map while remaining readable as prose.

## Autobiographical Interview mode

For an interview transcript, the same Codex could show: inferred target-episode details; other-event details; personal-semantic information; metacognitive statements; probe provenance; phenomenological language; uncertainty about event membership.

Traditional internal/external scoring would then be visible as one lens over a much richer transcript representation.

---

# 13. Annotation views as named lenses

Rather than hundreds of independent switches, the application should offer coherent lenses.

- **Structure lens**: events; scenes; episodes; boundaries; summaries.
- **Sensory lens**: expressed visual, auditory, motor, spatial, tactile, and interoceptive content; scale-adjustable feature colouring; sensory peaks.
- **Entity lens**: mentions; coreference; active entities; participant roles; relationship changes.
- **Time lens**: discourse order; story-world time; flashbacks; anticipation; temporal expressions.
- **Causal lens**: causes; enabling conditions; prevented outcomes; state changes; inferred versus explicit causality.
- **Epistemic lens**: narration; speech; belief; intention; hypothetical material; uncertainty; competing interpretations.
- **Recall lens**: source-to-recall mappings; omissions; gist; distortion; backtracking; external material.

Each lens is a serializable `ViewSpec`, so a paper or collaborator can open exactly the same annotated page state.

---

# 14. A Scala representation

The Codex should be compiled from the validated model rather than assembled ad hoc in the UI.

```scala
sealed trait AnnotationKind

sealed trait FeatureK   extends AnnotationKind
sealed trait HierarchyK extends AnnotationKind
sealed trait EntityK    extends AnnotationKind
sealed trait RelationK  extends AnnotationKind
sealed trait ContextK   extends AnnotationKind
sealed trait ClaimK     extends AnnotationKind
sealed trait RecallK    extends AnnotationKind

final case class TextAnnotation[K <: AnnotationKind](
  id: AnnotationId,
  support: SpanSet,
  payload: AnnotationPayload[K],
  claim: Option[ClaimId],
  evidence: Vector[EvidenceRef],
  priority: AnnotationPriority
)

final case class CodexViewSpec(
  pagination: PaginationSpec,
  lens: CodexLens,
  feature: Option[FeatureRendering],
  hierarchyLevels: Set[HierarchyLevel],
  relationLayers: Set[RelationLayer],
  selectedEntities: Set[CanonicalEntityId],
  epistemicHorizon: EpistemicHorizon,
  uncertaintyPolicy: UncertaintyPolicy,
  comparison: Option[ComparisonSpec]
)

final case class AnnotatedDocument(
  pages: Vector[AnnotatedPage],
  annotationIndex: AnnotationIndex,
  navigation: CodexNavigation,
  provenance: ViewProvenance
)
```

A page contains rendered fragments rather than copied text:

```scala
final case class AnnotatedPage(
  number: Int,
  sourceRange: TextSpan,
  lines: Vector[TextLine],
  annotations: Vector[PlacedAnnotation],
  gutters: PageGutters,
  continuationLinks: Vector[PagePortal]
)
```

The compilation contract is:

```scala
trait CodexCompiler:
  def compile(
    story: StoryModel[Validated],
    spec: CodexViewSpec
  ): Either[CodexCompilationError, AnnotatedDocument]
```

This keeps the scientific visualization testable independently of Laminar, SVG, or WebGL.

---

# 15. Rendering architecture

For the browser, the best approach is hybrid.

## DOM text

Use DOM elements for: exact prose; selection; copy and paste; search; accessibility; responsive typography. Every token or suitable run carries stable source-offset metadata.

## SVG overlay per page

Use SVG for: brackets; underlines; arrows; relation arcs; entity threads; portal tabs; context bands; uncertainty marks. The browser's `Range.getClientRects()` can map an exact source span to one or more line rectangles.

## Canvas or WebGL underlay

Use a canvas under the text for dense continuous fields: word-level heatmaps; scale-space colouring; population recall density; smooth feature contours. The user interacts with the DOM and SVG; the canvas supplies performance for dense numerical tracks.

## Stable pagination

The pagination specification should include: page dimensions; margins; font family category; font size; line height; paragraph spacing; annotation-gutter width.

Offsets remain independent of pagination. Reflowing changes the visual rectangles, not the source identities.

For reproducible figures, the complete typography and pagination configuration becomes part of the saved view receipt.

---

# 16. Visual laws for the Codex

- **Evidence law.** Every interpretive annotation resolves to a claim and evidence: \(\forall a\in A_{\mathrm{interpretive}},\ \operatorname{evidence}(a)\neq\varnothing\).
- **Span law.** Every annotation span is valid against the canonical source text.
- **Reflow law.** Changing page width may alter geometry but not annotation identity or textual support.
- **Hierarchy law.** A child unit's support lies inside—or is explicitly linked as discontinuous to—its parent's support.
- **Colour law.** One visual colour scale represents one declared variable. Missingness, uncertainty, and magnitude must not share the same channel.
- **Arrow law.** An arrow can imply direction only when the underlying relation is directed. Causal-looking arrows may only represent causal or enabling relations.
- **Uncertainty law.** Uncertain structure must not become visually indistinguishable from accepted structure.
- **Selection law.** A selected claim remains selected when the user changes page, changes zoom, switches lens, opens the Atlas, or opens the AMR microscope.
- **Audit law.** Every rendered aggregate exposes its source values, its reducer, its window, its coverage, its provider and configuration.

---

# 17. The first vertical slice

A compelling first implementation does not need every relation type. It should include:

1. genuinely paginated exact text;
2. event, scene, and episode brackets;
3. a selectable feature heatmap with scale control;
4. entity mention highlighting and next/previous navigation;
5. click-through from text to event and claim inspector;
6. local proposition or AMR display for a selected sentence;
7. cross-page event-reference portals;
8. page thumbnails showing scene structure and feature density;
9. reader-time versus omniscient mode;
10. reproducible saved views.

Applied to *The War of the Ghosts*, this would already allow a user to: read the story naturally; see its episode and scene structure; follow the young man across pronouns and descriptions; locate auditory and bodily-imagery peaks; distinguish the announced battle, actual battle, and later recounting; see that the injury is reported rather than unequivocally established; inspect why a proposed scene boundary occurs; jump from every annotation to the exact model claim behind it.

# The central design principle

The Atlas shows the story as a navigable world. The Codex shows how that world is **made from language**. They should be perfectly synchronized:

\[
\boxed{\text{Codex span} \leftrightarrow \text{proposition} \leftrightarrow \text{event} \leftrightarrow \text{scene} \leftrightarrow \text{Atlas object}.}
\]

Clicking a coloured phrase can fly to its event in the Atlas. Clicking a causal road in the Atlas can turn to the exact pages and underline the clauses supporting it. Zooming outward from a word should eventually reveal the proposition, event, scene, episode, and story structure containing it.

That is what prevents the page annotations from becoming ornament. The annotated page is the **evidentiary face of the formal model**, and the Atlas is its navigational face.
