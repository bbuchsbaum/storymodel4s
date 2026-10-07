# Delivery and measurement plan

**6 October 2026 restart:** the owner has moved to one primary agent and at most one helper.
The [stocktake](STATE-20261006.md) establishes the recovery baseline. The October delivery
sequence below is the current execution plan and supersedes historical staffing, next-owner
handoffs and M1-next prose. Mote remains authoritative; the accepted 1.0/1.1 scope and scientific
acceptance boundaries remain in force. This plan schedules work; it does not certify execution.
The owner's subsequent workstation requirement makes GitHub parity and an exercised remote
checkout handoff part of the first checkpoint. The sustained-development target is `buc-gw01`;
other machines must be able to clone, build and continue public/synthetic work through the same
portable contract. Private data and data-derived artifacts are used only on owner-approved
machines, including `buc-gw01`; joining the tailnet does not grant that approval.

Accepted direction, 19 September 2026, under the owner's request to assess the external review
and reorganize delivery. Implementation is pending unless evidence below says otherwise.
Baseline: `1113f96864a38a2869e49d9c8c5d4e2dc43f5d10`. This is the active delivery index.
The [backlog](BACKLOG.md) gives live Mote IDs, ticket acceptance criteria and scheduling lanes;
the [reconciliation](RECONCILIATION.json) accounts for every previously unfinished ticket.

## October delivery sequence

**Outcome:** a researcher can start with ordinary source and recall files, obtain checked local
reference and structured reconstruction mappings, inspect them in StoryAtlas, and export the
same results for analysis. Deliver that path in progressively broader, independently checked
examples. Measure progress by demonstrated journeys and reviewed commits.

**Starting point:** audit `e4933fcb2e58a845a703590d39b01e0bd213c0ca`, local main
`0500bc1029bea9df88291a7b73860f8fecfa804d`. The stocktake found 119 unfinished tickets, but
only a small working queue is selected. This is an execution order within the existing delivery
epic `bd-01M2TA01EHVRF6MQ1N00XTVK1K`, not another backlog or a change to release scope.

### 1. Recover, synchronize GitHub and qualify development hosts

Success means the reviewed main history, current instructions, delivery plan, Mote operations
and selected resumable work are available beyond this Mac. A fresh checkout on the always-on
workstation must demonstrate that it can continue development.

The order is **recover and qualify locally -> non-force GitHub synchronization/hosted evidence
-> fresh destination checkout and resume qualification**. The recovery slices below are the
first part of this checkpoint. Independent local product work may continue while host setup is
blocked; sustained execution on a new host waits for that host's qualification.

The approved workstation is `BUC-GW01`, reached through Tailscale at
`buc-gw01.tail5f873d.ts.net`. SSH, macOS15.1.1/ARM64, toolchain/storage, separate Git/API account
routing and private-data readback are verified. The exact433 source baseline is green on the
workstation and in hosted CI. Exactd67 StoryAtlas/default433 pin, public WOG/Bell exports and
independent hash readback also pass. Reviewed checkpoint23d0c73c/22bf1f50 was published
without force, with local/tracking/live parity and clean approved-host main readback recorded
in the checkpoint; numerical, CI-execution and consumer-pin tickets are closed after landing.
The [checkpoint index](evidence/workstation-checkpoint-20261006/README.md) binds revisions,
commands, passed/failed/skipped totals, preserved failures, private handoff and the resume state.

1. Prepare the audit/plan branch and first recovered slices for reviewed local integration,
   as detailed below. Review the actual accumulated main tree and preservation receipts, then
   qualify the exact result and sibling source pin. Fix any reproducibility regression exposed
   by an environment without local dependency overrides. Do not replace observed evidence with
   the assumption that 274 earlier local commits are remotely qualified.
2. Synchronize GitHub through a non-force push of the reviewed main result. Record local main,
   tracking main and live remote SHA equality. Retain a small explicit list of recovery branch
   refs still needed for resumption and publish their exact tips after reviewing identity and
   publication contents. Label each unfinished, review-ready or qualified; remote preservation
   does not require implementation completion. Do not push all refs or snapshots wholesale.
   Bind hosted CI by run URL, matrix cell and SHA. Git transport and
   GitHub API authentication are checked separately; preserve repo-local account routing.
3. Copy the recovery bundle and its hash manifest to approved private workstation/backup
   storage and verify them there. An ordinary GitHub clone does not carry `.git/recovery`, local
   stashes or the full protected object archive. Keep the original copy until readback succeeds.
4. Bootstrap a standalone checkout from GitHub, using the repository URL and account routing
   configured on that host rather than assuming this Mac's `github-bbuchsbaum` SSH alias exists.
   Record the OS/architecture, tested tool/build identities, checkout path, dependency pins and
   host-local artifact/data locations. The checked-in build selects Scala 3.7.4, sbt 1.12.14 and
   sbt-typelevel 0.8.7; current CI uses Temurin 17/21. Supply Node, Python and the tested Native
   compiler/libraries as required by the actual platform gate. Pin the tested Mote binary/build,
   not merely its `0.1.0` version label. Preflight the selected host before fixing setup commands.
5. Create the required `.mote/local` and `.mote/tmp` runtime directories on a fresh clone and
   verify that both are ignored by the committed rules (the checkpoint repaired the missing
   `tmp` rule). Keep generated runtime contents out of commits. Run Mote
   doctor with a distinct actor, and prove it sees the published ticket/operation state. Fetch
   grakern and its transitive builds from the checked-in source pins. A local working grakern
   checkout must not silently replace that proof.
6. Record the exact StoryAtlas consumer SHA and StoryModel source pin and verify that both are
   fetchable on the destination. An unavailable consumer revision remains an explicit handoff
   dependency. Run the strict baseline and exact-provider consumer from the workstation checkout;
   replay a small public/synthetic CLI export and independent reader. Bind SHA, commands, exits,
   passed/failed/skipped counts and output hashes in a portable handoff receipt. Missing optional
   model/media/data assets produce explicit unavailable capabilities rather than guessed output.

Each development machine uses an independent GitHub clone and host/session-specific Mote actor.
Keep one active implementation writer across all machines. Before a writer handoff, commit/push
the code, tracker operations and resumable evidence, release ownership, then have the next host
pull the published state and acquire its own claim. Local Mote leases are not a distributed
mutex across unsynchronized clones. Other machines can build/review public code and synthetic
fixtures concurrently within the one-helper and heavy-gate resource limits. Private-data work
requires explicit machine approval and the relevant corpus admission. Configure credentials on
each host normally rather than copying credential stores as project state.

Use `STORYMODEL4S_DATA` and `tools/data-root.sh --check` for approved host-local data/model
locations, as documented in `data/README.md`. The directory check establishes presence, not byte
identity. Map dataset/model manifests to those locations with byte verification; do not assume
`/Volumes/...`, old temporary logs or the Mac's caches will exist on a new host. Initial bootstrap
uses public/synthetic inputs. Private corpus use, held-out access and hosted-use permissions keep
their existing corpus-specific controls, even on an approved machine.

Provide one documented, tool-assisted private-data handoff under
`bd-01M48TNS4G0442F7SJW57671B6`, a bounded child of the existing delivery epic:

- Record owner approval for the destination and permitted corpus/use before any private transfer.
  `buc-gw01` is approved for development; additional machines require their own recorded approval.
  Bind the destination's authenticated host identity, explicit storage root and sufficient space.
- Preview the minimum source/model/study assets for the next milestone. Keep a private manifest
  of relative asset identities, byte sizes/counts, SHA-256, admission and partition restrictions.
  No participant text, identifying filenames or credentials enter the committed receipt.
- Copy over authenticated encrypted transport on the tailnet, with resumable staging. Preserve
  source originals and unrelated destination files; refuse conflicting assets rather than
  silently overwriting them. Require complete checksum verification before promotion/admission.
- Run destination byte validation and a small permitted reader smoke; record verified readback.
  Check interruption/resume, corrupt/truncated data, conflicting files and an unapproved host
  using synthetic fixtures. Missing assets remain explicit unavailable capabilities.
- Keep private data, derived study rows and data-bearing logs on approved machines/storage.
  Keep held-out partitions sealed. Code, recovery and data have separate manifests/receipts;
  GitHub, hosted CI and public artifacts carry only reviewed content-free receipts and synthetic
  fixtures. Machine transfer never widens corpus admission or authorizes redistribution.

Before the first sustained run, retain logs outside temporary directories, bound the process
and resource limits, and exercise interruption/restart once: a replacement session reads the
same Mote bead, branch/SHA, artifact paths, completed checks and next action and resumes without
repeating accepted work. The workstation has one writer and one heavy gate at a time; the helper
is bounded review/lookup. Continuous operation does not widen merge, release or data authority.
Use an established local runner/controller if unattended execution is requested, rather than
introducing a fleet/message-board dependency merely to keep the host running.

Exit: GitHub main parity is observed; required recovery tips are fetchable; hosted evidence is
bound to that revision; the destination has passed its fresh-checkout and resume smoke; and the
handoff can be reconstructed from committed instructions/receipts plus explicitly declared
private assets. Destination preflight, installation and private handoff are complete. The
checkpoint receipts identify the strict baseline, exact consumer/export acceptance and separate
main synchronization; tailnet presence alone never establishes those results.

#### Recovery slices for the first checkpoint

Prepare the reviewed audit/plan branch for integration so the current operating instructions
and recovered tracker history are available on main. Recover the following bounded slices in
separate commits, using their existing tickets and retaining evidence:

| Order | Existing ticket / candidate | Required result |
| --- | --- | --- |
| 1 | Reference-scope guard `bd-01M1CGP2K4H93WM8CW5EFEPTJK`, `ed9f478d` | Declaration-free recognized Scala source changes refuse; all 98 tool assertions pass on the integrated tool files. |
| 2 | FrameSet identity `bd-01M37ESFCE1SZFTV7NHJEC7SNC`, `69e48e9a` | Stream changes alter the receipt/identity; version change and scoped media results are retained, with live-media skips explicit. |
| 3 | Content scorer court `bd-01M379NZZ67GZJMWVBT5XNA0ZD`, `e02d80d` | Recover the tests/tools/evidence slice and recheck its affected platforms. Keep the full ticket open for the S2a-3 channel distinction. |
| Supporting docs | Docs-site claims `bd-01M31TQTBMBGEAS9A666B5MPA6`, `8973c520` | Review and replay the bounded documentation corrections during recovery or the first user walkthrough. |

Use the exact merge result for scoped gates and independent review. After the recovered slices,
run one strict full baseline gate in a clean standalone clone, then the StoryAtlas consumer at
the exact producer revision. Carry forward already qualified compiler, source and export work.
Resolve new regressions before adding features; distinguish infrastructure failures and old skips.

Exit: useful recovered slices are reachable from main, tracker records match Git, the baseline
and consumer receipts identify exact revisions, and one next failing product witness is named.
Complete the GitHub/workstation handoff above on this qualified baseline. The owner
has requested remote synchronization; package releases and unrelated public/data publication
remain separate actions.

Branch disposition is bounded maintenance: compare a branch when it overlaps the active work,
record salvage/supersession/retention against its ticket, and continue delivery. Keep the verified
recovery bundle. A sweep of all 61 unresolved refs is not a prerequisite for the first journey;
deletion requires a separate approved cleanup list.

### 2. Make the mapping contract usable through ordinary text files

This is the first visible product milestone: one ordinary UTF-8 source and two untimed recall
files produce both mappings, independently readable exchange and a package that StoryAtlas
actually opens. Inputs use synthetic or admitted text under rule 14.

Build the smallest complete path in this order:

1. Settle typed eligibility before lasting public result/denominator/codec shapes:
   `bd-01M16DBEH9PKER423BZ47ZKBMV`. Ineligible, missing and observed zero must differ across
   construction, reduction, wire formats and consumers. Complete the coherent migration;
   half-migrated meanings do not satisfy design rule 7. Apply the stable-signature/construction
   review `bd-01M19N0W937KVZCK78F6X57X1D` to each new public seam, with final closure before freeze.
2. Complete strict content wiring `bd-01M379N8HK5ZCV9W252X9MQ4VV`, then the remaining S2a-4
   unavailable-versus-abstention witness. Preserve canonical rename/permutation laws and the
   separately frozen historical path. Establish the controlled lexical/shared-evidence slice
   under G1 `bd-01M2TACM78289S4TECE91GT5K2`.
3. Extract the public reference `bd-01M2WVF86T8QEEA1TK8Z0ASJHW` and reconstruction
   `bd-01M2TAD04SR823TQVG9VPNH6R3` operations. Reference executes before HSMM; both use the same
   checked evidence digest. Reconstruction retains its named policies and historical parity.
4. Finish the necessary stable words/unit membership/support integration
   `bd-01M2WVH1DC8ZPDC992G4MX3TXG`. Wire offline source `bd-01M35PB1H5PDD55YVKR4TQ8M66`
   and prepare `bd-01M35B05TC6GBED9Z3HZMX618E` into facade `bd-01M2TADC4VKSDZ2S9SXETH2MYM`,
   exchange `bd-01M2WVHF4B5DAXJYY4W91VK4WV` and workspace CLI `bd-01M35B103S2KHRSF853GD2FYHW`.
   Reuse TextSourceCli, MappingExchangeCli and canonical M1 serializers; choose and test the
   public command spelling during implementation rather than promising a nonexistent command.

The registered embedding adapter remains G1/1.0 work. It may follow the lexical development
milestone; a strict embedding request remains typed unavailable until its receipt/replay court
passes. Keep G1 open. A coarse dependency on the full G1 container must not be bypassed silently:
when scheduling consumer work, identify the independently verified executable prerequisite,
split oversized existing work under T1 if necessary, and record the justified edge refinement.
Retain the embedding requirement and full parent acceptance at the release gate.

Exit witnesses for the text milestone:

- The user supplies files and ordinary configuration, without bench imports or handwritten
  checksum/join manifests. The source is prepared once for both participants.
- Every input participant/unit has a success, external alternative, abstention or typed failure;
  one failed participant preserves the other's results. Missing timing stays missing.
- An independent Python/R reader verifies joins, hashes, values, exact support and known answers.
- Interrupted publication and foreign/corrupt resume refuse, while a lawful resumed run preserves
  identity and completed participants. The untouched output opens in the exact-pair Atlas court.
- A walkthrough documents actual commands, emitted capabilities and limitations. Record the
  consumer receipt in the existing Atlas ticket/store; producer decode alone is not browser proof.

This is a milestone within the existing tickets. Their broader annotation-assisted, channel and
cohort acceptance remains open where unmet. Imaging, temporal grids and organization analyses
are not prerequisites for untimed text inspection. The final API freeze remains a release step.

### 3. Reproduce annotation-only Sherlock through the same public path

First complete the required registered pinned-ONNX/replay slice under G1
`bd-01M2TACM78289S4TECE91GT5K2`. The retained Sherlock development protocol names
`pinned-local-onnx`; a lexical provider substitution is not reproduction of that preset.
This adapter follows the lexical text milestone but precedes the historical Sherlock run.

Reuse the freshly scored development reproduction `bd-01M35JFJCQ2ANTVED792H705X6` and its
retained artifacts. Admit the annotation/recall file adapters through the existing prepare
ticket, preserving observation identities, fixed source cut, units, support and denominators.
Run the same public command/facade/exchange path used for text; keep corpus parsing outside the
solver and viewer. Use only permitted development participants under existing admission rules.

Exit: the structured A arm uses the retained provider/configuration and matches the historical
preset, with any implementation-caused difference independently measured and explained;
changing providers is not an accepted parity repair. The reference has its own declared policy;
independent readers and Atlas inspect the emitted package. This demonstrates the annotation-only
arm of `bd-01M35P83TCNHBSDY6NSK13Q3K0`, not the complete A+V/imaging journey.

### 4. Extend that journey to weights, time, video and imaging

| Order | Existing work | Executable completion witness |
| --- | --- | --- |
| A | Lambda `bd-01M35MHPFFKQHSJKC07VMWC7CW` | After the two endpoint paths work, test lambda 0, 0.5 and 1; preserve endpoint bytes, define the intermediate policy, refuse non-finite/out-of-range values, and bind policy/cache/export identity. |
| B | Complete remaining registered-channel acceptance under G1 | Carry forward the pinned ONNX/replay slice required by stage 3; finish remaining receipt/channel courts and close G1 only after its full acceptance passes. |
| C | Paired exchange and temporal integration `bd-01M2WVHF4B5DAXJYY4W91VK4WV`, `bd-01M3549Q5W5KQFSY3ZH81FARM0` | Query/readback preserves allocation/support distinctions, recall exposure, concentration, unprojectable mass and exact axes through the same facade/artifacts. |
| D | Video intake `bd-01M35JG7DXD7NSEE4WVEKT3CSF` and its caption/extraction children | Raw video produces a checked timed source; executed ordinal/media correspondence and replayed caption/model/frame/edition receipts refuse foreign or incomplete inputs. |
| E | Sherlock A+V under `bd-01M35P83TCNHBSDY6NSK13Q3K0` | Run A and A+V through the public path with the same unit inventory/cut/denominators and independently readable outputs. Preserve both results; an AV accuracy gain is not required. |
| F | OpenNeuro `bd-01M354E83Z4Z7DR4KPP6Q45MAJ` | Establish actual run/alias/full-media-origin/array-history joins and export one admitted development participant. Independent timing landmarks, volume indices and lost-mass accounting pass before cohort expansion. |

Extend the earlier walkthrough rather than creating a second command family or result schema.
These stages complete the declared 1.0 mapping journey. Synthetic clock declarations, a dataset
download, frame planning, provider availability or a benchmark result cannot replace its witnesses.

### 5. Qualify the demonstrated surface and release

Close the remaining stable-signature/eligibility checks, Native numerical-governance ticket
`bd-01M1D215EY4T5BR0VRJ694AMBQ`, platform-labelled goldens, actionable errors
`bd-01M2TAM66RTEMY3VVHA3N2MYXK`, reader docs `bd-01M19FPY1EC5QNTW6SBG3QYT3R`, and installation/
publishing/MiMa work `bd-01M2TAKTB61XNXV4Y6A9J3CB0C`. Cite exact-revision hosted CI under
`bd-01M19G69RQCHMT2EMG11XFT4WX`, using the already chosen GitHub-hosted runner route.

Exit: a fresh checkout/installation executes the documented text and Sherlock journeys; the
exact release tree has strict platform and sibling-consumer receipts; exported examples have
independent readback; the stability table matches actual signatures; the release gate
`bd-01M2TAMJ95K9D7H248A8387YW4` is satisfied. Publish after owner authorization. Calibration,
superiority and organization-recovery claims require their separately named scientific evidence.

### Working cadence and escalation

- One implementation ticket is active at a time. The primary agent writes/integrates; the second
  agent reviews the exact candidate or performs a separable bounded lookup. One heavy gate runs
  at a time. Mote owns claims, reservations, remaining acceptance and handoff; chat carries owner
  decisions. Existing governing documents remain the source of design rules.
- Size each implementation slice to one acceptance criterion under T1. Define its failing witness
  before editing. Complete the smallest meaningful checks, required mutation/control evidence,
  scoped platform gate and independent review; integrate promptly within authorized scope.
- At each completed slice, show the current user artifact and record one next failing witness,
  exact commit, command exits/totals and remaining acceptance. Keep tracker operations committed.
  Review findings are BLOCK or FOLLOW-UP (T4); a follow-up becomes bounded work, not a holding queue.
- Use `bash tools/reference-scope.sh BASE_SHA CANDIDATE_SHA` for each actual code diff. Run the
  emitted commands in a clean standalone clone. The shared baseline/release check is
  `sbt -batch 'set ThisBuild / tlFatalWarnings := true' checkAll`; formatting runs last. Docs-only
  slices use a complete candidate `git diff --check`, link checks and separate text review (T2).
- When a code BLOCK stalls the current slice, retain its runnable reproducer and revised next
  action. If it needs an owner decision or external resource, expose that exact need and continue
  a ready, bounded task that does not depend on it. Do not fall back to a general backlog sweep.

Scope remains fixed: reuse the existing modules/contracts and completed qualification. Film
compilation and organization analyses stay in 1.1; benchmark/LLM campaigns, corpus expansion and
empirical calibration remain on their existing research lanes. No calendar release promise is
made before the fresh baseline and first public journey expose the remaining integration cost.

**Current action:** recovery, numerical determinism, baseline qualification, GitHub/workstation
handoff and JVM gate serialization are complete on `cb310958`. The coherent eligibility migration
`bd-01M16DBEH9PKER423BZ47ZKBMV` is qualified at source `d5a7aefd` and consumer `2175735`;
[the full receipt](evidence/estimate-eligibility-20261006/README.md) binds the 80-cell source matrix,
15-cell default-pinned consumer matrix, compiled mutation controls and documentation successor.
The independently reviewed pair landed locally on source `68989cbc` and consumer `2175735`;
eligibility is closed. GitHub and approved `buc-gw01` are synchronized; both workstation
checkouts are clean and Mote health checks pass. The final handoff metadata preserves the
qualified inputs. Next is strict content wiring `bd-01M379N8HK5ZCV9W252X9MQ4VV`: controlled
Lexical / ContentTable / Unavailable channels through generation, canonical evidence and HSMM
rederivation together, retaining historical pins and distinct unavailable/abstaining outcomes.
The first proposed witness is canonical contradiction order through the production strict
evidence route and its successful HSMM validation. It has not yet been executed. G1, the broader
construction audit and release acceptance remain open; the remaining stages follow executable
prerequisites, not the size of the ready queue.

## Completed fixture milestone: M1 (22 September 2026)

The owner activated [one reproducible source-plus-recall investigation](goals/source-recall-workspace-m1-20260922.md),
Mote `bd-01M34J9FS10B14WQA309K0P6V8`. Its execution sequence is support honesty -> G1 records -> checked
producer packet -> StoryAtlas adoption/loading -> shared selection/matrix/evidence -> saved-state
replay/export and exact-pair acceptance. This bounded integration of the existing viewer supersedes
the earlier deferral of viewer work for this milestone only. The reference-measurement, film and
release commitments below remain in force on their own lanes and do not all gate M1.
Live Mote and the goal's explicit dependency/receipt handoffs supersede older next-task prose.
Its local exact-pair acceptance is complete; the charter retains the receipts. Ordinary-file
production delivery now follows the October sequence above.

## Operative 1.0 scope (owner decisions, 22 September 2026)

These decisions supersede any conflicting prose below, including ruling E and the G3/G4 rows of
§4. The live record is the release gate `bd-01M2TAMJ95K9D7H248A8387YW4` and its dependency
closure; the tracker reconciliation landed at `408178ef`.

- **1.0 delivers** ingestion of stories, videos and their recalls from ordinary files; mapping
  that publishes both the local reference result and the named structured reconstruction, with an
  explicit reference/reconstruction weight λ; exchange tables with an independent reader;
  temporal queries; the OpenNeuro ds001132 export; a public CLI; and a freshly scored Sherlock
  development reproduction. "Stories alone" means offline text to a deterministic segmented
  source, with a narrative StoryModel from `storyBuild` plus a tabular StoryModel export. "Videos
  alone" means a checked timed source built from shots and captions with media receipts.
- **1.0 acceptance** is the mapping journey `bd-01M35P83TCNHBSDY6NSK13Q3K0`. It runs Sherlock
  annotations-only and annotations+video, plus a text story, through the public path.
- **Moved to 1.1:**
  - The film compiler (video to narrative StoryModel), D1A-film, D1B end-to-end proofs, V1 and E0
    (container `bd-01M35MK5N6F0EWJE2EYT3D8Y9M`). Film-capable public types stay in 1.0.
  - Organization analyses: compatibility, organization counts, G3 synthetic recovery and the
    organization preview (container `bd-01M35P2EKWC52PZTMN2R5NMDFR`). Until then the facade
    offers no organization readouts.
- **Post-1.0 research (P3):** the benchmark, LLM-arm and human-ceiling work from the mapper
  roadmap.
- **Current division of labor:** one primary agent and at most one helper, as recorded in
  AGENTS.md on 6 October. Mote claims remain authoritative; one writer per scope and one heavy
  gate at a time. The September Fray assignments are historical coordination evidence.

The [recall-to-encoding workflow plan](../plans/2026-09-22-recall-encoding-workflow.md)
extends this delivery toward reusable temporal queries, uncertainty maps, structured
analysis exports and dynamic StoryAtlas views, with OpenNeuro Sherlock as the first
imaging adapter. It preserves the active G1 landing and existing scientific/release
gates; its new command and projection contracts are planned, not implemented.
Visualization implementation belongs in `~/code/scala/storyatlas4s`, extending the
M1 workspace through the same checked producer contracts and exact-pair acceptance.

The 22 September workflow reconciliation is indexed in [BACKLOG.md](BACKLOG.md).
Base mapping exchange no longer waits for organization statistics. Transition-table
production, independent reader checks and tiny-answer validation belong together
to the full G2 preview, whose organization/compatibility/recovery gates remain (1.1 since
22 September; see the operative scope above).
Temporal queries, raw-BIDS scan alignment and the post-M1 StoryAtlas temporal
consumer have distinct execution tickets. Optional video acquisition, fine
localization and empirical calibration do not gate the basic workflow.

## Execution handoff (23 September 2026)

The reviewed P1 batch landed locally at `577ebfdb0f9348f235ef75706167ddbbd6684cec`.
Its executable inputs match the tested `7469d3d7f49dd04df2d2bc76504fbef1202a703b`;
the merge adds only documentation, tracker records and a script comment.
The complete strict pinned-dependency `checkAll` passed: **8,570 passed, 5 skipped,
0 failed**. The sibling StoryAtlas consumer at `ef08425759840b84c5b6c4a5ecb8626bc5401265`
passed **402 tests**, strict compilation, formatting and JavaScript linking.
That consumer includes a separately reviewed two-line test traversal repair.
The [integration evidence](evidence/p1-integration-20260923/README.md) preserves
failed attempts, fixes, final command receipts, exact dependency identities and
merge equivalence. These local gates do not replace hosted CI or browser acceptance.

The closeout pass also completed two older review/documentation tickets at
`c600dd90`, with closure records at `7c9fc6dc`:
[movie-time contract reconciliation](evidence/movie-time-contract-closeout-20260923/README.md)
(`bd-01M199ABAG5SXR9C6Y0G4X6J2T`) and the
[replacement intake cold review](evidence/intake-cold-review-closeout-20260923/README.md)
(`bd-01M2WTPDBB4MQBXEJ181Q85ZG2`). The first lands the retained V12 amendment and
accounts for all nine required courts; eight incomplete courts have explicit 1.1
children. The second records a fresh A–C review and files its two remaining findings.
Those implementation follow-ups remain open. Neither documentary closure certifies
the broader film or intake workflow.

The sampled-frame S1 slice (`bd-01M37AMS9NYY9J0FWJESVPJ56E`) landed at
`eb5463b35bb54b763cb4807975925db564533405`, including the reviewed stream-identity
repair: **60 passed, 3 skipped, 0 failed**, with ten named mutants killed.
Its [receipt](evidence/ac4-s1-sampled-frames-20260923/README.md) qualifies this
bounded media slice; the parent video-ingestion workflow remains open. The optional
[offline-source review receipts](evidence/review-receipts-20260923/README.md)
landed separately at `c90a7e5d` and add evidence, not another workflow completion.

The Admissibility/StructuralCoverage seal (`bd-01M17ZNXY6AS1CMBQJRH3JMNVX`)
landed at `b01c3d09dcb4711b3d1c6abc9a15ba822fda7a9b`. Its
[final qualification](evidence/admissibility-final-20260923/README.md) accounts for
all 51 required scoped tasks: **8,303 passed, 5 skipped, 0 failed**. The existing
402-test Atlas consumer is reused with checked production-source equivalence.
Bounded test-fixture repairs retain the default 30-second deadline and have named
falsifiers; the failed Native attempts and surviving exploratory mutant remain
in the record. This closes the remaining cost-type construction door, not the
Estimate eligibility repair, API freeze or release gate.

The owner decisions and earlier landings close the CI-runner choice, exact-directory
disk cleanup, stability-boundary decision, strict-warning repair and Sherlock
re-scoring tickets. Existing GitHub-hosted runners remain the chosen route; the
stable boundary in [api-stability.md](../api-stability.md) is approved, not frozen.
The new landing completes the real-text compiler repair and declared-lossy
StoryModel export. It also lands deterministic offline text-source construction,
strict tie policy and part-correct frame planning as bounded slices of open
workflows. The ordinary-file facade, content-only scorer/registered channels,
and checked video acquisition remain explicit next steps.

Fray threads 68 and 70 record the accepted next handoff. Mote claims and exact
path reservations govern implementation; this table does not replace them.

| Existing work | Accepted next owner | Next executable witness |
| --- | --- | --- |
| G1 shared evidence `bd-01M2TACM78289S4TECE91GT5K2` | claude-release | Content-only scorer payloads cannot expose nested coordinates or original IDs; registered lexical and embedding adapters replay the same bound evidence. Preserve missingness, historical parity and rename/permutation laws. |
| Offline source `bd-01M35PB1H5PDD55YVKR4TQ8M66`, facade and prepare | codex-temporal | An ordinary text file and an annotation source reach the same checked public source/evidence seam, with an independently readable exchange; bench-only construction does not satisfy this witness. |
| Video source `bd-01M35JG7DXD7NSEE4WVEKT3CSF` | claude-p1 | Caption loading replays model/frame/receipt checks and joins per-part extents; foreign editions, missing receipts and unsampled-frame captions refuse. |
| Remaining stable-signature closure and Estimate eligibility | claude-sm-0923 | Outside-package forgery probes and an explicit eligibility/denominator/codec migration, rechecked against landed main. Estimate eligibility repair remains a 1.0 prerequisite. |
| Public mapping journey `bd-01M35P83TCNHBSDY6NSK13Q3K0` and release gate `bd-01M2TAMJ95K9D7H248A8387YW4` | codex-p1-lead | A text story and Sherlock A/A+V run through the public path with complete accounting and independent reader evidence; then the remaining CLI, lambda, OpenNeuro, stability and hosted-CI gates close on their own receipts. |

G1's embedding adapter remains required for 1.0. Facade development can proceed
with lexical/fixture channels while it is built, but a strict embedding request
must report typed unavailability until the adapter is checked. Hashing identifiers
is not a proof of rename invariance; keys and bindings stay in orchestration,
while semantic payloads and controlled registered adapters undergo the stated laws.

[ADR 0022](../adr/0022-mapping-producers-and-evidence.md) codifies the Fray 75/712
architecture agreement: complete mapping producers
(an LLM, human import or another engine) and local scoring providers are distinct
extension points that join one checked result/exchange family. A categorical result
must not require a fabricated `HsmmResult` or numeric confidence. Acquired evidence,
observed coverage, selected/rendered items and semantic support are distinct facts;
protocol-bound run sets retain their per-model/sample outcomes and replay receipts.
Declared input restrictions can disqualify strict-reference use but do not certify
absence of learned priors. Root and codex-temporal own the concrete result/facade
binding proposal; these extensions are agreed design, not completed implementation.
The existing LLM evaluation tickets remain separate. This agreement schedules no
experiment, changes no landing gate and authorizes no hosted data use or spending;
empirical response frequencies remain distinct from calibrated correctness.

## 1. Assessment

The project needs a correction at its inference-to-analysis boundary, not a rewrite. Keep the
checked source types, graph inference, intake, replay, exact coordinates, and useful structured
mapper. Make a mapping usable through a public API and export, and make its scientific meaning
depend on the evidence and inference policy actually used.

The most consequential gap is measurement validity. A reconstruction that rewards forward
movement can be useful for localization and still be unsuitable for measuring forward movement.
An order-prior switch, a shuffled-transcript subtraction, and a prose warning do not solve this.
The engineering response is a reference path whose information use is restricted and tested;
the scientific response is recovery validation. Neither substitutes for the other.

### Historical baseline evidence (19 September)

These are findings at the baseline, not current defect/status assertions. The dated
completion records in section 4 and live Mote supersede them; in particular, scorer
and production annotation-clock repairs have since landed. This table is retained
as the rationale for that work, not a fresh build or empirical reanalysis.

| Finding | Evidence inspected or executed here | Consequence |
|---|---|---|
| Production bypasses clock repair | `SherlockAnnotations.scala` `locusFor` multiplies seconds by rate; `sherlockRecallMap` calls default `parse`; `clockRepairs` has only test callers | Integrate the existing checked operation; don't delete it or claim integration already happened. |
| Repair builder improved, integration still incomplete | `TimebaseRepair.scala` checks mapping/formula and derives offset, but does not parse presentation parts or carry repair receipts in `Atlas` | One bounded intake integration ticket. |
| Axis names require a real join | Record target IDs such as `media-part-a-playback-ticks` differ from the content-derived axis IDs of `SourceBundle.filmEdition` | Resolve and verify record-to-bundle axis identity before projection. Passing the repair's own target back to itself would make the check tautological. |
| Strong sequencing assumptions remain | `RecallOrderControl.scaledConfig` scales four transition kinds; `Stay` remains. `Content` retains sequential external logits. `MonotoneScene` and fill follow inference | Keep these as reconstruction/diagnostic profiles, not reference measurement. |
| A reusable local computation exists | `StageTrace.localMass` uses a uniform prior over retained states; `render` refuses refined runs | Extract pre-inference evidence, not a diagnostic that requires an HSMM result first. Audit candidates too. |
| Assumption provenance is partial | `LayerUse` in `align/hsmm.scala` explicitly excludes refinement | Derive stage receipts for retrieval, rendering, scoring, context, refinement, inference, decision and projection. |
| Scorer discards outcomes | Executed synthetic `gold_scene.load_arm` probe: 3 input units (mapped, abstaining, untimed), 1 loaded unit. No corpus gold read | Repair unit identity and fixed eligibility before any further efficacy comparison. Timing-dependent gold eligibility must be distinguished from transcript-order eligibility. |
| General product logic remains in bench | `RecallToVideo.scala` owns `TimedSourceView`, configuration/orchestration; sidecars distinguish raw and decoded choices | Extract into existing modules and keep historical serializers as parity witnesses. |
| Film library migration remains real work | Approved D1A S0–S4c plan; current `StorySourceView` is text based | *Superseded 22 September:* compiled film API + D1B proofs moved to 1.1; film-capable types stay in 1.0. |
| Tracker state misrepresents activity | Live snapshot: 119 unfinished (96 open, 12 doing, 11 review), 206 closed; no active claims/reservations | Reconcile existing issues, remove obsolete fleet authority, and expose a small working queue. |
| Push premise is stale | Local HEAD, tracking ref and live `git ls-remote origin refs/heads/main` agree at the baseline | No remaining 75-commit push backlog. Retain only unresolved CI action. |
| CI did not execute | [CI run 35406786299](https://github.com/bbuchsbaum/storymodel4s/actions/runs/35406786299), empty job steps; check annotation says account payments/spending limit prevented startup | Account/runner action, not a demonstrated Scala failure. Local qualification can proceed; reproducible release still needs its declared gate. |
| Real Sherlock parity inputs are present | `tools/data-root.sh --check` found annotation, recall, ONNX files and partition; contents/gold were not opened | The clock integration can require a real-data before/after replay. Presence is not byte verification. |

A concurrent tracker-only commit, `46d476e720ac715a247619b615aa32cab792935d`, added 17 intake issues.
They are included in the reconciliation: the existing C1 clock issue owns the consolidated
integration; TR/alias consistency joins the scorer; distinct maintenance/later findings remain
open. The Scala assessment baseline is unchanged. The initial remote-parity observation does
not imply these later tracker changes have been pushed.

The supplied consultant files match this baseline but refer to an absent `AUDIT_EVIDENCE.md`.
Their E1–E13 references are not independently inspectable evidence. The table above supplies a
bounded reinspection; it does not silently certify every consultant assertion. Historical local
gates (including the September 17 5,986-test record) remain historical and locally observed.

### What we accept, modify, and defer

Accept the public mapping artifact, explicit outcomes, exact support, downstream tables,
source reuse, fail-closed scoring, small facade and separation of shipping from superiority.
Accept the review's reference/reconstruction distinction and behavioral recovery tests.

Modify the consultant sequence: assumption metadata enters the first mapping schema, and the
strict reference path and compatibility guard precede any scientific organization preview.
Do not ship an order-ablated HSMM under an independent-measurement label. Preserve a useful
annotation-assisted preview without waiting for the complete film compiler migration.

Defer contextual reference inference, joint behavioral parameter inference, additional solver
families, new corpus expansion, a new viewer, Parquet, and a universal refusal-type migration.
None is needed to close the first usable journey. The existing research comparison program
continues on its own lane and cannot gate ordinary library extraction or film engineering.

## 2. Decisions made now

1. **One evidence representation; named uses.** Reference measurement and structured
   reconstruction share immutable candidates/local scores, identities and export. Joint
   scientific inference is a later consumer, not a third pipeline. See [ADR 0019](../adr/0019-mapping-measurement-policy.md).
2. **Reference does not mean calibrated or prior-free.** Start with independent local
   normalization under an explicit target universe, grain, prior, temperature and external
   alternatives. Default measure name: normalized local score mass. A model-posterior or
   calibrated correctness claim requires the corresponding evidence.
3. **Scientific readouts require a compatible policy.** A successful check establishes policy
   compliance, not unbiasedness. Unknown provenance fails a reference request. Reconstruction
   statistics remain available, labeled as properties of that reconstruction.
4. **Single source for Sherlock admission pins.** The versioned, committed repair JSON is the
   admission record for the annotation and two media hashes. Remove duplicated Scala literals
   on integration. A changed record changes provenance and requires review; a caller-provided
   arbitrary JSON is not silently the admitted default. Verify artifact bytes, bind record
   digest/schema/version, and resolve references uniquely. A digest alone is identity, not trust.
   Keep annotation-byte verification distinct from later media-reachability verification.
5. **Existing `ClockRepair`, no new mapping vocabulary.** Parse parts and crosswalk restrictions,
   construct per-run maps and carry receipts to each row. Preserve `certifies`, `whyNotRepaired`,
   `explicitlyNotUsed`, and `doesNotCertify`; refuse unsupported semantic declarations and retain
   explanatory prose without treating it as executable policy. Validate row coverage, part joins,
   exact rates/extents and tails. Require integral, representable ticks; never round.
6. **Scope stays bounded.** Annotated-source preview first. *Superseded 22 September:* P1a was
   adopted, so D1A-types (landed) and the film-capable types are 1.0, while D1A-film, D1B
   end-to-end, V1 and E0 are 1.1. See the operative scope above.
7. **No new top-level module.** `align` owns pure evidence/inference/readouts; `recall` owns
   inference units and word/timing membership; `corpus-intake` owns file adapters; `codec` owns
   checked interchange; `pipeline` owns I/O/config/public execution; bench consumes these APIs.
8. **One implementation slice at a time.** At most one independent fixture/evaluation slice
   alongside it. P0 means the immediate foundation, not every desirable future comparison.
   Containers use nonblocking `rel`; `dep` names an actual prerequisite, never importance.

## 3. Measurement contract

### Evidence and information access

Freeze source and recall inventories before inference. Every requested unit has exactly one
processing outcome, including failures and abstentions. Keep source/external alternatives,
candidate omissions, specificity, support coverage and provenance separately representable.

The strict reference scorer receives fixed, self-contained recall packets and content-only
target projections with opaque IDs. Playback position, source sequence numbers, chronological
list order, previous assignments and generic trajectory preferences are not predictive inputs.
Retrieval, tie handling and prompts must satisfy the same restriction. The readout receives
the source coordinates separately. Permutation tests reconcile identities; numeric tolerances
are frozen before comparison. Intrinsic temporal meaning in language is not claimed absent.

Preserve linguistic interpretation. A later context profile may use explicit coreference and
evidence-backed relations, but must declare coupling and its allowed evidence for each estimand.
An explicit temporal assertion cannot automatically establish the temporal fidelity under study.

### Uncertainty and estimands

For adjacent original inference units, use joint assignment weights `Q_i(j,k)` to summarize
backward, forward and same-target transitions. Independent row products are permitted only
for the declared independent local model; context or sequence models need joints or coherent
samples. Normalized scores yield model/score-conditional summaries, not empirical probability
guarantees. Cross-part presentation order requires a checked composition/order declaration;
otherwise the transition is incomparable. Story-world order is a separate, potentially partial axis.

Declare the denominator. Initially export counts and the ratio of expected counts; do not label
it the expected per-trajectory ratio. Preserve external, incomparable, unresolved and failed
adjacencies. `A -> unresolved -> C` supplies no directly observed `A -> C` transition. Report unit
coverage and transition coverage by participant/condition, independently of audio timing.

Inference, organization and display grains are separate. Copying a clause assignment onto 15
words creates no additional persistence evidence. Scene-to-time allocation is an explicit
projection, not second-level localization; retain interval unions and support completeness.

Compute min/max additive transition counts over a declared admissible assignment set using
exact enumeration for tiny witnesses and sparse dynamic programming in production. These are
conditional ambiguity bounds, not confidence intervals. Candidate coverage and any threshold
forming the set travel with them. For unresolved rows with no declared admissible universe,
report bounds unavailable or use a separately declared completion universe; never bridge the gap.
Do not infer participant organization from concentrated averages of uninformative scores.
The first solver supports products of per-unit admissible sets and explicitly represented local
adjacent constraints only. Arbitrary coupled or HSMM admissibility requires another solver and
is refused by this one; it is not an implicit obligation of the preview.

The annotation preview identifies a checked source representation containing a bundle inventory
and per-target/per-axis bundle membership. This accommodates Sherlock's two existing part bundles
without inventing a single bundle ID or prematurely implementing film composition. The canonical
compiled `StoryModel` continues to have one composed bundle under D1A. Cross-part order is supplied
only by the verified presentation-order declaration, not by lexicographic part IDs.

### Scientific extension, later

Joint inference estimates organization parameters `beta` while integrating mappings:
`p(x | beta, phi, S) = sum_z p_phi(x | z, S) p_beta(z | S)`.
It needs an assessed observation likelihood (or explicitly generalized-score interpretation),
not a discriminative posterior silently relabeled as a likelihood. Reverse and revisit paths
must retain support. With observation likelihood constant in `z`, the likelihood is constant in
`beta`; observations must not update its prior. This is a future acceptance witness, not a reason
to delay the reference product.

The review's exponential-tilt identity is correct for a fixed finite support and the same
reward/readout statistic: `d E_lambda[R] / d lambda = Var_lambda(R) >= 0`. It does not prove the
effect size for every other readout or establish that all contextual information is invalid.
The cited [modular inference paper](https://arxiv.org/abs/2202.09968v4) supports restricting model
feedback as a methodological choice; it does not validate this mapper. The supplied circular
analysis reference is background, not a repository-specific empirical finding.

## 4. Delivery order and stopping gates

Task keys below resolve to existing or newly created Mote tickets in [BACKLOG.md](BACKLOG.md).
They describe deliverables, not claims of completed implementation. Each ticket names its
affected code, falsifier, artifacts and completion boundary.

| Gate | Smallest useful deliverable | Required witnesses | Stop condition |
|---|---|---|---|
| G0: trustworthy baseline | Freeze existing mapping inputs/config/outputs; correct scorer; S0 text pins; clock integration | Stable identity/denominator mutations; captured commands; same-input legacy report byte parity; wrong-axis/missing-repair refusal | No new efficacy claim through old scorer. Failed parity is investigated, never blessed away. |
| G1: shared evidence | Checked mapping records with stage policies; reusable local evidence and independent reference profile | External-consumer construction and codec probes; shared evidence identity; packet/target-order/ID equivariance; no sequence call in reference path | No reference label for HSMM ablations or unknown provenance. |
| G2: usable analysis preview | Typed facade, safe publication, reference + reconstruction views and λ, one Python/R reader (organization readouts: 1.1) | Independently authored tiny answers; no gap bridging/word inflation; all outcomes accounted; offline replay; interruption/corruption refusal | A researcher can run source + recall to interpretable tables without bench imports. Explicitly uncalibrated where applicable. |
| G3: behavioral recovery (1.1) | Frozen synthetic forward/reverse/revisit/ambiguity/unequal-quality court, then independent human annotations | Recovery bias, group-effect attenuation, reversal sensitivity, candidate coverage, specificity and uncertainty; frozen tolerances; failures retained | Architectural compliance permits exploratory use; empirical claims require the corresponding recovery evidence. |
| G4: compiled-film API (1.1) | Approved D1A S0–S4c, film phase plan/compiler, D1B typed signatures and end-to-end proofs | Existing text pins; exact film support/identity refusals; shared mapping contract; explicit annotation/caption origin | No 1.0 completion by relabeling the annotation preview as film compilation. |
| G5: stable delivery | Release evidence, public examples, migration/stability table, runnable CI route | Clean exact-SHA gates with bound totals; docs and consumer replay; resolved readiness | Publish only capabilities actually demonstrated. Superiority and certification are separate claim gates. |

Implementation order:

Execution checkpoint, 19 September 2026: the [frozen mapping baseline](evidence/sherlock-baseline-20260919/README.md)
is complete. Two unchanged development runs and the historical TSV agree; all
1,000 annotation coordinates are retained. The clean local full gate passed.
The [fail-closed scorer and unit manifest](evidence/sherlock-scorer-20260919/README.md)
are also complete: fixed support, complete outcomes, distinct participant/pooled estimates,
and actual Scala/Python rule witnesses with mutations. The measured observation inventory
contains **2,577 units**, not the original plan's 2,560 (the latter is the sum of final
zero-based ordinals and the adjacent-pair count). No units were dropped to match that error.
The [production ClockRepair integration](evidence/sherlock-clock-repair-20260919/README.md)
is complete: every annotation bound uses the checked map, each row carries a repair receipt,
and two development replays preserve all five legacy artifacts and all 1,000 coordinates.
Seven compiled refusal mutations and the clean full gate passed.
The [S0 text parity pins](evidence/d1a-s0-text-parity-20260919/README.md) are also complete:
the captured WOG compile, model/derivation bytes, renderings, node orders, Atlas textual twin
and historical receipts are frozen. Five compiled mutations cover the four required changes;
the clean full gate reports 6,444 passed executions and 5 skips across all 56 test tasks.
The [S1 ADR amendment](evidence/d1a-s1-adr-20260919/README.md) records the approved rulings
and compatibility design; its separate committed-text review found no S1 findings.
The [S2 checked substrate](evidence/d1a-s2-substrate-20260919/README.md) is complete:
revision-5 design corrections, 52 compiled killed mutants, 73 passing restored focused tests,
and a clean full gate with 6,600 passed executions and 5 unchanged skips across all 56 tasks.
All 13 docs examples and the 245-test storyatlas4s consumer gate pass at the exact candidate.
The [S3 typed acquisition support](evidence/d1a-s3-acquire-20260919/README.md) is complete:
8,640 declared historical text cases preserve complete resolver states, literal gap names remain
unchanged, and all 18 compiled mutants are killed with accepting controls. Restored focused tests
pass 55/55; the clean full gate passes 6,663 executions with the same five skips across 56 tasks.
Formatting, all 13 docs examples and the exact-provider 245-test consumer gate also pass.
The [S4a typed node support and fallible draft](evidence/d1a-s4a-node-support-20260919/README.md)
is complete: all 36 compiled mutants are killed, including ten separately cleaned construction
and visibility probes; 40 restored focused tests pass. The clean full gate passes 6,765
executions with the same five skips across 56 tasks. Formatting, all 13 docs examples and the
245-test migrated storyatlas4s consumer gate pass; frozen S0 values remain unchanged.
The [S4b sealed envelope, identity and checked text witness](evidence/d1a-s4b-envelope-20260919/README.md)
is complete: 54 compiled mutants are killed, including 24 separately cleaned compile probes;
62 restored focused tests pass. The clean full gate passes 6,927 executions with the same five
skips across 56 tasks. Formatting, all 13 docs examples, the exact-provider 245-test consumer
gate and 260 browser checks pass. The preserved baseline reproduces two existing smoke defects;
the separately reviewed test-only repair changes no application behavior. S0 is unchanged.
The [S4c sealed alignment capabilities](evidence/d1a-s4c-alignment-source-20260920/README.md)
are complete: 24 compiled mutants are killed, including 14 clean production/probe recompiles;
26 restored focused tests pass. All three backend HSMM artifacts exactly preserve their own
frozen baseline, including existing Native differences. The clean full provider gate passes
6,996 executions with the same five skips across 56 tasks; formatting, 13 docs examples and
the exact-provider 245-test consumer gate pass. Both frozen resources remain unchanged.
The next sequential slice is **D1B typed recall signatures**
(`bd-01M2TAC80YRK5JFNKTT7B7CAQN`), including point support and all 17 participant parity.
G0 as a whole remains open for infrastructure and independent preservation work;
these engineering gates establish no accuracy or measurement-validity claim.

Active goal, 19 September 2026: the owner requested a larger delegated work package.
[Film library foundation](goals/film-foundation-20260919.md) groups S1–S4c, D1B signatures,
the film phase plan and D1A-types parent closure: nine existing Motes, one implementation
worker (`film_foundation`), sequential gates, unchanged S0 values and explicit owner decisions.
The broader reference-measurement and delivery lanes remain as defined below.

1. **Capture baseline**, repair scorer, and pin S0. Preserve the at-risk MASC branch before any
   cleanup. Obtain a clean gate through the infrastructure ticket; a billing block does not stop
   local engineering but cannot count as an executed CI pass.
2. **Integrate Sherlock clocks** against that baseline. Fix the false production-caller claim.
   Legacy report bytes and a digest of every row/part/startTick/endTick tuple must match,
   including rows unused by the selected recall. Two unchanged baseline runs must agree first.
   Newly added provenance artifacts intentionally differ and
   are checked separately. Use the same cached provider responses/config, so stochastic reruns
   do not masquerade as arithmetic regressions.
3. **Land result semantics, extract local evidence, add reference inference and compatibility.**
   The first schema already carries policy/measure kinds. Implement a deterministic lexical
   reference first using existing capabilities; additional providers enter only after isolation
   and receipt tests. Retain the structured mapper as a named historical preset.
4. **Add readouts and exports**, then run the synthetic recovery court and end-to-end preview.
   Include a text-source miniature and an admitted annotated-video example. Real-data artifacts
   stay outside Git; commit only permitted receipts/checksums and redistributable fixtures.
5. **Complete film types/compiler/D1B** in the approved order. S3 and S4a both follow S2; S4b
   follows both; S4c follows S4b. Public D1B signature migration follows S4c and clock integration because its parity court
   consumes Sherlock coordinates. The earlier preview
   uses checked anchored targets and does not redesign `StoryModel` or duplicate its compiler.
6. **Validate and release.** Independent human recovery/benchmark preparation may proceed
   alongside engineering. Hosted models, human recruitment and unavailable media retain their
   existing narrow owner decisions; they do not block a local annotation-assisted preview.

## 5. Recovery protocol and acceptance discipline

Freeze fixtures, admissible sets, estimands, effect sizes/tolerances, random seeds, aggregation,
missingness rules and comparison counts before inspecting method outputs. Numeric performance
targets for human data must come from the intended use and be preregistered; this planning review
does not invent an accuracy promise. A negative recovery result can complete an evaluation
ticket while blocking the scientific claim. Never drop the hard cases to make a profile pass.

Minimum deterministic witnesses:

- Point masses for `2 -> 7 -> 3 -> 8`: backward 1, forward 2, same 0, comparable denominator 3.
- Two independent uniform assignments to two ordered targets: backward .25, forward .25,
  same .5; admissible backward-count bounds [0,1]. Report low information, not precise behavior.
- Equal row marginals with different joints must yield different transition summaries; reject
  marginal-only input when independence is not part of the policy.
- `A -> unresolved -> C`: two unresolved adjacencies, no direct A-to-C transition.
- One inferred clause displayed on 15 words: organization count is unchanged.
- Candidate truncation, duplicate target IDs, reordered storage, changed opaque IDs, wrong axes,
  all-external inputs and failures have explicit expected results/refusals.
- At fixed true organization, vary ambiguity, omissions, paraphrase and transcription quality;
  quantify induced condition differences. Human annotators retain ambiguity and multiple
  occurrences and receive no instruction to enforce chronology.

Every implementation ticket records baseline and result SHA, exact paths, executable commands,
test totals/exit, artifact digests, one discriminating failure injection per new guarantee,
compatibility impact and a separate cold review. Proposed test names are labeled proposed until
implemented. Follow `tools/reference-scope.sh` and AGENTS T3 for the scoped landing gate;
the strict full `checkAll` remains the scheduled main/release check.
Do not call a gate green from an exit without bound test totals. Test/provider/environment skips
remain visible. Clean archives or standalone clones avoid the linked-worktree sbt limitation.

## 6. Backlog policy and remaining owner decisions

Reuse the mapper epic as the delivery container. Keep original rationale in Mote history and
retain a complete before/after reconciliation. Close only demonstrated landed work, explicitly
superseded duplicates, or retired fleet duties. Broad old defects with incomplete evidence remain
open with a remaining-work statement; they are not closed merely to make the count smaller.
Remove stale assignees and doing/review states. The 2026-09-19 reconciliation was a
single-developer planning exercise. For the owner-directed cooperative P1 push on
2026-09-23, Mote carries current ownership and exact path reservations; Fray thread 49
on the existing shared board carries routing, reviews and handoffs.

Use lane tags and dependency-filtered queries from BACKLOG. M1 is complete locally; its
[canonical charter](goals/source-recall-workspace-m1-20260922.md) retains the exact
producer/consumer acceptance evidence. The active 1.0 queue starts with the strict build
gate repair and the stable-type decision, then the remaining shared-evidence and
source-ingestion/compile/export paths. The bounded Sherlock development reproduction
can proceed independently under its preregistered admission rules. Completed
baseline/scorer/clock/S0 work remains prerequisite evidence, not another execution queue.
The temporal workflow extends this with bounded downstream tickets. Benchmark campaigns,
corpus expansion, interview enhancements and fleet-tool defects remain separate. No
benchmark win or optional hosted provider sits on the preview/film path.

The CI route is decided on 23 September 2026: use the existing GitHub-hosted Ubuntu
runners, with no billing, visibility or credential changes. The
[route receipt](evidence/ci-route-20260923/README.md) shows that the latest observed hosted
run reached tests; the prior startup block is historical. The separate exact-revision
hosted qualification and G5 release gates remain open.

The owner approved the [1.0 stability boundary](../api-stability.md) on 23 September:
stable mapping/source contracts and their required estimate/result values;
experimental inference implementation, feature builders, organization readouts and
narrative compiler. The final signature/construction audit and release gates remain
required. `Estimate` eligibility must be repaired before the freeze; the deferred
signature/licensing API work moves to P3.

The owner-approved removal of two audited merged worktrees is recorded in the
[cleanup receipt](evidence/worktree-cleanup-20260923/README.md); other directories
remain outside that approval.

Remaining owner input is bounded: per-corpus hosted transmission and spend only if
those arms are pursued; human-coding resources
before recruitment; missing commercial clips only for those media tracks. Film claim licensing
was decided on 21 September 2026 (alternative A, see the
[F0 decision record](evidence/film-f0-claim-status-20260921/README.md)). None requires guessing
permission now. Hash policy and measurement
architecture follow the operative decisions; the 22 September scope decisions move
film compilation and organization analysis to 1.1 while preserving the declared 1.0
mapping and ingestion deliverables.

After each gate, demonstrate one actual user journey and name the next failing witness. Do not
start contextual measurement or joint inference until reference recovery has identified a
specific scientific limitation they would address. That is the guard against boiling the ocean.
