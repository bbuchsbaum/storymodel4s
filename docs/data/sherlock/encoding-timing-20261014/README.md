# Sherlock released scene-analysis clock

The primary authors' tutorial resolves the published analysis convention. Its
README says the initial 20-volume movie crop includes hemodynamic adjustment;
the scene loader uses the already-cropped series without another shift. The
[author's dataset page](https://jchenlab.johnshopkins.edu/) identifies this
[tutorial](https://github.com/Summer-MIND/mind_2018/tree/77e8ee9102837b921883e033b72e246fc5e3c535/tutorials/sherlock_nifti_kit_v2_withdata)
as Sherlock analysis code. `sources.json` pins seven primary files by revision,
SHA-256 and byte length; the source payloads remain in ignored local storage.

For the already admitted development recording `sub-03`, scene one-based index
`s` maps to raw zero-based index `s - 1 + 20`. An inclusive scene `[s,e]` selects
raw half-open indices `[s-1+20,e+20)`. Scene TR 27 therefore maps to raw index 46
(raw one-based TR 47). Adding three more volumes would double-count the reported
lag. Concatenated part-2 annotation indices first subtract the documented 946
part-1 volumes. Annotation seconds and rounded scene indices remain separate
coordinates.

`qualify_timing.py` compared all 50 primary MAT scene intervals against the
pinned OpenNeuro README and checked seven boundary examples plus six refusals.
The release's 946/1030-volume selections contain 27/31 indices without scene
labels. `crosswalk.json` preserves those gaps and every interval; it neither
bridges them nor applies a crop to an image. The 50-scene and 1000-segment
annotation editions remain distinct.

The fixed media probe selected nine annotation landmarks before decoding.
Six contained a nearby scene-score-above-0.3 cut within the fixed six-second window, with
signed nearest-cut residuals from -1.28 to +0.36 seconds. Three part-2 probes
(39, 475, 1500 seconds) had no such cut; all are retained in `landmarks.json`.
A post-hoc visual followup found nearby transitions at two misses and a
continuous changing shot at the other. This followup is excluded from the six
successful detector probes. No offset was fitted. These checks are consistent
with the exact media edition; nearby cuts alone cannot verify semantic identity
or scanner synchronization. Private film frames are not included here.

The physical full-media origin **25.5 seconds is a conditional inference**, from
`20*1.5 - 3*1.5`, assuming the crop separates into 17 calibration volumes and
three lag volumes and media zero matches annotation zero. No participant trigger
or presentation record was recovered. The published-analysis crosswalk can be
used with that authority; physical synchronization and an existing derivative's
actual preprocessing history remain unverified. Released scan-relative recall
word times receive no additional shift.

## Reproduce

Download the seven exact URLs in `sources.json` to a private directory, keeping
the recorded basenames. With SciPy available, run:

```sh
python3 qualify_timing.py DATASET_ROOT PRIMARY_SOURCE_DIRECTORY \
  ../openneuro-resume-20261013/verification.json NEW_CROSSWALK_JSON
python3 landmark_probe.py landmark-plan.json MEDIA_DIRECTORY NEW_PROBE_DIRECTORY
```

Both commands refuse replacement outputs. `crosswalk.json` binds the prior
actual-byte/header receipt; this investigation did not decode fMRI voxel arrays.
