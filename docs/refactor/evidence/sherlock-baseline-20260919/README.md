# Frozen mapping baseline — 19 September 2026

Mote: `bd-01M2WVCEBHAZBKJQ0EB84EA4HV`.
Captured code: `99344ea01ffd82613b85337d6dd23a49b809f0c7`.

Two separate runs of the existing `sherlockRecallMap` on development participant
`NN03_ANT_202231_final` produced identical report, posterior, stage-trace, voyage
and inventory bytes. Both subprocesses exited 0. The TSV also matches the preserved
historical `all17-monofill` report exactly. No production mapper behavior changed.

The [full `sbt checkAll` receipt](checkAll.json) binds the same commit in a clean
standalone clone, clean before and after: exit 0, 56 module/platform totals,
6,378 passed test executions, 5 skipped, 0 failures/errors. The skips are the live
remote-parser smoke, one optional neural localization test, and three live media
tool/worker tests; their exact names remain in the receipt. The independent real
baseline above did run the pinned local ONNX model. This is local gate evidence,
not an executed GitHub Actions run. [Scope derivation](scope.json) covers the full
main-to-candidate diff, including the preceding planning checkpoint.
The separate [format-last check](format-last.json) also exited 0 on that clean
commit. Code was then fast-forwarded locally onto `main`; this evidence and tracker
closure are a subsequent documentation-only checkpoint.

| Retained object | Count |
|---|---:|
| Parsed recall words, with stable file-hash/index IDs | 2,495 |
| Recall units, including every report/sidecar row | 173 |
| Source targets | 1,050 |
| Annotation rows with exact part/tick coordinates | 1,000 |

The source target grain is 1,000 annotation rows at level 0 and 50 coder scene
groups at level 1. This is the existing annotated-source reconstruction profile.
All 173 units received a source choice under its monotone/fill settings; this is
an output count, not evidence that every choice is correct or content-supported.
The word series records projection membership, not independent observations.
Of the 2,495 parsed words, 2,401 overlap an inference unit. The other 94 are
conjunction separators consumed by the existing clause-splitting rule; all remain
individually identified in the inventory. The [coverage receipt](word-coverage.json)
retains their indices. This is segmentation accounting, not 94 missing inference
rows; future word projections must declare how they handle these tokens.

The [manifest](manifest.json) contains the complete content-free inventory,
admitted input/model/record hashes, runtime, configuration, command/exit receipts
and artifact hashes. `rowLociSha256` covers the canonical UTF-8 table with header
`row\tpart\tstartTick\tendTick\n`, one LF-terminated row per annotation row in order.
An independent Python integer oracle derives this table from the raw annotation
columns and committed repair record, including rows unused by this recall.
Its digest is `59e8118472c91e07506910ab27b4cbf4a30ea9f0ce8703a6f44f66b4531dd8dc`.
The TSV digest is `528ceb1b1e84ddca9f0ef3395b1047929d60cc1bccac22fb0dcc3c67273aafc7`.

Raw artifacts and logs remain under the ignored local data root at
`study/recall-to-video/baseline-20260919-nn03-v2/`. Each run has a distinct directory
and receipts; hashes and exit markers are checked. No gold file, sealed participant
output, remote inference provider or media bytes were opened. The local ONNX model
was recomputed in each process; there is no response cache. The manifest preserves
model/tokenizer admission pins and the build inputs, including the preexisting local
`zz-worktree-local.sbt` shim. Log timings and absolute output paths were the only
predeclared parity exclusions; all five artifacts were compared without exclusions.

Reverify the saved evidence on the original local paths:

```sh
python3 tools/recall-study/freeze_baseline.py verify \
  docs/refactor/evidence/sherlock-baseline-20260919/manifest.json \
  --data-root /Users/bbuchsbaum/code/scala/storymodel4s/data
```

The executable capture commands are recorded in the manifest. A new capture must
use a fresh output directory and a committed, clean code revision. Future clock
integration compares the legacy TSV/anchor results and all-row coordinate digest
against this baseline; newly added provenance fields are tested separately.

The [14-test receipt](python-tests.json) covers lost/duplicated/reordered units,
foreign receipts, changed inputs/configuration, complete word membership, unused
source-row changes, failed/missing outputs, sealed-participant refusal, path escape,
unsupported offsets, duplicate-run receipts and byte differences. The
[three mutation witnesses](mutations.json) delete the complete-coordinate,
two-run parity and distinct-receipt guards in temporary Python modules. Each mutant
compiles, makes its named rejection test fail, and keeps the accepting replay test
passing. Run them with:

```sh
python3 tools/recall-study/test_freeze_baseline.py -v
python3 tools/recall-study/mutate_baseline_checks.py
```

The original synthetic [miniatures](../../../../tools/recall-study/fixtures/baseline-miniatures.json)
were admitted by a separate fresh-context reviewer before their first commit.
They represent text and two-part annotation views, backward/revisit paths,
external material, missing timing, a processing-failure control, ambiguous
occurrences, partial support and an argmax/decoded disagreement. Their expected
cases are hand-authored; these integrity tests do not measure mapper recovery.
Recovery remains a later, separately gated ticket.

The first real attempt, at `edb1e316`, was retained under
`study/recall-to-video/baseline-20260919-nn03/`. Its mapper exited 0, but capture
refused because the verifier conflated the stage trace's recall-graph checksum
with the transcript-text checksum. The exporter now records both identities;
synthetic fixtures also make them distinct. The accepted capture reran both
processes at the corrected commit. No failed attempt was blessed or overwritten.

The [fresh-context review](review.json) by `baseline_cold_review` identified and rechecked voyage
identity/completeness and duplicate-run receipt fixes, and independently reran
the synthetic tests and mutations. The final checksum correction was prompted by
the real run, independently rechecked, and is bound by the successful graph-identity joins above.

This baseline establishes reproducible engineering behavior on one development
recall. It establishes no localization accuracy, confidence calibration,
behavioral recovery, media reachability, reference-measurement compatibility or
population-level result.
