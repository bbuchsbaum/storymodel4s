# Film Festival: structured inference helps; scene fill loses the gain

The diagnostic implementation is complete. Steps 1, 2 and 5 have run on the existing corpus.
Steps 3 and 4 have tested preparation/intake workflows, but their scientific measurements await
independently reviewed film correspondence and human annotations. They are not completed studies.

## Same candidates, local costs and eligible recall units

All stages below use the full JL index and the same 3,751 eligible first-run recall units from
15 participants with gold. Twenty participants were run; five lack first-run gold. The source
selectors aggregate faithful/distorted modes at each anchor and choose the highest-mass source.

| Stage | Correct film | Accuracy |
| --- | ---: | ---: |
| Independent local source selection | 2,730 | 72.78% |
| HSMM posterior source selection | 2,900 | 77.31% |
| Scene decode without fill | 3,056 | 81.47% |
| Existing final decode with fill | 2,531 | 67.48% |

Independent local masses normalize the actual retained admissible costs at the run's temperature,
with a uniform state prior and no transition/refinement contribution. External states remain in
that normalization. This is an engineering comparator, not calibrated confidence.

| Change | Corrections | Regressions | Paired change, percentage points | Participant-bootstrap 95% interval |
| --- | ---: | ---: | ---: | --- |
| Local source selection to HSMM source selection | 239 | 69 | +4.53 | [+3.71, +5.33] |
| HSMM source selection to scene decode without fill | 250 | 94 | +4.16 | [+2.40, +5.61] |
| Decode without fill to existing final decode | 286 | 811 | -14.00 | [-20.60, -6.82] |

The no-fill result comes from the existing decoder on the same posterior with its fill callback
disabled. It retains the existing fallback to the unconstrained source anchor when a scene cannot
bind the unit; it does not discard those units. All stage denominators are fixed. The no-cartoon
arm independently shows the same pattern: 81.42% without fill and 67.45% with fill.

The correct film is among nominated candidates for 3,571 units (95.20%). This is a retrieval
ceiling only for selectors restricted to those candidates. Fill searches beyond them: 1,542 final
anchors have no mass in the original posterior, and only 491 of those are in the gold film.
That origin is now reported explicitly, separately from supported assignments.

**Decision:** the next prospective configuration to evaluate is scene decoding without fill.
Keep the measured contribution of the HSMM and scene decoder visible. No shipped default changed
in this diagnostic slice, and no unseen-sample or within-film timing claim follows from these
development numbers. Intervals resample whole participants, 4,000 draws, seed 20260904; they do
not establish generalization to new films.

The all-state local argmax answers a different question: it chooses an external state for 3,414
units and a source for 337 (321 in the correct film). The primary film-affiliation gold cannot
adjudicate whether those external interpretations are scientifically warranted. Both all-state
and source-only choices remain in the per-unit report; neither is silently substituted for the other.

## What caused the cartoon experiment's changes

The 90 corrections and 91 regressions now have an earliest observed divergence:

- 31 changed nominated candidate membership.
- 69 retained membership but changed nomination ranks or raw scores.
- 81 retained those nominations and local costs but changed posterior mass.

The last group is consistent with changes propagating through sequence inference from other units;
the local trace alone does not identify which neighboring change caused each result. Comparing
posterior masses at individual anchors prevents within-film mass shifts from being mislabeled as
decoder-only changes. Source identities preserve content and bounds across replay renumbering.

A deterministic sample of 12 corrections and 12 regressions is fixed by SHA-256 of participant and
unit identity, with seed label 20260905. The private casebook includes recall prose and the four
stage decisions; the committed comparison receipt includes identities and stage changes only.
Every eligible unit also has a readable TSV and a full JSON trace-derived diagnostic row.

## Matched sources and independent judgments

`filmfest_source_court.py` inventories 170 released windows across the five films other than the
known longer `cmiyc_long` cut. Fifteen final windows have shorter actual durations than the mapped
30-second window; 11 mapped proposals extend outside the annotation film extent. Ten individual
responses disagree with their released window bounds. These are recorded conflicts, not repaired
coordinates or evidence of exact media equivalence.

The generated correspondence worksheet is unreviewed. The preparer refuses it until a named
independent review records corresponding intervals and a hashed evidence artifact. Hash checking
binds that human judgment; it cannot prove the judgment. Coder rows must be wholly inside a reviewed
window. The factorial then holds the selected windows fixed across source origin (JL/crowd),
aggregation (medoid/SHA representative) and candidate budget (one/two per window). Budget one is
nested in budget two; eligibility uses the largest budget before any arm is prepared. No recall
gold selects the windows or descriptions. Semantic coverage can still differ between source pools.
The output retains whole-film loci and therefore still scores film identity only.

The blinded packet contains **33 cases and 844 source references**, including full recall context.
It exposes no model choices, old gold or selection strata to the annotator. Intake keeps ambiguous
alternatives distinct from multiple jointly required anchors, accepts insufficient evidence without
fabricating an anchor, and requires a named independence attestation. Source-description judgments
do not establish video timing. The score reports accepted-union mass and required-anchor support;
it explicitly leaves full merge recovery unmeasured by this single-anchor output.

Unreviewed correspondence and the unanswered annotation template both fail before writing a score
or matched source index. No independent labels or verified media have been fabricated. All existing
participants remain development data; a genuinely unseen confirmation sample is still needed.

## Evidence and reproducibility

Inference ran at `c39328902ab034b2f5733ac9569de06567bc469a`, with explicit historical settings and
the verified local ONNX encoder. The two complete arms took 407.9 and 376.5 seconds. All **120**
pre-existing TSV, posterior and voyage files are byte-identical to the uninstrumented runs.
The only added inference artifacts are opt-in stage traces. The aligner itself is unchanged.

The first full `sbt checkAll` gate passed at `a4f8e4d6`: **5,896 passed, five skipped, zero failed**
across 52 test-task totals. Final Python contracts pass **38 tests**. Eleven Python mutations
compile and fail their named assertions while a sibling passes. Reversing the Scala local-cost
preference compiles, fails the analytic odds-ratio and single-unit HSMM oracle assertions, and
leaves three sibling tests green; all five pass again after restoration. Separate cold reads
identified and verified fixes for trace binding, zero-mass anchors, divergence attribution,
packet binding and unbound replay sidecars. Final landing receipts are in the directory below.

The full gate uses the permitted local grakern override at
`d736dc565d97f617726bad0a9d1ba2fdeae58dd2` (an unrelated README edit remains). This is local evidence,
not evidence against the declared remote grakern pin, hosted CI or release certification.

Content-free results and receipts: [diagnostics-20260905](../data/filmfestival/diagnostics-20260905/).
Source/recall prose, the casebook, packet and raw outputs remain under the ignored data root:
`study/filmfestival/diagnostics-20260905/`.

```bash
# In a standalone clone, point STORYMODEL4S_DATA at the primary checkout's ignored data directory.
D=$(bash tools/data-root.sh)
python3 tools/recall-study/filmfest_replay.py run \
  docs/data/filmfestival/diagnostics-20260905/replay-manifest.json \
  "$D" "$D/study/filmfestival/new-saved-replay"
```

That command verifies 124 inputs, both full recall populations, stage counts and the fixed diagnostic
sample, then runs the Python contracts. The observed first replay took about three seconds. It
replays saved evidence; it does not rerun a changed Scala engine. Newly consumed sidecars are refused
unless the manifest binds them. A new output directory is required on every run.

To finish the pending scientific work, the local files are:

- `packet/packet.json` and `packet/answers-template.json`: provide independent annotations, saving
  a separate answers file. Keep `packet/PRIVATE-selection.json` away from the annotator.
- `correspondence-template-v2.json`: record reviewed film/window correspondence with its evidence.
  Unreviewed windows can remain accounted for and excluded; only reviewed eligible windows run.

Source attribution follows the [Film Festival provenance record](../data/filmfestival/README.md):
Lee, Chen & Hasson (2023), OpenNeuro `ds004042` v1.0.1; `jchenlab-jhu/filmfest` at `0ffad622`;
and `jchenlab-jhu/filmfest-textdata` at `bf22326d`.

Derived from openly released research data. Where the upstream release carries no explicit
licence, it is used here for non-commercial academic research under an open-science reading, with
attribution to the original authors. No source bytes are redistributed. The original authors have
not reviewed or endorsed this use.
