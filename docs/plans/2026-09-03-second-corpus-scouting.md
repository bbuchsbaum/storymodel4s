# Second recall corpus: scouting brief

*2026-09-03.* Handoff item 3 asks whether to bring in a second corpus and which. This brief was
produced by a scouting agent with web access, working from file listings and full texts it actually
opened (GitHub and OSF APIs, the OpenNeuro S3 listing, Europe PMC); "unverified" marks what it could
not open. Nothing here is an acquisition. Any recall transcript enters the repository only through
`docs/design/story-text-admission-checklist.md`, checked by someone other than the proposer, and
any source only through a `docs/data/` admission record like Sherlock's. What the owner decides on
this brief is *whether to open that court*, and for which corpus.

## Requirements, in priority order

1. a narrative stimulus encountered once;
2. free recall transcripts per participant, ideally word- or sentence-timed, ten or more people;
3. a human annotation of the stimulus at event or segment grain, or a timed transcript to build one;
4. an existing human coding of which stimulus event each recall segment refers to;
5. downloadable text under a licence permitting research use.

## Ranked table

| # | Corpus | Stimulus | N recall | Recall timestamps | Stimulus annotation | Recall-to-stimulus coding | Licence | Where | Confidence |
|---|---|---|---|---|---|---|---|---|---|
| 1 | **Film Festival** (Lee & Chen 2022, Chen lab) | 10 short films, 129–465 s each, ~50 min, watched once in scanner; videos withheld (copyright) | 20 scanned, 15 analysed; spoken, unprompted | Utterance-level (onset, duration, text) in ds004042 `events.tsv`; word-level xlsx for 19 participant codes in Word-timestamped-transcripts (Zenodo v1.3, the same record as Sherlock) | 3 human annotators; JL: 628 fine rows with start time (min.sec) and description, 216 coarse segments (paper: 202 events) | **Yes**: `recall_scenematched/sub-XX_recall.xlsx`, 15 subjects, per utterance: film, scene code(s) 1–216, start/end, text; coding scheme included | fMRI and utterance transcripts CC0; Zenodo "Other (Open)"; the filmfest GitHub repository has no LICENSE file | github.com/jchenlab-jhu/filmfest; zenodo.org/records/6574792; github.com/jchenlab-jhu/Word-timestamped-transcripts; zenodo.org/records/8208709; openneuro.org/datasets/ds004042 | High (downloaded, parsed) |
| 2 | **Naturalistic Free Recall Dataset** (Raccah et al. 2024, Sci Data) | 4 spoken stories heard once online: Pieman (948 w, 489 s), Eyespy (2318 w, 779 s), Oregontrail, Baseball; 2 per participant | 229; files: pieman 116, eyespy 116, oregontrail 113, baseball 113; spoken, immediate | Word-level p2fa TextGrid per participant per story (`data/recall_aligned`); cleaned `.txt`; audio withheld | Story transcripts with word-aligned TextGrids. Human boundaries only for Pieman (Michelmann 2021, N=205, `.mat` in the code repository); no scene descriptions | **None** human; automatic topic-model scoring only | CC0 (paper); the OSF API shows no licence object | osf.io/h2pkv; github.com/phoebsc/Naturalistic-Free-Recall-Dataset; PMC11615391 | High |
| 3 | **EventRecall** (Panela et al. 2025, Commun Psychol) | 3 written narratives (~1,500 w each) read once on paper | 60 files = 20 participants × 3; spoken, immediate | None (plain text) | Human boundary marks while reading (`visual_segmentation_data.csv`, word number); GPT and LLaMA segmentations | Partial: a human rater score per event per participant in `recall_events.csv`; no utterance alignment | MIT | github.com/ryanapanela/EventRecall; PMC12705437 | Medium-high |
| 4 | Sherlock_Merlin (Zadbood 2017) | Sherlock and Merlin episodes | **one speaker per film**; `.wav` in ds001110 stimuli; aligned transcripts ship as Narratives stimuli | word-level (Narratives) | Merlin annotation: none found | none | OpenNeuro (unverified) | openneuro.org/datasets/ds001110 | High that N = 1 |
| 5 | Sixth Sense (Zadbood 2022) | ~60 min film | 57, cued by 18 scenes | scene onsets and a `correct` flag only | 18 one-line scene descriptions | scene scores only | OpenNeuro | openneuro.org/datasets/ds004359 | No transcripts in the listing |
| 6 | Schema (Baldassano 2018) | 16 short stories | 31 | none | rubrics only | none seen | OpenNeuro | openneuro.org/datasets/ds001510 | No transcripts found |
| 7 | Reagh & Ranganath 2023 | 8 × 35 s home videos | 20 | none; detail counts only | none | none | by request | github.com/zreagh/8vid | Not usable |
| 8 | Cohn-Sheehy 2021/2022 | 4–8 short stories | 36–72 | none; scored xlsx | story PDFs | none | OSF | osf.io/uw4an | Transcripts not seen |

Confirmed negatives: Narratives ds002345 has no participant recall beyond the two Zadbood speakers.
NNDb and the StudyForrest extension descriptors have zero recall hits. Bellana 2022 (osf.io/dmbx4)
is free association. Michelmann 2021 is next-word prediction. Sava-Segal 2025 has no recall. The
Chen lab GitHub has no Merlin or Twilight Zone folder. Lee & Chen also ship 492 typed online
recalls (393 included) of ten *different* short films, one per person, without timestamps or
annotation.

## The top two: what each would take

**Film Festival.** Structurally a Sherlock twin: the same lab, the same transcription and alignment
procedure (Born et al. 2023 describes both), an annotation with per-row start times and
descriptions at two grains, and an utterance-to-scene gold for 15 participants. Work: (1) parse the
three annotation xlsx files and convert min.sec to seconds, noting that times run per scanning run
(each opens with a 39 s cartoon intro), not per film; (2) pick the reference annotator, probably JL
because its 216 coarse segments match the "1~216" scene range in the coding scheme (an inference,
not stated in the release); (3) take utterances from the CC0 ds004042 `events.tsv`, whose text
matches `recall_scenematched` row for row, so the gold aligns by utterance without the
word-timestamp files; (4) add word timing from the Zenodo xlsx, which needs a mapping from
three-letter codes to sub-XX numbers that the scout did not find documented; (5) extend the model
to ten independent narratives recalled in free order, with multi-scene utterances ("182, 185") and
film-level codes. Main risk: licensing. The fMRI data are CC0 and Zenodo says "Other (Open)", but
the GitHub repository holding the annotations and the gold has no licence file, so ask the Chen lab
before anything is published on it. Secondary risk: cross-film confusion is a failure mode Sherlock
never exercised, and with no video, visual grounding must come from annotation text alone, which
also means the captioning lane stays idle on this corpus.

**Naturalistic Free Recall Dataset.** Strengths: 113–116 recallers per story, word-level timing on
story and recall, CC0, and a large fixture for scaling tests. Work: (1) parse Praat TextGrid word
tiers for stimuli and recalls; (2) build a stimulus annotation, since only Pieman has human
boundaries and no story has scene descriptions, so segments must come from the transcript
(sentence or clause units with word timing) or a model; (3) hand-code a gold subset, say ten
participants on Pieman, because no human recall-to-event coding exists. Main risk: without that
hand coding the pipeline can only be scored on self-consistency, and audio-only stories change
modality, so a failure would be ambiguous between generalisation and stimulus mismatch. The right
second corpus for scale and licence, not for the first generalisation test.

## What this brief recommends, and what it does not decide

Film Festival first; the Naturalistic Free Recall Dataset second, with a small hand-coded gold.
Film Festival is also the corpus on which handoff item 4 becomes live: ten films recalled in free
order will not be 97.9% non-decreasing in one episode's scene index, so the `hard` backward and
`0.0` forward priors must be re-fitted on its development split rather than inherited.

Undecided, and the owner's: whether to open the acquisition court at all, the licence question to
the Chen lab, and which annotator's numbering is the gold's.
