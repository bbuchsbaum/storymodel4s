# What the pilot preserves

The destination is full working StoryModel4s capability in idiomatic Python, not a permanently
reduced subset. Small slices sequence the work; they do not cut scope. Every capability must
be ported, improved, or excluded with evidence that it is broken, redundant or stale.
Deferred functionality remains owed; passing pilot tests are not proof of full parity.

The owner's instruction is to preserve the best of StoryModel and StoryAtlas. Existing
behavior earns a place by its scientific or practical value, not by its implementation
language. A different implementation must retain the behavior or explicitly defer its
replacement. A deferral is not permission to remove the old capability.

The following rows are acceptance obligations, not claims that the pilot already implements
them. The source files and their inspected revisions are listed in [references.json](references.json).
During S1.0, bind each Stage 1 row to a named test or browser case and literal expected result.

| Asset | Preserve or reuse | Stage 1 witness |
|---|---|---|
| Exact text/media evidence and source identities | Preserve UTF-16 span meaning, discontinuous support, half-open ends, source/edition binding, exact times and deterministic identity. Use standard hashing rather than porting the SHA-256 implementation. | Non-BMP and repeated-text roundtrip; wrong-source and surrogate-splitting refusal; stable hashes; exact boundary behavior. |
| Rich source and recall accounts | Preserve entities, occurrence identity, details, contexts, part-whole and typed temporal/causal relations. Source construction remains independent of recall. | Shared participant across distinct events; several details per event; context-isolated speech; positive and foreign-endpoint cases for each bounded relation family. |
| Separate reference meanings and outcomes | Preserve single, joint, broad and alternative reference, narrative versus referent interpretation, external destinations, unresolved reference and processing statuses. | Broad reference gives no automatic constituent credit; joint and alternative differ; failed processing never becomes external content. |
| Detail, fidelity and analysis separation | Carry forward ADR 0030 rather than inventing a new scoring ontology. Address, content kind, discourse, assessment status and fidelity are distinct. | Fidelity-only change leaves address-based internal/external counts unchanged; distinct overlapping claims survive; policy changes retain the same account. |
| Missingness and uncertainty semantics | Preserve raw scores, normalized/transport mass, model posterior, calibrated probability and self-report as distinct meanings where supported. Keep unknown values explicit. | Categorical fixture has no invented posterior; unknown confidence stays unavailable; no silent normalization or thresholding. Numerical algorithms are deferred, not flattened into categorical data. |
| Measurement and temporal projection | Retain native support, declared target/grain, aggregation, allocation, eligibility, denominators and provenance. | Recall-indexed, correspondence and source-indexed tables; point onset does not become duration; held level differs from distributed amount; CLI/API agreement. |
| Complete accounting and bundle readback | Reuse the Python reader, but port the Scala reader's external input binding and reconstruction checks before trusting it. Preserve what the provider stated separately from the admitted judgment. | Missing unit, swapped slots, changed source/run/participant and coordinated row/judgment tampering detected against independently pinned inputs. |
| Recorded execution and recovery | Preserve preflight, bounded calls, record-before-parse, profile-bound cache identity, exclusive writer, uncertain-attempt handling and atomic publication. | Existing 429/500/malformed-reply/budget/resume/replay scenario with exact call counts; zero dispatch after invalid preflight; no duplicate call after valid recorded completion. |
| Independent scientific checks | Reuse fixtures, scorer definitions and mutation cases; keep a genuinely independent expected-value path. | Mutation kills a named assertion; comparison does not call the same production validator on both sides and label that independence. |
| Atlas linked inspection | Retain recall/source selection, exact supports, alternatives, coverage, calibration status and provenance. Rebuild the narrow interaction in React with keyboard operation and a text alternative to visual encodings. | Open a real output bundle, select a detail by keyboard, inspect its source/context and a nonlocalizable outcome. Text and graphics describe the same selection. No inference in the renderer. |
| Atlas saved investigations and export | Preserve artifact identity checks, validated selection/view state, scientific value integrity, explicit view settings and producer-granted export permissions. | Save/reopen reproduces state; replacement artifact refuses; viewport/crop changes do not change exported scientific values. Denied export refuses before constructing a download; unsupported grants fail closed. |
| Study infrastructure and data custody | Reuse adapters, manifests, model pins, recording identities, evaluation splits and local acquisition paths when their workflow is taken on. | Stage 1 uses admitted synthetic data only; manifests record compatibility. No study rerun or moved/deleted data is required. |

## Policies carried forward

[ADR 0030](../../docs/adr/0030-detail-account-and-derived-analysis.md) is accepted direction;
the port must not simplify away its distinctions:

- The initial source-row coverage rule credits definite single/joint **supported** bindings.
  Other grades remain inspectable; gist-detail error earns no default credit.
- Coverage precedence is present, then not-assessable, then uncertain, then absent. Broad
  and alternative-only evidence does not become definite presence. Absent means no qualifying
  evidence in the account, not proven psychological omission.
- The address/counting policy partitions atoms into internal, external, unresolved or excluded,
  with its own denominator. Fidelity changes alone cannot change that partition.
- Overlap is permitted for distinct claims. Claim identity and the counting policy determine
  credit. Missing fidelity does not erase independently admitted content.
- Self-reported phenomenology is separate from inferred content and source fidelity.

The [measurement contract](../../docs/plans/2026-11-16-detail-mapping-plan.md) supplies the
three analysis views and declared projections. It prescribes neither an fMRI model nor one
fixed scalar summary. Keep that freedom in the Python API.

## Source map

| Concern | Existing implementation/specification |
|---|---|
| Coordinates and deterministic identity | [span.scala](../../core/src/main/scala/storymodel4s/core/span.scala), [hash.scala](../../core/src/main/scala/storymodel4s/core/hash.scala), [ordinary transcript guide](../../docs/guide/ordinary-transcripts.md) |
| Replay and study admission | [StudyExecution.scala](../../pipeline/src/main/scala/storymodel4s/pipeline/agentmapping/StudyExecution.scala), [StudyExecutionSuite.scala](../../pipeline/src/test/scala/storymodel4s/pipeline/agentmapping/StudyExecutionSuite.scala) |
| Bundle semantics | [StudyBundle.scala](../../pipeline/src/main/scala/storymodel4s/pipeline/agentmapping/StudyBundle.scala), [StudyBundleSuite.scala](../../pipeline/src/test/scala/storymodel4s/pipeline/agentmapping/StudyBundleSuite.scala), [Python reader](../../tools/agentmapping/study_bundle.py), [Python tests](../../tools/agentmapping/test_study_bundle.py) |
| Provider boundary | [TextExecutionSuite.scala](../../provider-agent/src/test/scala/storymodel4s/provider/agent/TextExecutionSuite.scala), [RecordingsSuite.scala](../../provider-agent/src/test/scala/storymodel4s/provider/agent/RecordingsSuite.scala) |
| Admitted policy example | [Synthetic worked example](../../docs/design/detail-coding/synthetic-worked-example.md) |
| Atlas import/restore | [WorkspaceImport.scala](../../../storyatlas4s/shell/src/main/scala/storyatlas4s/shell/WorkspaceImport.scala), [WorkspaceController.scala](../../../storyatlas4s/shell/src/main/scala/storyatlas4s/shell/WorkspaceController.scala), [WorkspaceImportSuite.scala](../../../storyatlas4s/shell/src/test/scala/storyatlas4s/shell/WorkspaceImportSuite.scala) |
| Atlas inspection/export | [WorkspaceInspector.scala](../../../storyatlas4s/app/src/main/scala/storyatlas4s/app/WorkspaceInspector.scala), [WorkspaceExport.scala](../../../storyatlas4s/shell/src/main/scala/storyatlas4s/shell/WorkspaceExport.scala), [WorkspaceExportSuite.scala](../../../storyatlas4s/shell/src/test/scala/storyatlas4s/shell/WorkspaceExportSuite.scala) |

## Retained outside the first implementation

The existing Scala code, specialized algorithms, scientific tests and historical artifacts
remain available throughout the pilot. In particular, defer reimplementing the full graph-HSMM,
AMR interop, WL kernels, all embedding backends, media acquisition, the entire interview API,
all temporal/trajectory views and Atlas's full rendering/layout stack.

Each later adoption records the user workflow it enables, its compatibility contract, reusable
assets and independent checks. Preserve graph extensibility, sparse representations and
scientific meaning now; implement additional algorithms when the workflow needs them.

No preservation claim is established by a line-count reduction or by copying a type name.
Judge the retained behavior at its input, saved artifact and researcher-visible output.

## P2.1 source construction contract

Bead `bd-01M7KKCY94B9NWXAQSFM15EQ66` supplies an anchored-row construction path rather than
translating a finished Scala compiler. The bounded Scala lookup found hand-built graph fixtures
and admission primitives, not a working observation-to-rich-graph caller. This does not exclude
any graph capability from the later phases.

| Retained behavior | Scala source / caller / test evidence | Python path and independent witness |
|---|---|---|
| Proposal and accepted claim are distinct; missing, unresolved and rejected outcomes survive. | `acquire/resolve.scala` `Resolution`, `ClaimFamily`, `Resolver`, `CandidateLedger`; `ResolveSuite`, `D1aAcquireSupportSuite`. | `compile_source` and per-row `SourceRowResolution`; explicit row-status expected vector and atomic sibling-survival tests in `test_source.py`. This extractive profile does not claim Scala's critic/agreement/calibration policy. |
| Situations carry contexts; endpoints, roles, identities and evidence are checked. | `story/nodes.scala`, `StoryValidator` in `story/validate.scala`, `StoryModel.draft`; `story/Gens.scala` and `fixtures/wog/WarOfTheGhostsModel.scala` construct graph values directly. | Shared `_admission.admit_graph` plus ordinary `Account` admission. Source and recall context-promotion refusals, foreign row/role/quote guards and independent exact graph expectations. |
| Exact source edition and anchored observations remain independent of recall. | `core/textsource.scala` text-source/atlas construction; supplied atlases do not invent missing units. | `prepare_source` with exact UTF-16 rows; saved source digest reused by two distinct recall sessions; surrogate, overlap and foreign-side refusal. |
| Source evidence packets do not establish graph truth. | `pipeline/agentmapping/FilmOutline.scala`, `FilmOutlineSuite`; `AgentBundleSource.dictionary` joins annotations/media/targets and omits rows with missing joins. | Source result retains every requested row, including unsupported, failed and missing work. No silent row omission. `AgentAnswers.Field`/`AgentBundleSuite` are precedents for explicit processing states, not an equivalent source compiler. |

Compatibility: `storymodel.source-{request,proposal,result,recording}/v1` are new profiles;
the output uses unchanged Account v3. Existing extraction schemas, legacy Account v1/v2/v3
rules and accepted extraction graph identities remain unchanged. Public extraction proposal
imports are retained after moving shared implementation into a private module. Existing
recorded execution is reused without a second provider engine.

Independent checks: hand-stated expected source predicates, roles, UTF-16 supports and row
outcomes; JSON/account readback; two-session source reuse; source and recall attribution-guard
mutations; offline CLI/API agreement; installed example and schema drift checks. These prove
engineering behavior on admitted diagnostics, not extraction accuracy or scientific benefit.

Limitations still owed: state/place/time/playback evidence, relations, discontinuous proposal
quotes, cross-row coreference, scene/episode grouping, source construction diagnostics in the
inspector, semantic proposal evaluation and operational source adapters. Source completeness
is a receipt in `SourceResult`; exporting Account alone intentionally does not assert it.

## P2.2 first relation construction slice

Bead `bd-01M7KKF5KXSN6V0MA8KRSF7XF5`: `admit_relations` binds raw typed proposals to an exact
account, validates the complete edge union atomically, and exposes `relation_table` for an
executed notebook/CLI consumer. `verify_relations` recomputes the receipt. No existing source
or recall identity changes, and no extraction/source prompt schema changes.

Scala evidence: `story/relations.scala` distinguishes canonical Allen temporal relations,
causal kinds, reference kinds and entity relations; narrative containment lives separately in
`story/hierarchy.scala`. `StoryValidator` checks endpoints and context/interval constraints.
`ValidatorSuite` exercises temporal inverses, context scope and state targets; `ReviewFixesSuite`
exercises interval composition and membership cycles. `story/consistency.scala` separates
structural admission from narrative consistency and requires a cited cue for surface-explicit
causal claims. These are richer than the initial Account relation contract.

The [bounded relation guide](relations.md#bounded-compatibility-contract) records deliberate
narrowing: event-only `part_of` does not replace entity membership or narrative hierarchy;
`before` does not implement full Allen reasoning; the current same-context causal DAG does not
reproduce Scala's warning-based cross-context causal semantics. Those working capabilities stay
owed, together with epistemic status and entailed/hypothesized relation evidence. They are not
excluded by passing the bounded event-relation tests.

Independent witnesses: explicit endpoint/context/evidence tables, all existing four kinds on
source and recall, exact discontinuous quotes, atomic cycle/duplicate-identity refusals, preserved input
IDs, foreign recording and changed result refusal, offline CLI/API agreement and installed token
graph example. No causal/temporal truth or scientific validity is inferred from quoted text.

## P2.3 first text identity slice

Bead `bd-01M7KKFRRB3PRM7GK9QE9F6M4Z`: `resolve_text_identity` consumes account-bound,
quoted form annotations; `identity_role_table` verifies and consumes its sidecar while retaining
original event, role, mention and context IDs. Open mentions receive no canonical referent.

Scala source evidence: `document/coreference.scala` `EntityIdentity.resolve` and
`EntityIdentitySuite` implement introducing-label/type, unique preceding number-compatible
antecedents and a second holder pass. `mentionform.scala` infers a nominal only after receiving
a checked entity-like chart concept; Python labels alone do not supply that proof. `coref.scala`
derives canonical identity from member sets. ADR 0012 records why nearest-antecedent selection
and singleton referents for unresolved pronouns were rejected. ADRs 0024–0028 distinguish
licensed cross-modal identity, source construction, speech promotion and recall assessment.

Compatibility/improvement: retain local Python entity occurrences and put canonical text classes
in a versioned sidecar, rather than changing existing Account/recording schemas. Explicit form
and optional entity-type annotations replace unavailable checked-chart metadata. No automatic
nominal fallback or invented shared unknown type; missing earlier metadata blocks manufactured
uniqueness. Source and recall documents resolve separately. Exact supports establish discourse
order conservatively, including overlap refusal, instead of relying on ID/array order.

Checks use independently stated expected membership and open outcomes: repeated introducing
labels versus repeated pronouns, zero/one/several antecedents, unknown/missing input, no future
resolution, Unicode supports, document separation, unchanged contexts/IDs, source class stability
after pairing, forgery refusal and a saved installed CLI/role-table consumer. These are engineering
fixtures, not evidence of grammatical accuracy or empirical coreference performance.

Remaining capabilities: chart/quantity-based number, form acquisition, accepted context holders
and the full two-pass speech rule, cross-modal sufficient/distinctness licences, ambiguous source
identity classes, canonical graph projection and richer study consumers. The current identity
sidecar does not replace those working Scala paths; they remain explicit P2/P5 obligations.

## P3.1 first recorded direct mapping slice

Bead `bd-01M7KQ3B417J2ZP2165WSC3PJH`: [unit-only mapping](direct-mapping.md) adds a recorded,
graph-free row/scene route that consumes existing details and produces checked reference and
hypothesis-conditional fidelity. Existing bindings, addresses, inventories and graph IDs remain
unchanged. The same three analysis views consume its output.

Source evidence: Scala `pipeline/agentmapping/AgentPrompts.scala` renders all recall and global
target indices; `tools/sherlock_mapper/verify_claims.py` implements the graded assessor and uses
neighbor context. Python's separate versioned condition removes that recall-order information,
uses local aliases and exact quotes, and accounts for missing or invalid detail responses instead
of silently dropping them. `align/claimassessment.scala` is the richer fixed-population receipt
reference and remains owed, including conflicted support/counterevidence and reviewed absence.

Checks distinguish single/joint/broad/alternative/external, narrative/referent slots, conditional
evidence attribution, pending fidelity, all retained grades, invalid sibling isolation, discontinuous
support gaps, exact raw replay, changed-input refusal, collection/order invariance and three-view
readback. They establish deterministic admission and input conditions, not model quality.

Still owed: recurring-scene semantic validation, embedding channels, event/episode targets,
the fixed-question claim receipt and full P3 exit demonstration. This first route does not close P3.

## P2.4 first state and circumstance slice

Bead `bd-01M7KQ3022CTAA6M5AH1ZVCESA`: [v4 rich construction](rich-accounts.md) preserves states,
location roles and literal time/manner circumstances through independent recorded source/recall
construction, complete row receipts, pairing and a situation-table consumer. Shared validation
also permits state endpoints in the current relation and detail contracts. Legacy wire records
and prompts stay stable; old consumers reject v4 until explicitly upgraded.

Scala source: `story/nodes.scala` distinguishes events/states, polarity/modality and context-scoped
situations. Location is an entity type rather than a separate place node. `story/relations.scala`
models time/manner circumstances as supported phrases, not numeric clocks. Python preserves
those distinctions in this bounded profile; full typed entities and event qualifiers remain owed.

Evidence includes frozen pre-refactor legacy schema/request/prompt/result hashes, source reuse
across two recalls, exact state/role/context/circumstance refusals, recorded raw readback and a
consumer of every added collection. Recall inventories remain not-attempted because this graph
constructor does not extract details. This is not full P2, nor full Scala `TypedSupport` parity:
playback, richer contexts/relations/hierarchy, rich recall details and consumer integration remain.

## P2.5 rich detail-to-mapping integration

Bead `bd-01M7KSBXC2J91W0FNS5XS7K960`: the [recorded rich workflow](rich-detail-workflow.md)
admits expressed recall details into existing v4 situations, then consumes them through direct
mapping and the same analysis policies. Explicit inventory states distinguish completed zero,
missing, failed and valid partial work. Address/discourse proposals stay separate from reference
and fidelity; source situations cannot own recall details. Discontinuous text retains visible gaps.

This reuses `Detail`, `Inventory`, ADR 0030 policy and the earlier P3.1 resolver instead of adding
another estimator. V2 mapping envelopes retain v4 collections; frozen v1 schemas, requests,
prompts and outputs remain identical. Two recalls reuse one unchanged source in an installed-shape
example, with state-linked detail readback and all three views. The detail acquisition prompt is
snapshot-bound, so only the later mapping step has the unit-only input condition.

No existing details or completed inventories are silently replaced. Revision workflows, rich
grouping/summaries, remaining graph families, full structured alignment and the rest of P3–P6
remain owed. A connected bounded workflow is not full parity or a scientific accuracy result.

## P4.1 supplied-transition sequence inference

Bead `bd-01M7KVBD5K6101NP5DG2P2QK1M`: [sparse sequence inference](sequence-inference.md)
ports the numerical forward/backward, adjacent-flow and Viterbi path from `GraphHsmm.run`
in `align/hsmm.scala`. It consumes explicitly supplied per-unit states, finite costs and sparse
stochastic transition distributions. `align/matrix.scala` supplies the faithful/distorted/
external distinctions; `align/facets.scala` supplies the ten distortion facets. Input checking
does not assert that an anchor belongs to a scientific account or has passed `LocalEvidence`.

The three-unit seven-state `StructuralWeightSuite` oracle enumerates all 343 paths independently
of dynamic programming. Additional direct-path cases cover varying layers, unreachable states,
zero edges, numeric ties and wide cost cancellation. The installed example saves exact inputs
and results; CLI readback recomputes the complete result. Checked posterior/flow marginals and
source-state identity do not alone prove the inference result, hence input-bound verification.

Python improves cancellation handling with Decimal log messages and centered binary64
transcendental summation. A small versioned numerical tie tolerance and a digest fallback for
ambiguous rendered state keys are explicit differences from Scala's raw float/key comparison.
Old categorical and mapping artifacts are unchanged. Scientific posterior calibration and
the value of graph priors are not established by this engineering port.

Still owed: graph-feature transition construction, local-evidence and mode gates, refinement,
reconstruction/decision policies, richer alignment methods and feature/measurement consumers.
This kernel preserves the numerical path-weight model, not full Graph-HSMM workflow parity.

## Rich relation consumer integration

Bead `bd-01M7KYDXPJ4ZE61RWENP3GTWYG` connects v4 accounts to `admit_relations`,
`verify_relations`, `relation_table` and their existing CLI commands. The shared account validator
already checks both events and states; this slice makes them constructible and inspectable
through the same atomic admission engine. Explicit v2 proposal/result envelopes retain every
rich collection. Legacy v1 schema and output hashes are frozen before the extension.

Scala reference: `story/nodes.scala` defines both event and state situation nodes;
`story/validate.scala` checks situation endpoints for temporal, causal and reference edges.
The independent Python witnesses cover each bounded kind on source/recall and mixed/state
endpoints, foreign context/evidence refusals, union cycles, processing status, copied-object
tampering and exact account preservation. The installed rich relation example starts from
construction proposals and compares CLI/API readback. It is an engineering control.

This does not implement Scala's full temporal algebra, causal/reference subfamilies,
state-change edges, entity relations or `NarrativeHierarchy`. Pilot situation-to-situation
`part_of` is explicitly distinct from situation/segment membership under a narrative segment.
Identity and structured candidate consumers still need their own rich-account integration.

## Rich identity consumer integration

Bead `bd-01M7KZ12DNTKHFN1KSV5PNDKSG` extends `resolve_text_identity`, `verify_identity`,
`identity_role_table` and the existing CLI commands to complete v4 accounts. State participants
join event participants in context placement; rich role rows retain situation ID/kind, original
context, mention, role evidence and identity decision. No graph objects are rewritten.
The existing annotation/result schemas remain valid because they bind the full account digest
and address mentions, not event-only containers. Legacy schemas and output hashes are frozen.

Independent witnesses distinguish state-only root, attributed, missing and mixed placements;
the latter includes a root event and attributed state sharing a mention. First/second-person
mentions stay open with the appropriate missingness reason. Unique third-person resolution,
full-rich-account binding, forged-object refusal, nonempty detail/mapping preservation and
installed CLI role readback have executable controls. These extend the existing ADR 0012
bounded text-identity path; automatic acquisition, quantity, accepted holders, cross-modal
identity and canonical graph projection remain owed. Structured candidates are the next rich
consumer integration.

## Rich sparse candidate integration

Bead `bd-01M7KZENYZ218MH8RKSC8QDPYY` extends `align_account` and `verify_alignment` to
rich situation/detail graphs through one sparse engine. See the [rich candidate contract](alignment.md).
Same-kind predicate/role retrieval precedes the budget; the result exposes both declared source
universes. Qualifier and circumstance evidence is inspectable but does not decide reference or
fidelity. Old policy/result schemas and deterministic outputs are frozen before the extension.

The retained Scala distinction is consequential: `align/cost.scala` documents contradiction
while retaining an anchor under distorted mode, and ADR 0030 separates reference and fidelity.
The existing Python role-conflict veto remains a narrower heuristic. This integration does not
claim to port all Scala local evidence, mode gates or candidate methods.

Discriminating witnesses cover state-linked details, same-kind budget limits, wrong-kind
distractors, matching/differing/unknown qualifiers, alternatives and same-row ambiguity,
deterministic repeated-label circumstance evidence, prior assessments and complete graph
preservation. The installed example constructs one source and two recalls, admits details,
localizes both while exposing their polarity difference, and leaves fidelity pending in analysis.
The broader recurring-scene correspondence, semantic channels and full P3/P4 exits remain owed.
