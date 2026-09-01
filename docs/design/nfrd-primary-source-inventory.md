# NFRD primary-source inventory

**Bead:** `bd-01M17FAGN83C3ZT2VZ2VKHWTYJ`
**Status:** bounded Baseball inventory complete; aggregate-only git admission approved
**Inspection date:** 2026-08-29
**Scope:** public metadata, corpus documentation, and a bounded content-addressed local fetch.
Baseball source artifacts and the two survey workbooks were inspected. One smallest-by-byte Baseball
recall was processed only into non-textual summary checks to test whether released files were
placeholders. No participant language, questionnaire response, or recall audio was printed, copied
into this document, or committed; survey free-text cells were not read.

This is deliberately an NFRD-specific inventory. It is not a proposed general external-dataset
schema. The chief architect ruled that a general contract should be extracted only after at least
two real corpora have been inventoried.

## Disposition

NFRD remains the strongest near-term independent-recall corpus for `storymodel4s`. Three facts
govern the next step:

1. The source-to-recall compiler continues independently on the hand-authored WOG vertical path.
   NFRD inspection neither blocks nor is blocked by it.
2. The released recall text is a **derived content transcript**, not a raw observation of speech.
   Google Speech-to-Text, professional correction, deletion, and normalization occurred before
   release. Several operations cannot be reversed from the public artifacts.
3. The chief's amended data-class ruling permits local fetching and inspection of public,
   open-licence, researcher-de-identified corpora without further owner sign-off. NFRD is in that
   class: the public release is CC0, the paper records IRB oversight and data-sharing consent, and
   audio was deliberately withheld to reduce re-identification risk. Component rights, downstream
   provider disclosure, and git redistribution remain separate questions.

The recommended posture is therefore **proceed with a bounded, content-addressed local fetch;
keep participant content external to git; and preserve every upstream derivation in receipts**.
This paragraph supersedes the initial owner-sign-off gate recorded earlier on 2026-08-29.

## Classification contract used here

Every scientific field family below is assigned one of three classes.

| Class | Meaning in this inventory |
|---|---|
| **Observation** | A value recorded from the task, participant, or released stimulus. This does not mean objective truth; self-report remains self-report. |
| **Derivation** | A value created by transcription, normalization, pseudonymization, alignment, filtering, aggregation, modeling, or manual adjudication. The operation and reversibility must travel with it. |
| **Unknown** | Public primary records do not establish whether the field is observed or derived, or do not establish the relevant provenance. Unknown is never silently promoted to Observation. |

The class belongs to a field, not to a file extension. A TextGrid, for example, contains derived
token labels and derived timings; its existence as a released file is merely repository metadata.

## Authoritative records and snapshot

| Record | Inspected identity | What it establishes |
|---|---|---|
| [Raccah et al. data descriptor](https://doi.org/10.1038/s41597-024-04082-6) | *Scientific Data* 11, 1317 (2024), published 2024-12-03 | Cohort, task, stimulus provenance, consent statement, transcription and alignment methods, and published validation analysis. |
| [OSF project `h2pkv`](https://osf.io/h2pkv/) | Public project, created 2023-08-16; modified 2025-04-17 when inspected; **not an OSF registration** | Released experiment materials, recall representations, survey workbooks, and node-level CC0 declaration. Because this is a mutable project rather than a registration, the node ID is not an immutable dataset version. |
| [OSF API record](https://api.osf.io/v2/nodes/h2pkv/) | Node `h2pkv`, title `Free recall of narratives` | Machine-readable file IDs, sizes, timestamps, and SHA-256 hashes. |
| [Analysis repository](https://github.com/phoebsc/Naturalistic-Free-Recall-Dataset/tree/e1aea19210858b5ce6b32128e8df42b137effb8f) | `main` at `e1aea19210858b5ce6b32128e8df42b137effb8f` | Paper analysis code, source-story transcript copies, Pieman boundary material, and derived result arrays. The GitHub repository has no detected root licence. |
| [Project Gutenberg ebook 27584](https://www.gutenberg.org/ebooks/27584) | *Baseball Joe in the Big League*, plain-text revision updated 2021-01-04 | Exact textual source for the released Baseball transcript after declared lexical normalization. |
| [LibriVox catalog item](https://librivox.org/baseball-joe-in-the-big-league-by-lester-chadwick/) | *Baseball Joe in the Big League*, catalog date 2022-07-11, read by Donald Cummings | Exact performance and chapter identity for the Baseball stimulus; LibriVox states that its recordings are public domain in the United States. |
| [Internet Archive item](https://archive.org/details/baseballjoebigleague_2207_librivox) | `baseballjoebigleague_2207_librivox`, section 01, “Two Letters” | Downloadable original/derivative audio encodings and item-level CC0 metadata used for the signal comparison below. |

An acquisition receipt must therefore pin the OSF file hashes and acquisition timestamp, not only
the mutable node ID. The paper DOI identifies the publication, not an immutable OSF file tree.

## Cohort and task inventory

The paper reports 229 native-English-speaking participants: 167 recruited through NYU SONA and 62
through Prolific, with 145 reporting female gender. Participants heard two of four English spoken
narratives and immediately recalled each aloud for at least four minutes. Story order was
randomized within two fixed pairs:

- Pieman and Eyespy: 116 released participant IDs;
- Oregontrail and Baseball: 113 released participant IDs.

The task instructed participants to recall in order while returning to missed earlier material if
needed. Backtracking is therefore a permitted task behavior, not automatically an error.

The paper records NYU IRB `IRB-FY2016-1357` and reports a consent statement allowing
non-identifying information to be used or shared in future research without additional consent. It
also reports that names were replaced with numerical IDs, only age and gender were released as
demographics, and recall audio was withheld to reduce re-identification risk. Those statements are
the primary human-subject basis for the chief's public/de-identified data-class ruling; they must
remain attached to the intake receipt rather than being replaced by the CC0 label alone.

## OSF artifact inventory

### Experiment materials

The public OSF tree contains the following non-participant artifacts. SHA-256 values are OSF
metadata, not hashes recomputed from downloaded content.

| Path | Bytes | OSF SHA-256 |
|---|---:|---|
| `experiment_materials/instructions_questionnaire.txt` | 3,264 | `56ed7c0d6c471491e4b8e1f8447c46433c27ebd263901a9d3eb553e52a61bdef` |
| `experiment_materials/psychopy/readme.md` | 0 | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `experiment_materials/psychopy/narrative_recall_prolific.js` | 190,320 | `d8795d506cdbfe130e7450c5146d69164b18cb5544507a0be9274828627b64a3` |
| `experiment_materials/psychopy/narrative_recall_prolific.psyexp` | 186,017 | `8bd988f55280508792bcc62e168e73ee6ef87dabe342a14bde5c6be06a3a9a27` |
| `experiment_materials/psychopy/conditions.xlsx` | 8,888 | `7a60cb8621cf6c9f5e80e9b0add0ef363aa0375244c2754cb1143d14eb030fe0` |
| `experiment_materials/psychopy/index.html` | 1,032 | `1ccfd9f881926568a949935a6931fc30c0b77ca1113bf8b6e2904e45b0ce76de` |
| `experiment_materials/psychopy/narrative_recall_prolific-legacy-browsers.js` | 190,553 | `f3e3cf9d51fe0fc9bb1eb8f8b1845e13edf83a1c662c94af6ae1de9c1308fa64` |

The released stimuli are:

| Story | Transcript bytes / SHA-256 | Aligned TextGrid bytes / SHA-256 | Audio bytes / SHA-256 |
|---|---|---|---|
| Pieman | 4,871 / `fd449f98ae44812e48d790fe961947126e82fbf6e8758ff7985f24226aee53bb` | 145,044 / `69e331121dcc4ae97bd1e9d21957c6245c8078cf5b6a585094725ba0cfe56b53` | 7,837,405 / `f050bed92140282d04f98918b870054710e49c45fb1a10bcfd0bef68fb9b6731` |
| Eyespy | 11,949 / `008b10d75eec810b6b5b2aa55736d05be972ec24ac81072b08d50f6cb5c77666` | 347,177 / `5b8e959bbe22038a7df9a0dd823e73e6d4bfbbe979d3644e6234f06b950639a0` | 16,711,474 / `234d5afb22d1e7c08ca26b5aeee7638bb7a42b924c9137c3d35e145d134e3af8` |
| Oregontrail | 11,906 / `6603c9595c090d22e88b4a07466f6c5006137dbad4026f0a221e041119f810c6` | 348,269 / `b0c2a19bc1606faa9ef40c4ab53fcf92869c5e04d79a87ddfe5fc540e72f3c9f` | 13,380,209 / `f1d7e8009975c5b9b3be0c6cdb2c9830f4c7aafdc50c3687143728c94a1f0381` |
| Baseball | 11,237 / `030810edbcd4bc159f098a8ab8b778a8e1914992174911e464c11ff1a686a900` | 319,931 / `c0dd0503e5d787e34f937f6bfc24c97f346a928a8165ecee4b956d5b940a2db5` | 12,289,426 / `ab4875527694890c1372d8333a8b5a20cf558dffc7617e6352efe328322ba791` |

### Verified bounded fetch: Baseball

The Baseball source side is now pinned tightly enough for a local diagnostic model. Downloaded
bytes independently reproduced all three OSF SHA-256 values in the Baseball row above. The released
PsychoJS bundle loads `stories/baseball_audio.mp3`, and its conditions workbook pairs that resource
with the `baseball` condition. This binds the named experiment resource, although the historical
browser delivery log and per-trial byte identity remain unavailable.

The text source is Project Gutenberg ebook 27584, *Baseball Joe in the Big League; or, A Young
Pitcher's Hardest Struggles*, chapter I, “Two Letters.” The fetched Project Gutenberg plain-text
bytes had SHA-256
`541508f89fa3929cd5ec5ceffb495a6efdc3a0cb537103b94bab4564bf2a02ed`. A fresh court exposed and
corrected an error in the first inventory calculation: regex splitting had retained empty fields at
the quoted text's boundaries, so the earlier reported count of 2,188 and digest beginning `f3afa63`
did not describe a token stream. Under the now-explicit procedure — lowercase, take maximal Unicode
letter-or-digit runs, discard empty matches, and join tokens with `LF` without a trailing delimiter
— both the NFRD transcript and the Project Gutenberg chapter-I body contain 2,187 tokens with
token-stream SHA-256
`5a5f76e3b48361752ba260e599b28adf98ef0a9346a4f45bd361356dc36e9643`.
Node and Ruby independently reproduced the NFRD count and digest, and Node compared the two token
vectors element by element. Thus the released NFRD transcript preserves the chapter's lexical
sequence exactly under that declared comparison; its punctuation, typography, paragraphing, and
title omission remain derived.

The audio source is Donald Cummings's LibriVox section 01 from Internet Archive item
`baseballjoebigleague_2207_librivox`. The original 128-kbps file
`baseballjoebigleague_01_chadwick_128kb.mp3` is 804.179592 seconds and had locally computed SHA-256
`8b46777e5e417ff33613bc2529662b752f2656376a16acc152131a00219c63c4`; the released NFRD file is
768.052245 seconds and carries an FFmpeg `Lavf58.76.1` encoder tag. On mono 8-kHz decoded PCM, a
60-second opening cross-correlation placed the NFRD signal at 27.99025 seconds in the LibriVox
chapter with normalized peak 0.9305. Independently extracted middle and late windows aligned at the
predicted offset with peaks 0.9782 and 0.9727. These measurements are consistent with removal of
about 27.99 seconds of LibriVox introduction and 8.14 seconds of closing material followed by FFmpeg
processing; they do not establish a byte-preserving edit. The stimulus audio is therefore a
**Derivation** of an identified public recording, not an uninterpreted original observation.

### Participant-side release inventory

The participant text and TextGrid directories were first inspected through OSF metadata. Each text
file has a same-ID TextGrid in the corresponding story folder, and the participant-ID sets match
within each fixed story pair. No zero-byte file appears in the metadata snapshot.

| Representation | Pieman | Eyespy | Oregontrail | Baseball | Total |
|---|---:|---:|---:|---:|---:|
| Cleaned transcript files | 116 | 116 | 113 | 113 | 458 |
| Aligned TextGrid files | 116 | 116 | 113 | 113 | 458 |

Every released cleaned transcript is also nontrivial by byte size: the minima are 530 bytes for
Pieman, 783 for Eyespy, 763 for Oregontrail, and 676 for Baseball. No transcript is below 500 bytes.
As a bounded check, the smallest Baseball transcript was fetched and reduced locally to summary
statistics without printing its language: 676 bytes, 130 alphanumeric tokens, 73 unique tokens,
and 71.5% of tokens present in the Baseball source vocabulary. Its local SHA-256 reproduced OSF's
`f83582ce42a2117c294b2f66ed178102129ff9bcdad63d59e3104c3d5c993e30`. This is evidence that even
the smallest released Baseball file is content-bearing rather than an empty-recording placeholder;
it is not a quality judgment or an alignment score.

For a compact snapshot check, each digest below is SHA-256 over UTF-8 lines of
`filename<TAB>byte-size<TAB>OSF-SHA256`, sorted bytewise by line. This is a locally derived manifest
digest; it is not an OSF-issued identifier and does not hash the content a second time.

| Metadata folder | Manifest digest |
|---|---|
| `recall_transcripts/pieman` | `4d31d04cef3126d77b06b6b80437cbab650fcb61a27584931b67b2ce00b477ab` |
| `recall_transcripts/eyespy` | `3561ef1fa8c0d4ebdc548b1601bd507ed906a656e96a4a71dbd4b01191f34d01` |
| `recall_transcripts/oregontrail` | `9e5f5fa198790d975c45ba5f169bd4f350584c0aa296ff31c1627f10ef15ca31` |
| `recall_transcripts/baseball` | `d5cf72396b06d54930c72644d1f7d0afb5b199f2bf6c42e42f651039e70d58b4` |
| `recall_aligned/pieman` | `5d0b99fff91ac13e824030ce549c3b9725e87442df21bc0086dc8b05fd72cdc8` |
| `recall_aligned/eyespy` | `636779908e819af42e456f352b2c56756ac5b221df89331e2cc556b41051e81d` |
| `recall_aligned/oregontrail` | `0d59751d400253f2f32feedaedc2e1249ee3ff5b348c40c93662f8c008811bd3` |
| `recall_aligned/baseball` | `987a126f15d6afa89357da422ea65ce62238ffadbbcf9e96967dd554f52b60f4` |

The survey folder contains:

| Path | Bytes | OSF SHA-256 |
|---|---:|---|
| `data/survey/demographic_pieman_eyespy.xlsx` | 27,379 | `b6ca4baf4b7186aea21ba1a922a7c2fd20d799611fab48f62e2e944db6c2ab2c` |
| `data/survey/demographic_oregontrail_baseball.xlsx` | 39,086 | `2387c437e1c36bd3a3537b8f07db1c722340ec80d9520cef40d182086fbe78b0` |
| `data/survey/README_demographic.txt` | 3,800 | `f012021a065748412d9c29e752724e5919c51d58660a06d4e786367adf2add67` |
| `data/recall_transcripts/transcription_correction_instructions.txt` | 1,212 | `5d92aaf494885233e49610cf9e3d251893bea5218f1ee1285c115cc64477f71c` |

The survey workbooks were opened only far enough to read the first-column identifier structure and
sheet dimensions. `demographic_pieman_eyespy.xlsx` has one header plus 116 distinct participant
rows. `demographic_oregontrail_baseball.xlsx` has one header plus 113 distinct participant rows,
and that 113-ID set exactly matches both released transcript filename sets. The accompanying README
claim of 116 participants for the second workbook is therefore a stale documentation error, not an
uncertain data count. No questionnaire free-text cell was read.

### Published-code snapshot

The pinned GitHub tree contains the LDA topic-model, HMM segmentation, scoring, list-learning, text
checking, and semantic-centrality scripts; Pieman event-boundary inputs; source-story transcript
copies; and derived result arrays. It does **not** vendor the OSF participant recall transcripts;
its README instructs a user to place those transcripts into local folders.

The GitHub API reports no root licence for this repository. Availability is not a licence, so code
reuse rights are currently **Unknown** apart from separately licensed vendored subtrees. Reproducing
the paper can inspect the code; copying it into this project requires a licence determination.

## Field-by-field scientific classification

### Identity, assignment, and inclusion

| Field family | Class | Basis and handling |
|---|---|---|
| Numerical participant ID | **Derivation** | Names were replaced with numerical IDs. The mapping is withheld. The released ID is stable linkage, not an observed personal property. |
| Data-collection platform | **Observation** | Logged acquisition condition (`SONA` or `Prolific`). |
| Story pair and presentation order | **Observation** | Experimental assignment/log fields. Order is scientifically relevant and must not be reconstructed from filenames. |
| Membership in the released 229-person cohort | **Derivation** | Exclusion for engagement/missing metadata plus budget-driven, gender-stratified transcription selection produced the released cohort. |
| Upstream technical-loss status | **Unknown** | The paper reports about 9.4% loss among presented stories, but all 458 released transcript slots are populated and nontrivial. How technical loss affected selection into the released 229-person cohort is not recorded. |
| Released-file eligibility | **Derivation** | Selecting a released transcript as an analyzable row is a corpus inclusion rule. A first diagnostic may declare all 458 content-bearing released files eligible while retaining upstream selection as Unknown. |

### Stimulus side

| Field family | Class | Basis and handling |
|---|---|---|
| Released MP3 bytes | **Derivation** | The repository presents these as delivered stimuli, but Baseball is demonstrably trimmed and FFmpeg-processed from an identified LibriVox recording. Proof that every browser trial received the current OSF bytes is **Unknown**. |
| Story title/folder label | **Derivation** | Dataset naming used to link files; it is not story content or an independent identity proof. |
| Pieman source transcript text | **Derivation** | Taken from a prior neuroimaging dataset; any earlier transcription/editorial operations must travel with it. |
| Eyespy and Oregontrail source transcript text | **Derivation** | Produced using the dataset's speech-to-text and professional-correction procedure. |
| Baseball source transcript text | **Derivation** | Exact lexical match to Project Gutenberg ebook 27584, chapter I, under the corrected 2,187-token normalization; punctuation, paragraphing, typography, and title removal remain transformations. |
| Stimulus TextGrid token labels and times | **Derivation** | Forced alignment of transcript and audio; not independent observations. |
| Story events in `result_models` | **Derivation** | LDA-HMM segmentation, parameter selection, and boundary adjustment; not narrative gold. |

### Recall side

| Field family | Class | Basis and handling |
|---|---|---|
| Participant speech waveform | **Observation** | Closest available observation of the spoken production, but withheld publicly for re-identification risk. |
| Preliminary Google transcript | **Derivation** | Machine transcription; not released as an intermediate artifact. |
| Released cleaned transcript text | **Derivation** | Human-corrected and normalized after automatic transcription. It must never be labelled verbatim or raw. |
| TextGrid token labels | **Derivation** | Inherited from the cleaned transcript. |
| TextGrid word start/end times | **Derivation** | P2FA output after 11,025-Hz resampling with SoX 14.4.2. Recall audio is withheld, so public users cannot fully replay the alignment. |
| TextGrid noise/pause labels | **Derivation** | P2FA annotations of breaths, coughs, laughter, and pauses. Their completeness and error rate are **Unknown**. |
| Recall event segmentation/topic vectors | **Derivation** | LDA sliding windows plus HMM segmentation. Hyperparameters were selected against Pieman boundary agreement and applied to all stories. |
| Recall-to-story event match | **Derivation** | Maximum topic-vector correlation under the paper's scoring procedure; not a human alignment gold. |
| Recall probability, precision, PFR, and lag-CRP | **Derivation** | Aggregates of model-derived event matches and participant rows; eligible-set and support definitions must accompany reuse. |

### Survey side

The public survey README names the following columns. The workbooks were opened only for sheet
dimensions and first-column ID linkage; questionnaire response cells were not read.

| Field family | Class | Basis and handling |
|---|---|---|
| `ID` | **Derivation** | Numeric pseudonym linking survey and recall representations. |
| `platform`, `order_*` | **Observation** | Logged task condition and presentation order. |
| `age`, `gender` | **Observation** | Participant-reported demographic values, not externally verified facts. |
| `Slider_understood`, `Slider_engaged`, `Slider_difficult` | **Observation** | Participant self-ratings. Inclusion uses at least engagement, so the released cohort is derived from this observation. |
| `Text_strategy`, `Text_memorable`, `Text_shouldknow`, `Text_native`, `Text_otherlang`, `Text_activity` | **Observation** | Participant-authored free text. These fields may carry higher re-identification risk than the task recalls and are excluded from the first slice by data minimization. |

## Transcript transformation ledger

The corpus documentation and paper establish the following chain:

```text
participant speech (withheld)
  -> Google Speech-to-Text preliminary transcript (withheld)
  -> commercial human correction and normalization (released text)
  -> P2FA forced alignment after SoX resampling (released TextGrid)
```

| Operation | Output class | Reversible from public release? | Scientific consequence |
|---|---|---|---|
| Automatic speech-to-text | **Derivation** | **No.** Neither recall audio nor the preliminary transcript is public. | Recognition errors and the human corrections cannot be separated. |
| Human correction against audio | **Derivation** | **No.** No edit history or adjudication receipt is released. | Lexical content is higher fidelity, but provenance is only procedural. |
| Omit filler words such as `um` and `oh` | **Derivation** | **No.** | Removes hesitation and retrieval-process evidence; changes token counts, timing gaps, segmentation, and discourse features. |
| Collapse an immediately repeated word to one token | **Derivation** | **No.** | Removes repetition and possible repair evidence; changes fluency and local recurrence estimands. |
| Remove the task-ending phrase specified by the correction instructions | **Derivation** | **No.** | Reasonable task-boundary normalization, but still an absence-erasing edit whose rule must be recorded. |
| Normalize listed proper nouns | **Derivation** | **No.** | Improves entity matching but can hide uncertainty, partial retrieval, or transcription alternatives. |
| Spell numbers as words | **Derivation** | Partly, but not uniquely. | Changes tokenization while attempting to preserve lexical meaning. |
| Add/correct case, punctuation, and apostrophes | **Derivation** | **No** without the preliminary version. | Sentence/clause segmentation cannot be treated as participant-produced prosody. |
| Resample audio to 11,025 Hz | **Derivation** | Not publicly replayable for recalls. | Alignment depends on a transformed waveform that is not released. |
| P2FA token/noise alignment | **Derivation** | Inspectable but not fully reproducible without recall audio and a pinned aligner environment. | Timing is model output with unknown per-token accuracy, not ground truth. |

These edits make NFRD strong for **content recall** but unsuitable as the sole basis for claims about
hesitation, false starts, self-correction, repetition, or participant-produced punctuation. A
consumer must not impute removed phenomena as absent.

## Linkage and remaining reconciliation

The public metadata supports a clean mechanical linkage:

- a numerical participant ID links survey records, cleaned text, and TextGrid;
- every cleaned transcript filename has a same-ID, same-story TextGrid filename;
- the Pieman and Eyespy ID sets match exactly, as do Oregontrail and Baseball;
- story folders link each recall to one of the four released stimulus transcript/audio hashes;
- survey `order_*` fields carry within-participant presentation order.

The bounded fetch resolves one apparent contradiction and relocates the other:

1. `README_demographic.txt` says both fixed-pair workbooks contain 116 participants. The actual
   Oregontrail/Baseball workbook contains 113 distinct rows, and its ID set exactly matches both
   113-file transcript sets. The README is stale; the released pair count is 113.
2. The paper reports technical recall loss for approximately 9.4% of all presented stories,
   including empty recordings. That loss does not appear as empty or absent rows in the released
   corpus: all 458 expected transcript slots exist, all exceed 500 bytes, and the smallest checked
   Baseball file is content-bearing. The remaining **Unknown** is how technical loss affected
   selection from the original 291 participants into the released 229, not whether an empty file
   should be scored as a remembered zero.

A released-corpus analysis may therefore declare the 116/113 content-bearing transcript sets as its
eligible denominator. It must still disclose the upstream technical-loss and budget/transcription
selection process as an unresolved transportability constraint; it cannot imply that the released
229 are an unselected sample of all completed story presentations.

## Rights and human-subject gates

The OSF project declares CC0 1.0 Universal. The CC0 legal text itself limits the waiver to rights
held by the affirmer and disclaims clearance of other persons' copyright, privacy, consent, and
related rights. The node-level label is therefore evidence, but not a complete component-level
rights audit.

| Component | Current finding | Required gate |
|---|---|---|
| Participant cleaned transcripts and TextGrids | OSF/paper declare CC0; paper reports future research/data-sharing consent and withholding of audio for re-identification risk. | Local fetch and inspection are permitted under the chief's public/de-identified data-class ruling. Record provenance and licence per file; keep content outside git unless a separate vendoring decision is made. |
| Participant recall audio | Not publicly released. | Treat as unavailable; do not attempt reconstruction or access workarounds. |
| Survey free text | Included in public workbooks under node-level CC0 but potentially more identifying than task recall. | Exclude from the first slice by data minimization, not because recall-text inspection remains gated. Reconsider only for a question that needs it. |
| Pieman, Eyespy, Oregontrail stimulus audio/transcripts | Three source stories are from The Moth Radio Hour. The inspected NFRD records do not establish that the OSF depositor owns all underlying story/performance rights. | **Unknown component rights.** Obtain recorded reuse basis or use externally without redistribution only after counsel/owner review. Do not vendor them under the node-level CC0 assumption. |
| Baseball audio/transcript | Pinned to Project Gutenberg ebook 27584, chapter I, and Donald Cummings's section 01 in LibriVox/Internet Archive item `baseballjoebigleague_2207_librivox`. NFRD preserves the lexical token stream and trims/processes the audio as measured above. LibriVox states that its recordings are public domain in the US; the Internet Archive item carries CC0 metadata; Project Gutenberg supplies a US reuse basis. | Exact source/version provenance is satisfied for local diagnostic use. Check Canadian and intended-distribution jurisdiction before shipping source bytes or a derived fixture rather than importing a US-only conclusion. |
| Analysis code | Public GitHub repository; no detected root licence. | Inspect/use as a reference. Do not copy code until authors add a licence or permission is obtained. |

## Sherlock as a recall-side cleaning control

[The Sherlock analysis repository](https://github.com/ContextLab/sherlock-topic-model-paper/tree/81f90b8afa6dd714b780208bd89d1f1a26159ff5)
is pinned here at `81f90b8afa6dd714b780208bd89d1f1a26159ff5`. Its README labels
`data/raw` as raw video annotations and recall transcripts, and the tree contains 17 files named
`NN1 transcript.txt` through `NN17 transcript.txt`. The repository carries an MIT licence. No
Sherlock participant transcript was opened during this inspection.

Sherlock has a narrow, useful role before the audiovisual source is lawfully representable:

1. Treat each released Sherlock transcript as a transcript derivation whose detailed transcription
   protocol is still **Unknown**, but whose repository representation is explicitly pre-analysis
   `raw` relative to the paper's topic-model pipeline.
2. Encode the documented NFRD cleanup policy as a deterministic transform.
3. Compare **recall-side-only** quantities before and after that transform: unit segmentation,
   repetition/repair markers, discourse-function counts, trajectory fragmentation, and any metric
   whose eligible evidence changes.
4. Record moved estimands and support loss. Do not claim this reconstructs NFRD's missing tokens or
   estimates an NFRD-specific causal effect.

This sensitivity control does not require the copyrighted Sherlock episode because it asks what a
text-cleaning policy does to a recall representation, not whether the recall matches the film.
Before execution, verify that the repository licence covers the transcript components rather than
only the analysis code.

## Bounded first scientific use

The first use should answer one question, not import a corpus wholesale:

> For one lawfully cleared NFRD story, which source situations are consistently recalled, omitted,
> compressed, blended, or revisited, with support and uncertainty reported for every population
> summary?

Recommended constraints:

- use Baseball for the first diagnostic: exact source/version provenance is now pinned, subject to
  the remaining distribution-jurisdiction check before any source bytes ship;
- keep raw files outside git in a content-addressed local data location;
- pin every input SHA-256 and commit only non-identifying receipts or aggregates unless a separate
  vendoring decision explicitly admits participant text;
- exclude questionnaire free text from the first slice;
- freeze participant development and untouched evaluation partitions before model tuning;
- retain story identity as a grouping variable and call a one-story result **Diagnostic**, not
  calibrated or general;
- use storymodel4s-produced segmentations and alignments, retaining alternatives and support, rather
  than importing NFRD's LDA-HMM event labels as gold;
- use the declared 116/113 released-file denominator and report the upstream 9.4% technical loss as
  an unresolved selection/transportability fact; never reinterpret it as remembered zero;
- expose the result through Codex/Atlas evidence navigation only after the same receipts can be
  inspected without disclosing participant text to unauthorized agents or providers.

## Project-policy decisions and author questions still required

### Decisions that do not block local public-data inspection

The chief has already dispositioned local fetch and inspection: no additional owner sign-off is
required for the public, CC0, researcher-de-identified NFRD release. Separate project choices
remain before materially broader actions:

1. May any external model provider receive participant language, or must all processing remain
   local?
2. What retention, logging, backup, derived-output, and publication rules should the benchmark
   adopt?
3. May any participant-derived text ever enter git? The recommended default remains **no**.
4. Are questionnaire free-text fields excluded categorically, or merely from the first minimized
   slice?

### Questions for the NFRD maintainers

1. At what stage were presentations affected by the reported 9.4% technical loss removed, and did
   technical-loss status influence the budget/stratification selection of the released 229?
2. Can `README_demographic.txt` be corrected from 116 to 113 for the Oregontrail/Baseball workbook?
3. Are the preliminary Google transcripts, correction diffs, transcription quality records, or
   per-file exclusion flags preserved anywhere under controlled access?
4. Which P2FA revision/configuration was used, and were alignment errors reviewed?
5. What permission covers redistribution and computational reuse of the three Moth stimulus
   recordings and transcripts?
6. Can the authors confirm that Baseball used Donald Cummings's section 01 from Internet Archive
   item `baseballjoebigleague_2207_librivox`, with only introduction/closing trimming and FFmpeg
   processing beyond the measured transformation?
7. Can the OSF project be frozen as a registered, immutable release or supplied with a versioned
   checksum manifest?
8. Under what licence is the analysis-code repository released?

## Closure criterion for this inventory

This inventory now supports an unblocked local inspection. NFRD can enter the acceptance-bearing
external science court only after:

- every fetched component has a recorded provenance, licence, and checksum;
- the chosen source story has component-level rights and exact-version provenance for the intended
  distribution jurisdiction;
- the released 116/113 denominator and upstream technical-loss selection uncertainty are represented
  separately;
- the input snapshot is content-addressed outside git;
- the transcript cleanup receipt is attached to every downstream recall representation.

Until then, WOG proves the software path and NFRD remains an inventoried but scientifically
unadjudicated input. They advance in parallel and meet only after both gates are independently
green.
