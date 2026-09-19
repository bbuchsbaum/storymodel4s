# Vision

Storymodel4s makes narratives and their recollection into inspectable, reproducible objects of scientific analysis.

A narrative may be encountered as written text, spoken narration, a film, or another temporally presented audiovisual work. A recollection may preserve, compress, reorder, combine, reinterpret, or contradict what was presented. Our purpose is to represent both the source narrative and the recollection, and to make the relationship between them measurable without pretending that either has a uniquely correct interpretation.

The central research question is concrete: **what part of the source narrative is a person referring to at this word, passage, or measured moment of recall; with what alternatives, evidence, specificity, and uncertainty?** The answer may be a discrete assignment, a distribution over possible referents, or a structured account of a recall trajectory. These are explicitly related views of versioned evidence and inference, not disconnected pipelines with incompatible meanings.

## One narrative, several coordinates

We distinguish the work, the particular edition or presentation that a participant encountered, and the observations and interpretations used to model it. Text offsets locate written evidence. Playback coordinates locate evidence in a particular audiovisual edition. Recall-text coordinates locate what a participant said. Recall-audio coordinates locate when they said it, when that timing has actually been measured. Story-world chronology is a separate, potentially partial interpretation; it is not the playback clock.

A film is not its subtitles. Silence, gesture, action, sound, editing, and visual context can carry narrative information. Descriptions, transcripts, captions, and semantic charts are useful observations or derived representations; they do not replace the film or acquire its evidential authority merely by being well formed.

## A source model that can grow without changing its foundations

A useful source model may begin with reliably anchored narrative units and a modest hierarchy. It can be enriched with entities, events, states, propositions, causal relations, goals, contexts, and aligned feature tracks. A simple localization analysis must not require the completeness of every enrichment. Richer analyses must explicitly identify the additional evidence and assumptions they require.

Source models should be built once, versioned, and reused across participants. Alternative interpretations and corrected annotations remain distinguishable. Human annotations, automatic perception, parsers, and language models can contribute proposals, but provenance and deterministic checks preserve the difference between observation, interpretation, and unresolved absence.

## Mapping without false certainty

Recall-to-source mapping is open-world and often many-to-many. A person can summarize a whole episode, describe several events in one passage, revisit an earlier event, discuss something external, or recall an event with incorrect details. Ambiguity between alternatives is not the same as jointly recalling several events. Localization is not fidelity: identifying an event does not establish that every detail was remembered correctly.

A useful system can decline to localize. Missing source evidence, unavailable computation, ambiguous reference, unassessed fidelity, and a modeled intrusion are different conditions. Raw scores, transport mass, model-conditional posterior probabilities, and empirically calibrated probabilities remain distinguishable throughout computation and export.

## Analysis is a first-class outcome

The product is not complete when it produces an elaborate graph or a compelling visualization. It is complete for a declared use when a researcher can obtain stable, documented tables and sparse representations that link recall words and measured times to source units and source coordinates, reproduce the derivation, inspect alternatives, and understand its limitations.

Discrete decisions, probabilistic localization, multiscale summaries, temporal projections, and trajectory analyses must carry their coordinate systems, derivation rules, support coverage, and calibration status. Projecting a clause-level estimate onto its words does not create word-level inferential precision. Projecting a scene onto a time grid does not discover when an event happened inside that scene.

## Automation in service of scientific use

Ordinary use should be automated, economical, and resumable. Human effort should concentrate on source review, difficult ambiguities, benchmark construction, and scientific interpretation rather than repetitive repair of undocumented intermediate formats. Implementations may change; source identity, evidence, output meaning, and reproducibility must remain legible.

We aim for increasingly accurate models and mappings, but claims of superiority must be limited to the tasks, inputs, populations, and comparisons actually evaluated. The enduring objective is a trustworthy instrument for studying how people understand and remember narratives, not a particular algorithm or a benchmark score in isolation.

## Measurement and reconstruction

The broader Narrative Process Alignment vision remains: roles, polarity, contexts, chronology,
causality, sensory detail, gist and external associations should remain inspectable rather than
collapse into one localization score. For autobiographical interviews, the target episode is an
inferred account; its organization does not establish historical truth or genuine re-experiencing.
Those richer uses build on the same evidence discipline and do not enlarge the first preview.

Reference measurement uses declared content and linguistic evidence without a behavioral preference for the sequence of source assignments. Structured reconstruction may add such preferences for navigation or prediction. Both preserve common evidence and explicit policies. Scientific organization readouts must be compatible with those policies and assessed for recovery of the behavior being studied. Joint inference of behavioral parameters is a later, explicitly model-based route.

Delivery order and current evidence are maintained in [the active plan](docs/refactor/PLAN.md).
