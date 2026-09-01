package storymodel4s.view

import java.nio.charset.StandardCharsets

import munit.FunSuite
import storymodel4s.acquire.*
import storymodel4s.core.*

class OutputSuite extends FunSuite:
  private val textPlain = MediaTypeId.unsafe("text/plain")
  private val htmlType = MediaTypeId.unsafe("text/html")
  private val jsonType = MediaTypeId.unsafe("application/json")
  private val utf8 = CharsetId.unsafe("UTF-8")
  private val sourceBytes = "A\r\n😀  \t\r\nB".getBytes(StandardCharsets.UTF_8)
  private val source = StorySource.fromText("A\r\n😀  \t\r\nB").toOption.get
  private val original = OriginalSourceIdentity.fromBytes(
    sourceBytes,
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
  private val buildReceipt = ExtendedBuildReceipt(
    BuildReceipt(source.id, source.canonicalChecksum, "test/v1", Vector.empty, 0L),
    Vector.empty,
    Map.empty
  )
  private val validatedAuthority = AcquisitionViewAuthority
    .validatedBuild(SourceOutcome.Constructed(identities), buildReceipt)
    .toOption
    .get
  test("v1 combines only a nonempty homogeneous admitted view basis") {
    val acquired = AdmittedViewBasis.fromAcquisition(acquisition()).toOption.get
    assertEquals(
      AdmittedViewBasis.combineHomogeneous(Vector(acquired, acquired)),
      Right(acquired)
    )
    assert(AdmittedViewBasis.combineHomogeneous(Vector.empty).isLeft)
  }

  test("view-basis admission rejects checksum and unsupported fixture relabel mutations") {
    val admitted = AdmittedViewBasis.fromAcquisition(acquisition()).toOption.get
    assert(
      AdmittedViewBasis
        .fromWire(
          acquisition(),
          admitted.basis,
          admitted.sourceChecksum,
          Some(Checksum.ofText("wrong-build")),
          admitted.authority
        )
        .isLeft
    )

    assert(
      AdmittedViewBasis
        .fromWire(
          acquisition(),
          ViewBasis.ResearcherReviewedFixture,
          admitted.sourceChecksum,
          admitted.buildReceiptChecksum,
          BasisAuthority.FixtureReview(FixtureAdmissionReceiptId.unsafe("caller-fixture"))
        )
        .isLeft
    )
  }
  private val semanticBytes = "semantic-artifact".getBytes(StandardCharsets.UTF_8)
  private val semanticModelRef = SemanticModelRef(
    source.id,
    source.canonicalChecksum,
    OutputSchemaId.unsafe("storymodel/v1"),
    Checksum.ofBytes(semanticBytes)
  )
  private val universe = EstablishedUniverse
    .of(Vector.empty[String], UniverseDefinitionId.unsafe("eligible/v1"))
    .toOption
    .get

  private def acquisition(
      payloads: Vector[OutputPayload] = Vector.empty,
      invocationId: InvocationId = InvocationId.unsafe("invocation-1"),
      authority: Option[AcquisitionViewAuthority] = Some(validatedAuthority)
  ): AcquisitionAccount[String] =
    AcquisitionAccount
      .of(
        invocationId,
        SourceOutcome.Constructed(identities),
        TargetUniverse.Established(universe),
        SemanticOutcome.Validated(semanticModelRef),
        Vector.empty,
        payloads,
        Some(buildReceipt),
        authority
      )
      .toOption
      .get

  private val originalArtifact = ArtifactRef.fromBytes(
    ArtifactId.unsafe("artifact-source"),
    ArtifactRole.OriginalSource,
    textPlain,
    None,
    sourceBytes
  )
  private val canonicalArtifact = ArtifactRef.fromBytes(
    ArtifactId.unsafe("artifact-canonical"),
    ArtifactRole.CanonicalSource,
    textPlain,
    None,
    source.canonicalText.getBytes(StandardCharsets.UTF_8)
  )
  private val semanticArtifact = ArtifactRef.fromBytes(
    ArtifactId.unsafe("artifact-semantic"),
    ArtifactRole.SemanticModel,
    jsonType,
    Some(OutputSchemaId.unsafe("storymodel/v1")),
    semanticBytes
  )
  private val scientificArtifacts = ScientificArtifactRefs
    .of(
      acquisition(),
      Some(originalArtifact),
      Some(canonicalArtifact),
      Some(semanticArtifact)
    )
    .toOption
    .get
  private val basis = AdmittedViewBasis.fromAcquisition(acquisition()).toOption.get

  private def targetFixture[Id](
      universeMembers: Vector[Id],
      targetOrder: Vector[Id]
  ): (AcquisitionAccount[Id], ScientificArtifactRefs, AdmittedViewBasis) =
    val targetUniverse = EstablishedUniverse
      .of(universeMembers, UniverseDefinitionId.unsafe("target-identity-court/v1"))
      .toOption
      .get
    val account = AcquisitionAccount
      .of(
        InvocationId.unsafe("invocation-target-identity"),
        SourceOutcome.Constructed(identities),
        TargetUniverse.Established(targetUniverse),
        SemanticOutcome.Validated(semanticModelRef),
        targetOrder.map(TargetAccount(_, TargetDisposition.Accepted, Vector.empty)),
        Vector.empty,
        Some(buildReceipt),
        Some(validatedAuthority)
      )
      .toOption
      .get
    val artifacts = ScientificArtifactRefs
      .of(
        account,
        Some(originalArtifact),
        Some(canonicalArtifact),
        Some(semanticArtifact)
      )
      .toOption
      .get
    val admitted = AdmittedViewBasis.fromAcquisition(account).toOption.get
    (account, artifacts, admitted)

  private def providerParamFixture(
      params: Map[String, String]
  ): (AcquisitionAccount[String], ScientificArtifactRefs, AdmittedViewBasis) =
    val stage = StageId.unsafe("provider-param-stage")
    val output = Checksum.ofText("provider-param-output")
    val call = ProviderCall(
      "provider",
      "model",
      "v1",
      None,
      Checksum.ofText("provider-param-input"),
      output,
      params,
      None,
      cached = false
    )
    val record = StageRecord(
      stage,
      StageCacheKey.fromChecksum(Checksum.ofText("provider-param-key")),
      Vector(call.inputChecksum),
      Vector(output),
      Vector(call),
      cached = false
    )
    val receipt = ExtendedBuildReceipt(
      BuildReceipt(
        source.id,
        source.canonicalChecksum,
        "provider-param-court/v1",
        Vector(stage -> record.outputChecksum),
        0L
      ),
      Vector(record),
      Map.empty
    )
    val sourceOutcome = SourceOutcome.Constructed(identities)
    val authority = AcquisitionViewAuthority.validatedBuild(sourceOutcome, receipt).toOption.get
    val account = AcquisitionAccount
      .of(
        InvocationId.unsafe("invocation-provider-param"),
        sourceOutcome,
        TargetUniverse.Established(universe),
        SemanticOutcome.Validated(semanticModelRef),
        Vector.empty,
        Vector.empty,
        Some(receipt),
        Some(authority)
      )
      .toOption
      .get
    val artifacts = ScientificArtifactRefs
      .of(account, Some(originalArtifact), Some(canonicalArtifact), Some(semanticArtifact))
      .toOption
      .get
    (account, artifacts, AdmittedViewBasis.fromAcquisition(account).toOption.get)

  test("source-stage refusal closes only artifact references that actually exist") {
    val failure = OutputFailure(
      OutputFailureCode.DecodeFailed,
      OutputReceiptId.unsafe("receipt-source-refusal"),
      None,
      Vector(OutputReceiptId.unsafe("receipt-source-refusal"))
    )
    val universeFailure = UniverseFailure(
      UniverseFailureReason.UpstreamUnavailable,
      OutputReceiptId.unsafe("receipt-universe-refusal"),
      None
    )
    val semanticFailure = SemanticOutcome.Refused(cats.data.NonEmptyVector.one(failure))
    val afterIntake = AcquisitionAccount
      .of(
        InvocationId.unsafe("invocation-refused-after-intake"),
        SourceOutcome.Refused(RefusedSourceProgress.Admitted(original), failure),
        TargetUniverse.Unestablished(universeFailure),
        semanticFailure,
        Vector.empty,
        Vector.empty,
        None
      )
      .toOption
      .get
    assert(
      ScientificArtifactRefs
        .of(afterIntake, Some(originalArtifact), None, None)
        .isValid
    )
    assert(
      ScientificArtifactRefs
        .of(afterIntake, Some(originalArtifact), Some(canonicalArtifact), None)
        .isInvalid
    )

    val beforeIntake = AcquisitionAccount
      .of(
        InvocationId.unsafe("invocation-refused-before-intake"),
        SourceOutcome.Refused(RefusedSourceProgress.BeforeIntake, failure),
        TargetUniverse.Unestablished(universeFailure),
        semanticFailure,
        Vector.empty,
        Vector.empty,
        None
      )
      .toOption
      .get
    assert(ScientificArtifactRefs.of(beforeIntake, None, None, None).isValid)
    assert(
      ScientificArtifactRefs
        .of(beforeIntake, Some(originalArtifact), None, None)
        .isInvalid
    )

    val whitespace = " \t\r\n"
    val whitespaceBytes = whitespace.getBytes(StandardCharsets.UTF_8)
    val whitespaceOriginal = OriginalSourceIdentity.fromBytes(
      whitespaceBytes,
      textPlain,
      Some(utf8),
      utf8,
      BomDisposition.Absent,
      OutputReceiptId.unsafe("receipt-whitespace-intake")
    )
    val whitespaceDecoded = DecodedSourceIdentity.fromText(
      whitespace,
      DecodeReceipt.bind(
        OutputReceiptId.unsafe("receipt-whitespace-decode"),
        DecoderId.unsafe("strict-utf8/v1")
      )
    )
    assert(StorySource.fromText(whitespace).isLeft)
    val refusedAfterDecode = AcquisitionAccount
      .of(
        InvocationId.unsafe("invocation-refused-after-decode"),
        SourceOutcome.Refused(
          RefusedSourceProgress.Decoded(whitespaceOriginal, whitespaceDecoded),
          failure
        ),
        TargetUniverse.Unestablished(universeFailure),
        semanticFailure,
        Vector.empty,
        Vector.empty,
        None
      )
      .toOption
      .get
    val whitespaceArtifact = ArtifactRef.fromBytes(
      ArtifactId.unsafe("artifact-whitespace-source"),
      ArtifactRole.OriginalSource,
      textPlain,
      None,
      whitespaceBytes
    )
    assert(
      ScientificArtifactRefs
        .of(refusedAfterDecode, Some(whitespaceArtifact), None, None)
        .isValid
    )
    assert(
      ScientificArtifactRefs
        .of(refusedAfterDecode, Some(whitespaceArtifact), Some(canonicalArtifact), None)
        .isInvalid
    )
  }

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
      account: AcquisitionAccount[String] = acquisition(),
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

  private val htmlReceipt =
    reportReceipt("receipt-html", "html-renderer/v1", "html-config", htmlRequest)
  private val textReceipt =
    reportReceipt("receipt-text", "text-renderer/v1", "text-config", textRequest)
  private val htmlFailure = OutputFailure(
    OutputFailureCode.SerializationFailed,
    htmlReceipt.id,
    None,
    Vector(htmlReceipt.id)
  )
  private val failedProfileReceipt = ProfileReceipt(
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
    "text report".getBytes(StandardCharsets.UTF_8)
  )
  private def partialDeliveryResult(): StoryOutputResult[String] =
    StoryOutputResult
      .of(
        acquisition(),
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

  test("report requests require exactly one independent outcome") {
    val missing = StoryOutputResult.of(
      acquisition(),
      scientificArtifacts,
      Some(basis),
      Vector(htmlRequest, textRequest),
      Vector(ReportOutcome.Failed(htmlId, htmlFailure, htmlReceipt)),
      Vector.empty,
      Vector.empty
    )
    assert(missing.isInvalid)

    val valid = partialDeliveryResult()
    assertEquals(valid.reportOutcomes.size, 2)
    assert(valid.reportOutcomes.exists(_.isInstanceOf[ReportOutcome.Failed]))
    assert(valid.reportOutcomes.exists(_.isInstanceOf[ReportOutcome.Produced]))
  }

  test("a produced scientific report requires an admitted basis") {
    val noBasis = StoryOutputResult.of(
      acquisition(),
      scientificArtifacts,
      None,
      Vector(textRequest),
      Vector(ReportOutcome.Produced(textId, textArtifact, textReceipt)),
      Vector.empty,
      Vector.empty
    )
    assert(noBasis.isInvalid)
  }

  test("required unsupported extension prevents a produced dependent report") {
    val payloadId = OutputPayloadId.unsafe("future-required")
    val extension = UnsupportedExtension(
      payloadId,
      OutputNamespace.unsafe("future.example"),
      OutputSchemaId.unsafe("future/v2"),
      OpaqueCanonicalPayload.of(Vector[Byte](1, 2, 3)),
      ExtensionRequirement.Required
    )
    val request = textRequest.copy(requiredPayloads = Set(payloadId))
    val extensionAccount = acquisition(Vector(OutputPayload.Unsupported(extension)))
    val extensionArtifacts = ScientificArtifactRefs
      .of(
        extensionAccount,
        scientificArtifacts.originalSource,
        scientificArtifacts.canonicalSource,
        scientificArtifacts.semanticModel
      )
      .toOption
      .get
    val extensionBasis = AdmittedViewBasis.fromAcquisition(extensionAccount).toOption.get
    val extensionReceipt = reportReceipt(
      "receipt-extension-text",
      "text-renderer/v1",
      "text-config",
      request,
      extensionAccount,
      extensionArtifacts,
      Some(extensionBasis)
    )
    val result = StoryOutputResult.of(
      extensionAccount,
      extensionArtifacts,
      Some(extensionBasis),
      Vector(request),
      Vector(ReportOutcome.Produced(textId, textArtifact, extensionReceipt)),
      Vector.empty,
      Vector.empty
    )
    assert(result.isInvalid)
  }

  test("a report receipt from another result is rejected even for the same role and media") {
    val foreignAccount = acquisition(invocationId = InvocationId.unsafe("invocation-foreign"))
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
    val foreignReceipt = reportReceipt(
      "receipt-foreign-text",
      "text-renderer/v1",
      "text-config",
      textRequest,
      foreignAccount,
      foreignArtifacts,
      Some(foreignBasis)
    )
    assert(
      StoryOutputResult
        .of(
          acquisition(),
          scientificArtifacts,
          Some(basis),
          Vector(textRequest),
          Vector(ReportOutcome.Produced(textId, textArtifact, foreignReceipt)),
          Vector.empty,
          Vector.empty
        )
        .isInvalid
    )
  }

  test("report identity binds the intake receipt and refuses a one-field foreign result") {
    val foreignOriginal = OriginalSourceIdentity.fromBytes(
      sourceBytes,
      textPlain,
      Some(utf8),
      utf8,
      BomDisposition.Absent,
      OutputReceiptId.unsafe("receipt-intake-foreign")
    )
    val foreignIdentities = SourceIdentities.fromStorySource(
      foreignOriginal,
      source,
      identities.decodeReceipt,
      identities.canonicalizationReceipt
    )
    val foreignSource = SourceOutcome.Constructed(foreignIdentities)
    val foreignAuthority = AcquisitionViewAuthority
      .validatedBuild(foreignSource, buildReceipt)
      .toOption
      .get
    val foreignAccount = AcquisitionAccount
      .of(
        acquisition().invocationId,
        foreignSource,
        acquisition().universe,
        acquisition().semantic,
        acquisition().targets,
        acquisition().payloads,
        Some(buildReceipt),
        Some(foreignAuthority)
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
    val originalInput = ReportInputIdentity
      .forReport(acquisition(), scientificArtifacts, Some(basis), textRequest)
      .toOption
      .get
    val foreignInput = ReportInputIdentity
      .forReport(foreignAccount, foreignArtifacts, Some(foreignBasis), textRequest)
      .toOption
      .get
    assertNotEquals(originalInput, foreignInput)

    val foreignReceipt = ReportReceipt.issue(
      OutputReceiptId.unsafe("receipt-foreign-intake"),
      RendererId.unsafe("text-renderer/v1"),
      OutputSoftwareId.unsafe("storyatlas/test"),
      foreignInput,
      Checksum.ofText("text-config")
    )
    assert(
      StoryOutputResult
        .of(
          acquisition(),
          scientificArtifacts,
          Some(basis),
          Vector(textRequest),
          Vector(ReportOutcome.Produced(textId, textArtifact, foreignReceipt)),
          Vector.empty,
          Vector.empty
        )
        .isInvalid,
      "a receipt differing only in the original intake receipt must fail"
    )
  }

  test("report identity binds acquisition authority even when no view basis is requested") {
    val unlicensedAccount = acquisition(authority = None)
    val unlicensedArtifacts = ScientificArtifactRefs
      .of(
        unlicensedAccount,
        scientificArtifacts.originalSource,
        scientificArtifacts.canonicalSource,
        scientificArtifacts.semanticModel
      )
      .toOption
      .get
    val validatedInput = ReportInputIdentity
      .forReport(acquisition(), scientificArtifacts, None, textRequest)
      .toOption
      .get
    val unlicensedInput = ReportInputIdentity
      .forReport(unlicensedAccount, unlicensedArtifacts, None, textRequest)
      .toOption
      .get
    assertNotEquals(validatedInput, unlicensedInput)
  }

  test("report identity binds ordered universe and target identities") {
    val (alpha, alphaArtifacts, alphaBasis) = targetFixture(Vector("alpha"), Vector("alpha"))
    val (beta, betaArtifacts, betaBasis) = targetFixture(Vector("beta"), Vector("beta"))
    val alphaInput = ReportInputIdentity
      .forReport(alpha, alphaArtifacts, Some(alphaBasis), textRequest)
      .toOption
      .get
    val betaInput = ReportInputIdentity
      .forReport(beta, betaArtifacts, Some(betaBasis), textRequest)
      .toOption
      .get
    assertNotEquals(
      alphaInput,
      betaInput,
      "changing only one String target must change report identity"
    )

    val (ordered, orderedArtifacts, orderedBasis) =
      targetFixture(Vector("alpha", "beta"), Vector("alpha", "beta"))
    val (universeReordered, universeReorderedArtifacts, universeReorderedBasis) =
      targetFixture(Vector("beta", "alpha"), Vector("alpha", "beta"))
    val (targetsReordered, targetsReorderedArtifacts, targetsReorderedBasis) =
      targetFixture(Vector("alpha", "beta"), Vector("beta", "alpha"))
    val orderedInput = ReportInputIdentity
      .forReport(ordered, orderedArtifacts, Some(orderedBasis), textRequest)
      .toOption
      .get
    val universeReorderedInput = ReportInputIdentity
      .forReport(
        universeReordered,
        universeReorderedArtifacts,
        Some(universeReorderedBasis),
        textRequest
      )
      .toOption
      .get
    val targetsReorderedInput = ReportInputIdentity
      .forReport(
        targetsReordered,
        targetsReorderedArtifacts,
        Some(targetsReorderedBasis),
        textRequest
      )
      .toOption
      .get
    assertNotEquals(orderedInput, universeReorderedInput)
    assertNotEquals(orderedInput, targetsReorderedInput)
  }

  test("report identity frames payload coordinates without delimiter collisions") {
    val checksum = Checksum.ofText("same-known-payload")
    val left = acquisition(
      Vector(
        OutputPayload.Known(
          KnownPayloadRef(
            OutputPayloadId.unsafe("a:b"),
            OutputSchemaId.unsafe("c"),
            checksum
          )
        )
      )
    )
    val right = acquisition(
      Vector(
        OutputPayload.Known(
          KnownPayloadRef(
            OutputPayloadId.unsafe("a"),
            OutputSchemaId.unsafe("b:c"),
            checksum
          )
        )
      )
    )
    val leftArtifacts = ScientificArtifactRefs
      .of(left, Some(originalArtifact), Some(canonicalArtifact), Some(semanticArtifact))
      .toOption
      .get
    val rightArtifacts = ScientificArtifactRefs
      .of(right, Some(originalArtifact), Some(canonicalArtifact), Some(semanticArtifact))
      .toOption
      .get
    val leftInput = ReportInputIdentity
      .forReport(
        left,
        leftArtifacts,
        Some(AdmittedViewBasis.fromAcquisition(left).toOption.get),
        textRequest
      )
      .toOption
      .get
    val rightInput = ReportInputIdentity
      .forReport(
        right,
        rightArtifacts,
        Some(AdmittedViewBasis.fromAcquisition(right).toOption.get),
        textRequest
      )
      .toOption
      .get
    assertEquals(
      ReportInputIdentity
        .forReport(
          left,
          leftArtifacts,
          Some(AdmittedViewBasis.fromAcquisition(left).toOption.get),
          textRequest
        )
        .toOption
        .get,
      leftInput,
      "the same framed payload coordinates must replay identically"
    )
    assertNotEquals(
      leftInput,
      rightInput,
      "id=a:b/schema=c and id=a/schema=b:c must remain distinct"
    )
    val foreignReceipt = ReportReceipt.issue(
      OutputReceiptId.unsafe("receipt-payload-delimiter-foreign"),
      RendererId.unsafe("text-renderer/v1"),
      OutputSoftwareId.unsafe("storyatlas/test"),
      rightInput,
      Checksum.ofText("text-config")
    )
    assert(
      StoryOutputResult
        .of(
          left,
          leftArtifacts,
          Some(AdmittedViewBasis.fromAcquisition(left).toOption.get),
          Vector(textRequest),
          Vector(ReportOutcome.Produced(textId, textArtifact, foreignReceipt)),
          Vector.empty,
          Vector.empty
        )
        .isInvalid,
      "a foreign receipt from the formerly colliding payload must refuse"
    )
  }

  test("report identity frames provider parameter keys and values independently") {
    val (left, leftArtifacts, leftBasis) = providerParamFixture(Map("a" -> "b=c"))
    val (right, rightArtifacts, rightBasis) = providerParamFixture(Map("a=b" -> "c"))
    val leftInput = ReportInputIdentity
      .forReport(left, leftArtifacts, Some(leftBasis), textRequest)
      .toOption
      .get
    val rightInput = ReportInputIdentity
      .forReport(right, rightArtifacts, Some(rightBasis), textRequest)
      .toOption
      .get
    assertEquals(
      ReportInputIdentity
        .forReport(left, leftArtifacts, Some(leftBasis), textRequest)
        .toOption
        .get,
      leftInput,
      "the same provider key/value coordinates must replay identically"
    )
    assertNotEquals(
      leftInput,
      rightInput,
      "param a=b=c must not conflate key a/value b=c with key a=b/value c"
    )
  }

  test("collapsing target identity instances and target-only foreign receipts are refused") {
    final case class CollidingId(value: String)
    given OutputTargetIdentity[CollidingId] with
      def canonicalBytes(_value: CollidingId): Vector[Byte] = Vector(0)

    val (colliding, collidingArtifacts, collidingBasis) = targetFixture(
      Vector(CollidingId("alpha"), CollidingId("beta")),
      Vector(CollidingId("alpha"), CollidingId("beta"))
    )
    assert(
      ReportInputIdentity
        .forReport(colliding, collidingArtifacts, Some(collidingBasis), textRequest)
        .isLeft
    )
    assert(
      StoryOutputResult
        .of(
          colliding,
          collidingArtifacts,
          Some(collidingBasis),
          Vector.empty,
          Vector.empty,
          Vector.empty,
          Vector.empty
        )
        .isInvalid
    )

    val (alpha, alphaArtifacts, alphaBasis) = targetFixture(Vector("alpha"), Vector("alpha"))
    val (beta, betaArtifacts, betaBasis) = targetFixture(Vector("beta"), Vector("beta"))
    val betaReceipt = reportReceipt(
      "receipt-target-beta",
      "text-renderer/v1",
      "text-config",
      textRequest,
      beta,
      betaArtifacts,
      Some(betaBasis)
    )
    assert(
      StoryOutputResult
        .of(
          alpha,
          alphaArtifacts,
          Some(alphaBasis),
          Vector(textRequest),
          Vector(ReportOutcome.Produced(textId, textArtifact, betaReceipt)),
          Vector.empty,
          Vector.empty
        )
        .isInvalid,
      "a foreign receipt differing only in target identity must fail"
    )
  }

  test("projection requests distinguish established empty from omission") {
    val id = ProjectionRequestId.unsafe("projection-world-time")
    val request = ProjectionRequest(id, Set.empty)
    val projectionReceipt = ReportReceipt.issue(
      OutputReceiptId.unsafe("receipt-projection-world-time"),
      RendererId.unsafe("projection-renderer/v1"),
      OutputSoftwareId.unsafe("storyatlas/test"),
      ReportInputIdentity
        .forProjection(acquisition(), scientificArtifacts, Some(basis), request)
        .toOption
        .get,
      Checksum.ofText("projection-config")
    )
    val outcome = ProjectionOutcome(id, ProjectionDisposition.EstablishedEmpty(projectionReceipt))
    assert(
      StoryOutputResult
        .of(
          acquisition(),
          scientificArtifacts,
          Some(basis),
          Vector.empty,
          Vector.empty,
          Vector(request),
          Vector(outcome)
        )
        .isValid
    )
    assert(
      StoryOutputResult
        .of(
          acquisition(),
          scientificArtifacts,
          Some(basis),
          Vector.empty,
          Vector.empty,
          Vector(request),
          Vector.empty
        )
        .isInvalid
    )
  }

  test("required unsupported extension prevents established projection output") {
    val payloadId = OutputPayloadId.unsafe("future-projection-required")
    val extension = UnsupportedExtension(
      payloadId,
      OutputNamespace.unsafe("future.example"),
      OutputSchemaId.unsafe("future-projection/v1"),
      OpaqueCanonicalPayload.of(Vector[Byte](4, 5, 6)),
      ExtensionRequirement.Required
    )
    val id = ProjectionRequestId.unsafe("projection-future")
    val request = ProjectionRequest(id, Set(payloadId))
    val extensionAccount = acquisition(Vector(OutputPayload.Unsupported(extension)))
    val extensionArtifacts = ScientificArtifactRefs
      .of(
        extensionAccount,
        scientificArtifacts.originalSource,
        scientificArtifacts.canonicalSource,
        scientificArtifacts.semanticModel
      )
      .toOption
      .get
    val extensionBasis = AdmittedViewBasis.fromAcquisition(extensionAccount).toOption.get
    val projectionReceipt = ReportReceipt.issue(
      OutputReceiptId.unsafe("receipt-projection-future"),
      RendererId.unsafe("projection-renderer/v1"),
      OutputSoftwareId.unsafe("storyatlas/test"),
      ReportInputIdentity
        .forProjection(
          extensionAccount,
          extensionArtifacts,
          Some(extensionBasis),
          request
        )
        .toOption
        .get,
      Checksum.ofText("projection-config")
    )
    val outcome = ProjectionOutcome(
      id,
      ProjectionDisposition.Produced(None, OutputCount.unsafe(1), projectionReceipt)
    )
    assert(
      StoryOutputResult
        .of(
          extensionAccount,
          extensionArtifacts,
          Some(extensionBasis),
          Vector.empty,
          Vector.empty,
          Vector(request),
          Vector(outcome)
        )
        .isInvalid
    )
  }

  test("bundle paths reject traversal, platform roots, and device names") {
    assert(BundlePath.from("assets/report.css").isRight)
    assert(BundlePath.from("../report.css").isLeft)
    assert(BundlePath.from("C:/report.css").isLeft)
    assert(BundlePath.from("assets\\report.css").isLeft)
    assert(BundlePath.from("CON.txt").isLeft)
  }

  test("manifest retains valid science when HTML lowering fails") {
    val result = partialDeliveryResult()
    val entries = partialEntries()

    val localFailed = BundleProfileOutcome(
      BundleProfile.LocalOpen,
      ProfileDisposition.Failed(htmlFailure, failedProfileReceipt)
    )
    val manifest = BundleManifest.of(result, Vector(localFailed), entries)
    assert(manifest.isValid)
    val value = manifest.toOption.get
    assert(value.entries.exists(_.role == ArtifactRole.SemanticModel))
    assert(
      value.entries.exists(entry =>
        entry.role == ArtifactRole.BrowserPreview &&
          entry.disposition.isInstanceOf[ArtifactDisposition.Failed]
      )
    )

    val withoutCanonical = entries.filterNot(_.role == ArtifactRole.CanonicalSource)
    assert(BundleManifest.of(result, Vector(localFailed), withoutCanonical).isInvalid)
  }

  test("manifest closes complete source and semantic artifact references") {
    val result = partialDeliveryResult()
    val entries = partialEntries()
    val localFailed = BundleProfileOutcome(
      BundleProfile.LocalOpen,
      ProfileDisposition.Failed(htmlFailure, failedProfileReceipt)
    )
    assert(BundleManifest.of(result, Vector(localFailed), entries).isValid)

    val changedId = ArtifactRef
      .of(
        ArtifactId.unsafe("artifact-source-mutant"),
        originalArtifact.role,
        originalArtifact.mediaType,
        originalArtifact.schemaVersion,
        originalArtifact.byteLength,
        originalArtifact.checksum
      )
      .toOption
      .get
    val mismatchedOriginal = entries.map {
      case entry if entry.role == ArtifactRole.OriginalSource =>
        entry.copy(disposition = ArtifactDisposition.Produced(changedId))
      case entry => entry
    }
    assert(
      BundleManifest.of(result, Vector(localFailed), mismatchedOriginal).isInvalid,
      "a stable artifact-id mismatch must fail even when checksum and length agree"
    )

    val orphanSemantic = entries :+ entries
      .find(_.role == ArtifactRole.SemanticModel)
      .get
      .copy(path = BundlePath.unsafe("orphan-storymodel.json"))
    assert(
      BundleManifest.of(result, Vector(localFailed), orphanSemantic).isInvalid,
      "a second closed singleton entry must not survive as an orphan"
    )
  }

  test("partial draft has the same bijective semantic artifact closure as validated output") {
    val gap = ResultGap(
      ResultGapKind.Unresolved,
      OutputReceiptId.unsafe("receipt-partial-draft-gap"),
      None
    )
    val partialAcquisition = AcquisitionAccount
      .of(
        InvocationId.unsafe("invocation-partial-draft"),
        SourceOutcome.Constructed(identities),
        TargetUniverse.Established(universe),
        SemanticOutcome.Partial(cats.data.NonEmptyVector.one(gap), Some(semanticModelRef)),
        Vector.empty,
        Vector.empty,
        Some(buildReceipt),
        Some(validatedAuthority)
      )
      .toOption
      .get
    val partialArtifacts = ScientificArtifactRefs
      .of(
        partialAcquisition,
        Some(originalArtifact),
        Some(canonicalArtifact),
        Some(semanticArtifact)
      )
      .toOption
      .get
    val partialBasis = AdmittedViewBasis.fromAcquisition(partialAcquisition).toOption.get
    val partialHtmlReceipt = reportReceipt(
      "receipt-html",
      "html-renderer/v1",
      "html-config",
      htmlRequest,
      partialAcquisition,
      partialArtifacts,
      Some(partialBasis)
    )
    val partialTextReceipt = reportReceipt(
      "receipt-text",
      "text-renderer/v1",
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
    val entries = partialEntries()
    val localFailed = BundleProfileOutcome(
      BundleProfile.LocalOpen,
      ProfileDisposition.Failed(htmlFailure, failedProfileReceipt)
    )
    val profiles = Vector(localFailed)
    assert(BundleManifest.of(partialResult, profiles, entries).isValid)

    def changed(
        id: ArtifactId = semanticArtifact.id,
        role: ArtifactRole = semanticArtifact.role,
        mediaType: MediaTypeId = semanticArtifact.mediaType,
        schemaVersion: Option[OutputSchemaId] = semanticArtifact.schemaVersion,
        byteLength: Long = semanticArtifact.byteLength,
        checksum: Checksum = semanticArtifact.checksum
    ): ArtifactRef =
      ArtifactRef
        .of(id, role, mediaType, schemaVersion, byteLength, checksum)
        .toOption
        .get

    val mutations = Vector(
      "stable artifact id" -> changed(id = ArtifactId.unsafe("artifact-semantic-mutant")),
      "role" -> changed(
        role = ArtifactRole.Custom(
          OutputNamespace.unsafe("test"),
          OutputLabel.unsafe("semantic-mutant"),
          ArtifactId.unsafe("semantic-mutant-role")
        )
      ),
      "byte length" -> changed(byteLength = semanticArtifact.byteLength + 1),
      "checksum" -> changed(checksum = Checksum.ofText("semantic-mutant")),
      "media type" -> changed(mediaType = textPlain),
      "schema" -> changed(schemaVersion = Some(OutputSchemaId.unsafe("storymodel/v2")))
    )

    mutations.foreach { case (field, mutant) =>
      val manifestSide = entries.map {
        case entry if entry.role == ArtifactRole.SemanticModel =>
          entry.copy(disposition = ArtifactDisposition.Produced(mutant))
        case entry => entry
      }
      assert(
        BundleManifest.of(partialResult, profiles, manifestSide).isInvalid,
        s"partial-draft manifest-side $field mutation must fail"
      )

      ScientificArtifactRefs
        .of(
          partialAcquisition,
          Some(originalArtifact),
          Some(canonicalArtifact),
          Some(mutant)
        )
        .fold(
          _ => (),
          mutantRefs =>
            StoryOutputResult
              .of(
                partialAcquisition,
                mutantRefs,
                Some(basis),
                Vector(htmlRequest, textRequest),
                Vector(
                  ReportOutcome.Failed(htmlId, htmlFailure, htmlReceipt),
                  ReportOutcome.Produced(textId, textArtifact, textReceipt)
                ),
                Vector.empty,
                Vector.empty
              )
              .fold(
                _ => (),
                mutantResult =>
                  assert(
                    BundleManifest.of(mutantResult, profiles, entries).isInvalid,
                    s"partial-draft result-side $field mutation must fail"
                  )
              )
        )
    }

    val orphan = entries :+ entries
      .find(_.role == ArtifactRole.SemanticModel)
      .get
      .copy(path = BundlePath.unsafe("orphan-partial-storymodel.json"))
    assert(BundleManifest.of(partialResult, profiles, orphan).isInvalid)

    val withoutDraftAcquisition = AcquisitionAccount
      .of(
        InvocationId.unsafe("invocation-partial-no-draft"),
        SourceOutcome.Constructed(identities),
        TargetUniverse.Established(universe),
        SemanticOutcome.Partial(cats.data.NonEmptyVector.one(gap), None),
        Vector.empty,
        Vector.empty,
        Some(buildReceipt),
        Some(validatedAuthority)
      )
      .toOption
      .get
    val withoutDraftArtifacts = ScientificArtifactRefs
      .of(
        withoutDraftAcquisition,
        Some(originalArtifact),
        Some(canonicalArtifact),
        None
      )
      .toOption
      .get
    val withoutDraftBasis = AdmittedViewBasis
      .fromAcquisition(withoutDraftAcquisition)
      .toOption
      .get
    val withoutDraftHtmlReceipt = reportReceipt(
      "receipt-html",
      "html-renderer/v1",
      "html-config",
      htmlRequest,
      withoutDraftAcquisition,
      withoutDraftArtifacts,
      Some(withoutDraftBasis)
    )
    val withoutDraftTextReceipt = reportReceipt(
      "receipt-text",
      "text-renderer/v1",
      "text-config",
      textRequest,
      withoutDraftAcquisition,
      withoutDraftArtifacts,
      Some(withoutDraftBasis)
    )
    val withoutDraftHtmlFailure = htmlFailure.copy(
      receipt = withoutDraftHtmlReceipt.id,
      evidence = Vector(withoutDraftHtmlReceipt.id)
    )
    val withoutDraftResult = StoryOutputResult
      .of(
        withoutDraftAcquisition,
        withoutDraftArtifacts,
        Some(withoutDraftBasis),
        Vector(htmlRequest, textRequest),
        Vector(
          ReportOutcome.Failed(htmlId, withoutDraftHtmlFailure, withoutDraftHtmlReceipt),
          ReportOutcome.Produced(textId, textArtifact, withoutDraftTextReceipt)
        ),
        Vector.empty,
        Vector.empty
      )
      .toOption
      .get
    val withoutDraftEntries = entries.filterNot(_.role == ArtifactRole.SemanticModel)
    assert(BundleManifest.of(withoutDraftResult, profiles, withoutDraftEntries).isValid)
    assert(withoutDraftResult.acquisition.semantic.isInstanceOf[SemanticOutcome.Partial])
  }

  test("profile certification binds exact preview, assets, and court outcomes") {
    val htmlArtifact = ArtifactRef.fromBytes(
      ArtifactId.unsafe("artifact-html"),
      ArtifactRole.BrowserPreview,
      htmlType,
      None,
      "<html>preview</html>".getBytes(StandardCharsets.UTF_8)
    )
    val produced = StoryOutputResult
      .of(
        acquisition(),
        scientificArtifacts,
        Some(basis),
        Vector(htmlRequest, textRequest),
        Vector(
          ReportOutcome.Produced(htmlId, htmlArtifact, htmlReceipt),
          ReportOutcome.Produced(textId, textArtifact, textReceipt)
        ),
        Vector.empty,
        Vector.empty
      )
      .toOption
      .get
    val entries = partialEntries().map {
      case entry if entry.role == ArtifactRole.BrowserPreview =>
        producedEntry(ArtifactRole.BrowserPreview, "preview.html", htmlType, htmlArtifact)
      case entry => entry
    }
    val previewBinding = ProfileArtifactBinding(
      ArtifactRole.BrowserPreview,
      BundlePath.unsafe("preview.html"),
      htmlType,
      htmlArtifact.checksum
    )
    val textBinding = ProfileArtifactBinding(
      ArtifactRole.TextPreview,
      BundlePath.unsafe("preview.txt"),
      textPlain,
      textArtifact.checksum
    )
    val satisfiedClaim = failedProfileReceipt.copy(
      id = OutputReceiptId.unsafe("receipt-profile-local-satisfied"),
      decision = ProfileReceiptDecision.Satisfied,
      preview = Some(previewBinding),
      requiredAssets = Vector(textBinding),
      courts = Vector(
        ProfileCourtOutcome(
          ProfileCourtId.unsafe("browser-preview-produced"),
          ProfileCourtDisposition.Passed
        ),
        ProfileCourtOutcome(
          ProfileCourtId.unsafe("direct-file-open"),
          ProfileCourtDisposition.Passed
        )
      )
    )
    val satisfiedReceipt = VerifiedProfileReceipt.localOpen(satisfiedClaim).toOption.get
    val satisfied = BundleProfileOutcome(
      BundleProfile.LocalOpen,
      ProfileDisposition.Satisfied(satisfiedReceipt)
    )
    assert(BundleManifest.of(produced, Vector(satisfied), entries).isValid)

    val wrongDecision = satisfiedClaim.copy(
      decision = ProfileReceiptDecision.NotAttempted(NotAttemptedReason.VerificationNotRun)
    )
    assert(VerifiedProfileReceipt.localOpen(wrongDecision).isLeft)

    val wrongPreviewPath = BundlePath.unsafe("index.html")
    val movedEntries = entries.map {
      case entry if entry.role == ArtifactRole.BrowserPreview =>
        entry.copy(path = wrongPreviewPath)
      case entry => entry
    }
    val movedReceipt = VerifiedProfileReceipt
      .localOpen(satisfiedClaim.copy(preview = Some(previewBinding.copy(path = wrongPreviewPath))))
      .toOption
      .get
    val movedProfile = satisfied.copy(
      disposition = ProfileDisposition.Satisfied(movedReceipt)
    )
    assert(BundleManifest.of(produced, Vector(movedProfile), movedEntries).isInvalid)

    assert(
      VerifiedProfileReceipt.localOpen(satisfiedClaim.copy(preview = None)).isLeft,
      "satisfied profile cannot omit preview evidence"
    )

    val mutatedAssetReceipt = VerifiedProfileReceipt
      .localOpen(
        satisfiedClaim.copy(
          requiredAssets = Vector(textBinding.copy(checksum = Checksum.ofText("mutant")))
        )
      )
      .toOption
      .get
    val mutatedAsset = satisfied.copy(
      disposition = ProfileDisposition.Satisfied(mutatedAssetReceipt)
    )
    assert(BundleManifest.of(produced, Vector(mutatedAsset), entries).isInvalid)

    val arbitraryPassedCourt = satisfiedClaim.copy(
      courts = Vector(
        ProfileCourtOutcome(
          ProfileCourtId.unsafe("arbitrary-passed"),
          ProfileCourtDisposition.Passed
        )
      )
    )
    assert(
      VerifiedProfileReceipt.localOpen(arbitraryPassedCourt).isLeft,
      "one arbitrary passed court must not fabricate local-open satisfaction"
    )

    val failedCourt = failedProfileReceipt.copy(
      preview = Some(previewBinding),
      courts = Vector(
        ProfileCourtOutcome(
          ProfileCourtId.unsafe("direct-file-open"),
          ProfileCourtDisposition.Failed(OutputFailureCode.SerializationFailed, htmlReceipt.id)
        )
      )
    )
    val producedButFailed = BundleProfileOutcome(
      BundleProfile.LocalOpen,
      ProfileDisposition.Failed(htmlFailure, failedCourt)
    )
    assert(BundleManifest.of(produced, Vector(producedButFailed), entries).isValid)

  }

  private def partialEntries(): Vector[ManifestEntry] =
    Vector(
      producedEntry(
        ArtifactRole.OriginalSource,
        "source.txt",
        textPlain,
        originalArtifact
      ),
      producedEntry(
        ArtifactRole.CanonicalSource,
        "canonical.txt",
        textPlain,
        canonicalArtifact
      ),
      producedEntry(
        ArtifactRole.InvocationResult,
        "result.json",
        jsonType,
        ArtifactRef.fromBytes(
          ArtifactId.unsafe("artifact-result"),
          ArtifactRole.InvocationResult,
          jsonType,
          Some(OutputSchemaId.unsafe("story-output-result/v1")),
          "result".getBytes(StandardCharsets.UTF_8)
        ),
        ArtifactRequirement.Required
      ),
      producedEntry(
        ArtifactRole.SemanticModel,
        "storymodel.json",
        jsonType,
        semanticArtifact
      ),
      ManifestEntry(
        ArtifactRole.BrowserPreview,
        BundlePath.unsafe("preview.html"),
        htmlType,
        None,
        ArtifactRequirement.Required,
        ArtifactDisposition.Failed(htmlFailure, htmlReceipt.id)
      ),
      producedEntry(
        ArtifactRole.TextPreview,
        "preview.txt",
        textPlain,
        textArtifact
      )
    )

  test("manifest rejects case-fold path collisions and duplicate profiles") {
    val result = partialDeliveryResult()
    val entries = partialEntries()
    val localFailed = BundleProfileOutcome(
      BundleProfile.LocalOpen,
      ProfileDisposition.Failed(htmlFailure, failedProfileReceipt)
    )
    assert(BundleManifest.of(result, Vector(localFailed), entries).isValid)

    val duplicate = entries.head.copy(
      role = ArtifactRole.Custom(
        OutputNamespace.unsafe("test"),
        OutputLabel.unsafe("other"),
        ArtifactId.unsafe("other")
      ),
      path = BundlePath.unsafe(entries.head.path.value.toUpperCase)
    )
    assert(
      BundleManifest
        .of(result, Vector(localFailed), entries :+ duplicate)
        .isInvalid
    )
    assert(
      BundleManifest
        .of(
          result,
          Vector(
            BundleProfileOutcome(
              BundleProfile.LocalOpen,
              ProfileDisposition.Failed(htmlFailure, failedProfileReceipt)
            ),
            BundleProfileOutcome(
              BundleProfile.LocalOpen,
              ProfileDisposition.Failed(htmlFailure, failedProfileReceipt)
            )
          ),
          entries
        )
        .isInvalid
    )
  }

  private def producedEntry(
      role: ArtifactRole,
      path: String,
      mediaType: MediaTypeId,
      artifact: ArtifactRef,
      requirement: ArtifactRequirement = ArtifactRequirement.Required
  ): ManifestEntry =
    ManifestEntry(
      role,
      BundlePath.unsafe(path),
      mediaType,
      artifact.schemaVersion,
      requirement,
      ArtifactDisposition.Produced(artifact)
    )
