# Friends recall and narrative source set

This directory is the content-free admission record for a **proposed** Friends
source set. The source is the two-part stimulus from season 1, episodes 16–17:
“The One With Two Parts, Part 1” and “The One With Two Parts, Part 2”. The
upstream analysis describes it as one concatenated and edited clip; the exact
presentation cut is not yet reconstructed here.

Nothing here opens a court. The raw bytes live in the shared, git-ignored
`data/friends/` root. This directory records their identities, structure,
provenance, observed hazards, and the path into `storymodel4s`; it does not
contain participant recall prose or the stimulus transcript.

## Inventory

| Artifact | What it contains | Accounting |
|---|---|---|
| `FriendsRecallScoring.xlsx` | Recall-scoring workbook for 23 participant sheets plus a legend and a partial transcript sheet | 23,181 timeline rows with transcript text; 1,268 contiguous transcript runs |
| `friendsStoryBoard.xlsx` | Main stimulus storyboard, transcript, event attributes, and embedded causality/importance sheets | 52 non-empty event rows in `FriendsNarrComb`; 694 transcript rows |
| `friendsSRMStoryBoard.xlsx` | Independent `task-srm` storyboard | 57 event rows |
| `eventseg.zip` | Raw event-segmentation response archive | 57 participant directories; 57 CSVs, 57 logs, 56 PsychoPy files, and 1 TSV payload |
| `ratings.zip` | Independent causal/importance rating workbooks | Six rater workbooks |
| `README.md` | Upstream repository README | Pinned with the same repository commit |

The exact SHA-256, upstream Git blob identity, sheet dimensions, codebook, and
all measured anomalies are in [`source-manifest.json`](source-manifest.json).
Canonicalize that JSON with `jq -S -c` before digesting it.

## Important boundaries

- The GitHub repository at commit `7721753f` declares no `LICENSE` file. The
  admission record therefore leaves the licence decision open; these bytes are
  local-only and are not redistributed by this repository.
- Participant recall prose and the stimulus transcript remain in `data/friends`
  only. The REB/IRB and human-subject provenance basis must be recorded before
  any text-bearing artifact can be committed.
- The related fMRI release is OpenNeuro `ds008464`. No fMRI or video bytes are
  staged in this source set.
- The raw `WhichEvent` values are not yet a canonical event identity. The
  upstream `recallBehav.ipynb` applies a piecewise shift for removed interludes
  and merged events. That mapping must become an explicit, tested contract
  before a loader emits `storymodel4s` event IDs.
- `SecondsInMinuteTime` mixes string and Excel-date encodings. The numeric
  `SecondsOfRecall` field is the candidate local clock, but the raw field must
  be preserved for audit.
- The `False memory? (1=yes)` column contains non-binary string values `2`–`17`
  in `s5` rows 128–143. This is preserved as an anomaly, not silently repaired.
- Fifteen repeated-transcript runs change labels across their rows. The loader
  must retain row boundaries until a scoring-resolution rule is established.

## Sealed test split

The participant-level test split was sealed on 2026-09-21, before any model output on Friends
existed: **10 test / 13 development** participants, seed `20260921`, drawn from the admitted sheet
IDs alone ([`test-split.json`](test-split.json); `tools/recall-study/friends_split.py --check`
re-derives it). The test side holds 278 of the 630 gold units. The record also carries the power
assumptions and minimum detectable effects, and the exposure statement: the split is
model-untouched, not unseen, since human-coding statistics were already computed over all 23.

Every reader of Friends recall must go through `tools/recall-study/friends_guard.py`. The guard
refuses test participants unless it is called with a final opening that names a committed
release-candidate manifest by SHA-256, and it counts each such read in
[`test-split-reads.json`](test-split-reads.json), which currently records zero reads.
`tools/recall-study/tests/test_friends_guard.py` fails if any reader under `tools/` or the Scala
sources bypasses the guard.

## Intended framework path

The next work should be staged as separate contracts:

1. define the raw-to-canonical 52-event mapping against the storyboard;
2. normalize the recall timeline into `recall` units while preserving raw
   scoring values and provenance;
3. bind storyboard attributes and independent ratings as `features` sidecars;
4. bind event-segmentation responses as a separately identified behavioral
   track; and
5. exercise `align` only after the event and time identities have passed those
   gates.

No Friends loader, normalized table, alignment result, or participant-bearing
fixture is claimed by this intake.

## Primary sources

- [JamesWardAntony/friends](https://github.com/JamesWardAntony/friends) at
  `7721753fffe3c14bd49ae26216cd459cf9b5737a`
- [OpenNeuro ds008464](https://github.com/OpenNeuroDatasets/ds008464) at
  `cbb9ecee52120229ae71501d6055e0f8d044bac7`
- [Friends episode list](https://friends.fandom.com/wiki/List_of_Friends_Episodes)
