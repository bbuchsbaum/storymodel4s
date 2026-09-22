# Join an external recall clock to canonical words

`recall_word_clock.py` attaches explicitly selected CSV onsets to the words and
units in a pinned G1 `mapping-record/v0.1` inventory. This independent Python
reader uses only the standard library. It is groundwork for the timing and
exchange tickets, not the production mapping command or a scanner crosswalk.

## Run the producer-fixture example

From the repository root, with a new output path whose parent exists:

```sh
python3 tools/recall-study/recall_word_clock.py \
  --csv tools/recall-study/fixtures/word-clock-join/words.csv \
  --lineage tools/recall-study/fixtures/word-clock-join/lineage.json \
  --clock openneuro \
  --mapping tools/recall-study/fixtures/word-clock-join/g1-historical.json \
  --mapping-sha256 acea0831a78b03f5b7cfa548e81f037aa997d37dbdae8b6cdfd4b24ac82f59c7 \
  --recipe external-clock-exact-replay/v1 \
  --out /tmp/synthetic-word-clock-join.json
```

The mapping file is an actual G1 producer artifact containing original synthetic
text. The external timing CSV is separately authored. Four words join through
five CSV records; the empty record stays in the output ledger. One onset is
missing, and the second unit's measured onsets go backwards. These are useful
known answers, not estimates of mapping accuracy.

For real inputs, use an independently recorded mapping-file digest from the
producer receipt. Computing a new pin from an unexpected file would defeat that
check. The default lineage is the committed Sherlock manifest; `--expect-source`,
`--alias-id` and `--alias-map` have the same meanings as in
[clock intake](README.md#explicit-recall-clock-intake). Access to participant
transcripts remains governed by the study partition. This slice opens no new
participant or gold data and does not generate production G1 participant records.

## What the replay establishes

The explicit recipe uses the admitted CSV parser, trims characters U+0000–U+0020
from each word field, excludes empty results with a recorded reason, and joins
remaining fields with one U+0020 space. Each retained CSV field corresponds to
exactly one parsed inventory word. Quoted commas and internal newlines survive;
nonbreaking spaces are not removed by this trim rule.

If StorySource canonicalization would change that assembled text, the reader
refuses with `replay-needs-character-map`. It does not guess a shifted span.
Edited transcripts and one-to-many/many-to-one word correspondences need a later
explicit alignment/character-map recipe with stated timing derivation.

The reader checks the reconstructed transcript checksum, every ordered UTF-16
word span and ID, all unit ordinals and span components, reciprocal membership,
and the segmentation and inventory digests. Digest rendering follows G1's
length-delimited UTF-16 code-unit contract, including optional surface-unit IDs.
Words may be unassigned; units may have no words. An empty span overlaps nothing.
A discontinuous unit's hull does not own words in its gaps. Membership uses
overlap with actual nonempty components, not full containment.

The CSV and the inventory's original parser-input artifact retain separate
identities. Exact text and span correspondence **does not independently prove the
same recording**: two recordings can contain identical words. The output records
`recordingIdentityBinding: not-verified-by-this-reader`, even when structural
checks pass. A later admission/crosswalk receipt must establish that relationship
for imaging use. `sameInputArtifact` reports byte-identity equality separately.

The reader validates the inventory portion only. It reports
`fullMappingContextValidation: not-performed`; it does not reproduce contextual
`MappingCodecs.decode`, validate inference provenance or certify the full
`record_digest`. The original digest is labeled `declaredMappingRecordDigest`,
while the actual mapping-file bytes are pinned independently.

## Structured output

The experimental JSON schema is `storymodel4s.recall-word-clock-join/v1`.
CSV prose is not copied into the output; source IDs, word IDs and unit IDs remain.

| Field | Meaning |
|---|---|
| `binding` | Mapping byte pin, declared record digest, inventory/segmentation/transcript identities, original word-ID policy and admitted clock-source identity |
| `recipe`, `validation` | Executed correspondence rule and the explicit scope of validation |
| `clock` | Selected release column, in seconds, with no added offset |
| `records` | Every one-based CSV data record, its selected onset, and matched word ID or exclusion reason |
| `words` | Original canonical word IDs, zero-based parsed indices, UTF-16 spans, membership and external record onsets |
| `units` | All units, word IDs, observed/missing counts, first/last member timing and first/last measured-onset witnesses |
| `onsetOrder` within each unit | Observed, unobserved, backwards and equal adjacent-member pairs; no sorting or gap bridging |
| `capabilities` | Scanner alignment, word offsets and temporal interpolation remain unavailable |
| `producer` | Digests of the reader and shared intake implementation |

Use `(segmentationId, unitId)` to qualify a unit reference. Changing unitization
changes the segmentation/inventory binding while retaining the original parsed
word IDs. Changing clock selection preserves word identity and changes timing.

First/last measured onsets are witnesses in transcript order, not unit start/end
times. Missing boundary words remain explicit even when interior words have
timing. `coverage` counts observed onsets, not elapsed-time coverage. An empty unit
and a unit with entirely missing timing have different unavailable reasons.
Every unit's duration remains unavailable; nothing is held to the next onset or
to transcript end. Negative, equal and backwards times remain as observed.

Decimal strings preserve exact values. The shared intake bounds numeric text;
JSON integers are limited to 128 decimal digits and oversized values produce a
content-free structured refusal. Existing output files are never overwritten.
Success prints a small JSON count/digest summary; refusals use exit 2.

## Verification and integration boundary

```sh
python3 -m unittest discover -s tools/recall-study/tests -p 'test_recall*clock.py' -v
python3 tools/recall-study/tests/mutate_recall_word_clock.py \
  --out /tmp/word-clock-mutations
python3 tools/recall-study/tests/mutate_recall_clock.py \
  --out /tmp/clock-intake-mutations
```

Mutation directories must be new. The producer-generated fixture provides an
independent Scala-to-Python digest check. Additional authored inventory envelopes
exercise non-BMP characters, repeated words, reunitization and discontinuous or
empty spans; those envelopes are deliberately not full valid mapping results.
See [fixture provenance](fixtures/word-clock-join/README.md).

This reader does not change the existing Scala mapper's historical clock. The
canonical timing adapter, word/support exchange tables and verified recording/run
binding remain downstream work. StoryAtlas will consume that canonical handoff;
this experimental reader does not create an alternative visualization contract.
