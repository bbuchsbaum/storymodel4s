# JVM LocalReference oracle

This is executable numerical evidence from `storymodel4s` commit `ea146bbb7`, not a
reimplementation. It uses the existing `ReferenceFixture`, which is synthetic and checked,
and the established strict candidate/cost providers.

From this repository, create a standalone shared clone and install the probe beside the fixture:

```sh
probe_root=$(pwd)
probe_clone=$(mktemp -d /private/tmp/storymodel4s-local-reference.XXXXXX)
git clone --shared --no-checkout "$probe_root" "$probe_clone"
git -C "$probe_clone" checkout --detach ea146bbb7
cp "$probe_root/port/evidence/local-reference/LocalReferenceJvmEvidence.scala" \
  "$probe_clone/align/src/test/scala/storymodel4s/align/"
cd "$probe_clone"
sbt 'alignJVM/Test/runMain storymodel4s.align.LocalReferenceJvmEvidence' > run-1.log 2>&1
sbt 'alignJVM/Test/runMain storymodel4s.align.LocalReferenceJvmEvidence' > run-2.log 2>&1
diff -u <(rg '^\{"schema"' run-1.log) <(rg '^\{"schema"' run-2.log)
```

The runner prints one compact JSON line. On the captured clone both invocations exited zero and
their emitted JSON lines were byte-identical. `local-reference-jvm.json` is that emitted JSON,
prettified without changing its structure or values; it contains every emitted cost and mass row,
not a reduced transcription.

Scope: one synthetic, single-unit local-normalization oracle. It establishes neither provider
execution, corpus validity, calibration, HSMM inference, nor full Scala/Python parity.
