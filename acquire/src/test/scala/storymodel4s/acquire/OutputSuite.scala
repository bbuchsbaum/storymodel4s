package storymodel4s.acquire

import java.nio.charset.StandardCharsets

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*

class OutputSuite extends FunSuite:
  private val mediaType = MediaTypeId.unsafe("text/plain")
  private val utf8 = CharsetId.unsafe("UTF-8")
  private val decoder = DecoderId.unsafe("strict-utf8/v1")
  private val intake = OutputReceiptId.unsafe("receipt-intake")
  private val decode = OutputReceiptId.unsafe("receipt-decode")
  private val decodeReceipt = DecodeReceipt.bind(decode, decoder)
  private val canonicalize = OutputReceiptId.unsafe("receipt-canonicalize")
  private val definition = UniverseDefinitionId.unsafe("eligible-sentences/v1")

  private def sourceFixture(): (StorySource, OriginalSourceIdentity, SourceIdentities) =
    val raw = "A\r\n😀  \t\r\nB"
    val source = StorySource.fromText(raw).toOption.get
    val bom = Array(0xef.toByte, 0xbb.toByte, 0xbf.toByte)
    val originalBytes = bom ++ raw.getBytes(StandardCharsets.UTF_8)
    val original = OriginalSourceIdentity.fromBytes(
      originalBytes,
      mediaType,
      declaredCharset = Some(utf8),
      selectedCharset = utf8,
      BomDisposition.ConsumedUtf8,
      intake
    )
    val identities =
      SourceIdentities.fromStorySource(original, source, decodeReceipt, canonicalize)
    (source, original, identities)

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

  private def reviewEvidence(
      label: String,
      identities: SourceIdentities
  ): (Fingerprint, NonEmptyVector[Evidence], Provenance) =
    val reviewer = Fingerprint.unsafe(s"reviewer:$label:v1")
    val evidence = Evidence(
      EvidenceId.unsafe(s"evidence-$label"),
      Some(SpanSet.one(TextSpan.unsafe(0, identities.canonicalUtf16Length))),
      Set.empty,
      reviewer,
      StageId.unsafe(s"review-$label")
    )
    (reviewer, NonEmptyVector.one(evidence), Provenance.human(reviewer.value, "review/v1"))

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
    val source = StorySource.fromText("identity text").toOption.get
    val bytes = source.rawText.getBytes(StandardCharsets.UTF_8)
    val original = OriginalSourceIdentity.fromBytes(
      bytes,
      mediaType,
      Some(utf8),
      utf8,
      BomDisposition.Absent,
      intake
    )
    val identities = SourceIdentities.fromStorySource(
      original,
      source,
      decodeReceipt,
      canonicalize
    )
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
        OutputFailure(
          OutputFailureCode.PlanningFailed,
          OutputReceiptId.unsafe("receipt-plan"),
          None,
          Vector(OutputReceiptId.unsafe("receipt-plan"))
        )
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

    val fixtureInputs = reviewEvidence("fixture-account", identities)
    val fixtureEvidence = ViewEvidenceAdmitter
      .trusted(Fingerprint.unsafe("fixture-admitter:account:v1"))
      .fixtureReview(
        sourceOutcome,
        None,
        FixtureAdmissionReceiptId.unsafe("fixture-account-receipt"),
        fixtureInputs._1,
        fixtureInputs._2,
        fixtureInputs._3
      )
      .toOption
      .get
    val fixtureAuthority = AcquisitionViewAuthority
      .fixtureReview(sourceOutcome, None, fixtureEvidence)
      .toOption
      .get
    assert(
      AcquisitionAccount
        .of(
          InvocationId.unsafe("invocation-view-authority-fixture"),
          sourceOutcome,
          TargetUniverse.Established(universe),
          semantic,
          Vector.empty,
          Vector.empty,
          None,
          Some(fixtureAuthority)
        )
        .isValid,
      "fixture authority is distinct evidence and does not fabricate a build"
    )
  }

  test("human and fixture authority receipts are derived and revalidated from evidence") {
    val (source, _, identities) = sourceFixture()
    val sourceOutcome = SourceOutcome.Constructed(identities)
    val buildReceipt = receipt(source)
    val foreignStoryBuild = buildReceipt.copy(
      receipt = buildReceipt.receipt.copy(storyId = StoryId.unsafe("foreign-story"))
    )
    assert(AcquisitionViewAuthority.validatedBuild(sourceOutcome, foreignStoryBuild).isLeft)
    val humanInputs = reviewEvidence("human", identities)
    val humanEvidence = ViewEvidenceAdmitter
      .trusted(Fingerprint.unsafe("human-admitter:v1"))
      .humanAdjudication(
        sourceOutcome,
        buildReceipt,
        AdjudicationReceiptId.unsafe("human-adjudication-receipt"),
        humanInputs._1,
        humanInputs._2,
        humanInputs._3
      )
      .toOption
      .get
    val human = AcquisitionViewAuthority
      .humanAdjudication(sourceOutcome, buildReceipt, humanEvidence)
      .toOption
      .get
    assertEquals(
      AcquisitionViewAuthority.fromWire(
        sourceOutcome,
        Some(buildReceipt),
        human.kind,
        human.sourceChecksum,
        human.buildReceiptChecksum,
        human.evidenceChecksum,
        human.adjudicationReceipt,
        human.fixtureReceipt,
        Vector(humanEvidence)
      ),
      Right(human)
    )
    assert(
      AcquisitionViewAuthority
        .fromWire(
          sourceOutcome,
          Some(buildReceipt),
          human.kind,
          human.sourceChecksum,
          human.buildReceiptChecksum,
          Some(Checksum.ofText("different-evidence")),
          human.adjudicationReceipt,
          human.fixtureReceipt,
          Vector(humanEvidence)
        )
        .isLeft,
      "an adjudication receipt cannot be reused with different evidence"
    )

    val fixtureInputs = reviewEvidence("fixture", identities)
    val fixtureEvidence = ViewEvidenceAdmitter
      .trusted(Fingerprint.unsafe("fixture-admitter:wire:v1"))
      .fixtureReview(
        sourceOutcome,
        None,
        FixtureAdmissionReceiptId.unsafe("fixture-review-receipt"),
        fixtureInputs._1,
        fixtureInputs._2,
        fixtureInputs._3
      )
      .toOption
      .get
    val fixture = AcquisitionViewAuthority
      .fixtureReview(sourceOutcome, None, fixtureEvidence)
      .toOption
      .get
    assertEquals(
      AcquisitionViewAuthority.fromWire(
        sourceOutcome,
        None,
        fixture.kind,
        fixture.sourceChecksum,
        fixture.buildReceiptChecksum,
        fixture.evidenceChecksum,
        fixture.adjudicationReceipt,
        fixture.fixtureReceipt,
        Vector(fixtureEvidence)
      ),
      Right(fixture)
    )
    assert(
      AcquisitionViewAuthority
        .fromWire(
          sourceOutcome,
          None,
          fixture.kind,
          fixture.sourceChecksum,
          fixture.buildReceiptChecksum,
          fixture.evidenceChecksum,
          fixture.adjudicationReceipt,
          Some(FixtureAdmissionReceiptId.unsafe("caller-minted-fixture-receipt")),
          Vector(fixtureEvidence)
        )
        .isLeft,
      "a caller-selected fixture receipt cannot mint authority"
    )

    assert(
      AcquisitionViewAuthority
        .fromWire(
          sourceOutcome,
          None,
          fixture.kind,
          fixture.sourceChecksum,
          fixture.buildReceiptChecksum,
          fixture.evidenceChecksum,
          fixture.adjudicationReceipt,
          fixture.fixtureReceipt,
          Vector.empty
        )
        .isLeft,
      "wire checksums and a receipt cannot recreate fixture admission without the typed witness"
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
