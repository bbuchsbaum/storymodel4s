# Stable-boundary construction judgment

The root-directed signature inventory is an audit population, not a list of checked values.
The corrected walk follows public enum/sealed alternatives, constructors and result-producing
companion methods. This exposes more dependencies than the old 781-symbol inventory. The old
`semantic-inventory.json` is retained as adverse history. The reviewed corrected population is
2,058 symbols and 21,499 edges at source `2c4b74f9`, retained in
`semantic-inventory-repaired-2c4b74f9.json.gz` and its named provenance sidecar. A later
exact-distribution refresh is recorded separately. Public signatures do not prove factory behavior, execution or calibration.

The table below records the receipt-bearing boundaries and the concrete open cases examined in
this pass. It does not certify every class in the expanded dependency closure. The owner-approved
family decision in `docs/api-stability.md` remains the intended boundary; its final freeze requires
resolving the exposed experimental dependencies and the findings below. The broader historical
422-enum census is outside this pass.

| Family | Construction judgment | Evidence and consequence |
| --- | --- | --- |
| `MappingRun.Execution`, `StageProvenance.Derived` | Unforgeable execution/result binding | `MappingRunBoundarySuite` has external positive controls and closed construction doors. Factory validation ties actual shared evidence, inventory, source and both members. Imported workspace data does not acquire this authority. |
| `LocalReference.Profile`, `Result`, `Computed`, `NotComputed` | Unforgeable checked profile/results | `LocalReferenceBoundarySuite` separately probes constructors, copy, product/Mirror and companion `fromProduct`, with positive controls. The typed computed/not-computed alternatives and reasons are part of the signature closure. Scores remain model-conditional. |
| `StructuredReconstruction.Result`, decisions and structural weights | Checked producer and context-bound values | `ReconstructionBoundarySuite`, `StructuralWeightBoundarySuite` and the retained reconstruction/weight mutation receipts bind decisions and endpoints to the actual execution. A weight changes the declared estimand; it does not establish calibration. |
| `MappingResult`, `UnitOutcome`, mapping measures, stage ledger and derivation binding | Checked relational records | Mapping result/outcome/measure/vocabulary boundary suites test the external doors; mapping codecs rederive exact joins. Complete unit accounting and unavailable calibration remain explicit. Public error/reason tags are descriptive data. |
| `Admissibility`, `StructuralCoverage`, `CostBreakdown`, `SupportAssessment`, `CellSupportBasis` | Checked numerical and relational quantities | `AdmissibilityUnforgeableSuite` and `SupportAssessmentUnforgeableSuite` distinguish impossible counts, non-finite input, absent denominators and external/not-applicable cases. A surrounding HSMM result alone is not their construction proof. |
| `FlowStep`, `TransitionFlow`, `HsmmResult` | Checked structural consistency | `FlowUnforgeableSuite` checks finite nonnegative mass, distinct endpoints and chaining, alongside construction doors. `HsmmResult.validated` checks the result structure. This does not authenticate arbitrary historical cost-model invocation. The HSMM codec separately refuses noncanonical text/media support as `UnsupportedSupport`; structural validity does not grant general media serialization. |
| `SourceRepresentation`, bundle-bound physical support and composition | Checked source/axis/bundle relationships | Source/playback construction suites retain original coordinate identity, gaps and points. Immutable digest caches preserve the original byte identity. A physical coordinate does not imply scanner/media synchronization or disclosure permission. |
| `RecallInventory` and checked recall/word identity | Checked inventory and unit accounting | `RecallInventoryUnforgeableSuite` and `RecallInventorySuite` distinguish segmentation, parsing, policy and unassigned words. The digest cache retains the old checksum. Original recall content remains subject to the admission/disclosure rules. |
| `RecallTiming` | Declared observations admitted against an exact clock and inventory | Its checked admission rejects foreign/duplicate/missing word joins and nonpositive intervals; the boundary suite covers construction. Onset-only observations carry no duration. `SourceReported`/`Declared` is provenance vocabulary, not proof of physical synchronization. |
| `Estimate[V]`, missingness/ineligibility/reason tags | Honest open sum/product data | Generic `Observed` is not a finite-number witness. `Estimate.score` and numeric consumers supply their own checks. Eligibility, missingness and uncertainty remain separate. `Coverage` is separately checked for `0 <= observed <= eligible`. |
| `PropositionChart`, graph/recall/story values with check-state witnesses | Explicit checked/unchecked distinction | Unchecked proposals and descriptive relation payloads are constructible. Checked chart/graph factories and validators supply relational guarantees. A `Checked` structural value does not certify truth or semantic annotation quality. |
| `MappingPipeline.Config`, configuration requests and refusal tags | Public request payload; validated at producer entry | `Config.copy` is intentionally public. `MappingPipeline.validate` checks finite positive temperature, profile, candidate budget, requested capabilities and zero network/retry budgets. `MappingPipeline.Run` retains the admitted `Config`; the underlying `MappingRun.Execution` binds evidence and profile identities. Refusals do not themselves authorize computation. |
| `CandidateSet`, `Candidates`, `Nomination`, historical `LocalEvidence.compute` | Open historical payload with an unresolved abstention contract | The outside-package probe constructs nonempty nominations with `abstained=true` through apply/copy/Mirror. Historical computation accepts and retains that flag as `Unattested`. This is a real constructor/record observation, not a forged strict run. Resolve the invariant and stable exposure before freezing this family. |
| `StrictCandidates`, controlled scoring/embedding/caption registrations | Checked strict producer inputs and receipts | Existing strict-content, mapping-run, surface, caption and ONNX boundary suites distinguish these from caller-declared historical payloads. Registered ONNX identity binds actual execution; an arbitrary `CandidateSet` is insufficient. |
| `EmbeddingSpace`, `TruncationPolicy.KeepHead/KeepTail` | Partly checked recipe with an open token-bound contract | The compiled probe shows negative and zero token limits are admitted and retained by `EmbeddingSpace.of`. The current ONNX implementation produces `Reject`; occurrence in an actual ONNX result has not been demonstrated. Settle and enforce the recipe invariant before a stable checked-recipe claim. |
| Imported temporal `Session`/`Projection`, views and export descriptors | Checked artifact joins, separate from executed mappings | The separate inspection root list records this surface. Foreign clock/policy/view, forged quantity, unavailable timing, export permission and round-trip controls are covered by the temporal producer/consumer tests. Their presence in the inventory does not promote them silently to a stable Scala API. |

## Release consequence

The ordinary study path has independent execution and readback evidence. The pre-freeze audit
still has a concrete boundary decision: `LocalEvidence.candidates` exposes `CandidateSet`, and
public result/factory signatures reach configuration/provider and narrative implementation types
previously described as experimental. The owner-approved rule requires promotion and audit of
those exact dependencies, or a narrower supported facade before the final freeze. A zero-length
`unresolved` list only means the walker resolved its symbols; it is not acceptance of that decision.

The two constructive probes are retained under `construction-probes/`. Their findings are
follow-ups for the currently qualified study/inspection path and blockers for the broader claims
identified in the table. Keep them on their own Mote records; do not close the final release gate
or publish `1.0.0` on the basis of enumeration, the compatibility rehearsal or a passing build.

Tracked findings:

- `bd-01M4XTRN36R6SYXQBP5YN7FSQP`: Settle historical CandidateSet abstention semantics before stable API freeze.
- `bd-01M4XTS7JEY2ZQK2CFNZ429RFW`: Validate or explicitly delimit token truncation recipe declarations before API freeze.
