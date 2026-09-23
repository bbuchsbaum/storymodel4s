# Mapping exchange component

`MappingExchange` produces analysis files from one checked `MappingResult`.
`mapping.json` remains the authoritative `mapping-record/v0.1`; the TSV files are
lossless projections of that same record. The manifest binds its source, recall
inventory, segmentation, policies, roles and exact payload bytes.

This is the single-record component. The public facade's paired reference,
reconstruction and λ outputs still need their shared evidence/profile envelope.
The base package explicitly marks that capability unavailable, together with recall
timing, temporal projection, scanner alignment, organization and calibration.
Support geometry does not imply any of those capabilities.

For a JVM caller already holding the checked record and its context:

```scala
import java.nio.file.Path
import storymodel4s.codec.ExpectedMappingContext
import storymodel4s.pipeline.MappingExchangeBuild

val written = MappingExchangeBuild.write(record, Path.of("analysis/mapping"))
val restored = MappingExchangeBuild.read(
  Path.of("analysis/mapping"),
  ExpectedMappingContext(record.inventory, record.source, derivationContext)
)
```

The parent directory must exist and the destination must be new. The writer
publishes `manifest.json` only after every payload finishes. Interrupted output is
incomplete and cannot be read as a valid package. Existing output is preserved.

For a saved joined workspace, select the policy explicitly:

```scala
import storymodel4s.view.ArtifactId

val exported = MappingExchangeBuild.exportWorkspace(
  Path.of("workspace.json"),
  ArtifactId.unsafe("authored-control-a"), // use an actual policy ID in the workspace
  Path.of("analysis/mapping")
)
```

This entry point reads strict UTF-8, checks the complete workspace join and its
actual export permission, then copies the selected checked record without inference.
Inspection permission alone does not permit export. Denied imports and unknown
policies refuse before creating output. The returned receipt binds the exact input
file bytes, policy ID, mapping digest and published manifest digest; whitespace
changes in the input therefore change the input digest. CLI dispatch uses this
same producer boundary.
There is no standalone mapper command in this component; the prepare/CLI facade
owns ordinary-file input admission and inference execution.

| File | Contents |
|---|---|
| `mapping.json` | Complete canonical checked-record wire |
| `words.tsv` | Every word, UTF-16 offsets and explicit membership/unassignment |
| `units.tsv` | Unit order, spans, word membership and decomposition status |
| `targets.tsv` | Full source dictionary, hierarchy and support coverage metadata |
| `target-support.tsv` | Exact support status, native/composed anchors, unions and points |
| `alternatives.tsv` | Original measures, channel, destination, full posterior state and normalization domain |
| `measure-metadata.tsv` | Per-unit scale, prior, stage and derivation metadata |
| `decisions.tsv` | Every unit's outcome, including failure, localization and actual decision |
| `links.tsv` | Candidate sets, gates, fidelity and term-support accounting |
| `stages.tsv` | Stage IDs and exact provenance/assumption receipts |

Every table row carries `mapping_digest`. The declared target universe remains in
the manifest's policies; it is distinct from the full target dictionary. Copied
`support_coverage` is the existing immediate-child field, not a claim of transitive
temporal coverage. External destinations are explicit states. Failed units retain
their outcome rows even when they have no alternatives. No measure is renormalized.

TSV is UTF-8 without BOM, tab-delimited, with every cell double-quoted, embedded
quotes doubled and LF record endings. Row counts mean parsed records. The manifest
declares each column as a string, decimal integer, canonical JSON, or canonical
binary64 bits (`0x` plus 16 lowercase hex digits). Exact ticks and rational values
inside JSON cells remain decimal strings. Structured absence differs from a
present empty string. Unpaired UTF-16 in a direct TSV string refuses publication;
canonical JSON retains the existing ASCII-escaped wire behavior.

The package adds no transcript column but preserves caller-supplied labels,
failure reasons and receipts. It is not an anonymized export.

The independent Python reader uses only the standard library:

```sh
python3 examples/mapping/read_mapping.py --directory analysis/mapping
```

It checks byte hashes, fixed schemas, complete table projections and relational
joins. Its `read(files)` API returns the record and typed tables for analysis.
It does not replace Scala's `ExpectedMappingContext` admission of bound inference
results. Scala decodes the canonical record in that context, regenerates every
file, and compares the complete package, so a changed table with a recomputed hash
still refuses.

A generated, authored normalized-mass example is included:

```sh
python3 examples/mapping/read_mapping.py --directory examples/mapping/miniature
```

Its eight units include one failed unit; the seven successful units each supply
0.5 on a located source target, 0.25 on an unlocated source target and 0.25 on an
external state. These are authored controls, not estimated or calibrated probabilities.
For a fresh producer run, execute `codecJVM/Test/runMain
storymodel4s.codec.MappingExchangeFixture`, save the log, then run the reader with
`--fixture-log LOG` and `check_corruptions.py LOG`. The additional raw-score and
large-tick controls distinguish decoded choice from raw argmax and exercise exact
coordinates above the binary64 integer limit.
