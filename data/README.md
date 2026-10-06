# Local data root

Everything under `data/` except this file stays outside Git. It holds the bytes whose identities
`docs/data/` records: external sources, model weights, and the run record of the studies. It replaces
the earlier practice of keeping the same files under `tmp/`, a name that said *disposable* about the
one thing in the repository that is not.

One copy serves every worktree. `tools/data-root.sh` resolves the location as `$STORYMODEL4S_DATA`
when set, otherwise `<main checkout>/data` through the git common dir, so a worktree under
`.worktrees/` reads and writes the same files as the main checkout without symlinks.

## Layout

```text
data/
  sherlock/
    Sherlock_Segments_1000_NN_2017.tsv        released 1,000-row annotation, local TSV replay
    Sherlock_Recall_Scene_n50_Onsets.csv      scene-level recall gold, sha256 68cc307c…3753
    recall/                                   17 word-timestamped recall CSVs and their aliases
    media/                                    the two presentation-edition parts (video bytes)
  filmfestival/                             proposed second corpus; see docs/data/filmfestival/
    README, participants.tsv, task-*.tsv      ds004042 v1.0.1 metadata and presentation schedule
    recall_events/                            25 utterance transcripts, 20 participants, CC0
    annotations/                              three coders' stimulus annotation, 216 coarse segments
    recall_scenematched/                      recall-to-scene gold, 15 participants
    textdata/                                 crowd descriptions and predictions, six films
    derived/, subtitles/, FILMS.md            network metrics, one caption track, film sourcing
  memento/                                  admitted local row task; see docs/data/memento/
    MementoStoryBoard.xlsx                    129 subscenes / 44 scenes, presentation and story order
    Subjects.xlsx                             133 recall sheets; 123 admitted participants, nominal five-second grain
    ratings/r1-r7.xlsx                        seven raters' causal matrix and importance
  friends/                                  proposed Friends source set; see docs/data/friends/
    FriendsRecallScoring.xlsx                 23 participant recall-scoring sheets, raw workbook
    friendsStoryBoard.xlsx                    main storyboard, transcript, and embedded ratings
    friendsSRMStoryBoard.xlsx                 independent task-srm storyboard
    eventseg.zip                              two-version event-segmentation response archive
    ratings.zip                               six independent rater workbooks
  models/
    onnx/model.onnx, tokenizer.json           the pinned sentence encoder the pipeline reads
  study/
    recall-to-video/                          partition.json, one directory per arm, within-scene/
```

Identities: `docs/data/<corpus>/*.json` for the sources, and the study documents under
`docs/plans/` for every arm and packet, each of which records the digests of what it read and wrote.
`memento/` is admitted for the local row task and sealed split described in `docs/data/memento/`.
Consult each corpus record for its own admission scope. Local analysis never authorizes
redistribution of participant recall prose.

## Rules

- Sources are read-only. A new source is admitted through `docs/data/` first.
- Private data, derived participant/study artifacts and data-bearing logs stay on owner-approved
  development machines and approved private storage. The owner approved `buc-gw01` for
  development on 6 October 2026. Other machines can develop public code and synthetic fixtures; tailnet
  membership alone does not authorize private data access. Corpus admission and sealed-partition
  restrictions still apply on approved machines.
- The study record is written only by the study tools. Deleting an arm directory deletes evidence
  a landed document cites; the documents quote the numbers, but the row-level record is here.
- Video/source bytes may move to another approved development machine through the verified
  handoff below; they are never published or referenced by local path in a committed file.
- Recall prose lives only here and in the study record derived from it. A file admitted to Git from
  this tree must be content-free: identities, counts, digests, unit indices, segment numbers.

## Approved-machine handoff

The [development guide](../docs/development.md#private-data-on-approved-machines) documents
`tools/data_handoff.py`: committed host approval, explicit private selections, resumable SSH
streams, conflict refusal, full byte verification and a final readiness marker. Every new
destination reader must first pass `check-local`; directory presence does not admit a dataset.
Mote `bd-01M48TNS4G0442F7SJW57671B6` owns the first exercised handoff and its evidence.

Record owner approval for each destination and corpus/use, authenticate its host identity, and
select an explicit `STORYMODEL4S_DATA` root. Preview the minimum assets required; use a private
manifest containing relative asset identities, byte sizes/counts, SHA-256 and admission/partition
restrictions. Transfer through authenticated encrypted transport on the tailnet into resumable
staging. Preserve the source and unrelated destination files; conflicting destination assets
require resolution before replacement. Verify every byte against the manifest before admitting
the copy, then run a permitted reader smoke and record readback. A directory-presence check is
not checksum verification.

Test refusal of an unapproved destination, interruption/resume, corrupt/truncated bytes and
destination conflicts with synthetic assets before exercising the private transfer. Keep private
manifests, identifying filenames and data-bearing logs in approved private storage. Committed
receipts contain only reviewed content-free identities and verification status. GitHub and
hosted CI are not storage or execution destinations for these private assets. Copying leaves
corpus admission and held-out restrictions unchanged.
