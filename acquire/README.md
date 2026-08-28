# storymodel4s-acquire

The autonomous acquisition protocol: the pure, portable types and deterministic
logic that JVM-only providers (parser services, LLM agents, embedding backends)
and the M1 orchestrator implement against. No I/O, no HTTP, no model client.

**Agents propose; code resolves.**

## The contract

| Type | Role |
|---|---|
| `TaskPacket[I]` | One bounded task for one agent: typed input, the stable IDs it may reference, the standards excerpts selected by code, the prompt package it runs under, its budget. |
| `AgentProposal[A]` | The only thing an agent may return: `Proposed`/`Alternative` (value + evidence), `Abstained`, or `Unsupported`, with a `RawScore` (never a probability) and a receipt. |
| `CriticFinding` | A typed observation with a closed `FindingCode` vocabulary and severity. Data, never a mutation. |
| `Patch` / `PatchOp` | Typed edits over an abstract graph. `PatchApplier.applyPatch` is atomic: any failure returns the untouched graph through `Left`. |
| `EvidenceBundle[A]` → `Resolver.resolve` → `ResolutionState[A]` | Deterministic resolution: `Accepted(value, probability)` (calibrated by type), `Alternatives`, `Unresolved`, or `Rejected`. Never a majority vote. |
| `AcceptancePolicy` / `FamilyPolicy` | Per-claim-family thresholds; high-impact families (reported→root promotion, event coreference, role reversal, polarity, strict precedence, causal edges, target-episode membership) default to `Conservative`. |
| `CandidateLedger[A]` | Append-only record of every candidate, its bundle, and its state, with `supersedes` links. |
| `StageCacheKey`, `StageRecord`, `BuildReceiptBuilder` | Content-addressed stage caching and receipts, including `layerCoverage` (NotAttempted vs Attempted). |
| `PromptPackageManifest` / `PromptPackageRef` | Versioned prompt packages as data with a canonical checksum. |
| `FoilKind`, `FoilGenerator[A]`, `FoilReport` | Controlled adversarial transformations and the report math for critic preference rates. |
| `StageSpec`, `StageDag`, `BuildPlan`, `BudgetPolicy`, `GateStatus` | Effect-free orchestration contracts: deterministic topological order, cache-key-driven invalidation, budgets as data, gate combination. |

## Rules for providers

1. Decode model output into `AgentProposal`/`CriticFinding`/`Patch` via the
   smart constructors; a decode that violates an invariant is a provider error,
   not a partially trusted result.
2. Reference sentence/token/node IDs from the `TaskPacket`; never emit
   character offsets.
3. Report raw scores as `RawScore`. Calibration happens offline per claim
   family and arrives in `EvidenceBundle.calibrated`.
4. Every call produces a `ProviderCall` receipt wrapped in `AgentCallReceipt`,
   pinned to a `PromptPackageRef`.
5. Providers never construct `Resolved`, never write into a `StoryModel`, and
   never decide which stages run.

## Resolution semantics

Given a family `f`, a bundle, and a policy:

1. structurally invalid → `Rejected(StructurallyInvalid)`;
2. any blocking finding → `Rejected` (conservative family) or `Unresolved`;
3. no substantive proposal → `Unresolved(NoProposal)`;
4. conservative family with zero source support → `Rejected(NoSourceSupport)`;
5. no calibrated probability → `Unresolved(Uncalibrated)` when calibration is
   required and there is one candidate, else `Alternatives`;
6. leading candidate (most `Proposed` support, then best raw score, then first
   appearance) must meet `requireAgreement` → else `Unresolved`;
7. `p ≥ acceptThreshold` → `Accepted`; `reviewBand ≤ p` → `Alternatives`;
   otherwise `Rejected(BelowRejectBand)`.
