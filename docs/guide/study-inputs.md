# Prepare ordinary study files

`storymodel4s.pipeline.studyPrepare` imports a source once and each recall independently. It
runs offline and does not acquire models or contact providers. Preparation creates checked
inputs; it does not run a mapping or establish empirical validity.

Run the JVM command through sbt:

```sh
sbt 'pipeline/runMain storymodel4s.pipeline.studyPrepare template'
sbt 'pipeline/runMain storymodel4s.pipeline.studyPrepare validate study.json'
sbt 'pipeline/runMain storymodel4s.pipeline.studyPrepare prepare study.json prepared'
```

Save the template as `study.json`, next to the input files. Set the source path and add one recall
entry per participant/session. Paths are relative to that directory. External symlinks and
nonregular files refuse. A duplicate participant/session pair is an error; different sessions
may use identically named or identical transcripts without sharing word identities.

The `recall-baseline-lexical-words/v1` profile uses the existing deterministic heuristic recall
segmenter and counts lexical word/number tokens, excluding punctuation. These are baseline
language annotations, not a validated semantic analysis. UTF-8 raw text and canonical lineage
are preserved. Word identities bind participant, session, raw transcript and word parser;
timing and segmentation are separate identities.

Source format `text` generates the existing `surface-semicolon/v1` checked text package; select
`Sentence`, `Paragraph` or `Clause` targets explicitly. Format `checked-text` accepts an existing
`TextSourceArtifactCodec` artifact and preserves its segmentation. Text positions and ordinals
are not seconds. Format `video` returns the missing configured source-perception capability.
The source is never inferred from participants' recalls.

For repository texts carrying an admission header, select `"format": "admitted-text"` with the
same `path` and `kind` fields. The `AdmittedStoryInput.load` API returns a checked body-only
`TextSourcePackage` and a separate `AdmissionRecord`. The text CLI accepts the same format:

```sh
sbt 'pipeline/runMain storymodel4s.pipeline.textSourcePrepare docs/design/war-of-the-ghosts-boas1901.txt prepared-wog --admitted'
```

The UTF-8 document starts with `Format: admitted-story/v1`, followed by exactly one nonempty
`Title`, `Source`, `Basis`, `Modifications`, `Tradition`, `Admitted by`, and `Text-SHA256` field
(each on its own `Key: value` line). The exact line `--- BEGIN STORY TEXT ---` ends the header.
Everything after its LF is story text, including any final newline. `Text-SHA256` must match
those exact bytes before canonicalization. Header lines use LF; unknown or duplicate fields,
malformed UTF-8, empty text and a mismatched digest refuse. Plain text intake refuses recognized
admission headers so they cannot silently become story segments.

The checked text artifact retains the description under `admitted-story.*` metadata, including
the whole-document checksum. This records an existing admission decision; it grants no legal,
consent or disclosure authority. The authoritative Boas edition is
`docs/design/war-of-the-ghosts-boas1901.txt`; the Scala fixture is a checked mirror of its body.

Recall timing is optional. Set `timing` to a CSV path with exactly these columns:

```csv
participant,session,word,onset,end
```

Supply one row per lexical token, preserving its exact canonical spelling and participant/session.
The parser supports quoted commas/newlines and escaped quotes. Decimal times are exact rational
seconds. Blank onset and end mean missing; onset alone means onset-only; both mean the supplied
half-open interval. An end without onset refuses. No duration or scanner alignment is inferred.

The output contains the canonical checked source, one `prepared-recall/v2` wrapper per successful
observation, and a completion manifest listing all outcomes and file hashes. `StudyPreparation.decode`
requires the expected source edition, reconstructs inventory identity, and invokes the public graph
and timing codecs. Failed participants remain in the manifest and do not discard successes.

Exit 0 means all inputs were prepared; exit 3 means a valid partial batch; exit 2 means refusal.
`--resume` reuses an existing complete output only after verifying every expected byte. Incomplete,
changed or corrupt output refuses; choose a new output directory to retry while retaining the
failed attempt. Existing outputs are never silently overwritten.

## Timed annotations

Use a source declaration with `format: "timed-annotations"`, `path: "annotations.csv"`, and
`parts: "parts.json"`. The CSV header is:

```csv
ordinal,text,part,onset,end,group,group_label,locations,characters
```

Ordinals are positive and unique. Onset/end are integer ticks on the named part's declared
clock. An onset without end is a point event; a completely blank part/onset/end is unlocated.
Groups require both a positive group ordinal and a label. Locations and characters are optional
pipe-separated terms. The part file is an array of objects containing `part`,
`start`, `end`, `numerator`, and `denominator`; every field is a string. The rational
timebase is seconds per tick. Declare the complete extent explicitly. Annotation endpoints do
not establish the duration or a common clock across parts.

These declarations identify annotation coverage. Checksums are computed by the adapter; no media
edition or picture stream is asserted. Checked media replay has its own registration. Point events remain points, group
support preserves gaps, and unlocated descendants remain visible. No story-world order is
inferred. The prepared source stores the original CSV, declarations, and adapter version;
`checked-timed` accepts this artifact for checked replay. Temporal support reads these annotation
axes; `TemporalQuery.Region.on` remains edition-only, so annotation-region queries are not
part of this adapter.

## Execute both mappings

```sh
sbt 'pipeline/runMain storymodel4s.pipeline.mappingRun template'
sbt 'pipeline/runMain storymodel4s.pipeline.mappingRun run study.json mapping.json run-output'
```

Save the mapping template as `mapping.json`. It explicitly selects `mapping-lexical-pair/v1`,
temperature, candidate count, tie budget, requested capabilities, and zero network/retry budgets.
The command resolves settings once and uses the same preparation and execution API as notebooks.
It publishes independent local normalized score mass beside the historical structured
reconstruction preset. Reconstruction uses presentation order as an explicit assumption; neither
quantity is calibrated correctness probability. Unavailable local evidence remains unavailable.

Every participant remains in the manifest. Each successful run contains reference and
reconstruction records and an identity binding source, inventory, configuration and evidence.
The typed API also accepts actual `OnnxMappingRegistration.Registered` and
`CaptionMapping.Registered` envelopes; it consumes their own evidence rather than pairing
unrelated receipts with a run. Caption replay retains Draft authority. Raw-video perception or
loading arbitrary model/receipt files is not supplied by the ordinary-file command.

`MappingCodecs.decode` requires the actual `MappingRun.Execution` and member when reading
executed projections; it regenerates and compares the entire canonical record. The portable
record's inference/decision provenance is derived. The enclosing JVM run carries the registered
provider origin; the portable record does not independently certify that upstream authority.
`MappingPipeline.publish(run, output, resume)` publishes both members of a genuine registered
run, with its registration, shared evidence, execution and member profile identities. Batch
publication includes the same metadata for each successful observation.

Mapping exit 0 means complete and ready, 3 means a valid partial batch, 4 means structurally valid
output with a missing requested capability, and 2 means publication refused. Organization and
order readouts remain missing capabilities. `--resume` verifies the complete manifest and all
files; it never accepts an incomplete or changed output directory. Retry into a new directory to
preserve the failed attempt. This deterministic offline profile executes once and rejects a
nonzero network or retry budget.

## Open the results in Atlas

The [local workspace workflow](study-workspaces.md) connects these prepared inputs
and paired mappings to a cohort manifest and one Atlas inspection packet per
successful observation. It describes the required disclosure basis, source-cache
reuse, exact resume behavior and the distinction between imported claims and live
execution authority.
