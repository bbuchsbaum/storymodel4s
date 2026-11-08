# Architecture decision records

Each ADR records one decision and the alternative it rejected. The owner decides ADRs. The table
below summarizes each ADR's recorded status as of 2026-11-08; where it flags "owner to ratify",
no acceptance record was found.

Statuses:

- **Accepted**: binding.
- **Accepted, partly implemented**: binding, with work open.
- **Draft**: not binding; reopen or retire when next touched.

The "event layer" column marks the ADRs that the film and recall event layer (see
[ROADMAP](../ROADMAP.md)) builds on directly.

| ADR | Decision | Status | Event layer |
|---|---|---|---|
| [0001](0001-embedding-contract-and-hard-gate.md) | Embedding contract, anchor/fidelity modes, non-bypassable gate | Proposed (implemented in `embed-core`); owner to ratify | |
| [0002](0002-visualization-contract.md) | Visualization: one compiler family, typed projections | Draft | |
| [0003](0003-estimand-support-contract.md) | Estimand support: shared law, carrier-specific result types | Accepted | |
| [0004](0004-hierarchy-level-two-coordinates.md) | Hierarchy level as two coordinates | Draft | |
| [0005](0005-surface-to-narrative-compiler.md) | Compile proposal evidence into a narrative model | Accepted | yes |
| [0006](0006-validated-provenance-option-values.md) | Validate provenance option values at construction | Accepted | |
| [0007](0007-film-source-representation.md) | Film through typed evidence and a presentation axis | Accepted, partly implemented | yes |
| [0008](0008-provider-agent-and-remote-parser-runtime.md) | Remote parser runtime and `provider-agent` | Accepted | |
| [0009](0009-pipeline-orchestrator.md) | The `pipeline` story-build orchestrator | Accepted | |
| [0010](0010-credence-coordinates-and-claim-provenance.md) | Credence as two coordinates; provenance identifies its claim | Accepted | |
| [0011](0011-measured-feature-tracks.md) | Measured feature tracks | Accepted | |
| [0012](0012-entity-identity-by-referring-form.md) | Entity identity by referring form | Accepted | yes |
| [0013](0013-world-order-input.md) | Timed-source builder takes a declared world clock | Accepted | |
| [0014](0014-directed-causal-transition-kinds.md) | Directed causal transition kinds | Accepted | yes |
| [0015](0015-participant-role-calibration.md) | Participant-role calibration studies | Accepted | |
| [0016](0016-layer-use-ledger-and-theta-provenance.md) | Layer-use ledger and θ provenance | Accepted | |
| [0017](0017-bench-stage-diagnostics.md) | Opt-in bench stage diagnostics (`STORYMODEL4S_STAGE_TRACE`) | Accepted | |
| [0018](0018-annotation-intake-contract.md) | Annotation intake contract | Accepted | yes |
| [0019](0019-mapping-measurement-policy.md) | Mapping evidence, inference policy, scientific use | Accepted direction (2026-09-19); 2026-09-23 amendment proposed, owner to ratify | yes |
| [0020](0020-storymodel-tabular-export.md) | Declared-lossy tabular StoryModel export | Accepted | |
| [0021](0021-offline-text-source.md) | Deterministic offline text sources | Accepted | |
| [0022](0022-mapping-producers-and-evidence.md) | Mapping producers, evidence packets, uncertainty | Accepted, partly implemented | yes |
| [0023](0023-portable-alignment-numerics.md) | Portable alignment arithmetic | Accepted | |
| [0024](0024-cross-modal-referent-identity.md) | Cross-modal referent mentions and identity links | Accepted | yes |
| [0025](0025-presentation-axis-ownership.md) | Presentation axis ownership for source streams | Accepted | yes |
| [0026](0026-speech-promotion.md) | Defeasible promotion of speech into the narrated world | Accepted | yes |
| [0027](0027-source-identity-graph.md) | Recall-blind source identity graph (Sherlock body scenes) | Accepted | yes |
| [0028](0028-source-graph-export.md) | Exporting the source identity graph | Accepted | yes |

Notes:

- References to "chief ruling", "Fray" or mote board topics in ADR headers are historical: they
  record how a decision was reached, not a process to follow.
- ADR 0019's base text is accepted direction; its 2026-09-23 amendment was proposed on Fray and
  later recorded as "accepted direction, with amendments" (line ~598). The owner should confirm.
- The NMP proposal profile (planned ADR 0029, [plan](../plans/2026-10-24-nmp-integration.md)) is
  the intended wire format for LLM-proposed events. It is not yet decided.
