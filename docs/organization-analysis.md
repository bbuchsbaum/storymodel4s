# Recall organization (1.1)

The organization entry point counts transitions between original recall units while retaining
assignment uncertainty. It consumes a genuine pipeline run's strict local reference member.
Reconstruction and every positive structural weight are refused for this measurement request.

Run from ordinary local study and mapping configuration files:

```sh
sbt -batch 'pipeline/runMain storymodel4s.pipeline.organizationMap /absolute/study.json /absolute/mapping.json Discourse /absolute/output'
python3 tools/recall-study/read_organization.py /absolute/output
```

The mapping configuration uses the existing `mapping-lexical-pair/v1` or registered caption
profile and requests `Mapping`. The organization command separately declares `Discourse` or
`StoryWorld`, the current source level, original segmentation and preservation of unknown
adjacencies. The typed API also accepts genuine registered ONNX runs. It shares preparation,
recall admission, atomic completion and `--resume` verification with the mapping journey.
Exit 0 means complete; 3 retains failed observations; 4 means an empty cohort; 2 refuses invalid
input or publication. Successful siblings and failed attempts remain in the cohort manifest.

For an already executed run:

```scala
val request = OrganizationPipeline.request(run, MeasurementCompatibility.Axis.Discourse)
val organization = request.flatMap(r => MappingPipeline.organization(run, r).left.map(_.toString))
// OrganizationPipeline.publish(value, outputPath)
```

Each output includes both original mapping members, a producer-bound compatibility record,
`organization.json` and quoted `transitions.tsv`. The supplemental record binds the run,
registration, execution, reference record, inventory, source, requested axis and target grain.
Candidate coverage, original support receipts and stage/provider explanations travel with it.
The lower-level mapping ledger retains its unknown upstream provenance. Imported descriptions
cannot mint the producer-owned result; contextual verification requires the genuine run.

Forward, backward and same-target counts use the complete independent local score rows. They
are **score-conditional counts**, with no calibration claim. The backward rate divides expected
backward count by expected comparable count; it is not the expected ratio of a sampled path.
A zero denominator is unavailable. External, incomparable, unresolved and failed adjacencies
are separate. `A → missing → C` retains two unresolved adjacencies. Word display creates no
new observations.

Discourse uses declared source ranks within a part. Across media parts it requires the checked
composition's order, which takes precedence over conflicting local ranks. Redundant native and
composed coordinates do not obstruct a valid comparison. Unlocated media is incomparable when
its applicable order is unknown. Story-world ranks are a separate partial declaration. Equal
ranks of different targets remain incomparable; identical target identities count as same-target.

Conditional bounds range over the explicitly retained admitted destination sets, including
underflow-zero states. Every middle unit has one shared assignment across its two adjacencies.
Each category's minimum and maximum is an individual extremum; the displayed extrema need not
form one jointly attainable vector. Missing candidate universes make bounds unavailable.
The first solver supports Cartesian products; coupled/HSMM bounds are refused. Bounds are not
confidence intervals. The portable numerical API accepts declared independent rows or checked
adjacent joints, but those supplied data carry no reference-execution certificate.

The Python reader independently checks original inventory/mass joins, transition arithmetic,
denominators, TSV values and tiny product bounds. It provides structural and numerical readback,
and never restores execution authority. Large products report that bounds were not enumerated.
Media composition order requires the Scala contextual reader rather than the Python rank oracle.

The frozen synthetic campaign is in
`tools/recall-study/fixtures/organization-recovery/manifest.json`. Generate artifacts from a
clean checked-out producer revision, then compare them independently:

```sh
sbt -batch 'pipeline/Test/runMain storymodel4s.pipeline.OrganizationRecoveryWitness /absolute/manifest.json /absolute/results FREEZE_SHA PRODUCER_SHA'
python3 tools/recall-study/organization_recovery.py \
  --frozen-manifest tools/recall-study/fixtures/organization-recovery/manifest.json \
  --results /absolute/results --output /absolute/recovery-report.json
```

The campaign retains all cases and failures, reports bias and effect attenuation, and checks
committed freeze ancestry. Synthetic arithmetic and language-path results qualify the tested
engineering behavior. Human recovery, calibration and the real annotated-video preview require
their separately named evidence.
