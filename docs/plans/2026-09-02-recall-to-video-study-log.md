# Recall-to-video study log

Running record of arms tried, what happened, and what it implies. Development participants only;
the untouched six are unsealed once, at the end, and choose nothing.

- Partition: 11 development, 6 untouched test, seed 20260902, frozen before any arm was compared.
  Recorded in the ignored `tmp/study/partition.json`.
- Harness: `tools/recall-study/`. Outcomes are participant-macro means with a seeded percentile
  bootstrap over participants; a participant missing any required column is missing, not dropped.

## Participant count: an error of mine that the repository had already prevented

The local corpus has 33 recall exports and **17 distinct participants**. My first baseline was
computed over all 33 and double-counted 16 people; splitting on 33 would have placed the same
participant in both partitions, which the external-floor court forbids outright. Only the `NN`
enumeration is used.

**This was not a discovery.** `docs/data/sherlock/alias-map.json` already records it precisely, and
better than I did: 17 sources, 16 aliases, an identity rule requiring the complete file SHA-256 to
match, the shift ("for aliases 05 through 16, source ordinal equals alias ordinal plus one"), the
single omitted source (`recall-source-05`), and a validation block asserting that every alias's
bytes equal its mapped source. My independent fingerprint matching reproduced that mapping exactly,
including the off-by-one from the fifth alias onward.

The lesson is procedural and mine: the corpus had a manifest describing its own identity structure,
and I counted files instead of reading it. Every landed document states 17 correctly, so nothing
shipped on the wrong number.

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

## Arm 2: scene metadata only — the leaf half did the damage

Scene label plus its places and cast, leaves untouched.

| Outcome | Difference | Improved |
|---|---|---|
| Sequential coherence | +0.0140, CI includes zero | 7 of 11 |
| Concentration | −0.0055, CI excludes zero | 0 of 11 |
| Source mass | −0.0091, CI excludes zero | 3 of 11 |
| Localizability | −0.0044, CI excludes zero | 1 of 11 |

Against the bundled arm's −0.030 concentration and −0.102 source mass, this is a fraction of the
harm. The decomposition therefore answers the question the bundled arm could not: prefixing the
thousand leaves caused nearly all of it, and prefixing the fifty scenes caused a little. That is
what the cardinality account predicts, since the same recurring boilerplate dilutes a thousand
distinctive descriptions and merely pads fifty near-empty labels.

## Arm 3: scene captions — and why the headline number is not the result

Fifty machine scene descriptions from the pinned Qwen3-VL-4B, eight evenly spaced frames each,
median 85 words, as the scene node's embedded text. Leaves left bare.

| Outcome | Difference | Improved |
|---|---|---|
| Sequential coherence | **+0.0171, CI excludes zero** | 8 of 11 |
| Concentration | −0.0041, CI excludes zero | 0 of 11 |
| Source mass | +0.0029, CI includes zero | 6 of 11 |
| Localizability | −0.0104, CI excludes zero | 0 of 11 |

This was the first arm whose ordering gain had an interval excluding zero, and it should not be
credited, because a mechanical explanation was available and turned out to be most of it.

**The granularity check.** A scene node and its own leaves compete for the same posterior mass, so
enriching the scene node makes it a better competitor. Scene-anchored units rose from 212 to 245 of
1,744. A scene anchor carries a coarser and temporally smoother time than a leaf anchor, which
raises Kendall tau for free. Re-scoring on only the 1,455 units that stayed leaf-anchored in *both*
arms:

| Outcome | Full set | Matched leaf-anchored subset |
|---|---|---|
| Sequential coherence | +0.0171, excludes zero | **+0.0070, includes zero** |
| Concentration | −0.0041, excludes zero | −0.0049, excludes zero |
| Localizability | −0.0104, excludes zero | −0.0108, excludes zero |

More than half the ordering gain was the anchor granularity shifting, not better localisation. The
harm to localisation is unchanged by the restriction, so it is real.

## Arm 4: the digest control — what the camera actually contributed

The caption arm moves two things at once against baseline: the scene node gains content, and that
content is visual. The control gives the scene node content of the same shape and length with
nothing visual in it — eight of its own coder descriptions at evenly spaced midpoints, mirroring the
eight evenly spaced frames, truncated to the captions' median 85 words.

Digest against baseline: sequential coherence +0.0108 (includes zero), concentration −0.0090,
source mass +0.0065, localizability −0.0190. Nearly the same ordering nudge and *worse* localisation
damage than the captions produced.

Captions against the digest, which is the camera's own contribution:

| Outcome | Difference | Improved |
|---|---|---|
| Sequential coherence | +0.0063, CI includes zero | 5 of 11 |
| Concentration | +0.0049, CI excludes zero | **11 of 11** |
| Source mass | −0.0036, CI includes zero | 4 of 11 |
| Localizability | +0.0086, CI excludes zero | **11 of 11** |

Two readings, both supported, and they matter for different decisions.

1. **The visual channel carries real discriminative signal.** Against matched-length text written by
   a human coder about the same scene, the machine caption is better on both localisation measures
   for every single participant. That is the strongest evidence yet that the captioning court buys
   something, and it justifies the VLM admission.
2. **The scene node is the wrong place to spend it.** Every member of this family — metadata,
   captions, digests — trades localisation for a little ordering, and the ordering is mostly the
   granularity artefact. Captions are the best member of a bad family.

The scene-node lever is therefore closed, and the visual signal is banked for a use that does not
fatten a node competing with its own leaves.

## Diagnosis 2: the retrieval channel, measured with the sequential model removed

Three arms changing the indexed text had now failed, which is evidence about the channel rather than
its input. This probe removes the HSMM entirely and asks only which similarity ranks the plausible
segment highest, so no sequential prior can flatter any channel. Its tau values are therefore not
comparable to the pipeline's; only the differences between channels are.

- **The embedding space is not collapsed.** Pairwise cosine among the 1000 segment descriptions has
  mean 0.326 and sd 0.163. Anisotropy is not the problem, so a stronger encoder is not the only move.
- **The two channels are nearly complementary.** The pinned MiniLM encoder and Okapi BM25 over the
  same texts agree on the top-ranked segment for only **18.3%** of recall units.
- **Neural alone beats lexical alone**, +0.3534 against +0.2804, so the encoder is doing real work.
- **A blend beats both**, with a broad interior plateau rather than a knife edge:

| Weight on the neural side | Raw-argmax tau | Against pure neural | Improved |
|---|---|---|---|
| 0.00 (lexical only) | +0.2804 | −0.0730, excludes zero | 2 of 11 |
| 0.70 | +0.4085 | +0.0551, excludes zero | 9 of 11 |
| 0.75 | +0.4201 | +0.0667, excludes zero | 9 of 11 |
| **0.80** | **+0.4206** | **+0.0672, excludes zero** | **10 of 11** |
| 0.85 | +0.4031 | +0.0497, excludes zero | 9 of 11 |
| 0.90 | +0.3964 | +0.0429, excludes zero | 9 of 11 |
| 1.00 (neural only) | +0.3534 | reference | — |

The plateau survives substituting the signal the pipeline can actually compute for the one that was
convenient: with IDF-weighted Jaccard over lemma sets and global rather than per-unit
standardisation, the peak moves to a small admixture but stays significant (+0.040 at weight 0.95,
10 of 11 improving). It is a property of the channels, not of one convenient parameterisation.

**Why tau may judge this.** A lexical score knows nothing about time and cannot manufacture
sequentiality, so unlike the transition model this change cannot inflate the metric that judges it.
And because the blend is applied as a permutation that preserves each unit's own multiset of
distances, concentration is not mechanically inflated either. Both outcomes remain legitimate judges.

## Arms in flight

- `blend080`: the lexical re-ranking at weight 0.80 on the semantic side, measured by the real
  harness rather than the probe.

## Not yet attempted, with the reason

- **Candidate breadth** (`perLevel`, lexical overlap): untested, cheap, judged by sequential
  coherence only, since concentration moves mechanically with candidate-set size.
- **Leaf-level captions**: the visual signal is discriminative (11 of 11 against matched coder text)
  but was spent on a node that competes with its own leaves. Captioning at the leaf level would put
  it where the ranking is actually decided, at twenty times the inference cost.
- **Transition model**: deliberately not tuned. The HSMM already carries a sequential prior, and
  tuning a sequential prior to maximise a sequentiality metric measures nothing. It waits for
  adjudicated gold.
- **A stronger sentence embedder**: MiniLM-L6-v2 is the retrieval engine and is weak by current
  standards. This is likely the largest single lever, and it needs an admission record under
  ADR 0001 before it can be run.
