# storymodel4s

**Pre-release. Nothing is published; APIs will change without notice.**

storymodel4s is a Scala 3 library for representing written stories, human
recall of those stories, and the mapping between the two — including the
Autobiographical Interview, treated as recall against a *latent* source. It is
neurosymbolic by construction: typed propositional structure (an AMR-compatible
local kernel), a multiplex narrative graph with contexts, hierarchy, and typed
temporal/causal relations, and embedding feature views that retrieve but never
adjudicate.

## What it does

- Builds a `StoryModel = (atlas, graph, hierarchy, trajectory, claim ledger)`
  from text, with exact UTF-16 evidence spans on every explicit claim.
- Represents recall as idea units with discourse functions and expressed
  uncertainty, and aligns them to the story as an **unbalanced, hierarchical,
  directed** posterior `P` over source nodes *plus explicit external states*,
  with transition flow `F` over the recall trajectory.
- Derives a recall *signature* (coverage, fidelity facets, localizability,
  compression, chronology deformation, causal preservation, association and
  intrusion mass) — a scalar score is only ever a declared projection.
- Scores Autobiographical Interviews as detail atoms with probabilistic memory
  addresses; traditional internal/external totals are a versioned derived view.

## Smallest example

```scala
import storymodel4s.core.*

val source = StorySource.fromText("wog", WarOfTheGhostsText).toOption.get
val atlas  = SurfaceAnalyzer.analyze(source)
atlas.byKind(SurfaceUnitKind.Sentence).size // exact, offset-anchored sentences
```

A hand-modeled *The War of the Ghosts* (`fixtures`) exercises every difficult
phenomenon the representation must survive; see `docs/plans/` for the roadmap.

## Maturity

M0 (foundation): `core`, `amr`, `story`, `recall`, `align`, `interview` with
hand fixtures and law suites. No text→AMR parser, LLM adapter, or embedding
backend is included yet; those are provider interfaces (M2).

## Build and verify

Scala 3.7.4, sbt 1.12.14.

```
sbt testJVM      # fast loop
sbt checkAll     # scalafmt + compile + test on JVM, Scala.js, Native
```

Apache-2.0.
