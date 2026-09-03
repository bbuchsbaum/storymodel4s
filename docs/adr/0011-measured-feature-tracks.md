# ADR 0011 — Measured feature tracks: a general measure layer, materialized onto the model

**Status:** Accepted 2026-09-03, single-developer mode (AGENTS.md SD5)

**Date:** 2026-09-03

**Decider:** the owner's agent, single-developer mode

**Plan:** `docs/plans/2026-09-03-story-and-viewer-handoff.md` §2 item 3, §5 item 3; `vision.md`
("Word-level measurements such as imageability can form aligned feature tracks, be reduced over
declared windows, contribute evidence to scene or episode boundaries, and still be inspected
independently of the hierarchy they helped infer.")

## Context

No feature measured the story. `featureSpaces`, `sidecars`, `featureRefs`, `descriptors` and
`hypotheses` were empty on every model the pipeline built, and the only bridge from a
`FeatureTrack` to those fields lived inside one fixture test. The `features` module had the whole
vocabulary — spaces, targets, estimates with typed missingness, coverage, declared reductions,
derivation identities, `SM4SFT02` sidecars — and no producer: nothing in the repository measured a
word, and no data file of word-level norms exists here. A critic of the viewer said it exactly:
"Pudding did not infer an arc, it measured one."

The vision names imageability as *one example*. A slice tuned to imageability, or to one norms
table, would be a dataset and not a method.

## Decisions

### 1. A measure is a named, versioned procedure over the surface tokens

```scala
trait LexicalMeasure:
  def space: FeatureSpace[Double]
  def identity: Checksum
  def over(sequence: SurfaceSequence): TokenIndex => Estimate[Double]
```

in `features` (portable, no I/O). It is prepared once per sequence, so a text-level quantity is
counted once. Every token it cannot value is a typed `Missing`, never a number; non-lexical tokens
are `Excluded` by the track builder before a measure sees them (ADR 0003: no eligible unit gets a
default). Two families ship, and a third is one class away:

- **`LexiconMeasure(table)`** — any word→value table. `LexiconTable.of` folds keys through the
  same normalization the surface sequence applies to words, refuses an empty table, a non-finite
  value, two rows that fold to one key with different values, and a name that cannot be an
  identifier; its identity is the checksum of the canonical rendering of its entries plus its name,
  so the same content under two file names is one table and one changed value is another. The
  loader (`pipeline.LexiconFile`: `word<TAB>value` or `word,value`, comments, an optional header,
  every bad row refused by line number) lives with the caller that owns the file.
- **`TokenLength`** (code points) and **`TypeFrequency`** (occurrences of the normalized form in
  the whole text) — computed from the text alone, always available, and honest about being modest.
  They exist so the mechanism is exercised on every build without any external data, and so a
  reader can see what a measured track looks like on a real story before deciding which norms to
  bring.

### 2. Three declared grains per measure, nothing inferred

`TokenTracks.measure` builds the raw token track with each token's span as support.
`TokenTracks.perSentence` and `perSituation` are `Aggregate` reductions (mean, missing values
ignored, coverage on every observation) over the atlas sentences and over the compiled situations
in discourse order, so each derived space is named by its recipe and basis and carries the exact
support and lexical coverage of every unit. A sentence or situation with no measurable token is
`Missing`, with coverage `0/n`, never zero. No boundary is inferred from any of this (ADR 0004's
`level` question is open); the tracks are inspectable on their own, which is the promise this slice
keeps.

### 3. Materialization is a `codec` function, and it writes what the model already has fields for

`FeatureMaterializer.materialize(draft, tracks)` puts every track onto the model through
`featureSpaces`, `sidecars` and `featureRefs`: observed values become compact rows in target order
in an `SM4SFT02` sidecar (256 rows per block, declared, so a viewer can range-read one plate), every
missing observation keeps its reason and consumes no row, and the model's own `feature.*` laws are
run on the result. Each sidecar file is named by its manifest checksum. A `features.json`
(`features-record/v1`) beside the model carries every track in its sidecar-backed form, bound to
`storymodel.json` by story id, source checksum and the model's content checksum exactly as
`derivation.json` is (ADR 0009 amendment). It is written on every build, with no tracks when none
were requested, so "nothing was measured" is a record and not a missing file.

### 4. The pipeline seat

`storyBuild … [title] [--feature <spec>]…` with specs `token-length`, `type-frequency`,
`lexicon=<path>` (name = file stem) or `lexicon=<name>@<path>`. `FeatureStage.build` runs after
the compiler on the compiled draft (the situation grain needs the situations' supports; the compiler
owns no feature semantics, ADR 0005), and the build receipt gains a `features` stage whose digest
names each measured space and identity, when anything was measured. `derivation.json` and the
summary's encoding digest bind to the written model, features included.

## Measured on the fifty-sentence replay with `--feature token-length --feature type-frequency`

| quantity | value |
|---|---|
| surface tokens / lexical tokens | 517 / 425 |
| raw tracks | 2, each 517 observations, 425 rows, 92 `Excluded` |
| sentence tracks | 2 × 50 observations, all observed |
| situation tracks | 2 × 65 observations, all observed |
| sidecar files | 6, named by manifest checksum |
| `featureRefs` on the model | 1080 |
| `storymodel.json` | 0.91 MB |
| compilation fingerprint | unchanged: the compiler's work is untouched |

With the synthetic test lexicon (17 words, values invented for the court, not norms of anything),
the lexicon track's raw coverage is partial and every uncovered word is `NotInLexicon`, which is the
shape any real norms table will have.

## What this does not do

- It ships no published norms. Brysbaert-style concreteness or MRC imageability tables are the
  caller's to supply under their own licences; the loader takes any two-column file.
- It infers no boundaries and moves no hierarchy. A feature that "contributes evidence to scene or
  episode boundaries" needs ADR 0004's two `level` coordinates settled first.
- The viewer draws `Token`, `Sentence` and `Situation` targets; it does not yet read
  `features.json`, and the sibling repository's slice is to pair it with the model as it will
  `derivation.json`.

## Rejected alternatives

- **An imageability lexicon in the repository.** No public-domain table is available, and a slice
  that works only with one table is the thing the vision warns against.
- **A boolean "novelty" feature.** A 1/0 measurement is a categorical dressed as a scalar; type
  frequency carries the same information as a count.
- **Materializing inside the compiler.** The compiler's fingerprint would then move with every
  feature request although its derivation does not; the stage runs after it and the receipt records
  it separately.
- **One sidecar file per Atlas tile.** Storage identity must not couple to semantic tiles (the
  2026-08-29 SM4SFT02 ruling); one blocked monolith per space.
