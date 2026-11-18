# Recall-exposure Scala oracle

`expected.json` is a compact, synthetic, JVM-executed oracle for
`storymodel4s.align.TemporalExposure` at the exact `reference.sha` it records.
Rationals are `[numerator, denominator]`; all query windows are half-open.

The six cases cover separated intervals, an onset-only event with no invented
duration, a gap, duplicate intervals in one unit, cross-unit equal sharing,
processing/missingness, and overlap refusal. `u0` through `u3` are deliberately
abstract fixture labels, not participant or story data.

## Reproduce

Use a standalone shared clone, never the shared primary tree:

```sh
clone=$(mktemp -d /private/tmp/storymodel4s-exposure-XXXXXX)
git clone --shared /Users/bbuchsbaum/code/scala/storymodel4s "$clone"
git -C "$clone" checkout --detach cdb432cf9241fb70a8a7c812d90e01d9b139b3b2
git -C "$clone" apply /path/to/temporal-exposure-oracle.patch
(cd "$clone" && sbt -Dsbt.supershell=false 'alignJVM/testOnly storymodel4s.align.TemporalQuerySuite')
```

The probe prints exactly one line prefixed `RECALL_EXPOSURE_ORACLE=`. Strip that
prefix and compare the parsed JSON's `schema` and `cases` with `expected.json`
after adding the saved query windows from this file. The checked-in expected
artifact is the canonical consumer contract; the probe is intentionally a
disposable clone-only source change.

Actual JVM run: Scala 3.7.4 / sbt 1.12.14, `TemporalQuerySuite`, 26 total,
26 passed, 0 failed, exit status 0. No broader gate or non-JVM platform was run.
