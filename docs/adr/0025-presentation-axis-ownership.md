# ADR 0025 — Presentation axis ownership for source streams

**Status:** Accepted 2026-10-23 (AGENTS.md SD5). The owner chose the answers recorded under
"Owner decisions".

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
  the part's own streams, and a composition role to the declared composed bundle (`f45bad2f9`,
  `cb9f61272`, witnessed in `0f84d3ce7`).
- **Bundle ids ignore native axes.** `SourceBundle.computeId` hashes the edition, source and axis
  kinds, authority tracks, and each stream's id, kind and checksum, but no native axis.
  So a composed bundle's axis id can be derived before any stream is bound to it, and a part can
  carry a stream natively on the composed axis. The full `SourceBundle.identity` does include
  native axes.
- **The strict rule breaks the D1a model.** Requiring every native axis to be the primary axis or
  an axis named by one of the bundle's mappings failed 12 `core` tests. `D1aSupportSuite` and
  `D1bPointSupportSuite` deliberately build a secondary stream on its own unaligned clock, using
  another film's primary axis as a convenient stand-in, and add mappings later. An unaligned
  secondary clock is a legitimate state that this rule would make unrepresentable.

Every consumer that treats an equal axis as the same footage has to remember the hole. Two did not.

## Decision

### 1. A stream's native axis comes from one of three sources

1. **The bundle's primary axis.**
2. **A stream-native axis.** It is minted for one stream of one bundle by a new constructor,
   `PresentationAxis.streamNative(bundle: SourceBundleId, stream: StreamId, kind: StreamKind,
   extent, timebase)`. Every input is public, so the constructor alone proves nothing: another
   bundle could pass F1's id and mint F1's axis. What makes it unforgeable is the check in §2:
   `SourceBundle.of` recomputes the expected axis from its **own** computed id and the stream's own
   id, kind, extent and timebase, and compares it with the stream's native axis. Every input must
   therefore be recoverable from the stream and the bundle being built, which is why `kind` is the
   stream's `StreamKind` and not a caller-chosen axis kind. There is no cycle: `computeId` does not
   read native axes, so the bundle id exists before the axis is minted, and recomputing at `of`
   reads only those inputs.
3. **A foreign axis named by one of the bundle's own `TrackComposition` mappings** (owner decision
   2). This is the composition case.
   Whether those streams really are a part's streams remains `DeclaredComposition.of`'s check
   (stream equality and extents), and consumers that need "same footage" rely on it as `align` now
   does.

### 2. `SourceBundle.of` refuses every other native axis

A new invariant, `bundle/stream-axis`, refuses a stream whose native axis is none of the three.
For a stream-native axis it compares the recomputed id described in §1. Its error names the stream
and the axis. A court must kill removal of the check from outside `core`, with a borrowed
`streamNative(F1.id, …)` stream as the refused case and a lawful stream-native secondary clock as
its positive control (design rule 8).

The guarantee holds up to the content address: ids keep 12 hex characters (48 bits), so a
deliberate second preimage against another bundle's axis id costs about 2^48 hashes, the same bound
bundle ids already have. `streamNative` uses its own fingerprint tag, `stream-native-axis/v1`, so it
never shares a layout with edition or text axes. Two bundles with the same short id (same edition,
kinds, authority tracks and stream id, kind and checksum, but different native axes or mappings) can
lawfully reproduce each other's stream-native axes; consumers that must tell such bundles apart
compare full `identity`, never `id`.

Producers need the prospective bundle id before minting. `computeId` is `private[core]`, so a public
`SourceBundle.prospectiveId(edition, sourceKind, streams, axisKind, authorityTracks)` is part of
this decision, next to the existing `editionPlaybackAxis`, which already derives one for film
editions.

### 3. Consumers keep their guards

The `document` and `align` checks stay. Rule 3 still lets a bundle self-declare a composition over
a foreign axis, and only `DeclaredComposition.of` can show that the streams are the part's own. The
core rule removes the unmapped case. The consumer guards cover the mapped-but-undeclared case.

## Rejected alternatives

1. **Strict mapping rule only** (primary axis or a mapping axis). It makes an unaligned secondary
   clock unrepresentable, and it broke 12 D1a tests that model exactly that state.
2. **Store the full `PresentationAxis` in `SourceStream`.** The owning bundle would then travel
   with the stream. The timing problem could be met by the same draft-and-rebind step rule 2 uses,
   so the real objection is scope: it changes every stream construction and the wire format, where
   rule 2 adds one constructor and one invariant. Revisit it if rule 2 proves insufficient.
3. **Leave `core` unchanged and guard consumers only.** That is the current state, chosen for now.
   It relies on every future consumer remembering, and the survey found two that had not.
4. **Include native axes in `computeId`.** It is a fixed point: the primary axis id depends on the
   bundle id, which would then depend on the primary axis id. It would also leave the borrowed-axis
   route open and change every bundle id.

## Consequences

- `core` gains public vocabulary (`PresentationAxis.streamNative`) and one validation invariant.
  The reference scope is wide, and the `storyatlas4s` sibling build applies.
- The 12 D1a and D1b fixtures move their secondary streams to stream-native axes. Their intent is
  unchanged: support on an unmapped secondary clock still does not reach the primary axis without a
  `ClockRepair`. The rewrite is a change of fixture, not a weaker test.
- Bundles from production helpers and the Sherlock composition are unaffected. A migration probe
  must show this before landing: rebuild the production fixtures and the persisted study inputs
  that construct bundles.
- Production native axes do not change, so production `SourceBundle.identity` values (which cover
  native axes) and receipts that bind them stay the same. Short `SourceBundle.id` values do not
  change, because `computeId` is untouched.
- The borrowed-axis witnesses in `align/.../BorrowedAxisSuite.scala` and
  `document/.../ReferentIdentitySuite.scala` will no longer build an unmapped borrower. The unmapped
  case becomes a `core` refusal test. The `align` tests keep the consumer guards witnessed through
  mapped borrowers (a self-declared composition), which rule 3 still admits. The strict-rule
  measurement of 12 failures covered `core`, `story`, `codec` and `document` only; `align` must be
  measured too.

## Owner decisions (2026-10-23)

1. **Stream-native axes are accepted** as the lawful home of an unaligned secondary clock. Rejected:
   requiring a `ClockRepair` to the primary axis from the start, which would make "clock not yet
   repaired" unrepresentable.
2. **Rule 3 admits only `TrackComposition` mappings.** A `ClockRepair` or any other mapping family
   cannot license a foreign native axis. The check that composed streams equal a part's streams
   stays with `DeclaredComposition.of` and the consumer guards, because `SourceBundle.of` has no
   part bundles at construction time.
3. **Implement before S3c**, so S3c builds media bundles under the final rule.
