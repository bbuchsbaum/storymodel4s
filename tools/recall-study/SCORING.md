# Fixed-support Sherlock scene scoring

`gold_scene.py` compares two complete sets of scene outcomes against a previously frozen
population. It measures localization, not recovery of recall organization or calibrated
confidence. The old positional CLI and prediction-dependent `label` helper are retired.
`score_ladder.py` refuses before reading gold until its separate readout-migration ticket
adopts this contract. Do not use a historical script to bypass the fixed denominator.

## Freeze observation identity

Export from a committed revision with the actual pipeline segmenter. This opens raw recall
observations, partition metadata and admitted annotation bytes, but no predictions or gold.
Keep the result beneath the ignored data root. Only hashes and aggregates belong in git.

```sh
sbt 'embedBench/Test/runMain storymodel4s.bench.sherlock.SherlockUnitManifest ABS_DATA_ROOT ABS_REPO/docs/data/sherlock/recall-lineage.json ABS_OUTPUT.json CODE_SHA'
```

The manifest binds all 17 participants to their own admitted CSV bytes, source annotation
identity, partition, shared scene rule, unit IDs, ordinals, text hashes and optional onsets.
Missing timing does not remove a transcript unit. An existing output is never overwritten.
Use absolute paths: the forked `embedBench` process runs from the module directory.

## Freeze gold support before opening predictions

Real gold access still requires the study's ledgered evaluation procedure. The following
commands describe that future operation; this implementation was tested with synthetic gold.

```sh
python3 tools/recall-study/gold_scene.py freeze-support MANIFEST.json GOLD.csv SUPPORT.json --partition development
```

Record the printed **canonical support SHA256** in the evaluation ledger before importing
predictions. It differs from the pretty-printed file's byte SHA. Canonicalization is UTF-8 JSON
with sorted keys, compact separators, unescaped Unicode and non-finite numbers forbidden.
Comparison requires that independently retained pin. Recomputing a changed support's pin is
a new evaluation artifact, not a continuation of the old comparison.

Eligibility uses only the shared gold rule, intervals and frozen unit onsets. The record
preserves every input row, including exclusions and missing timing. NN01 remains excluded
for its inconsistent clock; NN05 has no gold source. The existing first matching closed
interval in sorted order is retained, including its boundary behavior.

## Import and compare complete arms

```sh
python3 tools/recall-study/gold_scene.py import-tsv MANIFEST.json ARM_A_DIRECTORY A.json --partition development
python3 tools/recall-study/gold_scene.py import-tsv MANIFEST.json ARM_B_DIRECTORY B.json --partition development
python3 tools/recall-study/gold_scene.py compare MANIFEST.json SUPPORT.json A.json B.json COMPARISON.json --support-sha256 FROZEN_PIN
```

TSV import requires exactly the selected participant files, with every original ordinal,
text checksum and onset. Optional `unitId` must agree. Extra TSV files refuse. Old empty
groups become abstentions; malformed groups become invalid outcomes. New adapters can
emit the versioned JSON arm directly with `label`, `abstention`, `invalid` or
`provider-failure`; invalid/failure outcomes require a reason. Nonlabels cannot carry a scene.

Missing, duplicate, extra or changed units refuse before scoring. All gold-eligible units
have weight one in both arms and every resample. Nonlabels count wrong. No-gold units remain
in outcome and coverage accounting but outside localization denominators.

The primary result is **B minus A**, averaged across eligible participants. The pooled
unit-weighted difference is secondary. `scoring-config.json` fixes the paired participant
cluster bootstrap, seed, resample count and percentile indices. Outputs include per-person
differences and leave-one-participant-out results; fewer than two eligible participants
give an explicitly undefined CI. The median scene distance applies only to labelled eligible
units and must be read with its coverage, not substituted for the primary result.

Transition coverage counts original adjacent units only. It never bridges unresolved units.
These are coverage counts, not scientific estimates of backward transitions or persistence.

## Reproduce synthetic checks

```sh
python3 tools/recall-study/tests/test_gold_scene.py -v
python3 tools/recall-study/mutate_scorer_checks.py
sbt 'embedBench/testOnly storymodel4s.bench.sherlock.SherlockGoldRuleSuite' 'embedBench/Test/runMain storymodel4s.bench.sherlock.SherlockGoldRuleWitness ABS_NEW_WITNESS.json'
python3 tools/recall-study/tests/test_gold_rule_single.py --scala-witness ABS_NEW_WITNESS.json
```

The witness runs actual Scala and Python code. The Python test without a Scala export checks
only the preregistered numeric oracle, and labels that narrower result explicitly.
