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
- Compiles validated models into renderer-neutral Codex and Atlas views whose
  marks preserve evidence, uncertainty, and model addresses.

## Smallest example

```scala
import storymodel4s.core.*

val source = StorySource
  .fromText("One night two young men left Egulac.", title = Some("A short story"))
  .toOption
  .get
val atlas  = SurfaceAnalyzer.analyze(source)
atlas.byKind(SurfaceUnitKind.Sentence).size // 1
```

A hand-modeled *The War of the Ghosts* (`fixtures`) exercises every difficult
phenomenon the representation must survive; see `docs/plans/` for the roadmap.

## Receipts and privacy

Embedding, cache, and remote-policy decisions carry typed receipts. Public
material keeps reproducible plain content identities. Computed internal or
sensitive material instead requires a caller-supplied `SensitiveKeyProvider`
and uses keyed HMAC-SHA256 identities; a missing key is a typed failure, never a
fallback to a public hash. Remote authorization accepts only
detector-certified `PseudonymizedText`. The trusted detector is constructed from
a typed whole-word table, not an executable callback, so its recorded identity
determines its behaviour. See [ADR 0001](docs/adr/0001-embedding-contract-and-hard-gate.md).

## Visualization seam

The portable `view` module compiles the model's actual epistemic state into
renderer-neutral `CodexFlow` and `NarrativeScene` artifacts. Layout, SVG/DOM
rendering, and the application live in the separate, currently unpublished
`storyatlas4s` sibling; storymodel4s never depends on it. See
[ADR 0002](docs/adr/0002-visualization-contract.md) and the
[application scaffold](docs/plans/2026-08-28-storyatlas4s-scaffold.md).

## Maturity

Seventeen build modules are present:

- evidence and semantics: `core`, `proposition`, `amr-interop`, `features`,
  `acquire`, `story`, `document`, `recall`, `align`, and `interview`;
- portable embeddings and outputs: `embed-core`, `view`, and `codec`;
- verification material: `fixtures` and `laws`;
- JVM-only structural work: `embed-grakern` and `embed-bench`.

`embed-core` includes deterministic hashed n-gram and TF-IDF baselines;
`embed-grakern` provides the JVM structural channel. The repository does not yet
include an unattended text→AMR parser, an LLM acquisition adapter, an HTTP/ONNX
provider, or calibrated production defaults. Complete automatic construction
remains programme work.

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
