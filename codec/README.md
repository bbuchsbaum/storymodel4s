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
| `HsmmResult` | schema `hsmm/v4` (`HsmmResultCodec.SchemaVersion`); v2 added required `supportWeight`, v3 added required `imputedTerms`, v4 replaces `supportWeight` with a required tagged `support` assessment and a required result-level `supportBasis`. There is no migration from earlier tags — a v1/v2/v3 artifact is re-derived, not upgraded. `HsmmResultCodec` writes the sparse posterior, flow, costs, support basis, nominated anchors, gate echo, and context fingerprints; decoding requires the original `RecallGraph`, `SourceView`, and an independently admitted `SupportBasis`, rebuilds records through `AlignWire`, then calls `HsmmResult.validated` and `AlignWire.matched`. The embedded basis proves internal consistency only; it is not an execution receipt. |
| `PropositionChart` | decodes to `Unchecked` then validates to `Checked` |
| `RecallGraph`, `TranscriptAtlas` | transcript as plain `StorySource` until the `PseudonymizedText` split lands |
| `ClaimLedger` | JSON Lines (`JsonLines.claims` / `readClaims`), append-only |

The committed War of the Ghosts `hsmm/v4` golden is the JVM/Scala.js canonical encoding. A v3
artifact is refused: the old numeric `supportWeight` cannot reveal whether support was assessed,
unestablished, or not applicable, and inventing any of those meanings would fabricate evidence.
Scala Native inference can differ in low-order `libm` bits; this is not hidden as a false
byte-identity claim. On every platform, the portable guarantee is byte-exact decode and re-encode
of one artifact after contextual validation. The schema does not claim cross-runtime inference
bit-identity.

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
