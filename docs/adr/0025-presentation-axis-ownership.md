# ADR 0025 — Presentation axis ownership for source streams

**Status:** Proposed 2026-10-23. Not accepted. It needs an owner decision (AGENTS.md SD5).

**Decider:** the owner

**Tracker:** `bd-01M5MZ8J3KV0S1JY45737X4WSH`. The consumer fixes for option (c) have landed; this
ADR is the durable `core` fix the owner chose to do later.

## Context

A `PresentationAxis` records the bundle that owns it, and its id is a fingerprint of that bundle
(`core/source.scala`, `PresentationAxis.fingerprintOf`). A `SourceStream`, however, keeps only a
`PresentationAxisId` as its native axis, and `SourceStream.of` accepts any id.
`SourceBundle.validate` checks mapping axes but not stream native axes. Because `EvidenceSupport`
admits an anchor whose axis equals the stream's native axis, a bundle B2 can put a stream on film
F1's primary axis. F1's playback intervals then anchor in B2, and two films appear to share footage
time.

What has been measured:

- **Permits and produces.** A borrowed-axis film builds through the public API (tests in
  `document/.../ReferentIdentitySuite.scala` and `align/.../BorrowedAxisSuite.scala`).
- **No production path creates one today.** Only `core`'s own helpers call `SourceStream.of`, and
  each puts the stream on its own bundle's axis. The only `SourceBundle.of` outside `core` is the
  Sherlock composition, which declares a `TrackComposition` for every part axis. `codec` does not
  decode bundles.
- **Two consumers failed open and are now guarded.** `document` referent overlap requires the same
  bundle (`9252b8129`). `align` `SourceRepresentation.of` ties a part role to the part itself or to
  the part's own streams, and a composition role to the declared composed bundle (`0f84d3ce7`).
- **Bundle ids ignore native axes.** `SourceBundle.computeId` hashes stream id, kind and checksum.
  So a composed bundle's axis id can be derived before any stream is bound to it, and a part can
  carry a stream natively on the composed axis. The full `SourceBundle.identity` does include
  native axes.
- **The strict rule breaks the D1a model.** Requiring every native axis to be the primary axis or
  an axis named by one of the bundle's mappings failed 12 `core` tests. `D1aSupportSuite` and
  `D1bPointSupportSuite` deliberately build a secondary stream on its own unaligned clock, using
  another film's primary axis as a convenient stand-in, and add mappings later. An unaligned
  secondary clock is a legitimate state that this rule would make unrepresentable.

Every consumer that treats an equal axis as the same footage has to remember the hole. Two did not.

## Decision (proposed)

### 1. A stream's native axis comes from one of three sources

1. **The bundle's primary axis.**
2. **A stream-native axis.** It is minted for one stream of one bundle by a new constructor,
   `PresentationAxis.streamNative(bundle, stream, kind, extent, timebase)`. Its fingerprint includes
   the bundle's content-addressed id and the stream id, so it cannot coincide with any other
   bundle's axis. This is the lawful home of an unaligned secondary clock. There is no cycle:
   `computeId` does not read native axes, so the bundle id exists before the axis is minted.
3. **A foreign axis named by one of the bundle's own mappings.** This is the composition case.
   Whether those streams really are a part's streams remains `DeclaredComposition.of`'s check
   (stream equality and extents), and consumers that need "same footage" rely on it as `align` now
   does.

### 2. `SourceBundle.of` refuses every other native axis

A new invariant, `bundle/stream-axis`, refuses a stream whose native axis is none of the three.
Its error names the stream and the axis.

### 3. Consumers keep their guards

The `document` and `align` checks stay. Rule 3 still lets a bundle self-declare a composition over
a foreign axis, and only `DeclaredComposition.of` can show that the streams are the part's own. The
core rule removes the unmapped case. The consumer guards cover the mapped-but-undeclared case.

## Rejected alternatives

1. **Strict mapping rule only** (primary axis or a mapping axis). It makes an unaligned secondary
   clock unrepresentable, and it broke 12 D1a tests that model exactly that state.
2. **Store the full `PresentationAxis` in `SourceStream`.** The owning bundle would then travel
   with the stream. But a stream's owner is not known when the stream is built, because bundle ids
   are computed from streams. It is also a wider API change than one constructor plus one
   invariant. Revisit it if rule 2 proves insufficient.
3. **Leave `core` unchanged and guard consumers only.** That is the current state, chosen for now.
   It relies on every future consumer remembering, and the survey found two that had not.
4. **Include native axes in `computeId`.** It would close the composed-axis prediction route but
   not the borrowed-axis route, and it would change every bundle id.

## Consequences

- `core` gains public vocabulary (`PresentationAxis.streamNative`) and one validation invariant.
  The reference scope is wide, and the `storyatlas4s` sibling build applies.
- The 12 D1a and D1b fixtures move their secondary streams to stream-native axes. Their intent is
  unchanged: support on an unmapped secondary clock still does not reach the primary axis without a
  `ClockRepair`. The rewrite is a change of fixture, not a weaker test.
- Bundles from production helpers and the Sherlock composition are unaffected. A migration probe
  must show this before landing: rebuild the production fixtures and the persisted study inputs
  that construct bundles.
- `SourceBundle.identity` already covers native axes, so receipts that bind full identities do not
  change. Short `SourceBundle.id` values do not change either.

## Questions for the owner

1. Accept stream-native axes (rule 2) as the lawful home of an unaligned clock? Or require every
   secondary stream to declare a `ClockRepair` to the primary axis from the start, which is stricter
   but makes "clock not yet repaired" unrepresentable?
2. Should a self-declared `TrackComposition` (rule 3) also require that the bundle's composed
   streams equal some other bundle's streams, which would move the stream-identity check into
   `core`? That needs part bundles at construction time, which `SourceBundle.of` does not have
   today.
3. Land this before or after S3c? S3c builds the per-film identity graph and will construct media
   bundles.
