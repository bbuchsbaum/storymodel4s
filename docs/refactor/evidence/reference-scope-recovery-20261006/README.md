# Reference-scope recovery, 6 October 2026

Mote: `bd-01M1CGP2K4H93WM8CW5EFEPTJK`.
Recovered exact two-file patch `ed9f478daba90d1728ce584b2a896f5a88be9b26` onto
`1f3254b599db1027cf9e78176613756eb6396c85`. Candidate `c3f33c14744ea09c5d4a8bf6df0ca46e9748ec97` was checked in a clean standalone
shared clone. The primary repository retains the referenced commit; the clone is a test surface.

The synthetic Git court passed **98 assertions; zero failed or skipped**. It covers changed and
deleted top-level defs, vals/givens, JVM roots, mixed declaration kinds and documentation controls,
plus existing reference-scope behavior. `bash -n` and `shellcheck` passed. The same court against
the old `1f3254b599db1027cf9e78176613756eb6396c85` scope script exits 1 at the named `changed top-level def`
assertion: expected 5, got 0. Earlier documentation/mixed-source assertions execute first.
This is a baseline regression control, not a claim that every sibling assertion survives a mutant.

[Receipt](receipt.json) binds the exact source blobs, commands, exits and log hashes.
[Regression log](regression.log) and [baseline control](baseline-control.log) retain raw outputs;
[syntax](syntax.log) and [shellcheck](shellcheck.log) retain their exits.

Reproduce by cloning/checking out the candidate, running `bash tools/reference-scope-test.sh`,
and exporting the base scope script with `git show BASE:tools/reference-scope.sh` into a private
scratch file. Set `REFERENCE_SCOPE_SCRIPT` to that file for the expected-red control. Run
`bash -n tools/reference-scope.sh tools/reference-scope-test.sh` and
`shellcheck tools/reference-scope.sh tools/reference-scope-test.sh` separately. Logs record actual
commands and exit status; temporary paths are not required for reconstruction.

Only shell tooling and its synthetic court changed. No Scala/sbt gate or StoryAtlas runtime test
was needed for this slice; repository-wide baseline, hosted CI and product acceptance remain
separate. Independent exact-SHA review and local main integration follow this receipt.
