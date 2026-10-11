# First three local qualifications, 11 October 2026

Producer: `9b7e830cfcf06e2a87209c658558ccd2432c023d`. Base: `a76c0477b5f9fbc108f57a88db7c5277b389f100`.

Loader, ordinary-file facade, and documentation salvage: strict `checkAll` passed
10,195 tests, zero failed/errors, five skipped across 56 platform/module cells.
14 executable docs examples and all site verification commands passed. StoryAtlas
`ac2ca6cb85fdb8a959390e0e3d16e7af87c3e16d` passed 488 tests in its full cross-platform
consumer gate plus three timed-workspace tests, using an explicit local producer source
override. Its source pin was not changed. Both generated workspaces passed the independent
Python reader. No hosted CI, browser qualification, scientific efficacy, or push is claimed.

Eight compiled mutants were killed by named assertions: loader digest, plain-header,
duplicate-header, written-model validation, invalid exit, config diagnostic redaction,
empty-batch inspection and empty-batch CLI. Exact mutant receipts include command,
source patch, test totals and status. Independent reviews are preserved in the two
review text files. The original readiness BLOCK was fixed and reviewed at `15d00da1`;
the additional executable facade documentation was reviewed at `9b7e830c`.

Raw logs are losslessly gzip-compressed; `manifest.json` binds uncompressed bytes.
Each run's `.meta.json` preserves its command, status and timestamps. Runtime workspace
artifacts are reproducible with the checked `StudyWorkspaceWitness` command in the receipt;
absolute paths in receipts identify the local run, not a portable installed location.
