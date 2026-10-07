# JVM CI serialization repair — 2026-10-06

Main `885cba1ee14a7520ae4725f4d93af3492b107469` failed [hosted run 37544052239](https://github.com/bbuchsbaum/storymodel4s/actions/runs/37544052239): JVM17's `WorkspaceMappingExportSuite.each explicit policy exports its unchanged record and an exact input-byte receipt` timed out after 30 seconds (36.072 seconds observed). The complete archived logs and receipt record JVM17 3,469 passed / 1 failed / 6 skipped; JVM21 3,470 / 0 / 6. JS completed 2,755 tests before job cancellation; Native was canceled before test totals. Neither canceled job qualifies that run.

Per-project `parallelExecution := false` does not serialize aggregate project tests. Interleaved module output shows concurrent aggregate execution. Contention is a plausible cause, not a proven exclusive cause of the timeout. Generated JVM CI now uses the existing sequential `testJVM` alias covering exactly the root's 24 JVM modules. Native retains `testNative`; JS retains `test`. Compiler/project preambles, deadlines, assertions and matrix cells are unchanged.

The clean-clone named-suite check passes four tests in 12.074 seconds under fatal warnings, followed by `scalafmtSbtCheck` and `githubWorkflowCheck`; raw output, exit status, alias membership and generated workflow digest are retained. This is local evidence on Java 25, not hosted JVM17/21 qualification. Hosted candidate qualification and exact-SHA independent review remain pending.

This CI-only transformation changes no consumed module settings, dependencies, public/runtime source or exports. StoryAtlas retains its independently qualified producer pin `433aa1056f6aa5e88b63b4b665079e9959a266ae`; a repin would not change its compiled inputs.

## Native witness extension

Candidate `d1ec73ef0f2791b11f21a2e4b98c40d6d62c8d25` passed both complete JVM jobs (each 24 module summaries, 3,470 passed / 0 failed / 6 skipped), JS (16 summaries, 2,755 / 0 / 0) and documentation. The formerly failing pipeline suite passed four tests in 20.818 seconds on JVM17 and 10.107 seconds on JVM21. Complete candidate logs are retained. Native failed `SemanticCompatibilitySuite.an exhausted canonical budget is refused, never ordered by id` at 38.144 seconds against its unchanged 30-second deadline: core 250 passed, proposition 79 passed / 1 failed; downstream Native modules did not run.

That witness independently runs the full 5,040-leaf search twice: default-keyed identity exactness, then gloss-keyed semantic projection refusal. The extension separates these two API contracts into named tests. Both assertions, the full `triangles(5)` fixture, production budget and all deadlines remain; no production code changes. The exactness API had no other named negative budget court, so removing its assertion was rejected. Qualification and a compiling wrong-ID-fallback mutation check are pending.
