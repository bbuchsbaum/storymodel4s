# Admissibility: independent StoryAtlas consumer check

The lead ran the sibling consumer at StoryAtlas
`39b7f27844674197546610c87cae79102b4c5e73`, against StoryModel
`c57d0ac726a685acd5b24bd60d55f898c562fe91`.

With fatal warnings enabled, `compileAll`, `testAll`, `scalafmtCheckAll`,
`scalafmtSbtCheck` and `app/fastLinkJS` exited 0: **402 passed, 0 failed,
0 errors, 0 skipped**, in ten test tasks. This establishes consumer compilation,
unit tests and JavaScript linking; it is not browser acceptance or hosted CI.
The author still owes the Model slice gate and the repaired compile-probe controls.

`qualification.json` binds both raw/compressed log digests, per-task totals and
all six repository inputs. The consumer and explicitly substituted dependencies
were tracked-clean before the run; all six were checked afterward. Command arrays,
working directory, elapsed time and actual exits are preserved in the metadata.

The first attempt failed during build loading with
`ClassNotFoundException: $87b12d903a899b9bc441$`, before tests. Its log is preserved
as `shared-staging-load-failure.log.gz`; it is inconclusive. The retry uses a private
`sbt.global.staging` directory. It leaves the shared staging cache untouched.

A later candidate may inherit this result only after checking that its production
and build inputs are identical to the tested revision. Probe-only and documentation
changes do not alter these consumer inputs. This receipt alone does not claim any
later candidate has passed its own remaining gates.
