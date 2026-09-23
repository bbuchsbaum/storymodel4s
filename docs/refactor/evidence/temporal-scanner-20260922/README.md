# Temporal-query, scanner-declaration and view-handoff qualification

Tested merge-result candidate `b8f22d0091f0427f779b86ac5e7e90a828da4070`, based on
main `408178ef809442022868792380ba9b8d08fcf673`. Executable inputs were committed
and unchanged during the gate. Later changes only record documentation/evidence.

`checkAll scalafmtCheckAll` and the exact synthetic producer commands exited 0:
**8,079 passed, five optional live skips, zero failures/errors**, bound to 56 test
tasks and 738 fresh JUnit reports. Existing environment warnings for clang 15,
Scala runtime Unsafe and multiple main classes are preserved in the full log.

All 23 compiling mutations were killed by their named tests: ten temporal-query,
ten scanner and three view-handoff faults. Before/after controls passed and the
restored source hashes match the candidate. Separate read-only cold review prompted
repairs to mixture decoding/identity, media domains, occurrence timebases and
malformed rational rejection; it did not independently execute the gate.

The independent Python readers accept the exact candidate outputs and reject six
corrupted-output controls. Query checks use authored Fraction answers, all three
platform mixture-identity witnesses and independent canonical identity derivation.
Scanner checks preserve crop/pad/censor distinctions and four exact affine landmarks.

- [qualification.json](qualification.json): command/exit, exact revisions, test totals,
  fresh report hashes, mutation/source controls and independent-reader receipts.
- [artifacts.json](artifacts.json): digests for the directly inspectable
  [temporal query](temporal-query.json) and [scanner sample output](scanner-samples.json).
- [raw-evidence.tar.gz](raw-evidence.tar.gz): complete gate and focused logs, initial
  failed attempts, mutations, reviews, independent outputs and raw fresh JUnit reports.

Archive SHA-256: `99a0d890d793521c2f8c4c7c1b80740b2d3704bc87cad193815caf94347586c8`.
Dependency substitution uses pinned grakern `0329c43c88a0b71e9aa4456723bb16bac2fa3841`.
The command receipt retains the local substitution and staging paths; see the
[query](../../../temporal-queries.md) and [scanner](../../../scanner-crosswalks.md)
guides for reproducible calls. No production provider or participant data is needed.

This qualifies conditional per-unit region/bin queries, declared scanner clocks
and a renderer-neutral projection. It does not establish actual OpenNeuro header/
recording/run/media joins, recall exposure, concentration summaries, calibration,
held-out efficacy, complete ordinary-file facade/exchange or Atlas browser acceptance.
The later [M1 merge qualification](merged-m1/README.md) includes neutral supplied-artifact
provenance. Media-aware source admission remains necessary for a joined temporal view.
