# Combined exchange and local-evidence landing

StoryModel `main` reached `29e85a0c` on 2026-09-23 UTC. This includes the
workspace exchange helper, public export command, process court, shared local
evidence and navigator snapshot regressions.

The two final-revision runs contain **8,371 total tests: 8,366 passed, five
skipped, zero failures or errors**, across 56 test tasks. This corrects the
operator Git note, which called the total a passed count. The note is retained
unchanged as the original report.

**The strict full gate did not pass.** The first strict run on `5daedc27`
stopped at a pre-existing unused import; `29e85a0c` repairs that import. Its
strict rerun stopped on three pre-existing E175 warnings in
`corpus-intake/.../MigrationSuite.scala`. The remaining corpus-intake tests and
formatting checks passed with ordinary repository settings. This composite test
coverage does not establish a green CI-style `checkAll`.

The outstanding strict gate is tracked in `bd-01M365FPGWS0XTXXNHNPFKGWZ7`.
The next repair must eliminate the warnings and run the required strict gate;
do not close that issue from these logs. The full ordinary-file workflow
`bd-01M35B103S2KHRSF853GD2FYHW` also remains open.

`qualification.json` records exit markers, parsed test totals and checksums of
both raw and compressed logs. Source revisions and settings come from the
operator Git note and Fray handoff; these raw logs lack their own exact-argv/SHA
command receipt. They are retained evidence, not a replacement for that missing
binding. The initial failed run is retained separately and is not added to the
final-revision totals. No tests were rerun while creating this archive.

The independent CLI process court is in `../cli-process/`: nine JVM process
cases, two exact mapping exports read by Python, and explicit refusal exits.
The landed CLI/helper bytes match that court's recorded source hashes.
