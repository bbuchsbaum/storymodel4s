# Documentation research brief

Research baseline: `51facdaacf75524e747faa4f53dad276cd40430d` (2026-08-30).

This file is an authoring ledger, not a public page. It records why the first site says what it
says and, equally important, what it does not say.

## Reader and journey

- Primary reader: a methods-minded cognitive scientist asking whether the representation keeps
  evidence, uncertainty, and time distinct.
- Secondary route: a Scala developer evaluating the source API.
- Separate public route: a collaborator learning contribution expectations without exposing
  internal board or governance records.
- Order: orientation, one honest success, concepts, source-use guide, decision records.

## Publishable claims

| Claim | Grade | Primary source at baseline |
| --- | --- | --- |
| The repository is pre-release and has no published artifact. | Confirmed | `README.md` |
| Raw text does not yet become a complete model automatically. | Confirmed | `README.md`; `docs/adr/0005-surface-to-narrative-compiler.md` |
| The WOG model is a hand-authored narrative acceptance fixture, not gold AMR. | Confirmed | `fixtures/src/main/scala/storymodel4s/fixtures/wog/WarOfTheGhostsModel.scala` |
| The WOG expectation ledger contains 12 plain-language phenomena. | Confirmed | `WarOfTheGhostsExpectations.scala`; `WarOfTheGhostsSuite.scala` |
| The fixture acceptance test currently passes. | Qualified until an exact-candidate clean run is recorded | `WarOfTheGhostsSuite.scala` plus local command receipt |
| Context, polarity, modality, evidence spans, and story-world/discourse order are separate typed structures. | Confirmed | `story/` and `core/` source; WOG acceptance suite |
| Embeddings are sidecar feature views and never adjudicate claims. | Confirmed design/API fact | `docs/adr/0001-embedding-contract-and-hard-gate.md`; `embed-core/` |
| A pinned local ONNX adapter exists. | Confirmed source fact only | `embed-onnx/README.md`; `build.sbt` |
| ONNX or other embeddings improve recall alignment. | Not publishable | No admitted calibrated result establishes this. |
| Complete automatic story construction works. | Intent only | Explicitly unfinished in `README.md`. |

## Deliberate exclusions

- Do not render or link `docs/plans/`, `docs/reviews/`, `AGENTS.md`, or the Mote board.
- Link all seven ADRs at exact revision; do not reproduce them.
- Treat `docs/design/` case by case. This first slice cites none of it publicly.
- Do not call hashed n-gram or TF-IDF distance semantic evidence.
- Do not imply a package can be added from Maven; no artifact has been released.
- Do not imply the WOG fixture was automatically acquired or is AMR gold.

## Framework experiment

Candidate: Starlight on Astro. The guide remains authored under `src/content/docs/`; raw artifacts
enter only through `public/`. Acceptance command:

```sh
STORYMODEL4S_GRAKERN_BUILD=/path/to/grakern npm run verify
```

The experiment passes only when every manifested Scala program compiles, runs, and byte-matches its
recorded stdout; Astro checks and builds; figures remain tied to their data; provenance mutation
checks pass; and the raw artifact reaches the build byte-for-byte. Record the final receipt in Mote.

## API friction ledger

| Workflow | Evidence | Reader expectation | Current cost | Recommendation | Compatibility | Documentation now |
| --- | --- | --- | --- | --- | --- | --- |
| Create a complete story model from prose | The site needs `WarOfTheGhostsModel.model`; the public surface/compiler programme is unfinished | Supply text and receive a reviewable draft model | Readers must start from a hand-authored fixture or compose lower-level stages | Finish the evidence-backed acquisition and compilation programme behind a separate design gate | New orchestration API and artifact schema | State the fixture boundary on the landing and story pages |
| Represent a recall for a terminal analysis | `RecallSegmenter.segment` returns one checked graph without a policy receipt or retained segmentation alternatives | Inspect and compare plausible boundaries and proposition analyses | One heuristic output can look settled after structural validation | Add a typed segmentation proposal/resolution artifact under the Code Red PRD and ADR process | New public vocabulary and codec migration | Call the current segmenter a heuristic segmentation-conditioned baseline |
| Compute a local embedding | `UseEmbeddings.scala` needs 107 nonblank lines for the full privacy/receipt path; the local batch itself is shorter | Embed public text with a small checked call | Space lookup, batch validation, and outcome handling obscure the first local operation | Audit a lawful local convenience path while retaining explicit remote custody | Additive API if identity and failures remain explicit | Teach the short deterministic path before remote authorization |
| Compose document identity | `ComposeDocument.scala` manually assembles chart-node and typed mention IDs before quotienting | Connect repeated mentions across sentence charts | Necessary identity concepts arrive before the scientific result | Explore constructors derived from admitted charts and resolver output | Additive API with identity consequences | Show the full current construction and the preserved local charts |
| Build feature windows | `WindowFeatures.scala` manually creates space, provenance, observations, plan, reducer, and missingness policy | Attach a lexical measure and compare window summaries | Eight public concepts precede the first result | Consider a checked builder for common scalar lexical tracks | Additive API; derived-space identity must stay exact | Explain each layer through the coverage-policy difference |
