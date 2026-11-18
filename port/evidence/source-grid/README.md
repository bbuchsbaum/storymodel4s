# Scala source-time grid reference

The JVM probe in this directory runs the repository's synthetic
`TemporalProjectionFixtures`. The captured JSON contains one row for each of
fourteen scenarios. Thirteen rows have a computed grid. The failed-processing
scenario records the actual failure. None of these fixtures supplies a recall
recording window.

For each successful row, `input` records the selected coordinate, bins, declarations,
and the components that Scala resolved before computing its grid. `expected.grid`
records the allocated mass, conditional lower and upper bounds for every bin,
the outside mass, and the allocation ledger. Floating-point strings encode the
exact binary64 bits. The Python comparison starts at the *resolved components*
of the Scala normalized-score measure. The Python workflow separately exercises
construction, model inference, native or composed coordinates, and recall duration.

To regenerate, use a standalone clone at the recorded reference revision. Set
`evidence` to the absolute path to this evidence directory in the port checkout.
Run the following from the clone. The JSON is extracted after sbt exits successfully.

```sh
reference=$(git rev-parse HEAD)
cp "$evidence/SourceTimeGridWitness.scala" codec/src/test/scala/storymodel4s/codec/
sbt -Dsbt.log.noformat=true "codecJVM/Test/runMain storymodel4s.codec.SourceTimeGridWitness $reference" > source-grid.log &&
  sed -n '/^{"cases":/p' source-grid.log > reproduced.json &&
  cmp reproduced.json "$evidence/source-time-grid-witness.json"
```
