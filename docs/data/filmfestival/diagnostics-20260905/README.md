# Film Festival stage diagnostics

Read [the results](../../../plans/2026-09-05-filmfestival-diagnostic-results.md) for the estimand,
stage comparison, development-only interpretation, pending inputs and reproduction command.

- `stage-scores.json`: all eligible units, participant/film breakdowns and paired stage intervals.
- `paired-changes.json`: the cartoon experiment's changed unit identities and fixed diagnostic sample.
- `run-receipt.json`, `diagnostic-receipt.json`, `output-parity.json`: exact inputs, implementations,
  outputs and the 120 byte-identical historical artifacts.
- `replay-manifest.json`, `replay-receipt.json`: a three-second saved-output check, with relative data
  paths, complete consumed-input binding and synthetic contracts.
- `landing-gate.json`, `final-checks.json`: final clean implementation at `8a89ff6f`; full
  `checkAll` (5,896 passed, five skipped), 38 Python contracts and separate final formatting checks.
  The following receipt-only commit changes no implementation, build input or test.
- `initial-full-gate.json`, mutation receipts: initial Scala court and discriminating falsifiers.
- `readiness.json`, `external-input-refusals.json`: unmatched/unreviewed source windows and unanswered
  independent annotations remain explicit pending inputs, with exercised refusal paths.

No source or recall prose is included here. The private packet/casebook and per-unit TSV/JSON remain
under the ignored data root. These artifacts do not establish media admission, human reliability,
within-film video timing or confirmation on unseen participants/films.

Derived from openly released research data. Where the upstream release carries no explicit
licence, it is used here for non-commercial academic research under an open-science reading, with
attribution to the original authors. No source bytes are redistributed. The original authors have
not reviewed or endorsed this use. Attribution and exact upstream revisions are in the
[provenance record](../README.md).
