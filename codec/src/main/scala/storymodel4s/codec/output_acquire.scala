package storymodel4s.codec

import cats.data.NonEmptyVector
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, HCursor, Json}
import io.circe.syntax.*
import storymodel4s.acquire.*
import storymodel4s.core.*

import CanonicalPrimitives.*
import CanonicalPrimitives.given
import CoreCodecs.given

/** Canonical codecs for renderer-independent acquisition output state. */
object OutputAcquireCodecs:
  given Encoder[InvocationId] = opaqueEncoder(InvocationId)
  given Decoder[InvocationId] = opaqueDecoder(InvocationId)
  given Encoder[UniverseDefinitionId] = opaqueEncoder(UniverseDefinitionId)
  given Decoder[UniverseDefinitionId] = opaqueDecoder(UniverseDefinitionId)
  given Encoder[DecoderId] = opaqueEncoder(DecoderId)
  given Decoder[DecoderId] = opaqueDecoder(DecoderId)
  given Encoder[CanonicalizationPolicyId] = opaqueEncoder(CanonicalizationPolicyId)
  given Decoder[CanonicalizationPolicyId] = opaqueDecoder(CanonicalizationPolicyId)
  given Encoder[CharsetId] = opaqueEncoder(CharsetId)
  given Decoder[CharsetId] = opaqueDecoder(CharsetId)
  given Encoder[MediaTypeId] = opaqueEncoder(MediaTypeId)
  given Decoder[MediaTypeId] = opaqueDecoder(MediaTypeId)
  given Encoder[OutputReceiptId] = opaqueEncoder(OutputReceiptId)
  given Decoder[OutputReceiptId] = opaqueDecoder(OutputReceiptId)
  given Encoder[AdjudicationReceiptId] = opaqueEncoder(AdjudicationReceiptId)
  given Decoder[AdjudicationReceiptId] = opaqueDecoder(AdjudicationReceiptId)
  given Encoder[FixtureAdmissionReceiptId] = opaqueEncoder(FixtureAdmissionReceiptId)
  given Decoder[FixtureAdmissionReceiptId] = opaqueDecoder(FixtureAdmissionReceiptId)
  given Encoder[OutputPayloadId] = opaqueEncoder(OutputPayloadId)
  given Decoder[OutputPayloadId] = opaqueDecoder(OutputPayloadId)
  given Encoder[OutputNamespace] = opaqueEncoder(OutputNamespace)
  given Decoder[OutputNamespace] = opaqueDecoder(OutputNamespace)
  given Encoder[OutputLabel] = opaqueEncoder(OutputLabel)
  given Decoder[OutputLabel] = opaqueDecoder(OutputLabel)
  given Encoder[OutputSchemaId] = opaqueEncoder(OutputSchemaId)
  given Decoder[OutputSchemaId] = opaqueDecoder(OutputSchemaId)
  given Encoder[LayerId] = opaqueEncoder(LayerId)
  given Decoder[LayerId] = opaqueDecoder(LayerId)

  given Encoder[DecodeReceipt] = Encoder.instance { receipt =>
    CanonicalPrimitives.obj(
      "id" -> receipt.id.asJson,
      "decoder" -> receipt.decoder.asJson
    )
  }
  given Decoder[DecodeReceipt] = Decoder.instance { c =>
    for
      id <- field[OutputReceiptId](c, "id")
      decoder <- field[DecoderId](c, "decoder")
    yield DecodeReceipt.bind(id, decoder)
  }

  given Encoder[BomDisposition] = Encoder.instance {
    case BomDisposition.Absent          => tagged("absent")
    case BomDisposition.ConsumedUtf8    => tagged("consumed_utf8")
    case BomDisposition.ConsumedUtf16Le => tagged("consumed_utf16_le")
    case BomDisposition.ConsumedUtf16Be => tagged("consumed_utf16_be")
    case BomDisposition.Preserved       => tagged("preserved")
    case BomDisposition.Rejected        => tagged("rejected")
  }
  given Decoder[BomDisposition] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "absent"            => Right(BomDisposition.Absent)
      case "consumed_utf8"     => Right(BomDisposition.ConsumedUtf8)
      case "consumed_utf16_le" => Right(BomDisposition.ConsumedUtf16Le)
      case "consumed_utf16_be" => Right(BomDisposition.ConsumedUtf16Be)
      case "preserved"         => Right(BomDisposition.Preserved)
      case "rejected"          => Right(BomDisposition.Rejected)
      case other               => unknown(c, "BomDisposition", other)
    }
  }

  given Encoder[ExtensionRequirement] = Encoder.instance {
    case ExtensionRequirement.Optional => tagged("optional")
    case ExtensionRequirement.Required => tagged("required")
  }
  given Decoder[ExtensionRequirement] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "optional" => Right(ExtensionRequirement.Optional)
      case "required" => Right(ExtensionRequirement.Required)
      case other      => unknown(c, "ExtensionRequirement", other)
    }
  }

  given Encoder[TargetDisposition] = Encoder.instance {
    case TargetDisposition.Accepted     => tagged("accepted")
    case TargetDisposition.Alternatives => tagged("alternatives")
    case TargetDisposition.Rejected     => tagged("rejected")
    case TargetDisposition.Unresolved   => tagged("unresolved")
    case TargetDisposition.Excluded     => tagged("excluded")
    case TargetDisposition.Failed       => tagged("failed")
  }
  given Decoder[TargetDisposition] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "accepted"     => Right(TargetDisposition.Accepted)
      case "alternatives" => Right(TargetDisposition.Alternatives)
      case "rejected"     => Right(TargetDisposition.Rejected)
      case "unresolved"   => Right(TargetDisposition.Unresolved)
      case "excluded"     => Right(TargetDisposition.Excluded)
      case "failed"       => Right(TargetDisposition.Failed)
      case other          => unknown(c, "TargetDisposition", other)
    }
  }

  given Encoder[LayerCoverage] = Encoder.instance {
    case LayerCoverage.NotAttempted => tagged("not_attempted")
    case LayerCoverage.Attempted    => tagged("attempted")
  }
  given Decoder[LayerCoverage] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "not_attempted" => Right(LayerCoverage.NotAttempted)
      case "attempted"     => Right(LayerCoverage.Attempted)
      case other           => unknown(c, "LayerCoverage", other)
    }
  }

  given Encoder[UniverseFailureReason] = Encoder.instance {
    case UniverseFailureReason.PlanningFailed           => tagged("planning_failed")
    case UniverseFailureReason.EligibilityFailed        => tagged("eligibility_failed")
    case UniverseFailureReason.UpstreamUnavailable      => tagged("upstream_unavailable")
    case UniverseFailureReason.InvalidDefinition        => tagged("invalid_definition")
    case UniverseFailureReason.Custom(namespace, label) =>
      custom("custom", namespace, label)
  }

  given Decoder[UniverseFailureReason] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "planning_failed"      => Right(UniverseFailureReason.PlanningFailed)
      case "eligibility_failed"   => Right(UniverseFailureReason.EligibilityFailed)
      case "upstream_unavailable" => Right(UniverseFailureReason.UpstreamUnavailable)
      case "invalid_definition"   => Right(UniverseFailureReason.InvalidDefinition)
      case "custom"               => decodeCustom(c, UniverseFailureReason.Custom.apply)
      case other                  => unknown(c, "UniverseFailureReason", other)
    }
  }

  given Encoder[OutputFailureCode] = Encoder.instance {
    case OutputFailureCode.SourceUnavailable        => tagged("source_unavailable")
    case OutputFailureCode.DecodeFailed             => tagged("decode_failed")
    case OutputFailureCode.CanonicalizationFailed   => tagged("canonicalization_failed")
    case OutputFailureCode.PlanningFailed           => tagged("planning_failed")
    case OutputFailureCode.ResolutionFailed         => tagged("resolution_failed")
    case OutputFailureCode.ValidationFailed         => tagged("validation_failed")
    case OutputFailureCode.RenderingFailed          => tagged("rendering_failed")
    case OutputFailureCode.SerializationFailed      => tagged("serialization_failed")
    case OutputFailureCode.IntegrityFailed          => tagged("integrity_failed")
    case OutputFailureCode.UnsupportedCapability    => tagged("unsupported_capability")
    case OutputFailureCode.Custom(namespace, label) => custom("custom", namespace, label)
  }

  given Decoder[OutputFailureCode] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "source_unavailable"      => Right(OutputFailureCode.SourceUnavailable)
      case "decode_failed"           => Right(OutputFailureCode.DecodeFailed)
      case "canonicalization_failed" => Right(OutputFailureCode.CanonicalizationFailed)
      case "planning_failed"         => Right(OutputFailureCode.PlanningFailed)
      case "resolution_failed"       => Right(OutputFailureCode.ResolutionFailed)
      case "validation_failed"       => Right(OutputFailureCode.ValidationFailed)
      case "rendering_failed"        => Right(OutputFailureCode.RenderingFailed)
      case "serialization_failed"    => Right(OutputFailureCode.SerializationFailed)
      case "integrity_failed"        => Right(OutputFailureCode.IntegrityFailed)
      case "unsupported_capability"  => Right(OutputFailureCode.UnsupportedCapability)
      case "custom"                  => decodeCustom(c, OutputFailureCode.Custom.apply)
      case other                     => unknown(c, "OutputFailureCode", other)
    }
  }

  given Encoder[ResultGapKind] = Encoder.instance {
    case ResultGapKind.MissingStage             => tagged("missing_stage")
    case ResultGapKind.Unresolved               => tagged("unresolved")
    case ResultGapKind.Rejected                 => tagged("rejected")
    case ResultGapKind.Unsupported              => tagged("unsupported")
    case ResultGapKind.Failed                   => tagged("failed")
    case ResultGapKind.Custom(namespace, label) => custom("custom", namespace, label)
  }

  given Decoder[ResultGapKind] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "missing_stage" => Right(ResultGapKind.MissingStage)
      case "unresolved"    => Right(ResultGapKind.Unresolved)
      case "rejected"      => Right(ResultGapKind.Rejected)
      case "unsupported"   => Right(ResultGapKind.Unsupported)
      case "failed"        => Right(ResultGapKind.Failed)
      case "custom"        => decodeCustom(c, ResultGapKind.Custom.apply)
      case other           => unknown(c, "ResultGapKind", other)
    }
  }

  given Encoder[OriginalSourceIdentity] = Encoder.instance { source =>
    CanonicalPrimitives.obj(
      "byteLength" -> source.byteLength.asJson,
      "checksum" -> source.checksum.asJson,
      "mediaType" -> source.mediaType.asJson,
      "declaredCharset" -> source.declaredCharset.asJson,
      "selectedCharset" -> source.selectedCharset.asJson,
      "bom" -> source.bom.asJson,
      "intakeReceipt" -> source.intakeReceipt.asJson
    )
  }

  given Decoder[OriginalSourceIdentity] = Decoder.instance { c =>
    for
      length <- field[Long](c, "byteLength")
      checksum <- field[Checksum](c, "checksum")
      mediaType <- field[MediaTypeId](c, "mediaType")
      declared <- field[Option[CharsetId]](c, "declaredCharset")
      selected <- field[CharsetId](c, "selectedCharset")
      bom <- field[BomDisposition](c, "bom")
      receipt <- field[OutputReceiptId](c, "intakeReceipt")
      value <- domain(
        c,
        OriginalSourceIdentity.of(length, checksum, mediaType, declared, selected, bom, receipt)
      )
    yield value
  }

  given Encoder[DecodedSourceIdentity] = Encoder.instance { source =>
    CanonicalPrimitives.obj(
      "decoder" -> source.decoder.asJson,
      "utf16Length" -> source.utf16Length.asJson,
      "checksum" -> source.checksum.asJson,
      "decodeReceipt" -> source.decodeReceipt.asJson
    )
  }

  given Decoder[DecodedSourceIdentity] = Decoder.instance { c =>
    for
      decoder <- field[DecoderId](c, "decoder")
      length <- field[Int](c, "utf16Length")
      checksum <- field[Checksum](c, "checksum")
      receipt <- field[DecodeReceipt](c, "decodeReceipt")
      _ <- requireDecoderBinding(c, decoder, receipt)
      value <- domain(c, DecodedSourceIdentity.of(length, checksum, receipt))
    yield value
  }

  given Encoder[SourceIdentities] = Encoder.instance { source =>
    Json.obj(
      "original" -> source.original.asJson,
      "storyId" -> source.storyId.asJson,
      "decoder" -> source.decoder.asJson,
      "decodedUtf16Length" -> source.decodedUtf16Length.asJson,
      "decodedChecksum" -> source.decodedChecksum.asJson,
      "canonicalPolicy" -> source.canonicalPolicy.asJson,
      "canonicalByteLength" -> source.canonicalByteLength.asJson,
      "canonicalUtf16Length" -> source.canonicalUtf16Length.asJson,
      "canonicalChecksum" -> source.canonicalChecksum.asJson,
      "decodeReceipt" -> source.decodeReceipt.asJson,
      "canonicalizationReceipt" -> source.canonicalizationReceipt.asJson
    )
  }

  given Decoder[SourceIdentities] = Decoder.instance { c =>
    for
      original <- field[OriginalSourceIdentity](c, "original")
      story <- field[StoryId](c, "storyId")
      decoder <- field[DecoderId](c, "decoder")
      decodedLength <- field[Int](c, "decodedUtf16Length")
      decodedChecksum <- field[Checksum](c, "decodedChecksum")
      policy <- field[CanonicalizationPolicyId](c, "canonicalPolicy")
      canonicalByteLength <- field[Long](c, "canonicalByteLength")
      canonicalUtf16Length <- field[Int](c, "canonicalUtf16Length")
      canonicalChecksum <- field[Checksum](c, "canonicalChecksum")
      decodeReceipt <- field[DecodeReceipt](c, "decodeReceipt")
      _ <- requireDecoderBinding(c, decoder, decodeReceipt)
      canonicalizationReceipt <- field[OutputReceiptId](c, "canonicalizationReceipt")
      value <- domainValidated(
        c,
        SourceIdentities.of(
          original,
          story,
          decodedLength,
          decodedChecksum,
          policy,
          canonicalByteLength,
          canonicalUtf16Length,
          canonicalChecksum,
          decodeReceipt,
          canonicalizationReceipt
        )
      )
    yield value
  }

  private def requireDecoderBinding(
      c: HCursor,
      claimed: DecoderId,
      receipt: DecodeReceipt
  ): Decoder.Result[Unit] =
    if claimed == receipt.decoder then Right(())
    else
      Left(
        io.circe.DecodingFailure(
          "decoder identity disagrees with the typed decode receipt",
          c.history
        )
      )

  given Encoder[OutputFailure] = Encoder.instance { failure =>
    CanonicalPrimitives.obj(
      "code" -> failure.code.asJson,
      "receipt" -> failure.receipt.asJson,
      "stage" -> failure.stage.asJson,
      "evidence" -> failure.evidence.asJson
    )
  }

  given Decoder[OutputFailure] = Decoder.instance { c =>
    for
      code <- field[OutputFailureCode](c, "code")
      receipt <- field[OutputReceiptId](c, "receipt")
      stage <- field[Option[StageId]](c, "stage")
      evidence <- field[Vector[OutputReceiptId]](c, "evidence")
      _ <-
        if evidence.distinct.size == evidence.size then Right(())
        else Left(io.circe.DecodingFailure("duplicate evidence identity", c.history))
    yield OutputFailure(code, receipt, stage, evidence)
  }

  given Encoder[RefusedSourceProgress] = Encoder.instance {
    case RefusedSourceProgress.BeforeIntake =>
      Json.obj("status" -> "before_intake".asJson)
    case RefusedSourceProgress.Admitted(original) =>
      CanonicalPrimitives.obj(
        "status" -> "admitted".asJson,
        "original" -> original.asJson
      )
    case RefusedSourceProgress.Decoded(original, decoded) =>
      CanonicalPrimitives.obj(
        "status" -> "decoded".asJson,
        "original" -> original.asJson,
        "decoded" -> decoded.asJson
      )
  }

  given Decoder[RefusedSourceProgress] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "before_intake" => Right(RefusedSourceProgress.BeforeIntake)
      case "admitted"      =>
        field[OriginalSourceIdentity](c, "original").map(RefusedSourceProgress.Admitted.apply)
      case "decoded" =>
        for
          original <- field[OriginalSourceIdentity](c, "original")
          decoded <- field[DecodedSourceIdentity](c, "decoded")
        yield RefusedSourceProgress.Decoded(original, decoded)
      case other => unknown(c, "RefusedSourceProgress", other)
    }
  }

  given Encoder[SourceOutcome] = Encoder.instance {
    case SourceOutcome.Constructed(identities) =>
      Json.obj("status" -> "constructed".asJson, "identities" -> identities.asJson)
    case SourceOutcome.Refused(progress, failure) =>
      CanonicalPrimitives.obj(
        "status" -> "refused".asJson,
        "progress" -> progress.asJson,
        "failure" -> failure.asJson
      )
  }

  given Decoder[SourceOutcome] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "constructed" =>
        field[SourceIdentities](c, "identities").map(SourceOutcome.Constructed.apply)
      case "refused" =>
        for
          progress <- field[RefusedSourceProgress](c, "progress")
          failure <- field[OutputFailure](c, "failure")
        yield SourceOutcome.Refused(progress, failure)
      case other => unknown(c, "SourceOutcome", other)
    }
  }

  given Encoder[UniverseFailure] = Encoder.instance { failure =>
    CanonicalPrimitives.obj(
      "reason" -> failure.reason.asJson,
      "receipt" -> failure.receipt.asJson,
      "stage" -> failure.stage.asJson
    )
  }

  given Decoder[UniverseFailure] = Decoder.instance { c =>
    for
      reason <- field[UniverseFailureReason](c, "reason")
      receipt <- field[OutputReceiptId](c, "receipt")
      stage <- field[Option[StageId]](c, "stage")
    yield UniverseFailure(reason, receipt, stage)
  }

  given [Id: Encoder]: Encoder[EstablishedUniverse[Id]] = Encoder.instance { universe =>
    Json.obj(
      "definitionIdentity" -> universe.definitionIdentity.asJson,
      "members" -> universe.members.asJson
    )
  }

  given [Id: Decoder]: Decoder[EstablishedUniverse[Id]] = Decoder.instance { c =>
    for
      definition <- field[UniverseDefinitionId](c, "definitionIdentity")
      members <- field[Vector[Id]](c, "members")
      value <- domain(c, EstablishedUniverse.of(members, definition))
    yield value
  }

  given [Id: Encoder]: Encoder[TargetUniverse[Id]] = Encoder.instance {
    case TargetUniverse.Established(value) =>
      Json.obj("status" -> "established".asJson, "value" -> value.asJson)
    case TargetUniverse.Unestablished(failure) =>
      Json.obj("status" -> "unestablished".asJson, "failure" -> failure.asJson)
  }

  given [Id: Decoder]: Decoder[TargetUniverse[Id]] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "established" =>
        field[EstablishedUniverse[Id]](c, "value").map(TargetUniverse.Established.apply)
      case "unestablished" =>
        field[UniverseFailure](c, "failure").map(TargetUniverse.Unestablished.apply)
      case other => unknown(c, "TargetUniverse", other)
    }
  }

  given [Id: Encoder]: Encoder[TargetAccount[Id]] = Encoder.instance { account =>
    Json.obj(
      "id" -> account.id.asJson,
      "disposition" -> account.disposition.asJson,
      "payloads" -> account.payloads.asJson
    )
  }

  given [Id: Decoder]: Decoder[TargetAccount[Id]] = Decoder.instance { c =>
    for
      id <- field[Id](c, "id")
      disposition <- field[TargetDisposition](c, "disposition")
      payloads <- field[Vector[OutputPayloadId]](c, "payloads")
    yield TargetAccount(id, disposition, payloads)
  }

  given Encoder[SemanticModelRef] = Encoder.instance { ref =>
    Json.obj(
      "storyId" -> ref.storyId.asJson,
      "sourceChecksum" -> ref.sourceChecksum.asJson,
      "schemaVersion" -> ref.schemaVersion.asJson,
      "artifactChecksum" -> ref.artifactChecksum.asJson
    )
  }

  given Decoder[SemanticModelRef] = Decoder.instance { c =>
    for
      story <- field[StoryId](c, "storyId")
      source <- field[Checksum](c, "sourceChecksum")
      schema <- field[OutputSchemaId](c, "schemaVersion")
      artifact <- field[Checksum](c, "artifactChecksum")
    yield SemanticModelRef(story, source, schema, artifact)
  }

  given Encoder[ResultGap] = Encoder.instance { gap =>
    CanonicalPrimitives.obj(
      "kind" -> gap.kind.asJson,
      "receipt" -> gap.receipt.asJson,
      "payload" -> gap.payload.asJson
    )
  }

  given Decoder[ResultGap] = Decoder.instance { c =>
    for
      kind <- field[ResultGapKind](c, "kind")
      receipt <- field[OutputReceiptId](c, "receipt")
      payload <- field[Option[OutputPayloadId]](c, "payload")
    yield ResultGap(kind, receipt, payload)
  }

  given Encoder[SemanticOutcome] = Encoder.instance {
    case SemanticOutcome.Validated(model) =>
      Json.obj("status" -> "validated".asJson, "model" -> model.asJson)
    case SemanticOutcome.Partial(gaps, draft) =>
      Json.obj(
        "status" -> "partial".asJson,
        "gaps" -> gaps.toVector.asJson,
        "draft" -> draft.asJson
      )
    case SemanticOutcome.Refused(errors) =>
      Json.obj("status" -> "refused".asJson, "errors" -> errors.toVector.asJson)
  }

  given Decoder[SemanticOutcome] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "validated" => field[SemanticModelRef](c, "model").map(SemanticOutcome.Validated.apply)
      case "partial"   =>
        for
          gaps <- field[Vector[ResultGap]](c, "gaps").flatMap(nonEmpty(c, "gaps", _))
          draft <- field[Option[SemanticModelRef]](c, "draft")
        yield SemanticOutcome.Partial(gaps, draft)
      case "refused" =>
        field[Vector[OutputFailure]](c, "errors")
          .flatMap(nonEmpty(c, "errors", _))
          .map(SemanticOutcome.Refused.apply)
      case other => unknown(c, "SemanticOutcome", other)
    }
  }

  given Encoder[KnownPayloadRef] = Encoder.instance { ref =>
    Json.obj(
      "id" -> ref.id.asJson,
      "schemaId" -> ref.schemaId.asJson,
      "checksum" -> ref.checksum.asJson
    )
  }

  given Decoder[KnownPayloadRef] = Decoder.instance { c =>
    for
      id <- field[OutputPayloadId](c, "id")
      schema <- field[OutputSchemaId](c, "schemaId")
      checksum <- field[Checksum](c, "checksum")
    yield KnownPayloadRef(id, schema, checksum)
  }

  given Encoder[UnsupportedExtension] = Encoder.instance { extension =>
    Json.obj(
      "id" -> extension.id.asJson,
      "namespace" -> extension.namespace.asJson,
      "schemaId" -> extension.schemaId.asJson,
      "checksum" -> extension.checksum.asJson,
      "payloadEncoding" -> "octets/v1".asJson,
      "payloadBytes" -> extension.payload.bytes.map(byte => byte.toInt & 0xff).asJson,
      "requirement" -> extension.requirement.asJson
    )
  }

  given Decoder[UnsupportedExtension] = Decoder.instance { c =>
    for
      id <- field[OutputPayloadId](c, "id")
      namespace <- field[OutputNamespace](c, "namespace")
      schema <- field[OutputSchemaId](c, "schemaId")
      checksum <- field[Checksum](c, "checksum")
      encoding <- field[String](c, "payloadEncoding")
      _ <-
        if encoding == "octets/v1" then Right(())
        else Left(io.circe.DecodingFailure(s"unknown payload encoding: $encoding", c.history))
      octets <- field[Vector[Int]](c, "payloadBytes")
      bytes <- octets.traverse { octet =>
        if octet >= 0 && octet <= 255 then Right(octet.toByte)
        else Left(io.circe.DecodingFailure(s"payload octet out of range: $octet", c.history))
      }
      payload <- OpaqueCanonicalPayload
        .from(bytes, checksum)
        .left
        .map(error => io.circe.DecodingFailure(error.message, c.history))
      requirement <- field[ExtensionRequirement](c, "requirement")
    yield UnsupportedExtension(id, namespace, schema, payload, requirement)
  }

  given Encoder[OutputPayload] = Encoder.instance {
    case OutputPayload.Known(ref) => Json.obj("status" -> "known".asJson, "ref" -> ref.asJson)
    case OutputPayload.Unsupported(extension) =>
      Json.obj("status" -> "unsupported_extension".asJson, "extension" -> extension.asJson)
  }

  given Decoder[OutputPayload] = Decoder.instance { c =>
    field[String](c, "status").flatMap {
      case "known" => field[KnownPayloadRef](c, "ref").map(OutputPayload.Known.apply)
      case "unsupported_extension" =>
        field[UnsupportedExtension](c, "extension").map(OutputPayload.Unsupported.apply)
      case other => unknown(c, "OutputPayload", other)
    }
  }

  given Encoder[StageRecord] = Encoder.instance { record =>
    Json.obj(
      "stage" -> record.stage.asJson,
      "key" -> record.key.checksum.asJson,
      "inputs" -> record.inputs.asJson,
      "outputs" -> record.outputs.asJson,
      "calls" -> record.calls.asJson,
      "cached" -> record.cached.asJson
    )
  }

  given Decoder[StageRecord] = Decoder.instance { c =>
    for
      stage <- field[StageId](c, "stage")
      key <- field[Checksum](c, "key")
      inputs <- field[Vector[Checksum]](c, "inputs")
      outputs <- field[Vector[Checksum]](c, "outputs")
      calls <- field[Vector[ProviderCall]](c, "calls")
      cached <- field[Boolean](c, "cached")
    yield StageRecord(stage, StageCacheKey.fromChecksum(key), inputs, outputs, calls, cached)
  }

  given Encoder[ExtendedBuildReceipt] = Encoder.instance { receipt =>
    val coverage = receipt.layerCoverage.toVector.sortBy(_._1.value).map { (layer, value) =>
      Json.obj("layer" -> layer.asJson, "coverage" -> value.asJson)
    }
    Json.obj(
      "receipt" -> receipt.receipt.asJson,
      "stages" -> receipt.stages.asJson,
      "layerCoverage" -> coverage.asJson
    )
  }

  given Decoder[ExtendedBuildReceipt] = Decoder.instance { c =>
    for
      receipt <- field[BuildReceipt](c, "receipt")
      stages <- field[Vector[StageRecord]](c, "stages")
      coverage <- c.downField("layerCoverage").as[Vector[Json]]
      pairs <- coverage.traverse { json =>
        val cursor = json.hcursor
        for
          layer <- field[LayerId](cursor, "layer")
          value <- field[LayerCoverage](cursor, "coverage")
        yield layer -> value
      }
      _ <-
        if pairs.map(_._1).distinct.size == pairs.size then Right(())
        else Left(io.circe.DecodingFailure("duplicate layerCoverage identity", c.history))
    yield ExtendedBuildReceipt(receipt, stages, pairs.toMap)
  }

  given Encoder[AcquisitionViewAuthority] = Encoder.instance { authority =>
    CanonicalPrimitives.obj(
      "status" -> (authority.kind match
        case AcquisitionViewAuthorityKind.ValidatedBuild    => "validated_build"
        case AcquisitionViewAuthorityKind.HumanAdjudication => "human_adjudication"
        case AcquisitionViewAuthorityKind.FixtureReview     => "fixture_review"
      ).asJson,
      "sourceChecksum" -> authority.sourceChecksum.asJson,
      "buildReceiptChecksum" -> authority.buildReceiptChecksum.asJson,
      "evidenceChecksum" -> authority.evidenceChecksum.asJson,
      "adjudicationReceipt" -> authority.adjudicationReceipt.asJson,
      "fixtureReceipt" -> authority.fixtureReceipt.asJson
    )
  }

  private[codec] final case class AcquisitionViewAuthorityClaim(
      kind: AcquisitionViewAuthorityKind,
      sourceChecksum: Checksum,
      buildReceiptChecksum: Option[Checksum],
      evidenceChecksum: Option[Checksum],
      adjudicationReceipt: Option[AdjudicationReceiptId],
      fixtureReceipt: Option[FixtureAdmissionReceiptId]
  )

  private[codec] given Decoder[AcquisitionViewAuthorityClaim] = Decoder.instance { c =>
    for
      status <- field[String](c, "status")
      kind <- status match
        case "validated_build"    => Right(AcquisitionViewAuthorityKind.ValidatedBuild)
        case "human_adjudication" => Right(AcquisitionViewAuthorityKind.HumanAdjudication)
        case "fixture_review"     => Right(AcquisitionViewAuthorityKind.FixtureReview)
        case other                => unknown(c, "AcquisitionViewAuthority", other)
      source <- field[Checksum](c, "sourceChecksum")
      build <- field[Option[Checksum]](c, "buildReceiptChecksum")
      evidence <- field[Option[Checksum]](c, "evidenceChecksum")
      adjudication <- field[Option[AdjudicationReceiptId]](c, "adjudicationReceipt")
      fixture <- field[Option[FixtureAdmissionReceiptId]](c, "fixtureReceipt")
    yield AcquisitionViewAuthorityClaim(kind, source, build, evidence, adjudication, fixture)
  }

  given [Id: Encoder]: Encoder[AcquisitionAccount[Id]] = Encoder.instance { account =>
    CanonicalPrimitives.obj(
      "invocationId" -> account.invocationId.asJson,
      "source" -> account.source.asJson,
      "universe" -> account.universe.asJson,
      "semantic" -> account.semantic.asJson,
      "targets" -> account.targets.asJson,
      "payloads" -> account.payloads.asJson,
      "buildReceipt" -> account.buildReceipt.asJson,
      "viewAuthority" -> account.viewAuthority.asJson
    )
  }

  given [Id: Decoder]: Decoder[AcquisitionAccount[Id]] =
    acquisitionAccountDecoder(Vector.empty)

  /** Explicit decoder for accounts carrying out-of-band human or fixture evidence admissions. */
  def acquisitionAccountDecoder[Id: Decoder](
      admittedEvidence: Iterable[AdmittedViewEvidence]
  ): Decoder[AcquisitionAccount[Id]] = Decoder.instance { c =>
    for
      invocation <- field[InvocationId](c, "invocationId")
      source <- field[SourceOutcome](c, "source")
      universe <- field[TargetUniverse[Id]](c, "universe")
      semantic <- field[SemanticOutcome](c, "semantic")
      targets <- field[Vector[TargetAccount[Id]]](c, "targets")
      payloads <- field[Vector[OutputPayload]](c, "payloads")
      receipt <- field[Option[ExtendedBuildReceipt]](c, "buildReceipt")
      authorityClaim <- field[Option[AcquisitionViewAuthorityClaim]](c, "viewAuthority")
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
            claim.fixtureReceipt,
            admittedEvidence
          )
        )
      )
      value <- domainValidated(
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
    yield value
  }

  private def tagged(status: String): Json = Json.obj("status" -> status.asJson)

  private def custom(status: String, namespace: OutputNamespace, label: OutputLabel): Json =
    Json.obj("status" -> status.asJson, "namespace" -> namespace.asJson, "label" -> label.asJson)

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

  private def nonEmpty[A](
      c: HCursor,
      fieldName: String,
      values: Vector[A]
  ): Decoder.Result[NonEmptyVector[A]] =
    NonEmptyVector
      .fromVector(values)
      .toRight(io.circe.DecodingFailure(s"$fieldName must be nonempty", c.history))

  private def domainValidated[A](
      c: HCursor,
      value: cats.data.ValidatedNec[DomainError, A]
  ): Decoder.Result[A] =
    value.toEither.left.map(errors =>
      io.circe
        .DecodingFailure(errors.toNonEmptyList.map(_.message).toList.mkString("; "), c.history)
    )

  private def domain[A](c: HCursor, value: Either[DomainError, A]): Decoder.Result[A] =
    value.left.map(error => io.circe.DecodingFailure(error.message, c.history))
