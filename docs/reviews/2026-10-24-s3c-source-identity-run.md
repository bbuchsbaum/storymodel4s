# S3c source identity run: Sherlock part b, rows 500-585 (2026-10-24)

**Bead:** S3c-5 `bd-01M5PZQEVQXP4HWKMV539MVSF8`, under S3c `bd-01M5MT30T5GFWWM07V8Q566J0B`.
**Specification:** ADR 0027 §§7-8.

This summary is content-free. It cites words by their recognizer index, rows by number and people
by annotation name (owner decision T); it quotes no dialogue or description (rule 14d). The
artifacts it cites stay under the local data root, at `data/source-identity/part-b/`.

## What ran

| Step | Code (local `main`) | Artifact | SHA-256 |
|---|---|---|---|
| Producer (S3c-2) | `tools/source_identity/producer.py` at `23db27133` | `run-1/inputs.json` | `66d0e4c1…` |
| Candidate universe | same | `run-1/universe.json` | `30ef90be…` |
| Labels (S3c-3) | fresh-context agent, blind packet | `s3c3/labels.json` | `8584a090…` |
| Model round (S3c-4) | `tools/source_identity/proposer.py` at `5b3cfbe74` | `s3c4/experiment-2/outputs.jsonl` | `58bea78c…` |
| Replay | same, offline | `run-2/inputs.json` | `3b7fbfcb…` |
| Resolver (S3c-1) | `sourceIdentity build` at `5b3cfbe74` | `run-2-graph/graph.json` | `b6d03d4b…` |

The run manifest pins the media part (`f6e2c839…`), the annotation table (`8c205826…`), the
recognizer transcript (`bcdbb70b…`) and the reviewed vocabulary (`caadfea0…`).

**Model round.** `deepseek/deepseek-v4.1-flash-1017` on Fireworks, with no fallback, zero data
retention and one attempt per request. Eight requests were sent and eight valid replies came back.
- Reported cost: USD 0.0054. All eight replies reported a cost, so the accounted upper bound is the
  same figure.
- Attempted bound: USD 0.084, against the USD 5 ceiling.
- A first prepared experiment was abandoned before execution. Its review found that the model was
  not told when each mention is spoken, and that the token cap was too low.

## Graph

- **Admitted.** 168 mentions (158 visual, 10 dialogue) and 86 presence claims. The producer
  refused decoder segments 10 and 11 (41.5 s and 84.4 s) and one low-probability pronoun.
- **Proposals.** 151 `NamedReference` links were licensed, and 2 model hints were recorded.
- **Model outcomes.** The model returned no deictic proposals. It gave two candidate sets, for words
  106 and 198, which became 2 `CompatibleDescription` hints. The other six mentions were
  absent or unclear.

## Measurement against the frozen labels (§7)

The universe is 43 pairs: 8 pronoun or noun mentions, each against the visual mentions of its
overlapping and adjacent rows. The labels are one agent's judgments, not human labels. That agent
did not view every frame, and the model has prior knowledge of the episode.

| Basis | Proposed | Licensed / recorded | Refused | Pairs with this outcome | Labelled `same` among them | Precision (Wilson 95%) |
|---|---|---|---|---|---|---|
| `DeicticReference` | 0 | 0 | 0 | 0 | — | undefined, N = 0 |
| `CompatibleDescription` hint | 2 | 2 recorded | 0 | 3 | 3 | 2/2 proposals (0.34-1.00); 3/3 pairs (0.44-1.00) |
| none | — | — | — | 40 | 0 | — |

- **Relations.** All 43 universe pairs are `Unknown`, so no pronoun or noun is linked to a person
  in this graph.
- **How 2 hints cover 3 pairs.** Hints are recorded between identity classes. The hint for word
  198 names John in row 558, and it applies to John's class, which also contains the row-557 pair.
- **Agreement with the labels.** The labels mark 3 of the 43 pairs `same`, and those are exactly
  the 3 hinted pairs. No hint falls on a pair labelled `different`. With N = 3, the interval is
  wide, as §7 says it would be.
- **`NamedReference`.** All 151 links come from the annotation and are not measured (§7).

## The body case against §8

- **The spoken name.** Words 102-103 are `Same` as the visual mentions of Jennifer Wilson, a class
  of 28: 27 visual mentions plus the spoken name. The link is annotation-derived, as §8 predicts
  under decision N.
- **The row-575 description.** Word 276 is a noun, so it can only receive hints, and it is
  `Unknown` to every class, as §8 predicts. The model judged it absent or unclear, so it received
  no hint.
  - **Finding: §7 cannot reach this link.** The candidate universe pairs a mention only with people
    listed in its overlapping and adjacent rows. Rows 574-576 list only Sherlock and John, so
    Jennifer Wilson was never a candidate for word 276.
  - That is not a model failure, and no hint can produce the link. It needs a rule for
    candidates who are absent from the screen, or another source such as tracks
    (`bd-01M5PYJ7237CQAGMY0GC7ESZM1`). This belongs to S3d's reading of the graph.
- **A pronoun about someone absent** (word 153, row 552): the model judged it absent or unclear, so
  no deixis was proposed and the relation stays `Unknown`, as §8 predicts.

## Limits

- **Small sample.** There are eight model calls and three positive labels. These numbers show the
  pipeline runs end to end and fails closed; they do not estimate accuracy.
- **Coarse presence.** Presence is per row, and row boundaries are whole seconds.
- **Segments 10 and 11** (78-204 s) yield no mentions.
- **Prior knowledge.** The model and the labeller both know the episode. That input is
  uncontrolled.
