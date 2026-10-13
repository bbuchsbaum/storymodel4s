# Recovery diagnosis: coverage, discrimination and concentration

The frozen campaign does not identify a defect in the organization arithmetic. Its large
packet discrepancy count combines missing coverage with deliberately uncertain assignments.
The useful improvement target is local content measurement: the existing MiniLM channel
resolves the paraphrase ties, while damaged names still produce wrong best matches. Temperature
changes affect concentration; they cannot restore information lost by the content scorer.

This is an exploratory diagnosis on the previously admitted synthetic controls. It changes no
production inference, default temperature, fixture, tolerance or original qualification report.

## Packet discrepancy decomposed

There are 36 distinct statistical configurations, each repeated four times to check storage
and display invariance. Of the 144 cases, 16 have complete point-mass mappings and recover
exactly; 32 have complete diffuse mappings; 96 omit an interior unit. The latter retain only
one of the three original adjacencies. Their total-count deficit is guaranteed by coverage.
The 128 nonzero discrepancies are not an estimated 88.9% failure probability.

The diagnostic retains the original complete truth and adds truth on measurable original
adjacencies. For every category:

```
reported count - complete truth
  = (reported count - measurable truth) + (measurable truth - complete truth)
```

Point-mass rows have zero assignment discrepancy even when a unit is missing. Original gaps
stay unresolved. No bridging or post-hoc rescaling is introduced.

An independently derived closed form reproduces all 144 count vectors to a maximum absolute
error of 2.22e-16. With four destinations, correct mass p and other-target mass r=(1-p)/3,
the retained forward/reverse contrast is p²-r² with all three adjacencies, or (p²-r²)/3 with
one surviving adjacency. The complete cases give 1, 5/9 and 2/9 at p=1, .75 and .5.

A separate finite generative control enumerates 4,096 latent/observation pairs at each of
four observation qualities. Under its explicitly known model, exact Bayes recovers perfect
observations, stays uniform at chance, and matches observation-conditional joint enumeration.
Population expected counts equal population truth even though individual path expectations
shrink. A uniform-returning mutant fails the informative checks. Thus path-conditional
shrinkage alone does not establish miscalibration. This control does not calibrate either
language channel. The dependence example in the JSON report is an analytical comparison of
joint distributions with identical marginals, not an executed production joint-readout test.

## Language diagnosis

The frozen comparison ran 96 conditions: two existing channels, four paths, three text
transformations and four temperatures. All completed. All 36 original lexical result objects
reproduced exactly. Independent normalization of the actual admitted state costs, including
external states and faithful/distorted multiplicity, reproduces every destination mass to
1.11e-16. Rank intervals distinguish source position, source-cost rank and destination-mass
rank; tied minima remain tied.

The following ranking columns describe the four distinct clauses in the forward control.
Contrast columns compare the frozen forward and reverse paths at the predeclared exploratory
T=.05. They are retained fractions of the known backward-count contrast of three.

| Condition | Lexical unique correct best | MiniLM unique correct best | Lexical retained contrast | MiniLM retained contrast |
|---|---:|---:|---:|---:|
| Exact | 4/4 | 4/4 | 0.999992 | 0.855351 |
| Paraphrase | 0/4, all tied best | 4/4 | 0.165192 | 0.521744 |
| Transcription | 0/4, all tied best | 2/4 | 0.000000 | 0.066377 |

Equal temperatures do not mean equal confidence: lexical Jaccard and MiniLM half-cosine
cost spreads differ. These figures diagnose mechanisms; they do not select a temperature or
establish held-out superiority.

Exact lexical matches already identify the correct destination. Their retained contrast
changes from .035270 at the template T=1 to .933814 at T=.15 and .999992 at T=.05. Much of
that loss comes from score concentration, rather than wrong ranking.

Lexical paraphrases match a name but do not distinguish the two events involving that name.
Every true destination is tied first–second. Their exact zero-temperature contrast limit is
1/6, so sharpening cannot produce full recovery. MiniLM gives every paraphrase a unique
correct best target; source-cost margins over the nearest competitor are .047687–.072712.
Its zero-temperature limit reaches the known organization, but that limit is not a calibrated
probability estimate.

For transcription, MiniLM's true-target source ranks are [2,1,2,1]. Both truncated-name
clauses at positions 0 and 2 have a wrong best target; their cost margins are negative
(-.042351 and -.069870). The zero-temperature reversal contrast is zero for both channels.
Sharper weights would eventually make these MiniLM errors more certain and erase its small
finite-temperature contrast. This is a discriminating counterexample to fixing recovery by
sharpening every row.

## Next improvement tranche

1. **Evaluate the registered semantic channel on a broader, separately admitted content
   challenge set.** Hold out entity names and predicate families, include near/far distractors,
   roles, negation, ties and confident wrong matches, and keep source order out of scoring.
   Compare lexical, semantic and any proposed combination on identification before organization.
2. **Fit any calibration on separate development observations.** Use proper scoring and
   explicit external/null outcomes, freeze parameters, then evaluate organization on untouched
   cases. Calibration may improve the meaning of uncertainty without eliminating conditional
   shrinkage. T=.05 is a sensitivity probe, not the proposed new default.
3. **Target name corruption with evidence that discriminates competing entities.** Test
   character-level or contextual entity evidence on unseen corruptions and plausible name
   collisions. Preserve ambiguous outcomes when the text cannot distinguish them. Do not
   hard-code the current four clauses or infer destination from narrative order.
4. **Report coverage separately.** Keep the full inventory, measurable-adjacency truth in
   synthetic diagnostics, and unresolved counts. Recovering missing full-path counts requires
   additional assumptions or observations; dividing by coverage is not a general repair.

The first implementation candidate should improve the content comparison and its public
registered execution path. The count engine should retain its present semantics.

## Reproduction and qualification

Frozen diagnostic plan: `f0629a4b79a5388554dc873b31bf932367dc0233`, before model comparison.
Producer: `2e45ebaf3b0415dd98541346a5c2f66b381ac4b2`.
Corrected checker: `a33888f8e9198c49e6108c6f24ab292ddfe67433`.
The pinned MiniLM model and tokenizer checksums were verified before native execution and
again by the adapter. No participant or sealed observations were used.

`diagnostic-artifacts.tar.gz` contains 96 numerical outputs and their completion manifest;
input-text directories are excluded. `artifact-receipt.json` binds the plan, archive and
`diagnosis-report.json`. Extract the original recovery archive and this diagnostic archive
into separate directories, then run from a checkout containing these commits:

```sh
python3 tools/recall-study/organization_diagnosis.py \
  --manifest tools/recall-study/fixtures/organization-recovery/manifest.json \
  --baseline-results /absolute/original-results \
  --plan docs/refactor/evidence/organization-diagnosis-20261013/PLAN.json \
  --results /absolute/diagnostic-results --output /absolute/diagnosis-report.json
```

The prescribed scope is pipeline only: one new test witness and Python diagnostic tools,
with no changed library/API or sibling-consumed code. At a33888f8, fatal-warning pipeline
tests passed 340, failed 0, skipped 0; format checks passed. Python tools passed 201,
failed 0, skipped 0. Eleven new diagnosis tests cover the closed-form oracle, missingness
decomposition, finite Bayes controls, the ignore-observations mutant, cost readback and rank
ties. All qualification is locally observed; no hosted CI or publication is claimed.

The first compilation attempt lacked sandbox access to the sbt boot lock; the next passed.
The first comparison launch used relative paths incompatible with sbt's fork directory and
stopped before inference; the absolute-path launch produced all 96 outputs. These failed
attempts are retained. The full pipeline gate log contains fixture prose and remains private
under `.agent-work/organization-diagnosis-20261013/`; the committed receipt binds its exact
raw bytes and publishes only content-free status lines.
