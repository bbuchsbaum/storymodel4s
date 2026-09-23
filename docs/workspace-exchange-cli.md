# Export a selected workspace mapping

The JVM command `storymodel4s.pipeline.mappingExchangeExport` exports one existing
checked workspace policy to the [mapping exchange format](mapping-exchange.md).
It runs locally without a provider or inference call. Ordinary-file preparation
and the complete study command remain separate work.

From a source checkout with the project's JDK and sbt requirements installed:

```sh
sbt -batch 'pipeline/runMain storymodel4s.pipeline.mappingExchangeExport workspace.json authored-control-b analysis/mapping'
python3 examples/mapping/read_mapping.py --directory analysis/mapping
```

Use an actual policy ID from the workspace; there is no default policy. The three
positional arguments are workspace path, policy ID and output directory. The output
parent must exist and the output directory must be new. This is a source-checkout
entry point, not a claim that a standalone binary installer is available. `--help`
prints the usage and exits successfully.

Successful export writes one JSON receipt to process stdout and exits zero:

```json
{"schemaVersion":"workspace-mapping-export-receipt/v0.1","status":"complete","input_sha256":"...","policy_id":"authored-control-b","mapping_digest":"...","manifest_sha256":"..."}
```

The receipt binds the exact input bytes, explicitly selected policy, checked mapping
and completed exchange manifest. sbt adds its own log lines around the child
command; retain the JSON receipt line separately when using `runMain`.

The command refuses invalid argument counts, invalid policy identifiers, unreadable
or invalid UTF-8 input, wrong joins, absent export permission, unknown policies and
existing output. A refusal writes one JSON diagnostic to stderr and exits 2; it
does not print a success receipt. Diagnostics omit raw paths, transcript text and
nested producer error payloads. Inspection permission does not imply export
permission. The producer checks that permission before creating output.

Payloads finish before `manifest.json` is published. A write failure can leave an
incomplete directory; it must not be used as a completed exchange. Existing output
is preserved. The independent reader checks the manifest and complete payloads.

The exchange retains the record's original measures, policy labels, every outcome
and exact identities. Exporting a reconstruction does not make it a reference
result, and the single-record package does not claim paired-profile availability.
