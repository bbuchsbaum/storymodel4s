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
