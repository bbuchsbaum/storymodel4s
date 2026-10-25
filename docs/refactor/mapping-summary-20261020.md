# Mapping summary and endpoint interpretation

The ordinary summary uses `mapping-summary/v2`. Run and batch publication manifests,
including exchange wrappers, use v2 after adding descriptive coverage and model metadata.
Weighted batch wrappers use `weighted-mapping-batch/v2`. Mapping record, paired exchange
and weighted member formats and their numeric contents retain their existing versions.
An old publication cannot be resumed as v2: publication refuses a mismatched manifest.

`completed` counts produced runs. It does not count measured units. `coverage` reports
prepared observations, produced runs and observations with unknown unit counts. Each
participant/session observation retains its declared inventory count, failure, and original
units. A preparation failure has no unit denominator; it contributes an observation failure,
never a zero-unit measurement. Zero/unknown fractions are absent in canonical JSON.

Each member reports processing status, measure availability and unavailable reason separately.
Overflow, uniform semantic nomination and uniform measured costs are nonexclusive diagnostic
counts. A real uniform measurement remains measured. The only supplied aggregate is a pooled
fraction over known units, explicitly labelled; it is not an equally weighted participant
average. Unknown inventories remain counted alongside that denominator. No condition grouping,
gold candidate recall, eligibility filter or missing-unit bridge is inferred.

`models` comes from the actual executed reference and reconstruction objects. It exposes the
reference cut/universe, temperature and prior; reconstruction fingerprint, temperature,
decision/fill policy and grouping; and raw state maximum versus chosen target/mass/origin.
Normalized local cost mass is not calibrated correctness. Reconstruction is model-conditional.
Fidelity states describe implemented gate consequences; they do not establish validated factual
distortion detection. Structural admission and policy compatibility do not establish efficacy.

Weighted descriptors retain exact reduced fractions and actual dispatch. At `0/1`, the
reference record is returned unchanged; at `1/1`, the constrained reconstruction record is
returned unchanged. Interior records use raw argmax and the fixed 0.15 inference temperature.
An exact interior fraction remains interior even if its floating coefficient rounds to 1.0.
Reference temperature is separately configurable. Weighted v1 promises no endpoint continuity.

The executable `StructuralWeightSuite` equal-cost witness admits two source and five external
states for each of three units and enumerates all 343 paths. Equal reference mass gives total
source mass 2/7. Positive interior weights approaching zero give first-row mass 2/7 and later
source mass approaching 1/2: zero transition logits imply a sigmoid class probability of 1/2,
not the absence of an external prior. The hypothetical zero-coefficient interior limit is not
the public `0/1` endpoint. This is model arithmetic, not calibration or empirical utility.

For a synthetic command-to-summary example, `MappingPipelineSuite` prepares the admitted
Anna/Bob control plus a missing-file observation and runs the same facade as:

```sh
sbt 'pipeline/runMain storymodel4s.pipeline.mappingRun run STUDY.json CONFIG.json OUTPUT'
```

Its mixed control has two attempts, one preparation, one produced run and one unknown-unit
observation. The successful inventory has one unit: reference measured 1, unavailable 0;
reconstruction measured 1, unavailable 0. The missing-file observation has no declared unit
count. The overflow control retains one unavailable reference row and an explicit
`local-reference:TieOverflow` reason. The empty/all-failed controls have absent fractions.

Independent readback uses only Python's JSON/CSV readers on the synthetic log packages:

```sh
python3 tools/mapping-audit-readback.py PATH_TO_WEIGHTED_SUITE_LOG
```

It reconciles summary inventories and measured denominators with exported TSVs, checks
weighted fraction/kind/dispatch/temperature policy fields, and refuses deliberately dropped
observations and contradictory dispatch metadata. It checks descriptive consistency and grants
no producer authority. Required exact-SHA review, full scoped platform gates, consumer checks
and research acceptance remain separate evidence.
