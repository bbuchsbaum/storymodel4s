# Production ClockRepair integration — 19 September 2026

Mote: `bd-01M2WTGF981AY7RCN8QC42JSPT`. Qualified implementation: `6a5a92881451fb2add3947e974a196cd85070d10`.
The [clean standalone-clone gate](checkAll.json) ran `sbt -batch checkAll`, exit 0:
**6,436 passed executions, 5 skipped, zero failures or errors** across
56 module/platform totals. The tree was clean before and after.
[Scope](scope.json) and [format-last](format-last.json) are bound to the same commit.
This is local qualification, not an executed CI or media-reachability check.

All production annotation bounds now pass through `ClockRepair.projectRunLocalSeconds` with a
distinct per-run source axis and the actual selected bundle axis. The checked record supplies
the annotation/media pins, part IDs, row partition, rates, extents and restrictions. Duplicate
Scala manifest literals are removed. `Atlas` links every row to the executed repair receipt.
The CLI writes a separate `.clock-repair.json` sidecar, bound to the report and source fingerprint.
The one-argument parser refuses; callers explicitly load or pass a checked record. See the
[existing ADR's integration contract](../../../adr/0018-annotation-intake-contract.md).

The [real replay receipt](capture.json) records two NN03 development runs at
`2ce9b13f7af7d484c97fd8fbcc2a45dae191005c` with the frozen local ONNX model, tokenizer and configuration.
There was no provider response cache: the pinned local model was recomputed. Both new runs match
the frozen baseline's **five legacy artifacts byte for byte**, including the TSV, inventory,
posterior, stage trace and voyage. This covers 173 recall units, 2,495 words, 1,050 targets and
all 1,000 annotation rows, including unselected rows, instants and rows 481–482.
The only later Scala difference is a mechanically checked Scaladoc wrap; the final gate also
includes the independently checked Python verifier fix.

| Witness | SHA-256 |
|---|---|
| Legacy TSV | `528ceb1b1e84ddca9f0ef3395b1047929d60cc1bccac22fb0dcc3c67273aafc7` |
| Anchor inventory | `e4ef1382fda290595d779922da3fea87bd6e9a74cc717dcf66103c5290465282` |
| Complete row/part/start/end table | `59e8118472c91e07506910ab27b4cbf4a30ea9f0ce8703a6f44f66b4531dd8dc` |

The [parity verifier](../../../../tools/recall-study/verify_clock_repair.py) verifies original
input bytes, configuration, complete inventories and all old artifact hashes. It separately
checks both new sidecars: all row-to-receipt links, actual axes, exact scales/offsets, restrictions,
record identity and independently recomputed receipt IDs. It also compares receipt parameter
meaning with the record: a valid digest alone is insufficient. [Results](parity.json).

```sh
python3 tools/recall-study/verify_clock_repair.py \
  docs/refactor/evidence/sherlock-baseline-20260919/manifest.json \
  data/study/recall-to-video/clock-repair-20260919-nn03/manifest.json \
  --data-root data
```

The [seven compiled Scala mutations](scala-mutations.json) bypass direct projection, bundle
identity, integrality, formula checking, offset consistency, duplicate-key refusal and observed
scanner bounds. Each fails its named rejecting test while its named accepting control passes;
fresh JUnit results prevent stale passes. Original files are restored byte for byte, the 87
intake and 8 bridge tests pass (one optional neural skip), and formatting passes last.
The [provenance witnesses](provenance-witnesses.json) reject eleven altered sidecars, including
a consistently rehashed forged receipt. Removing the parameter guard makes that rejecting
witness fail while preserving the accepting control.

The [cold review](review.json) found and independently reproduced three production refusal gaps
and the verifier's self-hash gap; each was fixed and given a discriminating witness. It also
prompted removal of production notebook/audio claims from the synthetic metadata fixture.
No new narrative fixture text was introduced. The initial [full-gate formatting failure](initial-format-failure.json)
ran no tests and is retained as a failure. A [prior full gate](prior-verifier-gate.json) also
remains, before the Python parameter-check fix; final qualification is the exact revision above.
Raw logs and [owned runners](local-runners.json) remain under the ignored data root.

The record is the sole reviewed admission authority. Owned `VerifiedArtifact` resolution proves
local byte consistency and uniquely binds schema/version; it does not independently authenticate
the Git root. Annotation bytes are verified against its pin. Media bytes are not opened.
The upstream notebook remains explicitly **declared-unverified**; the 998-row notebook-coordinate
checksum is checked by deterministic replay from the admitted annotation rows. The 0.2-second
part-A and 10.8-second part-B tails remain. Recall-clock and audio metadata are documentary
fields outside this annotation-to-video mapping contract.

This closes the bounded coordinate integration. It establishes engineering parity and receipt
integrity, not localization accuracy, reference-measurement compatibility, behavioral recovery,
or confidence calibration. G0 remains open for separate S0 and infrastructure work.
