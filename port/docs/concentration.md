# Where on the source timeline is a recall unit's mass concentrated?

Choose one recall unit, one source axis, and one alignment measure. A fixed-width
window finds the strongest region of a given duration. A shortest window finds how
much connected source time it takes to capture a specified amount. A disconnected
selection finds the fewest declared bins that reach the requested amount, allowing
bins from different parts of the source.

## Run the example

Run in `port/` with the package installed in the current Python environment.

```sh
python examples/timed_recall.py data/concentration-example
cd data/concentration-example
storymodel source-concentration analysis-1.json concentration-query.json --output measured.json
storymodel verify-source-concentration analysis-1.json concentration-query.json measured.json
```

In this synthetic example, one target has a declared uniform allocation over
`[0, 25000)` on a source clock with 2,500 ticks per second. The shortest window
capturing half the **allocated source mass** is `[0, 12500)`, or five seconds of film.
This duration describes a region on the source timeline. The separately measured
recall interval is three quarters of a second. External or unlocated alternatives
remain in the original row ledger. The result includes the original alternatives'
weights, the amount assigned to the window, and bounds given by their supports.

From the same output directory, prepare the account measure once and compare questions.

```python
from pathlib import Path
from storymodel.concentration import (
    BestWindow, ConcentrationQuery, CoverageTarget, DisconnectedBins,
    PreparedConcentration, ShortestWindow,
)

q = ConcentrationQuery.model_validate_json(Path("concentration-query.json").read_bytes())
prepared = PreparedConcentration(Path("analysis-1.json"), q.grid, q.unit)
half = CoverageTarget(basis="share-of-allocated-source", value="1/2")
for question in (
    BestWindow(width_ticks="2500"),
    ShortestWindow(target=half),
    DisconnectedBins(target=half),
):
    answer = prepared.query(question)
    print(answer.measure, answer.row_status, answer.result)
```

The input grid can come from [sequence inference](sequence-inference.md),
[independent local scoring](recall-clock.md) or [unbalanced transport](transport.md).
Choose `absolute` for an amount in the original measure's units, `share-of-supplied`
for a fraction of the achieved total including external or unlocated destinations,
or `share-of-allocated-source` for a fraction of the mass allocated on this axis.
Use decimal or fraction strings for coverage and integer strings for native ticks.

## Geometry, ties and missing evidence

- Connected windows range over the full declared part or composition. A crop affects
  only the disconnected-bin search. The original ledger's `allocated_outside` always
  describes the supplied grid. The mass outside a selected window on the source axis
  is `ledger.allocated_source - result.within.allocated`.
- Point mass at tick `p` enters exactly those half-open windows containing `p`.
  Mixtures retain their declared split between point and interval allocation, including
  when a point lies inside an interval. Lower and upper bounds use full containment
  and intersection of each target's support, respectively.
- Tied maximum-mass windows choose the earliest start. At the shortest feasible
  width, the result is the **strongest** window of that width, with the same earliest
  tie rule. Disconnected selection takes bins by descending mass and breaks ties by
  their original input positions. Returned indexes are in input order.
- An absolute target of zero admits the empty selection when all positive located
  mass has a usable allocation. A fractional target whose
  denominator is zero gives `undefined-conditioning`. Positive mass on known support
  without a usable allocation gives `unavailable-allocation`. An unattainable target
  reports both the quantity requested and the amount available. Missing input measures
  preserve the original row status. An iteration-limited transport plan keeps that status.
- The search works on exact changes in allocated mass per tick and their prefix
  sums. Memory grows with the number of distinct support endpoints. For K endpoints,
  fixed-width search takes O(K log K) time. Shortest-window search adds at most a
  logarithmic factor in the axis width. The per-window contributions are recalculated
  from the actual supports. A prepared row reuses the fitted measure and search index.

These are concentrations of a marginal measure for one recall unit. A joint trajectory
across units requires joint evidence. The synthetic JVM witness includes two separated
intervals, a point at tick 4, an external alternative and an unlocated target.
[Executed reference and replay instructions](../evidence/concentration/README.md)
