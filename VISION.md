# Vision

Storymodel4s makes narratives and their recollection into inspectable, reproducible objects of
scientific analysis.

A narrative may be encountered as written text, spoken narration, a film, or another temporally
presented audiovisual work. A recollection may preserve, compress, reorder, combine, reinterpret, or
contradict what was presented. Our purpose is to represent both the source narrative and the
recollection, and to make the relationship between them measurable without pretending that either
has a uniquely correct interpretation.

The central research question is concrete: **what part of the source narrative is a person referring
to at this word, passage, or measured moment of recall; with what alternatives, evidence,
specificity, and uncertainty?** The answer may be a discrete assignment, a distribution over
possible referents, or a structured account of a recall trajectory. These are explicitly related
views of versioned evidence and inference, not disconnected pipelines with incompatible meanings.

## One narrative, several coordinates

We distinguish the work, the particular edition or presentation that a participant encountered, and
the observations and interpretations used to model it. Text offsets locate written evidence.
Playback coordinates locate evidence in a particular audiovisual edition. Recall-text coordinates
locate what a participant said. Recall-audio coordinates locate when they said it, when that timing
has actually been measured. Story-world chronology is a separate, potentially partial
interpretation; it is not the playback clock.

A film is not its subtitles. Silence, gesture, action, sound, editing, and visual context can carry
narrative information. Descriptions, transcripts, captions, and semantic charts are useful
observations or derived representations; they do not replace the film or acquire its evidential
authority merely by being well formed.

## A source model that can grow without changing its foundations

A useful source model may begin with reliably anchored narrative units and a modest hierarchy. It
can be enriched with entities, events, states, propositions, causal relations, goals, contexts, and
aligned feature tracks. A simple localization analysis must not require the completeness of every
enrichment. Richer analyses must explicitly identify the additional evidence and assumptions they
require.

Source models should be built once, versioned, and reused across participants. Alternative
interpretations and corrected annotations remain distinguishable. Human annotations, automatic
perception, parsers, and language models can contribute proposals, but provenance and deterministic
checks preserve the difference between observation, interpretation, and unresolved absence.

## The event layer is the core

A rich mapping needs something rich to map onto, and something rich to map from. On both sides,
the central representation is a layer of **typed, time-bounded events**, with these parts:

- who takes part, with identity that persists across shots, mentions and modalities;
- what happens or holds, as an action or a state;
- where it takes place;
- what is said, kept in its own context until it is promoted;
- how each event relates to others: part-whole, temporal, causal and referential relations.

On the film side, events are compiled from every available observation: annotations, speech,
captions, frames and identity evidence. On the recall side, they are parsed from what the person
said.

Mapping compares event to event with typed evidence, such as a shared participant, a matching
action or state, the same place, or a reference to the same utterance. Embedding similarity and
temporal order are channels within that comparison, not substitutes for it. Language models and
perception systems propose events and links; storymodel4s checks and admits them.

## Mapping without false certainty

Recall-to-source mapping is open-world and often many-to-many. A person can summarize a whole
episode, describe several events in one passage, revisit an earlier event, discuss something
external, or recall an event with incorrect details. Ambiguity between alternatives is not the same
as jointly recalling several events. Localization is not fidelity: identifying an event does not
establish that every detail was remembered correctly.

A useful system can decline to localize. Missing source evidence, unavailable computation, ambiguous
reference, unassessed fidelity, and a modeled intrusion are different conditions. Raw scores,
transport mass, model-conditional posterior probabilities, and empirically calibrated probabilities
remain distinguishable throughout computation and export.

## Analysis is a first-class outcome

The product is not complete when it produces an elaborate graph or a compelling visualization. It is
complete for a declared use when a researcher can obtain stable, documented tables and sparse
representations that link recall words and measured times to source units and source coordinates,
reproduce the derivation, inspect alternatives, and understand its limitations.

Discrete decisions, probabilistic localization, multiscale summaries, temporal projections, and
trajectory analyses must carry their coordinate systems, derivation rules, support coverage, and
calibration status. Projecting a clause-level estimate onto its words does not create word-level
inferential precision. Projecting a scene onto a time grid does not discover when an event happened
inside that scene.

## Automation in service of scientific use

Ordinary use should be automated, economical, and resumable. Human effort should concentrate on
source review, difficult ambiguities, benchmark construction, and scientific interpretation rather
than repetitive repair of undocumented intermediate formats. Implementations may change; source
identity, evidence, output meaning, and reproducibility must remain legible.

We aim for increasingly accurate models and mappings, but claims of superiority must be limited to
the tasks, inputs, populations, and comparisons actually evaluated. The enduring objective is a
trustworthy instrument for studying how people understand and remember narratives, not a particular
algorithm or a benchmark score in isolation.

## Measurement and reconstruction

The broader Narrative Process Alignment vision remains: roles, polarity, contexts, chronology,
causality, sensory detail, gist and external associations should remain inspectable rather than
collapse into one localization score. For autobiographical interviews, the target episode is an
inferred account; its organization does not establish historical truth or genuine re-experiencing.
Those richer uses build on the same evidence discipline.

Reference measurement uses declared content and linguistic evidence without a behavioral preference
for the sequence of source assignments. Structured reconstruction may add such preferences for
navigation or prediction. Both preserve common evidence and explicit policies. Scientific
organization readouts must be compatible with those policies and assessed for recovery of the
behavior being studied. Joint inference of behavioral parameters is a later, explicitly model-based
route.

## Mission

Deliver a usable, reproducible system for modeling written and audiovisual narratives, modeling
their recall, and exporting evidence-grounded recall-to-source mappings for downstream data
analysis.

## The delivery contract

For a versioned source representation and a recall transcript, the system must produce a versioned
mapping artifact that accounts for every requested recall unit. Its public views must locate the
corresponding recall words and any measured recall timing; identify source referents and their text
or playback support; retain alternatives and non-localizable outcomes; and state the inference
method, decision rule, support coverage, and calibration status.

The product must support two interoperable source-building routes: a lightweight route from checked,
anchored annotations, and a narrative-model route that compiles source evidence into a richer story
representation. Both feed the same public mapping and analysis contract. Annotation-assisted video
mapping must be identified as such. A claim of automatic video-derived modeling requires an executed
path from audiovisual material, not merely matching against human scene descriptions.

## Commitments

### 1. Keep source, evidence, and interpretation separate

Pin source bytes or externally supplied source identities, editions, streams, coordinate axes,
annotations, provider outputs, and transformations. Preserve the distinction between presentation
time and story-world chronology. Derived caption or annotation text is not canonical film content.
Build source representations independently of the recall outputs used to evaluate mapping.

### 2. Make addressing exact and precision honest

Preserve exact text spans, stable word identifiers, and edition-bound playback coordinates. Store
exact timebases and ticks as authoritative; decimal seconds are derived conveniences. Preserve
discontiguous supports, uncertain occurrence identity, and missing timestamps. Never infer a word
offset from the next word's onset without an explicit estimation policy.

### 3. Keep the useful path small

A researcher can perform basic localization using anchored units without complete semantic parsing,
complete event graphs, or an external visualization application. Rich semantics, structural
comparisons, and trajectory models are capability-bearing enrichments rather than universal
prerequisites. The film event layer is a core delivery commitment, not an optional enrichment or a
1.x deferral (owner decision 2026-11-08).

### 4. Distinguish localization, fidelity, and processing status

A recall may refer to an event and distort it. Missing propositional evidence cannot establish
fidelity or distortion. Missing candidates do not establish intrusion. Provider failure does not
describe the rememberer's behavior. Represent each distinction in data, not only in prose warnings.

### 5. Preserve one result with explicit views

Raw scores, posterior or transport measures, discrete decisions, imputation, temporal projection,
and trajectory summaries have named derivations. A decoded anchor is never accompanied by another
anchor's confidence without an explicit label. A post-processing step cannot silently rewrite the
original posterior. Multi-event recall is not misrepresented as uncertainty between mutually
exclusive events.

### 6. Expose uncertainty without overstating it

Declare the meaning and normalization of every measure. Model posteriors are conditional on the
model, candidate set, source representation, and policy. Empirical calibration requires a separately
identified evaluation protocol and a calibration artifact bound to the relevant pipeline and
decision. Uncalibrated outputs remain usable for declared exploratory purposes and are always
labeled.

### 7. Make analysis outputs part of the core product

Publish documented tables, sparse relations, and machine-readable manifests. Provide a working
example that loads a mapping in R or Python, recovers discrete and probabilistic views, and checks
accounting and coordinate invariants without reading internal Scala implementation code. Derived
measures name their denominators, support policies, and assumptions.

### 8. Prefer deterministic validation at the boundaries

Providers propose evidence or interpretations. Checked constructors and validators enforce identity,
membership, coordinate, referential, and serialization rules. Validation does not establish
scientific truth. Preserve useful partial outputs with explicit readiness and failures; do not
fabricate completeness or require irrelevant semantic layers for a basic localization request.

### 9. Make operation reproducible and economical

Use explicit, versioned configuration; resolve it once per run. Cache reusable source-side work and
content-addressed provider exchanges. Make batch failures local, retries budgeted, and completed
artifacts verifiable. Offline replay and no-network modes must be enforceable. External transmission
of participant content requires an explicit permitted policy for the relevant data.

### 10. Evaluate the entire inference-to-analysis path

Freeze input and gold eligibility independently of predictions. Include every eligible failure and
abstention in the appropriate primary denominator. Match comparisons by stable unit identity and
participant population. Separate annotation-assisted, transcript-only, and audiovisual-derived input
tracks. Test reorderings, silent visual events, ambiguous summaries, missing timing, multiple
editions, and insufficient source coverage, not only favorable examples.

### 11. Separate delivery evidence from scientific claims

Maintain a current capability/status record with commit, command, test or example, result, and
limitations. A passing unit test, a historical local execution, a clean-machine reproduction, and a
held-out scientific validation are different achievements. Engineering may proceed without winning a
research comparison; claims of accuracy or superiority may not proceed without the relevant
evidence.

### 12. Converge rather than accumulate parallel products

Production inference belongs in library and orchestration modules, not in benchmark scripts.
Benchmarks consume the public API. One active delivery plan, a dependency-ordered tracker, and
explicit acceptance artifacts govern the current release. New abstractions must close a named
delivery gap or remove demonstrated duplication.

### 13. Distinguish measurement from reconstruction

Keep immutable mapping evidence reusable across a strict reference measurement profile and named
structured reconstruction profiles. Bind scientific readouts to stage-level inference policies,
preserve uncertain and missing transitions, and validate recovery of organization as well as
localization. An order-prior ablation or a shuffled-transcript subtraction does not by itself make a
reconstruction an independent measurement. Policy compliance is not empirical calibration.

## Where the plan lives

Current direction, milestones and the finish line are in [docs/ROADMAP.md](docs/ROADMAP.md). Current
status is in Mote. This file changes only when the commitments change.

Unimplemented features must be marked as such in Mote and in the README, and must not be
advertised as available. A lightweight mapping preview cannot be represented as completing the
film event layer.
