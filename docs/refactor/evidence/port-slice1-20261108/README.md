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

## Cold review and re-gate

A fresh-context review of `7601d194c` returned two BLOCKs:

- a reply lacking the requested field became an abstention;
- the schema test could not fail.

Both are fixed at `5a61e9382`, together with these follow-ups:

- a missing replies directory is refused;
- the output is published through a hard link, which is atomic and never replaces a file;
- tests cover scene 0, a missing index, and preserved unit identity;
- the identity-policy wording is corrected.

Re-gate at `5a61e9382`, in a standalone clone with fatal warnings on:

- `pipeline`: **442 tests, 0 failed**; `scalafmtCheckAll` is clean;
- `AgentArmImportSuite`: 13 of 13.

Mutation kills, one named test each, with the other 12 tests passing:

| Mutant | Test that failed |
|---|---|
| missing field becomes an abstention | "a reply lacking the requested field is invalid", line 158 |
| schema guard removed | "a manifest of another schema is refused", line 151 |

After restoring the code, the tree was clean and the suite passed 13 of 13. Re-running the
acceptance import gave 11 participants and 1,744 labels. The frozen scorer gives arm A **60.95**
and the LLM arm **79.20**.
