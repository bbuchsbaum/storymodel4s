# Synthetic content challenge: fitted confidence and name repair

The broader challenge supports development-only temperature fitting as an improvement to
**overall held-out proper scores on this constructed mixture**, but does not support automatic
name rewriting or a new production default. The repair arm helps some truncated names and harms
genuinely new names. Better aggregate NLL also does not imply better organization-count recovery.
The existing count engine and production inference/defaults are unchanged.

## Frozen experiment

- Baseline: `8860d064d2e9b9d5aa779d11c6c7caab7c4a3e36` (previous adverse diagnosis retained).
- Corpus and protocol freeze: `0389f93c4a4f47e8457edc2c3e6093cb71383587`.
- Native producer, checker and tests: `7a58aab66e3d793c5ad582aac48c896c51415ef4`.
- Development fit committed **before held-out model scoring**:
  `c9cd7fe278661488c5e9b914ba45f3a24793466b`.
- Corpus SHA256: `7bf4279a0678f14cf5c8ea21ef19df066c4d017a6c5cc2fe0294894d2c742e61`.
- Plan SHA256: `274a3c8235e75fe98de0ce989affacdb12a001d3bf1e4f5278371d6cd82c6dab`.
- Fit SHA256: `64d1f8feefd53e5ea6ca4b7ea0c2deed7c8bba80c009b08db49bf47b10c23bf5`.

Eight original machine-authored four-target sets cross two names and two predicates. Four sets
are development and four are held out. Names, predicate families and recall bytes are disjoint
between splits; sentence templates remain shared. Seven conditions cover exact clauses,
paraphrases, final-letter deletion, typos, ambiguous name prefixes, unrelated external events,
and declared novel names one edit from a known name. Development typos substitute a letter;
held-out typos transpose internal letters. The independent reviewer admitted the exact corpus
before it was committed; its own header records provenance, transformations and signoff.

There are 224 input rows, including 32 ambiguous rows. Per arm, each split has 96 point-labelled
rows and 16 set-labelled ambiguous rows. **Four source sets per split are the principal units of
variation**, not hundreds of independent participants or narratives. Weather controls repeat
within a split against different sources but never across splits. This is a performance holdout
whose authors knew its construction, not a blinded or independent natural-language corpus.
No participant or sealed evaluation text was opened.

Three arms use existing local lexical and pinned MiniLM scoring, plus a diagnostic preprocessed
MiniLM arm. The latter derives a first-token name vocabulary from the source, preserves exact
names, and repairs only a unique eligible spelling/prefix match under the fixed PLAN rule.
It never sees labels, source order or split membership. Actual native unit text and order are
checked against processed clauses. Original/processed input and clause hashes, native unit IDs,
edit records and normalization reasons remain in every completed artifact. Local-only input
preparation remains local-only; no disclosure whitelist or public workspace authority changed.

Five substantive external states are pooled into one scored External class; Unranked denotes
unavailability. Faithful/distorted states are collapsed only after state normalization, retaining
multiplicity. Collision labels are admissible sets and are excluded from ordinary NLL fitting.
Novel-name labels represent a declared new fictional identity; spelling alone does not identify
whether such a token is a new person or a transcription error. They are adversarial generative
controls, not a claim that every label is recoverable from the visible sentence.

## Results

All **84 development** and **140 held-out** native conditions completed, with zero failures.
The held-out inventory is smaller than 168 because lexical's fitted and stock temperatures are
identical; its single native result supports both labelled summaries. No case was dropped.
Development minimization over the frozen grid selected lexical **0.15**, MiniLM **0.5**, and
repaired MiniLM **0.5**. The independent reviewer reproduced all 27 development objectives using
50-digit Decimal arithmetic, with maximum difference **3.56e-15**.

Held-out point labels, 96 rows per arm/mode; lower NLL and Brier are better. Accuracy counts a
unique highest coarse destination; ties are retained separately. Brier is the sum across the
four source classes plus External, averaged over rows.

| Arm | Temperature | NLL | Brier | Unique correct /96 | True label tied best |
|---|---:|---:|---:|---:|---:|
| Lexical stock and fitted | 0.15 | 1.078437 | 0.533230 | 34 | 44 |
| MiniLM stock | 0.15 | 2.203718 | 0.804809 | 50 | 0 |
| MiniLM fitted | 0.5 | 1.471852 | 0.740678 | 64 | 0 |
| Repaired MiniLM stock | 0.15 | 2.275440 | 0.788027 | 54 | 0 |
| Repaired MiniLM fitted | 0.5 | 1.473920 | 0.740951 | 70 | 0 |

Fitting improves MiniLM's aggregate proper scores, substantially by reducing its excessive
confidence on external controls. It softens source confidence at the same time. Pooling five
external states means temperature can change the highest *coarse* class despite preserving
individual-state ordering. This is not evidence that fitting learned external subtypes.
Lexical has the best aggregate proper scores on this particular mixture, while MiniLM is much
better on paraphrase identification. The aggregate alone is an inadequate channel-selection rule.

Unique correct by condition (16 point rows each):

| Condition | Lexical .15 | MiniLM .15 | MiniLM .5 | Repaired .15 | Repaired .5 |
|---|---:|---:|---:|---:|---:|
| Exact | 16 | 16 | 16 | 16 | 16 |
| Paraphrase | 0 | 14 | 14 | 14 | 14 |
| Truncation | 2 | 10 | 6 | 14 | 14 |
| Held-out transposition | 0 | 10 | 10 | 10 | 10 |
| Unrelated external | 16 | 0 | 16 | 0 | 16 |
| Novel name | 0 | 0 | 2 | 0 | 0 |

The repair arm changes **14/16 truncated-name rows** and **16/16 novel-name rows**. It changes
zero exact, paraphrase, collision, held-out transposition or unrelated-external rows. At stock
T=.15, truncation improves from 10/16 to 14/16; the four set-level counts change from
`[2,2,2,4]` to `[2,4,4,4]`. All transpositions remain unchanged under ordinary Levenshtein distance,
which treats these swaps as two edits. The frozen rule was not expanded after seeing this.
Novel-name NLL worsens from **5.191036 to 5.932191** at stock temperature and from
**1.616190 to 1.775787** at fitted temperature. Its two correctly external fitted decisions become
incorrect. Thus the higher aggregate unique accuracy of repaired MiniLM coexists with slightly
worse fitted aggregate NLL and Brier. **The evidence rejects promoting this repair rule as an
automatic default.**

Held-out NLL by source set:

| Set | Lexical .15 | MiniLM .15 | MiniLM .5 | Repaired .15 | Repaired .5 |
|---|---:|---:|---:|---:|---:|
| test-a | 1.005879 | 2.204111 | 1.472495 | 2.297319 | 1.480176 |
| test-b | 1.005879 | 2.213823 | 1.477610 | 2.285775 | 1.481339 |
| test-c | 1.016128 | 2.170613 | 1.452888 | 2.182617 | 1.440403 |
| test-d | 1.285864 | 2.226323 | 1.484417 | 2.336051 | 1.493762 |

All collision inputs remain unchanged. Mean admissible-set mass is 0.841520 for lexical .15,
0.563173 for either MiniLM arm at .15, and 0.416284 at .5. Mean largest within-set normalized
mass is respectively 0.500000, 0.536654 and 0.511055. Set coverage and concentration are separate;
these numbers do not validate a probability allocation between indistinguishable names.
Forty held-out unchanged-input arm pairs reproduce the complete result, costs, registration and
certificate exactly.

## Organization consequences

The genuine native output uses the original four-row forward path. Only exact, paraphrase,
truncation and typo conditions have source point truth `[Backward=0, Forward=3, SameTarget=0]`.
There is no collision point truth and no gap bridging. Averaging these 16 set/condition paths,
MiniLM's reported Forward count falls from **1.239069 at .15** to **0.762100 at .5**, despite the
better aggregate NLL. Its Forward discrepancy from three therefore worsens from **-1.760931**
to **-2.237900**. Repaired MiniLM gives 1.287761 and 0.781932. These are deterministic conditional
count discrepancies on the constructed paths, not empirical estimator bias or human recovery.

The independent reader checks every native count vector, original adjacency and available
cost-to-mass row. It verified **336 development + 560 held-out rows**, with maximum held-out
mass disagreement **1.11e-16**. The original count-engine diagnosis remains valid. Confidence
fitting, identity uncertainty, and organization recovery are distinct requirements; temperature
alone does not resolve them.

## Verification and disposition

The committed evidence includes full numerical outcome archives and digest manifests, both
prelaunch receipts, all development grid objectives, all held-out case/row metrics, mutation and
tamper receipts, and command/exit receipts. Inputs are separately held in the admitted manifest;
raw per-run input directories are not in the numerical archive. Failure reasons retain exception
class and message digest rather than prose.

Three compiled Python guard mutants each failed exactly their named assertion while 11 siblings
passed: development-only selection, prelaunch committed-fit verification, and available-row
checking in partial results. A compiled Scala mutant that chose a first match under ambiguity
failed its named refusal test with three siblings passing; exact source bytes were restored.
The initial receipt parser expected the success prefix on this deliberately failed gate; reading
the retained log corrected that parser without rerunning the test. Rehashed tampered native
artifacts are refused for substituted clause correspondence, changed costs and dropped cases;
substituted uncommitted fit bytes are refused before native launch.

Pipeline scope and final gate results are recorded in `validation.json`. Tests run locally in a
clean standalone clone with fatal Scala warnings and formatting last. No production API changed,
so no StoryAtlas consumer module is affected. No hosted CI or remote publication is claimed.

The next substantive design question is how to retain alternative entity interpretations and
price genuinely external content without automatically replacing names. Any adaptive successor
must use development data or a new holdout: this holdout has now been opened. The present tranche
completes the frozen comparison and reusable, guarded evaluation path; it does not claim general
calibration, human efficacy, or qualification of the separate BIDS/Sherlock data journey.

## Reproduction

Use a clean standalone checkout with the pinned local ONNX model/tokenizer and Python 3.
The exact original argv, code revisions, preflight receipts and exit statuses are in
`log-receipts.json` and the accompanying logs. The checked `produce` action refuses dirty trees,
changed assets, uncommitted fits and overwritten output directories. It is the required launcher.

To independently recheck the saved campaign, unpack `numerical-artifacts.tar.gz` outside the
checkout, then run from the repository root (choose new output paths):

```sh
python3 tools/recall-study/organization_challenge.py fit \
  --results /absolute/path/development --output /absolute/path/refit.json
python3 tools/recall-study/organization_challenge.py evaluate \
  --results /absolute/path/heldout \
  --fit docs/refactor/evidence/organization-challenge-20261014/FIT.json \
  --output /absolute/path/rechecked-heldout.json
```

The original protocol/producer/fit commits must remain available: readers verify their Git
ancestry and exact committed input bytes. Rechecking numerical artifacts is distinct from
rerunning native embeddings. For a native rerun, follow the original development launcher at
producer `7a58aab6`, fit and commit its receipt before any held-out launcher, preserving all
outcomes and never selecting from test scores.
