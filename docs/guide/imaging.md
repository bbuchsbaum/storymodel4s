# Read recall and encoding exports on published sample indices

This walkthrough uses the already admitted Sherlock development participant `source03` /
OpenNeuro `sub-03`, release `ds001132` revision
`fc91edbbe1b8117f96b40bb4d1f5df43aa1afdce`. It connects two reproducible outputs: recall-word
onsets on the raw recall sampling grid, and newly selected encoding volumes using the published
beginning/end crops. The OpenNeuro README is the available crop authority. The owner confirmed
that finer presentation-trigger records are unavailable; physical movie/scanner synchronization
therefore stays unavailable.

## Keep the clocks separate

| Coordinate | Convention used here |
| --- | --- |
| Recall CSV | Released OpenNeuro scan-relative onset seconds; no extra crop or HRF shift |
| Raw recall samples | 716 stored volumes, zero-based indices, TR 1.5 seconds; half-open bins |
| Encoding part 1 | Raw indices `[20,966)`, retaining 946 volumes after beginning/end crops 20/6 |
| Encoding part 2 | Raw indices `[20,1050)`, retaining 1,030 volumes after crops 20/7 |
| Published annotation TR | One-based, inclusive intervals; part 2 first removes the 946-volume concatenation prefix |
| Physical movie-to-scanner transform | Unestablished |

For a part-local published TR `s`, the raw zero-based index is `s - 1 + 20`. The published
20-volume crop already includes the reported hemodynamic adjustment. Adding another three-volume
shift would apply it twice. Annotation seconds, annotation TR numbers and raw sample times remain
separate fields. The first selected encoding volume remains at raw sample time 30 seconds.

## Export and verify recall onsets

Use the admitted input directory described in the
[recall receipt](../refactor/evidence/recall-volumes-20261013/README.md). Its job binds the study,
mapping settings, original CSV, recording identity and supporting release evidence by checksum.
Keep participant data and derived outputs in private local storage. From the repository root:

```sh
storymodel4s recallVolumes /absolute/input/job.json /new/recall-output
python3 examples/mapping/read_recall_volumes.py /new/recall-output \
  --csv /absolute/input/recall-source-03.csv
```

With the source checkout, replace the command prefix with
`sbt 'pipeline/runMain storymodel4s.pipeline.recallVolumes ...'`. An existing destination refuses;
use a new path. The independent reader reconstructs the join from the original CSV and checks the
paired mapping package. The qualified development output retains all 2,495 records: 2,489 fall
inside raw coverage and six remain outside. It preserves all 173 inference units and 94 words
without unit membership.

`onset-attachments.json` links a sample to original inference units. A unit can appear beside
several samples, so these links are **non-additive references**. Read each unit's original
measure once from the paired exchange. Onsets alone do not establish duration or a distribution
of semantic mass across volumes; `temporal_mass_allocation` remains unavailable.

## Create encoding arrays with a known history

The export script checks the released image bytes and NIfTI headers before copying volumes.
Use the owner-supplied workbook and the exact admitted dataset; output paths must be new:

```sh
python3 docs/refactor/evidence/encoding-export-20261014/export_encoding.py \
  "$DATASET_ROOT" "$SHERLOCK_WORKBOOK" \
  docs/data/sherlock/openneuro-resume-20261013/verification.json \
  docs/data/sherlock/encoding-timing-20261014/crosswalk.json /new/encoding-output
python3 docs/refactor/evidence/encoding-export-20261014/verify_encoding.py \
  "$DATASET_ROOT" data/sherlock/Sherlock_Segments_1000_NN_2017.tsv \
  /new/encoding-output /new/encoding-readback.json
storymodel4s scannerSamples /new/encoding-output/sherlockPart1-scanner-job.json \
  /new/encoding-output/part1-scanner.json
storymodel4s scannerSamples /new/encoding-output/sherlockPart2-scanner-job.json \
  /new/encoding-output/part2-scanner.json
python3 docs/refactor/evidence/encoding-export-20261014/verify_scanner_outputs.py \
  /new/encoding-output /new/scanner-readback.json
```

Set `DATASET_ROOT` and `SHERLOCK_WORKBOOK` to their local paths. The scripts preserve every raw
volume index, array checksum and original spatial header. Each array is little-endian int16,
ordered as time × voxel, with 110,592 voxels per volume. There is no resampling, normalization,
interpolation or additional lag shift. Prior upstream censoring/preprocessing remains unknown.
These arrays are reproducible selections, not a claim that preprocessing for model fitting is complete.

For read-only access with NumPy:

```python
import json
from pathlib import Path
import numpy as np

root = Path('/new/encoding-output')
selection = json.loads((root / 'sherlockPart1-selection.json').read_text())
volumes = np.memmap(root / selection['selected_array'], mode='r', dtype='<i2',
                    shape=tuple(selection['shape_time_voxel']))
assert volumes.shape == (946, 110592)
assert selection['stored_indices'][0] == 20
```

The original voxel order is `x + 64*(y + 64*z)` for dimensions `(64,64,27)`; the copied NIfTI
header retains the spatial reference. `memberships.tsv` records inclusive annotation-TR
membership. All 1,000 segments remain present, including two without numeric TR assignments.
There are 2,065 memberships across 1,976 selected volumes; 89 volumes belong to two segments.
Overlapping memberships are relations, not probabilities. Predictor averaging, normalization,
HRF modeling and neural fitting are separate explicit analysis steps.

## Inspect truncation and timing sensitivity

This reader verifies the recall package again, then compares the original unit measures with
top-one and top-three destination truncation. It also reports the consequences of illustrative
minus/plus one-TR shifts, without applying a shift or choosing one from neural outcomes:

```sh
python3 docs/refactor/evidence/encoding-export-20261014/imaging_walkthrough.py \
  /new/recall-output /absolute/input/recall-source-03.csv /new/walkthrough.json
```

For the qualified lexical development run:

| Member | Units with a measure / original units | Supplied quantity retained by top 1 | By top 3 |
| --- | ---: | ---: | ---: |
| Reference, normalized score mass | 151 / 173 | 4.08% | 11.89% |
| Reconstruction, model posterior | 173 / 173 | 10.47% | 26.49% |

These are summaries of supplied model quantities, not accuracy scores. Each original measured
unit contributes once. The 22 reference units without a measure stay separate, and omitted
quantity is reported without redistribution. Turning a selected destination into a unit mass
would change the estimand.

The unshifted recall grid has six outside records. The illustrative −1.5-second shift gives
four outside records; +1.5 seconds gives thirteen. All 2,495 records remain accounted for in
each scenario. This sensitivity check provides no evidence for either offset and does not
alter the released clock. The [machine-readable readback](../refactor/evidence/encoding-export-20261014/walkthrough-readback.json)
binds the exact inputs, all quantities and counts. Fine timing, onset-only duration allocation
and physical movie/scanner synchronization remain unavailable throughout.
