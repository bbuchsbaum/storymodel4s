# The annotation intake contract: a plan

*2026-09-17, revision 3. Written against `main` at `0df1d9e7` (CI landed at `14f95f33`; revision 1
was written against `43140bcb` and several of its claims did not survive).*

*Revision 1 was vetted adversarially and came back with four blockers. Both of its "structural
walls" had a door, its smallest-useful-subset did not compile, and its phase order contradicted the
companion plan's own sequencing rule. Separately, a substrate survey found that most of the
vocabulary revision 1 proposed to build **already exists in `core` and has zero callers.* Revision 2
is smaller, differently ordered, and aimed at a sharper diagnosis. What revision 1 got right — a
verification precondition on reading, and a coordinate on every canonical value — survives, with
both doors closed.*

Companion: [`2026-09-17-friends-hill-climb-and-normalization.md`](2026-09-17-friends-hill-climb-and-normalization.md) §5–6.
Single-developer mode (AGENTS.md SD1–SD6).

## 0. The diagnosis

The repository does not lack an intake vocabulary. It has a careful, unforgeable, receipt-carrying
one — and most of it is unreachable, while the working code routes around it with literals, a
forged edition, and per-corpus Python forks. **One pattern, ten instances.**

| # | Instance | Evidence |
|---|---|---|
| **D1** | `MappingRelation`, `TrackComposition`, `EditionCorrespondence`, `OccurrenceId`: **zero references** outside `core/source.scala:1268-1465`, including tests. | measured |
| **D2** | `ClockRepair` — an exact-rational affine clock map whose no-repair overload *always* refuses ("cannot enter without a declared repair") — has **no production caller**; two `core` test suites only. | measured |
| **D3** | `SourceBundle.of` (`:1018`) is public and *does* accept `SourceKind.AnnotationTable` — but it has **zero callers**, and it demands a `PresentationAxis`. Every axis constructor is kind-specific: `textCharacter` (WrittenText), `editionPlayback` (FilmEdition), `inventedEditionPlayback` (refuses every kind, `:652-672`). **No axis for a non-edition kind can be minted, so `of` can never be satisfied for `AnnotationTable`.** Reachable in principle, unreachable in practice. | measured |
| **D4** | Consequently the one corpus with no admitted video forges an edition: `EditionId("filmfestival-annotation-<checksum>-<part>")` into `SourceBundle.filmEdition`, with the Scaladoc saying *"This is deliberately not a film edition"*. Friends would have to tell the same lie. | `FilmFestivalRecallMapping.scala:148-168` |
| **D5** | No code reads any `docs/data/*/source-manifest.json`. The four `docs/data` references in the codebase are comments. | `sherlock.scala:12,:44`; `filmfest_annotation.py:5`; `within_scene.py:314` |
| **D6** | The Sherlock crosswalk exists three times: `timebase-repair.json`, literals at `sherlock.scala:45-67`, and `SherlockAnnotationsSuite.scala:188-199` — a test named *"carries the crosswalk constants of timebase-repair.json v2"* that **never opens the JSON**. It asserts Scala literals against Scala literals and cannot fail for the reason its name gives. The derivation that binds them (`playbackEndTicks 3565000 + uncoveredTailTicks 500 = 3565500`) exists only in prose. | measured |
| **D7** | `FixtureManifest.verify(bytes)` is never on a main path, and `probe.verifyInput` (`probe.scala:273`) compares a **caller-supplied** `Checksum` rather than bytes it hashed. Verification of a number the caller handed over. | measured |
| **D8** | Four part-offset maps, one name, two incompatible semantics: `score.py:37` and `matched.py:14` use `media-part-b: 100000.0` (an order-preserving sentinel, correct — both feed only Kendall tau-b), `agreement.py:28` uses `1426.0` (a real time, because it measures gaps), `filmfest_gold_film.py:23` adds a fourth in a fourth key space. Nothing declares which is which. | measured |
| **D9** | `run1EndRow = 482` (`sherlock.scala:66`, 1-based) and `PART_A_LAST_ROW = 481` (`extract_scene_frames.py:48`, 0-based): one fact, two languages, two conventions. | measured |
| **D10** | The gold-eligibility rule is implemented twice by acknowledgement — *"Two implementations of one rule is one too many, so this one cites the other"* (`SherlockSceneCoding.scala:14-15`) vs `gold_scene.py:16`. Adding a corpus means forking a runner: `run-filmfestival.py` is the fork of `run-arm.sh`, and `tools/corpus/` holds five Film-Festival-specific scripts. `extract_scene_frames.py:50` also hardcodes an absolute path into a `.worktrees/perception-first-court` checkout that may not exist. | measured |

Three further facts that shape the plan rather than indict it:

- **There are five corpora, not four**, and one of them already solved part of this. NFRD baseball
  has a real intake court at `embed-bench/.../nfrd/intake.scala`. But "generalize it" is the wrong
  verb: `NfrdBaseballVerifier.verify` (`:573-720`) is participant-keyed end to end — transcript /
  TextGrid manifest pairing, `P\d{3}_baseball` regexes, a `SplitSpec` partition, `FieldLineage`, a
  17-field receipt — and it deliberately **retains no bytes** (`:507-512`), the opposite of what
  `Verified` needs. **Lift four primitives, leave the verifier alone:** `RelativeArtifactPath`
  (`:97-120`, path safety), the `Reader` shape (`:595`), `ArtifactReceipt` (`:497-505`, label +
  bytes + checksum with the path hidden from `toString`), and the `RootIssue` / `ReadOperation`
  refusal taxonomy (`:141-155`). Re-point `nfrd` at them, using its existing receipt checksum as the
  parity oracle (`verifyReceipt`, `:842`, returns `Unit`). Two commits — extract, then adopt.
  **Shared artifact verification and retained-byte snapshots are related but distinct contracts**;
  widening the NFRD court's visibility would fight the annotation use case rather than serve it.

- **`timebase-repair.json` is richer than a manifest.** It carries `certifies` / `doesNotCertify`,
  `nonEquivalences`, `scientificRestrictions`, and a `whyNotRepaired` note explaining that the
  notebook's repaired clock is correct for fMRI and wrong for media. Folding it into one flat
  manifest schema would destroy a good record.
- **Segmentation is missing, but narrowly.** Two types come close and both fall short.
  `view.IndependentCoding(name, checksum, intervals: Vector[CodedInterval(recall: ClockSpan,
  group: Int)])` (`voyage.scala:163-170`) is a named, checksummed, disjointness-proven interval
  list — but on **recall** time, with a bare `group: Int` and no ordinals, level or authority;
  `SherlockSceneCoding.load` already builds one. `core.PlaybackIntervalSet.of` (`:869`) is an
  ordered non-empty interval set on an axis, with no identity or semantics. What is missing is
  precise: **a segmentation on a *stimulus* axis carrying ordinals, a granularity level and an
  authority.** Revision 2 claimed nothing modelled timed segments at all; that was false and would
  not have survived a cold read.

**Therefore the plan is: make the existing vocabulary reachable, wire the records to code, and add
only what a real corpus proves is missing.** Standardize the artifact-identity core; let typed
sidecar records keep their own schemas and be read rather than transcribed; leave physical encoding
idiosyncratic.

## 1. Architecture

```
corpus         crossProject(JVM, JS, Native), CrossType.Pure, dependsOn(core)
               traceability, typed cells, segmentation, segment links, code books,
               profiles, capability vocabulary, typed refusals. No I/O.

corpus-intake  project (JVM-only), dependsOn(corpus.jvm, core.jvm), Test / fork := true
               readers (xlsx via JDK StAX, zip, tsv, csv), the generalized verifier,
               receipt and descriptor emission. Owns I/O, no semantics.
               No portable module depends on it.
```

Mirrors the established `core` → `media` / `pipeline` split (design contract rule 11). `corpus`
does **not** depend on `features` — revision 1 listed it and nothing used it. It does not depend on
`recall` or `align`; no cycle.

**Decisions revision 1 left implicit, now made:**

- **`corpus` takes `circe-core` + `circe-parser` directly** (portable, `%%%`, as `codec` does at
  `build.sbt:401-403`). The alternative — parsing in `codec` — fails, because `codec` depends on
  `view`/`align`/`recall`/`interview` and `corpus-intake` would have to drag that whole stack in.
  This is an ADR line.
- **`extensions` is a neutral `Map[String, Json]`**, load-bearing for nothing.
- **Naming.** `Profile` collides with `interview.scoring.Profile` (`Scoring.scala:347`) and `codec`
  imports both: use **`CorpusProfile`**. Revision 1's `Evidence` collides with `core.Evidence`
  (`claim.scala:29`), which is spans-and-claims and the wrong shape: use **`LinkEvidence`**,
  carrying `SourceCoordinate`s. Intake recall types are `RecallRow` / `RecallSpan`, never
  `RecallUnit` (`recall/unit.scala:124`).
- **Error strategy** (rule 12 names `ValidatedNec`): **accumulate** cell-level refusals per sheet,
  bounded at a declared cap; **fail fast** on structural refusals. A 27,777-row workbook must not
  surrender one bad cell per run.
- **`private[corpus]` admits the whole `storymodel4s.corpus.*` tree**, which includes
  `corpus-intake` and its tests. That ceiling is stated in the ADR, and every forge probe lives in
  `storymodel4s.consumer`, outside the tree — the precedent is
  `embed-bench/src/test/.../consumer/NfrdIntakePublicSuite.scala`.

### The two guarantees, with their doors closed

**Guarantee 1 — you cannot read a byte you have not hashed yourself.**

Revision 1 was wrong: its `verify` returned `Verified(manifest, at)` with the bytes discarded, so
`open` re-obtained them from an unstated source and nothing bound the bytes hashed to the bytes
read. It also could not implement its own test ("refuse an artifact on disk but absent from the
manifest") because a `ArtifactId => Option[Array[Byte]]` cannot be enumerated.

```scala
/** Enumerable, so completeness in both directions is checkable. */
trait ArtifactStore:
  def list: Vector[ArtifactId]
  def bytes(id: ArtifactId): Either[IntakeRefusal, Array[Byte]]

/** `IArray`, not `Array`. There are TWO aliasing routes and both were demonstrated by probe:
  * writing through a public byte accessor, and writing through the array the store still holds.
  * So `verify` copies into owned storage BEFORE hashing, and no mutable backing array is exposed.
  * Each route gets its own test; making `Verified` non-case addresses neither. */
final class VerifiedArtifact private[corpus] (
    val id: ArtifactId, val bytes: IArray[Byte], val checksum: Checksum)

/** Unforgeable and unobtainable: the only constructor is `verify`, and it carries what it verified. */
final class Verified private[corpus] (val manifest: SourceManifest, val artifacts: Vector[VerifiedArtifact])

/** The thin v2: artifact identity, plus typed records referenced by id and read separately.
  * It deliberately embeds no Clock, Segmentation, CodeBook or Capability — those are the deferred
  * families, and embedding them is what stopped revision 1's minimum subset from compiling. */
final class SourceManifest private[corpus] (
    val corpus: CorpusId,
    val schemaVersion: Int,                       // 2
    val artifacts: Vector[ArtifactRecord],        // id, path, byteLength, sha256, role
    val records: Vector[RecordRef],               // id + schema + path of a typed sidecar record
                                                  // MUST resolve to a verified artifact: see below
    val admission: AdmissionStatus,
    val contentPolicy: ContentPolicy,
    val nonClaims: Vector[String],
    val extensions: Map[String, Json])

object SourceManifest:
  def verify(m: SourceManifest, store: ArtifactStore)
      : Either[NonEmptyVector[VerificationFailure], Verified]

object CorpusReader:
  def open(v: Verified, p: CorpusProfile): Either[IntakeRefusal, OpenCorpus]  // reads only v.artifacts
```

**Sidecars are inside the wall, not beside it.** A `RecordRef` that merely names a path would
reopen "verify one thing, read another" for exactly the records P5 depends on. So every
load-bearing sidecar must **resolve uniquely to a `VerifiedArtifact` in the snapshot**, with its
own schema and version checked there. *Tests:* a `RecordRef` resolving to nothing; two resolving to
one artifact; sidecar bytes changed after the manifest was written; a sidecar whose schema string
or version disagrees with what the reference declares.

**"`open` reads only `v.artifacts`" is a promise, not a type** — `CorpusReader.open` lives in
`corpus-intake`, inside the `private[corpus]` tree, and nothing stops it calling
`Files.readAllBytes`. So it gets a falsifier rather than a sentence: an in-memory `ArtifactStore`
whose bytes match **no file on disk**; if `open` yields anything but those bytes' rows, the test
fails. *Mutant:* give `open` a path parameter and read from it.

**Guarantee 2 — you cannot produce a canonical value without its raw coordinate.**

Revision 1 was wrong here too: `Raw` was a public case class, so `Raw(fabricated, genuine.at,
"anything")` is one line the moment any reader has emitted a coordinate. The coordinate is not the
sensitive payload; the *joined relation* value↔literal↔coordinate is.

```scala
final class SourceCoordinate private[corpus] (
    val artifact: ArtifactId, val container: String, val row: Int, val column: String)

/** Non-case, so no Mirror and no copy. The triple can only be formed by a reader. */
final class Raw[+A] private[corpus] (val value: A, val at: SourceCoordinate, val literal: String)

/** NOT an enum: enum cases are case classes, so `Known(unmapped.raw)` compiles from outside and
  * fabricates a decode that never happened, and `NotApplicable` would take an arbitrary predicate.
  * The raw coordinate stays genuine while the *interpretation status* is invented. These are
  * checked results of decoding against a profile, a code book and the applicable row context. */
sealed trait Coded[+A]
final class Known[+A] private[corpus] (val raw: Raw[A]) extends Coded[A]
final class Unmapped private[corpus] (val raw: Raw[String]) extends Coded[Nothing]
final class NotApplicable private[corpus] (val raw: Raw[String], val condition: Applicability)
    extends Coded[Nothing]
```

Revision 2 accepted a vet's judgement that a forgeable `Known`/`Unmapped` was harmless "because it
carries no relation beyond `Raw`'s". A third review disproved that by probe: the status *is* a
claim — that this literal decoded to this value under this code book — and fabricating it is
exactly the forgery the wall exists to stop. Probed independently of `Raw`.

All three are proved from `storymodel4s.consumer`, with a positive control in the same file and
`typeCheckErrors` rather than a bare negative `typeChecks` — which returns `false` on any error and
has already produced two false passes in this repo.

## 2. The vocabulary, and what is deferred

Specified only as far as the first two corpora need. Everything else waits for evidence.

**Cells.** `Cell.integer/decimal/text/stamp`, each requiring a **declared** encoding from the
profile. Revision 1's `UndeclaredEncoding` fired only when two encodings fit — which leaves a column
with no declaration silently guessed, and an Excel serial `1.000983796296296` parses perfectly well
as a decimal. Corrected: **no declaration is an unconditional refusal**,
`NoDeclaredEncoding(at, column)`; genuine ambiguity is a profile-validation error, not a cell error.

**Clocks.** Reuse `ClockRepair` and `ExactRational` / `RationalTimebase`; do not build `ClockMap`.
Revision 1 claimed `Stamp(clock, ticks)` made cross-clock arithmetic fail to typecheck. False:
`ticks` is a public `Long`. Clocks are data read from profiles, so they cannot index a phantom type.
**It is a runtime-checked tag and is described as one.** What the type can do, and does: no
`Ordering[Stamp]`, no public `ticks` (only `ticksOn(axis): Either[...]`), and `ClockRepair` as the
only arithmetic.

**One estimand, one type.** `ClockRepair`'s axes are `PresentationAxisId`s (`source.scala:1325-1330`),
so `Stamp` is keyed by `PresentationAxisId` too — a separate `ClockId` would be rule 7's
two-types-one-estimand in reverse, with no mapping between them — and its ticks are on that axis's
`RationalTimebase`. There is no `ClockUnit` enum: `MinuteDotSecond` and `ExcelSerialDays` are **cell
encodings** (P1), not properties of a clock.

**Segmentation and links.** Genuinely missing; build it. Revision 1's `SegmentLink` had four
defects the vet found and all four are fixed:

```scala
final case class SegmentRef(segmentation: SegmentationId, ordinal: Int)

enum LinkClaim: case Bijection, Coarsening, Edit   // what the constructor CHECKS, not what it labels

enum Target:
  case To(ref: SegmentRef, evidence: LinkEvidence)
  case Removed(evidence: LinkEvidence)
```

Revision 2 had `Maps` and `MergedInto` as separate cases. That was a class **asserted by the
author** which the map itself determines — under `Coarsening`, a coarse segment holding exactly one
fine segment would be `Maps` and its siblings `MergedInto`, so a constructor choice can disagree
with the data, and nothing would catch the lie. Corrected: one `To`, and **multiplicity is
computed**.

`LinkClaim` then becomes a checked property rather than a label — but stating it as "total,
injective, onto" is not enough, and a third review produced the counterexample: `{a → To(x),
b → Removed}` is total, injective on `To`, and covers `x` exactly once, yet is no bijection.
**`Bijection` must therefore admit only `To` — no `Removed` at all** — and then enforce
bijectivity. `Coarsening` ⇒ total, onto, no `Removed`, with many-to-one membership expressed
directly by several `To`s sharing a target: no "survivor" is selected and no sibling is relabelled
an editorial merge. `Edit` ⇒ total, every `Removed` evidenced. Two
further checks revision 2 omitted: every `Target`'s `SegmentRef.segmentation` equals `link.to`, and
every map key is an ordinal of `link.from` — keys stay bare `Int`s, which is tolerable only because
the link names `from`, and the ADR says so.

Composition becomes ordinary function composition on `Option[SegmentRef]`, so revision 2's
`MergedInto∘Maps = MergedInto` table disappears along with the question of whether it typechecks.
The composability law still cannot be stated as written: a composed target's `LinkEvidence` derives
from two links while a directly declared `a→c`'s does not, so equality fails on the evidence field
for **every** witness. Either compare modulo evidence, or give `LinkEvidence` a `Composed(ab, bc)`
case. (Revision 2 reached the same conclusion by a different route; the vet's framing is better.)

Sherlock 1000→50 is a `Coarsening`; Friends em56 39 and 48 are an `Edit`. The companion table calls
those two "REMOVED (merged…)", which under this type is a **P3 decision** — `Removed`, or
`To(neighbour)` — to be taken from `FriendsMoreEMs`, not assumed now.

**Film Festival's `+106` is a `SegmentLink`, not a clock map.** Revision 1 made it an exit criterion
for the clock slice; it is an *ordinal* remap of coarse segment numbers restarting in run 2
(`filmfest_annotation.py:14-16,:36-37`). And ordinals have no home in `core`'s mapping apparatus at
all: `ClockRepair.of` demands two `PresentationAxisId`s, a segment-number space is not an axis, and
`inventedEditionPlayback` refuses outright. Either `SegmentLink` is that home, or `MappingFamily`
gains a fourth case — an ADR decision, taken when Film Festival is retrofitted, not before.

**Code books.** Keyed by the **decoded** code, not the raw literal, or `'1'` vs `1.0` re-enters
through the map key. `Applicability` is a typed, profile-declared predicate —
`WhenColumnEquals(column, code)` — not a string; otherwise Friends' "`WhichEvent` iff
`RecallType == 1`" lives in reader code and the problem has only moved. The converse (a value
present where the condition is false) is a `CellRefusal`, or the "iff" is unenforced.

**Capabilities — declared *and* checked.** Revision 1 let a manifest assert its own capabilities and
a registry admit on that assertion, which is identity asserted by the caller. Corrected:
`Capability` is **derived from `OpenCorpus`** (a gold table at level L with n coders ⇒
`PerUnitGold(L, n, coverage)`), and the manifest's declared set is a *claim* that `open` checks and
refuses on mismatch. `Option[Double]` fields that conflate unknown with absent become typed
(`Measured(d) | Unmeasured`), following `ImportanceWeight.unmeasured`'s precedent.

**Deferred until something needs it:** `CorpusRegistry`, `Requirement`, `CapabilityShape`.
`satisfying` has no caller today and revision 1 never defined `CapabilityShape`. It arrives when a
second experiment arm actually declares a requirement.

## 3. The slices

Revision 1 landed six vocabulary families and a manifest schema before opening a single workbook —
n = 0, while its own Phase B header claimed to avoid n = 1, and while the companion plan says
plainly: *"Do NOT design the standard first."* Corrected: **readers first, vocabulary as forced.**

Every slice names its defect, its tests, and the mutants that must die. Gate scope comes from
`bash tools/reference-scope.sh "$(git merge-base main CAND)" CAND`, never guessed.

**Fixtures.** Every fixture is synthetic — hand-built workbooks reproducing a measured hazard, not
a byte of any corpus (design contract rule 14(d)). Real-corpus runs read the git-ignored data root
and emit content-free receipts.

### P0 — ADR and skeleton
Write the ADR (read the free number from `origin/main`; 0018 was free at `43140bcb`). Record: the
diagnosis, the two guarantees, the `corpus`/`corpus-intake` split, circe in `corpus`, the
`private[corpus]` ceiling, StAX over a new XML dependency, the naming collisions, the fate of
`tools/corpus/xlsx_rows.py`, and rejected alternatives — (i) one JVM-only module (rule 11);
(ii) extending `acquire`, which is the agent-proposal protocol and already wrongly hosts a corpus
parser; (iii) lifting `NfrdBaseballVerifier` wholesale — rejected: it is participant-keyed and retains no
bytes, so only its four path/receipt/refusal primitives move.
*Mutant (corrected — revision 1's was decorative):* `compileAll`/`testAll`/`testJVM` are built from
`allModules` + `jvmOnlyModules` (`build.sbt:444-486`), **not** `root.aggregate`. Remove `"corpus"`
from `allModules` → `compileAll` must shrink. Also assert `reference-scope.sh` emits `corpusIntake`
for a `corpus-intake/src` change. New modules now also enter CI's `rootJS`/`rootNative` link steps.

### P1 — traceability and cells (small; needed by any reader)
`SourceCoordinate`, `Raw`, `Coded`, `Cell`, `CellRefusal`.
*Tests:* the measured hazards — `'1'` vs `1.0`; `6.1000000000000005` as `MinuteDotSecond`;
`1.000983796296296` as `ExcelSerialDays`; `'2'..'17'` in a column declared binary; a column with no
declaration. Forge probes in `storymodel4s.consumer` for `SourceCoordinate` and `Raw`.
*Mutants:* make `Raw` a case class → the consumer forge probe must fail; let `Cell.integer` fall
back to `toDoubleOption` → the `'1'`/`1.0` test must fail; make an undeclared column guess → the
no-declaration test must fail.

### P2 — the reader, in three commits
- **P2a. Xlsx via StAX** — zip entry, shared strings, sheet rows as `Raw` cells, and the declared
  dimension **not trusted** (the measured `A1:O58`-with-14-rows case). Synthetic workbooks only.
  *Mutants:* trust the declared dimension; treat a header-only sheet as success (the measured
  empty-CSV case); trim on the seconds column rather than the content columns (the measured
  padding-to-3000 case).
- **P2b-i. The verifier — this is T8.** Thin v2 manifest, `ArtifactStore`, `verify`, `Verified`,
  forge probe. *Tests:* wrong schema; wrong version; length mismatch; hash mismatch; artifact on
  disk but not in the manifest **and** vice versa (now possible, because `ArtifactStore`
  enumerates). *Mutants:* compare only length; skip the on-disk-not-in-manifest direction; make
  `Verified` a case class; hand `open` a path.
- **P2b-ii. `CorpusProfile` and `open`.** Column map plus encodings only — code books arrive in P3 —
  with a `Checksum` identity over its canonical rendering on `LexiconTable`'s precedent (refuse an
  empty table, a non-finite value, two keys folding to one). `CorpusReader.open`, `OpenCorpus`.
  *Mutant:* drop the code-book binding from the profile checksum → the identity test must fail.
  Revision 2 had these as one slice; they close different defects, share no test, and each lands
  green alone.
- **P2c. Friends, for real** — profile as data under the data root, never in Git. Exit: the four
  manifest errors from the companion plan §6 are each caught by a synthetic regression test, and a
  real run reproduces the measured counts in a content-free receipt.

### P3 — what Friends forces
Expected, and budgeted: (a) a lawful axis for a timed annotation table, so no corpus has to forge a
film edition — either a `SourceBundle.annotationTable` constructor (D3, D4) or an explicit ADR
decision to keep forging and rename the lie; (b) `SegmentLink` and its laws, with the 56→54→52
composition as the witness; (c) `Applicability` for the `RecallType == 1` condition. Each is a
separate commit, each recorded in the ADR as *forced by evidence*.

### P4 — Memento, the schema-breaker
Same dialect, 5 s grid, different column names, 12 header-layout variants across 133 sheets,
presentation-vs-story order. **Expect the schema to change; record every change as the evidence that
it is real rather than a transcription of Friends.**
*Mutants:* hardcode one header layout; let the reader sniff column names.

### P5 — Sherlock, and the end of the transcriptions
- Replace `sherlock.scala:45-67`'s literals with values read from `timebase-repair.json`, including
  the `end + tail = duration` derivation that currently lives only in prose. Replace
  `SherlockAnnotationsSuite.scala:188-199` with a test that **opens the JSON**. *The whole slice is
  one behaviour: change a value in the JSON and the suite goes red.* Write it to compare parsed
  against used, or the mutation still will not bite (D6, D9).
- Migrate `SherlockAnnotations` out of `acquire` into `corpus-intake`. Safe, and safer than revision 2 said: its only consumer is `SherlockRecallMapping` and its suite.
  `FilmFestivalRecallMapping` merely *mentions* `SherlockAnnotations` at `:27`, in a Scaladoc
  explaining why it does **not** reuse it. `acquire`'s JS/Native builds lose nothing.
- `timebase-repair.json` becomes the first machine-read typed sidecar record, keeping its own schema
  and version. It is **not** folded into the manifest.
- **And it is free to go one step further, so do it.** `ClockRepair.of` demands a
  `SourceDerivationReceipt` (`source.scala:480-495`: algorithm, parameters, input checksums), and
  the JSON already carries every input checksum — the annotation sha and both media shas. So P5
  constructs a **real `ClockRepair`** (scale 2500/1, offset 0, one per part) instead of literals.
  That is the proof that D1/D2's dead vocabulary is reachable, obtained at no extra cost, and it
  should be the slice's headline rather than a footnote.

### P6 — the descriptor, and one gold rule
`corpus-intake` emits `storymodel4s.corpus.descriptor/v1`; the scorers read it; the literal offset
maps, `TR = 1.5`, the `NN\d\d` regex and the participant exclusions come out; `run-arm.sh` takes a
corpus id. **The in-tree precedent is `agreement.py:31-45`**, which already reads `parts.json` and
falls back to a literal only if absent — the only data-driven seam in the tree, and the judge of
record. Generalize it rather than inventing a mechanism. The two senses of "part offset" (D8) become
two declared records, and the ambiguity stops being representable.
The gold rule becomes one implementation, with a differential receipt proving old and new agree on
every row before the second is deleted (D10).
*Mutants:* change a descriptor offset → scorer output changes; delete the descriptor → the scorer
refuses by name rather than defaulting; perturb one alias in the unified gold rule → the
differential test fails.

### P7 — capabilities, derived, and consumed
`Capability` derived from `OpenCorpus`, the manifest's declared set checked against it — which adds
an `IntakeRefusal` case inside `open`, changing P2b-ii's contract.

**It only earns its place if something consumes it**, or it re-creates D5 — a declared record
wired to nothing — which is the defect this whole plan exists to close. The consumer is P6's
descriptor: it carries the derived fields (gold level, coder count, recall grid, clock), and
`run-arm.sh` / `run-filmfestival.py` refuse an arm whose declared needs are unmet. If that consumer
is not built, **defer P7 entirely, including the `Capability` enum.** `CorpusRegistry`,
`Requirement` and `CapabilityShape` stay deferred either way.

### P8 — close
Cold review by a fresh-context agent (SD6). Run every mutant above and record each death **by
name** — a surviving mutant is a finding about the test and is recorded, never quietly replaced.
One evidence document with the gate command and bound test totals with command receipts (SD2: a run
with no test totals did not run), and what the evidence does not establish. Amend the ADR with
everything P3–P4 forced.

## 4. Open decisions, cost, and scaling down

**Three decisions this plan does not make, and names instead:**

1. **The lawful-annotation-axis question (P3a).** Add a `SourceBundle` constructor, or keep forging
   editions. Revision 1 did not notice the problem existed.
2. **Where ordinal remaps live.** `SegmentLink`, or a fourth `MappingFamily`. Decide at Film Festival
   retrofit, with two corpora of evidence.
3. **`tools/corpus/xlsx_rows.py`.** Retire it once the Scala reader is proven, or bind the two with a
   differential test on a synthetic workbook. Keeping it as informal "reference semantics" would
   recreate D10 on purpose — the one place revision 1 was too timid.

**Manifest migration** (not in revision 1). Sherlock's manifest has none of `artifacts`,
`admissionStatus` or `verification` — its keys are `mediaParts`, `boundNonVideoRecords`,
`orderedMediaPair`. A v2 parser requiring the first three cannot read it, and the 1.0 handoff says
of T8 that "the data design is sound and should not change". So: a per-manifest lift, or a thin v2
(artifact identity plus `extensions`, with typed records referenced by id) — which is how
`docs/data/sherlock/` is already organized, with `timebase-repair`, `alias-map` and
`annotation-lineage` as separate files. **Prefer the thin v2.**

| Phase | Value if you stop here |
|---|---|
| P0–P1 | Vocabulary only. Do not stop here. |
**The recommended first slice, narrower than P2 as a whole** (third review): prove *immutable
verified artifacts → verified sidecar parsing → one existing Sherlock consumer*, with the mutation
tests, before extending to workbook formats. That is P2b-i plus the sidecar rule plus a thin slice
of P5, and it establishes the central contract on a corpus already in the repo rather than on 23
sheets of Friends hazards.

| **P2** | **First real payoff:** a reader that cannot read unhashed bytes, and Friends actually opened. |
| P3–P4 | The schema is proven on two dialects rather than asserted on one. |
| P5 | D5, D6, D9 closed — editing a source record breaks a test. |
| P6 | A new corpus stops meaning a forked runner. |

**Smallest useful subset: P0, P1, P2a, P2b, P5.** Revision 1's proposed subset did not compile — its
manifest type referenced `Clock`, `Segmentation` and `Capability` from slices the subset excluded,
and its Sherlock retrofit targeted a record its manifest parser never reads. This one does compile, but only
because of two dependencies worth stating rather than discovering: `Applicability` ships in **P1**
with `Coded`, not in P2b with the profile that declares it, or `Coded.NotApplicable` has no type;
and `CorpusProfile` in **P2b is column-map-plus-encodings only**, gaining code books in P3. The
thin v2 above embeds none of the deferred families. It closes **D6 outright; D5 and D7 only if P5 also (a) lifts
`docs/data/sherlock/source-manifest.json` to thin v2 — it has no `artifacts` key today — and (b)
routes `sherlockRecallMap` through `Verified` on the main path, neither of which revision 2's P5
said; and D9 on one side only**, since `extract_scene_frames.py:48` keeps `481` until P6. D7 likewise has
a second half the subset does not reach: `probe.verifyInput` (`media/probe.scala:273`) still
compares a caller-supplied `Checksum`, and migrating the media probe onto `VerifiedArtifact` is a
named slice or D7 stays open there. D1–D4, D8
and D10 stay open. Not "the intake is fixed".

**Interaction with in-flight work.** T6 (CI) **has landed** (`14f95f33`); revision 1 said it was
pending. T8 ("manifest sha256 verifier") **is P2b** — merge them rather than running twice.

## 5. What this plan does not do

- It does not admit any corpus. `AdmissionStatus` is recorded, never decided.
- It does not commit any Friends artifact; the profile and manifest stay under the git-ignored data
  root.
- It does not change any inference, default, or published number. The retrofit's acceptance
  criterion is byte-identical report output.
- It does not standardize physical encoding, and does not fold the rich typed records into one flat
  manifest.
- It does not claim a code-size reduction. The claim is that a new corpus needs no new *parser*.

## 6. Non-claims

- No slice has been executed; all sizes are estimates.
- The mutants are designed, not run.
- "Two dialects are enough to validate the schema" is a judgement. P4 is where it is tested and it
  may fail.
- The diagnosis in §0 is measured; the inference that the ten instances share one cause is an
  argument, not a measurement.
- Revision 1's vet found four blockers; revision 2's found three more plus two false statements in
  the diagnosis; a third review then found three high-severity contract holes that **both** earlier
  passes had accepted — including one this author had explicitly recorded as harmless. The same is
  likely true of revision 3 in places no pass reached — in particular P3 and P4 are
  specified only as far as unopened workbooks allow.
