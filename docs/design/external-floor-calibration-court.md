# Recall-alignment external-floor calibration court

**Bead:** `bd-01M19947QTCAYJJYTC003HVDB5`
**Status:** pre-data specification; no calibrated floor exists yet
**Scope:** selection of `DefaultLocalCostModel.externalFloor` for one completely identified
alignment configuration
**Execution dependency:** support-honesty bead `bd-01M19956MFSG7076QE4J66T7E9`

This court asks one narrow question:

> Under a provider/model selected from development evidence by its own frozen protocol, and under a
> frozen candidate generator, cost model, transition model, source edition, and adjudication
> protocol, which external-floor values best separate source-anchored recall from genuinely
> external recall along complete inferred paths?

The answer is a decision-cost selection, not a probability calibration. A run may instead answer
that the floor is unestablished. That is the required result when the data cannot expose both
failure directions, the winning range is not bracketed, or independent story deletions do
not support the same choice.

This document freezes the estimand and refusal rules before a calibration corpus is admitted. It
does not change a public API, select a numeric default, admit a corpus, or report a result.

The governing repository sources are [ADR 0001](../adr/0001-embedding-contract-and-hard-gate.md),
the [frozen-fixture adjudication protocol](../plans/2026-08-28-m1-fixture-adjudication-protocol.md),
the current [`DefaultLocalCostModel`](../../align/src/main/scala/storymodel4s/align/cost.scala),
[`GraphHsmm`](../../align/src/main/scala/storymodel4s/align/hsmm.scala), and the benchmark
[`GoldUnit`](../../embed-bench/src/main/scala/storymodel4s/bench/gold.scala) and
[`Metrics`](../../embed-bench/src/main/scala/storymodel4s/bench/metrics.scala) contracts. Board
decisions routed to the bead add the full-identity, support-honesty, and WOG constraints recorded
below.

## 1. What the floor does

`externalFloor` is the local emission cost of the external state made natural by a recall unit's
discourse function. It also prices `ExternalState.Unranked`; an abstained unit has that single state,
so its emission adds the same constant to every complete path rather than making a source/external
choice. Every remaining external state costs `externalFloor + externalMismatch`. Source states
receive evidence-backed local costs. `GraphHsmm` combines those emissions with transitions and
relation-preservation passes, then returns:

- `P`, the posterior mass over alignment states for each recall unit;
- `F`, the posterior transition flow between adjacent recall units; and
- a Viterbi maximum-a-posteriori path.

The floor therefore changes a globally coupled path decision. It is not a threshold applied to a
single semantic score. Every trial value must rerun candidate-to-result inference for the complete
recall graph with all non-floor inputs held fixed.

A floor that is too low makes external states too cheap. Units with adjudicated `Source` floor
targets are then assigned to external states. This court calls that direction **false external**.

A floor that is too high makes external states too expensive. Units whose separately adjudicated
floor target establishes external origin are then forced onto source anchors. Association,
inference, intrusion, or uninterpretable labels alone do not establish that binary target. This
court calls the error on an established external target **false source**.

Both directions must be observable. A corpus containing only source signal can make an arbitrarily
high floor look good. A corpus containing only external signal can make an arbitrarily low floor
look good. Neither corpus identifies the trade-off.

## 2. Four quantities that must stay separate

| Quantity | Question | This court's use |
|---|---|---|
| Candidate recall | Did the frozen generator nominate at least one acceptable source anchor? | Upstream support condition, reported before floor scoring |
| Path-decision performance | Did the globally inferred Viterbi state choose the adjudicated source/external side? | Primary floor-selection outcome |
| Model-relative posterior mass | How did this identified model distribute `P` and `F` over its admitted states? | Diagnostic output; never called an empirical probability |
| Empirical probability calibration | Do stated probabilities match observed frequencies on held-out adjudicated data? | Separate named calibration model with Brier/ECE/NLL; not performed here |

`BenchReport.Calibrated` establishes that a frozen origin and protocol were admitted. It does not
by itself establish empirical probability calibration. Likewise, fitting `externalFloor` does not
turn posterior mass into a calibrated probability. Retrieval calibration and open-world rejection
calibration remain separate models under ADR 0001.

## 3. Frozen identity of one calibration problem

One result belongs to exactly one identity record. The provider/model must already have been selected
using development data alone under a separately frozen, content-addressed selection protocol. That
protocol, its development split, all tried alternatives, and the selected identity become inputs to
this court; calibration labels may not choose or rank providers, models, geometries, candidate
policies, or any other non-floor component.

The court identity record must be serialized canonically and content-addressed before calibration
labels are opened. The same pre-calibration freeze includes the maximum acceptable `E_low`,
`E_high`, `R_bal`, candidate-miss and support-failure rates for promotion, plus every required margin
over the named controls. Neither calibration nor test outcomes may define those limits.

| Boundary | Required identity |
|---|---|
| Corpus and gold | corpus/set ID; adjudication-protocol version and checksum; complete unit roster; exclusions; source-lineage partition; story-family strata; participant identities; separately adjudicated binary floor targets and their bases; related-source evidence targets; acceptable alignment targets; oracle discourse labels, if collected; gold checksum |
| Source | admitted source-edition ID; media and annotation checksums; raw/canonical text checksums where applicable; presentation-axis/adapter identity; source-atlas checksum; `SourceView` fingerprint; hierarchy and relation-layer checksums |
| Model-input recall | transcript-edition checksum; recall segmentation/checksum; discourse-function values actually supplied to inference; unattended or cross-fitted derivation identity and receipt; proposition-evidence checksum |
| Development selection | separate frozen provider/model-selection protocol ID and checksum; development roster and split; complete tried-alternative register; selected-provider receipt |
| Dense provider | full `ProviderFingerprint`; model, tokenizer, implementation, and runtime identities; per-item outcome and attempt receipts |
| Geometry | query `GeometryId`; document `GeometryId`; validated `GeometryPairRule`; both semantic views and instructions; dimension, normalization, truncation, pooling, and coverage policy |
| Candidate generation | `perLevel`; lexical-overlap rule; channel identities; fusion rule; nomination receipts; refusal/abstention rule |
| Local cost | all `CostWeights`; function prior; `externalMismatch`; `missingSemantic`; `distortionPenalty`; structural provider/reducer; support-weight refusal policy |
| Transitions | complete `HsmmConfig`, including temperature, transition parameters, refinement passes, and refinement weight |
| Search and scoring | ordered floor grid; cell definitions; weighting; selection/refusal rule; predeclared promotion limits and control margins; software commit; canonical codec/schema versions |

The model-input recall and gold are different artifacts even when they contain fields with the same
Scala type. `FunctionPrior` and `ExternalStates.natural` consume the discourse function on the
`RecallUnit`, so an oracle discourse label is part of the answer if it replaces that model input.
The model-input value must come from a frozen unattended procedure fitted on development data only,
or from a cross-fitted prediction whose training fold excludes the unit and its source-lineage and
participant groups. The gold may retain an oracle discourse label for adjudication analysis, but it
must never overwrite, select, or repair the value supplied to inference. Both values and their
distinct derivation receipts are published so equality is an observed result rather than an assumed
identity.

The deduplicated anchor set is not enough candidate identity. The execution bundle must also bind
the complete lawful nominations: channel, channel-local rank, raw score and its declared direction,
feature space and provider/geometry where applicable, hierarchy stratum, and execution or
derivation receipt. A checked nomination fingerprint must agree with the exact recall, source view,
and admitted candidate anchors. Until the current `HsmmResult` nomination-provenance residual is
closed or an equally checked companion packet supplies this proof, the court cannot establish a
reusable floor from that run.

Provider identity is not a display label. Distinct fingerprints or geometries define distinct
calibration problems even if they happen to emit identical vectors on this corpus. In particular,
two providers sharing a short rendered prefix remain different identities.

Changing any row invalidates reuse of the selected floor. A floor selected for hashed n-grams may
not be presented as selected for TF-IDF or a dense provider. A floor selected for one dense geometry
may not be reused after changing its query/document instructions, pooling, truncation, or missingness
policy.

The exact finite floor grid must be included in this identity record before calibration outcomes
are inspected. This document does not invent its numerical range before the provider and cost scale
exist. A later manifest may freeze the grid, but it may not expand, refine, or recenter that grid in
response to calibration loss. If the identified set touches either grid endpoint, the result is
unestablished because the optimum was not bracketed.

## 4. Population, split unit, and gold cells

The partition split unit is the **source-lineage group**, not a recall unit. A source-lineage group
is one source story together with every edition, translation, excerpt, derivative annotation, and
recall of that story. The complete group stays in one partition. No unit, response, edition, or
near-duplicate source may be scattered across development, calibration, and untouched test merely
to increase the apparent sample size.

Story family is a stratification variable, not the split unit. Following the decided fixture
protocol, every declared family contributes independent stories to every partition. Provider/model
selection is completed on development data only, under its separate frozen protocol, before this
court may inspect calibration labels. Floor fitting within the calibration partition uses
leave-source-lineage-group-out evidence.

No recall participant appears in more than one partition. When participant identities recur across
stories, the same frozen participant partition is reused and reported. The participant is the outer
sampling coordinate in the point estimand, and uncertainty summaries cluster by participant as well
as source story. A claim about new participants requires an untouched participant panel; a story
holdout alone does not establish it.

Development data may be used to implement the court, run the separate provider/model-selection
protocol, and freeze every input and promotion rule. Calibration stories choose only
`externalFloor`. Untouched-test stories are unsealed once, after floor selection, to estimate
performance against the already frozen confirmation limits. They choose nothing. A calibration or
untouched-test set used to alter the provider/model, grid, cells, loss, candidates, transitions,
promotion limits, or control margins becomes development data and must be replaced.

### 4.1 Adjudicated cells

Gold keeps the current groundedness classes distinct:

- source;
- association;
- inference;
- intrusion; and
- uninterpretable.

Those five labels describe where an adjudicator believes the content came from; they do not, by
themselves, define the binary floor decision. The current benchmark makes that distinction
observable: a `Groundedness.Inference` unit may also carry nonempty targets and a primary target, and
the existing source-anchor metrics treat that primary as acceptable. Therefore every admitted unit
also receives exactly one separately adjudicated floor target:

- **Source:** one or more acceptable alignment targets and a primary target;
- **External:** an observation establishing external origin, its evidence basis, and a typed
  external subtype where the protocol establishes one; or
- **Indeterminate:** the adjudication cannot establish which side of the binary floor decision is
  correct.

The current `GoldUnit` does not encode that distinction independently: `primary`, `groundedness`,
`externalSubtype`, and oracle `discourse` can currently coexist in combinations with different
meanings. Until a versioned gold schema or checked companion artifact carries the floor target,
origin basis, and the two target sets explicitly, the current benchmark record alone cannot
establish binary floor gold.

Related-source evidence targets and acceptable alignment targets are separate fields. An inference
may cite source material that motivated it without making that material an acceptable alignment
destination; conversely, an inference with an admitted primary target is source-targeted for this
binary court unless a new adjudication explicitly says otherwise. The implementation may not derive
the floor target from `groundedness != Source`, from the presence of an external subtype, or from a
discourse function.

`Groundedness.Uninterpretable` records a limit of interpretation, not evidence that the participant
went beyond the source. It becomes `Indeterminate` unless a separate admitted observation establishes
external origin. Indeterminate units remain in the complete roster and their count and rate are
reported by participant, story, and groundedness, but they do not enter false-external or
false-source loss.

Every source floor target must name at least one acceptable alignment target and a primary target.
Every external floor target must name its external-origin basis and groundedness and, where the
protocol establishes it, its typed external subtype. Source alternatives, hierarchy levels, blends,
and distortion facets remain in the gold rather than being flattened to one anchor.

For balanced weighting, each `Source` floor target enters exactly one source cell by the following
first-match rule:

1. **multi-target:** `blend` is true or more than one acceptable target is named;
2. **summary:** the primary target is above level 0 or the gold discourse label is `Summary`;
3. **distorted:** at least one adjudicated fidelity facet is present;
4. **faithful:** every remaining source-anchored unit.

The first-match rule exists only to give each unit one primary loss weight. The report also publishes
the overlapping multi-target, summary, and distortion trait slices, so a distorted blend cannot
disappear from the distortion audit merely because multi-target was its primary cell.

An `External` floor target enters one of the four external cells -- association, inference,
intrusion, or uninterpretable -- only when the separate external-origin basis supports that cell.
Groundedness alone never assigns it. External subtype accuracy is a secondary outcome; it does not
replace the source-versus-external decision.

A generally named library default requires all eight declared cells. A result from fewer cells must
name the narrower population in its identity and may not be promoted as the general default. At
minimum, a selectable binary floor requires `Source` targets and `External` targets with admitted
origin bases. Source plus intrusion but no externally established association, inference, or
uninterpretable material identifies only that narrower contrast. Indeterminate units cannot supply
a missing direction or cell.

### 4.2 Established universe and exclusions

The denominator is the complete frozen unit roster for the admitted calibration partition. Legal,
consent, language, source-edition, and adjudication exclusions are applied before model execution and
listed by typed reason. No unit may be excluded because its prediction is difficult.

If the roster is incomplete, the universe is unestablished. Counts may be reported, but coverage
rates and calibrated-floor claims may not be computed from an unknown denominator.

## 5. Support failures are not participant outcomes

This specification may land before the support-honesty repair, but no execution may pass the court
until that repair lands. An empty eligible content-term denominator cannot publish numeric support
of 1.0. External-state content support is not applicable or a separately typed estimand; it is not
the same quantity as measured source-cell support. A run that still conflates those meanings cannot
freeze a lawful support policy and therefore returns unestablished.

`ExternalState.Unranked` means the aligner could not rank the unit: semantic providers abstained and
no other channel supplied ranking evidence. It is an aligner support failure. It never means that a
participant intruded, associated, inferred, or produced uninterpretable content.

That classification requires an independently executed candidate-generation run from the admitted
inputs. `CandidateSet.without` deliberately removes an already observed channel for a counterfactual
ablation and may mark the remainder abstained when that removal empties the nominations. This
induced emptiness is not provider abstention. When it is used for ungated inference, it stays inside
the ablation protocol: `GraphHsmm.ablationUngated` returns a typed `AblationResult`, never an
`HsmmResult`. It never enters `Unranked` counts, support-failure rates, floor selection, control
comparison, or promotion evidence.

Current source does not enforce that separation for every call path. `Candidates.without` returns
ordinary `Candidates`, and ordinary `GraphHsmm.infer` accepts that value and can return an
`HsmmResult`; the result retains only derived candidate anchors, not the operation that removed a
channel. A `without`-to-`infer` run is therefore nonconforming and makes execution unestablished.
The court must not claim to recover ablation provenance after that information has been discarded.

Before this court may execute, candidate construction must derive a checked run-kind boundary from
the operation itself. Applying channel removal produces counterfactual-ablation candidates or an
equally checked receipt bound to the exact input candidates, removed channel, and output candidates;
ordinary inference and independent-control evidence accept only independently generated candidates.
The boundary is not a caller label: construction, contextual decoding, and output admission rederive
it, and omission or falsification is refused. `AblationResult` remains the required output of
`ablationUngated`, but that output distinction alone does not close the ordinary-`infer` bypass.

Three pre-decision outcomes are reported separately:

1. **Candidate miss:** a `Source` floor target has no acceptable target in its frozen nominations.
2. **Unranked:** inference routes the unit only to `External(Unranked)`.
3. **Support refusal:** the frozen support policy rejects the cell because its measured evidence is
   insufficient for the declared estimand.

These outcomes do not enter false-external or false-source loss. They remain in the established
denominator and reduce decision coverage. The report publishes their counts and rates by story,
recall, groundedness cell, provider outcome, and geometry. It never drops them and renormalizes the
surviving rows.

Gold `Indeterminate` is reported beside those three outcomes but is not one of them: it is an
adjudication limit, not a model support failure. It remains in the roster, reduces binary-gold
coverage, and receives no 0/1 floor-decision error regardless of the predicted state.

Candidate recall is measured on every `Source` floor target before this exclusion. Floor selection
is conditional on candidate support because a scalar external cost cannot recover an anchor that
was never nominated. A floor that appears successful only after candidate misses or provider
abstentions remove one gold class has failed the court.

## 6. Primary estimand and weighting

For a rankable, supported unit with an established `Source` or `External` floor target, collapse only
the Viterbi state for the primary decision:

- any admitted anchored state is **predicted source**;
- any external state except `Unranked` is **predicted external**.

A `Source` floor target predicted external contributes 1 to false-external loss. An `External` floor
target predicted source contributes 1 to false-source loss. An `Indeterminate` target contributes to
neither. A wrong source anchor, hierarchy level, or fidelity mode is not hidden: it is scored in the
secondary source metrics, but it is not redefined as an open-world rejection error.

For floor value `f`, define each cell error by nested macro-averaging:

```text
cell error(f)
  = mean over participants containing the cell
      mean over declared story-family strata for that participant containing the cell
        mean over source-lineage groups for that participant and stratum containing the cell
          mean over recalls for that participant and source-lineage group containing the cell
            mean 0/1 path-decision error over eligible units in that recall and cell

E_low(f)  = mean cell error over the four source cells
E_high(f) = mean cell error over the four external cells
R_bal(f)  = 0.5 * E_low(f) + 0.5 * E_high(f)
```

This weighting gives the two failure directions equal authority, then gives each declared cell and
participant equal authority. Within one participant, each represented story-family stratum,
source-lineage group, and recall receives equal authority at its level. A long transcript cannot
dominate by contributing more units, and within any one cell a participant with more stories or
recalls cannot receive more total weight than another participant represented in that cell. Empty
cells are not assigned zero error. They instead narrow the estimand or make it unestablished as
described above.

`R_bal` is the external-floor estimand. The full vector of cell errors, `E_low`, `E_high`, candidate
recall, support coverage, external-subtype accuracy, acceptable-anchor accuracy, hierarchy accuracy,
distortion/facet accuracy, `P`, and `F` remains part of the receipt. A single balanced number may not
erase a failure concentrated in one class.

## 7. Selection and the honest null

Run the complete aligner at every pre-registered floor. Let the **identified set** contain every
floor attaining the minimum `R_bal` on the calibration partition.

Then repeat the calculation after deleting each calibration source-lineage group in turn. A single
floor may be called selected only when:

1. both failure directions and every cell claimed by the result remain represented;
2. the complete calibration run has one unique minimizing floor;
3. every leave-one-source-lineage-group-out run has that same unique minimizing floor;
4. the minimizing floor is not a grid endpoint; and
5. no identity, support, or roster check failed.

At least three independent calibration source-lineage groups are therefore required for a generally
named selection, and every story-family stratum named by the claim must remain represented. More
units from one source-lineage group do not repair this deficiency.

If any condition fails, the result is **unestablished**. The report publishes the identified set,
risk curves, missing cells, and refusal reason; it does not choose a midpoint, reuse the current
default, or call the absence of evidence a tie-break. Exact ties are evidence for a set, not a
license to manufacture one preferred number.

The inferential null is that no unique, story-stable floor has been established for this identity.
That null stands until the five conditions above reject it. A selected floor may still fail on the
untouched test. Such a failure is reported as a failed confirmation, never followed by retuning on
the same test set.

The calibration partition fits only the numeric floor. The untouched test chooses neither a floor
nor a promotion rule; it evaluates the selected floor once against limits frozen before calibration
labels were opened. Passing every limit permits the predeclared promotion, while failure leaves the
default unestablished. If any limit or required control margin was absent from the pre-calibration
manifest, test results are diagnostic only. Failure does not authorize a second choice from the
same calibration or test outcomes.

## 8. Controls and claims

Every primary dense-provider run is accompanied by two independently identified controls:

- the deterministic hashed n-gram channel; and
- the corpus-fingerprinted TF-IDF channel.

Each control gets its own geometry identity, floor grid, inference runs, identified set, and
selection outcome. The controls establish how far lexical form alone can carry the task. They are
not semantic validation and their floor is not transferable.

Each control's candidate generation and inference are independently executed from the same admitted
recall and source inputs using only that control's declared channel, geometry, and receipts. A
control is never manufactured by applying `CandidateSet.without` to another arm's nominations or by
relabelling an empty-after-ablation result as abstention. A channel-removal run is a counterfactual
diagnostic whose inference output remains an `AblationResult`, not an `HsmmResult` or control run.
Until the operation-derived boundary in section 5 exists, the current `without`-to-ordinary-`infer`
path prevents execution rather than relying on a report writer to label the result honestly.

A claim that non-lexical dense geometry improves open-world alignment requires an admitted dense
provider and an untouched-test comparison against both controls under the same corpus, gold,
candidate policy, loss, and transition protocol. The comparison reports both directional errors;
an average improvement that conceals worse false-source or false-external behavior is not a win.

## 9. Required report and plots

Every execution emits a machine-readable bundle and a static human-readable report. At minimum the
bundle contains:

- the full identity record and its checksum;
- the established roster, exclusion counts, binary-floor-target counts, and indeterminate rates;
- per-floor, per-participant, per-story, per-recall, and per-cell sufficient counts;
- candidate recall, `Unranked`, support-refusal, and decision coverage;
- any counterfactual ablation diagnostics in a separate typed record excluded from those counts and
  from calibration or control comparison;
- `E_low`, `E_high`, `R_bal`, secondary metrics, and the identified set;
- each leave-one-source-lineage-group-out result;
- the selected floor or typed unestablished reason;
- untouched-test results, when unsealed; and
- exact software, provider, geometry, source-edition, input, and output receipts.

The static report includes these plots, with raw counts beside every rate:

1. `E_low`, `E_high`, and `R_bal` across the floor grid, with the identified set and endpoints
   marked;
2. per-cell error curves so macro-averaging cannot hide a failed family;
3. candidate recall and support-failure rates by cell and provider outcome;
4. source/external confusion counts on calibration and untouched test; and
5. model-relative source/external posterior-mass distributions, labelled **model-relative mass**.

A reliability diagram is permitted only for a separately fitted, named probability-calibration
model evaluated on held-out adjudicated labels. The floor report must not decorate raw `P` with a
calibrated-probability axis.

## 10. Pre-data adversarial court

The implementation must kill each plausible weaker procedure below.

| Case or mutation | Required outcome |
|---|---|
| All calibration units have `Source` floor targets | Unestablished: false-source direction is absent |
| All calibration units have `External` floor targets | Unestablished: false-external direction is absent |
| Source and intrusion exist, but other claimed external cells do not | Narrow Source-vs-Intrusion identity or unestablished general result |
| Provider abstention is concentrated in one gold class | Class-specific support failure; never improved decision loss through deletion |
| A caller applies `CandidateSet.without`, bypasses or falsifies the operation-derived ablation boundary, calls ordinary `GraphHsmm.infer`, and submits the `HsmmResult` as an independent control abstention | Checked construction, contextual decode, or output admission refuses it before calibration; if that boundary is absent, execution is unestablished. It contributes to neither `Unranked` or support-failure counts nor calibration or control comparison |
| Acceptable source candidates are removed | Candidate recall fails; floor selection may not claim recovery |
| A constructed low floor makes `Source` targets external | `E_low` increases, demonstrating the too-low failure direction |
| A constructed high floor makes `External` targets source | `E_high` increases, demonstrating the too-high failure direction |
| Identical local cells change path under different transitions | Full-path rerun detects the change; rowwise scoring mutant dies |
| Several floor values tie under `R_bal` | Identified set is published; no single floor selected |
| The minimum lies at a grid endpoint | Unestablished; grid is not expanded on the same calibration outcomes |
| `Unranked` is relabelled as Intrusion | Court fails: support failure and participant groundedness were conflated |
| Oracle gold discourse replaces the unattended or cross-fitted `RecallUnit.function` | Calibration is contaminated: the model input consumed gold through `FunctionPrior` and `ExternalStates.natural` |
| A `Groundedness.Inference` unit with an admitted primary target is automatically counted as external | Court fails: groundedness and the binary floor target were conflated |
| A `Groundedness.Uninterpretable` unit has no separate evidence of external origin | It is retained as `Indeterminate`; neither prediction receives binary error credit or blame |
| One participant contributes many stories or recalls while another contributes one | Their outer cell weights remain equal; only each participant's internal mean changes |
| Missing rows are survivor-renormalized | Published roster/coverage counts disagree and the court fails |
| Provider or geometry identity changes with numerically equal outputs | New calibration identity; old floor is not reused |
| Hashed n-gram, TF-IDF, and dense arms share one selected floor | Court fails: distinct geometries were conflated |
| Raw posterior mass is called calibrated probability | Court fails unless a named held-out calibration model and Brier/ECE/NLL exist |
| Calibration labels are used to select or rank a provider, model, geometry, candidate policy, or other non-floor component | Calibration is contaminated and replaced; the component returns to a separate development-only selection protocol |
| Calibration outcomes are used to set promotion limits or required margins over controls | No promotion claim is permitted; the limits must be frozen before a fresh calibration partition is opened |
| An untouched-test result changes any frozen choice | Test is contaminated and replaced; no confirmation claim survives |

Tests must use fixed, hand-calculable miniature paths for both directional mutations and an
independent implementation of the nested macro-average. Recomputing an expected loss through the
production reducer is not an oracle.

## 11. The WOG number is diagnostic only

The reproduced War of the Ghosts crossing at `1.480580357` is diagnostic evidence that both
branches of the current implementation can be reached under one regression setup. WOG is
machine-built regression material, not an adjudicated calibration partition. Its two faithful units
cannot identify the external floor. The number must not enter the floor grid as a preferred point,
an expected answer, a prior, or a tie-break.

A future calibration result may land above it, below it, include it in an identified set, or remain
unestablished. None of those outcomes validates or contradicts the WOG diagnostic by itself.

## 12. No-retuning rule

Before calibration outcomes are opened, complete and freeze the separate development-only
provider/model-selection protocol, then freeze the court identity record, floor grid, cells, loss,
support policy, exclusions, split, controls, promotion limits, required control margins, plots, and
refusal rules. After opening them:

- do not alter weights, candidates, provider instructions, geometries, missingness, transitions,
  cells, or the grid;
- do not remove difficult units or merge groundedness families;
- do not use calibration labels to choose any non-floor component;
- do not use calibration outcomes to define promotion limits or control margins;
- do not use untouched-test results to choose among tied floors or make any other choice;
- do not call a control-selected floor a dense-provider floor; and
- do not promote a narrower-cell result as a general default.

Any change starts a new versioned calibration problem on fresh selection evidence. Until one passes
this court and its untouched confirmation, `externalFloor` remains provisional.
