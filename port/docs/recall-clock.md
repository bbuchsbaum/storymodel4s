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
