# ADR 0013 — The timed-source builder takes a declared world clock

**Status:** Accepted 2026-09-04, single-developer mode (AGENTS.md SD5)

**Date:** 2026-09-04

**Decider:** the owner's agent, single-developer mode

**Plan:** `docs/plans/2026-09-03-navigation-assessment.md` §1.2, §6 slice 1, §9

## Context

`TimedSourceView.build` (`embed-bench/…/bench/video/RecallToVideo.scala`) built one `succession`
vector from presentation order and bound it to both `RelationLayer.DiscourseSuccession` and
`RelationLayer.WorldTime`, and set `worldOrder` to each node's `discoursePosition`. Three things
therefore sat on one axis: `TransitionKind.DiscourseSuccessor` (θ 1.5) and `WorldTimeSuccessor`
(θ 1.0) fired on the same edges, and `Backward`/`LongJump` are monotone in the same order through
text midpoints. On the shipped path, `RecallOrderControl.scaledConfig(1.5)` multiplied both
successor weights, so a single edge carried 3.75 of ordering prior with 40% of it labelled "world
time".

Mission commitment 5 requires that discourse time, story-world time and recall time be treated as
separate clocks. ADR 0007 rules that missing alignment is a typed refusal. A world clock that is
silently the discourse clock is neither separate nor refused. On *Sherlock* the two clocks nearly
coincide, so the fabrication was cheap; on any edition where they diverge — *Memento* is staged
precisely because they do — every world-time quantity in the report would have been a relabelled
discourse quantity.

Two facts shaped the fix. `RecallSignature.worldChronology` and `worldBackwardMass` key off
`view.worldOrder`, while `TransitionKind.WorldTimeSuccessor` keys off the `WorldTime` edges
(`align/signature.scala:535-547`, `align/hsmm.scala:74`): an absent clock must drop both or one
collinearity remains. And `ViewFingerprint.of` hashes every relation layer and the world order
(`align/wire.scala:116-134`), so a view with a different world clock is a different identity on
the wire and in every landed report.

## Decisions

### 1. `build` requires a `WorldOrderInput`; there is no default

```scala
enum WorldOrderInput:
  case Explicit(byOrdinal: Map[Int, Int], witness: WorldOrderWitness)
  case SameAsPresentation(witness: WorldOrderWitness)
  case Unknown(reason: WorldOrderAbsence)

def build(segments, worldOrder: WorldOrderInput, axes = Map.empty, naming = Naming.default)
    : Either[WorldOrderRefusal, Built]
```

The parameter has no default because the default it replaces *was* the defect. Every caller now
states which clock it is building under, and `Built.worldOrder` carries the statement.

### 2. `SameAsPresentation` reproduces the presentation-derived clock under a named witness

`WorldOrderWitness(assertedBy, basis, asserted: LocalDate)` says who asserts that the
edition presents its events in story order and on what grounds. Under it the `WorldTime` layer is
the discourse succession and the world order is the presentation order — byte for byte what the
builder used to assume. The Sherlock adapter declares it, and its basis records the exceptions
rather than hiding them: the annotation opens on John's Afghanistan nightmare (rows 8–18, named as
a nightmare at row 21), and carries flashback inserts interleaved with present-tense rows within
390–402 (Sherlock's deduction replays the laboratory meeting) and at 953–954 (the search for the
case); this declaration places all of them where they are shown. No other row is marked out of
order. The fixture atlas's `ViewFingerprint` pinned at `origin/main` 913f3a8e is reproduced
exactly (`SherlockRecallMappingSuite`), and a full run of the diagnostic on development
participant NN03 before and after the change produced a byte-identical report and posterior
sidecar, with the voyage document differing in exactly one field, `provenance.configChecksum`.
No landed Sherlock number or identity moves.

### 3. `Unknown` drops the layer and the order together

No `WorldTime` edges, `worldOrder = None`. Every consumer already reads that as absence rather
than zero: `worldChronology.value` is `None` with zero support, `worldBackwardMass` is `None`,
`SignatureProjection` refuses a projection that weights either, and `WorldTimeSuccessor` is a
measured 0.0. `WorldOrderAbsence` names why: `NotSupplied`, or `EditionNonlinear` when the edition
is known to reorder story time and no rank has been supplied.

### 4. `Explicit` takes both from the rank and from nothing else

A rank over leaf segments keyed by `TimedSegment.ordinal`, ties allowed, because the first
consumer (*Memento*'s `StoryOrderSceneNum`) is a per-subscene label shared within a broad scene and
twice across scenes. It carries a witness like the other declaration, saying who supplied the rank
and from what (a workbook column, a hand ranking), because a reader of provenance can verify a
rank from its digest but cannot learn its origin from it. World-time succession runs from every node at one distinct rank to every node
at the next distinct rank; a tie is a tie and is never broken by presentation order, so an explicit
rank cannot smuggle the discourse clock back in. A group sits at its earliest member's rank (the
minimum, not the first presented member). The rank is checked against the segments it will order:
a leaf without a rank, a rank naming no leaf, or an empty rank is `Left(WorldOrderRefusal)`, never
a partial map.

### 5. The declaration is provenance, not view content

`RecallToVideo.provenanceConfig` renders `worldOrder=<declaration>` into the configuration string
that `VoyageExport.document` hashes into `ViewProvenance`. A run under `Unknown` and a run under
`SameAsPresentation` are different derivations even when every number coincides. The view
fingerprint does not carry the declaration: it hashes what the aligner reads, and the declaration
changes what is read only through the edges and order it yields.

## Consequences

- One production caller (`SherlockAnnotationView.build`) and five test call sites across three
  suites (`RecallToVideoSuite` ×3, `LexicalBlendSuite`, `MonotoneSceneSuite`) declare, plus the
  Sherlock suite through the adapter; the diagnostic prints the declaration at start-up.
- `TimedSegment.ordinal` uniqueness is documented but not enforced by `build`; duplicate ordinals
  would collapse the leaf rank and `segmentByRef` without a refusal. Pre-existing, unreachable
  from the Sherlock adapter (which refuses any break in row numbering), and not closed here.
- A run under `Unknown` has a different `ViewFingerprint` from every landed Sherlock run. That is
  correct: the view's content differs. Nothing landed declares `Unknown`.
- The *Memento* adapter, when it exists, supplies `Explicit` from `StoryOrderSceneNum` for the
  chronological conditions and `Unknown(EditionNonlinear)` or `SameAsPresentation` for the others
  as the study declares; the type makes that choice a written one.
- The collinearity on *Sherlock* is now a declared fact rather than a fabricated one. The double
  weight on a coincident edge is a θ question, not a data question, and belongs to the layer-use
  ledger and θ provenance slice (ADR 0016, planned).
- `provenanceConfig` inherited an existing defect it does not fix: it renders `priorScale=1.0` when
  the environment variable is unset while the run uses `scaledConfig(1.5)`. Recorded for the θ
  provenance slice rather than changed here, so this slice moves no provenance checksum for a
  reason other than the declaration.

## Rejected

- **`Option[Map[SourceNodeRef, Int]]` with `None` meaning unknown.** `InMemorySourceView` already
  has that shape, and the builder filled it silently. An option carries no witness, no reason, and
  no distinction between "declared the same as presentation" and "copied from presentation".
- **A defaulted `SameAsPresentation` parameter.** The fallback with a nicer name: every existing
  caller would compile unchanged and nobody would have declared anything.
- **The declaration inside `ViewFingerprint`.** Would change every landed identity for a change in
  bookkeeping rather than content, and would make two views with identical edges and order
  unequal on the wire.
- **Validating `Explicit` before `build`, in a `WorldRank.from`.** The rank cannot be checked
  against a leaf set that does not exist yet; a pre-validated wrapper would either trust the caller
  to pass the same segments or need the same error channel in `build` anyway.
