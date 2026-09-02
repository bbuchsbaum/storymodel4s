# storymodel4s-provider-agent

JVM-only adapter that puts a hosted Claude model behind the `ParserTransport`
contract of `provider-parser`, so `JsonAmrCandidateProvider` can turn each
sentence of a story into a `PropositionChart[Checked]` with `ProviderCall`
receipts. Design record: ADR 0008.

What it is:

- `ClaudeParserTransport.from(prompt, exchange)` derives its `RemoteRuntime`
  (provider `anthropic`, model `claude-sonnet-5`, the SDK version pinned in
  `build.sbt`, the prompt package ref, the prompt-text checksum) from the
  prompt it will actually send, so a receipt cannot name a prompt the
  transport did not use. It decodes the `storymodel4s.parser.request/v1`
  envelope with its own decoder, refuses a request whose runtime fingerprint
  or config params (`prompt-package`, `prompt-text`, `max-tokens`) are not its
  own, makes **one model call per request item** (each bounded by the
  request's `timeoutMillis`), and emits `storymodel4s.parser.result/v2` with
  the tokens echoed verbatim. The model writes PENMAN with inline `~e.N`
  markers; the transport derives the `explicit-index-list/v1` sidecar from the
  decoded markers in decoder order and changes nothing, so the admission
  court's count, range, order, and prefix checks still bite.
- `ModelExchange` is the only door model text comes through: `Live` calls the
  provider, `Recorded` serves a content-keyed recordings directory and holds
  no client, `RecordingLive` serves an existing recording and otherwise calls
  and records. The model id always comes from the runtime through the
  rendered request, never from the exchange.
- `Recordings` keys every reply by a digest over model id, prompt-package
  checksum, prompt-text checksum, the sentence's text checksum, and the token
  list (ids, spans, texts). Request ids never participate. Every recording
  says what stands behind it: `captured` (the provider's reported model,
  usage, and wall time) or `authored` (hand-written evidence, which may carry
  no accounting at all). Replay re-runs the full court on every read.
- Duration in receipts: `result/v2` requires a `durationMillis`, so an
  authored replay publishes `duration-millis=0` and a receipt cannot tell an
  authored replay from a measured 0 ms call. Provenance of the reply is the
  recording's `origin` and the driver's ledger, not the receipt. Making the
  wire duration optional, or adding an origin field, is a `provider-parser`
  schema change and the next slice.
- Retries: the SDK keeps its default of two retries on 408/409/429/5xx and
  connection errors, so one reply, and one receipt, may stand behind up to
  three HTTP attempts; `timeoutMillis` bounds each attempt.
- `AgentPromptPackage` loads `prompts/penman-parse.v1.txt` and manifests it
  (`PromptRole.Custom("provider-agent", "penman-parse")`).
- `claudeParse` is the driver: `sbt "providerAgent/runMain
  storymodel4s.provider.agent.claudeParse <replay|record> <text> <recordings-dir> <out-dir>"`.
  Per sentence it writes `<id>.penman` and `<id>.chart.txt`
  (`Canonical.serialization`), plus `receipts.json` (every `ProviderCall`,
  attempt outcome, recording key, and how the reply was served) and
  `summary.json` (counts, runtime identity, the `StageRecord`, and the
  `BuildReceipt`). The ledger (`replayed-authored`, `replayed-captured`,
  `captured-live`, `unrecorded`, `corrupt`, `foreign`) is derived from the
  store before and after the run, not from the mode argument; the stage is
  `cached` only when every sentence was served from a recording that existed
  before the run and was admitted on read, and `liveCalls` never counts a
  corrupt recording (no call is made for it). `ClaudeParseDriver.run` takes
  an `ExchangeSource`: `Court` (the client the environment court's backend
  implies, and the default), `Anthropic` or `OpenAiCompatible` (the same, with
  the court checked against that expectation; a disagreement is refused, never
  coerced), or `Scripted` (an offline client with fixed replies), so the record
  path is tested without spend. Stdout carries counts and checksums only. Exit
  status: 2 when the run could not start, 1 when any sentence never reached
  the court, 0 otherwise.

Environment:

| variable | meaning |
|---|---|
| `STORYMODEL4S_AGENT_BACKEND` | `anthropic` (the default when absent) or `openai`; any other value is refused |
| `STORYMODEL4S_ANTHROPIC_API_KEY` | preferred Anthropic key; a blank value counts as absent |
| `ANTHROPIC_API_KEY` | fallback Anthropic key; a blank value counts as absent |
| `STORYMODEL4S_OPENAI_BASE_URL` | required for `openai`; an absolute http(s) URL, version prefix included |
| `STORYMODEL4S_OPENAI_MODEL` | required for `openai`; the model id the server serves |
| `STORYMODEL4S_OPENAI_API_KEY` | the `openai` key; may be blank only for a loopback host |
| `STORYMODEL4S_AGENT_LIVE=1` | required in addition to the above before any live call |

## Backends

Two model backends exist, and the identity a run publishes is derived from the
one the environment names, never written as a constant. `anthropic` reaches the
hosted model through the Java SDK; `openai` reaches any server speaking the
OpenAI chat-completions shape through `java.net.http` and circe, with no added
dependency. A run publishes `provider = anthropic` or
`provider = openai-compatible:<host>[:<port>]`, `model` = the configured model
id, and `sdkVersion` = `anthropic-java/<pin>` or `java.net.http/jdk-<N>`.

OpenRouter:

```
export STORYMODEL4S_AGENT_BACKEND=openai
export STORYMODEL4S_OPENAI_BASE_URL=https://openrouter.ai/api/v1
export STORYMODEL4S_OPENAI_MODEL=meta-llama/llama-3.1-70b-instruct
export STORYMODEL4S_OPENAI_API_KEY=...        # required: the host is not loopback
export STORYMODEL4S_AGENT_LIVE=1
```

Ollama:

```
export STORYMODEL4S_AGENT_BACKEND=openai
export STORYMODEL4S_OPENAI_BASE_URL=http://127.0.0.1:11434/v1
export STORYMODEL4S_OPENAI_MODEL=llama3.1:8b
export STORYMODEL4S_AGENT_LIVE=1              # no key: the host is loopback
```

vLLM:

```
export STORYMODEL4S_AGENT_BACKEND=openai
export STORYMODEL4S_OPENAI_BASE_URL=http://127.0.0.1:8000/v1
export STORYMODEL4S_OPENAI_MODEL=meta-llama/Meta-Llama-3.1-8B-Instruct
export STORYMODEL4S_AGENT_LIVE=1
```

LM Studio is the same shape on `http://127.0.0.1:1234/v1`.

The loopback rule: a blank or absent `STORYMODEL4S_OPENAI_API_KEY` is admitted
only when the base URL's host is `127.0.0.1`, `localhost`, or `::1`. A remote
server reached with no key is a configuration error, not a free call, so it is
refused before anything is read.

What is sent and what is read: one POST to `{base}/chat/completions` carrying
`model`, `max_tokens`, and exactly two messages (`system` = the prompt package,
`user` = the rendered sentence). No temperature, top_p, seed, or streaming flag.
The reply must carry `choices[0].message.content`, a `finish_reason`
(`stop`/`length`/`content_filter` map to the three known stop reasons and
anything else is kept verbatim as an unexpected stop), and a `usage` block; a
reply with no usage is refused rather than recorded with zeros, because an
unmeasured count must not be indistinguishable from a measured one. Cache reads
are absent, not zero, since this wire shape does not report them. Retries: at
most two, only on 429 or 5xx, backing off 200 ms then 400 ms, so one reply may
stand behind up to three attempts, each bounded by the request's `timeoutMillis`.
The key is written into the `Authorization` header and nowhere else; redirects
are never followed, so it cannot be replayed to a host the court never admitted.

Per-backend quality is a measured coverage number for a given corpus, not a
property of this adapter. Nothing here claims that a local 8B model parses the
prompt package as well as a hosted one; run the corpus and read the counts.

The recording key carries the provider label alongside the model id, so a hosted
model and a local server advertising the same model id cannot share a recording.
The key schema is `agent-recording/v2`; `v1` recordings do not replay under it.

Modes: `replay` opens an existing recordings directory (a missing one is
refused, never created) and never calls the model; a missing recording is
`TransportFailure.Io(recording-missing)` for that exchange. `record` requires
the flag and a key before it reads anything, reads the text before it may
create the recordings directory, reuses recordings already present, and
writes new ones. `ParserConfig.seed` is `None`: live
reruns are not fingerprint-identical; determinism is claimed for replay from
recordings and proved by `ParserDeterminism.compare` over two replays.

A `ProviderCall` minted under replay attests that this transport exchanged
that request under that runtime identity; whether a network call stood behind
it is recorded in the recording's `origin` and published by the driver, not in
the call. The receipt's `promptTemplateVersion` names the manifest checksum and
the prompt-text checksum (`name@version#manifest+text`), so two prompt texts
under one manifest never share a receipt identity.

Tests are network-free. The three committed recordings under
`src/test/resources/recordings/` are authored PENMAN for three admitted War of
the Ghosts sentences, named by their content keys. `LiveSmokeSuite` is skipped
unless the environment above opts in.
