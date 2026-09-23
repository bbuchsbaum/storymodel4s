# Existing GitHub-hosted CI route selected

On 2026-09-23 the owner selected **existing GitHub-hosted Ubuntu runners**, with
no billing, repository-visibility, credential or runner-registration changes.
This satisfies the route decision in `bd-01M2TA2283FC7FSA2G7TJ1WSMN`.

The earlier account-startup blockage no longer describes the latest observed
run. [CI run 35667411311](https://github.com/bbuchsbaum/storymodel4s/actions/runs/35667411311)
at remote revision `182d74aeed3b5a8bcd013de51e7024cc426c544b` started on
2026-09-21. Its Ubuntu 22.04 / Temurin 17 / rootJVM cell reached tests and failed;
the other three matrix cells were cancelled. The retained job log reports the
MigrationSuite E175 warnings, an embed-bench test compile failure, and a
WarOfTheGhostsCodecGoldenResourceSuite mismatch in the old `hsmm/v3` artifact.
These are executed build/test failures, not evidence of a current account block.

`receipt.json` records the read-only API/remote checks, job identities and log
hashes. The compressed file round-trips to the complete downloaded failed-job
log. Authentication was checked through the repository's isolated `bbuchsbaum`
profile with Keychain access; no login or credential change was made.

The separate qualification ticket `bd-01M19G69RQCHMT2EMG11XFT4WX` remains open.
It needs a green hosted matrix on the actual candidate revision with a URL,
cell, test totals and source binding. This route decision neither supplies that
receipt nor authorizes a release or deployment. The remote SHA above is not
current local main, and no push or CI rerun occurred in this decision step.
