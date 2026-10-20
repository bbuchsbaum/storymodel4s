# Selected mapping policies and reference-only execution

Owner-approved ADR: [0019, 20 October amendment](../adr/0019-mapping-measurement-policy.md).
Mote children E, F and G belong to `mapping-audit-20261020`.

The ordinary `mapping run` and `mapping exchange` commands accept the existing settings with
`"mode":"reference-only"` added. Omit mode or use `"mode":"paired"` for the historical journey.
The source and recall admission, sensitive ONNX key handling, deterministic preparation,
completion-marker publication and resume/corruption checks remain those of the ordinary facade.
Reference-only execution never creates a reconstruction grouping, reconstruction profile or
HSMM result. Reconstruction settings in reference-only mode refuse explicitly.

```json
{
  "mode": "reference-only",
  "profile": "mapping-lexical-pair/v1",
  "temperature": "1.0",
  "perLevel": 20,
  "tieBudget": 200,
  "requested": ["Mapping"],
  "networkRequests": 0,
  "retries": 0
}
```

The profile string retains the established lexical/caption/ONNX registration family. The mode
selects execution; it never manufactures a paired result. The typed entries are
`MappingPipeline.referenceLexical`, `referenceCaptions`, `referenceOnnx`, `referenceBatch`,
`referenceBatchOnnx` and `analyzeReference`. A `ReferenceRun` exposes only `reference` and
`MappingRun.ReferenceExecution`. `MappingRun.computeReference` consumes original checked
inputs and executes only `LocalReference.compute`; it grants no facade registration.

Optional settings select a complete source cut before nomination and pricing:

```json
"selection": {
  "version": "mapping-selection/v1",
  "level": 1,
  "reconstruction": null,
  "grouping": null
}
```

In paired mode, `reconstruction` may instead contain `temperature` (JSON number), `order`
(`Unconstrained`, `Nondecreasing` or `PenalizedBackward`), `backwardPenalty` and `forwardPenalty`.
These reuse checked `HsmmConfig` defaults and `StructuredReconstruction.DecisionPolicy`;
penalties incompatible with the selected order refuse. `grouping` may contain objects with
`root` (`sit:...` or `seg:...`) and integer `rank`. Roots/ranks are checked against the actual
source; they may not overlap. Explicit fill requests are outside this journey. The typed Config
also accepts an existing checked reconstruction profile. Weighted v1 requires exact no-fill
Sherlock; a different reconstruction profile refuses rather than silently changing the sweep.

Selection changes bind versioned config identities. Existing default config text, registrations,
paired execution/record identities and exact endpoint object dispatch retain their original
representation. A selected cut is never a subset filtered from already-priced evidence.
Caption production and ONNX registration retain their selected level, including unavailable
comparisons. Missing content stays unavailable; changing resolution does not grant a scoring
capability.

Reference records use `mapping-record/reference/v1` and one-member exchanges use
`mapping-exchange/reference/v1`. The legacy decoder refuses these versions. Replay requires
`ExpectedMappingContext(referenceExecution = Some(originalProducer))` with original inventory
and source and the explicit `MappingCodecs.decodeReference` or
`MappingExchange.decodeReference` entry. Supplying paired and reference authority together
refuses. Replay compares the entire canonical producer record. A wire checksum does not create
execution or facade origin; self-contained import of local evidence is outside this contract.

Batch publication uses `mapping-batch/reference/v1` or
`mapping-exchange-batch/reference/v1`. `mapping-summary/reference/v1` retains every attempt,
known and unknown inventories, available and unavailable unit rows and diagnostic counts,
with only a reference member. No reconstruction placeholders occur. Organization and order
remain explicit missing requested CLI capabilities. The typed `referenceOrganization` entry
certifies genuine registered origins over original-unit adjacencies;
`temporal(ReferenceRun, request, timing)` compiles the checked reference record. Base exchange
capabilities truthfully report that these optional readouts were not supplied to that export.

Independent readback:

```sh
python3 examples/mapping/read_reference_mapping.py OUTPUT_DIRECTORY
```

This checks publication hashes, TSV projections, original inventory/source joins, stage/execution
joins and summary denominators. It is an integrity reader, not a replacement for Scala contextual
admission. The paired reader refuses this new publication version. Scientific equality with the
paired reference member means equal `LocalReference.Outcome` values (including costs, mass,
information and diagnostics), not equal execution-specific receipt IDs or record bytes.
Structural compatibility establishes neither calibrated correctness nor empirical fidelity
accuracy or scientific efficacy.
