package storymodel4s.codec

import java.nio.charset.StandardCharsets

import cats.data.NonEmptyVector
import io.circe.Json
import munit.FunSuite
import storymodel4s.acquire.*
import storymodel4s.core.*
import storymodel4s.view.*

class OutputCodecSuite extends FunSuite:
  private val model = CodecFixture.validated
  private val source = model.source
  private val textPlain = MediaTypeId.unsafe("text/plain")
  private val htmlType = MediaTypeId.unsafe("text/html")
  private val jsonType = MediaTypeId.unsafe("application/json")
  private val utf8 = CharsetId.unsafe("UTF-8")
  private val originalBytes = source.rawText.getBytes(StandardCharsets.UTF_8)
  private val sourceAdmission = SourceIdentities.admitUtf8(originalBytes, textPlain, Some(utf8))
  private val identities = sourceAdmission.constructed match
    case Some((_, value)) => value
    case None             => fail(s"source fixture refused: ${sourceAdmission.outcome}")
  private val semanticText = StoryModelCodec.encode(model)
  private val semanticBytes = semanticText.getBytes(StandardCharsets.UTF_8)
  private val build = ExtendedBuildReceipt(
    BuildReceipt(source.id, source.canonicalChecksum, "output-codec-test/v1", Vector.empty, 0L),
    Vector.empty,
    Map.empty
  )
  private val sourceOutcome = SourceOutcome.Constructed(identities)
  private val validatedAuthority = AcquisitionViewAuthority
    .validatedBuild(sourceOutcome, build)
    .toOption
    .get
  private val extensionId = OutputPayloadId.unsafe("optional-future")
  private val optionalExtension = UnsupportedExtension(
    extensionId,
    OutputNamespace.unsafe("future.example"),
    OutputSchemaId.unsafe("future/v2"),
    OpaqueCanonicalPayload.of("opaque-extension".getBytes(StandardCharsets.UTF_8).toVector),
    ExtensionRequirement.Optional
  )
  private val universe = EstablishedUniverse
    .of(Vector.empty[String], UniverseDefinitionId.unsafe("eligible/v1"))
    .toOption
    .get
  private val modelRef = SemanticModelRef(
    source.id,
    source.canonicalChecksum,
    OutputSchemaId.unsafe(model.schemaVersion),
    Checksum.ofBytes(semanticBytes)
  )
  private val acquisition = AcquisitionAccount
    .of(
      InvocationId.unsafe("invocation-codec"),
      sourceOutcome,
      TargetUniverse.Established(universe),
      SemanticOutcome.Validated(modelRef),
      Vector.empty,
      Vector(OutputPayload.Unsupported(optionalExtension)),
      Some(build),
      Some(validatedAuthority)
    )
    .toOption
    .get
  private val htmlId = ReportId.unsafe("report-html")
  private val textId = ReportId.unsafe("report-text")
  private val htmlRequest = ReportRequest(
    htmlId,
    ReportKind.BrowserPreview,
    ArtifactRole.BrowserPreview,
    htmlType,
    ArtifactRequirement.Required,
    ReportAuthority.ScientificView,
    Set.empty
  )
  private val textRequest = ReportRequest(
    textId,
    ReportKind.TextPreview,
    ArtifactRole.TextPreview,
    textPlain,
    ArtifactRequirement.Required,
    ReportAuthority.ScientificView,
    Set.empty
  )
  private def reportReceipt(
      id: String,
      renderer: String,
      config: String,
      request: ReportRequest,
      account: AcquisitionAccount[String] = acquisition,
      artifacts: ScientificArtifactRefs = scientificArtifacts,
      admittedBasis: Option[AdmittedViewBasis] = Some(basis)
  ): ReportReceipt =
    ReportReceipt.issue(
      OutputReceiptId.unsafe(id),
      RendererId.unsafe(renderer),
      OutputSoftwareId.unsafe("storyatlas/test"),
      ReportInputIdentity.forReport(account, artifacts, admittedBasis, request).toOption.get,
      Checksum.ofText(config)
    )

  private lazy val htmlReceipt =
    reportReceipt("receipt-html", "html/v1", "html-config", htmlRequest)
  private lazy val textReceipt =
    reportReceipt("receipt-text", "text/v1", "text-config", textRequest)
  private lazy val htmlFailure = OutputFailure
    .general(
      OutputFailureCode.SerializationFailed,
      htmlReceipt.id,
      None,
      Vector(htmlReceipt.id)
    )
    .toOption
    .get

  private def decodeResult(text: String): Either[CodecError, StoryOutputResult[String]] =
    StoryOutputResultCodec.decode[String](text, originalBytes)
  private lazy val failedProfileReceipt = ProfileReceipt(
    OutputReceiptId.unsafe("receipt-profile-local-failed"),
    ProfileSchemaId.unsafe("local-open/v1"),
    ProfileVerifierId.unsafe("storyatlas-profile-verifier/v1"),
    OutputReceiptId.unsafe("execution-profile-local-failed"),
    OutputSchemaId.unsafe("story-output-profile-policy/v1"),
    ProfileReceiptDecision.Failed(OutputFailureCode.SerializationFailed),
    Vector(htmlReceipt.id),
    None,
    Vector.empty,
    Vector(
      ProfileCourtOutcome(
        ProfileCourtId.unsafe("browser-preview-produced"),
        ProfileCourtDisposition.Failed(OutputFailureCode.SerializationFailed, htmlReceipt.id)
      )
    )
  )
  private val textArtifact = ArtifactRef.fromBytes(
    ArtifactId.unsafe("artifact-text"),
    ArtifactRole.TextPreview,
    textPlain,
    None,
    "text preview".getBytes(StandardCharsets.UTF_8)
  )
  private lazy val scientificArtifacts = ScientificArtifactRefs
    .of(
      acquisition,
      Some(
        ArtifactRef.fromBytes(
          ArtifactId.unsafe("artifact-source"),
          ArtifactRole.OriginalSource,
          textPlain,
          None,
          originalBytes
        )
      ),
      Some(
        ArtifactRef.fromBytes(
          ArtifactId.unsafe("artifact-canonical"),
          ArtifactRole.CanonicalSource,
          textPlain,
          None,
          source.canonicalText.getBytes(StandardCharsets.UTF_8)
        )
      ),
      Some(
        ArtifactRef.fromBytes(
          ArtifactId.unsafe("artifact-semantic"),
          ArtifactRole.SemanticModel,
          jsonType,
          Some(OutputSchemaId.unsafe(model.schemaVersion)),
          semanticBytes
        )
      )
    )
    .toOption
    .get
  private lazy val basis = AdmittedViewBasis.fromAcquisition(acquisition).toOption.get
  private lazy val result = StoryOutputResult
    .of(
      acquisition,
      scientificArtifacts,
      Some(basis),
      Vector(htmlRequest, textRequest),
      Vector(
        ReportOutcome.Failed(htmlId, htmlFailure, htmlReceipt),
        ReportOutcome.Produced(textId, textArtifact, textReceipt)
      ),
      Vector.empty,
      Vector.empty
    )
    .toOption
    .get

  test("result root is canonical, fixed-point, and retains unsupported optional extensions") {
    val once = StoryOutputResultCodec.encode[String](result)
    val decoded = decodeResult(once)
    assertEquals(decoded, Right(result))
    assert(
      StoryOutputResultCodec.decode[String](once).isLeft,
      "bare result metadata must not establish a constructed source"
    )
    assert(
      StoryOutputResultCodec
        .decode[String](once, "beta".getBytes(StandardCharsets.UTF_8))
        .isLeft,
      "a successful source receipt cannot be transplanted onto different bytes"
    )
    assertEquals(decoded.map(StoryOutputResultCodec.encode[String]), Right(once))
    assert(once.startsWith("{\"acquisition\""))
    assert(once.contains("\"schemaVersion\":\"story-output-result/v2\""))
    assert(once.contains("\"status\":\"unsupported_extension\""))
    assert(once.contains("\"payloadEncoding\":\"octets/v1\""))
    assert(once.contains("\"payloadBytes\""))
    assert(!once.contains("canonicalText"), "result must not duplicate StoryModelCodec payload")

    val parsed = Canonical.parse(once).toOption.get
    val sourceObject = parsed.hcursor.downField("source").focus.flatMap(_.asObject).get
    val sourceIdentities = sourceObject("identities").flatMap(_.asObject).get
    val mismatchedConstructedDecoder = parsed.mapObject(
      _.add(
        "source",
        Json.fromJsonObject(
          sourceObject.add(
            "identities",
            Json.fromJsonObject(
              sourceIdentities.add("decoder", Json.fromString("different-decoder/v1"))
            )
          )
        )
      )
    )
    assert(
      decodeResult(Canonical.print(mismatchedConstructedDecoder)).isLeft,
      "constructed identity must derive decoder from the fixed typed decode receipt"
    )

    val decodeReceipt = sourceIdentities("decodeReceipt").flatMap(_.asObject).get
    Vector(
      "decoder" -> Json.fromString("different-decoder/v1"),
      "charset" -> Json.fromString("UTF-16"),
      "policy" -> Json.fromString("replace/v1"),
      "configChecksum" -> Json.fromString(Checksum.ofText("wrong-config").hex)
    ).foreach { case (fieldName, replacement) =>
      val changedIdentities = sourceIdentities.add(
        "decodeReceipt",
        Json.fromJsonObject(decodeReceipt.add(fieldName, replacement))
      )
      val changed = parsed.mapObject(
        _.add(
          "source",
          Json.fromJsonObject(
            sourceObject.add("identities", Json.fromJsonObject(changedIdentities))
          )
        )
      )
      assert(
        decodeResult(Canonical.print(changed)).isLeft,
        s"wire mutation of decode receipt $fieldName must be refused"
      )
    }

    val acquisition = parsed.hcursor.downField("acquisition").focus.flatMap(_.asObject).get
    val payloads = acquisition("payloads").flatMap(_.asArray).get
    val firstPayload = payloads.head.asObject.get
    val extension = firstPayload("extension").flatMap(_.asObject).get
    val tamperedExtension = extension.add("payloadBytes", Json.arr(Json.fromInt(0)))
    val tamperedPayload = firstPayload.add("extension", Json.fromJsonObject(tamperedExtension))
    val tamperedAcquisition = acquisition.add(
      "payloads",
      Json.fromValues(payloads.updated(0, Json.fromJsonObject(tamperedPayload)))
    )
    val tampered = parsed.mapObject(_.add("acquisition", Json.fromJsonObject(tamperedAcquisition)))
    assert(
      decodeResult(Canonical.print(tampered)).isLeft,
      "changing opaque bytes without changing their checksum must fail"
    )
    val wrongLengthPayload = firstPayload.add(
      "extension",
      Json.fromJsonObject(extension.add("byteLength", Json.fromLong(999L)))
    )
    val wrongLengthAcquisition = acquisition.add(
      "payloads",
      Json.fromValues(payloads.updated(0, Json.fromJsonObject(wrongLengthPayload)))
    )
    val wrongLength = parsed.mapObject(
      _.add("acquisition", Json.fromJsonObject(wrongLengthAcquisition))
    )
    assert(
      decodeResult(Canonical.print(wrongLength)).isLeft,
      "unsupported payload byte length must agree with retained exact bytes"
    )
  }

  test("unknown result schema and unknown closed-core status fail decoding") {
    val encoded = StoryOutputResultCodec.encode[String](result)
    val wrongVersion = Canonical
      .parse(encoded)
      .toOption
      .get
      .mapObject(_.add("schemaVersion", Json.fromString("story-output-result/v999")))
    decodeResult(Canonical.print(wrongVersion)) match
      case Left(CodecError.UnsupportedSchema(found, _)) =>
        assertEquals(found, "story-output-result/v999")
      case other => fail(s"expected unsupported schema, got $other")

    val parsed = Canonical.parse(encoded).toOption.get
    val outcomes = parsed.hcursor.downField("reportOutcomes").as[Vector[Json]].toOption.get
    val changed = outcomes.updated(
      0,
      outcomes.head.mapObject(_.add("status", Json.fromString("future_core_status")))
    )
    val wrongTag = parsed.mapObject(_.add("reportOutcomes", Json.fromValues(changed)))
    assert(decodeResult(Canonical.print(wrongTag)).isLeft)
  }

  test("wire admission rejects view-authority and foreign report-receipt mutations") {
    val encoded = StoryOutputResultCodec.encode[String](result)
    val parsed = Canonical.parse(encoded).toOption.get
    val basisObject = parsed.hcursor.downField("viewBasis").focus.flatMap(_.asObject).get

    val wrongBuildBasis = basisObject.add(
      "buildReceiptChecksum",
      Json.fromString(Checksum.ofText("wrong-build").hex)
    )
    val wrongBuild = parsed.mapObject(
      _.add("viewBasis", Json.fromJsonObject(wrongBuildBasis))
    )
    assert(decodeResult(Canonical.print(wrongBuild)).isLeft)

    val authority = basisObject("authority").flatMap(_.asObject).get
    val wrongAuthority = basisObject.add(
      "authority",
      Json.fromJsonObject(
        authority.add(
          "buildReceiptChecksum",
          Json.fromString(Checksum.ofText("wrong-authority").hex)
        )
      )
    )
    val wrongAuthorityRoot = parsed.mapObject(
      _.add("viewBasis", Json.fromJsonObject(wrongAuthority))
    )
    assert(decodeResult(Canonical.print(wrongAuthorityRoot)).isLeft)

    val fixtureRelabel = basisObject
      .add("basis", Json.obj("status" -> Json.fromString("researcher_reviewed_fixture")))
      .add(
        "authority",
        Json.obj(
          "status" -> Json.fromString("fixture_review"),
          "receipt" -> Json.fromString("fixture-relabel")
        )
      )
    val relabelledRoot = parsed.mapObject(
      _.add("viewBasis", Json.fromJsonObject(fixtureRelabel))
    )
    assert(
      decodeResult(Canonical.print(relabelledRoot)).isLeft,
      "the same source cannot be relabelled with fixture authority on the wire"
    )

    val acquisitionObject = parsed.hcursor.downField("acquisition").focus.flatMap(_.asObject).get
    val fixtureAuthorityClaim = Json.obj(
      "status" -> Json.fromString("fixture_review"),
      "sourceChecksum" -> Json.fromString(identities.canonicalChecksum.hex),
      "buildReceiptChecksum" -> Json.fromString(build.receipt.contentChecksum.hex),
      "evidenceChecksum" -> Json.fromString(Checksum.ofText("caller-evidence").hex),
      "adjudicationReceipt" -> Json.Null,
      "fixtureReceipt" -> Json.fromString("caller-fixture")
    )
    val combinedRelabel = parsed.mapObject(
      _.add(
        "acquisition",
        Json.fromJsonObject(acquisitionObject.add("viewAuthority", fixtureAuthorityClaim))
      ).add("viewBasis", Json.fromJsonObject(fixtureRelabel))
    )
    assert(
      decodeResult(Canonical.print(combinedRelabel)).isLeft,
      "a coordinated fixture authority and basis wire claim has no self-issued admission path"
    )

    val sourceObject = parsed.hcursor.downField("source").focus.flatMap(_.asObject).get
    val sourceIdentities = sourceObject("identities").flatMap(_.asObject).get
    val sourceOriginal = sourceIdentities("original").flatMap(_.asObject).get
    val foreignOriginal = sourceIdentities.add(
      "original",
      Json.fromJsonObject(
        sourceOriginal.add("intakeReceipt", Json.fromString("receipt-intake-foreign"))
      )
    )
    val foreignIntake = parsed.mapObject(
      _.add(
        "source",
        Json.fromJsonObject(sourceObject.add("identities", Json.fromJsonObject(foreignOriginal)))
      )
    )
    assert(
      decodeResult(Canonical.print(foreignIntake)).isLeft,
      "changing only the intake receipt must invalidate result-bound report receipts"
    )

    val foreignAccount = AcquisitionAccount
      .of(
        InvocationId.unsafe("invocation-codec-foreign"),
        acquisition.source,
        acquisition.universe,
        acquisition.semantic,
        acquisition.targets,
        acquisition.payloads,
        acquisition.buildReceipt,
        acquisition.viewAuthority
      )
      .toOption
      .get
    val foreignArtifacts = ScientificArtifactRefs
      .of(
        foreignAccount,
        scientificArtifacts.originalSource,
        scientificArtifacts.canonicalSource,
        scientificArtifacts.semanticModel
      )
      .toOption
      .get
    val foreignBasis = AdmittedViewBasis.fromAcquisition(foreignAccount).toOption.get
    val foreignInput = ReportInputIdentity
      .forReport(
        foreignAccount,
        foreignArtifacts,
        Some(foreignBasis),
        textRequest
      )
      .toOption
      .get
    val outcomes = parsed.hcursor.downField("reportOutcomes").as[Vector[Json]].toOption.get
    val producedIndex = outcomes.indexWhere(
      _.hcursor.downField("status").as[String].contains("produced")
    )
    val produced = outcomes(producedIndex).asObject.get
    val receipt = produced("receipt").flatMap(_.asObject).get
    val foreignReceipt = receipt.add("inputChecksum", Json.fromString(foreignInput.checksum.hex))
    val changedOutcome = Json.fromJsonObject(
      produced.add("receipt", Json.fromJsonObject(foreignReceipt))
    )
    val foreignReceiptRoot = parsed.mapObject(
      _.add("reportOutcomes", Json.fromValues(outcomes.updated(producedIndex, changedOutcome)))
    )
    assert(
      decodeResult(Canonical.print(foreignReceiptRoot)).isLeft,
      "a receipt derived for another result must not survive contextual decoding"
    )

    def targetedContext(
        targetId: String
    ): (AcquisitionAccount[String], ScientificArtifactRefs, AdmittedViewBasis) =
      val targetedUniverse = EstablishedUniverse
        .of(Vector(targetId), UniverseDefinitionId.unsafe("codec-target-identity/v1"))
        .toOption
        .get
      val account = AcquisitionAccount
        .of(
          acquisition.invocationId,
          acquisition.source,
          TargetUniverse.Established(targetedUniverse),
          acquisition.semantic,
          Vector(TargetAccount(targetId, TargetDisposition.Accepted, Vector.empty)),
          acquisition.payloads,
          acquisition.buildReceipt,
          acquisition.viewAuthority
        )
        .toOption
        .get
      val artifacts = ScientificArtifactRefs
        .of(
          account,
          scientificArtifacts.originalSource,
          scientificArtifacts.canonicalSource,
          scientificArtifacts.semanticModel
        )
        .toOption
        .get
      (account, artifacts, AdmittedViewBasis.fromAcquisition(account).toOption.get)

    val (alphaAccount, alphaArtifacts, alphaBasis) = targetedContext("alpha")
    val (betaAccount, betaArtifacts, betaBasis) = targetedContext("beta")
    val alphaReceipt = reportReceipt(
      "receipt-target-alpha",
      "text/v1",
      "text-config",
      textRequest,
      alphaAccount,
      alphaArtifacts,
      Some(alphaBasis)
    )
    val alphaResult = StoryOutputResult
      .of(
        alphaAccount,
        alphaArtifacts,
        Some(alphaBasis),
        Vector(textRequest),
        Vector(ReportOutcome.Produced(textId, textArtifact, alphaReceipt)),
        Vector.empty,
        Vector.empty
      )
      .toOption
      .get
    val betaInput = ReportInputIdentity
      .forReport(betaAccount, betaArtifacts, Some(betaBasis), textRequest)
      .toOption
      .get
    val alphaParsed = Canonical
      .parse(StoryOutputResultCodec.encode[String](alphaResult))
      .toOption
      .get
    val alphaOutcomes = alphaParsed.hcursor
      .downField("reportOutcomes")
      .as[Vector[Json]]
      .toOption
      .get
    val alphaProduced = alphaOutcomes.head.asObject.get
    val alphaReceiptWire = alphaProduced("receipt").flatMap(_.asObject).get
    val targetOnlyForeign = alphaProduced.add(
      "receipt",
      Json.fromJsonObject(
        alphaReceiptWire.add("inputChecksum", Json.fromString(betaInput.checksum.hex))
      )
    )
    val targetOnlyForeignRoot = alphaParsed.mapObject(
      _.add("reportOutcomes", Json.arr(Json.fromJsonObject(targetOnlyForeign)))
    )
    assert(
      decodeResult(Canonical.print(targetOnlyForeignRoot)).isLeft,
      "contextual decoding must rederive target identity instead of trusting the wire receipt"
    )
  }

  test("post-decode source refusal round-trips completed decoded identity and receipt") {
    val whitespace = " \t\r\n"
    val bytes = whitespace.getBytes(StandardCharsets.UTF_8)
    val admission = SourceIdentities.admitUtf8(bytes, textPlain, Some(utf8))
    val (refusedProgress, failure) = admission.refusal match
      case Some(value) => value
      case None        => fail("whitespace source was admitted")
    val refused = AcquisitionAccount
      .of[String](
        InvocationId.unsafe("invocation-whitespace-source-refused"),
        SourceOutcome.Refused(refusedProgress, failure),
        TargetUniverse.Unestablished(
          UniverseFailure(
            UniverseFailureReason.UpstreamUnavailable,
            OutputReceiptId.unsafe("receipt-whitespace-universe"),
            None
          )
        ),
        SemanticOutcome.Refused(NonEmptyVector.one(failure)),
        Vector.empty,
        Vector.empty,
        None
      )
      .toOption
      .get
    val admittedArtifact = ArtifactRef.fromBytes(
      ArtifactId.unsafe("artifact-whitespace-source"),
      ArtifactRole.OriginalSource,
      textPlain,
      None,
      bytes
    )
    val refs = ScientificArtifactRefs
      .of(refused, Some(admittedArtifact), None, None)
      .toOption
      .get
    val refusedResult = StoryOutputResult
      .of[String](
        refused,
        refs,
        None,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty
      )
      .toOption
      .get
    val encoded = StoryOutputResultCodec.encode[String](refusedResult)
    assert(encoded.contains("\"status\":\"decoded\""))
    assert(encoded.contains("source-canonicalization"))
    assertEquals(StoryOutputResultCodec.decode[String](encoded, bytes), Right(refusedResult))

    val parsed = Canonical.parse(encoded).toOption.get
    val sourceObject = parsed.hcursor.downField("source").focus.flatMap(_.asObject).get
    val progressObject = sourceObject("progress").flatMap(_.asObject).get
    val decodedObject = progressObject("decoded").flatMap(_.asObject).get
    val mismatchedDecoder = parsed.mapObject(
      _.add(
        "source",
        Json.fromJsonObject(
          sourceObject.add(
            "progress",
            Json.fromJsonObject(
              progressObject.add(
                "decoded",
                Json.fromJsonObject(
                  decodedObject.add("decoder", Json.fromString("different-decoder/v1"))
                )
              )
            )
          )
        )
      )
    )
    assert(
      StoryOutputResultCodec.decode[String](Canonical.print(mismatchedDecoder), bytes).isLeft,
      "a free decoder label must not disagree with the fixed typed decode receipt"
    )

    val progress = sourceObject("progress").flatMap(_.asObject).get.remove("original")
    val tampered = parsed.mapObject(
      _.add(
        "source",
        Json.fromJsonObject(sourceObject.add("progress", Json.fromJsonObject(progress)))
      )
    )
    assert(StoryOutputResultCodec.decode[String](Canonical.print(tampered), bytes).isLeft)
  }

  test("strict decode failure round-trips exact bytes and refuses position or byte mutation") {
    val bytes = Array(0x61.toByte, 0xc2.toByte, 0x20.toByte)
    val admission = SourceIdentities.admitUtf8(bytes, textPlain, Some(utf8))
    val sourceRefusal = admission.refusal match
      case Some((progress, failure)) => SourceOutcome.Refused(progress, failure)
      case None                      => fail("invalid UTF-8 was admitted")
    val account = AcquisitionAccount
      .of[String](
        InvocationId.unsafe("invocation-decode-failure"),
        sourceRefusal,
        TargetUniverse.Unestablished(
          UniverseFailure(
            UniverseFailureReason.UpstreamUnavailable,
            OutputReceiptId.unsafe("receipt-decode-failure-universe"),
            None
          )
        ),
        SemanticOutcome.NotRequested,
        Vector.empty,
        Vector.empty,
        None
      )
      .toOption
      .get
    val originalArtifact = ArtifactRef.fromBytes(
      ArtifactId.unsafe("artifact-invalid-source"),
      ArtifactRole.OriginalSource,
      textPlain,
      None,
      bytes
    )
    val refs = ScientificArtifactRefs.of(account, Some(originalArtifact), None, None).toOption.get
    val refused = StoryOutputResult
      .of[String](account, refs, None, Vector.empty, Vector.empty, Vector.empty, Vector.empty)
      .toOption
      .get
    val encoded = StoryOutputResultCodec.encode[String](refused)
    assertEquals(StoryOutputResultCodec.decode[String](encoded, bytes), Right(refused))

    val parsed = Canonical.parse(encoded).toOption.get
    val sourceObject = parsed.hcursor.downField("source").focus.flatMap(_.asObject).get
    val failureObject = sourceObject("failure").flatMap(_.asObject).get
    val detailObject = failureObject("detail").flatMap(_.asObject).get
    val valueObject = detailObject("value").flatMap(_.asObject).get
    val wrongPosition = detailObject.add(
      "value",
      Json.fromJsonObject(valueObject.add("bytePosition", Json.fromLong(1L)))
    )
    val tamperedFailure = failureObject.add("detail", Json.fromJsonObject(wrongPosition))
    val tampered = parsed.mapObject(
      _.add(
        "source",
        Json.fromJsonObject(sourceObject.add("failure", Json.fromJsonObject(tamperedFailure)))
      )
    )
    assert(StoryOutputResultCodec.decode[String](Canonical.print(tampered), bytes).isLeft)

    val changedBytes = bytes.clone()
    changedBytes(0) = 0x62.toByte
    assert(StoryOutputResultCodec.decode[String](encoded, changedBytes).isLeft)
  }

  test("partial outcome round-trips an optional canonical draft without claiming validation") {
    val gap = ResultGap(
      ResultGapKind.Failed,
      OutputReceiptId.unsafe("receipt-partial-gap"),
      None
    )
    val partialAcquisition = AcquisitionAccount
      .of(
        InvocationId.unsafe("invocation-partial-codec"),
        SourceOutcome.Constructed(identities),
        TargetUniverse.Established(universe),
        SemanticOutcome.Partial(NonEmptyVector.one(gap), Some(modelRef)),
        Vector.empty,
        Vector.empty,
        Some(build),
        Some(validatedAuthority)
      )
      .toOption
      .get
    val partialArtifacts = ScientificArtifactRefs
      .of(
        partialAcquisition,
        scientificArtifacts.originalSource,
        scientificArtifacts.canonicalSource,
        scientificArtifacts.semanticModel
      )
      .toOption
      .get
    val partialBasis = AdmittedViewBasis.fromAcquisition(partialAcquisition).toOption.get
    val partialHtmlReceipt = reportReceipt(
      "receipt-html",
      "html/v1",
      "html-config",
      htmlRequest,
      partialAcquisition,
      partialArtifacts,
      Some(partialBasis)
    )
    val partialTextReceipt = reportReceipt(
      "receipt-text",
      "text/v1",
      "text-config",
      textRequest,
      partialAcquisition,
      partialArtifacts,
      Some(partialBasis)
    )
    val partialHtmlFailure = htmlFailure
      .withReceipt(partialHtmlReceipt.id, Vector(partialHtmlReceipt.id))
      .toOption
      .get
    val partialResult = StoryOutputResult
      .of(
        partialAcquisition,
        partialArtifacts,
        Some(partialBasis),
        Vector(htmlRequest, textRequest),
        Vector(
          ReportOutcome.Failed(htmlId, partialHtmlFailure, partialHtmlReceipt),
          ReportOutcome.Produced(textId, textArtifact, partialTextReceipt)
        ),
        Vector.empty,
        Vector.empty
      )
      .toOption
      .get
    val encoded = StoryOutputResultCodec.encode[String](partialResult)
    assert(encoded.contains("\"status\":\"partial\""))
    assert(encoded.contains("\"draft\""))
    assertEquals(decodeResult(encoded), Right(partialResult))
  }

  test("manifest round-trips, has external BundleId, and preserves partial delivery") {
    val resultText = StoryOutputResultCodec.encode[String](result)
    val manifest = BundleManifest
      .of(
        result,
        Vector(
          BundleProfileOutcome(
            BundleProfile.LocalOpen,
            ProfileDisposition.Failed(htmlFailure, failedProfileReceipt)
          )
        ),
        manifestEntries(resultText)
      )
      .toOption
      .get
    val encoded = BundleManifestCodec.encode(manifest)
    assertEquals(BundleManifestCodec.decode(encoded, result), Right(manifest))
    assertEquals(BundleManifestCodec.bundleId(manifest), Checksum.ofText(encoded))
    assert(!encoded.contains("bundleId"))
    assert(!encoded.contains("bundle_manifest"))
    assert(encoded.contains("\"status\":\"failed\""))
    assert(!encoded.contains("\"detail\""))

    val parsed = Canonical.parse(encoded).toOption.get
    val profiles = parsed.hcursor.downField("profileOutcomes").as[Vector[Json]].toOption.get
    val withoutReceipt = profiles.head.mapObject { profileObject =>
      val disposition = profileObject("disposition").flatMap(_.asObject).get
      profileObject.add("disposition", Json.fromJsonObject(disposition.remove("receipt")))
    }
    val missingReceipt = parsed.mapObject(
      _.add("profileOutcomes", Json.fromValues(profiles.updated(0, withoutReceipt)))
    )
    assert(BundleManifestCodec.decode(Canonical.print(missingReceipt), result).isLeft)

    val failedDisposition = profiles.head.hcursor
      .downField("disposition")
      .focus
      .flatMap(_.asObject)
      .get
    val failedReceipt = failedDisposition("receipt").flatMap(_.asObject).get
    val fabricatedSatisfiedReceipt = failedReceipt
      .add("decision", Json.obj("status" -> Json.fromString("satisfied")))
      .add(
        "preview",
        Json.obj(
          "role" -> Json.obj("status" -> Json.fromString("browser_preview")),
          "path" -> Json.fromString("preview.html"),
          "mediaType" -> Json.fromString("text/html"),
          "checksum" -> Json.fromString(Checksum.ofText("preview").hex)
        )
      )
      .add(
        "courts",
        Json.arr(
          Json.obj(
            "court" -> Json.fromString("browser-preview-produced"),
            "disposition" -> Json.obj("status" -> Json.fromString("passed"))
          ),
          Json.obj(
            "court" -> Json.fromString("direct-file-open"),
            "disposition" -> Json.obj("status" -> Json.fromString("passed"))
          )
        )
      )
    val fabricatedSatisfiedDisposition = failedDisposition
      .add("status", Json.fromString("satisfied"))
      .add("receipt", Json.fromJsonObject(fabricatedSatisfiedReceipt))
    val fabricatedSatisfiedProfile = profiles.head.mapObject(
      _.add("disposition", Json.fromJsonObject(fabricatedSatisfiedDisposition))
    )
    val fabricatedSatisfied = parsed.mapObject(
      _.add("profileOutcomes", Json.fromValues(profiles.updated(0, fabricatedSatisfiedProfile)))
    )
    assert(
      BundleManifestCodec.decode(Canonical.print(fabricatedSatisfied), result).isLeft,
      "the exact passed court labels must not fabricate a satisfied local-open profile"
    )

    val retiredBuiltIn = profiles.head.mapObject(
      _.add("profile", Json.obj("status" -> Json.fromString("certified_network_independent")))
    )
    val unknownProfile = parsed.mapObject(
      _.add("profileOutcomes", Json.fromValues(profiles.updated(0, retiredBuiltIn)))
    )
    assert(BundleManifestCodec.decode(Canonical.print(unknownProfile), result).isLeft)

    val reversed = BundleManifest
      .of(result, manifest.profileOutcomes, manifest.entries.reverse)
      .toOption
      .get
    assertEquals(BundleManifestCodec.encode(reversed), encoded)
  }

  test("manifest rejects unknown schema and StoryModel bytes remain canonical") {
    val resultText = StoryOutputResultCodec.encode[String](result)
    val manifest = BundleManifest
      .of(
        result,
        Vector(
          BundleProfileOutcome(
            BundleProfile.LocalOpen,
            ProfileDisposition.Failed(htmlFailure, failedProfileReceipt)
          )
        ),
        manifestEntries(resultText)
      )
      .toOption
      .get
    val encoded = BundleManifestCodec.encode(manifest)
    val wrong = Canonical
      .parse(encoded)
      .toOption
      .get
      .mapObject(_.add("schemaVersion", Json.fromString("story-output-manifest/v999")))
    assert(BundleManifestCodec.decode(Canonical.print(wrong), result).isLeft)

    val decodedModel =
      StoryModelCodec.decode(semanticText).fold(error => fail(error.message), identity)
    assertEquals(StoryModelCodec.encode(decodedModel), semanticText)
    val semanticEntry = manifest.entries.find(_.role == ArtifactRole.SemanticModel).get
    semanticEntry.disposition match
      case ArtifactDisposition.Produced(artifact) =>
        assertEquals(artifact.checksum, Checksum.ofBytes(semanticBytes))
        assertEquals(artifact.byteLength, semanticBytes.length.toLong)
      case other => fail(s"expected produced semantic model, got $other")
  }

  test("manifest payload bindings are produced, bijective, and result-specific") {
    val resultText = StoryOutputResultCodec.encode[String](result)
    val entries = manifestEntries(resultText)
    val profile = Vector(
      BundleProfileOutcome(
        BundleProfile.LocalOpen,
        ProfileDisposition.Failed(htmlFailure, failedProfileReceipt)
      )
    )
    val payloadEntry = entries.find(_.payload.contains(extensionId)).get
    assert(BundleManifest.of(result, profile, entries).isValid)
    assert(BundleManifest.of(result, profile, entries.filterNot(_ == payloadEntry)).isInvalid)

    val duplicateArtifact = ArtifactRef.fromBytes(
      ArtifactId.unsafe("artifact-extension-duplicate"),
      ArtifactRole.Custom(
        optionalExtension.namespace,
        OutputLabel.unsafe("opaque-payload-duplicate"),
        ArtifactId.unsafe("artifact-extension-duplicate")
      ),
      payloadEntry.mediaType,
      payloadEntry.schemaVersion,
      optionalExtension.payload.bytes.toArray
    )
    val duplicate = payloadEntry.copy(
      role = duplicateArtifact.role,
      path = BundlePath.unsafe("payloads/optional-future-duplicate.bin"),
      disposition = ArtifactDisposition.Produced(duplicateArtifact)
    )
    assert(BundleManifest.of(result, profile, entries :+ duplicate).isInvalid)
    assert(
      BundleManifest
        .of(
          result,
          profile,
          entries.updated(
            entries.indexOf(payloadEntry),
            payloadEntry.copy(payload = Some(OutputPayloadId.unsafe("extra-payload")))
          )
        )
        .isInvalid
    )

    val wrongSchema = OutputSchemaId.unsafe("future/wrong")
    val payloadArtifact = payloadEntry.disposition match
      case ArtifactDisposition.Produced(artifact) => artifact
      case other                                  => fail(s"expected produced payload, got $other")
    val wrongSchemaArtifact = ArtifactRef.fromBytes(
      payloadArtifact.id,
      payloadEntry.role,
      payloadEntry.mediaType,
      Some(wrongSchema),
      optionalExtension.payload.bytes.toArray
    )
    assert(
      BundleManifest
        .of(
          result,
          profile,
          entries.updated(
            entries.indexOf(payloadEntry),
            payloadEntry.copy(
              schemaVersion = Some(wrongSchema),
              disposition = ArtifactDisposition.Produced(wrongSchemaArtifact)
            )
          )
        )
        .isInvalid
    )

    val wrongChecksumArtifact = ArtifactRef.fromBytes(
      ArtifactId.unsafe("artifact-extension-wrong-checksum"),
      payloadEntry.role,
      payloadEntry.mediaType,
      payloadEntry.schemaVersion,
      optionalExtension.payload.bytes
        .updated(
          0,
          (optionalExtension.payload.bytes.head ^ 0x01).toByte
        )
        .toArray
    )
    assert(
      BundleManifest
        .of(
          result,
          profile,
          entries.updated(
            entries.indexOf(payloadEntry),
            payloadEntry.copy(disposition = ArtifactDisposition.Produced(wrongChecksumArtifact))
          )
        )
        .isInvalid
    )
    assert(
      BundleManifest
        .of(
          result,
          profile,
          entries.updated(
            entries.indexOf(payloadEntry),
            payloadEntry.copy(
              disposition = ArtifactDisposition.NotAttempted(
                NotAttemptedReason.DependencyUnsupported,
                None
              )
            )
          )
        )
        .isInvalid
    )

    val foreignPayload = OutputPayload.Known(
      KnownPayloadRef(extensionId, optionalExtension.schemaId, Checksum.ofText("foreign-payload"))
    )
    val foreignAccount = AcquisitionAccount
      .of(
        acquisition.invocationId,
        acquisition.source,
        acquisition.universe,
        acquisition.semantic,
        acquisition.targets,
        Vector(foreignPayload),
        acquisition.buildReceipt,
        acquisition.viewAuthority
      )
      .toOption
      .get
    val foreignBasis = AdmittedViewBasis.fromAcquisition(foreignAccount).toOption.get
    val foreignResult = StoryOutputResult
      .of[String](
        foreignAccount,
        scientificArtifacts,
        Some(foreignBasis),
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty
      )
      .toOption
      .get
    assert(BundleManifest.of(foreignResult, profile, entries).isInvalid)
  }

  private def manifestEntries(resultText: String): Vector[ManifestEntry] =
    Vector(
      produced(
        ArtifactRole.OriginalSource,
        "source.txt",
        textPlain,
        None,
        originalBytes,
        "artifact-source"
      ),
      produced(
        ArtifactRole.CanonicalSource,
        "canonical.txt",
        textPlain,
        None,
        source.canonicalText.getBytes(StandardCharsets.UTF_8),
        "artifact-canonical"
      ),
      produced(
        ArtifactRole.InvocationResult,
        "result.json",
        jsonType,
        Some(OutputSchemaId.unsafe(StoryOutputResultCodec.SchemaVersion)),
        resultText.getBytes(StandardCharsets.UTF_8),
        "artifact-result"
      ),
      produced(
        ArtifactRole.SemanticModel,
        "storymodel.json",
        jsonType,
        Some(OutputSchemaId.unsafe(model.schemaVersion)),
        semanticBytes,
        "artifact-semantic"
      ),
      produced(
        ArtifactRole.Custom(
          optionalExtension.namespace,
          OutputLabel.unsafe("opaque-payload"),
          ArtifactId.unsafe("artifact-extension")
        ),
        "payloads/optional-future.bin",
        MediaTypeId.unsafe("application/octet-stream"),
        Some(optionalExtension.schemaId),
        optionalExtension.payload.bytes.toArray,
        "artifact-extension",
        Some(extensionId)
      ),
      ManifestEntry(
        ArtifactRole.BrowserPreview,
        BundlePath.unsafe("preview.html"),
        htmlType,
        None,
        ArtifactRequirement.Required,
        ArtifactDisposition.Failed(htmlFailure, htmlReceipt.id)
      ),
      ManifestEntry(
        ArtifactRole.TextPreview,
        BundlePath.unsafe("preview.txt"),
        textPlain,
        None,
        ArtifactRequirement.Required,
        ArtifactDisposition.Produced(textArtifact)
      )
    )

  private def produced(
      role: ArtifactRole,
      path: String,
      mediaType: MediaTypeId,
      schemaVersion: Option[OutputSchemaId],
      bytes: Array[Byte],
      id: String,
      payload: Option[OutputPayloadId] = None
  ): ManifestEntry =
    val artifact = ArtifactRef.fromBytes(
      ArtifactId.unsafe(id),
      role,
      mediaType,
      schemaVersion,
      bytes
    )
    ManifestEntry(
      role,
      BundlePath.unsafe(path),
      mediaType,
      schemaVersion,
      ArtifactRequirement.Required,
      ArtifactDisposition.Produced(artifact),
      payload
    )
