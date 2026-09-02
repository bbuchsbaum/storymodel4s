package storymodel4s.provider.agent

import storymodel4s.core.Checksum
import storymodel4s.provider.parser.ProviderFailureCode

/** Length-prefixed digest framing, so two different part vectors can never share a preimage. */
private[agent] object AgentIdentity:
  private val FramingVersion = "storymodel4s.provider.agent.identity/length-prefixed-v1"

  def digest(domain: String, parts: Iterable[String]): Checksum =
    Checksum.ofText(
      (FramingVersion +: domain +: parts.toVector).iterator
        .map(part => s"${part.length}:$part")
        .mkString
    )

/** Every failure code this module can emit, admitted once so no call site can mint a blank one. */
object AgentFailureCodes:
  private def admitted(literal: String): ProviderFailureCode =
    ProviderFailureCode
      .from(literal)
      .fold(reason => throw new IllegalStateException(s"$literal: $reason"), identity)

  val RecordingMissing: ProviderFailureCode = admitted("recording-missing")
  val RecordingCorrupt: ProviderFailureCode = admitted("recording-corrupt")
  val RecordingUnwritable: ProviderFailureCode = admitted("recording-unwritable")
  val RateLimited: ProviderFailureCode = admitted("rate-limited")
  val ConnectionFailed: ProviderFailureCode = admitted("connection-failed")
  val ReplyUndecodable: ProviderFailureCode = admitted("reply-undecodable")
  val RequestUndecodable: ProviderFailureCode = admitted("request-undecodable")
  val RequestSchemaMismatch: ProviderFailureCode = admitted("request-schema-mismatch")
  val RuntimeFingerprintMismatch: ProviderFailureCode = admitted("runtime-fingerprint-mismatch")
  val ConfigMismatch: ProviderFailureCode = admitted("config-mismatch")
  val TextChecksumMismatch: ProviderFailureCode = admitted("text-checksum-mismatch")
  val PenmanUnparsable: ProviderFailureCode = admitted("penman-unparsable")
  val MarkerNotOnConcept: ProviderFailureCode = admitted("marker-not-on-concept")
  val MaxTokens: ProviderFailureCode = admitted("max-tokens")
  val UnexpectedStop: ProviderFailureCode = admitted("unexpected-stop-reason")
  val Refusal: ProviderFailureCode = admitted("refusal")
  val Abstain: ProviderFailureCode = admitted("abstain")

  /** A service error keeps its HTTP status; the body never enters a code. */
  def serviceError(status: Int): ProviderFailureCode = admitted(s"service-error-$status")

  /** Every literal above, so a test can prove each one was admitted rather than thrown on. */
  val all: Vector[ProviderFailureCode] = Vector(
    RecordingMissing,
    RecordingCorrupt,
    RecordingUnwritable,
    RateLimited,
    ConnectionFailed,
    ReplyUndecodable,
    RequestUndecodable,
    RequestSchemaMismatch,
    RuntimeFingerprintMismatch,
    ConfigMismatch,
    TextChecksumMismatch,
    PenmanUnparsable,
    MarkerNotOnConcept,
    MaxTokens,
    UnexpectedStop,
    Refusal,
    Abstain
  )
