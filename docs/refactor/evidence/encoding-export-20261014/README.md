# Sherlock source03: reproducible encoding selection

This receipt establishes a new raw-array selection and the published annotation-index lookup.
It uses the admitted `sub-03` recording from ds001132 revision
`fc91edbbe1b8117f96b40bb4d1f5df43aa1afdce`. Physical scanner/media synchronization remains
unestablished, and the processing history of older derivative arrays remains unknown.

`export_encoding.py` verifies the exact released image and 348-byte header hashes, dimensions,
datatype, endianness, scaling, temporal units, voxel offset and complete payload length. It copies
original int16 volume bytes into new arrays with raw index ranges `[20,966)` and `[20,1050)`.
The outputs contain 946 and 1,030 volumes, respectively. Each time row contains 110,592 voxels
in original order: `x + 64*(y + 64*z)`. The original spatial header accompanies each array.
The selection receipt records every original index; no resampling, normalization, interpolation
or additional hemodynamic shift is applied. Upstream preprocessing and censoring remain unknown.

The [independent readback](actual-readback.json) compares **every output voxel byte** with the
corresponding original image payload. It separately reconstructs segment memberships from the
already pinned original TSV, without importing the export producer. All 1,000 original segment
IDs survive, including unavailable segments 481–482. The 998 numeric intervals produce 2,065
memberships across all 1,976 retained volumes; 89 volumes have two memberships. Ten injected
index, inventory, ordering, length and byte-corruption cases are rejected.

The authors' [TR expansion example](https://raw.githubusercontent.com/Summer-MIND/mind_2018/77e8ee9102837b921883e033b72e246fc5e3c535/tutorials/sherlock_nifti_kit_v2_withdata/code/labels_example_sherlock_segments.m)
uses inclusive start/end TR indices and averages feature values across overlapping memberships,
then normalizes each predictor. [Its exact pin](predictor-source.json) supplements the existing
[timing-source inventory](../../../data/sherlock/encoding-timing-20261014/sources.json).
This export preserves membership relations and applies neither of those predictor operations.
Membership is not a probability allocation or independent evidence of physical synchronization.

The two generated `scanner-samples-job/v0.1` files bind the actual new array hashes and selection
receipts to original scanner sample indices. `scannerSamples` checks the declarations and emits
the existing typed format; the separate byte reader supplies the array evidence that this CLI
does not inspect. The first retained volume remains at raw scanner-relative time 30 seconds.
It is not relabeled as an observed physical movie onset.

Both public `scannerSamples` commands completed at producer `0a3a9777`.
The [separate scanner reader](scanner-readback.json) checks every emitted sample index and
exact rational time, dropped-volume inventory, unchanged unknown censoring/history, array
binding and unavailable physical synchronization. It rejects ten additional altered-output
cases. The command log and its real exit metadata are retained beside this receipt.

## Reproduce

Use the exact workbook supplied by the owner (SHA-256
`5271b7704ccebfc7cae2228c78faf472c0ba5530c1a45e4c784757057bf476df`) and mounted admitted release.
Output paths must be new. Arrays, source headers and participant-derived outputs remain in
ignored local storage. Run from the repository root:

```sh
python3 docs/refactor/evidence/encoding-export-20261014/export_encoding.py \
  DATASET_ROOT WORKBOOK.xlsx \
  docs/data/sherlock/openneuro-resume-20261013/verification.json \
  docs/data/sherlock/encoding-timing-20261014/crosswalk.json NEW_OUTPUT
python3 docs/refactor/evidence/encoding-export-20261014/verify_encoding.py \
  DATASET_ROOT data/sherlock/Sherlock_Segments_1000_NN_2017.tsv NEW_OUTPUT NEW_RECEIPT.json
sbt 'pipeline/runMain storymodel4s.pipeline.scannerSamples NEW_OUTPUT/sherlockPart1-scanner-job.json NEW_OUTPUT/part1-scanner.json' \
    'pipeline/runMain storymodel4s.pipeline.scannerSamples NEW_OUTPUT/sherlockPart2-scanner-job.json NEW_OUTPUT/part2-scanner.json'
python3 docs/refactor/evidence/encoding-export-20261014/verify_scanner_outputs.py \
  NEW_OUTPUT NEW_SCANNER_RECEIPT.json
```

The indexing and byte checks support reproducibility of these new arrays. They do not establish
preprocessed analysis readiness, the historical predictor matrix, calibrated neural timing or
the missing participant-specific trigger/presentation relation. The broader imaging acceptance
remains open on that physical timing requirement.
