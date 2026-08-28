# storymodel4s

**Pre-release. No library artifact has been released; APIs will change without
notice.**

storymodel4s is a Scala 3 library for representing written stories, human
recall of those stories, and the mapping between the two — including the
Autobiographical Interview, treated as recall against a *latent* source. It is
neurosymbolic by construction: typed propositional structure (an AMR-compatible
local kernel), a multiplex narrative graph with contexts, hierarchy, and typed
temporal/causal relations, and embedding feature views that retrieve but never
adjudicate.

Read the project [vision](vision.md) and [mission](mission.md) for the scientific
object, operating commitments, and the boundary between current capabilities
and programme work.

## What it does

- Defines and validates a
  `StoryModel = (atlas, graph, hierarchy, trajectory, claim ledger)`, with exact
  UTF-16 evidence spans on every explicit claim. Complete automatic construction
  from raw text is programme work; current story fixtures are hand modeled.
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
backend is included yet; those are provider interfaces (M1, the unattended vertical slice).

## Build and verify

Scala 3.7.4, sbt 1.12.14.

```
sbt -Dstorymodel4s.grakern.build=/path/to/grakern testJVM      # fast loop
sbt -Dstorymodel4s.grakern.build=/path/to/grakern checkAll     # scalafmt + compile + test on JVM, Scala.js, Native
```

The `-D` (or `STORYMODEL4S_GRAKERN_BUILD`) override points the JVM-only
`embed-grakern` project at a local checkout of the in-house `grakern` library,
which is consumed as an immutable SHA source pin but is not yet published or
pushed; grakern's own build fetches its graph4s/gale pins from GitHub.

Apache-2.0.
