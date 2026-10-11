# Temporal projection producer handoff

This extension completes the producer-side temporal query path in
`bd-01M3549Q5W5KQFSY3ZH81FARM0`. It supplies checked source grids, recall exposure,
concentration queries, paired exchanges and renderer-neutral view packets.
StoryAtlas owns its renderer and browser acceptance.

## Semantics consumers must preserve

- `TemporalQuery` keeps normalized scores, model posterior and transport quantity
  distinct. Transport retains its supplied row budget and discrepancy.
- `TemporalExposure.checked` joins the exact mapping inventory and timing inventory.
  Word intervals are unioned within each inference unit. Across-unit overlap either
  refuses or uses explicitly requested equal shares. Gaps remain uncovered; a final
  onset has zero duration. Repeated word display does not create new observations.
- `TemporalGrid` stores only possible-support or nonzero allocated cells. Its shared
  bin vector also represents empty bins. Crop, rebin and top-k retain the original
  row, external states, missing locations, support without allocation and outside mass.
- `TemporalConcentration.bestWindow` optimizes on integer start ticks, breaking equal
  numerical quantities toward the earliest start. `shortestWindow` distinguishes an
  absolute quantity, share of supplied mass, and share of allocated source mass.
  `disconnected` selects whole declared grid cells; it does not claim sub-cell precision.
- A support interval is not a precision estimate. Allocation requires an explicit
  compatible uniform-interval, uniform-point or mixture kernel. A support-only row
  retains lower/upper bounds and has no allocated quantity.
- `TemporalProjection` retains every mapping unit, including failed units. Optional
  recall-window exposure is separate from the source grid. Duration weighting retains
  the assignment measure's units and explicitly refuses nonfinite products.
- Clock uncertainty is `NotModeled`. Marginal results cannot supply a joint path:
  `UnavailableWithoutJointEvidence` remains explicit. No calibrated accuracy,
  fine localization, neural inference or empirical performance claim follows here.

## Existing facade and additive exchange

For a prepared `MappingPipeline.Batch`, supply a request per observation checksum to
`MappingPipeline.temporal`, `inspectTemporal` or `exportTemporalResults`. Missing
requests, timing and failed mapping attempts remain in cohort counts. Exported
metadata records each supplied request and its digest even when projection fails.
An unchanged request can resume only when every existing artifact byte matches.

The ordinary command is:

```text
mappingRun temporal STUDY.json CONFIG.json REQUEST.json OUTPUT [--resume]
```

`REQUEST.json` is emitted by `TemporalProjectionCodecs.encodeRequest`. Source bins
are half-open decimal-string ticks on the selected part or occurrence axis. A recall
window must name its actual checked recall-clock digest. The CLI applies its one
request to the observations in that study; the typed batch API supports different
windows/clocks per observation. Exit codes follow the existing facade: 0 ready,
2 invalid input/refusal, 3 partial results or missing requested timing, 4 unavailable
requested capability. Existing text-admission and disclosure checks still apply.

The paired temporal bundle adds projection and, when supplied, original recall-timing
JSON files. It retains the base paired manifest and all original mapping files.
`MappingExchange.decodeTemporal` requires the originating execution and timings,
recompiles projections, and compares the complete rebuilt bundle. Rehashing a corrupt
projection does not grant admission. The base mapping exchange format is unchanged.

| Artifact or query | Version |
| --- | --- |
| Conditional query | `conditional-temporal-query/v1` |
| Projection | `temporal-projection/v1` |
| Request | `temporal-request/v1` |
| Paired temporal exchange | `mapping-exchange-pair-temporal/v1` |
| Batch exchange | `mapping-temporal-batch/v1` |
| Sparse grid | `sparse-temporal-grid/v1` |
| Concentration | `temporal-concentration/tick-lattice/v1` |
| View packet | `temporal-grid-view/v1` |
| Recall exposure | `recall-exposure/v1` |

Ticks, rational numerators/denominators and binary64 bit patterns are strings. Do not
route integer ticks through a JavaScript `Number`. Atlas should use the producer's
mapping/projection/axis identities and original inference-unit addresses; layout
must not change scientific values or renormalize visible rows.

## Reproduce and inspect

The qualification receipt and synthetic artifacts are stored under
[`evidence/next-five-20261010/temporal`](evidence/next-five-20261010/temporal).
That receipt names the exact producer revision, generator commands, raw log hashes,
file hashes, strict test totals, mutation outcomes and independent review.

The Scala test producers are `storymodel4s.codec.TemporalProjectionFixtures` and
`storymodel4s.pipeline.TemporalPipelineWitness OUTPUT PRODUCER_SHA`. Run them through
`codecJVM/Test/runMain` and `pipeline/Test/runMain` in a standalone checkout of the
recorded producer revision. The pipeline witness creates ordinary synthetic input
files and exports both reference/reconstruction members for timed and untimed cases,
including a failed cohort attempt.

`python3 tools/test_temporal_projection.py FIXTURE_LOG FACADE_WITNESS_DIRECTORY`
recomputes interval/point kernels, bounds, sparse weights, crop accounting and recall
exposure with Python integers and `Fraction`. It also checks the original paired
mapping artifacts, assignment weights, inventory units and timing joins. Deliberate
corruptions are rehashed before contextual rejection checks.

For a standalone projection, `tools/verify_temporal_projection.py FILE` checks its
arithmetic only. `--pair DIRECTORY` checks a complete paired bundle with unprefixed
member filenames. These independent readers check supplied artifacts for consistency;
Scala contextual decoding remains the admission authority for an independently
expected execution.

Known-answer controls include a .5 assignment uniformly allocated on `[10,20)`:
a one-tick crop contains .05, the full interval .5, and two one-tick bins .1. Support-only
query `[10,15)` has bounds `[0,.5]` and no allocation. The timed facade control has one
second of interval exposure, three uncovered seconds in `[0,4)`, and an onset at 2
without an invented end. Additional fixtures exercise point mass, disconnected peaks,
repeated occurrence refusal, external mass, failed units and tick `9007199254740993`.

The StoryAtlas compatibility receipt uses an explicit local source override at the
recorded producer SHA; it does not update the consumer's source pin. Local gates and
synthetic fixtures establish engineering contracts. Hosted CI, push, browser
completion and empirical scientific qualification are separate evidence.
