# Can a reader identify the speaker when name order is uninformative?

## Frozen protocol

Use the previous study's TSV edition, 19 eligible scenes and character-name matcher.
Exclude all 38 previously selected segments. Every new description must name Sherlock
and John and carry exactly one of their speaker credits. Include all 29 rows crediting
the second distinct name. Pair each with a different row crediting the first name.
Match on the first name mentioned so that every pair has opposite credited speakers.
Allocate same-scene controls first, then fill remaining pairs across scenes. Break
ties using a fixed SHA-256 order on source row IDs. The selection and presentation
orders use separate salts. No pairing or stratum information appears in the packets.

A fresh Codex reader sees only the existing attribution instruction and 58
descriptions with opaque IDs, in shuffled order. It may attribute speech to John
or Sherlock or abstain, citing a unique exact quotation for every positive answer.
Score one decision per segment against its original credit. Report correct, wrong
and abstained for each 29-row stratum, coverage and agreement among answered rows.
Always taking the first name gives 29 correct, 29 wrong. Always choosing one
character also gives 29 correct. Count both as reference values. Malformed
records stop scoring and any repairs must be disclosed.

This is a deliberately balanced challenge on previously unused rows in one episode.
Its outcome measures agreement with existing segment credits given the description.
It does not estimate natural prevalence, open-ended retrieval or participant accuracy.
Replay two candidate assessments per segment through the existing Python fidelity
resolver. These are deterministic translations of the single reading.
Frozen packet file SHA-256: `ac4597c4ed61d0793dabb7aec5abe7967a2bca92ca34f8525e6d33fa50ac1248`.

## Result

One fresh Codex reader received the frozen packet file from commit `1345b8dff`.
Its first submitted response passed the ID, coverage and exact quotation checks.
The selected rows span 18 scenes. Nineteen of the 29 pairs share a scene.

| Speaker credited | Correct | Wrong | Abstained |
|---|---:|---:|---:|
| First distinct name | 26 | 0 | 3 |
| Second distinct name | 28 | 0 | 1 |
| All 58 segments | 54 | 0 | 4 |
| First-name rule on all segments | 29 | 29 | 0 |

Coverage was 54/58 (93.1%) and agreement among answered rows was 54/54.
The reader recovered both speakers in 25 of the 29 matched pairs. Its 54
correct answers exceed the first-name rule by 25 on these selected rows.
A descriptive inspection of the four abstentions found two descriptions of
reactions without stated speech, one with a speaker introduced by a pronoun,
and one assigning dialogue to both candidates despite the single credit.
All four retain their original abstention score.

Deterministic library replay admitted 54 supported and 62 unverifiable
candidate assessments with fixed source references. The reader made 58 decisions.
The result supports description-based speaker attribution at a supplied segment
in this episode. Whether this information improves retrieval from recall is open.

## Reproduce

From the evidence branch root, use the port environment, the pinned local source
TSV, and `PYTHONPATH=port/src`. To replay the recorded reading, run
`python port/evidence/sherlock-speaker/balanced.py score port/evidence/sherlock-speaker/balanced-readings.json`.
To repeat the blind reading, use the same script's `prepare PRIVATE_DIR` command.
Give the reader only the generated `description.json`, save the response as
`description-answers.json`, then run `normalize PRIVATE_DIR`. Keep raw packets
and quotations in the private directory. A fresh reading may differ.
