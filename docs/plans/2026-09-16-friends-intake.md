# Friends intake: accounting before adaptation

Status: source accounted for; admission proposed; no court opened.

## Scope

The source set is the concatenated Friends season 1 episodes 16–17 stimulus,
with scored free recall, a main storyboard, an independent SRM storyboard,
event-segmentation responses, and six independent rating workbooks. The raw
source files are local under `data/friends/`; the content-free provenance
record is [`docs/data/friends/source-manifest.json`](../data/friends/source-manifest.json).

## What is established

- The local `FriendsRecallScoring.xlsx` is byte-identical to the file at the
  pinned GitHub commit and is accounted for by SHA-256 and Git blob identity.
- The recall workbook has 23 participant sheets, 23,181 transcript-bearing
  timeline rows, and a legend sheet. It contains participant prose and stays
  outside Git.
- The main storyboard has 52 non-empty `EventModelNum` rows and 30 scene
  numbers. Its raw event space is distinct from the recall scorer's observed
  `WhichEvent` range of 1–56.
- The upstream behavioral notebook declares a piecewise event-number shift for
  removed interludes and merged events. This is evidence that a mapping is
  needed, not evidence that the mapping is already a framework contract.
- `eventseg.zip` has 57 participant directories, while the notebook declares
  two analysis versions with 20 and 25 selected participants. Archive
  membership and analysis membership must remain separate fields.
- `ratings.zip` contains six rater workbooks. The causal and importance sheets
  embedded in `friendsStoryBoard.xlsx` are a separate source representation and
  must not be conflated with the archive.

## Admission limits

The upstream GitHub repository has no declared licence. No licence decision is
made by this intake. The participant recall transcript and stimulus transcript
also require the project's human-subject provenance gate before any text-bearing
artifact is committed. No video or fMRI bytes are included.

## Next executable slices

1. Write a content-free event crosswalk: raw storyboard event, raw scorer
   labels, removed/merged status, canonical event ID, evidence, and version.
2. Build a read-only Friends source audit that emits normalized metadata only;
   test mixed clocks, mixed numeric/string labels, missing scene labels, and
   the non-binary `False memory?` anomaly.
3. Define a recall-unit adapter with explicit row-boundary policy and preserved
   source coordinates; do not derive accuracy from `RecallType` without carrying
   the source codebook and exclusions.
4. Add causality, importance, storyline, detail, and event-segmentation tracks
   as independently typed feature inputs with their own provenance.
5. Add only content-free or synthetic regression fixtures until the text-bearing
   admission question is resolved.
