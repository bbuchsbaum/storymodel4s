# Production intake and temporal-support qualification

Base `6de00cfd`; implementation `7c9f9f04`; tested candidate
`621a7249f0c1fb3b62c4e3ca95ddea331edab3a2`. Executable inputs were committed and
unchanged throughout the gate. Subsequent changes only document qualification.

`checkAll scalafmtCheckAll` completed with exit 0: **8,015 passed, five optional
live skips, zero failures/errors**, bound to 56 test tasks and 726 fresh JUnit
reports. The same invocation ran the production intake CLI on the committed
synthetic job and exported three checked support fixtures. Independent Python
readers verified those exact outputs, including Fraction arithmetic beyond
2^53, with five deliberately corrupted-output controls rejected.

Ten compiled source mutations were killed by named tests. Both unmutated controls
passed all 23 focused JVM tests. Mutants cover byte and inventory binding, explicit
clock selection, complete record retention, transitive missingness, cycle refusal,
occurrence provenance, half-open points, contextual decoding and atomic completion.
A separate cold review found and prompted repairs to occurrence provenance and
partial manifest publication; it did not independently execute the final gate.

[qualification.json](qualification.json) binds commands, exit status, source pins,
log and test-report hashes and independent-reader receipts.
[raw-evidence.tar.gz](raw-evidence.tar.gz) preserves full logs, initial failed
compile/test attempts, mutation receipts, review notes and synthetic outputs.
Archive SHA-256: `46394d5fc6302c71001ef81315380c6b95d608162d62b2a41f7b29b72ea75259`.
The exact command and local grakern substitution are recorded in the qualification
receipt; the dependency uses its pinned `0329c43c88a0b71e9aa4456723bb16bac2fa3841`.

This qualifies synthetic intake and supplied-support contracts. It does not
establish scanner/recording linkage, temporal allocation, calibrated probabilities,
held-out mapping efficacy or StoryAtlas acceptance. The legacy source coverage
field remains immediate-child accounting and must not license temporal allocation.
