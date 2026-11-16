# Recorded film-and-time mapping

This is the first complete computational workflow in the pilot: supplied recall units and
raw recorded answers become independently computed mapping rows. The checked example uses
invented Scala test replies, explicitly labelled as synthetic; no model is called.

From `port/`:

```sh
uv sync --locked
mkdir -p data
uv run storymodel import-time-recording tests/fixtures/time-study-request.json \
  tests/fixtures/time-study-replies.json data/time-study-recording
uv run storymodel replay-time-study tests/fixtures/time-study-request.json \
  data/time-study-recording --output data/time-study-result.json
```

The recording directory and output file must be new. On another run choose new paths, or
replay the existing recording to a different output. Import journals and preserves raw
responses before interpretation; replay has no transport and cannot make model calls.
No browser or Scala build is needed to use this path.

The source input holds an independently supplied scene/film registry and checked clip
intervals. Recall units retain their study row keys and occurrence order. The resolver:

1. Admits the request, checks its exact recording identity and generates chunk prompts.
2. Reads recorded attempts. Invalid JSON can advance only to another recorded attempt.
3. Applies strict unit-key and chunk rules, reporting dropped and repeated items. As in
   Scala, the last answer for a repeated key wins; an accepted chunk missing a unit marks
   it omitted, while exhausted malformed attempts mark it unparsable.
4. Reads narrative and referent answers separately. Integer film keys exclude booleans,
   floating literals and strings. Time strings accept `m:ss` or `mm:ss`, with up to three
   fractional digits. All comparisons use exact fractions.
5. Resolves time into the containing scene and a separate tolerance-neighbourhood list.
   A boundary belongs to the later scene; the final clip endpoint remains in the last
   scene. A time past the clip selects the film with an explicit `time-outside` result.
6. Emits every supplied unit, stated values, judgements, processing outcomes, receipts and
   outcome counts. Requested `conf` values remain uninterpreted extras, never probabilities.

Python uses the same operations:

```python
from pathlib import Path
from storymodel.study_workflow import read_request, replay_time_study

request = read_request(Path("tests/fixtures/time-study-request.json"))
result = replay_time_study(request, Path("data/time-study-recording"))
assert len(result.rows) == 6
assert result.counts["referent"]["time-outside"] == 1
```

The result is `storymodel.time-study-result/v1`, not a rich detail account or a legacy Scala
bundle. Coarse scene references do not establish detail fidelity, internal/external counts,
or definite source-row recall. Those quantities are deliberately absent from this output.

## Parity and identity

Tests compute all six rows from the supplied inputs, then compare every semantic row field
against the pinned Scala-generated `synthetic-study-time.bundle.json`: twelve interpretations,
processing state, study row keys and extras. Python does not read this expected artifact
during computation. [Reference hashes](../tests/fixtures/time-study-reference.json) bind the
Scala implementation and fixture files used for comparison.

Pilot occurrence IDs differ intentionally from Scala IDs. The request digest is canonical
schema-admitted JSON, not the raw file digest; the raw reply import binds that canonical
digest. Result receipts also name source, recall, profile, imported-file and raw-response
digests. `--expected-request-sha256` and `--expected-import-sha256` accept independent raw-file
pins. Without those independent pins, local hashes establish consistency, not authenticity.
The saved import file records synthetic/provider provenance and is cross-checked against
the reply cache on replay.

The generated prompt and strict JSON framing have their own pilot recipe. Historical
harness prompt strings, permissive JSON/prose extraction, coercing-open protocol, Scala
mapping-record digests, and complete StudyBundle serialization are outside this profile.
Unknown/missing recordings refuse; they do not become omitted answers. Duplicate JSON keys
and nonfinite values refuse JSON admission rather than being silently repaired.

## Remaining computational scope

This workflow starts with supplied segmentation and clip intervals. It does not perform
text segmentation, build clocks from native acquisition ticks, extract event/detail graphs,
score candidate references or run graph-HSMM inference. The rich account API remains available
for those next slices. These synthetic parity cases establish engineering behavior, not
the quality of a language model's mapping or a measured development-speed advantage.
