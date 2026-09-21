# Memento nonlinear-narrative recall source set

This directory records the local row-task admission for the behavioural release accompanying
Antony, Lozano, Dhoat, Chen & Bennion (2024). The analysis population is **123 participants**,
with **27/33/30/33** in conditions 1–4. The workbook has 133 recall sheets; these are not 133
admitted participants. The extra condition-2 participant relative to the article remains an
explicit discrepancy in `recall-population.json`.

The owner authorized admission on 2026-09-18 and selected **63 test / 60 development** on
2026-09-21: test **14/17/15/17**, development **13/16/15/16** across conditions 1–4.
`task-definition.json` freezes the task before model outputs. Raw recall stays local and ignored.

## Why this corpus was scouted at all

`docs/plans/2026-09-03-second-corpus-scouting.md` recommends Film Festival as the second corpus and
does not mention Memento. A later systematic sweep of all public OpenNeuro datasets
(`docs/plans/2026-09-03-second-dataset-survey.md`) found this release through a dead link in the
README of `ds008464`. It is the only corpus found anywhere that carries **presentation order and
story order as separate, explicit data** over a film built to make the two diverge.

The specific value is a within-dataset control. Condition 1 watched the original nonlinear cut and
recalled freely; **condition 4 watched a restitched linear cut of the same length** and recalled
freely. Content, runtime and instruction are fixed; only whether presentation order carries the
chronology varies. Sherlock cannot separate those two hypotheses because in Sherlock they coincide.
Conditions 2 and 3 additionally move recall order by instruction with the nonlinear stimulus held
fixed. `docs/plans/2026-09-03-memento-integration.md` sets out what that buys and what it costs.

## Scope of admission

Local task parsing, scoring and a sealed participant split are admitted. No model comparison,
accepted narrative semantics, or permission to redistribute recall prose follows from this.
`docs/design/story-text-admission-checklist.md` §3 still governs any future Git admission of
participant text. The commercial stimulus is not stored or redistributed.

## Licence, and the disclaimer that goes with it

The owner decided on 2026-09-04 that these are open-science releases and that local research use
proceeds without waiting on an explicit licence grant. Conditions: no upstream bytes are
redistributed; every release and its article is cited wherever results are reported; and any
publication or artifact derived from a source whose upstream carries no LICENSE file carries this
disclaimer:

> Derived from openly released research data. Where the upstream release carries no explicit
> licence, it is used here for non-commercial academic research under an open-science reading, with
> attribution to the original authors. No source bytes are redistributed. The original authors have
> not reviewed or endorsed this use.

This decision covers **licence only**. Human-subject provenance is a separate question, governed by
§3 of `docs/design/story-text-admission-checklist.md`, and is unaffected by it.


## Records

| Record | Contract |
|---|---|
| `source-manifest.json` | Upstream repository, commit and article identity; per-artifact upstream blob, byte length and SHA-256 for all ten staged files; content policy; the article's `RecallType`, `Detail`, causality and importance code books; non-claims |
| `recall-population.json` | Reconciliation of 134 index rows against 133 sheets and the article's reported n, the identifiable-event exclusion rule, a stated partition rule, and the one residual disagreement |
| `task-definition.json` | Physical-row identity, eligibility, estimand, pinned inferred header overlays and split recipe |
| `task-audit.json` | Pre-seal condition totals, explicit anomaly coordinates and header-overlay sensitivity; no recall text |
| `test-split.json` | Seed, membership, bound task and reader digests, aggregate accounting and planning power |
| `test-split-reads.json` | Separate final-opening and count-only read attempts |

File-identity pins use SHA-256 of exact bytes. The audit object digest uses the canonical JSON
routine in `memento_task.digest` (UTF-8, sorted keys, compact separators, no NaN).

## Verification performed

Every staged file was re-hashed with `git hash-object` and compared to the upstream tree blob at
commit `bb8f1b85`. **All ten match.** The two analysis notebooks in the upstream tree are
deliberately not staged; they are code, not source data.

The `RecallType` code book was additionally checked against the data's own behaviour before the
article was consulted, and the two agree. Scene-identifiability falls monotonically across the
codes — 99.0%, 30.4%, 19.9%, 1.6%, 1.0% of rows carrying a `BroadSceneNum` for codes 1 to 5 — and
code 3, which the article defines as *inaccurate* detail, carries the highest `FalseMemory` rate
(2.4%). Mean transcript length also falls monotonically (100, 81, 62, 52, 38 characters). These aggregate
patterns are consistent with the recorded code book; they do not independently establish
annotation accuracy against the original audio.

## Scientific use, and its limits

- **`RecallType` is the accuracy filter, and its code book is now recorded.** Strict veridical is
  code 1 (11,762 rows). A gist-inclusive variant is codes 1+2 (16,726 rows; both are accurate by
  the article's definition). Codes 3, 4 and 5 must never enter an accuracy measure — 3 is
  *inaccurate* content, 4 is commentary, 5 is not recall. These are historical all-sheet counts
  before header overlays. Ten invalid code cells are explicitly excluded: five malformed text
  values, two Excel date serials, and three out-of-range integers; none is coerced.
- **Code 3 is a confabulation signal**, distinct from the 150 rows flagged in the `FalseMemory`
  column. Neither has an analogue in Sherlock or Film Festival.
- **The causal graph is not gold.** Seven raters, leave-one-out interrater reliability r = .10 to
  .70 (mean .48, median .52). The article averages the matrices; it does not threshold them. Our own
  count shows raters marking 45 to 1,891 of the 1,892 off-diagonal cells, so a threshold chosen
  without recording it would be an undeclared analytic decision.
- **Importance carries one value per scene** and predicts memorability strongly in the article
  (r = .75). Splitting the directed causal graph, *inbound* weight predicts memorability (r = .48)
  and *outbound* does not (r = .12, ns) — a directional claim our own error profile can be tested
  against.
- **The population must come from `recall-population.json`**, not from sheet order. Three sheets are
  absent from the index and four index rows have no sheet.
- Semantic values in the article come from Google's Universal Sentence Encoder over a cleaned
  screenplay. Our pipeline uses a different encoder, so semantic numbers are not comparable across
  the two; the causal and importance ratings are.

## Loader hazards

Three, all measured on the staged bytes:

- **12 distinct header layouts** across the 133 recall sheets. The common one (114 sheets) is
  `SecondsInMinuteTime, SecondsOfRecall, RecallType, BroadSceneNum, SubsceneNum, Detail, FalseMemory,
  FalseMemExp, Transcript`. Variants add `BroadSceneNum2`/`SubsceneNum2` for a recall unit spanning
  two scenes, append code-book legend columns, or drop `RecallType` or `Transcript` entirely. A
  loader keys on names, except the exact workbook/header/cell-bound overlays below.
- **`SecondsInMinuteTime` mixes three encodings** — `00:00:05`, `0:00:00`, and Excel datetimes
  serialized as `1900-01-01 00:00:05` — plus blanks. `SecondsOfRecall` is the safer clock, while
  itself occasionally being a time string rather than a number.
- **`RecallType` has ten invalid cells**: two Excel date serials, three out-of-range integers,
  and five malformed text values. Exclude explicitly; do not coerce.

## Frozen task and access

One substantive **physical Excel row** is one observation. Identity binds workbook SHA-256,
participant sheet and physical row number; blank row gaps and repeated text are preserved.
Eligible rows have a nonblank transcript, code 1 or 2, and valid scene annotations in 1–44.
Both populated scene fields must validate. An integer prediction matching either annotation
is correct once. This measures any-annotated-scene recovery, not recovery of all multi-scene
content. Missing scene gold (including code-2 gist rows) remains in accounting outside accuracy.
Missing or invalid time does not change eligibility; no row-index clock is invented.

The primary statistic averages eligible-row accuracy equally across participants, separately
by condition. A retained participant with no eligible rows makes full-population accuracy
undefined. Prediction input is a sequence of unit-ID/outcome pairs: duplicates, omissions and
extra IDs refuse scoring; invalid labels and nonlabels are wrong on eligible rows.

S42's unlabeled code column and S53/S72/S162/S175 transcript headings have **inferred** overlays
bound to exact bytes and full header signatures. S53's truncated clock heading is also normalized.
S72 has an empty canonical transcript column and a populated `Transcript ` column with a trailing
space; the overlay checks that the unused column stays empty. These are not author-confirmed corrections. Interpretation of the qualification in S53's long
transcript header remains unresolved. The pre-seal audit reports counts with overlays disabled
and each affected sheet's contribution, so this assumption remains visible. S84's noncanonical
clock heading is left unresolved because time is optional for this task.

All recall readers use `memento_guard`. Development text reads are permitted; test text reads
require an explicit committed release manifest and matching digest. Every final authorization
is logged; the capability is not one-use. After sealing, population surveys and admission-audit
forms refuse access, even if the committed split is deleted locally. Whole-corpus accounting
returns aggregates and logs its attempt before reading; commit the ledger before the next read.
The reader scan is a cooperative tripwire, not an operating-system security boundary.

Reproduce the committed seal without reading recall:

```sh
python3 tools/recall-study/memento_split.py --check --data-root /nonexistent
```

Planning power uses participant-level paired differences, not row counts, and reports the four
conditions separately. Its hypothetical SD grid is not an observed variance estimate; four
marginal calculations establish neither interaction nor familywise power nor film generalization.
The split is model-untouched, not unseen: structural and aggregate annotation audits preceded it.

## Sealed accounting (2026-09-21)

All 123 participants have eligible observations. The audit retains 37,473 source rows,
including 13,125 eligible rows (11,640 code 1; 1,485 code 2). It retains 3,292 scene-less
code-2 rows outside accuracy. Ten invalid-code cells and two invalid-scene rows have explicit
coordinates and reasons. The five inferred sheet overlays contribute 999 eligible rows;
without them 118 participants have eligible observations. No model predictions were inspected.

| Side | Participants | Eligible rows |
|---|---:|---:|
| Development | 60 | 7,017 |
| Test | 63 | 6,108 |

## Primary sources

- Release: [`JamesWardAntony/memento`](https://github.com/JamesWardAntony/memento) at `bb8f1b85`
- Article: [10.1162/jocn_a_02216](https://doi.org/10.1162/jocn_a_02216) (*J. Cogn. Neurosci.*
  36(11):2368–2385; PMC11887591, not open access)
