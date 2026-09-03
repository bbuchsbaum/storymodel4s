package storymodel4s.codec

import io.circe.Json
import munit.FunSuite
import storymodel4s.acquire.ClaimFamily
import storymodel4s.core.*
import storymodel4s.document.*
import storymodel4s.proposition.ConceptId
import storymodel4s.story.ModelStatus
import storymodel4s.view.DerivationRecord

/** The derivation record's refusals: what `DerivationArtifact.of` and `DerivationRecordCodec` will
  * not accept. The round-trip laws in [[CodecSuite]] only ever see lawful records, so every guard
  * here has its own negative case, and the mutation ledger in the commit message names the test
  * each guard fails.
  */
class DerivationRecordCodecSuite extends FunSuite:
  private val story = StoryId.unsafe("story:derivation-suite")
  private val sum = Checksum.ofText("x")
  private val stage = StageId.unsafe("codec-test")

  private def node(sentence: String, concept: String): ChartNodeRef =
    ChartNodeRef(SurfaceUnitId.unsafe(sentence), ConceptId.unsafe(concept))

  private val s1 = node("s1", "a")
  private val s2 = node("s2", "b")
  private val situation1 = NarrativeCandidateAddress.Situation(s1)
  private val situation2 = NarrativeCandidateAddress.Situation(s2)
  private val reason = DerivationGapReason.Unresolved(
    storymodel4s.acquire.ResolutionFailure.NoProposal
  )
  private val attempts = Vector(
    DerivationAttempt(
      situation1,
      ClaimFamily.SituationMention,
      DerivationDisposition.NotEmitted(reason)
    ),
    DerivationAttempt(
      situation2,
      ClaimFamily.SituationMention,
      DerivationDisposition.Emitted(ClaimId.unsafe("c:1"))
    )
  )
  private val gap =
    DerivationGap(stage, ClaimFamily.SituationMention, situation1, reason, Set.empty, Vector.empty)
  private val coverage = Vector(
    SentenceCoverage.Proposed(s1, FillerCounts.empty),
    SentenceCoverage.Abstained(s2, AbstentionReason.NoFocus)
  )

  private def of(
      attempts: Vector[DerivationAttempt] = attempts,
      gaps: Vector[DerivationGap] = Vector(gap),
      coverage: Vector[SentenceCoverage] = coverage
  ): Either[DomainError, DerivationArtifact] =
    DerivationArtifact
      .of(story, sum, sum, sum, sum, attempts, gaps, coverage, SummaryCoverage.NoTitle)

  private def refused(result: Either[DomainError, DerivationArtifact], fragment: String): Unit =
    result match
      case Left(DomainError.InvariantViolation("derivation-record", detail)) =>
        assert(detail.contains(fragment), detail)
      case other => fail(s"expected a refusal mentioning '$fragment', got $other")

  private val lawful: DerivationArtifact = of().fold(e => fail(e.message), identity)

  test("a lawful record is accepted and exposes the view's record shape") {
    assertEquals(lawful.gaps, Vector(gap))
    assertEquals(lawful.record, DerivationRecord.Reported(Vector(gap), coverage))
  }

  test("two attempts at one target are refused") {
    refused(of(attempts = attempts :+ attempts.head), "unique targets")
  }

  test("a gap at a target no attempt evaluated is refused") {
    val stray = gap.copy(target = NarrativeCandidateAddress.Situation(node("s9", "z")))
    refused(of(gaps = Vector(gap, stray)), "names no attempted target")
  }

  test("two gaps at one target are refused") {
    refused(
      of(gaps = Vector(gap, gap.copy(reason = DerivationGapReason.Alternatives))),
      "at most one gap"
    )
  }

  test("a gap whose reason disagrees with its attempt's disposition is refused") {
    refused(of(gaps = Vector(gap.copy(reason = DerivationGapReason.Alternatives))), "disposition")
  }

  test("a gap at an emitted attempt is refused") {
    refused(of(gaps = Vector(gap.copy(target = situation2))), "disposition")
  }

  test("a gap whose family disagrees with its attempt is refused") {
    refused(of(gaps = Vector(gap.copy(family = ClaimFamily.ContextAssignment))), "family")
  }

  test("a coverage ledger naming one sentence twice is refused") {
    refused(of(coverage = coverage :+ SentenceCoverage.NoChart(s1.sentence)), "at most once")
  }

  test("the encoding is canonical, versioned, and free of null") {
    val text = DerivationRecordCodec.encode(lawful)
    assert(text.startsWith("""{"attempts":"""), text.take(40))
    assert(text.contains(s""""schemaVersion":"${DerivationRecordCodec.SchemaVersion}""""))
    assert(!text.contains("null"))
    assertEquals(DerivationRecordCodec.decode(text), Right(lawful))
    assertEquals(
      DerivationRecordCodec.checksum(lawful),
      Checksum.ofBytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    )
  }

  test("an unsupported schema version is refused by name") {
    val text = DerivationRecordCodec
      .encode(lawful)
      .replace(
        DerivationRecordCodec.SchemaVersion,
        "derivation-record/v0"
      )
    DerivationRecordCodec.decode(text) match
      case Left(CodecError.Decode(_, detail)) =>
        assert(detail.contains("derivation-record/v0"), detail)
      case other => fail(s"accepted an unsupported schema: $other")
  }

  test("an unknown field is refused rather than dropped") {
    val json = io.circe.parser.parse(DerivationRecordCodec.encode(lawful)).toOption.get
    val widened = json.mapObject(_.add("annotations", Json.fromString("ride-along")))
    DerivationRecordCodec.decode(Canonical.print(widened)) match
      case Left(CodecError.Decode("$", detail)) => assert(detail.contains("unknown"), detail)
      case other                                => fail(s"accepted an unknown field: $other")
  }

  test("a decoded record still passes through the constructor's refusals") {
    val json = io.circe.parser.parse(DerivationRecordCodec.encode(lawful)).toOption.get
    val gaps = json.hcursor.downField("gaps").focus.get
    val doubled = json.mapObject(_.add("gaps", gaps.mapArray(arr => arr ++ arr)))
    DerivationRecordCodec.decode(Canonical.print(doubled)) match
      case Left(CodecError.Decode(_, detail)) => assert(detail.contains("at most one gap"), detail)
      case other                              => fail(s"accepted a doubled gap: $other")
  }

  test("malformed UTF-8 bytes are refused before parsing") {
    val bytes = DerivationRecordCodec.encode(lawful).getBytes("UTF-8") :+ 0xff.toByte
    DerivationRecordCodec.decode(bytes) match
      case Left(CodecError.Parse(detail)) => assert(detail.contains("UTF-8"), detail)
      case other                          => fail(s"accepted malformed bytes: $other")
  }

  test("the bound decode refuses a record for another story, source, or model, naming the field") {
    val model = CodecFixture.draft
    def bound(artifact: DerivationArtifact): Either[CodecError, DerivationArtifact] =
      DerivationRecordCodec.decode(model, DerivationRecordCodec.encode(artifact))
    val matching = DerivationArtifact
      .of(
        model.source.id,
        model.source.canonicalChecksum,
        StoryModelCodec.contentChecksum(model),
        sum,
        sum,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        SummaryCoverage.NoTitle
      )
      .fold(e => fail(e.message), identity)
    assert(matching.describes(model))
    assertEquals(bound(matching), Right(matching))

    def path(result: Either[CodecError, DerivationArtifact]): String = result match
      case Left(CodecError.Decode(p, _)) => p
      case other                         => fail(s"expected a binding refusal, got $other")
    val otherStory = DerivationArtifact
      .of(
        story,
        model.source.canonicalChecksum,
        StoryModelCodec.contentChecksum(model),
        sum,
        sum,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        SummaryCoverage.NoTitle
      )
      .toOption
      .get
    assertEquals(path(bound(otherStory)), "$.storyId")
    val otherSource = DerivationArtifact
      .of(
        model.source.id,
        sum,
        StoryModelCodec.contentChecksum(model),
        sum,
        sum,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        SummaryCoverage.NoTitle
      )
      .toOption
      .get
    assertEquals(path(bound(otherSource)), "$.canonicalSourceChecksum")
    val otherModel = DerivationArtifact
      .of(
        model.source.id,
        model.source.canonicalChecksum,
        sum,
        sum,
        sum,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        SummaryCoverage.NoTitle
      )
      .toOption
      .get
    assertEquals(path(bound(otherModel)), "$.modelChecksum")
    assert(!otherModel.describes(model))
    val _ = summon[ModelStatus.Draft =:= ModelStatus.Draft]
  }
