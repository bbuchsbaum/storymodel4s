# Analyze, inspect and export local files

Start with the [ordinary study input guide](study-inputs.md). Save the study template beside your
source and recall files. The baseline runs offline and requires no model download. With the
extracted CLI bundle on your path, run:

```sh
storymodel4s studyPrepare template > study.json
storymodel4s mappingRun template > mapping.json
storymodel4s mappingRun run study.json mapping.json analysis
storymodel4s mappingRun exchange study.json mapping.json exchange
```

The first two commands save JSON templates; edit `study.json` to name your files.
Use a new destination for each publication, or `--resume` to verify and reuse exactly matching
bytes. An existing or corrupt destination refuses instead of being overwritten.

The bundle needs Java 17 or newer on macOS or Linux. Extract it, run `chmod +x bin/storymodel4s`
inside its directory, and add that `bin` directory to your path. A public binary release is
pending; maintainers can build the ZIP with `sbt 'pipeline/cliDistribution'` from a clean
checkout. Its manifest binds the source revision and packaged files. For a source invocation,
replace `storymodel4s mappingRun ...` with
`sbt 'pipeline/runMain storymodel4s.pipeline.mappingRun ...'`. The
[complete file walkthrough](../../docs-site/src/content/docs/method/map-files.mdx) includes an
executable synthetic control, independent Python readback and weights `0/1,1/2,1/1`.

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
and readiness separately.

For local neural scoring, `mappingRun onnx-template` prints a configuration for the pinned
`sentence-transformers/all-MiniLM-L6-v2` model. Place its `model.onnx` and `tokenizer.json` files
beside the settings file, or use relative paths beneath that directory. The adapter checks the
catalog's exact artifact hashes before loading native code. There is no automatic download or
fallback. Create a local receipt key once and keep it for reproducible replay:

```sh
umask 077
openssl rand -out embedding.key 32
storymodel4s mappingRun onnx-template > mapping-onnx.json
```

The saved `mapping-onnx.json` has an `onnx` object naming `model`, `tokenizer`,
`keyFile`, and `keyId`; the key file contains exactly 32 raw bytes. Keep it in private local
storage. Receipt hashes are keyed for both source and recall text. Paths and key bytes are
excluded from published metadata; changing the key changes execution identity. The ordinary
commands `mappingRun`, `mappingWeight`, `studyWorkspace`, and `recallVolumes` share this
configuration. Scala callers can pass the settings path as the second argument to
`MappingPipeline.analyze`. Both the distributed command and sbt pipeline launcher disable ONNX
telemetry before starting their child JVM; other launchers must set `ORT_DISABLE_TELEMETRY=1`
before JVM startup.

The ONNX profile supports checked text and timed annotation sources. Timed rendering uses each
segment's embedding text when present, otherwise its description; groups use their embedding
text or label. Receipts retain a distinct rendering policy and the complete checked source.
It uses half-cosine semantic cost with independent reference and the declared Sherlock
reconstruction profile. This is not an assertion of historical benchmark-preset equivalence.
Joined video sources use the separate caption profile: ONNX caption fusion is unavailable and
refuses explicitly. Annotation and video comparisons must name the actual scoring profiles.

`FacadeFailure` retains I/O operation, exact local path and exception class for typed callers.
Its `diagnostic` JSON hashes paths and omits exception messages, because filenames and parser
errors can contain private text. Configuration parse errors use fixed codes. Legacy low-level
publication methods remain source compatible; the new facade returns typed failures.

`storyBuild` distinguishes a completed invalid draft (exit 3) from a validated complete run
(exit 0), an incomplete run (exit 1), and a run that could not start (exit 2). Its summary validates
the actual emitted draft, including feature-stage changes. Passing validation is a structural
result; partial coverage and empirical limitations remain in the receipts.
