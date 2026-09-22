# Separate cold review

Reviewer: existing read-only lean_expert continuous_uncertainty_design.

The review found no blocking numerical, accounting, authority or codec defect.
It identified decimal-token interoperability: Python admits `.1`, `1.` and `1.e2`,
while the initial Scala parser refused them. Source commit 1abb4825 aligns these
forms and adds shared emitted acceptance vectors. Python Fraction and the actual
intake agree on all 27 cases; direct token parsing still intentionally rejects
whitespace and blanks, which the cell adapter handles first.

The reviewer inspected source/tests; it did not run gates, access participant data
or qualify scanner alignment. The implementation author separately executed the
checks, the same-core-package malformed-rational attack and compiled mutations.
The first visibility mutation reused stale compile-time test results. That run
is retained as inconclusive; the corrected runner cleans Test before recompiling
constructor probes, and the altered visibility then fails the named test.

Follow-up review at committed d7cf78e3 found no remaining concrete parser,
documentation or independent-checker interoperability issue. The reviewer read
the committed parser while a temporary mutation was active, and did not mistake
that mutant for production code.

The first clean mutant attempt also exposed a cached probe persisting after source
restoration. The runner now cleans and checks the restored constructor immediately,
and each mutation must fail its own named test. Both earlier attempts are retained.
