# storymodel4s-interview

The Autobiographical Interview as a latent-source alignment problem (design
record Part 4, §57–76).

**Three axes that must never be derived from one another:**

- *event specificity* — does a detail denote one bounded occurrence? (`specificity`)
- *re-experiencing* — phenomenology, only from explicit ratings and
  first-person/source-monitoring language (`experiential`), aggregated per
  unique recall unit, never over atomized details. Rates are over assessed
  units; coverage is assessed units over participant recall units.
  Profile density, purity, probe gain, and related rates are 1-per-unit;
  anchoring, perceptual, integration, and fragmentation are 1-per-situation.
  `targetMass` is atom mass for the traditional sheet, not a measure of
  remembering.
- *accuracy* — unknowable without an independent source; absent here.

Target-episode membership (`address`) is a fourth, independent axis.

## Pipeline

```
InterviewSource (TranscriptAtlas + cue + probes + ratings)
  → InterviewSegmenter        participant-only RecallGraph over the same offsets
  → AtomProjection            countable DetailAtoms (§62), w_i placeholder = 1
  → TargetInduction.induce    latent target episode + Distribution[MemoryAddress] per atom
  → TargetInduction.assess    DetailAssessment with phase/probe context
  → InterviewModel.validate   promotes Draft → Validated
  → TraditionalScoring.score  AI-compatible expected + hard counts (versioned policy)
  → ProfileScoring.profile    rich profile (density, purity, integration, probe gain, …)
```

Traditional internal/external totals are a **derived, versioned projection**
(`AiScoringPolicy`) of the address × facet distributions; they never define the
ontology. Expected counts are fractional with a posterior-mass bound
(`Interval`); hard counts are produced only under a declared `HardCountRule`.

Induction in v0.1 is rule-based (habitual cues, other-episode markers,
metacognitive cues, lexical continuity; embeddings may be injected as a
`SemanticDistance` and only propose continuity). Every rule is meant to be
replaced by a provider behind the same types.

Pseudonymization (`Pseudonymizer`) is relational — `[PERSON_1]`,
`[SISTER_OF_SPEAKER]` — so coreference survives and the original is recoverable
only with the key.
