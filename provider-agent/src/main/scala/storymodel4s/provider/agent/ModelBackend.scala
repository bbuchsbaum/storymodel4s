package storymodel4s.provider.agent

import storymodel4s.provider.parser.RuntimeIdentityScalar

/** Which model service a run talks to, together with the identity that service implies.
  *
  * Why the identity is derived here and never written as a constant: an OpenAI-compatible server
  * can serve any model id it likes, including one Anthropic also serves, so a fixed provider label
  * would make a receipt from a local vLLM indistinguishable from a hosted Anthropic call, and would
  * let their recordings share a content key. Every identity scalar a receipt publishes -- provider,
  * model, wrapper version -- is computed from the configuration that chose the backend, so a caller
  * cannot assert a provenance it did not earn.
  *
  * Why the endpoint host carries its port: two servers on one machine (`127.0.0.1:8000` and
  * `127.0.0.1:8001`) routinely serve different weights under the same model id, and a label that
  * dropped the port would give those two runs one identity.
  */
enum ModelBackend(val provider: String, val model: String, val sdkVersion: String):
  /** The hosted Anthropic endpoint reached through the Java SDK; the wrapper version is the pin. */
  case Anthropic(modelId: String)
      extends ModelBackend(
        ModelBackend.AnthropicProvider,
        modelId,
        s"anthropic-java/${AnthropicSdkPin.version}"
      )

  /** Any server speaking the OpenAI chat-completions shape: OpenRouter, Ollama, vLLM, LM Studio.
    * The wrapper is the JDK's own HTTP client, so its version is the JDK feature release.
    */
  case OpenAiCompatible(endpointHost: String, modelId: String)
      extends ModelBackend(
        s"${ModelBackend.OpenAiCompatibleProviderPrefix}$endpointHost",
        modelId,
        ModelBackend.HttpClientVersion
      )

object ModelBackend:
  val AnthropicProvider: String = "anthropic"
  val OpenAiCompatibleProviderPrefix: String = "openai-compatible:"

  /** The model id the Anthropic backend uses when nothing configures one. */
  val DefaultAnthropicModel: String = "claude-sonnet-5"

  /** The wrapper version an OpenAI-compatible run records: there is no SDK, only the JDK client.
    *
    * Why no space in `java.net.http/jdk-N`: downstream this scalar is concatenated into a
    * `Fingerprint`, whose lexical rules reject whitespace, and that refusal arrives as a thrown
    * `IllegalArgumentException` rather than a typed failure. Measured 2026-09-02: the first draft
    * read `java.net.http jdk-25` and a replay through this backend died in `AmrCandidates`.
    */
  val HttpClientVersion: String = s"java.net.http/jdk-${java.lang.Runtime.version().feature()}"

  /** What a run resolves to when the environment names no backend. */
  val default: ModelBackend = Anthropic(DefaultAnthropicModel)

  /** Whether a scalar can survive the identifier rules every downstream fingerprint applies.
    *
    * The rule itself lives in `provider-parser`, which owns the admission that `RemoteRuntime.from`
    * and `PinnedRuntime.from` now enforce. This delegates rather than restating it, so there is one
    * rule with two enforcement points and no way for them to drift apart. Asking it here as well is
    * defence in depth: it turns `STORYMODEL4S_OPENAI_MODEL="my model"` into a refusal at the
    * environment court, naming the variable, instead of a refused transport further along.
    */
  def identityScalarIsSafe(value: String): Boolean = RuntimeIdentityScalar.isSafe(value)

  /** Leaves room for `provider:model:sdkVersion` inside the 256-character identifier limit. */
  val MaxIdentityScalarLength: Int = RuntimeIdentityScalar.MaxLength
