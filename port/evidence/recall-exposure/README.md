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

The probe prints one JSON object after `RECALL_EXPOSURE_ORACLE=`. The saved
`expected.json` adds the reference, encoding description and query bounds. To compare
the emitted object with those six cases, capture the sbt output in `probe.log`
and run the following from this evidence directory:

```sh
python3 - <<'PYTHON'
import json
from pathlib import Path

marker = "RECALL_EXPOSURE_ORACLE="
lines = [line.split(marker, 1)[1] for line in Path("probe.log").read_text().splitlines() if marker in line]
assert len(lines) == 1
observed = json.loads(lines[0])
saved = json.loads(Path("expected.json").read_text())
assert observed["schema"] == saved["schema"]
assert observed["cases"] == [
    {k: v for k, v in case.items() if k != "query"} for case in saved["cases"]
]
PYTHON
```

Actual JVM run: Scala 3.7.4 / sbt 1.12.14, `TemporalQuerySuite`, 26 total,
26 passed, 0 failed, exit status 0. No broader gate or non-JVM platform was run.
