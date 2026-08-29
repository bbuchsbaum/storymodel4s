package storymodel4s.core

/** Identifies the exact extractor/model/version that produced a piece of evidence.
  *
  * Convention: `provider:model:version:hash`, but any whitespace-free string is accepted so that
  * human annotators and rules can be fingerprinted too.
  */
object Fingerprint extends OpaqueId("Fingerprint")
type Fingerprint = Fingerprint.T

/** Version identity of the prompt template used for a provider call.
  *
  * Why: presence is fingerprint-significant, so an admitted value must carry information rather
  * than duplicate the absent case with an empty or whitespace-only string.
  */
object PromptTemplateVersion:
  opaque type PromptTemplateVersion = String

  /** Validates a prompt-template version without rewriting its provider-supplied bytes. */
  def from(raw: String): Either[DomainError, PromptTemplateVersion] =
    if raw.exists(char => !char.isWhitespace) then Right(raw)
    else Left(DomainError.InvalidFormat("PromptTemplateVersion", raw, "must not be blank"))

  /** Validating constructor for literals and trusted fixtures; throws when `raw` is blank. */
  def unsafe(raw: String): PromptTemplateVersion =
    from(raw).fold(error => throw new IllegalArgumentException(error.message), identity)

  extension (version: PromptTemplateVersion) def value: String = version

type PromptTemplateVersion = PromptTemplateVersion.PromptTemplateVersion

/** One call to an acquisition provider (rule, local model, remote LLM, human import).
  *
  * Why a non-case class: generated `fromProduct` accepts an untyped `Product` and can therefore
  * bypass the opaque prompt-version boundary. The explicit `apply` and `copy` retain ordinary
  * construction ergonomics while requiring a validated [[PromptTemplateVersion]].
  */
final class ProviderCall private (
    val provider: String,
    val model: String,
    val version: String,
    val promptTemplateVersion: Option[PromptTemplateVersion],
    val inputChecksum: Checksum,
    val outputChecksum: Checksum,
    val params: Map[String, String],
    val seed: Option[Long],
    val cached: Boolean
):
  /** Rebuilds a provider call without exposing an unchecked prompt-version field. */
  def copy(
      provider: String = provider,
      model: String = model,
      version: String = version,
      promptTemplateVersion: Option[PromptTemplateVersion] = promptTemplateVersion,
      inputChecksum: Checksum = inputChecksum,
      outputChecksum: Checksum = outputChecksum,
      params: Map[String, String] = params,
      seed: Option[Long] = seed,
      cached: Boolean = cached
  ): ProviderCall =
    ProviderCall(
      provider,
      model,
      version,
      promptTemplateVersion,
      inputChecksum,
      outputChecksum,
      params,
      seed,
      cached
    )

  override def equals(other: Any): Boolean = other match
    case that: ProviderCall =>
      provider == that.provider &&
      model == that.model &&
      version == that.version &&
      promptTemplateVersion == that.promptTemplateVersion &&
      inputChecksum == that.inputChecksum &&
      outputChecksum == that.outputChecksum &&
      params == that.params &&
      seed == that.seed &&
      cached == that.cached
    case _ => false

  override def hashCode(): Int =
    (
      provider,
      model,
      version,
      promptTemplateVersion,
      inputChecksum,
      outputChecksum,
      params,
      seed,
      cached
    ).hashCode

  override def toString: String =
    s"ProviderCall($provider,$model,$version,$promptTemplateVersion,$inputChecksum," +
      s"$outputChecksum,$params,$seed,$cached)"

object ProviderCall:
  /** Constructs a call whose optional prompt version has already passed its lexical boundary. */
  def apply(
      provider: String,
      model: String,
      version: String,
      promptTemplateVersion: Option[PromptTemplateVersion],
      inputChecksum: Checksum,
      outputChecksum: Checksum,
      params: Map[String, String],
      seed: Option[Long],
      cached: Boolean
  ): ProviderCall =
    new ProviderCall(
      provider,
      model,
      version,
      promptTemplateVersion,
      inputChecksum,
      outputChecksum,
      params,
      seed,
      cached
    )

/** Everything needed to reproduce or audit how a claim was produced. */
final case class Provenance(
    calls: Vector[ProviderCall],
    softwareVersion: String,
    configHash: Checksum
)

object Provenance:
  /** Provenance for deterministic library code with no provider calls. */
  def deterministic(softwareVersion: String, configHash: Checksum): Provenance =
    Provenance(Vector.empty, softwareVersion, configHash)

  /** Provenance for hand-authored fixtures and human adjudication. */
  def human(annotator: String, softwareVersion: String): Provenance =
    Provenance(Vector.empty, softwareVersion, Checksum.ofText(s"human:$annotator"))

/** Content-addressed receipt of a build. */
final case class BuildReceipt(
    storyId: StoryId,
    sourceChecksum: Checksum,
    schemaVersion: String,
    stages: Vector[(StageId, Checksum)],
    createdAtEpochMillis: Long
):
  /** Digest over everything except the timestamp, so equal builds have equal receipts. */
  def contentChecksum: Checksum =
    ContentAddress.digest(
      Vector(storyId.value, sourceChecksum.hex, schemaVersion) ++
        stages.map((s, c) => s"${s.value}=${c.hex}")
    )
