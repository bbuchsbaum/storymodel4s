# M1: one reproducible source-plus-recall investigation

Activated by the owner on 22 September 2026 to formalize the five-step implementation
sequence. This is the next bounded product milestone under [PLAN](../PLAN.md), not a
replacement for the scientific measurement contract or film/release commitments.
Live Mote owns status; this charter owns the goal, scope and completion boundary.

StoryModel goal: `bd-01M34J9FS10B14WQA309K0P6V8`. StoryAtlas execution container: `bd-01M34JJ6QAW89VD68MNJKEWTMB`.
Both are non-blocking containers; implementation dependencies are between executable tickets.

## Goal and completion boundary

A researcher opens generated source, recall and mapping artifacts in the same application,
selects any recall unit, inspects every supplied alternative and exact permitted evidence,
changes projection without losing semantic identity, and saves/reopens/exports the same
investigation. Source and recall retain independent clocks and horizons.

The product court runs on producer-generated WOG and a small structurally different synthetic
text case using the same app.js. At least one case exercises partial/draft authority. Generated
does not mean automatically discovered scenes or automatically parsed recall charts: construction
and serialization must use public checked producer APIs; synthetic status is visible.

M1 is complete only when all eight execution tickets satisfy their criteria and the final
StoryAtlas acceptance receipt is reconciled in both containers. Closed issue counts, a static
mockup, codec round trips alone, or an older browser run cannot substitute for the live journey.

## Formal work and repository ownership

| Step | Store / owner | Mote | Deliverable | Setup disposition |
|---|---|---|---|---|
| 1 | StoryModel | `bd-01M19956MFSG7076QE4J66T7E9` | Finish and land support honesty | Existing; resume solo/support-honesty |
| 2 | StoryModel | `bd-01M2WVENSB0P955Y0B603CC20Y` | Implement G1 mapping records revision 4 | Existing; blocked by support honesty |
| 3 / 4 upstream | StoryModel | `bd-01M34JAX5KKWECCY72KD4ZV0BW` | Checked manifest/join, fixed-cut matrix, evidence/subset queries and generated fixtures | New; blocked by G1 |
| 3 adoption | StoryAtlas | `bd-01M1C2TV1SJECKEQ6SNVSRBTHQ` | Qualify and pin the actual producer package | Existing; blocked at the explicit external entry gate |
| 3 loading | StoryAtlas | `bd-01M34JJ6YR2K714HPAPT8YTES6` | Open joined artifacts without rebuilding app.js | New; blocked by adoption |
| 4 | StoryAtlas | `bd-01M16KDH0TM7DF2BC0K08TH0TT` | Shared selection, matrix and exact evidence | Existing; blocked by loader; chronology edge removed |
| 5 replay/export | StoryAtlas | `bd-01M34JJ77XECH5T7J2K1ZKGFV8` | Save, reopen and export the investigation | New; blocked by interaction |
| 5 acceptance | StoryAtlas | `bd-01M34JJ7A88WEAR1MX61B5Q3J9` | Prove the complete journey on two generated fixtures | New; blocked by replay/export |

StoryModel owns scientific identities, joins, mapping values, fixed-cut projection, exact
support, permissions represented by checked capabilities, and deterministic scientific subset
queries. StoryAtlas owns I/O adapters, loading UX, semantic state, layout/lowering, accessible
interaction, saved-state handling and export orchestration. Intaglio remains generic graphics.
Ownership names a repository responsibility, not an invented active agent or permanent role.

The support-honesty and G1 acceptance criteria are preserved. G1 uses its
[revision 4 implementation plan](../../plans/2026-09-21-g1-mapping-records-plan.md); public
vocabulary and changes to canonical interchange still require the appropriate ADR line.
The packet is a separate downstream ticket so manifest, matrix and browser needs do not expand G1.

## Dependency and handoff rules

```text
StoryModel: support honesty -> G1 records -> checked provider packet + fixtures
                                                    |
                       exact commit, codecs, digests, expected answers, receipts
                                                    v
StoryAtlas: blocked adoption -> loader -> shared interaction -> save/export -> acceptance
```

Stores are independent. Foreign issue IDs are provenance, never fake dependency edges.
Atlas adoption is explicitly `blocked` at setup, so the ready queue does not advertise an
unavailable provider task. When the provider packet has a reachable implementation commit,
fixture generator/digests and gate receipts, the next implementer records those in adoption,
sets adoption open, and executes its own consumer qualification. Upstream closure alone never
closes adoption. Consumer tests then unlock the local chain through ordinary local dependencies.

The final consumer acceptance receipt names both exact commits and artifacts. Close the Atlas
container only after that receipt passes; verify it from the StoryModel goal before closing that
goal. This explicit receipt handoff is the cross-store gate, not a hidden status synchronization.

One implementation slice and one heavy build at a time. No fleet claims, reservations, candidate
protocol or named dormant reviewer is introduced. Support honesty was the next executable task
at setup; the live queries below identify the current executable work. The 22 September
[workflow reconciliation](../../plans/2026-09-22-recall-encoding-workflow.md) preserves active
G1/M1 scope and places temporal-query work downstream.

## Required behaviors and falsifiers

1. **Checked opening.** Load both examples without rebuilding app.js. Bind source/model edition,
   recall inventory/segmentation, mapping, derivation, optional features, permissions and receipts
   with exact identities/digests. Refuse wrong-source/recall pairs even if titles/local IDs match,
   changed sidecars, unsupported schemas and missing required roles before drawing. Keep lawful
   missing optional features and imported/draft gaps inspectable; never default to fixture authority.
2. **Complete inspection.** Use one explicit fixed target cut. Preserve all requested recall
   outcomes, every supplied alternative, external values, decision origin and measure semantics.
   Processing failures/unresolved status are not probability destinations. Unknown omitted-candidate
   probability is not known hidden mass. No source-only renormalization or manufactured child values.
3. **Navigation and evidence.** Ordinal keyboard traversal reaches untimed, onset-only, failed and
   all-external units. Source, matrix and Voyage share qualified semantic identity and explicit
   OnMark/ViaAncestor/OffProjection placement. Exact/discontiguous source and recall evidence and
   repeated inverse references remain accessible. Restore timed-only filtering or ordinal-keyed
   identity in a mutant and require the corresponding test to fail.
4. **Content authority.** A checksum identifies bytes; it grants no right to reveal them. Denied
   strings must be absent from payloads, DOM/accessibility, tooltips, diagnostics and exported files.
   Missing permission cannot be repaired by hiding text visually. Synthetic tests cover denial;
   no private corpus content is required.
5. **Declared policies.** Presentation controls change layout/projection. Scientific-policy controls
   select separately supplied checked mapping artifacts, preserve their labels and refuse incompatible
   joins. No estimator silently runs; historical/reconstruction output never becomes reference output.
   A second synthetic/precomputed mapping is sufficient for the M1 switching court.
6. **Replay and export.** Save digests, schema, qualified focus/selection, policy, fixed cut, independent
   horizons/cursors and viewport. Exact artifacts restore semantic state; changed artifacts refuse.
   Export view, accessible twin, provider-generated permitted subset and receipt. An independent
   consumer recovers the expected IDs, values/support and policy. Mutated digests, cropped-value
   renormalization or a wrong-policy export must fail. Pixel equality requires separately pinned layout.

The final acceptance ticket also requires keyboard/visible focus, non-color semantics, 200-percent
zoom, exact text and no stale-response activation. Existing source/static/Voyage regressions remain
in force. Browser receipts identify the project browser and owned-process cleanup.

## Gates and evidence

Use `tools/reference-scope.sh` for each StoryModel diff; run the applicable focused JVM/JS/Native
tests, mutation/control witnesses and separate SD6 review. Land under `sbt -batch checkAll` on the
clean merge result with exact SHA, command receipts, test totals, skips and exit status.

StoryAtlas uses exact reachable producer/Intaglio/grakern pins and records actual overrides:

```sh
sbt -batch <documented-exact-checkout-overrides> compileAll testAll scalafmtCheckAll app/fastLinkJS
sbt -batch <documented-exact-checkout-overrides> "cli/run edition --out target/edition" app/editionBundle
node app/smoke/smoke.cjs target/edition/index.html
```

The bracketed arguments above are placeholders for build.sbt's existing checked-out dependency
overrides, not literal executable flags. Add a dedicated M1 workflow court during implementation;
its command must be recorded in the acceptance receipt rather than invented in this planning step.
Keep warnings/skips visible and satisfy repository warning policy. Runtime/API changes receive
affected gates; document-only formalization does not claim to have rerun these courts.

Setup rechecked the 245-test consumer archive at Atlas 77297769/provider 774fb1e8; it contains
compiler warnings. The 260-check S4b browser receipt belongs to an earlier provider pair. Neither
closes this new milestone. At setup, support honesty was unmerged at caf2d5ea and G1 was a plan;
these are historical setup observations, not a second live status ledger.

## Scope and the existing roadmaps

M1 excludes automatic scene/episode proposals, automatic recall charts, calibrated probabilities,
strict reference inference/organization readouts, structural-channel efficacy comparisons, new
corpus admission, film playback, interviews, virtualization, cohort views and general chronology.
Those commitments remain in the broader plans. M1 inspection is useful with explicit missingness
and existing reconstruction artifacts; it does not certify measurement validity or superiority.

The uncommitted StoryAtlas refactor package is source material: adopt bounded portions of
AT-03/04/05, AT-06/07/08/10/11/13 and AT-23/24, with AT-02/29 failure courts at closure.
Do not import all 34 items or their old chronology/interview ordering as M1 prerequisites.
The old split-screen chronology dependency is removed. The separate substantial second-narrative
ticket follows provider adoption rather than interviews; M1's small second fixture does not close it.
Static no-JavaScript preview certification retains its own ticket and contract.

Use redistributable WOG and synthetic data, existing local/offline paths, and no private recall prose,
sealed-test opening, hosted spend or remote inference. Local qualification is not hosted CI,
publication or release. This formalization does not authorize a push or deploy.

## Live queries

From either repository, inspect its local implementation queue with:

```sh
mote ls --tag workspace-m1 --tag execution
mote ls --tag workspace-m1 --tag execution --ready
```

Use `mote show bd-01M34J9FS10B14WQA309K0P6V8` in StoryModel or `mote show bd-01M34JJ6QAW89VD68MNJKEWTMB` in
StoryAtlas for the corresponding container. No additional tracker or copied status ledger is used.
The [setup receipt](source-recall-workspace-m1-20260922.json) is an immutable setup snapshot; live Mote supersedes its statuses.
