# A second test dataset beside Sherlock — survey, 2026-09-03

We use Sherlock (Chen et al. 2017). The question was what else is openly downloadable that gives the
same three things: **(1)** stimulus video, **(2)** time-stamped stimulus annotations, **(3)** per-subject
free recall with timing and a gold recall→scene mapping.

**Answer: FilmFestival (OpenNeuro `ds004042`) is the only dataset that has all three.** Nothing else
is close. It is staged under `data/filmfestival/`; see `data/README.md` for provenance and
`data/filmfestival/FILMS.md` for the film table, sources, subtitles, and timebase traps.

## Why the others fail

Each was checked against a live file listing (OpenNeuro GraphQL, GitHub API, figshare API, GIN),
not against the papers' claims.

| Dataset | Video | Annotations | Free recall |
| --- | --- | --- | --- |
| **FilmFestival** `ds004042` (CC0) | no — sourceable per `FILMS.md` | **yes**, 3 coders, 216 scenes | **yes**, 20 subj timed + 15 subj gold-mapped |
| Sherlock `ds001132` | GIN serves Part 1 only; **no public Part 2** | yes (GIN `onsets/`) | yes |
| Schematic narrative `ds001510` (CC0) | **yes, CC BY 4.0** figshare `10.6084/m9.figshare.5760306.v3` | no — `events.tsv` are 68–194 B onset markers | fMRI only; no audio or transcripts in the tree |
| Sherlock/Merlin `ds001110` | **yes**, both films in-dataset | Merlin: none | one speaker, audio only, no transcripts |
| Narratives `ds002345` (CC0, 345 subj) | audio only | yes — word+phoneme, on datalad not OpenNeuro | **no** — the merlin/sherlock tasks are *listening to someone else's retelling* |
| Free recall of narratives (OSF `h2pkv`, 229 subj) | audio only | yes | yes, timestamped — but **no fMRI**, no gold mapping |
| NNDb `ds002837` (86 subj) | no — buy the discs | weak: only face-annotation `.1D` | no |
| StudyForrest `ds000113` | no — purchased DVD | **richest anywhere** — `psychoinformatics-de/studyforrest-data-annotations` (locations, emotions, music, faces, speech) | no |
| Friday Night Lights `ds003521` | no | not in tree | no |
| NeuroMod movie10 | annex symlinks + `cut_*.sh`, bring your own disc | not verified | no |
| Cam-CAN Hitchcock | managed access, application | none found | no |
| IBC `ds002685` | no | not verified | no |
| Le Petit Prince `ds003643` | n/a (audiobook) | yes, word-aligned | no |

## What this means

- **Second lane: FilmFestival.** Matches Sherlock's shape, with a *richer* gold set (per-utterance
  scene labels rather than scene onsets) and a large uncited crowd corpus on top
  (`filmfest-textdata`: 7,099 descriptions + 11,526 predictions, mapped to the fMRI clock).
- **Possible third lane: `ds001510`**, on different terms — it is the only dataset whose video is
  freely redistributable, so it exercises the video path where FilmFestival cannot. But legs 2 and 3
  both fail, so we would supply the annotation layer ourselves.
- **StudyForrest** is worth remembering purely as an annotation-schema reference: it is the most
  thorough naturalistic annotation set in the field, even though it has no recall.
- **OSF `h2pkv`** is a text-only recall corpus (229 subjects, timestamped) if we ever want recall
  volume without imaging.

## Addendum, same day: systematic OpenNeuro sweep

The table above came from a *name-driven* search, so it could only find datasets we already knew to
ask about. Redone properly: all **1,869** public OpenNeuro datasets were enumerated through the
GraphQL API and filtered on their declared `tasks` and `Name`. (Two API traps: `first:200` silently
returns 100, so naive offset stepping skips half the archive; and one dataset has a broken
`latestSnapshot` that poisons its whole page, needing a one-at-a-time fallback.)

Eleven datasets declare both a recall-ish task and a movie-ish stimulus. Six were new to us:

| Dataset | N | Stimulus | Recall artifacts |
| --- | --- | --- | --- |
| `ds005468` Kwon/Shim, CC0, 22.7 GB | 24 | movie + recall | **gold mapping, numeric only** — companion repo `somvid/Hippocampal-subspaces` has per-subject `Recall_event_onset/offset/idx.npy` (which movie event each recall event maps to), 61 movie event boundaries, per-event memorability. No transcripts |
| `ds005215` Park/Song/Shim, CC0, 15.6 GB | **65** | task `filmrecall` | nothing beyond BOLD in the tree; largest subject count of any movie-recall fMRI dataset found |
| `ds008464` "Friends" Antony/Reagh, CC0, 60.9 GB | 23 | Friends S1 E16–17, `view` + `recall` + pre/post | `events.tsv` are **empty BIDS stubs** (header + TODO). Study is about *causality in narratives*. README points to `JamesWardAntony/friends`, which **404s** |
| `ds003721` Visser/Henson/Holmes, CC0, 110 GB | 35 | distressing film, voluntary + intrusive recall | not inspected |
| `ds003338` Antony/Gureckis, CC0, 52.9 GB | 20 | basketball game endings, view + recall | not inspected |
| `ds005704` Masís-Obando/Norman/Baldassano, CC0, 108 GB | 25 | memory-palace room videos + recall | not inspected |

### The standout is not on OpenNeuro at all

Chasing the dead `friends` link through its author's account turned up
**`github.com/JamesWardAntony/memento`** (Antony et al. 2024) — behaviour only, no imaging, and the
best narrative-structure resource found anywhere in this search:

- **`MementoStoryBoard.xlsx`** — **129 subscenes across 44 broad scenes** (an earlier note said
  ~998; that was a padded sheet dimension). Columns: time, up to three characters, place, prose
  explanation, broad/sub scene number, plus the two that matter most here: **`NarrativePart`**
  (Backward 82 / Forward 26 / FlashbackWithinBackward 10 / FlashbackWithinForward 10) and
  **`StoryOrderSceneNum`** — a complete permutation of the 44 broad scenes into chronological order,
  i.e. an explicit **presentation-order → story-order mapping**. 17 named characters, 20 places,
  runtime to 01:50:27; 42 subscenes flagged as belonging to a shortened "clipped" version.
  Nine sheets in total, including `dialogueScript` (1,954 rows), `fullScript` (2,709 rows),
  `broadSceneDescriptions`, consensus `CausalityRatings`/`ImportanceRatings`, a 44x44 `visSim`
  visual-similarity matrix, and `blacktowhite` (347 s of the film is black-and-white).
- **`Subjects.xlsx`** — **133 subjects**, four conditions, recall transcripts timestamped at 5 s
  resolution, each row carrying `RecallType`, `BroadSceneNum`/`SubsceneNum` (gold recall→scene
  mapping), `Detail`, and **`FalseMemory` / `FalseMemExp`** — false memories explicitly annotated.
- **`ratings/r1–r7.xlsx`** — seven independent raters giving a 44x44 **scene→scene causal graph**
  plus per-scene importance (0-10). *Caveat, measured:* raters used the instrument very differently
  — edge counts run 45, 83, 116, 276, 380, 940, 1891 (r3 marked essentially all 1,892 cells), so
  this is not a clean gold standard and needs a consensus rule. Edges endorsed by >=2/3/4/5 raters:
  1175 / 419 / 152 / 65. Importance mean across-rater SD is 1.76, also substantial.

Memento is a film whose discourse order deliberately inverts its story order, annotated with both
orders, recalled by 133 people, with a 7-rater causal graph and labelled false memories. For a story
model that cares about fabula vs. syuzhet, causal structure, and recall fidelity, this is a sharper
instrument than any fMRI dataset in this survey — at the cost of having no brain data.

Also from the same author: `JamesWardAntony/bball_am` (basketball autobiographical memory).

## Addendum 2: non-OpenNeuro sweep (OSF, Zenodo, Dryad, figshare, GIN, CONP, EBRAINS, lab pages)

Almost everything it found we already hold. Confirmed **negative** for movie+recall: Dryad, Harvard
Dataverse, figshare, CONP, EBRAINS, Brainlife, NIMH Data Archive, Chinese repositories (SciDB); GIN
has only `ljchang/Sherlock`; naturalistic-data.org lists only Sherlock (recall) and Paranoia (none).
Ben-Yakov's 30-subject Spanish-dubbed Sherlock free recall has no public repository.

Two worth keeping:

- **`ContextLab/sherlock-topic-model-paper`** (Heusser & Manning) — `data/raw/` has the canonical
  `Sherlock_Segments_1000_NN_2017.xlsx` plus 17 per-subject `transcript.txt`, and `data/processed/`
  has `recall_event_times.npy`, `recall_events.npy`, `recall_text.npy`. A second, independent
  packaging of the Sherlock recall we already use — useful as a cross-check on our parsing.
- **`ryanapanela/EventRecall`** (**MIT licence**, pushed 2025-12-17) — Panela, Barnett, Barense &
  Herrmann 2025, *Communications Psychology*. **Baycrest.** 20 participants × 3 recalls = 60
  transcripts over three spoken stories (`Run`, `GoHitler`, `MyMothersLife`, texts included), with
  `data/segmentation/` holding **human** segmentation alongside **GPT** and **Llama** segmentations
  of the same narratives, plus `recall_events.csv` and event-level ISC. Stories, not movies, so it
  fails the video leg — but it is the closest thing found to a benchmark for
  *machine vs. human event segmentation*, which is our own machine lane's question, and the authors
  are local.

Lower value: **OSF `h2pkv`** (229 subj, four spoken stories, timestamped recall, no annotations) is
the largest recall corpus found but audio-only; **OSF `tpq2m`** (Bennion et al. 2025) is lists of
short clips, not a narrative.

*Correction to note if this survey is reused:* the sweep reported FilmFestival's neural data as
OpenNeuro `ds003242`. That accession is a food-craving study by Tomova et al. FilmFestival is
**`ds004042`**.

## Next slice

Normalize the three FilmFestival coders' `.xlsx` into the Sherlock-shaped TSV so the existing scorer
can run against it. Watch the two traps recorded in `FILMS.md`: Run 2 segment numbers need **+106**
to reach the global 1–216 space, and annotation times are run-relative `min.sec` decimals.
