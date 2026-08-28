# storymodel4s-core

Identity, evidence, and provenance primitives shared by every other module.

- `ids.scala` — opaque identifiers (`StoryId`, `EntityId`, …), kind-phantom
  `MentionId[K]` / `CanonicalId[K]`, `Probability`, `Credence`.
- `span.scala` — `TextSpan` (UTF-16, half-open), `SpanRef`, nonempty sorted `SpanSet`.
- `atlas.scala` — `StorySource` (raw + canonical text with checksums), `SurfaceUnit`,
  `SurfaceAtlas` with validated invariants and indexes, and the deterministic
  `SurfaceAnalyzer` (paragraphs / sentences / tokens; no NLP dependency).
- `claim.scala` — `EpistemicStatus`, `Evidence`, `ClaimMeta`, `Resolved[A]`,
  append-only `ClaimLedger`. Law: surface-explicit claims cite spans.
- `provenance.scala` — `Fingerprint`, `ProviderCall`, `Provenance`, `BuildReceipt`.
- `hash.scala` — pure-Scala SHA-256, `Checksum`, `ContentAddress`, `DeterministicId`.

Portable: compiles for JVM, Scala.js, and Scala Native; depends only on cats-core and
cats-collections.
