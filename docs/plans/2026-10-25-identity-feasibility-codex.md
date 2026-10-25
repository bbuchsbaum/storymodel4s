# Identity feasibility: population, review and binding contract

Date: 2026-10-25. Codex half of `codex-identity-plan-20261025`.
Mote: `bd-01M5SPY13QSN0X6MEBR170EZCZ`, under `bd-01M5MR1KTXEP898CTN2KE3J219`.
Companion: `2026-10-25-identity-feasibility-inputs.md`, Claude's input and scope-cost packet.

The offline census is complete. Human reference judgments, the selected source-scene manifest,
the experimental binder and the comparison runs are not complete. This packet specifies their
smallest useful next step; it does not license a new production identity basis or change an ADR.
The joint proposal remains recorded in epic notes
`20261025T140608.260924Z-p10810-c0000-rca0d-hddbbcf` and
`20261025T140809.851661Z-p16321-c0000-rec89-h9ebf19`.

## Measured population

The population is every data row in the pinned, admitted local NN03 word CSV. Neither the old
recall-unit export, automatic alignment nor a lexical screen selects membership.

| Measurement | Observed count | Meaning |
|---|---:|---|
| Raw word rows retained | 2,495 | Complete CSV population, including unflagged words |
| Review windows | 50 | Sequential navigation windows, at most 50 words each |
| Lexically screened rows | 306 | 257 pronoun and 49 person-noun matches under this script's lexicon |
| Rows with no scene nomination | 40 | Retained for human review |
| Rows whose nomination changes across timing conventions | 98 | Retained; nomination cannot determine inclusion or split |
| Human-resolvable natural attribution decisions | Unknown | Requires qualified human judgments; 306 is not this denominator |

The lexicon is a workload aid. Names, ellipsis, unlisted descriptions and non-person uses all
require review. Claude's narrower screen has different counts; neither screen defines cases.
The one-based Princeton TR nomination differs from the zero-based alternative on 98 rows and
from the inherited closed-seconds rule on 94; the union is 98. The index origin remains unsettled.
The ledger preserves all three nominations. Content evidence, not a boundary timestamp, must
establish a case's source scene.

An audit of the saved 173-unit voyage export selected 2,406 unique CSV rows using its saved
inclusive first/last timestamps. It left 89 rows unselected and selected two more than once.
159 of 173 exported texts matched the normalized CSV slices selected by those endpoints.
This audits that export's coverage; it does not establish a segmenter defect or identify the
omitted words as person references. The full CSV therefore defines the census.

The reproducible summary, hashes, scene counts and frozen scene assignment are in
[`census.json`](../refactor/evidence/identity-feasibility-20261025/codex/census.json).
The script makes no model calls and emits no participant prose to stdout or the summary.

## Reproduce and review locally

From the candidate checkout, with the admitted data in the primary repository:

```sh
python3 -B tools/identity_feasibility.py \
  --recall /Users/bbuchsbaum/code/scala/storymodel4s/data/sherlock/recall/NN03_ANT_202231_final.csv \
  --expected-recall-sha256 5bfe5e4afa516e86d302912db8f6978f16f80ab00a84cb2a878eb2ed0c15e48e \
  --scenes /Users/bbuchsbaum/code/scala/storymodel4s/data/sherlock/Sherlock_Recall_Scene_n50_Onsets.csv \
  --subject 3 \
  --legacy-voyage /Users/bbuchsbaum/code/scala/storymodel4s/data/study/recall-to-video/development-20260923/A/recall-map-NN03_ANT_202231_final.tsv.voyage.json \
  --summary /private/tmp/nn03-census-new-run.json \
  --private-dir /Users/bbuchsbaum/code/scala/storymodel4s/.agent-work/identity-feasibility-20261025/private/new-run
```

Both output destinations must be new (the private directory may exist only if empty). Preserve
the recorded input hashes; a different source is a different census. The measured initial run
is in `.agent-work/identity-feasibility-20261025/private/run-1/` in the primary repository.
`review.html` displays every word and its one-based CSV row ID; `review-windows.csv` records which
windows a human has checked; `decisions.csv` is a blank judgment template. `word-census.json`
records nominations and flags without text. Private files are mode 600, their directory mode 700,
and a destination inside Git must be ignored. These artifacts stay local and outside commits.
The public summary records their initial hashes; completed review files need a new versioned
receipt, not an assertion that the initial blank templates were reviewed.

The qualified human reviewer is an owner decision. Fresh agents may draft labels only within
the existing data permissions. Every case used for GO/STOP needs human review blind to arm
outputs. Agreement between agents is not independent ground truth.

1. Read all 50 windows, including unflagged words, and mark each window checked. Windows do not
   bound sentences or referring expressions: inspect adjacent windows when necessary.
2. Record every candidate person-reference decision. Use `first_word` and `last_word` as
   one-based inclusive CSV row IDs for the full referring expression. Record additional
   co-referring spans in `other_coref_spans` as a JSON array of inclusive pairs.
3. A decision is one referent attribution in a claim role and occurrence/state/context. Repeated
   tokens for that same decision count once. The same person in a different role or occurrence
   may form another decision; retain a shared cluster ID in `occurrence_group`. Do not inflate
   the sample with wording variants or correlated repetitions.
4. Fill `reference_form` (name, pronoun, description, ellipsis or other), `claim_role`, the
   occurrence group and `person_reference` (yes, no or unknown). Keep non-person and ambiguous
   candidates with their reasons rather than deleting them. The human census includes natural
   misses that no automatic alignment nominated.
5. Review source evidence separately, blind to predictions. Record source scene and exact source
   anchor IDs; use `source_resolvable` (yes, no or unknown) and `reference_outcome` (supported,
   contradicted, unresolved or external). These answer different questions: a clearly wrong
   attribution can be resolvable and contradicted. Record disagreement or insufficient evidence
   in `ambiguity_reason`, plus reviewer and review time. Unknown is permitted.
6. Check completeness, duplicate decisions and source evidence before sealing the manifest.
   Keep exclusion reasons, cross-partition cases and out-of-scope counts. A source candidate
   missing from B is an evaluated failure opportunity, never a reason to exclude the case.

The CSV is a private interchange template, not a validated gold loader. Before use, the pilot
runner must validate IDs, row bounds, declared values, reviewed status, source anchors and
uniqueness, and hash the completed artifacts. That runner is future work.

## Source scope and confirmation

The assignment of all 50 source scenes is frozen in `census.json`, using seed
`identity-feasibility-NN03-20261025-v1`. Scenes 29 and 30 are discovery-only. Sort the other
48 scenes by SHA-256 of `seed:scene`; the first 24 are confirmation and the remainder discovery.
This is prospective separation for the new pilot only. NN03 and this film have already been
examined; this split cannot establish generalization to a new participant or film.

The source-scene subset is **not sealed yet**. Use the complete blind reference census to choose
it before any arm outputs or binder tuning. The following selection procedure is proposed here
and must be sealed with the input packet before runs:

- Keep the two partitions separate. Start discovery with scenes 29 and 30 as the named witness.
  Within each partition, order other scenes by whole-scene playback duration from the pinned
  watch-scene metadata, ascending, then numeric scene ID. Record the duration calculation and
  source hash in the selection manifest; missing duration makes the cost estimate incomplete.
- Add whole scenes in that fixed order until the partition contains at least 15 distinct
  human-resolvable natural decisions and includes both pronoun and description cases. Retain
  every natural decision in selected scenes, including unknowns and incorrect/external recalls.
  This is the smallest prefix under the declared ordering, not a globally optimal scene subset.
  Report source viewing, vocabulary and anchor-review costs alongside its size.
- A decision that depends on scenes assigned to both partitions is quarantined and reported,
  not reassigned. It is excluded from both the 80% denominator and the 15-decision minimum.
  Report its reference form/complexity and the fraction of the full population quarantined;
  GO applies only to the selected, non-quarantined cases and cannot establish performance on
  these excluded hard cases. Within a partition, all source scenes needed for a decision must be selected
  before it counts toward the minimum. Human-evidenced scene assignment can correct a nomination
  before sealing, with a receipt; neither model outputs nor timing ambiguity can do so.
- If all eligible scenes still give fewer than 15 resolvable decisions, lack a required reference
  form, or exceed the accepted review budget, report insufficient feasibility. Do not recruit
  additional participants, manufacture variants or expand after seeing arm results.

A human reference curator may inspect confirmation material to establish the sealed labels;
the binder author and model tuning process must not inspect those labels or arm outputs until
the candidate is fixed. Record who held which artifacts. Source graph A must be made from source
evidence alone, without recall questions or recall answer labels. Use a separate source-only
annotation pass and artifact; a graph containing final recall-to-person assignments invalidates
the comparison. If effective separation cannot be maintained, report developmental evidence
and do not call the result untouched confirmation.

No whole-film reviewed graph is a prerequisite. An annotation-name baseline may be cheap to
extend, but names do not supply reviewed identity/event/state/context relations. Presence beyond
ADR 0027's accepted rows needs its own owner decision; this census does not extend that licence.

## Reference burden

Measured workload is 2,495 words in 50 windows. At an assumed careful reading rate of 100–200
words/minute, the first complete transcript pass takes roughly 12.5–24.9 minutes. At an assumed
2–5 minutes per attribution judgment, 30 distinct decisions would add 60–150 minutes. These are
planning assumptions, not measured throughput or a promise that 30 suitable decisions exist.
Record actual time for the first review batch before committing to the full budget.

Add source playback, source-only graph annotation, vocabulary classification, exact anchor
checking and disagreement adjudication from Claude's scene-cost inventory. The 306 lexical
matches neither predict 306 decisions nor bound the total review burden. The next owner-facing
cost estimate should use the chosen scene manifest and actual first-batch rate.

## Experimental adapter and binder contract

This is an implementation specification, not an implemented or tested binder. Existing ADR 0028
continues to govern production. New experiment vocabulary here is local result-schema wording,
not a proposed public Scala API.

Every run pins source edition, input/record bytes, graph revision, recall bytes, selected scenes,
evidence condition, adapter/binder version, labels and control manifest. All arms use the same
frozen decisions and admitted evidence budget, with equivalent recall context. A/B/D share the
binder. C is the direct multimodal competitor and bypasses graph acquisition/binding while
sharing output requirements and source/target checks. Report annotated and annotation-free
conditions separately. Giving one arm additional human content is an evidence advantage, not
proof of an architectural advantage.

| Boundary | Required input and behaviour | Failure outcome |
|---|---|---|
| Record preparation | Stable record ID; source edition/part; text hash; declared origin; exact source coordinate mapping; finite ordered times parsed as decimals and converted exactly to ticks | Missing or inconsistent input, never guessed part or rounded ticks |
| Speech anchors | Transcript segment and word IDs; exact ordered substring match; zero-based half-open UTF-16 offsets; original text hash; declared clock crosswalk and existing admission guards | Unbound/refused with reason; no text normalization used to invent a match |
| Reviewed source anchors | Stable mention ID resolving to record ID/hash and full expression extent, or an exact record-plus-extent; identity and any equivalence carry review provenance | Mere overlap, containment or a name substring cannot assign identity |
| Annotation provenance | Preserve annotation origin and original column/extent. Materialize a field-specific anchor only with an exact producer mapping | Mixed annotation does not become an observed visual or speech fact |
| Source identity | A supplies reviewed source-only identity relations; B supplies admitted automatic relations; D supplies its declared annotation-name basis | Absent candidate and unknown identity remain distinct; no confidence-as-licence |
| Recall attribution | Recall span and context; alternatives; source entity plus claim role, occurrence/state and context, each with its evidence path | Entity equality alone cannot prove the recalled role, event, state or target |
| Reported content | Preserve assertion/belief context unless a separate evidenced promotion establishes world state | A reported death is not automatically a narrated-world death |

The recall CSV row coordinates are not UTF-16 coordinates. Any later recall text assembly must
record its own reversible row-to-character mapping and byte hash. In UTF-16, supplementary
characters occupy two code units; test Unicode and punctuation explicitly. Stable mention IDs
are accepted only through their checked lookup against the pinned graph and exact extent.
An explicitly reviewed equivalence between two extents may be followed with its receipt.
Presence in the same row, a shared descriptor, off-screen absence or embedding similarity
cannot silently establish identity, distinctness or state continuity.

The experimental result for every decision must contain:

- decision and run IDs; all source/recall anchor IDs and input hashes;
- proposed source referent alternatives, or explicit unresolved/external outcome;
- separate content, person, role, occurrence/state, context and target findings with evidence;
- final supported/contradicted/unresolved/external finding, without converting system uncertainty
  into a participant error;
- all observed failure stages and the earliest blocking stage: input, candidate, anchor binding,
  referent inference, or role/event/context/target attribution;
- reference label and correctness only in the evaluator's joined output, never in arm inputs.

An absent dialogue candidate is not by itself an input failure: source words may be admitted
while the inventory omits the entity, and visual/annotation candidates may still exist. All
arms retain that case. Record observed stages without claiming to have tested unreachable later
stages. Keep a supported local fact separate from crediting it to this recall argument. Raw
model scores are not probabilities or calibrated acceptance.

The controls below are synthetic required witnesses for the future binder, not tests already
passed by the census tool. Include a matching positive control for each rejection.

| Contrast | Required discrimination |
|---|---|
| Full expression `Dana's sister` versus the overlapping name `Dana` | Different referents; exact full-expression anchor succeeds, overlap-only lookup refuses |
| Same person, different actor/recipient roles | Correct identity does not credit a swapped role |
| Same person, different occurrence or changed state | Identity does not license time/state continuity |
| A speaker reports a state versus an independently established world state | Assertion context remains distinct; separately evidenced promotion can succeed |
| An anchored off-screen referent versus an absent inventory candidate | Off-screen is not inherently unresolved; missing candidate is visible |
| External or genuinely ambiguous recall versus an evidenced target | No forced source assignment; alternatives and unknown remain explicit |
| Supported local action with an unsupported modifier | Preserve local support without crediting the unsupported whole attribution |

## Decision rule and handoff

The current result is **not evaluated**, not GO. The agreed engineering utility screen requires
at least 15 human-resolvable natural decisions in the assessed partition, at least 80% correct
attribution among those decisions, zero observed false attribution/contradiction, and every
discriminating control passed. Discovery and confirmation are reported separately. Also report
correct, wrong, unresolved and input-failed counts and answer coverage over every frozen natural
decision, with reference-unknown cases separate from scorable correctness. Natural decisions and
designed controls must never share a denominator. Small, clustered observations do not certify
a population precision rate.

If A fails, inspect the failed stage and allow one targeted seam repair before the untouched
confirmation run. A confirmation failure stops or reframes the approach; it does not spawn an
unbounded series of infrastructure slices. If B loses through absent discourse referents, test
an evidence-citing scene entity inventory/coreference repair first. ASR, tracking and diarization
are justified only by measured failures that require them. If C wins under the same error gate,
that selects a comparative follow-up, not automatic production adoption.

The motivating body/identity diagnostic and paired negatives remain a named witness. If human
review finds it resolvable, we cannot claim the issue solved while it remains unresolved. If the
evidence is insufficient, report explicit ambiguity. Its historical reuse makes it regression
evidence, not fresh validation.

The optional pseudonymized condition is a paired naming-sensitivity check, budgeted separately.
Rename every relevant text input and label consistently across compared arms; score the gate
on original names. Renaming does not uniquely test plot memorization and does not replace a
new-film evaluation.

Next: reconcile the two feasibility halves, choose the qualified human reviewer, complete the
blind reference census and source-only annotations, seal source scope/costs and confirmation
custody, then commission the bounded experimental binder/comparison. New spend, participant-data
routing and production policy amendments remain owner decisions when concrete. The existing
constraint on new paid model runs before S0/S1 land remains. No new model calls were made for
this packet.
