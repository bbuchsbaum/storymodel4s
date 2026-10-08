# Hosted count correction

The initial c401 receipt counted 12,656 passed tests over 76 totals from `gh run view --log`.
Comparing complete original job logs retrieved through the job-log API shows that the rendered
log lacks the final pipeline (120 passed) and corpus-intake (159 passed) totals in each JVM job.
The observed rendering discrepancy does not establish its cause. No tests were rerun for this
correction.

The corrected c401 count is **13,214 passed, zero failed/errors and 12 skipped over 80 totals**:
24 totals per JVM job and 16 per JS/Native job. The original rendered receipt is preserved at
`publication-c401-original-rendered.json`; `publication-c401.json` now binds all four complete raw
job logs and exact run metadata. Earlier immutable Mote progress notes retain the original count;
the later qualification note records the correction.

The same discrepancy occurs in the rendered 658b log: its 76 parseable totals sum to 12,674 passed.
All four complete raw job logs instead give **13,232 passed, zero failed/errors and 12 skipped over
80 totals**. The increase of 18 from the corrected previous head is the nine new parity tests on
each of JVM 17 and 21. Each suite reports zero failures and zero ignored tests; this hosted
reporter prints suite summaries rather than individual successful case names.

`verify.py` checks original raw hashes, exact head/job binding, all actual totals, suite completion
and source/docs conclusions. The rendered logs remain archived. All matrix conclusions were
successful at both exact revisions; the correction concerns the reported counts.
