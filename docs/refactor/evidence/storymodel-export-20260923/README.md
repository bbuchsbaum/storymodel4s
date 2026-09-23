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
