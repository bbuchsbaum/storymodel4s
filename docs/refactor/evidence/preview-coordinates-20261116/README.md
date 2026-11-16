# Supplied and automatic segmentation on common transcript coordinates

This is an offline development comparison, executed on 2026-11-16. It separates
boundary disagreement from mapping error. It uses the eleven declared Sherlock
development sessions and the nineteen FilmFestival sessions already used by the
annotation-assisted run. No protected Sherlock recall was read and no provider
was called. The [aggregate](aggregate.json) contains counts only.

| Corpus | Supplied units | Automatic units | Uniquely aligned supplied units | Unmatched supplied units |
|---|---:|---:|---:|---:|
| Sherlock | 1,744 | 1,744 | 1,744 | 0 |
| FilmFestival | 3,226 | 5,918 | 3,157 | 69 |

| Corpus | Matched internal boundaries | Supplied-only boundaries | Automatic-only boundaries | Excluded supplied boundaries |
|---|---:|---:|---:|---:|
| Sherlock | 1,733 | 0 | 0 | 0 |
| FilmFestival | 2,797 | 284 | 2,889 | 126 |

The Sherlock supplied units were themselves produced by the existing baseline;
their agreement is a reproducibility result, not agreement with human segmentation.
FilmFestival's automatic segmentation is substantially finer than its supplied
utterances. These differences are not accuracy estimates: there is no independent
gold definition of the correct boundary in this comparison.

## Coordinates and exclusions

The transcript is replayed from a separately pinned released input, never from
the supplied unit list. Sherlock uses its original word CSV and the existing
`sherlock-word-csv-replay/v1` rule. Every development CSV hash, canonical transcript
hash, supplied ordinal and supplied-text hash matches the frozen unit-lineage
manifest: eleven sessions and 1,744 units. FilmFestival uses its released recall
events TSV, replaying the `recall_transcript` cells in recorded order with one
space between nonempty cells (`filmfestival-event-text-replay/v1`). Its supplied
texts retain the existing packet's explicit whitespace-folding recipe. The source
files, supplied artifacts, manifests, automatic outputs and replay recipes are
checksummed in private receipts. The replayed text is a declared derived edition;
no recovery of unrecorded speech or original raw-text offsets is claimed.

The existing Scala `PreparedRecall.segmented` / `RecallSegmenter` produces the
automatic units and half-open canonical UTF-16 spans. The Python comparator
checks raw and canonical identity, recipes, exact slices, surrogate boundaries
and nonoverlap. It does not reimplement the segmenter or attest provider execution.

Supplied text is placed only when **every feasible ordered exact-match solution
agrees on its location**. Both preceding and following units constrain repeated
phrases. A match inside another word is refused. Absent units are explicitly
excluded; ambiguous matches are never replaced with the first match. An impossible
ordered sequence receives an order-conflict outcome. No ambiguous or order-conflict
outcomes occurred in this run.

Boundary positions are unit starts, measured only inside runs of at least two
consecutive uniquely aligned supplied units. The first/last exterior boundaries
and boundaries touching an excluded supplied unit are not scored. This excludes
126 FilmFestival supplied boundaries and does not score automatic cuts across
the missing material. Receipts retain the exact eligible domains and all unit
correspondences. Uncovered source alphanumeric characters are also retained as a
separate diagnostic (1,983 Sherlock; 7,430 FilmFestival), rather than silently
discarded or called mapping errors.

## Mapping and qualification boundary

Mapping error is **not assessed** here: there are no model outputs for this newly
prepared automatic-input condition. Previously reported scene scores belong to
their own input/prompt conditions. This result completes the common-coordinate
boundary comparison; it does not complete the preview's human measurement or
independent-usability requirements, establish detail fidelity, or qualify recall
order. Any subsequent mapping comparison must use its actual inputs and outputs.

## Reproduction

Keep all inputs and outputs under private ignored storage. From this checkout:

```sh
python3 docs/refactor/evidence/preview-coordinates-20261116/run.py prepare \
  --data-root /path/to/private/data --output /path/to/private/comparison
```

The existing dataset layout and the source dictionaries from the earlier slice5
replay are required. `commands.json` contains two `pipeline/runMain ...agentStudyPrepare`
arguments. Run those with sbt in a standalone checkout at the recorded runtime
revision; these are preparation commands and construct no provider client.

```sh
python3 docs/refactor/evidence/preview-coordinates-20261116/run.py compare \
  --output /path/to/private/comparison
python3 tools/agentmapping/test_compare_segmentations.py
python3 docs/refactor/evidence/preview-coordinates-20261116/mutate.py
```

The comparator also accepts an individual checksummed comparison manifest; see
`compare_segmentations.py --help`. Output paths must be new. The driver refuses
changed eligibility, source-dictionary or supplied-input artifacts.

Private run evidence is under `data/.preview-coordinates/20261116` and
`data/.preview-coordinates/20261116-verified`. The latter rechecks the finalized
driver and unit lineage using byte-identical saved Scala outputs and identical
study manifests. Its aggregate equals the first run. The first launch lacked
sandbox access to sbt's cache; another launch found a stale FilmFestival source
path after Sherlock succeeded. Those logs are retained. The corrected FilmFestival
launch completed with nineteen sessions and zero model calls.

Fifteen Python tests pass, including exhaustive small-sequence comparison against
independent enumeration. Two compiled mutations each fail their named assertion
while two sibling controls pass: accepting an ambiguous first occurrence, and
ignoring raw input identity. Runtime source code is unchanged by this slice.
