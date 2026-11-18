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
