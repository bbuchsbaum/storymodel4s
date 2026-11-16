# Prepare ordinary recall transcripts

`agentStudyPrepare` takes UTF-8 transcript files, a timed source CSV, the source's parts JSON,
and a small study manifest. It segments each transcript locally and writes an export for
[`agentStudyExecute`](live-study-execution.md). Preparation makes no model calls.

Keep the manifest, transcripts, export, recordings and bundles in private ignored storage.
The export contains raw recall text; automatic-input bundles contain canonical recall text.
Preparing a file does not establish permission to redistribute it or send it to a provider.

## Prepare files

Use the existing timed-annotation CSV and parts formats described in
[the recorded-pipeline design](../plans/2026-11-10-common-recorded-pipeline-design.md#source).
The source must already identify its scenes and, optionally, collections of scenes (films).

Create a manifest like this beside your transcript files:

```json
{
  "schema": "storymodel4s.agentmapping.transcript-study/v1",
  "model": "MODEL_ID",
  "condition": "transcript-baseline",
  "chunkSize": 50,
  "sessions": [
    {"participant": "p01", "session": "recall", "transcript": "p01.txt"}
  ]
}
```

Transcript paths resolve relative to the manifest. Participant, session and condition names
use letters, digits, underscores and hyphens, starting with a letter or digit. Each
participant-session pair must be unique. `chunkSize` is a positive number of units to answer
per request; each prompt still shows the whole transcript and source dictionary for context.
Large studies must therefore choose a model whose context can hold those inputs.

From a standalone checkout:

```text
sbt "pipeline/runMain storymodel4s.pipeline.agentmapping.agentStudyPrepare SOURCE.csv PARTS.json MANIFEST.json EXPORT_DIR"
```

All inputs are checked before the export is published. An existing output directory is refused.
The output contains `source.csv`, `parts.json`, the original manifest, `index.json`, and numbered
session JSON files. The v2 index records the exact source and parts hashes, model, fixed prompt
recipe, reply protocol and prompt hashes. This first preparation recipe asks for categorical
scene/film assignments through `strict-chunked/v1`; it does not ask for clip times or confidence.
Generated unit numbers and row keys are one-based positions, not original study row identifiers.

## Execute and inspect

Inspect the export's prompts and units before using your authorized execution environment.
Then follow the live/replay guide with `EXPORT_DIR/source.csv`, `EXPORT_DIR/parts.json` and
`EXPORT_DIR`. Both modes use the same mapping loop and bundle reader as supplied-unit studies.
A different source or parts file is refused. Offline replay requires completed recordings for
these exact prompts; recordings for supplied units cannot be relabelled as automatic results.

```text
python3 tools/agentmapping/read_bundle.py OUTPUT_DIR/bundles/p01_recall.bundle.json
python3 tools/agentmapping/read_bundle.py OUTPUT_DIR/bundles/p01_recall.bundle.json --unit 1
```

Automatic-input bundles add `recall`: canonical transcript text, raw and canonical SHA-256,
canonicalization and segmenter recipes, and every unit's text and spans. The raw text remains
in the export. The Scala reader regenerates the segmentation from raw text and checks the full
descriptor against the export and mapping records. The Python reader checks canonical hashes,
UTF-16 slices, row keys and record spans; it does not rerun the Scala segmenter or authenticate
the original file.

## Coordinates and interpretation

Spans are **half-open canonical UTF-16 code-unit offsets**: `start` is included and
`endExclusive` is excluded. A non-BMP character occupies two code units. They are not byte
offsets or offsets into the original raw text. `story-source/v1` normalizes line endings,
trailing spaces/tabs and blank-line runs. It does not normalize Unicode composition. Both
checksums remain visible, so two raw files that canonicalize alike can be distinguished.
No inverse mapping to raw offsets is claimed.

A unit may contain a newline. Its text and offsets remain intact; only prompt presentation
uses a JSON string with escapes. Text inside quotes is data. The current recipe is the existing
heuristic `RecallSegmenter`, recorded as `recall-segmenter/v1`; its boundaries are a baseline,
not human annotations or demonstrated scientific accuracy. A change to the algorithm requires
a new recipe and qualification, and unknown recipes refuse readback.

Segmentation disagreement and mapping error require separate evaluation. Comparing supplied
and automatic units needs a verified common transcript coordinate system and an explicit
alignment between their spans. Joining old supplied units with newlines can test the software,
but does not recover the original transcript. Repeated phrases cannot be aligned safely by
first text match. Corpus boundary comparisons, independent usability and prospective mapping
qualification remain separate acceptance work.
