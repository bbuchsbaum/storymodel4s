package storymodel4s.provider.agent

import cats.data.Validated
import java.nio.charset.StandardCharsets
import storymodel4s.acquire.{
  PermittedOperation,
  PromptPackageManifest,
  PromptPackageRef,
  PromptRole,
  StandardsRef
}
import storymodel4s.core.Checksum
import storymodel4s.provider.parser.ParserEnvelope

/** Why the prompt package could not be admitted from the classpath. */
enum PromptPackageLoadError:
  case ResourceMissing(path: String)
  case ManifestInvalid(messages: Vector[String])

  def message: String = this match
    case ResourceMissing(path)     => s"prompt resource $path is not on the classpath"
    case ManifestInvalid(messages) => s"prompt manifest invalid: ${messages.mkString("; ")}"

/** The versioned prompt program the remote parser runs under: its exact text, the text's checksum,
  * and the manifest whose checksum names the package in receipts and cache keys.
  *
  * Why a non-case class: the text checksum is a claim about the text, so both are fixed here and
  * cannot be paired by a caller.
  */
final class AgentPromptPackage private (
    val systemPrompt: String,
    val promptTextChecksum: Checksum,
    val manifest: PromptPackageManifest
):
  def ref: PromptPackageRef = manifest.ref

  override def equals(other: Any): Boolean = other match
    case that: AgentPromptPackage =>
      systemPrompt == that.systemPrompt && promptTextChecksum == that.promptTextChecksum &&
      manifest == that.manifest
    case _ => false

  override def hashCode(): Int = (systemPrompt, promptTextChecksum, manifest).hashCode

  override def toString: String =
    s"AgentPromptPackage(${manifest.name}@${manifest.version}, text=${promptTextChecksum.short()})"

object AgentPromptPackage:
  val ResourcePath: String = "prompts/penman-parse.v1.txt"
  val Name: String = "penman-parse"
  val Version: String = "v1"
  val Role: PromptRole = PromptRole.Custom("provider-agent", "penman-parse")

  /** Load the shipped prompt text and manifest it. */
  def load(): Either[PromptPackageLoadError, AgentPromptPackage] =
    Option(getClass.getClassLoader.getResourceAsStream(ResourcePath)) match
      case None         => Left(PromptPackageLoadError.ResourceMissing(ResourcePath))
      case Some(stream) =>
        val text =
          try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
          finally stream.close()
        fromText(text)

  /** Manifest an explicit prompt text; tests use it to prove identity moves with the text. */
  private[agent] def fromText(text: String): Either[PromptPackageLoadError, AgentPromptPackage] =
    val manifest = PromptPackageManifest.of(
      name = Name,
      version = Version,
      role = Role,
      inputSchemaId = ParserEnvelope.RequestSchema,
      outputSchemaId = ParserEnvelope.ResultSchema,
      permittedOperations = Vector(
        PermittedOperation.AddConcept,
        PermittedOperation.AddRelation,
        PermittedOperation.Abstain
      ),
      prohibitedInferences = Vector(
        "no coreference beyond the single sentence",
        "no causal or temporal relation the sentence does not state",
        "no alignment marker on a token outside the sentence"
      ),
      standardsRefs = Vector(StandardsRef("amr-guidelines", "1.2.6", "penman-notation")),
      exampleIds = Vector("fewshot-want", "fewshot-negation", "fewshot-name-transfer"),
      counterexampleIds = Vector("fewshot-abstain"),
      abstentionRules = Vector(
        "emit the single line ABSTAIN when the sentence carries no propositional content"
      ),
      selfCheck = Vector(
        "every concept carries exactly one tilde-e marker",
        "every marker index is within the token list",
        "every variable is defined exactly once",
        "nothing precedes or follows the graph"
      ),
      benchmarkSuiteId = "provider-agent/RecordedReplaySuite"
    )
    manifest match
      case Validated.Valid(admitted) =>
        Right(new AgentPromptPackage(text, Checksum.ofText(text), admitted))
      case Validated.Invalid(errors) =>
        Left(PromptPackageLoadError.ManifestInvalid(errors.toChain.toVector.map(_.message)))
