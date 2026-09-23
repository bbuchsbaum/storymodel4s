# S5 StoryModel export evidence (2026-09-23)

Author-run (LocallyObserved), clone .worktrees/storymodel-export, branch claude-sm/storymodel-export.

- `loss-record-mutants.log`: bound to tip 6a1195be with a clean `codec`/`pipeline` tree before and
  after (`CLEAN=0`, `POST_CLEAN=0`). `mutate.py` deletes each of the 17 `Loss(...)` records from
  `codec/.../storymodelexport.scala` in turn and runs `pipeline/testOnly StoryModelExportSuite`
  (JVM, `sbt --client`, grakern override `~/code/scala/grakern`). Every mutant compiled and failed
  2-4 of 37 tests, including its named `loss record <name> ...` test; the unmutated baseline after
  restoration passed 37/37.
- `loss-record-mutant-surface-units-rerun.log`: the client output of the surface-units mutant in
  the main run did not carry test names (totals only: 2 failed); a single rerun captured them.
- Not killed: count-value mutations on structures that are zero in the War of the Ghosts fixture
  and in the variant (feature-spaces, sidecars, feature-refs, sensory-profiles). Deleting those
  records is killed; miscounting them is not detectable on these fixtures. resolved-alternatives
  is now nonzero in the suite's variant model.
- Python: `examples/storymodel-export/check_export.py war-of-the-ghosts` recovers counts, the
  hand-checked `wog:ent:egulac` row, absent-vs-zero cells, lawful out-of-table-upstream and
  shared-id variants, and refuses 21 rehashed corruptions, each for its own reason.
- Reviews: codex-temporal (Fray #59 seq531 design APPROVE; #61 typed-id objection, fixed
  f74a30f5/6a1195be); a fresh-context Claude cold review (findings fixed in f74a30f5).

## Scoped gate (author-run, LocallyObserved)

- `scoped-gate-69a8e754.log.gz`: exact tip 69a8e754 in a standalone clone, clean tracked tree.
  Raw log sha256 5109114fbf992b7dcce74910393c23147a99894ffeb8d5abfefcf536fee204a4.
- Command: `sbt -batch -Dstorymodel4s.grakern.build=~/code/scala/grakern "set ThisBuild / tlFatalWarnings := true"`
  plus the 18 JVM modules from `tools/reference-scope.sh` and `codecJS/test; codecNative/test`,
  unpiped; then `scalafmtCheckAll` separately.
- Result: GATE_EXIT=0, FMT_EXIT=0, 20 `Passed: Total` lines, 3327 tests / 3322 passed / 5 skipped /
  0 failed, zero `[error]` lines. 2026-09-23T12:45:48Z to 12:53:14Z.
- Qualification: Grakern came from the local override at 8efc5efa (clean), not the build.sbt pin.
  This is not a pinned-dependency pass; the integrator's combined gate uses the pin.
