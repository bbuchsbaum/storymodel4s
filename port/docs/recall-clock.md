# Recall detail timing on a recording

Use a saved Sherlock analysis plan and a recording declaration to ask which source content
someone recalled in a given recording window. Run this example from the port directory.

```sh
python examples/timed_recall.py data/timed-example
cd data/timed-example
storymodel timed-recall analysis-1.json timed-recall-1-query.json --output measured.json
storymodel verify-timed-recall analysis-1.json timed-recall-1-query.json measured.json
```

The query binds the digest of the complete recall intake, including the original word CSV and
lineage, to a selected time column (Princeton or OpenNeuro), a fingerprinted recall audio
stream, a native tick corresponding to zero seconds in that column, and the provenance of
that declaration. Supply ordered, disjoint recording windows as half-open integer tick
intervals. Gaps are allowed. For a reported time of 1.25 seconds, a timebase of 1/4 seconds
per tick and zero at tick 10 give tick 15. A window starting at 15 includes that onset.
An onset between native ticks or outside the recording extent raises a validation error.

The saved example contains two recalls with identical canonical text and different raw timing.
The first detail's unit begins at recording tick 105 and contributes to the first bin.
The second recall has a blank first onset and contributes to the missing-onset count. Both
have separately supplied unit intervals and both can therefore contribute interval amounts.

Each detail carries its **unit's** first lexical onset. If the first word has a missing
timestamp, the unit onset stays missing, even when another word has a timestamp.
Each bin counts details whose unit begins in it. The counts, missing-onset details and
details outside the selected bins sum to the total number of admitted details. The entries
retain each detail's category, processing outcome and every correspondence row. Follow their
target IDs into the saved analysis for source occurrences. Joint targets and competing
hypotheses share the same detail count.

Optionally declare measured unit intervals in native ticks. Each detail contributes one unit
of amount distributed uniformly over its containing interval. A window's share is the
overlap divided by the unit interval's length. Cell coverage uses the window's length as its
denominator. For a unit spanning [15,23), the windows [15,19) and [20,23) receive 1/2 and
3/8 of each detail. The remaining 1/8 falls in the gap. Point-only units have onset counts
and empty interval allocations. These allocations describe an explicit analysis policy.
A detail's exact utterance time would require finer timing evidence.

The API is `storymodel.recall_clock.timed_recall(plan_path, query)`. The CLI commands
`timed-recall PLAN QUERY` and `verify-timed-recall PLAN QUERY RESULT` replay the saved
inputs and enforce the analysis export grants. This profile assumes unit playback speed
with a declared origin. Scanner synchronization and drifting clocks require additional
correspondence evidence.

## Local verification

The full Python suite at `bf58c977c` passed 1,084 tests in 51.93 seconds. Mypy checked
70 source files and Ruff passed. Both synthetic recalls produced byte-identical saved results
through the installed API and CLI. The CLI verified each against its saved inputs. The
tested wheel's SHA-256 was `fee76d3bae38e9f3edf7b1f1e4c58958b4993594f2cfad569eb5d5bc15292219`.

Independent review identified a multi-unit join error. Construction includes separator
whitespace in covering rows, while lexical utterance spans exclude it. The readout now takes
admitted unit IDs from replayed construction resolutions. The two-unit regression matches
separate details to ticks 105 and 113. Restoring the original join reproduces the failure.
Synthetic joint and alternative mapping probes each retained two targets and counted one
detail. The Scala reference for timing declarations is
`recall/src/main/scala/storymodel4s/recall/timing.scala`. These checks exercised the Python
workflow with synthetic inputs.

## Time attributed to each recall unit

A measured word interval contributes its duration to its containing recall unit.
An onset-only observation contributes an event at one tick. A missing observation
increments the unit's missing-word count for the whole inventory, since its position
in the recording is unknown. Unassigned words retain their IDs and contribute no unit
duration.

The synthetic example above also writes an exposure query for the first recording.
From the example output directory:

```sh
storymodel recall-exposure analysis-1.json exposure-query.json --output measured.json
storymodel verify-recall-exposure analysis-1.json exposure-query.json measured.json
```

In Python, call `storymodel.exposure.recall_exposure(plan_path, query)`. An
`ExposureQuery` identifies the prepared Sherlock intake, timing column, recording,
tick origin, half-open window and terminal mapping stage. Its optional `WordIntervals`
table gives each measured word's extent, the recording ID and the measurement
provenance. An interval with a known lexical onset must begin at that tick. Each
interval replaces the corresponding onset-only or missing observation. The example
uses a four-second window. The first word occupies 3/4 second, the second has only an
onset at tick 110, and 13/4 seconds are uncovered.

Overlaps among words of a single unit count once. The default `refuse` policy rejects
overlaps between units anywhere in the recording. With `equal-share`, each active
unit receives an equal fraction of the overlapping time. Summing unit durations and
uncovered time gives the exact window duration. All durations use rational seconds.

The result includes every constructed recall unit, its extraction inventory and the
mapping decisions replayed through the named stage for the same interpretation.
Earlier stages can map other units. Decisions from a different interpretation remain
separate, and later decisions cannot fill gaps in an earlier stage. A unit has complete
mapping coverage when its extraction completed, it contains details, and every detail
has an admitted decision. An admitted attempt can have an unresolved reference.
The result distinguishes completed empty inventories, failed or partial processing,
and details outside the replayed requests. `seconds_without_complete_mapping` sums the
measured durations of units lacking complete coverage. Uncovered recording time is
reported separately.

## Which film regions does a recall unit refer to?

Run these commands in the synthetic example output directory. In Python, use
`storymodel.source_grid.project_source_grid(plan_path, query)`.

```sh
storymodel source-time-grid analysis-1.json source-grid-query.json --output grid.json
storymodel verify-source-time-grid analysis-1.json source-grid-query.json grid.json
```

Select a Sherlock film part or the complete ordered presentation by its exact axis
ID. Supply disjoint half-open bins in ticks. The query names the inference recipe,
the source targets whose support contains their referents, and how to distribute
mass within each support. An interval can use uniform duration, points can use
equal weights, and a mixture specifies the fraction assigned to intervals. Choose
`support-only` when containment is the only defensible assumption.

For each recall unit, the grid shows how much model posterior falls in each bin.
Its lower bound counts targets whose entire support fits the bin. Its upper bound
counts all targets whose support intersects it. These bounds are conditional on
the declared support. Overlapping upper bounds across bins cannot be added as
independent probability. The ledger distinguishes allocated mass outside the bins,
targets with usable support but no allocation, targets without usable locations,
and each external destination. An extracted situation needs its own timing. A
sentence that mentions it can have measured film time while the situation remains
unplaced.

In the example, one target occupies ticks 0–25,000. It receives about 0.15135 of
the recall unit's posterior. The two bins each cover 10,000 ticks, so each receives
about 0.06054. The remaining 0.03027 is outside both. A second target and five
external destinations account for the rest of the model posterior. With 3/4 second
of measured recall exposure, each bin receives approximately 0.04540 model-weighted
seconds. These are allocated quantities under the declared model. The exposure
receipt separately records the actual timing and replayed mapping decisions.

Omit `exposure` to compute a source grid without a recording window. A selected
unit with zero measured duration has an empty weighted grid. A unit excluded from
inference has no computed grid. Within a computed row, omitted bin keys mean zero.
