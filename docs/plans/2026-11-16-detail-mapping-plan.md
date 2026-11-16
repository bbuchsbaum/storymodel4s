# Detail-level mapping after the scene preview (2026-11-16)

This revision amends the [working plan][preview] and the [roadmap](../ROADMAP.md). Its Mote
epic is `bd-01M7J5P1QW4C3ZRMQSGPNPQPFS`. The owner asked for it in chat on 2026-11-16, after a
review of how far the library is from supporting detailed scientific analysis of a film, a
recall and the mapping between them.

## Why the plan changes

The scene preview is close. Deliveries 1 to 3 are done, the ordinary-transcript entry point
has landed, and FilmFestival automatic input reaches 78.5% strict referent scene agreement.
A bundle, however, records only a scene, a film, cited rows, alternatives and a self-reported
confidence for each recall unit. That supports one measure, localization. The analyses the
library exists for need more:

- **Fidelity:** which recalled details are accurate, gist-correct with a wrong detail,
  inferred or invented.
- **Coverage and omission:** which film content each person recalled, and which they left out.
- **Participants:** who did what, linked to a stable cast, with speakers on dialogue.
- **Detail kinds:** event, perceptual, dialogue, mental state and external or semantic content.
- **Order:** an order measure that the mapper's use of surrounding recall does not contaminate.

The roadmap's route to these was E1 to E4: a compiled film event graph, a parsed recall event
graph, typed matching and structure-aware decoding, in that order. Two results argue for
another route:

1. Direct LLM mapping beat the structured pipeline without any event graph: 79.2% against
   61.0% participant-averaged scene-exact on Sherlock development (seen data), and 75.4%
   against 55.2% on the registered read of five new participants. Structure has not yet shown
   a measured benefit on this path.
2. A graded verifier (`tools/sherlock_mapper/verify_claims.py`) already judges recalled claims
   against film rows. On the owner's 50 calibration items it cut false contradictions from 19
   to 0, and its gist-detail calls on owner-supported claims held in 16 of 17, fully or partly
   ([mapper plan](2026-11-08-sherlock-mapper-plan.md)). The missing pieces it exposed were
   speaker labels and a severity field, not an event graph.

So detail structure is built by extending the answers and the source dictionary that already
travel through the common pipeline, one evaluated field set at a time. The full event graph is
kept for analyses that need it, such as causal centrality.

## What does not change

- The scene preview's deliveries, claim boundary and finish line stand. The preview ships
  without detail fields.
- Each capability is an input adapter or answer field set in the same bundle, read by the same
  readers and scored by the same scorer (uniform outputs across datasets and conditions).
- Rules 3, 7 and 13 bind every field. An LLM field is a proposal. A deterministic admission
  step checks its spans, citations and identifiers, and "unverifiable" and "not assessed" are
  explicit states, never a default value. A self-reported confidence is not a probability.
- Development on seen data is engineering. A claim of accuracy needs fresh items and a frozen
  protocol. Exits marked *fresh* below are such claims; the others are engineering exits.

## Capabilities

Each capability adds named fields to the bundle and has one exit. A field set stays
experimental in the claim boundary until its exit is met. Thresholds are proposals for the
owner to fix before the frozen protocol, not results.

### DL1. Recall detail inventory

Each recall unit is split into detail atoms. An atom has an exact span in canonical recall
coordinates, a kind and a memory address. The kinds extend the `interview` module's
`DetailAtom` cases (event, participant, attribute, temporal, spatial, perceptual, mental state,
relational) with reported speech. The address separates film-episode content from the
recaller's own knowledge, and discourse functions (commentary, inference, repair, repetition)
follow `InterviewDiscourseFunction`. Internal and external totals are a scoring-policy
projection of kind, address and discourse function, as `interview` requires, never a stored
kind. A span-anchored atom is a recall-side proposal; it is not yet a `DetailAtom`, which
refers to resolved situations and entities. The ADR records this split.

- Admission: every span lies inside its unit, atoms do not overlap, and the share of each
  unit's text left uncovered is reported.
- **Exit (fresh):** atom-level agreement with blind human detail coding reaches at least 80%
  of human-human agreement on the same units, with both reported.

### DL2. Source cast and row record

This is a slim first step of E1. Each film gets a cast with stable identifiers, drawn from the
annotations, the speech transcript and the frames. Each annotation or automatic row is tagged
with its participants, its action and, for dialogue, its speaker. It subsumes the
speaker-labelled evidence milestone (M3).

- Admission: every tag cites row text, a speech span or a frame. A cast member is never
  asserted without a citation. Identity links follow ADRs 0024 and 0027.
- **Exit (engineering):** an owner spot-check of three scenes accepts the record, with a
  coverage report for Sherlock and one FilmFestival film beside it (the share of rows with an
  identified participant and of dialogue with a speaker).

### DL3. Detail binding with graded fidelity

Each DL1 atom is bound to the source rows that support it, or to none, and each participant
mention is bound to a DL2 cast identifier or marked unresolved. The fidelity grade has these
values:

- supported;
- gist-correct with a detail error, with a severity;
- contradicted;
- recaller inference;
- external (the recaller's own knowledge or commentary, not film content);
- invented (presented as film content, absent from the source);
- unverifiable from the record;

- misread (the recall was misunderstood by the judge);
- not assessed (no judgement was attempted).

External content and invented film content are separate grades, as in the verifier, so they
are never conflated (rule 7). The grade ports the graded verifier into the common pipeline as a
new reply protocol. Scene assignment is not recomputed from it.

- **Exit (fresh):** on items coded blind, precision of the contradicted and gist-detail grades
  is at least 0.8 each, with precision and recall of every grade reported. The owner's 50 seen
  items calibrate the rubric and are not a test.

### DL4. Coverage and omission

This is derived from DL3, not asked of a model. For each source row or scene it records which
participants recalled it, with which grades. Coverage is reported per grade, so a row cited
only by contradicted or unverifiable atoms is not counted as recalled. A row with no binding is
"not evidenced as recalled", which is a claim about the record, not proof of an omission; the
report names it that way.

- **Exit (fresh):** row-level coverage agrees with human coverage coding on a sample at a
  kappa of at least 0.6, with the denominator stated.

### DL5. Order-independent condition

A separate condition maps each unit without the surrounding recall (the unit alone, or a fixed
local window), so that scene labels do not depend on recall order. Its labels are compared with
full-context labels unit by unit.

- **Exit (engineering):** a recorded claim-boundary decision on whether order and chronology
  move from "not supported" to "experimental", based on unit-level agreement with full context
  and with the human reference.

### DL6. Who-did-what in recurring scenes

This was M4. It becomes the first hard test of DL2 and DL3 together, on a bounded set of
recurring scenes where scene labels cannot make the distinction.

- **Exit (fresh):** the share of participant-action pairs judged correct exceeds the
  scene-only baseline, with an interval on the difference that excludes zero.

## Human reference

One coding effort serves the preview and DL1 to DL4. Delivery 5's double-coding rubric therefore
includes detail atoms, fidelity grades and coverage from the start, so coders are not recruited
twice. Coders do not see model answers, disagreements are kept before adjudication, and the
budget follows a timing pilot. The coding sheet is generated from bundles; the review workbench
is rebuilt only if the pilot shows a sheet is too slow.

Fresh items need participants or films not yet read. FilmFestival's participants have all been
used, and Sherlock has only the untouched remainder, which E5 also needs. That remainder is read
once: the fresh DL exits and E5's untouched read are one pre-registered read, frozen after DL1,
DL3 and DL4 are built. An exit that cannot wait for it uses a new collection. Choosing the
validation data is an open decision.

## Order

1. Finish preview delivery 4 (`bd-01M7H69QWE0NKSNB8XWW6MC02H`).
2. Write the joint coding rubric and run the timing pilot. This does not wait for delivery 4;
   it runs alongside it and delivery 5.
3. DL2 and DL1 in parallel: the cast record has no dependence on recall, and DL1 needs only
   prepared transcripts.
4. DL3 after DL1 and DL2; DL4 after DL3.
5. DL5 can run at any point after delivery 4. It needs no new fields.
6. DL6 after DL3.
7. E1 beyond DL2 (causal links and event hierarchy), E3 and E4 are retained research. Each
   starts only when a measured DL failure or a named analysis needs it.

## Analyses each step supports

| After | Newly supported, subject to its exit |
|---|---|
| Preview | Which scene a unit narrates or refers to; scene-level recall coverage, described only |
| DL1 | Amounts of internal and external detail, by participant and scene |
| DL2 | Participant and speaker structure of the film |
| DL3 | Recall fidelity: accurate, gist-preserved, distorted, inferred and invented detail |
| DL4 | Omissions and row-level coverage; comparisons between participants and groups |
| DL5 | Recall order and chronology, if its exit supports them |
| DL6 | Who did what in recall, against the film's cast |

## Open decisions

1. An ADR for the new public vocabulary (detail kinds, fidelity grades, cast identifiers),
   naming the rejected alternative of a full event graph first. The owner decides ADRs.
2. Validation data for fresh items: the untouched Sherlock remainder, a new FilmFestival-style
   corpus, or a new collection.
3. Who codes, the budget, and whether the owner's within-scene lane
   (`bd-01M2TA9ZK64GTG3526RMNWNHCT`) folds into the joint rubric.
4. Whether the evidence-binding programme (Mote `recovery-evidence-binding-20261023`) and the
   claim-assessment candidates in review are absorbed into DL2 and DL3 or kept as matched
   comparisons.

[preview]: 2026-11-09-categorical-mapping-and-transfer-plan.md#revision-2026-11-10-the-scientific-preview
