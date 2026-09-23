# Sherlock development reproduction — 23 September 2026

Mote: `bd-01M35JFJCQ2ANTVED792H705X6`.

All 22 local mapper processes succeeded. The annotations-only reports reproduce
the historical outputs byte-for-byte for all 11 development participants. Under
the repaired fixed-support scorer, A achieves **60.95% participant-average
scene-exact accuracy**. Historical caption augmentation gives 61.74%, a paired
change of **+0.79 percentage points, 95% CI [-0.43, +2.13]**. The interval spans
zero; this readout does not establish an AV gain or select a new default.

The [protocol](protocol.json) and study-log registration were committed at
`742308c2` before the first gold read. The canonical support digest was committed
separately at `20469746` before predictions were imported. The mapper and scorer
then ran from that clean tracked revision, whose production code is `1395ce2c`.
Exact full SHAs, commands, source/config/input hashes, runtime and artifacts are
bound in [receipt.json](receipt.json), [support registration](support-registration.json)
and [command-receipts.tar.gz](command-receipts.tar.gz).

The frozen mapper settings were reused for all 11 admitted development
participants. Historical captions have matching request, outcome and frame-byte
provenance, but predate the receipt-checked timed-source loader. AV uses those
captions as embedded scene text. No VLM was rerun, and frame-to-video temporal
correspondence was not independently verified here.

| Measure | A: annotations | AV: annotations + legacy captions |
| --- | ---: | ---: |
| Participant-average scene-exact accuracy | 60.95% | 61.74% |
| Participant-average within-one-scene accuracy | 79.41% | 80.71% |
| Secondary pooled scene-exact accuracy | 65.24% | 65.78% |
| Input participants / units | 11 / 1,744 | 11 / 1,744 |
| Gold-eligible participants / units | 10 / 1,499 | 10 / 1,499 |

The paired within-one-scene change is +1.29 points, CI [-0.67, +3.05]. Intervals
use the registered paired participant cluster bootstrap: seed 20260902, 2,000
draws. They describe this development comparison; they do not establish
generalization to other films or calibrated per-unit mapping probabilities.

| Participant | Input units | Gold-eligible units | A exact | AV exact |
| --- | ---: | ---: | ---: | ---: |
| NN03 | 173 | 156 | 58.97% | 57.69% |
| NN04 | 96 | 86 | 54.65% | 52.33% |
| NN05 | 68 | 0 | unavailable: no gold source | unavailable: no gold source |
| NN06 | 104 | 100 | 52.00% | 52.00% |
| NN07 | 98 | 90 | 43.33% | 44.44% |
| NN08 | 181 | 154 | 62.99% | 66.23% |
| NN11 | 148 | 124 | 50.81% | 51.61% |
| NN13 | 338 | 303 | 82.51% | 83.50% |
| NN15 | 97 | 80 | 73.75% | 75.00% |
| NN16 | 89 | 79 | 59.49% | 64.56% |
| NN17 | 352 | 327 | 70.95% | 70.03% |

[A-AV.json](A-AV.json) retains exact per-participant values, all outcome classes,
exclusions, coverage and leave-one-participant-out differences. Every unit has a
label under the frozen monotone/fill preset; this output completeness does not
mean every label has adequate semantic evidence. NN05 remains in the accounting
with an unavailable score, rather than zero or a silently dropped record.

The historical approximately 63.8% was pooled over 15 participants. It is **not
directly comparable** to the primary participant-average development estimand
here. The applicable engineering reproduction succeeds: all 11 new A TSVs have
the same hashes as their historical `all17-monofill` counterparts, and the
development pooled value is the previously recorded 65.24%. No other historical
participant report was opened.

## Verification and limits

The [independent check](independent-check.json) imports no production scorer. It
recomputes all 1,744 gold-support rows from raw CSV intervals with Decimal
arithmetic, then checks integer correctness counts and exact rational accuracy
ratios against the reported floating-point values. Tolerance is 1e-10 percentage
points, accommodating rounding far below one observation's contribution. A
compared with itself gives zero differences and zero-width difference intervals.

Three [injected report faults](oracle-faults.json) are rejected: substituting the
pooled rate for the primary mean, reporting absent gold as zero, and omitting a
participant. These are data-corruption witnesses, not mutations of the mapper.
The existing scorer's 19 synthetic tests also passed; its receipt explicitly
states that this invocation's raw test log was not retained. Scoped JVM compile,
all 22 mapper commands, both arm imports, both comparisons and the independent
check exited zero. This is not a new full-platform release gate.

Only one new development contrast was evaluated, AV minus A. The independent
oracle and fault probes re-read the same registered gold to validate that
readout; they introduce no additional hypotheses, populations or tuning. Friends
and Memento inputs and gold were never opened. No untouched Sherlock transcript
or prediction was opened, and no remote inference or upload occurred.

The benchmark preserves its historical recall clock and reconstruction policy.
It establishes neither OpenNeuro scanner correspondence nor fine-time accuracy,
behavioral recovery, reference-measurement compatibility or calibrated
uncertainty. Public-facade reproduction remains the separately tracked follow-up.

`run_study.py` checks all input pins, requires committed tracked files, refuses
existing outputs, clears ambient mapper settings and records every process
attempt. Raw reports and logs remain beneath the ignored data root. The
observation manifest is reused by its admitted byte digest; no other raw recall
or saved prediction is opened.

Execution order:

1. Commit the protocol, runner and study-log registration.
2. Run `run_study.py freeze --data-root DATA_ROOT --out NEW_STUDY_DIRECTORY`.
3. Commit `support-registration.json` with the protocol, gold-file and canonical
   support hashes before importing predictions.
4. Compile the pinned producer and retain its runtime classpath/build receipt.
5. Run `run_study.py map --data-root DATA_ROOT --out STUDY_DIRECTORY --classpath
   CLASSPATH_FILE`; A and AV receive separate Java processes for every participant.
6. Run `run_study.py score --data-root DATA_ROOT --out STUDY_DIRECTORY` and an
   independent integer-count/Decimal-interval readout check.
7. Retain content-free results, exact source/config/input/output digests, all
   participant outcomes and the historical-parity assessment here; request a
   separate review of the scientific wording before closing the issue.

The repaired scene scorer is `tools/recall-study/gold_scene.py`;
`tools/recall-study/score.py` serves different gold-free diagnostics. No scorer or
production mapper algorithm is changed by this reproduction.
