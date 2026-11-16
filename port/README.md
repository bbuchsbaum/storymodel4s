# Python / TypeScript pilot

This folder holds the selective rebuild of StoryModel and StoryAtlas. Its purpose is to
test whether a simpler implementation makes useful, correct changes substantially faster
while preserving the strongest parts of both projects.

The owner authorized a targeted pilot on 2026-11-16 and explicitly asked us to preserve
the best existing work. The current deliverable is the Stage 1 plan and workspace layout.
There is no runnable pilot yet; the directories below reserve its location.

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

Start implementation with S1.0 in the plan. Do not interpret directory placeholders or
proposed commands as delivered functionality. Stage 1 does not authorize retiring either
Scala implementation, changing existing study outputs, or claiming scientific accuracy.
