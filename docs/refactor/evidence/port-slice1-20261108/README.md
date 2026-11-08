# Port slice 1: recorded LLM-mapper replies as a frozen-scorer arm (2026-11-08)

Mote: `bd-01M6YK6YKQDMFWYX9CB7Z6J7TN`. Design:
[port design](../../../plans/2026-11-08-llm-mapper-port-design.md), slice 1.

## What landed

`pipeline` gains `AgentArmImport` and the command `agentMappingArm`. They read the frozen unit
manifest and the recorded harness replies, then check four things:

- unit id against the manifest;
- ordinal;
- no duplicate units;
- no reply units that are unknown to the manifest.

They write a `storymodel4s.bench.scene-outcomes/v1` arm. Every manifest unit receives an
outcome: label, abstention, invalid, or provider-failure with a reason. Categorical choices only;
no scores are carried.

## Gate

Run at `119d5b245` (code) and `fff0baeba` (formatting only), in a standalone clone, with
`tlFatalWarnings` on:

- the reference-scope modules (11 test reports): **3,130 tests, 0 failed, 0 errors, 5 skipped**;
- `AgentArmImportSuite`: 8 of 8;
- the re-gate of `pipeline` at `fff0baeba`: **437 tests, 0 failed**;
- `scalafmtCheckAll`: clean (exit 0).

## Acceptance

The input was `llm-mapper/a1v2-opus`, field `narrative`, partition development. The import
wrote 11 participants and 1,744 labels, with no abstentions, invalid outcomes or provider
failures. Arm SHA-256: `1170a688a34d3147a4f8f9ca434e36fb2e88f5198463f9cdb5f8aac0c980954a`.

The scoring command was `gold_scene.py compare` against arm A (`development-20260923/A.json`),
with support pin `b411c972…`, the registered 2026-09-23 support.

| Arm | Participant-average scene-exact |
|---|---:|
| A (old mapper) | 60.95 |
| LLM v2, imported through Scala | **79.20** |

- **Paired difference:** +18.25 points, 95% CI [+13.68, +22.88]; seed 20260902, 2,000 draws.
- **Pooled difference:** +16.14 points.
- **Match to the harness:** the result is identical to the Python harness (79.20), which meets the
  slice's acceptance criterion.

The full scorer output, with per-participant values, is in
[compare-A-LLM.json](compare-A-LLM.json). It contains no recall text.
