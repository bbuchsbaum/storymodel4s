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
  private val original = OriginalSourceIdentity.fromBytes(
    originalBytes,
    textPlain,
    Some(utf8),
    utf8,
    BomDisposition.Absent,
    OutputReceiptId.unsafe("receipt-intake")
  )
  private val identities = SourceIdentities.fromStorySource(
    original,
    source,
    DecodeReceipt.bind(
      OutputReceiptId.unsafe("receipt-decode"),
      DecoderId.unsafe("strict-utf8/v1")
    ),
    OutputReceiptId.unsafe("receipt-canonical")
  )
  private val semanticText = StoryModelCodec.encode(model)
  private val semanticBytes = semanticText.getBytes(StandardCharsets.UTF_8)
  private val build = ExtendedBuildReceipt(
    BuildReceipt(source.id, source.canonicalChecksum, "output-codec-test/v1", Vector.empty, 0L),
    Vector.empty,
    Map.empty
  )
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
      SourceOutcome.Constructed(identities),
      TargetUniverse.Established(universe),
      SemanticOutcome.Validated(modelRef),
      Vector.empty,
      Vector(OutputPayload.Unsupported(optionalExtension)),
      Some(build),
      Some(AcquisitionViewAuthority.ValidatedBuild)
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
  private lazy val htmlFailure = OutputFailure(
    OutputFailureCode.SerializationFailed,
    htmlReceipt.id,
    None,
    Vector(htmlReceipt.id)
  )
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
    val decoded = StoryOutputResultCodec.decode[String](once)
    assertEquals(decoded, Right(result))
    assertEquals(decoded.map(StoryOutputResultCodec.encode[String]), Right(once))
    assert(once.startsWith("{\"acquisition\""))
    assert(once.contains("\"schemaVersion\":\"story-output-result/v1\""))
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
      StoryOutputResultCodec.decode[String](Canonical.print(mismatchedConstructedDecoder)).isLeft,
      "constructed identity must derive decoder from the fixed typed decode receipt"
    )

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
      StoryOutputResultCodec.decode[String](Canonical.print(tampered)).isLeft,
      "changing opaque bytes without changing their checksum must fail"
    )
  }

  test("unknown result schema and unknown closed-core status fail decoding") {
    val encoded = StoryOutputResultCodec.encode[String](result)
    val wrongVersion = Canonical
      .parse(encoded)
      .toOption
      .get
      .mapObject(_.add("schemaVersion", Json.fromString("story-output-result/v999")))
    StoryOutputResultCodec.decode[String](Canonical.print(wrongVersion)) match
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
    assert(StoryOutputResultCodec.decode[String](Canonical.print(wrongTag)).isLeft)
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
    assert(StoryOutputResultCodec.decode[String](Canonical.print(wrongBuild)).isLeft)

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
    assert(StoryOutputResultCodec.decode[String](Canonical.print(wrongAuthorityRoot)).isLeft)

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
      StoryOutputResultCodec.decode[String](Canonical.print(relabelledRoot)).isLeft,
      "the same source cannot be relabelled with fixture authority on the wire"
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
      StoryOutputResultCodec.decode[String](Canonical.print(foreignReceiptRoot)).isLeft,
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
      StoryOutputResultCodec.decode[String](Canonical.print(targetOnlyForeignRoot)).isLeft,
      "contextual decoding must rederive target identity instead of trusting the wire receipt"
    )
  }

  test("post-decode source refusal round-trips completed decoded identity and receipt") {
    val whitespace = " \t\r\n"
    val bytes = whitespace.getBytes(StandardCharsets.UTF_8)
    val admitted = OriginalSourceIdentity.fromBytes(
      bytes,
      textPlain,
      Some(utf8),
      utf8,
      BomDisposition.Absent,
      OutputReceiptId.unsafe("receipt-whitespace-intake")
    )
    val decoded = DecodedSourceIdentity.fromText(
      whitespace,
      DecodeReceipt.bind(
        OutputReceiptId.unsafe("receipt-whitespace-decode"),
        DecoderId.unsafe("strict-utf8/v1")
      )
    )
    val failure = OutputFailure(
      OutputFailureCode.ValidationFailed,
      OutputReceiptId.unsafe("receipt-whitespace-source-refused"),
      None,
      Vector(OutputReceiptId.unsafe("receipt-whitespace-decode"))
    )
    val refused = AcquisitionAccount
      .of[String](
        InvocationId.unsafe("invocation-whitespace-source-refused"),
        SourceOutcome.Refused(RefusedSourceProgress.Decoded(admitted, decoded), failure),
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
    assert(encoded.contains("\"id\":\"receipt-whitespace-decode\""))
    assertEquals(StoryOutputResultCodec.decode[String](encoded), Right(refusedResult))

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
      StoryOutputResultCodec.decode[String](Canonical.print(mismatchedDecoder)).isLeft,
      "a free decoder label must not disagree with the fixed typed decode receipt"
    )

    val progress = sourceObject("progress").flatMap(_.asObject).get.remove("original")
    val tampered = parsed.mapObject(
      _.add(
        "source",
        Json.fromJsonObject(sourceObject.add("progress", Json.fromJsonObject(progress)))
      )
    )
    assert(StoryOutputResultCodec.decode[String](Canonical.print(tampered)).isLeft)
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
        Some(AcquisitionViewAuthority.ValidatedBuild)
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
    val partialHtmlFailure = htmlFailure.copy(
      receipt = partialHtmlReceipt.id,
      evidence = Vector(partialHtmlReceipt.id)
    )
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
    assertEquals(StoryOutputResultCodec.decode[String](encoded), Right(partialResult))
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
    val arbitrarySatisfiedReceipt = failedReceipt
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
            "court" -> Json.fromString("arbitrary-passed"),
            "disposition" -> Json.obj("status" -> Json.fromString("passed"))
          )
        )
      )
    val arbitrarySatisfiedDisposition = failedDisposition
      .add("status", Json.fromString("satisfied"))
      .add("receipt", Json.fromJsonObject(arbitrarySatisfiedReceipt))
    val arbitrarySatisfiedProfile = profiles.head.mapObject(
      _.add("disposition", Json.fromJsonObject(arbitrarySatisfiedDisposition))
    )
    val arbitrarySatisfied = parsed.mapObject(
      _.add("profileOutcomes", Json.fromValues(profiles.updated(0, arbitrarySatisfiedProfile)))
    )
    assert(
      BundleManifestCodec.decode(Canonical.print(arbitrarySatisfied), result).isLeft,
      "one arbitrary passed court must not fabricate a satisfied local-open profile"
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
      id: String
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
      ArtifactDisposition.Produced(artifact)
    )
