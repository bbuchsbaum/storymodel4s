# Mapping workload baseline — 20 October 2026

Mote child H: `bd-01M5DAK5ED2W3HW3XDS3MZV5PS`. This is a bounded synthetic baseline,
not a speedup, production capacity, calibration or scientific-utility claim.

## Receipt

Compiled capture revision: `3d0ed5f4c52b826e4c434a1309b9287933ae5c5c`. Source SHA-256:
`494b9e3e3d9d3fe8fa9029255a2d57626903c0fed693008438890a8a3d2c5876`. The complete 36-cell, 108-repetition receipt is
[mapping-workload-20261020.json](mapping-workload-20261020.json). Each repetition retains input
hashes, output/manifest identities, allocations, heap observations, operation timings and bytes.

Hardware: Apple M3 Max, 14 available processors, 36 GiB installed RAM. Runtime: OpenJDK
25.0.1, Mac OS X 14.3, aarch64. One warmup and three measured repetitions per cell;
all three output witnesses agree in every cell. No randomization or parallel worker is used.

The exact command is retained with exit 0 in
.agent-work/mapping-audit-20261020/mapping-audit-workload-temporal-08.log.meta.json. It runs
`pipeline/test`, then `pipeline/Test/runMain storymodel4s.pipeline.MappingWorkloadCapture`,
then formatting, with fatal warnings, sbt heap flag `-J-Xmx4g` and the local grakern override.
That flag caps sbt, not the forked capture JVM; its heap uses JVM ergonomics. The override was
clean revision `8efc5efade4e56c6e0b29745bd1b108d4c9a9d57`, not the default source pin.

## Observed operations

The matrix varies source size 2/8/16, recall length 1/8/16, tie budget 1/16 and two/three
requested weighted members. Per-level candidate request remains 1. Twelve cells retain tie
overflow. Every cell completed. Independent counter reconciliation checks considered candidates
= source size × recall length = observed + ineligible + missing scores in all 108 repetitions.
The largest adjacent-state product sum is 2,535. Largest paired package: 1,848,410 bytes;
largest weighted package: 4,806,024 bytes.

These are overlapping public operations, not additive stage partitions. Paired lexical execution
repeats nomination and pricing. Weighted publication repeats weighted inference before encoding.
File-size accounting and cleanup are outside the timed calls.

| Public operation | Median across matrix (ms) | Observed min–max (ms) |
|---|---:|---:|
| preparation | 3.689 | 2.378–10.663 |
| candidateNomination | 0.787 | 0.306–6.978 |
| localEvidencePricing | 1.694 | 0.650–6.093 |
| pairedLexicalLocalEvidenceAndInference | 41.016 | 8.960–363.328 |
| weightedSelection | 1.017 | 0.281–7.370 |
| pairedExchangeExport | 107.116 | 16.697–433.741 |
| weightedReexecutionAndExport | 196.638 | 29.082–913.301 |

The median across heterogeneous cells is descriptive. Compare matching cells using the
per-cell distributions below or the complete JSON, rather than treating this as one workload.

| Source | Recall | Tie budget | Members | Paired execution median [min,max] ms | Weighted reexecution/export median [min,max] ms | Adjacent products |
|---:|---:|---:|---:|---:|---:|---:|
| 2 | 1 | 1 | 2 | 15.66 [11.17,17.57] | 48.51 [43.25,67.35] | 0 |
| 2 | 1 | 1 | 3 | 11.60 [10.10,23.68] | 55.96 [53.14,62.15] | 0 |
| 2 | 1 | 16 | 2 | 9.83 [8.96,11.51] | 49.56 [39.06,95.53] | 0 |
| 2 | 1 | 16 | 3 | 11.67 [10.29,12.45] | 150.95 [96.42,155.81] | 0 |
| 2 | 8 | 1 | 2 | 51.82 [36.75,73.11] | 170.27 [164.04,172.47] | 252 |
| 2 | 8 | 1 | 3 | 46.85 [40.01,213.96] | 602.68 [476.45,638.82] | 252 |
| 2 | 8 | 16 | 2 | 37.37 [36.09,40.40] | 165.45 [159.36,167.74] | 252 |
| 2 | 8 | 16 | 3 | 38.73 [36.64,41.63] | 229.43 [216.74,229.45] | 252 |
| 2 | 16 | 1 | 2 | 66.88 [64.76,71.40] | 304.19 [294.04,366.02] | 540 |
| 2 | 16 | 1 | 3 | 66.74 [62.67,66.75] | 410.84 [407.98,593.23] | 540 |
| 2 | 16 | 16 | 2 | 63.81 [63.08,67.99] | 287.60 [286.71,296.09] | 540 |
| 2 | 16 | 16 | 3 | 68.59 [64.91,76.04] | 415.20 [402.15,457.58] | 540 |
| 8 | 1 | 1 | 2 | 10.47 [10.20,10.85] | 32.05 [29.08,32.35] | 0 |
| 8 | 1 | 1 | 3 | 10.23 [10.12,10.50] | 42.00 [41.88,43.17] | 0 |
| 8 | 1 | 16 | 2 | 15.23 [14.34,15.56] | 52.23 [46.30,54.32] | 0 |
| 8 | 1 | 16 | 3 | 14.97 [13.67,15.17] | 71.06 [68.31,76.18] | 0 |
| 8 | 8 | 1 | 2 | 36.09 [29.66,56.91] | 124.30 [113.95,174.53] | 175 |
| 8 | 8 | 1 | 3 | 28.76 [28.11,29.84] | 156.39 [155.42,157.10] | 175 |
| 8 | 8 | 16 | 2 | 60.01 [59.29,61.79] | 253.78 [227.20,261.88] | 567 |
| 8 | 8 | 16 | 3 | 62.21 [58.43,77.64] | 353.66 [340.29,376.11] | 567 |
| 8 | 16 | 1 | 2 | 55.98 [49.71,112.22] | 198.51 [186.76,522.68] | 375 |
| 8 | 16 | 1 | 3 | 51.11 [45.88,102.98] | 547.21 [338.25,577.17] | 375 |
| 8 | 16 | 16 | 2 | 118.10 [116.47,363.33] | 460.45 [443.87,488.71] | 1215 |
| 8 | 16 | 16 | 3 | 113.81 [107.15,130.74] | 613.21 [601.85,631.14] | 1215 |
| 16 | 1 | 1 | 2 | 15.34 [14.42,17.18] | 39.67 [37.11,39.73] | 0 |
| 16 | 1 | 1 | 3 | 15.05 [11.68,15.24] | 50.84 [44.06,159.10] | 0 |
| 16 | 1 | 16 | 2 | 20.11 [19.76,20.75] | 63.56 [58.47,64.78] | 0 |
| 16 | 1 | 16 | 3 | 21.26 [19.36,22.18] | 143.04 [88.76,144.57] | 0 |
| 16 | 8 | 1 | 2 | 33.21 [32.32,34.40] | 450.19 [135.17,457.42] | 175 |
| 16 | 8 | 1 | 3 | 33.34 [30.77,33.83] | 157.00 [155.98,208.79] | 175 |
| 16 | 8 | 16 | 2 | 92.59 [90.85,306.28] | 327.94 [323.10,375.95] | 1183 |
| 16 | 8 | 16 | 3 | 98.69 [95.30,99.78] | 485.55 [473.65,491.92] | 1183 |
| 16 | 16 | 1 | 2 | 51.71 [49.21,59.52] | 194.77 [194.65,203.43] | 375 |
| 16 | 16 | 1 | 3 | 54.68 [52.30,168.46] | 369.47 [349.15,568.59] | 375 |
| 16 | 16 | 16 | 2 | 174.26 [172.90,177.45] | 623.07 [615.87,626.01] | 2535 |
| 16 | 16 | 16 | 3 | 175.95 [175.13,214.29] | 895.49 [868.88,913.30] | 2535 |

## Export-only HSMM stage receipt

An export-only JVM patch was applied to a clean standalone clone pinned at
`90ebaa8f48dd45f70db13bcccc67dda1c0020234`; it does not alter the library checkout. The
patch SHA-256 is `4b45740293b93519799c7cec730b9d61ea978171d7617a21dd761ffad478dd24` and the
application script SHA-256 is `994c49ffe734e74f7dcd75b082e57443fef773d57104ed1d1d603e5d3d669d69`.
The script refuses the primary checkout, descendants, linked or subdirectory worktrees, dirty
clones, HEAD/discovery failures, input hash mismatches, and reapplication. Dry apply and apply
were both observed before compiling the isolated patched clone.

The ordinary direct 1 GiB JVM capture took 140.99 s; the instrumented timing capture took
153.43 s. They have 36 cells and 108 measured repetitions, and every canonical member/export
parity witness is identical between the two captures. The stage receipt contains 3,564 rows for
324 inference invocations. For every invocation there is exactly one transition-matrix and one
Viterbi row; forward/backward phases have equal pass counts, retained-flow rows carry a
nonnegative logical count, and every child interval is enclosed by its full-run interval.
The largest reported retained-flow count is 2,535, matching the largest adjacent state-product
coordinate in this matrix.

The machine-readable aggregate is
[mapping-hsmm-profile-20261020.json](mapping-hsmm-profile-20261020.json). It summarizes the
raw timing and separate sampler logs while retaining the qualification boundary below.

| Recorded phase | Rows | Median ms | Total ms | Thread allocation MiB |
|---|---:|---:|---:|---:|
| full run | 324 | 0.332 | 225.668 | 298.0 |
| transition matrix | 324 | 0.049 | 96.583 | 164.5 |
| emissions | 324 | 0.013 | 4.751 | 4.4 |
| forward alpha | 324 | 0.038 | 17.644 | 16.2 |
| backward beta | 324 | 0.035 | 18.747 | 16.3 |
| posterior rows | 324 | 0.015 | 5.384 | 3.9 |
| transition flow | 324 | 0.067 | 33.426 | 37.8 |
| Viterbi | 324 | 0.056 | 24.156 | 36.6 |

These are recorder stage totals, so they overlap through the full-run enclosing interval and must
not be summed as wall-clock time. The all-zero refinement row is retained in the JSON: this
workload configured zero refinement passes.

The patch writes into fixed primitive arrays (capacity 4,096) during inference and constructs
rows only while draining after the timed work. It records timestamps and optional per-thread
allocated bytes. It does not time validation separately, inspect result values in a pair loop, or
make the capture a performance comparison: the observed 12.44 s command-level difference also
includes recorder overhead and host variation. The existing workload uses zero refinement passes,
so its refinement row is correctly absent rather than inferred from a synthetic extra case.

`-Dhsmm.audit.heapSampleMillis=10` enables a separate daemon sampler that reports a sampled
simultaneous maximum heap value and interval in the capture runtime receipt. It is deliberately
disabled for the timing run and is not a JVM pool-peak sum. The separate run observed
385,161,896 bytes over 18,450 samples at 10 ms. It has canonical parity with both timing runs.
No `time -l` OS RSS was collected for that sampler run. The ordinary and instrumented timing
runs reported 443,600,640 and 688,214,080 bytes respectively; those are distinct whole-process
command observations, not simultaneous heap measurements or a recorder-overhead estimate.

## Memory and qualification limits

The original public-only capture reports 682,664,832 bytes as the sum of cumulative JVM heap-pool peaks. Pool
peaks need not coincide: this is not a simultaneous heap peak or stage-local allocation.
Per-thread allocated-byte counters are available and retained for every operation; heap deltas
may include GC and are not allocations. OS `time -l` reports 2,444,132,352 bytes maximum RSS
for the whole command tree, including sbt/compiler/tests; it is not isolated capture RSS.
No portable per-stage/process-RSS measurement was available. A live owned-process snapshot
during the capture was about 801 MiB RSS; a snapshot is not a peak.

The public harness alone has no separately measured transition, forward/backward, Viterbi,
refinement or retained-flow boundary. The export-only recorder above supplies those timing cuts
for the pinned clone only; validation and encoding remain outside its cuts, and the adjacent-state
product remains a workload coordinate rather than a complexity proof. No portable process-RSS or
per-stage heap API exists. Reference-only comparison awaits G. These limits mean H has a
reproducible baseline and an isolated internal receipt, not a general operating-envelope claim.

The failed first compile (Java release compatibility) and first admission attempt are retained
in the adjacent raw logs. Repeated strings correctly refused the fixed `ProjectSyntheticControl`
basis. The final harness uses the existing checked synthetic Jane-to-Anna pseudonymization
pattern with explicit span maps and fixture keys; no admission guard or library policy changed.
No participant or gold data were read.

Observed envelope: these 36 small synthetic cells on this host, with warm filesystem caches and
one warmup, completed with deterministic outputs. It says nothing about longer recalls, wider
candidate budgets, providers, cold start, throughput, or other hardware. Concurrent unrelated
builds were present, so timing variation includes host contention. No acceptance threshold was
predeclared; do not reinterpret these maxima as a universal safe capacity.

Recommended next action: retain existing budgets and no optimization on this evidence alone.
If the owner wants a performance acceptance target, choose workload/latency/memory targets
explicitly before using this baseline as a regression gate. Separate internal instrumentation
and reference-only comparison remain acceptance work on existing H/G, not a new project.
