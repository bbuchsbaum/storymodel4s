# Recall onset attachments and original semantic measures

`recallVolumes` executes the public paired mapper for one admitted observation and
attaches its canonical word onsets to a declared regular raw scanner run. It preserves
both original mapping members, including unit weights, external destinations, missing
measures and their original normalization domains.

A volume-to-unit link is a lookup into those measures. It is **not additive mass**:
a unit that touches several volumes remains one inference unit. Onset-only observations
do not establish duration or a temporal allocation policy. The export reports
`temporal_mass_allocation: unavailable-onset-only` and complete/partial/absent unit
attachment counts. Do not sum original unit weights over these links.

## Run

```sh
sbt 'pipeline/runMain storymodel4s.pipeline.recallVolumes /absolute/job.json /new/output'
python3 examples/mapping/read_recall_volumes.py /new/output --csv /absolute/clock.csv
```

The output path must not exist. A completion-marker manifest binds every output file.
Job inputs use paths relative to the job file and exact SHA-256 pins. A study's own
source paths are relative to its study JSON directory. The job has these exact fields:

| Field | Meaning |
| --- | --- |
| `schemaVersion` | `recall-volume-job/v1` |
| `study`, `config`, `csv` | Objects with `path` and `sha256` |
| `participant`, `session` | Exact single study observation identity |
| `columns` | Exact `header` array, zero-based `word`/`onset` column indices, named `clock` |
| `run` | Existing scanner run declaration: key, image/header hashes, count, exact sample seconds, origin and applied history |
| `period` | Reduced rational seconds, string `numerator` and `denominator` |
| `recording` | Declared join described below |

`recording` contains the same `participant`, `session`, `csv_sha256`, `clock` and
`run_key`, plus `basis` (`published-release-definition` or `synthetic-control`) and a
nonempty `evidence` array of checked file pins. This records the authority supplied by
the job; it does not independently prove acquisition synchronization. The run must
start at its first stored sample and have times exactly `i * period`.

CSV replay must reproduce the complete canonical transcript and exactly one canonical
lexical word per nonblank cell. Punctuation may surround that word. Cells with zero or
multiple lexical words refuse, even when aggregate counts happen to agree. Every CSV
record is retained, including blank word cells; invalid numeric cells refuse.

## Output and interpretation

- Original paired mapping files and `mapping-exchange-manifest.json`: semantic measures.
- `recall-timing.json`: canonical word observations and timing provenance.
- `scanner-run.json`, `scanner-binding.json`: declared regular run and identity clock map.
- `onset-attachments.json`: every CSV record, distinct `(volume, unit)` links with their
  contributing word IDs, and every original unit's attachment counts.
- `manifest.json`: `recall-volume-export/v1`, all file pins and declaration metadata.

Canonical JSON omits absent fields. A record without a `volume` has an explicit
`timing_status`: `outside-stored-coverage` or `missing-onset`. Blank word cells have
`correspondence: excluded-blank-word` and no canonical word/unit. A timed but unassigned
canonical word retains its volume without inventing a unit membership.

For period `p` and count `N`, volume `i` has declared support `[i*p,(i+1)*p)`.
Negative onsets and onsets at or after `N*p` remain outside coverage. These are sampling
bins, not measured slice exposure windows. No crop, HRF shift or encoding clock binding
is applied. The independent Python reader reconstructs all attachments from the pinned
CSV and original inventory, checks the paired exchange and preserves its measures.

The admitted Sherlock source03 development run is documented in
[the qualification receipt](refactor/evidence/recall-volumes-20261013/README.md).
Its public release definition establishes the selected recall clock. The encoding
movie-to-scanner origin remains unresolved.
