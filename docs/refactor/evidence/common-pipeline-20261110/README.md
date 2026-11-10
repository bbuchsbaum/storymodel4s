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

## Slice 2b: supplied recall units and checked records

Commits after 3d60a686c, to cc6299d22.

- `DiscourseFunction.NotAssessed` (ADR 0022 amendment, decision 2). The cost model gives it no
  natural external state and no prior; the interview pipeline never classifies it as an episode.
- `PreparedRecall.supplied` builds a checked recall inventory from a study's own units, marked
  not assessed. A recall is identified by its units alone, so it is the same in every condition.
- `StudyRecord` decides each stated answer against the source and builds the two checked
  categorical records. A film-only answer is a choice of the film; the choosable targets come
  from the source view.
- The FilmFestival source export numbers each film's collection with the film number the prompts
  show (1-10); the cartoon intros take 11 and 12.

Full gate at 2be5376a3, clean clone, fatal warnings:

| Run | Module runs | Passed | Failed |
|---|---:|---:|---:|
| JVM | 16 | 3,962 | 0 |
| JS | 10 | 2,760 | 0 |
| Native | 10 | 2,760 | 0 |
| storyatlas4s (`claude/storymodel4s-pin-20261109`) | 10 | 518 | 0 |

Format check clean. The commits after 2be5376a3 change only `pipeline` and docs; re-gate at
cc6299d22: `pipeline/test` 537 passed, 0 failed; `providerAgent/test` 82 passed, 1 skipped;
format check clean.

Acceptance at cc6299d22, both annotation-assisted runs, zero model calls: all 76 records
construct (19 sessions, two judgements, two models). Judgement counts equal the Python scorer's
prediction kinds summed over both sessions:

| Model | Judgement | Scene | Film | None | Failed |
|---|---|---:|---:|---:|---:|
| `claude-opus-5-5` | referent | 2,804 | 276 | 146 | 0 |
| `claude-opus-5-5` | narrative | 2,891 | 219 | 116 | 0 |
| `claude-haiku-5-5` | referent | 2,852 | 230 | 144 | 0 |
| `claude-haiku-5-5` | narrative | 2,945 | 190 | 91 | 0 |

Mutants at 2be5376a3, each failing a named test: a non-target scene accepted as a choice; the
universe widened to every scene; supplied units marked as assertions; a natural external state
given to an unassessed unit; the cartoon accepted as a film.

Independent review of 2be5376a3: no blocking defect. It verified that texts, unit ids and
inventory cannot disagree, that a forged non-target judgement is refused, and that the Sherlock
ledger is unchanged. Its findings for later slices are in the design note. Open, and not yet
reachable: an unassessed unit is not neutral in the HSMM cost model (every named external state
costs the mismatch), and supplied units carry `ExpressedUncertainty.Unmarked` although no hedge
detector ran.

## Slice 3: the v2 bundle, and an unassessed unit in the cost model

Commits after 685b2af5e, to a92cf05e1.

- **Cost model** (owner decision 2026-11-10). For a unit whose function was not assessed, every
  external state costs the floor and no level pays a granularity cost. Assessed units are
  unchanged; `NotAssessedSuite` pins that for every assessed function.
- **Bundle v2.** `agentStudyBundle` writes one bundle per participant-session: both checked
  records, each answer as stated and as judged against the source, provenance, and a source
  dictionary. `StudyBundle.decode` derives every judgement and both records again and requires
  the bundle to hold exactly those. `tools/agentmapping/study_bundle.py` reads a bundle without
  Scala, and `ff_score.py score` accepts a directory of bundles.

Full gate at a92cf05e1, clean clone, fatal warnings:

| Run | Module runs | Passed | Failed |
|---|---:|---:|---:|
| JVM | 16 | 3,983 | 0 |
| JS | 10 | 2,763 | 0 |
| Native | 10 | 2,763 | 0 |
| storyatlas4s (`claude/storymodel4s-pin-20261109`) | 10 | 518 | 0 |

Format check clean. Python: `test_study_bundle.py` 18 passed; `test_ff_score.py` passes.

Acceptance at a92cf05e1, both annotation-assisted runs, zero model calls:

- 19 bundles per model, 3,226 units each, every bundle read back and re-checked before writing.
- Scores computed from the bundles are byte-identical to scores computed from the original
  reply files by the same scorer, for both models and both judgements.
- Against the score files recorded on 2026-11-09, all 1,622 shared values are equal. Those
  files differ only in report fields and a schema label the scorer has gained since.
- A run of 19 bundles is about 90 MB: each record repeats the recall inventory and the source.

Mutants, each failing a named test. Scala: a judgement not re-derived; a failure accepted as an
answer; a scene that is not a target losing its film; extras keys unchecked; the rebuilt-record
comparison removed; row keys unbound; processing unchecked; reserved extras accepted by the
export; the old external-floor rule; a default preferred level. Python: extras keys unchecked;
judgement not re-derived; record outcome not compared; model not compared.

Independent review: the first pass at 37648a132 found three blocking defects in the readers (an
extra could overwrite a prediction; the Python reader accepted edits the library refused; row
numbers and keys were bound to nothing). They were fixed and re-reviewed at a92cf05e1 with no
blocking defect. Limits that remain, by design or as follow-ups:

- The Python reader has only the bundle, so it cannot check that row keys are the study's or
  that a record is intact. The library's reader checks both.
- Neither real run contains a failed unit, so the failure paths rest on the synthetic fixture.
- In the cost model, five external states tied at the floor give an unassessed unit more
  external mass than an assessed unit has (about 1.46 times at temperature 1) and an arbitrary
  best-path label; and a granularity of 0.0 reads as a perfect match. No path feeds supplied
  units to a cost model. Both await an owner decision.
