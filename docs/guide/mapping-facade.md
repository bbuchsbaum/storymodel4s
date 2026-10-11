# Analyze, inspect and export local files

Start with the [ordinary study input guide](study-inputs.md). Save the study template beside your
source and recall files. The baseline runs offline and requires no model download:

```sh
sbt 'pipeline/runMain storymodel4s.pipeline.studyPrepare template'
sbt 'pipeline/runMain storymodel4s.pipeline.mappingRun template'
sbt 'pipeline/runMain storymodel4s.pipeline.mappingRun run study.json mapping.json analysis'
sbt 'pipeline/runMain storymodel4s.pipeline.mappingRun exchange study.json mapping.json exchange'
```

The first two commands print JSON templates; save them as `study.json` and `mapping.json`.
Use a new destination for each publication, or `--resume` to verify and reuse exactly matching
bytes. An existing or corrupt destination refuses instead of being overwritten.

The exchange includes recall text and therefore requires the existing text-bound disclosure
admission. Ordinary local recalls can be analyzed but their text export refuses. For a runnable
public control, use exactly `Anna arrived.` as the recall (without a final newline) and add
`"admission":{"kind":"project-synthetic-control"}` to that recall entry in `study.json`.
This setting admits only that fixed project control; it cannot authorize arbitrary participant
text. Public Sherlock intake uses its checked lineage route. See the workspace guide for those
existing admission paths.

Scala callers can use the same public boundary without constructing mapping proof objects:

```scala
import java.nio.file.Path
import storymodel4s.pipeline.MappingPipeline
val config = MappingPipeline.Config(
  MappingPipeline.LexicalProfile, 1.0, 20, 200,
  Vector(MappingPipeline.Capability.Mapping)
)
val result = for
  batch <- MappingPipeline.analyze(Path.of("study.json"), config)
  summary = MappingPipeline.inspect(batch)
  receipt <- MappingPipeline.exportResults(batch, Path.of("exchange"))
yield (summary, receipt)
```

Inspection returns plan identity, attempts, completed and failed counts, and missing requested
capabilities. It excludes source and participant text. Export retains both Reference and
Reconstruction with their distinct measure kinds and checked identities. It does not authorize
publishing recall text or manufacture provider registration; those existing checks still apply.

`mappingRun` exits 0 for a complete ready batch, 3 for a valid batch with failed observations,
4 when observations succeeded but a requested capability is missing, and 2 for refusal before
publication. A partial batch takes precedence over missing capabilities; inspect the outcomes
and readiness separately. Requesting ONNX through the ordinary lexical path refuses until the
caller supplies its checked registration to the typed ONNX entry point.

`FacadeFailure` retains I/O operation, exact local path and exception class for typed callers.
Its `diagnostic` JSON hashes paths and omits exception messages, because filenames and parser
errors can contain private text. Configuration parse errors use fixed codes. Legacy low-level
publication methods remain source compatible; the new facade returns typed failures.

`storyBuild` distinguishes a completed invalid draft (exit 3) from a validated complete run
(exit 0), an incomplete run (exit 1), and a run that could not start (exit 2). Its summary validates
the actual emitted draft, including feature-stage changes. Passing validation is a structural
result; partial coverage and empirical limitations remain in the receipts.
