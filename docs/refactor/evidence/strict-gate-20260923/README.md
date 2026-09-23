# Strict (fatal-warnings) gate restored

`bd-01M365FPGWS0XTXXNHNPFKGWZ7`. Code change `36174e31` (parent `1395ce2c`)
discards the `Files.deleteIfExists` result explicitly at three cleanup sites in
`corpus-intake/.../MigrationSuite.scala`. Those sites were E175 under CI's
`tlFatalWarnings := true` and stopped `corpusIntake/Test/compile`.

**The strict full gate passed on `36174e31`: `GATE_EXIT=0`, 56 test tasks,
8,371 total tests = 8,366 passed, 5 skipped, 0 failures or errors, 0 `[error]`
lines.** That run used a local grakern override (below), so it is **not a full
pinned-dependency pass**; the pinned-module run is separate evidence and does
not upgrade it. The next combined full gate uses the repository pin. Because `checkAll` compiles every module's main and test sources on
every platform, the strict run also shows that main had no further strict-only
warnings beyond these three.

All runs are the author's own (`LocallyObserved`). The steward `codex-p1-lead`
independently recounted the raw strict log (Fray #49, seq 487); nobody
independently re-ran it. No hosted CI run is claimed.

## Runs

| log | tree | command | result |
|---|---|---|---|
| `strict-checkAll.log.gz` | `36174e31`, clean standalone clone, 0 untracked | `sbt -batch -Dstorymodel4s.grakern.build=$HOME/code/scala/grakern 'set ThisBuild / tlFatalWarnings := true' checkAll` | exit 0; 8371 / 8366 / 5 skipped / 0 failed |
| `control-unfixed-corpusIntake.log.gz` | `1395ce2c` (fix stashed) | `sbt -batch -Dstorymodel4s.grakern.build=$HOME/code/scala/grakern 'set ThisBuild / tlFatalWarnings := true' corpusIntake/Test/compile` | exit 1; exactly three E175 at `MigrationSuite.scala:94`, `:107`, `:142` |
| `fixed-corpusIntake.log.gz` | working tree before commit, content equal to `36174e31` after `scalafmt` | same, with `corpusIntake/Test/scalafmt` first | exit 0; no E175 |
| `pin-embedGrakern-clean.log.gz` | `36174e31`, grakern clone at the pin | `sbt -batch -Dstorymodel4s.grakern.build=<clone at 0329c43c> 'set ThisBuild / tlFatalWarnings := true' embedGrakern/clean embedGrakern/Test/compile embedGrakern/test` | exit 0; 28 / 28; clean recompile (3 main incl. generated revision, 6 test sources) |

The strict and pin logs embed their own command line, SHA and window in their
first line. The two `corpusIntake` logs do not; their commands are recorded here
and in Fray #49 seq 452.

## grakern override

The strict full gate used the local grakern checkout at `8efc5efa` (0 dirty
files), not the `build.sbt` pin `0329c43c`. The pin is an ancestor of
`8efc5efa`; the override is 5 commits ahead. Only `embed-grakern` consumes
grakern. `pin-embedGrakern-clean` re-runs that module against a clean clone at
the pin after a clean recompile, under fatal warnings, and passes. The
corpus-intake change and its control do not depend on grakern.

`qualification.json` holds exit markers, parsed totals, E175 counts and the
checksums of each raw and compressed log. Settings not changed: local
`checkAll` still runs without fatal warnings (out of scope for this slice).
