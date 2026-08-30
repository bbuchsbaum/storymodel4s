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
npm run build
npm run verify:verbatim
```

The experiment passes only when source and built byte counts and SHA-256 digests are identical.
Record the final digest in the Mote evidence post; do not hard-code a pre-build result here.
