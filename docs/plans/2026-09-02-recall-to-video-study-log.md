# Recall-to-video study log

Running record of arms tried, what happened, and what it implies. Development participants only;
the untouched six are unsealed once, at the end, and choose nothing.

- Partition: 11 development, 6 untouched test, seed 20260902, frozen before any arm was compared.
  Recorded in the ignored `tmp/study/partition.json`.
- Harness: `tools/recall-study/`. Outcomes are participant-macro means with a seeded percentile
  bootstrap over participants; a participant missing any required column is missing, not dropped.

## Data integrity finding, before anything was run

The local corpus has 33 recall exports but **17 distinct participants**. Every `sub-NN` file is a
byte-identical recall to an `NN` file (16 exact matches on a 30-word fingerprint; NN05 has no `sub`
counterpart), matching the 17 sources the lineage manifest enumerates. An earlier baseline computed
over all 33 double-counted 16 people. Worse, splitting on 33 would have placed the same participant
in both partitions, which the external-floor court forbids outright. Only the `NN` enumeration is
used.

## Baseline (human coder descriptions, MiniLM channel, top-8 per level, lexical overlap off)

11 development participants, 1,744 recall units:

| Outcome | Mean | 95% CI |
|---|---|---|
| Sequential coherence (Kendall tau) | 0.4306 | [0.384, 0.478] |
| Concentration (MAP anchor mass) | 0.1167 | [0.112, 0.121] |
| Source mass | 0.6752 | [0.652, 0.700] |
| Localizability | 0.6625 | [0.660, 0.664] |

## Diagnosis: the errors are distant and cross-scene

Over all 17 participants, for units whose MAP anchor and runner-up are both segment nodes:

- Median distance between the two anchors: **108 segments**, roughly five minutes of film.
- Within five segments: 17.5%, against about 1% by chance. Real local signal, but not the bulk.
- **76.4% put the two anchors in different scenes**, against about 3% if the runner-up were random.

So the aligner is not merely fuzzy about the neighbourhood; it confuses moments minutes apart, and
the confusion crosses scene boundaries. That is why the scene level, 50 nodes rather than 1000, is
where discrimination is worth buying first.

Candidate nomination is not the limiting factor in an obvious way: `unranked` mass is 0.000, so
every unit receives candidates. The posterior spreads over a shortlist of roughly eight to sixteen
admitted states, and the MAP holds about an eighth of it.

## Arm 1: enriched source text — FAILED, and instructively

Prefixing each segment's embedded text with its location and cast, and each scene's with its label
plus places and cast. Paired per-participant differences against baseline:

| Outcome | Difference | Improved |
|---|---|---|
| Sequential coherence | +0.003, CI includes zero | 5 of 11 |
| Concentration | **−0.030**, CI excludes zero | 0 of 11 |
| Source mass | **−0.102**, CI excludes zero | 0 of 11 |
| Localizability | −0.018, CI excludes zero | 0 of 11 |

Strictly worse on everything that moved, and no participant improved on three of four measures.

**Mechanism, confirmed rather than assumed.** Same-scene confusion rose from 23.9% to 33.6%, and
mean external mass from 0.352 to 0.440. The prefix made segments within a scene look more alike and
made the aligner less able to attribute recall to any source node at all.

**Why, in one sentence.** The description column carries 992 distinct values across 1000 segments;
every other annotation column is low-cardinality (location 40 values and near-constant within a
scene, speaking names 34, camera angle 40). Prefixing a high-cardinality field with recurring
low-cardinality boilerplate dilutes the one discriminative signal inside a mean-pooled embedding.

**The general lesson, which should govern every later arm:** more text is not better, more
*distinctive* text is. Any addition whose vocabulary recurs across the episode will spread mass
rather than concentrate it. This raises the bar for the caption arms: a caption that reads like
every other caption will hurt exactly the same way.

**Design error, corrected.** That arm changed leaf and scene text at once and so cannot say which
half caused the harm. The policy is now separable (`enriched-leaf`, `enriched-scene`), and the
scene-only half is being measured on its own. The prior for it is better than for the leaf half,
because a scene node's text today is a near-contentless label such as "2. War Scene", so adding
content there fills a vacuum rather than diluting a signal.

## Arms in flight

- `enriched-scene`: scene label plus places and cast, leaves untouched.
- `caption-scene`: 50 scene captions from the pinned Qwen3-VL-4B, eight frames per scene sampled
  evenly, extracted with the realized LGPL FFmpeg build. 400 frames, digest `ac6d751bd1de4eda`.

## Not yet attempted, with the reason

- **Candidate breadth** (`perLevel`, lexical overlap): untested, cheap, judged by sequential
  coherence only, since concentration moves mechanically with candidate-set size.
- **Transition model**: deliberately not tuned. The HSMM already carries a sequential prior, and
  tuning a sequential prior to maximise a sequentiality metric measures nothing. It waits for
  adjudicated gold.
- **A stronger sentence embedder**: MiniLM-L6-v2 is the retrieval engine and is weak by current
  standards. This is likely the largest single lever, and it needs an admission record under
  ADR 0001 before it can be run.
