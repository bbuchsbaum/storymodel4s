package storymodel4s.acquire

import java.nio.charset.StandardCharsets

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*

class OutputSuite extends FunSuite:
  private val mediaType = MediaTypeId.unsafe("text/plain")
  private val utf8 = CharsetId.unsafe("UTF-8")
  private val definition = UniverseDefinitionId.unsafe("eligible-sentences/v1")

  private def sourceFixture(): (StorySource, OriginalSourceIdentity, SourceIdentities) =
    val raw = "A\r\n😀  \t\r\nB"
    val bom = Array(0xef.toByte, 0xbb.toByte, 0xbf.toByte)
    val originalBytes = bom ++ raw.getBytes(StandardCharsets.UTF_8)
    SourceIdentities.admitUtf8(originalBytes, mediaType, Some(utf8)) match
      case SourceAdmission.Constructed(source, identities) =>
        (source, identities.original, identities)
      case SourceAdmission.Refused(_, failure) => fail(s"fixture refused: $failure")

  private def receipt(source: StorySource): ExtendedBuildReceipt =
    ExtendedBuildReceipt(
      BuildReceipt(source.id, source.canonicalChecksum, "story-output-test/v1", Vector.empty, 0L),
      Vector.empty,
      Map.empty
    )

  private def modelRef(source: StorySource): SemanticModelRef =
    SemanticModelRef(
      source.id,
      source.canonicalChecksum,
      OutputSchemaId.unsafe("storymodel/v1"),
      Checksum.ofText("semantic-artifact")
    )

  test("three source identities stay distinct and canonical bytes bind UTF-16 coordinates") {
    val (source, original, identities) = sourceFixture()
    val canonicalBytes = source.canonicalText.getBytes(StandardCharsets.UTF_8)

    assertNotEquals(original.checksum, identities.decodedChecksum)
    assertNotEquals(identities.decodedChecksum, identities.canonicalChecksum)
    assertEquals(identities.decodedChecksum, source.rawChecksum)
    assertEquals(identities.canonicalChecksum, source.canonicalChecksum)
    assertEquals(Checksum.ofBytes(canonicalBytes), source.canonicalChecksum)
    assertEquals(identities.canonicalByteLength, canonicalBytes.length.toLong)
    assertEquals(identities.canonicalUtf16Length, source.canonicalText.length)
    assertEquals(source.canonicalText, "A\n😀\nB")
    assertEquals(source.canonicalText.indexOf("B"), 5)
    assertEquals(source.canonicalText.codePointCount(0, source.canonicalText.indexOf("B")), 4)
  }

  test("equal source digests do not collapse the three typed identity roles") {
    val bytes = "identity text".getBytes(StandardCharsets.UTF_8)
    val (source, identities) = SourceIdentities.admitUtf8(bytes, mediaType, Some(utf8)) match
      case SourceAdmission.Constructed(value, identity) => value -> identity
      case SourceAdmission.Refused(_, failure)          => fail(s"fixture refused: $failure")
    val original = identities.original
    assertEquals(original.checksum, identities.decodedChecksum)
    assertEquals(identities.decodedChecksum, identities.canonicalChecksum)
    assertEquals(identities.original, original)
    assertEquals(identities.storyId, source.id)
    assertEquals(identities.canonicalPolicy, SourceIdentities.CurrentCanonicalPolicy)
  }

  test("an established empty universe has no numeric rate") {
    val universe = EstablishedUniverse.of(Vector.empty[String], definition).toOption.get
    assertEquals(universe.rate(0), Right(UniverseRate.NotApplicableEmpty))
    assertEquals(universe.rate(0).toOption.flatMap(_.value), None)
  }

  test("an established positive universe validates the numerator") {
    val universe = EstablishedUniverse.of(Vector("a", "b"), definition).toOption.get
    val measured = universe.rate(1).toOption.get
    assertEquals(measured.value, Some(0.5))
    assert(universe.rate(-1).isLeft)
    assert(universe.rate(3).isLeft)
    assertEquals(EstablishedRate.of(1, 2).map(_.value), Right(0.5))
    assert(EstablishedRate.of(-1, 2).isLeft)
    assert(EstablishedRate.of(1, 0).isLeft)
    assert(EstablishedRate.of(3, 2).isLeft)
  }

  test("established universes reject duplicate members") {
    assert(EstablishedUniverse.of(Vector("a", "a"), definition).isLeft)
  }

  test("unestablished universe forbids target accounting") {
    val failure = UniverseFailure(
      UniverseFailureReason.PlanningFailed,
      OutputReceiptId.unsafe("receipt-plan"),
      None
    )
    val semantic = SemanticOutcome.Refused(
      NonEmptyVector.one(
        OutputFailure
          .general(
            OutputFailureCode.PlanningFailed,
            OutputReceiptId.unsafe("receipt-plan"),
            None,
            Vector(OutputReceiptId.unsafe("receipt-plan"))
          )
          .toOption
          .get
      )
    )
    val result = AcquisitionAccount.of(
      InvocationId.unsafe("invocation-1"),
      SourceOutcome.Constructed(sourceFixture()._3),
      TargetUniverse.Unestablished(failure),
      semantic,
      Vector(TargetAccount("a", TargetDisposition.Unresolved, Vector.empty)),
      Vector.empty,
      None
    )
    assert(result.isInvalid)
    assert(result.swap.toOption.get.toNonEmptyList.exists {
      case DomainError.InvariantViolation(path, _) =>
        path == "output/universe/unestablished-targets"
      case _ => false
    })
  }

  test("established universe requires exactly-once member accounting") {
    val universe = EstablishedUniverse.of(Vector("a", "b"), definition).toOption.get
    val gap = ResultGap(
      ResultGapKind.Unresolved,
      OutputReceiptId.unsafe("receipt-gap"),
      None
    )
    val result = AcquisitionAccount.of(
      InvocationId.unsafe("invocation-2"),
      SourceOutcome.Constructed(sourceFixture()._3),
      TargetUniverse.Established(universe),
      SemanticOutcome.Partial(NonEmptyVector.one(gap), None),
      Vector(TargetAccount("a", TargetDisposition.Accepted, Vector.empty)),
      Vector.empty,
      None
    )
    assert(result.isInvalid)
  }

  test("validated semantics require matching source and build receipt") {
    val (source, _, identities) = sourceFixture()
    val universe = EstablishedUniverse.of(Vector("a"), definition).toOption.get
    val result = AcquisitionAccount.of(
      InvocationId.unsafe("invocation-3"),
      SourceOutcome.Constructed(identities),
      TargetUniverse.Established(universe),
      SemanticOutcome.Validated(modelRef(source)),
      Vector(TargetAccount("a", TargetDisposition.Accepted, Vector.empty)),
      Vector.empty,
      Some(receipt(source))
    )
    assert(result.isValid)

    val wrongReceipt = receipt(StorySource.fromText("other").toOption.get)
    assert(
      AcquisitionAccount
        .of(
          InvocationId.unsafe("invocation-4"),
          SourceOutcome.Constructed(identities),
          TargetUniverse.Established(universe),
          SemanticOutcome.Validated(modelRef(source)),
          Vector(TargetAccount("a", TargetDisposition.Accepted, Vector.empty)),
          Vector.empty,
          Some(wrongReceipt)
        )
        .isInvalid
    )
  }

  test("view authority is admitted only from its matching source and build evidence") {
    val (source, _, identities) = sourceFixture()
    val sourceOutcome = SourceOutcome.Constructed(identities)
    val buildReceipt = receipt(source)
    val validatedAuthority = AcquisitionViewAuthority
      .validatedBuild(sourceOutcome, buildReceipt)
      .toOption
      .get
    val universe = EstablishedUniverse.of(Vector.empty[String], definition).toOption.get
    val gap = ResultGap(
      ResultGapKind.Unresolved,
      OutputReceiptId.unsafe("receipt-view-authority-gap"),
      None
    )
    val semantic = SemanticOutcome.Partial(NonEmptyVector.one(gap), None)

    assert(
      AcquisitionAccount
        .of(
          InvocationId.unsafe("invocation-view-authority-no-build"),
          sourceOutcome,
          TargetUniverse.Established(universe),
          semantic,
          Vector.empty,
          Vector.empty,
          None,
          Some(validatedAuthority)
        )
        .isInvalid,
      "validated-build authority cannot be asserted without the admitted build"
    )

    assert(
      AcquisitionAccount
        .of(
          InvocationId.unsafe("invocation-view-authority-build"),
          sourceOutcome,
          TargetUniverse.Established(universe),
          semantic,
          Vector.empty,
          Vector.empty,
          Some(buildReceipt),
          Some(validatedAuthority)
        )
        .isValid
    )

  }

  test("wire authority admits validated builds and refuses ungoverned human or fixture claims") {
    val (source, _, identities) = sourceFixture()
    val sourceOutcome = SourceOutcome.Constructed(identities)
    val buildReceipt = receipt(source)
    val foreignStoryBuild = buildReceipt.copy(
      receipt = buildReceipt.receipt.copy(storyId = StoryId.unsafe("foreign-story"))
    )
    assert(AcquisitionViewAuthority.validatedBuild(sourceOutcome, foreignStoryBuild).isLeft)
    val validated = AcquisitionViewAuthority
      .validatedBuild(sourceOutcome, buildReceipt)
      .toOption
      .get
    assertEquals(
      AcquisitionViewAuthority.fromWire(
        sourceOutcome,
        Some(buildReceipt),
        validated.kind,
        validated.sourceChecksum,
        validated.buildReceiptChecksum,
        validated.evidenceChecksum,
        validated.adjudicationReceipt,
        validated.fixtureReceipt
      ),
      Right(validated)
    )

    assert(
      AcquisitionViewAuthority
        .fromWire(
          sourceOutcome,
          Some(buildReceipt),
          AcquisitionViewAuthorityKind.HumanAdjudication,
          identities.canonicalChecksum,
          Some(buildReceipt.receipt.contentChecksum),
          Some(Checksum.ofText("caller-evidence")),
          Some(AdjudicationReceiptId.unsafe("caller-adjudication")),
          None
        )
        .isLeft,
      "receipt-shaped human evidence is not an authority issuer"
    )
    assert(
      AcquisitionViewAuthority
        .fromWire(
          sourceOutcome,
          None,
          AcquisitionViewAuthorityKind.FixtureReview,
          identities.canonicalChecksum,
          None,
          Some(Checksum.ofText("caller-evidence")),
          None,
          Some(FixtureAdmissionReceiptId.unsafe("caller-fixture"))
        )
        .isLeft,
      "receipt-shaped fixture evidence is not an authority issuer"
    )
  }

  test("partial semantics may preserve a receipted draft without claiming validation") {
    val (source, _, identities) = sourceFixture()
    val universe = EstablishedUniverse.of(Vector("a"), definition).toOption.get
    val gap = ResultGap(
      ResultGapKind.Failed,
      OutputReceiptId.unsafe("receipt-validation-gap"),
      None
    )
    val partial = SemanticOutcome.Partial(NonEmptyVector.one(gap), Some(modelRef(source)))
    val valid = AcquisitionAccount.of(
      InvocationId.unsafe("invocation-partial-draft"),
      SourceOutcome.Constructed(identities),
      TargetUniverse.Established(universe),
      partial,
      Vector(TargetAccount("a", TargetDisposition.Unresolved, Vector.empty)),
      Vector.empty,
      Some(receipt(source))
    )
    assert(valid.isValid)

    assert(
      AcquisitionAccount
        .of(
          InvocationId.unsafe("invocation-partial-draft-no-receipt"),
          SourceOutcome.Constructed(identities),
          TargetUniverse.Established(universe),
          partial,
          Vector(TargetAccount("a", TargetDisposition.Unresolved, Vector.empty)),
          Vector.empty,
          None
        )
        .isInvalid
    )
  }

  test("unknown extensions remain typed and do not become known payloads") {
    val bytes = Vector[Byte](1, 2, 3)
    val extension = UnsupportedExtension(
      OutputPayloadId.unsafe("payload-future"),
      OutputNamespace.unsafe("future.example"),
      OutputSchemaId.unsafe("future/v2"),
      OpaqueCanonicalPayload.of(bytes),
      ExtensionRequirement.Optional
    )
    val payload = OutputPayload.Unsupported(extension)
    assertEquals(payload, OutputPayload.Unsupported(extension))
    assertEquals(extension.payload.bytes, bytes)
    assert(
      OpaqueCanonicalPayload
        .from(bytes, Checksum.ofText("different"))
        .isLeft,
      "opaque bytes must reject a mismatched supplied checksum"
    )
  }

  test("strict source admission binds exact bytes, decoded text, and derived receipts") {
    val alphaBytes = "alpha".getBytes(StandardCharsets.UTF_8)
    val betaBytes = "beta".getBytes(StandardCharsets.UTF_8)
    val alpha = SourceIdentities.admitUtf8(alphaBytes, mediaType, Some(utf8)) match
      case SourceAdmission.Constructed(source, value) => source -> value
      case SourceAdmission.Refused(_, failure)        => fail(s"alpha refused: $failure")
    val beta = SourceIdentities.admitUtf8(betaBytes, mediaType, Some(utf8)) match
      case SourceAdmission.Constructed(source, value) => source -> value
      case SourceAdmission.Refused(_, failure)        => fail(s"beta refused: $failure")

    assertNotEquals(alpha._2.original.checksum, beta._2.original.checksum)
    assertNotEquals(alpha._2.decodeReceipt.id, beta._2.decodeReceipt.id)
    assertNotEquals(alpha._2.canonicalizationReceipt, beta._2.canonicalizationReceipt)
    assert(
      OriginalSourceIdentity
        .fromWire(
          betaBytes,
          alpha._2.original.byteLength,
          alpha._2.original.checksum,
          alpha._2.original.mediaType,
          alpha._2.original.declaredCharset,
          alpha._2.original.selectedCharset,
          alpha._2.original.bom,
          alpha._2.original.intakeReceipt
        )
        .isLeft
    )
    assert(
      SourceIdentities
        .fromWire(
          betaBytes,
          mediaType,
          Some(utf8),
          None,
          LanguageTag.English,
          Map.empty,
          alpha._2
        )
        .isLeft,
      "a successful receipt cannot be transplanted to different decoded text"
    )
  }

  test("strict decode failure records exact byte position and refuses altered wire claims") {
    val bytes = Array(0x61.toByte, 0xc2.toByte, 0x20.toByte)
    val detail = SourceIdentities.admitUtf8(bytes, mediaType, Some(utf8)) match
      case SourceAdmission.Refused(_, failure) =>
        failure.detail match
          case Some(OutputFailureDetail.StrictDecode(value)) => value
          case other => fail(s"expected strict decode detail, got $other")
      case SourceAdmission.Constructed(_, _) => fail("invalid UTF-8 was admitted")

    assertEquals(detail.bytePosition, 2L)
    assertEquals(detail.reason, StrictDecodeFailureReason.InvalidContinuationByte)
    assertEquals(
      StrictDecodeFailure.fromWire(
        bytes,
        detail.receipt,
        detail.decoder,
        detail.charset,
        detail.policy,
        detail.configChecksum,
        detail.originalChecksum,
        detail.bytePosition,
        detail.reason
      ),
      Right(detail)
    )
    assert(
      StrictDecodeFailure
        .fromWire(
          bytes,
          detail.receipt,
          detail.decoder,
          CharsetId.unsafe("UTF-16"),
          detail.policy,
          detail.configChecksum,
          detail.originalChecksum,
          detail.bytePosition,
          detail.reason
        )
        .isLeft
    )
    assert(
      StrictDecodeFailure
        .fromWire(
          bytes,
          detail.receipt,
          detail.decoder,
          detail.charset,
          detail.policy,
          detail.configChecksum,
          detail.originalChecksum,
          -1L,
          detail.reason
        )
        .isLeft
    )
  }

  test("not-requested semantics carry no semantic build or view authority") {
    val (_, _, sourceIdentities) = sourceFixture()
    val empty = EstablishedUniverse.of(Vector.empty[String], definition).toOption.get
    val noSemantics = AcquisitionAccount.of(
      InvocationId.unsafe("invocation-source-only"),
      SourceOutcome.Constructed(sourceIdentities),
      TargetUniverse.Established(empty),
      SemanticOutcome.NotRequested,
      Vector.empty,
      Vector.empty,
      None
    )
    assert(noSemantics.isValid)
    assert(
      AcquisitionAccount
        .of(
          InvocationId.unsafe("invocation-source-only-with-build"),
          SourceOutcome.Constructed(sourceIdentities),
          TargetUniverse.Established(empty),
          SemanticOutcome.NotRequested,
          Vector.empty,
          Vector.empty,
          Some(receipt(sourceFixture()._1))
        )
        .isInvalid
    )
    assertNotEquals(
      SemanticOutcome.NotRequested,
      SemanticOutcome.Refused(
        NonEmptyVector.one(
          OutputFailure
            .general(
              OutputFailureCode.SourceUnavailable,
              OutputReceiptId.unsafe("not-requested-foil"),
              None,
              Vector.empty
            )
            .toOption
            .get
        )
      )
    )
  }
