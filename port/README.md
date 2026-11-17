# Python / TypeScript pilot

This folder holds the selective rebuild of StoryModel and StoryAtlas. Its purpose is to
test whether a simpler implementation makes useful, correct changes substantially faster
while preserving the strongest parts of both projects.

The first computational slice is executable: validated paired accounts, contextual events,
recall details, reference hypotheses, conditional fidelity, analysis policies, exact time
allocation, legacy bundle reading and recorded replay. The React app is a scaffold.
Algorithms and representations come first; visual refinement follows later.

The [recorded film-and-time workflow](docs/time-study-workflow.md) now runs supplied recall
units and raw synthetic Scala reply fixtures through parsing and exact scene resolution.
Its semantic rows are checked against the pinned Scala output; no provider call is required.

[Input preparation](docs/input-preparation.md) now starts that workflow from raw UTF-8 recall
and native source ticks. It preserves raw/canonical identity, exact text spans and clock
derivations, with no parallel mapper or UI dependency.

[Recorded extraction](docs/extraction-workflow.md) turns raw recall and typed proposal JSON
into recall-local entities, contextual events and evidenced details. It feeds the existing
analysis views while leaving unattempted source matching and unresolved identity explicit.

[Partial alignment](docs/alignment-workflow.md) now adds sparse predicate/role candidates and
inspectable reference decisions. It preserves alternatives, unresolved and search-limited
outcomes, existing external references, and unassessed fidelity.

The [port roadmap](docs/roadmap.md) defines the next phases toward full working capability;
its immediate next slice is source-account construction.

## Run it

For the idiomatic Python entry points and independent source/recall construction, start with
the [Python API guide](docs/python-api.md) and `examples/python_workflow.py`.

From this directory, with Python 3.12 and `uv`:

```sh
uv sync --locked
mkdir -p data
uv run storymodel example --output data/example.account.json
uv run storymodel validate data/example.account.json
uv run storymodel analyze data/example.account.json --output data/example.analysis.json
uv run storymodel analyze data/example.account.json --policy target-event-only/v1
uv run pytest -q
uv run mypy src/storymodel
uv run ruff check src tests
uv run ruff format --check src tests
```

Output files are created atomically and never overwritten. Use a new name for another run.
The example uses independently admitted synthetic text; no provider calls or private data
are needed. The same account gives three internal details under the content policy and two
under the event policy, with both source rows supported. Counts do not depend on fidelity.

```python
from storymodel import project
from storymodel.demo import example_account

account = example_account()
analysis = project(account, "target-event-only/v1")
assert analysis.counts.internal == 2
```

See [computational scope](docs/computational-foundation.md) for supported contracts,
replay usage and the remaining algorithmic work. This is an initial Stage 1 slice, not a
completed port or evidence of scientific accuracy or measured speedup over Scala.

- [Stage 1 plan](docs/stage-1.md): workflow, ordered slices, acceptance and decision criteria.
- [Preservation map](docs/preservation.md): assets, guarantees, source pointers and witnesses.
- [Design decisions](docs/decisions.md): provisional choices and rejected alternatives.
- [Reference snapshot](docs/references.json): exact source revisions and file hashes.

```text
port/
  docs/                 Plan, preservation map, decisions, reference snapshot
  src/                  Python package, CLI and analysis implementation
  web/                  React/TypeScript workbench
  schemas/              Generated exchange schemas and schema fixtures
  tests/fixtures/       Admitted synthetic examples and expected results
  evidence/             Small public receipts, commands and measured results
  data/                 Ignored inputs, provider recordings and private run output
```

Keep new pilot code, documentation, configuration, dependency locks, tests and evidence
inside `port/`. Use the existing root Mote store; do not create another tracker here.
Planning bead: `bd-01M7K75VHV71V6GGAXEDD8QNFY`. Mote holds execution status.

The existing Scala modules and sibling StoryAtlas checkout remain reference implementations.
Source links in these documents are navigational; `references.json` records the inspected
versions. Later source changes do not silently update the comparison baseline.

Stage 1 does not authorize retiring either
Scala implementation, changing existing study outputs, or claiming scientific accuracy.
