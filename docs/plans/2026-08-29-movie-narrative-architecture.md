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
| `SourceBundle` | Bundle and edition semantic identity; immutable stream manifests; exact checksums; one audience-facing edition playback axis plus designated authority tracks; checked cross-stream mappings; mutable access/rights authorization lives in a separate receipted build envelope |
| `SourceStream` | Stream semantic identity, kind, native coordinate system, extent, checksum, and derivation lineage; build authorization references the separate access disposition |
| `PresentationAxis` | A path-dependent coordinate kind, extent, ordering, normalization, and fingerprint for one audience-facing edition timeline; it is not an elementary picture, audio, or subtitle stream clock |
| `AxisIntervalSet` | Nonempty, bounded, sorted, deduplicated half-open intervals on exactly one axis |
| `EvidenceSupport` | Nonempty heterogeneous anchors without generic mixed-axis hull, overlap, or order |
| `NarrativeSourceAtlas` | Checked proposal units and source support consumed by the existing compiler |

`SurfaceAtlas` remains the exact UTF-16 text implementation. A text conformance adapter must
express the current `StorySource` and `SurfaceAtlas` without changing their semantics.

The current text stack also contains `AudioSpan`, `TranscriptTurn.audio`, and
`TranscriptAtlas.turnAtAudio`. `AudioSpan` is a legacy text-first overlay in nonnegative integral
milliseconds; it has no bundle, edition, stream, axis, rational timebase, origin, epoch, occurrence,
or rendition identity, and it permits an explicit empty `[t,t)` span. C1 must preserve that API's
text, turn, and half-open lookup semantics, but it must not alias `AudioSpan` to a native media
coordinate or promote it to media `EvidenceSupport`. Promotion requires an explicit checked binding
to a named local axis with exact `1/1000` scale, followed by a checked mapping before edition
playback use. Absent timing, an explicit legacy-empty span, unknown packet duration, and coordinate
or mapping refusal remain distinct outcomes.

### 3.1 Native media coordinates

At the ingest boundary, PTS and DTS are either **present signed integer ticks** or **missing**. A
sentinel such as FFmpeg's `AV_NOPTS_VALUE` cannot inhabit a coordinate type. Present ticks are
interpreted through an exact rational timebase and bound to a bundle, edition, stream, axis,
origin, clock epoch, and rendition. A bare `Long`, floating-point seconds, frame number, normalized
`Double`, or missing-value sentinel is not a scientific source coordinate.

Timestamp classification is operation- and provenance-sensitive. The raw value `INT64_MIN` in an
FFmpeg packet PTS or DTS field denotes `AV_NOPTS_VALUE` and must become typed missingness before any
coordinate construction. The same raw value returned by `av_rescale_rnd` because a requested result
is not representable denotes typed arithmetic refusal. `AV_ROUND_PASS_MINMAX` may preserve a packet
sentinel through a library operation, but it cannot launder that sentinel into a present coordinate.
No value-only classifier may decide among missingness, a present coordinate, and arithmetic refusal.

Provenance is itself derived evidence, not a caller-supplied discriminator. C1 owns two deliberately
different portable operations. The pure exact rescaler receives the exact input coordinate, source
and target rational scales, target axis, rounding and endpoint policy and computes its own result or
refusal. The packet-field normalizer may consume only an explicitly synthetic, fixture-scoped packet
witness and selects PTS or DTS from that witness. Its checked outcomes remain fixture scoped and
cannot become runtime-observed media evidence or enter a production `NarrativeSourceAtlas`.

Any record claiming to have been emitted by a decoder remains unchecked through C1, D0, and D1,
even when every field, checksum, fingerprint and derived identity is internally consistent. Only
the separately authorized E0 decoder integration may mint a runtime-observed packet/extractor
witness through an admitted adapter-owned invocation/result join.
That join binds an orchestrator-issued invocation, the admitted bundle, edition, stream and input
checksum, the adapter build and configuration fingerprint, the exact raw packet result and digest,
and the terminal execution receipt. A generic codec rehydrates such a record only as draft;
promotion requires the E0 adapter verifier to find the issued invocation and exact result in the
append-only build journal. The adapter's issuance capability is not a serializable caller-writable
tag.

Content identity and observation authority are distinct. Packet-witness, extractor/build and
rescale-operation identities are recomputed from the complete canonical preimage of the thing each
describes rather than accepted as labels. Recomputation catches identity mismatch and receipt
substitution; it does not prove that an extractor ran. A caller-authored whole payload plus a
perfectly self-consistent receipt therefore remains draft unless the E0 issuance join succeeds.
No public checked constructor may accept a pair such as `(rawTick, provenanceTag)` or `(rawTick,
outcomeKind)`.

The design must enumerate every public or package-visible construction and factory path. Generic
or caller-accessible constructors and factories—including ordinary or unsafe-named `apply` methods,
`copy`, companion `fromProduct`, `summon[Mirror.ProductOf[T]].fromProduct`, and codec or reflection
rehydration—may not mint these checked classifications, identities, or runtime-observation
authority. Only the private door reached by the fixture packet normalizer, pure exact rescaler, or
E0 adapter-owned invocation/result join may mint the corresponding scoped result.

- Every present PTS or DTS retains its signed native tick under an exact reduced rational timebase;
  PTS and DTS missingness are retained independently. Present native PTS may enter the edition's
  audience-facing playback axis only through a checked track-composition receipt that binds
  container and edit-list semantics, origins, timebases, stream and edition checksums, and parser
  version.
- DTS remains decode-order provenance, as distinguished from PTS in the
  [FFmpeg packet contract](https://ffmpeg.org/doxygen/trunk/structAVPacket.html).
- DTS cannot become evidence or substitute for presentation PTS.
- After actual demuxer or format conversion, a packet with both PTS and DTS present must satisfy
  `PTS >= DTS`. A violation is a typed source-contract refusal that preserves the original fields
  and their provenance; ingest may not swap, clamp, relabel, reorder, or otherwise repair them.
- Variable-frame-rate frame lookup uses an explicit frame-to-PTS index, never
  `frame / nominalFps`. Duration is either known and strictly positive or typed unknown; a
  packet duration of zero cannot construct an empty interval, instant, or measured zero.
- Negative ticks and nonzero origins are lawful. Wrap width, unwrapping, reset, splice, and
  discontinuity evidence are receipted when exposed by the source. Equal raw ticks in different
  epochs are different occurrences, and clock repair is monotone only within a declared epoch.
  Stream start time and wrap width come from the
  [FFmpeg stream contract](https://ffmpeg.org/doxygen/trunk/structAVStream.html) when available.
- Audio sample, subtitle cue, scanner/TR, recall, and story-world coordinates remain separate.
- Equal numeric values on two editions or streams do not establish correspondence.
- A re-encode, alternate cut, repaired clock, downmix, or resample has a new stream identity.
- Active audio and subtitle rendition selection participates in source-view and result identity
  even when picture playback PTS is unchanged.

### 3.2 Mappings

A mapping is serializable data, not a Scala function field. Three identities remain distinct:

1. source and target axis identities and their directed roles;
2. the canonical mapping relation, determined by mapping family, directed source/target roles,
   normalized exact segments, gaps, occurrences, explicit epoch or discontinuity seams, and endpoint
   semantics;
3. the derivation receipt, containing algorithm, parameters, input checksums, residuals, parser or
   fitter version, and provenance.

Coordinate compatibility depends on the directed axes, mapping family, and canonical relation. Audit
and build identity retain the derivation receipt. Splitting one exact affine segment into two
contiguous collinear restrictions of the same exact affine function does not change relation identity
after canonical coalescing only when mapping family and directed source/target roles are equal,
occurrence identity is equal, no gap, hold, epoch, or discontinuity seam intervenes, and half-open
endpoint semantics are compatible. Changing a mapping family, directed role, gap, hold, explicit
epoch or discontinuity seam, occurrence, slope, intercept, or endpoint does.
A tolerance is a fitted-relation admission bound, separate from localization uncertainty; it never
defines coordinate equality, deduplication, interval membership, axis identity, or correspondence.

Three mapping families must remain distinct:

- **Clock repair** is a checked partial monotone exact-rational transformation between clocks for
  one source.
- **Track composition** maps native PTS into the edition playback axis through ordered,
  target-disjoint mapped, empty-edit, and hold segments. Source intervals may repeat or reorder.
- **Edition correspondence** is evidential, partial, possibly many-to-many or nonmonotone, and
  remains a claim rather than a coordinate cast.

Repeated material requires occurrence identity. Otherwise two appearances of the same source
content can collapse into one false event location.

Projection is a total checked outcome, not `Option`: mapped exact-rational, mapped quantized or
enclosing, presentation gap, outside domain, no correspondence, ambiguous, or refused because the
requested arithmetic or quantization cannot be represented lawfully. Refusal is not missingness,
outside-domain, or absent correspondence. Reverse projection is not assumed functional. Every
accepted support retains edition, stream, axis, occurrence, mapping receipt, and rendition scope.

Exact-rational projection remains exact-rational. If a caller requests an integral target lattice,
the result is a typed quantized or enclosing coordinate with declared rounding policy and residual
or error bound; the rounded integer is never relabelled exact. Arithmetic is overflow-safe or
refuses. Half-open interval projection declares endpoint rounding and may not invent coverage. This
makes the rounding choice required by the
[FFmpeg rational-rescale contract](https://ffmpeg.org/doxygen/trunk/group__lavu__math.html) part of
the checked scientific operation rather than a hidden implementation default.

Exact round-trip equality is required only on an exact bijective segment. Holds, repeated or
reordered ranges, quantization, and edition correspondence return every occurrence-scoped preimage,
a bounded preimage extent or set, or typed ambiguity; they never select the first match. Mapping
composition preserves every intervening gap and cannot bridge it from mapped endpoints alone.

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
licence. Sherlock is the canonical external F2 video-recall target under the existing
[source audit](../design/sherlock-source-representation-audit.md) and owner decision
`post-01M1CN0CHHMYSA93R7AZMPZQB2`. Actual episode video files remain local, external to Git, and
non-redistributable. Media hashes and metadata are shareable; annotations, recall transcripts and
exports, aliases, transforms and receipts, graphs, features, reports, model outputs, and other
non-video derivatives may be committed and shared after their ordinary provenance and story-text
admission checks. The public-source records do not by themselves establish exact local-artifact or
experimental-edition equality.

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
| Sherlock | Canonical external F2 target; raw episode video bytes are external/non-redistributable; non-video artifacts are eligible for ordinary provenance and story-text admission; exact edition, transform, alias, coordinate, semantic, and anti-circularity gates remain open |
| Brain Treebank | External-only/hold for film-derived material and unlicensed analysis code |
| MF2 | Exclude from defaults; owner decision required for NC-SA research-only use; independently verify any underlying film and do not inherit subtitle rights |

The Sherlock storage/sharing disposition is resolved, but no exact source-film plus human-recall
pair is yet scientifically admitted. ADR Stage B now progresses through exact edition binding,
annotation and recall transforms, the 17-source alias map, typed coordinates, artifact-specific
story-text admission, semantic authority, and anti-circularity rather than a new Sherlock licence,
consent, REB, participant-prose, or aggregate-only hold.

The clean first acquisition court, after all ratified entry gates, is project-authored F0 media and
then a 20–40 second checksummed *Big Buck Bunny* excerpt processed by an exact preferably
LGPL-only [FFmpeg build](https://ffmpeg.org/legal.html) and a learned-checkpoint-free classical
[PySceneDetect](https://github.com/Breakthrough/PySceneDetect) `ContentDetector` recipe. It proves ingest,
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

For F2 corpora without a more specific owner disposition, participant content remains
caller-supplied and external under Constitution IX. Sherlock follows the artifact-specific owner
decision above: annotations, recall transcripts and exports, aliases, and derived data may enter
Git and be shared after ordinary provenance and story-text admission, without a new
Sherlock-specific licence, consent, REB, participant-prose, or aggregate-only hold. Synthetic or
researcher-authored timed text is labelled and passes the same checklist with independent review.
A human annotation is evidence about a coding act and may be gold after adjudication; it does not
inherit direct stimulus authority merely because a person wrote it.

## 10. Validation and adversarial courts

Release gates are claim-family specific. A system does not pass because one aggregate metric is
high.

### 10.1 Structural gates

- every anchor names an existing admitted stream and lies within its native extent;
- missing PTS or DTS cannot be constructed as a sentinel coordinate, and unknown duration cannot
  become an empty interval, instant, or measured zero;
- equal raw `INT64_MIN` values from a packet field and an unrepresentable rescale result are
  classified by the fixture-scoped packet normalizer and pure exact rescaler as missing timestamp
  and arithmetic refusal respectively; a caller-supplied provenance or outcome tag, a value-only
  classifier, and `AV_ROUND_PASS_MINMAX` sentinel laundering all fail;
- packet, extractor, and rescale-operation identities are recomputed from their complete canonical
  witness or receipt preimages; provenance-flip, packet-receipt substitution, rescale-receipt substitution,
  supplied-identity mismatch, and generic checked-constructor mutations fail; the lawful C1
  same-mechanism controls succeed: matching fixture and rescale identities validate, a non-sentinel
  fixture packet field constructs a fixture-scoped present tick, a fixture packet sentinel becomes
  fixture-scoped missingness, a representable rescale succeeds, and an unrepresentable rescale
  refuses;
- an internally consistent caller-authored runtime packet payload and whole receipt remains draft,
  while the same content emitted under the admitted E0 adapter-owned issued invocation/result join
  may become runtime observed; the positive E0 control proves a correct issued invocation, result,
  receipt, and digest join, and replay through a generic codec alone never upgrades authority;
- each otherwise inaccessible checked result remains constructible through its one operation-specific
  private door; every forbidden generic-door probe also has a same-mechanism, same-shape
  compile-success control on an honest Cartesian control type under the same platform, imports,
  package visibility, codec configuration, and compiler settings, so a broken probe cannot
  masquerade as a closed door;
- when both packet timestamps are present after demuxer or format conversion, `PTS < DTS` becomes a
  typed source-contract refusal with the original values retained; swapping, clamping, relabelling,
  or reordering the fields to manufacture an admissible packet fails;
- negative ticks, nonzero origins, epoch or unwrap identity, and declared discontinuities survive
  ingest and mapping;
- all interval and mapping laws pass, including explicit gaps;
- legacy `AudioSpan` values preserve their text-first millisecond and half-open lookup semantics,
  cannot construct media support while unbound, and require checked local-axis binding plus a
  checked edition mapping before playback use;
- absent transcript timing, an explicit legacy-empty `AudioSpan`, unknown packet duration, and
  coordinate or mapping refusal remain distinguishable;
- edition playback time remains present where one elementary stream has no sample, and no unknown
  final-sample duration is stretched to fill the edition;
- mixed-axis operations refuse without a checked mapping;
- exact-rational projection never publishes a rounded integer as exact; quantized or enclosing
  results declare rounding, residuals, endpoint policy, and overflow-safe arithmetic or refusal;
- arithmetic or quantization refusal remains a typed projection outcome and cannot become a gap,
  outside-domain, no-correspondence, ambiguity, or missing value;
- mapping tolerance is never coordinate equality, deduplication, interval membership, or axis
  identity;
- axis identity, canonical mapping-relation identity, and derivation-receipt identity remain
  distinct, with semantically equivalent segment coalescing preserving relation identity;
- reverse and round-trip laws are conditional on mapping shape, preserve all occurrence-scoped
  preimages, and never compose across a gap;
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
- a three-turn `TranscriptAtlas` with absent timing, one nonempty `AudioSpan`, and one explicit
  empty `AudioSpan`: exact text, turn order, speaker, phase, prompt, support, and half-open lookup
  survive conformance; unbound promotion to media support refuses; explicit exact `1/1000`
  local-axis binding preserves endpoints but still requires checked edition mapping; changing
  stream, axis, origin, epoch, occurrence, or rendition changes bound support identity;
- missing PTS represented as `Long.MinValue`, and packet duration zero represented as an empty
  interval or instant;
- negative PTS with a nonzero origin, plus equal raw ticks on opposite sides of a wrap, reset, or
  discontinuity;
- VFR frame time computed from nominal FPS;
- one `1/3`-second tick projected to integral milliseconds and falsely published as exact `333`,
  two distinct rationals collapsed by the same rounded target tick, and intermediate integer
  overflow checked against an independent arbitrary-precision oracle or typed arithmetic refusal;
- arithmetic or quantization refusal relabelled as presentation gap, outside domain, no
  correspondence, ambiguity, or missingness;
- start and end rounding that expands a half-open interval and invents coverage;
- nontransitive tolerance used as equality or canonical deduplication;
- an edition interval with no picture sample but continuing admitted audio, and an unknown final
  frame extended merely to fill the edition extent;
- one affine mapping segment versus two contiguous collinear restrictions of the same exact affine
  function, with the same mapping family and directed source/target roles, the same occurrence, no
  intervening gap, hold, epoch, or discontinuity seam, and compatible half-open endpoint semantics:
  equal canonical relation, distinct derivation receipt; equal slope with a changed intercept,
  occurrence, endpoint, or seam must change the relation;
- holding axes and exact segments fixed while swapping mapping family or directed source/target roles
  must change relation identity or refuse; deleting an explicit epoch or discontinuity seam and then
  coalescing equal-coefficient segments must change identity or support, or refuse admission;
- edit-list empty segments, holds, repeated source ranges, and ambiguous reverse projection;
- exact bijective round-trip, set- or extent-valued hold reversal, every occurrence of a repeated
  range, no first-match reverse, and gap-preserving mapping composition;
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
| B0 | ADR Stage B artifact-specific admission: exact Sherlock media identities, annotation/recall records, transforms, 17-source alias map, two-run timebase repair, story-text checks, segmentation/semantic status, and the F0/F1/F2 plus component ledger | Actual episode video contents remain external; non-video artifacts receive their applicable admission outcome; source/edition, coordinate, semantic, uncertainty, feature-use, and anti-circularity courts pass; no superseded Sherlock rights or participant-content hold blocks closure |
| C1 | ADR Stage C portable contracts: `SourceBundle`, stream/axis/coordinate/interval-set, `EvidenceSupport`, typed boundary layers, and checked atlas plus text and timed-transcript conformance | Begins only after P1 and B0 close; applicable construction/refusal courts and text regressions pass on JVM/JS/Native, including the `AudioSpan` absent/nonempty/legacy-empty fixture, fixture-scoped operation-derived `INT64_MIN` classification with provenance-flip and receipt-substitution refusals, pure exact rescaling, exhaustive checked-construction-door probes, refusal of `PTS < DTS` without field repair, refusal of runtime-observation promotion, and refusal to invent an edition axis; no provider or decoder dependency |
| D0 | ADR Stage D read-only Sherlock atlas adapter | Exact pinned records, two-run repair, 1,000/50/30 segmentation identities, diagnostic feature-use ledger, artifact-specific admitted fixtures, no committed episode video bytes, decoder, or model dependency; all purported runtime decoder records remain draft through D0 and D1 |
| D1 | Stage D cross-module seam migration | Compiler atlas annotations, model envelope, alignment seams, claim-family status licensing, media feature targets/support/restriction, codec/version migration, unforgeable validated tracks, joined proposals/findings, strong execution and independence identities, request/output separation, and permutation-invariant resolution all pass |
| E0 | ADR Stage E minimal film vertical proof | Opens only under a separately authorized decoder/component-realization bead; with only narrowly admitted dependencies, the adapter-owned invocation/result join, generic-codec draft boundary, forged-whole-receipt versus issued-receipt court, all ADR 0007 courts, and project-authored F0 courts pass before downstream use; one exact lawful film source slice and real recall produce a `NarrativeCompilation`; `validated` is present and one predeclared research question is answered reproducibly regardless of sign |
| G1 | General acquisition worker plus timed-language lattice | Exact replay, checked coverage partition, call/payload/support join, provider-drift refusal, and text/media alternatives with critic findings pass |
| G2 | Identity and perceptual proposal tracks | Speaker/face/character/rendition abstention, factorial binding courts, sensitive sidecars, and direct/inferred separation pass |
| G3 | Expanded admitted source construction | Separately admitted movie, annotations, foils, and gold evaluate source-side action, sound, identity, context, chronology, hierarchy, and causality without claim/query leakage |
| G4 | Exact paired recall comparison | Predeclared transcript-only, visual-only, audio-only, full-fusion, and one-shot contrasts complete with common estimands, support, coverage, budgets, uncertainty, and any positive, null, or negative result reported honestly |
| V1 | storymodel4s renderer-neutral view packets/codecs | Packet laws, saved-state refusals, synchronized evidence navigation, and optional scanner mappings pass in this repository |
| V2 | Sibling StoryAtlas consumer | Separately authorized storyatlas4s bead consumes one SHA-pinned V1 artifact; no reverse dependency |

Under the artifact-specific ADR 0007 amendment, B0 remains Sherlock scientific admission, D0
remains the narrow read-only Sherlock adapter, and E0 precedes broader workers and film-specific visualization. The
post-E worker sequence should prefer cheap deterministic or high-coverage acquisition before
expensive interpretation, but no tool tier carries an epistemic status. Model admission follows
measured capability, licence, calibration, and local courts; named models are candidates, not
defaults.

## 13. Decision register

| Decision | Consensus direction |
|---|---|
| D1 acquisition boundary | Portable source/evidence contracts in `core`; measurements in `features`; proposals/resolution in `acquire`; heavy runtimes in later JVM adapters |
| D2 time | PTS/DTS are independently present-or-missing signed ticks; C1 derives fixture-scoped packet-field and pure-rescale outcomes by separate operations rather than caller-supplied provenance tags; recomputed content identity does not grant observation authority; runtime decoder records remain draft through D0/D1, and only separately authorized E0 adapter-owned issued invocation/result integration may mint runtime packet/extractor provenance; present `PTS < DTS` is a source-contract refusal with no field repair; packet duration zero is unknown; exact rational timebase/origin/epoch/rendition; edition playback axis; canonical directed family/occurrence/seam relation identity distinct from derivation receipt; exact or typed quantized/enclosing/refused projection; conditional set-valued reverse; clock repair, track composition, and edition correspondence distinct |
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
boundaries, text and legacy timed-transcript conformance, and refusal laws. It does not include a worker, feature-target
expansion, runtime-observed packet witness, `StoryModel` envelope migration, or resolver change.
That separation lets the project test its source contract before multiplying it across providers.
