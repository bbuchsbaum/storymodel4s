# Recovered S2a-4 courts and strict baseline

The 41-file non-Mote allowlist from `e02d80d` was recovered without its unlanded parent chain. It contains test courts, a mutation analyzer and historical evidence; no production semantic change. The complete strict baseline on `0bd050d` clean-recompiled all six align/proposition test cells and passed all 56 platform/module cells, formatting and `githubWorkflowCheck`. See `receipt.json` for actual passed/skipped totals and the compressed full log.

All twelve retained mutation runs were re-audited against identical current source/test blobs, original and mutant hashes, named compiled failures, sibling successes and actual exit receipts. This is retrospective evidence, with its original dates and SHAs preserved. It is not a claim to have rerun those mutations today. The selected original recovery branch must remain fetchable for these historical source identities.

This completes the recovered court slice. It does not close S2a-4: the unavailable-versus-abstention acceptance depends on unfinished S2a-3. It establishes local engineering qualification, not hosted CI, live provider qualification, or new scientific results.
