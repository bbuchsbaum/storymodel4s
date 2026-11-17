# Raw recall and native clock preparation

Input preparation now feeds the existing recorded mapping workflow. Source timing is built
independently of recall; raw recall becomes deterministic units with exact evidence spans.
The preparation artifact retains the inputs and recomputes derived values when read.
Verification: [checks and evidence](../evidence/input-preparation.json).

Run the complete admitted synthetic example from `port/`:

```sh
uv sync --locked
uv run python examples/native_time_workflow.py data/native-time-demo
uv run storymodel replay-time-study data/native-time-demo/prepared.json \
  data/native-time-demo/recording --output data/native-time-result.json
```

The example creates a new output directory. It joins the six already admitted synthetic
recall phrases with blank lines, derives the four clip intervals from original native tick
inputs, and applies the existing synthetic answer constants. The new synthetic import is
explicitly labelled; no live provider recording is relabelled and no model is called.

For your own local inputs:

```sh
uv run storymodel prepare-time-study data/source.json data/recall.txt \
  --recall-key participant-session --allow-export --output data/prepared.json
uv run storymodel import-time-recording data/prepared.json data/replies.json data/recording
uv run storymodel replay-time-study data/prepared.json data/recording --output data/result.json
```

Use new destination names. The replies must be bound to this prepared request's digest;
old recordings are not silently reused. `--allow-export` grants subsequent result export;
without it, preparation can be stored privately but CLI result export refuses. Optional
`--source-sha256` and `--recall-sha256` check independent byte pins at intake.

## Source preparation

The [numeric source fixture](../tests/fixtures/native-time-source.json) shows the input shape.
Each part declares tick bounds and an exact seconds-per-tick scale. Each annotated row has
a scene, collection, part, start tick, optional end tick and target flag. Scene and collection
target membership are derived from their rows. Large ticks are strings, never JSON floats.

Each target film declares an affine clock:

`clip_seconds = rate × (source_seconds − origin_seconds) + offset_seconds`

The origin must equal the first target row. A scene starts at its earliest row; scenes are
ordered by their source ordinals. The first clip interval starts at zero, the last ends at
the declared duration, and the intermediate boundaries follow the affine clock. Parts must
share the declared source-seconds coordinate system; this profile does not infer epoch or
stream synchronization from acquisition metadata.

Checks refuse missing timebases, duplicate identities, rows outside part bounds, a scene
belonging to two films, missing/extra clip clocks, non-target scenes within a clip, and
non-increasing intervals. The final zero-anchored intervals are checked too: a negative
offset cannot create a reversed first interval. This last check intentionally strengthens
the Scala construction, which checks monotonicity before replacing its first bound by zero.

Exact numeric strings accept integers, plain decimals and fractions. This pilot input schema
is distinct from Scala's CSV/parts/clock file formats. It preserves their relevant numeric
meaning; it does not claim a complete importer for each study's annotation format.

## Recall preparation

Strict UTF-8 intake keeps raw bytes' text identity, including an initial BOM. The pinned
StorySource canonicalization normalizes CRLF/CR, trims spaces/tabs at line ends, collapses
long blank-line runs and removes leading/trailing blank lines. It does not apply Unicode NFC,
correct spelling or rewrite the transcript.

Paragraph/sentence boundaries and clause splitting follow the bounded Scala surface and
clause rules. Units carry exact half-open UTF-16 spans into canonical text. Separator ranges
retain coordinates not included in units, including discarded connective text; they are not
silently represented as extracted content. Raw and canonical digests remain distinct, and
the preparation ID binds session key, raw text and recipe. The canonical `Document` stores
already-prepared text under its identity recipe; the surrounding receipt names the actual
raw-to-canonical derivation.

This is segmentation only. It does not infer entities, events, discourse functions, hedge
scores, propositions or scientific recall categories. Heuristic boundaries are a reproducible
baseline, not validated psychological idea-unit labels. Units use generated ordinal row keys;
they do not pretend to retain study row keys that a raw transcript never supplied.

Surface normalization uses code-point lowercase, including the dotted-I boundary control,
rather than Python's context-sensitive full lowercase. Short-clause merging avoids rescanning
the accumulated prefix when the new fragment already determines the merge.

The recipe records the interpreter's Unicode database version and explicit ASCII connective
regex boundaries. This avoids silently changing preparation identity when Unicode tables
change. Tests cover the selected surface/clause behavior and Unicode controls; they do not
establish exhaustive parity across every Scala/JVM/JS/Native regex engine or Unicode version.

## One preparation artifact, one replay implementation

`PreparedTimeStudy` stores native source inputs, a checked recall receipt and execution
settings. Its `.request` property rebuilds the existing `TimeStudy`; there is no separately
trusted request snapshot. The request binds the native input digest, raw recall digest,
segmentation receipt and recipe. Equal canonical text from different raw files cannot reuse
recordings silently. A changed recall never changes the source's independently derived clock.

```python
from pathlib import Path
from storymodel.intake_workflow import prepare_time_study_files
from storymodel.study_workflow import replay_time_study

prepared = prepare_time_study_files(
    Path("data/source.json"),
    Path("data/recall.txt"),
    "participant-session",
)
for unit in prepared.recall.units:
    assert unit.support.slice(prepared.recall.document.text) == unit.text
# After importing replies for this exact request:
result = replay_time_study(prepared.request, Path("data/recording"))
```

`read_request` accepts both the older supplied-unit request and this preparation artifact.
Existing supplied-request hashes and recordings retain their previous meaning. Result rows
still implement the coarse film/time profile; they do not acquire detail fidelity or definite
source-row coverage merely because their input units now have text anchors.

Next computational work is typed extraction into the retained event/detail account, followed
by candidate generation and alignment. The current slice removes supplied-unit and precomputed-
clock prerequisites without claiming those later algorithms.
