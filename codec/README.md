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
| `PropositionChart` | decodes to `Unchecked` then validates to `Checked` |
| `RecallGraph`, `TranscriptAtlas` | transcript as plain `StorySource` until the `PseudonymizedText` split lands |
| `ClaimLedger` | JSON Lines (`JsonLines.claims` / `readClaims`), append-only |

## Seams (deferred, marked in code)

- `view` specs (`CodexSpec`, `CodexFlow`, `NarrativeScene`): after `view` is committed.
- `HsmmResult`: after the held W1 anchor/fidelity-mode branch merges (`AlignState` shape changes).
