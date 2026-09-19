# D1A S0 text parity pins

`d1a-s0-text-parity.json` freezes the production text path at `27ebdb71` (19 September 2026).
`TextParitySuite` checks it; there is no automatic update mode. Later D1A slices must preserve
these bytes and values. A deliberate compatibility break needs a separate decision and a
new baseline, not an update made to get a failing test through.

The inputs are the existing 50-sentence `WarOfTheGhostsText.text` fixture and all fifty
`recordings/wog-captured` replies. The suite uses replay, an empty environment, time
`1700000000000`, no title and no feature requests. It exercises `StoryPipeline.run` and the
pure compiler. No provider request is made. This is the fixture literal, not the distinct
header-bearing admitted text file. The replay remains a draft: 65 situations, 23 entities,
6 contexts, 1 segment, 84 gaps and 3 validation violations.

The pins cover:

- Compiler fingerprint and candidate-set identity, canonical model content checksum,
  exact model file bytes and exact `derivation.json` bytes.
- Actual private compiler `source-support/v2` and `evidence/v2` renderings, read through
  a small JVM test adapter. Their selected input identities are recorded. A public emitted
  entity mention ID and the existing unwrapped text-support wire encoding are also pinned.
- The production graph's discourse order, plus test-computed reference lists for all four
  node families in the declared order `(support.minSpan.start, support.minSpan.endExclusive, id)`.
  The later D1A projection API must consume these same expected lists. Today only the
  situation discourse order observes a production ordering API; map iteration order is not
  a contract. In this implementation `minSpan` is the support hull.
- The SHA-256 of the draft Atlas `NarrativeScene.textualTwin` in UTF-8 with no appended
  newline, using the scene/hidden/selected spec, empty state and `d1a-s0-text-parity`
  renderer identity. This is a semantic view artifact, not a browser screenshot.
- The exact two files in `runs/2026-09-02-wog-record-1`, their byte checksums, and the
  decoded parser/build receipt content checksums. That historical record contains neither
  a model nor a derivation artifact. Its hashes do not stand in for missing output bytes.

Cross-platform model encoding already has a literal checksum witness in
`codec/src/test/scala/storymodel4s/codec/StoryModelCodecSuite.scala`, on JVM, JS and Native.
`fixtures/.../WarOfTheGhostsCodecGoldenSuite.scala` separately checks HSMM encoding and
round trips; despite its name it does not assert its checksum constant or read its JSON golden.
Those checks are not duplicated here. **The S0 compilation witness is JVM-only**; neither JS/Native compilation
parity nor scientific correctness of the machine interpretation is claimed.

Required mutation witnesses: shift a captured fixture alignment to an adjacent token;
emit `anchors: null`; change the existing text-support encoding; change the compiler's
`source-support/v2` span rendering. Each must compile, fail a named S0 assertion, and leave
the independent historical-receipt control passing. Qualification receipts belong in
`docs/refactor/evidence/d1a-s0-text-parity-20260919/`.
