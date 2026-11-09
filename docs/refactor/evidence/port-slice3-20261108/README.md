# Port slice 3: text-exchange record/replay and harness cache import (2026-11-08)

Bead bd-01M6Z1HRKYBVHTP4115937PR7F, epic bd-01M6Y7MWZGP29TNDJ90SG36A45. Design:
`docs/plans/2026-11-08-llm-mapper-port-design.md`, slice 3.

## What landed

- **`provider-agent` `TextExchange`.** A recording key in its own domain
  (`agent-text-exchange/v1`), built from the provider, the model, and the system-prompt and
  prompt texts. It is served from the existing `Recordings` directory type.
  - The public key takes the texts and hashes them itself.
  - Only the importer, inside the package, keys by hashes.
- **`HarnessCacheImport`.** Imports the research harness's reply cache as captured replies.
  - A reported model is kept only when exactly one model was reported.
  - Usage and wall time are kept.
  - The stop reason is recorded as `unrecorded`.
  - Any malformed record refuses the whole import.
- **`pipeline/agentmapping/AgentReplay`.** Follows the harness's control flow:
  - three attempts per chunk, with the fixed strict-JSON retry suffix;
  - the harness's reply extraction and Python `int()` unit keys;
  - later chunks overwrite earlier keys;
  - a missing unit is written as null and listed as missing.
- **Commands.** `agentHarnessImport` and `agentMappingReplay`.
  - Replay holds no model client.
  - Replay refuses an exchange it needs but cannot find.
  - It also refuses a development participant without a unit table, and a partition whose
    development and untouched lists overlap or whose untouched list is malformed.

## Acceptance

Run at c4218ab4d on git-ignored data.

- 412 harness cache records were imported (260 opus, 152 sonnet).
- Replay covered all 11 development participants: 34 exchanges, each accepted on its first
  attempt, with 0 model calls (`replay-receipt.json`).
- The arm built by `agentMappingArm` from the replayed replies is **byte-identical** (`cmp`) to
  the slice-1 arm built from the harness's own output files.
  - Its SHA is `aa09ddfa…c631f`.
  - It holds 1,744 labels, with no abstentions, invalid values or provider failures.
  - The frozen scorer's 79.20 therefore carries over unchanged.

## Gate

The gate ran in the scratch clone with `tlFatalWarnings := true`. Reference scope is
provider-agent and pipeline; storyatlas4s does not reference either.

- **At f1ce778cf.** provider-agent 83 total (82 passed, 1 skipped, 0 failed), pipeline 470/0,
  `scalafmtCheckAll` clean.
- **At c4218ab4d, after the review fixes (pipeline only).** Pipeline 471/0, `scalafmtCheckAll`
  clean, acceptance rerun byte-identical.

## Mutation kills

Each mutation was run at f1ce778cf; sibling tests passed.

| Mutant | Killed by |
|---|---|
| retry suffix numbered from 1 | `the retry suffix is the harness's literal text` |
| key drops the model | `the key is derived from the texts, and every identity field moves it` |
| missing cache directory imported as empty | `a missing cache directory is refused, never imported as empty` |
| row-reference rewrite removed | `a fenced reply with prose and row references parses` |

## Review

A cold review of f1ce778cf priced one BLOCK: replay silently skipped a development participant
without a unit table, where the harness falls back to a frozen table. Fixed in 9599e48f6 by
refusing.

Also fixed:

- a malformed or absent untouched list is now refused;
- the receipt's accepted flag is now explicit;
- tests now cover overwrite order and an accepted empty reply.

The reviewer's claim that `accepted` conflated an empty reply with exhausted attempts did not
hold, because `Option.nonEmpty` is `isDefined`. The flag now says so explicitly, and a test pins
it.

The remaining follow-ups are bead bd-01M6Z2ACV22SFZD1W5X4K58Y27.
