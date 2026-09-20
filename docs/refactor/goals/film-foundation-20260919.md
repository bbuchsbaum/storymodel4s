# Goal: film-capable library foundation with frozen text parity

Activated 19 September 2026 at the owner's request for roughly ten existing Motes dispatched
to an implementation sub-agent. Starting revision: `d9eb9543`. Governing delivery plan:
[PLAN](../PLAN.md); design approved at setup: [D1A revision 4](../../plans/2026-09-17-d1a-source-to-story-plan.md).
The operative plan is revision 5, incorporating the completed pre-S2 cold review and
bounded staging clarifications; the A/C/D/E rulings are unchanged.
The live goal and Mote own execution status; this document defines the completion boundary.

## Result

The library's model, evidence, atlas and alignment APIs can carry checked film support and
exact playback intervals. Existing text identities, output bytes and ordering retain the
[S0 pins](../evidence/d1a-s0-text-parity-20260919/README.md). Sherlock coordinates retain their
[receipted baseline](../evidence/sherlock-clock-repair-20260919/README.md). A reviewed phase
plan and executable child tickets describe the subsequent film compiler work.

This is nine existing Motes: eight executable deliverables and one parent closure. The
parent closure is an evidence reconciliation, not a ninth implementation. No duplicate
implementation tickets are created to reach a numerical target.

## Ownership and sequence

Implementation worker: **`/root/film_foundation`**, Mote actor
**`codex-storymodel4s-film-foundation`**. Root remains responsible for the goal boundary,
acceptance reconciliation and user decisions. The worker has accepted the bounded scope.
One implementation writer and one heavy build at a time. Temporary fresh-context cold
reviewers satisfy AGENTS.md SD6; this delegation does not restart fleet governance.
No claims, reservations, presence leases or candidate protocol.

| Order | Mote | Deliverable and closure witness |
|---|---|---|
| 1 | `bd-01M2TABFW72D8WWQF481SD37KW` — S1 | Amend ADR 0007 with A/C/D/E, the approved vocabulary, identity/canonical rules, rejected alternatives, and the composed Sherlock bundle. Separate cold review of the committed text. |
| 2 | `bd-01M2TABVXSM72M9DD0SNB6GZ11` — S2 | Cold-review revision 4 before code. Checked sealed atlas, bound proposal surface, typed support, canonical intervals and additive evidence-support codec. Named refusal laws and compiled mutations; external construction probes. |
| 3 | `bd-01M2TAF8JH42NYJJAFWPD7HTZB` — S3 | Acquisition carries typed support; evidence access and acceptance rules support it. Existing text resolver verdicts and wire-visible gap names stay unchanged. |
| 4 | `bd-01M2TAFN5WMXW4NT20YJQAW2FR` — S4a | Nodes carry typed support; draft construction becomes fallible; ordering uses projections. Rewrite probes made vacuous by the changed return type. |
| 5 | `bd-01M2TAG1GEAGXCY33P0B17QBJ1` — S4b | Sealed atlas envelope, non-text identity, checked text witness, bound evidence and migrated consumers. Axis/surface identity mutations and construction refusals distinguish the formerly conflated cases. |
| 6 | `bd-01M2TAGDJ5F0FEPC3XPWVDSWDH` — S4c | Seal/split AlignmentSource; migrate its consumers mechanically. Preserve the text reconstruction behavior and S0 pins. |
| 7 | `bd-01M2TAC80YRK5JFNKTT7B7CAQN` — D1B signatures | Typed SourceView/HsmmResult with exact intervals; replace joined-text surrogates; preserve the declared Sherlock per-unit anchor baseline. Refuse foreign bundle/axis/intervals and unsupported wire forms. Record the public API in the stability inventory. |
| 8 | `bd-01M2TAGST4EBFR1HM9Q3EWSYAH` — film phase plan | Answer the ten carried design questions, cold-review the plan, file child tickets with acceptance/dependency edges, and put the disclosed film-status choice to the owner. |
| 9 | `bd-01M1CQKRG1A4J4BEWCC78F4TEZ` — D1A-types parent | Reconcile every types acceptance criterion to landed child evidence and close the parent. Do not count an annotation preview or a plan as film compilation. |

S3 and S4a could run in either order under the approved design; this goal chooses the
sequential order above. S4b requires both. D1B signatures and the film plan follow S4c.
All external prerequisites inspected at setup are closed: C1 portable contracts, S0 and
ClockRepair. The existing dependency graph is preserved. The parent may close once its
types criteria are met; final goal reconciliation still covers all nine Motes.

## Execution and acceptance

The detailed acceptance criteria on each live Mote and in the approved phase plan remain
binding. This charter groups work; it does not replace those criteria with a weaker summary.

- Commit compiling work promptly on named local branches. Finish and land each green slice
  before beginning another implementation. Preserve unrelated changes. Do not push remotely.
- Each code slice runs `sbt -batch clean compileAll testAll` in a clean standalone clone or
  exact commit export, then `sbt -batch scalafmtCheckAll scalafmtSbtCheck` separately. Bind
  command, exact revision, clean state, exit status and every test-task total. Recount the
  alias if the module inventory changes; it contains 56 tasks at setup.
- Preserve the frozen S0 JSON values. Adapt test call sites to the new public API when
  necessary, including wiring the reference node-order lists to the actual projection API.
  Do not regenerate expectations to bless a regression.
- Demonstrate each new guard with a named compiled mutant and a passing control. A
  compile-time construction-probe mutation needs its own clean recompile. Compilation
  failure alone is not a successful mutation witness.
- Cold-review each slice separately; additionally complete the outstanding revision-4
  design review before S2. Resolve findings with code/evidence or a priced, explicit blocker.
- Run `cd docs-site && npm run verify:examples` when the ticket requires it or public API /
  examples change. For changes consumed by storyatlas4s (core/view/features or dependency
  edges), compile and run that sibling's test suite against the candidate before landing,
  recording both revisions. Use an isolated consumer checkout and preserve the user's tree.
- Keep the committed repair record authoritative and receipts attached. Inventory the
  existing D1B all17 anchor fixture before that migration; reuse its frozen artifact or
  reconstruct a declared pre-change baseline. Do not substitute an NN03-only result for
  an all-participant acceptance criterion. No new gold opening or benchmark tuning.
- A completed Mote cites the artifact, witnesses, gate, review and local commit. Leave
  unmet work open. Final reconciliation checks all nine, plus the clean repository state
  and next failing witness; setup and dispatch do not complete this goal.

## Known wording and policy boundaries

The current plan's approved A/C/D/E rulings supersede the parent's historical “E open”
sentence. S1 records those existing decisions; it does not reopen them.

D1B's historical `hsmm/v3` / “until v4” prose predates the current checked wire contract.
Use its operative 19 September amendment. Determine the actual current version and
capabilities; do not downgrade the wire or imply unsupported interval serialization works.
Record any necessary vocabulary/version decision in the governing ADR before implementation.

The named WOG HSMM “golden” currently has an unused checksum constant; its running suite
checks encoding and contextual round trips. Do not claim that as a byte pin. At the S4c
review, make the intended preservation witness executable against the pre-migration
behavior, using platform-labelled before/after evidence where needed. Preserve the
independent Native numerical-policy issue `bd-01M1D215EY4T5BR0VRJ694AMBQ`; do not silently
claim one cross-platform HSMM byte identity or choose a new tolerance. The existing
`codec/StoryModelCodecSuite` does provide a literal cross-platform model-encoding checksum.

Generated-caption licensing of `SurfaceExplicit` remains the owner's decision. The film
phase-plan ticket requires presenting that decision, not implementing it. Prepare concrete
alternatives, provenance disclosures and the dependent ticket boundary before asking.
An unanswered decision must remain visible and block affected future implementation;
it is not approval. If it also prevents an acceptance criterion of the plan itself from
being met, leave that Mote and the goal unfinished rather than relaxing the criterion.

Film compiler implementation, D1B's end-to-end film proofs, V1/E0, reference-measurement
inference, human recovery/calibration studies, new providers/corpora and stable 1.0 release
are outside this batch. Existing research, maintenance and preservation work keeps its
place in the broader delivery plan. Local qualification does not establish executed CI,
scientific validity or remote publication.
