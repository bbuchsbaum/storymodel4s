# Five-workstream completion: execution record

The owner authorized completion of the five workstreams after the 14 October assessment.
The canonical sequence is in [PLAN](../../PLAN.md); [RELEASE-QUEUE](../../RELEASE-QUEUE.md)
maps it to existing tickets. This directory records execution, not prospective completion.

## Baseline reconciliation

Source main at entry: `76f6fad800176c27f756cf62a644cd68f9a694ca`.
The shared checkout was on `codex/passport-resume-20261013` at
`eca26785f210506016d928f242cd9d03a067da84`, an ancestor 23 commits behind main.
There were no working changes outside `.mote`.

Of 71 untracked operation files, 70 were byte-identical to files already committed on main.
Each was independently compared with its main blob, copied to ignored preservation storage,
and checksum-checked before relocation. A normal branch switch then restored the committed
files; all 70 restored checksums matched. The unique earlier operation was preserved in place.
The original branch remains unchanged. No reset, branch deletion or data cleanup was used.

The existing Mote authority-v2 format and its immutable authority records are preserved with
the operations they authenticate. `mote authority status` validates the contract and
`mote doctor` reports a clean operation store. Local runtime/lock/session files are excluded.
`baseline.json` binds the source/consumer/remote observations and tracker counts. The initial
stocktake counted 345 closed and 91 open tickets; the new active delivery claim changes its
status, not its acceptance or completion.

The docs update supersedes stale next-task prose without changing the accepted 1.0/1.1 scope.
Known implementation and scientific boundaries remain explicit: ordinary CLI ONNX registration,
actual A+V and historical-preset parity, encoding authority, final stability/distribution,
and StoryAtlas temporal adoption. The StageTrace follow-up remains nonblocking.

## Qualification carried forward

The [integration gate](../integration-20261014/README.md) passed JVM 3,479, JavaScript 2,594
and Native 2,594 tests, with zero failures and four optional JVM skips. It reproduced all
30 actual recall export files byte-for-byte. The subsequent
[entity experiment](../entity-hypotheses-20261014/README.md) changed pipeline test tooling;
its exact producer passed 354 pipeline tests and 228 Python checks, with no failures/skips.
Those are retained local receipts for their named revisions, not a new current-head full gate.

At entry, live GitHub source main was `908b935ca55fb6a4bc0f1e10b16a5b42c93adff4`,
128 commits behind local main with no divergence. Its CI run
[37985371855](https://github.com/bbuchsbaum/storymodel4s/actions/runs/37985371855)
and documentation run
[37985371953](https://github.com/bbuchsbaum/storymodel4s/actions/runs/37985371953)
were successful. They do not qualify the newer local code. Subsequent synchronization and
hosted receipts must name their exact revisions here before that workstream is called complete.

## Next action

Review and commit the baseline/queue/authority snapshot, prove fresh-clone tracker readback,
then integrate and synchronize the reviewed source baseline. Continue the public ONNX
command bridge under `bd-01M35P83TCNHBSDY6NSK13Q3K0`; keep original acceptance and real-data
restrictions intact. Local-only dashboard/runtime state is under `.dashboard` and `.agent-work`.
