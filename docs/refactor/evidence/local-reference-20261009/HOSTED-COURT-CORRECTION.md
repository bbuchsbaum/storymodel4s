# Receipt court setup correction

The initial execution of `hosted-receipt-court.py` at source `191b3292` and an intermediate
rerun exited one while preparing the wrong-suite-count fixture: raw logs retain ANSI codes
around the suite name, so the contiguous suite/status substitution matched zero occurrences.
The first diagnosis incorrectly attributed this to checkout-line formatting; line 45 of the
committed driver and the raw log establish the actual suite-count cause. These were setup
assertions, not qualified refusals. Neither attempt contributes an acceptance count. Individual
subprocess observations were not saved; the intermediate driver's exact bytes, captured
top-level log and actual-exit metadata are preserved in `runs/hosted-receipt-court-setup*`.

The corrected mutation identifies the normalized suite completion line, then changes only its
count or completion marker in the original raw bytes. The checkout mutation changes only the
SHA on the line following `git log -1 --format=%H`. The complete rerun has an actual exit,
all per-case observations and script hashes. The current collector/verifier independently
strip ANSI only for parsing and retain original archived bytes.
