# D1A: the source-to-story seam — a phase plan (revision 5)

*2026-09-17. Draft for owner approval, written against `main` at `5799e94c`. The bead is
`bd-01M1CQKRG1A4J4BEWCC78F4TEZ`. The governing record is ADR 0007: §5, §6, §7, the migration
sequence, and the rejected alternatives. Everything here is LocallyObserved. No code has moved.*

*Three fresh-context cold reviews returned revisions 1, 2 and 3. Each round went one layer deeper:*
- *revision 1 failed at the outer doors: film status, the codec, alignment, forgery, and
  fingerprints;*
- *revision 2 failed at identity, the proposal surface, and the component encoders;*
- *revision 3 failed on whether its slices could land on their own, on how identity was defined,
  and on whether splitting D1A could really stay additive.*

*Revision 4 was approved under the A/C/D/E rulings below. Revision 5, 2026-09-19, folds the
outstanding pre-S2 cold review into bounded construction, identity and staging corrections.
Those rulings stay in force. S0 and S1 are complete; implementation after them remains open.*

## 0. Scope, and the owner's decisions

The 1.0 handoff (§7) was about **signatures**. 1.0 must not freeze `StoryModel`, the story nodes,
`Evidence` and `AlignmentSource` in a text-only form. So the work is split in two:

- **D1A-types (this plan).** It makes the public types film-capable and adds the core refusals
  and non-text identity. It also fixes the public shapes the film path will need (decisions D1
  and D2), so that D1A-film really is additive. Text behaviour stays byte-identical, and parity is
  pinned. It adds no film compile.
- **D1A-film (a later plan and bead).** It lets the compiler compile a film source. Its open
  questions are in §4.

**What each piece blocks.**
- D1B's *signature* work (the public `align` types `SourceView`, `HsmmResult` and `wire`) needs
  only D1A-types. D1B's courts can use hand-built film models.
- D1B's end-to-end film proofs, V1 and E0 need D1A-film.

### Owner rulings, 2026-09-18

- **A. The gate is adjusted.** The owner's ruling: "we're developing software. build first,
  then researchers ask questions."
  - ADR 0007's migration step 2 made engineering stages wait for "one answered research
    question". That research half is removed. S1 records the change as an amendment.
  - The step's engineering reason stays and gets a mechanism: "generalizing an unvalidated
    compiler seam would multiply an unknown". S0 answers it by pinning the text compiler's
    behaviour before any type moves, so the generalisation cannot silently change it.
  - This does **not** claim the text seam is scientifically validated. It claims only that D1A
    cannot alter it unseen.
- **C. Approved:** the split, the design in §2 and the slices in §3.
- **D. Approved:** D1 (one bundle per model; multi-part films compose) and D2 (the proposal
  surface lives outside the bundle, bound by checksum and receipt).
- **E. Library end to end (2026-09-18).** 1.0 waits for D1A-types, D1A-film and all of D1B:
  through the API, a film source compiles, recall aligns against it, and results carry exact
  playback intervals. The film file format (V1) and the Sherlock terminal command (E0) are 1.x,
  additive under their own schema version. D1A-film is therefore on the 1.0 path.

### Decisions the owner must make at approval

1. **A. Override ADR 0007's Stage D block, or show that it closed.** ADR 0007:477-479 blocks
   Stages B–D until one real transcript goes through Stage 1b to one answered research question.
   No record says that happened. Stages B and C landed anyway. S1 records the owner's decision.
2. **C. Approve the split, the design in §2 and the slices in §3.** D1A-types is sized at L to XL.
3. **D. Where film proposal text lives, and how many bundles a model has.** This decides public
   shapes now, so it cannot wait for D1A-film.
   - **D1: one model has exactly one bundle.** A multi-part film such as Sherlock's two parts
     becomes one composed bundle, built with the core `TrackComposition`, which is unused today.
     Composition is an additive core operation, so it can land later. **Recommended.**
   - **D2: the proposal surface lives outside the bundle** (**recommended**). The bundle is the
     film as presented. Annotation descriptions and captions are *derivations* of the film: ADR
     0007:112 calls the workbook "an observation of what a coder recorded and a derivation of the
     episode". So the proposal surface is a separate artifact that the atlas binds by checksum and
     receipt.
     - **What it keeps working:** anchors minted today on film-only bundles by
       `media/caption.scala:580`, `media/boundary.scala:456` and `SherlockAnnotations.scala:222`
       stay valid.
     - **What it costs:** the film model's identity must include the surface checksum (§2.2).
     - **The alternative, inside the bundle:** the surface becomes a bundle stream whose units
       are scoped by `StreamId`. The bundle id then changes with every annotation set, and every
       existing film-anchor producer would need a rebase operation that does not exist.
   - Under D2, `NarrativeProposalUnit.surface: Option[SurfaceUnitId]` keeps its shape. Each atlas
     binds at most one proposal surface, so an id is unambiguous inside its model.
4. **E. Does 1.0 ship video recall end to end, or only signatures that can carry it?**
   - **Signatures only:** 1.0 needs D1A-types plus D1B's signature work.
   - **End to end:** 1.0 also needs D1A-film, D1B's proofs, V1 and E0. That is about four more
     XL items.

## 1. Facts that shape the design

Each of these was verified by hand or by at least two independent reads.

**Codec**
- **Model decoders.** The decoders hard-code `SpanSet` (`codec/story.scala` 99, 146, 174, 239,
  309, 536-550).
- **Public codec entry points.** The public model givens are `modelEncoder` (:700) and
  `draftDecoder` (:721). `StoryModelCodec` returns a plain `String` or `Checksum`, and it has 9
  callers in main code.
- **Unversioned claims ledger.** `JsonLines.claims`/`readClaims` (`ledger.scala:42-46`) is an
  unversioned carrier for `ClaimMeta`/`Evidence`. `CodecSuite:232-234` round-trips it.
- **`BundleJson` does not encode `Evidence`.** It encodes receipts (`BundleJson.scala:233-250`).
- **Version-bump precedent.** Schema 0.6.0 moved to 0.7.0 when a field "began to be written at
  all" (`model.scala:164-166`). `schemaVersion` sits inside the canonical model and inside
  `BuildReceipt.contentChecksum`, so a bump moves every pinned checksum.

**Core**
- **`EvidenceSupport.of`** (`core/source.scala:1332`) checks only non-emptiness and
  bundle/stream membership. It also has a dead branch.
- **The anchor enum** (`source.scala:1253-1276`) has four cases: `Text`, `MediaTime`, `Shot` and
  `Track`.
- **`PlaybackIntervalSet.of` refuses overlapping intervals** (:919-925).
- **Bundle ids are truncated.** `SourceBundle.computeId` leaves out axis extent, timebase and
  mappings (:998-1016), and the id is a truncated `short()` address (`hash.scala:121-122`).
  Two `filmEdition` bundles with the same edition and checksum but different extents therefore
  share an id. Axis ids do include extent and timebase (:588-606).
- **The only atlas implementer is `TextNarrativeAtlas`.** It is built only from `writtenText`
  (`atlas.scala:738-753`).

**Story**
- **Model identity is keyed by `StorySource`.** The model holds `source` (`model.scala:20`) and
  `draft` takes a caller-supplied `receipt` (:188). No law checks the join between receipt and
  source.
- **`BoundaryBelief.evidence`** (`hierarchy.scala:14`) is not in `model.claims`.
- **Some ordering functions have no bundle to read.** `NarrativeGraph.discourseOrder` and
  `discoursePosition` are public (`graph.scala:21-29`).

**Tests**
- **Before S0, no golden pinned compiler output.** The War of the Ghosts compile suites live in `pipeline`,
  which runs on the JVM only. `runs/2026-09-02-wog-record-1/` holds only
  `compilation-report.json` and `receipts.json`. The model checksum golden lives in
  `codec/StoryModelCodecSuite`; `fixtures/WarOfTheGhostsCodecGoldenSuite` separately checks
  HSMM encoding and round trips, without asserting its checksum constant. Both run on all
  platforms. (Coverage corrected during S0.)

## 2. The design of D1A-types

### 2.1 Sealed atlas, one bundle, a bound surface slot

`NarrativeSourceAtlas` becomes a `sealed trait` in `core` with two final cases:

- **`TextNarrativeAtlas`**, as today. It is the only case that carries canonical text.
- **`AnchoredNarrativeAtlas.of(bundle, units, surface: Option[BoundProposalSurface])`**, which is
  checked. It refuses:
  - a unit whose support is off the bundle;
  - a bundle whose primary axis is not `EditionPlayback` in this slice;
  - duplicate `NarrativeProposalUnitId`s before constructing the lookup map;
  - a unit `surface` that is not a sentence of the bound surface;
  - a duplicate unit surface.

`BoundProposalSurface` is a distinct type: a `SurfaceAtlas`, its derived checksum, the supplied
`SourceDerivationReceipt`, and the derived identity of that association. The surface digest
binds the source's identity and canonical bytes plus every unit's ID, kind, span, ordinal and
explicitly tagged parent absence/presence, in canonical unit order. Free-form fields are hashed
before joining so separators cannot alias two records. The association identity binds this
digest, the canonical source checksum and the receipt's safe `bindingIdentity`. No caller-supplied digest is
trusted. This is a recorded association: the input-only receipt cannot independently attest
that it produced those output bytes. It never converts to canonical text. Same text with
different unit structure, and the same surface with a different receipt, must differ.

`AnnotationTimeline` and zero-duration instants do not become playback intervals. They are
explicitly unsupported by this anchored atlas; the annotation-preview API remains available.
Before D1B closes an acceptance criterion containing admitted Sherlock instants, a point-capable
projection must be recorded and implemented. Inventing a positive duration is forbidden.

D1A-types adds the slot and the checks. D1A-film populates it. `surfaceAtlas` leaves the trait.
`StoryModel` holds `atlas`, and `bundle` is `atlas.bundle`.

### 2.2 Model identity

| | text model | non-text model |
|---|---|---|
| `storyId` | unchanged: the source's id, including a `fromText` explicit id (`atlas.scala:199-210`) | `ContentAddress.of("story-anchored", bundle.identity.hex, tagged bound-surface identity or absence)` |
| receipt `sourceChecksum` | unchanged: `source.canonicalChecksum` | the full, untruncated SHA-256 of that same identity input |

`SourceBundle.identity` is additive; the existing bundle ID stays unchanged for existing anchors.
Its full digest binds the edition, source kind, every stream's ID/kind/checksum/native axis,
extent, timebase and derivation parents, the full primary-axis fingerprint, ordered authority
tracks and sorted full `CheckedMapping.identity` values. Option and enum cases are tagged.
Each mapping identity binds family, axes, exact rational parameters or complete occurrence/
interval/pair payload, and receipt `bindingIdentity`. `SourceDerivationReceipt.bindingIdentity`
is additive: it hashes algorithm and parameter fields separately, plus ordered input checksums,
under an explicit domain. Historical `receipt.identity` stays unchanged. The legacy NUL-joined
preimage aliases algorithm/parameter pairs `("a\\u0000b", "c")` and `("a", "b\\u0000c")`;
the new identity must distinguish their actual embedded-NUL strings. A relation ID names only a mapping's family and
endpoints; using it as the mapping's content identity is rejected because a different repair
offset or composition can share it. The new identity courts include those pairs and changes
to a non-primary stream's coordinate metadata.

**The door.** `draft` checks that the receipt's `storyId` and `sourceChecksum` equal the derived
values. That applies to text as well. Any existing text caller that passes a mismatched receipt
is a defect found; S4b fixes the caller and names it.

**The court** uses the pair the truncation creates: two `filmEdition` bundles with the same bundle
id and different timebase or extent must give different `storyId`s. **Mutation:** omit the full
axis fingerprint from the bundle identity, and the court must fail. Further courts change
mapping parameters, non-primary stream coordinate metadata and bound-surface content/receipt
independently; each must change `storyId` and the source checksum.

### 2.3 Text access is one witness, minted from the atlas

```scala
final class StoryText private (val source: StorySource, val surface: SurfaceAtlas, val stream: StreamId)
final class TextModel[S <: ModelStatus] private (val model: StoryModel[S], val text: StoryText)
object StoryModel:
  def asText[S <: ModelStatus](m: StoryModel[S]): Option[TextModel[S]]   // Some iff atlas is TextNarrativeAtlas
```

The other conditions a text model needs (all support is `Text`, and no evidence anchors) are
already guaranteed at `draft` by the canonical form (§2.4). So `asText` checks the atlas case
only. The courts are one positive case and one negative case.

**The witness survives the model's transitions.** `StoryValidator.validate` and `adjudicated` gain
`TextModel` overloads that return a `TextModel`. The compiler's text path yields a compilation
whose draft is a `TextModel`, so `pipeline/StoryBuild.scala:194,231,312-317` never meets an
impossible `None`.

**These consumers take `TextModel`:**
- **codec:** `StoryModelCodec`; the `modelEncoder` and `draftDecoder` givens; the derivation
  artifact; `FeatureMaterializer` and `FeaturesArtifact`.
- **view:** `DraftModel`; the view compilers.
- **align:** `StorySourceView`, including its `adjudicated` path.
- **pipeline:** `Features`; `StoryBuild`.
- **story:** `NarrativeConsistency`; `StoryRender`.
- **embed-bench:** `bench/wog.scala:49`.
- **laws:** `Laws.scala:170-182` and the generators.
- **docs-site:** the examples.

**Text operations move behind the witness.** `supporting`, `covering` and `situationsCovering`
become `private[story]` and are exposed on `TextModel`. The derived-view ids
(`alignment.scala:117,123`) use §2.2 identity, because they belong to the general source.

**Rejected, and recorded in S1:**
- **A phantom medium parameter.** ADR 0007 rejects it.
- **A capability index on `StoryModel`.** It would put a second parameter on every mention of
  the model.
- **`Option` text fields.** A caller could pair a model with foreign text.
- **An `Either` returned from `encode`.** It would add runtime refusals at 9 call sites.

### 2.4 Node support and the canonical form

```scala
enum TypedSupport:
  case Text(spans: SpanSet)
  case Anchored(support: EvidenceSupport)
```

`TypedSupport` lives in `core`, because `acquire` does not depend on `story` (`build.sbt:152`).

**Canonical form is keyed on the primary axis kind.** A `TextCharacter` primary axis, which
covers written text and text-primary transcripts, requires `Text`. The admitted non-text kind
is `EditionPlayback` and requires `Anchored`; other primary kinds are refused in this slice.
Text evidence has no anchors; evidence on a film model has no bare spans. Upstream-only
evidence may have neither. The twin form cannot enter a model.

`draft` returns `Either`, and the `private[story] copy` routes through the same check. It checks
four things:
- **canonical form**, over every support and every piece of evidence, including
  `BoundaryBelief.evidence`;
- **membership:** every anchor is on `atlas.bundle`;
- **projectability:** every `Anchored` support has an anchor on the primary axis;
- **the receipt join** (§2.2).

**Extent is not checked in `draft`.** It stays a validator law (`support.in-text`,
`claims.spans-in-text`). `ValidatorSuite:366`, `InvalidStoryGens:73-94`, and a decode that yields
a draft carrying violations all behave as they do today.

**Nodes:** five case classes, the `SituationNode` wrapper, and `CircumstanceEdge`.
- Overloaded `apply`s taking a `SpanSet` keep 54 construction sites compiling.
- The 10 `.copy(support = SpanSet)` sites are edited.
- Text mention ids stay rendered from every span ref, never from a hull.

### 2.5 The primary projection and ordering

```scala
enum PrimaryProjection:
  case TextSpans(axis: PresentationAxisId, spans: SpanSet)
  case Playback(axis: PresentationAxisId, intervals: PlaybackIntervalSet)
```

The projection is derived and never carried. `Text(s)` projects to `TextSpans`, and `Anchored(e)`
projects to `Playback(e.intervalsOn(primaryAxis))`. The result is total, because `draft` enforces
projectability.

**Order:** by projection start, then end, then node id. For text this reproduces today's
`minSpan` order exactly, and S0 pins it.

`discourseOrder` and `discoursePosition` move to the model, which has the bundle. The unbound
graph-level versions become `private[story]`. A checked `NarrativeGraph.discourseOrderOn(bundle)`
operation shares the same projection/order implementation and returns `Either`; it is needed
by the compiler when it constructs trajectory before the model exists. `TypedSupport.Anchored`
carries no cached projection. Point support required by admitted Sherlock row 13 is a named
D1B prerequisite; no interval-only result may claim to include that instant.

### 2.6 `core.Evidence`

`Evidence` gains `anchors: Option[EvidenceSupport] = None`. `Evidence` holds no bundle. The joined
claim forms wherever evidence meets a bundle, and the canonical-form refusals are enforced at
each of those points:
- `draft`;
- `NarrativeCompilerInput.of`, for the ledger and `EvidenceRef.Inline`;
- `AnchoredNarrativeAtlas.of`.

That satisfies rule 8's cartesian test. `Evidence` stays a public value carrier. `spanLaw` does
not change in D1A-types.

### 2.7 The codec: stated behaviour for each anchored value

The component encoders stay total. They gain one self-versioned sub-shape, `evidence-support/v1`,
with one payload per anchor case:

- `Text`: bundle, stream, spans;
- `MediaTime`: bundle, stream, axis, intervals;
- `Shot`: bundle, stream, `ShotId`, interval;
- `Track`: bundle, stream, `TrackId`, intervals.

The sub-shape is written only for anchored values. The 0.7.0 decoders refuse it with a typed
error. Round-trip is declared a text-only law.

**No schema bump; S1 records the final argument.** Unlike 0.6.0 to 0.7.0, no 0.7.0 model
artifact may contain the sub-shape. At S4b the model entry points take `TextModel`, which holds
no anchors. To make S2 and S3 independently landable, S2 first refuses anchors at every current
text-model construction path (including internal copy/status paths and boundary-belief evidence)
and at text compiler-input joins (ledger and inline evidence). The still-total draft constructor
throws `IllegalArgumentException` for that newly expressible invalid input; S4a replaces this
temporary refusal with its typed `Either`. The decoder refuses anchored evidence explicitly.
An accepting text control and a compiled guard-removal mutation prove that 0.7.0 export cannot
contain it. This adds bounded story/document guards to S2's scope; postponing them until the
final witness would violate the no-bump claim. Absence is unambiguous and the sub-shape is tagged.

The one unversioned carrier that could hold anchored claims is `JsonLines`. Its round-trip court
stays text-only. V1 versions any film artifact when it first writes one.

**Courts:** one per anchor case, each an encode followed by a typed refusal on decode.
**Mutation:** drop one payload field, and its court must fail.

### 2.8 `AlignmentSource`

`AlignmentSource` becomes sealed. The general source is built from any `StoryModel[Validated]` and
exposes `evidenceOf` and `primaryOf`. The text source is built from a `TextModel[Validated]` and
keeps `sourceSupport`, which `StorySourceView` consumes. Three align call sites change
mechanically. Scoring does not change.

## 3. The slices

Every slice lands on `main` on its own green gate (§5). The values S0 pins must stay identical.

### S0 — Text parity baseline (test-only; S)

Completed 19 September 2026 at `051f88f3`: [frozen pins, mutation witnesses, cold review and
clean full-gate receipt](../refactor/evidence/d1a-s0-text-parity-20260919/README.md).
Later slices must leave the frozen values unchanged.

**What it pins, on the JVM in `pipeline`:**
- for the War of the Ghosts compile: the fingerprint, the `candidateSet` hex,
  `StoryModelCodec.contentChecksum`, and the `derivation.json` checksum;
- one exemplar each of the `source-support/v2`, `evidence/v2` and mention-id renderings;
- the ordering of every node, as a list;
- one `view` artifact checksum.

**The recorded run.** The receipts in `wog-record-1` are pinned as receipt checksums. That record
holds no model or derivation bytes.

**Cross-platform coverage.** Model parity is cited from `codec/StoryModelCodecSuite`; the
`fixtures` suite separately checks HSMM encoding and round trips. Compile parity on JS and
Native is a non-claim. These existing checks are not duplicated by S0.

**Mutation witnesses:**
- one fixture span offset;
- emitting `"anchors": null`;
- changing the `Text` support encoding;
- changing a `source-support/v2` span rendering.

### S1 — ADR 0007 amendment (docs; S)

Completed at `75c8df52`: [committed-text review and checks](../refactor/evidence/d1a-s1-adr-20260919/README.md).

S1 records:
- owner decisions A, D and E;
- the split;
- the vocabulary: `TypedSupport`, `StoryText`, `TextModel`, `asText`, `PrimaryProjection`,
  `AnchoredNarrativeAtlas`, `BoundProposalSurface`, `Evidence.anchors`, `evidence-support/v1`,
  `intervalsOn` and `hasSupport`;
- the canonical-form rule, and non-text identity;
- the no-bump argument, and round-trip as a text-only law;
- the rejected alternatives;
- that the Sherlock adapter becomes an `AnchoredNarrativeAtlas` construction over one composed
  bundle.

### S2 — Core substrate (core, laws, codec; bounded story/document guards; M)

- **Atlas:** seal it, add `AnchoredNarrativeAtlas` and `BoundProposalSurface` with their checks,
  remove `surfaceAtlas`, and back lookup with a map after refusing duplicate proposal IDs.
  Implement the derived surface and receipt-association digests in §2.1.
- **Full identities:** add `SourceBundle.identity` and `CheckedMapping.identity` as §2.2 specifies;
  preserve the existing IDs. Same-endpoint/different mapping parameters and changed secondary
  stream coordinate metadata must remain distinguishable.
- **`EvidenceSupport.of` refuses:**
  - an anchor whose kind does not match its stream's kind;
  - a `MediaTime` axis that differs from its intervals' axis;
  - an axis foreign to the bundle;
  - spans outside the extent.

  Delete the dead branch.
- **Anchor/stream compatibility:**

  | Anchor | Admitted stream kinds | Required coordinate extent |
  |---|---|---|
  | `Text` | CanonicalText, Subtitle, TimedText, Annotation | `TextChars` |
  | `MediaTime` | Picture, Audio, Subtitle, TimedText, Annotation | `PlaybackTicks` |
  | `Shot` | Picture | `PlaybackTicks` |
  | `Track` | Picture, Audio | `PlaybackTicks` |

  DerivedClock and Custom have no implicit evidence capability. A playback anchor must use its
  stream's native axis, or the primary axis reached from that native axis by an explicit
  ClockRepair/TrackComposition in the bundle. EditionCorrespondence is not a coordinate cast.
  Native-axis bounds use that stream's extent; mapped primary-axis bounds use the primary extent.
  A mapped anchor must also be fully covered by the chosen mapping's image. Composition source
  segments must lie within the selected stream extent; their target union preserves gaps.
  ClockRepair's source coordinate is exact run-local seconds: convert native extent ticks using
  its rational timebase, then apply its exact scale/offset. Compare image bounds without rounding.
  No matching-endpoint map licenses coordinates in an unmapped gap or outside its image.
  Bundle-wide axis membership alone is insufficient. Test a right-bundle/wrong-stream axis pair
  and two textual streams; neither may borrow the other's support or extent. Include out-of-image,
  gap-crossing, partial-coverage and foreign source-segment refusals with mapped accepting controls.
- **Per-stream `textSpans`.** Intervals on one axis that overlap or abut merge into one
  canonical form. `intervalsOn(axis)` returns it, under a law that the merge is idempotent and
  independent of order.
- **New types:** `PrimaryProjection`, `TypedSupport` and `Evidence.anchors`.
- **`evidence-support/v1`** in the codec, with its four courts. This is the only codec work in
  S2, and it is additive. The temporary text-model/compiler-input guards in §2.7 land in the
  same slice before any anchored Evidence value can reach the unchanged 0.7.0 model wire.
- **Remove `LegacyAudioBinding.toMediaSupport`,** which can never return `Right` (:1639-1670), or
  fix it.
- **Laws and probes:**
  - one law per refusal;
  - the untested foreign-bundle case at `SourceBundleSuite:149`;
  - construction probes for `NarrativeProposalUnit`, `AnchoredNarrativeAtlas` and
    `BoundProposalSurface`;
  - a probe that nothing outside `core` can extend the sealed atlas;
  - duplicate proposal-ID and unsupported-primary-axis refusals with named passing controls;
  - surface content/unit/receipt changes, and mapping parameter/receipt/stream-metadata mutations;
  - a model containing anchored ordinary or boundary evidence cannot be constructed or exported,
    and compiler input refuses anchored inline/ledger evidence before accepting a text request.

### S3 — acquire (acquire, document call sites; S–M)

- **`SourceSupport(score, support: Option[TypedSupport])`**, with a text constructor and a
  derived `spans`.
- **`EvidenceRef` gets a support accessor.**
- **`hasSpans` becomes `hasSupport`.**
  - The wire-visible gap reasons `NoSpanEvidence` and `MissingSpanEvidence` keep their names,
    but their scaladoc is corrected.
  - A court checks that every text verdict is unchanged.
  - This slice gets a cold review, because it changes an acceptance rule.
- **`TaskReferences.validateAgainst`** gains a `NarrativeSourceAtlas` overload.

### S4a — Node support and a fallible `draft` (M–L)

**Modules:** story, core, codec, document, fixtures, laws, view, pipeline, docs-site,
embed-bench. That covers every reader of node `.support` and every caller of `draft`.

- **Nodes take `TypedSupport`,** with the overloads, and the 10 `copy` sites are edited.
- **`draft` returns `Either`.** In this slice every model is still text, so the check is "all
  `Text`". The model **keeps** `source` and `SurfaceAtlas`, so no consumer of those loses them.
- **The node decoders** read `TypedSupport.Text`.
- **Ordering moves to the projection** (§2.5), and the graph-level ordering functions become
  `private[story]`. The compiler's pre-model ordering uses the checked shared operation.
- **Rewrite the probes that `Either` makes vacuous.** These are the `copy[Validated]` and `Product`
  probes in `StoryModelUnforgeableSuite:31-35`. Each is re-proved on its own clean recompile.

S0 must stay identical.

### S4b — Envelope, identity and the text witness (L)

S4b touches the same modules as S4a, plus align's `StorySourceView` line.

- **Envelope:** `StoryModel` holds the sealed atlas, `source` moves into `StoryText`, and `draft`
  gains the membership, projectability and receipt-join checks.
- **Identity:** §2.2, with both courts.
- **Witness:** `StoryText`, `TextModel`, `asText`, and the `validate`/`adjudicated` overloads. The
  compiler's text path yields a `TextModel`.
- **Consumers:** every consumer in §2.3 moves to `TextModel`, and the text operations move behind
  it.
- **Validator:**
  - text checks move behind the witness;
  - `member-within-parent` is re-expressed on the projection;
  - bound checks are added for `ContextFrame` and `CircumstanceEdge`, and for claim-evidence and
    boundary-belief anchors.
- **Probes:**
  - `StoryText` and `TextModel` have no public door;
  - the first probes on the node types, recording what stays open and why;
  - a negative compile assertion that a `StoryModel` cannot stand where a `TextModel` is
    required, with a positive control of the same shape.

S0 must stay identical.

### S4c — `AlignmentSource` (story, align call sites; S)

Seal `AlignmentSource` and split it as §2.8 describes. S0 must stay identical, including the
HSMM golden.

**Order:**
1. S0, then S1, then S2.
2. S3 and S4a, in either order.
3. S4b.
4. S4c.

## 4. D1A-film: open questions carried forward

Decisions D1 and D2 settle the public shapes. The remaining questions are internal to the
compiler, or additive:

1. **Populate `BoundProposalSurface`.** Add the model law that no node or claim support is
   anchored on the surface.
2. **Render identity.** Fingerprint and `candidateSet` headers carry the §2.2 identity. The court:
   the same description text on two bundles yields two fingerprints.
3. **Context placement.** Either the film placement is NarratedWorld only, and a `Held` context
   becomes a typed gap, or `Held` is anchored to the unit's interval. `ContextStep` spans are
   unvalidated today (`placement.scala:84-103`, `compiler.scala:1674`).
4. **The anchored `within` rule** (`compiler.scala:553-556`): containment in the unit whose
   surface is the attempt's sentence.
5. **Stage 1b provenance.** `claimProvenance` locates parse calls through spans (:2465-2476).
6. **Mention ranking and ids.** Ranking is `:1512`. `MentionPosition(offset: Int)`
   (`mentionform.scala:174`) cannot hold `Long` ticks. The film path therefore needs an additive
   position case. Replacing the field would be a break, so check this item first. `Material`,
   `EmittedMention` and `derivedMeta` are typed as `SpanSet`.
7. **The status rule.**
   - **What the doc says:** `SurfaceExplicit` means "directly stated by the source text; must cite
     spans" (`claim.scala:10`).
   - **What is in play:** licensing it from an annotator's or a captioner's derivation changes
     that meaning. `StatusWeight` is 1.0 (`relations.scala:234`), and consistency law 3 reads
     tokens.
   - **Where a rule can live:** `spanLaw` has no bundle in scope.
   - **Owner decision:** whether generated captions can license `SurfaceExplicit` at all.
8. **Anchored `evidence/v2` and `source-support/v2` rendering.** The court: moving one tick
   changes the fingerprint.
9. **The film court.**
10. **The Sherlock adapter's repair receipts**, and composing its two parts (D1).

## 5. Gate, per slice

- **Correctness, then formatting.** Run `sbt -batch clean compileAll testAll` on the merge
  result, then `scalafmtCheckAll scalafmtSbtCheck` as a separate run.
- **Docs examples.** Run `cd docs-site && npm run verify:examples` for every slice that touches
  the docs-site examples (S3 onward).
- **SD2's two surviving checks:**
  - a clean tree for the merge result over the exact touched paths;
  - a gate log with bound totals and its command receipt, with all 56 test tasks reporting.
- **One clean recompile per compile-time probe mutation** (AGENTS.md:1387-1398).
- **S0 identical.**
- **A named mutation witness per new guard.**
- **A fresh-context cold review for every slice.**
- **CI:** cite a run by URL, matrix cell and SHA once CI runs. The Actions billing block is
  deferred.

## 6. Tracker changes on approval

- **`bd-01M1CQKRG1A4J4BEWCC78F4TEZ` becomes D1A-types.**
  - Its paths and courts are rewritten to §3.
  - "No align/codec edit" becomes three rules: no align scoring change; no 0.7.0 wire change for
    text; the only codec addition is `evidence-support/v1`.
- **A new D1A-film bead** carries §4. D1A-types blocks it.
  - It blocks D1B's end-to-end proofs and E0.
  - It does not block D1B's signature work.
  - Whether it sits on the 1.0 path follows decision E.

## 7. What this plan does not do

- **No film compile.** That is D1A-film.
- **No align behaviour, `SourceView` or `HsmmResult` change.** That is D1B.
- **No film artifact versioning or renderer.** That is V1.
- **No bundle composition code.** D1 records the rule, and D1A-film or intake P5 builds it.
- **No provider, decoder, media or corpus change.**

## 8. Non-claims

- **Counts** come from four surveys and three cold reviews. They are good to about ±10%.
- **Byte-identical text output** now has the S0 witness above. Every later slice must retain
  those pins. Compile parity is JVM-only; cross-platform model encoding has its separate court.
- **The split is additive only under decisions D1 and D2.** Under the in-bundle alternative it is
  not, and §2.1 and §2.2 would change.
- **Revision 4 cold review was completed at `3c4cdf40`.** Revision 5 records its identity,
  staging, atlas, axis, pre-model ordering and stream-association corrections. Recheck this
  revision before S2 code begins; this design review is not executable qualification.
