# Identity feasibility packet: inputs, clocks, scope cost and criteria (Claude half)

**Date:** 2026-10-25.
**Bead:** `bd-01M5SPTKQX5GQS9CM3E0KZT7XQ`, under epic `bd-01M5MR1KTXEP898CTN2KE3J219`.
**Plan:** the joint proposal recorded on the epic (Mote ops
`20261025T140608.260924Z-p10810-c0000-rca0d-hddbbcf` and
`20261025T140809.851661Z-p16321-c0000-rec89-h9ebf19`). The other half, population, inclusion rule,
reference burden and the binder and adapter specification, is Codex's
(`bd-01M5SPY13QSN0X6MEBR170EZCZ`).

**Scope of this document.** It contains counts, row and scene ids, hashes and rules, and no story
text, dialogue or recall text (rule 14d). Every measurement was read-only and offline, with no model
calls. The local artifacts it cites stay under `data/source-identity/feasibility/`.

## 1. Inputs and anchors

The records are those the connected assessor consumed in `connected-main-03/requests.json` (sha256
`e383392c…10bdc`):
- 429 distinct record ids: 159 `caption`, 159 `manual-annotation` and 111 `asr-segment`. Eight pairs
  share kind, part, times and text and differ only in id, ownership, evidence role and
  source-evidence id, so there are 421 distinct by kind, part, times and text;
- 363 on `annotation-run-2` and 66 on `annotation-run-1`.

| Need (ADR 0028) | Finding | Status |
|---|---|---|
| Speech records carry recognizer word indices and per-word UTF-16 offsets (section 7) | They carry neither, but both **derive exactly**. In all 111 `asr-segment` records, the decoder segment's words appear in order as exact substrings of the record text, and nothing but whitespace is left over. Offsets are stored in `asr-record-offsets.json` (sha256 `2089bd97…0f64`, record ids, indices and offsets only) | Derivable; needs a deterministic step in record preparation |
| Record clock maps to the graph's edition (section 2) | `docs/data/sherlock/timebase-repair.json`, `annotationToPlaybackCrosswalk` `annotation-raw-to-part-playback-v1` (status established): run-1 maps to `media-part-a`, run-2 to `media-part-b`, both identity. All 111 ASR records' start and end equal their segment's `source_media` times, which confirms the mapping on this data | Established; the reader's part argument must be passed through this declared table, not inferred from names |
| Record times are whole ticks (section 2) | All 858 start and end values are whole 1/44100 s ticks, and none is reversed. They are serialized as JSON floats, and the reader refuses floats | The caller parses with `parse_float=Decimal` |
| Long decoder segments | 8 of the 111 ASR records lie in segments longer than 30 s, which ADR 0027 section 2 refuses | Those records' speech handles stay unbound |
| One modality per record (section 3) | No record declares a modality. 108 of the 159 `manual-annotation` records carry a quoted line or speaker marker alongside the description, and 51 do not. Without a declared modality, ADR 0028 refuses the kind as mixed, so all 159 records are refused | **Decided in the adapter specification (Codex half; storyatlas4s Fray board, card #136, message @1022):** manual annotation stays annotation-origin evidence. A mixed row is not split into a visual observation. Source-field-qualified anchors (column, exact extent and producer mapping) may be materialized only where provenance supports the decomposition; otherwise the row stays mixed and unbound |

## 2. The recall timing convention

**Clock: the Princeton clock. Index origin: not established beyond plus or minus one TR.**

**Receipt.** `docs/plans/2026-09-02-gold-scene-preregistration.md` section 2 says the
`Recall_Scene_n50_Onsets` file (sha256 `68cc307c…3753`) gives TR indices at 1.5 s on the Princeton
clock. This was fixed by comparing the last coded offset times 1.5 with the last Princeton word
onset, which agrees for 15 of 16 subjects. Its section 3 table maps gold subject 3 to NN03.

**My independent NN03 check:**
- The last coded offset is TR 713. With 1-based TRs covering [(n-1)·1.5, n·1.5), it ends at
  1069.5 s, 1.1 s after the last Princeton word onset (1068.4 s). The Princeton maximum TR is also
  713.
- The OpenNeuro clock gives a maximum TR of 718 and a last word 6.4 s after the coded end, which is
  inconsistent. So the clock is Princeton.
- The CSV's Princeton TR equals floor(onset / 1.5) + 1 for all 2,495 words.

**Index origin.** The data do not settle whether Onset and Offset are 1-based TR indices, covering
[(n-1)·1.5, n·1.5), or 0-based ones covering [n·1.5, (n+1)·1.5). The NN03 evidence points both ways:
- The first coded onset (TR 6) falls exactly on the first word (9.0 s) under the 0-based reading.
- The last coded offset (TR 713) contains the last word under the 1-based reading.
- 40 words fall outside every coded interval under 1-based, and 43 under 0-based. NN03's 33
  intervals tile, apart from 2 gaps.

The two origins differ on 98 of 2,495 words (3.9%), including 7 of the 257 pronoun or person-noun
tokens in all 2,495 words, 256 of which fall inside coded scenes (`tr-origin-sensitivity.json`).

A third convention exists. The inherited `gold_scene.py` uses closed word-seconds endpoints, and it
differs from 1-based TR nomination on 94 words. The union of all three disagreements is the same 98
words (Codex census, Fray #136).

**Declared rule:**
- **Nomination** uses the CSV's 1-based Princeton TR column within [Onset, Offset], inclusive. It
  matches the CSV's own TR numbering.
- **The 98 boundary words** are flagged, kept in the population, and reported under both origins.
- **No inclusion or selection rule may depend on a boundary word's scene.** Scene membership only
  nominates a review window; it never decides a reference (see the population half).

**Part-local watch TRs.** The watch-scene file restarts its numbering per part, so a global scene id
is 27 plus the Part2 scene number. Watch TRs are local to each part, so the annotation's continuous
TR minus 946 gives the part-b local TR.

## 3. Scope cost: the scenes NN03 recalls

These are counts from `scene-scope-cost-v2.json` (sha256 `5d318a50…8086`). The file was generated by
`build_scope_cost.py` (sha256 `4df100e1…f0a1`, local beside it) against
`tools/source_identity/producer.py` at `9282a0cf8` (sha256 `f1097e1e…ce151`). It supersedes the
first version (`scene-scope-cost.json`, kept unchanged as v1), whose name counts did not follow the
producer's rule.

**Scenes and rows.** NN03 recalls 32 scenes. They cover 699 distinct annotation rows. Rows are
assigned to scenes by TR overlap, so three boundary rows (69, 858 and 911) count in two scenes,
which makes 702 row-to-scene assignments.

**Vocabulary review.** The 32 scenes hold 38 distinct `Name - All` entries, and 30 of them are not
in the reviewed list of ADR 0027. Each is a one-time classification: character, or role label with
a count. The whole film has 46. In the table, the per-scene column counts unreviewed entries
present in that scene, and the bracketed number counts those seen there for the first time (the
latter sums to 30).

**Source candidates** are counted with the producer's own rule (`dialogue_mentions`), which applies
ADR 0027 §2's guards:
- segments over 30 s are refused;
- pronouns and nouns below probability 0.5 are refused;
- empty timings are refused;
- names must exactly equal a full name in the scene's `Name - All` entries.

390.75 s of speech lies in refused long segments. Ten scenes have no producer candidate at all: 2,
3, 4, 10, 11, 30, 36, 41, 42 and 44. Scene 30 is the body-scene core.

**Producer runs** are deterministic, take seconds per part, and make no model calls. Their cost is
the vocabulary review. ADR 0027 decision 1 grants `Name - All` as human-reviewed presence only for
rows 500-585. **Using it for the other rows needs the owner to extend decision 1.**

**Recall side.** Over these scenes NN03's recall has 2,455 words, of which 256 are pronoun or
person-noun tokens. That is the raw pool from which the population half draws decisions. These are
tokens, not decisions: many co-refer, and some refer to no person.

| Scene | Part | Rows | Unreviewed entries in scene (first seen here) | Refused long segments (s) | Producer candidates: pronoun / noun / name | NN03 recall words | NN03 pronoun / noun tokens |
|---|---|---|---|---|---|---|---|
| 2 | a | 8-18 | 2 (2) | 1 (13) | 0 / 0 / 0 | 38 | 0 / 2 |
| 3 | a | 19-23 | 0 (0) | 1 (31) | 0 / 0 / 0 | 23 | 2 / 0 |
| 4 | a | 24-36 | 1 (1) | 1 (45.68) | 0 / 0 / 0 | 30 | 5 / 0 |
| 5 | a | 37-44 | 1 (0) | 0 (0) | 0 / 0 / 1 | 70 | 12 / 1 |
| 8 | a | 64-69 | 2 (2) | 0 (0) | 0 / 1 / 0 | 125 | 8 / 2 |
| 9 | a | 69-72 | 6 (4) | 0 (0) | 6 / 1 / 0 | 34 | 5 / 1 |
| 10 | a | 73-80 | 2 (2) | 1 (12.87) | 0 / 0 / 0 | 44 | 0 / 0 |
| 11 | a | 81-86 | 2 (1) | 1 (17) | 0 / 0 / 0 | 43 | 2 / 1 |
| 13 | a | 97-137 | 8 (7) | 0 (0) | 4 / 1 / 3 | 158 | 7 / 0 |
| 14 | a | 138-164 | 1 (1) | 0 (0) | 0 / 0 / 6 | 85 | 7 / 2 |
| 16 | a | 187-251 | 2 (1) | 1 (4.29) | 8 / 1 / 5 | 146 | 19 / 0 |
| 19 | a | 263-297 | 2 (1) | 1 (23.28) | 3 / 0 / 4 | 79 | 5 / 2 |
| 22 | a | 314-338 | 1 (0) | 0 (0) | 2 / 0 / 2 | 100 | 8 / 1 |
| 24 | a | 350-371 | 2 (0) | 0 (0) | 0 / 0 / 2 | 91 | 15 / 0 |
| 29 | b | 490-504 | 2 (0) | 1 (24.8) | 3 / 0 / 1 | 70 | 2 / 1 |
| 30 | b | 505-545 | 0 (0) | 2 (90.00) | 0 / 0 / 0 | 53 | 2 / 1 |
| 33 | b | 590-631 | 1 (0) | 0 (0) | 40 / 2 / 0 | 237 | 24 / 0 |
| 34 | b | 632-654 | 4 (3) | 1 (12.09) | 15 / 0 / 1 | 146 | 20 / 1 |
| 35 | b | 658-686 | 2 (0) | 2 (27.20) | 13 / 1 / 1 | 51 | 5 / 1 |
| 36 | b | 687-718 | 5 (4) | 1 (58.46) | 0 / 0 / 0 | 104 | 10 / 0 |
| 37 | b | 719-733 | 2 (1) | 0 (0) | 0 / 0 / 3 | 28 | 1 / 1 |
| 38 | b | 734-784 | 1 (0) | 0 (0) | 12 / 1 / 1 | 109 | 10 / 5 |
| 40 | b | 804-833 | 2 (0) | 0 (0) | 2 / 0 / 0 | 83 | 8 / 1 |
| 41 | b | 834-840 | 1 (0) | 1 (5.35) | 0 / 0 / 0 | 15 | 0 / 1 |
| 42 | b | 841-844 | 0 (0) | 1 (14) | 0 / 0 / 0 | 31 | 6 / 0 |
| 43 | b | 845-858 | 2 (0) | 1 (11.73) | 1 / 0 / 0 | 27 | 3 / 2 |
| 44 | b | 858-869 | 0 (0) | 0 (0) | 0 / 0 / 0 | 71 | 7 / 1 |
| 45 | b | 871-898 | 0 (0) | 0 (0) | 8 / 0 / 0 | 45 | 8 / 0 |
| 46 | b | 899-911 | 0 (0) | 0 (0) | 3 / 1 / 0 | 91 | 12 / 2 |
| 47 | b | 911-934 | 0 (0) | 0 (0) | 1 / 2 / 0 | 102 | 5 / 3 |
| 48 | b | 935-961 | 0 (0) | 0 (0) | 10 / 1 / 0 | 80 | 5 / 1 |
| 49 | b | 963-981 | 0 (0) | 0 (0) | 20 / 2 / 0 | 46 | 0 / 0 |

**For scene selection (input to the inclusion rule):**
- **Scenes with no producer candidate stay in the same frozen population for every arm** (A, B, D
  and C). Membership never depends on an arm's capability (correction from Codex, storyatlas4s Fray
  board, card #136, message @1028).
  - Zero dialogue candidates alone is not proof of input failure. Each item records its measured
    stage, which separates three cases: refused or missing ASR input; admitted input with a
    candidate omission; and the separate availability of visual or annotation evidence.
  - Where B cannot answer, its measured stage is recorded. The scene is not removed.
- **Repairs differ by scene.** Nine of the ten have refused long segments overlapping the scene, so
  an ASR repair would bear on them. Scene 44 has no refused segment, so an ASR repair would not help
  it. Scenes 10, 36 and 41 have refused segments *and* admitted words with no candidate (in 36 the
  annotation names do not match the recognized words exactly), so a repair would not reach all
  their speech.
- **The body scene (29-30) contributes few NN03 tokens.** That supports keeping it as the named
  witness and regression case, not the main population.
- **Most NN03 pronoun and noun tokens, among scenes with any producer candidate:** scenes 33, 34,
  16, 24, 38 and 46, in that order. Scene 24's candidates are names only.

## 4. Frozen criteria

### 4.1 The joint proposal, restated

These restate the epic notes `20261025T140608.260924Z-p10810-c0000-rca0d-hddbbcf` and
`20261025T140809.851661Z-p16321-c0000-rec89-h9ebf19`. Where wording here and there differ, the
notes govern.

**Scope.** NN03 only. Alignment may nominate cases but is never the only membership rule, and misses
stay in the set. Natural decisions, designed diagnostics (regression cases only) and hard controls
are reported separately.

**Arms:**
- **A, B and D share one minimal binder.** B is the current automatic graph; D is an annotation-name
  baseline.
- **C, the direct multimodal competitor, bypasses graph acquisition and binding.** It shares the
  admitted evidence budget, the required output fields, the source and target checks, the
  references and the evaluation. It runs on the same admitted evidence and equivalent recall
  context. Evidence advantages are kept separate from architecture advantages. Annotated and
  annotation-free conditions are not mixed.
- **A is a reviewed source graph built without recall questions or answer labels.** It holds
  reviewed source mentions, unnamed people, full referring-expression anchors, identity,
  event/state/context, and source anchors. It never includes the recall-to-person mapping.
  - Its experimental adapter resolves only an exact reviewed source-mention anchor (record plus
    exact cited extent, or a stable mention id), or an explicitly reviewed equivalence between
    anchor extents.
  - Span overlap never assigns an entity.
  - A synthetic relational-description contrast is included.
  - A human-reviewed source relation is an experimental reference input, not a production-licensed
    `IdentityBasis`.
- **The failure stage is recorded per item:** input missing, candidate absent, binding, inference or
  attribution.

**References:**
- Fresh agents may draft judgments.
- A qualified human reviews every case used for GO/STOP, and Unknown is allowed. Without that review
  the result is developmental feasibility only.
- The reviewed source graph and the recall answer judgments are separate artifacts.
- Judges are blind to arm outputs.
- An untouched confirmation subset is frozen before implementation or selection, and runs only after
  the candidate is fixed.

**Engineering GO gate.** It is a utility screen, not a precision guarantee.
- At least 15 independently judged, human-resolvable natural attribution decisions. Fewer means
  "insufficient sample", never GO.
- Correct attribution over human-resolvable natural decisions of at least 80%.
- Also reported, over all frozen natural decisions: correct, wrong, unresolved and input-failed
  counts, and answer coverage. Unknown references stay visible.
- Discovery and confirmation denominators are shown separately.
- Candidate and confirmation GO need zero observed false attribution or contradiction, and every
  discriminating control passed.
- The same error gate applies to C and to any repair.
- Arms are selected by correct attribution at equal error, then by effort, cost and reuse.

**Named witness.** The motivating body and identity diagnostic, with its paired negatives, is a
named acceptance witness.
- If independent human review finds it resolvable, the issue counts as solved only when it is
  resolved, even if the aggregate gate passes.
- If review finds insufficient evidence, the result is an explicit ambiguity finding.
- Reused body cases are regression evidence, not fresh validation.

**Decision rule:**
- **If A fails:** inspect the failing stage and allow one targeted seam repair. If the repaired
  version fails the untouched confirmation run, stop or reframe; do not spawn more slices.
- **If B misses because the correct entity is absent:** the first repair is a scene-wide source
  entity inventory with evidence-citing discourse-coreference proposals and alternatives,
  independent of recall.
  - Tracking or diarization come first only if observed failures require them.
  - Widening candidates stays hints-only under the current ADRs, so a success names the exact policy
    amendment needed.
- **If C wins:** that selects a focused comparative follow-up and a reconsideration of S3d/S5 scope.
  It is not automatic production adoption, and it is no basis for rewriting rule 13 from this pilot.

**Owner decisions, when they become concrete:**
- any new spend (C);
- any participant-data routing;
- the qualified human reference reviewer;
- any production policy amendment.

The epic's existing constraint stands: no new paid model runs before S0/S1 land. Local processing
still respects its admission, custody and instrument-validity permissions.

### 4.2 Additions proposed here, for agreement before any arm runs

These are additions to the first two epic notes. **Agreed with Codex** and recorded on the epic as
op `20261025T144732.050282Z-p39096-c0000-r1211-h23b326`: item 2 (quarantine) and item 6
(pseudonymization), and the population correction in section 3. That op is part of the governing
record, and 4.1's GO gate is read together with item 2. **Still pending Codex's agreement:** items
1, 3 and 4. **Owner decision:** item 5.

1. **The unit.** A natural attribution decision is one recall person-reference in NN03, a pronoun,
   description or name, together with the claim role it fills. Correlated tokens of one decision
   count once.
2. **The split procedure: v2 contiguous blocks** (agreed with Codex; storyatlas4s Fray board, card
   #136, messages @1043 and @1044). This replaces the v1 split, which assigned each scene on its own
   under seed `identity-feasibility-NN03-20261025-v1`. v1 is kept as superseded, and it was replaced
   before any labels, outputs or binder work existed.
   - **Blocks.** Ten fixed blocks of five global scene ids: 1-5, 6-10, through 46-50. They are
     derived from source ordering only and are not claimed to be story arcs.
   - **Assignment.** Block 26-30, which holds the body scenes, is forced into discovery. Seed
     `identity-feasibility-NN03-20261025-v2` ranks the other nine blocks by SHA-256(seed:block
     start). The first five go to confirmation and the other four to discovery. One seed only: if
     the sample proves insufficient, that is reported and no other seed is tried.
   - **Selection.** Within each partition, whole blocks are taken in seed order, with the body block
     first in discovery. Selection stops when the partition has at least 15 distinct non-regression
     human-resolvable natural decisions, plus at least 2 hard natural decisions drawn from at least
     2
     of these strata: off-screen person, relational description, within-partition multi-scene, and
     role, event, state or context disambiguation. Designed controls stay mandatory and separate.
     Absent strata are reported. Measured playback, vocabulary and anchor costs act as budget
     limits, never as a ranking.
   - **Part boundary.** Block 26-30 crosses the media-part boundary, because Part1 ends at global
     scene 27. Scenes 26-27 are part a and 28-30 are part b. A chain inside the block can cross
     parts without crossing partitions, and it uses both part crosswalks.
   - **Disclosure.** At the author's request for review, and before any labels or outputs, Claude
     computed the v2 assignment once from the declared seed. Confirmation gets blocks 1-5, 6-10,
     11-15, 31-35 and 46-50. As a metadata proxy, not a measured quarantine rate, 6 of 32 adjacent
     pairs in NN03's recall order cross partitions, and confirmation holds 17 of the 32 recalled
     scenes and 1,403 of the 2,495 recall words. No seed was rejected.

   Scene assignment alone does not stop coreference across blocks. A decision whose reference chain
   spans both partitions is quarantined. These rules are Codex's decision (card #136, messages
   @1028 and @1032), frozen before any output:
   - **Dependencies first.** Human review establishes each decision's source-scene dependencies
     before sealing, and touching both partitions triggers quarantine. A quarantined decision cannot
     be reassigned to evade the split.
   - **Outside the gate.** Quarantined decisions are excluded from the 80% utility-rate denominator
     and from the 15-decision floor.
   - **Still reported.** They are kept as a separate row in full accounting, with counts by
     reference form and complexity.
   - **The share that became ungatable is reported.** GO is qualified as applying only to the
     selected, non-quarantined decisions, and it is never a claim about cross-scene attribution.
   - **Limits are shown, not repaired silently.** If quarantine removes the hard cases or makes the
     sample insufficient, the packet shows that limitation rather than changing the split.
   - **Within-partition cases stay eligible.** Decisions spanning several scenes on one side remain
     eligible once all their required scenes are selected.
3. **The control list.** The discriminating controls are wrong person, off-screen person, time,
   state, context and relational description.
4. **Unresolved is never a participant failure.** This restates a principle from the planning thread
   (storyatlas4s Fray board, card #124, message @996), not a clause in the epic notes.
5. **Presence beyond rows 500-585** needs the owner to extend ADR 0027 decision 1 (section 3).
   *Owner decision.*
6. **Pseudonymized naming-sensitivity check.** Agreed with Codex (storyatlas4s Fray board, card
   #136, message @1025) as an optional, budgeted check, not a prerequisite.
   - Rename the characters consistently across every text input and the relevant labels. When
     comparing architectures under renamed input, apply the same renaming to the graph and
     name-baseline arms.
   - Original and renamed cases are paired, not extra independent decisions.
   - **The gate is scored on the original-name condition.** The renamed condition is reported as
     sensitivity to naming. It is not a unique measure of memorized-plot dependence: renaming can
     alter linguistic cues, and visual recognition can still reveal the episode.
   - It does not replace a new-film follow-up. It changes no gate.
