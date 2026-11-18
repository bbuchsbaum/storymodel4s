# From words to recording windows

A saved feature table lets you ask which words occurred in a recording window, what
their measured values were, and which recall details began there. Reproduce the synthetic
Sherlock example from the port directory with the installed package.

```sh
python examples/feature_windows.py data/word-example
cd data/word-example
storymodel feature-windows analysis-1.json timed-recall-1-query.json norms.json --output measured.json
storymodel verify-feature-windows analysis-1.json timed-recall-1-query.json norms.json measured.json
```

The Python entry point is `feature_windows(plan, query, lexicon)` in
`storymodel.feature_windows`. Supply a `RecordingQuery` and a `Lexicon` with a name,
units and finite scalar entries. The table records the replay digest, query, lexicon,
original UTF-16 word spans, occurrence IDs, observed recording ticks and individual scores.
The method counts Unicode code points in the lexical form. Its lexicon lookup uses the
project's simple lowercase normalization. A changed norm changes the lexicon identity.
Canonicalizing the lookup keys permits table reuse across input orderings.

Each recording window is half open. Its mean uses only measured lexical occurrences.
The two counts next to each mean tell how many eligible words were measured and how many
lacked a value. Missing timing and measured onsets outside the selected windows have
their own occurrence lists. Details appear in the window containing their unit's first
lexical onset. The full detail rows retain joint, alternative and unresolved reference
information.

| Window in the example | Word | Mean length | Mean synthetic score | Scored / eligible | Detail onsets |
|---|---|---:|---:|---:|---:|
| [100,108) | AA | 2 | 0 | 1 / 1 | 1 |
| [108,116) | BB | 2 | unavailable | 0 / 1 | 0 |

Both source-scene and measured-unit summaries use the same two reducers. Source-scene
values describe the **annotation text** of the member rows. A measured-unit summary
covers that unit's words with known onsets inside its declared interval. Neither
mean assigns duration to a point observation.

Open the generated `feature-windows-1.json` with the file picker in the React workbench
(`cd port/web && npm run dev` from the repository root). Choose Recording windows,
Recall details, or Unplaced words. The workbench also reads saved analysis replays.
Files are read locally and the displayed scores come from Python.

The Scala behavioral references are `features/measure.scala` (TokenLength and LexiconTable),
`MeasureSuite` and `WindowSuite`. This Python profile uses lexical occurrences as the
eligible population. Nonlexical punctuation is outside that population. Additional feature
spaces, smoothing kernels, derivation tracking and the full eligibility ledger remain open.

## Caption evidence and alignment

The same synthetic example saves a separate `captions.json` and a decoded alignment.
Run `storymodel caption-alignment analysis-1.json captions.json --output aligned.json`.
The sidecar names source occurrences and binds the source account digest. Supply a blend
between 0 and 1 to choose the contribution of the stemmed caption distance to the lexical
distance. The existing inference pipeline uses the resulting local costs. Inspect the
retrieved candidates first, then caption scores and local rankings, then the decoded
sequence. This ordering lets a researcher distinguish a target excluded by the retrieval
budget from a retrieved target that scored poorly.

The Scala reference is `align/.../captionscoring.scala`. The Python adapter accepts an
explicit target-to-caption declaration. Scala's media, clock and multi-part correspondence
checks would be needed to derive that declaration automatically from physical caption files.

## A second study

From the port directory, run `python examples/friends_workflow.py data/friends-example`
and open `data/friends-example/analysis-result.json` in the workbench. This example starts
with synthetic, decoded study rows. It preserves both storyboard segmentations, the selected
source scale, and the original and presentation times. A reported whole-recall duration
has its own field. The example carries the recall transcript through to a saved analysis
without manufacturing word-level measurements.

The Scala reference is `corpus-intake/.../FriendsIntake.scala`. Workbook parsing, full
workbook qualification and participant-data admission are separate pieces of the corpus
workflow. Raw study data is unnecessary for the synthetic exercise.
