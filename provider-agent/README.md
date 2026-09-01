# storymodel4s-provider-agent

JVM-only adapter that puts a hosted Claude model behind the `ParserTransport`
contract of `provider-parser`, so `JsonAmrCandidateProvider` can turn each
sentence of a story into a `PropositionChart[Checked]` with `ProviderCall`
receipts. Design record: ADR 0008.

What it is:

- `ClaudeParserTransport` decodes the `storymodel4s.parser.request/v1`
  envelope with its own decoder, makes **one model call per request item**
  (each bounded by the request's `timeoutMillis`), and emits
  `storymodel4s.parser.result/v2` with the tokens echoed verbatim. The model
  writes PENMAN with inline `~e.N` markers; the transport derives the
  `explicit-index-list/v1` sidecar from the decoded markers in decoder order
  and changes nothing, so the admission court's count, range, order, and
  prefix checks still bite.
- `ModelExchange` is the only door model text comes through: `Live` calls
  the provider, `Recorded` serves a content-keyed recordings directory and
  holds no client, `RecordingLive` serves an existing recording and otherwise
  calls and records. The model id always comes from the `RemoteRuntime`
  through the rendered request, never from the exchange.
- `Recordings` keys every reply by a digest over model id, prompt-package
  checksum, prompt-text checksum, the sentence's text checksum, and the token
  list (ids, spans, texts). Request ids never participate. Replay re-runs the
  full court on every read.
- `AgentPromptPackage` loads `prompts/penman-parse.v1.txt` and manifests it
  (`PromptRole.Custom("provider-agent", "penman-parse")`). Its ref and the
  prompt-text checksum are part of the `RemoteRuntime` identity and of
  `ParserConfig.params`, so they reach every cache key and receipt.
- `claudeParse` is the driver: `sbt "providerAgent/runMain
  storymodel4s.provider.agent.claudeParse <replay|record> <text> <recordings-dir> <out-dir>"`.
  Per sentence it writes `<id>.penman` and `<id>.chart.txt`
  (`Canonical.serialization`), plus `receipts.json` (every `ProviderCall` and
  attempt outcome) and `summary.json` (counts, runtime identity, and the
  `BuildReceipt`). Stdout carries counts and checksums only.

Environment:

| variable | meaning |
|---|---|
| `STORYMODEL4S_ANTHROPIC_API_KEY` | preferred key; a blank value counts as absent |
| `ANTHROPIC_API_KEY` | fallback key; a blank value counts as absent |
| `STORYMODEL4S_AGENT_LIVE=1` | required in addition to a key before any live call |

Modes: `replay` (the default expectation) never calls the model; a missing
recording is `TransportFailure.Io(recording-missing)` for that exchange.
`record` requires the flag and a key, reuses recordings already present, and
writes new ones. `ParserConfig.seed` is `None`: live reruns are not
fingerprint-identical; determinism is claimed for replay from recordings and
proved by `ParserDeterminism.compare` over two replays.

Tests are network-free. The three committed recordings under
`src/test/resources/recordings/` are hand-written PENMAN for three admitted
War of the Ghosts sentences, named by their content keys. `LiveSmokeSuite` is
skipped unless the environment above opts in.
