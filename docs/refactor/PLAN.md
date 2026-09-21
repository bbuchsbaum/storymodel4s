# Delivery and measurement plan

Accepted direction, 19 September 2026, under the owner's request to assess the external review
and reorganize delivery. Implementation is pending unless evidence below says otherwise.
Baseline: `1113f96864a38a2869e49d9c8c5d4e2dc43f5d10`. This is the active delivery index.
The [backlog](BACKLOG.md) gives live Mote IDs, ticket acceptance criteria and scheduling lanes;
the [reconciliation](RECONCILIATION.json) accounts for every previously unfinished ticket.

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

### Current evidence

These are findings at the baseline, not a fresh full build or an empirical reanalysis.

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
| Film library migration remains real work | Approved D1A S0–S4c plan; current `StorySourceView` is text based | Preserve ruling E: compiled film API + D1B proofs remain on the stable 1.0 path. |
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
6. **Scope stays bounded.** Annotated-source preview first; D1A-types, D1A-film and D1B remain
   required for stable 1.0. V1 full film-model wire and E0 corpus-specific film terminal stay 1.x.
   Ruling E remains in force; the unadopted P1 alternatives are parked, not silently accepted.
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
| G2: usable analysis preview | Typed facade, safe publication, reference + reconstruction views, organization readouts, one Python/R reader | Independently authored tiny answers; no gap bridging/word inflation; all outcomes accounted; offline replay; interruption/corruption refusal | A researcher can run source + recall to interpretable tables without bench imports. Explicitly uncalibrated where applicable. |
| G3: behavioral recovery | Frozen synthetic forward/reverse/revisit/ambiguity/unequal-quality court, then independent human annotations | Recovery bias, group-effect attenuation, reversal sensitivity, candidate coverage, specificity and uncertainty; frozen tolerances; failures retained | Architectural compliance permits exploratory use; empirical claims require the corresponding recovery evidence. |
| G4: compiled-film API | Approved D1A S0–S4c, film phase plan/compiler, D1B typed signatures and end-to-end proofs | Existing text pins; exact film support/identity refusals; shared mapping contract; explicit annotation/caption origin | No 1.0 completion by relabeling the annotation preview as film compilation. |
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
Remove stale assignees and doing/review states. No claims, reservations or candidates are created
for this single-developer planning exercise.

Use lane tags and dependency-filtered queries from BACKLOG. The immediate queue is baseline,
scorer, clock integration and S0, with rescue as independent preservation work. Benchmark
campaigns, viewer work, corpus expansion, interview enhancements and fleet-tool defects are
visible separately. No benchmark win or optional hosted provider sits on the preview/film path.

Remaining owner input is bounded: the CI account/runner route (deferred by the owner on
21 September 2026: local exact-SHA gates qualify engineering meanwhile, and G5 stays open);
per-corpus hosted transmission and spend only if those arms are pursued; human-coding resources
before recruitment; missing commercial clips only for those media tracks. Film claim licensing
was decided on 21 September 2026 (alternative A, see the
[F0 decision record](evidence/film-f0-claim-status-20260921/README.md)). None requires guessing
permission now. Hash policy and measurement
architecture are decided here; P1 scope reduction is not adopted.

After each gate, demonstrate one actual user journey and name the next failing witness. Do not
start contextual measurement or joint inference until reference recovery has identified a
specific scientific limitation they would address. That is the guard against boiling the ocean.
