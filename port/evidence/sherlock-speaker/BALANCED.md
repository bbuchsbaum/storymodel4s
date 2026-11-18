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
