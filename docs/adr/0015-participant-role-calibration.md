# ADR 0015 — Participant-role calibration studies

Accepted 2026-09-04, single-developer mode. Implements the calibration handoff
without manufacturing the adjudications it requires.

## Target and boundary

The first target is **whether the proposed participant role between the named
situation and filler is supported by the exact source**, under a versioned
adjudication protocol. It is conditional on the frozen extraction pipeline
emitting that candidate. It is not entity identity, participant coverage, parser
confidence, truth in the world, or recall quality. A deterministic mapping rule
still describes how the candidate was generated; it does not establish this
semantic target.

`ParticipantCalibration` belongs to `document`, which already owns these
candidates and depends on `acquire`. No module or dependency is added. The legacy
`align` calibrators are not moved into the story path: they lack source-bound
fit identities and do not represent this target.

## Data and identity

An `Item` is extracted from a validated `NarrativeCompilerInput` and one of its
single, scored, proposed participant candidates. Its identity binds the exact
source, charts, endpoints, role, score/scorer and evidence. A supplied pipeline
checksum identifies the frozen parser configuration, prompts, lexicons and
extraction policy; observed producer identities are also derived from chart
receipts, rather than trusting that declaration alone. Runtime I/O must retain
the referenced pipeline manifest. A checksum cannot prove what an external
manifest claims.

A `Judgment` binds the item checksum to Correct, Incorrect, or Unresolved, a
named adjudicator fingerprint and the checksum of the adjudication protocol.
These are external attestations, never labels inferred from the parser. A
`Corpus` requires exactly one final judgment for every item, rejects duplicate
items, repeated acquisitions of the same source endpoints, and unmatched judgments, and retains unresolved judgments in its identity
and coverage. Unresolved is excluded from estimation, never counted incorrect.
Do not present synthetic tests or machine labels as human gold.

The curator supplies a story-group identity to keep alternate editions,
paraphrases and repeated acquisitions of one narrative together. Exact source
checksums cannot occur in different groups. Neither this check nor a hash can
discover that two different texts are versions of the same narrative; that is a
curation obligation. War of the Ghosts remains an acceptance oracle, not training
or tuning data. No text or participant data is added by this slice.

## Estimator and evaluation

Current adapter scores are constants (0.9 for the standard-role scorer, 0.5 for
the lexicon-argument scorer). They do not support a fitted slope within either
scorer. We reject pooled Platt scaling and treating the constants as already
probabilistic. Instead, a cell is the exact (predicted role, scorer, raw score)
under one pipeline and producer identity. There is no interpolation, fallback
across roles/scorers, or prior-only prediction for unseen cells.

Version `participant-role-cell-beta11/v1` fixes a Beta(1,1) prior. For k correct
out of n adjudicated candidates in a cell, the predictive mean is (k+1)/(n+2).
The fitted artifact retains correct/incorrect counts, contributing story groups,
and excluded item identities. This is an exchangeable Bernoulli working model;
within-story dependence and corpus selection limit its interpretation. We do
not report binomial confidence intervals as though candidates were independent
stories. The prior is a declared modeling choice, not observed evidence.

A fit needs at least two labeled story groups. A prediction additionally needs
its cell represented in at least two training groups. These are structural
minimums, not evidence that calibration is good. Application rejects every
training group and every training source checksum, including sources whose
judgments were unresolved. A new score, scorer, role, pipeline or producer is
an explicit refusal. Fits are content-addressed with full SHA-256 identities,
including training items, labels, adjudicators, protocol and estimator version.
Row order has no meaning; no rounded coefficient or truncated hash defines a fit.

Leave-story-out evaluation requires at least three labeled groups and refits
from training rows in each fold. Every test item retains its prediction or
refusal, including unresolved items. Brier score and log loss are optional when
no labeled prediction exists; never zero by default. Each fold reports coverage
and sample counts alongside those scores. Overall results retain folds rather
than disguising uneven story sizes in one pooled scalar. A fit and an evaluation
are distinct artifacts: fitting does not certify generalization or choose an
acceptance threshold.

## Story-path application

A fitted model can return a new `ParticipantAttempt` whose candidate basis is
`Calibrated(p, CalibrationModelId)`; the original raw score, evidence, proposals,
and rule receipts remain intact. It is still submitted to the existing resolver
and compiler under an explicitly chosen acceptance policy. Calibration does not
mutate an accepted graph or bypass structural validation. No pipeline default is
changed, and no production model is shipped without adjudicated data and an
assessed held-out evaluation. A refused application is returned to the caller;
it does not silently regain a deterministic license.

## Evidence and limitations

Courts must falsify label leakage, source alias leakage, scorer/role pooling,
unresolved-as-negative, forged construction and identity omissions. Analytic
Beta-Bernoulli values and held-out label interventions are independent oracles.
Synthetic fixtures test mechanics only. The supplied handoff names adjudications
as missing; this change supplies the instrument, not an empirical claim that any
story-path probability is calibrated.

References: the Beta-binomial update is derived in [Gelman et al., Bayesian Data
Analysis, chapter 2](https://sites.stat.columbia.edu/gelman/book/BDA3.pdf).
[Arrieta-Ibarra et al. (2022)](https://www.jmlr.org/papers/v23/22-0658.html)
discuss why binned calibration summaries trade resolution against statistical
confidence. Neither source establishes validity for this project's corpus.

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

## Relationship to the frozen-set protocol

The existing 2026-08-28 M1 fixture protocol still governs selection-quality
claims: independent human annotation blind to charts and candidates, an audited
frozen calibration partition, and untouched-test evaluation. This ADR supplies
the mechanical fit and within-calibration-partition folds, not a new permission
to call diagnostic labels gold. Final candidate judgments must be mapped from
independently frozen source annotations; unresolved mappings remain unresolved.
Protocol eligibility and selection effects are not proved by the `Corpus`
constructor, and no production default is enabled here. The frozen corpus
manifest is still a candidate list; no frozen resource directory was available
in this checkout on 2026-09-04.
