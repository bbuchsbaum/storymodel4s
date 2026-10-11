# Temporal producer qualification

Producer: `c89136192cdde5929bec7900b9e954741e387340`.
Final independently reviewed source and Python reader:
`59bd1fecac628b6c7667920a5b9d2d79a0d512d2`.
The only delta between these revisions is the two Python reader files;
all Scala and build inputs are identical. Local integration base:
`198550727aa09dcc52d05662652c51f2565a79dd`.

## Qualification

| Check | Passed | Failed | Errors | Skipped | Cells |
| --- | ---: | ---: | ---: | ---: | ---: |
| Strict producer `checkAll` | 10,406 | 0 | 0 | 5 | 56 |
| StoryAtlas strict compatibility | 488 | 0 | 0 | 0 | 10 |
| StoryAtlas timed workspace | 3 | 0 | 0 | 0 | 1 |

All commands exited zero, including final formatting. Fresh fixture generation and
the workspace reader passed. The final Python reader checked 14 synthetic cases,
4 facade projections and rejected 23 deliberate corruptions. The producer-revision
reader also passed its earlier 21 corruption controls; the final reader adds two
contextual controls. These reader counts are separate from sbt test totals.

The gate ran in a clean standalone clone with fatal warnings and the pinned grakern
revision. `temporal-reference-scope.txt` records the scoped command; the executed
`checkAll` covers the full repository. Formatting ran last. Command metadata records
actual exit status and working directory. Reader checks use newly generated artifacts
from the exact producer revision, not the earlier development exports.

Sixteen compiled mutations were killed. Fourteen core/boundary mutations ran at
`e08bf64d058470b3d939b34bc2d42549382e6b79`; the mutation binding receipt proves their
11 source/test inputs byte-identical at the final producer. Two additional final-producer
mutations removed the duration-weighted overflow guard and failed-request identity.
Each had a named assertion failure. Constructor probes required clean test recompilation;
earlier cached or malformed-command runs are retained as inconclusive, not kills.
The restored core court passed 65 tests before the final full gate.

## Independent evidence and scope

The independent reviewer issued SOURCE PASS on the exact final source revision after
repairs to contextual source/measurement joins, failed-request resume identity,
selected-coordinate joins and missing-measure relabeling. The review transcript is
retained in `receipts/temporal-review.txt`. Final archive review is recorded separately
on Mote and in git after this archive commit.

The Python reader independently recomputes interval/point allocation, support bounds,
sparse weights, crop accounting and recall exposure using integer and rational
arithmetic. Paired checks join the original mapping members, units, assignment weights,
source selection and timing; rehashing corrupt JSON does not bypass these checks.
Scala contextual decoding remains the admission authority for an independently expected
execution. Standalone projection verification checks arithmetic only.

Fixtures include narrow/broad/disconnected/point support, mixture allocation,
external mass, repeated occurrences, failed rows, missing timing and ticks above 2^53.
The checked native source axis is distinct from unavailable repeated-occurrence
allocation. See the [producer handoff](../../../TEMPORAL-HANDOFF-20261011.md) for
schema versions, API/CLI contracts, commands and known answers.

StoryAtlas uses an explicit local source override at this producer revision.
Its source pin is unchanged. These receipts establish local engineering contracts;
they do not establish hosted CI, browser acceptance, model inference, calibrated
localization or empirical scientific efficacy. No push was performed.

## Artifact integrity

`manifest.json` binds each stored file and its original bytes. Raw logs use lossless
gzip with deterministic timestamps. Development and inconclusive logs remain visibly
named; only `temporal-final-*` and the named consumer receipts establish final
qualification. The final-state and qualification JSON files bind revisions and totals.
The fixture archive contains derived exchanges and requests, not raw story input files.
