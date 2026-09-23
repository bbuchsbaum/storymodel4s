# Workspace export pairing evidence

Producer commit `6a310fe38e6db8c44aada401e5262d8c56d43b00` passed seven pipeline
tests. Three policies export byte-identical original records; the historical
record is reopened with its stored derivation context. A valid inspection-only
archive, unknown policy and malformed UTF-8 each refuse before creating output.

Both compiling mutants were killed by their named tests: replacing export
permission with inspection permission, and replacing explicit policy selection
with the first policy. Four tests passed before and after mutation, with exact
source restoration. The independent Python reader checks all three emitted
packages and their authored values; substituting the first policy for the second
also fails independently.

[qualification.json](qualification.json) binds commands, totals, source hashes and
the raw archive. [raw-evidence.tar.gz](raw-evidence.tar.gz) retains both initial
failed attempts: an incorrect `ArtifactId` import and a historical readback test
that omitted the required derivation context. Neither failure was counted as a
mutation kill. Archive SHA-256:
`1bfb90d96ed5e40f52b8bad1ce9c539b2290c7b61c413a6be8df78ebd19063a3`.

Pairing: Codex's CLI driver supplied the second-policy/denied-export counterexample;
the exchange driver implemented the producer and these witnesses. CLI dispatch is
a separate integration commit. Full clean `checkAll` remains required before
landing. This component does not complete the paired-profile exchange ticket.
