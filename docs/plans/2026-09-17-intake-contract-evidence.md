# The annotation intake contract: evidence record

*2026-09-17. Branch `solo/intake-contract`, 39 commits from `main` at `0df1d9e7`.
Implements ADR 0018 and the epic `bd-01M2R747J0SCHRFH9BF70TWT4S`.*

Everything below is **LocallyObserved** (AGENTS.md §4): one author, one machine, no second party.
What a mechanism established is marked; what is judgement is marked as such.

## The gate

Command from `bash tools/reference-scope.sh "$(git merge-base main HEAD)" HEAD` — not retyped:

```
sbt -batch "acquireJVM/test; alignJVM/test; amrInteropJVM/test; codecJVM/test; coreJVM/test;
corpusJVM/test; corpusIntake/test; documentJVM/test; embedBench/test; embedCoreJVM/test;
embedGrakern/test; embedOnnx/test; featuresJVM/test; fixturesJVM/test; interviewJVM/test;
lawsJVM/test; media/test; pipeline/test; propositionJVM/test; providerAgent/test;
providerParser/test; recallJVM/test; storyJVM/test; viewJVM/test"
```

| | |
|---|---|
| | at P8 sign-off (2026-09-17, after cold review) | as first recorded |
|---|---|---|
| Gate exit | **0** | 0 |
| Modules reporting totals | **24 of 24** | 24 of 24 |
| Tests | **2,466** | 2,443 |
| Failed | **0** | 0 |
| Errors | **0** | 0 |
| `scalafmtCheckAll` (run LAST, separately) | exit **0** | exit 0 |

A run with no totals did not run (SD2). All 24 modules reported. The merge-base used was
`0df1d9e746e0f72f25ec1d2102e797ce1e5f0f72`; the command above is pasted from `reference-scope.sh`,
not retyped.

The +23 tests are the cold-review fixes: the profile-identity forgery and its injectivity family,
the `SegmentLink` cross-work and cross-axis pins, the non-monotone-coarsening pin, corpus-wide
refusal reporting, the refusal-count pin, the `CorpusProfile` consumer probes, and the
`participantPattern` mutations on the Python side.

**A caveat recorded rather than smoothed over.** A first full-tree `sbt test` (all platforms, not
the scoped JVM gate) reported `corpusNative / Compile / compileIncremental` failed. It did not
reproduce: an immediate re-run of the same tree exited 0 with zero `[error]` lines, as did the
scoped gate above. It followed the module `clean`s used during mutation testing, so a parallel
compile race is the likely cause, but that is a hypothesis and not a diagnosis. If it recurs it is
real.

## Mutants killed, by name

Each was applied to the source, **asserted to have changed the file**, and run under a cleaned test
scope. Both of those conditions were learned the hard way in this session; see "How a mutation run
lies" below.

| Slice | Mutation | Result |
|---|---|---|
| P0b | remove `"corpus"` from `allModules` | `compileAll` shrinks |
| P1a | `SourceCoordinate` → `case class` | consumer probe fails (Mirror reachable) |
| P1a | constructors widened to public | 2 probes fail |
| P1b | `Known` → `case class` | Mirror/copy probe fails |
| P1b | inapplicable-value returns `Absent` | the iff test fails |
| P1b | blank-on-applicable → `Unmapped` | 2 tests fail |
| P1c | undeclared encoding guessed | unconditional-refusal test fails |
| P1c | min.sec read as a number | 2 tests fail |
| P1c | `IntegerText` accepts a whole decimal | that test fails |
| P1c | no bound on the plain-decimal rendering | pathological-exponent test fails |
| NF1 | drop the dot-segment check | 2 traversal tests fail |
| NF1 | path `toString` renders its value | non-disclosure test fails |
| P2a | header-only sheet reads as success | `NoDataRows` test fails |
| P2a | `trimTrailing` trims on the index column | padding test fails |
| P2a | style-only rows kept | that test fails |
| P2a | DTD support re-enabled | external-entity test fails |
| P2b-i | hash the store's array without copying | aliasing route 1 fails |
| P2b-i | `toArray` returns the backing store | aliasing route 2 fails |
| P2b-i | skip present-but-undeclared | 2 tests fail |
| P2b-i | compare byte length only | same-length test fails |
| P2b-i | drop the present-filter before hashing | one-failure-per-artifact test fails |
| P2b-s | skip the dangling-record check | refused-at-construction fails |
| P2b-s | trust the reference's schema | 2 tests fail |
| P2b-ii | profile identity ignores the encoding | identity test fails |
| P2b-ii | profile identity omits the header row | field-completeness test fails |
| P2b-ii | drop length prefixing from the rendering | both collision tests fail |
| P2b-ii | `open` reads from disk | **5 tests fail, incl. the falsifier** |
| P2b-ii | header-missing column skipped | fail-fast test fails |
| P2b-ii | report only the first cell refusal | accumulation test fails |
| P2b-ii | an unreadable cell reads as blank | present-and-empty test fails |
| P2b-ii | duplicate headers resolved silently | duplicate-header test fails |
| P3link | a bijection may drop sources | 2 tests fail |
| P3link | totality unchecked | `NotTotal` test fails |
| P3link | composition ignores the intermediate | mismatch test fails |
| P3link | Coarsening stops checking ontoness | unreached-target test fails |
| P3link | compose skips the intermediate-extent check | incomplete-intermediate test fails |
| P3link | Bijection stops checking injectivity | invertibility property fails |
| P3seg | onsets need not increase | invariant test fails |
| P3seg | onsets must STRICTLY increase | invariant test fails (would refuse Sherlock) |
| P5clk | JSON `uncoveredTailTicks` 500 → 501 | derivation test fails |
| P5clk | JSON `annotationRows` `1-482` → `1-481` | 2 tests fail |
| P5clk | JSON `partId` (by path) | part-mapping test fails |
| P5clk | JSON `axisId` (by path) | axis-binding test fails |
| P5clk | JSON `annotationEndSeconds` (by path) | extent test fails |
| P5clk | JSON `playbackStartTicks` (by path) | origin test fails |
| P5clk | one shared source axis for both runs | distinct-axes test fails |
| P6desc | change a declared offset | consumer reads the change |
| P6gold | descriptor declares TR 2.0 | reaches the rule |

**50 mutants, 50 killed.**

Three behaviours a reviewer confirmed CORRECT by probe had no test pinning them -- bytes fetched
once per artifact, an unlisted id never fetched at all, and one array served under two ids yielding
independent copies. A confirmed-but-unpinned behaviour is one that drifts on the next edit, so all
three now have tests.

One mutant a reviewer found SURVIVING has since been closed: removing `filter(present.contains)`
before hashing caused a declared-but-absent artifact to be reported twice, once as
`MissingFromStore` and again as `Unreadable`. The reviewer judged it benign and it was. Benign is
not the same as intended, and an unpinned behaviour drifts -- a verification report that
double-counts is one somebody eventually reads as two problems. A test now requires exactly one
failure per missing artifact, and the mutant dies.

The other surviving mutant, `verify`'s unreachable schema check, was made moot rather than tested:
`SourceManifest.of` now owns that check, where it is reachable. Two mutants are recorded as *surviving and benign* by the P2b reviewer
(`filter(present.contains)` removal, which only double-reports; and `verify`'s schema check, dead
once `of` owns it) — both are noted rather than replaced.

## Two identity collisions, from a probe suite an agent left on disk

`rev-profile` never reported, but had written a probe suite into the scratchpad. Two of its probes
targeted something the field-completeness test structurally cannot catch — that test varies each
field in turn, which finds OMISSIONS but not COLLISIONS. Both collided:

- `Custom("a:b", "c")` and `Custom("a", "b:c")` both render `a:b:c`, so two different encodings
  produced one identity.
- `ContentAddress.digest` joins parts with NUL, so a column literally named
  `a\u0000text\u0000false\u0000b` digested identically to two columns `a` and `b`.

In a content-addressed scheme, two profiles that READ DIFFERENTLY sharing an identity means a
receipt cannot say which reading produced it — the identity's entire purpose.

Fixed by length-prefixing every part and framing the encoding field by field rather than through
its rendered string. Mutant: remove the prefixing → both collision tests fail.

## A finding recovered from an agent that never reported

One of the reviewers that went idle had in fact done work, left on disk in the session scratchpad:
a `jshell` probe of `BigDecimal.stripTrailingZeros.toPlainString` across pathological inputs,
including `1E+100000000`. Nothing was ever reported, and the artifact was found only by listing the
scratchpad while chasing the missing reports.

It was right. `Cell.normalize` under `DecimalText` expanded the exponent: `1E+10000000` renders as a
**ten-million-character string in about 18 ms**, and a larger exponent exhausts the heap. A workbook
is untrusted input — the same reason the XML parser refuses DTDs — so one crafted or corrupt cell
could take the process down. My own `NormalizeSuite` had tested `1E+2` and stopped there.

Now bounded at 1,000 characters, computed from `precision` and `scale` **without rendering the
string**. Mutant: remove the bound → the pathological-exponent test fails.

## A claim of mine that was overstated, and the correction

Commit `09292e25` said: *"the whole slice is ONE behaviour: change a value in
`timebase-repair.json` and a test goes red."* It demonstrated two such values. **That was
overstated.** Hunting for a counterexample found four load-bearing values in the crosswalk section
that could be changed in silence:

| value | why it is load-bearing |
|---|---|
| `partId` | says which media part a run maps to; a swap sends run-1 annotations to part B |
| `axisId` | becomes the TARGET AXIS of a real `ClockRepair`; a wrong value binds a nonexistent axis |
| `annotationEndSeconds` | the run's annotation extent |
| `playbackStartTicks` | the run's playback origin, which is what makes the repair an identity |

Four tests were added and each mutation now turns the suite red. Verified both before (green) and
after (red) by mutating the crosswalk **by JSON path**.

**And a third way a mutation run lies, found doing this.** A first pass reported `runId`, `partId`,
`axisId` and `annotationEndSeconds` as uncovered. `runId` was a FALSE POSITIVE: the file has four
`"runId"` keys in three sections, and a first-textual-occurrence string replace hit
`coordinateSystems[]`, not `annotationToPlaybackCrosswalk.runs[]` — mutating something no test
reads and calling the silence a gap. **A mutation targeted by text rather than by structure can
report a gap that does not exist, as easily as it can miss one that does.**

## A column read from the wrong place, non-deterministically

`openSheet` built its header map by collecting into a `Map` keyed by the column NAME. Two header
cells carrying the same bound name therefore collapsed silently, and the survivor was whichever the
`Map` iteration happened to yield — not even deterministically. A whole column could be read from
the wrong place, and nothing would say so.

Friends sheets carry a pasted legend column (`Recall types`, present in 18 of 23 sheets), so
duplicated header text is not hypothetical.

Now a typed refusal, `DuplicateHeader`. Mutant: raise the duplicate threshold so duplicates resolve
silently → the test fails.

## An implementation that contradicted its own ADR

ADR 0018 §8 says: *"accumulate cell-level refusals per sheet under a declared cap; fail fast on
structural refusals. A 27,777-row workbook must not surrender one bad cell per run."*

`CorpusReader.openRow` did the opposite. One unreadable cell aborted the entire sheet, so on
Friends — 23 sheets, 27,777 content rows — a run would report exactly one problem and stop.

Corrected: cell refusals accumulate per sheet under a cap of 100, a row with a bad cell is still
returned built from the cells that DID read, and structural refusals (a column the header lacks, an
unverified artifact) still fail fast. An unreadable cell is `Undeclared` in the row context, not
blank — reading it as present-and-empty would be a claim the data does not support.

Mutants: report only the first refusal → the accumulation test fails; treat an unreadable cell as
blank → the present-and-empty test fails.

Found by reading `openRow` against §8 after a reviewer briefed on that exact function went quiet.

## A type that would have refused an already-admitted corpus

`Segmentation` required STRICTLY increasing onsets. Measured against the admitted 1,000-row
Sherlock annotation, that rule refuses it: row 3 is zero-duration (`rawStartSeconds ==
rawEndSeconds == 20`) and row 4 also starts at 20, so two consecutive segments share an onset.
`SherlockAnnotations` documents this as lawful at :76-77 — *"A zero-duration row is lawful here and
becomes a media instant"* — so the new type contradicted a documented property of a corpus this
repository has already admitted.

Corrected to **non-decreasing**. Order comes from the ordinal; the onset is an observation. A
DECREASE is still refused, and the same table has exactly one — at row 483, where run 2 restarts at
0. That refusal is informative rather than obstructive: it is the signal that the Sherlock
annotation is **two** segmentations, one per media part, not one. The same boundary Film Festival's
part-local numbering showed from the other side.

Found by running an attack I had briefed a reviewer to run, after the reviewer went quiet. Mutant:
restore strict increase → the segmentation-invariant test fails.

## How a mutation run lies, three ways, measured here

1. **Zinc does not recompile a forge probe.** `typeCheckErrors` expands to a literal list, so no
   dependency is recorded and a mutant reads as *survived* when it is killed. A `zincAnchor` fixes
   SHAPE mutations and **cannot** fix accessibility ones — it would have to name the member whose
   inaccessibility the probe asserts. Measured table in `docs/design/unforgeable-types.md`.
   Consequence: **mutation runs clean the test scope.**
2. **A mutation that never applied.** One read as survived because scalafmt had aligned
   `case None    =>` and the mutator looked for `case None =>`. Every mutation since asserts a
   changed file first.
3. **A mutation that applied to the wrong thing.** See the section above: a first-occurrence string
   replace on a multi-section JSON file mutated a key no test reads, and the resulting green read
   as an uncovered gap. Mutate structured data by PATH, not by text.

## Real-data runs (content-free receipts)

Both are runnable mains, not tests: the bytes are in the git-ignored data root and the source sets'
admission state is `proposed`.

Both receipts now report **cell refusals**, and both read `none`. They did not before: `OpenSheet`
accumulated refusals and flagged truncation, but neither runner looked, so a run could have read a
corpus with hundreds of unreadable cells and printed a clean-looking receipt. A receipt that does
not mention failures overstates the read, which is what makes the accumulation worth having.

**Friends** — 6/6 artifacts verified, 2,638,557 bytes hashed; 53 rows; 52 events, 30 scenes, 2
episodes, 5 storylines, 10 places; `Time` 0..2515 s; `TimeOrig` max 1318 s. Every number
independently reproduces earlier ad-hoc measurement.

**Friends 56→52 crosswalk, derived** — 56 sources, 52 mapped, 52 distinct targets, injective;
removed `19, 37, 39, 48`; **49 exact `Time` matches of 56**, so an equality join would have produced
a map of 49 and silently dropped three events.

**Memento** — 10/10 artifacts verified; 129 rows, 129 subscenes, 44 broad scenes, 44 story-order
values, 4 narrative parts; `Time` 0..6627 s, exactly the recorded `lastSubsceneTimecode` 01:50:27.

## What the second corpus broke

Three schema changes, which is what P4 existed to find:

1. **Excel day origin.** Friends starts at 1.0, Memento at 0. Either rule on the other corpus gives
   nonsense (−86,231 s and 86,485 s). The origin is now a parameter.
2. **Artifact key.** Friends names each file `path`; Memento names it `id`.
3. **Nested paths.** Memento declares `ratings/r1..r7.xlsx`; the store listed one level and
   correctly refused all seven as "declared but absent" — which is how it was found.

## Scheduled adversarial reviews

Six risky slices were named in advance, with the reason recorded on each bead before implementation.

| Slice | State |
|---|---|
| P1b Coded | closed; 3 majors remediated at `854b2d24` |
| P2b-i Verified | closed; 2 majors remediated at `3206c205` |
| P2b-s sidecars | closed; remediated with the above |
| P2b-ii profile/open | **dispatched; never executed** — brief worked by the author |
| P3 SegmentLink | **dispatched; never executed** — brief worked by the author |
| P5 ClockRepair | **dispatched; never executed** — brief worked by the author |

**Eleven review agents were dispatched. Three delivered, all within the first third of the session.
Every agent spawned afterwards failed to execute at all** — producing neither a report nor any
artifact on disk.

**The cause, once it finally surfaced: the account ran out of Fable 5.1 usage credits.** Every
failed agent reported `You're out of usage credits. Run /usage-credits to keep using Fable 5.1 or
/model to switch models.` The three reviews that landed were spent before the budget was.

Five remedies were tried before that was known, and all five were treating the wrong thing:
re-tasking two agents that had already delivered; a three-line prompt, to test prompt size;
file-based delivery, to route around the message channel; stopping seven idle agents to free
concurrency, on a *mistaken* diagnosis that a cap was starving later spawns; and repeated requests
inviting "did not complete" as an answer. None addressed a credit limit, and the author's stated
diagnosis at the time — a concurrency cap — was wrong.

The gap is recorded rather than worked around, because a verification record that quietly
substituted self-review for adversarial review would be the exact defect this subsystem was built
to close. **It is resolvable by the owner and not by the author:** `/usage-credits`, or `/model` to
review under a different model.

**Four of the six silent agents had nonetheless done real work**, left in the session scratchpad and
found only by listing it: a `BigDecimal` probe that found a denial-of-service in `Cell.normalize`, a
probe suite that found two identity collisions, a mutation harness whose log named two surviving
mutants, and a stashed attack suite that established three behaviours nothing tested. All four
findings are in the tables above. Each found something the author had not found and, by
construction, would not have found — the DoS because his suite tested `1E+2` and stopped, the
collisions because his test was built to detect omissions rather than collisions, the survivors
because he had only run his own designed mutants.

For the three slices whose reviewers never ran, the author worked the briefs directly: **7 defects
found and fixed, 7 decisions pinned with tests and mutants, no question left open.** Those findings
are real and are listed above. **They are not a review**, and this table does not present them as
one.

The distinction is not a formality. The three delivered reviews each caught something the author
had *written a justification for* — a forgeable decode status a prior reviewer had explicitly waved
through, and an `IArray` a commit message described as having no write path. Everything found by
self-attack was something simply *not yet examined*. Self-review reliably reaches the second
category and reliably misses the first, which is why the reviews were scheduled before
implementation rather than run afterwards by the author.

The three closed reviews found defects in claims the commits had already made — including a third
aliasing route into "verified" bytes that a commit described as having no write path, and a
forgeable decode status that an earlier review had explicitly waved through. **Three of six reviews
have not reported, and nothing here rests on them having done so.**

## Second cold review of the three risky slices, 2026-09-17 (post-fix)

Requested by the owner after the first acceptance. Fable 5.1 was still out of credits (`fable-slices-2`
idle in 54 s with the same verbatim failure), so this was again the default model, batched over all
three slices. It reviewed the FIXES, which no one had reviewed — they were the newest code on the
branch.

**Slice A (`CorpusProfile` / `CorpusReader.open`) — held.** No fifth collision shape. The reviewer
built 400,000 random adversarial profiles over the collision vocabulary and found 0 collisions in the
shipped nested form. It also **verified rather than refuted** the author's judgement that the
surviving flat-with-tags mutant is benign: the flat form is decodable left-to-right because group
arity is recoverable from the tag literal, and the reviewer independently confirmed the one
precondition that makes it so — that no arity-1 encoding's `render` collides with `custom` or
`excel-serial-days`. That judgement had been made by its own author and checked by nobody; it now
has an independent check.

**Slice B (`SegmentLink`) — TWO DEFECTS, both fixed.**

1. `compose` matched the intermediate BY NAME. A `SegmentLink` stored only `SegmentationId`s, and a
   `SegmentationId` is a string. `compose` knew ids were untrustworthy — it checks intermediate
   *coverage* for that reason — but coverage only catches an intermediate that is too small.
   Demonstrated: onsets `0/10/20` composed with onsets `5000/6000/7000` under the shared id `b`
   produced a 3-entry mapping asserting a relationship nothing had established.
2. Found while reproducing (1): `Segmentation.equals` compared `id` and `segments` only, so a
   **Friends segmentation equalled a Sherlock one** with the same ordinals. `work`, `axis`, `level`
   and `authority` were all invisible to it — and that equality contradicted the cross-work refusal
   `SegmentLink.of` had just gained.

   Fix for both: `Segmentation` now carries a content `identity: Checksum` (the `CorpusProfile` /
   ADR 0011 precedent), equality is identity, and `SegmentLink` carries `fromIdentity`/`toIdentity`
   so `compose` can compare content. The rendering is framed AND nested, because
   `GranularityLevel.Custom.render` and `SegmentationAuthority.render` both join with a colon at
   variable arity — the exact shape that collided in `CorpusProfile`, not relearned here.

   The identity check is ordered AFTER the coverage check deliberately: both refuse, but
   `IntermediateIncomplete` names the uncovered ordinals and `IntermediateDiffers` does not. Putting
   it first turned an existing test's precise answer into a vague one, which is a regression even
   though both answers are refusals. The code was reordered; the test was not weakened.

**Slice C (`TimebaseRepair`) — the file is only half load-bearing.**

Measured by mutating `docs/data/sherlock/timebase-repair.json` BY JSON PATH (first-textual-occurrence
replacement produced a false result earlier in this session) and asserting each mutation applied:

- **18 of 18** fields that the code *reads* are load-bearing — every mutation died. That includes all
  four the P5 bead named as once-untested: `partId`, `axisId`, `annotationEndSeconds`,
  `playbackStartTicks`, in **both** runs.
- **7 fields were SILENT** — changeable with the whole `corpus-intake` suite green.

Three of the seven were load-bearing values the code *restated* rather than read, which is the
epic's founding defect still alive in one corner: `MediaManifest.nn2017` hardcoded
`durationTicks = 3565500L` and `3887000L`, which are exactly
`coordinateSystems[0].parts[*].endSeconds × 2500`. The file declares each part's extent **twice**
(once in `coordinateSystems`, once as `playbackEndTicks + uncoveredTailTicks`) and Scala a third
time; two agreed by luck and nothing reconciled them.

The test named for the file — `"the pinned nn2017 manifest carries the crosswalk constants of
timebase-repair.json v2"` — asserted Scala literals against Scala literals, exactly as the P5 bead
warned. It now opens the file, checks `nn2017` against the parsed record, and binds the file's two
declarations of each extent to each other. `ticksPerSecond` is **derived** from the file
(`playbackEndTicks / annotationEndSeconds`) rather than taken from the manifest, so the manifest's
own 2500 is checked against the file instead of checking itself. All three mutations now die.

Four fields remain silent and are left so, named rather than fixed:
`coordinateSystems[1].runs[0].ordinaryEndSeconds` and `coordinateSystems[3].rowCount` (documentation
of the repaired analysis axis, which no code consumes), `sourceDerivation.sha256` (a provenance claim
about the upstream notebook that nothing verifies), and `coordinateSystems[2].secondsPerTr` = 1.5,
which IS load-bearing and is transcribed into both `SherlockSceneCoding.scala` and `gold_scene.py`.
Binding that one is the cross-language agreement this document already records as unpinned; it is
descriptor work (P6), not a fix to make in passing.

Gate after these fixes: exit 0, 24/24 modules, **2,472 tests**, 0 failed, 0 errors, scalafmt clean.

## What this evidence does NOT establish

- **No corpus is admitted.** `AdmissionStatus` is recorded, never decided. Friends and Memento both
  remain `proposed`, no court is open, and no Friends or Memento artifact is committed.
  *Evidenced:* both intake runners print the admission state they read, and both print `proposed`.
  **Corrected 2026-09-17:** this was FALSE when written. Only `FriendsIntake` printed an admission
  line; `MementoIntake` printed none, so a reader comparing the two receipts would have taken
  Memento's silence for a clean bill. Found by cold review. The claim was not weakened to match the
  code — the line was added to `MementoIntake`, so the sentence above is now true of both.
  Reading the git-ignored data root was always permitted — `story-text-admission-checklist.md` §3
  bars **committing** recall prose, not reading staged bytes — so verifying and reading a corpus is
  not admitting it.
- **No inference, default or published number changed.** No adapter was retrofitted onto the
  canonical tables, so no arm was re-run and no score moved.
  *Evidenced:* `git diff main..HEAD --numstat tools/recall-study/` is **397 insertions, 0
  deletions**. Every Python change is additive — a new module, new `configure` functions, new
  tests — and no existing line was modified or removed. `configure()` has no caller outside the
  tests, so the arm runners still read the same literals they always did. A scorer's computation
  cannot have moved.
- **The Python scorers are not rewired.** P6desc landed the descriptor contract and its refusals on
  both sides; the **seven** files that carry corpus constants still carry them — `agreement.py`,
  `extract_scene_frames.py`, `gold_scene.py`, `matched.py`, `score.py`, `score_ladder.py`,
  `within_scene.py`. The gold rule exists in two implementations, which is what makes retiring one
  safe once they are shown to agree — not the same thing as having retired it.
  **Corrected 2026-09-17**, on three counts, all found by cold review:
  1. "ten files" was a guess that was never counted. It is seven, now enumerated and countable.
  2. "provably agree" overstated `test_gold_rule_single.py`. That test compares the descriptor-driven
     rule against the **Python literals it replaces**; it never reads `SherlockSceneCoding.scala`.
     The Scala and Python implementations are *asserted* to agree by the ADR and the descriptor's
     own provenance, not proven to by any test. Cross-language agreement remains unpinned.
  3. `participantPattern` was declared by the descriptor, assigned by `configure()`, and **read by
     nothing** — `int(name[2:4])` did the work and hardcoded Sherlock's two-letter `NN01_` shape. A
     corpus with different ids would have configured without complaint and then been parsed by the
     wrong rule: a declared knob that looks live and is inert, which is the exact defect this
     contract exists to remove. Now load-bearing at both call sites, with a mutation test that a
     changed pattern reaches the parse and that the old pattern stops matching.
- **The guarantees are closed at the TYPE level, not at runtime.** `private[corpus]` is public
  bytecode; plain reflection forges a `Known`. Stated as a non-claim in ADR 0018.
- **Three of six scheduled reviews were never delivered by the reviewer they were scheduled for.**
  The Fable 5.1 reviews of P2b-ii, P3 `SegmentLink` and P5 `ClockRepair` did not run. The failure is
  verbatim, and it is account-level, which is why no retry cleared it:
  `"idleReason":"failed","failureReason":"You're out of usage credits. Run /usage-credits to keep
  using Fable 5.1 or /model to switch models."`
  Fable *did* review this branch before that: `fable-vet` (plan vet, 4 blockers — it is what flagged
  P2b-ii as the riskiest slice), `fable-p1b` (`Coded`), `fable-p2b` (P2b-i/P2b-s). Three slices were
  owed, not six.
  **Owner decision, 2026-09-17:** the delivered cold review is accepted as sufficient for those three
  slices and the Fable review is no longer owed (bead `bd-01M2RYPH4MA547YT4GS2A91RK3`, closed with
  the full rationale). Recorded as the owner's call, asked and answered explicitly — not as the
  author judging that his own code had been reviewed enough. What that acceptance does **not**
  establish: no Fable review of these three slices exists, and the judgement that the surviving
  digest-nesting mutant is benign was made by the author and checked by nobody else.
  **Corrected 2026-09-17.** This bullet previously read "Nothing here was verified by a second
  party," which was false when written — reviews had already been delivered and are cited elsewhere
  in this document. The cold review of the whole subsystem has since returned, and it found one
  blocker and three majors that the author had passed himself on:
  - `CorpusProfile.identity` collided: one sheet reading three columns and **two sheets reading
    different artifacts** produced the same digest, because framing made every *part* self-delimiting
    while leaving the part *vector* flat and count-free against a variable-arity encoding. Three
    prior forgery probes had each been reasoned about and passed. Fixed by nesting the digest.
  - `SegmentLink.of` never compared `from.work` to `to.work`, so a Friends segmentation could be
    linked onto a Sherlock one and pass every ordinal check. Fixed; cross-*axis* linking is
    deliberately still legal and now pinned as such.
  - The Friends receipt reported one sheet's cell refusals while the profile bound two, so an
    unreadable MoreEMs sheet printed `cell refusals : none`. Fixed corpus-wide.
  - `refusalsTruncated` was a Boolean, so 101 bad cells and 27,777 read identically. Now a count.

  The methodological point is worth recording against the next branch: the review that found the
  blocker was run by the **default model**, dispatched as a throwaway diagnostic after the Fable
  agents failed. What made it effective was that it was cold, not which model ran it. The author had
  attacked this digest three times and passed himself each time.
