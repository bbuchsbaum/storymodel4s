# ADR 0022: mapping producers, evidence packets and uncertainty

Status: accepted architecture direction, 2026-09-23. Implementation remains open.
Decision: Fray conversation 75, proposal 712, confirmed at 713/714/716/717;
the owner requested codification. Implementation coordination remains in the
[delivery plan](../refactor/PLAN.md) and existing Mote tickets below.

## Decision and scope

A complete mapper may be an LLM, structured inference engine, human import or
ensemble. Supporting that choice does not depend on demonstrating that an LLM
wins a benchmark. Preserve two extension points:

1. A complete mapping producer returns outcomes, including categorical choices
   without scores, with their evidence and declared interpretation.
2. A local evidence provider returns admissible scores or typed unavailability
   for consumption by an inference engine.

Both join one checked result/exchange family. Reuse existing modules, source
identities, evidence anchors, outcome accounting and scientific measure types.
Do not fabricate an `HsmmResult`, numeric `DecisionBasis` or executed-stage
authority to admit a direct categorical result. Structural admission, declared
policy compatibility, execution evidence and empirical validity are separate.

This supplements [ADR 0019](0019-mapping-measurement-policy.md) and the
[recall-to-encoding workflow](../plans/2026-09-22-recall-encoding-workflow.md).
It does not change release priorities or the requirement for the current facade
to publish reference and reconstruction. A future complete producer cannot gain
reference authority merely by being plugged into that facade.

## One evidence model, recorded selection and rendering

Use a content-addressed packet bound to the exact source/edition, recall
inventory where applicable, target universe and evidence profile. The packet
reuses checked source anchors and evidence artifacts; it is not another story
ontology. A reusable encoding package exists independently of a particular
recall. Bind task-specific selection to that package at execution.

The prompt rendering and local scorer projection consume this same evidence
model. Each records its own selection/rendering identity, exposed item IDs,
ordering, transformations, truncation, candidate subset, context and parameters.
A shared packet digest does not mean that two producers saw the same material.
Execution receipts bind the actual submitted payload; caller assertions alone
remain asserted provenance. Receipts establish what was supplied to a provider,
not which tokens or frames influenced its internal reasoning.

Keep four facts separately inspectable:

| Fact | Required distinction |
| --- | --- |
| Available artifacts | What exists or can be acquired, versus absent, not requested, refused or failed acquisition |
| Observed coverage | Which frames, intervals or audio samples an extraction actually observed, with sampling policy and density |
| Selected/rendered evidence | Which items and representations were supplied to this particular mapper or scorer |
| Semantic support | What an attributed observation supports, contradicts or leaves unresolved |

`SourceRepresentation` physical support coverage does not establish channel
availability or semantic completeness. Retain channel and region granularity:
human annotations, captions, ASR, visual detections and direct audiovisual input
may coexist with different coverage and authority. Derived items retain their
upstream ancestry; two descriptions of the same frames are not presumed
independent. No mention or detection is not evidence of absence by default.

Prefer encoding-only acquisition that can be reused before recall is seen.
Recall-conditioned retrieval or video inspection remains possible with a
separately declared selection policy and receipts. Changing that policy creates
a different analysis condition. Requested but unavailable evidence must produce
an explicit capability outcome or a separately named fallback, never a silent
replacement of the requested condition.

## Mapping outcomes, protocols and epistemic limits

Retain one accounted outcome per input observation and per attempted run. Keep
genuine external content, unresolved target, insufficient evidence, candidate
search truncation, valid abstention, exclusion and technical failure distinct.
An LLM's "none of these" cannot establish source-external content when it saw
only a shortlist. Top-k selection must retain its universe, ordering/selection
policy, measure semantics and omitted mass when known; unknown mass stays
unavailable. Neither ranking nor a categorical choice requires invented weights.

A run set records a declared protocol: model/provider versions, decoding
parameters, evidence/selection/rendering identities, prompts, candidate orders,
sample IDs, attempt/retry accounting and response/replay artifacts. Retain each
model, sample and perturbation stratum. A declared weighted mixture may add an
aggregate without replacing constituent results or presuming independence.
Recorded calls support exact replay; a seed or temperature zero alone does not
guarantee a fresh identical response.

Repeated outputs supply empirical response frequencies under that protocol.
They are not automatically model posteriors or calibrated correctness
probabilities. Calibration needs its own model and validation evidence. Publish
the denominator and conditioning for every frequency. Valid abstentions retain
their count/rate; technical failures retain their own accounting and do not
become event mass. Retries cannot silently increase a model's voting weight or
discard failed attempts. Fixed-population evaluation still accounts for all
observations, including unsuccessful processing.

Opaque shuffled candidate IDs and ordering perturbations can test positional
sensitivity. They cannot certify absence of chronology in descriptions or a
model's learned knowledge. Recorded exposure may disqualify strict reference;
its apparent absence does not prove eligibility. Within-clip dynamics and global
source-order preferences are different information. Preserve the existing
declared-policy assessment and empirical recovery obligations.

## Time and visualization

Keep target ambiguity, a reference extending over multiple targets, within-target
localization and recall/encoding/scanner clock uncertainty separate. Alternatives
"A or B" differ from a reference to "A and B"; inclusion measures need not sum
to one. A confident event choice may have no temporal allocation at all.

Where a producer supplies a lawful temporal allocation, the existing workflow's
temporal query contract carries its measure, support, assumptions and provenance.
Without one, return support-only information and typed unavailable precision;
do not invent a point estimate or uniform distribution over an interval. A
scene-first localization strategy is optional, not a universal mapper constraint.
Text character/ordinal axes do not become seconds without a presentation mapping.
Sherlock's cartoon/video onset and scanner offsets remain independently receipted
clock transformations; this ADR supplies no additional clock evidence.

StoryModel owns checked scientific projections and exchange. StoryAtlas uses
the same quantities as the CLI and notebook readers, exposing target alternatives,
extent/localization, per-channel coverage and between-run disagreement separately.
Selection, zoom and top-k display do not renormalize published measures or turn
missingness into zero. Existing codecs must reject unsupported new semantics;
incompatible contract changes need an explicit version and migration, not reuse
of the old schema tag.

## Acceptance witnesses and existing implementation ownership

The rows below are acceptance specifications, not implemented tests or new
release blockers. Each implementation slice must supply its relevant executable
witnesses, exact revision, artifacts and independent review or falsifier evidence.
Existing ticket scope and scheduling remain authoritative.

| ID | Discriminating witness | Existing work |
| --- | --- | --- |
| MP1 | Admit a cited categorical mapping without numbers or an HSMM object; reject foreign unit/target/evidence IDs and authority escalation. Roundtrip preserves the categorical basis. | Facade/result binding: `bd-01M2TADC4VKSDZ2S9SXETH2MYM`; lead + codex-temporal |
| MP2 | Same packet with different selected frames, ordering or truncation produces distinct rendering bindings; replay refuses mismatched packet/payload. Caller-written receipts cannot assert executed authority. | Facade + shared evidence `bd-01M2TACM78289S4TECE91GT5K2`; lead/codex-temporal + claude-release |
| MP3 | Full physical scene support with only two observed frames and no audio retains sparse visual coverage and unavailable audio. Missing annotations, extraction failure and an explicit negative observation remain distinct. | Video source `bd-01M35JG7DXD7NSEE4WVEKT3CSF`, claude-p1; offline source `bd-01M35PB1H5PDD55YVKR4TQ8M66`, codex-temporal |
| MP4 | A shortlisted "none" remains unresolved/search-limited; a separately supported external reference remains external. Top-k preserves omitted or unavailable mass. | Facade + existing mapping-record/exchange contracts |
| MP5 | Ten scheduled attempts yield five A, two B, one valid abstention and two failures: publish all ten outcomes and explicit denominators. Frequencies over eight valid responses differ from rates over ten attempts. Neither is labeled calibrated correctness. Preserve retry history and per-model strata. | Run envelope on facade; experimental runner `bd-01M2TA6ZHJGYPCYQBAN15PFZ0D` and runtime `bd-01M2TA5VFE5X7E4SP1AM4HJ530` when scheduled |
| MP6 | Shuffling IDs does not automatically certify reference eligibility; explicit chronology exposure is retained. Source-only and recall-conditioned acquisition remain distinguishable. | Shared evidence + facade policy assessment |
| MP7 | All responses choose a scene supported on [100,190) seconds: target agreement is available, a particular second and within-scene density are unavailable without additional localization. Distinguish A-or-B from A-and-B. | Temporal queries `bd-01M3549Q5W5KQFSY3ZH81FARM0`; localization `bd-01M354ETV6PTK1W0V9RDCW7RN6` |
| MP8 | CLI/independent reader/StoryAtlas consume one artifact and retain identical quantities and missingness after filtering, zoom and save/reopen. Unsupported semantics refuse explicitly. | Exchange `bd-01M2WVHF4B5DAXJYY4W91VK4WV`; StoryAtlas temporal views `bd-01M354JNKNKJ15N4T5MGQTRSET` |

The first concrete implementation proposal belongs to the existing facade/result
binding work: lawful categorical outcomes plus packet/selection/rendering binding,
demonstrated with offline synthetic/imported output. Registered scoring and video
coverage remain separately owned producers of the same evidence contract.
Provider execution and ensemble experiments follow their existing tickets when
scheduled; design support does not claim that those integrations are complete.

Current code makes this a real extension: `UnitOutcome.computed` in
`align/.../mapping.scala` requires a numeric decision basis;
`DerivationBinding.of` in `align/.../mappingbinding.scala` accepts an `HsmmResult`.
`StageReceipt`/`ProviderIdentity` already describe inputs and providers, but do
not by themselves prove execution. The [G1 plan](../plans/2026-09-21-g1-mapping-records-plan.md)
explicitly defers the run envelope to the facade. Preserve that historical
implementation record while extending the construction boundary; do not rewrite
old G1 acceptance as if the generalized producer already existed.

## Rejected alternatives

Reject an LLM-specific parallel result schema, forcing complete mapper outputs
through the local-score API, and fabricating HSMM results to reuse constructors.
Reject independent prompt/scorer evidence models, availability booleans that
erase regional coverage, self-certified execution/reference authority, silent
ensemble pooling, and treating agreement as calibration or temporal precision.

The existing [mapper comparison plan](../plans/2026-09-18-best-recall-to-video-mapper.md)
still owns LLM-versus-structured comparisons: freeze observation units, evidence
conditions, target grain and evaluation denominators; measure shortlist candidate
recall for retrieval-plus-LLM systems. This ADR neither establishes a winning
method nor makes a benchmark victory a prerequisite for the producer contract.


### 2026-10-09 — ordinary-file preparation

The study preparation boundary lives in `pipeline` and reuses the checked source, recall graph,
word inventory and timing constructors. Its versioned wrapper records participant/session,
raw-transcript/parser-qualified observation identity, exact source-artifact edition, and the
existing canonical graph/timing payloads. Decoding reconstructs the inventory and compares the
whole artifact. Word identity excludes segmentation and timing, so correcting those does not
rename the observation's words. Source preparation is independent of recalls.

The offline recall profile names the existing heuristic RecallSegmenter and lexical token
projection. It does not assert validated language analysis. A timing CSV declares participant,
session, lexical word, optional onset and optional end; decimal seconds remain exact rationals.
Missing/onset-only/interval observations remain distinct. Recording/scanner identity stays
unestablished. The CSV parser is the same strict quoting-correct parser used by RecallTimingIntake.
Input paths resolve under the study directory; external symlinks and special files refuse.

Reject filename-derived participant identity, timing-dependent word IDs, inferred durations,
reconstruction of a source from its recalls, and automatic hosted/model fallback. The preparation
wrapper is a checked-input artifact, not a second mapping estimator or a workspace schema.

The ordinary timed-annotation adapter reuses the portable `TimedSegment`/`MediaLocus`
contract moved from `embed-bench`; the historical benchmark builder keeps aliases for
compatibility. A checked `TimedSourceView` binds declared part bundles and their axes,
preserves point observations and disjoint intervals, and leaves world order unknown.
The rejected alternative is importing the benchmark builder or inferring a movie clock
from annotation endpoints. The prepared artifact stores original CSV plus explicit
part declarations and adapter version, then replays checked construction on read; it
is not a new normalized Segmentation/SegmentLink interchange schema.

The typed JVM facade depends directly on `align`, `corpus-intake`, and `embed-onnx`
(and the existing media replay adapter). It accepts an actual registered ONNX or
caption execution envelope and invokes both mapping producers from that envelope's
originating evidence. The rejected alternative pairs arbitrary runs with caller-supplied
receipts. Portable projections derive only their executed inference and decision stages;
upstream authority remains in the checked JVM run envelope. An explicit execution
context is required to decode such a derived projection, and every field must match
its regenerated canonical record.

Ordinary annotation-only parts use the existing `SourceBundle.annotationTable` and
`AnnotationTimeline`, with adapter-derived checksums. Users supply no checksum or
internal identifier. Treating annotation coverage as a `FilmEdition` is rejected;
actual media editions remain the separate checked media-intake path.

The ordinary annotation adapter uses explicit `onTimeAxis` coordinate/point/interval factories
for both declared annotation timelines and edition playback. Existing `on` factories remain
edition-only. Axis identity/kind and exact integer extents remain checked; codecs and temporal
support retain annotation clocks. Rejected alternative: fabricate a film edition so ordinary
annotation ticks pass an edition-only constructor.

### 2026-10-10 paired exchange adoption

The public mapping facade publishes `mapping-exchange-pair/v1` over two unchanged
`mapping-exchange/v0.1` members. The pair is constructed from one checked `MappingRun.Execution`;
its execution, originating evidence, source, inventory and member profiles remain bound.
The JVM run/batch envelopes retain provider registration and every participant outcome, including
failed attempts without a mapping. Completion manifests are written last and resume verifies
exact bytes. Rejected alternative: a second set of mapping tables or caller-assembled pairs,
which would duplicate semantics or permit unrelated records to imply common evidence.
The independent Python consumer checks the wire and relational joins; it does not certify
execution from untrusted self-described bytes or replace contextual Scala admission.

### 2026-10-10 recall disclosure provenance

Recall preparation retains a closed, text-bound `RecallDisclosure.Input`: local-only,
checksum-admitted public-corpus replay, or an actual `embed.PseudonymizedText` witness.
The public-corpus adapter executes its pinned CSV-to-text replay; a generic caller label
cannot mint that basis. A fixed project-synthetic control supports executable examples.
The existing embed dependency supplies the pseudonymization boundary; no key provider or
re-identification map enters preparation or the workspace.

Ordinary private text may be prepared and checkpointed locally. Returning its text for
workspace or batch exchange publication requires an admitted basis scoped to the local workspace and exact
text equality. Receipt descriptions preserve identities and detector-relative provenance;
they do not recreate witnesses, establish consent, or authorize hosted/Git disclosure.
Contextual prepared-recall decoding needs the retained witness to recover its authority.
Rejected alternative: infer permission from an approved machine, a caller boolean, or a
serialized receipt. Disclosure basis participates in attempt/publication identity while
semantic observation and word identity remain tied to the actual recall text.

### 2026-10-10 ordinary-source workspace extension

The canonical workspace archive gains a versioned ordinary-source role alongside the
existing semantic-model variant. Text packages and annotation sources remain their actual
kinds; lack of a narrative model is an explicit capability. Portable annotation decoding
replays the existing deterministic adapter, with identical CSV and exact-tick rules; the JVM
entry delegates to that codec. Rejected alternative: synthesize a narrative model, clock,
or fixture source to satisfy the existing semantic-model role.

The new consumer projection preserves original mapping bytes, claimed record digests,
policy declarations and receipts as imported inspection data. It does not manufacture a
`MappingRun.Execution` or promote serialized derived-stage claims to live authority.
Both members must join the same source and recall inventory; their declared universes may
differ. The existing narrative workspace variant remains supported. The ordinary-file
producer uses the retained disclosure basis before emitting recall text and supplies the
same archive to StoryAtlas's real input decoder.

## Admitted-story input boundary (2026-10-10)

The ordinary-file producer accepts `admitted-story/v1` through `AdmittedStoryInput.load`,
`admitted-text` study input, and `textSourcePrepare --admitted`. A checked result binds the
separate provenance description to a body-only text package using the exact UTF-8 body hash.
The existing text-source artifact retains descriptive header fields and document hash as source
metadata; it carries no new publication or consent authority. The admitted Boas file is the
text authority and its Scala fixture must match its body bytes.

Rejected alternatives: caller-specific header stripping (unverifiable boundaries), treating
metadata as story prose (false segmentation), and a new corpus/artifact framework (the existing
checked text exchange already retains metadata). Plain public intake refuses recognized
admission headers. Supplied-atlas and admitted modes are separate explicit CLI choices.

## Concrete public facade diagnostics (2026-10-10)

`MappingPipeline.analyze`, `inspect` and `exportResults` adapt ordinary files through the existing checked
preparation, paired execution and exchange. `FacadeFailure` unifies only errors crossing these
entry points. Typed I/O diagnostics retain operation, local path and exception class; default log
JSON contains the path digest and no exception message. `MappingExchangeBuild` carries these
I/O diagnostics while retaining its existing scientific refusals. `storyBuild` reserves exit 3
for a completed invalid draft and validates the actual written model before reporting validity.

Rejected alternatives: a universal refusal supertype across modules (no concrete consumer need),
echoing parser/exception messages (can disclose input text), and treating successful execution as
validation or requested-capability readiness (different outcomes). Existing mapping status 3 for
partial batches and 4 for missing capabilities remain distinct and documented.
