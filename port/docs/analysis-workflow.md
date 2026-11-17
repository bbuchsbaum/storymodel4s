# Replay a connected analysis

`replay_analysis` runs an explicit saved construction-to-analysis journey using the existing
resolvers. It reconstructs or reads independent source and recall accounts, pairs them, replays
ordered detail/mapping/fidelity stages, and derives the three analysis views. No provider is
called. It retains complete stage requests/results and recording hashes rather than returning
only a final account.

```python
from pathlib import Path
from storymodel import read_analysis_plan, replay_analysis, verify_analysis

path = Path("study/analysis.json")
plan = read_analysis_plan(path)
result = replay_analysis(plan, path.parent)
views = result.views
verify_analysis(plan, path.parent, result)
```

The equivalent installed commands are:

```sh
storymodel replay-analysis study/analysis.json --output analysis-result.json
storymodel verify-analysis study/analysis.json analysis-result.json
```

Relative paths resolve against the manifest's directory, not the shell's working directory.
Absolute paths remain usable when explicitly supplied. `AnalysisPlan`, `AnalysisAccountInput`,
`AnalysisStage` and `AnalysisFile` also construct plans directly in notebooks. The manifest is
a plain description of a linear sequence; there is no scheduler, callback or stage inference.

## Plan shape

```json
{
  "schema_version": "storymodel.analysis-plan/v1",
  "source": {"kind": "rich", "file": {"path": "source-request.json"}, "recording": "source-recording"},
  "recall": {"kind": "rich", "file": {"path": "recall-request.json"}, "recording": "recall-recording"},
  "stages": [
    {"name": "details", "kind": "details", "request": {"path": "detail-request.json"}, "recording": "detail-recording"},
    {"name": "mapping", "kind": "mapping", "request": {"path": "mapping-request.json"}, "recording": "mapping-recording"},
    {"name": "fidelity", "kind": "fidelity", "request": {"path": "fidelity-request.json"}, "recording": "fidelity-recording"}
  ]
}
```

An account input has kind `account` for an existing snapshot, `rich` for recorded text
construction, or `playback` for recorded supplied-media observations. Snapshot inputs do not
invent construction receipts. Each file can carry a `sha256` pin, and each recording can carry
an `import_sha256` pin. Without independently retained pins, replay checks consistency rather
than provenance authentication.

`recall_details` optionally admits text recall details **before pairing**, as needed by the
native-source workflow. Its stages must have kind `details`. `upgrade_native_assessment: true`
explicitly applies the existing v8 upgrade after pairing; it requires native media and never
rewrites the independently built source snapshot. `policy` and optional `eligible_rows` pass
unchanged to the existing analysis projection. Legacy account pairs remain supported; mixed
legacy/rich pairs require an explicit upgrade by the caller.

## What the chain proves

Each validated stage request must contain exactly the preceding output account before its
recording is read. Matching prompts, unit labels or packet hashes is insufficient: repeated
occurrences may share prompt text but differ in identity and provenance. Reordered steps, stale
snapshots and foreign recordings fail explicitly. No hash is silently rebound to a new account.
Context/holder evidence is revalidated by the existing fidelity boundary against the exact
mapped snapshot. Construction-stage identity results cannot simply be carried forward as proof.

Source and recall grants are checked before dependent recording reads. A denied source stops
before recall intake; a denied recall stops before subsequent stages. Every referenced file
is checked against its optional pin, and the actual import hash is passed to the stage replay
so a changed import cannot be accepted between reads. Original record directories and exact
replies remain required for verification; the result is not a standalone substitute for them.

Processing gaps remain in typed stage receipts. Missing recordings stop replay; rejected grades
remain pending, completed grades stay protected by the underlying resolvers, and alternative or
broad references do not silently become definite row credit. A plan may intentionally omit a
stage, but the resulting analysis must not be described as a completed extraction/assessment.

## Executable connected example

From `port/`, run `python examples/analysis_workflow.py OUTPUT`. It reuses existing admitted
abstract controls, saves plans and full results, and exercises:

- one source and two contextual recalls, including unresolved-holder refusal;
- supplied native source observations and two text recalls, including unsupported-gap refusal;
- two orders of the nine-case correspondence controls, preserving joint/broad/alternative/
  external references and completed assessments through nineteen saved stages per recall.

The example constructs/imports its scripted controls first. The installed `replay-analysis`
command then consumes those saved artifacts. Scripted proposals establish engineering behavior,
not extraction accuracy, grading validity or scientific superiority. Tests and qualification are
recorded in [analysis-workflow.json](../evidence/analysis-workflow.json); wire definitions are in
[analysis-workflow.json](../schemas/analysis-workflow.json).

## Remaining work

This is a saved workflow API, not automatic observation acquisition or a new provider engine.
It does not recover incomplete import directories, schedule retries, infer a missing stage,
rewrite old context evidence or close the remaining graph/algorithm parity gaps. Synthetic
study intake and safe interrupted-run recovery are next. Existing stage APIs and artifact formats
remain available unchanged. Full receipts retain snapshots per step, so long chains can produce
large artifacts; no compact storage or large-study performance claim is made here.
