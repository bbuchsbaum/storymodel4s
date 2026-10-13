# Passport resume: development input and recall coverage

The remounted Passport removes the data-access blocker. At release `1.0.0`,
revision `fc91edbbe1b8117f96b40bb4d1f5df43aa1afdce`, all ten selected dataset
files match the September acquisition receipt: the README and the three
development `sub-03` images, event files and task sidecars. Both local Sherlock
parts also match the recorded presentation-edition SHA-256 and byte length.
See [verification.json](verification.json). Compressed image bytes were hashed;
only NIfTI headers were decompressed. No voxel values were decoded.

## Released recall clock

The [timestamp release paper](https://pmc.ncbi.nlm.nih.gov/articles/PMC10460947/)
(Born et al., DOI `10.1016/j.dib.2023.109490`) supplies the missing public identity
evidence: Table 1 maps canonical source 03 to OpenNeuro subject 03; Table 3
assigns columns E-G to OpenNeuro. Methods specifies scan-relative word onsets,
one-based TR labels and no hemodynamic adjustment. The source and convenience
alias bytes are bound by the existing lineage and alias-map hashes.

[recall-coverage.json](recall-coverage.json) checks those released columns against
the actual 716-volume, 1.5-second raw run. Its authority is the published release
definition, not a new measurement of scanner/audio synchronization. The resulting
zero-based index is the released TR number minus one. Exact half-open TR bins
are declared sampling supports; they are not measured slice-exposure windows.

- 2,495 requested CSV records; 2,489 assigned to existing raw bins.
- Six records (2490-2495) lie outside `[0,1074)` seconds: their onsets are
  1074.4-1075.9 seconds, with requested raw indices 716-717.
- 624 raw bins contain at least one released word onset. This does not mean
  other bins are censored or unusable.
- Every row's released TR onset and word containment agree exactly; no onset is
  missing. Five boundary controls pass and five contradictory inputs refuse.

The six unavailable rows remain in the numeric ledger. No corrective offset,
clamp, duration imputation or HRF shift was applied. These are CSV record
coordinates, not canonical mapping-word IDs or semantic weights. The generic
scanner API still labels its declarations as not independently verified; this
evidence does not change that API authority.

## Crop prescriptions and unresolved encoding origin

The pinned README prescribes these crop-only maps for `sub-03`. They have not
been applied to any image array by this run:

| Run | Beginning/end crop | Result length | Zero-based selected-to-raw index |
| --- | ---: | ---: | --- |
| Part 1 | 20 / 6 | 946 | `raw = selected + 20` |
| Part 2 | 20 / 7 | 1030 | `raw = selected + 20` |
| Recall | 3 / 0 | 713 | `raw = selected + 3` |

The recall crop must not be added to the already scan-relative OpenNeuro word
columns. An existing derivative's applied history remains unknown until its
own receipt is inspected.

The movie event files report onset zero, whereas crop index zero selects raw
time 30 seconds. Those facts alone do not establish the physical full-media
origin or identify any lag embedded in the scene-analysis convention. The
README's first scenes cover TRs 1-26 in part 1 and 1-25 in part 2; the latter
next starts at TR 27. A nominal introduction duration cannot resolve this join.

The [original methods](https://hassonlab.princeton.edu/document/361), page 12,
describe retained introductory cartoons and synchronized presentation. A
participant-specific trigger/file record or documented original raw-to-scene
transformation is still needed to distinguish physical onset from analysis
shift. No such presentation log/code was found in the pinned dataset tree.
Recover that primary record, then check multiple events across both exact media
parts. Do not fit an offset merely to force the available durations to agree.

Thus the BIDS and full-journey parent beads remain open. This slice establishes
restored input access, byte/header identity, and released recall-clock coverage;
it does not complete encoding weights or the actual Sherlock A+V journey.

## Reproduce

Use new output paths; both scripts refuse an existing output. From the repo root:

```sh
python3 docs/data/sherlock/openneuro-resume-20261013/verify.py \
  '/Volumes/My Passport for Mac/data/openneuro/ds001132-1.0.0' tmp \
  /tmp/sherlock-input-recheck.json
python3 docs/data/sherlock/openneuro-resume-20261013/recall_coverage.py \
  tmp/recall_zenodo/sub-03_recall_final.csv /tmp/sherlock-input-recheck.json \
  /tmp/sherlock-recall-coverage.json
```

The fresh timestamp changes the recheck digest, which the coverage receipt
explicitly binds. No participant prose or image payload is copied into this
directory. These bounded evidence scripts are not a general BIDS adapter.
