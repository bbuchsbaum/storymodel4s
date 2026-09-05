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
  memento/                                  proposed third corpus; see docs/data/memento/
    MementoStoryBoard.xlsx                    129 subscenes / 44 scenes, presentation and story order
    Subjects.xlsx                             133 participants' recall, five-second grid, scene gold
    ratings/r1-r7.xlsx                        seven raters' causal matrix and importance
  models/
    onnx/model.onnx, tokenizer.json           the pinned sentence encoder the pipeline reads
  study/
    recall-to-video/                          partition.json, one directory per arm, within-scene/
```

Identities: `docs/data/<corpus>/*.json` for the sources, and the study documents under
`docs/plans/` for every arm and packet, each of which records the digests of what it read and wrote.
`filmfestival/` and `memento/` are staged but **not admitted**: their records state
`admissionStatus.state = proposed`, and no court is open for either.

## Rules

- Sources are read-only. A new source is admitted through `docs/data/` first.
- The study record is written only by the study tools. Deleting an arm directory deletes evidence
  a landed document cites; the documents quote the numbers, but the row-level record is here.
- Video bytes are never copied elsewhere and never referenced by path in a committed file.
- Recall prose lives only here and in the study record derived from it. A file admitted to Git from
  this tree must be content-free: identities, counts, digests, unit indices, segment numbers.
