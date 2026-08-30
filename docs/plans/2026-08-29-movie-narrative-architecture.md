# Movie narrative architecture and implementation plan

**Status:** Open board draft under `bd-01M18459K9QS09Y4H09HPY7BTZ`. This plan sits beneath
[ADR 0007](../adr/0007-film-source-representation.md); it does not authorize a decoder, provider,
source download, dependency, or public-API migration by itself. Board authority treats ADR 0007 as
ratified at `220cbdc` with wording repair `6530ac4`, while its file header still says `Proposed`.
ADR authority must reconcile that metadata before this plan is called final.

**Decision:** A movie is a bundle of synchronized evidence for the same narrative model used for
text. Movie support is an acquisition and evidence problem below `NarrativeGraph`, not a second
narrative ontology.

```text
immutable source bundle
  -> checked media, language, identity, and feature records
  -> typed semantic proposals with exact multimodal support
  -> deterministic resolution
  -> the existing proposition, document, story, and recall pipeline
```

The governing maxim is:

> Retain evidence early, combine interpretations late, and never let a model output acquire more
> authority merely because it combines more modalities.

## 1. Authority, prerequisites, and scope

The governing decisions recorded in ADR 0007 are:

- film does not create a sibling `FilmModel`;
- subtitles are not a proxy for the film;
- one `SourceBundle` identifies the immutable edition and every stream used in a build;
- typed evidence retains its stream and coordinate identity;
- a pinned edition has one declared primary `PresentationAxis`;
- mixed-axis arithmetic requires a checked mapping;
- vectors cannot become propositions;
- learned outputs are receipted derivations or proposals;
- the existing `PropositionChart`, deterministic resolver and compiler, `NarrativeGraph`,
  hierarchy, and recall alignment remain the semantic spine.

The compiler sub-prerequisite landed at
`e66147071ba1beeeed477aef38ae26051ae453f7`, but the real-transcript Stage 1b gate is **not**
satisfied. The current WOG vertical proves mechanical reachability; it explicitly does not answer a
research question. Before Stage B through D implementation begins, one lawfully admitted real
transcript must pass through Stage 1b, deterministic resolution, and compilation to one declared,
answered research question with exact receipts. Compiler availability alone is not that evidence.

**Q5 admission is a second, independent gate.** It must identify the exact source bytes, code,
weights, licences, gated terms, purposes, and owner-only actions. Closing Q5 does not waive the
real-transcript gate, and closing the real-transcript gate does not admit any movie asset or
component. An open repository does not establish that its weights, training data, benchmark
annotations, or media assets share the same licence.

Runtime builds are fully automatic. Humans may create gold data, calibrate claims, audit releases,
and decide access terms. They are not required to repair or complete a production story model.
`Unresolved`, `Unsupported`, and partial coverage are valid automated outcomes.

## 2. The shared architecture

```text
                              +-------------------------+
movie bytes ---------------->| SourceBundle            |
subtitles/scripts -----------| stream + edition IDs    |
experiment timing ---------->| checked axis mappings   |
                              +------------+------------+
                                           |
              +----------------------------+----------------------------+
              |                            |                            |
       source anchors               measurement sidecars        proposal-source text
      MediaAtlas persists           FeatureTracks persist       timed tracks persist
              |                            |                            |
              +----------------------------+----------------------------+
                                           |
                      semantic proposals and critic findings only
                                           |
                                 deterministic resolver
                                           |
         PropositionChart -> document identity -> NarrativeGraph / hierarchy
                                           |
                                  source-recall alignment
```

This is not a generic `Observation` supertype. The architecture preserves three different forms
of authority:

1. **Source evidence** says which immutable bytes or annotations support a claim.
2. **Measurements and features** say what an algorithm measured over a declared extent.
3. **Semantic proposals** say what an agent or model interprets those measurements to mean.

Only the deterministic resolver can create `Accepted`. Structural chart validity proves that a
chart is well formed; it does not prove that the chart describes the movie correctly.

## 3. Portable source and time contracts

The first movie implementation slice after both entry gates is portable and provider-free. `core`
owns:

| Contract | Required meaning |
|---|---|
| `SourceBundle` | Bundle and edition semantic identity; immutable stream manifests; exact checksums; primary presentation stream; checked cross-stream mappings; mutable access/rights authorization lives in a separate receipted build envelope |
| `SourceStream` | Stream semantic identity, kind, native coordinate system, extent, checksum, and derivation lineage; build authorization references the separate access disposition |
| `PresentationAxis` | A path-dependent coordinate kind, extent, ordering, normalization, and fingerprint for one declared audience-facing stream |
| `AxisIntervalSet` | Nonempty, bounded, sorted, deduplicated half-open intervals on exactly one axis |
| `EvidenceSupport` | Nonempty heterogeneous anchors without generic mixed-axis hull, overlap, or order |
| `NarrativeSourceAtlas` | Checked proposal units and source support consumed by the existing compiler |

`SurfaceAtlas` remains the exact UTF-16 text implementation. A text conformance adapter must
express the current `StorySource` and `SurfaceAtlas` without changing their semantics.

### 3.1 Native media coordinates

Native timestamps are integers interpreted through an exact rational timebase and bound to a
bundle, edition, stream, axis, origin, and rendition. A bare `Long`, floating-point seconds, frame
number, or normalized `Double` is not a scientific source coordinate.

- Every stream retains native integer PTS and optional DTS under an exact reduced rational
  timebase. Native PTS may enter the edition's audience-facing playback-PTS axis only through a
  checked track-composition receipt that binds container and edit-list semantics, origins,
  timebases, stream and edition checksums, and parser version.
- DTS remains decode-order provenance, as distinguished from PTS in the
  [FFmpeg packet contract](https://ffmpeg.org/doxygen/trunk/structAVPacket.html).
- DTS cannot become evidence or substitute for presentation PTS.
- Variable-frame-rate frame lookup uses an explicit frame-to-PTS index, never
  `frame / nominalFps`, and retains explicit duration or typed unknown duration.
- Audio sample, subtitle cue, scanner/TR, recall, and story-world coordinates remain separate.
- Equal numeric values on two editions or streams do not establish correspondence.
- A re-encode, alternate cut, repaired clock, downmix, or resample has a new stream identity.
- Active audio and subtitle rendition selection participates in source-view and result identity
  even when picture playback PTS is unchanged.

### 3.2 Mappings

A mapping is serializable data, not a Scala function field. It records source and target identities,
ordered mapping segments, gaps, tolerance, algorithm, parameters, input checksums, and its own
checksum.

Three mapping families must remain distinct:

- **Clock repair** is a checked partial monotone exact-rational transformation between clocks for
  one source.
- **Track composition** maps native PTS into edition playback PTS through ordered,
  target-disjoint mapped, empty-edit, and hold segments. Source intervals may repeat or reorder.
- **Edition correspondence** is evidential, partial, possibly many-to-many or nonmonotone, and
  remains a claim rather than a coordinate cast.

Repeated material requires occurrence identity. Otherwise two appearances of the same source
content can collapse into one false event location.

Projection is a total checked outcome, not `Option`: mapped, presentation gap, outside domain, no
correspondence, or ambiguous. Reverse projection is not assumed functional. Every accepted support
retains edition, stream, axis, occurrence, mapping receipt, and rendition scope.

### 3.3 Boundaries are typed claims

Cinematographic, coded-scene, and narrative-event boundaries are different layers on a compatible
time axis. They never share one generic boundary value.

- boundary existence and localization are separate from transition morphology and extent;
- a hard cut admits an instant, while dissolve and fade morphology admit intervals;
- visual transition rhyme is a feature relation, not transition morphology;
- shot adjacency is a derived graph edge, not evidence that a narrative event changed;
- a human-coded scene boundary records the coding act and its episode derivation; coincidence with
  a shot cut does not convert one claim into the other;
- search coverage distinguishes not examined, examined with no candidate, and a substantive
  negative-boundary proposal. An empty detector output cannot construct the negative claim;
- raw scores and calibration are claim-family and detector-head specific.

The twenty courts in ADR 0007 are incorporated as the mandatory minimum. This plan only adds to
them.

## 4. Timed language is a lattice

ASR, subtitles, closed captions, screenplay text, OCR, audio description, translations, and human
transcripts are separate immutable tracks. Each track retains:

- its own exact text atlas;
- language and track kind;
- native timing, or an explicit absence of timing;
- source or provider receipt;
- access and licence state;
- confidence and coverage.

Token and utterance correspondences are derived claims. A resolved working utterance preserves all
alternatives and never overwrites the contributing tracks.

The working utterance is itself a content-addressed resolution artifact. Its identity binds every
contributing track and atlas checksum; language and track kind; exact timing or typed absence of
timing; correspondence proposals and selected checked mappings; alternatives; resolution policy
and version; and receipt. Access authorization is bound by the build envelope, not the semantic
content fingerprint. Normalization creates another derived text view. It never rewrites source,
ASR, subtitle, script, OCR, translation, or human-transcript bytes.

Lexical correspondence and occurrence are separate claims. A deleted screenplay line may align to
subtitle wording without establishing that the line was spoken in the final cut. Timing
availability (`Timed` or `UntimedByDesign`) is distinct from processing coverage: not requested,
observed, examined with no candidate, abstained or unsupported, and failed.

This matters because:

- subtitles often condense or normalize speech;
- scripts can contain deleted or changed lines;
- translations express a different linguistic artifact;
- audio description is already a human interpretation of visible events;
- ASR can mishear or omit overlap;
- OCR can identify text that no character spoke.

After resolution, dialogue follows the existing text path:

```text
timed utterance
  -> exact clauses and text support
  -> PropositionChart candidates
  -> document references and contexts
  -> deterministic resolution and compilation
```

The checked chart keeps exact alignments to its selected text atlas. It does **not** acquire media
truth or media intervals merely by being structurally checked. The proposal or accepted-claim
envelope carries the typed text-plus-media `EvidenceSupport`. A spoken report of an unseen past
event directly supports the speech event and its embedded linguistic proposition; it does not by
itself make that past event a narrated-world fact.

## 5. Identity is a graph, not a label lookup

P0 distinguishes at least four non-convertible identity domains:

- rendition-scoped acoustic speaker cluster and overlapping speaker activity;
- picture-stream- and shot-scoped face track;
- cross-shot appearance-continuity cluster;
- narrative `EntityId` or character.

Performer identity is deferred. It is unnecessary for the first narrative vertical and adds
biometric and privacy scope.

Raw linguistic or perceptual output never carries a narrative `EntityId`. `ActiveSpeaker`, visual
continuity, vocal-character attribution, visual-character depiction, and device emission are
separate interval-local proposal relations with alternatives and abstention. They are not an
equivalence quotient, a global cluster lookup, or forced to be one-to-one.

Visibility and coverage, emission path, vocalization kind, diegesis, and audio rendition are
orthogonal coordinates. This lets the model represent combinations such as off-screen,
device-mediated, dubbed singing without laundering them through one speaker enum. Narrators,
crowds, overlap, direct speech, voice-over, nondiegetic audio, and unresolved attribution remain
explicit. `ContextKind.Speech` is created only after accepted narrative attribution; story
modality is truth status and is never reused as media modality.

Face and voice embeddings are sensitive feature sidecars. They do not enter portable core, become
character identity, or leave an authorized environment merely because they are pseudonymous.

## 6. Observation, proposal, and acceptance boundary

The authority gradient is enforced in four steps:

1. An untrusted worker returns wire outcomes for exact requested extents and declares coverage.
2. A JVM adapter validates schema, source identity, axis membership, extents, sidecar shape and
   checksum, and per-item completeness.
3. The adapter constructs a joined admitted outcome that binds the issued request, bundle, stream,
   axis, extent, output family and schema, terminal outcome, provider attempt, payload or sidecar
   digest, evidence support, and verified cache lineage. Measurements then become checked feature
   records; semantic outputs become `AgentProposal[A]` or `CriticFinding` values with typed support,
   alternatives, exact targets, and receipts.
4. The deterministic resolver produces `Accepted`, `Alternatives`, `Unresolved`, or `Rejected`.

A lawful request, call receipt, payload, support record, cache witness, or checked chart is not
sufficient by itself. Only their checked join may enter resolution. Substituting an independently
lawful call receipt or payload from another execution must refuse.

Proposal and critique are separately scheduled bounded passes. A critic names the exact proposal,
evidence, and artifact identities it examined. It may add a finding or alternative; it cannot mutate
a candidate, write a status, or accept/reject a claim. Only the resolver interprets findings.

The output contract follows epistemic role, not model family:

| Worker output | Lawful destination |
|---|---|
| Scalar, vector, categorical score | Feature sidecar with observed/missing/failed coverage |
| ASR text or visual description | Derived text or semantic proposal with exact support |
| Shot, track, interval, identity, boundary | Typed candidate with provenance and uncertainty |
| Action, speaker, event, context, causal claim | `AgentProposal` requiring deterministic resolution |
| Structurally checked local chart | Proposal value; never accepted merely by construction |

`DirectlyVisible`, `DirectlyAudible`, and `LinguisticallyExpressed` describe the mode of evidence.
They are not epistemic statuses. Cross-modal support may make an interpretation better evidenced;
it does not automatically make it true.

### 6.1 Claim-family status licensing

D1 must separate five coordinates:

1. anchor geometry and source membership;
2. evidence mode;
3. artifact relation, such as recorded by a coder or derived from an episode;
4. resolver disposition;
5. epistemic status licensed for the accepted claim family.

A coder row may be `RecordedByCoder` and `DerivedFromEpisode`; it is not thereby
`DirectlyVisible`. A human-adjudicated claim records governance history, not infallible stimulus
truth. The compiler uses a total claim-family-specific licensing policy. If no basis licenses a
stronger status, it emits the conservative truthful status or leaves the claim unresolved—never a
default. New public status vocabulary requires explicit ADR or board authority.

Late fusion creates a new derived feature or proposal with a dependency DAG and support union. It
does not mutate raw observations, erase alternatives, or upgrade an interpretation to fact.

## 7. Worker and artifact protocol

Scala owns task identity, scientific schemas, smart constructors, validation, resolution, caching,
and build receipts. GPU workers own decoding and model inference only.

The first worker integration should be a batch artifact protocol rather than an always-on service:

- canonical JSON or JSONL for sparse task and result records;
- Arrow-compatible sidecars for dense arrays;
- content-addressed media slices;
- versioned OCI images for Python, PyTorch, or JAX runtimes.

This is easier to replay on a workstation, CI runner, or cluster. A later gRPC or Arrow Flight
transport may carry the same envelopes without changing scientific types.

A task binds bundle, stream, axis, extent, admitted purpose, output schema, prompt/config and
preprocessing identities, budget, timeout/retry policy, cancellation identity, and cache inputs.
The checked build manifest additionally binds its refinement mode, source inputs, stage DAG,
selection policy, nomination scores and thresholds, planned extent universe, requested extents,
terminal outcomes, attempt receipts, and realized output digests. Every planned extent belongs to
exactly one coverage state:

- not requested;
- observed;
- examined with no candidate;
- abstained or unsupported;
- failed.

Every requested extent has exactly one non-`NotRequested` terminal result. Missing, duplicate, or
foreign-axis outcomes refuse construction; feature missingness may summarize but never replace the
attempt record.

The receipt binds the source checksums, exact model and weight revision, container/runtime,
preprocessing, configuration, schemas, attempted extent, sidecar/output digests, attempt history,
hardware where material, and verified cache lineage. A worker cannot assert cache authority,
construct a validated feature track, or write an accepted narrative node.

The request/cache key and the realized artifact digest are different identities. Downstream stages
bind admitted upstream output digests, not request keys alone. If the same admitted request produces
different output, the build branches or emits an explicit provider-drift artifact; it cannot reuse
a downstream cache entry as though the outputs were equal.

Parallel completion order is not semantic input. If distinct candidate values tie on every declared
decision factor, the truthful result is a canonical `Alternatives` set—not acceptance selected by
arrival order, lexical order, or hash. Candidate serialization uses a stable typed content identity,
and the resolver is permutation invariant over the complete proposal multiset.
Reproducibility and independence are separate: an `ExecutionFingerprint` distinguishes exact
weights, runtime, preprocessing, schema, prompt, and configuration, while an admitted
`IndependenceLineage` determines whether two results count as independent votes. Neither is
worker-asserted. Multi-provider agreement cannot use a display `(provider, model, version)` tuple or
two configurations of one underlying model as evidence of independence.

Before a media worker is admitted, the current public `FeatureTrack` construction seam must be
closed or typed as draft versus validated, and provider identity must distinguish weights,
configuration, preprocessing, and schema rather than only a display provider/model/version tuple.

## 8. Coarse-to-fine scheduling without outcome leakage

The source scheduler is a receipt-bearing DAG:

1. deterministic ingest and indexing;
2. cheap full-source scan;
3. source-only nomination of shots, dialogue exchanges, boundaries, uncertain intervals, and
   potentially important nonverbal windows;
4. bounded expensive interpretation;
5. global deterministic identity, event, context, chronology, and hierarchy resolution.

Every stage records attempted coverage and explicit missingness.

There are three legal refinement modes:

| Mode | Recall access | Scientific use |
|---|---|---|
| `SourceFrozen` | None from held-out recalls | Required for primary recall scoring, omission, and population inference |
| `DiagnosticExpansion` | May inspect one recall/query | Separate child artifact for debugging; never overwrites the frozen source or enters primary estimates |
| `CrossFittedExpansion` | Development recalls only | Refinements are frozen before disjoint subjects or films are evaluated |

Changing or deleting a held-out recall must not change the frozen source artifact hash. This is the
decisive court against circular source construction.

These modes are construction boundaries, not tags on an otherwise unrestricted record.
`SourceFrozen` inputs cannot contain direct **or transitive** lineage from held-out recalls,
benchmark queries, answers, or evaluation labels; a
`DiagnosticExpansion` child is not consumable by primary estimators; and a
`CrossFittedExpansion` binds informing and evaluation partition identities and proves them
disjoint. The complete artifact identity includes the mode, coverage partition, selection policy,
receipts, and realized outputs even when its accepted narrative graph happens to be unchanged.

Sherlock's recall-tuned `TopicHmmEvent30` channel is always a diagnostic sidecar with a feature-use
ledger, never independent source gold. In claim-blind MF2 evaluation, fact/fib text cannot affect
acquisition, feature selection, or window nomination. Lineage-taint and query-permutation courts
must catch both indirect leaks.

## 9. Fixture and corpus strategy

One corpus should not be forced to prove source integrity, model understanding, and human recall.

| Tier | Artifact | Claim it may support |
|---|---|---|
| F0 | Tiny deterministic synthetic media generated for CI | Container/stream ingest, PTS/DTS, VFR, gaps, mappings, timed text, evidence, codecs, and refusal laws only |
| F1 | Pinned open movie or excerpt | Source input for source-side construction; evaluation still requires separately admitted annotations, foils, or gold |
| F2 | Lawfully paired exact stimulus edition plus human recalls | End-to-end source-to-recall alignment; neural linkage only when scanner streams and mappings are separately admitted |

The leading F1 candidate is *Big Buck Bunny*. The Blender Foundation states that the project output
is [CC BY 3.0](https://peach.blender.org/about/) and publishes
[official downloads](https://peach.blender.org/download/). The exact file and any derived excerpt
still require a checksummed edition manifest, the prescribed attribution, and a recorded
derivation.

FilmFestival is an excellent F2 candidate, but its
[OpenNeuro deposit](https://github.com/OpenNeuroDatasets/ds004042) explicitly omits the ten film
files for copyright, and its [companion repository](https://github.com/jchenlab-jhu/filmfest) has no
licence. Sherlock remains a sparse annotation-only diagnostic under the existing
[source audit](../design/sherlock-source-representation-audit.md); its pinned
[dataset metadata](https://github.com/OpenNeuroDatasets/ds001132/blob/master/dataset_description.json)
has a blank licence, its workbook repository has no licence, and the BBC episode is separate.

[MF2](https://arxiv.org/abs/2506.06275) may supply source-side narrative facts, causal/order
questions, and matched false alternatives. Its
[released benchmark](https://github.com/deep-spin/MF2) is noncommercial CC-BY-NC-SA, while
underlying film items can have separate rights. It remains optional until an owner-approved
admission record establishes the intended use.

No source asset, benchmark annotations, CI fixture, code, or model weights inherit admission from
another item in the same research paper or repository.

### 9.1 Current Q5 disposition

The primary-source audit is provisional and **does not close B0**:

| Item | Current disposition |
|---|---|
| Project-authored F0 media | Admit after generator, manifest, hashes, and independent text-admission review |
| *Big Buck Bunny* | Admit as F1 after exact file/excerpt hash, CC BY 3.0 attribution, and derivation record; it is not a dialogue fixture |
| *Sintel* | Conditional second F1 dialogue excerpt under CC BY 3.0; any committed transcript is separately admitted |
| FilmFestival | Hold as an exact F2 pair until the viewed film editions and participant-content/REB basis are admitted |
| Sherlock | Hold pending written artifact-level licence clarification; never vendor episode bytes |
| Brain Treebank | External-only/hold for film-derived material and unlicensed analysis code |
| MF2 | Exclude from defaults; owner decision required for NC-SA research-only use; independently verify any underlying film and do not inherit subtitle rights |

No exact source-film plus human-recall pair is presently admitted. ADR Stage B therefore cannot
close while the Sherlock disposition remains unresolved unless ADR authority ratifies a
replacement; the scientific film-recall vertical also remains blocked on an exact paired edition.

The clean first acquisition court, after all ratified entry gates, is project-authored F0 media and
then a 20–40 second checksummed *Big Buck Bunny* excerpt processed by an exact preferably
LGPL-only [FFmpeg build](https://ffmpeg.org/legal.html) and weightless
[PySceneDetect](https://github.com/Breakthrough/PySceneDetect) detectors. It proves ingest,
timebase, gaps, shot proposals, and replay—not ASR, speaker identity, semantics, narrative quality,
or recall alignment.

Provider candidates remain component-scoped: Whisper plus an exact Montreal Forced Aligner model
may be conditionally admitted; WhisperX remains on hold as a composite; pyannote requires the owner
to accept gated terms; learned shot and active-speaker checkpoints remain on hold pending explicit
weight permission; ByteTrack is admissible only as weightless association; and Qwen/CLAP-family
weights are conditional provider-side artifacts with pinned preprocessing and no proposition
authority. B0 still needs dependency, container, codec, tokenizer, quantizer, privacy, retention,
credential, and service-term closure for anything selected.

### 9.2 Human and committed-text rules

F2 participant content remains caller-supplied and external. No recall bytes, prose, excerpts,
provider logs, or derived text enter git without an owner-recorded REB and redistribution basis.
Synthetic or researcher-authored timed text is labelled and passes the story-text admission
checklist with independent review. Source-bearing tests commit only aggregate outputs permitted by
the admission record. A human annotation is evidence about a coding act and may be gold after
adjudication; it does not inherit direct stimulus authority merely because a person wrote it.

## 10. Validation and adversarial courts

Release gates are claim-family specific. A system does not pass because one aggregate metric is
high.

### 10.1 Structural gates

- every anchor names an existing admitted stream and lies within its native extent;
- all interval and mapping laws pass, including explicit gaps;
- mixed-axis operations refuse without a checked mapping;
- accepted claims have eligible support and an explicit claim-family status licence;
- evidence mode, artifact relation, resolver disposition, and epistemic status remain independent;
- typed boundary layers pass all ADR 0007 courts and cannot entail one another;
- vectors and track continuity cannot construct propositions or identity;
- joined critic findings target exact proposals and cannot mutate or accept them;
- source builds are byte-identical under the SourceFrozen leakage court;
- transitive recall/query/answer/gold lineage is rejected from SourceFrozen artifacts;
- exact cache replay and provider drift are reported separately;
- changing a planned extent outcome or nomination policy changes the complete artifact identity;
- equal request keys with unequal realized outputs cannot reuse downstream cache entries;
- two distinct equal-support, equal-calibration candidate values resolve to the same canonically
  ordered `Alternatives` set under every completion-order permutation;
- only a present `NarrativeCompilation.validated` can construct the alignment source view; draft,
  invalid, partial, and gap-bearing compilations remain useful artifacts but refuse alignment;
- no human correction is required by the runtime.

### 10.2 Acquisition gates

- transcript wording and timing, stratified by acoustic condition and overlap;
- diarization, active speaker, and character assignment reported separately;
- automatically accepted speaker assignments meet a high-precision gate with abstention allowed;
- direct visible/audible observations are evaluated separately from inference;
- action models must defeat same-object opposite-direction/order, role-swap, and across-cut identity
  foils rather than rely only on object- and scene-heavy action benchmarks;
- provider outputs report coverage, unsupported cases, and failure, not only accuracy on attempted
  successes.
- source assets, evaluation annotations, counterfactual foils, and human gold are admitted and
  versioned separately; an available movie does not supply its own gold.

### 10.3 Binding courts

At minimum, the suite contains:

- PTS substituted with DTS;
- VFR frame time computed from nominal FPS;
- edit-list empty segments, holds, repeated source ranges, and ambiguous reverse projection;
- identical speaker or track labels scoped to different editions or renditions;
- changing only the selected dub changes source-view and attribution identity;
- two editions with equal numeric timestamps;
- repeated or reordered content without occurrence identity;
- shot/reverse-shot showing the listener;
- listener-face permutations with fixed audio cannot change vocal attribution;
- off-screen, voice-over, device-mediated, overlapping, dubbed, crowd, and sung speech;
- subtitle wording that differs from audio;
- a screenplay line absent from the final cut;
- a lawful request/call/payload join with only the call receipt substituted from another execution;
- equal-support rival proposals delivered in opposite completion order;
- the same request returning different provider outputs under one cache key;
- a diagnostic child forged as `SourceFrozen` or a cross-fitted split with overlapping cohorts;
- a recall-tuned Sherlock feature presented as independent source gold;
- MF2 query/fib text permuted while source acquisition and window selection must remain unchanged;
- a coder row reclassified as directly visible merely because its timestamp matches the episode;
- a draft or invalid compilation passed to source-to-recall alignment;
- a silent but narratively important action;
- an important sound with no visible source;
- dialogue describing an unshown past event;
- flashback and montage with discontinuous support;
- correct evidence bound to the wrong nearby time, face, speaker, modality, or character;
- plausible but unestablished causation;
- cross-edition fusion using matching labels and normalized time.

Each court needs a mutation witness that makes the wrong result pass when the intended guard is
removed.

### 10.4 Scientific comparison

On held-out films and recalls, compare:

1. transcript only;
2. visual only;
3. audio only;
4. full audiovisual late fusion;
5. one-shot omni-model baseline.

The comparison is a falsifiable scientific question, not a favorable-outcome gate. Every result
names its estimand, weighting population, support, missingness handling, uncertainty, and comparable
coverage and budget. Audiovisual superiority may be claimed only when the evidence supports it; a
null or negative result is a successful complete answer. Recall evaluation reports target
retrieval, source interval, hierarchy level, role and polarity, sensory detail, ordering,
compression, omission, and external mass separately.

A re-encode has a different source identity. “Stable under harmless re-encoding” is an invariance
benchmark after an explicit content-alignment claim, never equality of source hashes.

## 11. StoryAtlas contract

StoryAtlas consumes renderer-neutral packets from validated storymodel4s artifacts. It does not
infer scientific mappings.

Required packets and state are:

1. **Source Observation Packet:** checked bundle/axis identity, lanes, feature fingerprints,
   admitted access disposition, and evidence anchors; no media bytes or locators.
2. **Narrative Projection Packet:** compiled nodes, relations, alternatives, and exact support.
3. **Alignment Packet:** matrix-first recall rows versus source columns, including external mass
   and uncertainty.
4. **Coordination State:** semantic selection and focus plus an axis-bound playback cursor,
   separate from epistemic horizon.
5. **Evidence Navigation:** seeking only through checked mappings; no mixed-support hull.
6. **Saved-state receipt:** bundle, edition, axis, stream, alignment, model, view, layout, and
   required access-envelope policy identities.

Playback cursor, epistemic horizon, discourse position, story-world time, recall position, and
screen coordinates remain different values even when the interface aligns them visually. Playing
a frame cannot reveal a claim to a reader-horizon model, and moving an epistemic horizon cannot
seek media.

A source-only diagnostic UI may exercise these packets after the source contracts and admission
gate exist. It must be visibly labelled diagnostic and cannot be cited as a production recall
workspace. Production playback/recall views wait for the end-to-end film vertical.

This repository owns only the renderer-neutral packet and codec contract. The sibling StoryAtlas
repository consumes a SHA-pinned storymodel4s artifact under a separately authorized bead;
storymodel4s never depends on StoryAtlas.

### 11.1 Runtime playback is not a scientific packet

A production client needs a non-scientific playback binding that resolves exact bundle, edition,
rendition, stream, checksum, and presentation-axis identity to an opaque runtime media handle plus
current seek/range/codec capabilities. Signed URLs, bearer tokens, cookies, filesystem paths, and
expiring credentials never enter `ViewSpec`, scene data, or saved-state identity.

Refreshing credentials for the same admitted bytes does not change the scientific artifact.
Resolving to a different edition, rendition, stream, or checksum refuses. Expired or unavailable
access yields typed `PlaybackUnavailable` while claims, support, marks, selection, and epistemic
horizon remain inspectable and byte-identical; the client cannot helpfully substitute another cut.

Required courts are: same locator/different edition refuses; credential rotation preserves story
identity; serialized views contain no secrets or local locators; playback failure does not become
missing evidence; and an available alternate cut cannot replace an unavailable pinned edition.
StoryAtlas owns browser handle acquisition and media-element control. storymodel4s may define only
the immutable identity/refusal seam if separately authorized.

## 12. Staged implementation

Only the first open stage should receive an implementation bead at a time. Later stages are a
dependency plan, not concurrent authorization.

| Stage | Deliverable | Exit condition |
|---|---|---|
| P0 | Existing compiler seam | **Satisfied** at `e661470`; this proves mechanical reachability only |
| P1 | Real-transcript Stage 1b vertical | One admitted real transcript reaches an answered research question with exact receipts; **outstanding and blocks movie implementation** |
| B0 | ADR Stage B admission: exact Sherlock records/rights, two-run timebase repair, aggregate-only fixtures, and the F0/F1/F2 plus component ledger | Exact artifact dispositions and owner actions; no participant prose or episode video enters git; unresolved Sherlock requires clarification or an explicit ADR replacement; documentation-only work may proceed separately but cannot unlock C1 by itself |
| C1 | ADR Stage C portable contracts: `SourceBundle`, stream/axis/coordinate/interval-set, `EvidenceSupport`, typed boundary layers, and checked atlas plus text conformance | Begins only after P1 and B0 close; applicable construction/refusal courts and text regressions pass on JVM/JS/Native; no provider or decoder dependency |
| D0 | ADR Stage D read-only Sherlock atlas adapter | Exact pinned records, two-run repair, diagnostic feature-use ledger, aggregate-only fixtures, and no episode video, participant prose, decoder, or model dependency |
| D1 | Stage D cross-module seam migration | Compiler atlas annotations, model envelope, alignment seams, claim-family status licensing, media feature targets/support/restriction, codec/version migration, unforgeable validated tracks, joined proposals/findings, strong execution and independence identities, request/output separation, and permutation-invariant resolution all pass |
| E0 | ADR Stage E minimal film vertical proof | With only narrowly admitted dependencies, all ADR 0007 courts plus project-authored F0 courts pass; one exact lawful film source slice and real recall produce a `NarrativeCompilation`; `validated` is present and one predeclared research question is answered reproducibly regardless of sign |
| G1 | General acquisition worker plus timed-language lattice | Exact replay, checked coverage partition, call/payload/support join, provider-drift refusal, and text/media alternatives with critic findings pass |
| G2 | Identity and perceptual proposal tracks | Speaker/face/character/rendition abstention, factorial binding courts, sensitive sidecars, and direct/inferred separation pass |
| G3 | Expanded admitted source construction | Separately admitted movie, annotations, foils, and gold evaluate source-side action, sound, identity, context, chronology, hierarchy, and causality without claim/query leakage |
| G4 | Exact paired recall comparison | Predeclared transcript-only, visual-only, audio-only, full-fusion, and one-shot contrasts complete with common estimands, support, coverage, budgets, uncertainty, and any positive, null, or negative result reported honestly |
| V1 | storymodel4s renderer-neutral view packets/codecs | Packet laws, saved-state refusals, synchronized evidence navigation, and optional scanner mappings pass in this repository |
| V2 | Sibling StoryAtlas consumer | Separately authorized storyatlas4s bead consumes one SHA-pinned V1 artifact; no reverse dependency |

Unless ADR 0007 is explicitly amended, B0 remains Sherlock admission, D0 remains the narrow
read-only Sherlock adapter, and E0 precedes broader workers and film-specific visualization. The
post-E worker sequence should prefer cheap deterministic or high-coverage acquisition before
expensive interpretation, but no tool tier carries an epistemic status. Model admission follows
measured capability, licence, calibration, and local courts; named models are candidates, not
defaults.

## 13. Decision register

| Decision | Consensus direction |
|---|---|
| D1 acquisition boundary | Portable source/evidence contracts in `core`; measurements in `features`; proposals/resolution in `acquire`; heavy runtimes in later JVM adapters |
| D2 time | Native PTS, DTS provenance, exact rational timebase/origin/rendition; checked track composition into edition playback PTS; clock repair and edition correspondence distinct |
| D3 language | Immutable timed-text lattice; resolved utterances are receipted artifacts retaining alternatives; chart text alignment stays distinct from claim-level media support |
| D4 identity | Acoustic activity, face track, appearance continuity, and narrative entity remain non-convertible; orthogonal rendition/emission/vocalization/diegesis; performer deferred |
| D5 observations/charts | Four-step authority gradient with a checked request/call/payload/support join; proposal and critic findings remain separate; claim-family status licence required |
| D6 workers | Policy-minted tasks, fail-closed JVM adapter, complete outcomes/receipts, separate execution and independence identity, batch artifacts first |
| D7 scheduling | SourceFrozen primary build; diagnostic and cross-fitted refinement separated by construction boundary and lineage; complete coverage and realized outputs enter artifact identity |
| D8 first vertical | Ratified P1/B0 -> portable C1 -> narrow Sherlock D0/D1 -> minimal E0 film/real-recall question; F0/F1/F2 are evidence tiers, not permission to skip stages |
| D9 validation | Structural, acquisition, binding, narrative, recall, and reproducibility gates; counterfactual and mutation courts |
| D10 visualization | storymodel4s owns renderer-neutral packets; sibling StoryAtlas consumes a pinned artifact; playback, epistemic, narrative, recall, and screen coordinates never collapse |

## 14. Immediate next action

The next executable **library vertical** is P1, not C1 or a movie adapter. A separately assigned B0
admission audit may proceed as documentation-only design work: no source fetch, terms acceptance,
dependency, module, API, or code edit follows from it. Its dispositions are admitted for a declared
use, owner action required, or excluded.

C1 begins only after both P1 and B0 close. It is deliberately small: portable construction
boundaries, text conformance, and refusal laws. It does not include a worker, feature-target
expansion, `StoryModel` envelope migration, or resolver change. That separation lets the project
test its source contract before multiplying it across providers.
