# Production recall timing intake and source support

The offline `recallTimingIntake` command joins a pinned timing CSV to an existing
checked recall graph and word inventory. It emits the canonical
[`recall-timing/v0.1` sidecar](recall-timing.md), a record correspondence receipt,
and a manifest with exact file hashes. It performs no inference or provider calls.

## Run the synthetic example

From the repository root, with new output directories:

```sh
sbt 'pipeline/Test/runMain storymodel4s.pipeline.RecallTimingFixture /tmp/recall-intake-example'
sbt 'pipeline/runMain storymodel4s.pipeline.recallTimingIntake /tmp/recall-intake-example/job.json /tmp/recall-intake-result'
```

The fixture is original synthetic text. Five CSV records correspond to four words;
the blank word record remains in the receipt. OpenNeuro-column onsets are missing,
8.75, 10 and 9 seconds. The backwards pair is retained. There are no inferred
offsets or durations. Changing the selected column changes timing and provenance
without changing the original word identities.

For your own admitted inputs, the job declares:

| Field | Meaning |
|---|---|
| `schemaVersion` | `recall-timing-intake-job/v0.1` |
| `csv`, `graph` | `{ "path": "...", "sha256": "..." }`; relative paths resolve from the job directory |
| `parserArtifact` | Original word parser input identity, distinct from the external CSV |
| `inventoryDigest` | Expected digest of the original checked inventory |
| `wordSpans` | Ordered `{ "start": 0, "endExclusive": 4 }` UTF-16 spans from that parser |
| `columns` | Exact `header` array, zero-based `word` and `onset` indices, explicit `clock` key |

`graph` uses the existing canonical `RecallCodecs` representation. The command
decodes and validates it, reconstructs `RecallInventory`, checks the expected
digest, then compares the replayed text and every word span. It does not accept
a mapping digest or an unvalidated JSON inventory as its own proof. Preserve
producer-recorded pins; computing replacement pins from unexpected files defeats
identity verification. A pin establishes byte identity, not permission to access
a participant file. Study admission and split restrictions still apply.

CSV is strict comma-separated UTF-8, with an optional BOM, quoted commas/newlines,
and doubled quotes. All header cells are matched exactly, including duplicate
labels. Malformed quotes, wrong widths and invalid UTF-8 refuse. Word fields trim
U+0000–U+0020; nonempty fields join with one space. Changed StorySource
canonicalization refuses. Numeric cells use bounded exact decimal parsing;
blank selected cells remain missing. Every selected cell is checked, even on an
excluded blank word record. Other columns remain byte-bound but are not interpreted.
Declare and select the OpenNeuro onset column explicitly; no Princeton-to-OpenNeuro
offset is inferred or applied.

The output directory must not exist. `manifest.json`, atomically published last, is the
completion marker. Failed writes can leave an incomplete directory for inspection;
retries cannot overwrite it. Refusals return exit 2 with a content-free JSON error.
Success prints inventory/timing digests and counts. The receipt contains every
one-based data-record coordinate, matched word ID or exclusion reason, and exact
onset/missingness. It does not copy transcript prose. The sidecar requires its
checked inventory when decoded.

Library callers use `RecallTimingIntake.read(bytes, expectedChecksum, columns,
checkedGraph, inventory)`. The original parser identity and external timing source
remain separate. Even identical text does not establish the same recording:
recording identity, recording origin and scanner linkage remain unestablished.

## Source support for temporal queries

`TemporalSupport.read(source, target, selection)` reads exact geometry from a
checked `SourceRepresentation`. Choose `Selection.Part(fullBundleIdentity)` or
`Selection.Occurrence(mappingIdentity, occurrenceId)`. The latter identifies the
actual declared composition segment; a reused occurrence label within a mapping
refuses. Contributing anchors must belong to the selected mapping's stream; overlapping
part mappings cannot silently assign foreign evidence to an occurrence. Native
support does not acquire an invented occurrence, and absent
composed support is not automatically projected.

The result binds the original source digest, target, coordinate axis/timebase and
occurrence segment. It retains interval gaps and point atoms, including points
inside intervals. An occurrence selection partitions supplied geometry into
`included` and `excluded` support using half-open bounds. Selecting two repeated
occurrences produces two readouts, not two assignment observations or an automatic
50/50 allocation.

Each target and descendant is explicit: available, unlocated, text-only, no support
on the selected axis, or outside the selected occurrence. An unlocated parent
stays unlocated even if its descendants have loci. The transitive
`unlocatedDescendants` and `unavailableDescendants` lists prevent missing
grandchildren from disappearing behind located parents. Cyclic hierarchies refuse.
These lists do not prove that parent support contains child support. Display bounds
are a hull for navigation; they never replace disjoint support.

`TemporalSupportCodecs.encode/decode` publishes `temporal-support/v0.1`, with ticks
and timebases represented as exact strings. Decode requires the checked source,
re-executes the query, and compares the entire result. Altered geometry, missingness,
unknown fields, duplicate keys and foreign source bindings refuse.

The existing mapping wire/digests remain unchanged. Its legacy `supportCoverage`
is immediate-child accounting and must not authorize a temporal kernel. The new
readout supplies transitive accounting for the temporal-query follow-on.

## Remaining workflow

These outputs establish timing and physical-support inputs. Allocation-free bounds,
declared temporal kernels, uncertainty-at-resolution queries and sparse grids are
the next layer. OpenNeuro subject/run crosswalks need independently verified scanner
and recording identities; cartoon boundaries need their own evidence. StoryModel
will compile those scientific results for StoryAtlas's linked timelines, heatmaps
and media navigation. Neither this intake nor supplied source extent demonstrates
second-level localization accuracy.
