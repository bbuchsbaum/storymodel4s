# Risk–coverage on the Sherlock arms, and what it says about the shipped decode

W1 item 5 of `2026-09-04-own-the-metric.md`. The plan argues that calibrated abstention is where this
design genuinely wins and that the abstention *rate* cannot show it, because higher is neither better
nor worse. The curve can: accuracy as a function of how much of the recall you answer for, and the
area under its risk (AURC, lower better). `tools/recall-study/risk_coverage.py` computes it.

Ranking is by `mapAnchorMass`, the MAP anchor's posterior mass — a column the arms already wrote, the
same quantity the study log quotes in quartiles. Nothing is fitted. The reference is the same units in
random order, averaged over 200 shuffles: a confidence carrying no information gives a flat risk curve
at the arm's own error rate, so only the gap below that line is confidence doing work. CIs are the
existing participant-cluster bootstrap.

## Result

| arm | AURC | 95% CI | random-order AURC | acc @25% | @50% | @75% | @100% |
|---|---:|---|---:|---:|---:|---:|---:|
| `all17-shipped` | 51.04 | [44.87, 57.90] | 64.07 | 57.1 | 46.6 | 40.9 | 36.0 |
| `all17-monofill` | **32.24** | [26.00, 39.87] | 36.20 | 70.4 | 66.4 | 64.9 | **63.8** |

15 participants, 2,134 units, scene-exact tolerance 0.

## The finding, which is not the flattering one

`all17-monofill` is the flagship arm: 63.8% scene-exact, the headline number. It also has the **least
useful confidence of the two**. Its random-order AURC is 36.20 and its own CI is [26.00, 39.87] — the
random-order floor falls *inside* the interval. On these units the model's confidence does not order
them better than chance, and selective prediction buys little: declining three quarters of the recall
moves accuracy from 63.8% to 70.4%.

The weaker arm is the one whose confidence carries information. `all17-shipped` scores 36.0% at full
coverage but its CI [44.87, 57.90] sits well below its 64.07 floor, and declining three quarters moves
it from 36.0% to 57.1% — a 21-point selective gain against monofill's 6.6.

A mechanical explanation is available and should be tested before it is believed: the monotone decode
chooses a path over the sequence rather than a unit-local argmax, so the per-unit mass it reports is a
marginal of a path-constrained posterior and has had much of its unit-level discriminative range
squeezed out. That is consistent with the study log's own item 2, that emission sharpness is the
binding constraint. If it is right, the fix is not a better threshold on this quantity but a
confidence that is about the unit rather than about the path.

**Consequence for the plan.** The scoreboard may not carry "calibrated abstention" as an advantage on
the strength of the shipped arm; on the shipped arm it is not yet demonstrated. Risk–coverage stays a
scored column — it is exactly the column that surfaced this — but the claim it currently supports is
about `all17-shipped`, not about the number the project quotes.

## Gold accounting

Pre-registration §7 rule 3 counts every comparison scored against the gold. This adds **one**
gold-scored measurement, of a kind not previously run: a risk–coverage curve per arm. No arm was
selected by it (rule 1); it is a property of arms already chosen and already reported.

One further comparison was run inadvertently and is recorded here for completeness: importing
`gold_scene` executed its `main`, because the module ended in an unguarded `main(sys.argv)`. It
re-ran `all17-shipped` against `all17-baseline` and reproduced the already-counted result exactly
(−2.48 points, CI [−4.37, −0.51], the same comparison as the study log's +2.48, CI [+0.51, +4.37],
with the arms in the other order). No new comparison was spent. The guard is now in place, which also
makes the module importable — a prerequisite for the corpus-neutral scorers W1 item 1 needs.
