# Mapping workload capture — 20 October 2026

This is the reporting template for Mote `bd-01M5DAK5ED2W3HW3XDS3MZV5PS`. It records a bounded
baseline before any candidate budget, backend, or optimization change. Fill it from the exact
`MAPPING_WORKLOAD_CAPTURE` JSON line; do not infer a safe envelope from an unrun capture.

## Capture command

```sh
sbt 'pipeline/Test/runMain storymodel4s.pipeline.MappingWorkloadCapture'
```

The harness uses only generated Anna/Bob text with genuine checked synthetic Jane-to-Anna
pseudonymization witnesses (the existing multipart test pattern). It preserves the fixed
`ProjectSyntheticControl` admission and all disclosure guards. It keeps
source and recall dimensions at or below 16 units, runs one warmup and three recorded repetitions,
and covers `perLevel = 1`, tie budgets 1 and 16, and two requested weighted-member sets:
reference/reconstruction endpoints and reference/`1/2`/reconstruction.
It is JVM-only because heap and optional per-thread allocation observations come from the JVM.

## Receipt

| Field | Captured value |
|---|---|
| Git revision | _pending capture_ |
| Command and exit | _pending capture_ |
| Host / OS | _pending capture_ |
| Java version / VM | _pending capture_ |
| Warmups / repetitions | `1 / 3` |
| Source and recall input hashes | _from capture_ |
| Mapping and weighted manifests | _from capture_ |
| Deterministic parity | _from capture; all three witnesses must match_ |

## Per-cell results

Copy each cell's three repetitions, retaining the timing range or variation rather than only a
best value. Record heap before/after/delta, optional thread allocation, paired and weighted artifact
bytes, and `stateProductsAdjacent = sum_i S_i * S_(i+1)`.

| Source units | Recall units | Tie budget | Weighted members | Preparation | Nomination | Local pricing | Paired lexical / inference | Weighted selection | Paired export | Weighted export | Scored / nominated / retained | Adjacent-state products | Heap / allocation | Artifact bytes | Parity |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---|---|---|
| _ | _ | _ | _ | _ | _ | _ | _ | _ | _ | _ | _ | _ | _ | _ | _ |

## Measurement boundary and limits

`StudyPreparation.prepare`, `StrictCandidateGenerator.canonicalLevel`, `LocalEvidence.compute`,
`MappingPipeline.lexical`, `MappingRun.Execution.weighted`, `MappingPipeline.publishExchange`, and
`WeightedMapping.publish` are the measured public operations. Candidate nomination and local
pricing are retained separately, including source-level scored candidates, nominated anchors, and
retained states. The
lexical call deliberately includes local reference and reconstruction because the public facade
does not provide stable timing boundaries for forward/backward, Viterbi, and refinement.
These are overlapping public operations, not additive stage partitions: the paired lexical call
repeats nomination/pricing, and weighted publication repeats weighted inference before encoding.
Observed scores, ineligible candidates and missing scores are separate counters; considered
candidates include all three and are not labelled as successfully scored candidates.

Heap deltas and per-thread allocation are observations around a stage, not stage-local peak memory.
The reported heap peak is JVM-cumulative and explicitly not attributed to a stage. Process RSS is
`Unavailable` when no portable JVM API provides it. The harness must report that state rather than
substitute whole-process RSS as a stage measurement.

## Interpretation

- Observed bounded envelope: _pending capture_.
- Refused or overflow cases: _pending capture_.
- Recommended next action: _pending capture; may be no optimization_.
- Resource target proposal for owner choice: _pending capture; no universal limit is asserted_.

This template is a baseline receipt. It does not claim a speedup, a production capacity limit, or
separate costs for unavailable internal operations.
