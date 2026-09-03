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

## The untouched six, unsealed once

The configuration was frozen and landed as the default *before* this was run, so the estimate below
is not selected on the data it is measured against. The comparison is the chosen configuration
against the unblended channel, over the 6 participants and 833 units held out since the partition was
drawn. They chose nothing, then or now.

| Outcome | Held-out difference | Improved | Development, for contrast |
|---|---|---|---|
| Sequential coherence | **+0.0251, CI [−0.0362, +0.0879], includes zero** | 3 of 6 | +0.0723, excludes zero |
| Concentration | +0.0027, CI excludes zero | 5 of 6 | +0.0000, includes zero |
| Source mass | +0.0049, CI excludes zero | 5 of 6 | +0.0033, includes zero |
| Localizability | −0.0041, CI excludes zero | 0 of 6 | −0.0032, excludes zero |

Under the granularity check the picture is unchanged: ordering +0.0271 and still including zero, and
the anchor mix barely moves, 95 scene anchors against 98.

**What may and may not be claimed.**

- The primary outcome does **not** replicate as a significant result. The point estimate is positive
  and in the same direction, but at roughly a third of the development magnitude and with an
  interval spanning zero. Anyone reading only the development number would overstate this change.
- Two secondary outcomes do improve out of sample with intervals excluding zero, and 5 of 6
  participants improve on each. Development showed both as neutral, so this is not development's
  result repeating; it is a modest, independent gain.
- The localizability cost replicates exactly, −0.0041 against −0.0032, with no participant improving
  in either set. It is the most reliably estimated quantity in the study, and it is a cost.

**Why the shrinkage was expected, and why it is not evidence of a mistake.** The blend weight and the
field policy were both selected on development, so the development estimate is optimistic by
construction. The between-participant spread is essentially identical in the two sets, 0.082 against
0.088, so nothing about the held-out participants is unusual; the difference is selection, not
sampling.

**The binding constraint is the corpus, not the method.** At an out-of-sample mean of +0.025 and a
between-participant standard deviation of 0.088, excluding zero would take roughly **47
participants**. This corpus has 17. No amount of further iteration on this dataset can establish an
ordering effect of this size, and iterating harder against 11 development participants would mostly
manufacture more of the optimism seen above. This is the study design's real limit, and it should be
stated wherever the result is.

## Where this leaves the research question

Something real was found and it is smaller than the development set advertised. The best supported
statement is that a rarity-weighted lexical channel blended into the semantic one gives a modest,
out-of-sample improvement in how much of the posterior lands on the source and how concentrated it
is, a positive but unestablished improvement in ordering, and a small reliable cost in localizability.

The three findings likely to outlast the numbers are mechanisms rather than effects:

1. **Routing beats enrichment.** The identical metadata that made every outcome worse as embedded
   text produced the largest development gain once a channel that prices rarity indexed it, and
   added nothing once that channel already held the same information.
2. **A stronger encoder is a substitute for the lexical channel, not a complement**, and the smaller
   of the two moves. That was measured before any admission court was opened for it.
3. **The visual channel is informative but redundant against a complete human annotation.** It beat
   matched-length coder text at the same node for all 11 participants, and added nothing once the
   coder descriptions were indexed. The captioning court earns its place on films whose annotation
   is sparse, which is the ordinary case and the reason to keep it.

## Diagnosis 4: the sequential prior can be judged after all, and it is earned

The study had deferred all transition-model work on the ground that Kendall tau cannot judge a
sequential prior without circularity. That reasoning was right about tau and wrong about the
conclusion, because tau is not the only outcome available.

**The shuffle control, and two ways it failed.** The first plan was to permute the recall and treat
`tau(real) - tau(shuffled)` as ordering attributable to content. Recorded because the failures are
instructive:

1. A sentence-level shuffle returned tau identical to four decimal places in both arms. That null
   was too clean to be real, and it was not: these transcripts come from word-level CSVs and carry
   no punctuation at all, so the sentence splitter found one sentence and permuted nothing. A null
   result should always be checked for having run.
2. Shuffling recall units and re-segmenting the joined text collapsed 172 units into 88, because
   `StorySource` canonicalisation drops the punctuation used to rejoin them. Comparing arms with
   half the units and twice the text per unit would measure segmentation, not ordering.

Preserving the segmentation exactly needs the recall graph rebuilt from permuted units, which is
more machinery than the question required, because a cheaper design answers it outright.

**Scaling the prior to zero answers it directly.** `STORYMODEL4S_PRIOR_SCALE` multiplies the four
transitions that encode the direction of time — `DiscourseSuccessor`, `WorldTimeSuccessor`,
`Backward`, `LongJump` — leaving hierarchy, entity-thread and external moves alone so the state space
is unchanged. At zero there is no ordering prior, so tau cannot be inflated by one.

Turning the shipped prior on, against no prior, with the chosen channel:

| Outcome | Difference | Improved | Circular with the prior? |
|---|---|---|---|
| Sequential coherence | +0.1037 | 11 of 11 | **yes, cannot judge** |
| Concentration | +0.0123 | 11 of 11 | no |
| Source mass | +0.0135 | 11 of 11 | no |
| Localizability | +0.0057 | 11 of 11 | no |

**The prior is earned, not manufactured.** A prior marching forward regardless of evidence would
raise tau while flattening the posterior, since it would be overriding content. This one concentrates
the posterior, attributes more mass to the film and lowers source entropy, for every participant on
every measure. The unblended channel gives the same picture (+0.0822, +0.0133, +0.0142, +0.0055, all
11 of 11).

**This is the methodological unlock.** Transition-model work was blocked because its only judge
looked circular. Three non-circular judges are now demonstrated to move with it, so the provisional
weights can be tuned against those, with tau reported but never decisive.

**And it re-validates the blend independently.** With the ordering prior removed entirely, the blend
still improves ordering by +0.0508 with 10 of 11 participants and the interval excluding zero, and
source mass by +0.0039 with 9 of 11. The blend's gain is content-driven; it was never an interaction
with the prior. The two are complementary rather than substitutes, since the prior contributes more
with the blend on (+0.1037) than off (+0.0822).

## Diagnosis 5: an outcome that confidence cannot win, and what it says about the rest

Every outcome used until now can be inflated by making the model more certain. Tau rises with a
sequential prior because tau *is* sequentiality. Concentration and localizability are measures of how
peaked the posterior is, so any stronger prior sharpens them whether or not it is right. Diagnosis 4
leaned on those last two as "non-circular" judges. That was too generous, and the correction is
below.

**The proxy.** Seventeen people watched the *same* film. When two of them recall the same moment, a
correct mapping puts both descriptions in the same place. `tools/recall-study/agreement.py` pairs
recall units *across* participants by mutual-best IDF overlap of the recall text alone, so the
pairing is identical for every arm and no arm can change which units are compared, and reports the
gap in film seconds between the paired anchors. A model that became more confident without becoming
more accurate moves both anchors and closes nothing.

**The prior sweep on both kinds of outcome, and they disagree.**

| Prior scale | Concentration vs 1.0 | Localizability vs 1.0 | Cross-participant median gap | Within 60s |
|---|---|---|---|---|
| unblended baseline | — | — | 132.0s | 42.1% |
| 0.0 | −0.0123 | −0.0057 | 97.0s | 46.0% |
| 1.0 (shipped) | reference | reference | 99.0s | 44.6% |
| **1.5** | +0.0083, 11 of 11 | +0.0041, 11 of 11 | **85.5s** | **46.0%** |
| 2.0 | +0.0152, 11 of 11 | +0.0087, 11 of 11 | 99.0s | 43.5% |
| 3.0 | +0.0274, 11 of 11 | +0.0151, 11 of 11 | 98.0s | 44.1% |
| 5.0 | — | — | 135.0s | 42.9% |
| 8.0 | — | — | 165.0s | 40.1% |

Concentration and localizability rise **monotonically** and unanimously as the prior strengthens.
Agreement traces an **inverted U** peaking at 1.5, and by scale 8 the mapping is *worse than the
unblended baseline* at putting two people's account of the same moment in the same place.

**So the two sharpness measures are confidence, not correctness, and this is now shown rather than
argued.** At scale 8 the model is at its most concentrated and least accurate simultaneously. Any
future arm judged by concentration or localizability alone can be won by a model that has merely
stopped hedging. Diagnosis 4's claim that the prior is "earned" survives only in its weak form: the
prior helps, and the evidence for that is agreement improving from 97.0s at scale 0 to 85.5s at 1.5,
not the sharpness measures moving.

**A retracted null: the first test was the wrong one.** This section initially reported that no arm
reached significance, on a paired *sign* test that gave p=0.22 for the blend and p=0.17 for the best
prior. That test was a poor choice and the null was largely its doing. Roughly 40% of anchors are
unchanged between two arms, so the median of the per-pair differences is 0 by construction, and the
sign test discards magnitude entirely — it cannot see a pair that moves 400 seconds closer. Replacing
it with a paired bootstrap that resamples pair indices once and scores both arms on the same resample,
plus a Wilcoxon signed-rank that uses magnitude, changes the answer. The effect was in the data; the
estimator could not report it.

## The result: cross-participant agreement improves, and it replicates

The comparison is the shipped configuration — lexical blend at 0.8 with lemma fields, ordering prior
at 1.5 — against the unblended channel at the shipped prior of 1.0. Two changes together, not either
alone. The pairing is computed from recall text alone and is identical for both arms, so neither can
change which units are compared.

| Participant set | Pairs | Median gap, baseline → shipped | Within 60s | Signed-rank |
|---|---|---|---|---|
| Development (11) | 354 | 132.0s → 85.5s | 42.1% → 46.0% | **p = 0.0195** |
| Untouched (6) | 92 | 159.0s → 29.5s | 46.7% → 55.4% | **p = 0.0502** |
| Pooled (17) | 884 | **144.5s → 64.0s** | **43.0% → 48.8%** | **p < 0.0001** |

Pooled paired bootstrap: median gap **−80.5s, 95% CI [−111.0, −29.0], excludes zero**; within-60s
**+5.8 points, 95% CI [+2.8, +8.7], excludes zero**. Fisher's method over the two *disjoint*
participant sets gives **p = 0.0078**.

The held-out comparison was a single pre-specified test of the frozen configuration, so its 0.0502
carries no multiple-comparison discount; the development figure does, since four arms were examined
there. The effect is *larger* out of sample than in it, on a quarter of the pairs.

**What this means.** The median disagreement between two people's accounts of the same moment falls
by 56%, from about 2.4 minutes to about 1 minute of a 25-minute film. This is the outcome a merely
more confident model cannot win, and it is the first result in the study that both reaches
significance and replicates on participants that chose nothing.

**What it still does not mean.** Agreement is a proxy for accuracy, not accuracy. Two participants
can be moved into agreement at the wrong place, and nothing here would detect it. Fewer than half of
all pairs land within a minute even now. An accuracy claim needs adjudicated gold, and that remains
the highest-value open item.

## Where the effort should go next, on this evidence

1. **Gold, still the highest-value item by a distance.** Agreement now has the power to detect an
   effect this size, but it can only show that two participants were moved together, never that they
   were moved to the right place. The plan's 300-unit adjudication track would measure accuracy
   directly. This is an owner decision.
2. **More participants, for the same reason as before.** 17 is too few for the participant-level
   estimand and 354 pairs is too few for the unit-level one.
3. **The transition weights individually.** Only a single scalar over four transitions has been
   tried; the twelve weights have never been fitted, and agreement is now a judge that can fit them
   without circularity.
4. **Retire concentration and localizability as primary outcomes.** They should be reported as
   diagnostics of confidence, never used to choose an arm.

## The gold arrives, and the study can finally say "correct"

The plan's §5 proposed buying 300 adjudicated units with about 2.5 hours of owner time. That was
unnecessary at scene granularity: `Sherlock_Recall_Scene_n50_Onsets.csv` sits in the same onsets
folder as the annotation already in use and records, per participant, the recall interval during
which they were describing each scene. It supersedes the proposal with labels for *every* unit of 15
participants rather than a 300-unit sample. Provenance, clock, participant mapping, the two
exclusions, the labelling rule and the single pre-specified comparison were fixed in
`2026-09-02-gold-scene-preregistration.md` and committed **before** anything was scored.

Coverage: 2,134 of 2,408 units, 88.6%, fall inside a coded interval. The remaining 11.4% have no
gold and are missing rather than wrong — the participant was not describing a codeable scene, which
is a fact about the recall, not an error by the aligner.

**Scene-level accuracy, shipped configuration against the unblended channel.** One comparison, both
configurations frozen and landed before the gold was obtained.

| Set | Participants | scene-exact, baseline → shipped | Paired change | Improved |
|---|---|---|---|---|
| Development | 10 | 35.9% → 37.9% | +2.30 points, CI [+0.92, +3.65], excludes zero | 8 of 10 |
| Untouched | 5 | 27.4% → 31.7% | +2.82 points, CI [−2.31, +7.25], includes zero | 4 of 5 |
| **Pooled** | **15** | **33.4% → 36.0%** | **+2.48 points, CI [+0.51, +4.37], excludes zero** | **12 of 15** |

Within one scene: 42.0% → 45.8% pooled, +3.90 points, CI [+0.76, +6.60], excludes zero, 12 of 15.
Median scene distance falls from 3 to 2.

**It agrees with the gold-free proxy rather than contradicting it**, which pre-registration rule 4
required be checked either way. Cross-participant agreement said the shipped configuration puts two
people's account of the same moment closer together; gold says it puts more of them on the right
scene. Two different measurements, same direction, and the second is not a proxy.

**The honest absolute level.** The shipped mapping puts **36.0%** of recall units on exactly the
right scene of 50, and 45.8% within one scene. Chance is about 2%, so the mapping is doing real work,
and it is also wrong about the scene nearly two thirds of the time. That is the state of the recall-
to-video mapping as of today, measured against human labels rather than against itself.

**What the improvement is worth, stated plainly.** +2.5 points of scene accuracy, about 7% relative.
Real, pre-specified, and modest. The untouched five point the same way and slightly larger, but with
five participants their interval includes zero and they cannot carry the claim alone.

**The selection caveat that survives.** Both the blend weight and the prior scale were chosen on
development using gold-free proxies, so the development column inherits that selection even though
the gold did not inform it. The pooled result is the fair summary; the untouched five are the only
column selected on nothing at all, and they are underpowered.

**What gold still cannot do here.** Fifty scenes bound the granularity: nothing above measures
within-scene precision, and the 1000-segment localisation the report actually emits is unscored. An
adjudication track remains the only route to that, and is now a much smaller and better-targeted ask
than 300 units chosen blind — it would only need to resolve units the scene gold already places.

## The largest result in the study: recall walks forwards and the aligner did not

With gold in hand the 64% of wrong units could finally be decomposed, and the decomposition pointed
at one thing.

**Error structure.** Of the 1,365 wrong units, only 27% are within two scenes; **38.8% are more than
ten scenes away**. The failures are not fuzziness, they are gross displacement.

**The signal that had been sitting unused.** In the released scene coding, consecutive recall units
are non-decreasing in scene **97.9%** of the time on development participants and 98.4% on the
untouched. The pipeline's own output was non-decreasing **69.5%** of the time. Free recall of a
narrative walks forwards through it; this aligner wandered, and the gap was pure loss.

This is the claim the study previously could not make. Sequentiality had been off limits because
Kendall tau cannot judge a sequential prior without circularity. Gold breaks that: 97.9% is measured
against human labels, not against the model's own notion of order.

**Other decompositions, recorded because they bound what else is worth doing.**

- Confidence is informative: accuracy runs 21.4% / 29.6% / 35.8% / 57.2% across quartiles of MAP
  anchor mass. The model knows when it is guessing, which makes selective prediction viable.
- Unit length matters at the bottom only: the shortest quartile, median six words, scores 27.4%
  against roughly 39% for every other quartile.
- No scene is never predicted; over- and under-prediction are mild.

## `MonotoneScene`: decode the whole recall at once, forwards only

Each unit is scored for every scene by summing the posterior mass of its candidates in that scene. A
dynamic program picks the non-decreasing scene sequence maximising total mass, and each unit's anchor
becomes the heaviest candidate inside its assigned scene. Nothing is re-inferred and no evidence is
invented; this only changes which of the model's own candidates is believed, using ordering evidence
the per-unit argmax discards.

**A first attempt that was the wrong tool**, recorded because it nearly buried the result: isotonic
regression on the predicted scene indices *averages* pooled violators, treating "scene 12" as a
continuous quantity. It produced 7.3% accuracy, a 30-point collapse, and it was the estimator's fault
rather than the hypothesis's. Scene identity is categorical; the right form is a decode over discrete
candidates, not a projection.

| Set | Participants | scene-exact, shipped → monotone | Paired change | Improved |
|---|---|---|---|---|
| Development | 10 | 37.9% → 57.9% | +18.33 points, CI [+15.24, +21.26] | **10 of 10** |
| Untouched | 5 | 31.7% → 50.7% | +16.35 points, CI [+3.83, +23.81] | 4 of 5 |
| **Pooled** | **15** | **36.0% → 55.8%** | **+17.67 points, CI [+13.12, +21.24]** | **14 of 15** |

Within one scene: 45.8% → 71.6% pooled, +24.01 points, **15 of 15**. Median error distance 2 → 0.

Designed and validated on development, then confirmed once on the untouched five, which had no part
in its design. It is on by default; `STORYMODEL4S_MONOTONE_SCENE=off` recovers the per-unit argmax.

**The escape hatch, stated because it is load-bearing.** The assigned scene sequence is non-decreasing
by construction, but a unit may have no candidate inside its assigned scene, which happens when
monotonicity forbids the only place that unit put mass. Such a unit keeps the model's unconstrained
anchor, so emitted scenes are non-decreasing 84.7% of the time rather than 100%, against 69.4% before.
The alternative, emitting no anchor, would be worse than it looks: an unanchored unit drops out of
scoring entirely, so the arm would raise its own score by discarding the units it finds hardest.

**Where the remaining headroom is.** Gold says 97.9% monotone; the decode achieves 84.7%. The 15.3%
gap is entirely the escape hatch, and it fires because the candidate shortlist is too narrow to offer
the assigned scene. Widening nomination *specifically to satisfy the constraint* — rather than
globally, which was measured to hurt — is the obvious next lever.

## Comparisons scored against gold so far, per pre-registration rule 3

Four at the time this section was written. One pre-specified (shipped against baseline), two on
development for the monotone decode (design and in-pipeline validation), one confirmation on the
untouched five. A reader discounting for multiplicity should know that the untouched five have now
been read twice: once for the lexical blend on a gold-free outcome, once here.

*Restated on 2026-09-03, because the sections below this one kept scoring and this ledger did not
keep counting.* The fill added a development read and an untouched read; the forward-skip penalty
null added a development read; the content-free ramp floor added a development read and an
untouched read. **Nine scene-gold comparisons in all**: one pooled (the pre-specified read spans
both sets), five on development, three on the untouched five alone. Counting the pooled read on
both sides, development has been read six times against gold and the untouched five four times,
plus once on a gold-free outcome. The within-scene ledger opened on 2026-09-03 is separate and is
kept in that section.

## Filling the escape hatch, and two nulls that stopped further work

**`MonotoneScene` fill.** A unit with no candidate in its assigned scene now takes the closest leaf
in that scene under the emission channel the model already uses — a wider search, not new evidence.
A filler that declines reproduces the previous decisions exactly, which is tested.

| Set | Participants | scene-exact | Change | Improved |
|---|---|---|---|---|
| Development | 10 | 57.9% → 65.2% | +5.77 points | 8 of 10 |
| Untouched | 5 | 50.7% → 60.5% | +8.94 points | **5 of 5** |
| **Pooled** | **15** | **55.8% → 63.8%** | **+6.83 points** | **13 of 15** |

Within one scene 71.6% → 83.0%, 14 of 15. Emitted scenes are now 100% non-decreasing.

**Null 1: the forward-skip penalty.** The decode's prior was asymmetric with nothing to justify it —
a backward step forbidden, a leap twenty scenes forward free. With gross displacement solved and the
remaining errors at a median of one scene, pricing forward skips looked indicated. It is a null:
+0.31 points of scene accuracy at the best setting with the interval spanning zero and 3 of 10
participants improving. The parameter stays, defaulted off, because the asymmetry may matter on a
corpus that skips differently.

**Null 2: the rate prior, refused before it was built.** Gold scene is near-linear in recall time,
median Pearson r = 0.986 across development participants, which looked like a strong case for a
diagonal prior on scene position. Testing the assumption first killed it: a **content-free linear
ramp** using recall time alone and no content at all scores **9.7%** on development and 13.9% on the
untouched. The correlation is about *ordering*, not placement — participants dwell on scenes very
unevenly — so a rate prior would add almost nothing that monotonicity has not already taken.

That ramp is also the honest floor for everything above: at 9.7% against the pipeline's 65.2%, the
result is overwhelmingly content-driven and not an artefact of recall timing.

## What is left, on this evidence

The error profile inverted. Before the decode, 38.8% of errors were more than ten scenes away; now
70.8% of errors are within two scenes and only 4.0% beyond ten, with 82.7% of all units within one
scene. Gross displacement is solved; what remains is boundary precision.

1. **Within-scene precision is entirely unmeasured, and is now the largest blind spot.** The pipeline
   localises to one of 1000 segments; this gold resolves 50 scenes. Whether the fine anchor inside a
   correct scene is good or worthless is simply unknown. The adjudication track is now a far smaller
   ask than the original 300 blind units: it need only resolve units the scene gold already places.
2. **Emission sharpness is the binding constraint on scene accuracy.** The runner-up holds the gold
   scene for only 9.8% of wrong units, so the right scene is usually not second either. The decode is
   placing the path well; the per-unit evidence is not sharp enough to nail the boundary.
3. **Unit straddling is a small ceiling.** Only 8.5% of units have their first and last word in
   different gold scenes, so re-segmenting long units caps out well below that.
4. **Confidence remains usable but flatter**: accuracy runs 59.4% to 72.5% across quartiles of MAP
   mass, against 21.4% to 57.2% before the decode. Enough for selective prediction, not for accuracy.
5. **Generalisation, not tuning, is the credibility bottleneck.** One film, 15 gold participants, and
   the untouched five have now been read three times *(four, counting the ramp floor above; the
   restated ledger has the count)*. A second corpus would be worth more than any
   further parameter on this one — and the captioning lane, redundant here against an unusually
   complete annotation, is exactly what such a corpus would need.

## Within-scene precision: the apparatus, a machine read, and the human read still owed

*2026-09-03.* Item 1 above. The pre-registration is
`2026-09-03-within-scene-precision-preregistration.md`, written before the packet was generated and
committed before any lane was scored; the apparatus is `tools/recall-study/within_scene.py`. The
study record moved from `tmp/` to `data/study/recall-to-video/` the same day (`data/README.md`); the
default configuration's run is `all17-monofill` there, and a bare-environment rerun of NN03
reproduced it byte-for-byte before anything below was drawn.

**A gold-free diagnostic first.** Within a predicted scene the decode leaves the segment anchor free,
so consecutive units placed in the same scene can be read for order. Under the default configuration
1,686 such pairs split 54.1% forward, 13.2% tied, 32.7% backward; the baseline's 563 pairs split
57.4 / 24.7 / 17.9. Chance is symmetric, so the leaf anchors carry order, and not much of it. This
is a diagnostic in the README's sense and chooses nothing.

**The packet.** Frame: the ten development participants with gold, 1,499 units, of which 978 sit in
the gold scene under the default (stratum A) and 521 do not (stratum B). Sample: 15 + 5 per
participant, 200 units in 39 scene groups, seed 20260903. Digests, recorded here before any answer
existed: `packet.md` sha256 `b7dd6338…045d52`, `key.tsv` sha256 `e0d06c04…0a90ab`, and every input
in `manifest.json`. The packet shows the unit, its neighbours, and the gold scene's segments; it
shows no anchor, posterior, runner-up or arm.

**The machine lane, run first as the pre-registration allows.** Eight fresh-context language-model
adjudicators each received one chunk of the packet, carrying the rubric verbatim, and nothing else:
no key, no report, no repository. All 200 lines came back filled, 149 marked sure. The eight chunks
and the eight filled copies are retained beside the packet under `within-scene/machine-lane/` with
`lane-manifest.json`: digests, the scene groups each chunk carried, the model (claude-fable-5-1),
and a check that every chunk is the packet's rubric plus whole scene groups verbatim. Under M1 Law I1
this lane is *diagnostic*: it may not select an arm and its numbers are never called gold. It is
reported because it answers, provisionally, the question nobody could answer yesterday, and because
its agreement with the human lane will itself be a finding.

| Outcome, default configuration | Model | Scene-midpoint null | Uniform null |
|---|---|---|---|
| Primary set: stratum A, leaf-anchored, point or span grain | 124 units | | |
| `hit`, anchor inside the adjudicated range | **66.1%** [55.6, 77.7] | 25.0% | 19.4% |
| paired, model minus midpoint null | **+41.1 points** [+30.3, +53.0], 10 of 10 participants | | |
| `hit` on sure units only (99) | 73.7% [61.6, 85.9] | 23.2% | +50.5 [+38.8, +60.6], 10 of 10 |
| `hit ±1` / `hit ±2` segments | 73.4% / 76.6% | 34.7% / 39.5% | |
| time gap, median / 75th percentile | **0 s / 3 s** | 8 s / 20 s | mean 21 s |
| temporal error, all units, stratum-weighted, median / p75 | 0 s / 40 s | 17 s / 48 s | |

*The last row was first published as 6 s / 52 s against 19 s / 70 s. A fresh-context review found
the scorer weighting each stratum by frame size over its adjudicated count, not over its sample
size as pre-registered; corrected the same day, the row is as above. Direction unchanged.*

Grain, stratum A: point 46%, span 47%, whole 3%, none 5%. Stratum B: point 18%, span 46%, whole
8%, **none 28%**. Runner-up rescue: 4 of 42 misses. Confidence quartiles of anchor mass: 58.1, 67.7,
74.2, 64.5% — informative at the bottom, not monotone. Abstention concordance cannot be read on 16
scene-anchored units.

**What this says, provisionally.** When the scene is right, the fine anchor is right two-thirds of the
time and within one segment three-quarters of the time, against a quarter for the best content-free
guess; the median unit is placed inside the span the adjudicator marked. The anchor inside a correct
scene is not worthless. It also says the scene gold has edges: 28% of the units the default places in
a different scene describe, on this reading, nothing in their gold scene at all, which is exactly the
boundary noise the scene pre-registration's `within-1` outcome was written for and is worth the
owner's eye when the human lane runs.

**What it does not establish.** Nothing here is gold. A language model read the packet, and a
different reader can move every number; the pre-registration's reliability bar (median range
Jaccard ≥ 0.5 against the human lane) decides whether the machine lane's estimates may be quoted at
all. No arm was or may be selected on this lane. One film, ten participants, 124 primary units.

**Ledgers.** Within-scene, human lane: 0 comparisons. Machine lane: 1, the default configuration.

**The human read, which is now a short task.** Open `data/study/recall-to-video/within-scene/packet.md`,
fill the 200 answer lines in place (a scene's segments are read once per group; an hour to ninety
minutes), and do not open `answers-machine-lane.tsv` until done. Then:

```
python3 tools/recall-study/within_scene.py extract --lane human packet.md answers-human-lane.tsv
python3 tools/recall-study/within_scene.py score within-scene answers-human-lane.tsv all17-monofill all17-monofill --other answers-machine-lane.tsv
```

with paths under the study record. That scoring is the first human-lane comparison and is counted
here when it happens.

## The mapping made visible: the Recall Voyage

*2026-09-03.* The owner asked to see the mapping itself, with its uncertainty, first as a page and
then in the visualization module. Two things came out of building it that belong in this record.

**The report's mass column belongs to the argmax, not to the drawn anchor.** `mapAnchorMass`,
`runnerUpAnchor` and `localizability` are computed from the untouched posterior row, while
`mapAnchor` is the scene-monotone decode's choice. Over all 17 participants under the default
configuration the decode moves **1,462 of 2,577 anchors (57%)**, and **492 (19%)** are fills whose
anchor carries no posterior mass at all (the decode assigned a scene the row had no mass in, and the
fill re-scored that scene's leaves). Nothing above this section is affected: every scene-accuracy
number reads `mapAnchor` only, and the confidence-quartile diagnostic explicitly ranks by the
argmax's mass. But a reader sizing anything by the mass column would be sizing 57% of units by a
different node's mass. The run now writes `<report>.posterior.json` beside every report, content-
free, with the decoded anchor's own mass and its origin (argmax / decode-bound / decode-filled);
the TSV is byte-identical, verified over all 17.

**The typed export.** `<report>.voyage.json` is a `RecallVoyageDocument` (ADR 0002 §14): units with
their word timings, the posterior rows, every segment and scene on one source clock, one decision
per unit with its origin, and the released scene coding as an independent coding when
`STORYMODEL4S_SCENE_CODING` names it. storymodel4s `view` compiles it to a scene whose every
number is re-derived from the row (a forged mark is refused), and storyatlas4s draws it:
`storyatlas4s voyage --document <report>.voyage.json --out <dir>`, and the same pane in the
browser shell. The Python page under `tools/recall-study/voyage/` remains as the study's own
reference rendering and reads the sidecar; it is not the durable path.

**A gold-free diagnostic the voyage exposes.** Under the default configuration, of the units whose
anchor the decode moved, the fill accounts for a third. Whether a filled anchor is better or worse
than the argmax it replaced is exactly what the within-scene human lane can say, and the machine
lane's 124 primary units can be split by origin the day the human lane is scored.
