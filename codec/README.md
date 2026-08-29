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
| `PropositionChart` | decodes to `Unchecked` then validates to `Checked` |
| `RecallGraph`, `TranscriptAtlas` | transcript as plain `StorySource` until the `PseudonymizedText` split lands |
| `ClaimLedger` | JSON Lines (`JsonLines.claims` / `readClaims`), append-only |

## Numeric sidecars

`SidecarCodec` writes a 16-byte header (ASCII `SM4SFT01`, then an unsigned 64-bit little-endian
payload byte-count field) followed by finite row-major Float32 or Float64 values. This portable
implementation deliberately accepts only the nonnegative signed-`Long` / `Array[Byte]` capacity
subset of that field. The manifest checksum covers the complete file. Float32 storage is an
explicit quantization recorded by the manifest dtype; decoding widens those exact Float32 values
to Double and never pretends they equal the unquantized input.

On little-endian browsers, a validated file admits a zero-copy typed-array view at byte offset 16.
A big-endian host must use a little-endian `DataView`/copy fallback. The checksum proves full-file
integrity only: it is not confidentiality protection and does not authenticate individual range
fetches. Sensitive sidecars require the separately tracked keyed/encrypted artifact contract.

## Seams (deferred, marked in code)

- `view` specs (`CodexSpec`, `CodexFlow`, `NarrativeScene`): after `view` is committed.
- `HsmmResult`: after the held W1 anchor/fidelity-mode branch merges (`AlignState` shape changes).
