# NMP 0.1 integration plan (2026-10-24)

Owner decisions, 2026-10-24: S3c-1 lands first; the pilot uses DeepSeek; a record carrying a
producer-written authority field is refused with a reason, not stripped. Subscription CLIs
(Claude Code, Codex) are admitted as additional producer transports, subject to the isolation
rules in "Producers" below.

## Goal

Narrative Mapping Protocol (NMP) 0.1, an external specification with five JSON Schemas, becomes a
proposal-only wire profile. storymodel4s remains the canonical model and the only source of
authority (design rule 13). The NMP specification and its examples stay outside Git (rule 14d) and
are cited by the hashes in its manifest.

The acceptance test for the whole effort: a producer that writes `"accepted"`,
`"human_adjudicated"`, `"exhaustive_in_scope"` or a `basis` value acquires no authority by doing
so.

The external review of NMP inspected public commit `13aec7cc`. Local `main` is 32 commits ahead
(ADR 0026, ADR 0027, S3a, S4), so the gap analysis below is done against local `main`.

## Phases

| Phase | Work | Done when |
|---|---|---|
| 0 | Land S3c-1 (`97161f239`): SD1 mutants, cold review, T3 gate | landed on local `main` |
| 1 | Gap analysis, read-only: classify every NMP field against `story`, `document`, `recall`, `align`, `acquire`, `codec` | every field classified; independent review against the code |
| 2 | ADR 0028, NMP proposal profile (owner decides) | Accepted |
| 3a | Codec from NMP to proposal inputs, with the forged-authority court | landed with a mutation kill |
| 3b | Certification registry keyed by source sha; producers cannot write it | landed with a mutation kill |
| 3c+ | One slice per admitted semantic gap, each with its witness | each landed separately |
| 4 | `build_movie` proposer skill, recorded through the replay seam | recordings replay byte-identically |
| 5 | Unscored pilot, part b rows 500-585 | report on Mote |
| 6 | Recall parsing, alignment, `credit()` rubric, second film, human gold | not scheduled |

### Phase 1: field classes

Each field is one of: (1) directly representable; (2) a checked composition of existing types;
(3) a semantic gap needing an ADR decision; (4) producer metadata without authority; (5) refused.
Every class-3 gap carries a witness sketch, for example: a joint antecedent must not become two
independently sufficient causes; `intersects` must not become `Overlaps`; a denial must not become
an asserted fact. The analysis also states which admission path owns each record family: the
`acquire` claim resolver or the `document` identity resolver of ADR 0027.

### Phase 2: ADR 0028 decisions

- D1. The canonical model is storymodel4s. Rejected: NMP as the system of record; a separate
  LLM-specific result system (ADR 0022 already rejects it).
- D2. An incoming authority field causes refusal of that record, with the reason recorded.
- D3. Authority comes from registered certifications bound to a source sha, deterministic resolver
  rules, or calibrated models (none exist yet). Missing authority yields Unresolved.
- D4. Information time is three quantities: available to the viewer, accessible to a character,
  known or believed by a character. Availability of a component is not availability of the whole
  claim.
- D5. Which class-3 gaps are admitted now. Proposed first: joint antecedents, `intersects`,
  presentation identity. Support x specificity waits for alignment.
- D6. Wire files that carry story or recall text stay outside Git.

## Producers

A producer is identified by its transport, model as reported, skill hash and parameters, and every
receipt carries all four. Three transports are admitted:

- DeepSeek through the API (the pilot default, under the existing USD 5 cap).
- Claude Code headless (`claude -p`) on the owner's subscription.
- Codex (`codex exec`) on the owner's subscription.

The producer runs outside the sbt build and writes recordings. The build only replays them, so it
stays unattended and deterministic regardless of transport.

Subscription transports need these controls, each verified by a canary run before use:

- **Isolation.** Run in an empty working directory holding only the task packet. No tools, no MCP
  servers, no web access, no session persistence. Global instruction files and memory are not
  read. `claude --bare` cannot be used, because it disables subscription sign-in, so the canary
  must show that no user-level instructions reached the model. A recall-blind run that could read
  the repository is not recall-blind.
- **Pinning.** Subscription models cannot be pinned exactly and have no seed or temperature
  control. The recording is the artifact; re-running is a new sample, not a replay.
- **Limits.** Runs count against the plan's usage windows. Batch sizes are fixed before a run.
- **Custody.** Sending annotations and transcripts to Anthropic or OpenAI is the same disclosure
  class as DeepSeek. Participant recall goes to no external producer until the owner records an
  REB basis.

Two model families also give a control for correlated errors: the pilot reports where they
disagree.

## Phase 5 pilot

- Inputs: the sha-pinned ADR 0027 input list and row convention for part b rows 500-585.
- Conditions: original names and two or three consistent name permutations, mapped back before
  comparison. Three runs per condition with frozen prompts.
- Admission: identity through the S3c resolver; claims through the resolver chosen in phase 1.
- Report: disagreement per field across runs and producers; accepted, alternative, unresolved and
  refused rates; every promotion and causal admission checked against its source.
- Named witness: the reported death stays in its Reported context unless the ADR 0026 promotion
  path licenses it.
