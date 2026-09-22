# Declared scanner clocks and sample inventories

The offline `scannerSamples` CLI prepares exact scanner sample/index metadata from
explicit declarations. It does not open an image array or infer a recording/run join.

```sh
sbt 'pipeline/Test/runMain storymodel4s.pipeline.ScannerSampleFixture /tmp/scanner-job.json'
sbt 'pipeline/runMain storymodel4s.pipeline.scannerSamples /tmp/scanner-job.json /tmp/scanner-samples.json'
```

Both paths must be new. The synthetic job declares eight samples at 1.5-second
spacing. Its analysis array has two padding positions followed by stored indices
2, 4 and 7. Their original times remain 3, 6 and 10.5 seconds. Index 4 is censored;
index 7 has unknown censoring. Dropped indices and padding remain explicit.

The job has `schemaVersion: scanner-samples-job/v0.1`, a `run` declaration and a
`layout` declaration. `run` contains dataset/revision/participant/session/task/run,
image/header SHA-256 identities, sample count, exact rational `sample_seconds`,
origin and applied-history knowledge. `layout` contains analysis-array identity,
receipt, output length and ordered slots. Acquired slots carry original stored
indices and censor status. Padding slots carry a reason. The CLI binds the layout
to the run it just checked; do not supply a separate `run_digest` in the job.

Output is one atomically published `scanner-samples/v0.1` JSON file with job hash,
canonical `scanner-run/v0.1` and `scanner-layout/v0.1` records, derived sample
coordinates and an explicitly unestablished scanner binding. Existing files are
never overwritten. Invalid jobs and interrupted writes publish no final artifact.
Errors exit 2 with content-free JSON. Rational numerators and denominators are
canonical decimal strings, never floating-point timestamps.

Library callers can additionally create a `ScannerCrosswalk.Binding.declared`
between a checked reference and run inventory, using positive scale, offset,
half-open validity window and matching declaration/evidence identities. References
bind the entire recall clock, a full media part, or an actual composition occurrence.
`project` and `inverse` preserve exact seconds, including negative offsets; foreign
clocks, out-of-domain values and unrepresentable results refuse. Media ticks use the
actual timebase without subtracting a cartoon duration. Cropping changes the index
map, not the underlying clock. Querying a run sample never rounds another time to it.

Every scanner record is labeled **declared-not-independently-verified**. The
constructors check consistency, identity and arithmetic; a checksum does not establish
that an image header or alias crosswalk was inspected. `AppliedHistory.Unknown`
differs from a declared empty history. A stored sample can be censored; padding has
no stored-volume identity or invented sample time. Sample points do not imply
measured acquisition windows, and no HRF, interpolation or lag shift is applied.

Sherlock/OpenNeuro qualification still needs pinned dataset/header evidence,
recording-to-participant/run joins, task-event/full-media origins and transformation
receipts. Full-part alignment retains the introductory cartoon; episode-only views
need separate transition evidence. The historical clock discrepancies are regression
witnesses, not reusable corrections. This generic synthetic workflow does not close
that dataset acceptance gate.

For an explicitly declared analysis bin, `binding.inverseWindow(run, window)` maps
both bounds and permits an excluded endpoint at the validity boundary. A bin partly
outside the declared domain refuses rather than silently losing a tail.
`binding.mediaWindow(run, window)` additionally requires exact integral media ticks;
its result carries the actual `PresentationAxis`, checked interval and binding.
Use that axis and interval with `TemporalQuery.Region.on`, then `prepared.query`.
The prepared query checks the full axis identity. Fractional-tick boundaries require
a future explicitly declared resampling policy and are currently refused.
