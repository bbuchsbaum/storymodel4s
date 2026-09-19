# Recall Mapping: proposed analysis contract

**Status:** accepted design direction, not an implemented API. Prepared against `1113f96864a38a2869e49d9c8c5d4e2dc43f5d10`; amended 19 September 2026 by [PLAN.md](PLAN.md) and [ADR 0019](../adr/0019-mapping-measurement-policy.md). Version the preview independently of the existing story and HSMM schemas. This document specifies meanings first; reuse existing checked types wherever their meanings agree.

## 1. The artifact and its unit of inference

`RecallMapping` is the checked result for one recall against one identified source representation, with a `MappingRun` envelope identifying configuration, inputs, computation, and readiness. Batch output is a manifest of such runs, not a new kind of source story. A story model still has one source bundle, consistent with the approved D1A plan.

The artifact contains the input-word inventory, the inference-unit inventory, a source-target dictionary, source support, raw mapping measures, discrete decisions, and complete per-unit accounting. All public views derive from these records. A viewer must not reconstruct a second alignment by interpreting display strings.

The default inference unit is a source-independently segmented recall passage. Word IDs remain stable when segmentation changes; inference-unit IDs do not. A parent passage can have finer components if the segmenter supports them. A distribution is always indexed to its actual inference component and its stated estimand.

**Critical distinction:** alternatives in a distribution mean uncertainty about a referent. They do not mean that all listed events were recalled together. A passage containing two recalled events must be split into supported components, represented by an explicitly joint/multi-reference extension, or marked as requiring decomposition. Never normalize a multi-label relation and silently call it a categorical posterior.

## 2. Coordinate and identity rules

Use five separately identified coordinate systems:

1. Source text: canonical text checksum, zero-based half-open UTF-16 offsets, explicit normalization history.
2. Source presentation: bundle/edition, axis ID and kind, exact timebase, exact ticks, and part/composition identity.
3. Recall text: canonical transcript checksum, stable word IDs, zero-based half-open UTF-16 offsets.
4. Recall audio: recording/axis identity and measured or estimated word timing, with timing method and coverage.
5. Story-world order: optional partial relation or an explicitly declared ranking. It is never inferred from playback by default.

A subtitle or annotation axis is not an edition playback axis. An annotation-to-edition mapping needs its own identity and evidence. A source without that mapping can still support annotation-localized analysis but cannot advertise movie-second localization.

Authoritative long integers and rational numerator/denominator values must survive R, JavaScript, and Python loading. In JSON and exchange tables, encode exact ticks and potentially large integer identifiers as decimal strings. Derived decimal seconds are numeric conveniences, not authoritative replacements. All interval boundaries are half-open except where a source adapter explicitly preserves a different original convention and resolves it before mapping.

A narrative event may have several presentation occurrences. Preserve interval sets and occurrence ambiguity. A hull used for display is a different derivation from the union of evidence intervals. No interval may cross distinct axes without a declared composition mapping.

## 3. Minimum exchange bundle

Use UTF-8 TSV tables with a documented quoting convention plus JSON metadata; authoritative numeric measures are not rounded to four or six decimal places. A typed JSON codec may carry the same records. Readers validate rather than infer types from the first rows. Parquet is an optional later adapter, not a prerequisite.

### `manifest.json`

`source_bundles` is a checked inventory; each target and axis resolves to exactly one listed
bundle. The annotation preview may use Sherlock's two existing part bundles. It does not invent
a single bundle identity. A canonical compiled StoryModel still has one bundle, composed under
D1A when multipart. Two-part export/readback and foreign-part refusal are mandatory witnesses.

Required fields: `schema`, `schema_version`, `run_id`, `source_representation_id`, `source_bundles`, `recall_artifact_id`, `input_manifest_digest`, `source_digest`, `recall_digest`, `segmentation_id`, `resolved_config_digest`, `software_revision`, `provider_and_model_identities`, `candidate_policy_id`, `inference_method`, `decision_policy_id`, `measure_kind`, `normalization_scope`, `calibration_status`, `calibration_artifact_id`, `input_track`, `axes`, `coordinate_mappings`, `readiness`, `file_hashes`, `counts`, and `failures`, together with the policy fields in section 8. Each axis entry identifies its bundle, kind, recording/edition identity, extent, origin, and exact timebase; each coordinate-mapping entry identifies its endpoints, transformation, provenance, and applicable domain. Recall timing must never require guessing the timebase from a column name.

Store stable semantic identities separately from wall-clock timestamps and execution receipts. A repeated offline run may have a different execution time but the same input, configuration, and content identity. A changed source caption, feature model, segmentation, or decision rule invalidates the relevant derived identity.

### `recall_words.tsv`

Primary key: `(recall_artifact_id, word_id)`.

Fields: `participant_id`, `session_id`, `word_index`, `word_id`, `text`, `char_start_utf16`, `char_end_utf16`, `speaker_id`, `speaker_role`, `recall_axis_id`, `onset_tick`, `offset_tick`, `timing_status`, `timing_method_id`, `analysis_scope`.

Text-bearing exports require an explicit content policy; a content-restricted export can omit `text` while retaining identities and permitted coordinates. Null onset is missing, not zero. Null offset is not filled from the next onset. `timing_status` distinguishes measured, estimated, onset-only, missing, and invalid/refused input.

### `recall_units.tsv`

Primary key: `(run_id, recall_unit_id)`.

Fields: `ordinal`, `parent_unit_id`, `segmentation_id`, `first_observed_onset_tick`, `last_observed_word_onset_tick`, `timed_word_count`, `word_count`, `discourse_function`, `mapping_semantics`, `decomposition_status`.

The first observed onset need not be the true onset of the passage when preceding words lack timing. The last word onset is not an offset. Noncontiguous text support is preserved through `unit_words.tsv`; a hull is not substituted.

### `unit_words.tsv`

Primary key: `(run_id, recall_unit_id, word_id)`.

Fields: `membership_kind`, `projection_weight`, `projection_policy_id`. The first supported release uses a source-independent partition of words into the selected inference units. Overlapping-window inference requires an explicit aggregation policy and separate normalization tests; it cannot enter through accidental duplicate joins.

### `source_targets.tsv`

Primary key: `(source_representation_id, target_id)`.

Fields: `source_node_ref`, `target_kind`, `hierarchy_level`, `parent_target_id`, `occurrence_id`, `label`, `propositional_scope`, `evidence_origin`, `support_status`, `support_coverage`, `source_annotation_id`.

A target can be an event, a segment/scene, or another declared alignable unit. Source-wide hypotheses use explicit target granularity. A segment is not credited as if every descendant event were recalled. For evaluation or scalar aggregation, select a declared partition or use the explicitly defined hierarchy projection.

### `source_loci.tsv`

Primary key: `(source_representation_id, target_id, locus_id)`.

Fields: `axis_id`, `axis_kind`, `edition_id`, `part_id`, `locus_kind`, `start_tick`, `end_tick`, `timebase_numerator`, `timebase_denominator`, `char_start_utf16`, `char_end_utf16`, `support_relation`, `grounding_method_id`, `precision_status`.

Rows carry either the fields appropriate to text or those appropriate to time, not fabricated placeholders. Instants remain instants. `support_relation` distinguishes evidence support, estimated event extent, occurrence support, and display hull. A whole-scene caption can have exact scene coordinates without having exact event-localization precision.

### `mapping_links.tsv`

Primary key: `(run_id, recall_unit_id, component_id, state_id)`; `component_id` is `main` for ordinary single-component inference.

Fields: `target_id`, `external_state`, `raw_value`, `measure_kind`, `normalization_scope`, `inference_stage_id`, `candidate_set_id`, `fidelity_status`, `fidelity_facets`, `assessment_support_id`.

Exactly one of target and external destination is populated. Retain source and external alternatives. Do not create a calibrated-probability column merely by renaming or renormalizing `raw_value`. Rich joint/compound models require a schema extension identifying their factorization; they cannot reuse these rows with a different unstated interpretation.

### `unit_outcomes.tsv`

Exactly one row per requested inference unit, including failures and policy exclusions.

Fields: `processing_status`, `localization_status`, `reason_code`, `raw_argmax_target_id`, `raw_argmax_mass`, `decoded_target_id`, `decoded_target_mass`, `decision_origin`, `decision_policy_id`, `decision_in_candidate_support`, `calibrated_decision_confidence`, `calibration_artifact_id`, `fidelity_assessment_status`.

`processing_status`: complete, partial, failed, excluded-by-input-policy. `localization_status`: located, ambiguous, nonlocalizable, unranked, not-computed. These describe different dimensions. `decision_origin`: raw-argmax, structured-decode, gap-fill, manual-review, abstention. A gap-filled target can have zero raw posterior mass; that is retained and labeled, not repaired by inventing probability.

Optional `flows.tsv` declares whether values are posterior joint transition measures, transitions on one decoded path, or a named approximation. Optional metric tables name the measure, denominator, projection, uncertainty calculation, and applicable population. An inspection report is generated from these tables, not maintained separately.

## 3a. Evaluation support is not an inference unit

The benchmark owns a separate frozen evaluation-support manifest. It is not a gold-bearing input to the production mapper. With fixed segmentation, this can name the same recall units used by all arms. When comparing segmenters or inference resolutions, project each arm onto the same prespecified evaluation words/onsets/intervals and retain exactly the same gold eligibility and weights. Otherwise changing segmentation changes the denominator and can manufacture an improvement. Preserve the existing preregistered scoring support for historical comparisons; do not silently replace it with a new word-weighted estimand.

## 4. Measure semantics

The allowed measure kinds have distinct validation rules:

- `RawScore`: finite values with the declared direction and scale; no probability interpretation.
- `NormalizedScoreMass`: nonnegative, normalized local score weights under a named reference prior, target universe and temperature. Their sum is one but this does not make them calibrated probabilities or an observation likelihood. Export derived transition quantities as score-conditional summaries unless additional evidence licenses a probabilistic interpretation.
- `TransportMass`: nonnegative finite transported quantities and explicit source/row mass budgets; no automatic row-sum-one assumption.
- `ModelPosterior`: nonnegative finite probabilities normalized over the model's declared state space, including external states. Validation tolerance is declared by the codec; test at least `1e-8` for double-precision round trips.
- `CalibratedProbability`: a specifically defined probabilistic estimand with a calibration artifact, applicable input/domain scope, and validation evidence. Calibration of a final decision's correctness is not calibration of every cell of a categorical posterior.

Posterior normalization is not evidence completeness. A probability distribution conditional on a top-k candidate set can be sharply wrong because retrieval omitted the correct event. Store candidate policy and truncation status and evaluate candidate recall separately. Unknown mass outside the candidate set is not made known by subtracting stored probabilities from one.

An aligner unable to rank a unit differs from a modeled intrusion. An empty provider response differs from low semantic similarity. Inherited legacy labels such as a source state's `Faithful` mode must not be exported as an assessed fidelity result when the source view declares no propositional assessment scope.

## 5. Public views

### Discrete

One target or a declared non-assignment per unit, generated by a named decision rule. Retain the raw argmax alongside structured decoding. Never label a post-decoded choice as the posterior mode when it is not one.

### Probabilistic localization

Marginalize assessed fidelity modes to target identity when the desired estimand is location. Do not require correct recalled details to assign an event reference. Preserve external mass and candidate-conditioning metadata.

### Word-aligned

Project a unit's mapping to its member words through `unit_words.tsv`. Mark `inference_resolution=unit` and `projection_resolution=word`. This makes every word addressable for analysis without claiming that each word was independently localized. Genuine within-unit changes require finer inference units and independent evaluation.

### Time-aligned

When measured recall intervals exist, construct a declared recall-bin-to-unit exposure matrix `W`. When only onsets exist, an onset/impulse projection is permitted and is explicitly not duration coverage. Missing intervals stay missing.

For a categorical localization matrix `P` and a declared source-target-to-time-bin allocation `H`, a projected matrix is `D = W P H`. This is a derived modeling operation, not a new observation. Preserve excluded/unknown recall exposure and external or unprojectable source mass beside `D`.

`H` cannot distribute scene-level mass uniformly across time unless that allocation assumption is explicitly requested and recorded. Repeated occurrences require an occurrence allocation policy or retained occurrence ambiguity. For ordinary exports, exact support sets are preferable to manufacturing a dense time-by-time matrix.

### Multiscale and trajectory

Aggregate on a declared hierarchy cut; count an ancestor and descendant together only for a metric explicitly designed for overlapping supports. A coarse parent assignment cannot be turned into several independently recalled children. A decoded trajectory and the model's uncertainty over trajectories are different products with separate provenance.

## 6. Downstream quantities with defensible names

Ship three initial examples: the discrete recall trajectory, expected mapped-unit count by source unit, and an explicitly configured time-bin projection. For posterior `P`, `sum_i P[i,j]` is an expected count of unit assignments to target `j`, not the probability that target `j` was recalled at least once.

Unique-event recall probability generally requires joint information. Do not default to `1 - product_i(1-P[i,j])`, which assumes independence that a sequence model does not establish. Use a hard coverage statistic or an explicitly justified joint calculation instead.

Order preservation is interpreted relative to a named clock. Do not score monotonicity against playback and describe it as preservation of story-world chronology. Report localization specificity and support coverage with accuracy so that broad intervals cannot improve an apparent score for free. Scientific organization readouts require the reference policy and compatibility check below; an order-ablated HSMM is a reconstruction diagnostic, not a substitute. Differences between policies measure assumption sensitivity, not bias correction. Report unit and transition coverage by participant and condition separately from audio-timing coverage.

## 7. Reader/writer and regression obligations

Readers reject missing required files, digest mismatches, foreign source/recall identities, duplicate keys, unresolved targets, mismatched axes, malformed numbers, and unsupported schema versions. Partial bundles are readable only under an explicit partial-read mode. Write to a temporary output directory, verify it, then publish atomically where supported; otherwise a completion marker written last is authoritative and readers refuse an uncommitted bundle.

Required tests include: non-BMP Unicode round-trip; changed segmentation with stable word IDs; quoted/newline CSV input; missing and onset-only timing; mixed timed/untimed units; duplicate target IDs; a scene with one missing child locus; two-part films; discontiguous occurrences; annotation-only axes; source-vs-recall clock confusion; coarse support versus precise event extent; argmax/decode disagreement; gap-fill outside posterior support; no candidates; provider failure; all-external posterior; candidate truncation; absent fidelity evidence; and compound recall.

The R/Python consumer test must reconstruct word joins and load exact coordinates without implicit factor conversion, one-based/zero-based confusion, UTF-16/code-point confusion, or floating-point conversion of authoritative integer strings. Its expected answers come from an independently authored miniature fixture, not from reusing the exporter's calculation.

## 8. Inference policy and organization measurement

The initial schema includes `inference_policy_id`, `stage_assumption_receipts`,
`reference_prior_id`, `target_universe_id`, `candidate_coverage`, `context_policy_id`,
`inference_unit`, `organization_unit`, `projection_unit` and `measurement_compatibility`.
These fields are derived from the actual stages and bound to their inputs/configuration;
a caller-supplied label cannot certify reference measurement. A historical artifact without
the needed receipts has unknown provenance rather than an invented reference policy.

The strict local reference profile uses fixed recall packets, content-only target views,
opaque IDs and declared independent local normalization. No chronological candidate ordering,
sequence transition, iterative neighbor refinement, monotone decision or gap fill enters this
profile. Readouts receive coordinates separately. Source meaning can contain temporal language;
this restriction is on information access and organizational assumptions, not statistical
independence between content and time. Contextual measurement is a later named profile.

Reference and reconstruction share evidence and preserve their separate outputs. Compatibility
with a requested quantity/axis/grain/unknown-transition rule is checked across retrieval,
rendering, local scoring, context, refinement, inference, decision and projection. Outcomes:
CompatibleWithDeclaredPolicy, ModelDependentOnly, Incompatible, UnknownProvenance. A successful
check does not establish recovery accuracy or calibration. A hard-monotone result is refused as
a reference estimate of backtracking while remaining usable as a reconstruction summary.

Adjacent-unit transition counts use joint assignments. Products of rows require declared
conditional independence; coherent trajectory samples or joint marginals are required otherwise.
Report forward, backward, same-target, external, incomparable, unresolved and failure accounting.
Keep original adjacencies, including missing units. Initial rates are ratios of expected counts
(or score-weighted counts for NormalizedScoreMass), not expected per-trajectory ratios.
Organization summaries operate on the declared inference/event units, never inflated word copies.

Min/max additive count bounds retain the admitted candidate set and its coverage policy. They
are conditional ambiguity bounds, not confidence intervals. An uninformative row remains visibly
uninformative even when many such rows produce a stable average. Scientific recovery tests include
reverse/revisit sequences, repeated occurrences, metadata permutations and unequal measurement
quality. Calibration of a chosen target does not certify transition probabilities.
The first bound solver accepts per-row admissible sets and explicitly supported adjacent
constraints only; it refuses arbitrary coupled/HSMM admissibility. Independently enumerate tiny
cases to check the sparse dynamic program.
