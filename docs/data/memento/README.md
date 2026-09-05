# Memento nonlinear-narrative recall source set

This directory is the content-free admission record for a **proposed** third corpus: the
behavioural release accompanying Antony, Lozano, Dhoat, Chen & Bennion (2024), in which 133
participants freely recalled the film *Memento* under four experimental conditions.

**Nothing here opens a court.** The state is `proposed`. What this record does is establish
scientific identity — which upstream bytes, which counts, which code books, which population —
so that a decision to admit or decline is made against facts rather than against a description.

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

## What blocks admission

1. **Owner decision.** Whether to open an acquisition court, and in what order relative to Film
   Festival. The scouting brief reserves this.
2. **`docs/design/story-text-admission-checklist.md` §3.** The workbook contains participant recall
   prose: 21,854 rows of transcript text. No such text may be committed until an REB/IRB basis for
   redistribution is recorded by the owner and checked by someone other than the proposer. §1
   separately bars admitting recall transcripts on a public-domain basis, so "it is on GitHub" is
   not a basis. This slice proposes **no** text-bearing artifact for Git admission.
~~3. Licence.~~ **Resolved 2026-09-04** — see below. The upstream repository carries no LICENSE
   file, but the owner has accepted open-science research use under a recorded disclaimer.

Neither remaining item blocks analysis. §3 bars *committing* recall prose to Git; it does not bar
reading the staged bytes from the git-ignored data root, which is where they live and stay.

The stimulus is a commercial feature. No video is stored, hashed or referenced by path.

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

Both files are data, not prose conventions. Canonicalize with `jq -S -c` before digesting.

## Verification performed

Every staged file was re-hashed with `git hash-object` and compared to the upstream tree blob at
commit `bb8f1b85`. **All ten match.** The two analysis notebooks in the upstream tree are
deliberately not staged; they are code, not source data.

The `RecallType` code book was additionally checked against the data's own behaviour before the
article was consulted, and the two agree. Scene-identifiability falls monotonically across the
codes — 99.0%, 30.4%, 19.9%, 1.6%, 1.0% of rows carrying a `BroadSceneNum` for codes 1 to 5 — and
code 3, which the article defines as *inaccurate* detail, carries the highest `FalseMemory` rate
(2.4%). Mean transcript length also falls monotonically (100, 81, 62, 52, 38 characters). The codes
therefore mean what the article says they mean in this copy of the workbook.

## Scientific use, and its limits

- **`RecallType` is the accuracy filter, and its code book is now recorded.** Strict veridical is
  code 1 (11,762 rows). A gist-inclusive variant is codes 1+2 (16,726 rows; both are accurate by
  the article's definition). Codes 3, 4 and 5 must never enter an accuracy measure — 3 is
  *inaccurate* content, 4 is commentary, 5 is not recall. Seven rows carry non-integer values
  (Excel dates and free text) and must be excluded explicitly rather than coerced.
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
  loader must key on header names and never on column position.
- **`SecondsInMinuteTime` mixes three encodings** — `00:00:05`, `0:00:00`, and Excel datetimes
  serialized as `1900-01-01 00:00:05` — plus blanks. `SecondsOfRecall` is the safer clock, while
  itself occasionally being a time string rather than a number.
- **`RecallType` is not uniformly integral**: seven rows carry Excel dates, out-of-range integers
  (`22`, `45`), or free text (`'3? 2?'`, multi-line strings). Exclude explicitly; do not coerce.

## Primary sources

- Release: [`JamesWardAntony/memento`](https://github.com/JamesWardAntony/memento) at `bb8f1b85`
- Article: [10.1162/jocn_a_02216](https://doi.org/10.1162/jocn_a_02216) (*J. Cogn. Neurosci.*
  36(11):2368–2385; PMC11887591, not open access)
