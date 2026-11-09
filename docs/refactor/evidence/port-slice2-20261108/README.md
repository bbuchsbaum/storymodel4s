# Port slice 2: film packet and v2 prompts from typed sources (2026-11-08)

Bead bd-01M6YYS065A0XZ1RW7W499DY5G, epic bd-01M6Y7MWZGP29TNDJ90SG36A45. Design:
`docs/plans/2026-11-08-llm-mapper-port-design.md`, slice 2.

## What landed

- `pipeline/agentmapping/FilmOutline` renders the agent mapper's film packet from the checked
  `SherlockAnnotations` atlas (row times from media loci), the recognizer's `transcript.json`
  (suspect segments dropped) and the scene-caption file.
- `AgentPrompts` renders the v2 prompts from a versioned system-prompt resource and the arm's
  unit table.
- `agentMappingPackets` writes SHA-256 identities only, for development participants only.
- `SherlockAnnotations.Row.namesAllCell` keeps the cast cell as written: two rows use separators
  the split list cannot reproduce.
- Whitespace is stripped by Python's `isspace` set, because Java `trim` keeps the U+00A0 that
  ends row 73. That byte was the only difference in the 230 KB packet before the fix.

## Acceptance

`packet-identities.json` is the command's output at the landed tree (hashes, no text).

- The outline SHA `70239406…d910` equals the harness `outlineSha` of runs a1-opus and a1v2-opus.
- 34/34 development prompt SHAs are found among the recorded exchange cache's `promptSha`
  values for model opus under system SHA `aef7cb6f…841a`. No development participant lacks a
  unit table.

## Gate

The gate ran on the scratch clone, `tlFatalWarnings := true`.

- **At d753a07d4.** Covered the modules referencing the changed types: corpus-intake 159/0,
  embed-bench 196 total with 1 skipped and 0 failed, pipeline 459/0, `scalafmtCheckAll` clean.
  `corpus` only mentions the type in a comment. storyatlas4s does not reference it.
- **At 810c8a5bf, after the review fixes (pipeline only).** Pipeline 460/0, `scalafmtCheckAll`
  clean, acceptance rerun 34/34.

## Mutation kills

Each mutation was run with `FilmOutlineSuite`; siblings passed.

| Mutant | Killed by |
|---|---|
| `harnessStrip` replaced by Java `strip` | exact-render, no-break space, harness-whitespace tests |
| cast cell rebuilt from the split list | exact-render test |
| speech tie goes to the later row | exact-render and earliest-row tests |

## Review

A cold review of d753a07d4 found no BLOCK. Fixed in 810c8a5bf:

- one strip rule for the final packet;
- a missing recall cell is refused as `MissingCell`;
- the chunk size is validated in the CLI;
- prompt tests use gapped unit indices;
- the directory stream is closed;
- development participants without a unit table are listed.

The remaining follow-ups are bead bd-01M6YZKAAN9NHV5YDTYRE482G9.
