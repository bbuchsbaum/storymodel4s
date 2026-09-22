# A reusable recall-to-encoding workflow

Design and execution plan, 22 September 2026. Requested by the owner after the
[Sherlock assessment](../design/sherlock-fmri-analysis-readiness-2026-09-22.md), with
OpenNeuro raw/BIDS as the first imaging target and dynamic visualization as part of
the intended workflow. Runtime commands, new artifact roles and acceptance suites
below are specifications until implemented.

This extends [ADR 0019](../adr/0019-mapping-measurement-policy.md), the
[delivery plan](../refactor/PLAN.md), and the active
[M1 investigation milestone](../refactor/goals/source-recall-workspace-m1-20260922.md).
It does not replace the G1 implementation in progress, expand its current landing,
or remove the film compiler from the agreed stable-release path. The existing
tracker owns implementation status. This document supplies the product and
mathematical contract across those tickets, plus bounded follow-on work.

The visualization application lives in `~/code/scala/storyatlas4s`; scientific
production and analysis contracts live in `~/code/scala/storymodel4s`. Treat these
as one researcher workflow with two repository responsibilities. The existing
[StoryAtlas M1 delivery entry](../../../storyatlas4s/docs/delivery/workspace-m1.md)
is the consumer-side starting point. This sibling link assumes adjacent checkouts;
the repository and path remain the reference in a standalone checkout.

## 1. Outcome and governing decisions

A researcher prepares a source once, maps multiple recalls under explicitly named
evidence/inference policies, inspects uncertainty interactively, queries any
supported recall or source time, and exports the same checked results to Python/R
or an imaging analysis. Changing a plot or sample grid does not require inference.
Changing the evidence or scientific model produces a separately identified result.

The authoritative scientific result is a mapping between identifiable recall
observations and identifiable source occurrences. Continuous temporal maps are
derived measures with declared timing and allocation rules. A smooth image never
creates evidence between observations.

Decisions:

1. Keep stable words, discrete inference units, optional compound references,
   source occurrences, and exact support. Do not reduce the result to a timestamp
   pair or a dense matrix.
2. Offer both an allocation-free support view and a declared temporal-density view.
   The first is available when evidence establishes only an interval. Uniform
   within-interval allocation is an optional projection, never an implicit default.
3. Keep local reference inference and structured reconstruction over shared
   evidence. New structured profiles do not silently inherit Sherlock monotonicity
   or fill. Retain the historical preset by explicit name for parity.
4. Distinguish between-target ambiguity, within-target extent/allocation,
   recall/clock timing uncertainty, and empirical model error. Do not combine them
   into an unexplained confidence scalar.
5. Default displays preserve unconditional source mass, external mass, unknown
   support and failures. Conditional views are named and show their denominator.
6. Use typed coordinate transformations and occurrence identities. A repeated clip,
   a revised edition and another run do not become the same observation because
   their content is similar.
7. Deliver an annotation-assisted analysis before comprehensive film understanding.
   Add audiovisual and structural evidence through the same interfaces and outputs.
8. Reuse existing modules and StoryAtlas. No new top-level module, general plugin
   platform, hosted service, database, or mandatory cloud provider is required.
9. Ship useful uncalibrated outputs honestly. A claim of reliable two-second
   localization has an additional empirical gate; it is not the gate for inspecting
   a broad or ambiguous mapping.

## 2. One workflow, reusable outside Sherlock

```text
Source artifacts -> checked source package -> reusable source evidence --------+
                                                                              |
Recall artifacts -> stable words + unitization + timing -> local evidence -----+
                                                                              v
                                                    named inference + decisions
                                                                              |
                                                         checked mapping result
                                                                              |
                            +----------------------+--------------------------+
                            v                      v                          v
                    temporal queries       organization readouts       evidence inspector
                            |
                   projection + optional scan crosswalk
                            |
                 exchange tables / sparse weights / StoryAtlas
```

Adapters translate source formats into existing checked contracts. Corpus names,
participant aliases, column layouts, scene-number conventions, and scanner crop
tables stay in `corpus-intake`, not in the solver or visualization.

The first delivery must pass the same journey on a text miniature, a synthetic
multipart/repeated-media case, and admitted Sherlock development material. A
second admitted corpus is a transfer check when its existing rules permit it;
opening a sealed participant split is not required for software generality.

Capabilities are explicit. A text-only source supports character/ordinal queries
and target uncertainty, but has no encoding-seconds map without an admitted
presentation schedule. An untimed recall supports unit/word navigation but cannot
claim a continuous recall clock. Missing optional video features still permit the
annotation arm; missing a requested feature refuses that arm rather than silently
running another model.

Source preparation freezes identity and evidence, not scientific interpretation.
Annotation prose, video-derived proposals, audio, and accepted narrative structure
retain their origin and authority. Feature packages are separate from the source
bundle and bind to its edition, occurrences and support.

## 3. Discrete events and continuous uncertainty

### 3.1 Four layers of time-related information

| Layer | Example | Required treatment |
|---|---|---|
| Reference ambiguity | Either event A or event B was recalled | Alternative target hypotheses and their measure semantics |
| Reference extent | The unit summarizes a whole 90-second scene | Preserve that extent; do not equate it with timestamp error |
| Localization within support | A specific action is somewhere inside that scene | Allocation unavailable, assumed, or estimated from finer evidence |
| Observation timing | Word boundary uncertainty, offset drift, scan origin | Separate timing model/transform and its provenance |

A claim about spoken recall time is not automatically a claim about the instant
of covert memory retrieval. The initial estimand is source reference during
observed speech. A neural or cognitive latency model is a separate analysis.

### 3.2 Reference hypotheses before time bins

For unit `u`, retain weights `p[u,j]` on occurrence-qualified targets `j`, modes,
external states, candidate population, and outcome. Their type declares whether
they are raw scores, normalized local-score mass, model posterior mass, transport
mass or calibrated probabilities. Only operations appropriate to the type apply.

“A or B” and “A and B” require different records. Alternatives are a categorical
choice. Multiple actual referents require either a checked decomposition into
linked subunits or structured hypotheses over sets of referents. For such set
hypotheses `R[h]`, inclusion mass is:

```text
inclusion[u,j] = sum_h weight[u,h] * I(j belongs to R[h])
```

Inclusion masses can sum above one. They cannot be normalized into single-target
probabilities without changing the question. Initial delivery uses G1's explicit
compound/decomposition status and refuses unsupported single-point precision.
Later support may add expected-reference-count maps or a declared allocation over
the referents. Neither requires changing the meaning of existing categorical rows.

Choose an explicit target cut for comparisons. Mixed hierarchy outputs remain
inspectable, but a parent and its children must not be counted twice as independent
evidence. A coarse target's mass stays coarse/unallocated until a policy says how
to project it. Splitting a target into ten equivalent children is not a reason to
give that content ten times the prior mass; refinement must preserve a declared
base measure when no evidence changes.

### 3.3 Temporal measures on encoding support

The domain is a disjoint union of occurrence-qualified source axes. A composed
axis is optional and receipted. Coincident coordinates do not erase occurrence
identity.

For a query region `B`, define a temporal measure only when allocation is supplied:

```text
mu[u](B) = sum_j p[u,j] * K[u,j](B)
```

Every available, fully allocated `K` is a nonnegative measure of total mass one
on its declared domain. Unavailable allocation has no numeric kernel; its assigned
mass goes to the allocation ledger, never to an all-zero array. Probability-valued
readouts additionally require checked normalized assignment semantics, including
external mass; generic nonnegative alignment weights alone do not prove this.
Other supported measures retain their declared units and normalization rules.

The allocation status and any available kernel identify their basis:

* **Unavailable:** source support is known but no internal allocation is asserted.
* **Declared allocation:** e.g. uniform over a finite interval union.
* **Model-estimated localization:** a bound fine-localizer supplies a distribution.
* **Observed point/extent evidence:** an admitted measurement and any error model.

For uniform allocation over positive-duration support `S`:

```text
K(B) = duration(S intersect B) / duration(S)
```

Use the union, not its convex hull. Point supports are atoms, not narrow Gaussian
curves invented for plotting. A mix of points and intervals needs declared mixture
weights. Partial support does not establish what fraction of a target's probability
is located; do not infer that fraction from observed duration alone.

An allocation-free view can report useful identification bounds. For wholly known,
nonempty supports and fixed nonnegative categorical weights, allowing any
allocation supported on each `S[j]` gives:

```text
lower(B) = sum_j p[u,j] * I(S[j] is contained in B)
upper(B) = sum_j p[u,j] * I(S[j] intersects B)
```

These are conditional support/allocation bounds, not confidence intervals. Unknown
support needs a separately declared completion domain or unavailable bounds.
They answer what the evidence permits before choosing a uniform kernel.

Keep a mass ledger: projected source mass, known source mass awaiting allocation,
source mass with incomplete/unknown location, external mass, and omitted known
mass in a filtered export. Unknown candidate coverage is a limitation, not an
invented residual probability. Failures are outcomes, not probability categories.

### 3.4 Querying recall time

Preserve word identity under changes of unitization, with explicit word-to-unit
membership. Record timing as missing, onset-only, interval, or estimated; keep the
clock, precision and derivation. A unit's last word onset is not its measured end.

The default for onset-only data is an event view. Dense temporal views require an
explicit policy, such as verified speech intervals, forced-alignment estimates,
bounded hold, or a declared kernel. Hold-last must have an explicit stop/gap policy;
it must not fill a long silence or the unknown final-word duration automatically.

Let `alpha[u](t)` be the declared recall-exposure/mixture weights, nonnegative with
sum at most one. Then:

```text
mu[t](B) = sum_u alpha[u](t) * mu[u](B)
```

The missing exposure `1 - sum_u alpha[u](t)` remains visible as uncovered query
time, not as a participant intrusion. Normalizing over covered exposure is an
explicit conditional query. If weights instead describe simultaneous activity or
event intensity, their sum may exceed one and the readout has different units.

Overlaps need a declared membership policy. Boundary uncertainty can yield a
mixture of neighboring units; a plotting interpolator cannot invent that mixture.
Splitting or duplicating display words creates no additional independent recall
observations. Query rows always retain contributing inference-unit IDs.

### 3.5 What “precisely at t_j” should mean

At query time `t`, a useful resolution-based readout is:

```text
massWithin(t, center, delta) = mu[t]([center-delta, center+delta])
bestWindowMass(t, delta) = maximum of massWithin over valid centers on one axis
```

The window never crosses a run/occurrence boundary implicitly. Report best center,
mass, half-width `delta`, conditioning, allocation policy, candidate coverage and
calibration status. This makes “within two seconds” an explicit query rather than
an interpretation of a color.

Also offer the shortest connected interval containing a requested mass and a
possibly disconnected high-mass set. Return all component intervals, their masses,
total duration and hull separately. Report unattainable requested mass when
external/unallocated mass prevents reaching it. A 90%-source-conditional set and
a 90%-unconditional set are different objects.

Do not report only a mean timestamp. Equal alternatives at 10 and 100 seconds have
a mean where no evidence lies. Likewise, certainty about a 180-second scene is
not second-level precision. Target entropy, temporal spread, known support extent,
and empirical localization error must remain separate. Compare entropy only under
a declared partition; shrinking bins alone must not count as increased uncertainty.

Examples below are synthetic model outputs, not empirical claims:

| Situation | Honest statement |
|---|---|
| 0.92 mass in a four-second region with validated fine evidence | “Model mass within ±2 seconds is 0.92”; empirical reliability shown separately |
| Two distant regions with 0.47 mass each | “Two plausible occurrences”; preserve both islands |
| 0.95 on one 80-second scene, no internal evidence | “Scene strongly identified; within-scene localization unavailable” |
| 0.10 source mass, 0.90 external | Show low source support even if conditional source distribution is narrow |
| No timed evidence at the query | “No temporal observation here”; ordinal recall inspection still works |

The [synthetic illustration](assets/recall-encoding-uncertainty-example.png) shows
these distinctions on one scale. Its source masses are 0.96, 0.94 and 0.95; explicit
uniform allocation gives best four-second masses of 0.92, 0.47 and 0.0475. Recall
exposures are authored ten-second intervals separated by two-second missing gaps.
Those rectangles do not represent ten independent observations or measured human
performance. The [SVG](assets/recall-encoding-uncertainty-example.svg) is available
for close inspection.

## 4. Obtaining finer localization without manufacturing precision

Adopt coarse retrieval followed by optional evidence-based refinement:

1. Nominate occurrences/scenes using content-only features under a declared budget.
2. Preserve a broad alternative set and candidate-recall diagnostics. A narrow
   posterior inside a badly truncated candidate set is not a precision certificate.
3. Within plausible regions, compare finer annotation units, dialogue turns,
   timestamped actions and locally sampled audiovisual evidence.
4. Produce child localization measures with their own evidence/coverage receipts.
   Preserve residual parent alternatives; failure to refine retains a coarse result.
5. If refinement changes the evidence or posterior, mint a new mapping variant with
   a parent link. Do not treat an adaptive inference pass as a display zoom.

A region-wide summary may never have one scientifically meaningful timestamp.
The successful output can be a region or several referents. Finer targets alone do
not justify a precision claim, especially when all inherit the same scene text.

Clock uncertainty can impose a shared error across an entire run. Start with exact
verified transforms and explicit unknowns; add uncertain offsets/drifts only when
evidence supports their error model. Propagate a shared offset coherently through
joint draws, not independently at every pixel. Variances cannot generally be
added unless the stated independence and error-model assumptions warrant it.

For nonlinear downstream statistics or trajectory uncertainty, use valid joint
assignments/coherent path samples when available. Marginal maps suffice for
expected linear features, but not a posterior over correlations or trajectories.
Independent sampling from HSMM marginals is a separately labeled approximation.

## 5. Evidence and inference flexibility

Support explicit, versioned profiles with the same result contract:

| Profile dimension | Initial choices | Extension |
|---|---|---|
| Source evidence | Annotation descriptions and selected fields | Captions, ASR/dialogue, audio/video features, compiled propositions/relations |
| Scoring | Deterministic lexical and existing local sentence embeddings | Structural channels and registered local providers |
| Inference | Strict local reference; named structured reconstruction | Declared contextual models after their compatibility courts |
| Decision | Argmax, top-k, abstain; explicit historical decode/fill | Decision rules with separately qualified calibration |
| Temporal allocation | Support-only; explicit uniform union | Checked fine-localization distribution |
| Recall exposure | Onset events; admitted intervals | Receipted forced alignment or bounded estimates |

Local reference and structured profiles may share evidence only when their
information restrictions permit it. Source chronology must not leak into strict
reference retrieval, tie-breaking, prompts or local scoring. A label alone cannot
certify compatibility.

Annotation-only and annotation-plus-video comparisons freeze units, target cut,
candidate budget, configuration and evaluation denominator. Bind caption artifacts
through the stronger existing media contracts; do not accept an unverified text
map as if it proved model/frame/edition identity. Preserve feature coverage and
missingness. Separate ablation comparisons of one channel from whole-system
comparisons using different target or acquisition policies.

Cache source features across participants. Cache identities include source support,
rendering, provider/model/configuration, and evidence policy. Default execution is
local/offline; remote providers retain existing explicit authorization and budget
contracts. An unavailable model fails the requested arm instead of silently
substituting the lexical baseline.

## 6. Clock and imaging integration

Use existing `ClockRepair`, `TrackComposition`, edition/occurrence identities and
exact rational/tick contracts. Add named adapters and checked transform records
where the current types cannot yet express a concrete required scanner operation.
Do not invent a universal transformation engine in advance of that need.

Record the source clock, recall clock and scanner clock separately. Each scanner
sample inventory binds dataset version, subject/session/run, stored-file identity,
array length, acquisition timestamps or TR, origin, index base, crop/padding,
censoring and already-applied shifts. Preserve an explicit index map after dropped
or censored samples. No arithmetic on subject filenames substitutes for an alias
crosswalk.

BIDS event onset is relative to the first stored data point; discarding initial
acquisitions changes that reference. A BIDS-looking event file does not prove its
relationship to independently supplied media. Verify the actual join and keep
negative onsets when legitimate. See the [BIDS events specification](https://bids-specification.readthedocs.io/en/stable/modality-agnostic-files/events.html).

Sherlock's first adapter must explicitly select OpenNeuro word/TR columns, verify
participant aliases and full-part media-onset/task-event/crop joins, retain both
tails and scan-break metadata separately from composed playback, and use complete
media durations for composition. Full-part joins retain introductory material;
episode-only selection additionally requires verified cartoon/episode transitions
for both parts. An unresolved transition does not invalidate an otherwise verified
full-part join. The earlier measured 7.5-second recall-clock difference and 0.2-second
display discrepancy are regression witnesses, not hard-coded universal corrections.

For declared recall sampling matrix `A`, target weights `P` and encoding allocation
`K`, the categorical semantic projection is:

```text
W[recall_sample, encoding_bin] = sum_u,j A[recall_sample,u] * P[u,j] * K[u,j,encoding_bin]
```

`K` may depend on the unit when localization is recall-specific. Sparse tables or
operators are sufficient; do not require a dense all-participant matrix. Include
the mass/exposure ledger and sample masks beside `W`. Conditional source
normalization is a different export with its denominator retained.

Hemodynamic operators are separate from coordinates and mapping. Encoding and
recall HRFs/lags may differ. HRF undershoots can make outputs negative, so neural
regressors are not probability matrices. Do not apply probability-conservation
tests to signed regressors or call a convolved map a posterior. The initial
storymodel4s deliverable exports semantic weights and timing; the user's imaging
workflow applies its declared BOLD model, with a recipe link back to the mapping.

## 7. Structured outputs and query contract

Keep one immutable checked mapping package, separate derived projection packages,
and small saved investigation files referring to both by digest. Do not put media
bytes, copies of every grid, or presentation-specific state into the scientific
mapping. Complete existing G1 schemas first; extend or add a bound projection
schema afterward without rewriting their established semantics.

| Artifact role | Required information |
|---|---|
| Run manifest | Schema versions, exact inputs, source/recall identities, full resolved config, code/model pins, stage receipts, outcome/readiness |
| Source inventory | Editions, parts, occurrences, axes/timebases, target cut and hierarchy |
| Source support | Target-to-axis interval unions and points, completeness, evidence origin |
| Recall inventory | Transcript-bound words, segmentation IDs, unit membership, compound status |
| Recall timing | Clock-qualified observed/estimated/missing timings and origin/error metadata |
| Measures | All supplied source/external alternatives, typed values, candidate universe/omission limits |
| Decisions | Selection, selected-target value, decision origin, abstention/fill and calibration |
| Projection | Kernel/exposure rules, target grid or query region, transform IDs, conditioning and mass ledger |
| Precision readouts | Window masses/sets/components, support bounds, unattainable/undefined status, evidence/calibration scope |
| Scanner inventory | Subject/run/sample joins and acquired/padded/censored/out-of-range masks |
| Execution ledger | Attempts, cache hits, typed failures, exact outputs and completion receipt |

Canonical interchange is checked JSON plus documented rectangular TSV projections.
Identifiers are qualified, not positional. Exact ticks/rational integers survive as
decimal strings; double-valued measures retain round-trip precision, not display
rounding. Unknowns carry reasons, not numeric sentinels. Tables have primary/foreign
keys, stable ordering, quoting rules, units and dictionary metadata. Reject wrong
joins, duplicate IDs, unsupported schemas, nonfinite measures and digest mismatches.

Sparse matrix export includes row/column inventories, coordinate definitions and
shape. An omitted sparse entry means a known numeric zero only within declared
valid coverage. Unknown or unobserved samples require masks. Start with coordinate
TSV and a Python reader; add NPZ and a thin R reader over the same schema. Parquet
or Arrow can follow measured volume/interoperability needs.

Public query operations, with final names chosen during implementation:

* Recall unit/word/time or interval -> alternatives, support, precision and evidence.
* Encoding region union -> contributing recall units and contribution mass.
* Explicit grids -> tiles or sparse weights, plus all excluded/unallocated mass.
* Unit pair -> joint transition information, or an unavailable/approximation result.
* Policy pair -> matched-unit differences, with compatibility and fixed denominators.

Every query returns quantity, units, conditioning, axes/bin geometry, original mass
ledger, projection identity and stable contributing IDs. For inverse selection,
`mu[u](B)` is contribution to source region `B`, not `P(u | B)`. A reverse posterior
requires an explicit recall prior and Bayes normalization; its zero denominator
is undefined. This distinction applies to the viewer as well as analysis code.

An illustrative query response makes the intended meaning concrete. Field names
are proposed, all values are synthetic, and identity strings below stand for bound
artifact IDs rather than unverified user labels:

```json
{
  "mapping_id": "synthetic-mapping",
  "projection_id": "synthetic-uniform-projection",
  "query": {"axis_id": "recall-a", "seconds": 15.0},
  "contributing_units": [{"unit_id": "u2", "exposure_weight": 1.0}],
  "reference_semantics": "alternative-single-occurrence",
  "quantity": "projected-model-posterior-mass",
  "conditioning": "unconditional",
  "allocation_basis": "declared-uniform-within-support",
  "mass_ledger": {
    "projected_source": 0.94,
    "source_awaiting_allocation": 0.0,
    "source_location_unknown": 0.0,
    "external": 0.06,
    "omitted_known_mass": 0.0
  },
  "uncovered_recall_exposure": 0.0,
  "regions": [
    {"occurrence_id": "a", "axis_id": "encoding-a", "ticks_per_second": "1",
     "start_tick": "60", "end_tick_exclusive": "64", "mass": 0.47},
    {"occurrence_id": "b", "axis_id": "encoding-a", "ticks_per_second": "1",
     "start_tick": "124", "end_tick_exclusive": "128", "mass": 0.47}
  ],
  "best_window": {"width_seconds": 4.0, "mass": 0.47, "tied_occurrence_ids": ["a", "b"]},
  "candidate_coverage": {"status": "unmeasured"},
  "calibration": {"status": "unavailable"},
  "fine_localization_evidence": {"status": "unavailable"}
}
```

This can draw two islands, answer a four-second precision query and preserve
external mass without claiming calibrated precision. A real wire additionally
binds the source/recall inventories, stage and allocation receipts, complete query
geometry and schema versions. Unsupported requested precision returns a structured
reason while the valid broad/alternative mapping remains accessible.

## 8. CLI, notebooks and reproducibility

The primary analyst experience is one study config and one command, with individual
stages available for inspection/reuse. The following commands are a proposed UX:

```text
storymodel study validate study.yaml
storymodel study run study.yaml --out runs/study-A --offline
storymodel mapping query runs/study-A --recall-unit u17 --format json
storymodel mapping export runs/study-A --projection projection.yaml --out analysis/A
storymodel study compare runs/study-A runs/study-AV --out comparisons/A-AV
```

The study configuration binds source package, recall cohort, feature package,
unitization, timing origin, inference/decision policy and requested capabilities.
The output records its fully resolved form. Scientific settings must not depend
silently on inherited environment variables. Provide a minimal annotation preset,
an explicit historical Sherlock preset and an advanced config; avoid 30 mandatory
flags for the common journey.

Allow source preparation and mapping stages separately. A cohort manifest selects
participants and identities without hard-coding their count. `--offline` must fail
if an artifact or recording is absent; it must not trigger an unnoticed download.
Resume checks every dependency digest and completed artifact, not existence of a
TSV. Use task-owned staging and atomic publication or a completion manifest last.
Interrupted runs remain inspectable but cannot satisfy complete-result readers.

Distinguish execution completion, structural validity and requested capability
readiness. A completed coarse mapping can be ready for target inspection and
unready for calibrated two-second localization. Batch failures remain local and
accounted for; selected required-capability failure produces an actionable nonzero
exit with a structured report, without discarding completed participants.

Ship one executable Python walkthrough, then its small R equivalent: load/verify,
query a recall time, retrieve top-k regions, inspect mass/coverage, export sparse
weights, and join scan samples. Neither imports bench code or requires Scala in
the notebook runtime. Preprocessing and voxel/ROI modeling remain outside this
library; mapping can run through any existing job scheduler using the same CLI.

## 9. Dynamic visualization is a consumer of these same quantities

Extend the existing investigation workspace in `~/code/scala/storyatlas4s` after
its M1 provider adoption. The provider computes checked subsets, aggregation and
precision; the viewer controls focus, layout, playback and presentation.

### Repository ownership and shared contract

| Repository | Responsibility in this workflow |
|---|---|
| `storymodel4s` | Scientific identities, evidence, inference, timing transforms, uncertainty semantics, checked queries and projections, canonical codecs, CLI analysis artifacts, and provider-side view compilation in `view` |
| `storyatlas4s` | Artifact loading, linked timelines/heatmaps/time series, semantic selection, media navigation, layout, accessible interaction, saved investigations, and figure/report export orchestration |
| `intaglio` | Generic graphics primitives and rendering used by StoryAtlas |

The dependency runs from StoryAtlas to the checked StoryModel contracts; StoryModel
remains usable independently by a CLI, Python or R. Shared contracts and codecs
come from the producer's pinned revision. StoryAtlas consumes declared capabilities
and handles unsupported versions explicitly. Presentation adapters do not copy the
estimator, implement their own probability normalization, or reconstruct scientific
values from rendered marks. The same checked provider can run locally on JVM or JS
where supported; this boundary does not require a hosted service.

Temporal inspection extends the M1 loader, selection and saved-investigation
contracts. A selection identifies recall units or exact source regions, together
with artifact, occurrence, axis and policy identities; it is independent of pixels
and of which linked view initiated it. Tables, inspection panels, plot annotations
and exported data use the same quantity names, units, conditioning and missingness.

Cross-repository completion requires one receipt naming both exact commits,
contract versions, artifact digests, fixtures and commands. For each temporal
slice, the CLI, an independent Python/R reader and StoryAtlas must agree on known
region masses, timing transforms and typed unavailable outcomes. The consumer
journey loads the generated bundle, selects a recall unit, brushes a disjoint
source region, changes resolution, compares annotation-only and audiovisual
artifacts, saves/reopens the investigation, and exports the selected quantities.
The first temporal consumer uses synthetic precomputed profile pairs; actual AV
acquisition and empirical profile comparison are separate delivery/claim gates.
Verify conservation, identity preservation, explicit policy changes and refusal
of stale or incompatible results throughout. Include narrow, broad, multimodal,
untimed and external cases. Producer tests alone do not complete the viewer slice;
browser success alone does not establish scientific correctness or calibration.

### Linked views and interaction

Required linked views:

1. **Recall timeline/transcript.** Words and inference units, observed timing,
   missing spans and boundaries. Untimed units remain keyboard/ordinal reachable.
2. **Encoding timeline.** Parts/occurrences, source target hierarchy, exact support,
   repeated presentations and allowed evidence; optional local media playback.
3. **Mapping heatmap.** Encoding time horizontally, recall time vertically, with
   clearly labeled measure, units and allocation policy. Gaps/failures differ from
   zeros. Multi-modal assignments appear as separate islands.
4. **Time-series view.** Recall time against selected encoding region(s), source/
   external mass, precision-at-resolution and coverage. A decoded trajectory is a
   separately toggled layer; posterior means are not connected through unsupported
   regions as if they described a real trajectory.
5. **Focused inspector.** Alternatives, exact region union, evidence, raw versus
   selected values, clock path, calibration, and why precision is unavailable.

Moving the recall cursor updates the source bands and inspector. Brushing an
encoding region highlights contributing recall units. Brushing disjoint regions
preserves the union. Selecting a target or word keeps the same semantic selection
when switching timeline, heatmap or hierarchy projection. Coupled playheads are
explicit navigation modes, not a continuous causal/time-warp assertion.

Controls fall into two groups. View controls change zoom, bins, axes, visible layers
and selections. Scientific controls choose a different checked mapping or mint a
new explicit projection. Model, evidence or unitization changes start a new run;
they never quietly mutate the artifact behind the current visualization.

Rendering rules:

* Label bin mass versus density. Mass is `mu(B)`; mean density is `mu(B)/duration(B)`.
  Points need distinct atom markers. Smaller bins do not imply less confidence.
* No automatic per-row, per-viewport or top-k renormalization. Show clipped and
  omitted mass. Conditional source views expose source-mass denominators.
* Rebin the authoritative measure, not a rasterized image. Zoom must preserve
  integral mass, source support and selected identities.
* Support-only regions are visibly unallocated. A uniform-projection heatmap is
  labeled as such; its smoothness must not imply estimated within-scene timing.
* Display smoothing is recorded presentation state, distinct from scientific
  kernels. The raw support remains inspectable and exported scientific values
  remain unchanged.
* A 90%-mass region is labeled with model/score semantics and conditioning.
  “90% calibrated coverage” requires the corresponding validation record.
* Missing timing, failed inference, out-of-source, unlocated source support and
  known zero receive distinct labels and non-color cues.

Use sparse interval queries and coalesced multiresolution tiles for large studies.
Tile keys bind mapping, projection, axes, bin edges and conditioning. Cache a source
once, cancel obsolete queries, and refuse stale responses after a policy change.
Initial acceptance targets: source-only selection at 100 ms p95 and cached viewport
updates at 250 ms p95 on a named local fixture/browser; measure them before claiming
performance and disclose hardware/data size. They are product targets, not results.

Saved investigations contain artifact digests, policy/projection identity,
selections, axis horizons, view settings and cursors. Reopening refuses changed
scientific artifacts rather than guessing a match. Export a figure plus permitted
data subset, accessible table and receipt. Denied content is absent from payloads,
DOM/accessibility and exports; local video bytes do not enter shareable packages.

## 10. Validation: precision must be earned

Separate software correctness from localization validity and downstream utility.

**Engineering courts:** independently authored finite examples, exact coordinate
oracles, mutation/control witnesses, cross-platform codecs and an independent
consumer. Include narrow and broad targets, two distant alternatives, repeated
clips, partial/disjoint/mixed support, “and” versus “or”, silence, untimed units,
overlap, external mass, candidate truncation, wrong clocks and scanner padding.

Required discriminating invariants:

* Rebinning conserves mass; cropping/zoom/top-k never redistributes discarded mass.
* Uniform allocation puts no mass in a gap between support components.
* Refinement with unchanged information/prior does not create artificial evidence.
* Copying unit results to words does not change observation or transition counts.
* Query exposure and source/external/allocation ledgers remain separately complete.
* Exact ticks above 2^53 round-trip through decimal-string encoding; lossy numeric
  conversions are refused. Wrong axes/editions/subjects, nonfinite values,
  duplicate outcomes and incomplete publication are refused.
* A sharp event posterior on a broad support cannot pass a fine-localization gate.
* A deterministic offset translates the map without changing mass; uncertain shared
  offsets retain run-level dependence.
* HRF output cannot enter a probability-typed reader.
* Equal marginals with different joint assignments can produce different trajectory
  summaries; the system must not replace the joints silently.
* Inverse selection does not advertise a reverse posterior without a prior.

**Empirical courts:** use development data for choices; freeze and respect existing
held-out gates. Build human reference annotations that distinguish a precise event
reference, broad event summary, several true referents, alternative plausible
occurrences, external material and unlocalizable cases. Keep annotator uncertainty
and disagreement. Do not force a point timestamp as gold for a broad summary.

Evaluate candidate recall, occurrence/scene accuracy, abstention/risk coverage,
precision at declared second-level tolerances, region containment/overlap under
explicitly different scoring rules, and time-set size at stated coverage. Evaluate
event-boundary timing separately from reference localization. Scene gold cannot
validate second-level uncertainty. Intersection with a large gold interval alone
is not evidence of precise localization.

Use proper scores when the labeled event/observation model makes them applicable:
e.g. Brier/log scores for a declared categorical truth; suitable continuous scores
only when the truth and source-axis geometry support that formulation. Ambiguous
set-valued labels require an explicit observation/scoring model, not arbitrary
replacement by their midpoint. [Gneiting and Raftery](https://sites.stat.washington.edu/people/raftery/Research/PDF/Gneiting2007jasa.pdf)
provide the forecasting basis for separating distribution quality from apparent
sharpness.

Calibration records bind model, evidence profile, candidate procedure, corpus/
population, segmentation, temporal resolution and scoring event. Report validation
by participant and corpus; words from one recall are not independent replicates.
Assess domain shift and preserve an uncalibrated status outside the qualified scope.
Conformal prediction sets are a later option if their exchangeability/sampling
assumptions can be justified; marginal coverage is not per-unit certainty or a
calibrated posterior. See [Romano, Sesia and Candes](https://arxiv.org/abs/2006.02544).

For fMRI, compare scientific results under full uncertainty, justified top-k
approximation and point decisions; report lost mass and sensitivity to timing/HRF
choices. Fit/choose alignment independently of the neural outcome used to evaluate
reinstatement, or explicitly separate fitting and evaluation. No efficacy threshold
is invented here: freeze numerical tolerances and a powered evaluation protocol
before reading the relevant held-out outcomes.

## 11. Delivery sequence with concrete exits

One implementation writer and one heavy gate at a time. An independent synthetic
fixture/readout can proceed alongside a separable implementation slice. The active
M1 provider/viewer chain remains in place; these exits extend it toward analysis.

| Slice | Existing owner/scope | Concrete completion boundary |
|---|---|---|
| 0. Canonical records | Existing G1 `bd-01M2WVENSB0P955Y0B603CC20Y` | Complete its current plan and exact green landing; no new continuous-model scope in that landing |
| 1. Reusable mapping execution | Existing shared evidence/reference/reconstruction tickets | Same typed library operation maps two fixed unit inventories against one prepared source; explicit reference and reconstruction outputs; no bench import |
| 2. Timing and support inputs | Existing G2 timing `bd-01M2WVH1DC8ZPDC992G4MX3TXG` | Stable words, onset-only/interval inputs, exact occurrence-qualified support and explicit missingness; numeric temporal queries are downstream |
| 3. CLI, exchange and temporal uncertainty inspection | Existing facade/exchange, then temporal queries `bd-01M3549Q5W5KQFSY3ZH81FARM0`; Atlas consumer `bd-01M354JNKNKJ15N4T5MGQTRSET` after M1 acceptance | Offline replay and independent reader first; support-only and declared uniform measures, region/grid queries and conservation checks; then heatmap/time-series inspection, mass-aware zoom and saved-state replay |
| 4. OpenNeuro vertical slice | Scanner crosswalk `bd-01M354E83Z4Z7DR4KPP6Q45MAJ`; distinct from the broader G2 organization preview | Verified full-part subject/run arrays and clocks; episode-only selection separately requires verified cartoon boundaries; one development participant then cohort; sparse semantic weights; independent reader recovers known scan joins |
| 5. Paired evidence profiles | Existing media/evidence and research lanes | A versus AV uses same units/cut/denominators; verified feature coverage and receipts; measured result retained even if AV does not help |
| 6. Fine localization and precision qualification | Fine-localization engineering `bd-01M354ETV6PTK1W0V9RDCW7RN6`; qualification under existing reliability `bd-01M2TAEW794XCR7D2HZJFNXVJY` | Fail-coarse behavior, human fine-time reference, resolution-specific validation and calibration; both precise and irreducibly broad cases supported |
| 7. Transfer and release | Existing film/release/hardening lanes | Same contracts on another admitted source/recall arrangement, documented reader/CLI, performance and compatibility evidence; existing stable film gates still apply |

Slices 1–3 are the first usable general workflow; slice 4 is the first usable
OpenNeuro workflow. Slice 6 adds reliable fine-localization claims. Avoid making
the later scientific claim a prerequisite for inspecting earlier uncertainty.
Existing reference/organization/synthetic-recovery prerequisites still govern
their named analysis capabilities; a localization-only release cannot claim that
it has completed the broader G2 scientific preview.

Tracker reconciliation, applied 22 September 2026:

* Keep G1 records unchanged. Shared evidence is `bd-01M2TACM78289S4TECE91GT5K2`,
  reference `bd-01M2WVF86T8QEEA1TK8Z0ASJHW`, compatibility
  `bd-01M2WVFV2WGF5BVQHX7JTYYWRH`, reconstruction
  `bd-01M2TAD04SR823TQVG9VPNH6R3`, and facade
  `bd-01M2TADC4VKSDZ2S9SXETH2MYM`.
* Preserve organization `bd-01M2WVGFA0J4D9T9ZBNR3MY2MR`, synthetic recovery
  `bd-01M2WVHVVYQBKSBSW573HZYR9Q`, and preview
  `bd-01M2WVJ8HKX6EHG2C5CXYEZYJ6` criteria. Do not bypass them with a new milestone name.
* Exact temporal-query, scanner-crosswalk, optional fine-localization and StoryAtlas
  consumer IDs are indexed in [BACKLOG.md](../refactor/BACKLOG.md). Existing evidence,
  facade, exchange, reliability and research tickets retain ownership of their work.
* Basic exchange is independent of organization implementation. Transition-table
  production, checked reader joins and independent tiny transition answers move
  together to the full G2 preview; compatibility -> organization -> synthetic
  recovery -> preview remains intact. Missing capabilities are explicit, not empty
  tables suggesting zero observations.
* M1 remains the fixed-cut source/recall/matrix and saved-investigation journey.
  The StoryAtlas temporal consumer is a sibling under its broader delivery epic,
  locally dependent on M1 acceptance and explicitly blocked for an exact temporal
  producer handoff. General chronology is a separate capability.
* StoryAtlas owns portable HTML reporting as well as dynamic inspection. StoryModel
  owns the report artifact handoff and benchmark migration; historical serializers
  remain until each arm has a proven replacement. The stale StoryModel pin-bump
  ticket is superseded by the existing StoryAtlas adoption obligation, not declared
  implemented.

Module responsibilities remain: `core` coordinates/support; `recall` words/units/
timing; `align` local evidence/inference/readouts; `features` sidecars; `media`
acquisition; `corpus-intake` adapters; `codec` checked exchange; `pipeline` config,
I/O and commands; `view` checked scientific view compilation; StoryAtlas
interaction/rendering in its own repository. No imaging engine is added.

For every implementation diff, run `tools/reference-scope.sh` to determine affected
platform gates, meaningful falsifiers and a separate cold review; land under SD2's
clean exact-revision `sbt -batch checkAll` with bound totals and exit. Proposed
future suite names are not evidence. Preserve environmental failures and all
skips. A doc-only plan does not requalify historical builds.

## 12. Immediate next action and plan boundary

Finish the active G1 landing, then implement the smallest source + recall -> checked
mapping -> independent reader journey using the existing facade/evidence tickets.
Prepare the synthetic temporal witnesses alongside that work. The first new
corpus-specific implementation is an OpenNeuro timing adapter, not another aligner.

This planning pass verified the current contracts, existing ticket ownership and
live G1 partial status; consulted an independent mathematical review; and checked
primary BIDS/scoring references. It did not run participant inference, consume
held-out labels, change the active implementation branch, or claim calibration.

The separate cold review prompted two repairs: an available allocation kernel
must have unit total mass while unavailable allocation stays in the ledger, and
large exact ticks must round-trip rather than be refused. Synthetic illustration
arithmetic and local document links were checked. The illustration was visually
inspected; it is a static explanatory figure, not a completed dynamic UI or an
empirical uncertainty measurement.

## 13. First implementation checkpoint — 22 September 2026

The independent clock-intake oracle is committed at `5a2c5387` on
`solo/openneuro-recall-clock-intake`; [usage](../../tools/recall-study/README.md#explicit-recall-clock-intake)
and [qualification evidence](../refactor/evidence/openneuro-recall-clock-intake-20260922/README.md)
are recorded together. It requires an explicit released clock, checks declared
CSV/alias identity, preserves all records and exact bounded numeric text, and
publishes a content-free intake artifact. Twenty-two tests and eleven compiled
mutations qualify the intake; both clock selections also matched all 2,495 records
of one admitted development source. This is an independent validation target for
the later Scala timing adapter, prepared alongside the active G1 implementation.

The existing mapper still selects its historical clock. Canonical word joins and
the verified OpenNeuro scanner-run crosswalk remain unimplemented by this slice;
scanner ticket `bd-01M354E83Z4Z7DR4KPP6Q45MAJ` remains open. No G1, M1, uncertainty
query or StoryAtlas acceptance criterion is closed by this helper. The next
integration remains the canonical G1/evidence/timing/facade path above.

The following slice, `9d366e26` on `solo/recall-word-clock-join`, adds the
[independent word-clock reader](../../tools/recall-study/WORD_CLOCK_JOIN.md).
It consumes the real G1 synthetic producer wire, verifies exact transcript and
UTF-16 inventory correspondence, retains every CSV record and unit, and reports
onsets with missingness and ordering diagnostics. Reunitization changes the
segmentation binding while parsed word identity survives. Shared recording
identity and full contextual mapping validation remain explicitly unverified by
this reader. Its [evidence](../refactor/evidence/recall-word-clock-join-20260922/README.md)
records 48 tests, 28 compiled mutation kills, both CLI clock examples and separate
review repairs. This advances the independent timing/exchange validation target;
the G2 timing, scanner, G1 and M1 completion boundaries remain unchanged.
