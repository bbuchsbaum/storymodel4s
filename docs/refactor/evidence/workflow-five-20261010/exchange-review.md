# Independent paired-exchange source review

- Reviewer: `/root/reconstruction_review`
- Candidate: `efba130cfc867c566ea83ca19ebded0d4914c240`
- Base: `07855d4dccf593b53432fe0f5c60b94fcfd1a542`
- Bead: `bd-01M2WVHF4B5DAXJYY4W91VK4WV`
- Verdict: **SOURCE PASS**, conditional on final gate and receipt qualification.

## Finding and repair

Candidate `40b95502` had an outcome-accounting BLOCK: the Python reader checked
batch unit IDs but accepted summary statuses inconsistent with member decision
tables. Failed attempts bypassed unit-denominator validation. The reviewer
reproduced acceptance of a changed Complete summary against actual emitted
fixture bytes while both member tables still reported complete.

The reviewed successor fixes this: completed summaries match both member
decision tables; failed attempts retain the prepared-unit denominator with both
statuses Failed, or no units when preparation failed. Failed attempts require
null registration and not-executed readiness. Producer labels are stable
Complete/Excluded/Failed rather than parameterized enum strings.

## Independent evidence

Before inspecting exchange tests or outputs, the reviewer derived the lexical
one-unit oracle from underlying segmentation, scoring and admission rules:
source spans [0,13) and [14,23); first-source cost 0; second-source and five
external-state costs 1. For q = exp(-1/temperature), masses are 1/(1+6q) and
q/(1+6q). Reference temperature is 1; Sherlock reconstruction temperature is
0.15. The one-unit reconstruction has no transition factors.

The reviewer inspected the repair tests and both reader-mutation logs, and
reconstructed each mutant from exact candidate source. Both mutant hashes match
results.json. Each log records the intended missing-refusal assertion failure,
one named passing control and zero errors.

Source review also inspected execution-owned pair construction, full canonical
contextual comparison, publication/resume integrity, exact-tick handling and
the reader's stated qualification boundary. No remaining source BLOCK or
FOLLOW-UP was identified.

## Qualification limits

This receipt approves source correctness within the reviewed scope. Final
source checkAll, independent-reader execution and pinned StoryAtlas gate
receipts require separate final verification. No build was run by the reviewer.
The Python reader checks package consistency; it does not authenticate
self-described execution authority. The synthetic oracle establishes an
engineering contract, not corpus efficacy or calibrated correctness.
