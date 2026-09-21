# Film Festival short-film recall source set

This directory is the content-free admission record for the corpus that
`docs/plans/2026-09-03-second-corpus-scouting.md` recommends as the second corpus: twenty
participants who watched ten short films (~50 min, two scanning runs) and then freely recalled them
aloud in the scanner. Lee, Chen & Hasson (2023), OpenNeuro `ds004042`.

**Nothing here opens a court.** The state is `proposed`. The scouting brief already argued the
scientific case; this record establishes byte identity, counts and the code space so that admission
is decided against facts.

## Why the artifact trail needed recording

The pieces are scattered across five upstream sources, and the two that matter most are not
discoverable from the dataset. `ds004042`'s README says annotation files are "provided as additional
files" and ships none; the Data in Brief paper names no repository. The annotations and the
recall-to-scene gold live in **`jchenlab-jhu/filmfest`**, cited only in the data-availability line
of a *third* paper (Lee & Chen 2022, *Nat Commun*). A fourth source,
**`jchenlab-jhu/filmfest-textdata`**, is cited in no publication at all.

Without this record an analysis could plausibly use the wrong annotator, the wrong clock, or the
wrong scene-number space, and look green while doing it.

## What blocks admission

1. **Owner decision.** Whether to open the acquisition court. The scouting brief reserves this.
2. **`docs/design/story-text-admission-checklist.md` §3.** The corpus carries participant recall
   prose — 4,005 utterance rows in `ds004042` alone, plus 3,226 scene-labelled utterances and the
   word-level transcripts. None may be committed until an REB/IRB basis is recorded by the owner and
   checked by someone other than the proposer. This slice proposes **no** text-bearing artifact for
   Git admission.
~~3. Licence.~~ **Resolved 2026-09-04** — see below. `ds004042` is CC0 and the Zenodo archives
   say "other-open"; `jchenlab-jhu/filmfest` and `filmfest-textdata` carry no LICENSE file, and the
   owner has accepted open-science research use under a recorded disclaimer.

Neither remaining item blocks analysis. §3 bars *committing* recall prose to Git; it does not bar
reading the staged bytes from the git-ignored data root, which is where they live and stay.

No video is committed. The release withholds it: *"Due to copyright issues, we are not uploading the
movie video files."* Public copies of eight films and the cartoon, with their trimmed clips, are
staged locally, git-ignored, at `data/filmfestival/media`; the two commercial excerpts are not.
`media-manifest.json` identifies those files without their content. `data/filmfestival/FILMS.md`
records where public copies of each film are, for local research use; it names commercial works and
is itself not for Git admission.

## Licence, and the disclaimer that goes with it

The owner decided on 2026-09-04 that these are open-science releases and that local research use
proceeds without waiting on an explicit licence grant. Conditions: no upstream bytes are
redistributed; every release and its article is cited wherever results are reported; and any
publication or artifact derived from a source whose upstream carries no LICENSE file carries this
disclaimer:

> Derived from openly released research data. Where the upstream release carries no explicit
> licence, it is used here for non-commercial academic research under an open-science reading, with
> attribution to the original authors. No source bytes are redistributed. The original authors have
> not reviewed or endorsed this use.

This decision covers **licence only**. Human-subject provenance is a separate question, governed by
§3 of `docs/design/story-text-admission-checklist.md`, and is unaffected by it.

## Records

| Record | Contract |
|---|---|
| `source-manifest.json` | Five upstream sources with commits, DOIs and licences; eight artifact groups accounting for all 104 staged files; verification receipt; content policy; non-claims |
| `media-manifest.json` | The 53 files under `data/filmfestival/media` by path, SHA-256 and size, with container, codecs and duration per video; per film, trim, clip and scan durations by cover name; the two absent excerpts' boundaries as annotation rows and run-relative times; Trillium origins; non-claims. Regenerate with `python3 tools/corpus/filmfest_media_manifest.py`; `--check` exits 1 on drift |

Canonicalize with `jq -S -c` before digesting.

## Verification performed

Every upstream-sourced file was re-hashed with `git hash-object` and compared to the upstream tree
blob at its recorded commit: **101 verified, 0 mismatched**. Three staged files have no upstream git
blob and carry SHA-256 identities instead — the Nature Communications source-data workbook (from the
publisher zip), one subtitle track extracted from a YouTube manual caption stream, and the
project-authored `FILMS.md`.

## Scientific use, and the traps that will bite

- **Scene id space is 1–216 globally, but the annotation restarts numbering in run 2.**
  `recall_scenematched` uses the global space; the annotation workbooks do not. **Add 106** to run-2
  segment numbers. Verified against a participant whose Bus Stop utterances are labelled 182/185/186
  while Bus Stop occupies run-2 segments 75–110.
- **Annotation times are run-relative `min.sec` decimals** (`6.31` means 6:31), and arrive as dirty
  floats such as `6.1000000000000005`. Parse as two-decimal mm.ss, never as a number.
- **The reference annotator is not stated by the release.** JL's 216 coarse segments match the
  coding scheme's "1~216" range, which is why the scouting brief infers JL. That inference is
  recorded as a non-claim, not a fact.
- **The annotation clock and the `events.tsv` clock are offset.** The annotation begins at the
  cartoon's first frame and includes the 6 s title cards that event durations exclude; `events.tsv`
  puts the cartoon at onset 3 s. The release itself warns onsets are annotator-subjective at
  non-punctate transitions. One alignment check against video is owed before cross-referencing them.
- **Ten independent narratives recalled in free order** is a regime Sherlock never exercised. The
  scouting brief is right that the monotone prior's `hard` backward and `0.0` forward settings,
  fitted on Sherlock's 97.9% non-decreasing scene index, must be re-fitted on a development split
  rather than inherited.
- **Five of the ten films contain no speech.** Anything leaning on transcript will behave differently
  here, and the captioning lane has no video for the two commercial excerpts.
- **`filmfest-textdata` covers six films, not ten**, and its `cmiyc_long` is a longer cut than the
  346 s presented in the scanner, so its onsets are not interchangeable with the fMRI clock without
  care. Its value is a second, independent, crowd-sourced segmentation on the same timeline —
  7,099 descriptions and 11,526 predictions, already mapped to `filmfest_onset` / `TR_onset`.

## Primary sources

- fMRI and utterance transcripts: [`ds004042`](https://openneuro.org/datasets/ds004042) v1.0.1, CC0
- Annotations and recall-to-scene gold:
  [`jchenlab-jhu/filmfest`](https://github.com/jchenlab-jhu/filmfest) at `0ffad622`,
  archived as [10.5281/zenodo.6574792](https://doi.org/10.5281/zenodo.6574792)
- Crowd corpus: [`jchenlab-jhu/filmfest-textdata`](https://github.com/jchenlab-jhu/filmfest-textdata)
  at `bf22326d`
- Per-event network metrics: [10.1038/s41467-022-31965-2](https://doi.org/10.1038/s41467-022-31965-2)
  Source Data, CC BY 4.0
- Word-level recall timing: [10.5281/zenodo.8208709](https://doi.org/10.5281/zenodo.8208709) —
  already identified by `docs/data/sherlock/recall-lineage.json`; the Film Festival half is on disk
  inside the Sherlock clone
