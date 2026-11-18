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
