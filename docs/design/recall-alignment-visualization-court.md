# Recall-alignment visualization court

**Bead:** `bd-01M1C8WSZ49M7SHG2HMQCWRK6E`

**Status:** pre-implementation acceptance specification

**Scope:** one renderer-neutral view of a checked recall graph and one gated `HsmmResult`

This court specifies how a recall-to-source alignment may become an inspectable report without
turning a maximum path, a drawing, or an unavailable value into scientific evidence. The report
answers three questions together:

1. Where did the identified model place each recall unit, and how broadly?
2. How did posterior mass move between consecutive recall units?
3. Which source, recall, configuration, evidence, and provider identities entitle those answers?

The semantic input is the complete gated result: posterior matrix `P`, transition flow `F`, the
Viterbi path, costs, candidate anchors, admissibility, and the result fingerprints. A renderer may
choose geometry, labels, and a visible branch threshold. It may not recompute the alignment,
renormalize a displayed subset, or invent a status that the input does not establish.

This document does not add a public Scala type, implement a lowering, admit a film or corpus, or
calibrate a probability. It freezes the court that a later implementation must pass.

## 1. Governing decisions

This court applies the following existing decisions rather than introducing a second vocabulary:

- ADR 0002 D10 derives recall marks from `AlignState`, `AlignmentMatrix`, `TransitionFlow`, and
  recall-side `ElaborationEdge` values.
- ADR 0002 D12 requires a deterministic textual twin for every graphical fact.
- ADR 0002 V-R1 forbids a recall visual category without an `align` type or named derived
  quantity.
- The current output-bundle ruling, `post-01M19TDF0CHGHYAVVR2C2XT1AW`, separates semantic,
  report, and delivery outcomes. A renderer failure cannot erase a valid semantic result.
- The same ruling makes canonical `preview.html` a self-contained static HTML/CSS report. Inline
  SVG and same-document fragment references are permitted; JavaScript, inline event handlers, and
  automatically loaded external resources are not.
- Output missingness law D3 requires measured zero, missing, ineligible, imputed, suppressed,
  failed, and unavailable to remain distinguishable in text and by a non-colour channel.

The report uses the terms **posterior mass** or **model-relative mass** for `P` and `F`. It does not
call those values calibrated probabilities. A later calibration model would be a separately named,
identified artifact.

## 2. Current source findings

The semantic vocabulary is already sufficient. The current implementation does not yet carry all
of the audit material needed by a publication-grade packet.

| Question | Current source answer | Consequence for this court |
|---|---|---|
| Can a cell distinguish faithful, distorted, and external placement? | Yes. `AlignState` has `Source`, `Distorted` with a nonempty facet set, and six typed `ExternalState`s. | No new placement category is allowed. |
| Can the view preserve breadth instead of one link? | Yes. Every `AlignmentRow` retains its state map, `topK`, source/external mass, and `localizability(K)`. | The packet retains every row entry, including present zeroes; top-k is display-only. |
| Can it show the route rather than infer it from row winners? | Yes. `TransitionFlow` contains an exact `FlowStep` between each consecutive pair. | Route marks come from `F`, never from adjacent argmax states. |
| Can it link marks back to typed identities? | Yes. `AlignRef.Cell` and `AlignRef.Transition` are already members of `ViewRef`. | Every cell and flow mark uses its existing typed reference. |
| Does `HsmmResult` retain the full candidate/provider receipt? | No. It proves canonical candidate anchors, but explicitly does not prove nomination provenance. `Nomination` retains channel/rank/score/space/receipt upstream. | A fully auditable packet needs a checked companion invocation record. It must not reconstruct provider identity from an anchor or channel label. |
| Does `HsmmResult` retain the complete `HsmmConfig` and local-cost configuration? | No. It retains `refinementPasses`, not temperature, transition weights, refinement weight, cost weights, floors, providers, or missingness policy. | Exact configuration must arrive in the checked invocation record. `toString`, defaults, and caller labels are not identities. |
| Does `HsmmResult.validated` prove that every Viterbi state and every explicit `F` endpoint is present as a key in its adjacent `P` row? | No. The validator proves nomination and mode/gate admission. Its marginal check reads a row-absent state as numeric zero, so it can admit an explicit zero-flow entry with a row-absent endpoint; the Viterbi check likewise does not test row-key membership. | The lowering independently checks key presence. It refuses a row-absent Viterbi state and every explicit `F` key with a row-absent endpoint, including a key whose flow is exactly zero. |
| Does `HsmmResult.validated` require a finite `logLikelihood`? | Not completely. It rejects `NaN`, but currently admits positive and negative infinity. | Publication-grade lowering refuses a non-finite value or preserves valid `P`/`F` with an explicit audit refusal. It never renders infinity as an ordinary measured statistic. |
| Does `recallChecksum` bind every recall relation the report may display? | No. It binds the transcript, units, temporal relations, and causal relations read by alignment; it does not bind elaboration, entities, or coreference. | A report that displays those relations also needs the complete checked recall artifact identity. It must not imply that `recallChecksum` proves fields outside its rendering. |
| Does a checked `RecallGraph` establish authority to disclose transcript text? | No. The current codec still carries recall through a plain `StorySource`; semantic validity and pseudonymization do not establish consent, REB authority, licence, or release status. | Exact text makes `preview.html` local-sensitive by default. A shareable or publication-ready disposition requires a separately established disclosure/admission outcome; fixtures use synthetic or admitted text. |
| Does `CostBreakdown` publish term eligibility? | Not completely. It publishes priced terms, `missingTerms`, `imputedTerms`, `supportWeight`, structural coverage, receipts, and exclusion, but not the complete eligible-term set used to compute support. | The report may show the published distinctions. It must render term-level eligibility as unavailable unless a later checked carrier supplies it. Absence from a map is not proof of ineligibility. |
| Does membership in a gated `HsmmResult` prove that each `CostBreakdown` crossed its checked factory? | No. `CostBreakdown` remains a `private[align]` case class and is reconstructible outside `align` through `Mirror.ProductOf`. The four nested structural member/reduction records are already sealed non-case classes with external compile-refusal courts, but a forged outer breakdown can still violate its own fields or pair a structural scalar with a valid receipt that does not reduce to it. `HsmmResult.validated` checks state admission plus mode/exclusion coherence, but does not replay `AlignWire.costBreakdown`. | Lowering rebuilds every outer breakdown through `AlignWire.costBreakdown` before publishing its fields. Wire decoding may also reconstruct the sealed nested records as defense in depth, without claiming they retain a public `Mirror` door. A rejected breakdown blocks publication-grade status. Because `total` is only a cached, range-checked value at the wire door, lowering recomputes it from the complete invocation configuration or marks it explicitly unverified. |
| Does current `ViewProvenance` prove the authority claimed by `ViewBasis`? | Not completely. The basis family is typed and build/adjudication cases require a model receipt checksum, but `ViewProvenance.of` still accepts a caller-selected basis and the fixture constructor carries no independent authority receipt. | Scientific lowering derives the existing basis from admitted fixture, build, or adjudication provenance. Unsupported, caller-asserted, or mixed authority roots refuse instead of being relabelled. |
| Does the current source axis establish a film presentation coordinate? | No. `SourceView` has exact source spans, optional measured discourse position, and optional story-world order. C1 film edition and `PresentationAxis` contracts do not exist yet. | Text fixtures show text coordinates. They render film edition and presentation axis as unavailable, never as normalized character position. |
| Does `view` already lower `HsmmResult`, `P`, or `F`? | No. The reference seam exists, but there is no semantic lowering. | This is a specification slice. Implementation begins only under separate authority. |

These gaps do not make the checked `HsmmResult` invalid. They determine what a report may claim.
A semantically valid result may have incomplete audit provenance or a failed renderer. Those states
remain separate.

## 3. Required input boundary

The lowering accepts checked high-level values, but it does not treat an outer cost record as proven
merely because a checked aggregate contains it:

- a checked `RecallGraph`;
- the exact `SourceView` against which the result was validated;
- a gated `HsmmResult` whose `viewFingerprint` and `recallChecksum` match those inputs;
- every `CostBreakdown`, reconstructed through `AlignWire.costBreakdown`, including its checks over
  structural term/receipt coherence and source-chart coverage; and
- a checked invocation record containing the exact configuration and provenance that are not in
  `HsmmResult` itself.

The invocation record is a requirement on the output bundle, not a new type authorized here. It
must bind the actual inputs. It may not ask a caller to assert what provider, geometry, or
configuration was used after inference has finished.

The lowering refuses publication-grade status when any required join fails. It may still preserve
the valid semantic result and state the audit refusal. In particular, it must not infer identity
from a display name, a short checksum prefix, a candidate anchor, the current default, or a rendered
`toString` value.

The lowering also enforces two key-presence relations not currently proved by
`HsmmResult.validated`: every Viterbi state is a key in its corresponding posterior row, and both
endpoints of every explicit flow-map key are keys in the adjacent rows. An explicit `0.0` flow does
not waive this relation. A non-finite `logLikelihood` refuses publication-grade status; valid `P`
and `F` may still be preserved with that audit refusal.

The same rule applies to the cost audit. A `CostBreakdown` that fails checked reconstruction cannot
publish terms, support, imputation, exclusion, structural receipt/scalar coherence, or `total` as
checked facts. In particular, a forged outer record can pair a structural term scalar with a valid
sealed receipt that reduces to a different value; `AlignWire.costBreakdown` must reject that join.
Independently valid `P` and `F` may be retained behind the typed audit refusal. The complete
invocation record supplies the cost weights, function prior, and policy needed to recompute the
cached `total`; if exact recomputation is not possible, the packet labels that total unverified
instead of presenting it as measured.

Disclosure is a separate boundary. A checked semantic packet does not authorize transcript
release. The invocation/output companion record must establish that the recall text is synthetic
or has passed the repository admission and disclosure process before any shareable or
publication-ready disposition is emitted.

### 3.1 Identity record

The report binds the following values without shortening them in the machine-readable packet:

| Boundary | Required values |
|---|---|
| Source text | `StorySource.id`, raw checksum, canonical checksum, and the exact source model/build receipt when present |
| Alignment source | derived `ViewFingerprint`, complete `SourceNodeRef` roster, hierarchy parent and level, exact `NodeSummary.support`, relation-layer identities, optional world-order record |
| Recall transcript | transcript `StorySource.id`, raw checksum, canonical checksum, and derived aligner-input `recallChecksum` |
| Recall units | complete checked recall-artifact identity; ordered `RecallUnitId`s, ordinals, exact `SpanSet`s, text, function, expressed uncertainty, proposition/evidence identity, and every displayed recall relation |
| Inference | every `HsmmConfig` field, every transition parameter, refinement settings, complete local-cost settings, support/refusal policy, and software revision |
| Candidates | complete lawful nominations per unit: anchor, channel, channel-local rank, raw score with its declared direction, space, and receipt; plus the canonical anchor set proved by `HsmmResult` |
| Provider and geometry | full typed provider fingerprint, query and document geometry identities, geometry-pair rule, semantic views/instructions, and attempt/derivation receipts when such a provider participated |
| Result | `HsmmResult.viewFingerprint`, `recallChecksum`, admissibility echo, posterior rows, transition steps, Viterbi states, costs, admissibility, log likelihood, and refinement pass count |
| View | compiler/configuration identity, branch threshold, top-k limit, omission policy, coordinate choice, filters, normalization, and software revision |
| Scientific view authority | provenance-derived `ViewBasis`, exact source checksum, and the corresponding admitted fixture, build, or adjudication receipt; one homogeneous authority root only |

For a deterministic lexical or table fixture with no typed dense-provider identity, the provider
field says that no typed dense provider participated. It does not invent a `ProviderFingerprint`.
For an upstream record that contains only the current string fields of `Nomination`, the report
shows those strings as legacy nomination metadata and marks typed provider/geometry identity
unavailable.

`ViewBasis` is an authority claim, not a display label or a synonym for model validation. The
lowering derives it from admitted fixture, build, or human-adjudication provenance. It refuses an
unsupported root, a caller-selected basis that its receipts do not establish, and heterogeneous
roots under the current scalar basis rather than choosing the first or strongest-looking one.
Changing only a label cannot change this field.

Film output adds the admitted source-bundle edition, immutable stream identities, and the checked
presentation-axis identity once C1 provides them. A text-only fixture omits those fields with an
explicit not-applicable reason. It does not reuse `canonicalChecksum`, `textLength`, or a relative
character offset as a film edition or clock.

## 4. Renderer-neutral semantic packet

This section names fields by their source values. It is an acceptance shape, not a proposed public
case class.

### 4.1 Source and recall rosters

The packet contains every source node and every recall unit used by inference.

For each source node it records:

- the exact `SourceNodeRef` and canonical key;
- whether the node is a `Situation` or `Segment`;
- hierarchy level, parent, and deterministic discourse rank;
- the exact `SpanSet` evidence, without replacing discontinuous spans by their hull;
- optional measured discourse position and optional story-world rank; and
- proposition/chart coverage and importance missingness where published by the source view.

For each recall unit it records:

- `RecallUnitId`, ordinal, text, and exact transcript `SpanSet`;
- discourse function and expressed-uncertainty cues;
- proposition/evidence identity; and
- recall-side temporal, causal, elaboration, and coreference relations that touch the unit, plus
  the referenced recall-entity definitions and mention spans.

Every evidence span is interpreted only against the checksum of its own source or transcript.
Equal offsets in different texts are not the same evidence.

The aligner's `recallChecksum` is also kept, but it is not substituted for the complete recall
artifact identity. Its deliberate field set binds what alignment reads. The complete identity binds
the elaboration, entity, and coreference facts that the report may additionally display.

`SourceView.measuredPosition` may be absent. The lowering carries that absence. It must never use
`relativePosition` to turn an unresolvable or unmeasured node into the real position `0.0`.

### 4.2 Posterior matrix `P`

The packet contains each `AlignmentRow` in recall order and every key/value entry in its mass map,
sorted by `AlignState.key`. It also contains the cost records that explain dropped states. Map-key
presence matters:

- a present value `0.0` is a state in this inference space with zero represented mass;
- an absent key is not in that row's state space and is **unavailable**, not measured zero; and
- a state excluded in `costs` remains a refusal/exclusion record, not a zero-mass placement.

The current `GraphHsmm` emits a posterior key for every non-excluded state and keeps excluded states
only in `costs`. A later lowering must check that relation. A non-excluded cost record with no
posterior key is an ambiguous cost-only state and refuses publication instead of being guessed to
mean zero or unavailable. A posterior key without a cost record may retain its valid mass, but its
cost/support audit is explicitly unavailable.

Each row reports these derived quantities without replacing the underlying cells:

- total mass;
- source, faithful, distorted, and per-external-state mass;
- mass per source anchor with modes summed;
- `localizability(sourceView.sourceNodeCount)` with the exact `K`; and
- a display branch set defined by the frozen top-k and mass threshold, plus the undisplayed
  remainder.

The display subset is never renormalized. If visible branches sum to `0.72`, the report says `0.72`
and reports the remainder; it does not rescale them to one.

Faithful and distorted mass stay separate. A distorted state with several facets is one state with
one mass and several facet labels. Per-facet summaries may overlap and therefore are explicitly
non-additive; the renderer must not duplicate the state's mass into an exclusive stacked total.

Hierarchy states also remain alternatives. Mass assigned directly to a segment is scene- or
episode-level gist. Leaf mass under that segment is not added to the segment's own mass and then
called a probability; such a roll-up would count two different alignment states twice.

All six external provinces are present in the legend and textual twin:

- `Association`;
- `Commentary`;
- `SourceConsistentInference`;
- `Intrusion`;
- `Uninterpretable`; and
- `Unranked`.

For a ranked row, `Unranked` is unavailable rather than zero if it is absent from the row's state
map. For an unrankable row, the other five external states are unavailable. `Unranked` is labelled
**aligner support failure**. It is never grouped into participant intrusion, association, or
uninterpretable content.

### 4.3 Transition flow `F`

The packet contains each `FlowStep` in recall order, with exact `from` and `to` unit IDs. Every
state-pair entry is sorted by `(fromState.key, toState.key)` and linked by
`AlignRef.Transition`. Within the Cartesian product of the adjacent posterior state sets,
`FlowStep.apply` defines an absent pair as zero flow. A transition whose endpoint is absent from the
corresponding row is unavailable, not zero. The packet retains the original sparse-map keys as
audit data while applying those semantics. Because the current `HsmmResult` marginal check can
admit an explicit zero-flow key with a row-absent endpoint, the lowering checks every explicit key
before applying sparse zero semantics and refuses the whole publication packet on a mismatch.
Deleting an absent map entry is lawful sparse representation; retaining the same pair explicitly
at zero asserts endpoint membership and must pass the key-presence check.

The report shows flow from `F` itself. It does not connect row argmax states and call the result a
trajectory. The existing validation law that each step's marginals agree with its adjacent
posterior rows is part of packet admission.

The required route phenomena use existing values:

- **backward return:** source-to-source flow for which `TransitionFeatures.between` publishes the
  `Backward` feature;
- **jump:** source-to-source flow shown with the existing `LongJump` feature value; any visual
  cutoff is part of the view configuration;
- **stay:** source-to-source flow whose anchors are equal;
- **revisit:** a categorical label only under a rule frozen in the view identity before inspecting
  output. The rule declares finite, strictly positive row-support and incoming-flow thresholds. An
  anchor qualifies only when an earlier non-adjacent row meets the row threshold, at least one
  intervening row does not, the later row again meets it, and exact incoming flow to that anchor
  meets the flow threshold. Present zero never qualifies. The report preserves the exact row and
  flow masses beside the label. Without the predeclared rule, it reports the neutral numeric fact
  “later mass/flow to a previously supported anchor” and does not claim a revisit;
- **external excursion:** flow whose endpoint changes between an anchored state and a typed
  external state, or between typed external states; and
- **mode change:** flow between faithful and distorted states, with every facet retained.

The textual twin lists the exact rule and endpoints behind every derived label. A drawing may make
a return visually obvious, but geometry alone never establishes that it is backward, long, or a
revisit.

### 4.4 Viterbi path

The Viterbi vector is shown as **one maximum-a-posteriori path under this identified model**. It is
not titled “the mapping,” “what the participant recalled,” or “ground truth.” The report places it
beside, not on top of, `P` and `F`.

Turning Viterbi off must not remove posterior branches, external mass, transition flow, or any
textual fact. A Viterbi state absent from the corresponding posterior row is malformed input. The
current `HsmmResult` validator can admit that shape when the state is nominated and gate-admitted,
so the lowering independently refuses it rather than repairing, inserting, or hiding the state.

### 4.5 Evidence, support, and refusal

Each anchored cell links both evidence axes:

- the recall unit's exact transcript span; and
- the source node's exact support spans.

Only after recursive checked reconstruction succeeds does it expose the published
`CostBreakdown` audit values for that cell:

- priced terms and values;
- `missingTerms`;
- `imputedTerms` with `MissingReason`;
- `supportWeight`;
- source-chart and member-level reduction coverage;
- fidelity mode and distortion facets; and
- `Exclusion`, including unassessable or unreachable states.

The report uses the following meanings:

| State | Required rendering |
|---|---|
| measured zero | Numeric `0`, a “measured” word label, and the ordinary measured pattern |
| missing | `Missing(<reason>)`; no numeric substitute |
| imputed | The substituted numeric term plus “imputed” and its reason; never “measured” |
| excluded/refused | Typed exclusion/refusal and no placement claim |
| suppressed by view | The retained value plus the view rule that hid it and a visible remainder/count |
| failed provider or renderer | Typed failure at that stage; prior valid values remain present |
| unavailable/not applicable | An explicit state and reason; never blank space or `0` |
| ineligible | Shown only when a checked carrier establishes it |

The current `CostBreakdown` does not carry the complete term-level eligibility set. A first
implementation may therefore show cell support and the published missing/imputed/exclusion states,
but it must label term-level eligibility **unavailable in this artifact**. It may not derive
eligibility from a term's absence or from `supportWeight == 1.0`.

Checked reconstruction is necessary but not sufficient for `total`: `AlignWire.costBreakdown`
checks that the cached value is finite and nonnegative, not that the invocation configuration
produces it. Publication-grade lowering recomputes `total` from the bound cost weights, function
prior, and other complete cost settings. If it cannot, it keeps the component audit but labels the
cached total unverified.

`logLikelihood` is an audit statistic, not evidence that the alignment is true. The lowering
requires it to be finite before rendering it as measured. If it is positive or negative infinity,
the report either refuses publication or retains the otherwise valid semantic packet while showing
a typed audit refusal; it never prints the value as an ordinary successful measurement.

### 4.6 Omission and low source mass

`AlignmentMatrix.columnMass` is aggregate mass across recall units. It is not itself a probability
and it does not prove memory unavailability.

The renderer may label a source node **below the declared aligned-mass threshold** whenever the
numeric condition holds. It may use the stronger label **omission** only when all of these are
present in a checked companion record:

1. a complete, established source-node universe at one declared hierarchy level;
2. an outcome-independent eligibility rule frozen before inspecting alignment output;
3. candidate/provider coverage sufficient under that rule;
4. a declared column-mass or visitation estimand and threshold; and
5. a denominator that keeps missing, refused, and ineligible nodes out of omission without deleting
   them from the report.

If any condition is absent, the report says that omission is not established. A never-nominated
node, a provider failure, and a genuinely low-mass eligible node must not share one blank mark.
Established-empty and unestablished universe are also different outcomes.

## 5. Static artifact and accessibility contract

The canonical target is one `preview.html` that opens directly with `file://` and needs no build,
server, package installation, or network access.

Because this report contains exact recall text and evidence spans, that artifact is
**local-sensitive by default**. Filesystem-openable does not mean shareable. Semantic validity,
pseudonymization, a successful renderer, and successful delivery do not establish consent, REB
authority, licence, or disclosure permission. A separately checked disclosure/admission outcome
must authorize a shareable or publication-ready disposition, and repository fixtures used by the
court must be synthetic or already admitted under the story-text checklist.

It contains:

- semantic summary and audit/refusal status;
- the full identity record rendered in text; any bundle-manifest reference is supplementary and is
  not needed to understand the report;
- source hierarchy and evidence table;
- recall-unit and evidence table;
- complete textual tables for `P`, `F`, Viterbi, costs, and refusals;
- an optional inline-SVG visualization generated from those same values; and
- deterministic internal fragment links between graphics and their textual twins.

It contains no JavaScript, `<script>`, inline `on*` handler, remote font, remote stylesheet, image,
frame, network URL, or sibling-file dependency. CSS `url(#fragment)` and SVG references to a
same-document pattern, mask, marker, or clip path are legal positive controls. Any other `url(...)`
or resource-bearing attribute is refused.

Every graphical distinction has all three of:

1. a text label;
2. a non-colour mark such as shape, line style, fill pattern, border, or glyph; and
3. a row in the textual twin linked by a deterministic mark identifier.

Keyboard reading order follows source and recall order, not SVG paint order. SVG marks are
supplementary; the complete tables remain usable when CSS and SVG are disabled. No fact requires
hover, click, animation, or spatial comparison to discover.

The HTML is deterministic for the same semantic packet and view configuration. Ordering is by
recall ordinal, `AlignState.key`, flow-step order, state-pair keys, and source reference key.
Geometry, pagination, and shortened display labels never enter model identity.

A later authorized redacted report is a separately identified projection with its own policy and
receipt. It does not mutate, replace, or retroactively authorize the semantic packet from which it
was derived. Flipping a local-only disposition to shareable without the separate disclosure record
is a hard refusal even when every semantic and rendering court passes.

## 6. Independent outcome laws

The production boundary reports three outcomes independently:

- **semantic outcome:** whether the checked input and renderer-neutral packet are valid;
- **report outcome:** whether textual and graphical report generation succeeded; and
- **delivery outcome:** whether the complete output bundle was written and verified.

A separate **disclosure/admission outcome** records whether the exact recall-bearing artifact may
leave its local-sensitive boundary. It is an authority record, not a fourth execution-success bit:
none of semantic, report, or delivery success implies it, and a local-only disposition does not
rewrite those three outcomes as failures.

A report failure cannot rewrite semantic success as alignment failure. A delivery failure cannot
delete or replace an already computed semantic packet. Conversely, a pretty HTML file cannot turn
an audit refusal, missing identity, or malformed result into semantic success.

The renderer-failure court injects a renderer that always fails after receiving a valid packet. The
required result retains the byte-identical semantic packet, records report failure, and permits the
bundle to deliver the semantic artifact and failure record. Any implementation that returns only a
single success/failure bit fails this law.

## 7. Executable fixture courts

These courts are named before implementation. The Anna fixtures use the current deterministic
table distance, so they exercise alignment and lowering rather than claim dense semantic-provider
validation.

| Court | Required observation | Mutation killed |
|---|---|---|
| Anna complete packet | Rows appear exactly in `u0`–`u3` order; each source/recall ID and exact evidence span survives; each `FlowStep` joins its adjacent rows and its marginals match them. | Drop, reorder, or relabel a row, source anchor, evidence span, or flow endpoint. |
| Sharp atomic anchor | Anna `u2` retains more than `0.6` mass on `e5`; its discourse support-density width stays below `0.15`, the summary foil is wider, and the full row remains available. | Blur a concentrated leaf placement by rescaling, hierarchy roll-up, or display-only normalization. |
| Scene gist/compression | The summary foil's MAP source is segment `sc2`; the packet identifies it as a `Segment`, preserves its own mass, and does not replace it with leaf winners. | Lower only situations, or expand segment mass into leaves and erase gist. |
| Bimodal blend | The blended foil retains more than `0.25` mass on both `e2` and `e5`, with the undisplayed remainder reported under any branch filter. | Render only argmax or Viterbi, or renormalize the visible pair to one. |
| Role-reversal distortion | The role-reversed foil has zero faithful mass on `e5`, more than `0.5` distorted mass there, and facet `RoleReversal`; the anchor remains source recall rather than intrusion. | Merge distorted into faithful, flatten all facets to “mismatch,” or turn anchored distortion into external mass. |
| Backward transition | The Anna step from `u2` to `u3` retains more than `0.5` backward source-to-source flow and exact `F` endpoints. | Connect row MAP states, sort by source order, or infer direction from screen geometry. |
| Long-jump transition | A deterministic flow fixture contains a source-to-source pair for which `TransitionFeatures.between` publishes `LongJump`; the packet retains the exact pair and mass, while a same-row foil changes only `F` to a non-jump pair. | Omit jump marks, derive them from row winners, or decide them from drawing distance instead of the exact `F` endpoints and declared transition feature. |
| External excursion flow | Holding every `P` row fixed, the Anna-adjacent flow around Association unit `u1` retains exact anchored-to-`Association` and `Association`-to-anchored entries selected by the predeclared flow display rule, with the external province named. | Infer excursions from adjacent row argmax states, drop all external `F` entries, or collapse the typed external endpoint to “other” while row summaries still pass. |
| Viterbi row membership | A synthetic gated input substitutes a nominated and gate-admitted state that is absent as a key from its corresponding `P` row; lowering refuses the packet instead of inserting or drawing it. | Trust `HsmmResult.validated` alone, or validate only nomination/mode admission and accept a row-absent Viterbi state. |
| Explicit-zero flow membership | A synthetic `FlowStep` adds an explicit `0.0` key whose endpoint is absent from an adjacent `P` row; lowering refuses it while the positive control omits that sparse key. | Treat row absence as numeric zero during endpoint validation and admit an asserted zero-flow edge outside the row state space. |
| Nonflattering revisit | With predeclared positive row/flow thresholds, Anna-style flow qualifies only after earlier support, a below-threshold intervening row, renewed row support, and sufficient incoming flow; removing the gap or dropping either threshold removes the categorical label while exact masses remain. | Label every tiny positive tail as a revisit, let present zero qualify, or change thresholds after seeing output. |
| External association | Anna `u1` has `Association` argmax and source mass below `0.2`; all other external provinces remain distinct or explicitly unavailable. | Collapse external states into “other,” or report association as intrusion. |
| Unranked support failure | The unrankable foil has `Unranked` mass `1.0`, `Intrusion` mass `0.0`, and no fabricated specificity/localizability value. | Count provider/candidate failure as participant intrusion, uninterpretable content, or numeric zero. |
| Missing and imputed channel | A measured semantic term and a provider-abstained term may retain the same price, but the latter carries `imputedTerms(Semantic)`, lower support, and is not also `missing`; an absent optional term retains its `MissingReason`. | Drop missing/imputation metadata, count imputed weight as measured, or turn absence into a favourable zero. |
| Cost-record construction boundary | Into an otherwise valid gated result, substitute a `Mirror`-forged outer breakdown with `supportWeight = 5`, `total = -7`, `Semantic` both present and missing, an unpriced illegal imputation, and a structural term scalar that disagrees with an independently valid sealed reduction receipt. `AlignWire.costBreakdown` refuses the record before publishing any cost field, while an equivalent positive control built through that factory passes. Exact `P`/`F` survive only behind the audit refusal. | Trust `HsmmResult.validated` or outer case-class membership, validate only mode/exclusion, or copy a structural scalar and receipt into the report without checking that the receipt reduces to the scalar. |
| Omission refusal | A node with low/zero column mass but no established eligibility/universe is labelled “omission not established,” while a checked eligible low-mass foil may receive the frozen omission label. | Infer participant omission from `columnMass` alone or delete support failures from the denominator. |
| Renderer failure | A valid Anna semantic packet remains byte-identical when the static renderer fails; report failure and delivery state are separate. | Couple semantic validity to HTML success or discard valid `P`/`F` on render error. |
| Static-resource court | `preview.html` opens from the filesystem with no requests; no script/handler/external resource exists; an inline SVG hatch using `url(#hatch)` renders and has a textual label. | Ban lawful same-document patterns, permit sibling/network resources, or make colour the only distinction. |
| Disclosure authority | A synthetic/admitted fixture can receive a separately checked shareable disposition; identical valid output over local-sensitive recall remains local-only. | Flip local-only to shareable because the graph is checked, pseudonymized, rendered, or redacted without a separate disclosure/admission receipt. |
| Non-finite log likelihood | Substituting positive or negative infinity for an otherwise valid result's `logLikelihood` preserves exact `P`/`F` only behind an explicit audit refusal and never renders the value as measured. | Delete the finite check, accept only the existing `NaN` check, or display infinity as an ordinary statistic. |
| Provenance-derived view basis | Fixture, acquired-build, and human-adjudicated inputs derive their distinct existing `ViewBasis` values from matching source and authority receipts. Removing a required receipt, caller-asserting another basis, or combining acquired and adjudicated/fixture roots under the scalar basis refuses the scientific report. | Accept a free basis label, infer authority from semantic validity, or choose the first/strongest-looking basis for mixed provenance. |

Every threshold in these courts is an existing fixture assertion or a frozen view setting. A later
implementation must retain exact numeric values in the semantic packet even when the HTML presents
rounded labels.

## 8. Acceptance checklist for an implementation candidate

An implementation candidate is reviewable only when all items below have executable evidence:

- [ ] It consumes a checked recall, matching source view, gated `HsmmResult`, and checked invocation
      record; it does not accept caller-selected semantic labels as proof.
- [ ] It refuses mismatched source/view/recall/configuration/candidate identities.
- [ ] It reconstructs every outer `CostBreakdown` through `AlignWire.costBreakdown`, including
      structural scalar/receipt and coverage coherence; a failure refuses the cost audit and
      publication-grade status without discarding independently valid `P`/`F`. Reconstructing the
      already sealed nested structural records during wire decoding is defense in depth, not a
      remedy for a current external `Mirror` door.
- [ ] It recomputes cached cost totals from the complete bound configuration or marks them
      explicitly unverified; a finite/nonnegative range check alone is not derivation evidence.
- [ ] It independently refuses a Viterbi state absent from its corresponding `P` row and every
      explicit `F` key with an endpoint absent from the adjacent rows, including explicit zeroes.
- [ ] It preserves every `P` row entry and every `F` entry, including present zeroes and map-key
      presence.
- [ ] It refuses an unexplained non-excluded cost-only state instead of guessing whether it was a
      zero-mass state or outside the posterior state space.
- [ ] It never replaces `P` and `F` with Viterbi or row argmax.
- [ ] It has mutation-bearing `F` courts for backward returns, long jumps, thresholded revisits,
      and typed external excursions; holding `P` fixed cannot preserve those marks after `F` changes.
- [ ] It preserves situation versus segment anchors, faithful mode, each distortion facet, every
      external state, and `Unranked`.
- [ ] It shows exact source and recall evidence spans against their own checksums.
- [ ] It exposes published support, missingness, imputation, and exclusion; unavailable eligibility
      is explicit.
- [ ] It refuses the omission label without an established universe and eligibility record.
- [ ] It uses `measuredPosition` or a checked typed axis; it never substitutes `relativePosition`
      where absence can occur.
- [ ] It binds full provider and geometry identities when present and explicitly reports their
      absence otherwise.
- [ ] It derives `ViewBasis` from matching admitted fixture/build/adjudication provenance, refuses
      unsupported or caller-asserted authority, and refuses mixed roots under the scalar basis.
- [ ] It labels posterior values model-relative and uncalibrated.
- [ ] It freezes any categorical revisit thresholds in view identity, requires positive support
      and incoming flow with an intervening absence, and preserves exact masses.
- [ ] It refuses a non-finite `logLikelihood` as an ordinary measured statistic while preserving
      otherwise valid `P`/`F` only with an explicit audit refusal.
- [ ] It emits a deterministic, complete textual twin.
- [ ] Its canonical HTML is static, self-contained, filesystem-openable, and free of active or
      external content while permitting same-document SVG fragments.
- [ ] Its non-colour and keyboard-access courts pass.
- [ ] Semantic, report, and delivery failures remain independent.
- [ ] Exact recall output is local-sensitive by default; a shareable/publication disposition has a
      separate disclosure/admission outcome, and redaction is a separately identified projection.
- [ ] All fixture mutations in section 7 are demonstrated to fail.

## 9. Source checklist used to freeze this court

The source audit was performed at repository `HEAD`
`e584ad6d8a1b6bc5cb28ac1c406a239114f1f48a`. Symbols, rather than line numbers, are the stable
review anchors:

| Source | Contract checked |
|---|---|
| `docs/adr/0002-visualization-contract.md`, D10/D12/V-R1 | Existing recall vocabulary, textual twin, and no-new-category law |
| output summary `post-01M19TDF0CHGHYAVVR2C2XT1AW` | Role-separated bundle, independent outcomes, provenance-derived homogeneous `ViewBasis`, D3 states, static HTML/CSS law |
| `align/src/main/scala/storymodel4s/align/matrix.scala`: `ExternalState`, `AlignState`, `AlignmentRow`, `AlignmentMatrix`, `FlowStep`, `TransitionFlow` | `P`, `F`, hierarchy/mode/external states, localizability, top-k, column mass, map-key semantics |
| `align/src/main/scala/storymodel4s/align/hsmm.scala`: `TransitionFeatures`, `HsmmConfig`, `HsmmResult`, `GraphHsmm` | Backward/long-jump features, gated result proof, row/flow consistency and its row-key-presence gap, non-finite `logLikelihood` gap, config residual |
| `align/src/main/scala/storymodel4s/align/candidates.scala`: `Nomination`, `CandidateSet`, `Candidates` | Candidate provenance retained upstream but not proved by `HsmmResult` |
| `align/src/main/scala/storymodel4s/align/cost.scala`: `CostBreakdown`, sealed structural reduction records, `DefaultLocalCostModel`; `bd-01M17ZNXY6AS1CMBQJRH3JMNVX` | Measured/missing/imputed/support/exclusion fields, missing eligibility carrier, the outer `CostBreakdown` `Mirror.ProductOf` gap, and the closed nested-record doors |
| `align/src/main/scala/storymodel4s/align/wire.scala`: `ViewFingerprint`, `recallChecksum`, `AlignWire` | Derived source/aligner-input identity, the narrower recall-checksum field set, checked outer cost reconstruction with structural scalar/receipt coherence, and the cached-total verification residual |
| `align/src/main/scala/storymodel4s/align/source.scala`: `SourceNodeRef`, `NodeSummary`, `SourceView` | Exact source evidence, hierarchy, optional world order, measured-position boundary |
| `view/src/main/scala/storymodel4s/view/codex.scala`: `ViewBasis`, `ViewProvenance` | Existing basis vocabulary and the current caller-selected authority gap |
| `recall/src/main/scala/storymodel4s/recall/unit.scala`, `graph.scala`, `relations.scala` | Ordered recall evidence and typed elaboration/relations |
| `codec/README.md`; `docs/design/story-text-admission-checklist.md`; AGENTS.md fixture policy | Plain recall source at the current codec boundary; admission, consent/REB/licence, and disclosure remain separate from semantic validity and pseudonymization |
| `align/src/main/scala/storymodel4s/align/ref.scala`, `view/src/main/scala/storymodel4s/view/ref.scala` | Existing typed cell/transition reference seam |
| `align/src/test/scala/storymodel4s/align/AnnaFixture.scala`, `WorkedExampleSuite.scala`, `CostSuite.scala` | Concrete fixture expectations and mutation-bearing numerical controls |
| `embed-core/src/main/scala/storymodel4s/embed/identity.scala`, `contract.scala` | Typed provider, geometry, geometry-pair, and attempt-receipt identities when present |

This checklist establishes that no new visualization semantic is needed. It also records why a
later implementation needs the output/invocation companion record: `HsmmResult` alone cannot prove
which complete configuration or nomination/provider lineage produced it.
