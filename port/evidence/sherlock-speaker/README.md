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
