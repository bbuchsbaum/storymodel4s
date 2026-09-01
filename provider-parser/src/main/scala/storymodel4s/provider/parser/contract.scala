package storymodel4s.provider.parser

import storymodel4s.acquire.PromptPackageRef
import storymodel4s.core.*

/** Stable identity of one sentence-level parser request inside and across batches. */
object ParserRequestId extends OpaqueId("ParserRequestId")
type ParserRequestId = ParserRequestId.T

/** Why an atlas-bound parser input could not be constructed. */
enum ParserInputError:
  case UnknownSentence(id: SurfaceUnitId)
  case WrongUnitKind(id: SurfaceUnitId, found: SurfaceUnitKind)
  case SentenceHasNoTokens(id: SurfaceUnitId)
  case TokenEscapesSentence(sentence: SurfaceUnitId, token: SurfaceUnitId)
  case DuplicateRequestId(id: ParserRequestId)

  def message: String = this match
    case UnknownSentence(id)      => s"unknown sentence ${id.value}"
    case WrongUnitKind(id, found) => s"surface unit ${id.value} is $found, not Sentence"
    case SentenceHasNoTokens(id)  => s"sentence ${id.value} has no atlas tokens"
    case TokenEscapesSentence(sentence, token) =>
      s"token ${token.value} escapes sentence ${sentence.value}"
    case DuplicateRequestId(id) => s"duplicate parser request ${id.value}"

/** One authoritative token derived from the source atlas with its exact bytes and span. */
final class AtlasToken private (
    val id: SurfaceUnitId,
    val span: TextSpan,
    val text: String
):
  override def equals(other: Any): Boolean = other match
    case that: AtlasToken => id == that.id && span == that.span && text == that.text
    case _                => false

  override def hashCode(): Int = (id, span, text).hashCode

  override def toString: String =
    s"AtlasToken(${id.value}, span=$span, text=${Checksum.ofText(text).short()})"

object AtlasToken:
  private[parser] def fromAtlas(atlas: SurfaceAtlas, token: SurfaceUnit): AtlasToken =
    new AtlasToken(token.id, token.span, atlas.text(token))

/** An unforgeable parser input obtained only from an existing atlas sentence.
  *
  * Why a non-case class with a private constructor: provider tokenisation must not be able to
  * masquerade as the atlas axis through generated `copy` or `fromProduct` construction.
  */
final class ParserSentenceInput private (
    val id: ParserRequestId,
    val sentenceId: SurfaceUnitId,
    val sentenceSpan: TextSpan,
    val text: String,
    val textChecksum: Checksum,
    val tokens: Vector[AtlasToken],
    val checksum: Checksum
):
  override def equals(other: Any): Boolean = other match
    case that: ParserSentenceInput =>
      id == that.id && sentenceId == that.sentenceId && sentenceSpan == that.sentenceSpan &&
      text == that.text && textChecksum == that.textChecksum && tokens == that.tokens &&
      checksum == that.checksum
    case _ => false

  override def hashCode(): Int =
    (id, sentenceId, sentenceSpan, text, textChecksum, tokens, checksum).hashCode

  override def toString: String =
    s"ParserSentenceInput(${id.value}, sentence=${sentenceId.value}, tokens=${tokens.size}, " +
      s"checksum=${checksum.short()})"

object ParserSentenceInput:
  /** Construct a request from one sentence and the token axis of the same atlas. */
  def fromAtlas(
      id: ParserRequestId,
      atlas: SurfaceAtlas,
      sentenceId: SurfaceUnitId
  ): Either[ParserInputError, ParserSentenceInput] =
    atlas.byId.get(sentenceId) match
      case None => Left(ParserInputError.UnknownSentence(sentenceId))
      case Some(unit) if unit.kind != SurfaceUnitKind.Sentence =>
        Left(ParserInputError.WrongUnitKind(sentenceId, unit.kind))
      case Some(sentence) =>
        val overlapping = atlas.unitsOverlapping(sentence.span, SurfaceUnitKind.Token)
        overlapping.find(token => !sentence.span.contains(token.span)) match
          case Some(token) =>
            Left(ParserInputError.TokenEscapesSentence(sentenceId, token.id))
          case None if overlapping.isEmpty =>
            Left(ParserInputError.SentenceHasNoTokens(sentenceId))
          case None =>
            val text = atlas.text(sentence)
            val tokens =
              overlapping.map(token => AtlasToken.fromAtlas(atlas, token))
            val textChecksum = Checksum.ofText(text)
            val checksum = ParserIdentity.digest(
              "parser-sentence/v2",
              Vector(
                id.value,
                sentenceId.value,
                sentence.span.start.toString,
                sentence.span.endExclusive.toString,
                textChecksum.hex
              ) ++ tokens.zipWithIndex.flatMap { (token, index) =>
                Vector(
                  "token",
                  index.toString,
                  token.id.value,
                  token.span.start.toString,
                  token.span.endExclusive.toString,
                  Checksum.ofText(token.text).hex
                )
              }
            )
            Right(
              new ParserSentenceInput(
                id,
                sentenceId,
                sentence.span,
                text,
                textChecksum,
                tokens,
                checksum
              )
            )

/** A validated batch with unique request identities. */
final class ParserBatch private (val inputs: Vector[ParserSentenceInput]):
  def ids: Vector[ParserRequestId] = inputs.map(_.id)
  def isEmpty: Boolean = inputs.isEmpty
  def size: Int = inputs.size

  override def equals(other: Any): Boolean = other match
    case that: ParserBatch => inputs == that.inputs
    case _                 => false

  override def hashCode(): Int = inputs.hashCode
  override def toString: String = s"ParserBatch(size=$size)"

object ParserBatch:
  /** Reject duplicate request ids before any transport or cache work occurs. */
  def validated(inputs: Vector[ParserSentenceInput]): Either[ParserInputError, ParserBatch] =
    inputs.groupBy(_.id).collectFirst { case (id, values) if values.size > 1 => id } match
      case Some(id) => Left(ParserInputError.DuplicateRequestId(id))
      case None     => Right(new ParserBatch(inputs))

  private[parser] def unsafe(inputs: Vector[ParserSentenceInput]): ParserBatch =
    new ParserBatch(inputs)

/** A runtime component whose bytes and redistribution basis must be pinned before execution. */
enum RuntimeComponent:
  case ParserSource
  case ParserPackage
  case Checkpoint
  case BaseImage
  case Dependency(name: String)

  def render: String = this match
    case ParserSource     => "parser-source"
    case ParserPackage    => "parser-package"
    case Checkpoint       => "checkpoint"
    case BaseImage        => "base-image"
    case Dependency(name) => s"dependency:$name"

object RuntimeComponent:
  private[parser] def isValid(component: RuntimeComponent): Boolean = component match
    case RuntimeComponent.Dependency(name) => name.exists(c => !c.isWhitespace)
    case RuntimeComponent.ParserSource | RuntimeComponent.ParserPackage |
        RuntimeComponent.Checkpoint | RuntimeComponent.BaseImage =>
      true

  private[parser] def canonicalParts(component: RuntimeComponent): Vector[String] =
    component match
      case RuntimeComponent.ParserSource     => Vector("parser-source")
      case RuntimeComponent.ParserPackage    => Vector("parser-package")
      case RuntimeComponent.Checkpoint       => Vector("checkpoint")
      case RuntimeComponent.BaseImage        => Vector("base-image")
      case RuntimeComponent.Dependency(name) => Vector("dependency", name)

/** Platform identity used when a completely pinned runtime cannot execute on the current host. */
enum RuntimePlatform:
  case DarwinArm64
  case DarwinX8664
  case LinuxArm64
  case LinuxX8664
  case Custom(namespace: String, label: String)

  def render: String = this match
    case DarwinArm64           => "darwin-arm64"
    case DarwinX8664           => "darwin-x86_64"
    case LinuxArm64            => "linux-arm64"
    case LinuxX8664            => "linux-x86_64"
    case Custom(namespace, id) => s"$namespace:$id"

object RuntimePlatform:
  private[parser] def isValid(platform: RuntimePlatform): Boolean = platform match
    case RuntimePlatform.Custom(namespace, label) =>
      namespace.exists(c => !c.isWhitespace) && label.exists(c => !c.isWhitespace)
    case RuntimePlatform.DarwinArm64 | RuntimePlatform.DarwinX8664 | RuntimePlatform.LinuxArm64 |
        RuntimePlatform.LinuxX8664 =>
      true

  private[parser] def canonicalParts(platform: RuntimePlatform): Vector[String] = platform match
    case RuntimePlatform.DarwinArm64           => Vector("darwin-arm64")
    case RuntimePlatform.DarwinX8664           => Vector("darwin-x86_64")
    case RuntimePlatform.LinuxArm64            => Vector("linux-arm64")
    case RuntimePlatform.LinuxX8664            => Vector("linux-x86_64")
    case RuntimePlatform.Custom(namespace, id) => Vector("custom", namespace, id)

/** Host resource whose measured availability can prevent a pinned runtime from materializing. */
enum RuntimeResource:
  case DiskBytes
  case MemoryBytes
  case Custom(namespace: String, label: String)

  def render: String = this match
    case DiskBytes             => "disk-bytes"
    case MemoryBytes           => "memory-bytes"
    case Custom(namespace, id) => s"$namespace:$id"

object RuntimeResource:
  private[parser] def isValid(resource: RuntimeResource): Boolean = resource match
    case RuntimeResource.Custom(namespace, label) =>
      namespace.exists(c => !c.isWhitespace) && label.exists(c => !c.isWhitespace)
    case RuntimeResource.DiskBytes | RuntimeResource.MemoryBytes => true

  private[parser] def canonicalParts(resource: RuntimeResource): Vector[String] = resource match
    case RuntimeResource.DiskBytes             => Vector("disk-bytes")
    case RuntimeResource.MemoryBytes           => Vector("memory-bytes")
    case RuntimeResource.Custom(namespace, id) => Vector("custom", namespace, id)

/** Stable category for a validated parser-runtime unavailability fact. */
enum ParserSetupFailureKind:
  case RuntimeDependencyUnpinned
  case ArtifactMissing
  case PlatformIncompatible
  case ResourceBudgetInsufficient
  case InvalidRuntimeField

/** Typed runtime field identity prevents a blank diagnostic path from becoming a fact. */
enum ParserRuntimeField:
  case ArtifactVersion
  case ArtifactSource
  case ArtifactLicenseEvidence
  case DependencyName
  case Provider
  case Model
  case RuntimeVersion
  case DuplicateComponent(component: RuntimeComponent)
  case TimeoutMillis
  case ParameterKey
  case SdkVersion
  case PromptPackageName
  case PromptPackageVersion
  case ResultSchema
  case PromptTemplateVersion

  def render: String = this match
    case ArtifactVersion               => "artifact/version"
    case ArtifactSource                => "artifact/source"
    case ArtifactLicenseEvidence       => "artifact/license-evidence"
    case DependencyName                => "component/dependency"
    case Provider                      => "provider"
    case Model                         => "model"
    case RuntimeVersion                => "runtime/version"
    case DuplicateComponent(component) => s"duplicate/${component.render}"
    case TimeoutMillis                 => "timeoutMillis"
    case ParameterKey                  => "params/key"
    case SdkVersion                    => "remote/sdk-version"
    case PromptPackageName             => "remote/prompt-package/name"
    case PromptPackageVersion          => "remote/prompt-package/version"
    case ResultSchema                  => "remote/result-schema"
    case PromptTemplateVersion         => "remote/prompt-template-version"

  private[parser] def canonicalParts: Vector[String] = this match
    case ArtifactVersion               => Vector("artifact-version")
    case ArtifactSource                => Vector("artifact-source")
    case ArtifactLicenseEvidence       => Vector("artifact-license-evidence")
    case DependencyName                => Vector("dependency-name")
    case Provider                      => Vector("provider")
    case Model                         => Vector("model")
    case RuntimeVersion                => Vector("runtime-version")
    case DuplicateComponent(component) =>
      Vector("duplicate-component") ++ RuntimeComponent.canonicalParts(component)
    case TimeoutMillis         => Vector("timeout-millis")
    case ParameterKey          => Vector("parameter-key")
    case SdkVersion            => Vector("sdk-version")
    case PromptPackageName     => Vector("prompt-package-name")
    case PromptPackageVersion  => Vector("prompt-package-version")
    case ResultSchema          => Vector("result-schema")
    case PromptTemplateVersion => Vector("prompt-template-version")

/** Why a requested runtime-unavailability fact could not be admitted. */
enum ParserSetupFailureAdmissionError:
  case MissingComponentsEmpty
  case InvalidComponent(index: Int)
  case DuplicateComponent(index: Int)
  case SupportedPlatformsEmpty
  case InvalidCurrentPlatform
  case InvalidSupportedPlatform(index: Int)
  case DuplicateSupportedPlatform(index: Int)
  case CurrentPlatformIncluded
  case InvalidResource
  case NegativeRequired
  case NegativeAvailable
  case BudgetIsSufficient

  def message: String = this match
    case MissingComponentsEmpty            => "at least one missing component is required"
    case InvalidComponent(index)           => s"runtime component $index is invalid"
    case DuplicateComponent(index)         => s"runtime component $index is duplicated"
    case SupportedPlatformsEmpty           => "at least one supported platform is required"
    case InvalidCurrentPlatform            => "the current platform is invalid"
    case InvalidSupportedPlatform(index)   => s"supported platform $index is invalid"
    case DuplicateSupportedPlatform(index) => s"supported platform $index is duplicated"
    case CurrentPlatformIncluded           => "the current platform is listed as supported"
    case InvalidResource                   => "the runtime resource is invalid"
    case NegativeRequired                  => "the required resource quantity is negative"
    case NegativeAvailable                 => "the available resource quantity is negative"
    case BudgetIsSufficient                => "required resources do not exceed availability"

/** Why a parser runtime is unavailable before a provider call can lawfully occur.
  *
  * The sealed value is privately constructed so its public name is always true: missing component
  * sets are nonempty and distinct, incompatible platforms exclude the current host, and resource
  * shortages have nonnegative quantities with `required > available`.
  */
sealed trait ParserSetupFailure:
  def kind: ParserSetupFailureKind
  def missingComponents: Vector[RuntimeComponent]
  def currentPlatform: Option[RuntimePlatform]
  def supportedPlatforms: Vector[RuntimePlatform]
  def resource: Option[RuntimeResource]
  def required: Option[Long]
  def available: Option[Long]
  def invalidField: Option[ParserRuntimeField]
  def message: String
  def checksum: Checksum
  private[parser] def render: String

object ParserSetupFailure:
  private final class Validated(
      val kind: ParserSetupFailureKind,
      val missingComponents: Vector[RuntimeComponent],
      val currentPlatform: Option[RuntimePlatform],
      val supportedPlatforms: Vector[RuntimePlatform],
      val resource: Option[RuntimeResource],
      val required: Option[Long],
      val available: Option[Long],
      val invalidField: Option[ParserRuntimeField],
      val message: String,
      identityParts: Vector[String]
  ) extends ParserSetupFailure:
    val checksum: Checksum =
      ParserIdentity.digest("parser-setup-failure/v2", identityParts)
    val render: String = s"runtime-unavailable:${checksum.hex}"

    override def equals(other: Any): Boolean = other match
      case that: Validated =>
        kind == that.kind && missingComponents == that.missingComponents &&
        currentPlatform == that.currentPlatform &&
        supportedPlatforms == that.supportedPlatforms && resource == that.resource &&
        required == that.required && available == that.available &&
        invalidField == that.invalidField && checksum == that.checksum
      case _ => false

    override def hashCode(): Int =
      (
        kind,
        missingComponents,
        currentPlatform,
        supportedPlatforms,
        resource,
        required,
        available,
        invalidField,
        checksum
      ).hashCode
    override def toString: String = s"ParserSetupFailure($render)"

  /** Admit a nonempty, distinct set of valid components known to be unpinned. */
  def runtimeDependencyUnpinned(
      missing: Vector[RuntimeComponent]
  ): Either[ParserSetupFailureAdmissionError, ParserSetupFailure] =
    missingComponents(
      ParserSetupFailureKind.RuntimeDependencyUnpinned,
      "runtime dependencies unpinned",
      "runtime-unpinned",
      missing
    )

  /** Admit a nonempty, distinct set of valid runtime artifacts known to be missing. */
  def artifactMissing(
      missing: Vector[RuntimeComponent]
  ): Either[ParserSetupFailureAdmissionError, ParserSetupFailure] =
    missingComponents(
      ParserSetupFailureKind.ArtifactMissing,
      "runtime artifacts missing",
      "runtime-artifact-missing",
      missing
    )

  /** Admit a valid current platform excluded from a nonempty, distinct supported set. */
  def platformIncompatible(
      current: RuntimePlatform,
      supported: Vector[RuntimePlatform]
  ): Either[ParserSetupFailureAdmissionError, ParserSetupFailure] =
    if !RuntimePlatform.isValid(current) then
      Left(ParserSetupFailureAdmissionError.InvalidCurrentPlatform)
    else if supported.isEmpty then Left(ParserSetupFailureAdmissionError.SupportedPlatformsEmpty)
    else
      supported.indexWhere(platform => !RuntimePlatform.isValid(platform)) match
        case index if index >= 0 =>
          Left(ParserSetupFailureAdmissionError.InvalidSupportedPlatform(index))
        case _ =>
          firstDuplicateIndex(supported) match
            case Some(index) =>
              Left(ParserSetupFailureAdmissionError.DuplicateSupportedPlatform(index))
            case None if supported.contains(current) =>
              Left(ParserSetupFailureAdmissionError.CurrentPlatformIncluded)
            case None =>
              val canonical = supported.sortBy(platform =>
                ParserIdentity.orderKey(RuntimePlatform.canonicalParts(platform))
              )
              Right(
                new Validated(
                  ParserSetupFailureKind.PlatformIncompatible,
                  Vector.empty,
                  Some(current),
                  canonical,
                  None,
                  None,
                  None,
                  None,
                  s"runtime platform ${current.render} is not one of " +
                    canonical.map(_.render).mkString(","),
                  Vector("platform-incompatible") ++
                    RuntimePlatform.canonicalParts(current) ++
                    canonical.zipWithIndex.flatMap { (platform, index) =>
                      Vector(s"supported/$index") ++ RuntimePlatform.canonicalParts(platform)
                    }
                )
              )

  /** Admit a measured nonnegative resource shortage. */
  def resourceBudgetInsufficient(
      resource: RuntimeResource,
      required: Long,
      available: Long
  ): Either[ParserSetupFailureAdmissionError, ParserSetupFailure] =
    if !RuntimeResource.isValid(resource) then
      Left(ParserSetupFailureAdmissionError.InvalidResource)
    else if required < 0 then Left(ParserSetupFailureAdmissionError.NegativeRequired)
    else if available < 0 then Left(ParserSetupFailureAdmissionError.NegativeAvailable)
    else if required <= available then Left(ParserSetupFailureAdmissionError.BudgetIsSufficient)
    else
      Right(
        new Validated(
          ParserSetupFailureKind.ResourceBudgetInsufficient,
          Vector.empty,
          None,
          Vector.empty,
          Some(resource),
          Some(required),
          Some(available),
          None,
          s"runtime ${resource.render} insufficient: required=$required available=$available",
          Vector("resource-budget-insufficient") ++
            RuntimeResource.canonicalParts(resource) ++
            Vector(required.toString, available.toString)
        )
      )

  /** Record one closed, typed runtime field whose supplied value was invalid. */
  def invalidRuntimeField(field: ParserRuntimeField): ParserSetupFailure =
    new Validated(
      ParserSetupFailureKind.InvalidRuntimeField,
      Vector.empty,
      None,
      Vector.empty,
      None,
      None,
      None,
      Some(field),
      s"invalid runtime field: ${field.render}",
      Vector("invalid-runtime-field") ++ field.canonicalParts
    )

  private def missingComponents(
      kind: ParserSetupFailureKind,
      messagePrefix: String,
      identityTag: String,
      missing: Vector[RuntimeComponent]
  ): Either[ParserSetupFailureAdmissionError, ParserSetupFailure] =
    if missing.isEmpty then Left(ParserSetupFailureAdmissionError.MissingComponentsEmpty)
    else
      missing.indexWhere(component => !RuntimeComponent.isValid(component)) match
        case index if index >= 0 => Left(ParserSetupFailureAdmissionError.InvalidComponent(index))
        case _                   =>
          firstDuplicateIndex(missing) match
            case Some(index) => Left(ParserSetupFailureAdmissionError.DuplicateComponent(index))
            case None        =>
              val canonical = missing.sortBy(component =>
                ParserIdentity.orderKey(RuntimeComponent.canonicalParts(component))
              )
              val rendered = canonical.map(_.render).mkString(",")
              Right(
                new Validated(
                  kind,
                  canonical,
                  None,
                  Vector.empty,
                  None,
                  None,
                  None,
                  None,
                  s"$messagePrefix: $rendered",
                  Vector(identityTag) ++ canonical.zipWithIndex.flatMap { (component, index) =>
                    Vector(s"component/$index") ++
                      RuntimeComponent.canonicalParts(component)
                  }
                )
              )

  private[parser] def knownRuntimeDependencyUnpinned(
      missing: Vector[RuntimeComponent]
  ): ParserSetupFailure =
    runtimeDependencyUnpinned(missing).fold(
      _ => invalidRuntimeField(ParserRuntimeField.DependencyName),
      identity
    )

  private def firstDuplicateIndex[A](values: Vector[A]): Option[Int] =
    values.indices.find(index => values.indexOf(values(index)) != index)

/** One immutable external artifact participating in a parser runtime. */
final class RuntimeArtifact private (
    val component: RuntimeComponent,
    val version: String,
    val source: String,
    val checksum: Checksum,
    val licenseEvidence: String
):
  override def equals(other: Any): Boolean = other match
    case that: RuntimeArtifact =>
      component == that.component && version == that.version && source == that.source &&
      checksum == that.checksum && licenseEvidence == that.licenseEvidence
    case _ => false

  override def hashCode(): Int =
    (component, version, source, checksum, licenseEvidence).hashCode

  override def toString: String =
    s"RuntimeArtifact(${component.render}, version=$version, checksum=${checksum.short()})"

object RuntimeArtifact:
  /** Admit an artifact only when its identity and licence evidence are nonblank. */
  def from(
      component: RuntimeComponent,
      version: String,
      source: String,
      checksum: Checksum,
      licenseEvidence: String
  ): Either[ParserSetupFailure, RuntimeArtifact] =
    val fields = Vector(
      ParserRuntimeField.ArtifactVersion -> version,
      ParserRuntimeField.ArtifactSource -> source,
      ParserRuntimeField.ArtifactLicenseEvidence -> licenseEvidence
    )
    fields.collectFirst {
      case (field, value) if !value.exists(c => !c.isWhitespace) => field
    } match
      case Some(field) => Left(ParserSetupFailure.invalidRuntimeField(field))
      case None if !RuntimeComponent.isValid(component) =>
        Left(ParserSetupFailure.invalidRuntimeField(ParserRuntimeField.DependencyName))
      case None =>
        Right(new RuntimeArtifact(component, version, source, checksum, licenseEvidence))

/** Identity every executable parser runtime publishes, so request envelopes, provider-call
  * receipts, and cache keys bind to one derived fingerprint whichever kind of runtime answers.
  *
  * Why a sealed trait over two private-constructor classes: a locally pinned runtime and a remote
  * model differ in what can be pinned (checksummed bytes versus a provider-asserted model id), and
  * that difference must stay visible through `weightsPinned` rather than be laundered into one
  * shape that implies both were pinned the same way.
  */
sealed trait RuntimeIdentity:
  def provider: String
  def model: String
  def version: String
  def fingerprint: Fingerprint
  def checksum: Checksum

  /** True only when every weight-bearing artifact was checksummed before execution. */
  def weightsPinned: Boolean

  /** Present only when the runtime's behaviour is fixed by a prompt package rather than weights. */
  def promptTemplateVersion: Option[PromptTemplateVersion]

/** A complete, content-addressed parser runtime, including the external wrapper version; no
  * provider can be `Ready` without one.
  */
final class PinnedRuntime private (
    val provider: String,
    val model: String,
    val version: String,
    val artifacts: Vector[RuntimeArtifact],
    val fingerprint: Fingerprint,
    val checksum: Checksum
) extends RuntimeIdentity:
  val weightsPinned: Boolean = true
  val promptTemplateVersion: Option[PromptTemplateVersion] = None

  override def equals(other: Any): Boolean = other match
    case that: PinnedRuntime =>
      provider == that.provider && model == that.model && version == that.version &&
      artifacts == that.artifacts && fingerprint == that.fingerprint && checksum == that.checksum
    case _ => false

  override def hashCode(): Int =
    (provider, model, version, artifacts, fingerprint, checksum).hashCode

  override def toString: String =
    s"PinnedRuntime($provider,$model,$version,checksum=${checksum.short()})"

object PinnedRuntime:
  private val CoreRequired: Set[RuntimeComponent] = Set(
    RuntimeComponent.ParserSource,
    RuntimeComponent.ParserPackage,
    RuntimeComponent.Checkpoint,
    RuntimeComponent.BaseImage
  )

  /** Require every core runtime surface plus any provider-specific dependencies exactly once. */
  def from(
      provider: String,
      model: String,
      version: String,
      artifacts: Vector[RuntimeArtifact],
      additionalRequired: Set[RuntimeComponent] = Set.empty
  ): Either[ParserSetupFailure, PinnedRuntime] =
    val scalars = Vector(
      ParserRuntimeField.Provider -> provider,
      ParserRuntimeField.Model -> model,
      ParserRuntimeField.RuntimeVersion -> version
    )
    scalars.collectFirst {
      case (field, value) if !value.exists(c => !c.isWhitespace) => field
    } match
      case Some(field) => Left(ParserSetupFailure.invalidRuntimeField(field))
      case None if additionalRequired.exists(component => !RuntimeComponent.isValid(component)) =>
        Left(ParserSetupFailure.invalidRuntimeField(ParserRuntimeField.DependencyName))
      case None =>
        val byComponent = artifacts.groupBy(_.component)
        byComponent.collectFirst { case (component, values) if values.size > 1 => component } match
          case Some(component) =>
            Left(
              ParserSetupFailure.invalidRuntimeField(
                ParserRuntimeField.DuplicateComponent(component)
              )
            )
          case None =>
            val missing = (CoreRequired ++ additionalRequired)
              .diff(byComponent.keySet)
              .toVector
              .sortBy(component =>
                ParserIdentity.orderKey(RuntimeComponent.canonicalParts(component))
              )
            if missing.nonEmpty then
              Left(ParserSetupFailure.knownRuntimeDependencyUnpinned(missing))
            else
              val ordered = artifacts.sortBy(artifact =>
                ParserIdentity.orderKey(RuntimeComponent.canonicalParts(artifact.component))
              )
              val checksum = ParserIdentity.digest(
                "parser-runtime/v2",
                Vector(provider, model, version) ++ ordered.zipWithIndex.flatMap {
                  case (artifact, index) =>
                    Vector("artifact", index.toString) ++
                      RuntimeComponent.canonicalParts(artifact.component) ++
                      Vector(
                        artifact.version,
                        artifact.source,
                        artifact.checksum.hex,
                        artifact.licenseEvidence
                      )
                }
              )
              val fingerprint = Fingerprint.unsafe(s"parser-runtime:${checksum.hex}")
              Right(
                new PinnedRuntime(provider, model, version, ordered, fingerprint, checksum)
              )

/** A remote model runtime whose weights cannot be pinned; identity is provider, model id, prompt
  * package, prompt text, result schema, and SDK version, and every one of them is digested.
  *
  * Why a non-case class with a private constructor: the fingerprint is a claim about the other
  * fields, so it must be derived here and never supplied by a caller. `weightsPinned` is fixed to
  * `false` because no checksummed checkpoint exists for a hosted model; a receipt minted under this
  * runtime therefore never reads as if bytes had been pinned.
  */
final class RemoteRuntime private (
    val provider: String,
    val model: String,
    val sdkVersion: String,
    val promptPackage: PromptPackageRef,
    val promptTextChecksum: Checksum,
    val resultSchema: String,
    val promptVersion: PromptTemplateVersion,
    val checksum: Checksum,
    val fingerprint: Fingerprint
) extends RuntimeIdentity:
  /** The SDK version stands in for the wrapper version a pinned runtime records. */
  def version: String = sdkVersion

  /** Remote weights cannot be pinned; identity is provider, model id, prompt package, and SDK. */
  val weightsPinned: Boolean = false

  val promptTemplateVersion: Option[PromptTemplateVersion] = Some(promptVersion)

  override def equals(other: Any): Boolean = other match
    case that: RemoteRuntime =>
      provider == that.provider && model == that.model && sdkVersion == that.sdkVersion &&
      promptPackage == that.promptPackage && promptTextChecksum == that.promptTextChecksum &&
      resultSchema == that.resultSchema && promptVersion == that.promptVersion &&
      checksum == that.checksum && fingerprint == that.fingerprint
    case _ => false

  override def hashCode(): Int =
    (
      provider,
      model,
      sdkVersion,
      promptPackage,
      promptTextChecksum,
      resultSchema,
      promptVersion,
      checksum,
      fingerprint
    ).hashCode

  override def toString: String =
    s"RemoteRuntime($provider,$model,$sdkVersion,prompt=${promptPackage.name}@" +
      s"${promptPackage.version},checksum=${checksum.short()})"

object RemoteRuntime:
  /** Admit a remote runtime only when every identity scalar is nonblank; derive its fingerprint. */
  def from(
      provider: String,
      model: String,
      sdkVersion: String,
      promptPackage: PromptPackageRef,
      promptTextChecksum: Checksum,
      resultSchema: String
  ): Either[ParserSetupFailure, RemoteRuntime] =
    val scalars = Vector(
      ParserRuntimeField.Provider -> provider,
      ParserRuntimeField.Model -> model,
      ParserRuntimeField.SdkVersion -> sdkVersion,
      ParserRuntimeField.PromptPackageName -> promptPackage.name,
      ParserRuntimeField.PromptPackageVersion -> promptPackage.version,
      ParserRuntimeField.ResultSchema -> resultSchema
    )
    scalars.collectFirst {
      case (field, value) if !value.exists(c => !c.isWhitespace) => field
    } match
      case Some(field) => Left(ParserSetupFailure.invalidRuntimeField(field))
      case None        =>
        val rendered =
          s"${promptPackage.name}@${promptPackage.version}#${promptPackage.checksum.hex}"
        PromptTemplateVersion
          .from(rendered)
          .left
          .map(_ =>
            ParserSetupFailure.invalidRuntimeField(ParserRuntimeField.PromptTemplateVersion)
          )
          .map { promptVersion =>
            val checksum = ParserIdentity.digest(
              "parser-remote-runtime/v1",
              Vector(
                provider,
                model,
                sdkVersion,
                "prompt-package",
                promptPackage.name,
                promptPackage.version,
                promptPackage.checksum.hex,
                "prompt-text",
                promptTextChecksum.hex,
                "result-schema",
                resultSchema,
                "weights-pinned",
                "false"
              )
            )
            val fingerprint = Fingerprint.unsafe(s"remote-runtime:${checksum.hex}")
            new RemoteRuntime(
              provider,
              model,
              sdkVersion,
              promptPackage,
              promptTextChecksum,
              resultSchema,
              promptVersion,
              checksum,
              fingerprint
            )
          }

/** Runtime availability is explicit: no unpinned, missing, incompatible, or oversized runtime can
  * silently execute, and a remote model is never mistaken for a pinned one.
  */
enum ParserRuntime:
  case Ready(runtime: PinnedRuntime)
  case Remote(runtime: RemoteRuntime)
  case Unavailable(reason: ParserSetupFailure)

/** Declared interpretation of alignment indices rendered into provider PENMAN. */
enum ParserAlignmentDialect:
  /** Every comma-separated number is one exact token index; no range semantics are permitted. */
  case ExplicitIndexListV1

  /** IBM ISI markers use two-number half-open ranges and are never admitted directly. */
  case IbmIsiRangeV1

  def wireName: String = this match
    case ExplicitIndexListV1 => "explicit-index-list/v1"
    case IbmIsiRangeV1       => "ibm-isi-range/v1"

object ParserAlignmentDialect:
  private[parser] def fromWire(raw: String): Option[ParserAlignmentDialect] =
    ParserAlignmentDialect.values.find(_.wireName == raw)

/** Validated decoding and transport settings whose checksum participates in every cache key. */
final class ParserConfig private (
    val params: Map[String, String],
    val seed: Option[Long],
    val timeoutMillis: Long,
    val checksum: Checksum
):
  override def equals(other: Any): Boolean = other match
    case that: ParserConfig =>
      params == that.params && seed == that.seed && timeoutMillis == that.timeoutMillis &&
      checksum == that.checksum
    case _ => false

  override def hashCode(): Int = (params, seed, timeoutMillis, checksum).hashCode

object ParserConfig:
  /** Reject non-positive timeouts and blank parameter names without rewriting values. */
  def from(
      params: Map[String, String],
      seed: Option[Long],
      timeoutMillis: Long
  ): Either[ParserSetupFailure, ParserConfig] =
    if timeoutMillis <= 0 then
      Left(ParserSetupFailure.invalidRuntimeField(ParserRuntimeField.TimeoutMillis))
    else
      params.keys.find(key => !key.exists(c => !c.isWhitespace)) match
        case Some(_) =>
          Left(ParserSetupFailure.invalidRuntimeField(ParserRuntimeField.ParameterKey))
        case None =>
          val checksum = ParserIdentity.digest(
            "parser-config/v2",
            Vector(
              "timeout",
              timeoutMillis.toString,
              "seed",
              seed.fold("none")(_ => "some")
            ) ++
              seed.toVector.map(_.toString) ++
              params.toVector.sortBy(_._1).zipWithIndex.flatMap { case ((key, value), index) =>
                Vector("param", index.toString, key, value)
              }
          )
          Right(new ParserConfig(params, seed, timeoutMillis, checksum))

/** Sanitized process-level failure; raw stdout/stderr never appears in the error value. */
enum TransportFailure:
  case Timeout(limitMillis: Long)
  case NonZeroExit(exitCode: Int, stderrChecksum: Checksum)
  case Materialization(errorChecksum: Checksum)
  case Io(code: ProviderFailureCode)

  def render: String = this match
    case Timeout(limit)         => s"timeout:$limit"
    case NonZeroExit(code, sum) => s"exit:$code:${sum.hex}"
    case Materialization(sum)   => s"materialization:${sum.hex}"
    case Io(code)               => s"io:${code.value}"

  private[parser] def canonicalParts: Vector[String] = this match
    case Timeout(limit)         => Vector("timeout", limit.toString)
    case NonZeroExit(code, sum) => Vector("non-zero-exit", code.toString, sum.hex)
    case Materialization(sum)   => Vector("materialization", sum.hex)
    case Io(code)               => Vector("io", code.value)

/** Effect-polymorphic byte boundary implemented by a later subprocess or HTTP adapter. */
trait ParserTransport[F[_]]:
  def exchange(requestJson: String, timeoutMillis: Long): F[Either[TransportFailure, String]]
