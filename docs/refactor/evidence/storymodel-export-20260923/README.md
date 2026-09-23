# S5 StoryModel export evidence (2026-09-23)

Author-run (LocallyObserved), clone .worktrees/storymodel-export, branch claude-sm/storymodel-export.

- `loss-record-mutants.log`: `mutate.py` deletes each of the 16 `Loss(...)` records from
  `codec/.../storymodelexport.scala` in turn and runs `pipeline/testOnly StoryModelExportSuite`
  (JVM, sbt --client, grakern override `~/code/scala/grakern`). Every mutant compiled; every one
  failed its named `loss record <name> ...` test plus the exact-set test (and the claim-accounting
  test where claims leave with the record), with 29-30 sibling tests passing. Source restored after.
- Not killed by this set: count-value mutations on structures that are zero in the War of the
  Ghosts fixture (feature-spaces, sidecars, feature-refs, sensory-profiles, resolved-alternatives).
  Deleting those records is killed; miscounting them is not detectable on this fixture.
- Python: `examples/storymodel-export/check_export.py war-of-the-ghosts` recovers counts, the
  hand-checked `wog:ent:egulac` row, absent-vs-zero cells, a lawful out-of-table upstream reference,
  and refuses 11 rehashed corruptions.
