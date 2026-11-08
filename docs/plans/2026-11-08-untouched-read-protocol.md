# Registered untouched-Sherlock read of the LLM mapper (2026-11-08)

This protocol was committed and pushed before any untouched transcript was sent to a mapper and
before any untouched gold support was frozen. The owner authorized it in chat on 2026-11-08.
Mote epic: `bd-01M6Y7MWZGP29TNDJ90SG36A45`.

## Population

The untouched partition of `data/study/recall-to-video/partition.json`:

- NN01, NN02, NN09, NN10, NN12 and NN14.
- NN01 is mapped but has no usable gold (`excluded-inconsistent-clock` in the scene-coding rule),
  so five participants are scored.

Their units come from the frozen `all17-monofill` reports, joined to stable IDs by ordinal from
`scorer-20260919/unit-manifest.json` (`common.units_frozen`). This join was verified on development
participants: IDs, counts and onsets are identical.

## Arms, frozen as of this commit

| Arm | Command | Role |
|---|---|---|
| Old mapper | `all17-monofill` reports, scene from the `group` column; verified equal to `assignedScene` on development | comparator |
| v2 Opus | `map_llm.py u-v2-opus --prompt v2 --model opus --chunk 60 --allow-untouched --participants NN01,NN02,NN09,NN10,NN12,NN14` | **primary** |
| v3 Opus | `map_v3.py u-v3-opus --model opus --chunk 30 --allow-untouched --participants ...` | secondary |
| Automatic, Sonnet | `map_auto.py u-auto-sonnet --evidence asr+frames --model sonnet --allow-untouched --participants ...` | secondary |

All harness code is at this commit, with the film outline as in the development runs. There is
no prompt, parameter or model change after this commit.

## Gold

`untouched_read.py freeze` runs `gold_scene.py freeze-support --partition untouchedTest` into
`llm-mapper/untouched-read-20261108/support-untouched.json` and prints only counts and hashes.
The mapper arms never read gold: their built-in scoring reads development support only.

## Outcomes

- **Primary:** participant-averaged scene-exact accuracy of v2 Opus, and its paired difference
  from the old mapper. The CI is a participant bootstrap (seed 20260902, 2,000 draws). With five
  participants the interval is coarse.
- **Secondary:**
  - within-one-scene and pooled accuracy;
  - the v3 and automatic arms, with the same paired comparison.
- **Not claimed:** generalization beyond Sherlock, calibrated probabilities, or row-level
  accuracy.

## Ledger and disclosure

This is the first untouched Sherlock read of these arms. The development ledger is unaffected.

Disclosure, as in `refactor/evidence/mapping-decomposition-20261108/protocol.md`: before this
registration, the author printed coarse statistics from the raw gold file for all subjects,
including untouched ones (row count, distinct-scene count, monotonicity, maximum offset). No
per-unit untouched labels were seen. No development or prompt decision used those statistics.

## Execution order

1. Commit this protocol and push it.
2. Run `untouched_read.py freeze`.
3. Run the three mapper arms.
4. Run `untouched_read.py score`.
5. Report the result as it comes out, including any participant where the LLM mapper does worse.
