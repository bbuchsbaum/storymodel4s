package storymodel4s.codec

import cats.syntax.all.*
import io.circe.{Decoder, Encoder, HCursor, Json}
import io.circe.syntax.*
import storymodel4s.acquire.*
import storymodel4s.core.*
import storymodel4s.view.*

import CanonicalPrimitives.*
import CanonicalPrimitives.given
import OutputAcquireCodecs.given

/** Canonical codecs for output reports, projections, artifacts, and manifests. */
object OutputCodecs:
  given Encoder[ReportId] = opaqueEncoder(ReportId)
  given Decoder[ReportId] = opaqueDecoder(ReportId)
  given Encoder[ProjectionRequestId] = opaqueEncoder(ProjectionRequestId)
  given Decoder[ProjectionRequestId] = opaqueDecoder(ProjectionRequestId)
  given Encoder[ArtifactId] = opaqueEncoder(ArtifactId)
  given Decoder[ArtifactId] = opaqueDecoder(ArtifactId)
  given Encoder[RendererId] = opaqueEncoder(RendererId)
  given Decoder[RendererId] = opaqueDecoder(RendererId)
  given Encoder[OutputSoftwareId] = opaqueEncoder(OutputSoftwareId)
  given Decoder[OutputSoftwareId] = opaqueDecoder(OutputSoftwareId)
  given Encoder[BundleProfileId] = opaqueEncoder(BundleProfileId)
  given Decoder[BundleProfileId] = opaqueDecoder(BundleProfileId)
  given Encoder[ProfileSchemaId] = opaqueEncoder(ProfileSchemaId)
  given Decoder[ProfileSchemaId] = opaqueDecoder(ProfileSchemaId)
  given Encoder[ProfileVerifierId] = opaqueEncoder(ProfileVerifierId)
  given Decoder[ProfileVerifierId] = opaqueDecoder(ProfileVerifierId)
  given Encoder[ProfileCourtId] = opaqueEncoder(ProfileCourtId)
  given Decoder[ProfileCourtId] = opaqueDecoder(ProfileCourtId)
  given Encoder[OutputCount] = Encoder.encodeInt.contramap(_.value)
  given Decoder[OutputCount] =
    Decoder.decodeInt.emap(value => OutputCount.from(value).left.map(_.message))

  given Encoder[BundlePath] = Encoder.encodeString.contramap(_.value)
  given Decoder[BundlePath] =
    Decoder.decodeString.emap(value => BundlePath.from(value).left.map(_.message))

  given Encoder[ArtifactRole] = Encoder.instance {
    case ArtifactRole.OriginalSource               => tagged("original_source")
    case ArtifactRole.CanonicalSource              => tagged("canonical_source")
    case ArtifactRole.InvocationResult             => tagged("invocation_result")
    case ArtifactRole.SemanticModel                => tagged("semantic_model")
    case ArtifactRole.BrowserPreview               => tagged("browser_preview")
    case ArtifactRole.TextPreview                  => tagged("text_preview")
    case ArtifactRole.ReportAsset(id)              => identified("report_asset", id)
    case ArtifactRole.ProjectionPacket(id)         => identified("projection_packet", id)
    case ArtifactRole.OptionalReport(id)           => identified("optional_report", id)
    case ArtifactRole.DeclaredLossExport(id)       => identified("declared_loss_export", id)
    case ArtifactRole.Custom(namespace, label, id) =>
      Json.obj(
        "status" -> "custom".asJson,
        "namespace" -> namespace.asJson,
        "label" -> label.asJson,
        "id" -> id.asJson
      )
  }

  given Decoder[ArtifactRole] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "original_source"   => Right(ArtifactRole.OriginalSource)
      case "canonical_source"  => Right(ArtifactRole.CanonicalSource)
      case "invocation_result" => Right(ArtifactRole.InvocationResult)
      case "semantic_model"    => Right(ArtifactRole.SemanticModel)
      case "browser_preview"   => Right(ArtifactRole.BrowserPreview)
      case "text_preview"      => Right(ArtifactRole.TextPreview)
      case "report_asset"      => field[ArtifactId](c, "id").map(ArtifactRole.ReportAsset.apply)
      case "projection_packet" =>
        field[ArtifactId](c, "id").map(ArtifactRole.ProjectionPacket.apply)
      case "optional_report" =>
        field[ArtifactId](c, "id").map(ArtifactRole.OptionalReport.apply)
      case "declared_loss_export" =>
        field[ArtifactId](c, "id").map(ArtifactRole.DeclaredLossExport.apply)
      case "custom" =>
        for
          namespace <- field[OutputNamespace](c, "namespace")
          label <- field[OutputLabel](c, "label")
          id <- field[ArtifactId](c, "id")
        yield ArtifactRole.Custom(namespace, label, id)
      case other => unknown(c, "ArtifactRole", other)
    }
  }

  given Encoder[ArtifactRequirement] = Encoder.instance {
    case ArtifactRequirement.Required             => tagged("required")
    case ArtifactRequirement.Optional             => tagged("optional")
    case ArtifactRequirement.Conditional(profile) =>
      Json.obj("status" -> "conditional".asJson, "profile" -> profile.asJson)
  }

  given Decoder[ArtifactRequirement] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "required"    => Right(ArtifactRequirement.Required)
      case "optional"    => Right(ArtifactRequirement.Optional)
      case "conditional" =>
        field[BundleProfileId](c, "profile").map(ArtifactRequirement.Conditional.apply)
      case other => unknown(c, "ArtifactRequirement", other)
    }
  }

  given Encoder[ReportAuthority] = Encoder.instance {
    case ReportAuthority.InvocationOnly => tagged("invocation_only")
    case ReportAuthority.ScientificView => tagged("scientific_view")
  }
  given Decoder[ReportAuthority] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "invocation_only" => Right(ReportAuthority.InvocationOnly)
      case "scientific_view" => Right(ReportAuthority.ScientificView)
      case other             => unknown(c, "ReportAuthority", other)
    }
  }

  given Encoder[ReportKind] = Encoder.instance {
    case ReportKind.BrowserPreview           => tagged("browser_preview")
    case ReportKind.TextPreview              => tagged("text_preview")
    case ReportKind.Custom(namespace, label) => custom(namespace, label)
  }

  given Decoder[ReportKind] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "browser_preview" => Right(ReportKind.BrowserPreview)
      case "text_preview"    => Right(ReportKind.TextPreview)
      case "custom"          => decodeCustom(c, ReportKind.Custom.apply)
      case other             => unknown(c, "ReportKind", other)
    }
  }

  given Encoder[NotAttemptedReason] = Encoder.instance {
    case NotAttemptedReason.UpstreamUnavailable      => tagged("upstream_unavailable")
    case NotAttemptedReason.DependencyUnsupported    => tagged("dependency_unsupported")
    case NotAttemptedReason.PolicyRefused            => tagged("policy_refused")
    case NotAttemptedReason.VerificationNotRun       => tagged("verification_not_run")
    case NotAttemptedReason.Custom(namespace, label) => custom(namespace, label)
  }

  given Decoder[NotAttemptedReason] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "upstream_unavailable"   => Right(NotAttemptedReason.UpstreamUnavailable)
      case "dependency_unsupported" => Right(NotAttemptedReason.DependencyUnsupported)
      case "policy_refused"         => Right(NotAttemptedReason.PolicyRefused)
      case "verification_not_run"   => Right(NotAttemptedReason.VerificationNotRun)
      case "custom"                 => decodeCustom(c, NotAttemptedReason.Custom.apply)
      case other                    => unknown(c, "NotAttemptedReason", other)
    }
  }

  given Encoder[SuppressionReason] = Encoder.instance {
    case SuppressionReason.Horizon                  => tagged("horizon")
    case SuppressionReason.Zoom                     => tagged("zoom")
    case SuppressionReason.EndpointVisibility       => tagged("endpoint_visibility")
    case SuppressionReason.LayoutPolicy             => tagged("layout_policy")
    case SuppressionReason.Custom(namespace, label) => custom(namespace, label)
  }

  given Decoder[SuppressionReason] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "horizon"             => Right(SuppressionReason.Horizon)
      case "zoom"                => Right(SuppressionReason.Zoom)
      case "endpoint_visibility" => Right(SuppressionReason.EndpointVisibility)
      case "layout_policy"       => Right(SuppressionReason.LayoutPolicy)
      case "custom"              => decodeCustom(c, SuppressionReason.Custom.apply)
      case other                 => unknown(c, "SuppressionReason", other)
    }
  }

  given Encoder[SemanticAbsenceReason] = Encoder.instance {
    case SemanticAbsenceReason.SourceNotConstructed     => tagged("source_not_constructed")
    case SemanticAbsenceReason.SemanticNotValidated     => tagged("semantic_not_validated")
    case SemanticAbsenceReason.SemanticsNotRequested    => tagged("semantics_not_requested")
    case SemanticAbsenceReason.NotApplicableToOutcome   => tagged("not_applicable_to_outcome")
    case SemanticAbsenceReason.Custom(namespace, label) => custom(namespace, label)
  }

  given Decoder[SemanticAbsenceReason] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "source_not_constructed"    => Right(SemanticAbsenceReason.SourceNotConstructed)
      case "semantic_not_validated"    => Right(SemanticAbsenceReason.SemanticNotValidated)
      case "semantics_not_requested"   => Right(SemanticAbsenceReason.SemanticsNotRequested)
      case "not_applicable_to_outcome" => Right(SemanticAbsenceReason.NotApplicableToOutcome)
      case "custom"                    => decodeCustom(c, SemanticAbsenceReason.Custom.apply)
      case other                       => unknown(c, "SemanticAbsenceReason", other)
    }
  }

  given Encoder[BundleProfile] = Encoder.instance {
    case BundleProfile.LocalOpen  => tagged("local_open")
    case BundleProfile.Custom(id) => Json.obj("status" -> "custom".asJson, "id" -> id.asJson)
  }

  given Decoder[BundleProfile] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "local_open" => Right(BundleProfile.LocalOpen)
      case "custom"     => field[BundleProfileId](c, "id").map(BundleProfile.Custom.apply)
      case other        => unknown(c, "BundleProfile", other)
    }
  }

  given Encoder[ProfileArtifactBinding] = Encoder.instance { binding =>
    Json.obj(
      "role" -> binding.role.asJson,
      "path" -> binding.path.asJson,
      "mediaType" -> binding.mediaType.asJson,
      "checksum" -> binding.checksum.asJson
    )
  }

  given Decoder[ProfileArtifactBinding] = Decoder.instance { c =>
    for
      role <- field[ArtifactRole](c, "role")
      path <- field[BundlePath](c, "path")
      mediaType <- field[MediaTypeId](c, "mediaType")
      checksum <- field[Checksum](c, "checksum")
    yield ProfileArtifactBinding(role, path, mediaType, checksum)
  }

  given Encoder[ProfileCourtDisposition] = Encoder.instance {
    case ProfileCourtDisposition.Passed                 => tagged("passed")
    case ProfileCourtDisposition.Failed(code, evidence) =>
      Json.obj(
        "status" -> "failed".asJson,
        "code" -> code.asJson,
        "evidence" -> evidence.asJson
      )
  }

  given Decoder[ProfileCourtDisposition] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "passed" => Right(ProfileCourtDisposition.Passed)
      case "failed" =>
        for
          code <- field[OutputFailureCode](c, "code")
          evidence <- field[OutputReceiptId](c, "evidence")
        yield ProfileCourtDisposition.Failed(code, evidence)
      case other => unknown(c, "ProfileCourtDisposition", other)
    }
  }

  given Encoder[ProfileCourtOutcome] = Encoder.instance { outcome =>
    Json.obj("court" -> outcome.court.asJson, "disposition" -> outcome.disposition.asJson)
  }

  given Decoder[ProfileCourtOutcome] = Decoder.instance { c =>
    for
      court <- field[ProfileCourtId](c, "court")
      disposition <- field[ProfileCourtDisposition](c, "disposition")
    yield ProfileCourtOutcome(court, disposition)
  }

  given Encoder[ProfileReceiptDecision] = Encoder.instance {
    case ProfileReceiptDecision.Satisfied    => tagged("satisfied")
    case ProfileReceiptDecision.Failed(code) =>
      Json.obj("status" -> "failed".asJson, "code" -> code.asJson)
    case ProfileReceiptDecision.NotAttempted(reason) =>
      Json.obj("status" -> "not_attempted".asJson, "reason" -> reason.asJson)
  }

  given Decoder[ProfileReceiptDecision] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "satisfied" => Right(ProfileReceiptDecision.Satisfied)
      case "failed" => field[OutputFailureCode](c, "code").map(ProfileReceiptDecision.Failed.apply)
      case "not_attempted" =>
        field[NotAttemptedReason](c, "reason").map(ProfileReceiptDecision.NotAttempted.apply)
      case other => unknown(c, "ProfileReceiptDecision", other)
    }
  }

  given Encoder[ProfileReceipt] = Encoder.instance { receipt =>
    Json.obj(
      "id" -> receipt.id.asJson,
      "schema" -> receipt.schema.asJson,
      "verifier" -> receipt.verifier.asJson,
      "execution" -> receipt.execution.asJson,
      "policy" -> receipt.policy.asJson,
      "decision" -> receipt.decision.asJson,
      "prerequisites" -> receipt.prerequisites.asJson,
      "preview" -> receipt.preview.asJson,
      "requiredAssets" -> receipt.requiredAssets.asJson,
      "courts" -> receipt.courts.asJson
    )
  }

  given Decoder[ProfileReceipt] = Decoder.instance { c =>
    for
      id <- field[OutputReceiptId](c, "id")
      schema <- field[ProfileSchemaId](c, "schema")
      verifier <- field[ProfileVerifierId](c, "verifier")
      execution <- field[OutputReceiptId](c, "execution")
      policy <- field[OutputSchemaId](c, "policy")
      decision <- field[ProfileReceiptDecision](c, "decision")
      prerequisites <- field[Vector[OutputReceiptId]](c, "prerequisites")
      preview <- field[Option[ProfileArtifactBinding]](c, "preview")
      requiredAssets <- field[Vector[ProfileArtifactBinding]](c, "requiredAssets")
      courts <- field[Vector[ProfileCourtOutcome]](c, "courts")
      _ <- uniqueWire(c, "prerequisites", prerequisites)
      _ <- uniqueWire(c, "requiredAssets", requiredAssets)
      _ <- uniqueWire(c, "courts", courts.map(_.court))
    yield ProfileReceipt(
      id,
      schema,
      verifier,
      execution,
      policy,
      decision,
      prerequisites,
      preview,
      requiredAssets,
      courts
    )
  }

  given Encoder[ProfileDisposition] = Encoder.instance {
    case ProfileDisposition.Satisfied(receipt) =>
      Json.obj("status" -> "satisfied".asJson, "receipt" -> receipt.receipt.asJson)
    case ProfileDisposition.Failed(error, receipt) =>
      Json.obj(
        "status" -> "failed".asJson,
        "error" -> error.asJson,
        "receipt" -> receipt.asJson
      )
    case ProfileDisposition.NotAttempted(reason, receipt) =>
      CanonicalPrimitives.obj(
        "status" -> "not_attempted".asJson,
        "reason" -> reason.asJson,
        "receipt" -> receipt.asJson
      )
  }

  given Decoder[ProfileDisposition] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "satisfied" =>
        for
          claim <- field[ProfileReceipt](c, "receipt")
          receipt <- domain(c, VerifiedProfileReceipt.localOpen(claim))
        yield ProfileDisposition.Satisfied(receipt)
      case "failed" =>
        for
          error <- field[OutputFailure](c, "error")
          receipt <- field[ProfileReceipt](c, "receipt")
        yield ProfileDisposition.Failed(error, receipt)
      case "not_attempted" =>
        for
          reason <- field[NotAttemptedReason](c, "reason")
          receipt <- field[ProfileReceipt](c, "receipt")
        yield ProfileDisposition.NotAttempted(reason, receipt)
      case other => unknown(c, "ProfileDisposition", other)
    }
  }

  given Encoder[BundleProfileOutcome] = Encoder.instance { outcome =>
    Json.obj(
      "profile" -> outcome.profile.asJson,
      "disposition" -> outcome.disposition.asJson
    )
  }

  given Decoder[BundleProfileOutcome] = Decoder.instance { c =>
    for
      profile <- field[BundleProfile](c, "profile")
      disposition <- field[ProfileDisposition](c, "disposition")
    yield BundleProfileOutcome(profile, disposition)
  }

  given Encoder[ArtifactRef] = Encoder.instance { artifact =>
    CanonicalPrimitives.obj(
      "id" -> artifact.id.asJson,
      "role" -> artifact.role.asJson,
      "mediaType" -> artifact.mediaType.asJson,
      "schemaVersion" -> artifact.schemaVersion.asJson,
      "byteLength" -> artifact.byteLength.asJson,
      "checksum" -> artifact.checksum.asJson
    )
  }

  given Decoder[ArtifactRef] = Decoder.instance { c =>
    for
      id <- field[ArtifactId](c, "id")
      role <- field[ArtifactRole](c, "role")
      mediaType <- field[MediaTypeId](c, "mediaType")
      schema <- field[Option[OutputSchemaId]](c, "schemaVersion")
      length <- field[Long](c, "byteLength")
      checksum <- field[Checksum](c, "checksum")
      value <- domain(c, ArtifactRef.of(id, role, mediaType, schema, length, checksum))
    yield value
  }

  given Encoder[ScientificArtifactRefs] = Encoder.instance { refs =>
    Json.obj(
      "originalSource" -> refs.originalSource.asJson,
      "canonicalSource" -> refs.canonicalSource.asJson,
      "semanticModel" -> refs.semanticModel.asJson
    )
  }

  given Encoder[ReportReceipt] = Encoder.instance { receipt =>
    Json.obj(
      "id" -> receipt.id.asJson,
      "renderer" -> receipt.renderer.asJson,
      "software" -> receipt.software.asJson,
      "inputChecksum" -> receipt.inputChecksum.asJson,
      "configChecksum" -> receipt.configChecksum.asJson
    )
  }

  private[codec] final case class ReportReceiptClaim(
      id: OutputReceiptId,
      renderer: RendererId,
      software: OutputSoftwareId,
      inputChecksum: Checksum,
      configChecksum: Checksum
  )

  private[codec] given Decoder[ReportReceiptClaim] = Decoder.instance { c =>
    for
      id <- field[OutputReceiptId](c, "id")
      renderer <- field[RendererId](c, "renderer")
      software <- field[OutputSoftwareId](c, "software")
      input <- field[Checksum](c, "inputChecksum")
      config <- field[Checksum](c, "configChecksum")
    yield ReportReceiptClaim(id, renderer, software, input, config)
  }

  given Encoder[ReportRequest] = Encoder.instance { request =>
    Json.obj(
      "id" -> request.id.asJson,
      "kind" -> request.kind.asJson,
      "role" -> request.role.asJson,
      "mediaType" -> request.mediaType.asJson,
      "requirement" -> request.requirement.asJson,
      "authority" -> request.authority.asJson,
      "requiredPayloads" -> request.requiredPayloads.toVector.sorted.asJson
    )
  }

  given Decoder[ReportRequest] = Decoder.instance { c =>
    for
      id <- field[ReportId](c, "id")
      kind <- field[ReportKind](c, "kind")
      role <- field[ArtifactRole](c, "role")
      mediaType <- field[MediaTypeId](c, "mediaType")
      requirement <- field[ArtifactRequirement](c, "requirement")
      authority <- field[ReportAuthority](c, "authority")
      required <- field[Vector[OutputPayloadId]](c, "requiredPayloads")
      _ <- uniqueWire(c, "requiredPayloads", required)
    yield ReportRequest(id, kind, role, mediaType, requirement, authority, required.toSet)
  }

  given Encoder[ReportOutcome] = Encoder.instance {
    case ReportOutcome.Produced(id, artifact, receipt) =>
      Json.obj(
        "status" -> "produced".asJson,
        "requestId" -> id.asJson,
        "artifact" -> artifact.asJson,
        "receipt" -> receipt.asJson
      )
    case ReportOutcome.Failed(id, error, receipt) =>
      Json.obj(
        "status" -> "failed".asJson,
        "requestId" -> id.asJson,
        "error" -> error.asJson,
        "receipt" -> receipt.asJson
      )
    case ReportOutcome.NotAttempted(id, reason, receipt) =>
      CanonicalPrimitives.obj(
        "status" -> "not_attempted".asJson,
        "requestId" -> id.asJson,
        "reason" -> reason.asJson,
        "receipt" -> receipt.asJson
      )
  }

  private[codec] enum ReportOutcomeClaim:
    case Produced(id: ReportId, artifact: ArtifactRef, receipt: ReportReceiptClaim)
    case Failed(id: ReportId, error: OutputFailure, receipt: ReportReceiptClaim)
    case NotAttempted(
        id: ReportId,
        reason: NotAttemptedReason,
        receipt: Option[ReportReceiptClaim]
    )

  private[codec] given Decoder[ReportOutcomeClaim] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "produced" =>
        for
          id <- field[ReportId](c, "requestId")
          artifact <- field[ArtifactRef](c, "artifact")
          receipt <- field[ReportReceiptClaim](c, "receipt")
        yield ReportOutcomeClaim.Produced(id, artifact, receipt)
      case "failed" =>
        for
          id <- field[ReportId](c, "requestId")
          error <- field[OutputFailure](c, "error")
          receipt <- field[ReportReceiptClaim](c, "receipt")
        yield ReportOutcomeClaim.Failed(id, error, receipt)
      case "not_attempted" =>
        for
          id <- field[ReportId](c, "requestId")
          reason <- field[NotAttemptedReason](c, "reason")
          receipt <- field[Option[ReportReceiptClaim]](c, "receipt")
        yield ReportOutcomeClaim.NotAttempted(id, reason, receipt)
      case other => unknown(c, "ReportOutcome", other)
    }
  }

  given Encoder[ProjectionRequest] = Encoder.instance { request =>
    Json.obj(
      "id" -> request.id.asJson,
      "requiredPayloads" -> request.requiredPayloads.toVector.sorted.asJson
    )
  }

  given Decoder[ProjectionRequest] = Decoder.instance { c =>
    for
      id <- field[ProjectionRequestId](c, "id")
      required <- field[Vector[OutputPayloadId]](c, "requiredPayloads")
      _ <- uniqueWire(c, "requiredPayloads", required)
    yield ProjectionRequest(id, required.toSet)
  }

  given Encoder[ProjectionDisposition] = Encoder.instance {
    case ProjectionDisposition.Produced(artifact, marks, receipt) =>
      CanonicalPrimitives.obj(
        "status" -> "produced".asJson,
        "artifact" -> artifact.asJson,
        "marks" -> marks.asJson,
        "receipt" -> receipt.asJson
      )
    case ProjectionDisposition.EstablishedEmpty(receipt) =>
      Json.obj("status" -> "established_empty".asJson, "receipt" -> receipt.asJson)
    case ProjectionDisposition.FilteredOrSuppressed(retained, suppressed, reason, receipt) =>
      Json.obj(
        "status" -> "filtered_or_suppressed".asJson,
        "retained" -> retained.asJson,
        "suppressed" -> suppressed.asJson,
        "reason" -> reason.asJson,
        "receipt" -> receipt.asJson
      )
    case ProjectionDisposition.Unsupported(error) =>
      Json.obj("status" -> "unsupported".asJson, "error" -> error.asJson)
    case ProjectionDisposition.Failed(error, receipt) =>
      Json.obj(
        "status" -> "failed".asJson,
        "error" -> error.asJson,
        "receipt" -> receipt.asJson
      )
  }

  private[codec] enum ProjectionDispositionClaim:
    case Produced(
        artifact: Option[ArtifactRef],
        marks: OutputCount,
        receipt: ReportReceiptClaim
    )
    case EstablishedEmpty(receipt: ReportReceiptClaim)
    case FilteredOrSuppressed(
        retained: OutputCount,
        suppressed: OutputCount,
        reason: SuppressionReason,
        receipt: ReportReceiptClaim
    )
    case Unsupported(error: OutputFailure)
    case Failed(error: OutputFailure, receipt: ReportReceiptClaim)

  private[codec] given Decoder[ProjectionDispositionClaim] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "produced" =>
        for
          artifact <- field[Option[ArtifactRef]](c, "artifact")
          marks <- field[OutputCount](c, "marks")
          receipt <- field[ReportReceiptClaim](c, "receipt")
        yield ProjectionDispositionClaim.Produced(artifact, marks, receipt)
      case "established_empty" =>
        field[ReportReceiptClaim](c, "receipt")
          .map(ProjectionDispositionClaim.EstablishedEmpty.apply)
      case "filtered_or_suppressed" =>
        for
          retained <- field[OutputCount](c, "retained")
          suppressed <- field[OutputCount](c, "suppressed")
          reason <- field[SuppressionReason](c, "reason")
          receipt <- field[ReportReceiptClaim](c, "receipt")
        yield ProjectionDispositionClaim.FilteredOrSuppressed(
          retained,
          suppressed,
          reason,
          receipt
        )
      case "unsupported" =>
        field[OutputFailure](c, "error").map(ProjectionDispositionClaim.Unsupported.apply)
      case "failed" =>
        for
          error <- field[OutputFailure](c, "error")
          receipt <- field[ReportReceiptClaim](c, "receipt")
        yield ProjectionDispositionClaim.Failed(error, receipt)
      case other => unknown(c, "ProjectionDisposition", other)
    }
  }

  given Encoder[ProjectionOutcome] = Encoder.instance { outcome =>
    Json.obj("requestId" -> outcome.requestId.asJson, "disposition" -> outcome.disposition.asJson)
  }

  private[codec] final case class ProjectionOutcomeClaim(
      requestId: ProjectionRequestId,
      disposition: ProjectionDispositionClaim
  )

  private[codec] given Decoder[ProjectionOutcomeClaim] = Decoder.instance { c =>
    for
      id <- field[ProjectionRequestId](c, "requestId")
      disposition <- field[ProjectionDispositionClaim](c, "disposition")
    yield ProjectionOutcomeClaim(id, disposition)
  }

  given Encoder[ViewBasis] = Encoder.instance {
    case ViewBasis.ValidatedBuild            => tagged("validated_build")
    case ViewBasis.HumanAdjudicated          => tagged("human_adjudicated")
    case ViewBasis.ResearcherReviewedFixture => tagged("researcher_reviewed_fixture")
  }
  given Decoder[ViewBasis] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "validated_build"             => Right(ViewBasis.ValidatedBuild)
      case "human_adjudicated"           => Right(ViewBasis.HumanAdjudicated)
      case "researcher_reviewed_fixture" => Right(ViewBasis.ResearcherReviewedFixture)
      case other                         => unknown(c, "ViewBasis", other)
    }
  }

  given Encoder[BasisAuthority] = Encoder.instance {
    case BasisAuthority.ValidatedBuild(buildReceiptChecksum) =>
      Json.obj(
        "status" -> "validated_build".asJson,
        "buildReceiptChecksum" -> buildReceiptChecksum.asJson
      )
    case BasisAuthority.HumanAdjudication(receipt) =>
      Json.obj("status" -> "human_adjudication".asJson, "receipt" -> receipt.asJson)
    case BasisAuthority.FixtureReview(receipt) =>
      Json.obj("status" -> "fixture_review".asJson, "receipt" -> receipt.asJson)
  }

  given Decoder[BasisAuthority] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "validated_build" =>
        field[Checksum](c, "buildReceiptChecksum").map(BasisAuthority.ValidatedBuild.apply)
      case "human_adjudication" =>
        field[AdjudicationReceiptId](c, "receipt").map(BasisAuthority.HumanAdjudication.apply)
      case "fixture_review" =>
        field[FixtureAdmissionReceiptId](c, "receipt").map(BasisAuthority.FixtureReview.apply)
      case other => unknown(c, "BasisAuthority", other)
    }
  }

  given Encoder[AdmittedViewBasis] = Encoder.instance { basis =>
    CanonicalPrimitives.obj(
      "basis" -> basis.basis.asJson,
      "sourceChecksum" -> basis.sourceChecksum.asJson,
      "buildReceiptChecksum" -> basis.buildReceiptChecksum.asJson,
      "authority" -> basis.authority.asJson
    )
  }

  private[codec] final case class AdmittedViewBasisClaim(
      basis: ViewBasis,
      sourceChecksum: Checksum,
      buildReceiptChecksum: Option[Checksum],
      authority: BasisAuthority
  )

  private[codec] given Decoder[AdmittedViewBasisClaim] = Decoder.instance { c =>
    for
      basis <- field[ViewBasis](c, "basis")
      source <- field[Checksum](c, "sourceChecksum")
      build <- field[Option[Checksum]](c, "buildReceiptChecksum")
      authority <- field[BasisAuthority](c, "authority")
    yield AdmittedViewBasisClaim(basis, source, build, authority)
  }

  given Encoder[ArtifactDisposition] = Encoder.instance {
    case ArtifactDisposition.Produced(artifact) =>
      Json.obj("status" -> "produced".asJson, "artifact" -> artifact.asJson)
    case ArtifactDisposition.Failed(error, receipt) =>
      Json.obj(
        "status" -> "failed".asJson,
        "error" -> error.asJson,
        "receipt" -> receipt.asJson
      )
    case ArtifactDisposition.NotAttempted(reason, receipt) =>
      CanonicalPrimitives.obj(
        "status" -> "not_attempted".asJson,
        "reason" -> reason.asJson,
        "receipt" -> receipt.asJson
      )
    case ArtifactDisposition.AbsentBySemanticContract(reason) =>
      Json.obj("status" -> "absent_by_semantic_contract".asJson, "reason" -> reason.asJson)
  }

  given Decoder[ArtifactDisposition] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "produced" => field[ArtifactRef](c, "artifact").map(ArtifactDisposition.Produced.apply)
      case "failed"   =>
        for
          error <- field[OutputFailure](c, "error")
          receipt <- field[OutputReceiptId](c, "receipt")
        yield ArtifactDisposition.Failed(error, receipt)
      case "not_attempted" =>
        for
          reason <- field[NotAttemptedReason](c, "reason")
          receipt <- field[Option[OutputReceiptId]](c, "receipt")
        yield ArtifactDisposition.NotAttempted(reason, receipt)
      case "absent_by_semantic_contract" =>
        field[SemanticAbsenceReason](c, "reason")
          .map(ArtifactDisposition.AbsentBySemanticContract.apply)
      case other => unknown(c, "ArtifactDisposition", other)
    }
  }

  given Encoder[ManifestEntry] = Encoder.instance { entry =>
    CanonicalPrimitives.obj(
      "role" -> entry.role.asJson,
      "path" -> entry.path.asJson,
      "mediaType" -> entry.mediaType.asJson,
      "schemaVersion" -> entry.schemaVersion.asJson,
      "requirement" -> entry.requirement.asJson,
      "disposition" -> entry.disposition.asJson,
      "payload" -> entry.payload.asJson
    )
  }

  given Decoder[ManifestEntry] = Decoder.instance { c =>
    for
      role <- field[ArtifactRole](c, "role")
      path <- field[BundlePath](c, "path")
      media <- field[MediaTypeId](c, "mediaType")
      schema <- field[Option[OutputSchemaId]](c, "schemaVersion")
      requirement <- field[ArtifactRequirement](c, "requirement")
      disposition <- field[ArtifactDisposition](c, "disposition")
      payload <- field[Option[OutputPayloadId]](c, "payload")
    yield ManifestEntry(role, path, media, schema, requirement, disposition, payload)
  }

  private def tagged(status: String): Json = Json.obj("status" -> status.asJson)

  private def identified(status: String, id: ArtifactId): Json =
    Json.obj("status" -> status.asJson, "id" -> id.asJson)

  private def custom(namespace: OutputNamespace, label: OutputLabel): Json =
    Json.obj("status" -> "custom".asJson, "namespace" -> namespace.asJson, "label" -> label.asJson)

  private def decodeCustom[A](
      c: HCursor,
      make: (OutputNamespace, OutputLabel) => A
  ): Decoder.Result[A] =
    for
      namespace <- field[OutputNamespace](c, "namespace")
      label <- field[OutputLabel](c, "label")
    yield make(namespace, label)

  private def unknown[A](c: HCursor, kind: String, value: String): Decoder.Result[A] =
    Left(io.circe.DecodingFailure(s"unknown $kind status: $value", c.history))

  private def uniqueWire[A](
      c: HCursor,
      fieldName: String,
      values: Vector[A]
  ): Decoder.Result[Unit] =
    if values.distinct.size == values.size then Right(())
    else Left(io.circe.DecodingFailure(s"duplicate $fieldName identity", c.history))

/** Versioned canonical codec for the composed story-output result. */
object StoryOutputResultCodec:
  val SchemaVersion: String = "story-output-result/v2"

  import OutputCodecs.*
  import OutputCodecs.given

  /** Encode the envelope without duplicating StoryModelCodec semantic bytes. */
  def encode[Id: Encoder](result: StoryOutputResult[Id]): String =
    val acquisition = result.acquisition
    val json = CanonicalPrimitives.obj(
      "schemaVersion" -> SchemaVersion.asJson,
      "invocationId" -> acquisition.invocationId.asJson,
      "source" -> acquisition.source.asJson,
      "acquisition" -> Json.obj(
        "universe" -> acquisition.universe.asJson,
        "semantic" -> acquisition.semantic.asJson,
        "targets" -> acquisition.targets.asJson,
        "payloads" -> acquisition.payloads.asJson,
        "buildReceipt" -> acquisition.buildReceipt.asJson,
        "viewAuthority" -> acquisition.viewAuthority.asJson
      ),
      "scientificArtifacts" -> result.scientificArtifacts.asJson,
      "viewBasis" -> result.basis.asJson,
      "reportRequests" -> result.reportRequests.asJson,
      "reportOutcomes" -> result.reportOutcomes.asJson,
      "projectionRequests" -> result.projectionRequests.asJson,
      "projectionOutcomes" -> result.projectionOutcomes.asJson
    )
    Canonical.print(json)

  /** Bare metadata cannot establish source construction; use the byte-context overload. */
  def decode[Id: Decoder: OutputTargetIdentity](
      text: String
  ): Either[CodecError, StoryOutputResult[Id]] =
    decodeWith(text, c => field[SourceOutcome](c, "source"))

  /** Decode while re-running source admission against the exact original bytes. */
  def decode[Id: Decoder: OutputTargetIdentity](
      text: String,
      originalBytes: Array[Byte]
  ): Either[CodecError, StoryOutputResult[Id]] =
    decodeWith(
      text,
      c =>
        c.downField("source").success match
          case Some(sourceCursor) =>
            OutputAcquireCodecs.admitSourceOutcome(sourceCursor, originalBytes)
          case None => Left(io.circe.DecodingFailure("missing source", c.history))
    )

  private def decodeWith[Id: Decoder: OutputTargetIdentity](
      text: String,
      decodeSource: HCursor => Decoder.Result[SourceOutcome]
  ): Either[CodecError, StoryOutputResult[Id]] =
    Canonical.parse(text).flatMap { json =>
      val decoder: Decoder[StoryOutputResult[Id]] = Decoder.instance { c =>
        for
          version <- field[String](c, "schemaVersion")
          _ <- requireVersion(c, version, SchemaVersion)
          invocation <- field[InvocationId](c, "invocationId")
          source <- decodeSource(c)
          ac <- c
            .downField("acquisition")
            .success
            .toRight(io.circe.DecodingFailure("missing acquisition", c.history))
          universe <- field[TargetUniverse[Id]](ac, "universe")
          semantic <- field[SemanticOutcome](ac, "semantic")
          targets <- field[Vector[TargetAccount[Id]]](ac, "targets")
          payloads <- field[Vector[OutputPayload]](ac, "payloads")
          receipt <- field[Option[ExtendedBuildReceipt]](ac, "buildReceipt")
          authorityClaim <- field[Option[OutputAcquireCodecs.AcquisitionViewAuthorityClaim]](
            ac,
            "viewAuthority"
          )
          authority <- authorityClaim.traverse(claim =>
            domain(
              c,
              AcquisitionViewAuthority.fromWire(
                source,
                receipt,
                claim.kind,
                claim.sourceChecksum,
                claim.buildReceiptChecksum,
                claim.evidenceChecksum,
                claim.adjudicationReceipt,
                claim.fixtureReceipt
              )
            )
          )
          acquisition <- domainValidated(
            c,
            AcquisitionAccount.of(
              invocation,
              source,
              universe,
              semantic,
              targets,
              payloads,
              receipt,
              authority
            )
          )
          scientificCursor <- c
            .downField("scientificArtifacts")
            .success
            .toRight(io.circe.DecodingFailure("missing scientificArtifacts", c.history))
          originalSource <- field[Option[ArtifactRef]](scientificCursor, "originalSource")
          canonicalSource <- field[Option[ArtifactRef]](scientificCursor, "canonicalSource")
          semanticModel <- field[Option[ArtifactRef]](scientificCursor, "semanticModel")
          scientificArtifacts <- domainValidated(
            c,
            ScientificArtifactRefs.of(
              acquisition,
              originalSource,
              canonicalSource,
              semanticModel
            )
          )
          basisClaim <- field[Option[AdmittedViewBasisClaim]](c, "viewBasis")
          basis <- basisClaim.traverse(claim =>
            domain(
              c,
              AdmittedViewBasis.fromWire(
                acquisition,
                claim.basis,
                claim.sourceChecksum,
                claim.buildReceiptChecksum,
                claim.authority
              )
            )
          )
          reportRequests <- field[Vector[ReportRequest]](c, "reportRequests")
          reportClaims <- field[Vector[ReportOutcomeClaim]](c, "reportOutcomes")
          reportOutcomes <- reportClaims.traverse(claim =>
            admitReportOutcome(c, acquisition, scientificArtifacts, basis, reportRequests, claim)
          )
          projectionRequests <- field[Vector[ProjectionRequest]](c, "projectionRequests")
          projectionClaims <- field[Vector[ProjectionOutcomeClaim]](c, "projectionOutcomes")
          projectionOutcomes <- projectionClaims.traverse(claim =>
            admitProjectionOutcome(
              c,
              acquisition,
              scientificArtifacts,
              basis,
              projectionRequests,
              claim
            )
          )
          result <- domainValidated(
            c,
            StoryOutputResult.of(
              acquisition,
              scientificArtifacts,
              basis,
              reportRequests,
              reportOutcomes,
              projectionRequests,
              projectionOutcomes
            )
          )
        yield result
      }
      decoder
        .decodeJson(json)
        .left
        .map(failure =>
          if failure.message.startsWith("unsupported schema") then
            CodecError.UnsupportedSchema(
              json.hcursor.downField("schemaVersion").as[String].getOrElse("<missing>"),
              Vector(SchemaVersion)
            )
          else CodecError.Decode(pathOf(failure), failure.message)
        )
    }

  private def admitReportOutcome[Id: OutputTargetIdentity](
      c: HCursor,
      acquisition: AcquisitionAccount[Id],
      artifacts: ScientificArtifactRefs,
      basis: Option[AdmittedViewBasis],
      requests: Vector[ReportRequest],
      claim: ReportOutcomeClaim
  ): Decoder.Result[ReportOutcome] =
    val id = claim match
      case ReportOutcomeClaim.Produced(value, _, _)     => value
      case ReportOutcomeClaim.Failed(value, _, _)       => value
      case ReportOutcomeClaim.NotAttempted(value, _, _) => value
    requests.find(_.id == id) match
      case None =>
        Left(io.circe.DecodingFailure(s"report outcome has unknown request ${id.value}", c.history))
      case Some(request) =>
        domain(c, ReportInputIdentity.forReport(acquisition, artifacts, basis, request)).flatMap {
          expected =>
            claim match
              case ReportOutcomeClaim.Produced(_, artifact, receipt) =>
                admitReportReceipt(c, receipt, expected).map(
                  ReportOutcome.Produced(id, artifact, _)
                )
              case ReportOutcomeClaim.Failed(_, error, receipt) =>
                admitReportReceipt(c, receipt, expected).map(ReportOutcome.Failed(id, error, _))
              case ReportOutcomeClaim.NotAttempted(_, reason, receipt) =>
                receipt
                  .traverse(admitReportReceipt(c, _, expected))
                  .map(ReportOutcome.NotAttempted(id, reason, _))
        }

  private def admitProjectionOutcome[Id: OutputTargetIdentity](
      c: HCursor,
      acquisition: AcquisitionAccount[Id],
      artifacts: ScientificArtifactRefs,
      basis: Option[AdmittedViewBasis],
      requests: Vector[ProjectionRequest],
      claim: ProjectionOutcomeClaim
  ): Decoder.Result[ProjectionOutcome] =
    requests.find(_.id == claim.requestId) match
      case None =>
        Left(
          io.circe.DecodingFailure(
            s"projection outcome has unknown request ${claim.requestId.value}",
            c.history
          )
        )
      case Some(request) =>
        domain(c, ReportInputIdentity.forProjection(acquisition, artifacts, basis, request))
          .flatMap { expected =>
            val disposition = claim.disposition match
              case ProjectionDispositionClaim.Produced(artifact, marks, receipt) =>
                admitReportReceipt(c, receipt, expected)
                  .map(ProjectionDisposition.Produced(artifact, marks, _))
              case ProjectionDispositionClaim.EstablishedEmpty(receipt) =>
                admitReportReceipt(c, receipt, expected)
                  .map(ProjectionDisposition.EstablishedEmpty.apply)
              case ProjectionDispositionClaim.FilteredOrSuppressed(
                    retained,
                    suppressed,
                    reason,
                    receipt
                  ) =>
                admitReportReceipt(c, receipt, expected).map(
                  ProjectionDisposition.FilteredOrSuppressed(retained, suppressed, reason, _)
                )
              case ProjectionDispositionClaim.Unsupported(error) =>
                Right(ProjectionDisposition.Unsupported(error))
              case ProjectionDispositionClaim.Failed(error, receipt) =>
                admitReportReceipt(c, receipt, expected).map(ProjectionDisposition.Failed(error, _))
            disposition.map(ProjectionOutcome(claim.requestId, _))
          }

  private def admitReportReceipt(
      c: HCursor,
      claim: ReportReceiptClaim,
      expected: ReportInputIdentity
  ): Decoder.Result[ReportReceipt] =
    domain(
      c,
      ReportReceipt.fromWire(
        claim.id,
        claim.renderer,
        claim.software,
        claim.inputChecksum,
        claim.configChecksum,
        expected
      )
    )

  private def requireVersion(
      c: HCursor,
      found: String,
      expected: String
  ): Decoder.Result[Unit] =
    if found == expected then Right(())
    else Left(io.circe.DecodingFailure(s"unsupported schema $found", c.history))

  private def domainValidated[A](
      c: HCursor,
      value: cats.data.ValidatedNec[DomainError, A]
  ): Decoder.Result[A] =
    value.toEither.left.map(errors =>
      io.circe
        .DecodingFailure(errors.toNonEmptyList.map(_.message).toList.mkString("; "), c.history)
    )

  private def pathOf(failure: io.circe.DecodingFailure): String =
    io.circe.CursorOp.opsToPath(failure.history) match
      case ""   => "$"
      case path => path

/** Versioned canonical manifest codec whose BundleId is external to its bytes. */
object BundleManifestCodec:
  val SchemaVersion: String = "story-output-manifest/v2"

  import OutputCodecs.given

  /** Encode only manifest state; the manifest never contains its own checksum. */
  def encode[Id](manifest: BundleManifest[Id]): String =
    Canonical.print(
      Json.obj(
        "schemaVersion" -> SchemaVersion.asJson,
        "profileOutcomes" -> manifest.profileOutcomes.asJson,
        "entries" -> manifest.entries.asJson
      )
    )

  /** SHA-256 of final canonical manifest bytes, intentionally not stored within the manifest. */
  def bundleId[Id](manifest: BundleManifest[Id]): Checksum = Checksum.ofText(encode(manifest))

  /** Decode the wire root and validate it against the already decoded result account. */
  def decode[Id](
      text: String,
      result: StoryOutputResult[Id]
  ): Either[CodecError, BundleManifest[Id]] =
    Canonical.parse(text).flatMap { json =>
      val cursor = json.hcursor
      val decoded = for
        version <- field[String](cursor, "schemaVersion")
        _ <-
          if version == SchemaVersion then Right(())
          else Left(io.circe.DecodingFailure(s"unsupported schema $version", cursor.history))
        profiles <- field[Vector[BundleProfileOutcome]](cursor, "profileOutcomes")
        entries <- field[Vector[ManifestEntry]](cursor, "entries")
        manifest <- BundleManifest
          .of(result, profiles, entries)
          .toEither
          .left
          .map(errors =>
            io.circe.DecodingFailure(
              errors.toNonEmptyList.map(_.message).toList.mkString("; "),
              cursor.history
            )
          )
      yield manifest
      decoded.left.map(failure =>
        if failure.message.startsWith("unsupported schema") then
          CodecError.UnsupportedSchema(
            cursor.downField("schemaVersion").as[String].getOrElse("<missing>"),
            Vector(SchemaVersion)
          )
        else CodecError.Decode("$", failure.message)
      )
    }
