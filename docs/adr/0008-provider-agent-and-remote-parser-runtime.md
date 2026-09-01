# ADR 0008 — A remote parser runtime and the `provider-agent` adapter

**Status:** Accepted 2026-09-01, single-developer mode

**Date:** 2026-09-01

**Decider:** the owner (single-developer mode, AGENTS.md SD5)

**Plan:** `docs/plans/2026-09-01-story-pipeline-solo-plan.md`, phase 1.1 and decision D1

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
   supplies the five fields the envelope, receipts, and `ParserCacheKey.of` need, so every
   existing `PinnedRuntime` call site compiles unchanged and every `match` on
   `ParserRuntime` names `Remote` explicitly. Remote receipts carry
   `promptTemplateVersion = Some(name@version#checksum)`; pinned receipts keep `None`.
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
5. **Granularity.** One model call per request item inside one `exchange`; `timeoutMillis`
   bounds each item call. The driver wraps the provider in
   `SentenceIsolatingParserProvider`, so one exchange carries one item in practice.
   Recording keys are per item.
6. **Credentials and spend.** The key is read from `STORYMODEL4S_ANTHROPIC_API_KEY`, falling
   back to a nonblank `ANTHROPIC_API_KEY`; a blank variable counts as absent. Live calls
   additionally require `STORYMODEL4S_AGENT_LIVE=1`. The driver's default mode is `replay`;
   `record` calls the model and writes recordings. A missing recording in replay is
   `TransportFailure.Io(recording-missing)` for that exchange. Stdout carries counts,
   checksums, and ids, never source prose.
7. **Recording store.** `Recordings(dir)`; key = digest over model id, prompt-package
   checksum, prompt-text checksum, the item's text checksum, and the token list (ids,
   starts, ends, texts); never the caller's request id. Each value is one JSON file with
   the raw model text and a header (model, stop reason, usage tokens, durationMillis). A
   recording whose header model is not the requested model is refused as corrupt. Replay
   re-runs the full court on every run.
8. **Driver output.** `@main def claudeParse(mode, textPath, recordingsDir, outDir)` writes
   per sentence a `.penman` file and a `.chart.txt` with `Canonical.serialization`, plus
   `receipts.json` and `summary.json` carrying a `StageRecord` and `BuildReceipt` built with
   `BuildReceiptBuilder`. No `PropositionChart` codec exists in the repository; the chart is
   published as its canonical serialization only, and a codec is a separate slice.
9. **Dependency.** The official Anthropic Java SDK (`com.anthropic:anthropic-java:2.34.0`,
   model id `claude-sonnet-5`), confined to one file. It brings OkHttp and Jackson
   transitively, acceptable in a JVM-only module under design-contract item 11. No
   sampling parameters and no thinking configuration are sent. The `providerAgent` project
   depends on `providerParser`, `core.jvm`, `acquire.jvm`, `amrInterop.jvm`, and
   `proposition.jvm`, forks its tests, and is part of the root aggregate and of
   `jvmOnlyModules`.
10. **Prompt package.** `prompts/penman-parse.v1.txt` on the classpath, manifested by
    `AgentPromptPackage` under `PromptRole.Custom("provider-agent", "penman-parse")` with
    nonempty abstention rules, self-checks, and permitted operations. The package checksum
    and the prompt-text checksum enter `ParserConfig.params`, so they reach the cache key
    and every receipt. The few-shot examples are not War of the Ghosts sentences: the gold
    fixture stays the oracle and never the input the model is tuned on.

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

## Consequences

- `provider-parser` gains a second executable runtime kind without weakening the pinned one;
  `ParserRuntimeField` gains five typed cases for the new refusals.
- A remote receipt and a pinned receipt for the same provider and model strings are
  distinguishable by `promptTemplateVersion` (present versus absent) and by the fingerprint
  prefix (`remote-runtime:` versus `parser-runtime:`).
- The live path is untested in this repository's gates by construction; `LiveSmokeSuite`
  runs only when the environment opts into spend. Everything else is network-free.
- `provider-parser/README.md` still describes a runtime as `Ready` or `Unavailable`; that
  sentence is now incomplete and is left for the owner's documentation pass.
