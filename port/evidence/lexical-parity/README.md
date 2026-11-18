# Scala lexical measurement fixture

The Python test and Scala probe both read `cases.tsv`. From a standalone clone of this
revision, run the Scala feature track code on the synthetic source and lexicon.

```sh
cp port/evidence/lexical-parity/FeatureMeasureParityProbe.scala features/src/test/scala/storymodel4s/features/
sbt "-Dfeature.measure.parity.fixture=$PWD/port/evidence/lexical-parity/cases.tsv" 'featuresJVM/testOnly storymodel4s.features.FeatureMeasureParityProbe'
```

The `result` in `results.json` records the source, lexicon and observations printed on
the `FEATURE_MEASURE_PARITY` line. The Python regression compares surface forms and all
three measures against these values. This fixture covers six surface occurrences. The
Sherlock word-onset workflow additionally respects the word occurrences supplied by its CSV.
