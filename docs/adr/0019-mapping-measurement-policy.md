# ADR 0019: mapping evidence, inference policy and scientific use

Accepted direction, 2026-09-19, single-developer mode (SD5), in response to the owner's external
review and backlog-reorganization request. These are design decisions; the APIs are not yet
implemented. Detailed contracts and acceptance gates: [delivery plan](../refactor/PLAN.md).

Reuse existing source, recall, cost and receipt types in existing modules. Introduce a checked,
versioned mapping result with immutable reusable local evidence and named inference policies:
strict local reference measurement and structured reconstruction. Reserve joint scientific
inference for later; it estimates behavioral parameters and must not reuse an arbitrary
discriminative posterior as an observation likelihood.

Reference inference has a declared target universe, grain, prior and external alternatives. It
does not use a behavioral trajectory preference at retrieval, scoring, refinement, inference or
postprocessing. Chronology is available separately to readouts. A source-blind fixed packet
inventory preserves legitimate linguistic context without assuming a recall trajectory.

Derive assumption receipts from executed stages, extending ADR 0016's transition-only scope and
promoting ADR 0017's pre-refinement local evidence. Bind result, configuration, candidates,
provider/rendering, measure semantics and all decisions/projections. Missing provenance cannot
be self-certified as a reference profile by a caller-supplied label.

A readout declares quantity, source axis/order, inference and organization units, resolution,
unknown-transition policy and compatible inference policy. Compatibility distinguishes
`CompatibleWithDeclaredPolicy`, `ModelDependentOnly`, `Incompatible` and `UnknownProvenance`.
These are proposed vocabulary meanings; exact Scala construction follows the checked-boundary
ticket. Compatibility proves declared information use, not empirical recovery or calibration.

Keep raw score mass, model-conditional probabilities, calibrated correctness probabilities,
discrete choices and filled/projection values distinct. Preserve external states and processing
failures separately. Transition summaries use joints, or row products under declared conditional
independence; missing units do not disappear and word projection adds no behavioral observations.
Ambiguity bounds are conditional on the candidate/admissible set and are not confidence intervals.

Rejected: deleting structured inference; calling priorScale=0 independent measurement; separate
pipelines for reference/reconstruction; documentation-only scientific restrictions; mandatory
joint Bayesian modeling before useful delivery; three new modules; treating a successful policy
check as an unbiasedness certificate. The cost is a small checked evidence/result seam and
empirical recovery work. The benefit is preserving useful reconstruction without laundering its
assumptions into observations.

Sherlock integration reuses ADR 0018's `ClockRepair` and existing manifest vocabulary. Committed
JSON is the single admission-pin source, loaded through verified identity and receipted by its
own digest. Duplicating those hashes in Scala is rejected: agreement between two editable copies
does not supply independent evidence. This changes admission maintenance, not the admitted bytes.

The first scoring slice (2026-09-19) introduces only versioned benchmark artifacts in existing
modules: the recall unit manifest, frozen gold support, complete scene outcomes, comparison
result and scoring configuration. It does not implement the proposed public mapping-result API.
Freeze observation identity first and gold eligibility separately, before opening predictions.
The support's canonical digest is supplied independently from the support file and recorded in
the evaluation ledger; a changed support requires a new declared artifact and ledger entry.
Reject silently recomputing the digest during comparison, which would allow denominator edits.

Retain the preregistered uniform unit weights and participant-average paired difference;
report pooled unit-weighted differences separately. Nonlabel outcomes remain wrong when gold
eligible, and no-gold units remain accounted for. Empty populations and singleton bootstrap
intervals are explicitly undefined. Timing, transcript-order and adjacent-transition coverage
remain separate; unresolved units never create a synthetic direct transition across a gap.

The Friends seal rework (2026-09-21) shares cooperative Python guard mechanics in
`tools/recall-study/sealed_split.py`, with corpus-owned fixed paths and a committed split.
Preserve the original seal record; migrate its empty read ledger to the shared schema with
separate final-opening and count-only attempt counts. Reject caller-selected split/ledger
paths and post-seal use of sealing-time APIs. Record aggregate-read attempts before access,
including failures, and require the ledger to be committed before the next attempt. A
content-free `--check` reproduces membership and planning metadata from historical counts;
a recount requires an explicit purpose and ledger entry. Rejected: rereading recall during
ordinary integrity checks, rewriting historical seal digests, and treating an import-only
reader scan as proof of guarded access. This adds no Scala module or dependency and does
not establish the future release-manifest content contract.

The Memento task and seal (2026-09-21) use the same shared guard with fixed corpus paths.
The independent population rule retains 123 participants; the owner chose 63 test and 60
development, stratified 14/17/15/17 test across conditions, with seed 20260921. SHA-256 ranking
uses explicit LF-separated fields and is independent of gold counts and Python RNG versions.
Physical worksheet rows define observations; optional observed time does not define identity.
Accuracy requires transcript, accurate code and valid scene gold; either annotated scene is a
single correct match. Keep no-gold rows and zero-eligible participants explicitly accounted for.
The scorer accepts ID/outcome pairs and refuses duplicate IDs before building its lookup.
Indexed XLSX reads preserve error cells and validate physical coordinates; legacy unindexed
reads retain their prior behavior. Exact-byte inferred header overlays are admitted with
pre-seal no-overlay sensitivity, not as author-confirmed labels. S53 qualification semantics
remain unestablished. Pin task, population and parser identities in the seal. Rejected: merging
repeated text, inventing row clocks, salvaging one valid half of malformed scene gold, treating
missing gold as model error, generic positional header fallbacks, and substituting an
eligibility-selected population. This adds no Scala module, dependency or public Scala type.

The pre-seal audit exposed S72's empty canonical transcript column and populated duplicate
heading with one trailing space. Bind a fifth sheet overlay to that exact header and require
the displaced column to remain empty. Preserve the superseded audit in local evidence; no
model output or split allocation informed this correction.
