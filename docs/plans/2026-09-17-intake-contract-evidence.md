# The annotation intake contract: evidence record

*2026-09-17. Branch `solo/intake-contract`, 27 commits from `main` at `0df1d9e7`.
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
| Gate exit | **0** |
| Modules reporting totals | **24 of 24** |
| Tests | **2,421** |
| Failed | **0** |
| Skipped | 5 |
| `scalafmtCheckAll scalafmtSbtCheck` | exit **0**, 0 files unformatted |

A run with no totals did not run (SD2). All 24 modules reported.

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
| P2b-s | skip the dangling-record check | refused-at-construction fails |
| P2b-s | trust the reference's schema | 2 tests fail |
| P2b-ii | profile identity ignores the encoding | identity test fails |
| P2b-ii | `open` reads from disk | **5 tests fail, incl. the falsifier** |
| P2b-ii | header-missing column skipped | fail-fast test fails |
| P3link | a bijection may drop sources | 2 tests fail |
| P3link | totality unchecked | `NotTotal` test fails |
| P3link | composition ignores the intermediate | mismatch test fails |
| P3seg | onsets need not increase | invariant test fails |
| P5clk | JSON `uncoveredTailTicks` 500 → 501 | derivation test fails |
| P5clk | JSON `annotationRows` `1-482` → `1-481` | 2 tests fail |
| P6desc | change a declared offset | consumer reads the change |
| P6gold | descriptor declares TR 2.0 | reaches the rule |

**34 mutants, 34 killed.** Two mutants are recorded as *surviving and benign* by the P2b reviewer
(`filter(present.contains)` removal, which only double-reports; and `verify`'s schema check, dead
once `of` owns it) — both are noted rather than replaced.

## How a mutation run lies, twice, measured here

1. **Zinc does not recompile a forge probe.** `typeCheckErrors` expands to a literal list, so no
   dependency is recorded and a mutant reads as *survived* when it is killed. A `zincAnchor` fixes
   SHAPE mutations and **cannot** fix accessibility ones — it would have to name the member whose
   inaccessibility the probe asserts. Measured table in `docs/design/unforgeable-types.md`.
   Consequence: **mutation runs clean the test scope.**
2. **A mutation that never applied.** One read as survived because scalafmt had aligned
   `case None    =>` and the mutator looked for `case None =>`. Every mutation since asserts a
   changed file first.

## Real-data runs (content-free receipts)

Both are runnable mains, not tests: the bytes are in the git-ignored data root and the source sets'
admission state is `proposed`.

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
| P2b-ii profile/open | dispatched; findings not delivered |
| P3 SegmentLink | dispatched; findings not delivered |
| P5 ClockRepair | dispatched; findings not delivered |

The three closed reviews found defects in claims the commits had already made — including a third
aliasing route into "verified" bytes that a commit described as having no write path, and a
forgeable decode status that an earlier review had explicitly waved through. **Three of six reviews
have not reported, and nothing here rests on them having done so.**

## What this evidence does NOT establish

- **No corpus is admitted.** `AdmissionStatus` is recorded, never decided. Friends and Memento both
  remain `proposed`, no court is open, and no Friends or Memento artifact is committed.
- **No inference, default or published number changed.** No adapter was retrofitted onto the
  canonical tables, so no arm was re-run and no score moved.
- **The Python scorers are not rewired.** P6desc landed the descriptor contract and its refusals on
  both sides; the ten files that carry corpus constants still carry them. The gold rule exists in
  two implementations that now provably agree, which is what makes retiring one safe — not the same
  thing as having retired it.
- **The guarantees are closed at the TYPE level, not at runtime.** `private[corpus]` is public
  bytecode; plain reflection forges a `Known`. Stated as a non-claim in ADR 0018.
- **Nothing here was verified by a second party.** Three of six scheduled reviews are outstanding,
  and the cold review of the whole subsystem is dispatched, not returned.
