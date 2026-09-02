# ADR 0009 — The `pipeline` story-build orchestrator

**Status:** Accepted 2026-09-02, single-developer mode

**Date:** 2026-09-02

**Decider:** the owner (single-developer mode, AGENTS.md SD5)

**Plan:** `docs/plans/2026-09-01-story-pipeline-solo-plan.md`, phase 1.2 (the plan's `build`
driver)

## Context

ADR 0005 §1 anticipates "a later JVM `build` orchestrator [that] may run providers and call the
compiler, but does not own the scientific transformation". Phase 1.1 landed `provider-agent`
(text to admitted charts, ADR 0008) and phase 1.3 landed `ChartProposalProvider` in `document`
(charts to checked compiler input). Nothing joined them: no path in the repository turned a text
file into a `StoryModel` on disk, and `ClaudeParseDriver.run` returned only a `DriverSummary`, so
a caller could not obtain the charts it had admitted.

The bundle spec (`docs/design/story-output-bundle-spec.md` §4.1–4.2) assigns `storymodel.json`
to the canonical `StoryModelCodec` form, allows it to carry a coherent receipted draft under a
partial outcome, and keeps validation status beside it rather than inside it.

## Decisions

1. **A JVM-only sbt project `pipeline`, package `storymodel4s.pipeline`.** It depends on
   `providerAgent`, `providerParser`, and the JVM projections of `core`, `proposition`,
   `acquire`, `document`, `story`, `codec`, with `fixtures` at test scope; circe core and parser;
   `Test / fork := true`. It is in the root aggregate and in `jvmOnlyModules`. It owns I/O,
   receipt composition, file layout, and exit status; it owns no semantics. Every stage it calls
   is pure or already courted in its own module.
2. **`provider-agent` gains a public `parse`.** `ClaudeParseDriver.parse(mode, textPath,
   recordingsDir, env, nowEpochMillis, source)` returns `Either[DriverError, ParseOutcome]`, a
   private-constructor class holding the story, atlas, batch, result, served-from ledger,
   recording keys, `ExtendedBuildReceipt`, the parser stage `(StageId, Checksum)` found in that
   receipt, and the `DriverSummary`. `ParseOutcome.charts` is the covered attempts in atlas
   sentence order, keyed by the atlas sentence each input was cut from. `run` is now `parse`
   followed by the existing writes, with one deliberate change: the build receipt is established
   before the first write, so a refused receipt (`ReceiptInvalid`) leaves the output directory
   untouched where it previously left per-sentence files behind. No provider-agent test pinned
   the old order; all 52 stay green unchanged, and a new one pins the new order: a negative
   timestamp (the one receipt refusal a caller can reach) yields `ReceiptInvalid` with no
   output directory, in both `run` and `StoryPipeline.run`.
3. **Flow and files.** `parse` → `ChartProposalProvider.propose` (for the coverage ledger) →
   `ChartProposalProvider.input(story, atlas, charts, Some(parserStage), now)` →
   `NarrativeCompiler.compile` → three files under `outDir`:
   - `storymodel.json`: exactly `StoryModelCodec.encode(compilation.draft)`, no trailing
     newline, so the bytes are the canonical text the content checksum digests. It is the
     receipted draft the bundle spec allows under a partial outcome; it carries the canonical
     source text because the model does.
   - `compilation-report.json` (`storymodel4s.pipeline.compilation-report/v1`): source
     identity and sentence count; the parser block (stage, checksum, proposed/failed/abstained/
     transport-failure counts, the six served-from counts, live-call count); coverage counts, the
     summary row, and one row per sentence (`proposed` with root key, `abstained` with anchor key
     and reason render, `empty-chart`, `no-chart`); every derivation gap as (stage, family,
     target render, reason render, upstream and evidence counts); validation (`validated`,
     error and warning counts, every violation as law, severity, path, reason); compilation
     fingerprint, candidate-set checksum, attempt and emitted-claim counts; model content
     checksum and node counts. No source prose: rows name unit ids, chart node keys, law names,
     and renders.
   - `receipts.json` (`storymodel4s.pipeline.receipts/v1`): the parser's `ExtendedBuildReceipt`
     through the canonical `codec` encoder; one ledger row per sentence (request id, sentence id,
     recording key, served-from, status, failure render, proposal digest, request checksum,
     receipt digest); every `ProviderCall` the compilation's provenance retained, which is the
     parser's, the AMR adapter's conversion receipt on each chart, and the proposal provider's;
     the provenance software version and config hash; the `BuildReceipt` with both stages and
     its content checksum.

   Everything is computed before the first write. A refusal at any stage writes nothing.
   The trio is a **pre-bundle**: `result.json` and `manifest.json` through `StoryOutputResult`
   (`view/output.scala`) are phase 2.2 and out of scope here.

   The report's `model` block carries three identities and says which are stable: the
   compilation `fingerprint` and the `BuildReceipt` content checksum (timestamp excluded) are
   equal across runs over the same inputs at any clock; `encodingDigest` is
   `StoryModelCodec.contentChecksum(draft)`, which digests the canonical encoding including
   `receipt.createdAtEpochMillis`, and is labelled timestamp-bearing in the report and on
   stdout so nobody reads it as a content identity.

   `BuildSummary.derive` refuses (typed `ReceiptMismatch`) a compilation whose receipt does not
   contain the parser stage `parse` established, or whose source checksum is not the parsed
   story's, so the summary can only be built from a parse and a compilation that belong
   together.
4. **Modes and exit status.** `DriverMode` is reused: `replay` cannot spend and refuses a
   missing recordings directory; `record` passes `LiveAuthorization.from(env)` before any read
   or write, exactly as the parse driver does. `ExitStatus` is an enum with the code attached:
   `CouldNotStart` (2) when the parser court never convened: every `PipelineError.NotStarted`
   except a refused build receipt (mode, credentials, text, source, recordings, prompt,
   transport, config); `Incomplete` (1) when any sentence never reached the parser court
   (`transportFailures > 0`), when the build receipt was refused after the court (in record
   mode that is after the live calls were made and kept), when the proposal provider, the input
   court, the compiler, or the summary's receipt check refused, or when a file could not be
   written; `Complete` (0) otherwise. A partial draft is a complete bundle: validation status
   is reported, not exited on. Only counts, checksums, and paths are printed.
5. **Determinism.** Two replay runs over the same inputs and the same `nowEpochMillis` produce
   byte-identical `storymodel.json`, `compilation-report.json`, and `receipts.json`, and equal
   compilation fingerprints (tested). The timestamp is an input: it sits in the `BuildReceipt`
   inside the draft, so the `@main` passes `System.currentTimeMillis()` and two CLI runs differ
   in the receipt's `createdAtEpochMillis`, therefore in the `storymodel.json` bytes and the
   encoding digest; the compilation fingerprint, the candidate-set checksum, and the receipt
   content checksum are equal across them (tested with two clocks).
6. **The report encoder lives in `pipeline`.** `BundleJson` renders the report and receipts
   with circe, reusing `codec`'s `ProviderCall`, `BuildReceipt`, `StageRecord`, and
   `ExtendedBuildReceipt` encoders so every receipt keeps its interchange shape.
7. **Test recordings.** A recording key digests the token identities of its sentence, and a
   token id names its story, so `provider-agent`'s three authored recordings (written for a
   three-sentence text) cannot serve the fifty-sentence fixture. `pipeline/src/test/resources/
   recordings/three/` holds byte-for-byte copies of them; `recordings/wog/` holds the same three
   files re-keyed to the fixture text. A suite test pins that both sets are exactly the three
   authored PENMAN replies under the keys each source derives.
8. **`tools/reference-scope.sh` joins `build.sbt` onto one line before reading
   `jvmOnlyModules`.** The list now exceeds 100 columns and scalafmt wraps it. Measured
   2026-09-02: the two-line form already on `main` matched nothing, so the tool would have
   emitted `providerAgentJVM/test`, a project that does not exist. An empty extraction now
   exits 3 (the same guard `module_dirs` has) instead of silently suffixing `JVM` everywhere.

## The War of the Ghosts court, as pinned

Replay over the fixture text with `recordings/wog/`:

| quantity | value | why |
|---|---|---|
| sentences | 50 | `SurfaceAnalyzer` over the admitted text |
| charts | 3 | the three authored replies, all admitted by the parser court |
| transport failures | 47 | every other sentence has no recording; `unrecorded` = 47 |
| `CoverageCounts` | `(2, 1, 0, 47)` | go-02 and come-01 are predicate roots; be-located-at-91 is `ConceptKind.Special` in the adapter's chart, so the provider abstains with `focus-not-predicate:Special` |
| gaps | 4 | three `unresolved:NoProposal` at the abstained anchor, one `trajectory-inputs-unsupported` step between the two situations |
| validation | 5 errors, `validated == false` | four `compiler.required-derivation`, one `trajectory.complete`; expected until phase 1.4 |
| exit | 1 | 47 sentences never reached the court |

The 1.3 court's `CoverageCounts(4, 2, 1, 43)` does not transfer: it used seven hand charts, two
with entity or embedded roots and one empty, and gave be-located-at-91 `Predicate` kind. The
abstention above is a finding for `document`, not for this module: `ChartProposalProvider.
StateFrames` names be-located-at-91 as a state, but the admissibility rule checks
`ConceptKind.Predicate` first and the AMR adapter classes `-91` reifications as `Special`, so no
real chart can ever reach the state branch. It is left as recorded, because this module owns no
semantics (decision 1); it is filed as its own bead.

The three-sentence text with `recordings/three/` gives `CoverageCounts(2, 1, 0, 0)`, four gaps,
no transport failure, exit 0.

## Rejected alternatives

- **Naming the module `build`** as the plan does. sbt reserves `build` for the meta-build
  directory `project/`'s own project, and a `lazy val build = project.in(file("build"))`
  collides with the `build` key namespace; `pipeline` says what it is.
- **A `CompilationReportCodec` in `codec`.** The report is this orchestrator's own account of a
  run, not an interchange artifact any other module decodes; a `codec → document` edge for it
  would widen the portable dependency graph for no consumer.
- **Widening `ChartProposalProvider.input` to return the proposals.** The ledger is needed for
  the report, so `propose` is called first and `input` second; both are pure and deterministic
  over the same arguments. Changing `document`'s public vocabulary for a consumer's convenience
  is not this module's decision.
- **A `test->test` dependency on `provider-agent` for its recordings.** It would have put
  `provider-agent`'s test classes on this module's test classpath for three files, and it would
  not have helped: the committed recordings are keyed to a different story (decision 7).
- **Writing the parser artifacts when the compiler refuses.** A partial bundle with no model is
  the shape the bundle spec warns against; the recordings directory already preserves what a
  record-mode run paid for, so nothing is lost by writing nothing.
- **Exit 0 with transport failures.** The files are complete, but the court record is not; a
  caller that scripts over the exit code must see the missing sentences.
- **Naming the driver object `StoryBuild`** beside `@main def storyBuild`. Scala 3 generates a
  class named after the main method, and the two differ only in case; on a case-insensitive
  filesystem one class file overwrites the other. The object is `StoryPipeline`.
- **Fixing the be-located-at-91 abstention in this slice** by admitting `Special` focus
  concepts. That is a semantic rule in `document` with its own ADR 0005 amendment line and its
  own court; widening the slice to carry it would land a semantic change under an I/O module's
  commit.

## Evidence

`StoryBuildSuite` (pipeline, JVM): twelve tests, replay only, `liveCalls == 0` by construction:
the two recording sets pinned to their sources; the fifty-sentence court above with every
literal (story id, coverage rows 0 and 1 in full, gap 0 in full, the violation law multiset,
the rules-text config hash); sentence isolation (delete one recording, exactly that row moves
to `no-chart`, every other row byte-equal); missing recordings refused with no output
directory; record mode without the environment opt-in refused with neither directory created;
a refused build receipt (negative timestamp) after the court and before any write, mirrored in
`provider-agent`'s `RecordedReplaySuite`; the summary's receipt check refusing a compilation
without the parser stage and one over another source; byte-identical files across two runs; the
clock moving only the receipt timestamp, the model bytes, and the encoding digest;
`StoryModelCodec.decode` round-trip to the encoding digest and the receipt; the exit-status
table; the three-sentence complete run at exit 0.

Mutation ledger, 2026-09-02 (apply mutant → run the project's tests → named test red → restore;
`git status` clean after every step; logs `mutant-*.log` in the session scratchpad):

| mutant | what was changed | tests that went red |
|---|---|---|
| m1 | `ExitStatus.of`: delete the `transportFailures > 0 => Incomplete` case | "the WOG replay court: 50 sentences, three charts, two situations, a partial draft" (1 of 9) |
| m2 | `StoryPipeline.run`: create `outDir` before `parse` | "a missing recordings directory is refused before any file is written", "record mode without the environment opt-in is refused before touching disk" (2 of 9) |
| m3 | `BuildSummary.derive`: `validated = compilation.validated.isEmpty` | the WOG court, "removing one recording makes exactly that sentence NoChart and moves nothing else", "a run whose every sentence reached the court exits 0" (3 of 9) |
| m4 | `BundleJson.coverageRow`: render `NoChart` as `proposed` | the WOG court, the sentence-isolation test (2 of 9) |
| m5 | `ClaudeParseDriver.run`: drop the `writeSummary` step after `parse` | five `RecordedReplaySuite` tests in provider-agent, first "the driver replays end to end: per-sentence artifacts, a derived ledger, no prose" (5 of 52) |

Fix-pass ledger, 2026-09-02 (same procedure, after the cold review of e0f14489):

| mutant | what was changed | tests that went red |
|---|---|---|
| m6 | `StoryPipeline.run`: hoist a write (`receipts.json`) above the parse court | "a refused build receipt comes after the court and before any write", the missing-recordings test, the record-without-opt-in test (3 of 12) |
| m7 | `ClaudeParseDriver.run`: hoist a write (`summary.json`) above `parse` | "a refused build receipt comes after the court and before any write" and three earlier refusal tests in `RecordedReplaySuite` (4 of 53) |
| m8 | `ExitStatus.of`: delete the `ReceiptInvalid => Incomplete` case | the refused-receipt test, the exit-status table (2 of 12) |
| m9 | `BuildSummary.derive`: drop the parser-stage check | "the summary refuses a compilation whose receipt does not carry the parse it was fed" (1 of 12) |
| m10 | `BuildSummary.derive`: drop the source-checksum check | the same test (1 of 12) |

What this evidence does not establish: the record path against the real SDK (network-free by
construction; `LiveSmokeSuite` stays skipped), the bundle wrapper of phase 2.2, and the WOG
numbers once phase 1.4 lands, which the owner has said will land first and will move the pins.
Several report fields have only a zero or `false` reading here and no positive control:
`validated`, `warnings`, `emptyCharts`, and the served-from counts `replayedCaptured`,
`capturedLive`, `foreign`, and `corrupt` (provider-agent courts the last four at the driver
level; this suite never exercises them through the pipeline).

## Consequences

- The plan's phase 1.2 exists under the name `pipeline`; `storyBuild replay <text> <recordings>
  <out>` is the first command that turns a text file into a `StoryModel` on disk.
- `ClaudeParseDriver.run`'s behaviour is unchanged except that a refused receipt now precedes
  every write.
- Phase 1.4's compiler extension will move the pinned gap and error counts; the suite is the
  place that change must be recorded.
- Phase 2.2 wraps the trio in `result.json` and `manifest.json` through `StoryOutputResult`.
