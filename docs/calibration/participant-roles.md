# A participant-role calibration study

The implemented study estimates whether a proposed participant role is supported
by its exact source. It does not score a person's recall or collapse narrative
alignment into correct/incorrect labels. Role reliability is one uncertainty
coordinate underneath that larger model.

The implementation is `document.ParticipantCalibration` (ADR 0014). Its numerical
and compiler tests use synthetic labels. No human-adjudicated role corpus or
production fit is supplied with this implementation.

## Prepare the evidence before fitting

Freeze the extraction pipeline: parser/model revision, decoding settings, prompt
packages, frame lexicon and normalization tables, chart-proposal rules, and the
sampling protocol. Retain the manifest and pass its SHA-256 checksum to `item`.
Observed parser and proposer identities are checked as well. Receipt parameters
are retained in item identity; the library cannot infer which external
configuration choices your manifest omitted.

Use a preregistered set of multiple stories. Group alternate editions,
paraphrases and repeated acquisitions of the same narrative under one StoryId.
Keep War of the Ghosts out of fitting and tuning. A minimum of three labeled
groups makes leave-story-out mechanically possible; three is not a scientific
sample-size recommendation. Plan adequate stories and judgments for each
role/scorer cell, rather than padding one story with more candidates.

For every checked compiler input, enumerate `input.participants`. Call
`ParticipantCalibration.item(input, attempt, storyGroup, manifestChecksum)` on
each attempt. Keep every refusal in the extraction ledger. An unscored or
multi-candidate attempt does not become a negative training example. Retain
full source and compiler inputs locally so the annotator can inspect:

- the exact source checksum and source text, the candidate's evidence spans,
  and enough surrounding context to determine the role;
- the situation and filler coordinates, their chart-local meanings, and the
  proposed normalized role;
- the content-addressed item ID that will bind the final judgment.

For selection-quality evidence, follow the existing
[frozen-fixture adjudication protocol](../plans/2026-08-28-m1-fixture-adjudication-protocol.md),
including independent human annotators, a separate adjudicator, partition
separation and its blindness rules. **Annotators must not see these extracted
charts, candidates or scores.** They establish source situations and roles from
the source and permitted atlas first. Freeze those annotations before comparing
model candidates with them. An audited mapping from the independent annotation
coordinates to candidate coordinates can then populate the final judgments;
ambiguous mappings remain Unresolved. A study that asks annotators to confirm
model proposals is diagnostic unless a separately approved protocol establishes
its eligibility. This guide does not amend the frozen-set protocol.

Record `Correct` when the independently established source annotation supports
that role for those local endpoints, `Incorrect` when it contradicts the
proposal, and `Unresolved(reason)` when the annotation evidence or coordinate
mapping does not settle it. Retain the final adjudicator fingerprint and
protocol checksum, original ratings and disagreement resolution. The target is
the local situation–filler relation, not automatically resolved cross-sentence
identity. Identity calibration needs its own target and adjudications.

The library checks binding and completeness, not whether humans performed the
work or whether a corpus qualifies as a frozen selection set. Machine labels
must retain machine provenance. The current frozen-corpus manifest remains a
candidate list, and this checkout contains no frozen corpus resource directory.
The fitted artifact below is an engineering result until the existing protocol's
requirements, including untouched-test evaluation, are met.

## Fit and inspect

Given extracted `items` and the externally obtained `judgments`:

```scala
import storymodel4s.document.ParticipantCalibration

val study = ParticipantCalibration.corpus(items, judgments)
val heldOut = study.flatMap(ParticipantCalibration.leaveStoryOut)
val finalFit = study.flatMap(ParticipantCalibration.fit)
```

These are distinct operations. Inspect each held-out fold's model ID, training
story membership, full outcomes, number scored, Brier score and log loss. Losses
are absent when no labeled prediction exists. Unseen cells and cells represented
in only one training story remain refusals. An unresolved test label may still
receive a prediction, but does not enter the losses. Report that coverage beside
the scores: a method that answers only easy cases must not appear to have solved
the whole task.

Cells separate predicted role, scorer identity and exact score. The fixed
Beta(1,1) estimator produces `(correct + 1) / (correct + incorrect + 2)` and
retains both counts and contributing story groups. This deliberately does not
fit a slope to the current constant table grades. Inspect between-story
variation and corpus composition; small nominal losses alone do not establish
calibration. There is no automatic deployment threshold or generalization badge.

Changing labels, adjudicator, protocol, story grouping, source, pipeline or
recorded acquisition changes the relevant fit identity. Row permutation does
not. Preserve `model.training`, the source/manifest artifacts it references and
the held-out results with the model ID. A fitted ID by itself is not the study.

## Enter the story path

Extract a query item from a new checked compiler input under the same frozen
pipeline. `model.calibrate(queryItem)` returns either a refusal or a
`ParticipantAttempt` whose candidate basis is the fitted probability and model
ID. Raw score, evidence and rule receipts are retained. Rebuild the compiler
input through `NarrativeCompilerInput.of` with the returned attempt and a
prospectively selected acceptance policy, then run `NarrativeCompiler.compile`.
The ordinary resolver still controls acceptance; the calibration test suite
exercises this full path and a stricter policy that refuses the same candidate.

A refusal must be handled explicitly. Do not use `getOrElse(originalAttempt)` to
silently restore the old deterministic license. Do not attach the probability
by mutating an already accepted graph. Changing acceptance policy is a new
scientific decision and must be receipted independently of fitting.

Production `storyBuild` defaults are unchanged. Connecting a fitted study to that
CLI, persisting its manifest, selecting a policy and evaluating its effects await
the adjudications and the held-out evidence. This implementation provides the
portable study and compiler seam needed to do that work without inventing its
results.

## Selection and unresolved judgments

The fitted rate is conditional on the adjudication being resolved, as well as
on the pipeline emitting the candidate and its role/scorer cell. Excluding
unresolved judgments does not establish that their correctness rate matches
the resolved cases: two correct and 98 unresolved would still yield 0.75 from
the declared estimator, based on only two labels. Applying that rate to an
unevaluable candidate is an unvalidated extrapolation. Predictions retained for
unresolved test items are diagnostics and never enter loss totals. An intended
production population needs evidence about this selection effect before a
policy uses these probabilities to license claims. Neither a fitted model ID
nor the successful compiler seam establishes that evidence.
