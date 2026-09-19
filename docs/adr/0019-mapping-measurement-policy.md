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
