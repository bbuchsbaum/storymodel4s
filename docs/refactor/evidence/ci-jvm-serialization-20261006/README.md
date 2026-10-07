# JVM CI serialization repair — 2026-10-06

Main `885cba1ee14a7520ae4725f4d93af3492b107469` failed [hosted run 37544052239](https://github.com/bbuchsbaum/storymodel4s/actions/runs/37544052239): JVM17's `WorkspaceMappingExportSuite.each explicit policy exports its unchanged record and an exact input-byte receipt` timed out after 30 seconds (36.072 seconds observed). The complete archived logs and receipt record JVM17 3,469 passed / 1 failed / 6 skipped; JVM21 3,470 / 0 / 6. JS completed 2,755 assertions before job cancellation; Native was canceled before test totals. Neither canceled job qualifies that run.

Per-project `parallelExecution := false` does not serialize aggregate project tests. Interleaved module output shows concurrent aggregate execution. Contention is a plausible cause, not a proven exclusive cause of the timeout. Generated JVM CI now uses the existing sequential `testJVM` alias covering exactly the root's 24 JVM modules. Native retains `testNative`; JS retains `test`. Compiler/project preambles, deadlines, assertions and matrix cells are unchanged.

The clean-clone named-suite check passes four assertions in 12.074 seconds under fatal warnings, followed by `scalafmtSbtCheck` and `githubWorkflowCheck`; raw output, exit status, alias membership and generated workflow digest are retained. This is local evidence on Java 25, not hosted JVM17/21 qualification. Hosted candidate qualification and exact-SHA independent review remain pending.

This CI-only transformation changes no consumed module settings, dependencies, public/runtime source or exports. StoryAtlas retains its independently qualified producer pin `433aa1056f6aa5e88b63b4b665079e9959a266ae`; a repin would not change its compiled inputs.
