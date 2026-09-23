# Workspace exchange CLI process court

Nine real JVM process invocations pass; both authored policies produce exact
workspace mapping bytes and are independently read by the Python package reader.
The refusals cover existing output, unknown policy, a freshly checksummed
inspection-only/export-denied archive, malformed UTF-8, missing arguments and an
invalid policy ID. Help exits zero. Every refusal exits **2** with no stdout;
success returns its input/policy/mapping/manifest receipt. Output directories stay
absent for permission/admission refusals, and existing bytes are preserved.

`qualification.json` binds the peer build and source hashes; `cases/process-receipt.json`
records argv, exits and stream digests. Full stdout/stderr is retained, including
JVM runtime warnings. `workspace-exchange-cli-process.log.meta.json` records the
independent probe command. Reproduce with `examples/mapping/check_cli.py`, a
`pipeline / Runtime / fullClasspath` file and the tracked M1 Bell workspace.

This process court adds to the focused 11-test control and does not replace the
required full merged checkAll gate. Only synthetic/precomputed mappings were used.
