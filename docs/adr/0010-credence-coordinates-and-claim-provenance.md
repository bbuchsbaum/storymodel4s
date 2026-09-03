# ADR 0010 — Credence as two coordinates, and provenance that identifies its claim

**Status:** Accepted 2026-09-03, single-developer mode (AGENTS.md SD5)

**Date:** 2026-09-03

**Decider:** the owner's agent, single-developer mode

**Plan:** `docs/plans/2026-09-02-model-truthfulness-slice.md` D4 and D5;
`docs/plans/2026-09-03-story-and-viewer-handoff.md` §2 items 1–2, §5 item 2

## Context

Measured on the model built from the fifty captured War of the Ghosts replies (handoff §2):

- Every one of 325 claims carried credence `1.0`. 197 carried it as a *calibrated probability*
  under a "calibration model" named `chart-rule-v1`, whose own Scaladoc said the rule was a total
  function of the chart, "so probability 1.0 is honest". 128 carried a raw `1.0` with no model at
  all, which design contract 3 forbids outright. `InteropTables` computed graded raw scores of
  `0.9` and `0.5` for role normalizations that were dropped at one line in the proposal provider
  and never reached the model. The parser reports no per-marker confidence, and its absence was
  written as `Credence.unsafeRaw(1.0)`, a default parameter, into every alignment claim.
- `ClaimMeta.provenance` on every claim was the run's entire provider call log: 604 calls copied
  into each of 325 provenances, 196,300 call records, 89.8 MB of `storymodel.json`, and a receipt
  that could not say which call produced which claim.

Both are the class design contract 7 names: a value that cannot be told apart from a different
value it must be told apart from. The certainty that a table lookup fired was stored in the field a
reader takes for certainty about the world; an imputed `1.0` was stored identically to a measured
one; and a claim's provenance was identical to every other claim's.

## Decisions

### 1. `Credence` is two coordinates: what was measured, and what licenses a probability

```scala
enum Score:
  case Unmeasured
  case Raw(value: Double, scorer: ScorerId)

enum CredenceBasis:
  case Uncalibrated
  case Calibrated(probability: Probability, model: CalibrationModelId)
  case Determined(rule: RuleId)

final class Credence private (val score: Score, val basis: CredenceBasis)
```

The two axes are independent meanings and get independent coordinates, not one wrapper (contract
7: "as many coordinates as there are meanings"). The lawful combinations and what each says:

| score | basis | says |
|---|---|---|
| `Unmeasured` | `Uncalibrated` | nobody scored it and nobody fit anything; the honest value for a parser that reports no confidence |
| `Raw(v, s)` | `Uncalibrated` | scorer `s` produced `v`; it is not a probability |
| `Raw(v, s)` | `Calibrated(p, m)` | model `m` mapped `v` to `p` |
| `Unmeasured` | `Determined(r)` | the value is a total function of its evidence under rule `r`; no probability applies |
| `Raw(v, s)` | `Determined(r)` | as above, and the evidence carried scorer `s`'s number |
| `Unmeasured` | `Calibrated` | **refused**: a calibration maps a number, and there is none |

`Determined` is the case that did not exist. A rule that cannot disagree with its input is certain
about the mapping, not about the world; recording it as `Calibrated(1.0)` failed contract 7's test
(name two things that must differ — the certainty that a role table fired, and the certainty that
two mentions corefer — and ask what published field differs; the answer was none). A determined
claim's certainty is exactly that of its inputs, which its evidence names.

A raw score names its **scorer**, so a table constant and a fitted model's output cannot share a
representation: `Raw(0.9, interop-tables/v1:standard-role)` says the number and says a table said
so. Three identifier kinds, not one, because a scorer emits a number, a rule determines a value,
and a calibration model maps a number to a probability — different roles, and a type per role keeps
a reader from taking the name of a lookup for the name of a fit.

Consequences on the wire and in fingerprints:

- `codec` model schema `0.3.0 → 0.4.0`; a claim's credence is `{"score": …, "basis": …}`.
  `0.3.0` is unsupported with no migration step, for the reason its predecessors are: a 0.3.0
  model wrote every determined value as a calibrated `1.0`, and a lift would have to decide which
  of those were fitted, which none were.
- `claim-meta/v2 → v3` and `provenance/v1 → v2` in the compiler's fingerprint renderings; every
  compilation fingerprint moves. No test pinned one to a literal.
- Negative zero folds onto zero in `Score.raw`, and `Score.render` prints IEEE-754 bits, so equal
  scores have one identity on every platform (the 2026-09-02 identity-bug class).

### 2. The acquire resolver accepts on a typed basis, never on a raw score

```scala
enum AcceptanceBasis:
  case Calibrated(probability: Probability, model: CalibrationModelId)
  case Determined(rule: RuleId)

final case class CandidateBasis[A](value: A, basis: AcceptanceBasis)
ResolutionState.Accepted(value, basis: AcceptanceBasis, evidence)
```

`AcceptanceBasis` has no uncalibrated case, so acceptance on a raw score is unrepresentable. A
`Calibrated` candidate goes through the accept/review/reject thresholds as before. A `Determined`
candidate has no probability to threshold: it is accepted on agreement and span evidence alone, or
not at all. `RawScore` gains its scorer for the same reason `Score.Raw` has one.

### 3. The chart proposal provider stops imputing

- Every proposal's basis is `Determined(rule)`: `chart-rule-v1`, `context-placement-v1` or
  `title-rule-v1`. Those names are unchanged: the rules did not change, only the coordinate they
  are stored in.
- A proposal's score is the minimum *measured* alignment score over its support under scorer
  `chart-proposal/v1:min-alignment`, and absent when there are no alignments or any of them is
  unmeasured. A minimum over a set with an unmeasured member would be a number about a subset
  presented as a number about the whole. The sentence-fallback support carries no score where it
  carried `1.0`.
- A participant's score is the role table's grade of its own normalization, under the table's
  scorer, taken from the `RoleAssignment` credence the provider used to drop. It is not combined
  with the alignment minimum: they are different scorers' numbers.
- `AmrCandidates.fromPenman`'s marker score defaults to `Credence.unmeasured`. A parser that
  reports confidence passes it; one that does not no longer reports `1.0`.
- Derived claims (entities, entity labels, context frames, trajectory steps, the alignment
  source's derived view) are `Unmeasured` + `Determined(compiler-derived:<kind>/v1 |
  trajectory-derive/v1 | derived-view/v1)`. The trajectory step's former `1.0 / 0.0` encoded whether
  a temporal edge licensed the transition, which `WorldTimeTransition` already states.

### 4. A claim's provenance is the calls that produced it

An accepted claim's `Provenance.calls` is exactly: the calls of the proposals that proposed the
accepted value, plus the parse receipts of every sentence its evidence lies in (found by span
offset through the atlas, since alignment spans name token units): the parser's call and the AMR
adapter's conversion receipt. A derived claim carries no calls; its
upstream claims, already in `Evidence.upstream`, carry theirs. `softwareVersion` and `configHash`
are unchanged. The run's whole log stays on `NarrativeCompilation.provenance` and in
`receipts.json`, where it was already published once.

## Measured on the fifty-sentence replay

Same 50 charts, 65 situations, 70 gaps, 135 violations, `validated == false` as before: nothing
here changes what the model derives, only what it says about how sure it is and where each claim
came from.

| quantity | before | after |
|---|---|---|
| `storymodel.json` | 89.8 MB | 0.8 MB |
| claims carrying a calibrated probability | 197, all `1.0` | 0 |
| claims carrying a raw score with no model | 128 | 0 (every claim has a basis) |
| claims `Unmeasured` + `Determined` | 0 | 269 |
| claims `Raw` + `Determined` | 0 | 56: 44 at `0.5` under `interop-tables/v1:lexicon-argument`, 12 at `0.9` under `interop-tables/v1:standard-role` |
| provider calls per accepted claim | 604 | its rule call and its sentence's two parse receipts (the parser's and the AMR adapter's): 3, or 5 for a temporal claim spanning two sentences |
| provider calls per derived claim | 604 | 0 |

## What this does not do

- It does not calibrate anything. No claim in the story path has a fitted probability, and the
  `align` module's `TemperatureScaling`, `PlattScaling` and `LeaveStoryOut` are still unreachable
  from `document` (they are siblings). When a fit exists, its identity is a `CalibrationModelId`
  and its output a `Calibrated` basis over a `Raw` score; nothing else changes.
- It does not give the situation, context, membership or coverage families a number. Their
  alignments are unmeasured because the parser reports no marker confidence; Article I says do not
  publish a measurement you could not make, "not as one". The role table's grades are the only
  graded signal the chart carries today, and they are now in the model under their own name.
- `story.StatusWeight.of(meta)` still falls back from an absent calibration to a constant per
  epistemic status when a relation layer is viewed as a matrix. That is the same class one level
  down and is left as recorded; it is not on the story-build path.

## Rejected alternatives

- **Propagating the interop scores as calibrated probabilities.** They are documented as
  non-probabilities; putting them in the `Calibrated` slot would repeat the defect with better
  numbers.
- **Deleting the fabricated `1.0` and leaving `None`.** That re-creates the conflation in the other
  direction: "determined by a total function" and "nobody has fit a model" would again share a
  representation.
- **One `CredenceKind` enum wrapping score and basis together.** Contract 7 names that shape: a
  single wrapper over two meanings launders the conflation under a name that implies it is
  settled.
- **A calibration model identity as a `String`.** It was one, in three places, and nothing kept a
  rule name out of it.
- **Keeping the union with the run log "for auditability".** The log is in `receipts.json` and on
  the compilation; a copy on every claim identified nothing and was 99.4% of the file.
