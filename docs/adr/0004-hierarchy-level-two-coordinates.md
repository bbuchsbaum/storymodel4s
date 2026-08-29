# ADR 0004 — Hierarchy level: two coordinates, not one wrapper

**Status:** Draft (proposed 2026-08-29)

**Date:** 2026-08-29

**Decider:** `claude-storymodel4s`

**Author:** `claude-storymodel4s`

## Context

`level: Int` appears on both sides of a boundary the project treats as scientifically
load-bearing, and nothing in the type distinguishes them:

- `features/boundary.scala:22` — `BoundaryEvidence.level`
- `features/boundary.scala:80` — `BoundaryScore.level`
- `features/boundary.scala:91` — `BoundaryBeliefInput(weights: Map[Int, Map[BoundarySignal, Double]])`
- `story/nodes.scala:121` — `BoundaryBelief.level`
- `story/hierarchy.scala:11` — `SegmentNode.level`
- `story/hierarchy.scala:84` — `descendantsAtLevel`

The first four concern a **task-conditioned perceptual grain**: what a rater or a model was asked
to mark, at an instructed granularity ("small event", "large event", "scene"). The last two concern
**structural containment depth** in a resolved hierarchy. These are different quantities. Per the
chief disposition on event segmentation (2026-08-29), they are two distinct estimands: a perceptual
boundary track, and a resolved narrative hierarchy, with the explicit rule that the resolved
hierarchy must never be recycled as human-consensus gold for the perceptual track.

`BoundaryBeliefInput.weights: Map[Int, …]` is the sharpest instance. **A map key is a comparability
claim.** Two entries under key `2` are asserted by the data structure to be the same level, with no
code anywhere having decided that they are.

## Decision

**Two non-interchangeable coordinates. Not one `Level` wrapper.**

1. **Task-and-grain semantics** for perceptual evidence and its weights: a depth *together with the
   declared instruction that produced it*. Two levels from different declared instructions are not
   comparable merely because their integers match.
2. **Hierarchy depth** for resolved segment containment. `SegmentNode.level` may remain structural
   depth under its own type, and must **not** become the public grain vocabulary by convenience —
   that is the path of least resistance and therefore the one this ADR names.

**A single wrapper over both is refused, and is worse than the bare `Int`.** A mechanical
replacement of every `level: Int` with one `Level` would compile, gate green, pass review, and
preserve the exact conflation under a name that now *implies* semantics it does not have. The bare
`Int` is at least honest: it advertises that it carries no meaning, so a reader knows to go and find
out. `Level` says the question has been settled. Typing a conflated quantity does not un-conflate
it; it launders it (AGENTS.md rule 7).

**No bare `Int` may ship in a public boundary API** where it implies comparability across
instructions.

**If a story-side retained belief derives from perceptual evidence, it must preserve the task-grain
identity and the resolver or ledger receipt**, never reconstruct either from segment depth.
Reconstructing a task-conditioned quantity from a structural one is precisely how the resolved
hierarchy would get recycled as gold for the perceptual track.

## A third site, measured 2026-08-29: level decides what can be MEASURED

The context above lists `features` and `story`. `align` belongs on that list, and it arrived by a
route this ADR did not anticipate — not through an identity question, but through the cost model.

`align/bridge/StorySourceView.scala:112-113`:

    case NarrativeNodeId.Situation(_) => evidence.propositionEvidenceOf(n)
    case NarrativeNodeId.Segment(_)   => None // segments never get a fabricated chart

The comment says this is deliberate and it is right to be. But its consequence is that **chart
evidence is available at one hierarchy level and structurally unavailable at another**, so the
`Chart` cost term is *eligible* for situation cells and *never eligible* for segment cells. Both
are alignment states in the same row of the same matrix.

This surfaced in the blend-support work (`bd-01M177SHFXZKMR8K39BPC9K6FF`). A proposed fix scales
each cell's cost to that cell's own eligible weight. On the current corpus it is exactly right and
was measured to be so — WOG carries no charts, so every cell's eligible weight is identical and the
question is invisible. On a corpus **with** charts, situation cells would sit on a 3.85 weight
basis and segment cells on 3.35, and a cell measured on more dimensions has more available
mismatch. Segments would look systematically cheaper than situations for a structural reason rather
than a narrative one.

**This generalises the ADR rather than merely adding an example.** The `BoundaryBeliefInput.weights`
case says a *map key* is a comparability claim. This case says the same of an *aggregation*:
comparing or pooling quantities across levels asserts that those levels are commensurable, and here
they provably are not, because the levels differ in what it is even possible to measure. Level is
therefore not only an identity question about which grain produced a number — **it constrains which
numbers exist at all**, and any operation that pools across levels inherits that constraint whether
or not it names it.

Two consequences for the decision above:

1. Hierarchy depth is not inert structural bookkeeping. It has measurement consequences in `align`,
   so the rule that `SegmentNode.level` must not drift into the public grain vocabulary matters in
   a module that neither `features` nor `story` imports.
2. Any cross-level aggregate — a cost compared across states of different depth, a mean over mixed
   levels, a `Map[Int, …]` pooling both — must **state the basis it puts them on**. "Scale each to
   its own eligible support" is a defensible rule for one cell and is not automatically a
   defensible rule for a comparison between two.

*The eligibility question itself is open and belongs to that bead, not to this ADR. Recorded here
because the site is a third instance of this ADR's subject and was found by someone who was not
looking for it.*

## Migration is atomic, and this is not the usual advice

`BoundaryEvidence`, `BoundaryScore`, `BoundaryBeliefInput` keying, the story-side belief
representation, codecs, laws and fixtures migrate **together or not at all**.

This is deliberately the opposite of how the `fromProduct` sweep was run. That sweep was correctly
cut into seven independently-gateable slices **because the property is per-type**: closing
`fromProduct` on `Credence` has no bearing on whether it is closed on `TextSpan`, so a half-done
sweep is simply a smaller done sweep. Here, **one surviving `Map[Int, _]` or one un-migrated codec
field re-bridges the two coordinates for everything downstream.** A half-done migration is not a
smaller done migration — it is an undone one that *looks* done, with a typed vocabulary sitting on
top of a live comparability bridge.

Nobody should read the slicing pattern used elsewhere in this repository as the house style for
this change.

## Sequencing

1. Freeze the multi-story decision fixture.
2. Ratify this ADR, including the grain vocabulary — whether the required grains are
   situation/event/scene/episode or a generic declared integer with stated semantics. **That
   vocabulary is not settled by this ADR** and is the open question.
3. Only then choose names and wire shapes. The wire shape is where the atomicity requirement above
   is either satisfied or silently broken, and a name chosen before the coordinates are settled is
   the name that gets overloaded.

## Status of this draft

Draft, not accepted. The two-coordinate decision and the refusal of a single wrapper are ruled and
I do not expect them to move. The grain vocabulary is open. `codex-storymodel-new-engineer` raised
the conflation from current source and its warning is the basis of this ADR;
`codex-storyatlas-root` and `codex-storymodel4s-scout` established the two-estimand framing it
rests on.
