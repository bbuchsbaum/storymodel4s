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

## Arm 5: the lexical blend — the first change that improves the mapping

BM25 over the same node texts, blended with the semantic channel and applied as a permutation: the
blended ranking is mapped back onto each unit's own multiset of semantic distances, so every unit
keeps the exact distance values it had and only their assignment to nodes changes. Nothing is
rescaled, so no threshold shifts and the arm cannot win by inflating confidence.

**The no-op is checked, not asserted.** At weight 1.0 the blend ranks by the semantic distance
itself and the permutation is the identity, so it must reproduce the baseline report byte-for-byte.
It does, before and after a later refactor. That one check exercises table construction, abstention
handling and the remapping against a known answer.

Weight on the semantic side, against baseline, full development set and then the granularity check:

| Weight | Ordering, full set | Ordering, leaf-anchored in both | Concentration | Scene anchors |
|---|---|---|---|---|
| 0.70 | +0.0334, includes zero | +0.0440, includes zero | −0.0026 | 212 → 246 |
| **0.80** | **+0.0433, excludes zero** | **+0.0601, excludes zero**, 8 of 11 | −0.0016, includes zero | 212 → 242 |
| 0.90 | +0.0256, includes zero | +0.0307, excludes zero | −0.0007, includes zero | 212 → 224 |

The in-pipeline peak at 0.80 matches the probe's plateau, which is mild evidence that the probe
measures the same thing the harness does.

Unlike the caption arm, this one **grows** under the granularity check rather than dissolving:
+0.0433 becomes +0.0601 when restricted to units anchored at the leaf level in both arms. The gain is
re-ranking, not coarser anchors. Concentration is untouched at every weight, which is what the
permutation design predicts and is the reason both outcomes remain legitimate judges here.

## Arm 6: the same metadata that failed in arm 1, given to the lexical index instead

`LexicalFields.WithLemmas` adds each of a node's content lemmas that its text does not already
contain — the locations and cast of arm 1 — to the BM25 index only. The encoder never sees them.

| Outcome | Full set | Leaf-anchored in both | Improved |
|---|---|---|---|
| Sequential coherence | **+0.0723, CI [+0.0305, +0.1204], excludes zero** | **+0.0709, excludes zero** | 9 of 11 |
| Concentration | +0.0000, includes zero | +0.0002, includes zero | 4 of 11 |
| Source mass | +0.0033, includes zero | — | 8 of 11 |
| Localizability | −0.0032, excludes zero | −0.0030, excludes zero | 0 of 11 |

Scene anchors barely move, 212 to 216, the smallest shift of any arm, so almost none of this is
granularity. It is the largest ordering gain in the study, 1.7 times the text-only blend and about
ten times the scene-caption arm's honest effect.

**This is the arm 1 mechanism confirmed by reversal, which is stronger evidence than the original
failure.** The identical annotation columns that made every outcome worse when prefixed to embedded
text — concentration −0.030, source mass −0.102, no participant improving on three of four measures —
produce the study's best result when routed to a lexical index. Nothing about the information
changed; only how it is priced. A mean-pooled embedding spends a fixed budget across a sentence, so
adding recurring boilerplate to a thousand distinctive descriptions dilutes them. BM25 weights a term
by rarity, so a name in four segments counts heavily, a name in four hundred counts for almost
nothing, and no term crowds out another.

**The general lesson, revised.** The earlier statement — more distinctive text, not more text — was
right but incomplete. The full statement is that a signal must be routed to a channel that can price
it. Low-cardinality metadata is not useless; it was in the wrong channel.

Sweeping the weight with the lexical side carrying more content leaves the peak where it was:

| Weight | Ordering, full set | Ordering, leaf-anchored | Localizability | Scene anchors |
|---|---|---|---|---|
| 0.70 | +0.0678, excludes zero | +0.0633, excludes zero | −0.0050 | 212 → 226 |
| **0.80** | **+0.0723, excludes zero** | **+0.0709, excludes zero** | −0.0032 | 212 → 216 |
| 0.90 | +0.0506, excludes zero | +0.0497, excludes zero | −0.0011 | 212 → 209 |

Weight 0.90 is worth noting for a different reason than its ordering: it does the least damage to
localizability and moves the anchor mix not at all. If localizability is later promoted over
ordering, that is the configuration to revisit.

## Diagnosis 3: the encoder is not the largest lever, which was the standing assumption

This log recorded that a stronger sentence embedder was "likely the largest single lever" and needed
an ADR 0001 admission record before it could be tried. That was a guess, and it is now measured. The
same sequential-model-free probe, over three stronger open encoders against the pinned MiniLM-L6-v2,
with each model's own pooling and retrieval prefix. Nothing was admitted, pinned, or wired in;
measuring an encoder is not adopting it.

| Encoder | Alone | Against MiniLM alone | Blended with BM25 at 0.80 |
|---|---|---|---|
| MiniLM-L6-v2 (pinned) | +0.3530 | reference | **+0.4202** |
| bge-base-en-v1.5 | +0.3963 | +0.0434, excludes zero, 10 of 11 | +0.4331 |
| gte-base | +0.3809 | +0.0280, includes zero | +0.4078 |
| all-mpnet-base-v2 | +0.3668 | +0.0138, includes zero | +0.4166 |

**The blend is worth more than a four-times-larger encoder, and most of the encoder's advantage is
redundant with it.** Upgrading to the best alternative buys +0.043 alone; blending BM25 into the
encoder already pinned buys +0.067. Doing both buys +0.080, so the second move adds only about
+0.013 once the first is in place. The two are substitutes, not complements, which makes sense: a
better encoder and a rarity-weighted lexical score are both ways of not losing the distinctive words.

**Consequence for the plan.** Writing an ADR 0001 admission record, realizing weights and pinning a
new encoder is a substantial court, and it is now costed at roughly a fifth of what the already-landed
change delivers. It should not be the next thing done. The standing claim that it was the largest
lever is withdrawn.

## Arms 7 and 8: two levers closed on top of the best configuration

Both were run against `blend080-lemmas` rather than against baseline, because a lever that only
repeats a gain already banked is not worth keeping.

**Scene captions as embedded text, on top of the blend.** Ordering +0.0110 against the best arm with
the interval including zero; concentration −0.0057 and localizability −0.0088, both excluding zero
with 0 of 11 improving; scene anchors back up from 216 to 245. This is the same result the digest
control produced in isolation, reproduced in a different context: the damage is not about what the
caption says, it is about making a node compete with its own leaves.

**Wider candidate nomination**, sixteen per level instead of eight. Ordering −0.0209 against the best
arm, excluding zero, with 1 of 11 improving. Concentration and localizability fall far more, but
those two may not judge this lever at all: admitting twice the states spreads posterior mass and
raises entropy arithmetically. Ordering is the outcome allowed to judge it, and ordering says no.
The historical default of eight stands, now on evidence rather than inheritance.

## Arm 9: captions routed to the lexical index — a null, and a useful one

The arm 1 reversal made this the obvious test: the same signal that hurt as embedded text produced
the study's largest gain once a rarity-weighted channel priced it, and a caption has the shape that
dilutes a mean-pooled vector. `Group.lexicalText` routes a rendering to the lexical index alone,
where the encoder never sees it.

Against the best configuration, every outcome's interval includes zero: ordering −0.0052, 4 of 11;
concentration −0.0004; source mass +0.0008; localizability +0.0004. It neither helps nor harms.

**Why the reversal did not repeat, which sharpens the rule.** Locations and cast were vocabulary the
lexical index did not already hold, so indexing them added discriminative terms. A caption is a
paraphrase of the same events the coder description already states, and that description is already
indexed. BM25 gains come from *new* discriminative vocabulary, not from more words about the same
thing. The revised rule is therefore narrower and more useful than "route a signal to a channel that
can price it": route a signal to a channel that can price it, **and only if that channel does not
already hold the same information**.

**What this says about the film, and it is worth saying.** The visual channel is genuinely
informative — against matched-length coder text at the same node it improved both localisation
measures for all 11 participants — yet it adds nothing once the coder descriptions are indexed. For
this corpus the human annotation already captures what the model sees. That is a finding about the
Sherlock annotation being unusually complete, not a failure of the captioner.

**Consequence for leaf-level captioning.** That was the expensive lever still open: caption all 1000
segments rather than 50 scenes, at roughly twenty times the inference. Its prior is now poor for the
same reason. Leaf descriptions are the corpus's most distinctive field, 992 distinct values across
1000 segments, so a caption there is competing against even better annotation than at the scene
level. It should not be run on this corpus without a reason beyond hope. On a film with sparse or
absent human annotation the argument reverses completely, and that is where the captioning court
earns its place.

## The standing cost, stated plainly

Every arm in the study, including the two that work, loses a little localizability: −0.0032 for the
best one, 0 of 11 participants improving. That is a consistent, small, real cost paid for a larger
ordering gain, and it should not be rounded to zero when this is written up. No arm so far has
improved localizability against baseline at all.

## Arms in flight

None. Every cheap lever has returned, and the expensive one has had its prior lowered by arm 9.
The chosen configuration is frozen and the untouched participants are unsealed once, below.

## Not yet attempted, with the reason

- **Candidate breadth** (`perLevel`, lexical overlap): untested, cheap, judged by sequential
  coherence only, since concentration moves mechanically with candidate-set size.
- **Leaf-level captions**: the visual signal is discriminative (11 of 11 against matched coder text)
  but was spent on a node that competes with its own leaves. Captioning at the leaf level would put
  it where the ranking is actually decided, at twenty times the inference cost.
- **Transition model**: deliberately not tuned. The HSMM already carries a sequential prior, and
  tuning a sequential prior to maximise a sequentiality metric measures nothing. It waits for
  adjudicated gold.
- **A stronger sentence embedder**: measured and demoted, see diagnosis 3. It is a real but small
  effect once the lexical blend is in place, and the admission court it would need is not justified
  by +0.013.
