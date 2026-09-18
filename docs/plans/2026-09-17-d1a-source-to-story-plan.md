# D1A: the source-to-story seam — a phase plan (revision 2)

*2026-09-17. Draft for owner approval. Written against `main` at `0e36a7fa`, after the intake
contract landed. Bead: `bd-01M1CQKRG1A4J4BEWCC78F4TEZ`. Governing record: ADR 0007 §5, §7, the
migration sequence, and its rejected alternatives. Everything below is LocallyObserved. No code
has moved.*

*Revision 1 was returned by a fresh-context cold review with five blockers and ten should-fix
findings. The author spot-checked the two blockers that change the plan's shape and found both
true. Revision 2 answers every finding. Where it declines one, it says why.*

| | Revision 1 | Revision 2 |
|---|---|---|
| Film claims | compiler hard-codes `SurfaceExplicit` (`compiler.scala:1352`, `:1464`) and `spanLaw` stays text-only, so no film situation could exist | owner decision B: the status rule is generalised to "located support" (ADR 0007 §7: "the same rule as text proposals") |
| Codec refusal | a typed refusal from `encode`, which returns a plain `String` behind a total circe `Encoder` | refusal at compile time: the 0.7.0 codec takes a `TextModel` witness, so a film model cannot reach it |
| Film alignment | silently empty (`StorySourceView:61-66` files every node as unsupported) | the text aligner takes the `TextModel` witness, so a film model cannot reach it; D1B adds film alignment |
| `StoryText` | forgeable: unsealed atlas, two bundles, text not tied to its model | the atlas is sealed, the bundle is derived from it, and the witness wraps the model and is minted only for written text |
| Fingerprints | `evidence/v2` and `source-support/v2` render spans only, so film ticks were invisible | anchored rendering is added, used only when anchored |
| Chart anchors | re-key to a closed anchor type (about 25 `.sentence` reads, plus `proposition` and a codec shape) | no re-key: charts stay sentence-keyed on the atlas's proposal surface (ADR 0007 §5) |

## 0. Decisions the owner must make at approval

1. **A. Override ADR 0007's Stage D block, or show that it closed.** ADR 0007:477-479 says
   Stages B–D "remain blocked until [the existing vertical] gate closes": one real transcript
   through Stage 1b to one answered research question. No record says the gate closed. Stages B
   and C landed anyway (B0R, C1). The owner's 2026-09-17 decision that 1.0 includes video recall
   implies an override but does not state one. S1 records it in the ADR as the owner's line.
2. **B. The status rule for film claims.** The compiler assigns `SurfaceExplicit` to situation
   and entity-mention claims. `ClaimMeta.spanLaw` (`claim.scala:82-95`) refuses `SurfaceExplicit`
   without text span evidence.
   - **Recommended:** generalise the law to "`SurfaceExplicit` requires located support". That
     means text spans on a written-text bundle, and anchors on any other bundle. A film situation
     is explicit in its proposal-source text (an annotation description), and its located
     evidence is the playback anchor. This is ADR 0007 §7's "same rule as text proposals", and it
     invents no new status.
   - **Alternative:** a separate status for perceptually anchored claims. That is new epistemic
     vocabulary, which ADR 0007 §6 and §7 argue against.
3. **C. Approve the design in §2 and the order in §3.** D1A is **XL** in total, not the L the
   bead says.

## 1. Context

The owner decided on 2026-09-17 that **1.0 is not text-only**. D1A rewrites `StoryModel`, the
story nodes and `AlignmentSource`, so it must land before the 1.0 signature freeze. ADR 0018 does
not change D1A's design:

- `story`, `document` and `acquire` cannot see `corpus`;
- `AnnotationTimeline` axes cannot hold evidence (§2.8);
- a future Sherlock adapter lowers corpus segments into core anchors and keeps the segmentation
  id only as lineage.

The facts that shape the plan, re-verified after the review:

- **Codec.** Node *decoders* hard-code `SpanSet`: `codec/story.scala` 99, 146, 174, 239 and 309,
  and `CircumstanceEdge` at 536-550. The encoders are type-directed. `StoryModelCodec.encode`
  returns `String`, `contentChecksum` returns `Checksum`, and `modelEncoder` is a total `Encoder`
  (:700-766). They have 9 main-code callers. The `Evidence` codec is at `codec/core.scala:265-282`.
- **Align** reads `model.source.canonicalText` at `bridge/StorySourceView.scala:52`, and
  `AlignmentSource.apply(model: StoryModel[Validated])` (`story/alignment.scala:82`) is total.
- **`EvidenceSupport.of`** (`core/source.scala:1332`) checks only nonemptiness and bundle/stream
  membership, and its `mixedBundle` branch is dead.
  - **Exact intervals are reachable but only by hand:** `anchors` is a public val, but there is
    no `intervalsOn(axis)`.
  - **`textSpans`** (:1321-1323) merges spans across all text streams.
  - **The unexercised refusal:** `SourceBundleSuite:149` tests two of three refusals; the
    foreign-bundle case is untested.
- **`NarrativeSourceAtlas`** (`atlas.scala:712`) is an unsealed trait whose `bundle` and
  `surfaceAtlas` the implementer supplies. Its scaladoc already anticipates a film adapter
  supplying "the same compiler-facing surface".
- **Compiler charts** are checked against `atlas.byId` sentences (`compiler.scala:524-528`).
- **Proposal units** carry `surface: Option[SurfaceUnitId]` (`source.scala:1687`).
- **No golden pins compiler output.** The War of the Ghosts compile suites live in `pipeline`
  (JVM-only). The hand-built WOG model goldens (`95028b2c…`, `ce61e761…`) live in `fixtures`
  and are already cross-platform.

## 2. The design

### 2.1 A sealed atlas, and one bundle

`NarrativeSourceAtlas` becomes a `sealed trait` with two final implementations in `core`:

- **`TextNarrativeAtlas`**, as today, over a written-text bundle.
- **`AnchoredNarrativeAtlas.of(bundle, units, proposalSurface)`**, which is checked. It refuses:
  - any unit whose support is off the bundle;
  - a written-text bundle (text has its own atlas);
  - a unit whose `surface` names a unit absent from `proposalSurface`.

The trait's `surfaceAtlas` is renamed `proposalSurface`, which has zero production callers. For
text it is the canonical surface. For film it is the proposal-source text (annotation
descriptions or captions, ADR 0007 §5). It is **never** canonical, and never a target for node
support.

`StoryModel` holds `atlas: NarrativeSourceAtlas`, and `bundle` is `atlas.bundle`. That gives one
source of truth. Lookup becomes map-backed.

### 2.2 Text access is a witness that wraps the model

```scala
final class TextModel[S <: ModelStatus] private (val model: StoryModel[S], val text: StoryText)
object StoryModel:
  def asText[S <: ModelStatus](m: StoryModel[S]): Option[TextModel[S]]
```

`asText` mints the witness from the model's **own** atlas. It succeeds only when all of the
following hold:

- the atlas is a `TextNarrativeAtlas`;
- the bundle is `WrittenText` with a `TextCharacter` primary axis;
- every node support is `Text`;
- every claim's evidence carries no anchors.

There is no other door. A film bundle that happens to carry a subtitle stream gets no witness, so
no text-only consumer can score a text quotient of a film (ADR 0007's rejected "subtitles as the
film source").

**Text-only consumers take `TextModel[S]`.** They are the 0.7.0 `StoryModelCodec`, the derivation
artifact, `StorySourceView`, the `view` compilers, the pipeline `Features`, the docs-site
examples, and the text operations `supporting`/`covering` (`model.scala:47,55`,
`graph.scala:177,216`). A film model is refused at the call site by the compiler, not at runtime
by an empty result. That answers blockers 2 and 3.

**Alternatives rejected, with reasons recorded:**

- **A phantom medium parameter** (`StoryModel[M, S]`). ADR 0007 rejects parameterising the whole
  model by medium.
- **A capability index on `StoryModel`** (`StoryModel[S, HasText]`). It refuses at compile time
  too, but it puts a second parameter on every one of the model's mentions. The witness touches
  only text-only consumers.
- **`text: Option` fields.** A caller could pair a model with a foreign text.
- **An `Either`-returning `encode`.** It turns a compile-time fact into a runtime refusal, across
  9 callers.

### 2.3 Node support: `TypedSupport` in core, one canonical form

```scala
enum TypedSupport:
  case Text(spans: SpanSet)
  case Anchored(support: EvidenceSupport)
  def textSpans: Option[SpanSet]
```

It lives in `core` because `acquire` (S3) and `story` (S4) both need it, and `acquire` does not
depend on `story` (`build.sbt:152`).

**The canonical form rule:** `Text` if and only if the bundle is written text; `Anchored` if and
only if it is not. The non-canonical twin, an `Anchored` support holding only canonical-text
anchors, is refused.

**It is checked at the model's construction, not only by the validator.** `StoryModel.draft`
returns `Either` and checks the following, because `view`, the codec and the derivation artifact
all consume drafts:

- the canonical form;
- that every `Anchored` anchor is on `atlas.bundle`;
- that every `Text` span is within the text extent.

It has 17 callers in 14 files (6 in main code). The primary projection is derived through the
model, as `m.primaryOf(node)` or `m.primaryOf(support)`. The caller never supplies a bundle.

**Nodes affected:** five case classes (`EntityNode`, `EventNode`, `StateNode`, `SegmentNode`,
`ContextFrame`), the `SituationNode` enum wrapper, and `CircumstanceEdge`. Overloaded companion
`apply`s taking a `SpanSet` keep the 54 text construction sites compiling. `Text` encodes as
today's bare `SpanSet`.

**Rejected:**

- **`EvidenceSupport` on every node.** Every construction would need a bundle, and decoders would
  need bundle context.
- **Keeping `SpanSet` and fabricating spans for film.** That is embed-bench's `TimedSourceView`
  pattern, which the PRD forbids.

### 2.4 `core.Evidence` and the status law

`Evidence` gains `anchors: Option[EvidenceSupport] = None`:

- `spans` is set if and only if the bundle is written text, and `anchors` is set if and only if it
  is not;
- a claim carrying both is refused;
- the codec omits `anchors` when it is `None`.

Because a `TextModel` cannot hold anchors, the 0.7.0 wire bytes cannot change.

`spanLaw` follows owner decision B. The claim-evidence anchor checks that the validator never
performs today (`validate.scala:153-159`) are added.

### 2.5 Charts stay sentence-keyed, on the proposal surface

**No re-key.** The compiler's chart checks (`compiler.scala:524-528`) run against
`atlas.proposalSurface`:

- **Text:** the proposal surface is the canonical surface, so behaviour is identical.
- **Film:** it is the proposal-source text. Stage 1b charts its sentences, and each film proposal
  unit's `surface` names the description sentence it came from.

Evidence for a film proposal is the unit's playback anchors. The compiler refuses a film proposal
whose support is spans into the proposal surface. That is the guard against `TimedSourceView`'s
fabrication.

**Rejected:** a closed `Sentence | Unit` chart anchor. It costs about 25 `.sentence` reads, the
`MentionGraph` and `localCharts` keys, `PropositionChart.sentence` in `proposition`, and a new
codec shape. It buys nothing, because the proposal surface already exists in the ADR's design.

**Limitation stated:** one description sentence per unit in D1A.

### 2.6 `AlignmentSource`

`AlignmentSource` is sealed, since outside implementers are not a 1.0 surface. The general source,
built from any `StoryModel[Validated]`, exposes `evidenceOf(target)` and `primaryOf(target)`.

The text projection `sourceSupport(target): Option[SpanSet]` moves to the text source built from a
`TextModel[Validated]`. `StorySourceView` consumes that one. This changes three call sites in
align (`StorySourceView.scala:63,66,121`) mechanically. It does not change align's behaviour or
scoring. D1B moves align onto `primaryOf`.

### 2.7 Fingerprints see film support

`evidence/v2` (`compiler.scala:2650-2660`) and `source-support/v2` (:2562-2568) gain an anchored
rendering: bundle, stream, axis, and exact interval ticks. It is used only when the support is
anchored, so text bytes are unchanged. ADR 0007 law 3 and house rule 10 require it.

**Court:** two film compilations that differ in one interval tick must differ in fingerprint.
**Mutation:** drop the ticks from the rendering; the court must fail.

### 2.8 Scope boundary for film in D1A

- **One bundle per model.** Sherlock's two-part edition is intake P5 and D1B.
- **`EditionPlayback` axes only.** `PlaybackInterval.on` refuses other kinds
  (`source.scala:874`), and admitting `AnnotationTimeline` evidence needs its own ADR amendment.
- **A synthetic, cross-platform film fixture** built from `SourceBundle.filmEdition`.
- **No film wire form.** Film models have no witness, so no codec, text aligner or viewer accepts
  them. The wire form and schema 0.8.0 belong to V1 (`bd-01M1CQQDCWN2RTT1Z8XNNB7VMY`).

## 3. The slices

Every slice lands on `main` on its own green gate (§4), with the S0 pinned values **unchanged**.

### S0 — Text parity baseline (test-only; S)

**Compile parity, in `pipeline`** (JVM-only, where the WOG compile suites already live). It pins:

- the `NarrativeCompilation` fingerprint, `candidateSet` hex, `StoryModelCodec.contentChecksum`
  hex, and the `derivation.json` checksum of the War of the Ghosts compile;
- one exemplar each of the `source-support/v2`, `evidence/v2` and mention-id offset renderings.

S0 also adopts `pipeline/src/test/resources/runs/2026-09-02-wog-record-1/`, which no test reads
today, or records why it does not.

**View parity:** a checksum of one `view` artifact over the fixture model. `view` has no golden
today, and S4 migrates about 25 of its lines.

**Cross-platform model parity** is already pinned by the `fixtures` goldens. S0 cites them
rather than duplicating them. Compile parity on JS and Native is a non-claim.

**Mutation witnesses:** perturb one fixture span offset. Then plant the three code-side mutants
S0 must kill:
- emitting `"anchors": null`;
- changing the text support encoding;
- changing a span rendering in `source-support/v2`.

### S1 — ADR 0007 amendment (docs; S)

S1 records the following, with the rejected alternatives of §2 on the day (SD5):

- **Owner decisions A and B.**
- **The vocabulary:** `TypedSupport`, `TextModel`/`StoryText`, `asText`, `PrimaryProjection`,
  `AnchoredNarrativeAtlas`, `proposalSurface`, `Evidence.anchors`, `intervalsOn`, `hasSupport`.
- **The canonical-form rule.**
- **The scope boundary.**
- **The D1A/D1B split,** which today lives only in the PRD and the beads.

### S2 — Core substrate (core, laws; M)

- **The sealed atlas:** seal `NarrativeSourceAtlas`, add `AnchoredNarrativeAtlas`, rename
  `proposalSurface`, and back lookup with a map.
- **`EvidenceSupport.of` refuses:**
  - an anchor/stream kind mismatch;
  - a `MediaTime` anchor whose declared axis is not its intervals' axis;
  - an axis foreign to the bundle;
  - text spans outside the extent.

  The dead branch is deleted.
- **Per-stream `textSpans`.** Add `intervalsOn(axis)`, the lossless union.
- **New types:** `PrimaryProjection`, `TypedSupport`, `Evidence.anchors` with the both-set
  refusal, and `spanLaw` per decision B.
- **`LegacyAudioBinding.toMediaSupport`** (:1639-1670) can never return `Right`. Fix it or delete
  it.
- **Laws and probes:**
  - the refusals above;
  - the untested foreign-bundle case at `SourceBundleSuite:149`;
  - an `intervalsOn` round-trip;
  - construction probes for `NarrativeProposalUnit` and `AnchoredNarrativeAtlas`;
  - a probe that no class outside `core` can extend the sealed atlas.

### S3 — acquire (acquire, document call sites; S–M)

- **`SourceSupport(score, support: Option[TypedSupport])`**, with a text constructor and a derived
  `spans`.
- **`EvidenceRef`** gets a support accessor and no new case.
- **The resolver's `hasSpans`** (`resolve.scala:367`) becomes `hasSupport`. This changes the
  acceptance rule, so it gets a cold review even though text verdicts must be identical. A court
  checks that every text resolver verdict in the existing suites is unchanged.
- **`TaskReferences.validateAgainst`** gains a `NarrativeSourceAtlas` overload.
- **No provider edit:** providers import only prompt and receipt types from `acquire`.

### S4 — Story envelope and the text path end to end (L)

Modules: story, core call sites, codec, view, align (call sites only), document (text path only),
pipeline, fixtures, laws, docs-site.

- **`StoryModel` holds the sealed atlas.** `draft` returns `Either` and checks what §2.3 lists.
- **`TypedSupport` on nodes,** with overloads for the 54 text sites.
- **The witness:** `TextModel` and `asText`. The text consumers of §2.2 take `TextModel`.
- **`AlignmentSource`** is split as in §2.6.
- **The compiler's text path adopts the new types** (`:1422` `minSpan`, `:2171` `draft`) and
  gains no film path. `TextNarrativeAtlas.of` can fail, but `NarrativeCompilerInput.of` already
  returns `Either`, so the failure surfaces there.
- **Validator:**
  - text checks move behind `TextModel`;
  - `hierarchy.member-within-parent` (`validate.scala:402-412`) is re-expressed on the primary
    projection, so it survives for film;
  - bound checks are added for `ContextFrame` and `CircumstanceEdge` support, and for
    claim-evidence anchors.
- **Probes:**
  - `StoryModelUnforgeableSuite` is extended;
  - `TextModel` has no public door;
  - the node types get their first construction probes, recording what stays open (public case
    classes) and why.

**S4 lands with every existing text behaviour intact. It compiles no film.**

### S5 — The compiler's film path (document; L)

- **`NarrativeCompilerInput` holds the sealed atlas.**
  - The text `of(source, SurfaceAtlas, …)` overload stays, so `ChartProposalProvider.input` does
    not change.
  - The four `SurfaceAtlas` annotations move to the interface.
  - `validateBundle` and `validateSpans` delegate to the support check.
- **Chart checks run against `proposalSurface`** (§2.5).
- **`materialize`** (:2425-2427) unions typed support.
- **The ordering, mention-id and root-segment extent** (:2025) come from the primary projection.
- **Mention forms** degrade through the existing `Definiteness.Unknown` path when there is no
  `TextModel`.
- **The trajectory** yields its existing empty or `Missing` forms without text sentences.
- **Anchored fingerprint rendering** (§2.7).
- **The film court:** a synthetic single-edition film atlas with a proposal surface, hand-built
  charts on its description sentences, and partial proposals with gaps. It must show:
  - the compile passes through the same stages;
  - each accepted node keeps its exact `PlaybackIntervalSet`;
  - `asText` is `None`, and passing the model to any text consumer does not compile, as a
    negative compile assertion with a same-shape positive control;
  - gaps are preserved;
  - claim statuses follow decision B, with none defaulted;
  - foreign bundle, axis, stream and anchor refuse;
  - a proposal whose support is spans into the proposal surface refuses;
  - one tick changes the fingerprint.

**Order:** S0, then S1, then S2. S3 and S4 follow in either order. S5 comes last.

## 4. Gate, per slice

- **Correctness first, then formatting:** `sbt -batch clean compileAll testAll` on the merge
  result, then `scalafmtCheckAll scalafmtSbtCheck` as a separate run. The `checkAll` alias runs
  formatting first.
- **`cd docs-site && npm run verify:examples`** for S3–S5. The docs-site examples are outside
  `compileAll`/`testAll`.
- **SD2's two surviving checks:** a clean merge-result tree over the exact touched paths, and a
  gate log carrying bound test totals with the command receipt. All 56 test tasks must report.
- **Its own clean recompile for every compile-time-probe mutation** (AGENTS.md:1387-1398). A
  single clean at the start does not cover mutants applied afterwards.
- **S0 values unchanged,** checked in the log.
- **A named mutation witness** for each new guard.
- **A cold review by a fresh-context agent for every slice,** in a separate pass (SD6).
- **CI:** cite the run by URL, matrix cell and SHA once it runs. It has never run, and the first
  push hit an Actions billing block, which the owner has deferred.

## 5. The bead must change to match

When this plan is approved, `bd-01M1CQKRG1A4J4BEWCC78F4TEZ` is amended. The paths add `core`
(source, atlas, claim), `laws`, `codec` (story and core), `view`, `pipeline`, `fixtures`,
docs-site, and align call sites. "No align/codec edit" becomes:

- no align scoring or behaviour change;
- no 0.7.0 wire change;
- no film wire form.

The courts add S0 parity, the core refusals, and the S5 film court. The size becomes XL.

## 6. What this plan does not do

- **No aligner behaviour, `SourceView` or `HsmmResult` change.** That is D1B.
- **No film wire form, schema bump or renderer.** That is V1.
- **No Sherlock bytes, two-part edition, annotation-timeline evidence, or corpus-to-core
  lowering.** Those are intake P5 and D1B.
- **No provider, decoder or media change.**

## 7. Non-claims

- Line and caller counts come from four read-only surveys and a cold review at `0e36a7fa`.
  They are good to about ±10%.
- Byte-identical text output is intent until S0 exists and holds across every slice. Compile
  parity is pinned on the JVM only.
- The film court uses a synthetic edition. It shows that the types and stages admit film, not
  that any film recall result is meaningful.
- Decision B's recommended rule is the author's reading of ADR 0007 §7. It is not the owner's
  ruling until S1 records it.
