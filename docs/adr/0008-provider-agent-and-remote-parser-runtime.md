# ADR 0008 — A remote parser runtime and the `provider-agent` adapter

**Status:** Accepted 2026-09-01, single-developer mode

**Date:** 2026-09-01

**Decider:** the owner (single-developer mode, AGENTS.md SD5)

**Plan:** `docs/plans/2026-09-01-story-pipeline-solo-plan.md`, phase 1.1 and decision D1 (on this
branch via 4c37fef3)

## Context

Nothing in the repository turned English into semantic content. `provider-parser` is a
transport contract with a scripted fake, and its only readiness case, `Ready(PinnedRuntime)`,
requires checksummed source, package, checkpoint, and base-image artifacts with licence
evidence. A hosted model has none of those: its weights cannot be pinned, and the only
identity a caller can name is provider, model id, prompt, and client version.

The admission court in `JsonAmrCandidateProvider` was written for that contract and must not
be weakened: exact token echo, `modelInput` equality, result schema v2, an
`explicit-index-list/v1` sidecar whose rows match the rendered `~e.N` markers, strict
conversion through `AmrCandidates.fromPenman`, and receipts that carry a real `ProviderCall`.

## Decisions

1. **Readiness.** `ParserRuntime` gains `Remote(runtime: RemoteRuntime)`. `RemoteRuntime` is a
   private-constructor class built only by `RemoteRuntime.from(provider, model, sdkVersion,
   promptPackage, promptTextChecksum, resultSchema)`; its `checksum` and `fingerprint` are
   digested over every field, and `weightsPinned` is fixed to `false` with the documented
   meaning *remote weights cannot be pinned; identity is provider, model id, prompt package,
   and SDK*. A sealed `RuntimeIdentity` trait over `PinnedRuntime` and `RemoteRuntime`
   supplies the fields the envelope, receipts, and `ParserCacheKey.of` need, so every
   existing `PinnedRuntime` call site compiles unchanged and every `match` on
   `ParserRuntime` names `Remote` explicitly. Remote receipts carry
   `promptTemplateVersion = Some(name@version#manifestChecksum+promptTextChecksum)`, so two
   prompt texts under one manifest never share a receipt identity; pinned receipts keep
   `None`.
2. **Determinism.** `ParserConfig.seed = None`. Live reruns are not fingerprint-identical.
   The determinism claim is replay from recordings, proved by `ParserDeterminism.compare`
   over two replays of the same recordings reporting every sentence unchanged.
3. **Sidecar.** The model emits PENMAN with inline `~e.N` markers only (`N` a 0-based index
   into the sentence's token list as sent). The transport derives the sidecar rows from the
   decoded markers in decoder marker order and rewrites nothing: indices are not sorted,
   deduplicated, or range-filtered, so the court's count, range, order, and prefix checks
   still bite. Stripping the markers fails as `AlignmentMissing`; stripping the sidecar from
   the emitted JSON fails as `MarkerCountMismatch`.
4. **Wire.** Package `storymodel4s.provider.agent` re-emits the request decoder and the
   result/v2 encoder with its own circe code; the provider-parser wire types stay private.
   `EnvelopeSuite` proves the emitted JSON is admitted by the real court with pinned literal
   token, concept, relation, and alignment counts.
5. **Granularity.** One model call per request item inside one `exchange`. The SDK keeps its
   default of two retries on 408/409/429/5xx and connection errors, so one item's reply, and
   the one receipt minted for it, may stand behind up to three HTTP attempts; `timeoutMillis`
   bounds each attempt, not their sum. The driver wraps the provider in
   `SentenceIsolatingParserProvider`, so one exchange carries one item in practice.
   Recording keys are per item.
6. **Credentials and spend.** The key is read from `STORYMODEL4S_ANTHROPIC_API_KEY`, falling
   back to a nonblank `ANTHROPIC_API_KEY`; a blank variable counts as absent. Live calls
   additionally require `STORYMODEL4S_AGENT_LIVE=1`, and only `LiveAuthorization.from(env)`
   can mint the proof a client is built from. The driver takes its mode as an explicit
   argument: `replay` needs nothing and cannot spend; `record` calls the model and writes
   recordings, and is refused before anything is read or created when the environment has
   not opted in. A missing recording in replay is `TransportFailure.Io(recording-missing)`
   for that exchange. Stdout carries counts, checksums, and ids, never source prose.
7. **Recording store.** `Recordings(dir)`; key = digest over the user-message template
   version, model id, prompt-package checksum, prompt-text checksum, the token budget, the
   item's text checksum, and the token list (ids, starts, ends, texts); never the caller's
   request id. Each value is one JSON file with the requested model, the raw model text, the
   stop reason, and an `origin`: `captured` (the provider-reported model, usage tokens, and
   duration) or `authored` (hand-written evidence, which must carry no accounting). A
   recording whose requested model is not the request's model is refused as corrupt. Replay
   opens an existing directory and never creates one; replay re-runs the full court on
   every run. Known conflation: `result/v2` requires `durationMillis`, so an authored replay
   publishes `duration-millis=0` in its receipt, indistinguishable there from a measured 0 ms
   call; the recording's `origin` and the driver's ledger carry the provenance, and an
   optional wire duration or an origin field is the schema change of the next slice.
8. **Driver output.** `@main def claudeParse(mode, textPath, recordingsDir, outDir)` writes
   per sentence a `.penman` file and a `.chart.txt` with `Canonical.serialization`, plus
   `receipts.json` and `summary.json` carrying the `StageRecord` and `BuildReceipt` built
   with `BuildReceiptBuilder`. How each reply was served (`replayed-authored`,
   `replayed-captured`, `captured-live`, `unrecorded`, `corrupt`, `foreign`) is derived from
   the store before and after the run, not from the mode argument; the stage is `cached`
   only when every sentence was served from a recording present before the run and admitted
   on read, and a corrupt recording never counts as a live call. `run` takes an
   `ExchangeSource` (`Anthropic`, or `Scripted` with fixed replies) so the record path is
   tested offline; both pass the environment court first, and the text is read before the
   recordings directory may be created. No `PropositionChart` codec exists in the
   repository; the chart is published as its canonical serialization only, and a codec is a
   separate slice.
9. **Dependency.** The official Anthropic Java SDK (`com.anthropic:anthropic-java:2.34.0`,
   version pinned once in `build.sbt` and generated into `AnthropicSdkPin`; model id
   `claude-sonnet-5`), confined to one file. Resolved transitive set on 2026-09-01:
   `anthropic-java-core` and `anthropic-java-client-okhttp` 2.34.0, `okhttp` 4.12.0, `okio`
   3.6.0, Jackson 2.18.2 (`annotations`, `core`, `databind`, `datatype-jdk8`,
   `datatype-jsr310`, `module-kotlin`), and `kotlin-stdlib` 1.9.10; acceptable in a JVM-only
   module under design-contract item 11, and nothing portable depends on it. No sampling
   parameters and no thinking configuration are sent. The `providerAgent` project depends on
   `providerParser`, `core.jvm`, `acquire.jvm`, `amrInterop.jvm`, and `proposition.jvm`,
   forks its tests, and is part of the root aggregate and of `jvmOnlyModules`.
10. **Prompt package.** `prompts/penman-parse.v1.txt` on the classpath, manifested by
    `AgentPromptPackage` under `PromptRole.Custom("provider-agent", "penman-parse")` with
    nonempty abstention rules, self-checks, and permitted operations. The transport derives
    its runtime identity from the prompt package it sends and refuses any request whose
    config params do not name the same package, prompt text, and token budget, so the
    package checksum reaches every cache key and receipt and cannot be misnamed. The few-shot
    examples are not War of the Ghosts sentences: the gold fixture stays the oracle and never
    the input the model is tuned on.

## Departures from plan 1.1

- The plan named `java.net.http` with no new dependency; the owner chose the official SDK
  (decision 9) for typed errors and retries, confined to one file.
- The plan named `acquire` stage-cache keys "so reruns replay from cache"; here reruns replay
  from the content-keyed recordings store. A `StageCacheKey` is minted for the build receipt,
  but no `acquire` cache is consulted or populated; a `ParserCache` implementation would need
  a `PropositionEvidence` codec that does not exist yet.

## Rejected alternatives

- **Synthesising `PinnedRuntime` artifacts for a remote model.** The artifact checksums
  would be caller-asserted identities with the authority of a receipt (AGENTS.md: an
  identity must be derived from what it describes, never asserted by the caller).
- **A separate alignment list from the model.** Two sources of truth for one alignment can
  disagree silently; deriving the sidecar from the markers leaves one.
- **A local AMR parser subprocess (amrlib).** Deterministic, but it needs the
  `Ready(PinnedRuntime)` checkpoint-and-licence evidence, a Python runtime, and AMR parsers
  are weak on 1901 prose. The transport contract keeps that door open.
- **Raw `java.net.http` instead of the SDK.** No typed errors, no retries, and a second
  hand-written client to maintain; the SDK is confined to one file so the choice can be
  reversed.
- **A batch-level model call.** One sentence's reply could contaminate another's, and the
  court judges sentences.
- **Fixing marker indices in the transport** (sorting, deduplicating, clamping). That would
  turn the court's checks into decoration.
- **Passing the runtime and the prompt to the transport separately.** A caller could then
  run prompt B under receipts naming prompt A; deriving the runtime from the prompt removes
  the second source.
- **An origin field on the result/v2 wire.** It would let a receipt itself say whether a
  network call stood behind it, but it is a `provider-parser` schema change; recorded here
  as the next step rather than folded into this slice.

## Consequences

- `provider-parser` gains a second executable runtime kind without weakening the pinned one;
  `ParserRuntimeField` gains five typed cases for the new refusals.
- A remote receipt and a pinned receipt for the same provider and model strings are
  distinguishable by `promptTemplateVersion` (present versus absent) and by the fingerprint
  prefix (`remote-runtime:` versus `parser-runtime:`).
- A `ProviderCall` minted under replay attests the transport exchange under that runtime
  identity; whether a network call stood behind it is evidence in the recording's `origin`
  and in the driver's ledger, not in the call.
- The live path is untested in this repository's gates by construction; `LiveSmokeSuite`
  runs only when the environment opts into spend. Everything else is network-free.
- `provider-parser/README.md` still describes a runtime as `Ready` or `Unavailable`; that
  sentence is now incomplete and is left for the owner's documentation pass.

## Amendment, 2026-09-02: a second, OpenAI-compatible backend

Status: accepted (single-developer mode, SD5: written by the author, decided by the
author, with the rejected alternatives recorded on the day).

The original decision put one hosted provider behind `ParserTransport`. That makes the
whole adapter unusable to anyone without an Anthropic key, including a run against a
model on the same machine. This amendment adds a second backend and, more importantly,
stops the provider identity being a constant.

- **`ModelBackend` is the new vocabulary.** A sealed `Anthropic(modelId)` |
  `OpenAiCompatible(endpointHost, modelId)` whose `provider`, `model`, and `sdkVersion`
  are computed from it. `ClaudeParserTransport.runtimeFor` takes a backend and derives the
  `RemoteRuntime` from it, so nothing in this module writes a provider label by hand.
  This is AGENTS.md's rule that an identity must be derived from what it describes: with a
  constant `"anthropic"`, a receipt from a local vLLM serving `claude-sonnet-5` would have
  been indistinguishable from a hosted call, and nothing would have caught it.
- **The endpoint host carries its port.** `127.0.0.1:8000` and `127.0.0.1:8001` routinely
  serve different weights under one model id; a label that dropped the port would give
  those two runs one identity, which is the defect the design contract is mostly about.
- **`OpenAiCompatibleModelClient` uses `java.net.http` and circe.** One POST with four
  fields and a reply read from three; no SDK.
- **The recording key takes the provider label and moves to `agent-recording/v2`.** Two
  backends serving the same model id must not share a recording. The domain version moved
  with the added field, so the two framings cannot share a tag. This re-keyed the nine
  committed recordings in `provider-agent` and `pipeline` test resources; the bodies are
  unchanged and both suites read their keys from the driver, so the rename was the whole
  change on the test side.
- **A blank OpenAI key is admitted only for a loopback host** (`127.0.0.1`, `localhost`,
  `::1`). A remote server reached with no key is a configuration error, not a free call.
- **The identity scalars must survive the identifier rules.** `AmrCandidates` builds a
  `Fingerprint` from `provider:model:sdkVersion` with the unchecked constructor, so a
  scalar carrying whitespace reaches a `throw` in the middle of a parse rather than a
  typed refusal. Measured 2026-09-02: the first draft's `java.net.http jdk-25` did exactly
  that. One predicate, `ModelBackend.identityScalarIsSafe`, is now asked at the environment
  court and again at `runtimeFor`, so no path to a runtime identity skips it.
- **The prompt package is unchanged (v1).** Per-backend quality is a measured coverage
  number for a given corpus, never a claim; this amendment asserts nothing about how well
  any particular server parses the prompt package.

## Rejected alternatives, 2026-09-02

- **Adding an OpenAI SDK.** It would buy typed errors this module already produces from
  status codes and a retry policy this module already states, at the cost of a dependency
  in a JVM-only adapter whose whole point is to be replaceable. `java.net.http` is in the
  JDK and the client is one file.
- **A constant provider label of `"openai"`.** It would make every OpenAI-compatible
  server one provider in every receipt, so OpenRouter and a laptop Ollama would be
  indistinguishable in the audit trail and would collide in the recordings.
- **Sending temperature, top_p, or a seed.** A sampling knob nobody set is a difference
  between two runs that no receipt would record. Nothing but `model`, `max_tokens`, and
  the two messages is sent.
- **Defaulting `usage` to zeros when a server omits it.** That is the fabricated-license
  defect: an unmeasured count identical to a measured one. The reply is refused instead.
- **Defaulting `finish_reason` to `stop` when absent.** It would make a truncated reply
  indistinguishable from a complete one. The reply is refused.
- **Retrying a timeout.** The budget the caller set is for the exchange; retrying after a
  stall would silently spend three times the stated bound. Only 429 and 5xx are retried,
  at most twice.
- **Following redirects.** A followed redirect repeats the `Authorization` header to a
  host the court never admitted.
- **Making `ModelClient` sealed.** Sealing it would require every implementation in one
  file, which would put the Anthropic SDK imports into `ModelExchange.scala` and destroy
  the property that `LiveModelClient.scala` is the only file touching the SDK. The trait
  keeps one method and no function-typed parameters, which is what the sealing was for;
  `ModelBackend` and `LiveAuthorization` are sealed instead, and those are the types
  exhaustiveness actually protects.
- **Letting a caller name the backend positionally.** `ExchangeSource.Anthropic` is an
  expectation checked against the environment court, not a selection: a disagreement is
  `DriverError.BackendMismatch`. Coercing to either side would publish receipts naming a
  provider nobody chose.

## Consequences, 2026-09-02

- A run with no Anthropic key can parse text through OpenRouter, Ollama, vLLM, or
  LM Studio, and its receipts say which.
- `v1` recordings do not replay under `v2`; there is no migration path and none is
  wanted, since the `v1` key could not distinguish two backends.
- `RemoteRuntime.from` still admits any nonblank scalar, and the `Fingerprint` built
  downstream from it can still throw for a scalar this module did not mint. That gap is
  `provider-parser`'s and is left open here rather than widened into this slice.
