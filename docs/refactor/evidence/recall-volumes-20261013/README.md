# Production recall onset export: Sherlock development source03

The public producer prepares the admitted source03 recall, executes both mapping members,
and binds canonical word observations to the published OpenNeuro raw recall clock. The
[readback receipt](actual-readback.json) records independent Python reconstruction from
the original CSV and prepared inventory; [the output manifest](output-manifest.json)
pins every local artifact. Participant text and output payloads remain in the ignored
`.agent-work/recall-volumes-20261013` directory, outside this evidence directory.

## Measured result

- 2,495 canonical words/CSV records; 2,489 inside raw coverage, six outside.
- 624 bins with onsets, 614 with an inference-unit attachment, 686 distinct volume/unit links.
- All 173 original inference units preserved: 172 completely attached, one partially attached.
- 94 canonical words have no inference-unit membership; their timing remains explicit.
- Reference: 151 complete and 22 excluded original-unit outcomes. Reconstruction: 173 complete.
  Original external alternatives and unavailable measures remain in both mapping members.

The six final records remain outside `[0,1074)` seconds. No clamp, extra crop offset,
HRF shift, duration estimate or encoding clock transformation is applied. Bins are
explicit half-open sampling supports over 716 stored volumes at TR 1.5 seconds.

The quantities above count observations and attachments. They do not estimate behavioral
fidelity or additive semantic mass per volume. Each original unit's measure retains its
normalization domain once in the paired exchange; volume links only reference it. Timing
coverage, unit membership and mapping exclusion are separate outcomes. In particular,
94 unassigned words and 22 excluded reference units do not silently disappear or become
zero-valued observations. Temporal mass allocation remains unavailable for onset-only data.

## Identity and authority

The [job](job.json) pins study/config/CSV and the supporting public-release receipts. Its
recording declaration connects canonical source03, session `released`, the exact CSV clock,
and BIDS `sub-03` task `freerecall` at release revision
`fc91edbbe1b8117f96b40bb4d1f5df43aa1afdce`. The [Passport evidence](../../../data/sherlock/openneuro-resume-20261013/README.md)
records the byte/header recheck and published subject/clock definition. This is declared
release authority, not independent acquisition-trigger/audio synchronization. The scanner
API's `declared-not-independently-verified` status is retained.

The source mapping uses the prepared annotation source and its declared coordinates. It
does not establish a physical movie-to-scanner origin. The BIDS and full-journey parent
beads remain open for that missing encoding evidence.

## Reproduce the bounded run

Use the existing admitted staging input directory. Copy `job.json`, `study.json` and
`mapping.json` from here to a new ignored input directory, alongside the byte-identical
`annotations.csv`, `parts.json`, `recall-lineage.json` from the previous public preparation,
source03 CSV named `recall-source-03.csv`, and `verification.json` / `recall-coverage.json`
from the Passport evidence directory. The job verifies its pins; public preparation checks
recall admission. Use new output paths.

```sh
sbt 'pipeline/runMain storymodel4s.pipeline.recallVolumes /absolute/input/job.json /new/output'
python3 examples/mapping/read_recall_volumes.py /new/output --csv /absolute/input/recall-source-03.csv
```

Synthetic public-path tests require no participant data:

```sh
sbt 'pipeline/testOnly *RecallVolumeExportSuite'
sbt 'pipeline/Test/runMain storymodel4s.pipeline.RecallVolumeFixture /new/synthetic-root'
python3 examples/mapping/test_recall_volumes.py /new/synthetic-root
```

The Python corruption witnesses update envelope hashes to test semantic checks, including
same-length foreign transcript text, changed clock/shift/authority, dropped outside rows,
duplicated links and invented temporal allocation. Independent review found the original
Python replay omission; the same-length replacement was reproduced before repair. Scala's
producer already refused it. Adverse runs and the surviving first containment mutant are
retained alongside the repaired witnesses and final gates; see `checks.json`.

## Final qualification

Source revision `5dced342139f4ae212bdcc8af6741700296bcfb6` is independently reviewed.
The final producer ran from evidence-only descendant
`703dacbe99981b7ead94a4ef32681918247b959a`. All 30 output files, including the
completion manifest, match the first production run byte for byte.

| Gate | Passed | Failed | Skipped |
| --- | ---: | ---: | ---: |
| JVM reference scope, 18 modules | 3475 | 0 | 4 |
| Scala.js reference scope, 12 modules | 2594 | 0 | 0 |
| Native reference scope, 12 modules | 2594 | 0 | 0 |
| Independent Python corruption tests | 11 | 0 | 0 |

The four JVM skips are optional live media/remote-provider tests. Formatting checks
pass. All results are locally observed; no hosted CI, push or main-branch landing is
claimed. Logs, process exit metadata and decompressed-log SHA-256 values are retained
in `checks.json` and `log-index.json`. The exact input/source pins and independent
review scopes remain separate from the unresolved encoding scientific acceptance.
