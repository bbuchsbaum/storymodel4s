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

## Source baseline synchronized and hosted-qualified

Independent review passed exact revision `7be3c0b10fd006a1c0b7679203e4cebdab5da3aa`.
A fresh standalone clone validates all 16,643 committed Mote operations with zero uncommitted
operations or doctor warnings. Main advanced by a normal fast-forward, and a non-force push
made GitHub main identical. [The receipt](hosted-baseline.json) retains the review scope,
fresh-clone readback, remote observations and all 76 hosted result cells.

[CI 38568151167](https://github.com/bbuchsbaum/storymodel4s/actions/runs/38568151167)
passed at that exact revision:

| Matrix cell | Passed | Failed/errors | Skipped |
| --- | ---: | ---: | ---: |
| JVM, Java 17 | 3747 | 0 | 18 |
| JVM, Java 21 | 3747 | 0 | 18 |
| Scala.js | 3183 | 0 | 0 |
| Native | 3183 | 0 | 0 |

The compressed raw log is retained and hash-bound. Documentation run
[38568151276](https://github.com/bbuchsbaum/storymodel4s/actions/runs/38568151276)
and integrity run [38568151452](https://github.com/bbuchsbaum/storymodel4s/actions/runs/38568151452)
also passed. These hosted results qualify the synchronized source baseline; they do not
qualify subsequent branch changes or update StoryAtlas's source pin.

## Owner-supplied timing workbook

The supplied `Sherlock_Segments_1000_NN_2017.xlsx` has SHA-256
`5271b7704ccebfc7cae2228c78faf472c0ba5530c1a45e4c784757057bf476df`, identical to the
already pinned authors' tutorial workbook. [Read-only comparison](workbook-comparison.json)
finds all 1,000 segment rows and numeric seconds/TR boundaries equal to the imported TSV.
The workbook retains the part-2 seconds reset, concatenated TR numbering and two rows without
TR assignment. It supplies annotation coordinates; it does not add a participant-specific
video-presentation/scanner-trigger record or an existing derivative array's processing history.

## Ordinary ONNX execution qualified locally

The [independent code review](onnx-review.json) passed exact source
`0a3a97777c861f41c911ae1232a911590576fd2a`. Its restored
[full gate](onnx-full-gate.json) ran all 56 test tasks, with fatal warnings enabled and
formatting last: JVM 4,287 passed and five skipped; Scala.js 3,188 passed; Native 3,188
passed. There were zero failures/errors. The six [compiled mutations](mutations/mutations.json)
each failed its named assertion while all selected siblings passed. Shape-correct external
construction probes were separately recompiled cleanly. [Logs](onnx-log-index.json) retain
the earlier failed controls as well as successful checks.

StoryAtlas `ac2ca6cb85fdb8a959390e0e3d16e7af87c3e16d` passed its
[exact-pair compatibility gate](onnx-atlas-gate.json): 488 tests, zero failures/errors/skips,
application linking and formatting. That override check does not change its default source pin
or establish the pending temporal browser adoption.

The ordinary `mappingRun exchange` and `mappingWeight` commands executed the pinned local
ONNX model for admitted source03. [Independent readback](source03-onnx.json) retains one
successful attempt, both complete 173-unit/2,495-word members, the same inventory digest and
weights 0, 0.5 and 1. Separately executed base publications agree exactly. Participant-derived
outputs and the receipt key stay in ignored local storage. The
[historical-preset comparison](historical-preset-differences.md) records changed scoring,
candidate and reconstruction choices; historical benchmark accuracy is not inherited.

The [encoding selection receipt](../encoding-export-20261014/README.md) binds newly copied
raw arrays, all 1,000 annotation outcomes and both actual scanner declaration exports. Physical
scanner/media synchronization remains unestablished. The owner confirmed on 14 October that
the OpenNeuro README crop prescriptions are the available timing authority and no finer
trigger records exist. Item 3 will complete on published-index alignment with that limitation
retained explicitly; the new arrays have independently verified raw-volume provenance.

## Current action

Complete actual planned video acquisition and the controlled lexical A/A+V comparison under
`bd-01M35P83TCNHBSDY6NSK13Q3K0`. The plan contains 999 positive annotation intervals; point
segment 13 retains unavailable video coverage. Continue installable-command preparation, stable
boundary audit, reader documentation and temporal StoryAtlas adoption. Local-only dashboard,
private artifacts and resumable build state are under `.dashboard` and `.agent-work`.

## Reviewed ONNX milestone synchronized

After the owner explicitly approved exact commit `22cc7243dd825198544569bc51f86bc21c8c0dbb`,
local main advanced from `7be3c0b1` and a non-force push made GitHub main identical. A fresh
`ls-remote` confirmed the full SHA. The [evidence review](evidence-review.json) passed that
exact candidate; newer working-branch changes were excluded. Hosted
[CI 38589639065](https://github.com/bbuchsbaum/storymodel4s/actions/runs/38589639065) and
[documentation 38589639080](https://github.com/bbuchsbaum/storymodel4s/actions/runs/38589639080)
were running at this checkpoint. Their final results remain separate from local qualification.

## Planned acquisition and installable commands

Source `2c4b74f9ef069e1bc1f6678f8dababe2421ca929` adds explicitly planned frame selection and
an offline CLI distribution. The independent [planned-video review](planned-video-review.json)
passed the repaired provenance boundary: altered or missing plan, selection-rule and realized
selection attachments refuse during replay. The original omitted replay check was reproduced
as one failing test with eight passing siblings. The repaired focused suites passed 28 tests.
All seven [compiled mutation checks](planned-mutations/mutations.json) fail their named test
with eight passing siblings; the exact candidate was restored cleanly afterward.

The [distribution review](distribution-review.json) passed the source and extracted artifact.
The original ZIP timestamp produced different bytes under UTC and America/Toronto; the repaired
timestamp gives identical 142,942,745-byte archives with SHA-256
`8ddca4c7c732d20fe9bf888c8da397db9e152f33820add229ea66a4641d2d833`.
All 73 manifest entries and 69 classpath entries were independently verified. This is the
measured timezone comparison, not a claim of reproducibility across different build environments.
The [artifact receipt](distribution-artifact.json) retains the failed collector step and its
correction separately from the successful builds.

The [installed ordinary-file journey](installed-journey.json) uses the existing exact synthetic
control from `PublicMappingFacade.scala`. Eight commands complete analysis, paired exchange,
weights 0/0.5/1, independent Python readers, identical resume and overwrite refusal. Model
resources are supplied separately. A public binary release has not been published.

The restored clean full source gate, actual Sherlock acquisition and subsequent consumer/docs
checks are in progress. These focused witnesses do not close those remaining acceptance gates.
