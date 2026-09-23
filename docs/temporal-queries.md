# Conditional recall-to-encoding queries

`TemporalQuery.prepare` reads a unit's selected measure from an existing checked
mapping. `Prepared.query` answers an encoding-region query on an explicitly selected
part or occurrence. These are scientific readouts for CLI and viewer adapters;
the viewer must not infer or renormalize them.

Each target declaration includes `SuppliedSupportContainsReferent` and one policy:

- `SupportOnly`: possible-location bounds, without allocation inside that support.
- `UniformIntervals`: uniform length over the actual interval union, retaining gaps.
- `UniformPoints`: equal mass over distinct supplied points.
- `Mixture(intervalShare)`: the stated share over intervals and the remainder over points.

The assumption is supplied, not established by a high model score. Missing or
partial domains, clipped occurrences and unresolved multiple coordinates retain
unavailable mass. A broad scene does not become precise localization because a
uniform kernel was requested. Point evidence has no duration. A mixed point/interval
support without an explicit mixture retains its weight as support awaiting allocation.

For a region B, resolved support bounds sum the weights of domains wholly inside B
(lower) and intersecting B (upper). These are conditional support bounds, not
confidence intervals. The allocated result separately integrates named kernels.
Neither readout distributes external or unresolved mass. The ledger distinguishes
allocated source, support without allocation, unavailable location and every external
state; candidate truncation/unknown coverage remains in the mapping policy.

The query uses all supplied alternatives. It ignores neither abstention nor missing
support, and does not renormalize a slightly nonunit total admitted by the mapping's
shape tolerance. Normalized scores are labeled separately from model posteriors;
no empirical calibration claim is made. Posterior fidelity states remain separate.

`TemporalQueryCodecs` emits `temporal-query/v0.1`: mapping/inventory/source/query
identities, exact axis and string ticks, input declarations, support evidence,
per-alternative contributions, bounds, allocations and accounting. Contextual decode
reexecutes the query against the checked mapping and compares the entire record.
This is a derived readout, not a second mapping schema.

This initial API queries one inference unit and one coordinate domain at a time.
Recall-time exposure, multi-occurrence allocation, concentration summaries and
StoryAtlas packet integration retain their separate acceptance gates. Declared scanner
bins can use the [exact scanner bridge](scanner-crosswalks.md); actual dataset/run
admission remains separate.
An onset alone does not define a recall interval; copying unit results to words does
not create independent observations. Scanner alignment requires an admitted run
crosswalk and never uses a guessed cartoon offset.

`TemporalPartitionView.from(partition)` retains the original checked readouts and
projects their contributions to marks and mapping-qualified local navigation.
Exact regions, atoms, interval gaps, support-only bounds, unavailable mass and
omitted top-k weight survive unchanged. There is no second scientific JSON schema.
M1 now supplies neutral artifact provenance. The Atlas scene/packet adapter still
needs a checked source package with actual media support; the current text-only
source admission and separate display timeline cannot establish that join.

For callers already holding a checked mapping, the production API is:

```scala
import storymodel4s.align.{TemporalQuery, TemporalSupport}
import storymodel4s.codec.TemporalQueryCodecs
import storymodel4s.view.TemporalPartitionView

// mapping, unit, bundle and target come from the same checked source/inventory.
val result = for
  prepared <- TemporalQuery.prepare(
    mapping, unit, TemporalQuery.Measure.NormalizedScoreMass,
    TemporalSupport.Selection.Part(bundle.identity),
    Vector(TemporalQuery.Declaration(
      target, TemporalQuery.Assumption.SuppliedSupportContainsReferent,
      TemporalQuery.Allocation.UniformIntervals
    ))
  )
  region <- TemporalQuery.Region.on(bundle.primaryAxis, Vector(10L -> 15L), Vector.empty)
  partition <- prepared.partition(Vector(region))
yield (partition.bins.map(TemporalQueryCodecs.encode), TemporalPartitionView.from(partition))
```

Use the actual axis timebase to choose ticks. The example's numbers are synthetic;
no seconds or sample duration are implied. Each requested target needs its own
declaration; undeclared targets retain unavailable location. The current public
query surface is the library API and contextual codec. A standalone ordinary-file
query command awaits the shared mapping/exchange intake instead of rebuilding its
own source dictionary.

To reproduce the checked synthetic JSON and independent numerical witness:

```sh
sbt 'codecJVM/testOnly *TemporalQueryCodecSuite' \
    'codecJVM/Test/runMain storymodel4s.codec.TemporalQueryFixture' > /tmp/temporal-query.log 2>&1
python3 tools/recall-study/check_temporal_query.py /tmp/temporal-query.log
```

A narrow region with high allocated mass is precise only **conditional on its
supplied support and allocation assumptions**. Read the support bounds, unknown
location, candidate coverage and measure label alongside that value. None of these
checks establishes empirical second-level accuracy or calibrated confidence.
