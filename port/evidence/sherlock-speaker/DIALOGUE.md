# Does a same-segment speaker match help locate remembered dialogue?

## Frozen comparison

Use the previous experiment's Sherlock development split, explicit Sherlock/John
speech-cue rule, saved A1 V2 Opus scene weights, human narrative-scene coding at
recall onset, and exclusions. The primary contrast is the change in log loss on
the 101 previously identified, coded speech-cued units when a lexical match
must also carry the speaker's credit in the same annotated film segment.
Compute the mean within each participant and weight participants equally.
Positive gain means lower loss with the speaker match. Also report all-unit
gain, participant wins, top-scene agreement (ties split), and mean gold rank.

* Film documents are the automatically transcribed words of this episode. Assign
  each nonsuspect word, within its film part, to the unique annotated segment
  containing its midpoint in a half-open interval. Discard gap and ambiguous
  assignments. Retain rows credited only to Sherlock or only to John, with at
  least one indexed term. This identical search pool is used in every comparison.
  The credit refers to the segment and does not identify the speaker of every word.
* Extract the text following the first detected reporting phrase, to the next
  reporting phrase, sentence break, semicolon, end of unit, or 32 words.
  Reuse the repository's lexical baseline tokenizer and TF-IDF recipe. Remove
  Sherlock, Holmes, John and Watson on both sides. Fit IDF on the film documents
  alone. Use cosine similarity, giving zero for an empty query or no overlap.
* For each scene, retain the best matching segment. With words alone, search both
  speakers. With the speaker constraint, search only rows crediting the recalled
  speaker. A negative control uses the opposite credit under the same rule.
  Multiply each saved scene weight by one plus its best similarity and normalize
  across all 50 scenes. No cue or usable terms leaves the mapper weight unchanged.
  The saved mapper without any adjustment is also scored.
* As a secondary diagnostic, repeat the lexical search from a uniform scene
  distribution. Top-scene agreement here measures standalone lookup. Both
  conditions retain all 50 scenes, even those with no eligible dialogue.
  Report how many cued units have indexed query terms, positive same-speaker
  overlap, changed evidence, and different top-scene sets.
* Freeze the executable and combined digest of the previous study inputs and both
  parts' ASR segment and word files before scoring. Calculate every forecast before
  loading evaluation labels. The unit is one recall unit and the endpoint is its
  human-coded narrative scene. Literal overlap on this development set is the
  scope of the comparison. There is no word-level or verbatim recall ground truth.

## Development result

Frozen protocol `d043f012c`. Of 5,232 film transcript word records, 5,223 had
a unique segment and 3,340 fell in a segment singly credited to either candidate.
After token filtering, 375 segments in 34 of the 50 scenes were searchable.
The existing evaluation supported 1,499 units from 10 participants. Of the 101
cued units, 89 had an indexed query term, 83 had a positive match in at least
one same-speaker segment, and 79 had different scene evidence under the restriction.

| Scene ranking on 101 cued units | Gold loss (bits) | Top-scene agreement | Mean gold rank |
|---|---:|---:|---:|
| Saved mapper | 1.5672 | 72.51% | 4.01 |
| Mapper with word match | 1.6056 | 72.05% | 3.50 |
| Mapper with word and speaker match | 1.6189 | 72.05% | 3.96 |
| Mapper with word and swapped speaker match | 1.5770 | 72.51% | 3.35 |

The primary gain from requiring the speaker was −0.01328 bits on cued units
and −0.00056 bits across all units. Four of ten participants improved. The
top-scene sets for the two primary arms were identical for every cued unit.
Neither adjustment improved the saved mapper's loss. The swapped control also
outscored the correct-credit arm on this endpoint.

In standalone dialogue search, the speaker restriction changed 37 top-scene sets.
Top-scene agreement rose from 25.30% to 29.12%, but mean gold rank worsened from
13.04 to 14.78. This standalone result describes a different use from reranking
the mapper's candidates.

An exploratory coverage check against the 101 coded scenes found 100 with
searchable dialogue and 98 with a searchable row credited to the recalled speaker.
Only 60 had positive word overlap in any eligible row in the coded scene, and only
50 had positive overlap in a same-speaker row there. The measurement cannot tell
whether a mismatch reflects paraphrase, word timing, the source and recall
segment boundaries, or a recalled reference to a different scene.

Keep the current retrieval default. These data test a literal word-matching
recipe, one film, two explicitly named speakers, and existing scene-level coding.

Run `python -B port/evidence/sherlock-speaker/dialogue.py run` from the research
branch using the port Python environment and the authorized local study files.
The text-free output reproduces dialogue-results.json. No new provider calls
or human coding were needed.
