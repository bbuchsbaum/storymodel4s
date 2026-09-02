package storymodel4s.provider.agent

import java.net.URI
import scala.util.Try

/** An API key admitted from the environment. Opaque so it can be threaded to the client without
  * ever being rendered, compared, or logged by this module.
  */
object ApiKey:
  opaque type ApiKey = String

  private[agent] def fromNonBlank(raw: String): ApiKey = raw

  extension (key: ApiKey) private[agent] def secret: String = key

type ApiKey = ApiKey.ApiKey

/** Why the environment does not name a usable backend.
  *
  * Why this is separate from [[LiveRefusal]]: a replay needs a backend too -- it names the runtime
  * in every receipt and keys every recording -- while it needs no key and no live flag. These
  * refusals therefore fire in a mode that cannot spend, and a caller that folded them into the live
  * refusals would report "live calls need a key" for a run that was never going to call.
  *
  * Why the base URL is never echoed: an http(s) URL may carry `user:password@` userinfo, so its
  * text is treated as credential-bearing even though the host alone is not.
  */
enum BackendRefusal:
  case UnknownBackend(variable: String, raw: String, admitted: Vector[String])
  case BaseUrlAbsent(variable: String)
  case BaseUrlNotHttp(variable: String)
  case ModelAbsent(variable: String)

  /** The model id cannot become part of a runtime identity; see
    * `ModelBackend.identityScalarIsSafe`.
    */
  case ModelNotIdentitySafe(variable: String, maxLength: Int)

  def message: String = this match
    case UnknownBackend(variable, raw, admitted) =>
      s"$variable=$raw is not one of ${admitted.mkString(", ")}"
    case BaseUrlAbsent(variable)  => s"the openai backend needs a nonblank $variable"
    case BaseUrlNotHttp(variable) =>
      s"$variable must be an absolute http or https URL naming a host"
    case ModelAbsent(variable) => s"the openai backend needs a nonblank $variable"
    case ModelNotIdentitySafe(variable, maxLength) =>
      s"$variable must be at most $maxLength characters with no whitespace or control characters"

/** Why a live model call is refused: the environment names no usable backend, did not opt into
  * spend, or has no key where the chosen backend requires one.
  */
enum LiveRefusal:
  case BackendRefused(refusal: BackendRefusal)
  case LiveFlagAbsent(variable: String, requiredValue: String)
  case ApiKeyAbsent(primary: String, fallback: String)
  case ApiKeyAbsentForRemoteHost(variable: String, host: String)

  def message: String = this match
    case BackendRefused(refusal)         => refusal.message
    case LiveFlagAbsent(variable, value) => s"live calls need $variable=$value"
    case ApiKeyAbsent(primary, fallback) => s"no nonblank $primary or $fallback in the environment"
    case ApiKeyAbsentForRemoteHost(variable, host) =>
      s"$variable must be nonblank for the remote host $host; a blank key is admitted only for " +
        "a loopback server"

/** What the environment says about the backend, before any question of spend. Package-private
  * because it carries the resolved endpoint, which only the credential court and the clients need;
  * the published vocabulary is [[ModelBackend]].
  */
private[agent] enum BackendChoice:
  case Anthropic(modelId: String)
  case OpenAiCompatible(chatCompletions: URI, modelId: String)

  /** The identity this choice implies; the same derivation the live authorization publishes. */
  def backend: ModelBackend = this match
    case Anthropic(modelId)                         => ModelBackend.Anthropic(modelId)
    case OpenAiCompatible(chatCompletions, modelId) =>
      ModelBackend.OpenAiCompatible(AgentCredentials.identityHost(chatCompletions), modelId)

/** Proof that the environment opted into spend and supplied everything the admitted backend needs.
  *
  * Why a sealed trait with two closed implementations rather than one case class: the lawful
  * combinations are not the cartesian product of the fields. An OpenAI-compatible endpoint may
  * carry no key only when its host is loopback, and an Anthropic authorization has no endpoint at
  * all. Only [[LiveAuthorization.from]] mints one, so no same-package code can pair a remote host
  * with an absent key, and no caller can pair an endpoint with a provenance it does not imply.
  */
sealed trait LiveAuthorization:
  /** The identity the admitted configuration implies; derived, never asserted by a caller. */
  def backend: ModelBackend

object LiveAuthorization:
  /** An admitted Anthropic authorization: the SDK owns the endpoint, so only the key is carried. */
  final class Anthropic private[LiveAuthorization] (val apiKey: ApiKey, modelId: String)
      extends LiveAuthorization:
    val backend: ModelBackend = ModelBackend.Anthropic(modelId)

    /** Names the model and nothing else; the key is never rendered, here or anywhere. */
    override def toString: String = s"LiveAuthorization.Anthropic(${backend.model})"

  /** An admitted OpenAI-compatible authorization: the resolved chat-completions endpoint and a key
    * that is absent only because the court found a loopback host.
    */
  final class OpenAiCompatible private[LiveAuthorization] (
      val chatCompletions: URI,
      val apiKey: Option[ApiKey],
      modelId: String
  ) extends LiveAuthorization:
    val backend: ModelBackend =
      ModelBackend.OpenAiCompatible(AgentCredentials.identityHost(chatCompletions), modelId)

    /** Says whether a key is carried, never what it is, and never the endpoint's userinfo. */
    override def toString: String =
      s"LiveAuthorization.OpenAiCompatible(${backend.provider}, ${backend.model}, " +
        s"keyed=${apiKey.isDefined})"

  /** A live call needs a usable backend, the explicit flag, and a key wherever one is required. */
  def from(env: Map[String, String]): Either[LiveRefusal, LiveAuthorization] =
    for
      choice <- AgentCredentials.backendChoice(env).left.map(LiveRefusal.BackendRefused(_))
      _ <- Either.cond(
        env.get(AgentCredentials.LiveVariable).contains(AgentCredentials.LiveValue),
        (),
        LiveRefusal.LiveFlagAbsent(AgentCredentials.LiveVariable, AgentCredentials.LiveValue)
      )
      admitted <- keyed(env, choice)
    yield admitted

  private def keyed(
      env: Map[String, String],
      choice: BackendChoice
  ): Either[LiveRefusal, LiveAuthorization] = choice match
    case BackendChoice.Anthropic(modelId) =>
      AgentCredentials
        .apiKey(env)
        .toRight(
          LiveRefusal.ApiKeyAbsent(
            AgentCredentials.PrimaryKeyVariable,
            AgentCredentials.FallbackKeyVariable
          )
        )
        .map(key => new Anthropic(key, modelId))
    case BackendChoice.OpenAiCompatible(chatCompletions, modelId) =>
      AgentCredentials.openAiKey(env) match
        case Some(key) => Right(new OpenAiCompatible(chatCompletions, Some(key), modelId))
        case None if AgentCredentials.isLoopback(chatCompletions) =>
          Right(new OpenAiCompatible(chatCompletions, None, modelId))
        case None =>
          Left(
            LiveRefusal.ApiKeyAbsentForRemoteHost(
              AgentCredentials.OpenAiKeyVariable,
              AgentCredentials.identityHost(chatCompletions)
            )
          )

/** Environment court for backends and credentials: the variable names, the backend selection, the
  * base-URL shape, and the nonblank rule for keys. Fail-closed throughout: an unrecognised value is
  * refused rather than defaulted, because a typo in a backend name would otherwise silently send a
  * run to the wrong provider under the wrong identity.
  */
object AgentCredentials:
  val PrimaryKeyVariable: String = "STORYMODEL4S_ANTHROPIC_API_KEY"
  val FallbackKeyVariable: String = "ANTHROPIC_API_KEY"
  val LiveVariable: String = "STORYMODEL4S_AGENT_LIVE"
  val LiveValue: String = "1"

  val BackendVariable: String = "STORYMODEL4S_AGENT_BACKEND"
  val AnthropicBackend: String = "anthropic"
  val OpenAiBackend: String = "openai"

  /** Every value [[BackendVariable]] may take; absent means [[AnthropicBackend]]. */
  val AdmittedBackends: Vector[String] = Vector(AnthropicBackend, OpenAiBackend)

  val OpenAiBaseUrlVariable: String = "STORYMODEL4S_OPENAI_BASE_URL"
  val OpenAiModelVariable: String = "STORYMODEL4S_OPENAI_MODEL"
  val OpenAiKeyVariable: String = "STORYMODEL4S_OPENAI_API_KEY"

  /** Appended to the configured base URL; the caller supplies the version prefix (`/v1`). */
  val ChatCompletionsPath: String = "/chat/completions"

  /** Hosts for which a blank key is a working configuration rather than a mistake. */
  val LoopbackHosts: Set[String] = Set("127.0.0.1", "localhost", "::1")

  private val HttpSchemes: Set[String] = Set("http", "https")

  private def nonBlank(value: String): Boolean = value.exists(c => !c.isWhitespace)

  /** The project-scoped key wins; a blank value in either variable counts as absent. */
  def apiKey(env: Map[String, String]): Option[ApiKey] =
    env
      .get(PrimaryKeyVariable)
      .filter(nonBlank)
      .orElse(env.get(FallbackKeyVariable).filter(nonBlank))
      .map(ApiKey.fromNonBlank)

  /** The OpenAI-compatible key; blank counts as absent, and absence is lawful only for loopback. */
  def openAiKey(env: Map[String, String]): Option[ApiKey] =
    env.get(OpenAiKeyVariable).filter(nonBlank).map(ApiKey.fromNonBlank)

  /** The host a backend identity carries, with the explicit port when the URL names one. */
  private[agent] def identityHost(uri: URI): String =
    val host = Option(uri.getHost).getOrElse("")
    if uri.getPort >= 0 then s"$host:${uri.getPort}" else host

  /** Whether the endpoint is served from this machine, which is what licenses a missing key. */
  private[agent] def isLoopback(uri: URI): Boolean =
    val host = Option(uri.getHost).getOrElse("").stripPrefix("[").stripSuffix("]").toLowerCase
    LoopbackHosts.contains(host)

  /** Resolve `{base}/chat/completions`; the base must be absolute, http(s), and name a host. */
  private def chatCompletions(raw: String): Either[BackendRefusal, URI] =
    val trimmed = raw.trim.reverse.dropWhile(_ == '/').reverse
    Try(new URI(trimmed)).toOption
      .filter(uri => Option(uri.getScheme).map(_.toLowerCase).exists(HttpSchemes.contains))
      .filter(uri => Option(uri.getHost).exists(nonBlank))
      .flatMap(_ => Try(new URI(trimmed + ChatCompletionsPath)).toOption)
      .toRight(BackendRefusal.BaseUrlNotHttp(OpenAiBaseUrlVariable))

  /** The backend the environment names, with the endpoint the clients need. */
  private[agent] def backendChoice(
      env: Map[String, String]
  ): Either[BackendRefusal, BackendChoice] =
    env.get(BackendVariable).map(_.trim).filter(nonBlank) match
      case None | Some(`AnthropicBackend`) =>
        Right(BackendChoice.Anthropic(ModelBackend.DefaultAnthropicModel))
      case Some(`OpenAiBackend`) =>
        for
          rawBase <- env
            .get(OpenAiBaseUrlVariable)
            .filter(nonBlank)
            .toRight(BackendRefusal.BaseUrlAbsent(OpenAiBaseUrlVariable))
          endpoint <- chatCompletions(rawBase)
          raw <- env
            .get(OpenAiModelVariable)
            .map(_.trim)
            .filter(nonBlank)
            .toRight(BackendRefusal.ModelAbsent(OpenAiModelVariable))
          model <- Either.cond(
            ModelBackend.identityScalarIsSafe(raw),
            raw,
            BackendRefusal.ModelNotIdentitySafe(
              OpenAiModelVariable,
              ModelBackend.MaxIdentityScalarLength
            )
          )
        yield BackendChoice.OpenAiCompatible(endpoint, model)
      case Some(other) =>
        Left(BackendRefusal.UnknownBackend(BackendVariable, other, AdmittedBackends))

  /** The identity a run of either mode publishes; a replay needs this and no credentials. */
  def backend(env: Map[String, String]): Either[BackendRefusal, ModelBackend] =
    backendChoice(env).map(_.backend)

  /** The live-call court; kept here so callers find every credential rule in one place. */
  def liveAuthorization(env: Map[String, String]): Either[LiveRefusal, LiveAuthorization] =
    LiveAuthorization.from(env)
