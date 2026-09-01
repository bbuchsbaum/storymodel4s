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
  no accounting at all). Only captured durations reach a receipt. Replay
  re-runs the full court on every read.
- `AgentPromptPackage` loads `prompts/penman-parse.v1.txt` and manifests it
  (`PromptRole.Custom("provider-agent", "penman-parse")`).
- `claudeParse` is the driver: `sbt "providerAgent/runMain
  storymodel4s.provider.agent.claudeParse <replay|record> <text> <recordings-dir> <out-dir>"`.
  Per sentence it writes `<id>.penman` and `<id>.chart.txt`
  (`Canonical.serialization`), plus `receipts.json` (every `ProviderCall`,
  attempt outcome, recording key, and how the reply was served) and
  `summary.json` (counts, runtime identity, the `StageRecord`, and the
  `BuildReceipt`). The ledger (`replayed-authored`, `replayed-captured`,
  `captured-live`, `unrecorded`) is derived from the store before and after
  the run, not from the mode argument. Stdout carries counts and checksums
  only. Exit status: 2 when the run could not start, 1 when any sentence
  never reached the court, 0 otherwise.

Environment:

| variable | meaning |
|---|---|
| `STORYMODEL4S_ANTHROPIC_API_KEY` | preferred key; a blank value counts as absent |
| `ANTHROPIC_API_KEY` | fallback key; a blank value counts as absent |
| `STORYMODEL4S_AGENT_LIVE=1` | required in addition to a key before any live call |

Modes: `replay` opens an existing recordings directory (a missing one is
refused, never created) and never calls the model; a missing recording is
`TransportFailure.Io(recording-missing)` for that exchange. `record` requires
the flag and a key before it reads or creates anything, reuses recordings
already present, and writes new ones. `ParserConfig.seed` is `None`: live
reruns are not fingerprint-identical; determinism is claimed for replay from
recordings and proved by `ParserDeterminism.compare` over two replays.

A `ProviderCall` minted under replay attests that this transport exchanged
that request under that runtime identity; whether a network call stood behind
it is recorded in the recording's `origin` and published by the driver, not in
the call. A wire-level origin field would be a `provider-parser` schema change
and is left for a later slice.

Tests are network-free. The three committed recordings under
`src/test/resources/recordings/` are authored PENMAN for three admitted War of
the Ghosts sentences, named by their content keys. `LiveSmokeSuite` is skipped
unless the environment above opts in.
