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

Actual planned video acquisition, controlled A/A+V execution, published-index imaging and
StoryAtlas browser readback now have the receipts below. The producer full gate, artifact/POM
checks and exact-candidate example replay have completed. The owner-authorized main advancements
are landed and pushed; final tracker and hosted receipts are being reconciled below. The 1.0 API freeze
and public artifact/site publication retain their separate requirements. Local-only dashboard,
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

At the `2c4b74f9` checkpoint, the full source gate and actual/consumer/docs checks remained open.
Their subsequent receipts below supersede that checkpoint; the focused witnesses alone did not
close those acceptance gates.


## Completed local caption and A/A+V execution

The installed `2c4b74f9` bundle completed both planned video parts. Independent readback verified
all 24 manifest files, 2,997 selected frames and 999 interval captions against the original
presentation packet inventory and admitted annotation CSV. Annotation 13 is onset-only and
has no fabricated caption interval. See `sherlock-planned-video-readback.json` and its reader.

The six public CLI commands then produced annotation-only and annotation-plus-video pairs and
weight sweeps at 0, 0.5 and 1. Both use the identical 173-unit inventory and 1,050 target IDs.
Reference completeness is 151 units for A and 160 for A+V; both reconstruction members retain
173 complete units. Independent readers verify endpoint byte identity and separate-command
base identity. These counts do not assess caption or mapping accuracy. Coordinate correspondence
is the explicitly declared published-part convention, not measured physical synchronization.
`run_actual_av.py` reproduces the command sequence; `sherlock-actual-av-readback.json` binds the
private command receipt and result hashes without publishing study content.

Readback exposed a Python-reader mismatch: the producer's documented row-sum shape tolerance is
1e-9, while the readers used 1e-12. The largest actual discrepancy was 1.3736789483687062e-12.
The readers now enforce the producer contract and retain every supplied value unchanged. All 12
reader controls pass; restoring the old guard fails the named roundoff control with 11 siblings
passing. Both signs beyond 2e-9 continue to refuse. The adverse observations are retained in
`sherlock-weighted-roundoff-audit.json`, with checks in `weighted-reader-tolerance.json`.

The full documentation verification passed on `2647d280`: 14 executable examples, five parser
checks, the Astro build and navigation, provenance, figure-mutation and verbatim checks. The new
ordinary-file guide also passed 390/1440-pixel layout and mobile-menu checks; both rendered pages
were inspected, and browser ownership audits before/after were clean. See `docs-full-verify.json`.

## Imported workspace tolerance and platform-gate continuation

Independent review found that the ordinary workspace reader still required `1e-12` row-sum
agreement while the established producer contract accepts `1e-9`. Exact `a2e023f1` uses the
shared `MappingTolerance.RowSum`. Both signs of a `5e-10` discrepancy pass workspace admission,
session creation and projection with their supplied values unchanged; both signs of `2e-9`
refuse. Independent review passed, all 93 focused tests pass, and restoring the old guard
compiles and fails only the new named test (92 pass, one fail). The
[tolerance receipt](temporal-workspace-tolerance.json) retains the mutation log.

The [full f4 gate attempt](producer-f4ecc807-full-gate-failed.json) compiled all platforms and
passed 5,258 tests over 26 tasks before a 2 GiB JVM heap exhausted while compiling Native
alignment tests. This is an incomplete gate, not a green result. The continuation runs exact
`a2e023f1` with 6 GiB, covers every remaining task including the changed codec and downstream
modules, and runs formatting last. The preceding passed modules have no source delta in the
two-file repair. The earlier load-related failed Native attempt is also preserved.

Atlas candidate `76046a24ce1f5cea7b79e48ba0c055d7ac7618e0` is preserved on its own repository's
`codex/temporal-adoption-20261014` branch; main is unchanged. Nine focused controller/import
checks pass, direct Scala.js UI compilation passes, and formatting/workflow generation pass.
At that checkpoint its full consumer gate, rendered browser checks and independent review
were pending. The later qualified candidate below supersedes it.


## Reviewed temporal candidate and actual browser readback

Producer `0726fcdc` repairs absent-clock packets without weakening exact-key validation.
The [round-trip receipt](untimed-roundtrip.json) retains 95 passing focused tests and the
compiled old-guard mutation (94 pass, one named failure). Untimed packets remain untimed.

Actual data then exposed repeated full-source and full-inventory digest work. Producer
`eb48a562` caches only those immutable digests, keeping their public methods and original
serialization expressions. The [cache receipt](digest-cache.json) binds 106 passing tests,
original/cached source, inventory, session and encoded-packet identity, and exact readback
of all 5,536 cells per actual condition. The single measured JVM runs took 11.5 and 15.7
seconds including decoding and two projections; these are local observations, not a benchmark
population. Independent review through
[`874f1b08`](../stability-audit-20261014/review-874f1b08.json) also compiled both changes and
checked 800 matching digest pairs from eight simultaneous readers.

StoryAtlas `d6f800f3` pins producer `eb48a562`. Its complete gate passed 510 tests with zero
failures, errors or skips, application bundling and formatting. The independently reviewed
consumer includes honest allocation labels, raw numeric CSV values, bounded bin counts,
keyboard focus, paging, optional clocks and both ordinary/temporal saved-state replay.
Earlier failed consumer gates are retained rather than counted as passes.

The [synthetic browser report](atlas-browser-d6f800f/report.json) passes all 30 controls.
Desktop/mobile rendering was inspected; the browser was closed and both ownership audits are
retained. The [actual-data browser report](actual-temporal-browser.json) passes both A and A+V:
173 units, eight row pages and 5,536 cells each, exact agreement with the independent JVM
reader, explicit blank unavailable quantities, and byte-identical view/receipt replay.
No HTTP requests or browser errors occurred. Measured import times were 34.9 and 48.1 seconds;
allocation changes took about 12.7 seconds. Those latencies remain visible delivery limitations.
Actual participant files, transcripts, tables and saved views remain in ignored local storage.

## Stability and release preparation

The corrected signature walker now includes public ADT alternatives and binds the admitted
runtime JAR set and any supplemental TASTy files. Independent review first reproduced an
unlisted-JAR provenance failure; the repaired runner refuses it before inspection. The
[construction judgment](../stability-audit-20261014/constructor-judgment.md) records the
checked receipt boundaries and the concrete pre-freeze findings. The expanded closure also
exposes types previously described as experimental; the final stable-boundary decision remains
promotion plus audit or a narrower facade. Enumeration is not a freeze certificate.

The [compatibility rehearsal](compatibility-rehearsal/receipt.json) passes an unchanged compiled
contract and detects exactly the deliberately removed stable method, without exclusions.
Its empty-baseline controls are local policy checks; a post-1.0 published comparison has not
been activated. [Release preparation](../../../release-preparation.md) records the artifact,
dependency, namespace, signing, deployment and final authorization requirements.

## Final local qualification

Producer `eb48a5622bf02d08c175d2956811fd35d4aaeb92` passed the uninterrupted
[full gate](producer-eb48a562-full-gate.json), with fatal warnings and formatting last:
JVM 4,361 passed/five skipped, JavaScript 3,258 passed, Native 3,258 passed. All 56 test
tasks completed with zero failures/errors. StoryAtlas `d6f800f3` retains the exact-pin
510-test gate, 30 synthetic browser checks and both actual A/A+V readbacks described above.

The [final offline ZIP](final-distribution-artifact.json) was rebuilt from that clean source,
extracted into a fresh directory and executed from outside the checkout. All 73 manifest files,
69 runtime classpath entries and five launcher tests passed. Its exact archive SHA-256 is
`b5f7b6a78026eb0886660351e4b822c4f4b86ee9e6beb2f0e253be2a79eca6e9`.
The [guide replay](final-documentation-replay.json) passed all 14 transcripts and five parser
controls; site inputs are byte-identical to the earlier full site/layout qualification.
[Command logs](final-command-log-index.json) retain these results.

[Artifact preflight](artifact-preflight.json) generated all 56 POMs and checked 50 distinct
direct external POM coordinates against Maven Central. Forty-six resolved; four grakern
`0.1-0329c43-SNAPSHOT` coordinates returned 404. Public Maven dependency resolution therefore
remains blocked. This does not prevent the offline bundle from carrying the verified dependency
bytes; the preflight does not claim complete transitive resolution or authorize publication.

The [final API audit](../stability-audit-20261014/README.md) is bound to this archive and has
complete signature/constructor-family coverage. Its pending stable-boundary decisions and
constructor debts remain explicit. [Review of the preceding evidence](review-bfbcb121.json)
passed; its script-provenance label follow-up is corrected. The [final receipt review](review-ad69af01.json)
and [metadata-head review](review-1211c767.json) both passed.

## Approved landing and hosted qualification

The owner approved both main advancements. Source `1211c767cf1a17c25e7ec44b55007e8bc2af67c8`
and StoryAtlas `d6f800f37e8290357b29c75c97d3350e9ff97c7e` were fast-forwarded and pushed
without force. [Remote readback](landing.json) verifies both exact SHAs and confirms preservation
of all 1,198 unrelated dirty Atlas paths.

Hosted source [CI](https://github.com/bbuchsbaum/storymodel4s/actions/runs/38632249175),
[documentation](https://github.com/bbuchsbaum/storymodel4s/actions/runs/38632249133), and
[Atlas CI](https://github.com/bbuchsbaum/storyatlas4s/actions/runs/38632251334) are retained under
`hosted-final/`. Documentation and Atlas CI passed. Atlas's three cells passed 282 tests on
Java 17, 282 on Java 21 and 228 on JavaScript, with zero failures/errors/skips.
Source CI failed twice before tests because different runners could not retrieve sbt 1.12.14
from `repo1.maven.org`; the other matrix cells were cancelled. Both attempts remain retained.
Both Central POM endpoints subsequently returned HTTP 200 with identical bytes.

The independently reviewed repair `d1a82d5b` selects `repo.maven.apache.org` for sbt bootstrap
through the launcher's repository configuration. It changes neither the sbt version nor build
dependency resolution policy. An empty-cache bootstrap and workflow/format checks passed;
their raw logs are in `ci-bootstrap/`. The exact repair's hosted
[CI](https://github.com/bbuchsbaum/storymodel4s/actions/runs/38634266780) and
[documentation job](https://github.com/bbuchsbaum/storymodel4s/actions/runs/38634266710) passed.
The complete API log archive binds all 80 platform tasks: each JVM passed 4,344 tests with
22 optional skips; JavaScript and Native each passed 3,258, with zero failures/errors.
The initial `gh run view --log` collection was truncated inside the pipeline tests and showed
only 22 JVM totals. The full archive establishes all 24, including pipeline and corpus intake;
both forms are retained, and the independent review records the correction. Hosted media
checks skipped 20 optional tests versus three on the local workstation; no missing totals are
being counted as passes.

## Reader-route acceptance follow-through

The [reader crosswalk](reader-journey-crosswalk.json) connects each MVP input to its authored
walkthrough and exact execution evidence: indexed/structured story, ordinary story and recall,
annotations with and without video, video-derived source, and exchange/scanner readback.
It preserves the original producer revision of each run. The older WOG control was independently
rechecked against its exact admitted source/configuration and all 99 output files; it supplies
one paired mapping and three weights with synthetic external recall, not an accuracy result.
No private participant text was added to the documentation.

The expanded guide at `a56a74b8` passed [full site verification](docs-crosswalk-checks.json):
14 exact executable transcripts, five parser tests, 22 built pages, navigation, layout,
figure-mutation, verbatim and provenance checks. The changed page passed 390/1440-pixel
layout and menu checks, and both retained screenshots were inspected. The owned browser
closed cleanly. A separate installed `textSourcePrepare` run and independent reader both
exited zero; its five surface segments are not five inferred sentences or a semantic graph.

[Hardware provenance](actual-browser-hardware.json) now names the local Apple M3 Max workstation
for the previously retained actual-data browser timings. It distinguishes this later hardware
observation from a new timing run; the existing measured latencies remain unchanged.


## Final recall-clock series and authorized follow-through

Source main advanced normally from `1211c767` to `a56a74b8`; the
[source receipt](source-followthrough-landing.json) verifies the non-force push and remote SHA.
Atlas main advanced from `d6f800f3` to `cda5210d`; its
[receipt](atlas-series-landing.json) verifies all 1,202 original dirty-file hashes, including
18 unrelated paths, after the fast-forward. The independently reviewed `9602b7aa` preserves
its recovered Mote history; no private participant outputs were added.

The independently authored [series review](atlas-series/review.json) passes exact `cda5210d`.
The series displays producer ROI mass and separate source-wide concentration width at each
supplied recall observation, retaining missing timing, source-reported/estimated basis and
unavailable quantities. A direct overlap calculation and exhaustive finite-grid search agree
with both unequal synthetic rows. The complete [gate](atlas-series/gate.json) passed 518 tests
with zero failures/errors/skips, bundling and formatting. An earlier attempt without a complete
log/exit remains inconclusive.

Review first reproduced mobile page overflow and lost keyboard focus on `544499eb`; both
BLOCKs were repaired. All 12 new browser controls and 30 existing controls now pass. Final
390/desktop screenshots were inspected and owned browsers closed with clean lifecycle audits.
The [actual replay](atlas-series/actual-browser-report.json) again matches all 5,536 cells in
each A/A+V condition to the independent JVM reader, preserves unavailable values and reopens
saved state byte-for-byte, with no network requests or browser errors. Single observed import
times are 25.2/33.6 seconds and allocation changes 9.2/9.8 seconds on the previously recorded
Apple M3 Max; these are local observations, not a population performance claim.

The exact main runs are source [CI 38640089960](https://github.com/bbuchsbaum/storymodel4s/actions/runs/38640089960),
[docs 38640090003](https://github.com/bbuchsbaum/storymodel4s/actions/runs/38640090003), and Atlas
[CI 38640529077](https://github.com/bbuchsbaum/storyatlas4s/actions/runs/38640529077).
The completed docs and Atlas results are retained under `hosted-followthrough/`. Atlas passed
286 tests on each JVM and 232 on JavaScript, with no failures/errors/skips. Source main's
repeated full matrix is still running at this receipt; it is not counted as a completed run.
[Qualification closeout](qualification-closeout.json) binds the green runtime matrix at `d1a82d5b`,
unchanged source runtime inputs, the green exact `a56a74b8` documentation run and the green Atlas
code revision. Later docs/Mote-only commits preserve those gates under T2.

The final [metadata review](closeout-review.json) passed source `d4b7ca0f` and Atlas `50a160168`.
Atlas's [tracker closeout](atlas-closeout-landing.json) is pushed; its
[final doctor](atlas-final-doctor.json) reports clean hashes and zero uncommitted operations.
The inspection-only producer ticket was also reconciled against its original acceptance and
closed: its exact review, compiled mutations, full platform gate and consumer/browser evidence
were already complete. The six source delivery tickets and Atlas adoption ticket are closed.
The broader delivery epic, construction judgment, infrastructure and final release gate remain
open for their explicitly listed freeze/publication requirements.


The [tracker snapshot](tracker-closeout.json) records 352 closed and 91 open source tickets,
with zero doing/review tickets. The open groups remain eight for 1.0, 21 for 1.1 and 62 for later
work. The [source doctor](source-final-doctor.json) verifies all 16,831 operations with clean
hashes and zero uncommitted operations at the metadata checkpoint. This is delivery bookkeeping,
not a declaration that the remaining release gate or research backlog is complete.
