# Film Festival: stabilized evaluation and development results

The cartoon exclusion produced no net gain. An independent crowd-description index produced
similar observed film-selection accuracy to the coder index in the declared diagnostic comparison.
Neither result selects a configuration or clears a source-admission court.

## Corrected baseline and the cartoon experiment

All four arms ran at `7d81e4aa2fb5ff99939cdb4ab3c8666040685afa`, over integration base
`680ef6e652632fdd22f3410fb3b5e7caa7c80809`. Each completed all 20 first-run participants with the
verified ONNX channel. Fifteen participants have first-run gold. The original reports remain intact
under the ignored data root. The full JL input is byte-identical to the historical annotation.

| Arm | Eligible units | Correct film | Anchor coverage |
| --- | ---: | ---: | ---: |
| Integrated JL baseline | 3,751 | 67.48% | 100% |
| Exclude both cartoon candidates | 3,751 | 67.45% | 100% |

Paired change: **-0.03 percentage points**, participant-bootstrap
95% interval **[-0.60, +0.76]**. There were **90 wrong-to-correct** and
**91 correct-to-wrong** transitions. All 207 baseline cartoon placements disappeared; their
redistribution did not improve the total. Cartoon-labelled recall stayed eligible in both arms.
**Decision: retain the original candidate policy; do not promote exclusion on this result.**

## Independent source comparison

| Gold subset | Eligible units | JL index | Crowd index | Crowd minus JL, paired 95% interval |
| --- | ---: | ---: | ---: | ---: |
| Six films | 2,717 | 82.30% | 82.81% | +0.52 pp [-0.61, +1.91] |
| Five-film sensitivity | 2,350 | 81.83% | 82.09% | +0.26 pp [-0.97, +1.61] |

Coverage was 100% in every row. These are film-identity comparisons: all source candidates carry
whole-film annotation intervals, without scene groups or a claim about crowd-window timing.
The JL index contains 435 candidates; the crowd index contains 236 window medoids, selected using
7,082 responses after excluding 17 whose bounds conflict with their released window definition.
The input receipt accounts for all 7,099 cleaned responses. No recall gold selected the medoids.

There is **no observed collapse in this setup**, and no demonstrated superiority or formal
non-inferiority claim. Source wording, aggregation and candidate density change together. The crowd
release's `cmiyc_long` is a longer cut than the scanner excerpt; the six-film result therefore remains
diagnostic. The prespecified five-film sensitivity excludes that film from gold eligibility while
retaining the same six-film candidate indices. Exact media equivalence remains unestablished.
These values must not be compared directly with the twelve-block baseline's 67.48%: both the
candidate representation and the scored population differ.

## What the repairs changed

- Literal TSV readers restore **3,226 gold rows**, versus 3,181 under default CSV quote parsing.
  Eligible model units increase from 3,688 to 3,751; units without overlapping gold fall from
  71 to 7. The aggregate reports account separately for 834 units in five participants without
  gold, 32 off-task/search units and the seven without overlap.
- Film intervals are half-open, so a boundary belongs to the film beginning there. Both cartoon
  durations contribute to the chance comparator. Chance is **not** a human ceiling.
- Unanchored eligible units remain in the primary denominator; accuracy conditional on anchoring
  is reported separately. The historical lexical arm had nine unanchored eligible units.
- Intervals resample participants, retaining their whole unit clusters (4,000 draws, seed 20260904).
  The primary pooled ratio stays unit-weighted. Arm comparisons verify the complete recall
  population, including excluded units, before checking eligible labels.
- On the repaired scorer, historical ONNX accuracy is **67.80%** with interval **[60.53, 76.22]**.
  The integrated baseline is **67.48%** with interval **[59.94, 76.01]**. Integration was not
  output-identical: it lost 92 previously correct units and corrected 80 others (net -0.32 pp).
  This was an engineering audit added during execution; no cause is isolated by it and no arm was
  selected from it. The new comparisons use the freshly run baseline.
- The constant-anchor foil scores **0 seconds** on all 1,788 text-matched agreement pairs while
  achieving only **0.29% film accuracy**. Agreement is a supporting diagnostic. Its legacy
  pair-resampled intervals are labelled descriptive, not participant-generalization uncertainty.

The adapter supplies `Unknown(NotSupplied)` for story-world order, propagates build refusals, and
requires readable ONNX artifacts by default. Explicit lexical mode refuses ONNX variables. Sherlock's
channel defaults are unchanged. Participant prose, source descriptions and model outputs stay outside
Git. Annotation axes remain placeholders; word timings are interpolated. Fidelity facets from the
unpopulated semantic source view are outside this scoreboard.

## Verification and artifacts

Sixteen Python tests pass; eleven Python mutations and two Scala mutations are killed by named
assertions. Both adapter tests pass. Separate cold reads caught population, path and TSV-dialect
errors, verified their fixes and reported no remaining concrete blocker. `sbt checkAll` completed with exit 0: 5,891 passed tests across
52 test-task totals; 5 reported tests were skipped. The gate ran on the verified
`7d81e4aa` export; all Scala, build and resource files match the scorer/result commit `be5caa6e`
exactly, with a checked path-difference receipt. The 16 Python tests also ran on `be5caa6e`.
Final `scalafmtCheckAll` and `scalafmtSbtCheck` also passed on the result commit.
This is local evidence, not hosted CI or a release certification.

The full gate uses the permitted local grakern override at `d736dc565d97f617726bad0a9d1ba2fdeae58dd2`
(with an unrelated README edit), recorded in `dependency-state.json`; it is not evidence of a build
against the declared remote grakern pin. The experiment reports explicitly record no structural
channel, so grakern is not an inference channel in these arms.

Receipts and aggregate scores: [stabilization-20260904](../data/filmfestival/stabilization-20260904/).
They include input hashes, exact commands, encoder hashes, report hashes, participant counts,
confusions, paired transitions, intervals and mutation witnesses. No source or participant prose is
included. Original and newly generated text-bearing artifacts remain under
`$STORYMODEL4S_DATA/study/filmfestival/stabilization-20260904/`.

All four runs took 43.4 minutes in total;
per-arm times were crowd-six-film-identity: 7.4 min, jl-all: 17.5 min, jl-no-cartoons: 13.9 min, jl-six-film-identity: 4.7 min.
This is an observed runtime, not a portable performance claim.

## Reproduce

```bash
D=$(bash tools/data-root.sh)
# In a standalone clone, set STORYMODEL4S_DATA to the primary checkout's data directory first.
python3 tools/corpus/filmfest_experiment.py "$D" "$D/study/filmfestival/NEW-inputs"
python3 tools/recall-study/run-filmfestival.py "$D" "$D/study/filmfestival/NEW-inputs" \
  "$D/study/filmfestival/NEW-arms" --grakern /path/to/grakern
python3 tools/recall-study/filmfest_gold_film.py \
  "$D/filmfestival/derived/annotation-JL.tsv" "$D/filmfestival/derived/gold-scenematched.tsv" \
  baseline "$D/study/filmfestival/NEW-arms/jl-all" \
  no-cartoons "$D/study/filmfestival/NEW-arms/jl-no-cartoons" --json-out /tmp/cartoon.json
# The source comparison uses jl-six-film-identity and crowd-six-film-identity, with:
# --films 1,3,4,5,6,10  and separately --films 3,4,5,6,10.
python3 -m unittest discover -s tools/recall-study/tests -v
python3 tools/recall-study/tests/mutate_filmfestival.py
```

These remain development analyses on previously inspected participants. A newly drawn partition
cannot retroactively make them untouched. Human reliability, actual film acquisition/admission,
independently supported clocks and an independent confirmation sample remain open work; no REB/IRB
or redistribution basis is inferred from this exercise.

## Source attribution and reuse

Data and source identities are recorded in the [Film Festival provenance record](../data/filmfestival/README.md):
Lee, Chen & Hasson (2023), OpenNeuro `ds004042` v1.0.1; the `jchenlab-jhu/filmfest` annotation and
recall-gold release at `0ffad622`; and `jchenlab-jhu/filmfest-textdata` at `bf22326d`.

Derived from openly released research data. Where the upstream release carries no explicit
licence, it is used here for non-commercial academic research under an open-science reading, with
attribution to the original authors. No source bytes are redistributed. The original authors have
not reviewed or endorsed this use.
