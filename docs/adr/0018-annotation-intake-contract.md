# ADR 0018 — The annotation intake contract: two modules, two guarantees, records that code reads

**Status:** Accepted 2026-09-17, single-developer mode (AGENTS.md SD5)

**Decider:** the owner's agent, single-developer mode

**Plan:** `docs/plans/2026-09-17-annotation-intake-contract.md` (revision 3, three independent
review passes). Companion: `docs/plans/2026-09-17-friends-hill-climb-and-normalization.md`.

## Context

This repository does not lack an intake vocabulary. It has a careful, unforgeable, receipt-carrying
one, and most of it is unreachable, while the working code routes around it with literals, a forged
film edition, and per-corpus Python forks. Measured against `0df1d9e7`:

- `MappingRelation`, `TrackComposition`, `EditionCorrespondence` and `OccurrenceId` have **zero
  references** outside `core/source.scala:1268-1465`, including tests.
- `ClockRepair` — an exact-rational affine clock map whose no-repair overload *always* refuses
  ("cannot enter without a declared repair", `:1349-1360`) — has **no production caller**; it
  appears only in `SourceBundleSuite` and `SourceConstructionBoundarySuite`.
- `SourceBundle.of` (`:1018`) is public and accepts `SourceKind.AnnotationTable`, yet has zero
  callers and demands a `PresentationAxis`. Every axis constructor is kind-specific —
  `textCharacter` (WrittenText), `editionPlayback` (FilmEdition), `inventedEditionPlayback`
  (refuses every kind, `:652-672`). **No axis for a non-edition kind can be minted, so `of` can
  never be satisfied for `AnnotationTable`.**
- Consequently the one corpus with no admitted video forges one:
  `FilmFestivalRecallMapping.scala:148-168` mints `EditionId("filmfestival-annotation-<sha>-<part>")`
  into `SourceBundle.filmEdition`, its own Scaladoc stating *"This is deliberately not a film
  edition."* The type says `FilmEdition`; the comment says it is not one.
- No code reads any `docs/data/*/source-manifest.json`. The four `docs/data` references in the
  codebase are comments.
- The Sherlock crosswalk exists three times: `timebase-repair.json`, literals at
  `sherlock.scala:45-67`, and `SherlockAnnotationsSuite.scala:188-199` — a test *named* "carries the
  crosswalk constants of timebase-repair.json v2" that never opens the JSON and asserts Scala
  literals against Scala literals. It cannot fail for the reason its name gives.
- `FixtureManifest.verify(bytes)` is never on a main path, and `probe.verifyInput`
  (`media/probe.scala:273`) compares a **caller-supplied** `Checksum`.

One pattern, ten instances: **records and types that assert authority and are wired to nothing.**

## Decisions

### 1. Two modules, mirroring the `core` → `media`/`pipeline` split

```
corpus         crossProject(JVM, JS, Native), CrossType.Pure, dependsOn(core)
corpus-intake  project (JVM-only), dependsOn(corpus.jvm, core.jvm), Test / fork := true
```

`corpus` holds the schema, refusals and laws, and performs no I/O. `corpus-intake` owns readers,
byte verification, receipts and file layout, and no semantics; no portable module depends on it.
`corpus` does **not** depend on `features` — an earlier draft listed it and nothing used it — nor on
`recall` or `align`. No cycle.

### 2. Two guarantees, each closed at the type level and proved from outside

**You cannot read a byte you have not hashed yourself.** `ArtifactStore` is *enumerable*, so
completeness is checkable in both directions; `verify` copies each artifact into owned storage
**before** hashing and yields a `Verified` carrying `VerifiedArtifact(id, bytes: IArray[Byte],
checksum)`; `CorpusReader.open` takes a `Verified`. `IArray`, not `Array`: two aliasing routes were
demonstrated by probe — a public accessor, and the array the store still holds — and making the
carrier non-case addresses neither.

**You cannot produce a canonical value without its raw coordinate.** `SourceCoordinate` and `Raw`
are `final` non-case classes with `private[corpus]` constructors. The sensitive thing is the joined
`value ↔ literal ↔ coordinate` relation, not the coordinate alone.

**`Coded` is not an enum.** Scala 3 enum cases are case classes, so `Known(unmapped.raw)` compiles
from outside and fabricates a decode that never happened — genuine coordinate, invented
interpretation status. It is a sealed trait over final non-case classes, produced only by decoding
against a profile, a code book and the applicable row context.

Every forge probe lives in `storymodel4s.consumer`, **outside** the defining tree, with a positive
control in the same file and `typeCheckErrors` rather than a bare negative `typeChecks` — which
returns `false` on any error and has already produced two false passes in this repository.

Each probe file also carries a never-called `zincAnchor` method taking the probed types as real
parameters. `typeCheckErrors` expands to a literal list, so without a real reference Zinc records no
dependency and the suite is not recompiled when a probed type changes — a mutation then reads as
survived when it is killed. The anchor fixes SHAPE mutations and **cannot** fix accessibility
mutations, because it would have to name the very member the probe asserts is unnameable; those
remain invisible without a clean, so **mutation runs still clean the test scope**. Measured table in
`docs/design/unforgeable-types.md`, "A probe that cannot fail".

**The verified payload is not exposed at all.** An earlier version of `VerifiedArtifact` offered
`bytes: IArray[Byte]` and called it an immutable view. That was false: `IArray` is an opaque type
over `Array`, and the standard library hands the backing array straight back —
`IArray.wrapByteIArray(a.bytes).unsafeArray(0) = 1` type-checks and mutates, as does matching the
`IArray` against `Array[Byte]`. A consumer could not forge a `VerifiedArtifact` but could rewrite
one after verification while its checksum went on vouching for the original. There is now no
accessor returning the array: readers use `byteAt`, `iterator`, or `toArray`, which copies.

**Non-claim: the guarantees are closed at the TYPE level, not at runtime.** `private[corpus]`
compiles to public bytecode, so plain `java.lang.reflect` — without even `setAccessible` — can
construct a `SourceCoordinate`, a `Raw` and hence a `Known` from any package, and `Raw$.of` and
`Coded$.decode` are callable from Java. Reflection and non-Scala callers are outside the perimeter
these types defend.

**A contract the encoding slice owes this one:** `Applicability` compares a column's *normalized*
value, and normalization is the declared encoding's canonical rendering. Until that rendering is
pinned per encoding, `RowContext` accepts whatever the reader put in it.

### 3. `private[corpus]` admits the whole `storymodel4s.corpus.*` tree

That includes `corpus-intake` and its tests. The ceiling is stated rather than implied: "constructible
only by a reader" means "constructible anywhere in two modules", and the probes are placed
accordingly.

### 4. Standardize the artifact-identity core; let typed sidecar records keep their own schemas

The manifest is *thin*: artifacts, `RecordRef`s, admission, content policy, non-claims, extensions.
It embeds no `Clock`, `Segmentation`, `CodeBook` or `Capability`. `timebase-repair.json` is richer
than a manifest — it carries `certifies`/`doesNotCertify`, `nonEquivalences`,
`scientificRestrictions` and a `whyNotRepaired` note explaining that the notebook's repaired clock is
correct for fMRI and wrong for media — and folding it into one flat schema would destroy a good
record. Sidecars stay separate **and** must resolve uniquely to a `VerifiedArtifact` in the snapshot,
with schema and version checked there; a `RecordRef` that merely names a path would reopen "verify
one thing, read another".

### 5. `corpus` takes circe directly

`circe-core` and `circe-parser`, portable (`%%%`), as `codec` does at `build.sbt:401-403`. Hosting
the parser in `codec` fails: `codec` depends on `view`/`align`/`recall`/`interview`, and
`corpus-intake` would have to drag that stack in.

### 6. Readers first; vocabulary as a corpus forces it

An earlier revision of the plan specified six vocabulary families before opening a single workbook
and was rejected at n = 0. Order: coordinates and cells → reader → first corpus → what that corpus
forces → a second corpus in the same dialect as the schema-breaker → the retrofits.

### 7. Names, because three collide

`CorpusProfile`, not `Profile` (`interview.scoring.Profile`, `Scoring.scala:347`; `codec` imports
both). `LinkEvidence`, not `Evidence` (`core.Evidence`, `claim.scala:29`, is spans-and-claims and
the wrong shape). `RecallRow`/`RecallSpan`, never `RecallUnit` (`recall/unit.scala:124`).

### 8. Error strategy

Accumulate cell-level refusals per sheet under a declared cap; fail fast on structural refusals. A
27,777-row workbook must not surrender one bad cell per run. Design contract rule 12 names
`ValidatedNec` for the accumulating half.

### 9. JDK StAX for xlsx

No new XML dependency. The declared `<dimension>` is not trusted: `friendsSRMStoryBoard` declares
`A1:O58` and holds 14 data rows, which is how an intake record came to claim 57 events from the
wrong episode.

## What this does not decide

Three questions are deferred **to the slice that will have evidence**, not answered here:

1. ~~A lawful axis for a timed annotation table.~~ **DECIDED 2026-09-17: add the constructor.**
   `AxisKind.AnnotationTimeline`, `PresentationAxis.annotationTable` and
   `SourceBundle.annotationTable` now exist in `core`. A timed annotation gets an axis identified by
   the annotation's own checksum, with no `EditionId` and no picture stream, so no corpus has to
   assert a film edition that does not exist. `SourceKind.AnnotationTable` was declared and
   unreachable precisely because every axis constructor was kind-specific; it is now reachable.
   Blast radius measured before the change: four files reference `AxisKind`, all in `core`, and no
   codec switches on it. `coreJVM/test` 175 and `codecJVM/test` 193 green after.
   Retiring Film Festival's forged edition is a separate, later change to the bench adapter.
2. ~~Where ordinal remaps live.~~ **DECIDED 2026-09-17: `SegmentLink` for scale-to-scale maps;
   `MappingFamily` gains nothing; Film Festival's `+106` is neither.**
   Building `SegmentLink` showed that "Friends 56→52" and "Film Festival +106" are not the same
   shape, though they are usually named together. Friends has two ANNOTATION SCALES of one
   stimulus, both globally ordered; a link between them is exactly `SegmentLink`, and the map is a
   test witness. Film Festival has ONE scale whose coarse segment numbers RESTART at 1 in run 2, so
   `(run-1, 7)` and `(run-2, 7)` are different segments with the same ordinal and the source
   numbering is not a total order over the work. A `Segmentation` requires dense 1..n ordinals, so
   the run-local numbering cannot BE one until the offset has already been applied. **The `+106` is
   therefore a reader-level reindex declared by the profile, not a mapping between segmentations**,
   and `MappingFamily` needs no fourth case. Both halves are recorded as tests in
   `SegmentLinkSuite`, including the refusal that makes the boundary checkable.
3. **`tools/corpus/xlsx_rows.py`** — **DECIDED 2026-09-17: retire, on a stated condition.** The
   Scala reader now exists with its own hazard tests (declared dimension untrusted, header-only
   sheets named, padding trimmed on content, DTD disabled). `xlsx_rows.py` stays ONLY while the
   five Film-Festival replay scripts in `tools/corpus/` still consume it, and is deleted when the
   descriptor slice moves those onto the Scala reader's output. It is not kept as "reference
   semantics": two implementations of one reading, with neither binding the other, is the
   duplicated-rule defect this ADR is about, and keeping it deliberately would be worse than
   having found it.

## Rejected

- **One JVM-only module.** Design contract rule 11 bars JVM APIs from anything portable downstream
  would want to depend on.
- **Extending `acquire` in place.** Its stated purpose is the autonomous acquisition protocol —
  task packets, proposal-only agents, critics, resolution (`build.sbt:134`) — and it already hosts a
  corpus parser it should not.
- **Lifting `NfrdBaseballVerifier` wholesale.** It is participant-keyed end to end — transcript /
  TextGrid manifest pairing, `P\d{3}_baseball` regexes, a `SplitSpec` partition, `FieldLineage`, a
  17-field receipt — and it deliberately **retains no bytes** (`:507-512`), the opposite of what a
  verified snapshot needs. Only four primitives move: `RelativeArtifactPath` (`:97-120`), the
  `Reader` shape (`:595`), `ArtifactReceipt` (`:497-505`), and the `RootIssue`/`ReadOperation`
  taxonomy (`:141-155`). Shared artifact verification and retained-byte snapshots are related but
  distinct contracts.
- **A Python-only interchange with no Scala types.** It cannot make either guarantee a
  precondition.
- **Deriving capabilities and admitting on the manifest's own assertion.** That is identity asserted
  by the caller. `Capability` is derived from the opened corpus and the declared set is a claim that
  `open` checks. `CorpusRegistry`, `Requirement` and `CapabilityShape` are deferred until a second
  experiment arm declares a requirement; `satisfying` has no caller today.

## Non-claims

- No corpus is admitted. `AdmissionStatus` is recorded, never decided.
- No inference, default or published number changes; the retrofit's acceptance criterion is
  byte-identical report output.
- Physical encoding is not standardized, and deliberately so.
- No claim of reduced code size. The claim is that a new corpus needs no new *parser*.
