# Does explicit speaker information improve recall-to-scene retrieval?

## Protocol fixed before scoring

Use all Sherlock development recalls, their archived A1 V2 Opus narrative-scene
alternatives, and the existing human onset-scene coding with its original exclusions.
Score the scene the participant was narrating at unit onset. The decoder's ordinary
50-scene local emissions (default floor 0.001) are normalized. The source bytes,
partition, recall unit files, ID joins, forecasts, and scoring support are pinned by
one digest in retrieval.py. Run both rankings before joining any human recall label.

The baseline replays the original local scene scores. The speaker condition adds
only an explicit speaker-credit signal. Both already have the ordinary descriptions,
names and dialogue that informed the original mapper. For a recall unit that names
Sherlock/Holmes or John/Watson directly before a speech verb, identify the named
speaker using the same fixed rule across all arms. If both or neither are detected,
keep all forecasts identical. For the named speaker multiply each baseline scene
weight by one plus the fraction of its annotated film rows crediting that speaker,
then normalize. Blank speaker cells count in the denominator. This caps any boost
at a factor of two before normalization and introduces no fitted parameter.

Two controls use exactly the same procedure. One uses the named character's
presence field, the other deliberately uses the opposite character's speaker
credit. Use comma-delimited exact membership in the respective film fields.
No condition receives human recall labels, future gold scenes or the sequence
decoder. Local private replay reuses the provider output with no new provider calls.
This measures explicit credit at the ranking stage. It leaves the effect of
changing the original language-model prompt untested.

Primary outcome is the reduction in human-scene log loss in bits, averaging
units within each participant and then weighting participants equally. Positive
means more probability on the coded scene on the geometric mean scale. Report
all scorable units and the narrower cued subset, with its own participant
denominators. Also report mean gold rank (midrank for ties) and top-one agreement
(split credit for a tied maximum). Count participant-level wins and inspect the
presence and swapped controls. All labels and weights remain fixed for this run.
This is a descriptive development comparison in one episode.

## Observed development result

Protocol commit `1a55ea7dc`. All 1,744 units in the 11 development sessions joined
to the archived mapper outputs in order. The scoring support supplied 1,499 eligible
human-coded units across 10 participants. It excluded 177 units outside the coded
intervals and 68 with no gold source. There were 101 eligible units with a single
recognized speaker cue (63 Sherlock, 38 John), distributed across all 10 participants.

| Equal-participant mean | Baseline | Speaker credit | Character present | Swapped credit |
|---|---:|---:|---:|---:|
| All units, gold loss in bits | 1.45063 | 1.45169 | 1.45062 | 1.45103 |
| Cued units, gold loss in bits | 1.56724 | 1.57533 | 1.56308 | 1.57018 |
| Cued units, mean gold rank | 4.015 | 3.131 | 3.162 | 3.655 |
| Cued units, top-one agreement | 72.51% | 72.51% | 72.51% | 72.51% |

Lower losses and ranks are better. With speaker credits, primary gain was
−0.00106 bits per unit across all scored units and −0.00808 on cued units.
Four of ten participants improved. All-unit top-one agreement stayed at 79.05%.
An exploratory inspection of the 101 cued units found that gold probability
increased for 80 and decreased for 21. The set of top-ranked scenes stayed
identical on every unit. The gold scene's rank changed on 11 cued units, all
of which initially tied with other scenes. This explains how mean rank could
improve without improving the top choice or the primary score.

The fixed credit adjustment earns no place in the retrieval default on this
evidence. This result concerns adding row-credit frequencies to already-informed
scene scores. The cue rule covers explicit named speech by two characters and the
endpoint is the human narrative scene at onset. It does not establish the value
of matching the words or substance of a particular line to the person saying it.

## Reproduce

From this branch root, run the following in the port Python environment with the
authorized local study root available through the Sherlock loader.

```sh
python -B port/evidence/sherlock-speaker/retrieval.py run
```

The output reproduces retrieval-results.json, which contains the aggregate and
per-session measurements. The script checks the frozen input digest and writes
no raw recall. All four conditions are deterministic replays of one saved model
reading per unit.
