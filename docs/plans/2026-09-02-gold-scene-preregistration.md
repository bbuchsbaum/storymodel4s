# Pre-registration: scene-level gold for recall-to-video

Written and committed **before any arm is scored against this gold.** Its purpose is to fix the
comparison in advance, because the gold is small, precious, and the one thing in the study that can
say "correct" — which makes it exactly the thing most easily spent by iterating against it.

## 1. What the gold is

`Sherlock_Recall_Scene_n50_Onsets.csv`, from the same onsets folder as the annotation already in use.

- Source: <https://gin.g-node.org/ljchang/Sherlock>, path `onsets/Sherlock_Recall_Scene_n50_Onsets.csv`
- sha256 `68cc307c19cfd429a3bd0eda84ee18d0152c87444d33a79246a7cd64d3763753`, 8343 bytes
- Columns `Subject, Scene, Onset, Offset`; 584 rows; 16 subjects; 49 of the 50 scenes appear
- A subject may recall a scene more than once, so intervals per subject are not a partition

It records, for each participant, the interval of their recall during which they were describing a
given scene of the film. It is the labelling the plan's §5 adjudication track was proposed to buy,
and it supersedes that proposal at scene granularity.

## 2. Clock and units, established before use

`Onset` and `Offset` are TR indices at 1.5 s. Multiplying the last coded offset by 1.5 lands within
0.2 to 11.3 seconds of that participant's final spoken word for 15 of the 16 subjects, which fixes
both the unit and the alignment. The pipeline reads column index 1 of the recall CSV, the Princeton
clock, which is the clock this agreement was measured against, so no offset correction is applied.

## 3. Participant mapping, and the two exclusions

Recall duration was unconstrained, so durations are idiosyncratic and function as fingerprints. The
mapping recovered from them follows the alias structure `docs/data/sherlock/alias-map.json` already
documents: identity up to subject 4, then a skip over the omitted source.

| Gold subject | Participant |
|---|---|
| 2, 3, 4 | NN02, NN03, NN04 |
| 5 … 16 | NN06 … NN17 |

- **NN05 has no gold**: it is the source the public alias set omits.
- **NN01 is excluded.** Gold subject 1 is coded continuously across 27 scenes to 1417 s, while
  NN01's released transcript ends at 782 s. Every other subject matches to within seconds, so this
  is a real discrepancy rather than rounding, most likely a partial transcript in the 2023
  re-release. It is excluded rather than guessed at, and this exclusion is fixed here, before
  scoring, so it cannot become a post-hoc choice.

This leaves **15 participants with gold**: 10 of the 11 development, 5 of the 6 untouched.

## 4. Labelling rule

A recall unit's gold scene is the scene whose interval, in Princeton seconds, contains that unit's
`recallOnsetSeconds`. A unit falling in no interval has **no gold** and is missing, never counted as
wrong: the participant was not describing a codeable scene, which is a fact about the recall and not
an error by the aligner. Missing units are reported as coverage.

A unit's predicted scene is the leading integer of the report's `group` column.

## 5. Outcomes, fixed here

1. **`scene-exact`** — the predicted scene equals the gold scene. Primary.
2. **`scene-within-1`** — the prediction is the gold scene or an immediate neighbour. Secondary,
   because scene boundaries are themselves coder judgements and an off-by-one is a near miss.
3. **`scene-distance`** — median absolute difference in scene index. Reported, not decisive.

Inference is a paired bootstrap over units, clustered by participant, plus a per-participant paired
comparison. Both are reported; neither is chosen after seeing which is kinder.

## 6. The comparison, and the limit on it

**Exactly one comparison is pre-specified**: the shipped configuration (lexical blend 0.8 with lemma
fields, ordering prior 1.5) against the unblended channel at prior 1.0. Both were frozen and landed
before this gold was obtained, so neither was chosen with any knowledge of it.

Development and untouched participants are reported separately and then pooled, and the split is the
one drawn by seed 20260902 long before this file.

## 7. Anti-overfitting rules, binding on later work

The corpus has one film and 15 gold participants. It cannot survive being iterated against.

1. **The gold is an evaluation set, not a tuning surface.** No arm may be selected by its gold score
   in this pass.
2. Any future configuration chosen using gold must be chosen on **development participants only**,
   with the untouched five untouched, and must say so wherever it is reported.
3. Every comparison scored against gold is **counted and reported**, so that a later reader can
   discount for multiplicity. This pass adds one.
4. A gain on gold that contradicts the gold-free proxies is reported as a contradiction, not
   silently preferred because it is the newer number.
5. Scene granularity bounds the claim: 50 scenes, so nothing here measures within-scene precision,
   and the word "calibrated" remains barred by one film regardless of gold.
