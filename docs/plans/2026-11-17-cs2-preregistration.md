# Case study 2: pre-registration of the sealed-six run (DRAFT)

**Status: DRAFT, awaiting the owner's approval.** Nothing in this document has been run on the
sealed participants. No sealed recall, gold row or prompt has been opened, rendered or cached.
Design: [the mapping-object plan](2026-11-16-cs2-mapping-object.md). Bead
`bd-01M7JRB11CHP7EPC35QQ12DT41`.

The sealed participants are sub-06, sub-09, sub-11, sub-12, sub-13 and sub-20
(`data/study/filmfestival/cs2-partition.json`). Each has one recall session (run-01), going by
file names only. **sub-06 has no gold coding:** there is no `recall_scenematched` workbook for
it. It is mapped but not scored, so five participants are scorable.

## 1. Frozen configuration

The frozen values were computed without sealed data. They are recorded in
`data/study/recall-precision/cs2/prereg-20261117/frozen-digests.json`.

**Code and configuration**

- **Harness commit:** `8101acc15e1a76e7a0345e6f84a4ade64e120f7c`.
- **`scorer-config.json` SHA-256:**
  `e6c28ab7162ff27ba59aafc57ad0921bf2f239e1fba48d22fbe6e5bc3d836343`.
- **Rules:**
  - `fallback: frame-film`;
  - `talk: orthogonal`, with `talk_work_variant: identifying`;
  - `cross_scene_broad: scene-set`;
  - `film_level_baseline: lopo`;
  - `mismatch_rule: none`.
- **ε and `frame_floor`:** 0.01 each.
- **Layout and rendering:** layout `prefix`, outline order `part-order`, rendering `plain`,
  pass 1 `v1`, pass-2 arm `coverage`.
- **Model:** alias `opus`; resolved id `claude-opus-5-5`, as the CLI records it.

**Prompt digests**

| Pass | System SHA-256 | Template SHA-256 | Renderer SHA-256 |
|---|---|---|---|
| Pass 1 (v1) | `d6c36074cc3c8c43…` | `b9e134d4080dba24…` | `d01fd63bb477d656…` |
| Pass 2 (span arms, coverage) | `6f72653fda0a955b…` | `54c3b38b9a4300e2…` | `d01fd63bb477d656…` |

The full digests are in the JSON file.

**Samples.** There are two:
- sample 1: seed `20261201`, salt `cs2-ff-sealed-s1`;
- sample 2: seed `20261202`, salt `cs2-ff-sealed-s2`.

Each sample reruns pass 1, because the film order is drawn per sample.

**Units.** Units come from the library segmenter (`agentStudyPrepare`, `recall-segmenter/v1`),
run on the sealed sessions' word transcripts as part of the sealed run, as was done for the
development data.

**Batching.**
- Pass 2 is pooled across the six sessions.
- Batches are grouped by framed film and seeded per film and sample.
- In each film, `nb = max(ceil(n / 40), 3)`. The item of session s at unit k goes to batch
  `(k + offset_s) mod nb`, so no batch holds two items of one session within the context
  window.

**Pooling and combination.**
- A participant's sessions are pooled into one cluster (this matters only if a second session
  appears).
- The two samples are combined by averaging bits per row.

**Frozen baselines**, fitted on the ten coded development participants only:

| Baseline | Frozen value |
|---|---|
| Duration prior | SHA-256 `b7aedf7312c83c06…` |
| LOPO marginal | SHA-256 `849a1d70ea545eaa…`, fitted on all ten |
| LOPO non-film rate | 0.03657 |
| Gold-context fitted | stay 0.9611, next 0.0135, scene stay 0.4202, scene next 0.3405 |
| HMM | stay 0.3992, next 0.3294, β 8.0 |
| TF-IDF | λ 0.2 |
| Gold-context ceiling | λ 0.1 |

Parameter SHA-256: gold-context fitted `4e05e88f2dbd2e03…`, HMM `4bde82099bf76df9…`.

## 2. Primary endpoint and decision rule

**Endpoint.** The FilmFestival scene-given-work term on specific gold rows, as a participant
mean in bits. It is compared with each participant's better baseline, i.e. the lower of
`tfidf` and `hmm_tfidf`. The gain is `min(tfidf, hmm_tfidf) − producer`, in bits per row,
averaged within participant.

**Success requires both of:**
1. a **one-sided** exact sign-flip test of mean gain > 0 (`sign_flip_p(d, "greater")`) with
   p ≤ 1/32, which means every scorable participant wins; and
2. a mean gain of at least **0.5 bits** per row.

With five scorable participants, a 5/5 win gives exactly p = 1/32.

**Required co-condition.** The same comparison on gold-blind 7 s windows (`--windows 7`) must
win for at least n − 1 of the n scorable participants: at least 4 of 5.

**For the owner.** The draft request said "6/6 wins" and "≥ 5/6 windows". sub-06 cannot be
scored, so this draft uses all-of-n and n − 1 of n. The owner must confirm or amend this.

**Development reference** (not a test; development data with revised rules):

| Run | Participants | Mean gain | Smallest gain | Wins |
|---|---|---|---|---|
| Full sample 1, per-call, gold spans | 10 | 1.43 | 0.79 | 10/10 |
| Full sample 1, per-call, 7 s windows | 10 | 1.40 | 0.73 | 10/10 |
| v1 + prefix | develop five | 1.44 | 0.84 | 5/5 |

## 3. Secondary results and predicted directions

- **Work term against `pass1_only`:** the producer is better (development skill about 0.40).
- **Film-or-not term against the LOPO non-film rate:** the producer is better (development
  skill about 0.14).
- **Top scene in gold set:** about 0.80 (development 0.79–0.82).
- **Switch F1, exact and ±1, against `pass1_only`:** the producer is no better than
  `pass1_only` (development 0.64 vs 0.70 exact).
- **Localizability AUROC:** above 0.8.
- **Gold-context ceiling and fitted forecasts:** the producer is better on the scene term, and
  not necessarily on the work term.

Each is reported with its participant-cluster CI and sign-flip p, and none decides success.

## 4. Controls on the sealed run

Every noise band is computed from the run's two samples on the same rows: paired participant
differences, ±2 SE.

1. **Null producer** (no calls).
   - Prediction: it equals the duration forecast exactly.
   - Pass: max abs bits difference = 0.
2. **Content shuffle** (pass 2 only, with sample-1 frames; about 36 calls).
   - Prediction: the scene term falls to the HMM level or below.
   - Pass: scene ≥ (producer + HMM) / 2.
3. **Wrong-context swap** (pass 2 only; about 36 calls).
   - Prediction: no gain.
   - Pass: Δscene ≥ −band.
4. **Memorization probe** (2 calls).
   - Prediction: chance.
   - Pass: film accuracy ≤ 1/10 + 2 SE, and scene accuracy ≤ chance + 2 SE.

## 5. Handling rules

- **Retries and failures.** A reply that fails to parse is retried up to twice, with the
  declared retry suffix. A batch still unparsed after that leaves its items as `pass2_missing`,
  and their words stay uncovered.
- **Counted, not repaired.** Non-compliant pass-1 units, `frame_mismatch` (rule `none`) and
  refused anchors or spans are counted and reported. They are never repaired by hand.
- **A participant with no scorable rows** (sub-06) is mapped and reported in the ledgers, and
  excluded from every endpoint.
- **One run, no rescoring.** Each sample runs once with the frozen configuration. No rule,
  baseline or parameter changes after the first sealed call. Any deviation, including a crash
  and rerun, is reported as a deviation with its cause.

## 6. Known limitations, stated in advance

- **Rules revised on development data.** The scoring rules were revised after seeing
  development results: fallback A, talk, scene sets, the LOPO film level, the non-film
  correction and `frame_floor`.
- **Interpolated word clock.** FilmFestival word onsets are interpolated within the authors'
  utterances, so the word clock carries their utterance timing.
- **Utterance-unit control deferred.**
- **Film switches are a weakness.** Switch F1 trails `pass1_only` on development data.
- **Overconfidence under shuffled content.** With content shuffled, the producer falls below the
  duration prior; it is confidently wrong.
- **Sherlock is a development case only.** It plays no part in this test.

## 7. Verification checklist, run before any sealed call

1. **Adapter refusal.** `test_frozen_participants_refused` passes at the frozen commit, and
   reading a sealed participant without `allow_frozen=True` raises `FrozenParticipant`.
2. **Zero cache hits.** Before each sealed call, the run asserts that the prompt's cache key
   is absent from the cache (a `--require-fresh` guard, to be added). Any hit stops the run.
3. **Digests reproduce.** A dry run on development data at the frozen commit (layout `prefix`,
   pass 1 `v1`) reproduces the frozen system, template and renderer digests and the
   scorer-config SHA.
4. **Clean git state** before any call, recorded in the manifest. Disk space is checked before
   the segmenter's sbt run.

## Projected calls

There are six sealed sessions. From development averages: about 214 units and about 205 pass-2
items per session.

| Part | Calls |
|---|---|
| Each sample | 6 pass-1 calls + about 36 pass-2 calls ≈ 42 |
| Two samples | ≈ 84 |
| Content shuffle + wrong context | about 72 |
| Memorization probe | 2 |
| **Total** | **about 158** |

At the measured development rates (about $0.20 per pass-2 call and about $0.25 per pass-1 call)
that is roughly $33 notional. The calls run on the subscription.
