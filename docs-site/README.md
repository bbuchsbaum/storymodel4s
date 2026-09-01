# storymodel4s documentation site

This is the authored, human-facing documentation site. It does not ingest the repository's
internal `docs/` tree. Public pages cite primary source files at the exact source revision instead.

## Local build

```sh
npm ci
npm run verify
```

Set `STORYMODEL4S_GRAKERN_BUILD` to the pinned local grakern checkout before running the gate.
`npm run verify` compiles and runs every program in `examples/manifest.json`, compares stdout
byte-for-byte with its recorded output, type-checks and builds the Astro site, and proves that
`public/artifacts/verbatim-proof.html` reached `dist/` byte-for-byte.

The visible build receipt uses `git rev-parse HEAD` by default. Reproducible builders may set
`DOCS_SOURCE_REVISION` and `DOCS_GENERATED_AT` explicitly.

## Editorial contract

- `Confirmed`: directly inspectable at an exact source revision.
- `Qualified`: observed under stated limits, without publication-grade independent evidence.
- `Intent`: design direction or unfinished capability.

Every material present-tense claim carries a source link or an evidence note. Pre-release status,
unstable APIs, hand-authored fixtures, and the unfinished raw-text-to-model path remain visible.
