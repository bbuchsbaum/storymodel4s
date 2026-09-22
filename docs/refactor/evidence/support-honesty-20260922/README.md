# Support honesty qualification, 22 September 2026

Mote `bd-01M19956MFSG7076QE4J66T7E9`, M1 prerequisite. The existing seven-commit
`solo/support-honesty` branch was integrated onto `f573087e`, repaired after a
separate cold review, and qualified at `6de935f1`.

The review exposed two package-visible factory bypasses. Both concrete
reproducers failed before repair: inconsistent support and a nonfinite external
cost could enter HsmmResult. Checked producer factories now refuse them; both
inference paths propagate invalid external costs as typed errors. Invalid default
model configuration can throw before producing a record; this does not certify
arbitrary model execution or bind cached totals to an authenticated invocation.

The full `sbt -batch checkAll` gate passed 7,215 tests, with five named optional/live
skips, zero failures/errors, and formatting checks green. StoryAtlas at `39be6b7b`
passed 245 tests plus compile, formatting and app linking against this producer.
Exact commands, overrides, JUnit reports, raw logs and warnings are retained in
[provider-gate.json](provider-gate.json) and [consumer-gate.json](consumer-gate.json)
and their adjacent archives. Warnings in unchanged tests and the toolchain remain
visible; this is not a warning-free-build claim.

Six compiled mutations were killed: empty eligibility as full support, zero
eligible weight as full support, the wrong external reason, each unchecked
producer, and a package-visible CostBreakdown constructor. The constructor mutant
and restored control used clean compilation. See [mutations.json](mutations.json),
[restored-control.json](restored-control.json), and [review.json](review.json).

The first full attempt was interrupted to fix the review finding. The next full
attempt failed on a Scala.js test-message formatting assumption (`1` versus `1.0`),
not a wire or scientific-value difference. Its corrected exact-message assertion
passed 20 tests on each backend before the final green gate. These attempts remain
in the archives, separately from successful evidence.

[golden-parity.json](golden-parity.json) independently compares all WOG fields
except schema/support against the platform-labelled v3 baselines. JVM/JS and
Native both match exactly. The suites also prove generated v4 bytes equal those
goldens. The prior D1B Sherlock baseline is unchanged; no private-corpus replay
was performed in this qualification. hsmm/v4 continues to refuse non-text
physical support; physical coordinates remain the subsequent mapping-record task.

[qualification.json](qualification.json) binds the exact producer/consumer pair
and dependency pins. This closes only support honesty, not G1 or the M1 browser
journey. No push, deployment or calibration is claimed.
