# Qualification after the M1 producer merge

Exact tested candidate `205c1582bfaedca9de3fe2202fcdc307d3a27e06` combines the
temporal/scanner slice with M1 producer `7b2f076a`. Full `checkAll`,
`scalafmtCheckAll` and the recorded query/scanner fixture commands exited 0:
**8,289 passed, five optional live skips, zero failures/errors**, across 56 test
tasks and 768 fresh JUnit reports. The 23 earlier killed mutations still bind to
identical executable source hashes. Both independent Python readers passed the
exact outputs and their six corruption controls.

See [qualification.json](qualification.json) for command/exit/task receipts and
[raw-evidence.tar.gz](raw-evidence.tar.gz) for full logs and fresh JUnit reports.
Archive SHA-256: `7d301b64437bf32aa656c8c65308fb86523a1bbbf31eaaaa666051e4041af043`.
Subsequent merges of `1e1e8dae` and `f3464a2d` change documentation only;
executable gate inputs remain identical to the tested candidate.

This establishes the checked conditional query, scanner declaration and generic
view projection contracts. M1 neutral supplied-artifact provenance is now present.
Actual media package admission, physical scanner-origin joins, full CLI exchange,
paired mapping profiles and the Atlas browser acceptance court remain separate.
