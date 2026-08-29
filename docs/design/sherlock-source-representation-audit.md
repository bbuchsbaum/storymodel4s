# Sherlock video-annotation source audit

**Bead:** `bd-01M17S2SGW2Z7NSR478SHSZRNW`

**Status:** primary-source inspection complete; conditional source-side use only

**Inspection date:** 2026-08-29

**Scope:** the released video-annotation workbook, the code that transforms it, the resulting
episode-side arrays, the two papers that define the segmentation, and the licence records attached
to those artifacts. No participant recall transcript, recall audio, or episode video was fetched,
opened, copied into this document, or added to the repository.

## Answer

The existing Sherlock annotations can serve as a **human-derived temporal source atlas and evidence
bank**. They cannot serve as a ready-made `NarrativeGraph`, a direct observation of the episode, or
an independent recall-quality gold standard.

The useful part is substantial. The released workbook supplies 1,000 ordered microsegments with
time coordinates, 50 coarse scene markers, a narrative-description field, character-presence,
focus and speaker fields, location, and several sensory or presentation features. This is enough
to avoid video decoding and automated audiovisual extraction in a first diagnostic. It can anchor
source order, coarse scene membership, feature tracks, and evidence-backed proposals about
situations and participants.

Three qualifications keep the answer conditional:

1. Every semantic field is an independent coder's representation of the copyrighted episode. It
   is a derivation, not the episode itself. Relations, stable entities, situations, propositions,
   and narrative hierarchy are not explicitly encoded.
2. The released seconds are run-local rather than one monotone clock, and the 50-scene and
   1,000-segment boundaries came from different coders. A source atlas needs an explicit,
   receipt-bearing timebase and boundary reconciliation.
3. The only detected licence is the analysis repository's root MIT file. It does not expressly
   identify the annotation workbook or settle rights in episode-derived descriptions and
   on-screen words. The separately CC0 OpenNeuro dataset does not contain this workbook.

The annotations therefore shorten the film path by removing the need to possess or process video
bytes. They do **not** remove the need for the presentation-axis/evidence work in the film-source
ADR, the proposition-provider vertical, or an annotation-specific rights decision.

## Classification contract

This inventory uses the same three classes as the other external-source inventories.

| Class | Meaning here |
|---|---|
| **Observation** | A released artifact fact or a response actually recorded from a coder/rater. It is not automatically a fact about the episode. |
| **Derivation** | A segmentation, label, description, alignment, aggregation, model output, or other value produced from the episode or upstream annotations. |
| **Unknown** | The primary records inspected here do not establish the relevant provenance, semantics, or rights. Unknown is never promoted to Observation. |

An annotation may be an Observation of what a coder entered while remaining a Derivation of the
episode. The second role is the one that matters when it is used as the source side of a recall
analysis.

## Authoritative records and snapshot

| Record | Inspected identity | What it establishes |
|---|---|---|
| [ContextLab repository](https://github.com/ContextLab/sherlock-topic-model-paper/tree/81f90b8afa6dd714b780208bd89d1f1a26159ff5) | `master` at `81f90b8afa6dd714b780208bd89d1f1a26159ff5` | Released raw annotations, participant transcripts, processed arrays, analysis code, paper source, and root MIT file. Participant paths were inventoried by name only and their contents were not fetched. |
| [Raw annotation workbook](https://github.com/ContextLab/sherlock-topic-model-paper/blob/81f90b8afa6dd714b780208bd89d1f1a26159ff5/data/raw/Sherlock_Segments_1000_NN_2017.xlsx) | Git blob `2b3c456af349e88b01b87cb8fd0ce4ab4ff016cf`; 176,658 bytes; local SHA-256 `3ffd57e8d2fc2f7e7c47e86d758f64f158fc406c82f5a37f7d420fc2a929ea95` | One sheet named `Segments`, 1,000 data rows, 23 columns. Only headers, numeric time structure, missingness, and aggregate cardinalities were reported. No annotation prose is reproduced here. |
| [Heusser, Fitzpatrick, and Manning (2021)](https://doi.org/10.1038/s41562-021-01051-6) | *Nature Human Behaviour* 5, 905-919 | The 1,000 time segments were annotated by an independent coder; lists the semantic feature families; documents removal of two scan-break rows, sliding-window topic modeling, interpolation, and HMM event derivation. |
| [Exact paper source](https://github.com/ContextLab/sherlock-topic-model-paper/blob/81f90b8afa6dd714b780208bd89d1f1a26159ff5/paper/main.tex) | Git blob `07ee0d3ea6af9bffdd887e17909e27071c947b9e`; 117,364 bytes | Reproducible methods text for the workbook-to-topic/HMM pipeline. |
| [Exact supplementary source](https://github.com/ContextLab/sherlock-topic-model-paper/blob/81f90b8afa6dd714b780208bd89d1f1a26159ff5/paper/supplementary_information.tex) | Git blob `91f969211687dbc78ddb5d2b82c27bae5a0daaa5`; 22,336 bytes | Records that the chosen episode window, recall window, and topic count were carried forward from an optimization using hand-counted recalled-scene performance. |
| [Exact topic-model notebook](https://github.com/ContextLab/sherlock-topic-model-paper/blob/81f90b8afa6dd714b780208bd89d1f1a26159ff5/code/notebooks/main/topic_model_analysis.ipynb) | Git blob `3dafd5ce1baf5a79b18a53d8a29cf837ec501edf`; 22,545 bytes | Reads the workbook, forward-fills scene markers, drops the two break rows, restitches run-2 seconds, selects the nine content fields, and constructs episode topic windows. |
| [ContextLab root licence](https://github.com/ContextLab/sherlock-topic-model-paper/blob/81f90b8afa6dd714b780208bd89d1f1a26159ff5/LICENSE) | Git blob `83d8af5f8f1723faeec472c06e0f330a40c86981`; 1,087 bytes | Standard MIT text naming Contextual Dynamics Laboratory and referring to software and associated documentation; it does not identify the annotation workbook or its coder. |
| [Summer-MIND Sherlock tutorial](https://github.com/Summer-MIND/mind_2018/tree/77e8ee9102837b921883e033b72e246fc5e3c535/tutorials/sherlock_nifti_kit_v2_withdata) | `master` at `77e8ee9102837b921883e033b72e246fc5e3c535` | States that the 50 scenes and 1,000 labels were coded by different people and that corresponding boundaries may differ by 0-3 seconds. No repository licence was detected. |
| [Chen et al. (2017)](https://doi.org/10.1038/nn.4450) | *Nature Neuroscience* 20, 115-125 | Original viewing/recall experiment and 50-scene recall-scoring frame. |
| [OpenNeuro `ds001132` v1.0.0](https://openneuro.org/datasets/ds001132/versions/1.0.0) | DOI `10.18112/openneuro.ds001132.v1.0.0` | Public BIDS imaging dataset. Its current public file tree has no annotation workbook, so its CC0 posture is not evidence for the workbook's licence. |

The same parsed 1,000 by 23 table also appears in the pinned Summer-MIND checkout at
`tutorials/sherlock_nifti_kit_v2_withdata/subjects/Sherlock_Segments_1000_NN_2017.xlsx`.
That archive is 161,074 bytes with SHA-256
`5271b7704ccebfc7cae2228c78faf472c0ba5530c1a45e4c784757057bf476df`. Its headers and every
parsed cell are identical to the ContextLab copy, but its ZIP/XLSX bytes are not. Intake should
retain the exact artifact digest and also compute a versioned canonical-table digest; neither
digest substitutes for the other.

## What the raw workbook actually contains

### Coordinate and grouping fields

| Column family | Measured structure | Scientific role |
|---|---|---|
| `Segment Number` | 1,000 non-missing, unique values | Stable row key within this artifact. It is not a narrative-node identity. |
| Start/end seconds | 1,000 pairs | Run-local media intervals. They require scan-break handling before becoming one presentation axis. |
| Start/end TRs at 1.5 seconds | Two rows missing both TR coordinates | Acquisition-aligned coordinates. They are not interchangeable with raw seconds without the declared stitch. |
| `Scene Segments` | 50 non-missing marker cells | Sparse starts for a coarse 50-scene partition after forward fill. The boundary relation to microsegments is approximate because a different coder supplied the scene segmentation. |

Forward-filling the scene markers yields 50 groups. Group sizes range from 1 to 66
microsegments, with a median of 14. This is a useful two-level ordering scaffold, not a validated
narrative hierarchy.

### Coded content and feature fields

| Column | Non-missing rows | Admissible interpretation |
|---|---:|---|
| `Scene Details - A Level` | 1,000 | Human description of the interval; proposal evidence for situations and local propositions. |
| `Space-In/Outdoor` | 1,000 | Coder classification of setting type. |
| `Name - All` | 1,000 | Character-presence labels; proposal evidence for entities and participation. |
| `Name - Focus` | 949 | Focused-character label; an attention/presentation feature, not automatically narrative salience. |
| `Name - Speaking` | 698 | Speaker label; does not encode the proposition spoken or its discourse context. |
| `Location` | 1,000 | Coder location label; requires ontology resolution before becoming a narrative entity/context. |
| `Camera Angle` | 994 | Presentation feature; not story-world geometry. |
| `Music Presence` | 1,000 | Audio-presentation classification; diegetic status is not encoded. |
| `Words on Screen` | 37 | Coder transcription of visible text; annotation evidence, not a licensed subtitle track. |
| Four arousal and four valence columns | Seven complete; one valence column has 996 values | Observations of four raters' responses. Any film-affect estimate is a derivation and must retain rater coverage. |

The paper's topic pipeline selects the nine fields from narrative detail through words on screen.
It does not use the sparse scene marker or arousal/valence ratings in that text window.

## Released derived episode artifacts

| Artifact | Pinned identity | Measured structure | Classification |
|---|---|---|---|
| `data/processed/video_text.npy` | Git blob `9569d011169382778160a1e9c168b362bdd92fbb`; 51,663,296 bytes | Serialized annotation-text windows; content was not fetched in this audit. | **Derivation** |
| `data/processed/topic_model.npy` | Git blob `ca88047684988d8eb2c317b71daf115f3f40d361`; 3,475,933 bytes | Serialized fitted model; not unpickled in this audit. | **Derivation** |
| `data/processed/video_eventseg_model` | Git blob `625466e6e38d528582b3aa0b78e70a7c26fa13d2`; 500,414 bytes | Serialized HMM/event model; not unpickled in this audit. | **Derivation** |
| `data/processed/video_events.npy` | Git blob `1cd0ac974743aefaf7c0eb891f3a1d6ae280786f`; SHA-256 `f9df8eaa667e98d4dd078fda04cabe55ac52d62c18f3c4e9da61ffd7709069f9` | Finite `30 x 100` `float64` matrix: one topic vector per derived HMM event. | **Derivation** |
| `data/processed/video_event_times.npy` | Git blob `3352cdad9aadad1905cbd52d782305210cd45719`; SHA-256 `2fc563ff67a0af0d59f6cada382aeac2c0443d4f1bcbdcd99c73dd15685ee695` | `30 x 2` integer bounds spanning indexed TRs 0-1975, adjacent without gaps in the published array. | **Derivation** |

The 30 HMM events are not the 50 human-coded scenes and neither population is the 1,000
microsegments. An intake contract must name which one it uses. Calling all three simply `event`
would erase provenance and make cardinality checks incapable of detecting a wrong artifact.

## Timebase and boundary court

The workbook is not valid under a naive single-clock parser.

- Segment 13 has equal start and end values in both seconds and TRs. It is a zero-duration row.
- Segments 481 and 482 are scan-break material and have no TR coordinates.
- Segment 483 resets seconds to 0 while TR numbering resumes at 947.
- The raw seconds contain one reset/overlap and several gaps. These facts are consistent with a
  two-run acquisition, not one continuous seconds axis.
- The published topic notebook removes the two break rows and adds the end of run 1 to run-2
  seconds before interpolation. That operation is part of the source derivation and needs a
  receipt; the corrected clock is not present in the workbook.
- The Summer-MIND record says the coarse scenes and fine labels were coded by different people,
  with boundary discrepancies of up to three seconds. Exact containment cannot be reconstructed
  merely by forward-filling a marker.

A lawful loader should therefore retain at least: raw row number, run identity, raw run-local
seconds, raw TR bounds when present, the stitch recipe and version, and any chosen coarse-scene
membership with an explicit reconciliation status. It should reject or separately classify the
zero-duration and break rows rather than silently coercing them into ordinary evidence spans.

In the approved film-contract vocabulary, the annotation row/time interval is a **locator**, the
declared repaired presentation axis supplies the **denominator**, and an absent TR coordinate
remains typed missing rather than becoming zero through an **absence-erasing derivation**. Raw
run-local seconds, repaired seconds, and TRs may render as numbers but are not interchangeable
coordinates.

## Source-contract capability

| Source-side need | Support from released annotations | Boundary |
|---|---|---|
| Presentation order | **Yes** | Segment order and repaired time coordinates are strong after a receipt-bearing stitch. |
| Coarse event/scene order | **Yes, derived** | Fifty scene starts exist, but another coder supplied them and exact fine/coarse boundaries may disagree. |
| Fine interval evidence | **Yes, derived** | Microsegment rows and time intervals can anchor claims to the annotation, not directly to frames. |
| Situations | **Proposal only** | Narrative descriptions are rich inputs, but no situation IDs, contexts, or acceptance states are encoded. |
| Participants/entities | **Proposal only** | Presence/focus/speaking labels support entity proposals. Stable IDs, aliases, identity uncertainty, and coreference are absent. |
| Relations and roles | **No explicit contract** | Co-presence and speaker fields are not semantic roles or typed relations. Relations require parsing/resolution of the description. |
| Narrative hierarchy | **Ordering scaffold only** | Microsegment-to-scene grouping is available; episode/sequence/subplot/goal hierarchy is absent. |
| Dialogue propositions | **No** | Speaker is present but utterance content, addressee, reported/believed contexts, and dialogue-turn boundaries are not encoded. |
| Visual-action propositions | **Proposal only** | The description may state visible action, but the workbook has no frame/track evidence or direct audiovisual access. |
| Sensory and presentation features | **Partial** | Camera, indoor/outdoor, music, visible text, arousal, and valence exist as separate fields; motion, object tracks, prosody, sound source, and diegetic status do not. |
| Exact source evidence under current code | **No** | Current accepted claims cite `SpanSet`; these records need row/time evidence. Serializing them into text would make coder prose look like the episode and would erase the source kind. |

The minimum honest claim is therefore: **a structured human annotation of the episode supports a
portable temporal evidence atlas and proposal generation**. It does not support the stronger
claim that the episode has already been compiled into the project's canonical narrative
semantics.

## Methodological cost

Aligning a recall to these records measures agreement with the coders' representation of the
episode. Every result inherits at least four choices:

1. where the 1,000 microsegment boundaries were placed;
2. where a different coder placed the 50 scene boundaries and how the two layers were reconciled;
3. which characters, locations, actions, and presentation features the workbook records or omits;
4. whether the analysis uses raw rows, 50 scenes, or 30 model-derived events.

This is defensible if the output is labeled accordingly. It is not direct episode recall accuracy,
veridicality, or completeness.

The published topic/HMM channel has an additional anti-circularity cost. The main pipeline:

- removes the two scan-break rows, leaving 998 annotations;
- concatenates nine feature columns into overlapping windows of up to 50 annotations;
- fits a 100-topic LDA model, of which 32 topics receive nonzero weight;
- interpolates the window trajectory to 1,976 episode TRs;
- chooses a 30-event HMM representation.

The supplementary methods say the episode window length, recall window length, and topic count
were carried forward from an earlier grid search whose objective used hand-counted recalled-scene
performance. Consequently, those parameters have seen a recall outcome. The released 30-event
representation may be useful as a **Diagnostic derivation**, but it is not an independent gold
court for recall performance on the same corpus. A calibrated evaluation would need held-out
retuning, a separate annotation court, or an explicit limitation plus a feature-use ledger that
prevents this representation from serving simultaneously as model input and independent truth.

## Licence and redistribution

The licence result is **Unknown for the annotation content**, not because the file is unavailable,
but because the available licences do not close the component-rights question.

- The ContextLab repository is public and GitHub detects its root `LICENSE` as MIT. The README
  says the repository contains all data analyzed in the paper.
- The standard MIT text grants rights in the software and associated documentation. It does not
  name the workbook, the independent coder, or data-specific redistribution terms.
- The workbook contains episode-derived narrative descriptions and transcriptions of words shown
  on screen. A repository-level software licence is not evidence that the licensors own every
  underlying component right in those cells.
- The Summer-MIND repository publishes a parsed-identical copy and has no detected repository
  licence.
- OpenNeuro `ds001132` is distributed through an open-data service, but the current dataset tree
  contains imaging/task files rather than this workbook. Its CC0 status must not be transferred to
  an artifact it does not contain.
- None of these facts licenses the BBC episode itself. The present route does not require episode
  bytes, which is a major reduction in exposure, but it does not prove the annotation text may be
  vendored by this library.

Until the owner or data custodian confirms annotation-specific terms, the low-exposure posture is:

1. keep workbook and annotation text outside git;
2. commit only locator, commit/blob identity, byte digest, schema, aggregate validation facts, and
   code that operates on a caller-supplied local file;
3. do not place annotation descriptions, visible words, participant recall, or episode frames in
   fixtures, tests, comments, or documentation;
4. preserve the root-MIT record as evidence without upgrading it into a data-specific licence;
5. obtain explicit component-rights confirmation before redistribution or publication of derived
   text excerpts.

This document does not admit a new story text under the binding
[`story-text-admission-checklist.md`](story-text-admission-checklist.md): it records only source
locators, artifact identities, column names, aggregate validation facts, and methodological
conclusions. No annotation-cell prose, participant language, episode dialogue, or frame is
reproduced.

## Minimum admissible intake contract

The first implementation should be narrower than a general film platform and stricter than a CSV
adapter.

1. **External immutable manifest.** Pin repository commit, Git blob, downloaded byte SHA-256,
   sheet name, schema version, and a canonical parsed-table digest. Do not identify the source by
   filename alone.
2. **Typed coordinate population.** Keep run-local seconds, TR coordinates, and stitched
   presentation time distinguishable. A bare normalized `Double` cannot say which axis it came
   from.
3. **Receipt-bearing repair.** Represent removal of the two break rows and the run-2 time offset as
   an explicit derivation. Preserve rejected/raw rows so the repair is auditable.
4. **Fail-closed validation.** Check the expected 1,000 rows and 23 columns, unique segment keys,
   50 scene markers, required-field coverage, finite coordinates, interval validity, and declared
   handling of the zero-duration row.
5. **Annotation evidence identity.** Anchor proposals to artifact ID, row ID, and raw/derived time
   support. Never mint text evidence by pretending the coder description is a transcript of the
   episode.
6. **Field separation.** Character presence, focus, speaking, location, camera, music, visible
   words, arousal, and valence remain distinct channels. Missing is not zero and one channel does
   not imply another.
7. **Proposal-only semantics.** Description parsing may propose situations, entities, roles,
   contexts, and relations. Only the deterministic resolver may accept them, with the annotation
   row as derived evidence.
8. **Named segmentation population.** `Microsegment1000`, `Scene50`, and `TopicHmmEvent30` must not
   collapse into one unqualified event ID family. The last is a sidecar derivation with a feature-
   use ledger.
9. **External-content tests.** Commit synthetic schemas and numeric boundary fixtures only. Run
   source-bearing integration tests against a local file after the rights gate; never check the
   workbook or its prose into git.
10. **Diagnostic-only first result.** The first Sherlock result reports coverage, unresolved
    proposals, and sensitivity to the 50-scene versus 30-HMM partition. It cannot make a calibrated
    performance claim.

## Cost and recommended next step

The annotations remove the most expensive acquisition dependency: no video decoder, frame store,
shot detector, captioner, or redistributable episode asset is needed to ask the first scientific
question. They also provide enough content to do more than ordering-only alignment.

They do not make the path a trivial text adapter. The current library still needs a source-kind-
preserving row/time evidence axis, the proposition-provider path for both source descriptions and
text recalls, and a deterministic bridge from annotation proposals into narrative types. Treating
the spreadsheet as a `StorySource` would be shorter only because it would hide the scientific
distinction this project is designed to preserve.

Recommended sequence:

1. Record an owner/chief disposition on whether root MIT is sufficient for local scientific use
   and whether annotation-content redistribution remains prohibited.
2. Use the film-source ADR's presentation-axis analysis to define the smallest annotation-row/time
   evidence contract; do not generalize to frames, tracks, or audio until a real consumer requires
   them.
3. Build a local-only loader and validator for the pinned workbook with synthetic committed courts
   for scan breaks, zero-duration rows, boundary mismatch, missing ratings, and byte-versus-table
   identity.
4. Compile the 998 usable microsegment descriptions into proposal artifacts, retaining 50-scene
   membership as an approximate external grouping and keeping the published 30-event HMM channel
   as a separate Diagnostic sidecar.
5. Ask one bounded question: does the annotation-derived narrative source recover the published
   coarse recall ordering without using the outcome-tuned topic/HMM channel? Report failure and
   unresolved coverage as results, not as ingestion errors.

## Final disposition

**Proceed, conditionally.** Sherlock supplies a usable source representation at the level of a
human-derived temporal annotation atlas. It is richer than an event list and materially shortens
the film-recall path. It is not the episode, not a canonical narrative graph, and not independent
gold. Keep the content external, make the time and segmentation derivations explicit, preserve the
coder model in every result, and close the annotation-specific rights question before any content
redistribution.
