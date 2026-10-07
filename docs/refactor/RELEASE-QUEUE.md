# Current release queue

7 October 2026 reconciliation. Read this first, then the selected Mote's current body and
acceptance. [PLAN](PLAN.md#operative-10-scope-owner-decisions-22-september-2026) retains the
owner's release scope; Mote owns status, claims and dependencies. The dated September backlog
and October 6 stocktake are history, rather than the next-worker queue.

The audit reviewed all 113 previously unfinished tickets against their canonical bodies,
decisions, dependencies and the landed component evidence. Each now has one release group,
a milestone and a specific next action. One new CI reliability ticket records the current-main
failure. The [crosswalk and verification](evidence/release-queue-20261007/README.md) bind the
before/after state and exact changes. A release tag is scheduling, not a completion verdict.

Later 7 October checkpoint: the Native reliability P0 is fixed and qualified at source
`09f08b43e8be88c0efdf12a6efbfab13d1801b18`. [Repair receipts](evidence/native-voyage-reliability-20261007/README.md)
bind the unchanged assertions/deadline, compiled mutation and green hosted matrix. The live
unfinished queue is now **112 tickets: 26 for 1.0, 24 for 1.1, 62 later**. Resume G1 below.

## Start here

Use one primary agent and at most one helper. Select one bounded acceptance criterion, inspect
its existing implementation and receipts, then claim narrow paths. Containers marked
`queue-container` are coordination targets; choose their child work instead of claiming an epic.
`--ready` means the recorded blocking dependencies are closed, rather than that a release,
resource approval or scientific claim is ready.

```sh
mote ls --tag release-1.0 --ready
mote show bd-01M2TACM78289S4TECE91GT5K2
mote ls --tag milestone-inputs --ready
mote ls --tag release-1.1
mote ls --tag release-later
# Replace the actor and paths with this session's actual narrow scope:
mote --actor YOUR-SESSION begin EXISTING-ID --paths PATH --note 'bounded acceptance and evidence'
```

The immediate order is:

| Order | Mote | Next acceptance |
| --- | --- | --- |
| 1 | `bd-01M2TACM78289S4TECE91GT5K2` | Finish registered controlled embedding/replay and the shared immutable local-evidence/StageTrace seam. |
| 2 | `bd-01M2WVF86T8QEEA1TK8Z0ASJHW`, `bd-01M2TAD04SR823TQVG9VPNH6R3` | Extract public reference before HSMM and named reconstruction from identical checked evidence. |
| 3 | `bd-01M2WVH1DC8ZPDC992G4MX3TXG`, `bd-01M2TADC4VKSDZ2S9SXETH2MYM` | Complete support adoption and the ordinary-file public facade, with explicit profiles, complete outcomes and safe publication. |

The stale docs repair `bd-01M31TQTBMBGEAS9A666B5MPA6` is a separate small implementation slice;
the helper can provide bounded review/lookup while the primary finishes its current ticket.
It returned from `review` to `open`: its preserved branch has useful prose repairs, but current
main still has stale claims.

## What is already done

The reconciliation baseline is source `8d4881a931fb809b27ac7ad14478fe60cde1e6e3` and StoryAtlas
`90f682d1acddd759867098e90f6e3ef163244b0e`. Both were synchronized with GitHub and clean approved
`buc-gw01` main checkouts. Newer tracker/documentation commits do not extend the code claim.

| Completed component | Evidence and boundary |
| --- | --- |
| Recovery, portable numerical baseline, GitHub/workstation and private-data handoff | [Checkpoint](evidence/workstation-checkpoint-20261006/README.md). Reference-scope and FrameSet repairs are landed; fresh-checkout and approved-host transfer acceptance are complete. |
| Estimate eligibility migration | `bd-01M16DBEH9PKER423BZ47ZKBMV` is closed. Bare `Ineligible`, missing and observed zero retain distinct semantics through the checked carriers/codecs and qualified consumer. |
| S2a-3 strict content wiring | `bd-01M379N8HK5ZCV9W252X9MQ4VV` is closed. [Exact-code receipts](evidence/strict-content-wiring-20261007/README.md) bind source `a2d795b26e64ca9cf304c861216c7622bb807190`, consumer `0cd43ffdb37e6a2c71577284368cec4cd823c921`, six compiled mutations and hosted qualification. |
| S2a-4 controlled-content courts | `bd-01M379NZZ67GZJMWVBT5XNA0ZD` closes in this reconciliation. Independent audit reconciled all twelve historical compiled mutations, current construction/reflection/law tests and the new unavailable-fallback mutant. See the [closure crosswalk](evidence/release-queue-20261007/s2a4-closure.md). No new runtime gate was needed. |
| Native fixture reliability | `bd-01M4B896MCEE43Q9QMR4HT60R0`: identical checked WOG archive production is forced in fixture setup; the adapter and every assertion retain the default deadline. Source `09f08b43` has 485 local passes, compiled mutant discrimination and a green exact-source hosted matrix. |
| Timing/support, offline source, single-record exchange and synthetic temporal/scanner contracts | Existing `TextSourceCli`, `RecallTimingBuild`, `MappingExchangeBuild/CLI` and temporal queries are reusable. Their parent tickets retain adoption, paired-result and real scanner acceptance. |
| Film-capable substrate/signatures, storyBuild and tabular StoryModel export | Landed components remain available. They do not establish the compiled-film route, organization preview, a full mapping facade or scientific efficacy. |

Source exact-code [CI run 37613462330](https://github.com/bbuchsbaum/storymodel4s/actions/runs/37613462330)
passed 12,722 tests, with zero failures/errors and 12 existing skips across four matrix jobs.
The subsequent baseline-main [run 37620122197](https://github.com/bbuchsbaum/storymodel4s/actions/runs/37620122197)
failed attempt 1 in Native: 2,704 passed, one timeout, zero errors across the 15 executed cells;
the remaining Native cell did not run. The failing fixture cell passed 159 of 160. This
baseline timing failure is retained in the completed Native P0 above.
Attempt 2 is green on the same 8d baseline: Native 16 cells passed 2,822 tests with zero failures/errors,
and all four matrix jobs succeeded. The successful retry is separate from fixture reliability;
the subsequent qualified fixture repair closes that P0. Both baseline attempts remain retained
separately from the [repair qualification](evidence/native-voyage-reliability-20261007/README.md).

## Release 1.0: researcher mapping workflow

The first product milestone is one ordinary UTF-8 story and two untimed recalls through
prepare, both mappings, independent exchange readback and an actual Atlas opening. Broader
1.0 acceptance then adds Sherlock A and A+V, lambda, temporal queries, raw video and the named
OpenNeuro export. Close individual components as their exact acceptance lands; retain broader
parents until their integration witnesses pass.

| Milestone | Motes and remaining work |
| --- | --- |
| Inputs | `bd-01M17010MM68DG7B49DMEKA2AA` admitted header/text loader; `bd-01M168TZ3VRQYSE7TNT5JKJTSJ` derived recall disclosure basis; `bd-01M2WVH1DC8ZPDC992G4MX3TXG` words/timing/support adoption; `bd-01M35PB1H5PDD55YVKR4TQ8M66` offline-source integration; `bd-01M35B05TC6GBED9Z3HZMX618E` ordinary source/cohort preparation. |
| Shared evidence and mappings | G1 above; reference `bd-01M2WVF86T8QEEA1TK8Z0ASJHW`; reconstruction `bd-01M2TAD04SR823TQVG9VPNH6R3`; facade `bd-01M2TADC4VKSDZ2S9SXETH2MYM`; lambda `bd-01M35MHPFFKQHSJKC07VMWC7CW`. |
| Exchange and workspace | `bd-01M2WVHF4B5DAXJYY4W91VK4WV` paired exchange/independent reader; `bd-01M35B103S2KHRSF853GD2FYHW` ordinary-file Atlas-openable study CLI. Producer decode is separate from actual consumer opening. |
| Video | `bd-01M37D2JPGXRTVF37PN417JW1Y` selection-bound caption request/checked extent; `bd-01M37AN2FX81EKWVJJ85PD9520` multipart loader/coverage; `bd-01M37ANBB3VPY81RGMA1Q1CHA2` executed ordinal/PTS correspondence; `bd-01M35JG7DXD7NSEE4WVEKT3CSF` raw-video timed-source command and checked sidecar join. |
| Temporal and imaging | `bd-01M3549Q5W5KQFSY3ZH81FARM0` exposure/concentration/query and consumer integration; `bd-01M354E83Z4Z7DR4KPP6Q45MAJ` actual ds001132 run/origin/volume joins, first qualified on one development participant. |
| Full journey | `bd-01M35P83TCNHBSDY6NSK13Q3K0`: text plus Sherlock annotations-only/annotations+video with both mappings and lambda 0/0.5/1; fixed units/cut/denominators; independent readback; temporal and imaging outputs. AV improvement is not an acceptance requirement. |
| Release quality | `bd-01M19N0W937KVZCK78F6X57X1D` stable-constructor judgment; `bd-01M31TQTBMBGEAS9A666B5MPA6` stale prose; `bd-01M19FPY1EC5QNTW6SBG3QYT3R` demonstrated docs journeys; `bd-01M2TAM66RTEMY3VVHA3N2MYXK` facade hardening; `bd-01M2TAKTB61XNXV4Y6A9J3CB0C` installable distribution, MiMa and deployment; `bd-01M2TAMJ95K9D7H248A8387YW4` final release gate. |

The delivery epic `bd-01M2TA01EHVRF6MQ1N00XTVK1K` groups this work. It is not another implementation
task. The registered pinned-ONNX/replay adapter remains a G1/1.0 requirement and a prerequisite
for the named Sherlock reproduction. A lexical development milestone may precede it only after
recording verified executable sub-prerequisites and an explicit refinement of the coarse G1
edge. This audit does not bypass that edge or replace pinned ONNX with lexical scores.

Some tickets are larger than a day. Before implementing one, split its existing acceptance into
the smallest evidenced child, preserve the full parent/release criteria, and name the consumer
witness. Avoid opening speculative child tickets for later work during this audit.

## Release 1.1: two preserved lanes

All 24 existing `release-1.1` assignments are unchanged.

| Lane | Order and acceptance |
| --- | --- |
| Organization | Container `bd-01M35P2EKWC52PZTMN2R5NMDFR`; compatibility `bd-01M2WVFV2WGF5BVQHX7JTYYWRH` -> counts/bounds `bd-01M2WVGFA0J4D9T9ZBNR3MY2MR` -> frozen synthetic recovery `bd-01M2WVHVVYQBKSBSW573HZYR9Q` -> public preview `bd-01M2WVJ8HKX6EHG2C5CXYEZYJ6`. Preserve original-unit grain, joint evidence and unknown/external outcomes. Synthetic recovery is separate from human recovery. |
| Compiled film | Container `bd-01M35MK5N6F0EWJE2EYT3D8Y9M`; existing F1-F6 -> compiler closure `bd-01M2T32SGVJXR2RT3RTS2DCN9P` -> D1B film proofs `bd-01M1CQNM5DZZNWD8BN4WRKVZRQ`, V1 `bd-01M1CQQDCWN2RTT1Z8XNNB7VMY` and E0 `bd-01M1CQSWJ538PAEME49200YARR`. Eight existing movie-time courts retain synthetic qualification gaps. F0 alternative A is already decided: unsupported caption-derived claims remain Hypothesized. |

The 1.0 facade withholds organization readouts. Its mapping/temporal outputs do not wait for
1.1 compatibility or the film compiler. Film-capable public types retain their existing
stable/experimental classification; compiler addition must preserve text behavior.

## Later work: preserve context without promising a version

`release-later` means outside the approved 1.0/1.1 acceptance, with no new version commitment.

| Cohort | How to resume |
| --- | --- |
| Research and calibration | Use the retained Phase1a/1b/2/3 preregistration and dependency chains. Runtime/prompt pilot -> preregister -> execute/readout -> optional provider/score-contract work -> development climb -> one guarded final opening. NFRD/OSF courts keep their own admission, split and estimand rules. Human agreement is a reference comparison; calibration and superiority need their separate studies. |
| Owner resources and disclosure | Hosted per-corpus permission/spend, human coders, fixed within-scene human packet, remaining corpus/model admission and missing licensed excerpts remain explicit owner work. These do not hold ordinary synthetic development. A resource or permission decision may legitimately record not-run/not-applicable. |
| Experimental interview and signatures | Preserve the membership routing -> conditional metrics -> per-metric exclusion chain; placement evidence precedes return-rule extensions. RecallSignature's ten remaining fields and construction need truthful estimands. Do not freeze experimental APIs merely to ship 1.0. |
| Maintenance | Construction-probe audit, filename/proof-description corrections, nonzero export-loss fixtures, shared TSV writer and measured NodeSummary reachability remain bounded follow-ups. A broad enum census is separate from the required stable-boundary audit. The canonical intake-format/contiguity proposals need a concrete consumer and ADR first. |
| View extensions | Forgotten-bag/Silent River packets and eight producer view extensions retain the September25 approved designs. Qualify the exact producer/Atlas pair; Atlas keeps unavailable states until supplied structure exists. No new 1.0 dependency or UI completion is asserted. |

Four open parents had all related children closed. None closes on that fact: RecallSignature
still has ten fields; construction-probe children partly closed by consolidation; facade
hardening's child closed by supersession; H5's compiler/abstention fix is landed but its
development pilot remains. Their Mote bodies now name those residuals.

## Salvage and operating boundaries

Inspect relevant paths and ancestry before merging preserved work. Useful pointers:

- Docs `codex/docs-site-claims-0923`: salvage prose from `589b6d265d1dacdff2fc04f9735cfa4ee44df490`,
  preserve the newer ModelAStory program/badge, then fix current SaveModel provenance separately.
  The old `8973c520` badge is not a drop-in replacement. Fetch the preserved commits with
  `git fetch origin recovery/docs-site-claims-20261007`. The reviewed archive tip is
  `5a4c82197fbe4082628769e63396d59bd2554afe`: original ancestry and prose are intact, inherited
  Actions workflows are removed, and `RECOVERY.md` explains selective salvage onto current main.
  Do not merge the archive wholesale; the original tip's cleanup workflow deleted CI artifacts.
- Old `solo/mapping-exchange` handoffs are historical: policy selection commit `6a310fe3` is
  already on main. Reuse it; finish paired facade adoption rather than merging it again.
- Temporal/scanner declaration component `b8f22d00` is on main. Actual scanner joins remain open.
- S2a-4 recovery `9339172694484aad98e2a75f961728dbdb0012b5` is on main. Preserve the unrelated
  top-level `align/ContentBehaviorLawsSuite.scala` draft; it is not a new implementation to stage.

Private data and data-derived artifacts are used only on owner-approved machines, including
`buc-gw01`. Other machines may develop public/synthetic code. Tailnet membership is not approval.
Use the [verified handoff](../../data/README.md), ignored storage and checksum/readback receipts;
do not copy credentials or machine actor/session state. A typed local-only disclosure basis is
separate from pseudonymization, consent and permission to send content to hosted APIs.

Gate runtime changes in a standalone clone, using current reference scope and the exact pinned
consumer where affected. Documentation/tracker changes use format/integrity checks and cold
exact-SHA review. Record local landing, hosted evidence, GitHub parity and approved-host sync
separately. Package publication, participant recruitment, hosted model spend and sealed gold
reads are not authorized by this queue reconciliation.
