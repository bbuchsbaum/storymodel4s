# Which source targets receive transport mass from each recall unit?

Use this baseline to compare many recall units against the same pool of source targets.
The comparison uses semantic distance and competes for mass across units. It can put
mass on a target that another unit nominated. Use the independent local score when the
question instead calls for a distribution over one unit's measured alternatives.

## Run the synthetic example

From the installed port environment, run these commands in `port/`.

```sh
python examples/timed_recall.py data/transport-example
cd data/transport-example
storymodel align-transport local-source.json local-recall.json transport-spec.json --output plan.json
storymodel verify-transport local-source.json local-recall.json transport-spec.json plan.json
storymodel source-time-grid analysis-1.json transport-grid-query.json --output grid.json
storymodel verify-source-time-grid analysis-1.json transport-grid-query.json grid.json
```

From the example output directory, inspect a row in Python.

```python
from pathlib import Path
from storymodel.artifacts import read_supported_account
from storymodel.transport import TransportSpec, align_transport, verify_transport

source = read_supported_account(Path("local-source.json"))
recall = read_supported_account(Path("local-recall.json"))
spec = TransportSpec.model_validate_json(Path("transport-spec.json").read_bytes())
plan = align_transport(source, recall, spec)
unit = spec.units[0].unit
print(plan.status, dict(plan.iter_row(unit)))
verify_transport(source, recall, spec, Path("plan.json").read_bytes())
```

The example fixes the comparison population at six source rows. The recall unit has a
budget of 1 and achieves about 0.982594. The target with declared timing receives
about 0.976381. Each of two bins contains two fifths of that target, or 0.390552 mass
per bin. The rest of that target falls outside the requested bins. Other targets lack
timing declarations. The recall interval lasts three quarters of a second, giving
0.292914 mass-weighted recall seconds per bin. The grid keeps cropped mass, missing
source locations and the requested row budget separately inspectable.

## What the solver measures

For a nonnegative plan `P`, the objective is the sum of the transport cost,
`epsilon * Σ Pᵢⱼ (log Pᵢⱼ − 1)`, and two generalized KL penalties on achieved
versus requested marginal masses. Row and column penalties are independent. Increasing
one penalty makes deviations on that side more costly. Every recall unit requests one
unit of mass. The same total budget is divided evenly across the union of nominated
source targets. Achieved marginals can be higher or lower. A budget shortfall carries
no external-content or fidelity label.

The account API accepts supplied targets or retrieves them from the source projection.
With lexical comparison, disjoint lemma sets have observed distance one. With a recorded
table, a missing comparison uses the declared distance (default 0.5). A measured 0.5
remains an observation. An ineligible pair has exactly zero mass. An empty candidate
union produces `no-candidates`; its grid row has no mass estimate. An unfinished fit
retains its last iterate and iteration count and is flagged in the grid. Verification
recomputes the complete result against the original accounts and specification.

## Storage, computation and numerical limits

The account artifact stores sparse cost exceptions, two vectors of log scaling factors,
achieved marginals and retrieval records. Reading one cell takes constant time by index.
Iterating one row requires visiting its columns. With explicit sparse support,
`solve_transport` traverses only declared edges. With a finite background cost,
`scale_transport` shares the sum for entirely default rows or columns. Mixed rows and
columns can require a full pass over their partners per iteration. Working storage
scales with the number of exceptions and axis sizes.

A zero penalty leaves that side unconstrained. With a positive penalty, a zero target
forces its incident mass to zero. Python preserves viable edges elsewhere when this
removes an entire component. One pinned Scala example instead loses both components
and returns all zeros at its iteration limit. The Python regression checks the surviving
edge against its one-cell analytic optimum. Positive targets with no admissible partner
in the input raise an error. Precision-limited fits can report nonconvergence or raise
a numerical error when the dual factors cannot resolve the result. The artifact records
transport mass on the candidate union. Calibration and corpus accuracy require separate
evidence. [Executable JVM comparison](../evidence/transport/README.md)
