# Film Festival stabilization and two development comparisons

Written before scoring either new comparison. The owner authorized steps 1–5 on 2026-09-04.
Baseline code is remote `main` at `680ef6e652632fdd22f3410fb3b5e7caa7c80809`; recovery is
`739acf79` on `recovery/filmfestival-20260904`. The isolated integration branch is
`solo/filmfestival-stabilize`. The shared planning branch and its unrelated edits stay intact.

## Fixed analysis contract

All available participants were previously inspected. These are development analyses, with no
untouched confirmation claim and no arm selection or prior tuning. Two planned experimental comparisons (historical rescoring and integration audits are separate):

1. JL full source versus JL excluding both cartoon candidate blocks. Recall units, gold eligibility,
   encoder and inference settings stay fixed. Cartoon-labelled recall remains eligible and can be
   scored wrong. Report paired correct-to-wrong and wrong-to-correct transitions and coverage.
2. JL versus independently collected crowd descriptions on gold movie codes **1,3,4,5,6,10**.
   This comparison measures **film identity only**. Each source candidate has its film's complete
   annotation interval, without scene groups. No crowd-window timestamp is asserted. JL retains
   its description rows; crowd uses one token-Jaccard medoid per released window, selected from all
   test-phase responses, with SHA-256 tie-breaking. This changes source wording and candidate
   density together; it does not isolate either causal effect. Film title features are shared.
   The six cleaned files contain 7,099 rows. Counts and input hashes are emitted by the preparer.

**Known source mismatch, declared before outcomes:** crowd `cmiyc_long` is a longer cut of Catch Me
If You Can. Its published timing map extends to 704 s, past that scanner block's end at 391 s.
Consequently the six-film result is diagnostic, not an independence-admission court. A prespecified
sensitivity restricts gold to **3,4,5,6,10** (the other five films), retaining all six source
candidates in both arms so eligibility alone changes. It does not establish exact video equivalence
for those five films. No success of either analysis promotes any source or closes B0.

Primary outcome: correctly placed film / all eligible recall units, including unanchored units in
the denominator. Secondary: anchor coverage, correctness conditional on anchoring, participant
mean and median, confusion counts. Pair by participant and unit ID, verifying recall text/timing
hashes and gold labels. First recall run only; eligibility from maximum temporal overlap with one
gold utterance. A zero-width recall unit uses point containment. Off-task, missing participant gold,
no overlapping gold and out-of-subset units receive separate counts. Word timings are interpolated.

95% percentile intervals resample whole participants, 4,000 draws, seed 20260904, retaining their
unit counts; primary pooled ratio therefore remains unit-weighted. Paired deltas resample the same
participants in both arms. Intervals are descriptive development uncertainty, without a claim of
confirmatory significance or generalization to new films. Report every sign and do not select an
arm from these scores. Duration-weighted chance sums both cartoon intervals and is restricted to
the declared gold film set. The in-sample majority-gold comparator is labelled as such. Neither is
a human ceiling; independent human reliability is still outstanding.

Gold-free agreement is supporting only: a constant-anchor predictor achieves a 0-second gap.
Keep that control visible. Its historical pair-resampled intervals are not participant-level
uncertainty. Missing corpus offsets, nonfinite times and changed recall populations fail visibly.
The Film Festival CLI requests ONNX by default and refuses missing artifacts. Lexical runs require
an explicit argument and absent ONNX variables. Existing Sherlock defaults are preserved.

The runner fixes the historical defaults explicitly: ONNX MiniLM, lexical blend 0.8, eight
candidates per level, lexical nomination off, prior scale 1, monotone decode and fill on, hard
backward penalty, zero forward penalty. The historical reports have no backward scene steps in
the inspected participants; no new prior setting is selected. The film-identity indices have no
scene groups, so scene decoding has no scene assignment to constrain there.

Film Festival supplies `WorldOrderInput.Unknown(NotSupplied)`: independent films share no declared
story-world clock. Annotation axes remain placeholders. This integration does not repair or use
source-empty fidelity facets; those remain outside the scoreboard.

Input preflight found 17 cleaned responses whose bounds disagree with their published window
(seven cmiyc, four Keith Reynolds, five Boyfriend, one Rock). Exclude those responses, count them
in the input receipt, and select each medoid from the remaining matching responses. This rule was
recorded before any new outcome was scored; it does not infer repaired timings.

## Reproduction

Use `STORYMODEL4S_DATA` pointing to the primary checkout's ignored `data` directory when building in
a standalone clone. `tools/corpus/filmfest_experiment.py DATA NEW_OUTPUT` creates the four source
indices and input receipt without reading gold. `tools/recall-study/run-filmfestival.py DATA
INPUT_DIRECTORY NEW_OUTPUT` runs four fixed ONNX arms, all twenty first-run recalls, with command,
configuration, input and output receipts. Never overwrite the original `jl-onnx-dev` or
`jl-lexical-dev` directories. Score with `filmfest_gold_film.py`; `--films` fixes gold eligibility and
`--json-out` saves aggregate results without participant prose. Exact executed commands, evidence,
limitations and results will be recorded after the run.

## Acceptance

- Narrow commits on a persistent branch; current-main integration; green `sbt checkAll` with bound
  command/exit receipts and test totals, plus a clean touched-path tree before landing.
- Regression controls for duration accumulation, anchor missingness, half-open film boundaries,
  cluster resampling, stable pair identity, missing offsets and the degenerate agreement predictor.
- Mutation witnesses and a separate fresh-context cold read (AGENTS.md SD1 and SD6).
- Preserve prior reports, publish corrected baseline and both comparisons, regardless of sign.

## Recorded corrections and scope of evidence

The initial prose named the earlier remote tip `5d17f169`; the actual fetch had already advanced
to `680ef6e6`. The run receipts and Git parent of `b70a9f76` establish that integration base.
This correction was recorded in a local protocol addendum before scoring the new comparisons.

Cold reads found and fixed full-population verification (including excluded units), relative-path
receipt ambiguity, and literal-TSV quoting. The last issue occurred in the real gold: default CSV
parsing returned 3,181 rows rather than 3,226. Full literal reading restores all rows. The prepared
full JL index is byte-identical to the historical TSV. Report and gold readers use that same dialect.
The engine ran all four arms at `7d81e4aa`; scorer repairs were staged outside its immutable checkout
and integrated after the runs. Aggregate scores carry the exact scorer SHA-256.

The historical-to-integrated comparison is an engineering audit added during execution, not a
predeclared scientific comparison: accuracy moved by -0.32 percentage points under the integrated
code and explicit world-order absence. No cause is isolated by that audit and no arm was selected
from it. Final experimental comparisons use the freshly run integrated baseline.

Results and conclusions: `2026-09-04-filmfestival-stabilization-results.md`.
