package storymodel4s.view

import java.nio.charset.StandardCharsets

import cats.data.ValidatedNec
import cats.syntax.all.*
import storymodel4s.acquire.*
import storymodel4s.core.*

/** Stable identity of one requested report. */
object ReportId extends OpaqueId("ReportId")
type ReportId = ReportId.T

/** Stable identity of one requested renderer-independent projection. */
object ProjectionRequestId extends OpaqueId("ProjectionRequestId")
type ProjectionRequestId = ProjectionRequestId.T

/** Stable identity of one emitted or promised artifact. */
object ArtifactId extends OpaqueId("ArtifactId")
type ArtifactId = ArtifactId.T

/** Stable identity of a report renderer implementation. */
object RendererId extends OpaqueId("RendererId")
type RendererId = RendererId.T

/** Stable identity of a report software build. */
object OutputSoftwareId extends OpaqueId("OutputSoftwareId")
type OutputSoftwareId = OutputSoftwareId.T

/** Stable identity of a selected bundle profile. */
object BundleProfileId extends OpaqueId("BundleProfileId")
type BundleProfileId = BundleProfileId.T

/** Stable identity of a conformance-profile schema. */
object ProfileSchemaId extends OpaqueId("ProfileSchemaId")
type ProfileSchemaId = ProfileSchemaId.T

/** Stable identity of a profile verifier implementation. */
object ProfileVerifierId extends OpaqueId("ProfileVerifierId")
type ProfileVerifierId = ProfileVerifierId.T

/** Stable identity of one enumerated profile court. */
object ProfileCourtId extends OpaqueId("ProfileCourtId")
type ProfileCourtId = ProfileCourtId.T

/** Nonnegative output count used in projection accounting. */
object OutputCount:
  opaque type OutputCount = Int

  val Zero: OutputCount = 0

  def from(value: Int): Either[DomainError, OutputCount] =
    if value < 0 then
      Left(DomainError.InvalidFormat("OutputCount", value.toString, "expected nonnegative"))
    else Right(value)

  def unsafe(value: Int): OutputCount =
    from(value).fold(error => throw new IllegalArgumentException(error.message), identity)

  extension (count: OutputCount) def value: Int = count
type OutputCount = OutputCount.OutputCount

/** Closed artifact authority roles with an explicit custom namespace boundary. */
enum ArtifactRole:
  case OriginalSource
  case CanonicalSource
  case InvocationResult
  case SemanticModel
  case BrowserPreview
  case TextPreview
  case ReportAsset(id: ArtifactId)
  case ProjectionPacket(id: ArtifactId)
  case OptionalReport(id: ArtifactId)
  case DeclaredLossExport(id: ArtifactId)
  case Custom(namespace: OutputNamespace, label: OutputLabel, id: ArtifactId)

  def wireName: String = this match
    case OriginalSource        => "original_source"
    case CanonicalSource       => "canonical_source"
    case InvocationResult      => "invocation_result"
    case SemanticModel         => "semantic_model"
    case BrowserPreview        => "browser_preview"
    case TextPreview           => "text_preview"
    case ReportAsset(_)        => "report_asset"
    case ProjectionPacket(_)   => "projection_packet"
    case OptionalReport(_)     => "optional_report"
    case DeclaredLossExport(_) => "declared_loss_export"
    case Custom(_, _, _)       => "custom"

/** Whether a role is mandatory under the selected profile. */
enum ArtifactRequirement:
  case Required
  case Conditional(profile: BundleProfileId)
  case Optional

/** Whether a report communicates only invocation state or scientific view state. */
enum ReportAuthority:
  case InvocationOnly
  case ScientificView

/** Closed report family with a typed extension boundary. */
enum ReportKind:
  case BrowserPreview
  case TextPreview
  case Custom(namespace: OutputNamespace, label: OutputLabel)

/** Why a requested report was deliberately not attempted. */
enum NotAttemptedReason:
  case UpstreamUnavailable
  case DependencyUnsupported
  case PolicyRefused
  case VerificationNotRun
  case Custom(namespace: OutputNamespace, label: OutputLabel)

/** Why a projection produced fewer visible marks than its scientific inventory. */
enum SuppressionReason:
  case Horizon
  case Zoom
  case EndpointVisibility
  case LayoutPolicy
  case Custom(namespace: OutputNamespace, label: OutputLabel)

/** Why a promised artifact has no bytes by semantic contract. */
enum SemanticAbsenceReason:
  case SourceNotConstructed
  case SemanticNotValidated
  case NotApplicableToOutcome
  case Custom(namespace: OutputNamespace, label: OutputLabel)

/** Selected conformance profile recorded by the manifest. */
enum BundleProfile:
  case LocalOpen
  case Custom(id: BundleProfileId)

  def sortKey: String = this match
    case LocalOpen  => "local_open"
    case Custom(id) => s"custom:${id.value}"

/** Exact artifact identity certified by a profile evaluation. */
final case class ProfileArtifactBinding(
    role: ArtifactRole,
    path: BundlePath,
    mediaType: MediaTypeId,
    checksum: Checksum
)

/** Closed result of one named profile court, with evidence for failures. */
enum ProfileCourtDisposition:
  case Passed
  case Failed(code: OutputFailureCode, evidence: OutputReceiptId)

/** One court result inside a profile disposition receipt. */
final case class ProfileCourtOutcome(
    court: ProfileCourtId,
    disposition: ProfileCourtDisposition
)

/** Decision content bound into a profile receipt rather than inferred from its enclosing branch. */
enum ProfileReceiptDecision:
  case Satisfied
  case Failed(code: OutputFailureCode)
  case NotAttempted(reason: NotAttemptedReason)

/** Auditable evaluation receipt shared by satisfied, failed, and unattempted profiles. */
final case class ProfileReceipt(
    id: OutputReceiptId,
    schema: ProfileSchemaId,
    verifier: ProfileVerifierId,
    execution: OutputReceiptId,
    policy: OutputSchemaId,
    decision: ProfileReceiptDecision,
    prerequisites: Vector[OutputReceiptId],
    preview: Option[ProfileArtifactBinding],
    requiredAssets: Vector[ProfileArtifactBinding],
    courts: Vector[ProfileCourtOutcome]
)

/** A satisfied local-open receipt issued by a governed profile verifier.
  *
  * [[ProfileReceipt]] remains the wire claim shared by all disposition branches. V1 deliberately
  * has no issuer: receipt-shaped data cannot establish that a court ran. The type remains in the
  * disposition vocabulary so a later governed verifier can add issuance without changing the wire
  * model.
  */
final class VerifiedProfileReceipt private (val receipt: ProfileReceipt):
  override def equals(other: Any): Boolean = other match
    case that: VerifiedProfileReceipt => receipt == that.receipt
    case _                            => false
  override def hashCode(): Int = receipt.hashCode
  override def toString: String = s"VerifiedProfileReceipt(${receipt.id.value})"

object VerifiedProfileReceipt:
  val LocalOpenRequiredCourts: Vector[ProfileCourtId] =
    Vector(
      ProfileCourtId.unsafe("browser-preview-produced"),
      ProfileCourtId.unsafe("direct-file-open")
    )

  /** Refuse data-only promotion until a governed court runner can issue this proof. */
  def localOpen(claim: ProfileReceipt): Either[DomainError, VerifiedProfileReceipt] =
    Left(
      DomainError.InvariantViolation(
        "output/profile/local-open-verification",
        s"satisfied local-open issuance is deferred; receipt ${claim.id.value} is an untrusted claim"
      )
    )

/** Certification result for one requested bundle profile. */
enum ProfileDisposition:
  case Satisfied(receipt: VerifiedProfileReceipt)
  case Failed(error: OutputFailure, receipt: ProfileReceipt)
  case NotAttempted(reason: NotAttemptedReason, receipt: ProfileReceipt)

/** Exactly one certification outcome for one requested profile. */
final case class BundleProfileOutcome(
    profile: BundleProfile,
    disposition: ProfileDisposition
)

/** Portable relative bundle path that cannot escape or collide by case. */
final class BundlePath private (val value: String):
  override def equals(other: Any): Boolean = other match
    case that: BundlePath => value == that.value
    case _                => false
  override def hashCode(): Int = value.hashCode
  override def toString: String = value

object BundlePath:
  private val Segment = "^[A-Za-z0-9._-]+$".r
  private val WindowsDevices =
    (Set("CON", "PRN", "AUX", "NUL") ++
      (1 to 9).flatMap(i => Vector(s"COM$i", s"LPT$i"))).toSet

  /** Validate a portable ASCII path independently of the enclosing directory. */
  def from(value: String): Either[DomainError, BundlePath] =
    val segments = value.split("/", -1).toVector
    val invalidDevice = segments.exists { segment =>
      val stem = segment.takeWhile(_ != '.').toUpperCase
      WindowsDevices.contains(stem)
    }
    val valid =
      value.nonEmpty &&
        !value.startsWith("/") &&
        !value.contains('\\') &&
        !value.contains(':') &&
        segments.forall(segment =>
          segment.nonEmpty &&
            segment != "." &&
            segment != ".." &&
            !segment.endsWith(".") &&
            !segment.endsWith(" ") &&
            Segment.matches(segment)
        ) &&
        !invalidDevice
    if valid then Right(new BundlePath(value))
    else Left(DomainError.InvalidFormat("BundlePath", value, "non-portable relative path"))

  /** Construct a path literal known to satisfy the portable grammar. */
  def unsafe(value: String): BundlePath =
    from(value).fold(error => throw new IllegalArgumentException(error.message), identity)

/** Exact emitted artifact identity and bytes metadata. */
final class ArtifactRef private (
    val id: ArtifactId,
    val role: ArtifactRole,
    val mediaType: MediaTypeId,
    val schemaVersion: Option[OutputSchemaId],
    val byteLength: Long,
    val checksum: Checksum
):
  private def parts = (id, role, mediaType, schemaVersion, byteLength, checksum)

  override def equals(other: Any): Boolean = other match
    case that: ArtifactRef => parts == that.parts
    case _                 => false
  override def hashCode(): Int = parts.hashCode
  override def toString: String =
    s"ArtifactRef(${id.value}, role=${role.wireName}, bytes=$byteLength)"

object ArtifactRef:
  /** Build exact metadata from the bytes that will be emitted. */
  def fromBytes(
      id: ArtifactId,
      role: ArtifactRole,
      mediaType: MediaTypeId,
      schemaVersion: Option[OutputSchemaId],
      bytes: Array[Byte]
  ): ArtifactRef =
    new ArtifactRef(
      id,
      role,
      mediaType,
      schemaVersion,
      bytes.length.toLong,
      Checksum.ofBytes(bytes)
    )

  /** Reconstruct metadata from a wire value while preserving nonnegative length. */
  def of(
      id: ArtifactId,
      role: ArtifactRole,
      mediaType: MediaTypeId,
      schemaVersion: Option[OutputSchemaId],
      byteLength: Long,
      checksum: Checksum
  ): Either[DomainError, ArtifactRef] =
    if byteLength < 0 then
      Left(
        DomainError.InvalidFormat(
          "ArtifactRef.byteLength",
          byteLength.toString,
          "expected nonnegative"
        )
      )
    else Right(new ArtifactRef(id, role, mediaType, schemaVersion, byteLength, checksum))

/** Complete source and semantic artifact references carried by the result root. */
final class ScientificArtifactRefs private (
    val originalSource: Option[ArtifactRef],
    val canonicalSource: Option[ArtifactRef],
    val semanticModel: Option[ArtifactRef]
):
  private def parts = (originalSource, canonicalSource, semanticModel)

  override def equals(other: Any): Boolean = other match
    case that: ScientificArtifactRefs => parts == that.parts
    case _                            => false
  override def hashCode(): Int = parts.hashCode
  override def toString: String =
    "ScientificArtifactRefs(original=" + originalSource.nonEmpty +
      ", canonical=" + canonicalSource.nonEmpty +
      ", semantic=" + semanticModel.nonEmpty + ")"

object ScientificArtifactRefs:
  /** Close complete artifact metadata against source construction and semantic state. */
  def of[Id](
      acquisition: AcquisitionAccount[Id],
      originalSource: Option[ArtifactRef],
      canonicalSource: Option[ArtifactRef],
      semanticModel: Option[ArtifactRef]
  ): ValidatedNec[DomainError, ScientificArtifactRefs] =
    val refs = new ScientificArtifactRefs(originalSource, canonicalSource, semanticModel)
    validate(acquisition, refs).as(refs)

  private[view] def validate[Id](
      acquisition: AcquisitionAccount[Id],
      refs: ScientificArtifactRefs
  ): ValidatedNec[DomainError, Unit] =
    (
      sourceRefs(acquisition.source, refs),
      semanticRef(acquisition.semantic, refs.semanticModel)
    ).mapN((_, _) => ())

  private def sourceRefs(
      source: SourceOutcome,
      refs: ScientificArtifactRefs
  ): ValidatedNec[DomainError, Unit] =
    val valid = source match
      case SourceOutcome.Constructed(identities) =>
        refs.originalSource.exists(artifact =>
          artifact.role == ArtifactRole.OriginalSource &&
            artifact.mediaType == identities.original.mediaType &&
            artifact.schemaVersion.isEmpty &&
            artifact.byteLength == identities.original.byteLength &&
            artifact.checksum == identities.original.checksum
        ) &&
        refs.canonicalSource.exists(artifact =>
          artifact.role == ArtifactRole.CanonicalSource &&
            artifact.schemaVersion.isEmpty &&
            artifact.byteLength == identities.canonicalByteLength &&
            artifact.checksum == identities.canonicalChecksum
        )
      case SourceOutcome.Refused(progress, _) =>
        val originalValid = progress match
          case RefusedSourceProgress.BeforeIntake       => refs.originalSource.isEmpty
          case RefusedSourceProgress.Admitted(identity) =>
            refs.originalSource.exists(artifact =>
              artifact.role == ArtifactRole.OriginalSource &&
                artifact.mediaType == identity.mediaType &&
                artifact.schemaVersion.isEmpty &&
                artifact.byteLength == identity.byteLength &&
                artifact.checksum == identity.checksum
            )
          case RefusedSourceProgress.Decoded(identity, _) =>
            refs.originalSource.exists(artifact =>
              artifact.role == ArtifactRole.OriginalSource &&
                artifact.mediaType == identity.mediaType &&
                artifact.schemaVersion.isEmpty &&
                artifact.byteLength == identity.byteLength &&
                artifact.checksum == identity.checksum
            )
        originalValid && refs.canonicalSource.isEmpty
    require(
      valid,
      DomainError.InvariantViolation(
        "output/scientific-artifacts/source",
        "source artifact references disagree with source construction"
      )
    )

  private def semanticRef(
      semantic: SemanticOutcome,
      artifact: Option[ArtifactRef]
  ): ValidatedNec[DomainError, Unit] =
    val valid = semantic match
      case SemanticOutcome.Validated(model) =>
        artifact.exists(ref =>
          ref.role == ArtifactRole.SemanticModel &&
            ref.schemaVersion.contains(model.schemaVersion) &&
            ref.checksum == model.artifactChecksum
        )
      case SemanticOutcome.Partial(_, draft) =>
        draft.fold(artifact.isEmpty)(model =>
          artifact.exists(ref =>
            ref.role == ArtifactRole.SemanticModel &&
              ref.schemaVersion.contains(model.schemaVersion) &&
              ref.checksum == model.artifactChecksum
          )
        )
      case SemanticOutcome.Refused(_) => artifact.isEmpty
    require(
      valid,
      DomainError.InvariantViolation(
        "output/scientific-artifacts/semantic",
        "semantic artifact reference disagrees with semantic outcome"
      )
    )

  private def require(
      condition: Boolean,
      error: => DomainError
  ): ValidatedNec[DomainError, Unit] =
    if condition then ().validNec else error.invalidNec

/** Canonical target identity bytes supplied explicitly for each output target-id type. */
trait OutputTargetIdentity[Id]:
  def canonicalBytes(value: Id): Vector[Byte]

object OutputTargetIdentity:
  /** Exact UTF-8 bytes; no platform charset, `toString`, or generic JSON fallback. */
  given OutputTargetIdentity[String] with
    def canonicalBytes(value: String): Vector[Byte] =
      value.getBytes(StandardCharsets.UTF_8).toVector

  private val Domain = "story-output-target-identity/v1\u0000".getBytes(StandardCharsets.UTF_8)

  private[view] def checksum[Id](value: Id)(using identity: OutputTargetIdentity[Id]): Checksum =
    Checksum.ofBytes(Domain ++ identity.canonicalBytes(value).toArray)

/** Complete deterministic identity of the scientific roots consumed by one lowering request. */
final class ReportInputIdentity private (val checksum: Checksum):
  override def equals(other: Any): Boolean = other match
    case that: ReportInputIdentity => checksum == that.checksum
    case _                         => false
  override def hashCode(): Int = checksum.hashCode
  override def toString: String = s"ReportInputIdentity(${checksum.short()})"

object ReportInputIdentity:
  def forReport[Id: OutputTargetIdentity](
      acquisition: AcquisitionAccount[Id],
      artifacts: ScientificArtifactRefs,
      basis: Option[AdmittedViewBasis],
      request: ReportRequest
  ): Either[DomainError, ReportInputIdentity] =
    derive(acquisition, artifacts, basis, reportRequestParts(request))

  def forProjection[Id: OutputTargetIdentity](
      acquisition: AcquisitionAccount[Id],
      artifacts: ScientificArtifactRefs,
      basis: Option[AdmittedViewBasis],
      request: ProjectionRequest
  ): Either[DomainError, ReportInputIdentity] =
    derive(
      acquisition,
      artifacts,
      basis,
      Vector("target=projection", s"request=${request.id.value}") ++
        request.requiredPayloads.toVector.map(_.value).sorted.map(id => s"required-payload=$id")
    )

  private[view] def validateTargetIdentities[Id: OutputTargetIdentity](
      acquisition: AcquisitionAccount[Id]
  ): Either[DomainError, Unit] =
    val ids = acquisition.universe match
      case TargetUniverse.Established(value) => value.members ++ acquisition.targets.map(_.id)
      case TargetUniverse.Unestablished(_)   => acquisition.targets.map(_.id)
    val distinct = ids.distinct
    val byChecksum = distinct.groupBy(OutputTargetIdentity.checksum(_))
    val collisions = byChecksum.values.filter(_.distinct.size > 1).toVector
    if collisions.isEmpty then Right(())
    else
      Left(
        DomainError.InvariantViolation(
          "output/report/target-identity",
          s"${collisions.size} canonical target identities collapse unequal in-scope ids"
        )
      )

  private def derive[Id: OutputTargetIdentity](
      acquisition: AcquisitionAccount[Id],
      artifacts: ScientificArtifactRefs,
      basis: Option[AdmittedViewBasis],
      targetParts: Vector[String]
  ): Either[DomainError, ReportInputIdentity] =
    validateTargetIdentities(acquisition).map { _ =>
      new ReportInputIdentity(
        framedDigest(
          Vector("story-output-report-input/v1", s"invocation=${acquisition.invocationId.value}") ++
            sourceParts(acquisition.source) ++
            universeParts(acquisition.universe) ++
            semanticParts(acquisition.semantic) ++
            acquisition.targets.zipWithIndex.flatMap { case (target, index) =>
              Vector(
                s"target[$index].identity=${OutputTargetIdentity.checksum(target.id).hex}",
                s"target[$index].disposition=${target.disposition}"
              ) ++ target.payloads.map(payload => s"target[$index].payload=${payload.value}")
            } ++
            acquisition.payloads.flatMap(payloadParts) ++
            acquisition.buildReceipt.toVector.flatMap(buildParts) ++
            acquisition.viewAuthority.toVector.flatMap(authorityParts) ++
            artifactParts(artifacts) ++
            basis.toVector.flatMap(basisParts) ++
            targetParts
        )
      )
    }

  private def sourceParts(source: SourceOutcome): Vector[String] = source match
    case SourceOutcome.Constructed(value) =>
      Vector(
        "source=constructed",
        s"story=${value.storyId.value}",
        s"original-bytes=${value.original.byteLength}",
        s"original-checksum=${value.original.checksum.hex}",
        s"original-media=${value.original.mediaType.value}",
        if value.original.declaredCharset.isDefined then "original-declared-charset.some"
        else "original-declared-charset.none",
        s"original-selected-charset=${value.original.selectedCharset.value}",
        s"original-bom=${value.original.bom}",
        s"original-intake-receipt=${value.original.intakeReceipt.value}",
        s"decoded-utf16=${value.decodedUtf16Length}",
        s"decoded-checksum=${value.decodedChecksum.hex}",
        s"canonical-policy=${value.canonicalPolicy.value}",
        s"canonical-bytes=${value.canonicalByteLength}",
        s"canonical-utf16=${value.canonicalUtf16Length}",
        s"canonical-checksum=${value.canonicalChecksum.hex}",
        s"decode-receipt=${value.decodeReceipt.id.value}",
        s"decoder=${value.decodeReceipt.decoder.value}",
        s"canonical-receipt=${value.canonicalizationReceipt.value}"
      ) ++ value.original.declaredCharset.toVector.map(value =>
        s"original-declared-charset.value=${value.value}"
      )
    case SourceOutcome.Refused(progress, failure) =>
      Vector("source=refused") ++ refusedProgressParts(progress) ++ failureParts("source", failure)

  private def refusedProgressParts(progress: RefusedSourceProgress): Vector[String] = progress match
    case RefusedSourceProgress.BeforeIntake       => Vector("source-progress=before-intake")
    case RefusedSourceProgress.Admitted(original) =>
      Vector("source-progress=admitted") ++ originalParts("refused-original", original)
    case RefusedSourceProgress.Decoded(original, decoded) =>
      Vector("source-progress=decoded") ++
        originalParts("refused-original", original) ++
        Vector(
          s"refused-decoded-utf16=${decoded.utf16Length}",
          s"refused-decoded-checksum=${decoded.checksum.hex}",
          s"refused-decode-receipt=${decoded.decodeReceipt.id.value}",
          s"refused-decoder=${decoded.decodeReceipt.decoder.value}"
        )

  private def originalParts(prefix: String, value: OriginalSourceIdentity): Vector[String] =
    Vector(
      s"$prefix-bytes=${value.byteLength}",
      s"$prefix-checksum=${value.checksum.hex}",
      s"$prefix-media=${value.mediaType.value}",
      if value.declaredCharset.isDefined then s"$prefix-declared-charset.some"
      else s"$prefix-declared-charset.none",
      s"$prefix-selected-charset=${value.selectedCharset.value}",
      s"$prefix-bom=${value.bom}",
      s"$prefix-intake-receipt=${value.intakeReceipt.value}"
    ) ++ value.declaredCharset.toVector.map(charset =>
      s"$prefix-declared-charset.value=${charset.value}"
    )

  private def universeParts[Id: OutputTargetIdentity](
      universe: TargetUniverse[Id]
  ): Vector[String] = universe match
    case TargetUniverse.Established(value) =>
      Vector(
        "universe=established",
        s"universe-definition=${value.definitionIdentity.value}",
        s"universe-size=${value.members.size}"
      ) ++ value.members.zipWithIndex.map { case (member, index) =>
        s"universe-member[$index]=${OutputTargetIdentity.checksum(member).hex}"
      }
    case TargetUniverse.Unestablished(failure) =>
      Vector(
        "universe=unestablished",
        universeFailureKind(failure.reason)
      ) ++ universeFailureValues(failure.reason) ++ Vector(
        s"universe-receipt=${failure.receipt.value}",
        if failure.stage.isDefined then "universe-stage.some" else "universe-stage.none"
      ) ++ failure.stage.toVector.map(stage => s"universe-stage.value=${stage.value}")

  private def semanticParts(semantic: SemanticOutcome): Vector[String] = semantic match
    case SemanticOutcome.Validated(model)     => modelParts("validated", model)
    case SemanticOutcome.Partial(gaps, draft) =>
      Vector("semantic=partial") ++
        gaps.toVector.zipWithIndex.flatMap { case (gap, index) =>
          Vector(
            resultGapKind(s"gap[$index]", gap.kind),
            s"gap[$index].receipt=${gap.receipt.value}",
            if gap.payload.isDefined then s"gap[$index].payload.some"
            else s"gap[$index].payload.none"
          ) ++ resultGapValues(s"gap[$index]", gap.kind) ++
            gap.payload.toVector.map(value => s"gap[$index].payload.value=${value.value}")
        } ++
        draft.toVector.flatMap(modelParts("draft", _))
    case SemanticOutcome.Refused(errors) =>
      Vector("semantic=refused") ++ errors.toVector.zipWithIndex.flatMap { case (error, index) =>
        failureParts(s"semantic-error[$index]", error)
      }

  private def modelParts(prefix: String, model: SemanticModelRef): Vector[String] =
    Vector(
      s"semantic=$prefix",
      s"model-story=${model.storyId.value}",
      s"model-source=${model.sourceChecksum.hex}",
      s"model-schema=${model.schemaVersion.value}",
      s"model-artifact=${model.artifactChecksum.hex}"
    )

  private def payloadParts(payload: OutputPayload): Vector[String] = payload match
    case OutputPayload.Known(ref) =>
      Vector(
        "payload=known",
        s"payload.id=${ref.id.value}",
        s"payload.schema=${ref.schemaId.value}",
        s"payload.checksum=${ref.checksum.hex}"
      )
    case OutputPayload.Unsupported(extension) =>
      Vector(
        "payload=unsupported",
        s"payload.id=${extension.id.value}",
        s"payload.namespace=${extension.namespace.value}",
        s"payload.schema=${extension.schemaId.value}",
        s"payload.checksum=${extension.checksum.hex}",
        s"payload.requirement=${extension.requirement}"
      )

  private def failureParts(prefix: String, error: OutputFailure): Vector[String] =
    Vector(
      failureCodeKind(prefix, error.code),
      s"$prefix-receipt=${error.receipt.value}",
      if error.stage.isDefined then s"$prefix-stage.some" else s"$prefix-stage.none"
    ) ++ failureCodeValues(prefix, error.code) ++
      error.stage.toVector.map(stage => s"$prefix-stage.value=${stage.value}") ++
      error.evidence.zipWithIndex.map { case (receipt, index) =>
        s"$prefix-evidence[$index]=${receipt.value}"
      }

  private def buildParts(value: ExtendedBuildReceipt): Vector[String] =
    Vector(
      s"build-story=${value.receipt.storyId.value}",
      s"build-source=${value.receipt.sourceChecksum.hex}",
      s"build-schema=${value.receipt.schemaVersion}"
    ) ++ value.receipt.stages.zipWithIndex.flatMap { case ((stage, checksum), index) =>
      Vector(
        s"build-stage[$index].id=${stage.value}",
        s"build-stage[$index].checksum=${checksum.hex}"
      )
    } ++ value.stages.zipWithIndex.flatMap { case (stage, index) =>
      Vector(
        s"stage-record[$index].id=${stage.stage.value}",
        s"stage-record[$index].key=${stage.key.checksum.hex}",
        s"stage-record[$index].cached=${stage.cached}"
      ) ++ stage.inputs.zipWithIndex.map { case (checksum, inputIndex) =>
        s"stage-record[$index].input[$inputIndex]=${checksum.hex}"
      } ++ stage.outputs.zipWithIndex.map { case (checksum, outputIndex) =>
        s"stage-record[$index].output[$outputIndex]=${checksum.hex}"
      } ++ stage.calls.zipWithIndex.flatMap { case (call, callIndex) =>
        providerCallParts(s"stage-record[$index].call[$callIndex]", call)
      }
    } ++ value.layerCoverage.toVector.sortBy(_._1.value).map { case (layer, coverage) =>
      coordinate("layer-coverage", layer.value, coverage.toString)
    }

  private def providerCallParts(prefix: String, call: ProviderCall): Vector[String] =
    Vector(
      s"$prefix.provider=${call.provider}",
      s"$prefix.model=${call.model}",
      s"$prefix.version=${call.version}",
      if call.promptTemplateVersion.isDefined then s"$prefix.prompt.some"
      else s"$prefix.prompt.none",
      s"$prefix.input=${call.inputChecksum.hex}",
      s"$prefix.output=${call.outputChecksum.hex}",
      if call.seed.isDefined then s"$prefix.seed.some" else s"$prefix.seed.none",
      s"$prefix.cached=${call.cached}"
    ) ++ call.promptTemplateVersion.toVector.map(version =>
      s"$prefix.prompt.value=${version.value}"
    ) ++ call.seed.toVector.map(seed => s"$prefix.seed.value=$seed") ++
      call.params.toVector.sortBy(_._1).map { case (key, value) =>
        coordinate(s"$prefix.param", key, value)
      }

  private def authorityParts(authority: AcquisitionViewAuthority): Vector[String] =
    Vector(
      s"acquisition-authority-kind=${authority.kind}",
      s"acquisition-authority-source=${authority.sourceChecksum.hex}",
      if authority.buildReceiptChecksum.isDefined then "acquisition-authority-build.some"
      else "acquisition-authority-build.none",
      if authority.evidenceChecksum.isDefined then "acquisition-authority-evidence.some"
      else "acquisition-authority-evidence.none",
      if authority.adjudicationReceipt.isDefined then "acquisition-authority-adjudication.some"
      else "acquisition-authority-adjudication.none",
      if authority.fixtureReceipt.isDefined then "acquisition-authority-fixture.some"
      else "acquisition-authority-fixture.none"
    ) ++ authority.buildReceiptChecksum.toVector.map(value =>
      s"acquisition-authority-build.value=${value.hex}"
    ) ++ authority.evidenceChecksum.toVector.map(value =>
      s"acquisition-authority-evidence.value=${value.hex}"
    ) ++ authority.adjudicationReceipt.toVector.map(value =>
      s"acquisition-authority-adjudication.value=${value.value}"
    ) ++ authority.fixtureReceipt.toVector.map(value =>
      s"acquisition-authority-fixture.value=${value.value}"
    )

  private def artifactParts(refs: ScientificArtifactRefs): Vector[String] =
    Vector(
      "original" -> refs.originalSource,
      "canonical" -> refs.canonicalSource,
      "semantic" -> refs.semanticModel
    ).flatMap { case (name, value) =>
      value.toVector.flatMap { ref =>
        Vector(
          s"artifact.name=$name",
          s"artifact.id=${ref.id.value}"
        ) ++ artifactRoleParts("artifact.role", ref.role) ++ Vector(
          s"artifact.media=${ref.mediaType.value}",
          if ref.schemaVersion.isDefined then "artifact.schema.some" else "artifact.schema.none",
          s"artifact.bytes=${ref.byteLength}",
          s"artifact.checksum=${ref.checksum.hex}"
        ) ++ ref.schemaVersion.toVector.map(value => s"artifact.schema.value=${value.value}")
      }
    }

  private def basisParts(value: AdmittedViewBasis): Vector[String] =
    Vector(
      s"basis=${value.basis}",
      s"basis-source=${value.sourceChecksum.hex}",
      if value.buildReceiptChecksum.isDefined then "basis-build.some" else "basis-build.none"
    ) ++ value.buildReceiptChecksum.toVector.map(checksum =>
      s"basis-build.value=${checksum.hex}"
    ) ++
      basisAuthorityParts(value.authority)

  private def reportRequestParts(request: ReportRequest): Vector[String] =
    Vector(
      "target=report",
      s"request=${request.id.value}",
      s"media-type=${request.mediaType.value}",
      s"authority=${request.authority}"
    ) ++ reportKindParts(request.kind) ++ artifactRoleParts("role", request.role) ++
      artifactRequirementParts(request.requirement) ++
      request.requiredPayloads.toVector.map(_.value).sorted.map(id => s"required-payload=$id")

  private def universeFailureKind(value: UniverseFailureReason): String = value match
    case UniverseFailureReason.Custom(_, _) => "universe-reason=custom"
    case other                              => s"universe-reason=$other"

  private def universeFailureValues(value: UniverseFailureReason): Vector[String] = value match
    case UniverseFailureReason.Custom(namespace, label) =>
      Vector(
        s"universe-reason.namespace=${namespace.value}",
        s"universe-reason.label=${label.value}"
      )
    case _ => Vector.empty

  private def resultGapKind(prefix: String, value: ResultGapKind): String = value match
    case ResultGapKind.Custom(_, _) => s"$prefix.kind=custom"
    case other                      => s"$prefix.kind=$other"

  private def resultGapValues(prefix: String, value: ResultGapKind): Vector[String] = value match
    case ResultGapKind.Custom(namespace, label) =>
      Vector(s"$prefix.kind.namespace=${namespace.value}", s"$prefix.kind.label=${label.value}")
    case _ => Vector.empty

  private def failureCodeKind(prefix: String, value: OutputFailureCode): String = value match
    case OutputFailureCode.Custom(_, _) => s"$prefix-code=custom"
    case other                          => s"$prefix-code=$other"

  private def failureCodeValues(prefix: String, value: OutputFailureCode): Vector[String] =
    value match
      case OutputFailureCode.Custom(namespace, label) =>
        Vector(s"$prefix-code.namespace=${namespace.value}", s"$prefix-code.label=${label.value}")
      case _ => Vector.empty

  private def artifactRoleParts(prefix: String, role: ArtifactRole): Vector[String] = role match
    case ArtifactRole.OriginalSource   => Vector(s"$prefix=original_source")
    case ArtifactRole.CanonicalSource  => Vector(s"$prefix=canonical_source")
    case ArtifactRole.InvocationResult => Vector(s"$prefix=invocation_result")
    case ArtifactRole.SemanticModel    => Vector(s"$prefix=semantic_model")
    case ArtifactRole.BrowserPreview   => Vector(s"$prefix=browser_preview")
    case ArtifactRole.TextPreview      => Vector(s"$prefix=text_preview")
    case ArtifactRole.ReportAsset(id)  => Vector(s"$prefix=report_asset", s"$prefix.id=${id.value}")
    case ArtifactRole.ProjectionPacket(id) =>
      Vector(s"$prefix=projection_packet", s"$prefix.id=${id.value}")
    case ArtifactRole.OptionalReport(id) =>
      Vector(s"$prefix=optional_report", s"$prefix.id=${id.value}")
    case ArtifactRole.DeclaredLossExport(id) =>
      Vector(s"$prefix=declared_loss_export", s"$prefix.id=${id.value}")
    case ArtifactRole.Custom(namespace, label, id) =>
      Vector(
        s"$prefix=custom",
        s"$prefix.namespace=${namespace.value}",
        s"$prefix.label=${label.value}",
        s"$prefix.id=${id.value}"
      )

  private def reportKindParts(kind: ReportKind): Vector[String] = kind match
    case ReportKind.BrowserPreview           => Vector("kind=browser_preview")
    case ReportKind.TextPreview              => Vector("kind=text_preview")
    case ReportKind.Custom(namespace, label) =>
      Vector("kind=custom", s"kind.namespace=${namespace.value}", s"kind.label=${label.value}")

  private def artifactRequirementParts(value: ArtifactRequirement): Vector[String] = value match
    case ArtifactRequirement.Required             => Vector("requirement=required")
    case ArtifactRequirement.Optional             => Vector("requirement=optional")
    case ArtifactRequirement.Conditional(profile) =>
      Vector("requirement=conditional", s"requirement.profile=${profile.value}")

  private def basisAuthorityParts(value: BasisAuthority): Vector[String] = value match
    case BasisAuthority.ValidatedBuild(checksum) =>
      Vector("basis-authority=validated-build", s"basis-authority.build=${checksum.hex}")
    case BasisAuthority.HumanAdjudication(receipt) =>
      Vector("basis-authority=human-adjudication", s"basis-authority.receipt=${receipt.value}")
    case BasisAuthority.FixtureReview(receipt) =>
      Vector("basis-authority=fixture-review", s"basis-authority.receipt=${receipt.value}")

  private def coordinate(label: String, values: String*): String =
    s"coordinate=${framedDigest(label +: values.toVector).hex}"

  private def framedDigest(parts: Vector[String]): Checksum =
    val bytes = Vector.newBuilder[Byte]
    parts.foreach { part =>
      val encoded = part.getBytes(StandardCharsets.UTF_8)
      val length = encoded.length
      bytes += ((length >>> 24) & 0xff).toByte
      bytes += ((length >>> 16) & 0xff).toByte
      bytes += ((length >>> 8) & 0xff).toByte
      bytes += (length & 0xff).toByte
      bytes ++= encoded
    }
    Checksum.ofBytes(bytes.result().toArray)

/** Deterministic receipt bound to an unforgeable report-input identity and configuration. */
final class ReportReceipt private (
    val id: OutputReceiptId,
    val renderer: RendererId,
    val software: OutputSoftwareId,
    val input: ReportInputIdentity,
    val configChecksum: Checksum
):
  def inputChecksum: Checksum = input.checksum
  private def parts = (id, renderer, software, input, configChecksum)
  override def equals(other: Any): Boolean = other match
    case that: ReportReceipt => parts == that.parts
    case _                   => false
  override def hashCode(): Int = parts.hashCode
  override def toString: String = s"ReportReceipt(${id.value}, input=${input.checksum.short()})"

object ReportReceipt:
  def issue(
      id: OutputReceiptId,
      renderer: RendererId,
      software: OutputSoftwareId,
      input: ReportInputIdentity,
      configChecksum: Checksum
  ): ReportReceipt = new ReportReceipt(id, renderer, software, input, configChecksum)

  /** Revalidate an untrusted wire checksum against the exact derived result input. */
  def fromWire(
      id: OutputReceiptId,
      renderer: RendererId,
      software: OutputSoftwareId,
      inputChecksum: Checksum,
      configChecksum: Checksum,
      expectedInput: ReportInputIdentity
  ): Either[DomainError, ReportReceipt] =
    if inputChecksum == expectedInput.checksum then
      Right(new ReportReceipt(id, renderer, software, expectedInput, configChecksum))
    else
      Left(
        DomainError.InvariantViolation(
          "output/report/receipt-input",
          s"wire input ${inputChecksum.hex} does not match derived input ${expectedInput.checksum.hex}"
        )
      )

/** One requested report and the extension payloads it requires. */
final case class ReportRequest(
    id: ReportId,
    kind: ReportKind,
    role: ArtifactRole,
    mediaType: MediaTypeId,
    requirement: ArtifactRequirement,
    authority: ReportAuthority,
    requiredPayloads: Set[OutputPayloadId]
)

/** Independent delivery outcome for one requested report. */
enum ReportOutcome:
  case Produced(requestId: ReportId, artifact: ArtifactRef, receipt: ReportReceipt)
  case Failed(requestId: ReportId, error: OutputFailure, receipt: ReportReceipt)
  case NotAttempted(
      requestId: ReportId,
      reason: NotAttemptedReason,
      receipt: Option[ReportReceipt]
  )

  def reportId: ReportId = this match
    case Produced(id, _, _)     => id
    case Failed(id, _, _)       => id
    case NotAttempted(id, _, _) => id

/** One requested projection/layer pair and its extension dependencies. */
final case class ProjectionRequest(
    id: ProjectionRequestId,
    requiredPayloads: Set[OutputPayloadId]
)

/** Total disposition family for one requested projection/layer pair. */
enum ProjectionDisposition:
  case Produced(artifact: Option[ArtifactRef], marks: OutputCount, receipt: ReportReceipt)
  case EstablishedEmpty(receipt: ReportReceipt)
  case FilteredOrSuppressed(
      retained: OutputCount,
      suppressed: OutputCount,
      reason: SuppressionReason,
      receipt: ReportReceipt
  )
  case Unsupported(error: OutputFailure)
  case Failed(error: OutputFailure, receipt: ReportReceipt)

/** Exactly one outcome for one requested projection/layer pair. */
final case class ProjectionOutcome(
    requestId: ProjectionRequestId,
    disposition: ProjectionDisposition
)

/** Basis whose authority is constructed from admitted provenance rather than a free label. */
final class AdmittedViewBasis private (
    val basis: ViewBasis,
    val sourceChecksum: Checksum,
    val buildReceiptChecksum: Option[Checksum],
    val authority: BasisAuthority
):
  private def parts = (basis, sourceChecksum, buildReceiptChecksum, authority)

  override def equals(other: Any): Boolean = other match
    case that: AdmittedViewBasis => parts == that.parts
    case _                       => false
  override def hashCode(): Int = parts.hashCode
  override def toString: String = s"AdmittedViewBasis(${basis.label})"

object AdmittedViewBasis:
  /** Preserve one homogeneous authority root and refuse mixed provenance in the v1 scalar model. */
  def combineHomogeneous(
      values: Iterable[AdmittedViewBasis]
  ): Either[DomainError, AdmittedViewBasis] =
    values.toVector match
      case Vector() =>
        Left(
          DomainError.InvariantViolation(
            "output/view/basis-empty",
            "at least one admitted basis is required"
          )
        )
      case head +: tail if tail.forall(_ == head) => Right(head)
      case _                                      =>
        Left(
          DomainError.InvariantViolation(
            "output/view/basis-mixed",
            "v1 cannot collapse heterogeneous admitted authority roots"
          )
        )

  /** Derive the only basis licensed by an admitted acquisition account. */
  def fromAcquisition[Id](
      acquisition: AcquisitionAccount[Id]
  ): Either[DomainError, AdmittedViewBasis] =
    val sourceChecksum = acquisition.source match
      case SourceOutcome.Constructed(identities) => Some(identities.canonicalChecksum)
      case SourceOutcome.Refused(_, _)           => None
    val buildChecksum = acquisition.buildReceipt.map(_.receipt.contentChecksum)
    (sourceChecksum, acquisition.viewAuthority.map(_.kind)) match
      case (Some(source), Some(AcquisitionViewAuthorityKind.ValidatedBuild)) =>
        buildChecksum
          .map(checksum =>
            new AdmittedViewBasis(
              ViewBasis.ValidatedBuild,
              source,
              Some(checksum),
              BasisAuthority.ValidatedBuild(checksum)
            )
          )
          .toRight(
            DomainError.InvariantViolation(
              "output/view/basis-build",
              "validated-build authority requires the admitted build receipt"
            )
          )
      case (Some(source), Some(AcquisitionViewAuthorityKind.HumanAdjudication)) =>
        (buildChecksum, acquisition.viewAuthority.flatMap(_.adjudicationReceipt))
          .mapN((checksum, receipt) =>
            new AdmittedViewBasis(
              ViewBasis.HumanAdjudicated,
              source,
              Some(checksum),
              BasisAuthority.HumanAdjudication(receipt)
            )
          )
          .toRight(
            DomainError.InvariantViolation(
              "output/view/basis-build",
              "human-adjudicated authority requires its admitted build and evidence receipt"
            )
          )
      case (Some(source), Some(AcquisitionViewAuthorityKind.FixtureReview)) =>
        acquisition.viewAuthority
          .flatMap(_.fixtureReceipt)
          .map(receipt =>
            new AdmittedViewBasis(
              ViewBasis.ResearcherReviewedFixture,
              source,
              buildChecksum,
              BasisAuthority.FixtureReview(receipt)
            )
          )
          .toRight(
            DomainError.InvariantViolation(
              "output/view/basis-authority",
              "fixture authority requires its admitted evidence receipt"
            )
          )
      case _ =>
        Left(
          DomainError.InvariantViolation(
            "output/view/basis-authority",
            "a view basis requires constructed source and admitted acquisition authority"
          )
        )

  /** Revalidate an untrusted wire claim against the authority admitted by the account. */
  def fromWire[Id](
      acquisition: AcquisitionAccount[Id],
      basis: ViewBasis,
      sourceChecksum: Checksum,
      buildReceiptChecksum: Option[Checksum],
      authority: BasisAuthority
  ): Either[DomainError, AdmittedViewBasis] =
    fromAcquisition(acquisition).flatMap { admitted =>
      val claimed = (basis, sourceChecksum, buildReceiptChecksum, authority)
      val expected =
        (admitted.basis, admitted.sourceChecksum, admitted.buildReceiptChecksum, admitted.authority)
      if claimed == expected then Right(admitted)
      else
        Left(
          DomainError.InvariantViolation(
            "output/view/basis-authority",
            "wire basis, source, build receipt, or authority receipt disagrees with admitted acquisition provenance"
          )
        )
    }

/** Typed evidence root supporting one view-basis authority claim. */
enum BasisAuthority:
  case ValidatedBuild(buildReceiptChecksum: Checksum)
  case HumanAdjudication(receipt: AdjudicationReceiptId)
  case FixtureReview(receipt: FixtureAdmissionReceiptId)

/** Composed output result that cannot lose or duplicate requested report state. */
final class StoryOutputResult[Id] private (
    val acquisition: AcquisitionAccount[Id],
    val scientificArtifacts: ScientificArtifactRefs,
    val basis: Option[AdmittedViewBasis],
    val reportRequests: Vector[ReportRequest],
    val reportOutcomes: Vector[ReportOutcome],
    val projectionRequests: Vector[ProjectionRequest],
    val projectionOutcomes: Vector[ProjectionOutcome]
):
  private def parts =
    (
      acquisition,
      scientificArtifacts,
      basis,
      reportRequests,
      reportOutcomes,
      projectionRequests,
      projectionOutcomes
    )

  override def equals(other: Any): Boolean = other match
    case that: StoryOutputResult[?] => parts == that.parts
    case _                          => false
  override def hashCode(): Int = parts.hashCode
  override def toString: String =
    s"StoryOutputResult(reports=${reportOutcomes.size}, projections=${projectionOutcomes.size})"

object StoryOutputResult:
  /** Accumulate request, basis, extension, and outcome mismatches. */
  def of[Id: OutputTargetIdentity](
      acquisition: AcquisitionAccount[Id],
      scientificArtifacts: ScientificArtifactRefs,
      basis: Option[AdmittedViewBasis],
      reportRequests: Iterable[ReportRequest],
      reportOutcomes: Iterable[ReportOutcome],
      projectionRequests: Iterable[ProjectionRequest],
      projectionOutcomes: Iterable[ProjectionOutcome]
  ): ValidatedNec[DomainError, StoryOutputResult[Id]] =
    val requests = reportRequests.toVector.sortBy(_.id.value)
    val outcomes = reportOutcomes.toVector.sortBy(_.reportId.value)
    val projectionRequestVector = projectionRequests.toVector.sortBy(_.id.value)
    val projectionOutcomeVector = projectionOutcomes.toVector.sortBy(_.requestId.value)
    (
      unique(requests.map(_.id), "StoryOutputResult.reportRequest"),
      unique(outcomes.map(_.reportId), "StoryOutputResult.reportOutcome"),
      exact(requests.map(_.id), outcomes.map(_.reportId), "report"),
      unique(projectionRequestVector.map(_.id), "StoryOutputResult.projectionRequest"),
      unique(projectionOutcomeVector.map(_.requestId), "StoryOutputResult.projectionOutcome"),
      exact(
        projectionRequestVector.map(_.id),
        projectionOutcomeVector.map(_.requestId),
        "projection"
      ),
      ScientificArtifactRefs.validate(acquisition, scientificArtifacts),
      ReportInputIdentity.validateTargetIdentities(acquisition).toValidatedNec,
      reportArtifacts(requests, outcomes),
      admittedBasis(acquisition, basis, requests, outcomes),
      reportReceipts(acquisition, scientificArtifacts, basis, requests, outcomes),
      projectionReceipts(
        acquisition,
        scientificArtifacts,
        basis,
        projectionRequestVector,
        projectionOutcomeVector
      ),
      unsupportedDependencies(acquisition.payloads, requests, outcomes),
      unsupportedProjectionDependencies(
        acquisition.payloads,
        projectionRequestVector,
        projectionOutcomeVector
      )
    ).mapN((_, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
      new StoryOutputResult(
        acquisition,
        scientificArtifacts,
        basis,
        requests,
        outcomes,
        projectionRequestVector,
        projectionOutcomeVector
      )
    )

  private def reportArtifacts(
      requests: Vector[ReportRequest],
      outcomes: Vector[ReportOutcome]
  ): ValidatedNec[DomainError, Unit] =
    val byId = requests.map(request => request.id -> request).toMap
    val mismatches = outcomes.collect { case ReportOutcome.Produced(id, artifact, _) =>
      byId.get(id) match
        case Some(request)
            if request.role == artifact.role && request.mediaType == artifact.mediaType =>
          None
        case _ => Some(id)
    }.flatten
    require(
      mismatches.isEmpty,
      DomainError.InvariantViolation(
        "output/report/artifact",
        s"${mismatches.size} produced reports do not match their request role/media type"
      )
    )

  private def admittedBasis[Id](
      acquisition: AcquisitionAccount[Id],
      basis: Option[AdmittedViewBasis],
      requests: Vector[ReportRequest],
      outcomes: Vector[ReportOutcome]
  ): ValidatedNec[DomainError, Unit] =
    val requestById = requests.map(request => request.id -> request).toMap
    val producedScientific = outcomes.exists {
      case ReportOutcome.Produced(id, _, _) =>
        requestById.get(id).exists(_.authority == ReportAuthority.ScientificView)
      case _ => false
    }
    val admitted = AdmittedViewBasis.fromAcquisition(acquisition).toOption
    (
      require(
        !producedScientific || basis.nonEmpty,
        DomainError.InvariantViolation(
          "output/view/basis",
          "a produced scientific report requires admitted view basis"
        )
      ),
      require(
        basis.forall(value => admitted.contains(value)),
        DomainError.InvariantViolation(
          "output/view/basis-provenance",
          "view basis does not exactly match the admitted source, build, and authority receipt"
        )
      )
    ).mapN((_, _) => ())

  private def reportReceipts[Id: OutputTargetIdentity](
      acquisition: AcquisitionAccount[Id],
      artifacts: ScientificArtifactRefs,
      basis: Option[AdmittedViewBasis],
      requests: Vector[ReportRequest],
      outcomes: Vector[ReportOutcome]
  ): ValidatedNec[DomainError, Unit] =
    val byId = requests.map(request => request.id -> request).toMap
    val invalid = outcomes.filter { outcome =>
      val receipt = outcome match
        case ReportOutcome.Produced(_, _, value)     => Some(value)
        case ReportOutcome.Failed(_, _, value)       => Some(value)
        case ReportOutcome.NotAttempted(_, _, value) => value
      receipt.exists(value =>
        byId
          .get(outcome.reportId)
          .forall(request =>
            ReportInputIdentity
              .forReport(acquisition, artifacts, basis, request)
              .forall(_ != value.input)
          )
      )
    }
    require(
      invalid.isEmpty,
      DomainError.InvariantViolation(
        "output/report/receipt-input",
        s"${invalid.size} report receipts do not bind the exact result input"
      )
    )

  private def projectionReceipts[Id: OutputTargetIdentity](
      acquisition: AcquisitionAccount[Id],
      artifacts: ScientificArtifactRefs,
      basis: Option[AdmittedViewBasis],
      requests: Vector[ProjectionRequest],
      outcomes: Vector[ProjectionOutcome]
  ): ValidatedNec[DomainError, Unit] =
    val byId = requests.map(request => request.id -> request).toMap
    val invalid = outcomes.filter { outcome =>
      val receipt = outcome.disposition match
        case ProjectionDisposition.Produced(_, _, value)                => Some(value)
        case ProjectionDisposition.EstablishedEmpty(value)              => Some(value)
        case ProjectionDisposition.FilteredOrSuppressed(_, _, _, value) => Some(value)
        case ProjectionDisposition.Unsupported(_)                       => None
        case ProjectionDisposition.Failed(_, value)                     => Some(value)
      receipt.exists(value =>
        byId
          .get(outcome.requestId)
          .forall(request =>
            ReportInputIdentity
              .forProjection(acquisition, artifacts, basis, request)
              .forall(_ != value.input)
          )
      )
    }
    require(
      invalid.isEmpty,
      DomainError.InvariantViolation(
        "output/projection/receipt-input",
        s"${invalid.size} projection receipts do not bind the exact result input"
      )
    )

  private def unsupportedDependencies(
      payloads: Vector[OutputPayload],
      requests: Vector[ReportRequest],
      outcomes: Vector[ReportOutcome]
  ): ValidatedNec[DomainError, Unit] =
    val unsupportedRequired = payloads.collect {
      case OutputPayload.Unsupported(extension)
          if extension.requirement == ExtensionRequirement.Required =>
        extension.id
    }.toSet
    val requestById = requests.map(request => request.id -> request).toMap
    val wronglyProduced = outcomes.collect { case ReportOutcome.Produced(id, _, _) =>
      requestById.get(id).filter(_.requiredPayloads.exists(unsupportedRequired.contains))
    }.flatten
    require(
      wronglyProduced.isEmpty,
      DomainError.InvariantViolation(
        "output/report/unsupported-extension",
        "a report requiring an unsupported extension cannot be produced"
      )
    )

  private def unsupportedProjectionDependencies(
      payloads: Vector[OutputPayload],
      requests: Vector[ProjectionRequest],
      outcomes: Vector[ProjectionOutcome]
  ): ValidatedNec[DomainError, Unit] =
    val unsupportedRequired = payloads.collect {
      case OutputPayload.Unsupported(extension)
          if extension.requirement == ExtensionRequirement.Required =>
        extension.id
    }.toSet
    val requestById = requests.map(request => request.id -> request).toMap
    val wronglyEstablished = outcomes.filter { outcome =>
      val blocked = requestById
        .get(outcome.requestId)
        .exists(_.requiredPayloads.exists(unsupportedRequired.contains))
      val claimsProjection = outcome.disposition match
        case ProjectionDisposition.Produced(_, _, _) | ProjectionDisposition.EstablishedEmpty(_) |
            ProjectionDisposition.FilteredOrSuppressed(_, _, _, _) =>
          true
        case ProjectionDisposition.Unsupported(_) | ProjectionDisposition.Failed(_, _) => false
      blocked && claimsProjection
    }
    require(
      wronglyEstablished.isEmpty,
      DomainError.InvariantViolation(
        "output/projection/unsupported-extension",
        "a projection requiring an unsupported extension cannot claim established output"
      )
    )

  private def exact[A](
      requests: Vector[A],
      outcomes: Vector[A],
      kind: String
  ): ValidatedNec[DomainError, Unit] =
    val requestSet = requests.toSet
    val outcomeSet = outcomes.toSet
    val missing = requests.filterNot(outcomeSet.contains)
    val extra = outcomes.filterNot(requestSet.contains)
    require(
      missing.isEmpty && extra.isEmpty,
      DomainError.InvariantViolation(
        s"output/$kind/accounting",
        s"missing=${missing.size}, extra=${extra.size}"
      )
    )

  private def unique[A](values: Vector[A], kind: String): ValidatedNec[DomainError, Unit] =
    val seen = scala.collection.mutable.HashSet.empty[A]
    values.find(value => !seen.add(value)) match
      case Some(duplicate) => DomainError.DuplicateId(kind, duplicate.toString).invalidNec
      case None            => ().validNec

  private def require(
      condition: Boolean,
      error: => DomainError
  ): ValidatedNec[DomainError, Unit] =
    if condition then ().validNec else error.invalidNec

/** One manifest entry whose non-produced branches carry no invented byte metadata. */
final case class ManifestEntry(
    role: ArtifactRole,
    path: BundlePath,
    mediaType: MediaTypeId,
    schemaVersion: Option[OutputSchemaId],
    requirement: ArtifactRequirement,
    disposition: ArtifactDisposition
)

/** Produced or explicitly unavailable bytes for one promised role. */
enum ArtifactDisposition:
  case Produced(artifact: ArtifactRef)
  case Failed(error: OutputFailure, receipt: OutputReceiptId)
  case NotAttempted(reason: NotAttemptedReason, receipt: Option[OutputReceiptId])
  case AbsentBySemanticContract(reason: SemanticAbsenceReason)

/** Final manifest domain value; its external BundleId is computed by the codec. */
final class BundleManifest[Id] private (
    val profileOutcomes: Vector[BundleProfileOutcome],
    val entries: Vector[ManifestEntry]
):
  private def parts = (profileOutcomes, entries)

  override def equals(other: Any): Boolean = other match
    case that: BundleManifest[?] => parts == that.parts
    case _                       => false
  override def hashCode(): Int = parts.hashCode
  override def toString: String =
    s"BundleManifest(profiles=${profileOutcomes.size}, entries=${entries.size})"

object BundleManifest:
  /** Validate role, path, semantic, and report disposition consistency at once. */
  def of[Id](
      result: StoryOutputResult[Id],
      profileOutcomes: Iterable[BundleProfileOutcome],
      entries: Iterable[ManifestEntry]
  ): ValidatedNec[DomainError, BundleManifest[Id]] =
    val rawProfiles = profileOutcomes.toVector
    val profileVector = rawProfiles.sortBy(_.profile.sortKey)
    val entryVector = entries.toVector.sortBy(entry =>
      (entry.role.wireName, asciiFold(entry.path.value), entry.path.value)
    )
    (
      require(
        profileVector.exists(_.profile == BundleProfile.LocalOpen),
        DomainError.InvariantViolation(
          "output/manifest/profiles",
          "every bundle requires a local-open profile outcome"
        )
      ),
      unique(rawProfiles.map(_.profile), "BundleManifest.profile"),
      profileReceiptConsistency(profileVector),
      profileReportConsistency(profileVector, entryVector),
      unique(entryVector.map(_.path.value), "BundleManifest.path"),
      unique(
        entryVector.map(entry => asciiFold(entry.path.value)),
        "BundleManifest.caseFoldedPath"
      ),
      unique(entryVector.map(_.role), "BundleManifest.role"),
      invocationResult(entryVector),
      producedMetadata(entryVector),
      reportEntries(result, entryVector),
      semanticEntry(result, entryVector),
      sourceEntries(result, entryVector)
    ).mapN((_, _, _, _, _, _, _, _, _, _, _, _) => new BundleManifest(profileVector, entryVector))

  private def profileReceiptConsistency(
      profiles: Vector[BundleProfileOutcome]
  ): ValidatedNec[DomainError, Unit] =
    val invalid = profiles.filterNot { outcome =>
      val receipt = outcome.disposition match
        case ProfileDisposition.Satisfied(value)       => value.receipt
        case ProfileDisposition.Failed(_, value)       => value
        case ProfileDisposition.NotAttempted(_, value) => value
      val decisionMatches = outcome.disposition match
        case ProfileDisposition.Satisfied(_) =>
          outcome.profile == BundleProfile.LocalOpen &&
          receipt.decision == ProfileReceiptDecision.Satisfied
        case ProfileDisposition.Failed(error, _) =>
          receipt.decision == ProfileReceiptDecision.Failed(error.code) &&
          receipt.prerequisites.contains(error.receipt)
        case ProfileDisposition.NotAttempted(reason, _) =>
          receipt.decision == ProfileReceiptDecision.NotAttempted(reason)
      decisionMatches &&
      receipt.prerequisites.distinct.size == receipt.prerequisites.size &&
      receipt.requiredAssets.distinct.size == receipt.requiredAssets.size &&
      receipt.courts.map(_.court).distinct.size == receipt.courts.size
    }
    require(
      invalid.isEmpty,
      DomainError.InvariantViolation(
        "output/manifest/profile-receipt",
        s"${invalid.size} profile outcomes disagree with their disposition receipt"
      )
    )

  private def profileReportConsistency(
      profiles: Vector[BundleProfileOutcome],
      entries: Vector[ManifestEntry]
  ): ValidatedNec[DomainError, Unit] =
    val local = profiles.find(_.profile == BundleProfile.LocalOpen).map(_.disposition)
    val browser = entries.find(_.role == ArtifactRole.BrowserPreview)
    val satisfiedValid = profiles.forall {
      case BundleProfileOutcome(_, ProfileDisposition.Satisfied(receipt)) =>
        certificationMatches(receipt.receipt, browser, entries)
      case _ => true
    }
    val valid = local match
      case Some(ProfileDisposition.Satisfied(receipt)) =>
        certificationMatches(receipt.receipt, browser, entries)
      case Some(ProfileDisposition.Failed(_, receipt)) =>
        browser.nonEmpty && receipt.preview.forall(bindingMatches(_, browser, entries))
      case Some(ProfileDisposition.NotAttempted(_, receipt)) =>
        browser.nonEmpty && receipt.preview.forall(bindingMatches(_, browser, entries))
      case None => false
    (
      require(
        valid,
        DomainError.InvariantViolation(
          "output/manifest/local-open-profile",
          "local-open profile outcome disagrees with browser_preview delivery"
        )
      ),
      require(
        satisfiedValid,
        DomainError.InvariantViolation(
          "output/manifest/profile-certification",
          "a satisfied profile receipt does not bind passed courts and exact artifacts"
        )
      )
    ).mapN((_, _) => ())

  private def certificationMatches(
      receipt: ProfileReceipt,
      browser: Option[ManifestEntry],
      entries: Vector[ManifestEntry]
  ): Boolean =
    receipt.preview.exists(binding =>
      binding.path == BundlePath.unsafe("preview.html") &&
        bindingMatches(binding, browser, entries)
    ) &&
      receipt.courts.nonEmpty &&
      receipt.courts.forall(_.disposition == ProfileCourtDisposition.Passed) &&
      receipt.courts.map(_.court).distinct.size == receipt.courts.size &&
      receipt.requiredAssets.forall(asset => bindingMatches(asset, None, entries))

  private def bindingMatches(
      binding: ProfileArtifactBinding,
      expected: Option[ManifestEntry],
      entries: Vector[ManifestEntry]
  ): Boolean =
    val candidates = expected.fold(entries)(Vector(_))
    candidates.exists {
      case ManifestEntry(role, path, mediaType, _, _, ArtifactDisposition.Produced(artifact)) =>
        role == binding.role &&
        path == binding.path &&
        mediaType == binding.mediaType &&
        artifact.checksum == binding.checksum
      case _ => false
    }

  private def invocationResult(
      entries: Vector[ManifestEntry]
  ): ValidatedNec[DomainError, Unit] =
    val matches = entries.filter(_.role == ArtifactRole.InvocationResult)
    val dispositionValid = matches.headOption.exists(entry =>
      entry.disposition match
        case ArtifactDisposition.Produced(_) | ArtifactDisposition.Failed(_, _) => true
        case _                                                                  => false
    )
    require(
      matches.size == 1 &&
        matches.head.requirement == ArtifactRequirement.Required &&
        dispositionValid,
      DomainError.InvariantViolation(
        "output/manifest/invocation-result",
        "exactly one required invocation_result entry is required"
      )
    )

  private def producedMetadata(
      entries: Vector[ManifestEntry]
  ): ValidatedNec[DomainError, Unit] =
    val mismatches = entries.collect {
      case entry @ ManifestEntry(_, _, _, _, _, ArtifactDisposition.Produced(artifact))
          if entry.role != artifact.role || entry.mediaType != artifact.mediaType ||
            entry.schemaVersion != artifact.schemaVersion =>
        entry.path
    }
    require(
      mismatches.isEmpty,
      DomainError.InvariantViolation(
        "output/manifest/produced-metadata",
        s"${mismatches.size} produced entries disagree with artifact metadata"
      )
    )

  private def reportEntries[Id](
      result: StoryOutputResult[Id],
      entries: Vector[ManifestEntry]
  ): ValidatedNec[DomainError, Unit] =
    val requestById = result.reportRequests.map(request => request.id -> request).toMap
    val entriesByRole = entries.groupBy(_.role)
    val mismatches = result.reportOutcomes.flatMap { outcome =>
      requestById.get(outcome.reportId) match
        case None          => Some(outcome.reportId)
        case Some(request) =>
          val matching = entriesByRole.getOrElse(request.role, Vector.empty)
          outcome match
            case ReportOutcome.Produced(_, artifact, _) =>
              if matching.exists(_.disposition == ArtifactDisposition.Produced(artifact)) then None
              else Some(request.id)
            case ReportOutcome.Failed(_, error, receipt) =>
              if matching.exists(
                  _.disposition == ArtifactDisposition.Failed(error, receipt.id)
                )
              then None
              else Some(request.id)
            case ReportOutcome.NotAttempted(_, reason, receipt) =>
              if matching.exists(
                  _.disposition == ArtifactDisposition.NotAttempted(reason, receipt.map(_.id))
                )
              then None
              else Some(request.id)
    }
    require(
      mismatches.isEmpty,
      DomainError.InvariantViolation(
        "output/manifest/report",
        s"${mismatches.size} report outcomes lack a matching manifest entry"
      )
    )

  private def semanticEntry[Id](
      result: StoryOutputResult[Id],
      entries: Vector[ManifestEntry]
  ): ValidatedNec[DomainError, Unit] =
    val semantic = entries.filter(_.role == ArtifactRole.SemanticModel)
    val valid = entryMatchesRef(
      semantic,
      result.scientificArtifacts.semanticModel,
      SemanticAbsenceReason.SemanticNotValidated
    )
    require(
      valid,
      DomainError.InvariantViolation(
        "output/manifest/semantic-model",
        "semantic_model entry disagrees with semantic outcome"
      )
    )

  private def sourceEntries[Id](
      result: StoryOutputResult[Id],
      entries: Vector[ManifestEntry]
  ): ValidatedNec[DomainError, Unit] =
    val original = entries.filter(_.role == ArtifactRole.OriginalSource)
    val canonical = entries.filter(_.role == ArtifactRole.CanonicalSource)
    val valid =
      entryMatchesRef(
        original,
        result.scientificArtifacts.originalSource,
        SemanticAbsenceReason.SourceNotConstructed
      ) &&
        entryMatchesRef(
          canonical,
          result.scientificArtifacts.canonicalSource,
          SemanticAbsenceReason.SourceNotConstructed
        )
    require(
      valid,
      DomainError.InvariantViolation(
        "output/manifest/source",
        "source entries disagree with source construction outcome"
      )
    )

  private def entryMatchesRef(
      entries: Vector[ManifestEntry],
      expected: Option[ArtifactRef],
      absentReason: SemanticAbsenceReason
  ): Boolean =
    expected.fold(
      entries.isEmpty ||
        (entries.size == 1 &&
          entries.head.disposition == ArtifactDisposition.AbsentBySemanticContract(absentReason))
    )(artifact =>
      entries.size == 1 &&
        entries.head.disposition == ArtifactDisposition.Produced(artifact) &&
        entries.head.role == artifact.role &&
        entries.head.mediaType == artifact.mediaType &&
        entries.head.schemaVersion == artifact.schemaVersion
    )

  private def unique[A](values: Vector[A], kind: String): ValidatedNec[DomainError, Unit] =
    val seen = scala.collection.mutable.HashSet.empty[A]
    values.find(value => !seen.add(value)) match
      case Some(duplicate) => DomainError.DuplicateId(kind, duplicate.toString).invalidNec
      case None            => ().validNec

  private def require(
      condition: Boolean,
      error: => DomainError
  ): ValidatedNec[DomainError, Unit] =
    if condition then ().validNec else error.invalidNec

  private def asciiFold(value: String): String =
    value.map { character =>
      if character >= 'A' && character <= 'Z' then (character + ('a' - 'A')).toChar
      else character
    }
