# Synthetic worked example

## Admission header

**Source:** Original machine-authored project diagnostic, Toronto, Canada, 2026, edition
detail-coding-synthetic/v2.
**Basis:** Original project contribution under Apache-2.0; no external, participant, or traditional content.
**Modifications:** Corrected S1 on-to-by and span offsets from draft v1.
**Human-subject provenance:** No participant material; not a transcript.
**Admitted by:** Independent parent reviewer /root, 2026-11-16, exact v2 text and UTF-16 spans checked.

This approves bytes only, not empirical labels or a human pilot.

| ID | Source observation |
|---|---|
| S1 | A courier places a blue parcel by a bench. |
| S2 | A guard watches from the doorway. |

R1 UTF-16 [0,65): The courier left a blue parcel by the bench, and someone watched.

This admitted example demonstrates only span notation: courier phrase [4,43), blue [19,23),
someone watched [49,64), watched [57,64), someone [49,56). The labels below are illustrative policy inputs, not human results.

## One account, two analyses

The illustrative account retains three distinct claims; D1 and D2 intentionally share words.
Source S2 supports the watching action while the recalled participant remains unidentified.

| Atom | Recall unit/span | Kind | Address | Reference | Illustrative fidelity |
|---|---|---|---|---|---|
| D1 | R1 [4,43) | event occurrence | TargetSpecific | single S1 | supported |
| D2 | R1 [19,23) | attribute | TargetSpecific | single S1 | supported |
| D3 | R1 [49,64) | event occurrence | TargetSpecific | single S2 | supported action; actor unresolved |

Under **target-episode-novel-content/v1**, all three are countable internal details: internal 3,
external 0, unresolved 0, excluded 0. Under the illustrative **target-event-only/v1** analysis,
D1 and D3 are countable and D2 is excluded by the declared kind filter: internal 2, external 0,
unresolved 0, excluded 1. This second policy is an example of a different question, not the
default. Both reports retain the unchanged account's atom IDs, spans, addresses and evidence.
Neither reruns extraction or mapping. The fraction internal/(internal+external) is 3/3 and 2/2
respectively; counts and exclusions must accompany it.

Changing only D1's fidelity to contradicted, or its assessment to pending/not-assessed, leaves
these address classifications unchanged. Under DL4's initial policy, definite supported bindings
credit rows S1 and S2; broad-only or alternative-only versions would remain uncertain. These are
illustrative contrast cases, not additional observations or human agreement estimates.
