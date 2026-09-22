# storymodel4s-codec

Canonical JSON wire seam for storymodel4s artifacts (codec milestone, view-independent half).

## Canonical form

- Object keys sorted; no insignificant whitespace; `null` never written (absent `Option` = absent key).
- `Double` values are `"0x" + 16 lowercase hex digits` of the IEEE-754 bits (`features.CanonicalDouble`),
  so JVM, Scala.js, and Scala Native produce byte-identical text; NaN/±∞/−0.0 round-trip exactly.
  Plain JSON numbers are accepted on input.
- `BigDecimal` is its plain scale-stripped form; integers are JSON integers.
- Parameterless enum cases are their names; parameterized cases are objects tagged by `"type"`.
- Opaque identifiers are strings; sets are sorted arrays; maps keyed by identifiers are objects.
- `core.Address` is exactly its canonical `tag/kind/part…` rendered string; decoding rejects
  malformed strings and non-canonical escape spellings before consumers re-type the address.
- Every top-level artifact carries `schemaVersion`; unknown versions are rejected (`Migration`).
- Every private-constructor type decodes through its smart constructor (`TextSpan.of`,
  `SpanSet.of`, `Credence.from`, `ClaimMeta.of`, `Coverage.of`, `StorySource.fromText` + checksum
  check, `SurfaceAtlas.validated`, `TranscriptAtlas.validated`, `RecallGraph.validated`,
  `ChartValidator.check`), so a decoded value satisfies the same invariants as a constructed one.

## Artifacts

| Type | Notes |
|---|---|
| `StoryModel[S]` | status is not written; decodes to `Draft`; `StoryModelCodec.contentChecksum` is SHA-256 of the canonical text, identical for Draft/Validated/Adjudicated; the atlas is encoded units-only so the text appears exactly once (in `source`) |
| `FeatureTrack[FeatureTarget, Double | String]` | inline scalar/categorical; `Estimate.Missing(reason)` is preserved, never `null`/`0`/`NaN`; vectors only via `SidecarManifest` |
| `SidecarTrack[T, V]` | retains the true `FeatureSpace[V]` while observed values point to compact rows in an `SM4SFT01` binary sidecar; Missing remains explicit and consumes no row |
| `HsmmResult` | schema `hsmm/v4` (`HsmmResultCodec.SchemaVersion`); v2 added required `supportWeight`, v3 added required `imputedTerms`, v4 replaced `supportWeight` with a required tagged `support`. There is no migration from earlier tags — a v1/v2/v3 artifact is re-derived, not upgraded, and a v3 artifact is refused with the typed `CodecError.UnsupportedSchema`. `HsmmResultCodec` writes the sparse posterior, flow, per-cell costs (`terms`, `mode`, `exclusion`, `total`, `missingTerms`, `sourceChartCoverage`, `reductions`, `support`, `imputedTerms`), nominated anchors, gate echo, and context fingerprints; decoding requires the original `RecallGraph` and `SourceView`, rebuilds records through `AlignWire`, then calls `HsmmResult.validated` and `AlignWire.matched`. `support` is `{type: Assessed, share, measuredTerms, eligibleTerms, eligibleWeights}`, `{type: Unestablished, reason, measuredTerms, eligibleTerms, eligibleWeights}` or `{type: NotApplicable, reason}`, each with an exact field set (the artifact, cost entries and costs refuse unknown fields too); the decoder re-derives the share (compared bit for bit) and every reason from the carried basis and the record, so neither is taken from the artifact (bd-01M19956MFSG7076QE4J66T7E9). |
| `PropositionChart` | decodes to `Unchecked` then validates to `Checked` |
| `RecallGraph`, `TranscriptAtlas` | transcript as plain `StorySource` until the `PseudonymizedText` split lands |
| `ClaimLedger` | JSON Lines (`JsonLines.claims` / `readClaims`), append-only |

The committed War of the Ghosts `hsmm/v4` goldens are platform-labelled:
`fixtures/src/test/resources/golden/hsmm-v4-wog.json` is the JVM and Scala.js canonical encoding
and `hsmm-v4-wog.native.json` the Scala Native one, each followed by one terminal newline. The
`hsmm/v3` goldens (`hsmm-v3-wog.json`, `hsmm-v3-wog.native.json`) are kept byte-identical as
refusal fixtures. A v1, v2 or v3 artifact is refused: v2 carries no `imputedTerms`, and the only
value a migration could invent is "nothing was imputed"; a v3 `supportWeight` of `1.0` meant both
"every eligible term was measured" and "nothing was eligible" (every WOG external cell published
it with zero terms), so a migration would have to invent which support state applied. Each is
re-derived from its inputs. Removing support, each backend's v4 golden is its v3 golden exactly.
Scala Native inference can differ in low-order `libm` bits (five posterior/flow leaves on WOG,
one ULP each); this is not hidden as a false byte-identity claim, and each backend is compared
with its own golden. On every platform, the portable guarantee is byte-exact decode and re-encode
of one artifact after contextual validation. The schema does not claim cross-runtime inference
bit-identity.

## Checked mapping records

`MappingCodecs` writes the separate `mapping-record/v0.1` schema. It does not replace
StoryModel or the text-only `hsmm/v4` format. Construct a `MappingResult` with public checked
factories, then use:

```scala
val json: String = MappingCodecs.encode(mapping)
val expected = ExpectedMappingContext(mapping.inventory, mapping.source)
val restored: Either[MappingCodecError, MappingResult] = MappingCodecs.decode(json, expected)
```

For a record with result-bound values, supply
`expected.copy(derivation = Some(DerivationContext(result, checkedRecall, sourceView)))`.
Decode checks the full inventory/source identities, all six binding coordinates, per-row units
and the actual result identity. It re-derives posterior values, gates, fidelity and term support
through the same checked producers. Supplying a context proves a content join; it does not
certify that an estimator executed. There is no context-free Circe decoder.

Raw scores, normalized score mass, transport mass and model posteriors keep distinct types,
normalization scopes and decision bases. Every requested outcome is retained, including failures,
exclusions, abstention and external destinations. A missing candidate is not a measured zero.
`Derived` stage authority, calibrated quantities and measurement compatibility remain reserved.
Historical adaptation uses `Unknown(HistoricalArtifact)` with no invented execution receipts.

Unlike the legacy generic format, every mapping optional value is explicitly tagged, long
coordinates and rational integers are decimal strings, and authoritative Doubles must retain
canonical IEEE-754 hex values. The mapping printer uses ASCII escapes to preserve all UTF-16
code units. Whitespace and object-key order may vary on input; missing fields, duplicate keys
(including escaped aliases), unknown fields (including null values), numeric tick tokens and
unsupported versions refuse. Errors distinguish JSON/schema errors, contract refusals, reserved
authority, missing context and digest/value mismatches.

The source section carries complete multipart identities, declared composition, exact physical
support and partial/unlocated status. It cannot infer cross-part order without a composition or
turn a parent assignment into child values. The mapping record contains no display permission
capability or file-publication manifest; those belong to the checked M1 provider packet.

Portable fixtures pin the authored miniature, composed source and large coordinates identically.
A locally inferred historical fixture pins JVM/JS and Native separately: one published posterior
leaf differs by one ULP, with corresponding result/record identity changes. This is preserved
exactly, not rounded. The existing WOG `hsmm/v4` goldens remain unchanged. See the
[G1 codec evidence](../docs/refactor/evidence/g1-mapping-records-20260922/slice9/receipt.json).

## Numeric sidecars

`SidecarCodec` preserves the original full-fetch `SM4SFT01` format: a 16-byte header (eight-byte
magic/version, then an unsigned 64-bit little-endian payload byte count) followed by finite
row-major Float32 or Float64 values. `SM4SFT02` is its range-loadable blocked form. Its aligned
48-byte header binds payload bytes, dimension, row count, dtype, and an explicit positive
`SidecarBlockRows`; a fixed 32-byte digest per derived block follows, then the same row-major
payload. Blocks contain complete compact observed rows, never semantic Atlas/Codex tiles and never
partial rows. Missing observations still consume no row.

The V2 manifest supplies the checksum of the exact header/index prelude; that expected root is
never read from the file being verified. Each index digest binds the block ordinal, first global
row, row count, and exact bytes, so a fetched block is independently verifiable and cannot be
relocated. Offsets and lengths are derived from the checked manifest rather than accepted from a
file table. The existing manifest checksum retains its meaning over the complete file. Re-blocking
the same logical values is a physical rewrite with a different layout and checksum; callers must
not cache one block size under another's identity. Partial validation returns checked blocks, never
a `FeatureTrack` that would falsely imply the unfetched file was validated.

Both formats deliberately accept only the nonnegative signed-`Long` / `Array[Byte]` capacity subset
of their unsigned header fields. Float32 storage is an explicit quantization recorded by the
manifest dtype; decoding widens those exact Float32 values to Double and never pretends they equal
the unquantized input. Finite Double values smaller than the Float32 subnormal range silently
underflow to `+0.0` or `-0.0`; preserving the resulting raw Float bits, including the sign of zero,
is part of the declared quantization law.

On little-endian browsers, a validated V1 file admits a zero-copy typed-array view at byte offset
16; V2 block ranges begin at checked eight-byte-aligned offsets. A big-endian host must use a
little-endian `DataView`/copy fallback. These plain SHA-256 checks prove integrity relative to a
trusted manifest only: they are not authenticity or confidentiality protection, and they are not a
safe receipt identity for non-public material. Sensitive sidecars still require a separately typed
keyed/encrypted artifact contract.

## Seams (deferred, marked in code)

- `view` specs (`CodexSpec`, `CodexFlow`, `NarrativeScene`): after `view` is committed.
- First-class storage-quantization provenance: a Float32 sidecar currently retains the logical
  derivation and Float64-valued feature-space identity; the manifest dtype is the interim record of
  that materialization choice.
