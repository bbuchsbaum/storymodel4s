package storymodel4s.provider.agent

/** An API key admitted from the environment. Opaque so it can be threaded to the client without
  * ever being rendered, compared, or logged by this module.
  */
object ApiKey:
  opaque type ApiKey = String

  private[agent] def fromNonBlank(raw: String): ApiKey = raw

  extension (key: ApiKey) private[agent] def secret: String = key

type ApiKey = ApiKey.ApiKey

/** Why a live model call is refused: the environment did not opt into spend, or has no key. */
enum LiveRefusal:
  case LiveFlagAbsent(variable: String, requiredValue: String)
  case ApiKeyAbsent(primary: String, fallback: String)

  def message: String = this match
    case LiveFlagAbsent(variable, value) => s"live calls need $variable=$value"
    case ApiKeyAbsent(primary, fallback) => s"no nonblank $primary or $fallback in the environment"

/** Proof that the environment opted into spend and supplied a key. Only `LiveAuthorization.from`
  * mints one, so a client cannot be built from a key alone or from same-package code that skips the
  * environment court.
  */
final class LiveAuthorization private (val apiKey: ApiKey)

object LiveAuthorization:
  /** A live call needs both the explicit flag and a key; either absence is a typed refusal. */
  def from(env: Map[String, String]): Either[LiveRefusal, LiveAuthorization] =
    if !env.get(AgentCredentials.LiveVariable).contains(AgentCredentials.LiveValue) then
      Left(LiveRefusal.LiveFlagAbsent(AgentCredentials.LiveVariable, AgentCredentials.LiveValue))
    else
      AgentCredentials
        .apiKey(env)
        .toRight(
          LiveRefusal.ApiKeyAbsent(
            AgentCredentials.PrimaryKeyVariable,
            AgentCredentials.FallbackKeyVariable
          )
        )
        .map(key => new LiveAuthorization(key))

/** Environment court for credentials: the variable names, and the nonblank rule for keys. */
object AgentCredentials:
  val PrimaryKeyVariable: String = "STORYMODEL4S_ANTHROPIC_API_KEY"
  val FallbackKeyVariable: String = "ANTHROPIC_API_KEY"
  val LiveVariable: String = "STORYMODEL4S_AGENT_LIVE"
  val LiveValue: String = "1"

  private def nonBlank(value: String): Boolean = value.exists(c => !c.isWhitespace)

  /** The project-scoped key wins; a blank value in either variable counts as absent. */
  def apiKey(env: Map[String, String]): Option[ApiKey] =
    env
      .get(PrimaryKeyVariable)
      .filter(nonBlank)
      .orElse(env.get(FallbackKeyVariable).filter(nonBlank))
      .map(ApiKey.fromNonBlank)

  /** The live-call court; kept here so callers find every credential rule in one place. */
  def liveAuthorization(env: Map[String, String]): Either[LiveRefusal, LiveAuthorization] =
    LiveAuthorization.from(env)
