package storymodel4s.provider.agent

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

  /** The wrapper version an OpenAI-compatible run records: there is no SDK, only the JDK client. */
  val HttpClientVersion: String = s"java.net.http jdk-${java.lang.Runtime.version().feature()}"

  /** What a run resolves to when the environment names no backend. */
  val default: ModelBackend = Anthropic(DefaultAnthropicModel)
