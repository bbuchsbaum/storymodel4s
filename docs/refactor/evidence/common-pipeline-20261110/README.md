# Common recorded pipeline: evidence (2026-11-10)

Design: [the design note](../../../plans/2026-11-10-common-recorded-pipeline-design.md).
Everything here is from recorded runs; no model was called. Exports and reply files hold recall
text or are derived from it and stay outside the repository.

## Slice 1: collection level in timed sources (landed 24e6d743e)

- Gate at a435b574c, clean clone, fatal warnings: JVM 15 module runs, 3,817 passed, 0 failed;
  JS 9 runs, 2,651 passed; Native 9 runs, 2,651 passed; format check clean.
- storyatlas4s (branch `claude/storymodel4s-pin-20261109`) compiles and passes 10 test runs
  against it.
- Seven mutants, each killed by a named test.
- FilmFestival loads as 628 rows, 216 scenes and 12 collections, with 203 target scenes equal
  to the Python tools' valid set and 10 target films. Sherlock's artifact checksum and
  representation digest are unchanged.
- Independent review: Scala clean; the exporter marked the title row of the one mixed scene as
  a target, fixed before landing.

## Slice 2a: generic replay under a named reply protocol

Commits f1e00e59a to 0b8b76af6.

- `StudyReplay` replays any exported study from text-exchange recordings. Prompts are taken as
  recorded; the reply rules are a named protocol recorded in the export.
- `ff_export_run.py` exports a run's units and prompts and refuses unless what it renders equals
  the run's frozen manifest. It refuses the time conditions, which have no protocol yet.

Gate at 0b8b76af6, clean clone, fatal warnings: `pipeline/test` 520 passed, 0 failed;
`providerAgent/test` 82 passed, 0 failed, 1 skipped; format check clean.

Acceptance, on that commit, for the two annotation-assisted FilmFestival runs:

| Run | Sessions | Utterances | Exchanges | Model calls | Differences |
|---|---:|---:|---:|---:|---:|
| `claude-opus-5-5` | 19 | 3,226 | 63 | 0 | 0 |
| `claude-haiku-5-5` | 19 | 3,226 | 63 | 0 | 0 |

"Differences" compares every field of every unit row and every chunk receipt with the Python
reply files (`tools/agentmapping/compare_replies.py`). The Haiku run's 15 items answered outside
their chunk are dropped and counted, as the harness did.

Mutants at 78d418414, each failing named tests in `StudyReplaySuite`:

- any number accepted as an integer;
- out-of-chunk items kept under the strict protocol;
- omitted and unparsable swapped;
- a film-only answer read as no assignment.

Independent review of 78d418414 found one blocking defect: the reply protocol was a command-line
argument, so an export could be replayed under the wrong rules without complaint. It is now
recorded in the export. Guards tightened after the review: a unit's line must be a whole line of
its prompt; chunk bounds must be the units each chunk owns and what the prompt says it asks
about; the index names each participant-session. Known and accepted: a reply containing `NaN`
is accepted by Python and refused here, which fails closed.
