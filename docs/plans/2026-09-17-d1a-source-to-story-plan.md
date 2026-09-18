# D1A: the source-to-story seam — a phase plan (revision 3)

*2026-09-17. This is a draft for owner approval, written against `main` at `8355d8cd`. It covers
bead `bd-01M1CQKRG1A4J4BEWCC78F4TEZ`. The governing record is ADR 0007: §5, §7, its migration
sequence and its rejected alternatives. Everything below is LocallyObserved, and no code has
moved.*

*Two fresh-context cold reviews each returned the plan. Revision 1 had five blockers. Revision 2
answered them at the outer doors, the model codec and the text aligner. The second review then
found three more blockers one level in. The film model had no defined identity. The film
proposal-surface text was unbound, so any text could be paired with any film. The component
encoders stayed total over film values. Most of its should-fix items were also film-path depth.*

*Revision 3 changes the plan's shape rather than patching it again.*

## 0. The shape: split D1A, and decide what 1.0 actually needs

The handoff's concern (§7) was **signatures**. 1.0 must not freeze `StoryModel`, the story nodes,
`Evidence` and `AlignmentSource` in text-only form. That concern is answered by making those
public types film-capable while text behaviour stays byte-identical. It does not require the
compiler to compile a film.

Both reviews found that the film compile path carries design questions this plan cannot settle
from surveys alone:
- film identity headers;
- a proposal surface bound to a bundle stream;
- context placement;
- Stage 1b provenance for film claims;
- mention ranking on tick coordinates;
- the meaning of `SurfaceExplicit` for perceptual evidence.

So the work splits in two:

- **D1A-types (this plan).** It gives the public types a film-capable shape, adds the core
  refusals, and defines film model identity. Every text behaviour stays intact, with parity
  pinned. It adds no film compile path. This is 1.0-critical.
- **D1A-film (a separate plan and bead, written after D1A-types lands).** The compiler compiles a
  synthetic film source. It is additive to D1A-types' signatures: a new `NarrativeCompilerInput`
  overload, a new atlas case and new renderings. Its open questions are listed in §4 so that
  nothing found in review is lost.

**The 1.0 freeze also waits on D1B.** D1B (`bd-01M1CQNM5DZZNWD8BN4WRKVZRQ`) changes the public
`SourceView`, `HsmmResult` and `wire` types in `align`. That is outside this plan, but it is on
the same critical path.

### Decisions for the owner at approval

1. **A. Override ADR 0007's Stage D block, or show that it closed.**
   - ADR 0007:477-479 blocks Stages B–D until the existing vertical gate closes: one real
     transcript through Stage 1b to one answered research question.
   - No record says it closed. Stages B and C landed anyway.
   - The owner's decision that 1.0 includes video recall implies an override but does not state
     one. S1 records the owner's line.
2. **C. Approve the split, the design in §2 and the slices in §3.** D1A-types is L to XL. The
   ADR 0007 §7 status question (formerly decision B) moves to D1A-film, where a film claim first
   exists.

## 1. Facts that shape the design

These were verified by hand or by at least two independent reads at `0e36a7fa`/`8355d8cd`.

- **Node decoders** hard-code `SpanSet` at `codec/story.scala` 99, 146, 174, 239, 309 and
  536-550. The encoders are type-directed.
- **`StoryModelCodec`** returns a plain `String`/`Checksum` behind a total `Encoder` (:700-766),
  with 9 main-code callers.
- **The component givens have a public consumer.** `pipeline/BundleJson.scala:5-6` imports
  `CoreCodecs.given` and encodes acquisition bundles, which carry `Evidence`. So those givens
  cannot be hidden. Their film behaviour must be stated.
- **The compiler hard-codes `SurfaceExplicit`** for situation and entity-mention claims
  (`compiler.scala:1352`, `:1464`). `ClaimMeta.spanLaw` (`claim.scala:82-95`) has no bundle in
  scope.
- **`EvidenceSupport.of`** (`core/source.scala:1332`) checks only nonemptiness and bundle/stream
  membership, and its second check is dead. `textSpans` merges spans across streams.
  `PlaybackIntervalSet.of` refuses overlap (:919-925), so a union of support has no defined
  canonical form.
- **`NarrativeSourceAtlas`** (`atlas.scala:712`) is unsealed. Its only implementer is
  `TextNarrativeAtlas`. `surfaceAtlas` has no production callers and is used only in
  AtlasSuite:241,247.
- **Model identity is `StorySource`-keyed throughout:**
  - `StoryModel.source` (model.scala:20);
  - 24 `input.source.id` reads in the compiler;
  - the fingerprint and `candidateSet` headers (`compiler.scala:2250-2253`, `3015-3018`);
  - the derived-view ids (`alignment.scala:117,123`);
  - `BuildReceipt(storyId, sourceChecksum, …)` (`provenance.scala:145`).
- **Text operations return collections:** `supporting` and `covering` on the model
  (model.scala:47,55) and on the graph (`graph.scala:177,216,250`). `view/compiler.scala:696,733`
  calls `model.supporting`.
- **No golden pins compiler output.** The War of the Ghosts compile suites live in `pipeline`,
  which is JVM-only. The model goldens (`95028b2c…`, `ce61e761…`) live in `fixtures`, which is
  cross-platform.

## 2. The design of D1A-types

### 2.1 A sealed atlas with one bundle, and no film surface yet

`NarrativeSourceAtlas` becomes a `sealed trait` in `core` with two final cases:

- **`TextNarrativeAtlas`**, as today. It alone carries the text surface.
- **`AnchoredNarrativeAtlas.of(bundle, units)`**, which is checked. It refuses a unit whose
  support is off the bundle, and it refuses a written-text bundle.

`surfaceAtlas` leaves the trait. A film atlas carries **no** surface in D1A-types. Binding a
proposal surface to a named bundle stream by checksum is D1A-film's first question (§4), so that
no untyped film surface can be added now and mistaken for canonical text.

`StoryModel` holds `atlas`, and `bundle` is `atlas.bundle`. That leaves one source of truth.
Lookup becomes map-backed.

### 2.2 Model identity for a source with no canonical text

`StoryModel.source` moves into `StoryText` (§2.3). Identity is derived, never supplied:

| | text model (unchanged) | non-text model |
|---|---|---|
| `storyId` | `source.id` | content address of (bundle id, primary-axis id) |
| receipt `sourceChecksum` | `source.canonicalChecksum` | the bundle's content checksum |
| fingerprint and `candidateSet` headers | as today | bundle id plus primary axis (the rendering lands with D1A-film; the identity function lands here) |

**Court:** one set of units on two different film bundles must give two `storyId`s. **Mutation:**
drop the axis id from the address, and the court must fail.

### 2.3 Text access is a witness minted in one place

```scala
final class StoryText private (val source: StorySource, val surface: SurfaceAtlas, val stream: StreamId)
final class TextModel[S <: ModelStatus] private (val model: StoryModel[S], val text: StoryText)
object StoryModel:
  def asText[S <: ModelStatus](m: StoryModel[S]): Option[TextModel[S]]
```

**This is a runtime mint at one point, not a type-level proof.** `asText` succeeds only if all
of the following hold:
- the atlas is a `TextNarrativeAtlas`;
- the bundle is `WrittenText` with a `TextCharacter` primary axis;
- every node support is `Text`;
- no claim evidence carries anchors.

Each condition gets its own court in which it alone fails.

**The witness survives validation.** `StoryValidator.validate` gains a `TextModel` overload that
returns a `TextModel`. Promotion changes neither the atlas nor the support, so no caller meets an
impossible `None`.

**The consumers that take `TextModel`** are every text-only consumer the two reviews enumerated:
- **codec:** `StoryModelCodec`, the derivation artifact, `FeatureMaterializer`/`FeaturesArtifact`
  (`codec/materialize.scala:42-295`);
- **view:** `DraftModel` (`view/draft.scala:121-191`) and the `view` compilers;
- **align:** `StorySourceView`;
- **pipeline:** `Features`;
- **story:** `NarrativeConsistency` (`consistency.scala:117`), `StoryRender` (`render.scala:10,112`)
  and the derived-view ids;
- **laws:** the laws generators;
- **docs-site:** the docs-site examples.

**Text operations move behind the witness.** `supporting`, `covering` and `situationsCovering`
become `private[story]` on the model and graph, and are exposed only on `TextModel`. Accessors
that already return `Option` (`support.textSpans`, `ClaimMeta.spans`) stay. `None` is an honest
answer for them, not a flattering empty collection.

**Rejected alternatives, each recorded in S1:**
- **A phantom medium parameter.** ADR 0007 rejects parameterising the model by one medium.
- **A capability index on `StoryModel`.** It puts a second type parameter on every mention of
  the model.
- **`Option` text fields.** They make pairing with a foreign text possible.
- **An `Either`-returning `encode`.** It turns one compile-time fact into runtime refusals at 9
  call sites.

### 2.4 Node support: `TypedSupport` in core, one canonical form

```scala
enum TypedSupport:
  case Text(spans: SpanSet)
  case Anchored(support: EvidenceSupport)
```

It lives in `core` because both `acquire` and `story` need it (`build.sbt:152`).

**The model's own door enforces three rules.** `StoryModel.draft` returns `Either`, and the
`private[story] copy` routes through the same check. The rules are:
- **canonical form:** `Text` iff the bundle is written text, and `Anchored` iff it is not, so the
  twin cannot be represented;
- **membership:** every anchor is on `atlas.bundle`;
- **projectability:** every `Anchored` support has an anchor on the bundle's primary axis, so the
  primary projection is total.

**What `draft` does not check.** It does not check text extent. `support.in-text` and
`claims.spans-in-text` stay validator laws, so `ValidatorSuite:366`, the `InvalidStoryGens`
cases (`:73-94`), and a decode that yields a draft carrying violations all behave as today.

**Nodes.** The node types are five case classes, the `SituationNode` enum wrapper and
`CircumstanceEdge`. Overloaded companion `apply`s taking a `SpanSet` keep 54 text sites compiling.
Text mention ids stay rendered from every span ref, never from a hull.

### 2.5 `core.Evidence`: an anchor field, with the law where the bundle is

`Evidence` gains `anchors: Option[EvidenceSupport] = None`. `Evidence` holds no bundle, so it
cannot check the canonical form itself. Under rule 8's cartesian test, the joined claim is formed
wherever evidence meets a bundle, and three places enforce the refusals there:
- `StoryModel.draft`, for claim evidence;
- `NarrativeCompilerInput.of`, for the evidence ledger and `EvidenceRef.Inline`;
- `AnchoredNarrativeAtlas.of`.

The refusals are that both fields are set, and that the field does not match the bundle's kind.
`Evidence` stays a public value carrier on the same ground as `TypedSupport.Text`.

`spanLaw` is **unchanged** in D1A-types. No film claim exists until D1A-film.

### 2.6 Codec: stated behaviour for every film-capable value

The component encoders stay public and total, because `pipeline` needs them. They gain one
defined sub-shape, `evidence-support/v1`, holding bundle, stream, axis and exact interval ticks.
It is written **only** when a value is anchored: for `TypedSupport.Anchored`, and for
`Evidence.anchors` when it is `Some`. Text values never contain it, so text bytes are unchanged.
The 0.7.0 decoders refuse the sub-shape with a typed error.

The model schema stays 0.7.0. No 0.7.0 model artifact can contain the sub-shape, because the
model entry points take `TextModel`. The acquisition-bundle JSON can carry it only when a
pipeline writes film values, and the first such writer (V1) versions it. There is no silent loss
and no exception.

**Court:** an anchored `Evidence` encodes to the sub-shape, and the 0.7.0 decoder refuses it.
**Mutation:** make the encoder drop the anchors, and the court must fail.

### 2.7 `AlignmentSource`

`AlignmentSource` becomes sealed. The general source built from any `StoryModel[Validated]`
exposes `evidenceOf` and `primaryOf`. The text projection `sourceSupport` moves to the text
source built from a `TextModel[Validated]`, which `StorySourceView` consumes. That touches three
align call sites mechanically, with no scoring change. D1B moves align onto `primaryOf`.

## 3. The slices of D1A-types

Every slice lands on `main` on its own green gate (§5), and the S0 values must not change.

### S0 — Text parity baseline (test-only; S)

**Compile parity, in `pipeline`.** It pins the War of the Ghosts compile:
- the fingerprint;
- the `candidateSet` hex;
- `StoryModelCodec.contentChecksum` hex;
- the `derivation.json` checksum;
- one exemplar each of the `source-support/v2`, `evidence/v2` and mention-id renderings.

S0 adopts `pipeline/src/test/resources/runs/2026-09-02-wog-record-1/`, or records why not.

**View parity.** One `view` artifact checksum.

**Cross-platform model parity** is cited from the `fixtures` goldens. Compile parity on JS and
Native is a non-claim.

**Mutation witnesses** are one fixture span offset plus three code-side mutants:
- emitting `"anchors": null`;
- changing the `Text` support encoding;
- changing a `source-support/v2` span rendering.

### S1 — ADR 0007 amendment (docs; S)

S1 records:
- owner decision A;
- the split;
- the vocabulary: `TypedSupport`, `StoryText`, `TextModel`, `asText`, `PrimaryProjection`,
  `AnchoredNarrativeAtlas`, `Evidence.anchors`, `evidence-support/v1`, `intervalsOn`,
  `hasSupport`;
- the canonical-form rule;
- non-text identity (§2.2);
- the rejected alternatives in §2;
- that sealing the atlas makes ADR 0007 §5's future Sherlock adapter an `AnchoredNarrativeAtlas`
  construction rather than an implementer, and that its repair receipts have no slot yet
  (D1A-film).

### S2 — Core substrate (core, laws; M)

- **The atlas:** seal it, add `AnchoredNarrativeAtlas`, remove `surfaceAtlas` from the trait, and
  back lookup with a map.
- **`EvidenceSupport.of` refuses:**
  - an anchor/stream kind mismatch;
  - a `MediaTime` axis that differs from its intervals' axis;
  - an axis foreign to the bundle;
  - spans outside the text extent.

  The dead branch is deleted.
- **Per-stream text reads.** `textSpans` becomes per stream.
- **A defined union.** Overlapping or abutting intervals on one axis merge into their canonical
  form. `intervalsOn(axis)` returns that canonical union, and a law states it is idempotent and
  order-independent.
- **New types:** `PrimaryProjection`, `TypedSupport` and `Evidence.anchors`.
- **`LegacyAudioBinding.toMediaSupport`** can never return `Right` (:1639-1670). Fix it or
  delete it.
- **Laws and probes:**
  - one refusal law for each refusal above;
  - the untested foreign-bundle case at `SourceBundleSuite:149`;
  - construction probes for `NarrativeProposalUnit` and `AnchoredNarrativeAtlas`;
  - a probe that nothing outside `core` extends the sealed atlas.

### S3 — acquire (acquire, document call sites; S–M)

- **`SourceSupport`** becomes `(score, support: Option[TypedSupport])`, with a text constructor
  and a derived `spans`.
- **`EvidenceRef`** gets a support accessor and no new case.
- **The resolver's `hasSpans`** becomes `hasSupport`.
  - The names `requireSpanEvidence`, `NoSpanEvidence` and `MissingSpanEvidence` stop being
    accurate (`resolve.scala:128,360`, `compiler.scala:2429`). They are kept, because they are
    wire-visible gap reasons. Their scaladoc is corrected.
  - A court checks that every existing text verdict is unchanged. This slice gets a cold review,
    because it changes an acceptance rule.
- **`TaskReferences.validateAgainst`** gains a `NarrativeSourceAtlas` overload.
- **No provider module changes.**

### S4a — Envelope, identity and node support (story, codec, document, fixtures, laws; M–L)

- **The envelope:** `StoryModel` holds the sealed atlas, and `draft` returns `Either` with the
  §2.4 checks. The 17 `draft` callers adapt.
- **Identity:** non-text identity (§2.2), with its court.
- **Node support:** `TypedSupport` on the nodes, with the overloads.
- **The codec:** the node codecs and the Evidence codec move to §2.6, and gain their court.
- **The compiler's text path** adopts the types (`:1422`, `:2171`).
- **Story `minSpan` users** keep text ordering and go through the text projection:
  `NarrativeGraph.discourseOrder` (`graph.scala:21-27`), `DiscourseTrajectory.derive`
  (`trajectory.scala:103-117`) and `validate.scala:597`.

S4a gives no film behaviour. S0 must not change.

### S4b — The text witness and its consumers (story, codec, view, pipeline, docs-site, laws; L)

- **The witness:** `StoryText`, `TextModel`, `asText` and the validate overload, with one court
  per `asText` condition.
- **The consumers:** every consumer listed in §2.3 moves to `TextModel`, and the text operations
  go behind it.
- **The validator:** text checks go behind the witness, and `hierarchy.member-within-parent`
  (`validate.scala:402-412`) is re-expressed on the primary projection.
- **New bound checks** cover `ContextFrame` and `CircumstanceEdge` support, and claim-evidence
  anchors.
- **Probes:**
  - `StoryModelUnforgeableSuite` is extended.
  - `StoryText` and `TextModel` have no public door.
  - The node types get their first probes, which record what stays open and why.
  - A negative compile assertion, paired with a same-shape positive control, shows that a
    `StoryModel` cannot be passed where a `TextModel` is required.

S0 must not change.

### S4c — `AlignmentSource` (story, align call sites; S)

This slice seals `AlignmentSource` and splits it as in §2.7. S0 must not change, and that
includes the HSMM golden.

**Order:** S0, S1 and S2 come first. S3 and S4a can then land in either order. S4b follows S4a,
and S4c follows S4b.

## 4. D1A-film: the open questions, carried forward

A plan for D1A-film is written after S4c lands, and its bead is filed when this plan is approved.
The questions it must answer come from both reviews:

1. **Proposal surface.** Bind the proposal-source text (annotation descriptions or captions) to
   a named bundle stream by checksum, as a distinct type rather than a `SurfaceAtlas` usable as
   canonical text. It also needs a model law that no node or claim support is anchored on that
   stream, because an `Anchored` support could otherwise hold spans into the description, which
   is `TimedSourceView` under another name. The law must hold in `draft`, not only in the
   compiler.
2. **Identity rendering.** Fingerprint and `candidateSet` headers carry bundle id and primary
   axis. The court is that the same description text on two bundles gives different
   fingerprints.
3. **Context placement.** Choose NarratedWorld only, with Held steps refused as a typed gap, or
   Held steps anchored to the unit interval. `ContextStep` carries unvalidated text spans
   (`placement.scala:84-103`, `compiler.scala:1674`).
4. **The `within` rule** (`compiler.scala:553-556`) needs an anchored form: containment in the
   unit whose surface is the attempt's sentence. The atlas must also refuse a non-sentence
   surface and duplicate surfaces.
5. **Stage 1b provenance.** `claimProvenance` finds parse calls through evidence spans
   (`:2465-2476`), so film claims would silently lose their receipts.
6. **Mention ranking and ids.** Ranking by description order (`:1512`) needs attention, and so
   does `Int` `offsetOf` (`mentionform.scala:293`), because ticks are `Long`. `Material`,
   `EmittedMention` and `derivedMeta` are typed `SpanSet` (`:1181,1194,2489-2500`).
7. **The status rule (formerly decision B).** `SurfaceExplicit` is documented as "directly stated
   by the source text; must cite spans" (`claim.scala:10`). Licensing it by an annotator's or
   captioner's derivation changes its meaning, and `StatusWeight` gives it 1.0
   (`relations.scala:234`).
   - It must also be decided whether generated captions can license it.
   - Consistency law 3 (`consistency.scala:115-123`) reads text tokens.
   - The bundle-dependent half of any rule cannot live in `spanLaw`, which has no bundle.
   - This is the owner's decision, with those disclosures.
8. **Anchored fingerprint rendering** for `evidence/v2` and `source-support/v2`. The court is that
   one tick moves the fingerprint.
9. **The film court.** A synthetic single-edition film compiles through the same stages. Accepted
   nodes keep their canonical interval union, gaps are preserved, no status is defaulted, and the
   foreign-identity refusals hold.
10. **The Sherlock adapter's repair receipts** need a slot on `AnchoredNarrativeAtlas`.

## 5. Gate, per slice

- **Correctness first, then formatting.** Run `sbt -batch clean compileAll testAll` on the merge
  result, then `scalafmtCheckAll scalafmtSbtCheck` as a separate run.
- **Docs examples.** Run `cd docs-site && npm run verify:examples` for S3 onward.
- **SD2's two surviving checks:** a clean merge-result tree over the exact touched paths, and a
  gate log with bound test totals and its command receipt. All 56 test tasks must report.
- **Probe mutants:** each compile-time-probe mutation gets its own clean recompile
  (AGENTS.md:1387-1398).
- **Parity:** the S0 values do not change.
- **Mutation witnesses:** one named witness for each new guard.
- **Cold review:** a fresh-context agent reviews every slice in a separate pass (SD6).
- **CI:** cite each run by URL, matrix cell and SHA once CI runs. It has never run, because the
  Actions billing block is deferred.

## 6. Tracker changes on approval

- **`bd-01M1CQKRG1A4J4BEWCC78F4TEZ` becomes D1A-types.**
  - Its paths add `core` (source, atlas, claim), `laws`, `codec` (story and core), `view`,
    `pipeline`, `fixtures`, docs-site and the align call sites.
  - "No align/codec edit" becomes: no align scoring change, no 0.7.0 wire change for text, and
    film wire versioning left to V1.
  - Its courts add S0 and the per-slice courts.
- **A new D1A-film bead** is filed. It carries the §4 list, blocks D1B and E0, and is blocked by
  D1A-types.

## 7. What this plan does not do

- It adds no film compile path (D1A-film).
- It makes no aligner behaviour, `SourceView` or `HsmmResult` change (D1B).
- It adds no film artifact versioning or renderer (V1).
- It uses no Sherlock bytes and makes no corpus change.
- It makes no provider, decoder or media change.

## 8. Non-claims

- Line and caller counts come from four surveys and two cold reviews, and are good to about ±10%.
- Byte-identical text output is an intent until S0 exists and holds on every slice. Compile
  parity is JVM-only.
- The split rests on the author's reading that D1A-film is additive to D1A-types' signatures.
  D1A-film's plan must confirm it, and any break it finds lands before 1.0.
- Revision 3 has had a focused cold review of its D1A-types half. The result is recorded below
  once it returns.
