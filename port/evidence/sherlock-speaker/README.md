# Speaker attribution at a supplied Sherlock segment

## Protocol fixed before reading

Use the first segment credited only to Sherlock and the first credited only to John,
by source row ID within every scene containing both. One attribution per segment per
condition. Ground truth is agreement with the annotation's single speaker credit.
It identifies a speaker during the segment and does not assign dialogue to a character.

Three fresh machine readings see disjoint, separately ordered sets of opaque packets.
One sees descriptions with the credit, one sees only descriptions, and one sees only
a generic statement that someone speaks. The last condition has one distinct input
shared by all 38 segments. Readers get no scene IDs, other conditions, keys, feedback,
speech transcripts or participant data. The full instruction is TASK in assay.py.

Score correct, wrong and abstained counts by condition, and separately on the 24
descriptions naming one candidate and the 14 naming both. Compare two description-only
rules fixed before the reading: pick the sole named candidate or abstain, and pick
the first candidate named. Coverage is answered / total. Agreement among answers is
correct / answered. Malformed outputs or absent/invalid quotations stop scoring for
repair and disclosure. Quotation checks establish a source location. A positive
answer favors the named candidate over the swapped candidate in this single-credit
task. Both comparisons arise from the same judgment. These are fixed development
segments clustered in 19 scenes, with no population-level inference.

## Recorded result

Protocol commit `24a2a9ebb`. Three fresh Codex readers each received one condition.
The first responses passed the packet and quotation checks. The reader was asked a
single attribution question per segment. The two candidate fidelity grades below
are deterministic translations of that answer.

| Input or rule | Correct | Wrong | Abstained | Both names (correct/wrong/abstained) |
|---|---:|---:|---:|---|
| Description and speaker credit | 38 | 0 | 0 | 14/0/0 |
| Description only | 37 | 0 | 1 | 14/0/0 |
| Sole name, otherwise abstain | 24 | 0 | 14 | 0/0/14 |
| First name | 36 | 2 | 0 | 12/2/0 |
| Speaker evidence masked | 0 | 0 | 38 | 0/0/14 |

Description-only coverage was 37/38 and agreement was 37/37 among answers. It
recovered both speakers whom first-name order missed. The one abstention occurred
on a description of a character performing a physical action without a speech cue.
The masked counts project one abstention onto 38 identical inputs.

The library admitted 154 candidate assessments at fixed references in 77 distinct
packets. With the credit supplied, it recorded 38 supported credits and 38
contradicted swaps. On descriptions it recorded 37 supported and 39 unverifiable.
The masked packet produced two unverifiable assessments. A description identifying
one speaker need not exclude another. Span validation checks that quotations exist
in the supplied row. The semantic choice came from a machine reader. The library
replay verifies how that choice was recorded. These observations concern supplied
segments of one episode. They do not estimate retrieval or participant recall accuracy.

For a follow-up that distinguishes attribution from name order more often, the same
19 scenes contain 125 unused single-credit segments naming both candidates. Of these,
29 across 14 scenes credit the second name and 96 credit the first. These are
availability counts only. A balanced follow-up should conceal the sampling rule
and report separately for both name orders.

## Reproduction

From the task branch root, use the port Python environment and set
`PYTHONPATH=port/src`. The loader expects the authorized local TSV under `SM_DATA`.
Supply a fresh private directory for preparation. Keep raw packets and response
quotations there. The committed readings contain choices, offsets and text hashes.

```sh
d=port/evidence/sherlock-speaker
python "$d/assay.py" score "$d/readings.json"
python "$d/replay.py" "$d/readings.json"
```

To repeat the reading, run `python "$d/assay.py" prepare "$private_dir"`, give each
arm to its own fresh reader, and save each reply as `<arm>-answers.json` in that
directory. Run `python "$d/assay.py" normalize "$private_dir"` to produce the
source-free reading record. A fresh machine reading may differ.
