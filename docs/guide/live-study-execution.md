# Live study execution and offline replay

The command consumes the same checked source CSV, parts JSON and study export used by the
[recorded pipeline](../plans/2026-11-10-common-recorded-pipeline-design.md). It writes one v2
mapping bundle per participant-session plus an invocation receipt. This first live adapter uses
the existing OpenAI-compatible chat-completions transport. Supply an explicitly authorized
endpoint and study; the command does not decide whether participant data may be sent there.

Use a standalone checkout for sbt. Keep study exports, recordings and outputs in private,
ignored storage. The output bundles contain source text; recordings contain verbatim model
replies. Never commit credentials or study material.

For ordinary UTF-8 recall files, first use [transcript preparation](ordinary-transcripts.md).

## Configure and run

Configure `STORYMODEL4S_AGENT_BACKEND=openai`, `STORYMODEL4S_AGENT_LIVE=1`,
`STORYMODEL4S_OPENAI_BASE_URL` (including the version path, such as `/v1`) and
`STORYMODEL4S_OPENAI_MODEL`. The model must equal the study export's model. Supply
`STORYMODEL4S_OPENAI_API_KEY` through your secret environment for a remote endpoint; a loopback
endpoint may omit it. The command appends `/chat/completions` to the base URL.

```text
sbt "pipeline/runMain storymodel4s.pipeline.agentmapping.agentStudyExecute live SOURCE.csv PARTS.json EXPORT_DIR RUN_DIR OUTPUT_DIR 4096 60000 10"
```

The final arguments are the maximum output tokens per request, timeout in milliseconds per HTTP
attempt, and maximum new client invocations for this command. The transport retries only 429 and
5xx responses, at most twice, with 200 ms and 400 ms backoffs. Thus ten client invocations permit
at most thirty HTTP attempts. The study export separately bounds malformed-reply retries and
records their exact suffix. Transport failures stop the study; they do not silently become
malformed-reply retries.

All source files, sessions, units and prompt/chunk bounds are checked before dispatch. `RUN_DIR`
holds an immutable input/configuration descriptor, recordings, the dispatch journal, and an
invocation receipt even if the study fails. The invocation receipt records completed execution;
only the receipt inside the published output proves publication. Receipt persistence and lock
closure precede publication, so their failure cannot leave an output that the command reports
as failed. Successful output is published together:

```text
OUTPUT_DIR/
  receipt.json
  bundles/
    PARTICIPANT_SESSION.bundle.json
```

An existing output directory is refused. A per-invocation budget stop leaves no published output
and preserves every completed recording. Rerun with the same inputs, configuration and `RUN_DIR`
to resume; the output directory must still be unused. Cached responses, including malformed
ones followed by a recorded correction, consume no new-call budget. Changed inputs or endpoint,
model, token budget or timeout refuse resume.

## Replay and inspect

```text
sbt "pipeline/runMain storymodel4s.pipeline.agentmapping.agentStudyExecute replay SOURCE.csv PARTS.json EXPORT_DIR RUN_DIR REPLAY_OUTPUT"
python3 tools/agentmapping/read_bundle.py REPLAY_OUTPUT/bundles/PARTICIPANT_SESSION.bundle.json
python3 tools/agentmapping/read_bundle.py REPLAY_OUTPUT/bundles/PARTICIPANT_SESSION.bundle.json --unit 0
```

Replay reads the saved execution descriptor. It does not read credentials or construct a model
client. Unit rows and checked mapping records must match the live output. Invocation metadata
differs: replay reports zero new calls and zero HTTP attempts. Requested model identity and
provider-reported model identity remain separate, and usage is retained only when supplied by
the provider. Cached usage describes the original exchange, not newly consumed tokens.

Legacy imported `agent-text-exchange/v1` recordings still use `agentStudyBundle`. New live calls
use v2 keys that include endpoint identity and token budget. A v2 miss never falls back to v1.

## Interrupted or failed calls

One live writer holds the recordings directory lock. Dispatch is journaled before sending, and
a captured reply is atomically recorded before its mapping text is parsed. A valid recording
wins over a stale started marker. A started or failed request without a valid recording refuses
automatic repetition, including after a process restart. A corrupt journal or recording also
refuses dispatch.

This guarantees reuse of durably recorded replies. It cannot guarantee exactly-once execution
at a remote service: a request may have been billed before the process lost its reply. Preserve
that run and reconcile its uncertain entries before authorizing another attempt. Starting a
fresh run directory is a new execution decision and may repeat calls; it is not ordinary resume.

The synthetic HTTP courts test transport, retries, budget stop/resume and offline equivalence.
They establish engineering behavior, not real-model quality, provider availability or scientific
validity of scene assignments. Real-provider smoke qualification is reported separately in the
delivery receipt.
