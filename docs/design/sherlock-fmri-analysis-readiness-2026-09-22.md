# Sherlock recall-to-encoding mapping for fMRI

Assessment on 22 September 2026 against `main` at
`9f88599773b322a3300d9ea72839fcb9c70c6a45`. This is an investigation and proposed
analysis contract, not an implementation, new ADR, or efficacy qualification.

Target selected by the owner during this investigation: **OpenNeuro raw/BIDS
ds001132**. Princeton timing is retained as source provenance and a comparison
clock, not the default analysis coordinate.

**Conclusion:** an annotation-assisted recall-to-video reconstruction already runs.
It emits recall-unit timings, source intervals, alternative anchor masses, and
provenance. A video-caption augmentation path also exists. What is missing for a
reliable fMRI workflow is a checked join to the particular imaging release and
preprocessing history, honest projection from units/intervals onto samples, and a
stable public mapping interface. These can be delivered without waiting for a
complete audiovisual story compiler.

## 1. The requested object

Treat the fundamental result as a relation between recall units and source
occurrences, with uncertainty and explicit external outcomes:

`P(source occurrence, mode | recall unit, source evidence, model policy)`.

Each occurrence has exact source support, possibly an interval union. A lookup at
recall time `t_r` first identifies the timed unit or units that cover it under a
declared timing policy. Their assignments then identify encoding support. A
single `t_e`, a top-k list, and a time-bin matrix are derived views of that object.
They need not contain the same information.

There are two different meanings of “several matches”: competing hypotheses about
one referent, and a recall statement genuinely describing several events. Top-k
alternatives address the former. The latter needs compound-reference or
decomposition semantics; it is not established by displaying several candidates.

For this application, encoding time means **presentation time**, including
flashbacks where they were shown. It does not mean the chronology of the fictional
world. Keep that choice even though the historical Sherlock inference configuration
declares world order to be presentation order.

## 2. The clocks, cartoons, and scan joins

The original experiment prepended a nominal 30-second cartoon to **each** viewing
run. The original analysis retained those introductory events, and the 50-scene
population includes them. Therefore “cartoon” is a valid source occurrence, not
automatically an intrusion. See the [original methods](https://hassonlab.princeton.edu/document/361).

The local annotation has a more specific segmentation fact: scene 1 and scene 28
each occupy `[0,39)` seconds in their respective parts; the following scene begins
at 39 seconds. This was checked from numeric columns only. It does **not** establish
that the cartoon's visual content lasts exactly 39 seconds. The nominal duration,
annotation boundary, and exact transition in these video bytes still need an
explicit reconciliation before defining a Sherlock-content-only clock.

Recommended authoritative coordinate is `(edition, part, playback tick)`, with
zero at the start of that video part, including its introductory material. Offer a
derived concatenated presentation clock with zero at part A's start, excluding the
scanner break. Its definition is:

```text
part A: t_e = part-local seconds
part B: t_e = 1426.2 + part-local seconds
```

This concatenation is a declared presentation composition, not elapsed scanner
time across runs. A Sherlock-only view should use an explicit interval selection
and piecewise composition. Removing two introductions cannot be implemented by
one global subtraction. Retain both cartoon occurrence identities even if their
content is identical: their encoding brain samples are different, and recall may
not identify which presentation it refers to.

The admitted [timebase record](../data/sherlock/timebase-repair.json) distinguishes:

| Coordinate | Observed definition | Appropriate use |
|---|---|---|
| Part A playback | 1,426.2 s, 2,500 ticks/s | Exact local media lookup |
| Part B playback | 1,554.8 s, 2,500 ticks/s | Exact local media lookup |
| Composed playback | 2,981.0 s; B starts at 1,426.2 | Continuous presentation display/export |
| Raw annotation | Seconds restart at row 483 | Annotation-to-part join |
| Notebook repair | Drop rows 481–482; add 1,419 to run 2; endpoint 2,963 s | Reproduction of that analysis axis |
| Annotation TR labels | 1-based, 1–1,976; TR 1.5 s; two missing break rows | Declared annotation/scanner scaffold |
| Recall clocks | Separate Princeton and OpenNeuro columns | Release-specific recall join |

The notebook endpoint is 18 seconds shorter than the full media composition.
Rows 481–482 cover the recorded scan-break material but have no TR labels. The
raw annotation leaves 0.2 s of part A and 10.8 s of part B uncovered. None of these
gaps should acquire an inferred narrative assignment just to complete a matrix.

There is also a current display discrepancy. `SherlockSourceAtlas.of` composes
parts using complete manifest durations. `VoyageExport.SourceClock.of` instead
places sorted parts after their maximum annotated endpoint. On this corpus that
puts B at 1,426.0 rather than 1,426.2 seconds. Some study scoring tools use another
ordering-only sentinel. Use native loci plus the checked composition for fMRI;
do not recover physical time from a plot's horizontal coordinate or an ordering
score. See [source composition](../../corpus-intake/src/main/scala/storymodel4s/corpus/intake/SherlockSourceAtlas.scala)
and [Voyage export](../../embed-bench/src/main/scala/storymodel4s/bench/video/VoyageExport.scala).

The imaging release adds a distinct transformation. Its [README](https://raw.githubusercontent.com/OpenNeuroDatasets/ds001132/master/README)
specifies subject-specific cropping before scene labels apply: most encoding runs
lose 20 initial TRs, one subject loses 6, and end crops differ. Resulting runs have
946 and 1,030 volumes. Recall crops include negative values representing padding.
These crop counts must not be interpreted as instructions to remove the cartoon.

For a verified crop-only array transformation, zero-based indices obey
`raw_index = cropped_index + beginning_crop`. Negative results identify padded
positions with no acquired sample. That equation is not a complete stimulus-onset
or hemodynamic model. The general timing join requires each run's stimulus origin,
volume acquisition times, and transformations already applied to the supplied
array. Censoring is a mask or explicit index map, not permission to compress time.

Use an explicit imaging manifest containing release/version, subject crosswalk,
run, array identity and length, TR or acquisition timestamps, index base, onset
reference, crops/padding, censoring, and any temporal shift already applied.
The [word-timestamp release paper](https://pmc.ncbi.nlm.nih.gov/articles/PMC10460947/)
explicitly distinguishes the Princeton and OpenNeuro recall clocks. Current
`RecallWordsCsv.parse` reads column 2 (Princeton onset) and discards the other timing
columns; it has no release selector.

A content-free comparison of the six timing columns in the 16 local convenience
exports found OpenNeuro word onset equal to Princeton word onset plus **7.5
seconds (five TRs)**, with decimal serialization differences no larger than
0.0000000000002 s. Thus the present parser would expose recall coordinates five
TRs early if its output were interpreted directly on that OpenNeuro word clock.
Use the explicit released OpenNeuro columns and verify the selected artifact;
do not replace this with an undocumented hard-coded adjustment. The alias map
also omits canonical recall source 05 and shifts subsequent numbers, so numeric
filename resemblance is not a sufficient participant join.

Live public BIDS metadata supplies TR=1.5 s in the
[encoding task JSON](https://raw.githubusercontent.com/OpenNeuroDatasets/ds001132/master/task-sherlockPart1_bold.json).
For subject 01 the event files contain only task-level rows: encoding onsets 0
with durations 1,419 and 1,545 s, and recall onset 3 with duration 777 s. See the
[part-1 event file](https://raw.githubusercontent.com/OpenNeuroDatasets/ds001132/master/sub-01/func/sub-01_task-sherlockPart1_events.tsv),
[part-2 event file](https://raw.githubusercontent.com/OpenNeuroDatasets/ds001132/master/sub-01/func/sub-01_task-sherlockPart2_events.tsv),
and [recall event file](https://raw.githubusercontent.com/OpenNeuroDatasets/ds001132/master/sub-01/func/sub-01_task-freerecall_events.tsv).
These rows do not independently reconcile the README's crop instructions with
the local playback origin or identify the cartoon boundary. Pin a dataset version
and inspect the actual analysis arrays and preprocessing receipts before choosing
the final scanner transform. Do not silently equate event-file zero, video zero,
and a cropped-array zero merely because all are represented by 0.

Keep hemodynamic modeling separate from these coordinate maps. A chosen lag or HRF
changes the analysis projection, not the meaning of `t_e`. Record it for encoding
and recall separately and avoid applying a shift already present in the arrays.
No imaging arrays or local preprocessing history were inspected in this assessment, so
no participant-specific scan alignment is certified.

## 3. What the implementation can do today

| Capability | Current evidence and limitation |
|---|---|
| Admit the source | `SherlockAnnotations` verifies annotation bytes and executes checked per-run `ClockRepair`; separate receipts identify every row. |
| Preserve film coordinates | Exact ticks, points, interval unions, bundles, and composition exist in `core`; `SherlockSourceAtlas` preserves native and composed support. |
| Build mapping inputs | 1,000 annotation rows and 50 grouped scene targets; annotation prose and selected fields supply scoring features. These are not automatically accepted narrative semantics. |
| Read/segment recall | Word CSV to joined transcript to `RecallSegmenter`; unit onset and last-word onset retained. No measured word offset is supplied. |
| Retrieve and infer | Local ONNX sentence embeddings or lexical fallback, lexical reranking, hierarchical candidates, `GraphHsmm`, external states, posterior, transition flow, and Viterbi machinery. |
| Choose a reconstruction | Scene-monotone decoding and in-scene fill are enabled by default in the bench mapper. They are additional decisions after posterior inference. |
| Inspect uncertainty | TSV plus posterior, optional stage trace, clock-repair, and Voyage sidecars. The posterior sidecar exposes anchor masses and the selected anchor's own mass. |
| Add video information | Local frame extraction, pinned caption workers, checked media caption proposal APIs, and bench caption input exist. This is sampled visual caption augmentation, not comprehensive audiovisual inference. |
| Public analysis package | Still incomplete. The existing executable lives in `embed-bench`; `storyBuild` is a separate text-to-story compiler command. |

Code anchors: [Sherlock command/view](../../embed-bench/src/main/scala/storymodel4s/bench/sherlock/SherlockRecallMapping.scala),
[mapping orchestration and CSV timing](../../embed-bench/src/main/scala/storymodel4s/bench/video/RecallToVideo.scala),
[alignment row API](../../align/src/main/scala/storymodel4s/align/matrix.scala),
[caption contracts](../../media/src/main/scala/storymodel4s/media/caption.scala).

Important output qualifications:

* `AlignmentRow.topK` ranks **states**. For top-k encoding locations, first sum
  modes belonging to the same anchor, as `anchorMass` does. Preserve external mass.
* These are model posterior masses under the retained candidates and model
  assumptions, not calibrated probabilities of correct localization.
* The legacy TSV's `mapAnchor` may be changed by decoding while `mapAnchorMass`
  still describes the posterior maximum. Read `posterior.json`'s `decoded.mass`
  for the chosen anchor. A fill can choose a target with zero original posterior
  mass. The chosen path must not overwrite the uncertainty distribution.
* Source and scene nodes overlap physically. A posterior over hierarchical targets
  is not yet a posterior over seconds. Choosing a fixed target cut or allocating
  coarse support into time requires a declared projection.
* The sidecar serializes masses to six decimal places; expect small sum-rounding
  errors. It is a diagnostic wire, not the final full-precision analysis contract.
* The mapper is annotation-feature based. Its execution does not automatically
  engage proposition parsing, validated causal structure, or grakern distance
  merely because those mechanisms exist elsewhere in the library.

`hsmm/v4` support honesty has landed and was qualified in the
[22 September evidence](../refactor/evidence/support-honesty-20260922/README.md).
That wire deliberately does not serialize non-text physical support. G1 mapping
records are the planned separate carrier. Live Mote shows G1 as `doing`, with
qualified partial work on `solo/workspace-m1-g1`; it is not complete on this main
revision. Timing, exchange tables, and the public facade remain G2 work. The M1
viewer milestone does not itself certify an fMRI workflow.

## 4. Annotations versus annotations plus video

Run a paired comparison with identical recall units, target cut, candidate budget,
decode policy, and evaluation population:

1. **A: annotations.** Descriptions and explicitly selected annotation fields.
2. **AV: annotations plus local visual evidence.** The same targets, augmented with
   timestamped frame/clip captions or separately scored visual features.
3. **Text-length control.** Additional annotation text of comparable length where
   caption concatenation changes the text budget.

The existing path accepts `STORYMODEL4S_SCENE_CAPTIONS` and
`STORYMODEL4S_CAPTION_CHANNEL=embed|lexical`. Historical experiments already used
50 scene captions from eight sampled frames each. The study log reports that
embedding scene captions shifted assignments toward coarse scene nodes; the
apparent ordering gain weakened on a matched-granularity subset. Lexical caption
augmentation on the stronger annotation baseline showed no clear gain in its
reported diagnostics. Those diagnostics are not a fine-time localization or fMRI
validation, so the evidence does not establish that video is useless. See the
[historical study log](../plans/2026-09-02-recall-to-video-study-log.md).

Two integration gaps matter. The bench caption loader reads a JSON `captions` map
without replaying the media adapter's full model/frame/receipt checks. The frame
extraction script selects one majority part for a scene crossing parts. A robust
analysis path should bind exact evidence extents and caption provenance, preserve
partial coverage, and refuse mismatched editions. Sampled frames do not prove
coverage of dialogue, fleeting actions, or an entire long scene.

The useful first comparison is therefore A versus the existing AV mechanism with
those joins repaired, assessed on an independently annotated development sample.
Audio/ASR, motion-sensitive clip features, typed proposition parsing and structural
reranking are subsequent explicit arms. Do not make the complete film compiler a
prerequisite for the first clock-correct annotation analysis.

Historical scene accuracy was reported around 63.8% over 15 participants, with
substantial gains from monotone decode/fill. This is a historical development
record, not a fresh result, clean held-out estimate, calibrated confidence claim,
or evidence of second-level precision. The committed within-scene human answer
lane remains absent; the machine lane is explicitly diagnostic. Freeze new choices
on development material and preserve existing sealed-test rules.

## 5. From uncertain intervals to fMRI samples

Let `p[u,j]` be mass on source target `j` for unit `u`. Define a separately recorded
projection `K[j,b]` onto encoding time bin `b`, with each completely located target
allocating total mass one across its supported bins. Then:

`W[u,b] = sum_j p[u,j] * K[j,b]`.

For a single interval and uniform-within-support policy, `K[j,b]` is its overlap
with bin `b` divided by its support duration. Disjoint support uses the interval
union, not its convex hull. A point needs an explicit point-to-bin convention.
Uniform allocation is a projection assumption, not evidence that recall was
localized inside the interval. Partially located targets need an explicit coverage
policy; do not redistribute unknown support silently.

The full row accounts separately for projected source mass, unlocated source mass,
external mass, and any omitted known top-k mass. Unknown candidate coverage is a
separate limitation; it is not a calculable leftover probability. Processing
failure is a processing outcome, not a probability destination.

Recall projection needs a corresponding explicit rule. The present timestamps
are word **onsets**, so `[first onset,last onset]` is not a measured speech
duration. Useful initial outputs are onset events and unit-level mappings. A
dense recall-TR representation requires measured/estimated offsets or a declared
kernel/hold policy, including silence, missing times, overlapping support, and
the final word. Copying a clause's assignment to its words improves lookup
resolution but creates no new independent localization observations.

For downstream Python/R, export a sparse recall-sample by encoding-sample weight
matrix plus row/column coordinate tables, missingness masks, and the original
unit-level alternatives. That can support weighted encoding-pattern summaries,
reinstatement comparisons, event aggregation, and uncertainty sensitivity
analyses. Conditional normalization by source mass is legitimate only as a named
analysis choice, retaining the original coverage alongside it. A weighted average
of patterns and a weighted average of correlations are different estimands.

Use a declared reconstruction profile for localization and a separately validated
reference profile for behavioral order/revisit measurements. The current default
monotone scene decoder forbids scene-level backward movement; switching it off
still leaves the HSMM's sequencing assumptions. Neither is automatically an
independent measure of temporal organization. Neural data used to tune alignment
also cannot serve as an independent test of its neural reinstatement without a
proper separation of fitting and evaluation.

## 6. Execution now and the desired public interface

The current command signature is real and was verified against source and the
study runner:

```sh
sbt -batch "embedBench/runMain storymodel4s.bench.sherlock.sherlockRecallMap <annotation.tsv> <recall.csv> <report.tsv>"
```

Replace bracketed arguments with local files. Set both
`STORYMODEL4S_ONNX_MODEL` and `STORYMODEL4S_ONNX_TOKENIZER` to use the pinned local
encoder; otherwise it uses the lexical fallback. `STORYMODEL4S_STAGE_TRACE=on`
retains stage diagnostics. Set `STORYMODEL4S_MONOTONE_SCENE=off` and
`STORYMODEL4S_MONOTONE_FILL=off` only when intentionally requesting that
reconstruction ablation. Record all settings; ambient environment variables are
part of the historical harness's behavior.

For AV, run the same command with a verified caption artifact and the caption
channel selected. Caption generation remains a separate local preprocessing step.
The historical `run-arm.sh` batches participants but skips any existing TSV by
existence alone, so it is not a safe resumable analysis runner when configuration
or sidecars change. Fresh output directories avoid that particular hazard.

Recommended product interface, **proposed, not available commands**:

```text
storymodel source prepare --config source.yaml --out source/
storymodel recall map --source source/ --recalls recalls.tsv --config annotation.yaml --out A/
storymodel recall map --source source/ --recalls recalls.tsv --config annotation-video.yaml --out AV/
storymodel mapping export --mapping A/ --fmri-manifest openneuro-scans.json --projection projection.yaml --out analysis-A/
```

The source bundle is reused across participants and arms. Explicit configuration
binds input hashes, model and feature pins, candidate policy, inference/decision
policy, clock choice, target grain, and export projection. Atomic publication and
manifest verification make resume meaningful; incomplete runs must not masquerade
as completed mappings.

Minimal analysis outputs:

| File | Content |
|---|---|
| `manifest.json` | Exact source/recall/run/model identities, configuration, schemas, receipts |
| `units.tsv` | All requested units, word membership, selected recall clock, timing status, processing outcome |
| `targets.tsv` | Qualified target/occurrence IDs, part, support status and annotation identity |
| `target-support.tsv` | One row per interval or point per axis, including exact ticks |
| `alternatives.tsv` | Unit, target or external state, rank, mass type, value, candidate coverage |
| `decisions.tsv` | Selected target, that target's mass, decode/fill origin, calibration status |
| `scan-crosswalk.tsv` | Release/subject/run/native and analysis sample joins, masks and transform receipts |
| `weights.npz` or coordinate TSV | Sparse projected weights, with explicit row and column inventories |

TSV/JSON plus one small Python or R reader is enough initially. Parquet, a Scala
dependency in the analysis notebook, and NIfTI processing inside storymodel4s are
not necessary for the first deliverable.

## 7. Smallest useful implementation sequence

1. **Complete existing G1 records.** Preserve unit accounting, measure semantics,
   exact source support, decisions, and provenance. Reuse the active work; do not
   start a competing mapping schema.
2. **Add the Sherlock imaging crosswalk within the timing/intake work.** Select
   OpenNeuro columns explicitly, bind aliases to participants, reconcile
   introductory intervals, encode per-run crop/sample transforms, and replace
   display-derived concatenation in physical exports.
3. **Complete the G2 facade and exchange consumer.** Produce one participant's
   annotation mapping and independently recover its alternatives and coordinates
   in Python/R. Then add batch publication and scan projection.
4. **Run the paired AV development comparison.** Bind caption evidence through the
   existing media contract, freeze the target cut and units, and assess actual
   localization rather than posterior concentration or monotonicity alone.
5. **Qualify the scientific readout.** Test human-scored timing accuracy at the
   intended grain, coverage/abstention, calibration if probability claims are
   wanted, and sensitivity of the fMRI result to uncertainty and projection.

Critical falsifiers: crossing the A/B boundary; accidentally using 1,419 or 1,426
instead of 1,426.2 for composed playback; conflating the two introductory
occurrences; wrong participant alias or recall release; applying a crop/HRF shift
twice; off-by-one volume indexing; assigning data to padding/censored samples;
turning last-word onset into a measured offset; dropping failed units; labeling
argmax mass as selected-target mass; renormalizing top-k or source-only values
silently; smoothing across a run break or a gap in support; and changing target
grain between A and AV.

## 8. Evidence from this assessment

Executed locally:

* `python3 tools/recall-study/test_freeze_baseline.py -q`: 14 tests passed.
* `bash tools/data-root.sh --check`: required annotation, recall, encoder and study
  partition paths present. No held-out recall or gold was opened for evaluation.
* Recomputed annotation SHA-256 and both media SHA-256/byte lengths: all matched
  the admitted record. No decoding or frame-semantic verification was performed.
* Ran the independent integer-coordinate oracle on all 1,000 annotation rows:
  every locus matched the frozen inventory, including one point support.
* Inspected numeric scene boundaries, CSV headers, source records, current code,
  prior evidence records, live G1 status, public BIDS task metadata, and differences
  between released recall clocks. Participant prose was not printed.

The full historical clock-repair verifier was also attempted and **refused** with
`changed artifact: build.sbt`: the old manifest binds a different build. This was
not weakened or reported as a pass. The narrower coordinate/hash checks above do
not establish current end-to-end mapper parity. No new model inference, full
`sbt checkAll`, fMRI analysis, calibration, or held-out efficacy evaluation was run.
