# storymodel4s

**Pre-release. No library artifact has been released; APIs will change without
notice.**

storymodel4s is a Scala 3 library for representing written stories, human
recall of those stories, and the mapping between the two.

A story says some things and reports others; a person recalling it may preserve
an event but reverse who acted, or promote something a character merely said
into something that happened. The library keeps those apart, and every claim it
records cites the sentence it came from.

It also treats the Autobiographical Interview as recall against a *latent*
source. It is neurosymbolic by construction: typed propositional structure (an
AMR-compatible local kernel), a multiplex narrative graph with contexts,
hierarchy, and typed temporal/causal relations, and embedding feature views that
retrieve but never adjudicate.

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
  intrusion mass) — a scalar score is only ever a declared projection. These
  are computed outputs, not validated detectors: the distorted fidelity modes
  and facets (role reversal and modality among them) and intrusion mass have no
  efficacy evidence. Apart from hand-authored War of the Ghosts foils scored as
  diagnostics (`WogDiagnostic`, [embed-bench](embed-bench/README.md)), none has
  been tested against labelled errors in real recall, and the external-floor
  [calibration court](docs/design/external-floor-calibration-court.md) behind
  intrusion mass has not been run.
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

Twenty-four build modules are present, sixteen cross-platform (JVM, Scala.js,
Native) and eight JVM-only:

- evidence and semantics: `core`, `proposition`, `amr-interop`, `features`,
  `acquire`, `story`, `document`, `recall`, `align`, and `interview`;
- portable embeddings, outputs and corpus intake: `embed-core`, `view`,
  `codec`, and `corpus`;
- verification material: `fixtures` and `laws`;
- JVM-only adapters, orchestration and evaluation: `provider-parser`,
  `provider-agent`, `embed-grakern`, `embed-onnx`, `embed-bench`, `media`,
  `pipeline`, and `corpus-intake`.

`embed-core` includes deterministic hashed n-gram and TF-IDF baselines;
`embed-grakern` provides the JVM structural channel; `embed-onnx` runs a pinned
sentence encoder locally and offline, with committed model and tokenizer
checksums. That encoder has been compared with lexical rankers, including
against the hashed n-gram and TF-IDF baselines on the War of the
Ghosts cases (`embed-bench/src/test/resources/onnx/wog-minilm-comparison.txt`),
and against Okapi BM25 on 11 Sherlock development participants with the
sequential model removed, where it ranked better alone and a blend beat both
([study log, "Diagnosis 2"](docs/plans/2026-09-02-recall-to-video-study-log.md#diagnosis-2-the-retrieval-channel-measured-with-the-sequential-model-removed)).
Both are diagnostics (a hand-declared fixture and a gold-free ordering proxy),
so no claim is made here about what the encoder improves.
`provider-agent` puts a hosted model behind the parser transport (ADR 0008)
and `pipeline` runs a text file through it to a `StoryModel` on disk
(ADR 0009); neither has an established quality claim. The repository does not
yet include a remote (HTTP) embedding provider or calibrated production
defaults, and complete automatic construction remains programme work.

## Build and verify

Scala 3.7.4, sbt 1.12.14.

```
sbt testJVM      # fast loop
sbt checkAll     # compile + test on JVM, Scala.js, Native, then scalafmt
```

The JVM-only `embed-grakern` project consumes the in-house `grakern` library as
an immutable SHA source pin. grakern has no published artifacts, so sbt clones
the pinned revision from `github.com/canardlapin/grakern` (CI is configured to
build this way).
`-Dstorymodel4s.grakern.build=/path/to/grakern` (or
`STORYMODEL4S_GRAKERN_BUILD`) substitutes a local checkout. grakern's own build
fetches its graph4s/gale pins from GitHub.

Apache-2.0.
