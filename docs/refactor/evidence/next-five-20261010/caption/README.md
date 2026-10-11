# Caption selection integrity qualification

Candidate: `198550727aa09dcc52d05662652c51f2565a79dd`, over local main
`9b7e830cfcf06e2a87209c658558ccd2432c023d`. Semantic commit `8845f55c` plus
format-only `19855072`. The first gate found only two formatting failures;
the corrected exact candidate passed strict `media/test; pipeline/test`,
`scalafmtCheckAll; scalafmtSbtCheck`: 337 passed, zero failed/errors, three skipped.
The optional model/asset tests were not executed; no VLM inference claim follows.

Dependency-free Python worker request-schema tests: four passed. Three compiled
mutants (selection identity, unbound legacy sampled request, extent ordinal order)
each killed their named assertion: one failed, twelve passed. Restored focused
suites passed 19 tests. Historic v1 request/outcome fixtures remain unchanged;
v1 replay is accepted for full frames and refuses sampled selections. New issued
requests bind the selected frame identity with request schema v2. CaptionExtent
is a checked non-case class; direct case-class construction is intentionally removed.

The raw logs are compressed losslessly. `manifest.json` binds the original bytes;
metadata includes exact commands and exit status. These are local engineering receipts.
