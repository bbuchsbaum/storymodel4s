# Raw study inputs through construction and analysis

`prepare_construction(key, side, raw_bytes, ...)` prepares either exact UTF-8 recall text or
versioned source-annotation JSON. The result retains actual input material, not only a digest.
Its `.request` derives an existing rich construction request. Public consumers recompute the
raw-to-derived relationship and reject copied or edited receipts that no longer agree.

Recall preparation reuses the existing canonicalization and segmentation contract. Original
occurrence supports and separators stay in the intake receipt. Construction rows extend each
occurrence to the next occurrence's start, covering the initial/final separators as well.
Coverage is bookkeeping; graph claims still require exact quoted evidence. This does not
declare separator text semantically extracted or change occurrence identity.

Source annotations declare parts and rows with explicit ordinals. The document follows part
order and then row order, regardless of JSON array order or local clock resets. Rows have
stable keys and exact UTF-16 spans; optional native timing remains attached to the declared
part and timebase. No global elapsed timeline is inferred from text concatenation.

## Recording and compatibility

`PreparedConstructionRecording` binds the entire prepared input. Use
`prepared_construction_prompt`, `import_prepared_construction`, and
`replay_prepared_construction` for the offline recording path. Import persists raw replies
before parsing and refuses an existing recording directory. Interrupted-import recovery is
still a separate pending capability.

Different raw inputs can produce the same canonical document and rich request. They still
produce different prepared digests and cannot share prepared recordings. Ordinary rich
recordings are not silently promoted to intake-bound recordings. Existing rich v1/v2 requests,
prompts, receipts and replay remain unchanged.

`PreparedConstructionResult.construction` is the original rich resolver receipt.
`.account` preserves its graph/document identities while adding the prepared-input and inner
receipt digests to the derivation recipe. `verify_prepared_construction` recomputes both from
the actual prepared input and raw reply. Merely parsing a result is not verification of the
original proposal or intake.

Analysis manifests opt into `storymodel.analysis-plan/v2` and use account input kind
`prepared-rich`. Saved replay retains the prepared input and outer receipt, pairs the
provenance-bound accounts, and enforces the existing full-snapshot stage continuity. V1 plans
refuse the new input kind. Downstream details, mapping, fidelity and views reuse their
existing implementations.

## Executable example

From `port/`, with the package installed:

```sh
python examples/prepared_workflow.py data/prepared-example
storymodel replay-analysis data/prepared-example/analysis-1.json --output data/analysis-1.json
storymodel verify-analysis data/prepared-example/analysis-1.json data/analysis-1.json
```

The example uses abstract token controls, one source and two recalls. It checks declared
source ordering, identical canonical recall text with different raw bytes, an admitted grade,
and a refused citation that leaves grading pending. The output directory must be new.

This generic source-annotation profile is not yet the retained Sherlock TSV or word-timestamp
CSV adapter. Study-specific metadata, measured recall timing and their byte-lineage rules
remain explicit subsequent work. Scripted controls prove engineering contracts, not semantic
extraction accuracy, study validity or complete P2/P5 coverage.
