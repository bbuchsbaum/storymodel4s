# Create a local Atlas investigation

The `studyWorkspace` JVM command prepares ordinary source files and a recall cohort,
runs the existing paired lexical mapping facade, and writes one inspection packet
per completed observation. A root completion manifest records every attempted
participant and unit, including failures. These imported records support inspection;
opening them does not reproduce or certify their inference.

Use a standalone source checkout. The local qualification uses sbt 1.12.14
running on Homebrew Java 25.0.1; the repository pins Scala 3.7.4 and its build
dependencies. Check sbt's startup banner for the JVM it actually selects. The lexical workflow needs no downloaded
model, provider credential or network service at runtime. sbt may download build
dependencies on the first invocation. This is the tested source-checkout entry point;
no packaged installer is implied.

Generate the small configuration templates:

```sh
sbt 'pipeline/runMain storymodel4s.pipeline.studyPrepare template'
sbt 'pipeline/runMain storymodel4s.pipeline.mappingRun template'
```

Save the emitted JSON as `study.json` and `mapping.json`. Set the source path and
participant/session recall paths as described in [Study inputs](study-inputs.md).
Paths are relative to the study configuration. Keep `timing: null` for untimed
recalls. The source may be text, a checked text artifact, a supplied surface atlas,
a validated narrative model, or explicitly clocked annotations. A raw video file
still requires a configured perception implementation.

## Admit recall disclosure

Ordinary local text remains usable for local preparation and mapping. Publishing
it into an inspection workspace, paired exchange, or weighted exchange requires a
retained, text-bound basis:

- For the digest-bound public Sherlock exports, add
  `"admission":{"kind":"sherlock","sourceId":"recall-source-01","lineage":"recall-lineage.json"}`
  to the corresponding recall entry. Its `path` names the released CSV. The
  adapter checks the pinned lineage, exact CSV bytes and rows, then deterministically
  replays the word column. The receipt identifies that transformation.
- The Scala API accepts a genuine `PseudonymizedText` through
  `RecallDisclosure.certified` and `StudyPreparation.prepare`'s disclosure map.
  The prepared recall retains that actual witness. A JSON flag, redacted-looking
  string or imported receipt cannot substitute for it.
- `"admission":{"kind":"project-synthetic-control"}` admits only the fixed
  synthetic text `Anna arrived.` used by the executable examples and tests.

These bases authorize the local workspace scope. The serialized receipt describes
that basis; it does not recreate authority or grant Git/hosted redistribution.
Prepared recall version 2 records the description. Contextual decode of an admitted
recall requires the retained input; ordinary decode restores only local-only
checkpoints.

## Validate, run, and inspect

Capture the producer revision from the source checkout:

```sh
producer_revision=$(git rev-parse HEAD)
sbt 'pipeline/runMain storymodel4s.pipeline.studyWorkspace validate study.json mapping.json'
sbt "pipeline/runMain storymodel4s.pipeline.studyWorkspace run study.json mapping.json source-cache investigations/attempt-1 $producer_revision"
```

Validation reports disclosure readiness and requested capability readiness. It
creates no workspace. Run refuses before creating cache/output if a prepared recall
lacks its disclosure basis. Exit 0 means complete, 3 means an attempt has failed,
4 means a requested capability is unavailable, and 2 means refusal. Read the
manifest alongside the exit code: execution completion is distinct from capability
readiness and scientific qualification.

The paired records retain separate reference normalized score mass and structured
reconstruction model posterior. Neither is a calibrated correctness probability.
The workflow resolves the named mapping profile once; it never replaces a failed
input with a sample story or derives the source from its recalls.

Open an `<observation>-workspace.json` packet in the matching StoryAtlas consumer.
The version 0.2 canonical workspace archive uses the existing role/manifest/byte
checks with a `SourceInput` role for ordinary text and annotation sources. It retains
both original mapping documents, the checked recall inventory, provenance description,
capability declaration and exact artifact identities. Text inspection does not
require media or timing. Narrative graph visualization requires an actual narrative
model; timed annotation coverage does not establish video perception or movie-edition
identity.

## Reuse and resume

The source is prepared once for the cohort and cached as
`source-cache/<source-edition>/source.json`. For later participants, point the source
entry at that artifact using `format: "checked-text"` with the same segment kind,
or `format: "checked-timed"` for annotations. This replays the cached preparation;
the original raw source is no longer required. Keep the cache within the configured
study root because input path containment still applies. Write the later cohort to
a new attempt directory; earlier participant packets remain unchanged.

Add `--resume` to retry the exact command. Resume compares the complete expected
manifest and every output byte, including configuration, producer revision, input
identities and results. It rejects incomplete, corrupt or foreign output and never
overwrites a historical investigation. New source bytes or segmentation produce a
new identity. Completion is published last.

## Exact structural-weight sweep

The separate `mappingWeight` CLI publishes a paired base plus requested weighted
members:

```sh
sbt 'pipeline/runMain storymodel4s.pipeline.mappingWeight study.json mapping.json 0/1,1/2,1/1 weighted-output'
```

Weights are exact reduced signed-64-bit fractions in `[0,1]`. Zero and one return the
unchanged endpoint records and exchange bytes. Interior weights scale every named
Sherlock transition coefficient, including external entry and persistence, at the
fixed preset temperature. They execute a real posterior with raw decisions and no
fill. They do not mix the two endpoint measures or establish endpoint continuity.
Organization and order readouts remain unavailable under this profile.

The independent Python readers under `examples/mapping` validate exported wire
identities, supports, outcome denominators, measures and receipts. They do not
construct live execution authority. Exact source/consumer revisions, commands and
qualification receipts are recorded in the workflow evidence directory; a codec
pass alone is not a browser-opening claim.
