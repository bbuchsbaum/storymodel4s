# codec milestone — the wire seam for the Narrative Codex, the Atlas, and the CLI

- Bead: `bd-01M14K855QC1VB6H17S4W9FCS2` (proposed owner: `codex-storymodel-release`;
  M1 W6 item 14). Authored by `claude-storymodel4s` per ADR 0002 §8.
- Status: specification. The `codec` module exists in `build.sbt` (circe-core /
  circe-parser, cross JVM/JS/Native) and contains no sources.
- Consumers: `view` (static Codex edition, Atlas tracer), the M1 CLI
  (`build / validate / inspect / diff`), the browser app in `storyatlas4s`,
  and snapshot-diff tests across model versions.

## 1. What must be encodable

Ordered by need. Items 1–4 unblock slice 1; 5–6 unblock recall views.

1. **Addresses** (`core.Address`): as the canonical rendered string
   `tag/kind/part…` — never as an object. `Address.parse` is the decoder. Every
   module's typed ref (`CoreRef`, `StoryRef`, `FeatureAddress`, `DocRef`,
   `RecallRef`, `AlignRef`) crosses the wire *only* as an `Address`; the
   consumer re-types through its `Addressable.parse`. This keeps codec free of
   per-module ref codecs and makes the closed `ViewRef` a `view`-side concern.
2. **`StoryModel[Validated]`** in full: `source` (canonical text + checksums),
   `atlas` (units, spans), `graph` (nodes, contexts, all relation layers with
   `ClaimMeta`), `hierarchy` (containment + boundary beliefs), `trajectory`,
   `featureSpaces`, `sidecars`, `featureRefs`, `descriptors`, `hypotheses`,
   `sensoryProfiles`, `receipt`. Decoding yields `StoryModel[Draft]`; the
   consumer revalidates to recover `Validated` (status is never trusted from
   the wire).
3. **Feature tracks** (`features.FeatureTrack[T, V]`) for scalar and
   categorical values inline; vector values by `SidecarManifest` + a separate
   binary column (see §3). `Estimate.Missing(reason)` must survive the round
   trip as `Missing`, never as `null`/`0`/`NaN`.
4. **View state and specs** (from `view`): `CommonViewState`, `CodexSpec`,
   `AtlasSpec`, `PaginationSpec`, `LayoutReceipt`, and the artifacts
   `CodexFlow` and `NarrativeScene`. codec therefore `dependsOn(view)`; view
   never depends on codec (ADR 0002 D14).
5. **`RecallGraph`** (transcript atlas, units, relations).
6. **`HsmmResult`**: `posterior: AlignmentMatrix` (rows as sparse
   `state-address → mass` maps, keyed by the `AlignStateKey` rendering, not by
   `AlignState.key`), `flow: TransitionFlow`, `viterbi`, `logLikelihood`,
   `costs` (sparse), `refinementPasses`.

Not encoded: anything JVM-only (grakern/graph4s objects), provider clients,
caches. `BuildReceipt`/`ExtendedBuildReceipt` are data and are encoded.

## 2. Contract

- **Canonical JSON**: object keys sorted; no insignificant whitespace in the
  canonical form; doubles through `features.CanonicalDouble` (platform-stable
  rendering, JVM = JS = Native); integers as JSON integers; `Option` as absent
  key, never `null`; enums as their case names; opaque ids as strings.
- **Checksum stability**: `Checksum.ofText(encodeCanonical(x))` is identical
  on JVM, JS, and Native for every fixture (this is what makes snapshots and
  the `diff` command meaningful).
- **Schema version** at the top of every document (`schemaVersion`), matching
  `StoryModel.schemaVersion`; decoders reject unknown major versions.
- **No copied text**: annotations, refs, and tracks carry spans/addresses,
  never substrings of `canonicalText`; `canonicalText` appears exactly once,
  in `source`.
- **Sensitive material**: transcripts may be pseudonymized; the codec encodes
  whatever `PseudonymizedText` type ADR 0001 (P0-4) settles on and must not
  serialize a reidentification key alongside it. Until that type exists, the
  codec treats recall transcripts as plain `StorySource` and the ADR 0001
  disposition adds the split.

## 3. Sidecar columns

Dense numeric data (vector embeddings, long scalar tracks) go in a sidecar
keyed by `SidecarManifest.checksum`: a length-prefixed little-endian
`Float32`/`Float64` column with the manifest's `dtype`/`layout`. JSON carries
the manifest only. In the browser this is a `Float32Array` over the fetched
bytes; on the JVM a `ByteBuffer`. Arrow IPC is explicitly deferred (ADR 0002
D7) — this format is the minimum that a static artifact needs.

## 4. Laws (module `laws`, Discipline rule sets)

- `decode(encode(x)) == x` for every type in §1, on generated models
  (`StoryGens`, `AlignGens`, `AddressGens`) and on the WOG fixture.
- `encode(decode(encode(x))) == encode(x)` (canonical form is a fixed point).
- `Checksum.ofText(encode(x))` equal across platforms for the fixtures
  (golden files under `fixtures`).
- `Address` wire law: `Address.parse(render(a)) == Right(a)` (already in
  `AddressableLaws`; codec reuses it).
- Missingness law: an `Estimate.Missing(r)` observation decodes to
  `Missing(r)` with the same reason.
- Compile invariance (ADR 0002 §8):
  `compile(decode(encode(m)), s, spec) == compile(m, s, spec)` for the Codex
  compiler on the WOG fixture, once `view` exists.

## 5. Static-artifact layout (slice 1 deliverable)

```text
<story>/
  model.json          StoryModel[Validated] (canonical JSON)
  tracks/<space>.json FeatureTrack per space (scalar/categorical)
  sidecars/<checksum>.bin
  views/<name>.json   CommonViewState + CodexSpec (+ LayoutReceipt when placed)
  codex.html          the static edition (DOM text + inline SVG), generated by view + app
  receipt.json        BuildReceipt / ExtendedBuildReceipt
```

`inspect` produces this directory; `diff` compares two `model.json` files by
claim address; `validate` revalidates a decoded model.

## 6. Sequencing

1. `Address` + `StoryModel` + `FeatureTrack` codecs with round-trip laws on
   generated models (unblocks `view`'s WOG textual twin and the CLI).
2. `view` types (after `bd-01M14K858TQQH2A46GZBE479YR` lands).
3. `RecallGraph` + `HsmmResult` (slice 4 needs; cheap to do early).
4. Golden checksums per platform in `fixtures`.

Open question for the owner: whether `codec` should also emit the
`NARRATIVE_PROCESS_ALIGNMENT_NOTES.md` §31.5 `render/` artifacts (`summary.md`,
`graph.dot`, `timeline.json`); recommendation — no, those are `view` textual
twins, not codec.
