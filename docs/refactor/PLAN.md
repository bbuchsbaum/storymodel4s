# Delivery and measurement plan

Accepted direction, 19 September 2026, under the owner's request to assess the external review
and reorganize delivery. Implementation is pending unless evidence below says otherwise.
Baseline: `1113f96864a38a2869e49d9c8c5d4e2dc43f5d10`. This is the active delivery index.
The [backlog](BACKLOG.md) gives live Mote IDs, ticket acceptance criteria and scheduling lanes;
the [reconciliation](RECONCILIATION.json) accounts for every previously unfinished ticket.

## Active product milestone: M1 (22 September 2026)

The owner has activated [one reproducible source-plus-recall investigation](goals/source-recall-workspace-m1-20260922.md),
Mote `bd-01M34J9FS10B14WQA309K0P6V8`. Its execution sequence is support honesty -> G1 records -> checked
producer packet -> StoryAtlas adoption/loading -> shared selection/matrix/evidence -> saved-state
replay/export and exact-pair acceptance. This bounded integration of the existing viewer supersedes
the earlier deferral of viewer work for this milestone only. The reference-measurement, film and
release commitments below remain in force on their own lanes and do not all gate M1.
Live Mote and the goal's explicit dependency/receipt handoffs supersede older next-task prose.

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
- **Division of labor** is agreed on the shared Fray board (storyatlas4s home, thread #11). Mote
  claims remain authoritative. There is one writer per bead and module, and heavy sbt gates are
  serialized through the Fray "sbt-gate lock" card.

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

The current integration candidate is `7469d3d7f49dd04df2d2bc76504fbef1202a703b`.
Its final strict pinned-dependency `checkAll` is running; it is not yet landed.
The preceding full gate at `03569d1a` failed 13 obsolete WOG expectations.
The independently reviewed repair preserves explicit missing-proposal refusal,
retains the historical S0 golden with an explained three-hash delta, and passes
the complete affected JVM pipeline court (120/120) before this broad gate.
The [integration evidence](evidence/p1-integration-20260923/README.md) retains both
failed full gates, repairs, independent exchange readers and exact dependency
bindings. Local execution is distinct from the still-required hosted CI receipt.

The owner decisions and earlier landings close the CI-runner choice, exact-directory
disk cleanup, stability-boundary decision, strict-warning repair and Sherlock
re-scoring tickets. Existing GitHub-hosted runners remain the chosen route; the
stable boundary in [api-stability.md](../api-stability.md) is approved, not frozen.
The current reviewed batch adds the real-text compiler repair, declared-lossy
StoryModel export, deterministic offline text source, strict tie policy and
part-correct frame planning. The latter three are bounded slices of open tickets,
not completion of their entire workflows.

Fray threads 68 and 70 record the accepted next handoff. Mote claims and exact
path reservations govern implementation; this table does not replace them.

| Existing work | Accepted next owner | Next executable witness |
| --- | --- | --- |
| G1 shared evidence `bd-01M2TACM78289S4TECE91GT5K2` | claude-release | Content-only scorer payloads cannot expose nested coordinates or original IDs; registered lexical and embedding adapters replay the same bound evidence. Preserve missingness, historical parity and rename/permutation laws. |
| Offline source `bd-01M35PB1H5PDD55YVKR4TQ8M66`, facade and prepare | codex-temporal | An ordinary text file and an annotation source reach the same checked public source/evidence seam, with an independently readable exchange; bench-only construction does not satisfy this witness. |
| Video source `bd-01M35JG7DXD7NSEE4WVEKT3CSF` | claude-p1 | Caption loading replays model/frame/receipt checks and joins per-part extents; foreign editions, missing receipts and unsampled-frame captions refuse. |
| Stable-signature closure, Admissibility and Estimate | claude-sm-0923 | Outside-package forgery probes and an explicit eligibility/denominator/codec migration, rechecked against landed main. Estimate eligibility repair remains a 1.0 prerequisite. |
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
implemented. Follow `tools/reference-scope.sh`; final landing uses `sbt checkAll` under SD2.
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
