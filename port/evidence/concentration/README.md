# Scala TemporalConcentration reference

This directory contains executable, synthetic JVM evidence for the connected and
disconnected concentration APIs at `7a5f1f591c9971ce5f527d8d4d95e2dcc9cf2806`.
It reconstructs the checked row from `TemporalQuerySuite`: an actual `[0,40)`
edition-playback axis, two disconnected intervals, one point allocation,
unavailable source support, and external mass. The witness derives axis,
components, support, allocation and ledger directly from `prepared`; the `[0,10)`
unit grid and `[0,2)` crop are explicit analysis queries in the expected output.

The witness emits the exact synthetic inputs and complete allocation ledger, then
records `bestWindow`, `shortestWindow`, `disconnected`, a cropped grid, and three
distinct refusals. It is an execution of the Scala implementation, not a Python
reimplementation.

Regenerate in a standalone clone; `evidence` is the absolute path to this directory
in the port checkout:

```sh
reference=7a5f1f591c9971ce5f527d8d4d95e2dcc9cf2806
probe_root=$(pwd)
probe_clone=$(mktemp -d /private/tmp/storymodel4s-concentration.XXXXXX)
git clone --shared --no-checkout "$probe_root" "$probe_clone"
git -C "$probe_clone" checkout --detach "$reference"
cp "$evidence/TemporalConcentrationJvmEvidence.scala" \
  "$probe_clone/align/src/test/scala/storymodel4s/align/"
cd "$probe_clone"
sbt -Dsbt.log.noformat=true \
  "alignJVM/Test/runMain storymodel4s.align.TemporalConcentrationJvmEvidence $reference" > run-1.log 2>&1
sbt -Dsbt.log.noformat=true \
  "alignJVM/Test/runMain storymodel4s.align.TemporalConcentrationJvmEvidence $reference" > run-2.log 2>&1
rg '^\{"schema"' run-1.log > emission-1.json
rg '^\{"schema"' run-2.log > emission-2.json
cmp emission-1.json emission-2.json
cmp emission-1.json "$evidence/temporal-concentration-jvm.json"
sbt -Dsbt.log.noformat=true "alignJVM/testOnly storymodel4s.align.TemporalQuerySuite"
```

The temporary clone is disposable only after the successful comparison and test.
This establishes synthetic, local temporal arithmetic. It does not establish
provider execution, corpus validity, calibration, joint-path inference, or full
Scala/Python parity.
