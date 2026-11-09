# Porting the LLM mapper into the library: design (2026-11-08)

This document proposes a design; nothing in it is implemented yet. It comes from a read-only
design pass over `main` and `claude/integrate-codex` (tip f09066772), which the port depends
on. The research harness it ports is `tools/sherlock_mapper/`, described in
[the mapper plan](2026-11-08-sherlock-mapper-plan.md).

## Placement

- **`provider-agent`, JVM-only.**
  - A text-exchange recording key (`agent-text-exchange/v1`) over provider, model, system-prompt
    SHA, prompt SHA and budget. The existing `RecordingKey` is parser-specific.
  - A subscription-CLI `ModelBackend` case. It strips the API key and records the CLI version
    and the reported model.
- **`pipeline`.** A new `agentmapping/` package covers four steps: render the evidence packet,
  record or replay the call, parse the reply, and resolve deterministically.
- **Portable modules** get no LLM code. The only portable change is a categorical outcome door
  in `align` (D1).

## How the outputs map onto existing types

| Output | Type |
|---|---|
| Per-participant result | `MappingResult.checked` (supplied measures, no execution authority) |
| Narrative scene | `UnitOutcome` with `ExternalDecode(Destination(seg:sherlock:scene:N))` |
| Alternatives, confidence | `RawScores` (`agent:alts`, self-reported). Never a posterior or a calibrated value. |
| Referent scene | A second member, never the `Reference` member |
| Rows | Citations of `sit:sherlock:row:N` in the `TimedSourceView` |
| v3 claims | `ClaimAssessmentReceipt.checked` with `ClaimAssessorKind.AgentProposal`. `not-in-film` and `unclear` are unresolved, never Contradicted. |
| Discourse function | `DiscourseFunction` |
| Missing or unparsable units | `ProcessingStatus.Failed(ProviderFailure \| InvalidOutput)`, keeping every attempt |

## Slices

1. **Import the recorded harness outputs** into a `scene-outcomes/v1` arm, then run
   `gold_scene.py compare`.
   - **Acceptance:** 79.20 participant-average (81.39 pooled) over 1,499 units.
2. **Render the outline and prompts from typed sources:** `SherlockAnnotations`, the
   `asr-transcript` role and captions.
   - **Acceptance:** the outline SHA equals the harness `outlineSha`, and every prompt SHA is
     found in the cache.
3. **Text-exchange record/replay**, with an importer for the harness cache.
   - **Acceptance:** zero unrecorded calls, and the arm is byte-identical to slice 1.
4. **Superseded 2026-11-09** by the
   [categorical mapping plan](2026-11-09-categorical-mapping-and-transfer-plan.md): 4a
   categorical decision support, 4b a typed bundle with an independent reader.
5. **v3 claims** as a `ClaimAssessmentReceipt`, and the automatic condition (ASR plus frames,
   time converted to scene only at scoring).
   - **Acceptance:** 74.97 is reproduced.

## Owner decisions

- **D1.** A categorical `UnitOutcome` door in `align`, as an ADR 0022 implementation slice.
  - Recommended.
- **D2.** Narrative and referent are two members of an agent producer with policy compatibility
  `ModelDependentOnly`; neither is the reference member.
  - Recommended.
- **D3.** A subscription-CLI backend, subject to the ADR 0008 live opt-in.
  - Recommended.
- **D4.** An ADR 0022 amendment for the v3 claim vocabulary. It maps onto the existing types
  above and adds no new fidelity enum.
  - Recommended.
- **D5.** Evaluation scope. The replay and record commands refuse untouched participants. The
  no-film run is recorded as the exposure baseline. 79.2 is not called held-out before the
  ledgered test read.
  - Recommended.
